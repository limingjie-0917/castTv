package com.bd.casttv.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import com.bd.casttv.R
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 缩略图工具：负责截图（PixelCopy）、目录管理、异步加载本地缩略图到 ImageView。
 *
 * 采用轻量实现（BitmapFactory + inSampleSize + LruCache + 单线程解码），
 * 覆盖 Coil 在本项目内的核心需求（本地文件 → ImageView），无需引入外部依赖，
 * 兼容离线构建。
 */
object Thumbnails {

    private const val TAG = "Thumbnails"

    /** 目标缩略图尺寸（16:9），与列表项 120x68dp 相匹配。 */
    const val TARGET_WIDTH = 320
    const val TARGET_HEIGHT = 180
    private const val JPEG_QUALITY = 80

    /** 历史记录缩略图目录（临时缓存，App 卸载 / 清理即消失）。 */
    fun historyDir(cacheDir: File): File =
        File(cacheDir, "thumbnails_history").apply { if (!exists()) mkdirs() }

    /** 收藏缩略图目录（持久化，App 沙盒空间）。 */
    fun favoriteDir(filesDir: File): File =
        File(filesDir, "thumbnails_favorites").apply { if (!exists()) mkdirs() }

    /**
     * 根据 URI 生成收藏截图文件路径（同一 URI 复用同一文件，避免重复截图）。
     */
    fun favoriteFileFor(filesDir: File, uri: String): File {
        val hash = md5Hex(uri).take(24)
        return File(favoriteDir(filesDir), "thumb_${hash}.jpg")
    }

    /** 生成一个临时历史截图文件（每次播放独立文件，避免并发覆盖）。 */
    fun newHistoryFile(cacheDir: File): File {
        val name = "hist_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}.jpg"
        return File(historyDir(cacheDir), name)
    }

    // ------------------------------------------------------------------
    // 目录大小 / 清理
    // ------------------------------------------------------------------

