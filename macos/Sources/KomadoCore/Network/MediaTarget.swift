import Foundation

public enum MediaTarget: Equatable, Sendable {
    case mainDisplay
    case mainDisplayControl
    case display(UInt32)
    case window(info: WindowInfo, processID: Int32)
}
