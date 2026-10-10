package com.techazsure.deskless.link

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes, made and read on the phone. */
object Qr {
    /** The squares of a QR code for [text], with no border; the caller draws them at any size. */
    fun squares(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )

    /**
     * Looks for a QR code in one camera frame. [luminance] is the frame's brightness, one byte per pixel,
     * row by row. Returns the text, or null when no code can be read in this frame.
     */
    fun read(luminance: ByteArray, width: Int, height: Int): String? {
        if (luminance.size < width * height) return null
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: Exception) {
            // Most frames hold no code, or a blurred one; the next frame is tried.
            null
        }
    }
}
