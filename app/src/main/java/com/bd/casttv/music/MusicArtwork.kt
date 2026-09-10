package com.bd.casttv.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import androidx.annotation.ColorInt
import kotlin.math.max
import kotlin.math.min

object MusicArtworkLoader {
    fun createVinylBitmap(size: Int, @ColorInt accentColor: Int): Bitmap {
        val safeSize = size.coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = safeSize / 2f
        val radius = safeSize * 0.48f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = 0xFF090909.toInt()
        canvas.drawCircle(center, center, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = safeSize * 0.018f
        for (index in 0 until 8) {
            paint.color = if (index % 2 == 0) 0x26FFFFFF else 0x16000000
            val ringRadius = radius * (0.32f + index * 0.075f)
            canvas.drawCircle(center, center, ringRadius, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = accentColor
        canvas.drawCircle(center, center, radius * 0.16f, paint)

        paint.color = 0xFFF7F2E6.toInt()
        canvas.drawCircle(center, center, radius * 0.048f, paint)
        return bitmap
    }

    /**
     * 从远程音频的 ID3 元数据提取内嵌封面。调用方必须放在后台线程执行。
     * 限制解码尺寸，避免异常大的内嵌图片在 TV 设备上造成内存压力。
     */
    fun extractEmbeddedArtwork(audioUrl: String, maxDimension: Int = 1_200): Bitmap? {
        if (audioUrl.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioUrl, emptyMap())
            val bytes = retriever.embeddedPicture ?: return null
            if (bytes.isEmpty()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun scaleCenterCrop(source: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val safeWidth = outWidth.coerceAtLeast(1)
        val safeHeight = outHeight.coerceAtLeast(1)
        if (source.width == safeWidth && source.height == safeHeight) return source
        val src = Rect(0, 0, source.width, source.height)
        val dst = RectF(0f, 0f, safeWidth.toFloat(), safeHeight.toFloat())
        val result = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return result
    }
}

object MusicBlurUtils {
    fun createBackdropBitmap(source: Bitmap, outWidth: Int, outHeight: Int, radius: Int = 18): Bitmap {
        val scaled = MusicArtworkLoader.scaleCenterCrop(source, outWidth, outHeight)
        return stackBlur(scaled, radius)
    }

    /**
     * 轻量 Stack Blur：用于 API 21~30 的背景降级；尺寸控制在小图后再模糊，避免 TV 老盒子卡顿。
     */
    fun stackBlur(source: Bitmap, radius: Int): Bitmap {
        val safeRadius = radius.coerceIn(1, 32)
        val bitmap = if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) source.copy(Bitmap.Config.ARGB_8888, true)
        else source.copy(Bitmap.Config.ARGB_8888, true)
        if (safeRadius <= 1) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val div = safeRadius * 2 + 1
        val red = IntArray(width * height)
        val green = IntArray(width * height)
        val blue = IntArray(width * height)
        val alpha = IntArray(width * height)
        val vMin = IntArray(max(width, height))
        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum) { index -> index / divSum }
        val stack = Array(div) { IntArray(4) }

        var yi = 0
        var yw = 0
        for (y in 0 until height) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var aSum = 0
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0
            var aInSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var aOutSum = 0
            for (i in -safeRadius..safeRadius) {
                val pixel = pixels[yi + min(width - 1, max(i, 0))]
                val sir = stack[i + safeRadius]
                sir[0] = pixel shr 24 and 0xFF
                sir[1] = pixel shr 16 and 0xFF
                sir[2] = pixel shr 8 and 0xFF
                sir[3] = pixel and 0xFF
                val rbs = safeRadius + 1 - kotlin.math.abs(i)
                aSum += sir[0] * rbs
                rSum += sir[1] * rbs
                gSum += sir[2] * rbs
                bSum += sir[3] * rbs
                if (i > 0) {
                    aInSum += sir[0]
                    rInSum += sir[1]
                    gInSum += sir[2]
                    bInSum += sir[3]
                } else {
                    aOutSum += sir[0]
                    rOutSum += sir[1]
                    gOutSum += sir[2]
                    bOutSum += sir[3]
                }
            }
            var stackPointer = safeRadius
            for (x in 0 until width) {
                alpha[yi] = dv[aSum]
                red[yi] = dv[rSum]
                green[yi] = dv[gSum]
                blue[yi] = dv[bSum]

                aSum -= aOutSum
                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                var stackStart = stackPointer - safeRadius + div
                val sir = stack[stackStart % div]
                aOutSum -= sir[0]
                rOutSum -= sir[1]
                gOutSum -= sir[2]
                bOutSum -= sir[3]

                if (y == 0) vMin[x] = min(x + safeRadius + 1, width - 1)
                val pixel = pixels[yw + vMin[x]]
                sir[0] = pixel shr 24 and 0xFF
                sir[1] = pixel shr 16 and 0xFF
                sir[2] = pixel shr 8 and 0xFF
                sir[3] = pixel and 0xFF

                aInSum += sir[0]
                rInSum += sir[1]
                gInSum += sir[2]
                bInSum += sir[3]
                aSum += aInSum
                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                val sirNext = stack[stackPointer]
                aOutSum += sirNext[0]
                rOutSum += sirNext[1]
                gOutSum += sirNext[2]
                bOutSum += sirNext[3]
                aInSum -= sirNext[0]
                rInSum -= sirNext[1]
                gInSum -= sirNext[2]
                bInSum -= sirNext[3]
                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var aSum = 0
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0
            var aInSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var aOutSum = 0
            var yp = -safeRadius * width
            for (i in -safeRadius..safeRadius) {
                val yiSafe = max(0, yp) + x
                val sir = stack[i + safeRadius]
                sir[0] = alpha[yiSafe]
                sir[1] = red[yiSafe]
                sir[2] = green[yiSafe]
                sir[3] = blue[yiSafe]
                val rbs = safeRadius + 1 - kotlin.math.abs(i)
                aSum += alpha[yiSafe] * rbs
                rSum += red[yiSafe] * rbs
                gSum += green[yiSafe] * rbs
                bSum += blue[yiSafe] * rbs
                if (i > 0) {
                    aInSum += sir[0]
                    rInSum += sir[1]
                    gInSum += sir[2]
                    bInSum += sir[3]
                } else {
                    aOutSum += sir[0]
                    rOutSum += sir[1]
                    gOutSum += sir[2]
                    bOutSum += sir[3]
                }
                if (i < height - 1) yp += width
            }
            var yiLocal = x
            var stackPointer = safeRadius
            for (y in 0 until height) {
                pixels[yiLocal] = (dv[aSum] shl 24) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
                aSum -= aOutSum
                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                var stackStart = stackPointer - safeRadius + div
                val sir = stack[stackStart % div]
                aOutSum -= sir[0]
                rOutSum -= sir[1]
                gOutSum -= sir[2]
                bOutSum -= sir[3]

                if (x == 0) vMin[y] = min(y + safeRadius + 1, height - 1) * width
                val pixelIndex = x + vMin[y]
                sir[0] = alpha[pixelIndex]
                sir[1] = red[pixelIndex]
                sir[2] = green[pixelIndex]
                sir[3] = blue[pixelIndex]

                aInSum += sir[0]
                rInSum += sir[1]
                gInSum += sir[2]
                bInSum += sir[3]
                aSum += aInSum
                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                val sirNext = stack[stackPointer]
                aOutSum += sirNext[0]
                rOutSum += sirNext[1]
                gOutSum += sirNext[2]
                bOutSum += sirNext[3]
                aInSum -= sirNext[0]
                rInSum -= sirNext[1]
                gInSum -= sirNext[2]
                bInSum -= sirNext[3]
                yiLocal += width
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
