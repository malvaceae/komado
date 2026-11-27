import ApplicationServices
import Carbon.HIToolbox
import CoreGraphics
import KomadoCore

/// Builds a key-down/key-up pair after the caller verifies session, capture,
/// focus and user authorization.
@MainActor
final class KeyboardKeyDispatcher {
    private let trusted: () -> Bool
    private let post: (CGEvent) -> Void
    private var sequence: UInt64 = 0
    private var closed = false

    init(trusted: @escaping () -> Bool = { AXIsProcessTrusted() },
         post: @escaping (CGEvent) -> Void = { $0.post(tap: .cghidEventTap) }) {
        self.trusted = trusted
        self.post = post
    }

    @discardableResult
    func send(_ input: KeyboardKey, captureRevision: UInt64, sessionAllowed: Bool) -> Bool {
        guard !closed, sessionAllowed, trusted(), input.sequence > sequence,
              input.captureRevision == captureRevision, (try? input.encode()) != nil,
              let source = CGEventSource(stateID: .hidSystemState),
              let down = CGEvent(keyboardEventSource: source, virtualKey: Self.keyCode(input.key), keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: Self.keyCode(input.key), keyDown: false) else { return false }
        var flags: CGEventFlags = []
        if input.modifiers & 1 != 0 { flags.insert(.maskCommand) }
        if input.modifiers & 2 != 0 { flags.insert(.maskAlternate) }
        if input.modifiers & 4 != 0 { flags.insert(.maskControl) }
        if input.modifiers & 8 != 0 { flags.insert(.maskShift) }
        down.flags = flags
        up.flags = flags
        guard trusted(), !closed else { return false }
        sequence = input.sequence
        post(down)
        post(up)
        return true
    }

    func close() { closed = true }

    private static func keyCode(_ key: KeyboardKey.Key) -> CGKeyCode {
        let code: Int = switch key {
        case .backspace: kVK_Delete
        case .delete: kVK_ForwardDelete
        case .enter: kVK_Return
        case .tab: kVK_Tab
        case .escape: kVK_Escape
        case .left: kVK_LeftArrow
        case .right: kVK_RightArrow
        case .up: kVK_UpArrow
        case .down: kVK_DownArrow
        case .home: kVK_Home
        case .end: kVK_End
        case .pageup: kVK_PageUp
        case .pagedown: kVK_PageDown
        case .a: kVK_ANSI_A
        case .b: kVK_ANSI_B
        case .c: kVK_ANSI_C
        case .d: kVK_ANSI_D
        case .e: kVK_ANSI_E
        case .f: kVK_ANSI_F
        case .g: kVK_ANSI_G
        case .h: kVK_ANSI_H
        case .i: kVK_ANSI_I
        case .j: kVK_ANSI_J
        case .k: kVK_ANSI_K
        case .l: kVK_ANSI_L
        case .m: kVK_ANSI_M
        case .n: kVK_ANSI_N
        case .o: kVK_ANSI_O
        case .p: kVK_ANSI_P
        case .q: kVK_ANSI_Q
        case .r: kVK_ANSI_R
        case .s: kVK_ANSI_S
        case .t: kVK_ANSI_T
        case .u: kVK_ANSI_U
        case .v: kVK_ANSI_V
        case .w: kVK_ANSI_W
        case .x: kVK_ANSI_X
        case .y: kVK_ANSI_Y
        case .z: kVK_ANSI_Z
        case .digit0: kVK_ANSI_0
        case .digit1: kVK_ANSI_1
        case .digit2: kVK_ANSI_2
        case .digit3: kVK_ANSI_3
        case .digit4: kVK_ANSI_4
        case .digit5: kVK_ANSI_5
        case .digit6: kVK_ANSI_6
        case .digit7: kVK_ANSI_7
        case .digit8: kVK_ANSI_8
        case .digit9: kVK_ANSI_9
        case .f1: kVK_F1
        case .f2: kVK_F2
        case .f3: kVK_F3
        case .f4: kVK_F4
        case .f5: kVK_F5
        case .f6: kVK_F6
        case .f7: kVK_F7
        case .f8: kVK_F8
        case .f9: kVK_F9
        case .f10: kVK_F10
        case .f11: kVK_F11
        case .f12: kVK_F12
        }
        return CGKeyCode(code)
    }
}
