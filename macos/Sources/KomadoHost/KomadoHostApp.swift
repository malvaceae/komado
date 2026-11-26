import AppKit
import KomadoCore
import SwiftUI

@main
struct KomadoHostApp: App {
    @StateObject private var model = HostModel()
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        MenuBarExtra(model.sharingScreen ? "画面を共有中" : "komado",
                     systemImage: model.sharingScreen ? "rectangle.on.rectangle" : "macwindow") {
            Text(model.running ? (model.connectedDevice != nil ? "接続中" : (model.ready ? "接続待機中" : "準備中")) : "停止中")
            if model.sharingScreen { Text("画面を共有中") }
            if let device = model.connectedDevice { Text("接続中: \(device)") }
            Divider()
            Button("komadoを開く") {
                model.refresh()
                openWindow(id: "host")
                NSApp.activate(ignoringOtherApps: true)
            }
            if model.running {
                if model.sharingScreen {
                    Button("画面共有を停止") { Task { await model.stopSharing() } }
                }
                Button("接続を停止") { Task { await model.stop() } }
            }
            Divider()
            Button("終了") { Task { await model.stop(); NSApp.terminate(nil) } }
                .keyboardShortcut("q")
        }
        Window("komado", id: "host") {
            HostView(model: model)
                .frame(minWidth: 520, minHeight: 480)
                .onAppear { model.refresh() }
        }
    }
}

private struct HostView: View {
    @ObservedObject var model: HostModel
    @State private var deviceToRemove: PairedDevice?
    @State private var confirmRemoval = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("komado").font(.largeTitle.bold())
                    Text(model.running ? (model.connectedDevice != nil ? "接続中" : (model.ready ? "接続待機中" : "準備中")) : "停止中")
                        .foregroundStyle(.secondary)
                    if model.sharingScreen { Label("画面を共有中", systemImage: "rectangle.on.rectangle") }
                }
                if let error = model.error {
                    Text(error).foregroundStyle(.red)
                }
                Divider()
                VStack(alignment: .leading, spacing: 8) {
                    Text("このMacの確認情報").font(.headline)
                    if model.identityError {
                        Text("確認情報を読み込めませんでした。")
                            .foregroundStyle(.secondary)
                    } else if let fingerprint = model.fingerprint {
                        Text("SHA-256 フィンガープリント").foregroundStyle(.secondary)
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
                    Text("接続の待ち受け").font(.headline)
                    TextField("VPNアドレス（使う場合のみ）", text: $model.vpnAddress)
                        .disabled(model.running || model.busy)
                    Text("同一LANからの接続を受け付けます。VPNを使う場合は、このMacのVPNアドレスも入力してください。")
                        .foregroundStyle(.secondary)
                    Toggle("待ち受け中の自動スリープを抑制", isOn: $model.keepAwakeWhileListening)
                    if model.running {
                        if model.sharingScreen {
                            Button("画面共有を停止") { Task { await model.stopSharing() } }
                        }
                        Button("接続を停止") { Task { await model.stop() } }
                    } else {
                        Button("接続を開始") { Task { await model.start() } }
                            .disabled(model.busy || !model.networkReady || model.fingerprint == nil)
                    }
                    if let connected = model.connectedDevice { Text("接続中: \(connected)") }
                    ForEach(model.addresses, id: \.self) { address in
                        Text(address).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                    }
                }
                Divider()
                VStack(alignment: .leading, spacing: 8) {
                    Text("画面収録").font(.headline)
                    if model.screenPermission {
                        Text("許可されています。画面転送時にメインディスプレイを共有します。")
                            .foregroundStyle(.secondary)
                    } else {
                        Text("画面転送には画面収録の許可が必要です。")
                            .foregroundStyle(.secondary)
                        Button("画面収録を許可") { model.requestScreenPermission() }
                    }
                }
                Divider()
                VStack(alignment: .leading, spacing: 8) {
                    Text("ペアリング").font(.headline)
                    Button("ペアリング情報を発行") { Task { await model.issueInvitation() } }
                        .disabled(!model.ready || model.busy || model.connectedDevice != nil)
                    if let invitation = model.invitation {
                        Text("Androidに上記のアドレスとポート、確認情報、次のコードを入力してください。有効期限は2分です。")
                            .foregroundStyle(.secondary)
                        Text(invitation).font(.system(.body, design: .monospaced)).textSelection(.enabled)
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
                        ForEach(model.devices, id: \.id) { device in
                            HStack {
                                Label(device.name, systemImage: "iphone")
                                Spacer()
                                Button("削除") { deviceToRemove = device; confirmRemoval = true }
                                    .disabled(model.busy)
                            }
                        }
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .confirmationDialog("端末の登録を削除しますか？", isPresented: $confirmRemoval, presenting: deviceToRemove) { device in
            Button("削除", role: .destructive) { Task { await model.remove(device) } }
            Button("キャンセル", role: .cancel) { }
        } message: { device in
            Text("\(device.name)の登録を削除し、現在の接続を切断します。")
        }
    }
}
