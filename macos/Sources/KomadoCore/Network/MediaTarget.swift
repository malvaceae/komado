import Foundation

public enum MediaTarget: Equatable, Sendable {
    case mainDisplay
    case mainDisplayControl
    case display(UInt32)
    case displayControl(UInt32)
    case window(info: WindowInfo, processID: Int32)
    case windowControl(info: WindowInfo, processID: Int32, size: WindowSize? = nil)

    public var isControl: Bool {
        switch self {
        case .mainDisplayControl, .displayControl, .windowControl: true
        default: false
        }
    }
}
