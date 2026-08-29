package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.cartoon.CartoonStore
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity
import com.bd.casttv.ui.theme.CartoonDesign
import com.bd.casttv.webparse.WebParsePageType
import com.bd.casttv.webparse.WebParseRequestBus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 动画城：云端动画卡片网格（5 列），末尾一张管理卡。
 * 进入时从云端拉取 cartoons 索引并缓存到本地 [CartoonStore]，离线时展示缓存。
 * 点击卡片 → P3 将 WebParsePage 按需下载适配器 + 强制解析。
 * 点击管理卡 → P5 CartoonManagementDialog。
 */
class CartoonCityPage(context: Context) : BasePage(context) {
    override val pageId = "cartoon_city"
    override val pageTitle = "动画城"
    override val pageIconRes = R.drawable.ic_more_cartoon
    override val enablePageScroll = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true

    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private val cartoonStore = CartoonStore(context)
    private val TAG = "CartoonCityPage"

    private val adapter = CartoonAdapter()
    private var grid: RecyclerView? = null

    companion object {
        /** 封面网格列数：4 列，卡片尺寸更舒展、焦点态无重叠。 */
        private const val SPAN_COUNT = 4
        private const val TYPE_CARTOON = 0
        private const val TYPE_MGMT = 1
    }

    /** 空态/加载视图：Premium Media 风格，去掉底板，保留琥珀圆环 loading + 文案胶囊徽。 */
    private val emptyView = EmptyPanel(context)

