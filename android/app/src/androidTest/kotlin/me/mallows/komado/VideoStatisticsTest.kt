package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.RTCStats

@RunWith(AndroidJUnit4::class)
class VideoStatisticsTest {
    private fun stat(type: String, id: String, values: Map<String, Any>) = RTCStats(1, type, id, values)

    @Test fun usesTheVideoTransportsSelectedPairOnly() {
        val stats = listOf(
            stat("inbound-rtp", "video", mapOf("kind" to "video", "transportId" to "transport", "frameWidth" to 1280,
                "frameHeight" to 720, "framesPerSecond" to 29.5, "codecId" to "codec", "decoderImplementation" to "ExampleDecoder",
                "packetsReceived" to 90, "packetsLost" to 10, "framesDropped" to 3)),
            stat("codec", "codec", mapOf("mimeType" to "video/H264")),
            stat("transport", "transport", mapOf("selectedCandidatePairId" to "selected")),
            stat("candidate-pair", "selected", mapOf("currentRoundTripTime" to 0.042)),
            stat("candidate-pair", "other", mapOf("currentRoundTripTime" to 9.0)),
            stat("inbound-rtp", "audio", mapOf("kind" to "audio")),
        ).associateBy { it.id }
        assertEquals(VideoStatistics(1280, 720, 29.5, 0.042, "H264", "ExampleDecoder", 10.0, 3.0), VideoStatistics.from(stats))
    }

    @Test fun ignoresMissingMalformedOrAmbiguousValues() {
        val video = stat("inbound-rtp", "video", mapOf("kind" to "video", "transportId" to "missing",
            "frameWidth" to -1, "frameHeight" to 3.5, "framesPerSecond" to Double.NaN))
        val result = VideoStatistics.from(mapOf(video.id to video))
        assertNull(result.width)
        assertNull(result.height)
        assertNull(result.framesPerSecond)
        assertNull(result.roundTripSeconds)
        assertNull(result.codec)
        assertNull(result.decoder)
        val second = stat("inbound-rtp", "second", mapOf("kind" to "video", "frameWidth" to 640))
        assertEquals(VideoStatistics(null, null, null, null, null, null, null, null), VideoStatistics.from(mapOf(video.id to video, second.id to second)))
    }

    @Test fun ignoresAnUnrelatedCodec() {
        val video = stat("inbound-rtp", "video", mapOf("kind" to "video", "codecId" to "codec"))
        val audioCodec = stat("codec", "codec", mapOf("mimeType" to "audio/opus"))
        assertNull(VideoStatistics.from(mapOf(video.id to video, audioCodec.id to audioCodec)).codec)
    }

    @Test fun handlesMissingOrInvalidCounters() {
        fun parse(values: Map<String, Any>): VideoStatistics {
            val video = stat("inbound-rtp", "video", values + ("kind" to "video"))
            return VideoStatistics.from(mapOf(video.id to video))
        }
        assertNull(parse(mapOf("packetsReceived" to 0, "packetsLost" to 0)).packetLossPercent)
        assertNull(parse(mapOf("packetsReceived" to 20, "packetsLost" to -1)).packetLossPercent)
        assertNull(parse(mapOf("packetsReceived" to 20)).packetLossPercent)
        assertNull(parse(mapOf("framesDropped" to Double.NaN)).framesDropped)
        assertEquals(0.0, parse(mapOf("packetsReceived" to 20, "packetsLost" to 0)).packetLossPercent)
    }
}
