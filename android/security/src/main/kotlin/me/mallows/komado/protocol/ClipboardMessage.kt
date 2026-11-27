package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Clipboard transfers; content is never logged or persisted by komado. */
sealed interface ClipboardOperation {
    data object Get : ClipboardOperation
    data object Prepare : ClipboardOperation
    data class Set(val text: String) : ClipboardOperation
    data object AutoStart : ClipboardOperation
    data object AutoPoll : ClipboardOperation
    data object AutoPrepare : ClipboardOperation
    data class AutoSet(val text: String) : ClipboardOperation
}
data class ClipboardRequest(val sequence: Long, val captureRevision: Long, val operation: ClipboardOperation)
sealed interface ClipboardResult {
    data object Ok : ClipboardResult
    data object Ready : ClipboardResult
    data object Started : ClipboardResult
    data object Unchanged : ClipboardResult
    data object Unavailable : ClipboardResult
    data object Busy : ClipboardResult
    data class Text(val value: String) : ClipboardResult
}
data class ClipboardResponse(val sequence: Long, val result: ClipboardResult)

object ClipboardCodec {
    const val MAX_TEXT_BYTES = 16_384
    const val MAX_MESSAGE_BYTES = 65_536
    class MessageException : Exception("Invalid clipboard message")

    fun validText(value: String): Boolean {
        if (value.length !in 1..MAX_TEXT_BYTES) return false
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size in 1..MAX_TEXT_BYTES && String(bytes, Charsets.UTF_8) == value &&
            value.none { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' }
    }

    fun decodeRequest(data: ByteArray): ClipboardRequest {
        val f = fields(data)
        if (f.size < 4) throw MessageException()
        val sequence = integer(f[2]); val revision = integer(f[3])
        return when {
            f[1] == "clipboard.get" && f.size == 4 -> ClipboardRequest(sequence, revision, ClipboardOperation.Get)
            f[1] == "clipboard.prepare" && f.size == 4 -> ClipboardRequest(sequence, revision, ClipboardOperation.Prepare)
            f[1] == "clipboard.auto.start" && f.size == 4 -> ClipboardRequest(sequence, revision, ClipboardOperation.AutoStart)
            f[1] == "clipboard.auto.poll" && f.size == 4 -> ClipboardRequest(sequence, revision, ClipboardOperation.AutoPoll)
            f[1] == "clipboard.auto.prepare" && f.size == 4 -> ClipboardRequest(sequence, revision, ClipboardOperation.AutoPrepare)
            f[1] == "clipboard.auto.set" && f.size == 5 && validText(f[4]) -> ClipboardRequest(sequence, revision, ClipboardOperation.AutoSet(f[4]))
            f[1] == "clipboard.set" && f.size == 5 && validText(f[4]) -> ClipboardRequest(sequence, revision, ClipboardOperation.Set(f[4]))
            else -> throw MessageException()
        }
    }

    fun encodeRequest(value: ClipboardRequest): ByteArray {
        if (value.sequence !in 1..PointerClickCodec.MAX_SEQUENCE || value.captureRevision !in 1..PointerClickCodec.MAX_SEQUENCE ||
            (value.operation is ClipboardOperation.Set && !validText(value.operation.text)) ||
            (value.operation is ClipboardOperation.AutoSet && !validText(value.operation.text))) throw MessageException()
        val fields = mutableListOf("1", "", value.sequence.toString(), value.captureRevision.toString())
        when (val operation = value.operation) {
            ClipboardOperation.Get -> fields[1] = "clipboard.get"
            ClipboardOperation.Prepare -> fields[1] = "clipboard.prepare"
            is ClipboardOperation.Set -> { fields[1] = "clipboard.set"; fields.add(operation.text) }
            ClipboardOperation.AutoStart -> fields[1] = "clipboard.auto.start"
            ClipboardOperation.AutoPoll -> fields[1] = "clipboard.auto.poll"
            ClipboardOperation.AutoPrepare -> fields[1] = "clipboard.auto.prepare"
            is ClipboardOperation.AutoSet -> { fields[1] = "clipboard.auto.set"; fields.add(operation.text) }
        }
        return encode(fields).also { if (decodeRequest(it) != value) throw MessageException() }
    }

    fun decodeResponse(data: ByteArray): ClipboardResponse {
        val f = fields(data)
        if (f.size < 4 || f[1] != "clipboard.result") throw MessageException()
        val sequence = integer(f[2])
        val result = when {
            f[3] == "ok" && f.size == 4 -> ClipboardResult.Ok
            f[3] == "ready" && f.size == 4 -> ClipboardResult.Ready
            f[3] == "started" && f.size == 4 -> ClipboardResult.Started
            f[3] == "unchanged" && f.size == 4 -> ClipboardResult.Unchanged
            f[3] == "unavailable" && f.size == 4 -> ClipboardResult.Unavailable
            f[3] == "busy" && f.size == 4 -> ClipboardResult.Busy
            f[3] == "text" && f.size == 5 && validText(f[4]) -> ClipboardResult.Text(f[4])
            else -> throw MessageException()
        }
        return ClipboardResponse(sequence, result)
    }

    fun encodeResponse(value: ClipboardResponse): ByteArray {
        if (value.sequence !in 1..PointerClickCodec.MAX_SEQUENCE ||
            (value.result is ClipboardResult.Text && !validText(value.result.value))) throw MessageException()
        val fields = mutableListOf("1", "clipboard.result", value.sequence.toString())
        when (val result = value.result) {
            ClipboardResult.Ok -> fields.add("ok")
            ClipboardResult.Ready -> fields.add("ready")
            ClipboardResult.Started -> fields.add("started")
            ClipboardResult.Unchanged -> fields.add("unchanged")
            ClipboardResult.Unavailable -> fields.add("unavailable")
            ClipboardResult.Busy -> fields.add("busy")
            is ClipboardResult.Text -> { fields.add("text"); fields.add(result.value) }
        }
        return encode(fields).also { if (decodeResponse(it) != value) throw MessageException() }
    }

    private fun integer(value: String): Long {
        if (value.isEmpty() || value.length > 16 || value.startsWith('0') || value.any { it !in '0'..'9' }) throw MessageException()
        return value.toLongOrNull()?.takeIf { it in 1..PointerClickCodec.MAX_SEQUENCE } ?: throw MessageException()
    }

    private fun fields(data: ByteArray): List<String> {
        if (data.size !in 1..MAX_MESSAGE_BYTES) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false; reader.beginArray()
                val values = mutableListOf<String>()
                while (reader.hasNext()) {
                    if (values.size == 5 || reader.peek() != JsonToken.STRING) throw MessageException()
                    values.add(reader.nextString())
                }
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT || values.size < 2 || values[0] != "1") throw MessageException()
                return values
            }
        } catch (_: Exception) { throw MessageException() }
    }

    private fun encode(fields: List<String>): ByteArray {
        val output = StringWriter()
        JsonWriter(output).use { writer -> writer.beginArray(); fields.forEach(writer::value); writer.endArray() }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (it.size > MAX_MESSAGE_BYTES) throw MessageException() }
    }
}
