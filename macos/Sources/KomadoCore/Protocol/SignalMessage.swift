import Foundation

public struct DisplayInfo: Equatable, Sendable {
    public let id: UInt32
    public let width: UInt32
    public let height: UInt32
    public let name: String
    public init(id: UInt32, width: UInt32, height: UInt32, name: String) {
        self.id = id
        self.width = width
        self.height = height
        self.name = name
    }
}

public struct ApplicationInfo: Equatable, Sendable {
    public let id: UInt32
    public let name: String
    public init(id: UInt32, name: String) { self.id = id; self.name = name }
}

public struct WindowInfo: Equatable, Sendable {
    public let id: UInt32
    public let width: UInt32
    public let height: UInt32
    public let application: String
    public let title: String
    public init(id: UInt32, width: UInt32, height: UInt32, application: String, title: String) {
        self.id = id
        self.width = width
        self.height = height
        self.application = application
        self.title = title
    }
}

public struct WindowSize: Equatable, Sendable {
    public let width: UInt16
    public let height: UInt16
    public init(width: UInt16, height: UInt16) { self.width = width; self.height = height }
}

/// Version 1 signaling messages. A successfully decoded message is still
/// untrusted until it has been accepted by the authenticated session state.
public enum SignalMessage: Equatable, Sendable {
    case pair(name: String, invitation: Data, publicKeyDER: Data)
    case authenticate(deviceID: UUID)
    case challenge(Data)
    case proof(Data)
    case accepted(deviceID: UUID)
    case rejected
    case startMedia
    case startControl
    case startDisplay(UInt32)
    case startDisplayControl(UInt32)
    case listDisplays
    case displays([DisplayInfo])
    case listWindows(UInt16)
    case windows(next: UInt16, values: [WindowInfo])
    case listApplications(UInt16)
    case applications(next: UInt16, values: [ApplicationInfo])
    case applicationIcon(UInt32)
    case applicationIconResult(UInt32, Data)
    case startApplication(UInt32)
    case startApplicationControl(UInt32)
    case startWindow(UInt32)
    case startWindowControl(UInt32, size: WindowSize? = nil)
    case stopMedia
    case mediaStopped
    case resumeCheck
    case resumeAllowed
    case resumeDenied
    case offer(String)
    case answer(String)

    public enum MessageError: Error, Equatable { case invalid }

