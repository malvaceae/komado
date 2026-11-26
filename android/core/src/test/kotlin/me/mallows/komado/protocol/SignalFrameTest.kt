package me.mallows.komado.protocol

import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignalFrameTest {
    private fun decodeHex(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun sharedFramesDecodeAtEverySplit() {
        val vectors = File(System.getProperty("komado.fixtures"), "signal-framing.txt")
            .readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(vectors.isNotEmpty())
        for (row in vectors) {
            val (frameHex, payloadHex) = row.split(" ")
            val frame = decodeHex(frameHex)
            val payload = decodeHex(payloadHex)
            assertContentEquals(frame, SignalFrame.encode(payload))
            for (split in 0..frame.size) {
                val decoder = SignalFrame.Decoder()
                val frames = decoder.append(frame.copyOfRange(0, split)) + decoder.append(frame.copyOfRange(split, frame.size))
                assertEquals(1, frames.size)
                assertContentEquals(payload, frames[0])
                decoder.finish()
            }
        }
    }

    @Test fun multipleFramesAndLengthBoundaries() {
        val first = byteArrayOf(1, 2)
        val second = ByteArray(SignalFrame.MAXIMUM_PAYLOAD) { 9 }
        val framed = SignalFrame.encode(first) + SignalFrame.encode(second)
        val decoder = SignalFrame.Decoder()
        val firstBatch = decoder.append(framed.copyOfRange(0, SignalFrame.MAXIMUM_CHUNK))
        assertEquals(1, firstBatch.size)
        assertContentEquals(first, firstBatch[0])
        val secondBatch = decoder.append(framed.copyOfRange(SignalFrame.MAXIMUM_CHUNK, framed.size))
        assertEquals(1, secondBatch.size)
        assertContentEquals(second, secondBatch[0])
        decoder.finish()
        assertFailsWith<SignalFrame.FrameException> { SignalFrame.encode(byteArrayOf()) }
        assertFailsWith<SignalFrame.FrameException> { SignalFrame.encode(ByteArray(SignalFrame.MAXIMUM_PAYLOAD + 1)) }
    }

    @Test fun invalidAndTruncatedFramesCloseDecoder() {
        for (header in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 0x40, 1), ByteArray(4) { -1 })) {
            val decoder = SignalFrame.Decoder()
            assertFailsWith<SignalFrame.FrameException> { decoder.append(header) }
            assertFailsWith<SignalFrame.FrameException> { decoder.append(byteArrayOf()) }
        }
        val incomplete = SignalFrame.Decoder()
        incomplete.append(byteArrayOf(0, 0, 0, 2, 1))
        assertFailsWith<SignalFrame.FrameException> { incomplete.finish() }
        val oversized = SignalFrame.Decoder()
        assertFailsWith<SignalFrame.FrameException> { oversized.append(ByteArray(SignalFrame.MAXIMUM_CHUNK + 1)) }
    }
}
