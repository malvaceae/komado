package me.mallows.komado.protocol

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class DisplayInfo(val id: Long, val width: Int, val height: Int, val name: String)
data class ApplicationInfo(val id: Long, val name: String)
data class WindowInfo(val id: Long, val width: Int, val height: Int, val application: String, val title: String, val onScreen: Boolean)
data class WindowSize(val width: Int, val height: Int)
enum class WindowAction { MINIMIZE, RESTORE, CLOSE }

/** Decoding validates message structure, not the sender or session state. */
sealed interface SignalMessage {
    data class Pair(val name: String, val invitation: ByteArray, val publicKeyDER: ByteArray) : SignalMessage
    data class Authenticate(val deviceID: UUID) : SignalMessage
    data class Challenge(val value: ByteArray) : SignalMessage
    data class Proof(val signatureDER: ByteArray) : SignalMessage
    data class Accepted(val deviceID: UUID) : SignalMessage
    data object Rejected : SignalMessage
    data object StartMedia : SignalMessage
    data object StartControl : SignalMessage
    data class StartDisplay(val id: Long) : SignalMessage
    data class StartDisplayControl(val id: Long) : SignalMessage
    data object ListDisplays : SignalMessage
    data class Displays(val values: List<DisplayInfo>) : SignalMessage
    data class ListWindows(val offset: Int) : SignalMessage
    data class Windows(val next: Int, val values: List<WindowInfo>) : SignalMessage
    data object WatchWindows : SignalMessage
    data object WindowsWatching : SignalMessage
    data object WindowsChanged : SignalMessage
    data object StopWatchingWindows : SignalMessage
    data object WindowsWatchStopped : SignalMessage
    data class ListApplications(val offset: Int) : SignalMessage
    data class Applications(val next: Int, val values: List<ApplicationInfo>) : SignalMessage
    data class ApplicationIcon(val id: Long) : SignalMessage
    data class ApplicationIconResult(val id: Long, val data: ByteArray) : SignalMessage
    data class ListLaunchableApplications(val offset: Int) : SignalMessage
    data class LaunchableApplications(val next: Int, val values: List<ApplicationInfo>) : SignalMessage
    data class LaunchableApplicationIcon(val id: Long) : SignalMessage
    data class LaunchableApplicationIconResult(val id: Long, val data: ByteArray) : SignalMessage
    data class LaunchApplication(val id: Long) : SignalMessage
    data class LaunchApplicationResult(val id: Long, val success: Boolean) : SignalMessage
    data class StartApplication(val id: Long) : SignalMessage
    data class StartApplicationControl(val id: Long) : SignalMessage
    data class StartWindow(val id: Long) : SignalMessage
    data class StartWindowControl(val id: Long, val size: WindowSize? = null) : SignalMessage
    data class WindowActionRequest(val id: Long, val action: WindowAction) : SignalMessage
    data class WindowActionResult(val id: Long, val success: Boolean) : SignalMessage
    data object StopMedia : SignalMessage
    data object MediaStopped : SignalMessage
    data object ResumeCheck : SignalMessage
    data object ResumeAllowed : SignalMessage
    data object ResumeDenied : SignalMessage
    data class Offer(val sdp: String) : SignalMessage
    data class Answer(val sdp: String) : SignalMessage
}

object SignalMessageCodec {
    class MessageException : Exception("Invalid signaling message")

