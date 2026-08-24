package com.bd.casttv.ui

import android.R as AndroidR
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.bd.casttv.R as AppR

class GlowUnderlineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private companion object {
        private const val VERTICAL_GLOW_HEIGHT_FRACTION = 0.72f // 光晕高度约占控件 60%~80%，从下划线向上消失
    }

    private val glowColor = Color.parseColor("#FFCC66")
    private val idleLineColor = Color.parseColor("#66FFFFFF")
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glowColor }
    private val idleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleLineColor }
    private var active = false
    private var selectedState = false
    private var showIdleLine = false

    init {
        setWillNotDraw(false)
        context.obtainStyledAttributes(attrs, AppR.styleable.GlowUnderlineView).recycle()
    }

    /**
     * 统一入口：同一帧内更新「选中下划线」和「焦点光晕」，避免外部先后设置
     * isSelected / isActivated 时产生一帧不同步的视觉延迟。
     */
    fun applyVisualState(selected: Boolean, active: Boolean) {
        selectedState = selected
        this.active = active
        if (isSelected != selected) super.setSelected(selected)
        if (isActivated != active) super.setActivated(active)
        refreshDrawableState()
        invalidate()
    }

    fun setIdleLineVisible(visible: Boolean) {
        if (showIdleLine == visible) return
        showIdleLine = visible
        invalidate()
    }

    override fun setSelected(selected: Boolean) {
        selectedState = selected
        super.setSelected(selected)
        invalidate()
    }

    override fun setActivated(activated: Boolean) {
        active = activated || isFocused
        super.setActivated(activated)
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // focused/activated 只控制垂直散射光晕；selected 只控制厚实暖黄下划线。
        val nextActive = isActivated || drawableState.any { it == AndroidR.attr.state_focused || it == AndroidR.attr.state_activated }
        val nextSelected = isSelected || drawableState.any { it == AndroidR.attr.state_selected }
        if (nextActive != active || nextSelected != selectedState) {
            active = nextActive
            selectedState = nextSelected
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val lineH = 4f * resources.displayMetrics.density
        if (!active) {
            drawInactiveUnderline(canvas, w, h, lineH)
            return
        }

        val underlineTop = h - lineH
        val glowHeight = (h * VERTICAL_GLOW_HEIGHT_FRACTION).coerceAtMost(underlineTop).coerceAtLeast(lineH)
        val topY = underlineTop - glowHeight
        val bottomY = underlineTop + lineH * 0.35f

        drawVerticalGlow(
            canvas = canvas,
            left = 0f,
            right = w,
            bottomY = bottomY,
            topY = topY,
            cornerRadius = lineH / 2f
        )
        drawUnderline(canvas, w, h, lineH)
    }

    private fun drawInactiveUnderline(canvas: Canvas, width: Float, height: Float, lineH: Float) {
        if (isEnabled && selectedState) {
            drawUnderline(canvas, width, height, lineH)
            return
        }
        if (isEnabled && showIdleLine) {
            val idleLineH = 2f * resources.displayMetrics.density
            canvas.drawRoundRect(0f, height - idleLineH, width, height, idleLineH / 2f, idleLineH / 2f, idleLinePaint)
        }
    }

    private fun drawUnderline(canvas: Canvas, width: Float, height: Float, lineH: Float) {
        canvas.drawRoundRect(0f, height - lineH, width, height, lineH / 2f, lineH / 2f, linePaint)
    }

    private fun drawVerticalGlow(
        canvas: Canvas,
        left: Float,
        right: Float,
        bottomY: Float,
        topY: Float,
        cornerRadius: Float
    ) {
        glowPaint.shader = LinearGradient(
            0f,
            bottomY,
            0f,
            topY,
            intArrayOf(
                withAlpha(Color.rgb(255, 204, 102), 0.72f),
                withAlpha(Color.rgb(255, 220, 140), 0.28f),
                withAlpha(Color.rgb(255, 236, 180), 0f)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(left, topY, right, bottomY, cornerRadius, cornerRadius, glowPaint)
        glowPaint.shader = null
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        return Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
