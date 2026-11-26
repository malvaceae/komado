package me.mallows.komado.protocol

/** Length-prefixed signaling frames. Payload parsing and authentication are separate. */
object SignalFrame {
    const val MAXIMUM_PAYLOAD = 16_384
    const val MAXIMUM_CHUNK = MAXIMUM_PAYLOAD + 4

    class FrameException(message: String) : Exception(message)

    fun encode(payload: ByteArray): ByteArray {
        if (payload.size !in 1..MAXIMUM_PAYLOAD) throw FrameException("Invalid frame length")
        val size = payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()) + payload
    }

    /** One decoder per connection; any malformed frame permanently closes it. */
    class Decoder {
        private var buffer = ByteArray(0)
        private var closed = false

        fun append(chunk: ByteArray): List<ByteArray> {
            if (closed) throw FrameException("Decoder closed")
            if (chunk.size > MAXIMUM_CHUNK) return fail("Chunk too large")
            buffer += chunk
            val frames = mutableListOf<ByteArray>()
            while (buffer.size >= 4) {
                val size = (0..3).fold(0L) { value, i -> (value shl 8) or (buffer[i].toLong() and 0xff) }
                if (size !in 1..MAXIMUM_PAYLOAD.toLong()) return fail("Invalid frame length")
                val length = size.toInt()
                if (buffer.size < length + 4) break
                frames.add(buffer.copyOfRange(4, 4 + length))
                buffer = buffer.copyOfRange(4 + length, buffer.size)
            }
            return frames
        }

        fun finish() {
            if (closed) throw FrameException("Decoder closed")
            if (buffer.isNotEmpty()) fail<Unit>("Truncated frame")
            closed = true
        }

        private fun <T> fail(message: String): T {
            buffer = ByteArray(0)
            closed = true
            throw FrameException(message)
        }
    }
}
