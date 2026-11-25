package com.linnas.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * M7 分享链接二维码生成:把分享 URL 编码为二维码位图,
 * 供「分享」对话框中直接展示,手机扫码即访问。
 */
object QrCode {

    /** 生成二维码;失败返回 null(调用方回退为纯文字链接)。size 为边长(px)。 */
    fun make(content: String, sizePx: Int, padding: Int = 1): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to padding,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000L.toInt() else 0xFFFFFFFF.toInt())
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
