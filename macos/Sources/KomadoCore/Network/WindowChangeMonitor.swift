import AppKit
import ApplicationServices
import Foundation

/// Uses accessibility notifications as hints; the catalog remains the source of truth.
@MainActor
final class WindowChangeMonitor {
    private nonisolated static let registrationQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "me.mallows.komado.window-observers"
        queue.qualityOfService = .utility
        queue.maxConcurrentOperationCount = 4
        return queue
    }()
    private final class Registration: @unchecked Sendable {
        let observer: AXObserver
        init(_ observer: AXObserver) { self.observer = observer }
    }
    private final class Lifetime: @unchecked Sendable {
        private let lock = NSLock()
        private var running = true
        var isRunning: Bool { lock.withLock { running } }
        func stop() { lock.withLock { running = false } }
    }

    private let onChange: @Sendable () -> Void
    private let lifetime = Lifetime()
    private var applicationObservation: NSKeyValueObservation?
    private var registrations: [pid_t: Registration] = [:]
    private var pending: Set<pid_t> = []
    private var running: Set<pid_t> = []
    private var stopped = false

    init(onChange: @escaping @Sendable () -> Void) {
        self.onChange = onChange
    }

    func start() {
        guard !stopped, AXIsProcessTrusted() else { return }
        applicationObservation = NSWorkspace.shared.observe(\.runningApplications, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.reconcile() }
        }
        reconcile()
    }

    func stop() {
        stopped = true
        lifetime.stop()
        applicationObservation?.invalidate()
        applicationObservation = nil
        for registration in registrations.values {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(registration.observer), .commonModes)
        }
        registrations.removeAll()
        running.removeAll()
        pending.removeAll()
    }

    private func reconcile() {
        guard !stopped else { return }
        let current = Set(NSWorkspace.shared.runningApplications.compactMap { app -> pid_t? in
            guard app.processIdentifier > 0, app.activationPolicy != .prohibited else { return nil }
            return app.processIdentifier
        })
        if current != running, !running.isEmpty { onChange() }
        running = current
        for pid in Array(registrations.keys) where !current.contains(pid) {
            if let registration = registrations.removeValue(forKey: pid) {
                CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(registration.observer), .commonModes)
            }
        }
        for pid in current where registrations[pid] == nil && !pending.contains(pid) {
            pending.insert(pid)
            let context = UInt(bitPattern: Unmanaged.passUnretained(self).toOpaque())
            Self.registrationQueue.addOperation { [weak self, lifetime] in
                guard lifetime.isRunning else { return }
                let registration = Self.register(pid: pid, context: context)
                guard lifetime.isRunning else { return }
                Task { @MainActor [weak self] in self?.registered(pid: pid, registration: registration) }
            }
        }
    }

    private nonisolated static func register(pid: pid_t, context: UInt) -> Registration? {
        var observer: AXObserver?
        guard AXObserverCreate(pid, { _, _, _, pointer in
            guard let pointer else { return }
            let address = UInt(bitPattern: pointer)
            MainActor.assumeIsolated {
                guard let address = UnsafeMutableRawPointer(bitPattern: address) else { return }
                Unmanaged<WindowChangeMonitor>.fromOpaque(address).takeUnretainedValue().onChange()
            }
        }, &observer) == .success, let observer else { return nil }
        let app = AXUIElementCreateApplication(pid)
        let names = [kAXWindowCreatedNotification, kAXUIElementDestroyedNotification,
                     kAXTitleChangedNotification, kAXWindowResizedNotification,
                     kAXWindowMiniaturizedNotification, kAXWindowDeminiaturizedNotification,
                     kAXApplicationHiddenNotification, kAXApplicationShownNotification]
        var registered = false
        for name in names {
            if AXObserverAddNotification(observer, app, name as CFString, UnsafeMutableRawPointer(bitPattern: context)) == .success {
                registered = true
            }
        }
        return registered ? Registration(observer) : nil
    }

    private func registered(pid: pid_t, registration: Registration?) {
        pending.remove(pid)
        guard !stopped, running.contains(pid), let registration else { return }
        registrations[pid] = registration
        CFRunLoopAddSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(registration.observer), .commonModes)
    }
}