    /** 计算指定目录下所有文件的字节数。 */
    fun folderSize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    /** 删除目录下所有文件（保留目录本身）。返回删除条数。 */
    fun clearFolder(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var n = 0
        dir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        memoryCache.evictAll()
        return n
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 0.01) String.format("%.0f KB", bytes / 1024.0)
        else String.format("%.2f MB", mb)
    }

    // ------------------------------------------------------------------
    // PixelCopy 截图（API 26+），失败则返回 null
    // ------------------------------------------------------------------

    /**
     * 从 [playerView] 内部截取当前画面到 Bitmap。回调在主线程执行。
     *
     * 优先按渲染面类型选择路径：
     *  - 找到 TextureView：直接 [TextureView.getBitmap]，兼容性最好，TV 端可稳定生成缩略图。
     *  - 找到 SurfaceView：走 PixelCopy（API 26+），受厂商 Overlay 影响可能黑图，
     *    结果通过 [isMostlyBlack] 过滤。
     *  - 都没找到 / 不满足条件：回调 null，交由调用方 fallback（例如 MMR 抽帧）。
     *
     * 结果 Bitmap 已经按 [TARGET_WIDTH]x[TARGET_HEIGHT] 缩放。
     */
    @MainThread
    fun captureFromPlayerView(playerView: View, onResult: (Bitmap?) -> Unit) {
        // 1) TextureView：任何 API 都支持，直接同步取像素。
        val textureView = findTextureView(playerView)
        if (textureView != null && textureView.isAvailable) {
            try {
                val bmp = textureView.getBitmap(TARGET_WIDTH, TARGET_HEIGHT)
                if (bmp != null) {
                    if (isMostlyBlack(bmp)) {
                        Log.w(TAG, "TextureView.getBitmap produced mostly black thumbnail; ignore it")
                        if (!bmp.isRecycled) bmp.recycle()
                        onResult(null)
                    } else {
                        onResult(bmp)
                    }
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TextureView.getBitmap failed: ${t.message}")
            }
        }

        // 2) SurfaceView：走 PixelCopy（API 26+）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onResult(null); return
        }
        val surfaceView = findSurfaceView(playerView)
        if (surfaceView == null || !surfaceView.holder.surface.isValid) {
            onResult(null); return
        }
        try {
            capturePixelCopy(surfaceView, onResult)
        } catch (t: Throwable) {
            Log.w(TAG, "captureFromPlayerView failed: ${t.message}")
            onResult(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun capturePixelCopy(surfaceView: SurfaceView, onResult: (Bitmap?) -> Unit) {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) { onResult(null); return }
        // 直接截取实际尺寸，随后缩放到目标 320x180，避免长宽比问题。
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val handler = ensureCaptureHandler()
        PixelCopy.request(surfaceView, bmp, { copyResult ->
            surfaceView.post {
                if (copyResult == PixelCopy.SUCCESS) {
                    val scaled = try {
                        Bitmap.createScaledBitmap(bmp, TARGET_WIDTH, TARGET_HEIGHT, true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "scale failed: ${t.message}")
                        null
                    } finally {
                        if (bmp.isRecycled.not()) bmp.recycle()
                    }
                    if (scaled != null && isMostlyBlack(scaled)) {
                        Log.w(TAG, "PixelCopy produced mostly black thumbnail; ignore it")
                        if (scaled.isRecycled.not()) scaled.recycle()
                        onResult(null)
                    } else {
                        onResult(scaled)
                    }
                } else {
                    if (bmp.isRecycled.not()) bmp.recycle()
                    Log.w(TAG, "PixelCopy failed code=$copyResult")
                    onResult(null)
                }
            }
        }, handler)
    }

    /** 递归查找 View 树内的第一个 SurfaceView。 */
    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                val found = findSurfaceView(c)
                if (found != null) return found
            }
        }
        return null
    }

    /** 递归查找 View 树内的第一个 TextureView。 */
    private fun findTextureView(root: View): TextureView? {
        if (root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                val found = findTextureView(c)
                if (found != null) return found
            }
        }
        return null
    }

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Synchronized
    private fun ensureCaptureHandler(): Handler {
        val h = captureHandler
        if (h != null) return h
        val t = HandlerThread("thumb-pixelcopy").apply { start() }
        captureThread = t
        val nh = Handler(t.looper)
        captureHandler = nh
        return nh
    }

    // ------------------------------------------------------------------
    // MediaMetadataRetriever 抽帧（收藏 / 历史默认取第 3 秒）
    // ------------------------------------------------------------------

    private const val FRAME_AT_3S_US = 3_000_000L

    /**
     * 异步从视频源 [uri] 提取第 3 秒画面帧并保存到 [file]。
     * 成功后回调 JPEG 文件绝对路径；失败回调 null。PixelCopy 截图逻辑仍保留为播放画面兜底。
     */
    @MainThread
    fun extractThirdSecondFrameToFile(uri: String, file: File, onResult: (String?) -> Unit) {
        if (uri.isBlank()) {
            onResult(null)
            return
        }
        decodeExecutor.execute {
            val path = extractFrameAtUs(uri, FRAME_AT_3S_US, file)
            mainHandler.post { onResult(path) }
        }
    }

    /** MMR 兜底最长耗时。TV 固件对网络源 seek 支持很弱，超时后强制放弃，避免长时间阻塞。 */
    private const val MMR_TIMEOUT_MS = 8_000L

    @WorkerThread
    private fun extractFrameAtUs(uri: String, timeUs: Long, file: File): String? {
        // TV 端 MMR 常常在 setDataSource / getFrameAtTime 内部长时间阻塞。
        // 用独立线程执行抽帧，本方法所在的抽帧线程 join 超时后放弃并 release retriever，
        // 让阻塞在 native 层的调用尽快返回，避免 ANR / 长期占用 decodeExecutor。
        val retriever = MediaMetadataRetriever()
        val holder = arrayOfNulls<String>(1)
        val worker = Thread({
            try {
                if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
                    retriever.setDataSource(uri, mapOf("User-Agent" to "CastTV/1.0 (Linux; Android)"))
                } else {
                    retriever.setDataSource(uri)
                }
                val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw != null) {
                    val scaled = try {
                        Bitmap.createScaledBitmap(raw, TARGET_WIDTH, TARGET_HEIGHT, true)
                    } finally {
                        if (!raw.isRecycled) raw.recycle()
                    }
                    if (isMostlyBlack(scaled)) {
                        Log.w(TAG, "MediaMetadataRetriever produced mostly black thumbnail; ignore it")
                        if (!scaled.isRecycled) scaled.recycle()
                    } else {
                        holder[0] = saveJpeg(scaled, file)
                        if (!scaled.isRecycled) scaled.recycle()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "extractFrameAtUs failed: ${t.message}")
            }
        }, "thumb-mmr-worker").apply { isDaemon = true }
        worker.start()
        return try {
            worker.join(MMR_TIMEOUT_MS)
            if (worker.isAlive) {
                Log.w(TAG, "extractFrameAtUs timeout after ${MMR_TIMEOUT_MS}ms uri=$uri; release retriever")
                // 主动 release，通常会让阻塞在 native 中的调用尽快返回；worker 是 daemon 线程，
                // 即便无法唤醒也不阻塞进程退出。
                try { retriever.release() } catch (_: Throwable) {}
                null
            } else {
                holder[0]
            }
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------
    // 投屏元数据封面图下载（DIDL-Lite / upnp:albumArtURI）
    // ------------------------------------------------------------------

    /** 封面图下载：连接 / 读取超时。 */
    private const val ARTWORK_TIMEOUT_MS = 5_000
    /** 封面图最大下载体积，超过则丢弃，避免 TV 上拉超大图长时间阻塞。 */
    private const val ARTWORK_MAX_BYTES = 2 * 1024 * 1024
    /** 封面图下载 UA。 */
    private const val ARTWORK_UA = "CastTV/1.0"

    /** 历史封面图缓存目录（cacheDir，兼容旧调用；系统清理 cache 时会被清掉）。 */
    fun artworkDir(cacheDir: File): File =
        File(cacheDir, "thumbnails_artwork").apply { if (!exists()) mkdirs() }

    /**
     * 历史 / 收藏封面图持久化目录（filesDir，App 沙盒空间）。
     * 放在 filesDir 而非 cacheDir，低存储设备被系统清理 cache 时封面依然可用。
     */
    fun artworkDirPersistent(filesDir: File): File =
        File(filesDir, "thumbnails_artwork").apply { if (!exists()) mkdirs() }

    /** 在指定封面目录 [dir] 下根据封面 URL 生成稳定的本地文件路径（同一 URL 复用同一文件）。 */
    fun artworkFileFor(dir: File, artworkUrl: String): File {
        val hash = md5Hex(artworkUrl).take(24)
        return File(dir, "art_${hash}.jpg")
    }

    /**
     * 异步下载 [artworkUrl] 到 [file]（JPEG）。
     * - 超时：连接 & 读取各 [ARTWORK_TIMEOUT_MS]ms。
     * - 大小：超过 [ARTWORK_MAX_BYTES] 直接丢弃。
     * - 校验：解码为 Bitmap，[isMostlyBlack] 过滤，缩放到 [TARGET_WIDTH]x[TARGET_HEIGHT] 后写盘。
     * - 成功回调本地路径；失败回调 null（主线程回调）。
     */
    @MainThread
    fun downloadArtworkToFile(artworkUrl: String, file: File, onResult: (String?) -> Unit) {
        if (artworkUrl.isBlank()) { onResult(null); return }
        // 已存在且非空：直接复用（同 URI 只下载一次）。
        if (file.exists() && file.length() > 0) {
            onResult(file.absolutePath); return
        }
        decodeExecutor.execute {
            val path = downloadArtworkBlocking(artworkUrl, file)
            mainHandler.post { onResult(path) }
        }
    }

    @WorkerThread
    private fun downloadArtworkBlocking(artworkUrl: String, file: File): String? {
        var conn: HttpURLConnection? = null
        var raw: Bitmap? = null
        try {
            val url = URL(artworkUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = ARTWORK_TIMEOUT_MS
                readTimeout = ARTWORK_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ARTWORK_UA)
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "downloadArtwork http=$code url=$artworkUrl")
                return null
            }
            val declared = conn.contentLength
            if (declared > ARTWORK_MAX_BYTES) {
                Log.w(TAG, "downloadArtwork too big: declared=$declared url=$artworkUrl")
                return null
            }
            val bytes = BufferedInputStream(conn.inputStream).use { input ->
                val buf = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > ARTWORK_MAX_BYTES) {
                        Log.w(TAG, "downloadArtwork exceed limit=$total url=$artworkUrl")
                        return null
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) return null
            raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = try {
                Bitmap.createScaledBitmap(raw, TARGET_WIDTH, TARGET_HEIGHT, true)
            } finally {
                if (!raw.isRecycled) raw.recycle()
            }
            if (isMostlyBlack(scaled)) {
                if (!scaled.isRecycled) scaled.recycle()
                Log.w(TAG, "downloadArtwork produced mostly black bitmap; ignore url=$artworkUrl")
                return null
            }
            val savedPath = saveJpeg(scaled, file)
            if (!scaled.isRecycled) scaled.recycle()
            return savedPath
        } catch (t: Throwable) {
            Log.w(TAG, "downloadArtwork failed url=$artworkUrl: ${t.message}")
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------
    // 保存 Bitmap 到 JPEG
    // ------------------------------------------------------------------

    /** 将 Bitmap 保存为 JPEG（quality=[JPEG_QUALITY]）到指定文件。成功返回文件路径，失败返回 null。 */
    @WorkerThread
    fun saveJpeg(bitmap: Bitmap, file: File): String? {
        return try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }
            file.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "saveJpeg failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 异步加载本地缩略图到 ImageView（Coil 替代）
    // ------------------------------------------------------------------

    // 缩略图解码线程池：单线程会让列表多张图串行排队、出图明显变慢。
    // 改为固定 3 线程的小容量池并发解码（TV SoC 通常 4 核，留一核给主线程/渲染），
    // 显著缩短收藏 3 列网格 / 历史横向列表首次出图的整体等待时间。
    private val decodeExecutor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "thumb-decode").apply {
            isDaemon = true
            // 低于默认优先级，避免与主线程/渲染争抢 CPU，保证滑动优先流畅。
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    /**
     * 缩略图内存缓存：按可用内存的 1/8 分配（此前固定 3MB 偏小，列表来回滑动易被逐出重解码）。
     * 缓存内均为 RGB_565 小图，占用可控。
     */
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L)
            .coerceIn(3L * 1024 * 1024, 16L * 1024 * 1024)
            .toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 每个 ImageView 上挂载最新一次加载的 tag，防止 Recycler 复用时错图。 */
    private val TAG_KEY = R.id.tag_thumb_request

    /**
     * 异步把本地缩略图文件加载到 [target]。
     * - [path] 为 null / 空 / 文件不存在 → 显示 [placeholderRes]（默认兜底图）。
     * - 加载失败 → 显示 [placeholderRes]。
     */
    @MainThread
    fun load(
        target: ImageView,
        path: String?,
        @DrawableRes placeholderRes: Int = R.drawable.ic_thumb_default
    ) {
        val requestTag = path ?: "@placeholder"
        target.setTag(TAG_KEY, requestTag)
        // 先显示占位图，避免闪现旧图
        target.setImageResource(placeholderRes)
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.exists() || file.length() <= 0) return

        // 命中缓存：缓存内的位图在存入前（load 的解码回调、PixelCopy/MMR 抽帧）
        // 均已通过 isMostlyBlack 校验，此处无需在主线程再次逐像素扫描，直接使用，
        // 避免绑定/滑动时在主线程重复执行 getPixel 采样。
        val cached = memoryCache.get(path)
        if (cached != null && !cached.isRecycled) {
            target.setImageBitmap(cached)
            return
        }

        decodeExecutor.execute {
            val bmp = decodeSampled(file, TARGET_WIDTH, TARGET_HEIGHT)
            mainHandler.post {
                // Recycler 已复用则丢弃结果
                if (target.getTag(TAG_KEY) != requestTag) return@post
                if (bmp != null && !isMostlyBlack(bmp)) {
                    memoryCache.put(path, bmp)
                    target.setImageBitmap(bmp)
                } else {
                    target.setImageResource(placeholderRes)
                }
            }
        }
    }

    @WorkerThread
    private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? = try {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opt)
        val sample = calculateInSampleSize(opt.outWidth, opt.outHeight, reqW, reqH)
        val opt2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // 缩略图节省内存
        }
        BitmapFactory.decodeFile(file.absolutePath, opt2)
    } catch (t: Throwable) {
        Log.w(TAG, "decode failed: ${t.message}")
        null
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / sample >= reqW && halfH / sample >= reqH) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return true
            var dark = 0
            var total = 0
            val stepX = (w / 24).coerceAtLeast(1)
            val stepY = (h / 24).coerceAtLeast(1)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r + g + b < 36) dark++
                    total++
                    x += stepX
                }
                y += stepY
            }
            total > 0 && dark.toFloat() / total.toFloat() > 0.92f
        } catch (_: Throwable) {
            false
        }
    }

    private fun md5Hex(text: String): String = try {
        val d = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        d.joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        // 兜底：hash 值即可，安全性无关
        Integer.toHexString(text.hashCode())
    }
}
