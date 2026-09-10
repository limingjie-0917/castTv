package com.bd.casttv.music.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

class MusicCoverArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val discRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var artworkBitmap: Bitmap? = null

    @ColorInt
    private var accentColor: Int = Color.rgb(245, 196, 81)

    fun setAccentColor(@ColorInt color: Int) {
        accentColor = color
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        artworkBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val size = minOf(width, height)
        val radius = size * 0.48f
        val cx = width / 2f
        val cy = height / 2f
        discRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val bitmap = artworkBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            discPaint.shader = null
            discPaint.style = Paint.Style.FILL
            discPaint.color = Color.argb(230, 8, 8, 10)
            canvas.drawCircle(cx, cy, radius, discPaint)

            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(discRect.width() / bitmap.width.toFloat(), discRect.height() / bitmap.height.toFloat())
            val dx = discRect.left + (discRect.width() - bitmap.width * scale) * 0.5f
            val dy = discRect.top + (discRect.height() - bitmap.height * scale) * 0.5f
            val matrix = android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            shader.setLocalMatrix(matrix)
            bitmapPaint.shader = shader
            canvas.drawCircle(cx, cy, radius * 0.92f, bitmapPaint)
            bitmapPaint.shader = null
        } else {
            drawMusicNoteFallback(canvas, cx, cy, radius)
        }

        strokePaint.shader = null
        strokePaint.strokeWidth = size * 0.014f
        strokePaint.color = Color.argb(160, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(cx, cy, radius * 0.98f, strokePaint)
    }

    /** 深色渐变底图：一个主音符配合右侧两个错落的小音符，全部使用 Path 绘制。 */
    private fun drawMusicNoteFallback(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        discPaint.style = Paint.Style.FILL
        discPaint.shader = LinearGradient(
            discRect.left,
            discRect.top,
            discRect.right,
            discRect.bottom,
            intArrayOf(
                Color.rgb(26, 34, 44),
                blendColor(Color.rgb(10, 17, 25), accentColor, 0.22f),
                Color.rgb(5, 10, 16),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * 0.92f, discPaint)
        discPaint.shader = null

        // 柔和光晕让音符与深色背景自然融合，不产生生硬的纯色块。
        discPaint.shader = RadialGradient(
            cx - radius * 0.22f,
            cy - radius * 0.18f,
            radius * 1.05f,
            intArrayOf(
                withAlpha(blendColor(accentColor, Color.WHITE, 0.18f), 82),
                withAlpha(accentColor, 24),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * 0.92f, discPaint)
        discPaint.shader = null

        val primary = blendColor(accentColor, Color.WHITE, 0.30f)
        drawSingleNote(
            canvas = canvas,
            centerX = cx - radius * 0.22f,
            centerY = cy + radius * 0.04f,
            size = radius * 0.92f,
            rotation = -7f,
            color = primary,
            alpha = 255,
        )
        drawSingleNote(
            canvas = canvas,
            centerX = cx + radius * 0.43f,
            centerY = cy - radius * 0.31f,
            size = radius * 0.34f,
            rotation = 11f,
            color = blendColor(primary, Color.WHITE, 0.22f),
            alpha = 222,
        )
        drawSingleNote(
            canvas = canvas,
            centerX = cx + radius * 0.52f,
            centerY = cy + radius * 0.30f,
            size = radius * 0.27f,
            rotation = -13f,
            color = blendColor(accentColor, Color.WHITE, 0.48f),
            alpha = 190,
        )
    }

    /**
     * 用贝塞尔曲线构造圆润音符头、略带收束的符杆和向外舒展的弧形旗帜。
     * size 是音符的整体视觉高度，坐标以音符中心为原点，避免依赖文字字形。
     */
    private fun drawSingleNote(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        rotation: Float,
        @ColorInt color: Int,
        alpha: Int,
    ) {
        val path = Path().apply {
            // 椭圆音符头：略向左倾斜，通过不对称贝塞尔形成更柔和的轮廓。
            moveTo(-0.34f * size, 0.26f * size)
            cubicTo(-0.42f * size, 0.10f * size, -0.22f * size, -0.02f * size, -0.03f * size, 0.02f * size)
            cubicTo(0.16f * size, 0.06f * size, 0.19f * size, 0.22f * size, 0.05f * size, 0.34f * size)
            cubicTo(-0.10f * size, 0.47f * size, -0.29f * size, 0.43f * size, -0.34f * size, 0.26f * size)
            close()

            // 符杆并非简单矩形，顶部略宽、底部自然衔接音符头。
            moveTo(0.015f * size, 0.16f * size)
            lineTo(0.015f * size, -0.49f * size)
            cubicTo(0.015f * size, -0.53f * size, 0.045f * size, -0.55f * size, 0.085f * size, -0.54f * size)
            lineTo(0.14f * size, -0.52f * size)
            lineTo(0.14f * size, 0.12f * size)
            cubicTo(0.105f * size, 0.16f * size, 0.065f * size, 0.18f * size, 0.015f * size, 0.16f * size)
            close()

            // 旗帜使用两条弧线构成渐收尾部，呈现轻盈的上扬动势。
            moveTo(0.08f * size, -0.53f * size)
            cubicTo(0.30f * size, -0.48f * size, 0.49f * size, -0.37f * size, 0.50f * size, -0.18f * size)
            cubicTo(0.50f * size, -0.06f * size, 0.44f * size, 0.02f * size, 0.34f * size, 0.08f * size)
            cubicTo(0.38f * size, -0.06f * size, 0.30f * size, -0.18f * size, 0.13f * size, -0.23f * size)
            lineTo(0.08f * size, -0.53f * size)
            close()
        }

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(rotation)

        // 一层轻微偏移阴影提升层次，仍保持电视端远距离观看时的清晰边缘。
        notePaint.shader = null
        notePaint.color = Color.argb((alpha * 0.32f).toInt(), 0, 0, 0)
        canvas.save()
        canvas.translate(size * 0.035f, size * 0.045f)
        canvas.drawPath(path, notePaint)
        canvas.restore()

        notePaint.shader = LinearGradient(
            -size * 0.32f,
            -size * 0.55f,
            size * 0.45f,
            size * 0.42f,
            withAlpha(blendColor(color, Color.WHITE, 0.24f), alpha),
            withAlpha(color, alpha),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, notePaint)
        notePaint.shader = null

        // 音符头上的窄高光强调圆润质感。
        val highlight = Path().apply {
            moveTo(-0.27f * size, 0.20f * size)
            cubicTo(-0.23f * size, 0.10f * size, -0.11f * size, 0.07f * size, -0.02f * size, 0.09f * size)
            cubicTo(-0.12f * size, 0.09f * size, -0.20f * size, 0.14f * size, -0.27f * size, 0.20f * size)
            close()
        }
        notePaint.color = Color.argb((alpha * 0.34f).toInt(), 255, 255, 255)
        canvas.drawPath(highlight, notePaint)
        canvas.restore()
    }

    @ColorInt
    private fun blendColor(@ColorInt from: Int, @ColorInt to: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt(),
        )
    }

    @ColorInt
    private fun withAlpha(@ColorInt color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