    fun decode(payload: ByteArray): SignalMessage {
        if (payload.size !in 1..SignalFrame.MAXIMUM_PAYLOAD) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
            val fields = mutableListOf<String>()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                while (reader.hasNext()) {
                    if (fields.size == 258 || reader.peek() != JsonToken.STRING) throw MessageException()
                    fields.add(reader.nextString())
                }
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
            }
            if (fields.size < 2 || fields[0] != "1") throw MessageException()
            return when (fields[1]) {
                "pair" -> {
                    if (fields.size != 5 || !validName(fields[2])) throw MessageException()
                    SignalMessage.Pair(fields[2], decodeBase64(fields[3], 32..32), decodeBase64(fields[4], 1..128))
                }
                "auth" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Authenticate(parseUUID(fields[2]))
                }
                "challenge" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Challenge(decodeBase64(fields[2], 32..32))
                }
                "proof" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Proof(decodeBase64(fields[2], 1..72))
                }
                "accepted" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Accepted(parseUUID(fields[2]))
                }
                "rejected" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.Rejected
                }
                "start" -> {
                    if (fields.size == 2) SignalMessage.StartMedia
                    else if (fields.size == 3) SignalMessage.StartDisplay(positiveInteger(fields[2], 4_294_967_295L))
                    else throw MessageException()
                }
                "display.control" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.StartDisplayControl(positiveInteger(fields[2], 4_294_967_295L))
                }
                "control.start" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.StartControl
                }
                "display.list" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.ListDisplays
                }
                "display.listed" -> {
                    if (fields.size !in 6..258 || (fields.size - 2) % 4 != 0) throw MessageException()
                    val values = (2 until fields.size step 4).map { i ->
                        if (!validCatalogLabel(fields[i + 3])) throw MessageException()
                        DisplayInfo(positiveInteger(fields[i], 4_294_967_295L),
                            positiveInteger(fields[i + 1], 65_535).toInt(), positiveInteger(fields[i + 2], 65_535).toInt(), fields[i + 3])
                    }
                    if (values.map { it.id }.toSet().size != values.size) throw MessageException()
                    SignalMessage.Displays(values)
                }
                "window.list" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.ListWindows(nonnegativeInteger(fields[2], 65_535).toInt())
                }
                "window.watch", "window.watching", "window.changed", "window.watch.stop", "window.watch.stopped" -> {
                    if (fields.size != 2) throw MessageException()
                    when (fields[1]) {
                        "window.watch" -> SignalMessage.WatchWindows
                        "window.watching" -> SignalMessage.WindowsWatching
                        "window.changed" -> SignalMessage.WindowsChanged
                        "window.watch.stop" -> SignalMessage.StopWatchingWindows
                        else -> SignalMessage.WindowsWatchStopped
                    }
                }
                "window.listed" -> {
                    if (fields.size !in 3..195 || (fields.size - 3) % 6 != 0) throw MessageException()
                    val next = nonnegativeInteger(fields[2], 65_535).toInt()
                    val values = (3 until fields.size step 6).map { i ->
                        if (!validCatalogLabel(fields[i + 3]) || !validCatalogLabel(fields[i + 4]) || fields[i + 5] !in listOf("0", "1")) throw MessageException()
                        WindowInfo(positiveInteger(fields[i], 4_294_967_295L),
                            positiveInteger(fields[i + 1], 65_535).toInt(), positiveInteger(fields[i + 2], 65_535).toInt(),
                            fields[i + 3], fields[i + 4], fields[i + 5] == "1")
                    }
                    if ((values.isEmpty() && next != 0) || values.map { it.id }.toSet().size != values.size) throw MessageException()
                    SignalMessage.Windows(next, values)
                }
                "application.list", "launch.list" -> {
                    if (fields.size != 3) throw MessageException()
                    val offset = nonnegativeInteger(fields[2], 65_535).toInt()
                    if (fields[1] == "application.list") SignalMessage.ListApplications(offset) else SignalMessage.ListLaunchableApplications(offset)
                }
                "application.listed", "launch.listed" -> {
                    if (fields.size !in 3..67 || (fields.size - 3) % 2 != 0) throw MessageException()
                    val next = nonnegativeInteger(fields[2], 65_535).toInt()
                    val values = (3 until fields.size step 2).map { i ->
                        if (!validCatalogLabel(fields[i + 1])) throw MessageException()
                        ApplicationInfo(positiveInteger(fields[i], Int.MAX_VALUE.toLong()), fields[i + 1])
                    }
                    if ((values.isEmpty() && next != 0) || values.map { it.id }.toSet().size != values.size) throw MessageException()
                    if (fields[1] == "application.listed") SignalMessage.Applications(next, values) else SignalMessage.LaunchableApplications(next, values)
                }
                "application.icon", "launch.icon" -> {
                    if (fields.size != 3) throw MessageException()
                    val id = positiveInteger(fields[2], Int.MAX_VALUE.toLong())
                    if (fields[1] == "application.icon") SignalMessage.ApplicationIcon(id) else SignalMessage.LaunchableApplicationIcon(id)
                }
                "application.iconed", "launch.iconed" -> {
                    if (fields.size != 4) throw MessageException()
                    val id = positiveInteger(fields[2], Int.MAX_VALUE.toLong())
                    val data = decodeBase64(fields[3], 0..8192)
                    if (fields[1] == "application.iconed") SignalMessage.ApplicationIconResult(id, data) else SignalMessage.LaunchableApplicationIconResult(id, data)
                }
                "launch.start" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.LaunchApplication(positiveInteger(fields[2], Int.MAX_VALUE.toLong()))
                }
                "launch.result" -> {
                    if (fields.size != 4 || fields[3] !in listOf("0", "1")) throw MessageException()
                    SignalMessage.LaunchApplicationResult(positiveInteger(fields[2], Int.MAX_VALUE.toLong()), fields[3] == "1")
                }
                "application.start", "application.control" -> {
                    if (fields.size != 3) throw MessageException()
                    val id = positiveInteger(fields[2], Int.MAX_VALUE.toLong())
                    if (fields[1] == "application.start") SignalMessage.StartApplication(id) else SignalMessage.StartApplicationControl(id)
                }
                "window.control" -> {
                    if (fields.size != 3 && fields.size != 5) throw MessageException()
                    val size = if (fields.size == 5) WindowSize(positiveInteger(fields[3], 65_535).toInt(),
                        positiveInteger(fields[4], 65_535).toInt()) else null
                    SignalMessage.StartWindowControl(positiveInteger(fields[2], 4_294_967_295L), size)
                }
                "window.start" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.StartWindow(positiveInteger(fields[2], 4_294_967_295L))
                }
                "window.action" -> {
                    if (fields.size != 4) throw MessageException()
                    val id = positiveInteger(fields[2], 4_294_967_295L)
                    val action = WindowAction.entries.firstOrNull { it.name.lowercase() == fields[3] } ?: throw MessageException()
                    SignalMessage.WindowActionRequest(id, action)
                }
                "window.action.result" -> {
                    if (fields.size != 4 || fields[3] !in listOf("0", "1")) throw MessageException()
                    SignalMessage.WindowActionResult(positiveInteger(fields[2], 4_294_967_295L), fields[3] == "1")
                }
                "media.stop" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.StopMedia
                }
                "media.stopped" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.MediaStopped
                }
                "resume.check" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.ResumeCheck
                }
                "resume.allowed" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.ResumeAllowed
                }
                "resume.denied" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.ResumeDenied
                }
                "offer" -> {
                    if (fields.size != 3 || !validSDP(fields[2])) throw MessageException()
                    SignalMessage.Offer(fields[2])
                }
                "answer" -> {
                    if (fields.size != 3 || !validSDP(fields[2])) throw MessageException()
                    SignalMessage.Answer(fields[2])
                }
                else -> throw MessageException()
            }
        } catch (_: MessageException) {
            throw MessageException()
        } catch (_: Exception) {
            throw MessageException()
        }
    }

    fun encode(message: SignalMessage): ByteArray {
        fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
        val fields = when (message) {
            is SignalMessage.Pair -> {
                if (!validName(message.name) || message.invitation.size != 32 || message.publicKeyDER.size !in 1..128) throw MessageException()
                listOf("1", "pair", message.name, b64(message.invitation), b64(message.publicKeyDER))
            }
            is SignalMessage.Authenticate -> listOf("1", "auth", message.deviceID.toString())
            is SignalMessage.Challenge -> {
                if (message.value.size != 32) throw MessageException()
                listOf("1", "challenge", b64(message.value))
            }
            is SignalMessage.Proof -> {
                if (message.signatureDER.size !in 1..72) throw MessageException()
                listOf("1", "proof", b64(message.signatureDER))
            }
            is SignalMessage.Accepted -> listOf("1", "accepted", message.deviceID.toString())
            SignalMessage.Rejected -> listOf("1", "rejected")
            SignalMessage.StartMedia -> listOf("1", "start")
            SignalMessage.StartControl -> listOf("1", "control.start")
            is SignalMessage.StartDisplay -> listOf("1", "start", message.id.toString())
            is SignalMessage.StartDisplayControl -> listOf("1", "display.control", message.id.toString())
            SignalMessage.ListDisplays -> listOf("1", "display.list")
            is SignalMessage.Displays -> {
                if (message.values.size !in 1..64) throw MessageException()
                listOf("1", "display.listed") + message.values.flatMap { listOf(it.id.toString(), it.width.toString(), it.height.toString(), it.name) }
            }
            is SignalMessage.ListWindows -> listOf("1", "window.list", message.offset.toString())
            SignalMessage.WatchWindows -> listOf("1", "window.watch")
            SignalMessage.WindowsWatching -> listOf("1", "window.watching")
            SignalMessage.WindowsChanged -> listOf("1", "window.changed")
            SignalMessage.StopWatchingWindows -> listOf("1", "window.watch.stop")
            SignalMessage.WindowsWatchStopped -> listOf("1", "window.watch.stopped")
            is SignalMessage.Windows -> {
                if (message.values.size > 32 || message.values.any { !validCatalogLabel(it.application) || !validCatalogLabel(it.title) }) throw MessageException()
                listOf("1", "window.listed", message.next.toString()) + message.values.flatMap {
                    listOf(it.id.toString(), it.width.toString(), it.height.toString(), it.application, it.title, if (it.onScreen) "1" else "0")
                }
            }
            is SignalMessage.ListApplications -> listOf("1", "application.list", message.offset.toString())
            is SignalMessage.Applications -> listOf("1", "application.listed", message.next.toString()) +
                message.values.flatMap { listOf(it.id.toString(), it.name) }
            is SignalMessage.ApplicationIcon -> listOf("1", "application.icon", message.id.toString())
            is SignalMessage.ApplicationIconResult -> listOf("1", "application.iconed", message.id.toString(), b64(message.data))
            is SignalMessage.ListLaunchableApplications -> listOf("1", "launch.list", message.offset.toString())
            is SignalMessage.LaunchableApplications -> listOf("1", "launch.listed", message.next.toString()) +
                message.values.flatMap { listOf(it.id.toString(), it.name) }
            is SignalMessage.LaunchableApplicationIcon -> listOf("1", "launch.icon", message.id.toString())
            is SignalMessage.LaunchableApplicationIconResult -> listOf("1", "launch.iconed", message.id.toString(), b64(message.data))
            is SignalMessage.LaunchApplication -> listOf("1", "launch.start", message.id.toString())
            is SignalMessage.LaunchApplicationResult -> listOf("1", "launch.result", message.id.toString(), if (message.success) "1" else "0")
            is SignalMessage.StartApplication -> listOf("1", "application.start", message.id.toString())
            is SignalMessage.StartApplicationControl -> listOf("1", "application.control", message.id.toString())
            is SignalMessage.StartWindow -> listOf("1", "window.start", message.id.toString())
            is SignalMessage.StartWindowControl -> listOf("1", "window.control", message.id.toString()) +
                (message.size?.let { listOf(it.width.toString(), it.height.toString()) } ?: emptyList())
            is SignalMessage.WindowActionRequest -> listOf("1", "window.action", message.id.toString(), message.action.name.lowercase())
            is SignalMessage.WindowActionResult -> listOf("1", "window.action.result", message.id.toString(), if (message.success) "1" else "0")
            SignalMessage.StopMedia -> listOf("1", "media.stop")
            SignalMessage.MediaStopped -> listOf("1", "media.stopped")
            SignalMessage.ResumeCheck -> listOf("1", "resume.check")
            SignalMessage.ResumeAllowed -> listOf("1", "resume.allowed")
            SignalMessage.ResumeDenied -> listOf("1", "resume.denied")
            is SignalMessage.Offer -> {
                if (!validSDP(message.sdp)) throw MessageException()
                listOf("1", "offer", message.sdp)
            }
            is SignalMessage.Answer -> {
                if (!validSDP(message.sdp)) throw MessageException()
                listOf("1", "answer", message.sdp)
            }
        }
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray()
            fields.forEach { writer.value(it) }
            writer.endArray()
        }
        val bytes = output.toString().toByteArray(Charsets.UTF_8)
        decode(bytes)
        return bytes
    }

    private fun validName(name: String): Boolean {
        if (name.length > 256) return false
        val bytes = name.toByteArray(Charsets.UTF_8)
        return name.trim(' ').isNotEmpty() && bytes.size <= 256 &&
            String(bytes, Charsets.UTF_8) == name && name.none { it.isISOControl() }
    }

    private fun positiveInteger(value: String, maximum: Long): Long {
        if (value.isEmpty() || value.length > 10 || value.startsWith('0') || value.any { it !in '0'..'9' }) throw MessageException()
        return value.toLongOrNull()?.takeIf { it in 1..maximum } ?: throw MessageException()
    }

    private fun nonnegativeInteger(value: String, maximum: Long): Long =
        if (value == "0") 0 else positiveInteger(value, maximum)

    private fun validCatalogLabel(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size in 1..96 && String(bytes, Charsets.UTF_8) == value && value.none { it.isISOControl() }
    }

    private fun validSDP(sdp: String): Boolean {
        if (sdp.length > 12_288 || !sdp.startsWith("v=0\r\n")) return false
        val bytes = sdp.toByteArray(Charsets.UTF_8)
        return bytes.size <= 12_288 && String(bytes, Charsets.UTF_8) == sdp &&
            sdp.none { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' }
    }

    private fun parseUUID(value: String): UUID {
        val uuid = UUID.fromString(value)
        if (uuid.toString() != value) throw MessageException()
        return uuid
    }

    private fun decodeBase64(value: String, size: IntRange): ByteArray {
        if (value.length > ((size.last + 2) / 3) * 4) throw MessageException()
        val decoded = Base64.decode(value, Base64.NO_WRAP)
        if (decoded.size !in size || Base64.encodeToString(decoded, Base64.NO_WRAP) != value) throw MessageException()
        return decoded
    }
}
