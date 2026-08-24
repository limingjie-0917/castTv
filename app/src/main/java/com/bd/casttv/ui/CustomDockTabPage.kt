package com.bd.casttv.ui

import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.databinding.DialogCustomDockTabBinding
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.HealthStatus
import com.bd.casttv.favorites.TabChannel
import com.bd.casttv.favorites.TabChannelSource
import com.bd.casttv.favorites.TabChannelAggregator
import com.bd.casttv.settings.SourceHealthStore
import com.bd.casttv.settings.TabChannelConfigStore
import com.bd.casttv.ui.preview.PreviewPlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一级 Dock 的「自定义 Tab」内容页：左侧频道列表 + 右侧主源预览小窗。
 *
 * - 绑定合集后，按标题归一把资源聚合为「频道」，重复源作为备源保留。
 * - 主源可用时预览/播放主源；异常源仅打标不参与播放；无可用源频道仍展示。
 * - 支持「源管理」：设为主源 / 标记异常 / 恢复可用。
 */
class CustomDockTabPage(
    private val inflater: LayoutInflater,
    private val favoritesStore: FavoritesStore,
    private val configStore: TabChannelConfigStore,
    private val healthStore: SourceHealthStore,
    private val useSurfaceView: Boolean,
    private val playBoundaryShake: (View) -> Unit,
    private val onOpenFullscreen: (FavoritesStore.FavoriteItem) -> Unit,
) {

    private val ui = Handler(Looper.getMainLooper())

    private var previewingSourceId: String? = null
    private var previewingChannelKey: String? = null
    private var previewAttemptSeq: Int = 0
    private var previewTimeoutRunnable: Runnable? = null

    private val healthController by lazy {
        CustomDockTabHealthController(
            context = inflater.context,
            store = healthStore,
            onProgress = { checked, total, running -> updateDetectionStatus(checked, total, running) },
            onDebouncedUpdate = { refreshChannelStatuses() },
        )
    }

    private var binding: DialogCustomDockTabBinding? = null
    private var root: View? = null
    private var player: ExoPlayer? = null
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var channelLoadJob: Job? = null
    private var previewListener: Player.Listener? = null

    private var currentTabId: String = ""
    private var currentCollectionId: String = ""
    private var channels: List<TabChannel> = emptyList()
    private var allSessionChannels: List<TabChannel> = emptyList()
    private var sessionUnavailableIds: Set<String> = emptySet()
    private var focusedChannel: TabChannel? = null
    private var reloadRequestSeq: Int = 0
    private var isPreviewActive: Boolean = false

    private val adapter = CustomDockChannelAdapter(
        onFocused = { channel ->
            focusedChannel = channel
            healthController.prioritize(channel)
            schedulePreview(channel)
        },
        onClick = { channel -> openFullscreen(channel) },
        onManage = { channel -> showManageDialog(channel) },
        playBoundaryShake = playBoundaryShake,
    )

    private val manageDialog by lazy {
        ChannelSourceManageDialog(
            context = inflater.context,
            onSetPrimary = { channelKey, sourceId ->
                configStore.setPrimary(currentTabId, channelKey, sourceId)
                reloadChannels(preserveKey = channelKey)
            },
            onSetAbnormal = { channelKey, sourceId, abnormal ->
                configStore.setAbnormal(currentTabId, channelKey, sourceId, abnormal)
                reloadChannels(preserveKey = channelKey)
            },
            onRecheck = { channel ->
                healthController.forceRecheck(channel)
            },
        )
    }

    private var pendingPreview: TabChannel? = null
    private var isChannelListScrolling: Boolean = false
    private val previewRunnable = Runnable {
        val channel = pendingPreview
        pendingPreview = null
        if (channel != null && channel.channelKey == focusedChannel?.channelKey) startPreview(channel)
    }

    fun ensureInflated(parent: ViewGroup): View {
        if (root != null) return root!!
        val b = DialogCustomDockTabBinding.inflate(inflater, parent, false)
        binding = b
        root = b.root

        b.previewPlayerView.visibility = if (useSurfaceView) View.VISIBLE else View.GONE
        b.previewPlayerViewTexture.visibility = if (useSurfaceView) View.GONE else View.VISIBLE

        // 兜底页：小新贴纸摇摆 + 去设置按钮
        try {
            b.imgEmptySticker.startAnimation(AnimationUtils.loadAnimation(parent.context, R.anim.anim_sticker_sway))
        } catch (_: Throwable) {
        }
        b.btnEmptyGoSettings.setOnClickListener { openSettingsForRebind() }
        b.btnEmptyGoSettings.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { requestListFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { true } // 兜底页内部不需要上移，避免焦点跳回不可见区域
                KeyEvent.KEYCODE_DPAD_DOWN -> { true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openSettingsForRebind(); true }
                else -> false
            }
        }

        b.resourceList.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.VERTICAL, false)
        b.resourceList.clipToPadding = false
        b.resourceList.setPadding(b.resourceList.paddingLeft, b.resourceList.paddingTop, b.resourceList.paddingRight, dp(parent, 40))
        b.panelPreview.setPadding(b.panelPreview.paddingLeft, b.panelPreview.paddingTop, b.panelPreview.paddingRight, dp(parent, 40))
        b.resourceList.adapter = adapter
        b.resourceList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                isChannelListScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                if (isChannelListScrolling) {
                    ui.removeCallbacks(previewRunnable)
                } else {
                    focusedChannel?.let { schedulePreview(it, force = false) }
                }
            }
        })

        b.previewPlayerContainer.setOnClickListener { focusedChannel?.let { openFullscreen(it) } }
        b.previewPlayerContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { requestListFocus(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    focusedChannel?.let { openFullscreen(it) }; true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { playBoundaryShake(b.previewPlayerContainer); true }
                KeyEvent.KEYCODE_DPAD_UP -> { playBoundaryShake(b.previewPlayerContainer); true }
                else -> false
            }
        }

        return b.root
    }

    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun bindTab(tabId: String, @Suppress("UNUSED_PARAMETER") tabName: String, collectionId: String) {
        currentTabId = tabId
        currentCollectionId = collectionId
        reloadChannels(preserveKey = focusedChannel?.channelKey)
    }

    fun currentChannelsSnapshot(): List<TabChannel> = allSessionChannels.ifEmpty { channels }

    fun applySessionUnavailableFilter(unavailableIds: Set<String>) {
        sessionUnavailableIds = unavailableIds
        val filtered = applySessionFilter(allSessionChannels.ifEmpty { channels })
        channels = filtered
        adapter.submitList(filtered)
        focusedChannel = filtered.firstOrNull { it.channelKey == focusedChannel?.channelKey } ?: filtered.firstOrNull()
        binding?.let { b ->
            b.textResourceListTitle.text = "频道列表 · ${filtered.size} 个频道 · 本次已隐藏 ${unavailableIds.size} 个不可用频道"
            if (filtered.isEmpty()) {
                stopPreviewPlayerOnly()
                showEmptyFallback(true)
                b.textEmptyTitle.text = "可用频道暂时为空"
                b.textEmptyMessage.text = "本次检测后，不可用频道已临时隐藏。关闭页面后会恢复全量频道。"
                b.textEmptyHint.text = "💡 提示：也可以重新进入页面后选择不过滤"
                b.btnEmptyGoSettings.requestFocus()
            } else {
                showEmptyFallback(false)
                focusedChannel?.let { schedulePreview(it, force = true) }
            }
        }
    }

    private fun applySessionFilter(source: List<TabChannel>): List<TabChannel> {
        if (sessionUnavailableIds.isEmpty()) return source
        return source.filterNot { channel ->
            val active = channel.activeSource
            val sourceId = active?.sourceId.orEmpty()
            val uri = active?.item?.uri?.trim().orEmpty()
            channel.channelKey in sessionUnavailableIds || sourceId in sessionUnavailableIds || uri in sessionUnavailableIds
        }
    }

    /** 重新读取合集 + 配置，聚合频道并刷新（尽量保留当前焦点频道）。 */
    private fun reloadChannels(preserveKey: String?) {
        val b = binding ?: return
        val tabId = currentTabId
        val collectionId = currentCollectionId
        val requestSeq = ++reloadRequestSeq

        healthController.cancel()
        stopPreviewPlayerOnly()
        focusedChannel = null
        channels = emptyList()

        showEmptyFallback(false)
        b.textResourceListTitle.text = "频道列表 · 加载中..."
        b.progressPreviewLoading.visibility = View.VISIBLE
        b.textPreviewStatus.visibility = View.GONE
        b.textPreviewStatus.translationY = 0f
        b.textPreviewStatus.text = ""
        b.textPreviewInfo.text = "-"
        updateDetectionStatus(0, 0, running = false)
        adapter.submitList(emptyList())

        channelLoadJob?.cancel()
        channelLoadJob = pageScope.launch {
            val result = withContext(Dispatchers.IO) {
                val collection = if (collectionId.isBlank()) null else try { favoritesStore.collection(collectionId) } catch (_: Throwable) { null }
                val items = collection?.items.orEmpty()
                val overrides = if (tabId.isBlank()) emptyMap() else try { configStore.overrides(tabId) } catch (_: Throwable) { emptyMap() }
                val health = try { healthStore.all() } catch (_: Throwable) { emptyMap() }
                ChannelLoadResult(collection == null, items.size, TabChannelAggregator.aggregate(items, overrides, health))
            }
            if (requestSeq != reloadRequestSeq || tabId != currentTabId || collectionId != currentCollectionId) return@launch
            allSessionChannels = result.channels
            channels = applySessionFilter(result.channels)

            val countText = when {
                result.collectionMissing -> "绑定合集已失效"
                channels.isEmpty() -> "0 个频道"
                else -> "${channels.size} 个频道 · ${result.itemCount} 个源"
            }
            b.textResourceListTitle.text = "频道列表 · $countText"
            adapter.submitList(channels)

            if (channels.isEmpty()) {
                focusedChannel = null
                stopPreviewPlayerOnly()
                showEmptyFallback(true)
                val title = "哎？这里空空的耶～"
                val (message, hint) = when {
                    collectionId.isBlank() -> "这个 Tab 还没有绑定合集，没有频道可以看哦！快去「设置」里绑定一个合集，或者换一个有内容的合集吧～不然小新也不知道要播什么给你看！(；′⌒`)" to
                        "💡 提示：进入设置 → 自定义 Tab → 重新选择合集，即可绑定频道"
                    result.collectionMissing -> "咦？你之前绑定的那个合集好像不见了耶～可能在本地被删除了，所以这里就空空的啦！去「设置」里重新换绑一个合集就好～(；′⌒`)" to
                        "💡 提示：进入设置 → 自定义 Tab → 重新选择合集，换绑后就能看到频道啦"
                    else -> "这个合集还在，但是里面暂时没有内容，所以频道也就空空的啦～你可以去「收藏」里加点资源，或者回「设置」里换绑一个有内容的合集哦！(；′⌒`)" to
                        "💡 提示：进入收藏添加资源，或去设置里换绑合集，都可以恢复内容"
                }
                b.textEmptyTitle.text = title
                b.textEmptyMessage.text = message
                b.textEmptyHint.text = hint
                b.btnEmptyGoSettings.requestFocus()
                return@launch
            }

            showEmptyFallback(false)
            val next = preserveKey?.let { key -> channels.firstOrNull { it.channelKey == key } } ?: channels.first()
            focusedChannel = next
            schedulePreview(next, force = true)
            b.resourceList.post { healthController.startDetectionIfNeeded(orderedChannelsForDetection()) }
        }
    }

    private fun refreshChannelStatuses() {
        val b = binding ?: return
        if (currentCollectionId.isBlank()) return
        val tabId = currentTabId
        val collectionId = currentCollectionId
        val previousFocusedKey = focusedChannel?.channelKey
        val listHadFocus = b.resourceList.hasFocus()

        pageScope.launch {
            val next = withContext(Dispatchers.IO) {
                val collection = try { favoritesStore.collection(collectionId) } catch (_: Throwable) { null }
                val items = collection?.items.orEmpty()
                val overrides = if (tabId.isBlank()) emptyMap() else try { configStore.overrides(tabId) } catch (_: Throwable) { emptyMap() }
                applySessionFilter(TabChannelAggregator.aggregate(items, overrides, try { healthStore.all() } catch (_: Throwable) { emptyMap() }))
            }
            if (tabId != currentTabId || collectionId != currentCollectionId || next.isEmpty()) return@launch

            channels = next
            adapter.submitList(next)

            val restored = previousFocusedKey?.let { k -> next.firstOrNull { it.channelKey == k } }
            focusedChannel = restored ?: next.firstOrNull()
            ui.removeCallbacks(previewRunnable)
            pendingPreview = null

            if (listHadFocus && restored != null) {
                adapter.positionOf(restored)?.let { pos ->
                    b.resourceList.post { b.resourceList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
                }
            }

            val focused = focusedChannel
            val src = focused?.activeSource
            if (focused != null && src != null) updatePreviewInfo(focused, src)
        }
    }

    private fun orderedChannelsForDetection(): List<TabChannel> {
        val all = channels
        if (all.isEmpty()) return emptyList()

        val ordered = LinkedHashMap<String, TabChannel>()
        fun add(channel: TabChannel?) {
            if (channel != null) ordered[channel.channelKey] = channel
        }

        // 检测范围：当前聚焦频道 + 紧随其后的 3 个频道，一共最多 4 个频道，避免右侧预览区触发全量播放源检测。
        val focusedIndex = focusedChannel?.let { focused ->
            all.indexOfFirst { it.channelKey == focused.channelKey }
        }?.takeIf { it >= 0 } ?: 0
        for (i in focusedIndex until (focusedIndex + MAX_DETECTION_CHANNEL_COUNT).coerceAtMost(all.size)) {
            add(all.getOrNull(i))
        }
        return ordered.values.toList()
    }

    fun requestInitialFocus() {
        val b = binding ?: return
        if (adapter.itemCount > 0) {
            val pos = focusedChannel?.let { adapter.positionOf(it) } ?: 0
            b.resourceList.post {
                val vh = b.resourceList.findViewHolderForAdapterPosition(pos)
                if (vh?.itemView?.requestFocus() != true) b.resourceList.requestFocus()
            }
        } else {
            // 频道为空：焦点落在兜底页「去设置」按钮上（如果兜底页可见）。
            if (b.panelEmptyFallback.visibility == View.VISIBLE) {
                b.btnEmptyGoSettings.requestFocus()
            } else {
                b.resourceList.requestFocus()
            }
        }
    }

    fun acquirePreviewPlayer() {
        player = PreviewPlayerHolder.acquire(inflater.context)
        ensurePreviewListener()
    }

    fun resumePreview() {
        isPreviewActive = true
        val activePlayer = player
        val activeChannel = focusedChannel
        val activeSource = activeChannel?.activeSource
        if (activePlayer != null && activeChannel != null && activeSource != null && previewingChannelKey == activeChannel.channelKey && previewingSourceId == activeSource.sourceId) {
            binding?.let { b ->
                val activeView = if (useSurfaceView) b.previewPlayerView else b.previewPlayerViewTexture
                if (activeView.player !== activePlayer) activeView.player = activePlayer
                b.progressPreviewLoading.visibility = View.GONE
                b.textPreviewStatus.visibility = View.GONE
                b.textPreviewStatus.translationY = 0f
                b.textPreviewStatus.text = ""
            }
            try {
                activePlayer.volume = 1f
                activePlayer.playWhenReady = true
                activePlayer.play()
            } catch (_: Throwable) {
                startPreview(activeChannel)
            }
        } else {
            val channel = activeChannel ?: return
            schedulePreview(channel, force = true)
        }
        healthController.startDetectionIfNeeded(orderedChannelsForDetection())
    }

    fun pausePreviewForPageSwitch() {
        pausePreviewInternal(cancelHealthDetection = true)
    }

    /**
     * 外部投屏到达后，播放器页启动前先让自定义 Tab 预览播放器立即让渡音视频资源。
     * 与切页暂停一样停止预览与预检，但保留当前频道与播放器实例，便于从播放器返回后按原逻辑恢复。
     */
    fun pausePreviewForExternalCast() {
        pausePreviewInternal(cancelHealthDetection = true)
    }

    private fun pausePreviewInternal(cancelHealthDetection: Boolean) {
        isPreviewActive = false
        ui.removeCallbacks(previewRunnable)
        previewTimeoutRunnable?.let { ui.removeCallbacks(it) }
        previewTimeoutRunnable = null
        pendingPreview = null
        if (cancelHealthDetection) healthController.cancel()
        binding?.let { b ->
            b.progressPreviewLoading.visibility = View.GONE
            b.textPreviewStatus.visibility = View.GONE
            b.textPreviewStatus.translationY = 0f
        }
        try {
            player?.playWhenReady = false
            player?.pause()
            player?.volume = 0f
        } catch (_: Throwable) {
        }
    }

    fun stopPreview() {
        isPreviewActive = false
        reloadRequestSeq++
        healthController.cancel()
        stopPreviewPlayerOnly()
    }

    private fun stopPreviewPlayerOnly() {
        ui.removeCallbacks(previewRunnable)
        previewTimeoutRunnable?.let { ui.removeCallbacks(it) }
        previewTimeoutRunnable = null
        pendingPreview = null
        previewingSourceId = null
        previewingChannelKey = null
        previewAttemptSeq++
        // 断开 View 与 player 的绑定并清空播放列表，但不释放全局预览单例。
        binding?.let { b ->
            b.previewPlayerView.player = null
            b.previewPlayerViewTexture.player = null
            b.progressPreviewLoading.visibility = View.GONE
        }
        PreviewPlayerHolder.pauseAndClear()
        player = PreviewPlayerHolder.current()
    }

    private fun openFullscreen(channel: TabChannel) {
        val source = channel.activeSource
        if (source == null) {
            Toast.makeText(inflater.context, "当前频道暂无可用源", Toast.LENGTH_SHORT).show()
            return
        }
        if (healthController.isDetecting(source.item.uri)) {
            Toast.makeText(inflater.context, "当前频道正在预检中，检测完成后播放会更稳定，也可以继续尝试播放～", Toast.LENGTH_SHORT).show()
        }
        PreviewPlayerHolder.pauseForFullscreen()
        onOpenFullscreen(source.item)
    }

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    private fun showManageDialog(channel: TabChannel) {
        focusedChannel = channel
        manageDialog.show(channel)
    }

    private fun schedulePreview(channel: TabChannel, force: Boolean = false) {
        val b = binding ?: return
        val source = channel.activeSource
        if (source == null) {
            stopPreviewPlayerOnly()
            b.progressPreviewLoading.visibility = View.GONE
            b.textPreviewStatus.visibility = View.VISIBLE
            b.textPreviewStatus.translationY = 0f
            b.textPreviewStatus.text = "当前频道暂无可用源\n可按菜单键在源管理中恢复异常源"
            b.textPreviewInfo.text = "频道名称：${channel.displayName}\n主源：暂无可用源\n备份源：共 ${channel.sourceCount} 个源"
            b.textHealthTag.text = "不可用"
            b.textAbnormalTag.visibility = if (channel.abnormalCount > 0) View.VISIBLE else View.GONE
            return
        }
        b.progressPreviewLoading.visibility = View.VISIBLE
        updatePreviewLoadingStatus(channel, source)
        updatePreviewInfo(channel, source)

        if (!isPreviewActive) {
            ui.removeCallbacks(previewRunnable)
            pendingPreview = null
            b.progressPreviewLoading.visibility = View.GONE
            b.textPreviewStatus.visibility = View.GONE
            b.textPreviewStatus.translationY = 0f
            b.textPreviewStatus.text = ""
            return
        }

        pendingPreview = channel
        ui.removeCallbacks(previewRunnable)
        if (isChannelListScrolling && !force) return
        if (force) ui.postDelayed(previewRunnable, 120L) else ui.postDelayed(previewRunnable, 360L)
    }

    private fun startPreview(channel: TabChannel) {
        if (!isPreviewActive) return
        // 防止旧频道的延迟预览任务在新频道获焦后才执行，导致几秒后预览又切回上一频道。
        if (channel.channelKey != focusedChannel?.channelKey) return
        val source = channel.activeSource ?: return
        startPreviewSource(channel, source, channel.playableSources.indexOfFirst { it.sourceId == source.sourceId }.coerceAtLeast(0))
    }

    private fun startPreviewSource(channel: TabChannel, source: TabChannelSource, sourceIndex: Int) {
        if (!isPreviewActive) return
        if (channel.channelKey != focusedChannel?.channelKey) return
        val b = binding ?: return
        val playableSources = channel.playableSources
        if (playableSources.isEmpty()) return
        val attemptSeq = ++previewAttemptSeq
        previewingChannelKey = channel.channelKey
        previewingSourceId = source.sourceId
        previewTimeoutRunnable?.let { ui.removeCallbacks(it) }
        val activeView = if (useSurfaceView) b.previewPlayerView else b.previewPlayerViewTexture
        if (player == null) {
            player = PreviewPlayerHolder.acquire(inflater.context)
            ensurePreviewListener()
        }
        val p = player ?: return
        if (activeView.player !== p) activeView.player = p
        try {
            b.progressPreviewLoading.visibility = View.VISIBLE
            updatePreviewLoadingStatus(channel, source)
            p.volume = 1f
            PreviewPlayerHolder.switchTo(source.item.uri)
            startPreviewTimeout(attemptSeq, channel, sourceIndex)
        } catch (_: Throwable) {
            handlePreviewSourceFailed(attemptSeq, source.sourceId)
        }
    }

    private fun startPreviewTimeout(attemptSeq: Int, channel: TabChannel, sourceIndex: Int) {
        previewTimeoutRunnable?.let { ui.removeCallbacks(it) }
        val timeout = Runnable {
            if (attemptSeq != previewAttemptSeq) return@Runnable
            if (!isPreviewActive || channel.channelKey != focusedChannel?.channelKey) return@Runnable
            playNextPreviewSource(channel, sourceIndex)
        }
        previewTimeoutRunnable = timeout
        ui.postDelayed(timeout, PREVIEW_SOURCE_TIMEOUT_MS)
    }

    private fun cancelPreviewTimeout() {
        previewTimeoutRunnable?.let { ui.removeCallbacks(it) }
        previewTimeoutRunnable = null
        binding?.let { b ->
            b.progressPreviewLoading.visibility = View.GONE
            b.textPreviewStatus.visibility = View.GONE
            b.textPreviewStatus.translationY = 0f
        }
    }

    private fun handlePreviewSourceFailed(attemptSeq: Int, sourceId: String) {
        if (attemptSeq != previewAttemptSeq || sourceId != previewingSourceId) return
        val channel = focusedChannel ?: return
        val currentIndex = channel.playableSources.indexOfFirst { it.sourceId == sourceId }
        playNextPreviewSource(channel, currentIndex)
    }

    private fun playNextPreviewSource(channel: TabChannel, currentIndex: Int) {
        val b = binding ?: return
        val sources = channel.playableSources
        if (sources.size <= 1) {
            b.progressPreviewLoading.visibility = View.GONE
            b.textPreviewStatus.visibility = View.VISIBLE
            b.textPreviewStatus.translationY = 0f
            b.textPreviewStatus.text = "预览失败\n可按 OK 全屏尝试，或按菜单键切换主源"
            return
        }
        val nextIndex = ((currentIndex.coerceAtLeast(0) + 1) % sources.size)
        val nextSource = sources[nextIndex]
        b.progressPreviewLoading.visibility = View.VISIBLE
        updatePreviewLoadingStatus(channel, nextSource)
        updatePreviewInfo(channel, nextSource)
        startPreviewSource(channel, nextSource, nextIndex)
    }

    private fun requestListFocus() {
        val b = binding ?: return
        val focused = focusedChannel
        if (focused == null) { b.resourceList.requestFocus(); return }
        adapter.positionOf(focused)?.let { pos ->
            b.resourceList.post {
                val vh = b.resourceList.findViewHolderForAdapterPosition(pos)
                if (vh?.itemView?.requestFocus() != true) b.resourceList.requestFocus()
            }
        } ?: b.resourceList.requestFocus()
    }


    private fun showEmptyFallback(show: Boolean) {
        val b = binding ?: return
        b.panelEmptyFallback.visibility = if (show) View.VISIBLE else View.GONE
        b.panelNormalContent.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun openSettingsForRebind() {
        try {
            val intent = Intent(inflater.context, com.bd.casttv.ui.framework.NewMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, "settings")
            }
            inflater.context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(inflater.context, "跳转设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePreviewLoadingStatus(channel: TabChannel, source: TabChannelSource) {
        val b = binding ?: return
        val sources = channel.playableSources
        val sourceIndex = sources.indexOfFirst { it.sourceId == source.sourceId }
        val loadingText = if (sources.size > 1 && sourceIndex >= 0) {
            "加载播放源(${sourceIndex + 1}/${sources.size})"
        } else {
            "加载播放源"
        }
        b.textPreviewStatus.visibility = View.VISIBLE
        b.textPreviewStatus.translationY = dp(b.root, 48).toFloat()
        b.textPreviewStatus.text = loadingText
    }

    private fun updatePreviewInfo(channel: TabChannel, source: TabChannelSource) {
        val b = binding ?: return
        val subtitle = formatSubtitle(source.item).ifBlank { "主源" }
        val detectingHint = if (healthController.isDetecting(source.item.uri)) "\n资源正在后台预检，完成后播放会更稳定" else ""
        b.textPreviewInfo.text = "频道名称：${channel.displayName}\n主源：$subtitle · 可播放\n备份源：${(channel.sourceCount - 1).coerceAtLeast(0)} 个$detectingHint"
        b.textHealthTag.text = when (source.health.status) {
            HealthStatus.HEALTHY -> "健康"
            HealthStatus.DEGRADED -> if (source.health.reason.contains("无声")) "无声音" else "较慢"
            HealthStatus.UNHEALTHY -> "无法连接"
            HealthStatus.UNKNOWN -> "检测中"
        }
        b.textAbnormalTag.visibility = if (channel.abnormalCount > 0) View.VISIBLE else View.GONE
    }

    private fun updateDetectionStatus(checked: Int, total: Int, running: Boolean) {
        val b = binding ?: return
        if (total <= 0) {
            b.panelDetectionStatus.visibility = View.GONE
            return
        }
        b.panelDetectionStatus.visibility = View.VISIBLE
        b.progressSourceDetection.visibility = if (running) View.VISIBLE else View.GONE
        b.textDetectionStatus.text = if (running) {
            "正在预检直播源 · ${checked.coerceIn(0, total)}/$total，完成后播放更稳定，可继续浏览"
        } else {
            "直播源预检完成 · 已更新 $total/$total 个源状态"
        }
    }

    private fun formatSubtitle(item: FavoritesStore.FavoriteItem): String {
        val parts = ArrayList<String>(3)
        if (item.source.isNotBlank()) parts.add(item.source)
        if (item.resolution.isNotBlank()) parts.add(item.resolution)
        if (item.isLive) parts.add("直播")
        return parts.joinToString(" · ")
    }

    private fun ensurePreviewListener() {
        if (previewListener != null) return
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) cancelPreviewTimeout()
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePreviewSourceFailed(previewAttemptSeq, previewingSourceId ?: return)
            }
        }
        previewListener = listener
        player?.addListener(listener)
    }

    fun destroyPreviewPage() {
        channelLoadJob?.cancel()
        pageScope.coroutineContext[Job]?.cancel()
        stopPreview()
        previewListener?.let { listener -> player?.removeListener(listener) }
        previewListener = null
        player = null
        PreviewPlayerHolder.release()
        PreviewPlayerHolder.releaseIfIdle()
    }

    private data class ChannelLoadResult(
        val collectionMissing: Boolean,
        val itemCount: Int,
        val channels: List<TabChannel>,
    )

    private companion object {
        private const val MAX_DETECTION_CHANNEL_COUNT = 4
        private const val PREVIEW_SOURCE_TIMEOUT_MS = 15_000L
    }
}