    public static func decode(_ payload: Data) throws -> SignalMessage {
        guard (1...SignalFrame.maximumPayload).contains(payload.count),
              !hasTrailingComma(payload),
              let fields = (try? JSONSerialization.jsonObject(with: payload)) as? [String],
              fields.count >= 2, fields[0] == "1" else { throw MessageError.invalid }
        switch fields[1] {
        case "pair":
            guard fields.count == 5, validName(fields[2]),
                  let token = decodeBase64(fields[3], range: 32...32),
                  let publicKey = decodeBase64(fields[4], range: 1...128) else { throw MessageError.invalid }
            return .pair(name: fields[2], invitation: token, publicKeyDER: publicKey)
        case "auth":
            guard fields.count == 3, let id = parseUUID(fields[2]) else { throw MessageError.invalid }
            return .authenticate(deviceID: id)
        case "challenge":
            guard fields.count == 3, let data = decodeBase64(fields[2], range: 32...32) else { throw MessageError.invalid }
            return .challenge(data)
        case "proof":
            guard fields.count == 3, let data = decodeBase64(fields[2], range: 1...72) else { throw MessageError.invalid }
            return .proof(data)
        case "accepted":
            guard fields.count == 3, let id = parseUUID(fields[2]) else { throw MessageError.invalid }
            return .accepted(deviceID: id)
        case "rejected":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .rejected
        case "start":
            if fields.count == 2 { return .startMedia }
            guard fields.count == 3, let id = Self.positiveInteger(fields[2], max: UInt64(UInt32.max)) else { throw MessageError.invalid }
            return .startDisplay(UInt32(id))
        case "display.control":
            guard fields.count == 3, let id = positiveInteger(fields[2], max: UInt64(UInt32.max)) else { throw MessageError.invalid }
            return .startDisplayControl(UInt32(id))
        case "control.start":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .startControl
        case "display.list":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .listDisplays
        case "display.listed":
            guard fields.count >= 6, fields.count <= 258, (fields.count - 2).isMultiple(of: 4) else { throw MessageError.invalid }
            var displays: [DisplayInfo] = []
            var ids = Set<UInt32>()
            for i in stride(from: 2, to: fields.count, by: 4) {
                guard let id = positiveInteger(fields[i], max: UInt64(UInt32.max)),
                      let width = positiveInteger(fields[i + 1], max: 65_535),
                      let height = positiveInteger(fields[i + 2], max: 65_535),
                      validCatalogLabel(fields[i + 3]), ids.insert(UInt32(id)).inserted else { throw MessageError.invalid }
                displays.append(DisplayInfo(id: UInt32(id), width: UInt32(width), height: UInt32(height), name: fields[i + 3]))
            }
            return .displays(displays)
        case "window.list":
            guard fields.count == 3, let offset = nonnegativeInteger(fields[2], max: 512) else { throw MessageError.invalid }
            return .listWindows(UInt16(offset))
        case "window.listed":
            guard fields.count >= 3, fields.count <= 163, (fields.count - 3).isMultiple(of: 5),
                  let next = nonnegativeInteger(fields[2], max: 512) else { throw MessageError.invalid }
            var windows: [WindowInfo] = []
            var ids = Set<UInt32>()
            for i in stride(from: 3, to: fields.count, by: 5) {
                guard let id = positiveInteger(fields[i], max: UInt64(UInt32.max)),
                      let width = positiveInteger(fields[i + 1], max: 65_535),
                      let height = positiveInteger(fields[i + 2], max: 65_535),
                      validCatalogLabel(fields[i + 3]), validCatalogLabel(fields[i + 4]),
                      ids.insert(UInt32(id)).inserted else { throw MessageError.invalid }
                windows.append(WindowInfo(id: UInt32(id), width: UInt32(width), height: UInt32(height),
                                          application: fields[i + 3], title: fields[i + 4]))
            }
            guard !windows.isEmpty || next == 0 else { throw MessageError.invalid }
            return .windows(next: UInt16(next), values: windows)
        case "application.list":
            guard fields.count == 3, let offset = nonnegativeInteger(fields[2], max: 65_535) else { throw MessageError.invalid }
            return .listApplications(UInt16(offset))
        case "application.listed":
            guard fields.count >= 3, fields.count <= 67, (fields.count - 3).isMultiple(of: 2),
                  let next = nonnegativeInteger(fields[2], max: 65_535) else { throw MessageError.invalid }
            var applications: [ApplicationInfo] = []
            var ids = Set<UInt32>()
            for i in stride(from: 3, to: fields.count, by: 2) {
                guard let id = positiveInteger(fields[i], max: UInt64(Int32.max)), validCatalogLabel(fields[i + 1]),
                      ids.insert(UInt32(id)).inserted else { throw MessageError.invalid }
                applications.append(ApplicationInfo(id: UInt32(id), name: fields[i + 1]))
            }
            guard !applications.isEmpty || next == 0 else { throw MessageError.invalid }
            return .applications(next: UInt16(next), values: applications)
        case "application.icon":
            guard fields.count == 3, let id = positiveInteger(fields[2], max: UInt64(Int32.max)) else { throw MessageError.invalid }
            return .applicationIcon(UInt32(id))
        case "application.iconed":
            guard fields.count == 4, let id = positiveInteger(fields[2], max: UInt64(Int32.max)),
                  let data = decodeBase64(fields[3], range: 0...8192) else { throw MessageError.invalid }
            return .applicationIconResult(UInt32(id), data)
        case "application.start", "application.control":
            guard fields.count == 3, let id = positiveInteger(fields[2], max: UInt64(Int32.max)) else { throw MessageError.invalid }
            return fields[1] == "application.start" ? .startApplication(UInt32(id)) : .startApplicationControl(UInt32(id))
        case "window.control":
            guard (fields.count == 3 || fields.count == 5), let id = positiveInteger(fields[2], max: UInt64(UInt32.max)) else { throw MessageError.invalid }
            if fields.count == 3 { return .startWindowControl(UInt32(id)) }
            guard let width = positiveInteger(fields[3], max: 65_535),
                  let height = positiveInteger(fields[4], max: 65_535) else { throw MessageError.invalid }
            return .startWindowControl(UInt32(id), size: WindowSize(width: UInt16(width), height: UInt16(height)))
        case "window.start":
            guard fields.count == 3, let id = positiveInteger(fields[2], max: UInt64(UInt32.max)) else { throw MessageError.invalid }
            return .startWindow(UInt32(id))
        case "media.stop":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .stopMedia
        case "media.stopped":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .mediaStopped
        case "resume.check":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .resumeCheck
        case "resume.allowed":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .resumeAllowed
        case "resume.denied":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .resumeDenied
        case "offer":
            guard fields.count == 3, validSDP(fields[2]) else { throw MessageError.invalid }
            return .offer(fields[2])
        case "answer":
            guard fields.count == 3, validSDP(fields[2]) else { throw MessageError.invalid }
            return .answer(fields[2])
        default:
            throw MessageError.invalid
        }
    }

