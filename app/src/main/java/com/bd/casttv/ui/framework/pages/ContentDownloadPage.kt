package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.douyin.DouyinDownloadManager
import com.bd.casttv.douyin.DouyinDownloadService
import com.bd.casttv.douyin.DouyinDownloadStore
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.util.Thumbnails
import java.io.File

/**
 * 内容下载页：从本地收藏读取点播内容，按合集分组，支持批量下载。
 *
 * 布局：
 * - 顶部工具栏：下载路径 + 修改 + 批量下载
 * - 左栏：合集列表（onFocus 切换合集）
 * - 右栏：视频列表
 * - 底部状态栏：当前任务 + 进度 + 队列剩余 + 暂停/继续
 */
class ContentDownloadPage(context: Context) : BasePage(context), DouyinDownloadManager.Listener {
    override val pageId = Settings.PAGE_ID_CONTENT_DOWNLOAD
    override val pageTitle = "内容下载"
    override val pageIconRes = R.drawable.ic_watch_later
    override val enablePageScroll = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true

    private val settingsStore by lazy { Settings(context.applicationContext) }
    private val favoritesStore by lazy { FavoritesStore(context.applicationContext) }

    // 数据
    private data class CollectionItem(
        val id: String,
        val name: String,
        val count: Int
    )
    private data class VideoItem(
        val id: String,
        val title: String,
        val uri: String,
        val durationMs: Long,
        val thumbPath: String?,
        val collectionId: String,
        val collectionName: String,
        val downloadTask: DouyinDownloadStore.Task? = null
    )

    private var collections = listOf<CollectionItem>()
    private var videos = listOf<VideoItem>()
    private var selectedCollectionId: String = ""
    private var isMultiSelectMode = false
    private val selectedIds = mutableSetOf<String>()

