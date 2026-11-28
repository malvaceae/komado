package me.mallows.komado

import org.webrtc.RTCStats

/** Addresses and connection identifiers do not leave this parser. */
internal data class VideoStatistics(val width: Int?, val height: Int?, val framesPerSecond: Double?,
                                    val roundTripSeconds: Double?, val codec: String?, val decoder: String?,
                                    val packetLossPercent: Double?, val framesDropped: Double?) {
    private data class ReceiveSample(val stream: String, val timestampUs: Double, val bytes: Double)
    private var receiveSample: ReceiveSample? = null

    fun receiveBitsPerSecond(previous: VideoStatistics): Double? {
        val current = receiveSample ?: return null
        val old = previous.receiveSample ?: return null
        val elapsed = current.timestampUs - old.timestampUs
        val bytes = current.bytes - old.bytes
        if (current.stream != old.stream || elapsed <= 0 || bytes < 0) return null
        return (bytes * 8_000_000 / elapsed).takeIf { it.isFinite() }
    }

    companion object {
        fun from(stats: Map<String, RTCStats>): VideoStatistics {
            val inbound = stats.values.filter {
                it.type == "inbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video")
            }.singleOrNull()
            val members = inbound?.members.orEmpty()
            fun positiveDimension(name: String): Int? {
                val value = (members[name] as? Number)?.toDouble() ?: return null
                if (!value.isFinite() || value !in 1.0..32768.0 || value % 1.0 != 0.0) return null
                return value.toInt()
            }
            val width = positiveDimension("frameWidth")
            val height = positiveDimension("frameHeight")
            val fps = (members["framesPerSecond"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1000.0 }
            val transport = (members["transportId"] as? String)?.let(stats::get)?.takeIf { it.type == "transport" }
            val pair = (transport?.members?.get("selectedCandidatePairId") as? String)?.let(stats::get)
                ?.takeIf { it.type == "candidate-pair" }
            val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it in 0.0..3600.0 }
            val codec = (members["codecId"] as? String)?.let(stats::get)?.takeIf { it.type == "codec" }
                ?.members?.get("mimeType")?.let { it as? String }
                ?.takeIf { it.startsWith("video/", ignoreCase = true) && it.length > "video/".length }
                ?.substringAfter('/')
            val decoder = (members["decoderImplementation"] as? String)?.takeIf { it.isNotBlank() }
            fun count(name: String): Double? = (members[name] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0 && it % 1.0 == 0.0 }
            val received = count("packetsReceived")
            val lost = count("packetsLost")
            val packetLoss = if (received != null && lost != null && received + lost > 0)
                lost / (received + lost) * 100 else null
            return VideoStatistics(width, height, fps, rtt, codec, decoder, packetLoss, count("framesDropped")).also { result ->
                val bytes = count("bytesReceived")
                if (inbound != null && bytes != null && inbound.timestampUs.isFinite()) {
                    result.receiveSample = ReceiveSample(inbound.id, inbound.timestampUs, bytes)
                }
            }
        }
    }
}
