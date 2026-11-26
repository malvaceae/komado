import Foundation

/// Length-prefixed signaling frames. Payload parsing and authentication are
/// separate: a frame by itself must never be treated as an authorized message.
public enum SignalFrame {
    public static let maximumPayload = 16_384
    public static let maximumChunk = maximumPayload + 4

    public enum FrameError: Error, Equatable {
        case invalidLength
        case chunkTooLarge
        case truncated
        case closed
    }

    public static func encode(_ payload: Data) throws -> Data {
        guard (1...maximumPayload).contains(payload.count) else { throw FrameError.invalidLength }
        let size = UInt32(payload.count)
        var data = Data([UInt8((size >> 24) & 0xff), UInt8((size >> 16) & 0xff),
                         UInt8((size >> 8) & 0xff), UInt8(size & 0xff)])
        data.append(payload)
        return data
    }

    /// One decoder per connection; any malformed frame permanently closes it.
    public struct Decoder {
        private var buffer = Data()
        private var closed = false

        public init() {}

        public mutating func append(_ chunk: Data) throws -> [Data] {
            guard !closed else { throw FrameError.closed }
            guard chunk.count <= SignalFrame.maximumChunk else { return try fail(.chunkTooLarge) }
            buffer.append(chunk)
            var frames: [Data] = []
            while buffer.count >= 4 {
                let header = Array(buffer.prefix(4))
                let size = header.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
                guard size > 0 && size <= SignalFrame.maximumPayload else { return try fail(.invalidLength) }
                let length = Int(size)
                guard buffer.count >= 4 + length else { break }
                frames.append(Data(buffer.dropFirst(4).prefix(length)))
                buffer = Data(buffer.dropFirst(4 + length))
            }
            return frames
        }

        public mutating func finish() throws {
            guard !closed else { throw FrameError.closed }
            guard buffer.isEmpty else { return try fail(.truncated) }
            closed = true
        }

        private mutating func fail<T>(_ error: FrameError) throws -> T {
            buffer.removeAll()
            closed = true
            throw error
        }
    }
}
