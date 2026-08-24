package com.bd.casttv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码位图生成器（基于 ZXing core）。
 *
 * 用于收藏「导入 / 导出」入口：TV 端启动局域网 HTTP 服务后，把访问地址编码为二维码，
 * 手机扫码即可在浏览器中下载 / 上传收藏 JSON。
 */
object QrCodeGenerator {

    /**
     * 将 [content] 编码为 [size]×[size] 像素的黑白二维码位图；失败返回 null。
     * 采用中等纠错等级（M），并留出 1 模块的静区（margin），便于手机快速识别。
     */
    fun encode(content: String, size: Int = 480): Bitmap? {
        if (content.isBlank() || size <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }
}