    // UI
    private val collectionAdapter = CollectionAdapter()
    private val collectionRecycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = collectionAdapter
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
    }
    private val videoAdapter = VideoAdapter()
    private val videoRecycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = videoAdapter
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
    }
    private val pathTextView = TextView(context)
    private val cancelBatchBtn = TextView(context)
    private val batchBtn = TextView(context)
    private val statusTitleView = TextView(context)
    private val statusProgressView = TextView(context)
    private val statusQueueView = TextView(context)
    private val controlBtn = TextView(context)

    // 下载根路径
    private val downloadRootPath: String
        get() {
            val custom = settingsStore.contentDownloadRootPath
            if (custom.isNotBlank()) return custom
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            return File(movies, "castTvDownload").absolutePath
        }

    // 权限相关
    private var pendingDownloadUris: List<Pair<String, String>>? = null // (uri, title)
    private var pendingCollectionId = ""
    private var pendingCollectionName = ""

    companion object {
        private const val STORAGE_PERMISSION_REQ_CODE = 10002
    }

    init {
        DouyinDownloadManager.customRootPath = downloadRootPath
        buildLayout()
    }

    private fun buildLayout() {
        initBatchBtn()
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // 顶部工具栏
        outer.addView(buildTopBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 中间：左栏合集 + 右栏视频
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }
        // 左栏
        val leftPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#332A2A32"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        leftPanel.addView(TextView(context).apply {
            text = "合集"; textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val leftScroll = ScrollView(context).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER
        }
        leftScroll.addView(collectionRecycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        leftPanel.addView(leftScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(10) })
        middle.addView(leftPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))

        // 右栏
        val rightPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#332A2A32"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        val rightHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightHeader.addView(TextView(context).apply {
            text = "视频列表"; textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rightHeader.addView(cancelBatchBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
        ).apply { marginEnd = dp(8) })
        rightHeader.addView(batchBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
        ))
        rightPanel.addView(rightHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        rightPanel.addView(videoRecycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(10) })
        middle.addView(rightPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3.8f).apply {
            leftMargin = dp(6)
        })

        outer.addView(middle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })

        // 底部状态栏
        outer.addView(buildStatusBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        contentContainer.addView(outer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#332A2A32"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        // 下载路径
        val pathLabel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        pathLabel.addView(TextView(context).apply {
            text = "下载路径"; textSize = 12f
            setTextColor(Color.parseColor("#A0A4AE"))
        })
        pathLabel.addView(pathTextView.apply {
            textSize = 14f; setTextColor(Color.WHITE)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        bar.addView(pathLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 修改按钮
        val modifyBtn = smallButton("修改") { showPathChangeDialog() }
        bar.addView(modifyBtn, LinearLayout.LayoutParams(dp(70), dp(34)).apply {
            leftMargin = dp(10)
        })

        return bar
    }

    private fun initBatchBtn() {
        // 取消按钮（多选模式时显示在批量下载左侧）
        cancelBatchBtn.apply {
            text = "取消"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), 0)
            visibility = View.GONE
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            fun refresh(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.parseColor("#FFD700") else Color.parseColor("#66FFD700"))
                }
            }
            refresh(false)
            setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    exitMultiSelectMode()
                    batchBtn.requestFocus()
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    batchBtn.requestFocus()
                    true
                } else false
            }
            setOnClickListener {
                exitMultiSelectMode()
                batchBtn.requestFocus()
            }
        }

        batchBtn.apply {
            text = "批量下载"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            fun refresh(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(Color.parseColor(if (isMultiSelectMode) "#66FFD700" else "#33FFFFFF"))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.parseColor("#FFD700") else Color.parseColor("#66FFD700"))
                }
            }
            refresh(false)
            setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    toggleMultiSelectMode()
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isMultiSelectMode) {
                    cancelBatchBtn.requestFocus()
                    true
                } else false
            }
            setOnClickListener { toggleMultiSelectMode() }
        }
    }

    private fun buildStatusBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#441A1A1E"))
                setStroke(dp(1), Color.parseColor("#33FFD700"))
            }
        }

        // 当前任务
        statusTitleView.apply {
            text = "暂无下载任务"; textSize = 13f
            setTextColor(Color.parseColor("#B0B4BE"))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(statusTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))

        // 进度
        statusProgressView.apply {
            text = ""; textSize = 13f
            setTextColor(Color.parseColor("#FFD700"))
        }
        bar.addView(statusProgressView, LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })

        // 队列剩余
        statusQueueView.apply {
            text = ""; textSize = 12f
            setTextColor(Color.parseColor("#A0A4AE"))
        }
        bar.addView(statusQueueView, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })

        // 占位，按钮靠右
        val spacer = View(context)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        // 控制按钮
        controlBtn.apply {
            text = "暂停全部"; textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), 0)
            visibility = View.GONE
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            fun refresh(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.parseColor("#FFD700") else Color.parseColor("#66FFD700"))
                }
            }
            refresh(false)
            setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
        }
        bar.addView(controlBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))

        return bar
    }

    // ---- 生命周期 ----

    override fun onEnter() {
        DouyinDownloadManager.addListener(this)
        DouyinDownloadManager.customRootPath = downloadRootPath
        loadCollections()
        refreshStatusBar()
    }

    override fun onLeave() {
        DouyinDownloadManager.removeListener(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isMultiSelectMode) {
            exitMultiSelectMode()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- 下载监听器 ----

    override fun onTaskProgress(task: DouyinDownloadStore.Task) {
        updateVideoTask(task)
        refreshStatusBar()
    }

    override fun onTaskCompleted(task: DouyinDownloadStore.Task) {
        updateVideoTask(task)
        refreshStatusBar()
    }

    override fun onTaskFailed(task: DouyinDownloadStore.Task) {
        updateVideoTask(task)
        refreshStatusBar()
    }

    override fun onTaskAdded(task: DouyinDownloadStore.Task) {
        updateVideoTask(task)
        refreshStatusBar()
    }

    override fun onQueueChanged() {
        refreshStatusBar()
    }

    private fun updateVideoTask(task: DouyinDownloadStore.Task) {
        val pos = videos.indexOfFirst { it.uri == task.uri }
        if (pos >= 0) {
            videos = videos.toMutableList().apply {
                set(pos, get(pos).copy(downloadTask = task))
            }
            videoAdapter.notifyItemChanged(pos)
        }
    }

    // ---- 数据加载 ----

    private fun loadCollections() {
        val allCollections = favoritesStore.collectionsInfo()
        val result = mutableListOf<CollectionItem>()
        for (col in allCollections) {
            val collection = favoritesStore.collection(col.id) ?: continue
            val vodCount = collection.items.count { !it.isLive && it.uri.isNotBlank() }
            if (vodCount > 0) {
                result.add(CollectionItem(col.id, col.name, vodCount))
            }
        }
        // 检查是否有未分组的（暂时都在合集中，这里留作扩展）
        collections = result
        renderCollectionList()
        if (collections.isNotEmpty() && selectedCollectionId.isBlank()) {
            selectedCollectionId = collections.first().id
            loadVideos(selectedCollectionId)
        }
    }

    private fun loadVideos(collectionId: String) {
        val collection = favoritesStore.collection(collectionId) ?: run {
            videos = emptyList()
            videoAdapter.submit(emptyList())
            return
        }
        val taskMap = DouyinDownloadStore.getTasksByCollection(context, collectionId)
            .associateBy { it.uri }
        videos = collection.items
            .filter { !it.isLive && it.uri.isNotBlank() }
            .map { item ->
                VideoItem(
                    id = item.id,
                    title = item.title,
                    uri = item.uri,
                    durationMs = item.durationMs,
                    thumbPath = item.thumbPath ?: item.artworkPath,
                    collectionId = collectionId,
                    collectionName = collection.name,
                    downloadTask = taskMap[item.uri]
                )
            }
        videoAdapter.submit(videos)
    }

    private fun renderCollectionList() {
        collectionAdapter.submit(collections)
    }

    private fun selectCollection(colId: String) {
        if (colId == selectedCollectionId) return
        val oldId = selectedCollectionId
        selectedCollectionId = colId
        // 只刷新旧选中和新选中的两个 item，避免全量重建丢失焦点
        val oldPos = collections.indexOfFirst { it.id == oldId }
        val newPos = collections.indexOfFirst { it.id == colId }
        if (oldPos >= 0) collectionAdapter.notifyItemChanged(oldPos)
        if (newPos >= 0) collectionAdapter.notifyItemChanged(newPos)
        loadVideos(colId)
    }

    // ---- 多选模式 ----

    private fun toggleMultiSelectMode() {
        if (isMultiSelectMode) {
            // 当前是多选模式，点击"下载已选"执行下载
            if (selectedIds.isNotEmpty()) {
                batchDownloadSelected()
            }
            exitMultiSelectMode()
        } else {
            enterMultiSelectMode()
        }
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        selectedIds.clear()
        cancelBatchBtn.visibility = View.VISIBLE
        batchBtn.text = "下载已选 (0)"
        batchBtn.post {
            val bg = batchBtn.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#66FFD700"))
        }
        videoAdapter.notifyDataSetChanged()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedIds.clear()
        cancelBatchBtn.visibility = View.GONE
        batchBtn.text = "批量下载"
        batchBtn.post {
            val bg = batchBtn.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#33FFFFFF"))
        }
        videoAdapter.notifyDataSetChanged()
    }

    private fun batchDownloadSelected() {
        if (selectedIds.isEmpty()) return
        if (!hasStoragePermission()) {
            val toDownload = videos.filter { selectedIds.contains(it.id) }
                .map { it.uri to it.title }
            pendingDownloadUris = toDownload
            pendingCollectionId = selectedCollectionId
            pendingCollectionName = collections.firstOrNull { it.id == selectedCollectionId }?.name ?: ""
            requestStoragePermission()
            return
        }
        doBatchDownload()
    }

    private fun doBatchDownload() {
        val toDownload = videos.filter { selectedIds.contains(it.id) }
        for (v in toDownload) {
            DouyinDownloadManager.enqueueFavorite(
                context = context,
                uri = v.uri,
                title = v.title,
                collectionId = v.collectionId,
                collectionName = v.collectionName,
                durationMs = v.durationMs,
                thumbPath = v.thumbPath ?: ""
            )
        }
        DouyinDownloadService.start(context)
        toastMsg("已添加 ${toDownload.size} 个任务到下载队列")
    }

    // ---- 单个下载操作 ----

    private fun onDownloadClicked(video: VideoItem) {
        val task = video.downloadTask
        if (task != null) {
            when (task.status) {
                DouyinDownloadStore.Status.COMPLETED -> {
                    val file = File(task.localPath)
                    if (file.exists()) {
                        // 本地播放
                        launchPlayer("file://${task.localPath}", task.title)
                    } else {
                        // 文件已删除，重新下载
                        startSingleDownload(video)
                    }
                }
                DouyinDownloadStore.Status.FAILED -> {
                    DouyinDownloadManager.retry(context, task.id)
                    DouyinDownloadService.start(context)
                    toastMsg("已重新加入下载队列")
                }
                DouyinDownloadStore.Status.PENDING, DouyinDownloadStore.Status.DOWNLOADING -> {
                    toastMsg("正在下载中…")
                }
            }
            return
        }
        startSingleDownload(video)
    }

    private fun startSingleDownload(video: VideoItem) {
        if (!hasStoragePermission()) {
            pendingDownloadUris = listOf(video.uri to video.title)
            pendingCollectionId = video.collectionId
            pendingCollectionName = video.collectionName
            requestStoragePermission()
            return
        }
        DouyinDownloadManager.enqueueFavorite(
            context = context,
            uri = video.uri,
            title = video.title,
            collectionId = video.collectionId,
            collectionName = video.collectionName,
            durationMs = video.durationMs,
            thumbPath = video.thumbPath ?: ""
        )
        DouyinDownloadService.start(context)
        toastMsg("已加入下载队列")
    }

    private fun onDeleteDownload(task: DouyinDownloadStore.Task) {
        val fileExists = task.localPath.isNotBlank() && File(task.localPath).exists()
        val message = if (fileExists) {
            "确定要删除本地文件和下载记录吗？\n\n${task.title}"
        } else {
            "确定要删除该下载记录吗？\n\n${task.title}"
        }
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setTitle("删除确认")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                DouyinDownloadManager.remove(context, task.id, deleteFile = fileExists)
                toastMsg("已删除")
                loadVideos(selectedCollectionId)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    // ---- 状态栏 ----

    private fun refreshStatusBar() {
        val current = DouyinDownloadStore.getCurrentDownloading(context)
        if (current != null) {
            statusTitleView.text = "当前任务：${current.title}"
            statusProgressView.text = if (current.totalBytes > 0) "${current.progress}%" else "下载中…"
            val remaining = DouyinDownloadManager.getQueueRemainingCount(context)
            statusQueueView.text = "队列剩余：$remaining 个"
            controlBtn.visibility = View.VISIBLE
        } else {
            val pending = DouyinDownloadStore.getPendingAndDownloading(context)
            if (pending.isNotEmpty()) {
                val first = pending.first()
                statusTitleView.text = "当前任务：${first.title}"
                statusProgressView.text = "等待中"
                statusQueueView.text = "队列剩余：${pending.size - 1} 个"
                controlBtn.visibility = View.VISIBLE
            } else {
                statusTitleView.text = "暂无下载任务"
                statusProgressView.text = ""
                statusQueueView.text = ""
                controlBtn.visibility = View.GONE
            }
        }
        pathTextView.text = downloadRootPath
    }

    // ---- 路径修改 ----

    private fun showPathChangeDialog() {
        val palette = ThemeManager.currentPalette(context)
        val warm = Color.parseColor("#FFD700")

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), warm)
            }
        }

        // 标题
        panel.addView(TextView(context).apply {
            text = "修改下载路径"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 分隔线
        panel.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(warm)
                alpha = 60
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(12); bottomMargin = dp(14) })

        // 当前路径标签
        panel.addView(TextView(context).apply {
            text = "当前路径"
            textSize = 13f
            setTextColor(Color.parseColor("#A0A4AE"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 路径内容
        panel.addView(TextView(context).apply {
            text = downloadRootPath
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(1), Color.argb(80, 210, 214, 222))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // 提示文案
        panel.addView(TextView(context).apply {
            text = "路径修改后，新下载的内容将保存到新路径，已下载的文件不会移动。"
            textSize = 13f
            setTextColor(Color.parseColor("#A0A4AE"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // 按钮栏
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
        }

        lateinit var resetBtn: TextView
        lateinit var closeBtn: TextView

        fun dialogBtn(label: String): TextView = TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            fun refresh(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(if (focused) 2 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
                    setColor(Color.argb(52, 32, 34, 40))
                }
                setTextColor(if (focused) warm else Color.argb(235, 245, 245, 245))
            }
            refresh(false)
            setOnFocusChangeListener { v, has ->
                refresh(has)
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
            }
        }

        closeBtn = dialogBtn("关闭")
        resetBtn = dialogBtn("恢复默认")

        // 按键导航
        closeBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { BoundaryFocusHandler.shake(v); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { resetBtn.requestFocus(); true }
                else -> false
            }
        }
        resetBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { closeBtn.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { BoundaryFocusHandler.shake(v); true }
                else -> false
            }
        }

        btnBar.addView(closeBtn, LinearLayout.LayoutParams(dp(100), dp(40)))
        btnBar.addView(resetBtn, LinearLayout.LayoutParams(dp(120), dp(40)).apply {
            marginStart = dp(12)
        })

        panel.addView(btnBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            closeBtn.requestFocus()
        }
        // 给按钮注入 dialog 引用
        closeBtn.setOnClickListener { dialog.dismiss() }
        resetBtn.setOnClickListener {
            settingsStore.contentDownloadRootPath = ""
            DouyinDownloadManager.customRootPath = downloadRootPath
            pathTextView.text = downloadRootPath
            toastMsg("已恢复默认路径")
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---- 权限 ----

    private fun hasStoragePermission(): Boolean {
        val sdk = android.os.Build.VERSION.SDK_INT
        return when {
            sdk >= 33 -> true
            sdk >= 29 -> {
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            else -> {
                val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                read == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        write == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun requestStoragePermission() {
        val activity = context as? android.app.Activity ?: return
        val sdk = android.os.Build.VERSION.SDK_INT
        val permissions = when {
            sdk >= 33 -> return
            sdk >= 29 -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        activity.requestPermissions(permissions, STORAGE_PERMISSION_REQ_CODE)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != STORAGE_PERMISSION_REQ_CODE) return
        val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (granted) {
            val uris = pendingDownloadUris
            val colId = pendingCollectionId
            val colName = pendingCollectionName
            pendingDownloadUris = null
            pendingCollectionId = ""
            pendingCollectionName = ""
            if (!uris.isNullOrEmpty()) {
                for ((uri, title) in uris) {
                    DouyinDownloadManager.enqueueFavorite(
                        context = context,
                        uri = uri,
                        title = title,
                        collectionId = colId,
                        collectionName = colName
                    )
                }
                DouyinDownloadService.start(context)
                toastMsg("已添加 ${uris.size} 个任务到下载队列")
            }
        } else {
            toastMsg("存储权限被拒绝，无法下载")
            pendingDownloadUris = null
        }
    }

    // ---- 播放 ----

    private fun launchPlayer(uri: String, title: String) {
        if (uri.isBlank()) { toastMsg("暂无播放地址"); return }
        try {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URI, uri)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE, "内容下载")
                putExtra(PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
                putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, pageId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) { toastMsg("无法打开播放器") }
    }

    // ---- 工具 ----

    private fun toastMsg(msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            fun refresh(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.parseColor("#FFD700") else Color.parseColor("#66FFD700"))
                }
            }
            refresh(false)
            setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- CollectionAdapter ----

    private inner class CollectionAdapter : RecyclerView.Adapter<CollectionViewHolder>() {
        val items = mutableListOf<CollectionItem>()

        fun submit(newItems: List<CollectionItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionViewHolder {
            return CollectionViewHolder(FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
                ).apply { bottomMargin = dp(6) }
                clipChildren = false
            })
        }

        override fun onBindViewHolder(holder: CollectionViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class CollectionViewHolder(private val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        private var textView: TextView? = null

        fun bind(item: CollectionItem) {
            root.removeAllViews()
            val isSelected = item.id == selectedCollectionId
            val tv = TextView(context).apply {
                text = "${item.name}(${item.count})"
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (isSelected) Color.parseColor("#FFD700") else Color.parseColor("#E0E4EA"))
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isFocusable = true; isFocusableInTouchMode = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (isSelected) Color.parseColor("#44FFD700") else Color.TRANSPARENT)
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        selectCollection(item.id)
                    }
                    FocusFxHelper.applyFocusFxState(this, hasFocus, cornerRadiusDp = 8)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        (videoRecycler.findViewHolderForAdapterPosition(0) as? VideoViewHolder)?.let {
                            it.itemView.findViewWithTag<View>("video_action_btn")?.requestFocus()
                        }
                        true
                    } else false
                }
            }
            textView = tv
            root.addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
    }

    // ---- VideoAdapter ----

    private inner class VideoAdapter : RecyclerView.Adapter<VideoViewHolder>() {
        val items = mutableListOf<VideoItem>()

        fun submit(newItems: List<VideoItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            return VideoViewHolder(FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                clipChildren = false
            })
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class VideoViewHolder(private val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        fun bind(item: VideoItem) {
            root.removeAllViews()
            val card = buildVideoCard(item)
            root.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        private fun buildVideoCard(video: VideoItem): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                minimumHeight = dp(76)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#331A1A1E"))
                    setStroke(dp(1), Color.parseColor("#22FFFFFF"))
                }
            }

            // 多选模式复选框
            if (isMultiSelectMode) {
                val isChecked = selectedIds.contains(video.id)
                val canSelect = canSelectForDownload(video)
                val checkbox = TextView(context).apply {
                    text = if (isChecked) "✓" else ""
                    textSize = 16f; gravity = Gravity.CENTER
                    setTextColor(if (canSelect) Color.WHITE else Color.parseColor("#66FFFFFF"))
                    width = dp(28); height = dp(28)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(if (isChecked) Color.parseColor("#FFD700") else Color.parseColor("#33FFFFFF"))
                        setStroke(dp(1), if (canSelect) Color.parseColor("#66FFD700") else Color.parseColor("#33FFFFFF"))
                    }
                    if (canSelect) {
                        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                        setOnClickListener {
                            if (selectedIds.contains(video.id)) {
                                selectedIds.remove(video.id)
                            } else {
                                selectedIds.add(video.id)
                            }
                            updateBatchBtnText()
                            videoAdapter.notifyItemChanged(adapterPosition)
                        }
                    }
                }
                card.addView(checkbox, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    rightMargin = dp(10)
                })
            }

            // 缩略图
            val thumbHeight = dp(64)
            val thumbWidth = (thumbHeight * 16f / 9f).toInt()
            val thumbBox = FrameLayout(context)
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#22FFFFFF"))
                }
            }
            Thumbnails.load(thumb, video.thumbPath)
            thumbBox.addView(thumb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            card.addView(thumbBox, LinearLayout.LayoutParams(thumbWidth, thumbHeight).apply {
                rightMargin = dp(12)
            })

            // 信息区
            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(context).apply {
                text = video.title
                textSize = 14.5f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // 时长
            if (video.durationMs > 0) {
                info.addView(TextView(context).apply {
                    text = formatDuration(video.durationMs)
                    textSize = 12f
                    setTextColor(Color.parseColor("#A0A4AE"))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) })
            }

            // 状态标签 + 按钮
            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val statusLabel = statusLabelText(video.downloadTask)
            if (statusLabel != null) {
                bottomRow.addView(TextView(context).apply {
                    text = statusLabel
                    textSize = 11f
                    setTextColor(statusLabelColor(video.downloadTask))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(Color.parseColor("#33FFFFFF"))
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            val spacer = View(context)
            bottomRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            // 操作按钮
            val actionBtn = actionButtonFor(video)
            actionBtn.tag = "video_action_btn"
            bottomRow.addView(actionBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)
            ))

            // 已完成时增加删除按钮
            val task = video.downloadTask
            if (task != null && task.status == DouyinDownloadStore.Status.COMPLETED) {
                val delBtn = smallActionButton("删除") {
                    onDeleteDownload(task)
                }
                bottomRow.addView(delBtn, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)
                ).apply { leftMargin = dp(6) })
            }

            info.addView(bottomRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })

            card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            return card
        }

        private fun canSelectForDownload(video: VideoItem): Boolean {
            val task = video.downloadTask ?: return true
            return when (task.status) {
                DouyinDownloadStore.Status.COMPLETED -> {
                    // 已完成且文件存在 → 不可选
                    !(task.localPath.isNotBlank() && File(task.localPath).exists())
                }
                DouyinDownloadStore.Status.PENDING, DouyinDownloadStore.Status.DOWNLOADING -> false
                DouyinDownloadStore.Status.FAILED -> true
            }
        }

        private fun statusLabelText(task: DouyinDownloadStore.Task?): String? {
            task ?: return null
            return when (task.status) {
                DouyinDownloadStore.Status.COMPLETED -> {
                    val exists = task.localPath.isNotBlank() && File(task.localPath).exists()
                    if (exists) null else "本地文件不存在"
                }
                DouyinDownloadStore.Status.FAILED -> "下载失败"
                DouyinDownloadStore.Status.PENDING -> null
                DouyinDownloadStore.Status.DOWNLOADING -> null
            }
        }

        private fun statusLabelColor(task: DouyinDownloadStore.Task?): Int {
            task ?: return Color.WHITE
            return when (task.status) {
                DouyinDownloadStore.Status.FAILED -> Color.parseColor("#FF8B8B")
                DouyinDownloadStore.Status.COMPLETED -> Color.parseColor("#A0A4AE")
                else -> Color.parseColor("#FFD700")
            }
        }

        private fun actionButtonFor(video: VideoItem): TextView {
            val task = video.downloadTask
            val (label, enabled) = when {
                task == null -> "下载" to true
                task.status == DouyinDownloadStore.Status.PENDING -> "↓ 等待中" to false
                task.status == DouyinDownloadStore.Status.DOWNLOADING -> {
                    val text = if (task.totalBytes > 0) "↓ ${task.progress}%" else "↓ 下载中…"
                    text to false
                }
                task.status == DouyinDownloadStore.Status.COMPLETED -> {
                    val exists = task.localPath.isNotBlank() && File(task.localPath).exists()
                    if (exists) "播放" to true else "重新下载" to true
                }
                task.status == DouyinDownloadStore.Status.FAILED -> "重试" to true
                else -> "下载" to true
            }
            return smallActionButton(label, enabled = enabled) {
                onDownloadClicked(video)
            }
        }

        private fun smallActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (enabled) Color.WHITE else Color.parseColor("#66FFFFFF"))
                isFocusable = enabled; isFocusableInTouchMode = enabled; isClickable = enabled
                fun refresh(focused: Boolean) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor(Color.parseColor(if (enabled) "#33FFFFFF" else "#1AFFFFFF"))
                        setStroke(
                            dp(if (focused && enabled) 2 else 1),
                            if (focused && enabled) Color.parseColor("#FFD700") else Color.parseColor("#44FFD700")
                        )
                    }
                }
                refresh(false)
                setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
                if (enabled) {
                    setOnClickListener { onClick() }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                            onClick()
                            true
                        } else false
                    }
                }
            }
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format("%d:%02d", min, sec)
        }
    }

    private fun updateBatchBtnText() {
        if (isMultiSelectMode) {
            batchBtn.text = "下载已选 (${selectedIds.size})"
        }
    }
}
