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
            if model.sharingScreen { Text("\(model.sharingWindow ? "ウインドウ" : "画面")を共有中: \(model.sharingDisplayName ?? "画面")") }
            if model.controllingScreen { Text("Androidからの操作を許可中") }
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
    @State private var selectedEndpoint = ""
    @StateObject private var loginItem = LoginItemController()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("komado").font(.largeTitle.bold())
                    Text(model.running ? (model.connectedDevice != nil ? "接続中" : (model.ready ? "接続待機中" : "準備中")) : "停止中")
                        .foregroundStyle(.secondary)
                    if model.sharingScreen { Label("\(model.sharingWindow ? "ウインドウ" : "画面")を共有中: \(model.sharingDisplayName ?? "画面")", systemImage: "rectangle.on.rectangle") }
                    if model.controllingScreen { Text("Androidからの操作を許可中") }
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
                    Text("利用可能なネットワークから接続を受け付けます。")
                        .foregroundStyle(.secondary)
                    Toggle("待ち受け中の自動スリープを抑制", isOn: $model.keepAwakeWhileListening)
                    Toggle("ログイン時にkomadoを起動", isOn: Binding(
                        get: { loginItem.registered }, set: { loginItem.setRegistered($0) }))
                        .disabled(loginItem.state == .unavailable)
                    if loginItem.state == .requiresApproval {
                        Text("ログイン項目をシステム設定で許可してください。")
                            .foregroundStyle(.secondary)
                        Button("ログイン項目の設定を開く") { loginItem.openSettings() }
                    } else if loginItem.state == .unavailable {
                        Text("ログイン項目を利用できません。アプリの配置を確認してください。")
                            .foregroundStyle(.secondary)
                    }
                    if loginItem.error {
                        Text("ログイン項目を変更できませんでした。システム設定を確認してください。")
                            .foregroundStyle(.red)
                    }
                    Text("起動後の接続待ち受けは手動で開始します。")
                        .foregroundStyle(.secondary)
                    Picker("映像の最大解像度", selection: $model.captureResolution) {
                        ForEach(CaptureResolution.allCases) { resolution in
                            Text(resolution.label).tag(resolution)
                        }
                    }
                    Text("次に画面共有を開始するときに適用します。")
                        .foregroundStyle(.secondary)
                    Toggle("Androidからの操作を許可", isOn: $model.allowRemoteControl)
                    Toggle("クリップボード共有を許可", isOn: $model.allowClipboardSharing)
                        .disabled(!model.allowRemoteControl)
                    Toggle("クリップボードの自動同期も許可", isOn: $model.allowClipboardAutomatic)
                        .disabled(!model.allowClipboardSharing)
                    Text("自動同期は未分類の機密情報を転送する可能性があります。")
                        .foregroundStyle(.secondary)
                    Text("操作にはアクセシビリティの許可も必要です。画面ロック中の操作停止は未検証です。")
                        .foregroundStyle(.secondary)
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
                        Text("許可されています。Androidから画面またはウインドウを選んで共有します。")
                            .foregroundStyle(.secondary)
                    } else {
                        Text("画面転送には画面収録の許可が必要です。")
                            .foregroundStyle(.secondary)
                        Button("画面収録を許可") { model.requestScreenPermission() }
                    }
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("アクセシビリティ").font(.headline)
                    if model.accessibilityPermission {
                        Text("操作に必要な権限が許可されています。")
                            .foregroundStyle(.secondary)
                    } else {
                        Text("AndroidからMacを操作するには、システム設定でアクセシビリティの許可が必要です。")
                            .foregroundStyle(.secondary)
                        Button("アクセシビリティの許可を確認") { model.requestAccessibilityPermission() }
                    }
                }
                Divider()
                VStack(alignment: .leading, spacing: 8) {
                    Text("ペアリング").font(.headline)
                    Button("ペアリング情報を発行") { Task { await model.issueInvitation() } }
                        .disabled(!model.ready || model.busy || model.connectedDevice != nil)
                    if let invitation = model.invitation {
                        Text("AndroidでQRコードを読み取るか、上記の接続先・確認情報と次のコードを入力してください。有効期限は2分です。")
                            .foregroundStyle(.secondary)
                        Text(invitation).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                        if !model.pairingEndpoints.isEmpty {
                            Picker("QRコードの接続先", selection: Binding(
                                get: { model.pairingEndpoints.first(where: { $0.id == selectedEndpoint })?.id ?? model.pairingEndpoints.first?.id ?? "" },
                                set: { selectedEndpoint = $0 }
                            )) {
                                ForEach(model.pairingEndpoints) { endpoint in
                                    Text(endpoint.displayName).tag(endpoint.id)
                                }
                            }
                            if let image = pairingImage(invitation: invitation) {
                                Image(decorative: image, scale: 1)
                                    .resizable().interpolation(.none).aspectRatio(contentMode: .fit)
                                    .frame(width: 256, height: 256)
                                Text("QRコードには一時コードが含まれます。信頼できる端末で読み取ってください。")
                                    .foregroundStyle(.secondary)
                            }
                        }
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
        .onAppear { loginItem.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            loginItem.refresh()
            model.refreshPermissions()
        }
        .confirmationDialog("端末の登録を削除しますか？", isPresented: $confirmRemoval, presenting: deviceToRemove) { device in
            Button("削除", role: .destructive) { Task { await model.remove(device) } }
            Button("キャンセル", role: .cancel) { }
        } message: { device in
            Text("\(device.name)の登録を削除し、現在の接続を切断します。")
        }
    }

    private func pairingImage(invitation: String) -> CGImage? {
        guard let endpoint = model.pairingEndpoints.first(where: { $0.id == selectedEndpoint }) ?? model.pairingEndpoints.first,
              let fingerprint = model.fingerprint else { return nil }
        let payload = PairingInvitation(host: endpoint.address, port: endpoint.port,
            fingerprint: fingerprint.replacingOccurrences(of: ":", with: ""), token: invitation)
        guard let data = try? payload.encode() else { return nil }
        return PairingQRCode.image(for: data)
    }
}
