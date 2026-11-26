import AppKit
import KomadoCore
import SwiftUI

@MainActor
final class HostModel: ObservableObject {
    @Published private(set) var fingerprint: String?
    @Published private(set) var devices: [PairedDevice] = []
    @Published private(set) var identityError = false
    @Published private(set) var devicesError = false

    private let identityStore = HostTLSIdentityStore()
    private let registry = PairedDeviceRegistry()

    func refresh() {
        do {
            fingerprint = try identityStore.load().map { Self.format($0.fingerprintSHA256) }
            identityError = false
        } catch {
            fingerprint = nil
            identityError = true
        }
        do {
            devices = try registry.all()
            devicesError = false
        } catch {
            devices = []
            devicesError = true
        }
    }

    func createIdentity() {
        do {
            let identity = try identityStore.create()
            fingerprint = Self.format(identity.fingerprintSHA256)
            identityError = false
        } catch {
            identityError = true
        }
    }

    private static func format(_ data: Data) -> String {
        data.map { String(format: "%02X", $0) }.joined(separator: ":")
    }
}

@main
struct KomadoHostApp: App {
    @StateObject private var model = HostModel()
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        MenuBarExtra("komado", systemImage: "macwindow") {
            Text("接続機能は準備中です")
            Divider()
            Button("komadoを開く") {
                model.refresh()
                openWindow(id: "host")
                NSApp.activate(ignoringOtherApps: true)
            }
            Divider()
            Button("終了") { NSApp.terminate(nil) }
                .keyboardShortcut("q")
        }
        Window("komado", id: "host") {
            HostView(model: model)
                .frame(minWidth: 460, minHeight: 340)
                .onAppear { model.refresh() }
        }
    }
}

private struct HostView: View {
    @ObservedObject var model: HostModel

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 5) {
                Text("komado").font(.largeTitle.bold())
                Text("接続機能は準備中です").foregroundStyle(.secondary)
            }
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                Text("このMacの確認情報").font(.headline)
                if model.identityError {
                    Text("確認情報を読み込めませんでした。")
                        .foregroundStyle(.secondary)
                } else if let fingerprint = model.fingerprint {
                    Text("SHA-256 フィンガープリント")
                        .foregroundStyle(.secondary)
                    Text(fingerprint).font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                } else {
                    Text("接続時にこのMacを確認するための情報を作成します。")
                        .foregroundStyle(.secondary)
                    Button("確認情報を作成") { model.createIdentity() }
                }
            }
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                Text("登録済み端末").font(.headline)
                if model.devicesError {
                    Text("端末情報を読み込めませんでした。")
                        .foregroundStyle(.secondary)
                } else if model.devices.isEmpty {
                    Text("登録された端末はありません。")
                        .foregroundStyle(.secondary)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(model.devices, id: \.id) { device in
                                Label(device.name, systemImage: "iphone")
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(24)
    }
}