    private class EmptyPanel(context: Context) : FrameLayout(context) {
        private val accent = CartoonDesign.Palette.ACCENT
        private val accentDim = CartoonDesign.Palette.ACCENT_DIM
        val progress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
            indeterminateDrawable?.setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN)
            visibility = GONE
        }
        val title = TextView(context).apply {
            textSize = CartoonDesign.Type.TITLE_LG
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(ctx = context, 10), 0, dp(ctx = context, 8))
        }
        val detail = TextView(context).apply {
            textSize = CartoonDesign.Type.META
            setTextColor(CartoonDesign.Palette.TEXT_SECONDARY)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setLineSpacing(dp(ctx = context, 3).toFloat(), 1f)
            setPadding(dp(ctx = context, 48), 0, dp(ctx = context, 48), dp(ctx = context, 10))
        }
        val hint = TextView(context).apply {
            textSize = CartoonDesign.Type.TITLE_SM
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CartoonDesign.Palette.BADGE_ACCENT_FG)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            background = CartoonDesign.capsuleBadge(
                context, CartoonDesign.Palette.BADGE_ACCENT_BG,
                Color.argb(220, Color.red(accentDim), Color.green(accentDim), Color.blue(accentDim))
            )
            val ph = dp(ctx = context, 14); val pv = dp(ctx = context, 8)
            setPadding(ph, pv, ph, pv)
        }
        var retryAction: (() -> Unit)? = null

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            clipChildren = false
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val progLp = LinearLayout.LayoutParams(dp(ctx = context, 52), dp(ctx = context, 52)).apply {
                    bottomMargin = dp(ctx = context, 10)
                }
                addView(progress, progLp)
                addView(title, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                addView(detail, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                // OK 重试：用胶囊徽的大小作为容器的焦点块，但外层再包一层以便上下居中
                val wrap = FrameLayout(context)
                wrap.addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                })
                addView(wrap, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx = context, 6)
                })
            }
            addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
            setOnClickListener { retryAction?.invoke() }
            setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    performClick(); true
                } else false
            }
            // Premium Media 风格：去掉背景底板，仅保留圆环 + 文案/胶囊徽
            background = null
            val p = dp(ctx = context, 12)
            setPadding(p, p, p, p)
        }

        /**
         * 设置空态/加载态内容。
         *  - loading=true：顶部显示金黄色圆形进度条，title 显示"正在加载 X…"，detail 可显示 token/连接状态；hint 隐藏
         *  - loading=false：进度条消失，title 是空态/错误标题，detail 显示诊断，hint 显示 OK 重试
         *  - resultCount：成功 N 条时显示"已获取 N 条"的后缀，并在 900ms 后自动隐藏整面板
         *  - autoHideOnResult=true：resultCount 命中时自动隐藏（用于同步成功+下方已有卡片场景，不挡住网格）；
         *                          =false：保持显示（用于"整页为空"场景，面板就是主 UI）
         *  - onAutoHide：自动隐藏时的回调（通常用于把焦点从面板切回网格首卡）；形参放在最后，支持 Kotlin trailing lambda 语法
         */
        fun show(
            loading: Boolean,
            titleText: String,
            detailText: String = "",
            hintText: String = "按 OK 键刷新",
            resultCount: Int? = null,
            autoHideOnResult: Boolean = true,
            onAutoHide: (() -> Unit)? = null
        ) {
            // 修复 loading 不显示的根因：父容器 visibility 此前被 updateEmpty() 置成 GONE，
            // 仅改子 View 可见性不会让父容器重新显示。这里根据语义强制切父容器自身的 visibility。
            //  - loading=true → 必须显示（"同步中"overlay 覆盖在网格上方，WRAP_CONTENT 不会全屏遮挡）
            //  - resultCount 存在且>0 → 先显示"已获取 N 条"横幅，再看 autoHideOnResult 决定是否延迟隐藏
            //  - 其它 loading=false 情况 → 保持显示（错误面板 / 空态是当前页面唯一内容）
            visibility = VISIBLE
            progress.visibility = if (loading) VISIBLE else GONE
            title.text = when {
                resultCount != null && !loading && resultCount > 0 ->
                    "$titleText（已获取 $resultCount 条）"
                else -> titleText
            }
            detail.text = detailText
            detail.visibility = if (detailText.isBlank()) GONE else VISIBLE
            hint.text = hintText
            hint.visibility = if (loading || hintText.isBlank()) GONE else VISIBLE

            removeCallbacks(autoHideRunnable)
            autoHideCallback = onAutoHide
            if (resultCount != null && resultCount > 0 && !loading && autoHideOnResult) {
                postDelayed(autoHideRunnable, 900L)
            }
        }

        private var autoHideCallback: (() -> Unit)? = null
        private val autoHideRunnable = Runnable {
            visibility = GONE
            val cb = autoHideCallback
            autoHideCallback = null
            cb?.invoke()
        }

        fun bindFocusFx() {
            // ponytail: 最短工作 diff —— FocusFxHelper.applyFocusFx 内部会链式合并焦点监听器，
            //           这里只负责 scale+cornerRadius+elevation 的视觉特效；描边粗细跟随焦点态走
            //           FocusFxHelper 自带 elevation shadow，不单独 setStroke 以免覆盖它内部监听器。
            com.bd.casttv.ui.framework.FocusFxHelper.applyFocusFx(
                view = this@EmptyPanel,
                scale = 1.015f,
                cornerRadiusDp = 16
            )
        }

        private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    }

    init {
        val outer = FrameLayout(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val rv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, SPAN_COUNT)
            adapter = this@CartoonCityPage.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipToPadding = false
            // 内边距主要由 outer 承担，RV 自身只留少量上下边距避免首/末行卡到顶/底边
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            addItemDecoration(CartoonItemDecoration(dp(context, 14), dp(context, 18), SPAN_COUNT))
        }
        grid = rv
        outer.addView(rv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        emptyView.retryAction = { loadCartoons() }
        emptyView.bindFocusFx()
        val emptyLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            width = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        }
        outer.addView(emptyView, emptyLp)
        contentContainer.addView(outer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onEnter() {
        super.onEnter()
        Log.i(TAG, "onEnter() fired; contentContainer w=${contentContainer.width} h=${contentContainer.height}")
        loadCartoons()
    }

    override fun refreshTheme() {
        super.refreshTheme()
    }

    private fun loadCartoons() {
        loadJob?.cancel()
        // 协程异常不要被 SupervisorJob 静默吞；任何 Throwable 直接走 loadFailed 显式展示给用户
        loadJob = pageScope.launch(CoroutineExceptionHandler { _, th ->
            Log.e(TAG, "loadCartoons: coroutine crashed", th)
            val reason = "${th.javaClass.simpleName}${th.message?.let { ": $it" }.orEmpty()}"
            runOnUiAnchor { loadFailed(title = "页面加载异常：$reason", detail = "请检查网络或 Gitee 授权状态后重试。\n诊断：$reason") }
        }) {
            val cached = cartoonStore.getCachedCartoons()
            cachedSnapshotLast = cached
            Log.i(TAG, "loadCartoons: start, cached=${cached.size}, token?=${GiteeShareStore.tokenSummary()}")
            val firstItems = cached.map { CartoonItem.Cartoon(it) }
            runOnUiAnchor {
                adapter.submit(firstItems)
                updateEmpty(cached.isEmpty())
                if (cached.isNotEmpty()) {
                    emptyView.show(
                        loading = true,
                        titleText = "正在同步云端最新…",
                        detailText = "本地缓存有 ${cached.size} 部；令牌状态：${GiteeShareStore.tokenSummary()}",
                        hintText = ""
                    )
                } else {
                    emptyView.show(
                        loading = true,
                        titleText = "正在加载动画城…",
                        detailText = "连接云端共享仓库中…\n令牌状态：${GiteeShareStore.tokenSummary()}",
                        hintText = ""
                    )
                }
            }

            // 再从云端拉取最新索引
            val result = withContext(Dispatchers.IO) {
                runCatching { GiteeShareStore.fetchCartoonsIndex() }
                    .getOrElse { t -> GiteeApi.ApiResult.Error("加载异常: ${t.javaClass.simpleName}: ${t.message ?: "无详情"}") }
            }
            Log.i(TAG, "loadCartoons: cloud result=${result.javaClass.simpleName}, " + when (result) {
                is GiteeApi.ApiResult.Success -> "items=${result.value.size}, titles=${result.value.take(5).map { it.title }}"
                is GiteeApi.ApiResult.Error -> "message=${result.message.take(160)}"
                is GiteeApi.ApiResult.NotFound -> "NotFound"
            })
            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    val cartoons = result.value
                    runCatching { cartoonStore.cacheCartoons(cartoons) }
                        .onFailure { Log.w(TAG, "cacheCartoons failed", it) }
                    cachedSnapshotLast = cartoons
                    // 修复云端脏数据：异步巡检不自愈 UI
                    if (cartoons.isNotEmpty()) {
                        pageScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, th ->
                            Log.w(TAG, "cleanupCloudDirtyCartoons crashed: ${th.message}")
                        }) {
                            cleanupCloudDirtyCartoons(cartoons)
                        }
                    }
                    val nextItems = cartoons.map { CartoonItem.Cartoon(it) } +
                            if (cartoons.isNotEmpty()) listOf(CartoonItem.Management) else emptyList()
                    runOnUiAnchor {
                        adapter.submit(nextItems)
                        updateEmpty(cartoons.isEmpty())
                        if (cartoons.isEmpty()) {
                            emptyView.show(
                                loading = false,
                                titleText = "动画城还是空的",
                                detailText = "云端与本地都暂无动画卡片。\n打开网页解析播放页 → 解析成功后点「添加到动画城」。\n当前云端返回 0 条（本地缓存也是空）。",
                                hintText = "按 OK 键重新加载",
                                autoHideOnResult = false
                            )
                        } else {
                            emptyView.show(
                                loading = false,
                                titleText = "加载完成",
                                detailText = buildString {
                                    append("从云端拿到 ").append(cartoons.size).append(" 部动画：\n")
                                    cartoons.take(3).forEachIndexed { i, c ->
                                        append("  ").append(i + 1).append(". ").append(c.title)
                                            .append("（").append(c.episodeCount).append("集）\n")
                                    }
                                    if (cartoons.size > 3) append("  …另 ").append(cartoons.size - 3).append(" 部未列示")
                                }.trim(),
                                hintText = "",
                                resultCount = cartoons.size,
                                autoHideOnResult = true
                            ) {
                                // 900ms 后自动隐藏面板：把焦点从面板交给第 0 张卡片，避免用户还要手动按方向键
                                val rv = grid ?: return@show
                                val target = (rv.layoutManager as? GridLayoutManager)
                                    ?.findViewByPosition(0)
                                (target ?: rv).requestFocus()
                            }
                        }
                        Log.i(TAG, "loadCartoons: applied items=${adapter.itemCount}, empty=${cartoons.isEmpty()}")
                    }
                }
                is GiteeApi.ApiResult.NotFound,
                is GiteeApi.ApiResult.Error -> {
                    val keep = cached.isNotEmpty()
                    val nextItems = if (keep) cached.map { CartoonItem.Cartoon(it) } +
                            listOf(CartoonItem.Management) else emptyList()
                    val msg = (result as? GiteeApi.ApiResult.Error)?.message.orEmpty()
                    runOnUiAnchor {
                        if (keep) adapter.submit(nextItems)
                        updateEmpty(cached.isEmpty())
                        if (cached.isEmpty()) {
                            val head = if (result is GiteeApi.ApiResult.NotFound)
                                "云端还没有动画城数据" else "云端加载失败"
                            val detail = buildString {
                                append("请检查：1. 盒子是否能上网；2. 是否已在 Gitee 同步页完成授权；\n")
                                append("3. 共享仓库 shared_data/cartoons/_index.json 是否存在。\n")
                                append("令牌状态：").append(GiteeShareStore.tokenSummary()).append("\n")
                                append("诊断：").append(msg.ifBlank { "云端 404 (仓库或文件不存在)" })
                            }
                            loadFailed(title = head, detail = detail)
                        } else {
                            emptyView.show(
                                loading = false,
                                titleText = "云端加载失败，暂显示本地 ${cached.size} 部",
                                detailText = "诊断：${msg.ifBlank { "NotFound" }}",
                                hintText = "按 OK 键重新连接云端"
                            )
                        }
                    }
                    Log.w(TAG, "loadCartoons: cloud failed, keepCache=$keep, msg=$msg")
                }
            }
        }
    }

    private fun loadFailed(title: String, detail: String, cached: List<GiteeShareStore.SharedCartoon>? = cachedSnapshotLast) {
        val safeCache = cached ?: emptyList()
        if (safeCache.isNotEmpty()) {
            adapter.submit(safeCache.map { CartoonItem.Cartoon(it) } + CartoonItem.Management)
            updateEmpty(false)
            Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
            return
        }
        updateEmpty(true)
        emptyView.show(
            loading = false,
            titleText = title,
            detailText = detail,
            hintText = "按 OK 键重试"
        )
        emptyView.requestFocus()
    }

    private var cachedSnapshotLast: List<GiteeShareStore.SharedCartoon> = emptyList()

    /** UI 更新统一走此入口：保证 anchor（grid 或 page）在主 Looper 同步/调度，且确保 RV 已布局后再 dispatch。 */
    private inline fun runOnUiAnchor(crossinline block: () -> Unit) {
        val anchor: View = grid ?: this@CartoonCityPage.contentContainer
        if (anchor.handler == null) {
            anchor.post { block() }
        } else {
            try {
                block()
            } catch (e: Throwable) {
                // RV 在非主线程 submit 会炸；兜底 post 一次
                Log.w(TAG, "runOnUiAnchor direct block failed, post retry: ${e.javaClass.simpleName}")
                anchor.post { block() }
            }
        }
    }

    private fun updateEmpty(empty: Boolean) {
        val emptyBefore = emptyView.visibility
        val gridBefore = grid?.visibility ?: View.GONE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        grid?.visibility = if (empty) View.GONE else View.VISIBLE
        Log.i(TAG, "updateEmpty(empty=$empty): emptyView ${visibilityName(emptyBefore)}->${visibilityName(emptyView.visibility)}, grid ${visibilityName(gridBefore)}->${visibilityName(grid?.visibility ?: View.GONE)}")
    }

    private fun visibilityName(v: Int) = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.GONE -> "GONE"
        View.INVISIBLE -> "INVISIBLE"
        else -> "0x${v.toString(16)}"
    }

    /**
     * 云端脏数据一次性清理：
     *  1. 读取原始 _index.json 文本，检测是否有字符串 "null" / uploadeat 拼写 / uploaded_at 等非规范写法
     *  2. 只要有 1 条不规范，decode 后按 encodeCartoon 的规范写法重 encode，一次性 PUT 回 _index.json
     *  3. 对每张脏数据，也覆写对应 cartoon 单文件（因为单文件里同样存在旧写入时的 globalAdapterId="null" 字符串）
     *
     * ponytail: 不在用户交互线程里跑；失败仅 log，不影响当次渲染。下一次进入会继续自愈直到全部干净。
     *            走 GiteeApi 直连（带 Bearer token），不新增 MCP 依赖。
     */
    private suspend fun cleanupCloudDirtyCartoons(parsedCartoons: List<GiteeShareStore.SharedCartoon>) {
        if (parsedCartoons.isEmpty()) return
        // Step 1: 读原始 _index.json 文本逐字段自检
        val idxContent = when (val r = GiteeShareStore.fetchRawCartoonsIndexText()) {
            is GiteeApi.ApiResult.Success -> r.value
            else -> {
                Log.w(TAG, "cleanupCloud: 无法读取原始索引，跳过")
                return
            }
        }
        val (rawDirty, rawListOrNull) = runCatching {
            val arr = org.json.JSONArray(idxContent)
            var dirty = false
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val adapterField = obj.opt("globalAdapterId")
                val adapterDirty = adapterField is String &&
                    (adapterField.equals("null", ignoreCase = true) || adapterField.isBlank())
                val timeKeyDirty = !obj.has("uploadedAt") &&
                    (obj.has("uploadeat") || obj.has("uploaded_at") || obj.has("UploadedAt"))
                if (adapterDirty || timeKeyDirty) dirty = true
                list.add(obj)
            }
            dirty to list
        }.getOrElse { false to null }
        if (!rawDirty) {
            Log.i(TAG, "cleanupCloud: 无脏数据，跳过")
            return
        }
        val rawList = rawListOrNull ?: return
        // Step 2: 覆写对应 cartoon 单文件（只改 globalAdapterId 字段 + 时间键）
        var fixedFiles = 0
        val iter = rawList.iterator()
        while (iter.hasNext()) {
            val obj = iter.next()
            val cidRaw = obj.optString("cartoonId")
            if (cidRaw.isBlank()) continue
            val cid = cidRaw
            val path = "shared_data/cartoons/$cid.json"
            val file = when (val r = GiteeApi.getFileResult(path)) {
                is GiteeApi.ApiResult.Success -> r.value
                else -> continue
            }
            val json = runCatching { org.json.JSONObject(file.content) }.getOrNull() ?: continue
            val oldAdapter = json.opt("globalAdapterId")
            var changed = false
            if (oldAdapter is String && (oldAdapter.equals("null", ignoreCase = true) || oldAdapter.isBlank())) {
                json.put("globalAdapterId", org.json.JSONObject.NULL); changed = true
            } else if (oldAdapter == null && !json.isNull("globalAdapterId")) {
                json.put("globalAdapterId", org.json.JSONObject.NULL); changed = true
            }
            // 时间键修复：uploadeat/uploaded_at/UploadedAt → uploadedAt
            val seq: Sequence<Long> = sequenceOf("uploadedAt", "uploadeat", "uploaded_at", "UploadedAt").map { json.optLong(it, -1L) }
            val ts = seq.firstOrNull { it > 0L }
            if (ts != null && !json.has("uploadedAt")) { json.put("uploadedAt", ts); changed = true }
            if (!changed) continue
            when (val put = GiteeApi.putFileResult(path, json.toString(2), file.sha, "fix: cleanup cartoon $cid dirty fields")) {
                is GiteeApi.ApiResult.Success -> fixedFiles++
                is GiteeApi.ApiResult.Error -> Log.w(TAG, "cleanupCloud: 修复单文件 $cid 失败：${put.message}")
                else -> {}
            }
        }
        // Step 3: 重建规范索引并 PUT（规范编码 = CartoonStore.encodeCartoon 等价）
        val cleanArray = org.json.JSONArray()
        parsedCartoons.forEach { c ->
            cleanArray.put(org.json.JSONObject().apply {
                put("cartoonId", c.cartoonId)
                put("title", c.title)
                put("detailUrl", c.detailUrl)
                put("cover", c.cover)
                put("globalAdapterId", c.globalAdapterId ?: org.json.JSONObject.NULL)
                put("adapterName", c.adapterName)
                put("episodeCount", c.episodeCount)
                put("creatorId", c.creatorId)
                put("deviceName", c.deviceName)
                put("uploadedAt", c.uploadedAt)
            })
        }
        val indexPath = "shared_data/cartoons/_index.json"
        val current = GiteeApi.getFile(indexPath)
        when (val putIndex = GiteeApi.putFileResult(
            indexPath, cleanArray.toString(2),
            current?.sha,
            "fix: cleanup cartoons index dirty fields (globalAdapterId string null, uploadedAt typo)"
        )) {
            is GiteeApi.ApiResult.Success -> {
                Log.i(TAG, "cleanupCloud: 完成，修正 _index.json 脏数据；同步重写单文件 $fixedFiles/${parsedCartoons.size}")
            }
            is GiteeApi.ApiResult.Error -> Log.w(TAG, "cleanupCloud: _index 重写失败：${putIndex.message}")
            else -> {}
        }
    }

    override fun focusToFirstContent(): Boolean {
        val g = grid ?: return false
        g.post {
            g.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        pageScope.cancel()
        super.onDetachedFromWindow()
    }

    // ---- 卡片点击 ----

    private fun openCartoon(cartoon: GiteeShareStore.SharedCartoon, sourceView: View) {
        if (!com.bd.casttv.settings.Settings(context).webParseEnabled) {
            Toast.makeText(context, "请先在设置中启用网页解析功能", Toast.LENGTH_SHORT).show()
            return
        }
        // 直接打开动画城自建结果页（overlay，BACK 自动回列表）。
        // 解析链路 & 简介/集数回写云端 & 本地缓存 & 列表卡片刷新，全部在结果页内部处理。
        CartoonDetailPage.open(
            activity = context as? NewMainActivity ?: run {
                Toast.makeText(context, "当前页面不支持打开详情", Toast.LENGTH_SHORT).show(); return
            },
            cartoon = cartoon,
            triggerView = sourceView
        ) { refreshed ->
            // 回写成功后：把当前缓存里这张卡覆盖 + DiffUtil 刷新封面/集数/简介，避免用户再返回列表还看到旧数据。
            cartoonStore.upsertLocal(refreshed)
            val current = adapter.dataSnapshot()
            val next = current.map {
                if (it is CartoonItem.Cartoon && it.data.cartoonId == refreshed.cartoonId)
                    CartoonItem.Cartoon(refreshed) else it
            }
            adapter.submit(next)
        }
    }

    private fun openManagement(sourceView: View) {
        val currentCartoons = cartoonStore.getCachedCartoons()
        if (currentCartoons.isEmpty()) {
            Toast.makeText(context, "动画城还是空的，暂无可管理内容", Toast.LENGTH_SHORT).show()
            return
        }
        CartoonManagementDialog(context, currentCartoons) {
            // 删除完成回调：清理本地缓存并重新拉云端（同时触发适配器级联删除的副作用）
            loadCartoons()
        }.show()
    }

    // ---- Adapter ----

    private sealed class CartoonItem {
        data class Cartoon(val data: GiteeShareStore.SharedCartoon) : CartoonItem()
        object Management : CartoonItem()
    }

    /**
     * 4 列网格对称间距 ItemDecoration，从根源避免"卡片尺寸和间距来源不一致 → 相邻卡重叠 / 最后一列贴边"。
     *
     * 原理（edge-to-edge 经典整数分配，保证相邻两列之间绝对间距恒等于 hGap）：
     *  - each = hGap / SPAN_COUNT
     *  - col X：left  = hGap - X * each
     *  -          right =          (X + 1) * each
     *  于是相邻两列之间水平间距 = right(col X) + left(col X+1) = (X+1)*each + (hGap - (X+1)*each) = hGap ✔
     *
     * 卡片宽度严格等于 cell 宽（(RV 可用宽度 - RV padding - ΣhGap)/SPAN_COUNT），不依赖 margin，杜绝列重叠。
     * 行间：第 0 行无 top，其余行前补 vGap（保证上下相邻两张卡的垂直间距=vGap）。
     */
    private class CartoonItemDecoration(
        private val hGap: Int,
        private val vGap: Int,
        private val spanCount: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val pos = parent.getChildAdapterPosition(view).takeIf { it != RecyclerView.NO_POSITION } ?: return
            val col = pos % spanCount
            val each = hGap / spanCount
            outRect.left = hGap - col * each
            outRect.right = (col + 1) * each
            outRect.top = if (pos < spanCount) 0 else vGap
            outRect.bottom = 0
        }
    }

    private inner class CartoonAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val data = mutableListOf<CartoonItem>()

        /** DiffUtil submit 前读取当前数据快照，便于详情页回写云端时"只改对应卡"。 */
        fun dataSnapshot(): List<CartoonItem> = data.toList()

        fun submit(list: List<CartoonItem>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = data.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = data[oldPos]
                    val b = list[newPos]
                    return when {
                        a is CartoonItem.Cartoon && b is CartoonItem.Cartoon ->
                            a.data.cartoonId == b.data.cartoonId
                        a is CartoonItem.Management && b is CartoonItem.Management -> true
                        else -> false
                    }
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = data[oldPos]
                    val b = list[newPos]
                    return a is CartoonItem.Cartoon && b is CartoonItem.Cartoon &&
                        a.data.title == b.data.title &&
                        a.data.cover == b.data.cover &&
                        a.data.episodeCount == b.data.episodeCount
                }
            })
            data.clear()
            data.addAll(list)
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount() = data.size

        override fun getItemViewType(position: Int) = if (data[position] is CartoonItem.Management) TYPE_MGMT else TYPE_CARTOON

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            // 卡片宽度 = (RV 可用宽度 - 左右 padding - 列间 gap 总和) / SPAN_COUNT * 缩小比例
            val rvPaddingH = parent.paddingLeft + parent.paddingRight
            val hGapPx = CartoonDesign.dp(parent.context, 14)
            val totalGapPx = hGapPx * (SPAN_COUNT - 1)
            val cellW = (((parent.width - rvPaddingH - totalGapPx).toFloat()) / SPAN_COUNT).toInt()
            // 宽 +1dp，封面高等比例（3:4）；整体圆角从 MD 16 下调到 SM 12，
            // 遮罩覆盖从 20dp 降到 14dp，让底部 12dp 圆角干净露出来（不被渐变遮罩"吃"角）。
            val CARD_R = CartoonDesign.Radius.SM
            val cardW = ((cellW * 0.85f).toInt() + CartoonDesign.dp(parent.context, 1))
                .coerceAtLeast(CartoonDesign.dp(parent.context, 150))
            val coverH = (cardW * 4f / 3f).toInt()
            val titleBar = CartoonDesign.dp(parent.context, 42)
            val overlap = CartoonDesign.dp(parent.context, 14)
            val totalH = coverH + titleBar - overlap

            val outer = FrameLayout(parent.context).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                clipChildren = false
                clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(cellW, totalH)
            }
            // 液态玻璃卡片层：宽度 cardW 居中，圆角 SM=12（较小角度 + 底部完整露圆）
            val card = FrameLayout(parent.context).apply {
                clipChildren = false
                clipToPadding = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                background = CartoonDesign.liquidGlassDrawable(
                    parent.context,
                    CARD_R,
                    CartoonDesign.TintMode.BASE,
                    CartoonDesign.Palette.STROKE_SOFT
                )
            }
            outer.addView(card, FrameLayout.LayoutParams(cardW, totalH, Gravity.CENTER_HORIZONTAL))

            return if (viewType == TYPE_MGMT) {
                createManagementHolder(outer, card, parent, CARD_R)
            } else {
                createCartoonHolder(outer, card, parent, coverH, titleBar, CARD_R)
            }
        }

        private fun createCartoonHolder(
            root: FrameLayout,
            outer: FrameLayout,
            parent: ViewGroup,
            coverH: Int,
            titleBarPx: Int,
            cardR: CartoonDesign.Radius
        ): CartoonVH {
            val ctx = parent.context
            val image = ClippedImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setCircle(false)
                setCornerRadius(CartoonDesign.dp(ctx, cardR.dp).toFloat())
                setImageResource(R.drawable.ic_thumb_default)
            }
            // 海报式底部渐变遮罩：让标题条直接叠在封面下方
            val bottomShade = View(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(
                        Color.argb(240, 8, 10, 18),
                        Color.argb(170, 8, 10, 18),
                        Color.argb(0, 8, 10, 18)
                    )
                )
            }
            // 语义徽章：右上角集数徽（胶囊 badge + 更新/完结/解析中 三态）
            val badge = TextView(ctx).apply {
                textSize = CartoonDesign.Type.BADGE
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
                gravity = Gravity.CENTER
                val ph = CartoonDesign.dp(ctx, 10); val pv = CartoonDesign.dp(ctx, 5)
                setPadding(ph, pv, ph, pv)
                background = CartoonDesign.capsuleBadge(
                    ctx, CartoonDesign.Palette.BADGE_INFO_BG,
                    Color.argb(180, 130, 160, 230)
                )
                visibility = View.GONE
            }
            val name = TextView(ctx).apply {
                textSize = CartoonDesign.Type.TITLE_SM
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                val ph = CartoonDesign.dp(ctx, 12)
                val pv = CartoonDesign.dp(ctx, 6)
                setPadding(ph, CartoonDesign.dp(ctx, 14), ph, pv)
                gravity = Gravity.BOTTOM or Gravity.START
            }
            // 结构（全部塞进 card）：封面 → 渐变遮罩 → 标题 → 徽章
            outer.addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
            outer.addView(bottomShade, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CartoonDesign.dp(ctx, 110),
                Gravity.BOTTOM
            ))
            outer.addView(name, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, titleBarPx, Gravity.BOTTOM
            ))
            outer.addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply { setMargins(0, CartoonDesign.dp(ctx, 10), CartoonDesign.dp(ctx, 10), 0) })
            return CartoonVH(root, outer, image, badge, name)
        }

        private fun createManagementHolder(
            root: FrameLayout, outer: FrameLayout, parent: ViewGroup, cardR: CartoonDesign.Radius
        ): MgmtVH {
            val ctx = parent.context
            // 管理卡：ACCENT tinted 液态玻璃，显得"不同"而不是又一张封面卡
            outer.background = CartoonDesign.liquidGlassDrawable(
                ctx, cardR,
                CartoonDesign.TintMode.ACCENT,
                Color.argb(180, 190, 156, 62)
            )
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_more_settings)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    CartoonDesign.Palette.BADGE_ACCENT_FG
                )
            }
            val label = TextView(ctx).apply {
                text = "管理我的动画"
                textSize = CartoonDesign.Type.TITLE_SM
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CartoonDesign.Palette.BADGE_ACCENT_FG)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val sub = TextView(ctx).apply {
                text = "删除 / 上传播放记录 / 同步设备"
                textSize = CartoonDesign.Type.STATUS
                setTextColor(CartoonDesign.mixColor(
                    CartoonDesign.Palette.BADGE_ACCENT_FG,
                    CartoonDesign.Palette.TEXT_MUTED, 0.35f
                ))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val center = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
            }
            center.addView(icon, LinearLayout.LayoutParams(
                CartoonDesign.dp(ctx, 44), CartoonDesign.dp(ctx, 44)
            ).apply { bottomMargin = CartoonDesign.dp(ctx, 10) })
            center.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            center.addView(sub, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = CartoonDesign.dp(ctx, 4) })
            outer.addView(center, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ).apply {
                val p = CartoonDesign.dp(ctx, 16)
                setMargins(p, CartoonDesign.dp(ctx, 20), p, p)
            })
            return MgmtVH(root, outer)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = data[position]
            if (holder is CartoonVH && item is CartoonItem.Cartoon) {
                holder.bind(item.data)
            }
        }
    }

    private inner class CartoonVH(
        private val outer: FrameLayout,
        private val card: FrameLayout,
        private val image: ClippedImageView,
        private val badge: TextView,
        private val name: TextView
    ) : RecyclerView.ViewHolder(outer) {

        private var current: GiteeShareStore.SharedCartoon? = null

        init {
            outer.setOnFocusChangeListener { _, has ->
                if (!has) BoundaryFocusHandler.cancelShake(outer)
                CartoonDesign.liquidGlassToFocused(itemView.context, card, has)
                if (has) FocusFxHelper.applyFocusFxState(outer, true, cornerRadiusDp = CartoonDesign.Radius.SM.dp)
                else outer.foreground = null
            }
            outer.setOnClickListener {
                val item = current ?: return@setOnClickListener
                openCartoon(item, outer)
            }
            outer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val item = current ?: return@setOnKeyListener false
                        openCartoon(item, outer)
                        true
                    }
                    else -> false
                }
            }
        }

        fun bind(cartoon: GiteeShareStore.SharedCartoon) {
            val ctx = itemView.context
            current = cartoon
            name.text = cartoon.title
            // 语义徽章三态：有集数 -> INFO（蓝紫）；为 0 或更新中 -> WARN；描述已同步且集数 >= 100 -> 完结 SUCCESS
            when {
                cartoon.episodeCount <= 0 -> {
                    badge.visibility = View.VISIBLE
                    badge.text = "更新中"
                    badge.setTextColor(CartoonDesign.Palette.BADGE_WARN_FG)
                    badge.background = CartoonDesign.capsuleBadge(
                        ctx, CartoonDesign.Palette.BADGE_WARN_BG,
                        Color.argb(180, 220, 156, 68)
                    )
                }
                cartoon.episodeCount >= 120 -> {
                    badge.visibility = View.VISIBLE
                    badge.text = "全${cartoon.episodeCount}集 已完结"
                    badge.setTextColor(CartoonDesign.Palette.BADGE_SUCCESS_FG)
                    badge.background = CartoonDesign.capsuleBadge(
                        ctx, CartoonDesign.Palette.BADGE_SUCCESS_BG,
                        Color.argb(170, 72, 186, 128)
                    )
                }
                else -> {
                    badge.visibility = View.VISIBLE
                    badge.text = "更新至第${cartoon.episodeCount}集"
                    badge.setTextColor(CartoonDesign.Palette.BADGE_INFO_FG)
                    badge.background = CartoonDesign.capsuleBadge(
                        ctx, CartoonDesign.Palette.BADGE_INFO_BG,
                        Color.argb(180, 130, 160, 230)
                    )
                }
            }
            image.setImageResource(R.drawable.ic_thumb_default)
            if (cartoon.cover.isNotBlank()) {
                pageScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            val conn = (URL(cartoon.cover).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8000; readTimeout = 8000
                            }
                            conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        } catch (_: Throwable) { null }
                    }
                    if (bmp != null && current?.cartoonId == cartoon.cartoonId) {
                        image.setImageBitmap(bmp)
                    }
                }
            }
            CartoonDesign.liquidGlassToFocused(ctx, card, outer.hasFocus())
        }
    }

    private inner class MgmtVH(
        private val outer: FrameLayout,
        private val card: FrameLayout
    ) : RecyclerView.ViewHolder(outer) {
        init {
            outer.setOnFocusChangeListener { _, has ->
                if (!has) BoundaryFocusHandler.cancelShake(outer)
                CartoonDesign.liquidGlassToFocused(itemView.context, card, has)
                if (has) {
                    FocusFxHelper.applyFocusFxState(outer, true, cornerRadiusDp = CartoonDesign.Radius.SM.dp)
                } else {
                    outer.foreground = null
                }
            }
            outer.setOnClickListener { openManagement(outer) }
            outer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        openManagement(outer)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun dp(v: Int): Int = CartoonDesign.dp(context, v)
    private fun dp(parent: ViewGroup, v: Int): Int = CartoonDesign.dp(parent.context, v)
    private fun dp(ctx: Context, v: Int): Int = CartoonDesign.dp(ctx, v)
}
