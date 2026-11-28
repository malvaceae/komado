import Foundation
import Network

/// One authenticated TLS connection with an SDP exchange for each media session.
actor SignalingSession {
    enum SessionError: Error { case notAuthenticated, unexpectedState }
    private enum MediaState { case idle, preparingOffer, awaitingAnswer, complete, stopping, windowAction, launching }
    private let connection: NWConnection
    private let handshake: ServerHandshake
    private let queue: DispatchQueue
    private let onAuthenticated: @Sendable (PairedDevice) async -> Void
    private let onMediaRequested: @Sendable (PairedDevice, MediaTarget, Bool) async -> Void
    private let canResume: @Sendable (PairedDevice) async -> Bool
    private let onMediaStopRequested: @Sendable (PairedDevice) async -> Void
    private let onWindowAction: @Sendable (PairedDevice, WindowInfo, Int32, WindowAction) async -> Void
    private let onLaunchApplication: @Sendable (PairedDevice, UInt32, URL) async -> Void
    private let onAnswer: @Sendable (PairedDevice, String) async -> Void
    private let onClosed: @Sendable () async -> Void
    private var decoder = SignalFrame.Decoder()
    private var timeout: Task<Void, Never>?
    private var closed = false
    private var ready = false
    private var device: PairedDevice?
    private var mediaState: MediaState = .idle
    private var resumeChecked = false
    private var resuming = false
    private var displaySnapshot: [DisplayInfo]?
    private var applicationSnapshot: [ApplicationSelection]?
    private var nextApplicationOffset: UInt16? = 0
    private var sentApplicationCount = 0
    private var launchableSnapshot: [LaunchableApplication]?
    private var nextLaunchableOffset: UInt16? = 0
    private var sentLaunchableCount = 0
    private var launchingID: UInt32?
    private var windowSnapshot: [WindowSelection]?
    private var nextWindowOffset: UInt16? = 0
    private var sentWindowCount = 0
    private var windowActionID: UInt32?
    private var watchingWindows = false
    private var windowWatchID: UUID?
    private var windowChangePending = false
    private var windowWatchReady = false
    private var windowChangeDeferred = false
    private var windowMonitor: WindowChangeMonitor?
    private var windowReconciliation: Task<Void, Never>?
    private var windowEventCheck: Task<Void, Never>?
    private var reconcilingWindows = false

    init(connection: NWConnection, handshake: ServerHandshake, queue: DispatchQueue,
         onAuthenticated: @escaping @Sendable (PairedDevice) async -> Void,
         onMediaRequested: @escaping @Sendable (PairedDevice, MediaTarget, Bool) async -> Void,
         canResume: @escaping @Sendable (PairedDevice) async -> Bool,
         onMediaStopRequested: @escaping @Sendable (PairedDevice) async -> Void,
         onWindowAction: @escaping @Sendable (PairedDevice, WindowInfo, Int32, WindowAction) async -> Void,
         onLaunchApplication: @escaping @Sendable (PairedDevice, UInt32, URL) async -> Void,
         onAnswer: @escaping @Sendable (PairedDevice, String) async -> Void,
         onClosed: @escaping @Sendable () async -> Void) {
        self.connection = connection
        self.handshake = handshake
        self.queue = queue
        self.onAuthenticated = onAuthenticated
        self.onMediaRequested = onMediaRequested
        self.canResume = canResume
        self.onMediaStopRequested = onMediaStopRequested
        self.onWindowAction = onWindowAction
        self.onLaunchApplication = onLaunchApplication
        self.onAnswer = onAnswer
        self.onClosed = onClosed
    }

    func start() {
        guard !closed else { return }
        connection.stateUpdateHandler = { [weak self] state in
            Task { await self?.stateChanged(state) }
        }
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(30)) } catch { return }
            await self?.close()
        }
        connection.start(queue: queue)
    }

    func close() async {
        guard !closed else { return }
        closed = true
        timeout?.cancel()
        timeout = nil
        windowReconciliation?.cancel()
        windowReconciliation = nil
        windowEventCheck?.cancel()
        windowEventCheck = nil
        let monitor = windowMonitor
        windowMonitor = nil
        await monitor?.stop()
        connection.cancel()
        await handshake.disconnect()
        await onClosed()
    }

    func sendOffer(_ sdp: String) throws {
        guard !closed, device != nil else { throw SessionError.notAuthenticated }
        guard mediaState == .preparingOffer else { throw SessionError.unexpectedState }
        let encoded = try SignalFrame.encode(SignalMessage.offer(sdp).encode())
        mediaState = .awaitingAnswer
        timeout?.cancel()
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(30)) } catch { return }
            await self?.close()
        }
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            if error != nil { Task { await self?.close() } }
        })
    }

    func sendMediaStopped() throws {
        guard !closed, device != nil, mediaState == .stopping else { throw SessionError.unexpectedState }
        let encoded = try SignalFrame.encode(SignalMessage.mediaStopped.encode())
        mediaState = .idle
        displaySnapshot = nil
        applicationSnapshot = nil
        nextApplicationOffset = 0
        sentApplicationCount = 0
        launchableSnapshot = nil
        nextLaunchableOffset = 0
        sentLaunchableCount = 0
        windowSnapshot = nil
        nextWindowOffset = 0
        sentWindowCount = 0
        timeout?.cancel()
        timeout = nil
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            Task { await self?.sentCatalog(error: error) }
        })
    }

    func sendWindowActionResult(id: UInt32, success: Bool) throws {
        guard !closed, device != nil, mediaState == .windowAction, windowActionID == id else { throw SessionError.unexpectedState }
        let encoded = try SignalFrame.encode(SignalMessage.windowActionResult(id, success).encode())
        mediaState = .idle
        windowActionID = nil
        windowSnapshot = nil
        nextWindowOffset = 0
        sentWindowCount = 0
        timeout?.cancel()
        timeout = nil
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            Task { await self?.sentCatalog(error: error) }
        })
    }

    func sendLaunchApplicationResult(id: UInt32, success: Bool) throws {
        guard !closed, device != nil, mediaState == .launching, launchingID == id else { throw SessionError.unexpectedState }
        let encoded = try SignalFrame.encode(SignalMessage.launchApplicationResult(id, success).encode())
        mediaState = .idle
        launchingID = nil
        launchableSnapshot = nil
        nextLaunchableOffset = 0
        sentLaunchableCount = 0
        applicationSnapshot = nil
        nextApplicationOffset = 0
        sentApplicationCount = 0
        timeout?.cancel()
        timeout = nil
        connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
            Task { await self?.sentCatalog(error: error) }
        })
    }

    private func stateChanged(_ state: NWConnection.State) async {
        guard !closed else { return }
        switch state {
        case .ready where !ready:
            ready = true
            receive()
        case .failed, .cancelled:
            await close()
        default: break
        }
    }

    private func receive() {
        guard !closed else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: SignalFrame.maximumChunk) { [weak self] data, _, complete, error in
            Task { await self?.received(data: data, complete: complete, error: error) }
        }
    }

    private func received(data: Data?, complete: Bool, error: NWError?) async {
        guard !closed else { return }
        guard error == nil else { await close(); return }
        if let data, !data.isEmpty {
            do {
                let frames = try decoder.append(data)
                // The peer must wait for each response; pipelining isn't supported.
                guard frames.count <= 1 else { await close(); return }
                guard let frame = frames.first else {
                    if complete { await close() } else { receive() }
                    return
                }
                let message = try SignalMessage.decode(frame)
                if let device {
                    if watchingWindows {
                        switch message {
                        case .listWindows, .stopWatchingWindows: break
                        default: await close(); return
                        }
                    }
                    if resumeChecked && !resuming { await close(); return }
                    if message == .resumeCheck && mediaState == .idle && !resumeChecked {
                        resumeChecked = true
                        resuming = await canResume(device)
                        guard !closed, mediaState == .idle else { return }
                        let response = try SignalFrame.encode((resuming ? SignalMessage.resumeAllowed : .resumeDenied).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if message == .stopMedia && mediaState == .complete {
                        mediaState = .stopping
                        timeout = Task { [weak self] in
                            do { try await Task.sleep(for: .seconds(20)) } catch { return }
                            await self?.close()
                        }
                        await onMediaStopRequested(device)
                        return
                    }
                    if message == .listDisplays && mediaState == .idle && displaySnapshot == nil {
                        let displays = try await DisplayCatalog.active()
                        guard !closed, mediaState == .idle, displaySnapshot == nil else { return }
                        let response = try SignalFrame.encode(SignalMessage.displays(displays).encode())
                        displaySnapshot = displays
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .listApplications(offset) = message, mediaState == .idle {
                        if offset == 0 && nextApplicationOffset == nil {
                            applicationSnapshot = nil
                            nextApplicationOffset = 0
                            sentApplicationCount = 0
                        }
                        guard nextApplicationOffset == offset else { await close(); return }
                        let snapshot: [ApplicationSelection]
                        if let current = applicationSnapshot { snapshot = current }
                        else {
                            guard offset == 0 else { await close(); return }
                            snapshot = try await ApplicationCatalog.all()
                            guard !closed, mediaState == .idle else { return }
                            applicationSnapshot = snapshot
                        }
                        let start = Int(offset), end = min(Int(offset) + 32, snapshot.count)
                        guard start <= end else { await close(); return }
                        let next = end < snapshot.count ? UInt16(end) : 0
                        nextApplicationOffset = next == 0 ? nil : next
                        sentApplicationCount = end
                        let response = try SignalFrame.encode(SignalMessage.applications(next: next,
                            values: snapshot[start..<end].map(\.info)).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .applicationIcon(id) = message, mediaState == .idle {
                        guard let snapshot = applicationSnapshot,
                              let selected = snapshot.prefix(sentApplicationCount).first(where: { $0.info.id == id }) else { await close(); return }
                        let icon = await ApplicationCatalog.icon(selected)
                        guard !closed, mediaState == .idle else { return }
                        let response = try SignalFrame.encode(SignalMessage.applicationIconResult(id, icon).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .listLaunchableApplications(offset) = message, mediaState == .idle {
                        if offset == 0 && nextLaunchableOffset == nil {
                            launchableSnapshot = nil
                            nextLaunchableOffset = 0
                            sentLaunchableCount = 0
                        }
                        guard nextLaunchableOffset == offset else { await close(); return }
                        let snapshot: [LaunchableApplication]
                        if let current = launchableSnapshot { snapshot = current }
                        else {
                            guard offset == 0 else { await close(); return }
                            snapshot = try await LaunchableApplicationCatalog.all()
                            guard !closed, mediaState == .idle else { return }
                            launchableSnapshot = snapshot
                        }
                        let start = Int(offset), end = min(Int(offset) + 32, snapshot.count)
                        guard start <= end else { await close(); return }
                        let next = end < snapshot.count ? UInt16(end) : 0
                        nextLaunchableOffset = next == 0 ? nil : next
                        sentLaunchableCount = end
                        let response = try SignalFrame.encode(SignalMessage.launchableApplications(next: next,
                            values: snapshot[start..<end].map(\.info)).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .launchableApplicationIcon(id) = message, mediaState == .idle {
                        guard let snapshot = launchableSnapshot,
                              let selected = snapshot.prefix(sentLaunchableCount).first(where: { $0.info.id == id }) else { await close(); return }
                        let icon = await LaunchableApplicationCatalog.icon(selected)
                        guard !closed, mediaState == .idle else { return }
                        let response = try SignalFrame.encode(SignalMessage.launchableApplicationIconResult(id, icon).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .launchApplication(id) = message, mediaState == .idle {
                        guard let snapshot = launchableSnapshot,
                              let selected = snapshot.prefix(sentLaunchableCount).first(where: { $0.info.id == id }) else { await close(); return }
                        mediaState = .launching
                        launchingID = id
                        timeout = Task { [weak self] in
                            do { try await Task.sleep(for: .seconds(20)) } catch { return }
                            await self?.close()
                        }
                        await onLaunchApplication(device, id, selected.url)
                        if !closed { receive() }
                        return
                    }
                    if message == .watchWindows, mediaState == .idle, !watchingWindows,
                       windowSnapshot != nil, nextWindowOffset == nil {
                        watchingWindows = true
                        windowWatchID = UUID()
                        windowChangePending = false
                        windowWatchReady = false
                        windowChangeDeferred = false
                        let monitor = await MainActor.run {
                            let monitor = WindowChangeMonitor { [weak self] in
                                Task { await self?.scheduleWindowCheck() }
                            }
                            monitor.start()
                            return monitor
                        }
                        guard !closed, watchingWindows else { await monitor.stop(); return }
                        windowMonitor = monitor
                        let response = try SignalFrame.encode(SignalMessage.windowsWatching.encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        windowWatchReady = true
                        if windowChangeDeferred { await windowDidChange() }
                        windowReconciliation = Task { [weak self] in
                            while !Task.isCancelled {
                                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                                await self?.reconcileWindows()
                            }
                        }
                        return
                    }
                    if message == .stopWatchingWindows, mediaState == .idle, watchingWindows {
                        watchingWindows = false
                        windowWatchID = nil
                        windowChangePending = false
                        windowWatchReady = false
                        windowChangeDeferred = false
                        windowReconciliation?.cancel()
                        windowReconciliation = nil
                        windowEventCheck?.cancel()
                        windowEventCheck = nil
                        let monitor = windowMonitor
                        windowMonitor = nil
                        await monitor?.stop()
                        guard !closed else { return }
                        let response = try SignalFrame.encode(SignalMessage.windowsWatchStopped.encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .listWindows(offset) = message, mediaState == .idle {
                        if watchingWindows && offset == 0 { windowChangePending = false }
                        if offset == 0 && nextWindowOffset == nil {
                            windowSnapshot = nil
                            nextWindowOffset = 0
                            sentWindowCount = 0
                        }
                        guard nextWindowOffset == offset else { await close(); return }
                        let snapshot: [WindowSelection]
                        if let current = windowSnapshot { snapshot = current }
                        else {
                            guard offset == 0 else { await close(); return }
                            snapshot = try await WindowCatalog.all()
                            guard !closed, mediaState == .idle else { return }
                            windowSnapshot = snapshot
                        }
                        let start = Int(offset), end = min(Int(offset) + 32, snapshot.count)
                        guard start <= end else { await close(); return }
                        let next = end < snapshot.count ? UInt16(end) : 0
                        nextWindowOffset = next == 0 ? nil : next
                        sentWindowCount = end
                        let response = try SignalFrame.encode(SignalMessage.windows(next: next,
                            values: snapshot[start..<end].map(\.info)).encode())
                        connection.send(content: response, completion: .contentProcessed { [weak self] error in
                            Task { await self?.sentCatalog(error: error) }
                        })
                        return
                    }
                    if case let .windowAction(id, action) = message, mediaState == .idle {
                        guard !watchingWindows else { await close(); return }
                        guard let snapshot = windowSnapshot,
                              let selected = snapshot.prefix(sentWindowCount).first(where: { $0.info.id == id }),
                              try await WindowCatalog.matches(selected), !closed, mediaState == .idle else { await close(); return }
                        mediaState = .windowAction
                        windowActionID = id
                        timeout = Task { [weak self] in
                            do { try await Task.sleep(for: .seconds(20)) } catch { return }
                            await self?.close()
                        }
                        await onWindowAction(device, selected.info, selected.processID, action)
                        if !closed { receive() }
                        return
                    }
                    guard !watchingWindows else { await close(); return }
                    let target: MediaTarget?
                    let startsMedia: Bool
                    switch message {
                    case .startMedia: target = .mainDisplay; startsMedia = true
                    case .startControl: target = .mainDisplayControl; startsMedia = true
                    case let .startDisplayControl(id):
                        let current = try await DisplayCatalog.active()
                        guard !closed, mediaState == .idle, let snapshot = displaySnapshot,
                              DisplayCatalog.containsSelected(id, in: snapshot, current: current) else { await close(); return }
                        target = .displayControl(id); startsMedia = true
                    case let .startDisplay(id):
                        let current = try await DisplayCatalog.active()
                        guard !closed, mediaState == .idle, let snapshot = displaySnapshot,
                              DisplayCatalog.containsSelected(id, in: snapshot, current: current) else { await close(); return }
                        target = .display(id); startsMedia = true
                    case let .startApplication(id), let .startApplicationControl(id):
                        guard let snapshot = applicationSnapshot,
                              let selected = snapshot.prefix(sentApplicationCount).first(where: { $0.info.id == id }),
                              try await ApplicationCatalog.matches(selected), !closed else { await close(); return }
                        if case .startApplicationControl = message {
                            target = .applicationControl(info: selected.info, bundleIdentifier: selected.bundleIdentifier)
                        } else {
                            target = .application(info: selected.info, bundleIdentifier: selected.bundleIdentifier)
                        }
                        startsMedia = true
                    case let .startWindowControl(id, size):
                        guard let snapshot = windowSnapshot,
                              let selected = snapshot.prefix(sentWindowCount).first(where: { $0.info.id == id }),
                              try await WindowCatalog.matches(selected),
                              !closed else { await close(); return }
                        target = .windowControl(info: selected.info, processID: selected.processID, size: size); startsMedia = true
                    case let .startWindow(id):
                        guard let snapshot = windowSnapshot,
                              let selected = snapshot.prefix(sentWindowCount).first(where: { $0.info.id == id }),
                              try await WindowCatalog.matches(selected),
                              !closed else { await close(); return }
                        target = .window(info: selected.info, processID: selected.processID); startsMedia = true
                    default: target = nil; startsMedia = false
                    }
                    if startsMedia && mediaState == .idle, let target {
                        if resuming {
                            guard await canResume(device) else { await close(); return }
                            guard !closed, mediaState == .idle else { return }
                        }
                        mediaState = .preparingOffer
                        timeout = Task { [weak self] in
                            do { try await Task.sleep(for: .seconds(30)) } catch { return }
                            await self?.close()
                        }
                        await onMediaRequested(device, target, resuming)
                        if !closed { receive() }
                        return
                    }
                    guard case let .answer(sdp) = message, mediaState == .awaitingAnswer else { await close(); return }
                    mediaState = .complete
                    timeout?.cancel()
                    timeout = nil
                    await onAnswer(device, sdp)
                    if !closed { receive() }
                    return
                }
                let response = await handshake.receive(message)
                guard !closed else { return }
                guard let response else { await close(); return }
                let encoded = try SignalFrame.encode(response.encode())
                connection.send(content: encoded, completion: .contentProcessed { [weak self] error in
                    Task { await self?.sent(response: response, error: error) }
                })
            } catch { await close() }
        } else if complete {
            _ = try? decoder.finish()
            await close()
        } else { receive() }
    }

    private func sentCatalog(error: NWError?) async {
        guard !closed else { return }
        guard error == nil else { await close(); return }
        receive()
    }

    private func scheduleWindowCheck() {
        guard !closed, watchingWindows, !windowChangePending, windowEventCheck == nil else { return }
        windowEventCheck = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            await self?.checkWindowEvent()
        }
    }

    private func checkWindowEvent() async {
        windowEventCheck = nil
        await reconcileWindows()
    }

    private func windowDidChange() async {
        guard !closed, watchingWindows, !windowChangePending else { return }
        guard windowWatchReady else { windowChangeDeferred = true; return }
        windowChangeDeferred = false
        windowChangePending = true
        do {
            let message = try SignalFrame.encode(SignalMessage.windowsChanged.encode())
            connection.send(content: message, completion: .contentProcessed { [weak self] error in
                if error != nil { Task { await self?.close() } }
            })
        } catch { await close() }
    }

    private func reconcileWindows() async {
        guard !closed, watchingWindows, !windowChangePending, !reconcilingWindows,
              let snapshot = windowSnapshot, let watchID = windowWatchID else { return }
        reconcilingWindows = true
        defer { reconcilingWindows = false }
        do {
            let current = try await WindowCatalog.all()
            guard !closed, watchingWindows, windowWatchID == watchID else { return }
            if current != snapshot { await windowDidChange() }
        } catch { await close() }
    }

    private func sent(response: SignalMessage, error: NWError?) async {
        guard !closed else { return }
        guard error == nil, response != .rejected else { await close(); return }
        if case .accepted = response {
            timeout?.cancel()
            timeout = nil
            guard let authenticated = await handshake.authenticatedDevice(), !closed else { await close(); return }
            device = authenticated
            await onAuthenticated(authenticated)
        }
        if !closed { receive() }
    }
}
