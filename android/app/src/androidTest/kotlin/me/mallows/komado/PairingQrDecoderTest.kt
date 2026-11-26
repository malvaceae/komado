package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import me.mallows.komado.protocol.PairingInvitation
import me.mallows.komado.protocol.PairingInvitationCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class PairingQrDecoderTest {
    @Test fun decodesPaddedLuminanceAndRejectsInvalidInput() {
        val invitation = PairingInvitation("192.0.2.1", 48365, "A".repeat(64), "B".repeat(64))
        val text = String(PairingInvitationCodec.encode(invitation), Charsets.UTF_8)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
        val rowStride = 820
        val bytes = ByteArray(rowStride * 400) { 0xff.toByte() }
        for (y in 0 until 400) for (x in 0 until 400) bytes[y * rowStride + x * 2] = if (matrix[x, y]) 0 else 0xff.toByte()
        assertEquals(invitation, PairingQrDecoder.decode(400, 400, rowStride, 2, ByteBuffer.wrap(bytes)))
        assertNull(PairingQrDecoder.decode(400, 400, rowStride, 2, ByteBuffer.wrap(bytes, 0, 10)))
        assertNull(PairingQrDecoder.decode(4096, 4096, 4096, 1, ByteBuffer.wrap(bytes)))
        assertNull(PairingQrDecoder.decode(1, 1, -1, 1, ByteBuffer.wrap(bytes)))
    }
}
