package com.bd.casttv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.bd.casttv.R

/**
 * 零依赖圆角/圆形裁切 ImageView。
 *
 * 通过 [Canvas.clipPath] 在 CPU 侧真正裁掉 bitmap 越界像素，
 * 保证任何设备（含关闭硬件加速合成、老年 GPU 驱动、TV 盒子 Skia 版本差异）
 * 都能出圆形/圆角矩形。不依赖：
 *   - com.google.android.material.imageview.ShapeableImageView（Material 组件包）
 *   - View.setClipToOutline() + Outline.setOval/RoundRect（依赖 GPU 合成器兑现，
 *     且对 android:foreground / scaleType=centerCrop 时的越界像素并不总生效）
 *
 * XML 属性：
 *   app:civIsCircle="true"  → 按最短边一半做圆
 *   app:civIsCircle="false" + app:civCornerRadius="14dp" → 圆角矩形
 */
class ClippedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val path = Path()
    private val rect = RectF()

    // -1 表示按最短边一半 → 圆形；>=0 表示圆角矩形（单位 px）
    private var cornerRadiusPx: Float = -1f
    private var isCircle: Boolean = true

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.ClippedImageView)
            isCircle = ta.getBoolean(R.styleable.ClippedImageView_civIsCircle, true)
            cornerRadiusPx = ta.getDimension(R.styleable.ClippedImageView_civCornerRadius, -1f)
            ta.recycle()
        }
    }

    fun setCircle(circle: Boolean) {
        isCircle = circle
        rebuildPath(width, height)
        invalidate()
    }

    fun setCornerRadius(px: Float) {
        cornerRadiusPx = px
        isCircle = false
        rebuildPath(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath(w, h)
    }

    private fun rebuildPath(w: Int, h: Int) {
        path.reset()
        if (w <= 0 || h <= 0) return
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        if (isCircle) {
            val r = minOf(w, h) / 2f
            path.addCircle(w / 2f, h / 2f, r, Path.Direction.CW)
        } else {
            val r = if (cornerRadiusPx > 0) cornerRadiusPx else 0f
            path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
    }

    override fun draw(canvas: Canvas) {
        if (width == 0 || height == 0) {
            super.draw(canvas)
            return
        }
        val save = canvas.save()
        // clipPath 会同时裁掉子内容 + foreground（AppCompatImageView.draw 内部
        // 会依次绘制 background → onDraw(bitmap) → foreground），
        // 但我们希望 foreground 的白描边"完整"叠在上层。
        // 由于描边 drawable 本身就贴合圆/圆角矩形，即使被同一 path 裁切也不会缺角。
        canvas.clipPath(path)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}
