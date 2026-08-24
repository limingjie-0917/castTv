package com.bd.casttv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        // 子 View 宽度按“自然内容宽度”测量（UNSPECIFIED）：
        // 若沿用 AT_MOST，子容器里 match_parent 的纯装饰 View（如 GlowUnderlineView，
        // 未重写 onMeasure）会走 getDefaultSize 在 AT_MOST 下返回整行最大宽度，
        // 把 wrap_content 的按钮容器撑到整列宽（padding 再小也无效）。
        // 用 UNSPECIFIED 时 getDefaultSize 返回 suggestedMinimumWidth(=0)，
        // 装饰层不再撑宽，容器收敛为文字宽 + padding；高度仍沿用父约束。
        val childWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(0), MeasureSpec.UNSPECIFIED)
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var measuredWidth = paddingLeft + paddingRight

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, childWidthSpec, 0, heightMeasureSpec, totalHeight)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > maxWidth) {
                totalHeight += lineHeight
                measuredWidth = maxOf(measuredWidth, paddingLeft + paddingRight + lineWidth)
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
        totalHeight += lineHeight
        measuredWidth = maxOf(measuredWidth, paddingLeft + paddingRight + lineWidth)
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec), resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingLeft && x - paddingLeft + childWidth > maxWidth) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }
}
