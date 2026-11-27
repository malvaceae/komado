package me.mallows.komado

import org.webrtc.RTCStats

/** Only numeric display metrics leave this parser; addresses and identifiers do not. */
internal data class VideoStatistics(val width: Int?, val height: Int?, val framesPerSecond: Double?, val roundTripSeconds: Double?) {
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
            return VideoStatistics(width, height, fps, rtt)
        }
    }
}
