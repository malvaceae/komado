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
                "frameHeight" to 720, "framesPerSecond" to 29.5)),
            stat("transport", "transport", mapOf("selectedCandidatePairId" to "selected")),
            stat("candidate-pair", "selected", mapOf("currentRoundTripTime" to 0.042)),
            stat("candidate-pair", "other", mapOf("currentRoundTripTime" to 9.0)),
            stat("inbound-rtp", "audio", mapOf("kind" to "audio")),
        ).associateBy { it.id }
        assertEquals(VideoStatistics(1280, 720, 29.5, 0.042), VideoStatistics.from(stats))
    }

    @Test fun ignoresMissingMalformedOrAmbiguousValues() {
        val video = stat("inbound-rtp", "video", mapOf("kind" to "video", "transportId" to "missing",
            "frameWidth" to -1, "frameHeight" to 3.5, "framesPerSecond" to Double.NaN))
        val result = VideoStatistics.from(mapOf(video.id to video))
        assertNull(result.width)
        assertNull(result.height)
        assertNull(result.framesPerSecond)
        assertNull(result.roundTripSeconds)
        val second = stat("inbound-rtp", "second", mapOf("kind" to "video", "frameWidth" to 640))
        assertEquals(VideoStatistics(null, null, null, null), VideoStatistics.from(mapOf(video.id to video, second.id to second)))
    }
}
