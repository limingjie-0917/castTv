package com.bd.casttv.ui.framework

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.util.ThemeManager
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 非主页统一标题头：
 *   页面标题  [ 随机散布的小新贴纸 x3~5 ]         提示文字
 *   ───────────  分隔线（无焦点=浅灰细线；页面获焦=纯暖黄实色发光线，300ms 属性动画过渡）
 *
 * 使用方式：
 *   val header = PageHeaderView(context)
 *   header.bind(stickerRes = R.drawable.sticker_shinchan, title = "设置")
 *   header.setPageFocused(true/false)
 *
 * 视觉：
 *  - 贴纸：标题文字右侧随机散布 3~5 张小新贴纸，大小/旋转角度/上下位置均随机，贴纸之间保持间距，
 *    避免互相重叠；每次 bind() 都会重新随机排列。
 *  - 标题：22sp 加粗，暖黄色。
 *  - 分隔线：容器 4dp 高，内部 2dp 主线 + 微弱发光，颜色随焦点淡入淡出。
 */
class PageHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val titleView = TextView(context)
    private val stickerArea = FrameLayout(context)
    private val hintView = TextView(context)
    private val headerRow = LinearLayout(context)
    private val dividerContainer = FrameLayout(context)
    private val dividerIdle: View = View(context)
    private val dividerActive: View = View(context)

    private var currentFocused: Boolean = false
    private var animator: ObjectAnimator? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        isFocusableInTouchMode = false

        // 顶部一行：标题 + 随机贴纸散布区 + 右侧提示
        headerRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            background = headerBackgroundDrawable()
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(2f).toInt())
        }

        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(WARM)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            titleView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )

        // 随机贴纸散布区：位于标题文字右侧，不裁切，允许贴纸溢出重叠
        stickerArea.apply {
            clipChildren = false
            clipToPadding = false
        }
        headerRow.addView(
            stickerArea,
            LayoutParams(dp(STICKER_AREA_WIDTH_DP).toInt(), dp(STICKER_AREA_HEIGHT_DP).toInt(), 1f).apply {
                marginStart = dp(4f).toInt()
            }
        )

        // 右侧提示文字
        hintView.apply {
            text = "遥控器方向键【下】进入页面"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(WARM)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.BOTTOM or Gravity.END
            includeFontPadding = false
            setPadding(0, 0, 0, dp(1f).toInt())
        }
        headerRow.addView(
            hintView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(8f).toInt()
            }
        )

        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 分隔线容器：给发光留出上方空间
        dividerContainer.apply {
            clipChildren = false
            clipToPadding = false
        }
        addView(
            dividerContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(4f).toInt()).apply {
                // 标题/提示文案 与 分隔线之间的间距：进一步收紧，让分隔线更靠近标题栏主体。
                topMargin = 0
            }
        )

        // 底层：浅灰细线（idle）
        dividerIdle.background = idleDrawable()
        dividerContainer.addView(
            dividerIdle,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1.5f).toInt(), Gravity.BOTTOM)
        )

        // 上层：纯暖黄实色发光线（active）——默认透明，随焦点变化动画显现
        dividerActive.background = activeDrawable()
        dividerActive.alpha = 0f
        dividerContainer.addView(
            dividerActive,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(6f).toInt(), Gravity.BOTTOM)
        )
    }

    /**
     * 绑定标题，并在标题右侧随机散布 3~5 张小新贴纸（模拟真实贴上去的效果）。
     * stickerRes 参数保留用于兼容旧调用签名，不再作为左侧固定图标使用。
     */
    fun bind(@Suppress("UNUSED_PARAMETER") stickerRes: Int, title: CharSequence) {
        titleView.text = title
        if (title.toString() == "设置") {
            renderExitRectSticker()
        } else {
            renderRandomStickers(EXIT_CIRCLE_STICKER_POOL)
        }
    }

    fun refreshTheme() {
        headerRow.background = headerBackgroundDrawable()
        dividerIdle.background = idleDrawable()
        dividerActive.background = activeDrawable()
    }

    /** 设置页标题栏使用退出应用弹窗顶部同款长方形贴纸。 */
    private fun renderExitRectSticker() {
        stickerArea.removeAllViews()
        val iv = ClippedImageView(context).apply {
            setCircle(false)
            setCornerRadius(dp(10f))
            setImageResource(R.drawable.sticker_squad_wall)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_rounded_rect_border)
            alpha = 0.92f
            rotation = 0f
        }
        val width = dp(96f).toInt()
        val height = dp(27f).toInt()
        val left = dp(10f).toInt()
        val top = ((dp(STICKER_AREA_HEIGHT_DP) - height) / 2f).toInt()
        stickerArea.addView(
            iv,
            FrameLayout.LayoutParams(width, height).apply {
                leftMargin = left
                topMargin = top
            }
        )
    }

    /** 每次调用都会重新随机挑选贴纸数量（3~5张）、大小、旋转角度与位置，模拟真实贴纸的随意感。 */
    private fun renderRandomStickers(stickerPool: List<Int>) {
        stickerArea.removeAllViews()
        val areaWidthPx = dp(STICKER_AREA_WIDTH_DP)
        val areaHeightPx = dp(STICKER_AREA_HEIGHT_DP)
        val count = Random.nextInt(3, 6) // 3~5 张
        val pool = stickerPool.toMutableList().also { it.shuffle() }
        val slotWidth = areaWidthPx / count
        val placedStickers = mutableListOf<StickerPlacement>()
        for (i in 0 until count) {
            val res = pool[i % pool.size]
            val sizePx = dp(Random.nextInt(MIN_STICKER_DP, MAX_STICKER_DP + 1).toFloat())
            val iv = ClippedImageView(context).apply {
                setCircle(true)
                setImageResource(res)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
            }
            // 随机旋转角度 ±5°~15°
            val angle = Random.nextInt(5, 16).toFloat() * if (Random.nextBoolean()) 1f else -1f
            // 按槽位分布 + 多次随机尝试，命中已有贴纸碰撞时重新取点，确保视觉上完全分开
            val placement = findNonOverlappingPlacement(
                areaWidthPx = areaWidthPx,
                areaHeightPx = areaHeightPx,
                slotWidth = slotWidth,
                index = i,
                sizePx = sizePx,
                rotation = angle,
                placed = placedStickers
            )
            // 受标题栏可用空间限制，极端随机失败时跳过当前贴纸，优先保证已展示贴纸完全不重叠
            if (!placement.placeable) {
                continue
            }
            placedStickers.add(placement)
            stickerArea.addView(
                iv,
                FrameLayout.LayoutParams(sizePx.toInt(), sizePx.toInt()).apply {
                    leftMargin = placement.x.toInt()
                    topMargin = placement.y.toInt()
                }
            )
            // 贴纸旋转出现动效，出现顺序略有先后，更像逐个贴上去
            iv.rotation = angle * 2.2f
            iv.alpha = 0f
            iv.scaleX = 0.4f
            iv.scaleY = 0.4f
            iv.animate()
                .rotation(angle)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(i * 70L)
                .setDuration(360)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
                .start()
        }
    }

    private data class StickerPlacement(
        val x: Float,
        val y: Float,
        val boundsWidth: Float,
        val boundsHeight: Float,
        val placeable: Boolean = true
    ) {
        val left: Float = x
        val top: Float = y
        val right: Float = x + boundsWidth
        val bottom: Float = y + boundsHeight
    }

    private fun findNonOverlappingPlacement(
        areaWidthPx: Float,
        areaHeightPx: Float,
        slotWidth: Float,
        index: Int,
        sizePx: Float,
        rotation: Float,
        placed: List<StickerPlacement>
    ): StickerPlacement {
        val rotationRadians = Math.toRadians(kotlin.math.abs(rotation).toDouble())
        val rotatedBounds = (sizePx * (cos(rotationRadians) + sin(rotationRadians))).toFloat()
        val boundsSize = rotatedBounds + dp(STICKER_SAFE_GAP_DP)
        val maxX = (areaWidthPx - boundsSize).coerceAtLeast(0f)
        val maxY = (areaHeightPx - boundsSize).coerceAtLeast(0f)
        repeat(STICKER_PLACEMENT_MAX_ATTEMPTS) {
            val baseX = slotWidth * index
            val jitterX = (Random.nextFloat() - 0.5f) * slotWidth * 0.55f
            val boundsX = (baseX + jitterX).coerceIn(0f, maxX)
            val boundsY = if (maxY > 0f) Random.nextFloat() * maxY else 0f
            val candidate = StickerPlacement(
                x = boundsX + (boundsSize - sizePx) / 2f,
                y = boundsY + (boundsSize - sizePx) / 2f,
                boundsWidth = boundsSize,
                boundsHeight = boundsSize
            )
            if (placed.none { candidate.overlaps(it) }) {
                return candidate
            }
        }

        // 极端随机失败时按顺序寻找第一个可用网格点，避免回退到重叠摆放
        val step = (boundsSize / 2f).coerceAtLeast(dp(2f))
        var y = 0f
        while (y <= maxY) {
            var x = 0f
            while (x <= maxX) {
                val candidate = StickerPlacement(
                    x = x + (boundsSize - sizePx) / 2f,
                    y = y + (boundsSize - sizePx) / 2f,
                    boundsWidth = boundsSize,
                    boundsHeight = boundsSize
                )
                if (placed.none { candidate.overlaps(it) }) {
                    return candidate
                }
                x += step
            }
            y += step
        }

        return StickerPlacement(
            x = 0f,
            y = 0f,
            boundsWidth = boundsSize,
            boundsHeight = boundsSize,
            placeable = false
        )
    }

    private fun StickerPlacement.overlaps(other: StickerPlacement): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    /**
     * 通知焦点状态变化：true=页面内有焦点，激活渐变发光；false=浅灰细线。
     * 变化采用 300ms 的 alpha 属性动画过渡。
     */
    fun setPageFocused(focused: Boolean) {
        if (focused == currentFocused) return
        currentFocused = focused
        animator?.cancel()
        val target = if (focused) 1f else 0f
        animator = ObjectAnimator.ofFloat(dividerActive, "alpha", dividerActive.alpha, target).apply {
            duration = 300L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun headerBackgroundDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        ThemeManager.currentPalette(context).pageHeaderGradient
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9f)
    }

    /** 浅灰细线：不足以显眼但保持存在感。 */
    private fun idleDrawable(): GradientDrawable {
        val palette = ThemeManager.currentPalette(context)
        val tail = palette.pageHeaderGradient.lastOrNull() ?: Color.rgb(22, 54, 111)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(120, Color.red(tail), Color.green(tail), Color.blue(tail)))
            cornerRadius = dp(1f)
        }
    }

    /**
     * 焦点态：纯暖黄实色，底部 4dp 主线 + 上方 alpha 更低的 6dp 发光层。
     * 通过 LayerDrawable 叠一层更高的纯色暖黄半透明层，模拟柔和上散光晕。
     */
    private fun activeDrawable(): LayerDrawable {
        val mainLine = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(255, Color.red(WARM), Color.green(WARM), Color.blue(WARM)))
            cornerRadius = dp(1f)
        }
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(80, Color.red(WARM), Color.green(WARM), Color.blue(WARM)))
            cornerRadius = dp(2f)
        }
        val layers = LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(glow, mainLine))
        // glow 高 6dp，主线贴底保持 4dp。
        layers.setLayerInset(0, 0, 0, 0, 0)
        layers.setLayerInset(1, 0, dp(2f).toInt(), 0, 0)
        return layers
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
        private val ROYAL_BLUE = Color.rgb(46, 99, 196)
        private val ROYAL_BLUE_DARK = Color.rgb(33, 73, 143)
        private val ROYAL_BLUE_DEEP = Color.rgb(22, 54, 111)
        // 标题右侧随机贴纸散布区域尺寸
        private const val STICKER_AREA_WIDTH_DP = 160f
        private const val STICKER_AREA_HEIGHT_DP = 48f
        // 每张贴纸随机大小范围（dp）
        private const val MIN_STICKER_DP = 26
        private const val MAX_STICKER_DP = 40
        private const val STICKER_SAFE_GAP_DP = 2f
        private const val STICKER_PLACEMENT_MAX_ATTEMPTS = 80
        // 退出应用弹窗底部同款 5 张圆形贴纸；除「设置」页外，页面标题栏统一从这里随机取用。
        private val EXIT_CIRCLE_STICKER_POOL = listOf(
            R.drawable.sticker_bochan,
            R.drawable.sticker_nene_shiro,
            R.drawable.sticker_masao,
            R.drawable.sticker_shinchan,
            R.drawable.sticker_kazama
        )
    }
}
