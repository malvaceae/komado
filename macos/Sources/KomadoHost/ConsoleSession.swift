import CoreGraphics
import Foundation

/// Console membership does not by itself prove that the screen is unlocked.
enum ConsoleSession {
    static func isOnConsole() -> Bool {
        isOnConsole(CGSessionCopyCurrentDictionary() as? [String: Any])
    }

    static func isOnConsole(_ properties: [String: Any]?) -> Bool {
        properties?[kCGSessionOnConsoleKey as String] as? Bool == true
    }
}
