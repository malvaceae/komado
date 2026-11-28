import AppKit
import KomadoCore
import SwiftUI

@main
struct KomadoHostApp: App {
    @StateObject private var model = HostModel()
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        MenuBarExtra(model.sharingScreen ? "\(model.sharingApplication ? "アプリ" : model.sharingWindow ? "ウインドウ" : "画面")を共有中" : "komado",
                     systemImage: model.sharingScreen ? "rectangle.on.rectangle" : "macwindow") {
            Text(model.running ? (model.connectedDevice != nil ? "接続中" : (model.ready ? "接続待機中" : "準備中")) : (model.restoreListening ? "待ち受け再開待ち" : "停止中"))
            if model.sharingScreen { Text("\(model.sharingApplication ? "アプリ" : model.sharingWindow ? "ウインドウ" : "画面")を共有中: \(model.sharingDisplayName ?? "画面")") }
            if model.controllingScreen { Text("端末からの操作を許可中") }
            if let device = model.connectedDevice { Text("接続中: \(device)") }
            Divider()
            Button("komadoを開く") {
                model.refresh()
                openWindow(id: "host")
                NSApp.activate(ignoringOtherApps: true)
            }
            if model.running || model.restoreListening {
                if model.connectedDevice != nil {
                    Button("端末を切断") { Task { await model.disconnectDevice() } }
                }
                Button("接続の受付をオフにする") { Task { await model.stop() } }
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
    private enum Page: String, CaseIterable {
        case overview = "概要", devices = "端末", settings = "設定"
    }

    @ObservedObject var model: HostModel
    @State private var page = Page.overview
    @State private var deviceToRemove: PairedDevice?
    @State private var confirmRemoval = false
    @State private var showingPairing = false
    @State private var selectedEndpoint = ""
    @StateObject private var loginItem = LoginItemController()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("画面", selection: $page) {
                ForEach(Page.allCases, id: \.self) { page in Text(page.rawValue).tag(page) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 360)
            if let error = model.error {
                Text(error).foregroundStyle(.red)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    switch page {
                    case .overview: overview
                    case .devices: devices
                    case .settings: settings
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(24)
        .frame(maxWidth: 700, alignment: .leading)
        .onAppear { loginItem.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            loginItem.refresh()
            model.refreshPermissions()
        }
        .onChange(of: model.connectedDevice) { device in
            if device != nil { showingPairing = false }
        }
        .sheet(isPresented: $showingPairing) { pairingSheet }
        .confirmationDialog("端末の登録を解除しますか？", isPresented: $confirmRemoval, presenting: deviceToRemove) { device in
            Button("登録を解除", role: .destructive) { Task { await model.remove(device) } }
            Button("キャンセル", role: .cancel) { }
        } message: { device in
            Text("\(device.name)の登録を解除し、接続中の場合は切断します。")
        }
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("このMacへの接続").font(.title2.bold())
                    Text("接続した端末からMacの画面やアプリを使えます。")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                addDeviceButton
            }
            GroupBox {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "desktopcomputer")
                        .font(.title2)
                        .foregroundStyle(model.running ? Color.green : Color.secondary)
                        .frame(width: 40, height: 40)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(connectionStatus).font(.headline)
                        Text(connectionDetail).foregroundStyle(.secondary)
                        if model.sharingScreen {
                            Label("\(model.sharingApplication ? "アプリ" : model.sharingWindow ? "ウインドウ" : "画面")を共有中: \(model.sharingDisplayName ?? "画面")", systemImage: "rectangle.on.rectangle")
                        }
                    }
                    Spacer()
                    Toggle("接続を受け付ける", isOn: Binding(
                        get: { model.running || model.restoreListening },
                        set: { enabled in
                            Task {
                                if enabled {
                                    if model.fingerprint == nil && !model.identityError { model.createIdentity() }
                                    if model.fingerprint != nil { await model.start() }
                                } else { await model.stop() }
                            }
                        }))
                    .toggleStyle(.switch)
                    .disabled(model.busy || (model.identityError && !model.running))
                }
                .padding(8)
            }
            if model.identityError {
                Text("このMacの確認情報を読み込めませんでした。")
                    .foregroundStyle(.red)
            }
            if model.restoreListening && !model.running {
                Button("接続の受付を再試行") { Task { await model.start() } }
                    .disabled(model.busy || !model.networkReady || model.fingerprint == nil)
            }
            if model.connectedDevice != nil {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("接続中の操作と共有").font(.headline)
                        Spacer()
                        Button("接続を切断") { Task { await model.disconnectDevice() } }
                            .disabled(model.busy)
                    }
                    GroupBox {
                        VStack(alignment: .leading, spacing: 12) {
                            switchRow("端末からMacの操作を許可", isOn: $model.allowRemoteControl)
                            switchRow("音声を共有", isOn: $model.allowAudioSharing)
                            switchRow("クリップボードを共有", isOn: $model.allowClipboardSharing)
                                .disabled(!model.allowRemoteControl)
                            switchRow("クリップボードを自動同期", isOn: $model.allowClipboardAutomatic)
                                .disabled(!model.allowClipboardSharing)
                        }
                        .toggleStyle(.switch)
                        .padding(8)
                    }
                    Text("操作にはアクセシビリティの許可が必要です。自動同期では機密情報が転送される場合があります。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("権限").font(.headline)
                GroupBox {
                    VStack(alignment: .leading, spacing: 12) {
                        permissionRow("画面収録", symbol: "rectangle.on.rectangle", description: "端末に画面やウインドウを表示", allowed: model.screenPermission) {
                            model.requestScreenPermission()
                        }
                        Divider()
                        permissionRow("アクセシビリティ", symbol: "accessibility", description: "端末からMacを操作", allowed: model.accessibilityPermission) {
                            model.requestAccessibilityPermission()
                        }
                    }
                    .padding(8)
                }
                if !model.screenPermission || !model.accessibilityPermission {
                    Button("システム設定を開く") {
                        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.systempreferences") {
                            NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration(), completionHandler: nil)
                        }
                    }
                }
            }
        }
    }

    private var connectionStatus: String {
        if model.connectedDevice != nil { return "端末と接続中" }
        if model.running { return model.ready ? "接続待機中" : "準備中" }
        return model.restoreListening ? "接続の再開待ち" : "停止中"
    }

    private var connectionDetail: String {
        if let device = model.connectedDevice { return "\(device)が接続中です。受付をオフにすると接続も切断されます。" }
        if model.running && model.ready { return "登録済みの端末からの接続を待っています。" }
        if model.running || model.restoreListening { return "接続の準備をしています。" }
        return "接続を受け付けていません。"
    }

    private func permissionRow(_ title: String, symbol: String, description: String, allowed: Bool, action: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(.secondary)
                .frame(width: 30, height: 30)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                Text(description).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if allowed { Text("許可済み").foregroundStyle(.secondary) }
            else { Button("許可を確認", action: action) }
        }
    }

    private var devices: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("登録済み端末").font(.title2.bold())
                Spacer()
                addDeviceButton
            }
            if model.devicesError {
                Text("端末情報を読み込めませんでした。").foregroundStyle(.red)
            } else if model.devices.isEmpty {
                Text("登録された端末はありません。").foregroundStyle(.secondary)
            } else {
                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(model.devices, id: \.id) { device in
                            HStack {
                                Label(device.name, systemImage: "iphone")
                                Spacer()
                                Button("登録を解除…") { deviceToRemove = device; confirmRemoval = true }
                                    .disabled(model.busy)
                            }
                        }
                    }
                    .padding(8)
                }
            }
        }
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("設定").font(.title2.bold())
            GroupBox {
                VStack(alignment: .leading, spacing: 12) {
                    switchRow("ログイン時にkomadoを起動", isOn: Binding(
                        get: { loginItem.registered }, set: { loginItem.setRegistered($0) }))
                        .disabled(loginItem.state == .unavailable)
                    if loginItem.state == .requiresApproval {
                        Text("ログイン項目をシステム設定で許可してください。").foregroundStyle(.secondary)
                        Button("ログイン項目の設定を開く") { loginItem.openSettings() }
                    } else if loginItem.state == .unavailable {
                        Text("ログイン項目を利用できません。アプリの配置を確認してください。").foregroundStyle(.secondary)
                    }
                    if loginItem.error { Text("ログイン項目を変更できませんでした。").foregroundStyle(.red) }
                    switchRow("接続を受け付けている間は自動スリープしない", isOn: $model.keepAwakeWhileListening)
                    Picker("映像の最大解像度", selection: $model.captureResolution) {
                        ForEach(CaptureResolution.allCases) { resolution in Text(resolution.label).tag(resolution) }
                    }
                    .frame(maxWidth: 400)
                    Text("解像度は次に画面共有を開始するときに適用します。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .toggleStyle(.switch)
                .padding(8)
            }
        }
    }

    private func switchRow(_ title: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(title)
            Spacer()
            Toggle(title, isOn: isOn).labelsHidden().toggleStyle(.switch)
        }
    }

    private var addDeviceButton: some View {
        Button("端末を追加") {
            Task {
                await model.issueInvitation()
                if model.invitation != nil { showingPairing = true }
            }
        }
        .buttonStyle(.borderedProminent)
        .disabled(!model.ready || model.busy || model.connectedDevice != nil)
        .help(model.ready ? "接続する端末でQRコードを読み取ります" : "接続の受付をオンにしてください")
    }

    private var pairingSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("端末を追加").font(.title2.bold())
            if let invitation = model.invitation {
                Text("接続する端末のkomadoでQRコードを読み取ってください。")
                Picker("接続先", selection: Binding(
                    get: { model.pairingEndpoints.first(where: { $0.id == selectedEndpoint })?.id ?? model.pairingEndpoints.first?.id ?? "" },
                    set: { selectedEndpoint = $0 }
                )) {
                    ForEach(model.pairingEndpoints) { endpoint in Text(endpoint.displayName).tag(endpoint.id) }
                }
                if let image = pairingImage(invitation: invitation) {
                    Image(decorative: image, scale: 1)
                        .resizable().interpolation(.none).aspectRatio(contentMode: .fit)
                        .frame(width: 256, height: 256)
                }
                Text("QRコードの有効期限は2分です。信頼できる端末で読み取ってください。")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("QRコードの有効期限が切れました。")
                Button("QRコードを再発行") { Task { await model.issueInvitation() } }
                    .disabled(!model.ready || model.busy || model.connectedDevice != nil)
            }
            HStack { Spacer(); Button("閉じる") { showingPairing = false } }
        }
        .padding(24)
        .frame(minWidth: 340)
    }

    private func pairingImage(invitation: String) -> CGImage? {
        guard let endpoint = model.pairingEndpoints.first(where: { $0.id == selectedEndpoint }) ?? model.pairingEndpoints.first,
              let fingerprint = model.fingerprint else { return nil }
        let systemName = Host.current().localizedName ?? ""
        let usableName = !systemName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && systemName.utf8.count <= 256 &&
            systemName.unicodeScalars.allSatisfy { !($0.value <= 0x1F || (0x7F...0x9F).contains($0.value)) }
        let payload = PairingInvitation(name: usableName ? systemName : "Mac", host: endpoint.address, port: endpoint.port,
            fingerprint: fingerprint.replacingOccurrences(of: ":", with: ""), token: invitation)
        guard let data = try? payload.encode() else { return nil }
        return PairingQRCode.image(for: data)
    }
}
