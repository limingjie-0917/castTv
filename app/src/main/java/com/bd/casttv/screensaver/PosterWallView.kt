package com.bd.casttv.screensaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.displayThumbPath
import com.bd.casttv.settings.Settings
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 收藏缩略图海报墙自绘视图。
 * 从系统屏保（[PosterDreamService]）抽出为同 module 可复用组件，
 * App 内空闲触发（[PosterWallActivity]）与系统屏保共用同一套绘制逻辑。
 */
internal class PosterWallView(context: android.content.Context) : View(context) {
    private companion object {
        val CARD_GRADIENT_CENTER = Color.rgb(30, 78, 216) // 宝蓝
        val CARD_GRADIENT_EDGE = Color.rgb(165, 220, 255) // 浅蓝
        val CARD_BORDER = Color.argb(190, 210, 235, 255)

        val POSTER_PLACEHOLDER_CENTER = Color.rgb(52, 125, 245)
        val POSTER_PLACEHOLDER_EDGE = Color.rgb(190, 235, 255)

        private const val MAX_POSTER_COUNT = 80
        private const val MIN_PLACEHOLDER_COUNT = 12
        private const val GRADIENT_CACHE_LIMIT = 48

        private val gradientCache = object : LinkedHashMap<String, Bitmap>(GRADIENT_CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
                val shouldRemove = size > GRADIENT_CACHE_LIMIT
                if (shouldRemove) eldest?.value?.recycle()
                return shouldRemove
            }
        }
    }

    private data class Poster(val title: String, val bitmap: Bitmap?)
    private val settings = Settings(context)
    // 与视频卡片 Thumbnails.load() 默认兜底图保持一致，避免屏保海报墙无缩略图时出现空白。
    private var defaultPosterBitmap: Bitmap? = null
    // ANR 优化（v1.1.131）：海报数据（读取收藏 JSON + 解码多张缩略图）改为后台线程加载，
    // 避免在主线程构造 View 时同步磁盘 IO / Bitmap 解码导致启动卡死。
    // 先用占位列表（>=12 项）保证 onDraw 的取模逻辑安全，加载完成后替换并重绘。
    @Volatile private var posters: List<Poster> = List(MIN_PLACEHOLDER_COUNT) { Poster("加载中…", null) }
    @Volatile private var loadGeneration = 0
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val gradientRenderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(243, 231, 208)
        textSize = 22f
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 2f, Color.argb(235, 0, 0, 0))
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(166, 106, 63)
        textSize = 28f
        isFakeBoldText = true
    }
    private var running = false
    private var resumeWhenVisible = false
    private var startMs = 0L
    private var bgBitmap: Bitmap? = null
    private var bgBitmapW = 0
    private var bgBitmapH = 0
    private var lastDecodeMaxEdge = 0
    private val frameRunnable = Runnable {
        if (running && isAttachedToWindow && visibility == VISIBLE) invalidate()
    }

    fun start() {
        running = true
        resumeWhenVisible = false
        startMs = System.currentTimeMillis()
        ensurePosterLoad()
        invalidate()
    }

    fun stop() {
        pauseAnimation(keepResumeFlag = false)
    }

    private fun pauseAnimation(keepResumeFlag: Boolean) {
        resumeWhenVisible = keepResumeFlag && running
        running = false
        removeCallbacks(frameRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            if (resumeWhenVisible) start() else ensurePosterLoad()
        }
    }

    override fun onDetachedFromWindow() {
        pauseAnimation(keepResumeFlag = true)
        loadGeneration++
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (resumeWhenVisible) start() else if (running) invalidate()
            ensurePosterLoad()
        } else if (visibility == GONE) {
            pauseAnimation(keepResumeFlag = true)
            loadGeneration++
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgBitmap?.recycle()
        bgBitmap = null
        bgBitmapW = 0
        bgBitmapH = 0
        ensurePosterLoad(force = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        canvas.drawBitmap(ensureBackgroundBitmap(w.toInt(), h.toInt()), 0f, 0f, bitmapPaint)
        when (settings.screensaverStyle) {
            Settings.SCREENSAVER_WATERFALL -> drawWaterfall(canvas, w, h)
            Settings.SCREENSAVER_MOSAIC -> drawMosaic(canvas, w, h)
            else -> drawTracks(canvas, w, h)
        }
        if (running && isAttachedToWindow && visibility == VISIBLE) postOnAnimation(frameRunnable)
    }

    private fun drawTracks(c: Canvas, w: Float, h: Float) {
        val t = (System.currentTimeMillis() - startMs) / 1000f
        val rows = 4
        val cardW = w / 4.2f
        val cardH = cardW * 0.62f
        val gap = 28f
        for (r in 0 until rows) {
            val y = 58f + r * (cardH + 34f)
            val speed = (18f + r * 6f) * if (r % 2 == 0) -1f else 1f
            val rowLen = posters.size * (cardW + gap)
            val base = ((t * speed) % rowLen)
            for (i in posters.indices) {
                var x = i * (cardW + gap) + base - cardW
                while (x < -cardW) x += rowLen
                while (x > w) x -= rowLen
                drawPoster(c, posters[(i + r * 3) % posters.size], x, y, cardW, cardH)
            }
        }
    }

    private fun drawWaterfall(c: Canvas, w: Float, h: Float) {
        val t = (System.currentTimeMillis() - startMs) / 1000f
        val cols = if (w > 1500f) 4 else 3
        val gap = 24f
        val cardW = (w - gap * (cols + 1)) / cols
        for (col in 0 until cols) {
            val cardH = cardW * (0.58f + (col % 2) * 0.16f)
            val stride = cardH + gap
            val speed = 20f + col * 5f
            val total = posters.size * stride
            for (i in posters.indices) {
                var y = i * stride - (t * speed % total) - cardH
                while (y < -cardH) y += total
                val x = gap + col * (cardW + gap)
                drawPoster(c, posters[(i + col * 2) % posters.size], x, y, cardW, cardH)
            }
        }
    }

    private fun drawMosaic(c: Canvas, w: Float, h: Float) {
        val t = (System.currentTimeMillis() - startMs) / 1000f
        val driftX = (t * 9f) % 80f
        val driftY = (t * 6f) % 60f
        val unit = w / 5f
        for (i in 0 until max(18, posters.size)) {
            val p = posters[i % posters.size]
            val scale = if (i % 5 == 0) 1.45f else if (i % 3 == 0) 1.15f else 0.95f
            val cw = unit * scale
            val ch = cw * 0.68f
            val x = ((i * 137) % (w.toInt() + unit.toInt())).toFloat() - unit / 2 - driftX
            val y = ((i * 91) % (h.toInt() + unit.toInt())).toFloat() - unit / 2 - driftY
            drawPoster(c, p, x, y, cw, ch)
        }
    }

    private fun drawPoster(c: Canvas, p: Poster, x: Float, y: Float, w: Float, h: Float) {
        val rect = RectF(x, y, x + w, y + h)

        // 卡片配色：宝蓝色从中心向浅蓝渐变。
        c.drawBitmap(ensureRadialGradientBitmap(w, h, 22f, CARD_GRADIENT_CENTER, CARD_GRADIENT_EDGE, "card"), null, rect, bitmapPaint)

        cardPaint.shader = null
        cardPaint.style = Paint.Style.STROKE
        cardPaint.strokeWidth = 1.6f
        cardPaint.color = CARD_BORDER
        c.drawRoundRect(RectF(x + 1.2f, y + 1.2f, x + w - 1.2f, y + h - 1.2f), 21f, 21f, cardPaint)

        cardPaint.style = Paint.Style.FILL
        val inner = RectF(x + 4f, y + 4f, x + w - 4f, y + h - 4f)
        val posterBitmap = p.bitmap ?: defaultPosterBitmap
        if (posterBitmap != null && !posterBitmap.isRecycled) {
            c.drawBitmap(posterBitmap, null, inner, bitmapPaint)
        } else {
            c.drawBitmap(ensureRadialGradientBitmap(inner.width(), inner.height(), 18f, POSTER_PLACEHOLDER_CENTER, POSTER_PLACEHOLDER_EDGE, "placeholder"), null, inner, bitmapPaint)
            c.drawText("🖍", inner.centerX() - 22f, inner.centerY() - 4f, smallPaint)
        }
        cardPaint.shader = LinearGradient(
            0f,
            inner.bottom - 58f,
            0f,
            inner.bottom,
            Color.TRANSPARENT,
            Color.argb(215, 20, 11, 10),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(RectF(inner.left, inner.bottom - 62f, inner.right, inner.bottom), 18f, 18f, cardPaint)
        val text = p.title.ifBlank { "小新的收藏" }.take(18)
        c.drawText(text, inner.left + 16f, inner.bottom - 20f, titlePaint)
    }

    private fun ensurePosterLoad(force: Boolean = false) {
        if (!isAttachedToWindow) return
        if (visibility != VISIBLE) return
        val decodeMaxEdge = targetDecodeMaxEdge()
        if (!force && decodeMaxEdge == lastDecodeMaxEdge && defaultPosterBitmap != null) return
        lastDecodeMaxEdge = decodeMaxEdge
        val generation = ++loadGeneration
        Thread({
            val defaultBitmap = try { decodeResourceSampled(R.drawable.ic_thumb_default, decodeMaxEdge) } catch (_: Throwable) { null }
            val loaded = try { loadPosters(context, decodeMaxEdge) } catch (_: Throwable) { null }
            if (generation == loadGeneration && visibility == VISIBLE && isAttachedToWindow) {
                defaultPosterBitmap = defaultBitmap
                if (loaded != null) posters = loaded
                postInvalidate()
            } else {
                defaultBitmap?.recycle()
                loaded?.forEach { it.bitmap?.recycle() }
            }
        }, "poster-wall-load").apply { isDaemon = true }.start()
    }

    private fun targetDecodeMaxEdge(): Int {
        val vw = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val vh = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        return ceil(sqrt(vw.toDouble() * vw + vh.toDouble() * vh)).toInt().coerceAtLeast(1)
    }

    private fun ensureBackgroundBitmap(w: Int, h: Int): Bitmap {
        bgBitmap?.takeIf { !it.isRecycled && bgBitmapW == w && bgBitmapH == h }?.let { return it }
        bgBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bgGradientPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                Color.rgb(26, 20, 18),
                Color.rgb(14, 10, 9),
                Color.rgb(5, 4, 4)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgGradientPaint)
        bgGradientPaint.shader = null
        bgBitmap = bitmap
        bgBitmapW = w
        bgBitmapH = h
        return bitmap
    }

    private fun ensureRadialGradientBitmap(w: Float, h: Float, radius: Float, centerColor: Int, edgeColor: Int, tag: String): Bitmap {
        val bw = ceil(w).toInt().coerceAtLeast(1)
        val bh = ceil(h).toInt().coerceAtLeast(1)
        val br = ceil(radius).toInt().coerceAtLeast(0)
        val key = "$tag:$bw:$bh:$br:$centerColor:$edgeColor"
        synchronized(gradientCache) {
            gradientCache[key]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        gradientRenderPaint.shader = RadialGradient(
            bw / 2f,
            bh / 2f,
            max(bw.toFloat(), bh.toFloat()),
            intArrayOf(centerColor, edgeColor),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        gradientRenderPaint.style = Paint.Style.FILL
        Canvas(bitmap).drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), radius, radius, gradientRenderPaint)
        gradientRenderPaint.shader = null
        synchronized(gradientCache) { gradientCache[key] = bitmap }
        return bitmap
    }

    private fun loadPosters(context: android.content.Context, targetMaxEdge: Int): List<Poster> {
        val result = mutableListOf<Poster>()
        try {
            FavoritesStore(context).collections().flatMap { it.items }.forEach { item ->
                // 缩略图取值统一走 displayThumbPath()：优先 DLNA 封面，回落本地截图。
                val bmp = item.displayThumbPath()?.let { path -> decodeFileSampled(path, targetMaxEdge) }
                result.add(Poster(item.title.ifBlank { item.uri }, bmp))
            }
        } catch (_: Throwable) {}
        while (result.size < MIN_PLACEHOLDER_COUNT) result.add(Poster("暂无缩略图", null))
        return result.take(MAX_POSTER_COUNT)
    }

    private fun decodeResourceSampled(resId: Int, targetMaxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetMaxEdge)
            inJustDecodeBounds = false
        }
        return BitmapFactory.decodeResource(resources, resId, opts)
    }

    private fun decodeFileSampled(path: String, targetMaxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetMaxEdge)
            inJustDecodeBounds = false
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, targetMaxEdge: Int): Int {
        val maxSrcEdge = max(srcWidth, srcHeight)
        if (maxSrcEdge <= 0 || targetMaxEdge <= 0) return 1
        var sample = 1
        while (maxSrcEdge / (sample * 2) >= targetMaxEdge) sample *= 2
        return sample.coerceAtLeast(1)
    }
}
