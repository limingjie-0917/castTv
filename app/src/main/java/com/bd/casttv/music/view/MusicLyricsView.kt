package com.bd.casttv.music.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.bd.casttv.music.MusicLrcLine
import kotlin.math.abs

class MusicLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 196, 81)
        textAlign = Paint.Align.CENTER
        textSize = sp(28f)
        isFakeBoldText = true
    }
    private val nearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(208, 214, 214, 218)
        textAlign = Paint.Align.CENTER
        textSize = sp(17f)
    }
    private val farPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(148, 164, 164, 170)
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
    }

    private var lyrics: List<MusicLrcLine> = emptyList()
    private var positionMs: Long = 0L
    private var emptyText: String = "暂无歌词"

    fun setAccentColor(color: Int) {
        activePaint.color = color
        invalidate()
    }

    fun setLyrics(lines: List<MusicLrcLine>, emptyText: String = "暂无歌词") {
        lyrics = lines
        this.emptyText = emptyText
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        this.positionMs = positionMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (lyrics.isEmpty()) {
            drawCenteredText(canvas, emptyText, cx, cy, activePaint)
            return
        }
        val activeIndex = activeLineIndex(positionMs)
        val activeLine = lyrics.getOrNull(activeIndex) ?: return
        val nextTime = lyrics.getOrNull(activeIndex + 1)?.timeMs ?: activeLine.timeMs + 4_000L
        val duration = (nextTime - activeLine.timeMs).coerceAtLeast(1L)
        val progress = ((positionMs - activeLine.timeMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val lineGap = height * 0.17f
        val baseShift = progress * lineGap

        for (index in lyrics.indices) {
            val relative = index - activeIndex
            if (abs(relative) > 3) continue
            val y = cy + relative * lineGap - baseShift
            when {
                index == activeIndex -> drawCenteredText(canvas, lyrics[index].text, cx, y, activePaint)
                abs(relative) == 1 -> drawCenteredText(canvas, lyrics[index].text, cx, y, nearPaint)
                else -> drawCenteredText(canvas, lyrics[index].text, cx, y, farPaint)
            }
        }
    }

    private fun activeLineIndex(positionMs: Long): Int {
        if (lyrics.isEmpty()) return 0
        var low = 0
        var high = lyrics.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timeMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer.coerceIn(0, lyrics.lastIndex)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
        val display = TextUtils.ellipsize(text, android.text.TextPaint(paint), width * 0.88f, TextUtils.TruncateAt.END).toString()
        val baseline = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(display, cx, baseline, paint)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }
}
