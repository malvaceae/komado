import Foundation
import Combine
import ServiceManagement

/// The operating system remains the source of truth for registration and consent.
@MainActor
final class LoginItemController: ObservableObject {
    enum State: Equatable { case disabled, enabled, requiresApproval, unavailable }
    struct Service {
        let status: () -> State
        let register: () throws -> Void
        let unregister: () throws -> Void
        let openSettings: () -> Void

        @MainActor static let system = Service(status: {
            switch SMAppService.mainApp.status {
            case .notRegistered: .disabled
            case .enabled: .enabled
            case .requiresApproval: .requiresApproval
            case .notFound: .unavailable
            @unknown default: .unavailable
            }
        }, register: { try SMAppService.mainApp.register() },
           unregister: { try SMAppService.mainApp.unregister() },
           openSettings: { SMAppService.openSystemSettingsLoginItems() })
    }

    @Published private(set) var state: State
    @Published private(set) var error = false
    private let service: Service

    init(service: Service = .system) {
        self.service = service
        state = service.status()
    }

    var registered: Bool { state == .enabled || state == .requiresApproval }

    func refresh() { state = service.status(); error = false }

    func setRegistered(_ value: Bool) {
        guard value != registered, state != .unavailable else { return }
        error = false
        do {
            if value { try service.register() } else { try service.unregister() }
        } catch { self.error = true }
        state = service.status()
    }

    func openSettings() { service.openSettings() }
}
