package me.mallows.komado

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import me.mallows.komado.protocol.PairingInvitation
import me.mallows.komado.protocol.PairingInvitationCodec
import java.nio.ByteBuffer

/** Decodes one bounded camera luminance plane without storing its image or contents. */
internal object PairingQrDecoder {
    fun decode(width: Int, height: Int, rowStride: Int, pixelStride: Int, data: ByteBuffer): PairingInvitation? {
        if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 4_194_304 ||
            rowStride <= 0 || pixelStride <= 0) return null
        val source = data.slice()
        val last = (height - 1L) * rowStride + (width - 1L) * pixelStride
        if (last < 0 || last >= source.limit()) return null
        return try {
            val luminance = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                luminance[y * width + x] = source.get(y * rowStride + x * pixelStride)
            }
            val image = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image)), mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")).text
            if (text.length > 1024) null else PairingInvitationCodec.decode(text.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { null }
    }
}