    public func encode() throws -> Data {
        let fields: [String]
        switch self {
        case let .pair(name, invitation, publicKey):
            guard Self.validName(name), invitation.count == 32, (1...128).contains(publicKey.count) else { throw MessageError.invalid }
            fields = ["1", "pair", name, invitation.base64EncodedString(), publicKey.base64EncodedString()]
        case let .authenticate(id): fields = ["1", "auth", id.uuidString.lowercased()]
        case let .challenge(data):
            guard data.count == 32 else { throw MessageError.invalid }
            fields = ["1", "challenge", data.base64EncodedString()]
        case let .proof(data):
            guard (1...72).contains(data.count) else { throw MessageError.invalid }
            fields = ["1", "proof", data.base64EncodedString()]
        case let .accepted(id): fields = ["1", "accepted", id.uuidString.lowercased()]
        case .rejected: fields = ["1", "rejected"]
        case .startMedia: fields = ["1", "start"]
        case .startControl: fields = ["1", "control.start"]
        case let .startDisplayControl(id):
            guard id > 0 else { throw MessageError.invalid }
            fields = ["1", "display.control", String(id)]
        case let .startDisplay(id):
            guard id > 0 else { throw MessageError.invalid }
            fields = ["1", "start", String(id)]
        case .listDisplays: fields = ["1", "display.list"]
        case let .displays(displays):
            guard (1...64).contains(displays.count) else { throw MessageError.invalid }
            fields = ["1", "display.listed"] + displays.flatMap { [String($0.id), String($0.width), String($0.height), $0.name] }
        case let .listWindows(offset):
            guard offset <= 512 else { throw MessageError.invalid }
            fields = ["1", "window.list", String(offset)]
        case let .windows(next, values):
            guard next <= 512, values.count <= 32 else { throw MessageError.invalid }
            fields = ["1", "window.listed", String(next)] + values.flatMap {
                [String($0.id), String($0.width), String($0.height), $0.application, $0.title]
            }
        case let .listApplications(offset):
            fields = ["1", "application.list", String(offset)]
        case let .applications(next, values):
            guard values.count <= 32 else { throw MessageError.invalid }
            fields = ["1", "application.listed", String(next)] + values.flatMap { [String($0.id), $0.name] }
        case let .applicationIcon(id): fields = ["1", "application.icon", String(id)]
        case let .applicationIconResult(id, data): fields = ["1", "application.iconed", String(id), data.base64EncodedString()]
        case let .startApplication(id): fields = ["1", "application.start", String(id)]
        case let .startApplicationControl(id): fields = ["1", "application.control", String(id)]
        case let .startWindowControl(id, size):
            guard id > 0 else { throw MessageError.invalid }
            fields = ["1", "window.control", String(id)]
                + (size.map { [String($0.width), String($0.height)] } ?? [])
        case let .startWindow(id):
            guard id > 0 else { throw MessageError.invalid }
            fields = ["1", "window.start", String(id)]
        case .stopMedia: fields = ["1", "media.stop"]
        case .mediaStopped: fields = ["1", "media.stopped"]
        case .resumeCheck: fields = ["1", "resume.check"]
        case .resumeAllowed: fields = ["1", "resume.allowed"]
        case .resumeDenied: fields = ["1", "resume.denied"]
        case let .offer(sdp):
            guard Self.validSDP(sdp) else { throw MessageError.invalid }
            fields = ["1", "offer", sdp]
        case let .answer(sdp):
            guard Self.validSDP(sdp) else { throw MessageError.invalid }
            fields = ["1", "answer", sdp]
        }
        let data = try JSONSerialization.data(withJSONObject: fields, options: [.withoutEscapingSlashes])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    private static func validName(_ name: String) -> Bool {
        name.utf8.count <= 256 && !name.trimmingCharacters(in: CharacterSet(charactersIn: " ")).isEmpty &&
            !name.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) }
    }

    private static func positiveInteger(_ value: String, max: UInt64) -> UInt64? {
        guard !value.isEmpty, value.utf8.count <= 10, !value.hasPrefix("0"),
              value.utf8.allSatisfy({ (48...57).contains($0) }), let number = UInt64(value),
              (1...max).contains(number) else { return nil }
        return number
    }

    private static func nonnegativeInteger(_ value: String, max: UInt64) -> UInt64? {
        if value == "0" { return 0 }
        return positiveInteger(value, max: max)
    }

    private static func validCatalogLabel(_ value: String) -> Bool {
        (1...96).contains(value.utf8.count) && !value.unicodeScalars.contains {
            $0.value < 32 || (0x7f...0x9f).contains($0.value)
        }
    }

    private static func validSDP(_ sdp: String) -> Bool {
        sdp.utf8.count <= 12_288 && sdp.hasPrefix("v=0\r\n") &&
            !sdp.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) && ![9, 10, 13].contains($0.value) }
    }

    // Foundation accepts a trailing comma in an array even though JSON forbids it.
    static func hasTrailingComma(_ data: Data) -> Bool {
        var inString = false, escaped = false
        var previous: UInt8?
        for byte in data {
            if inString {
                if escaped { escaped = false }
                else if byte == 0x5c { escaped = true }
                else if byte == 0x22 { inString = false; previous = byte }
            } else if byte == 0x22 {
                inString = true
            } else if byte != 0x20 && byte != 0x09 && byte != 0x0a && byte != 0x0d {
                if byte == 0x5d && previous == 0x2c { return true }
                previous = byte
            }
        }
        return false
    }

    private static func parseUUID(_ string: String) -> UUID? {
        guard let uuid = UUID(uuidString: string), uuid.uuidString.lowercased() == string else { return nil }
        return uuid
    }

    private static func decodeBase64(_ string: String, range: ClosedRange<Int>) -> Data? {
        guard string.utf8.count <= ((range.upperBound + 2) / 3) * 4,
              let data = Data(base64Encoded: string), range.contains(data.count),
              data.base64EncodedString() == string else { return nil }
        return data
    }
}
