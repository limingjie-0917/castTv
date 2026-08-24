package com.bd.casttv.ui.framework

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.bd.casttv.R
import com.bd.casttv.settings.Settings

class BottomIndicatorBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    data class PageItem(val id: String, val title: String, val iconRes: Int)
    private val row = LinearLayout(context)
    private val track = FrameLayout(context)
    private val thumb = View(context)
    private var items = emptyList<PageItem>()
    private var current = 0
    private var scaleValue = Settings(context).indicatorScale
    private var rootFocusActive = true
    private var visibleForRootFocus = true
    private val iconTint = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(WARM, SOFT)
    )

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setPadding(dp(18), dp(4), dp(18), dp(6))
        setBackgroundColor(Color.TRANSPARENT)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            background = null
        }
        row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER; row.isFocusable = false; row.isFocusableInTouchMode = false; row.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        box.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        track.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.argb(70,255,255,255)) }
        track.isFocusable = false
        track.isFocusableInTouchMode = false
        thumb.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(WARM) }
        thumb.isFocusable = false
        thumb.isFocusableInTouchMode = false
        track.addView(thumb, FrameLayout.LayoutParams(1, dp(3)))
        box.addView(track, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(4) })
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        alpha = 1f
        setScale(scaleValue)
    }

    fun bindPages(pages: List<PageItem>) {
        items = pages
        row.removeAllViews()
        pages.forEachIndexed { i, p ->
            row.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                    val selected = i == current
                    addView(ImageView(context).apply {
                        setImageResource(if (p.iconRes != 0) p.iconRes else R.drawable.ic_dock_home)
                        imageTintList = iconTint
                        isSelected = selected
                        isFocusable = false
                        isFocusableInTouchMode = false
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams((dp(22) * scaleValue).toInt(), (dp(22) * scaleValue).toInt()))
                },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
        }
        post { updateThumb(false) }
    }

    fun setCurrentIndex(i: Int, animate: Boolean = true) {
        current = i.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        for (n in 0 until row.childCount) {
            val item = row.getChildAt(n) as? LinearLayout ?: continue
            val selected = n == current
            (item.getChildAt(0) as? ImageView)?.isSelected = selected
        }
        updateThumb(animate)
    }

    fun setRootFocusActive(active: Boolean) {
        rootFocusActive = active
        (getChildAt(0) as? LinearLayout)?.background = null
        animate().cancel()
        if (active) {
            visibleForRootFocus = true
            alpha = 1f
        }
        animate().translationY(0f).setDuration(160).start()
    }
    fun setRootFocusVisible(visible: Boolean) {
        if (visibleForRootFocus == visible && alpha == if (visible) 1f else 0f) return
        visibleForRootFocus = visible
        animate().cancel()
        if (visible) {
            (getChildAt(0) as? LinearLayout)?.background = null
            animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            animate().alpha(0f).translationY(0f).setDuration(200).start()
        }
    }
    fun setScale(scale: Float) { scaleValue = scale.coerceIn(0.7f, 1.6f); minimumWidth = (dp(460) * scaleValue).toInt(); requestLayout() }
    override fun onMeasure(w: Int, h: Int) { val width = ((resources.displayMetrics.widthPixels * 0.5f * scaleValue).toInt()).coerceAtLeast(minimumWidth); super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), h) }
    private fun updateThumb(animate: Boolean) { if (items.isEmpty() || track.width == 0) return; val w = track.width / items.size; val x = (w * current).toFloat(); thumb.layoutParams = (thumb.layoutParams as FrameLayout.LayoutParams).apply { width = w }; if (animate) thumb.animate().translationX(x).setDuration(220).start() else thumb.translationX = x }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    companion object { private val WARM = Color.rgb(245,196,81); private val SOFT = Color.rgb(184,184,184) }
}
