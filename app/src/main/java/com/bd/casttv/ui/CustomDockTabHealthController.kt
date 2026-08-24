package com.bd.casttv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bd.casttv.favorites.SourceHealthRecord
import com.bd.casttv.favorites.SourceHealthScorer
import com.bd.casttv.favorites.TabChannel
import com.bd.casttv.health.SourceHealthDetector
import com.bd.casttv.settings.SourceHealthStore
import java.util.ArrayDeque

/**
 * 自定义 Tab「源健康」控制器：
 * - 依据 2 小时 URL 级缓存 TTL 判断需要检测的 uri
 * - 按每组 3 个源分批串行预检，避免进入页面时全量检测抢占播放资源
 * - 检测结果通过 debounce 通知 UI 刷新（避免频繁 notifyDataSetChanged 打断预览/焦点）
 */
class CustomDockTabHealthController(
    context: Context,
    private val store: SourceHealthStore,
    private val onProgress: (checked: Int, total: Int, running: Boolean) -> Unit,
    private val onDebouncedUpdate: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())

    private var detector: SourceHealthDetector? = null
    private var runningTotal: Int = 0
    private var runningDone: Int = 0

    private val pendingGroups = ArrayDeque<List<String>>()
    private val queuedUris = LinkedHashSet<String>()
    private val runningUris = LinkedHashSet<String>()
    private var detectionSeq: Int = 0

    private val refreshTask = Runnable { onDebouncedUpdate.invoke() }

    fun startDetectionIfNeeded(channels: List<TabChannel>) {
        val total = collectAllUris(channels).size
        val targetGroups = collectTargetGroups(channels, force = null)
        val targetCount = targetGroups.fold(0) { acc, group -> acc + group.size }
        if (targetCount <= 0) {
            onProgress(0, 0, false)
            return
        }
        startBatchedDetection(targetGroups, initialDone = (total - targetCount).coerceAtLeast(0), displayTotal = total)
    }

    fun forceRecheck(channel: TabChannel) {
        val targetGroups = collectTargetGroups(listOf(channel), force = true)
        val targetCount = targetGroups.fold(0) { acc, group -> acc + group.size }
        if (targetCount <= 0) {
            onProgress(0, 0, false)
            return
        }
        startBatchedDetection(targetGroups, initialDone = 0, displayTotal = targetCount)
    }

    fun isDetecting(uri: String): Boolean {
        val key = uri.trim()
        if (key.isBlank()) return false
        return runningUris.contains(key) || queuedUris.contains(key)
    }

    /** 用户焦点移动后，把当前频道尚未检测的源插到下一组优先检测，不中断当前组。 */
    fun prioritize(channel: TabChannel) {
        if (queuedUris.isEmpty()) return
        val boosted = collectTargets(listOf(channel), force = null).filter { queuedUris.contains(it) }
        if (boosted.isEmpty()) return

        val boostedSet = boosted.toSet()
        val remaining = ArrayList<String>()
        while (!pendingGroups.isEmpty()) {
            val group = pendingGroups.pollFirst() ?: continue
            group.forEach { uri -> if (uri !in boostedSet) remaining.add(uri) }
        }

        pendingGroups.clear()
        (boosted + remaining).chunked(CHANNEL_GROUP_SIZE).forEach { pendingGroups.add(it) }
    }

    fun cancel() {
        ui.removeCallbacks(refreshTask)
        pendingGroups.clear()
        queuedUris.clear()
        runningUris.clear()
        detector?.cancel()
        detector = null
        detectionSeq += 1
        runningTotal = 0
        runningDone = 0
        onProgress(0, 0, false)
    }

    private fun startBatchedDetection(groups: List<List<String>>, initialDone: Int, displayTotal: Int) {
        detector?.cancel()
        detector = SourceHealthDetector(appContext)

        pendingGroups.clear()
        queuedUris.clear()
        runningUris.clear()

        groups.filter { it.isNotEmpty() }.forEach { group ->
            pendingGroups.add(group)
            queuedUris.addAll(group)
        }

        runningTotal = displayTotal
        runningDone = initialDone.coerceIn(0, runningTotal)
        val seq = ++detectionSeq
        onProgress(runningDone, runningTotal, true)
        startNextGroup(seq)
    }

    private fun startNextGroup(seq: Int) {
        if (seq != detectionSeq) return
        val group = pendingGroups.pollFirst()
        if (group == null) {
            runningUris.clear()
            queuedUris.clear()
            onProgress(runningTotal, runningTotal, false)
            scheduleDebouncedUpdate()
            return
        }

        runningUris.clear()
        runningUris.addAll(group)
        queuedUris.removeAll(group.toSet())

        val d = detector ?: SourceHealthDetector(appContext).also { detector = it }
        d.detect(
            uris = group,
            onEach = onEach@ { uri, record ->
                if (seq != detectionSeq) return@onEach
                runningDone = (runningDone + 1).coerceAtMost(runningTotal)
                runningUris.remove(uri.trim())
                val merged = mergeRecord(store.get(uri), record)
                store.put(uri, merged)
                onProgress(runningDone, runningTotal, runningDone < runningTotal)
                scheduleDebouncedUpdate()
            },
            onDone = onDone@ {
                if (seq != detectionSeq) return@onDone
                runningUris.clear()
                startNextGroup(seq)
            }
        )
    }

    private fun scheduleDebouncedUpdate() {
        ui.removeCallbacks(refreshTask)
        ui.postDelayed(refreshTask, 400L)
    }

    private fun mergeRecord(old: SourceHealthRecord?, fresh: SourceHealthRecord): SourceHealthRecord {
        val o = old ?: SourceHealthRecord.UNKNOWN
        val mergedSuccess = o.successCount + fresh.successCount
        val mergedFail = o.failCount + fresh.failCount
        // 滑动/累加式最终分：本次快照分与累计成功率融合，避免单次抖动主导排序。
        val blended = SourceHealthScorer.blendedScore(
            currentScore = fresh.score,
            successCount = mergedSuccess,
            failCount = mergedFail,
        )
        return fresh.copy(
            score = blended,
            successCount = mergedSuccess,
            failCount = mergedFail,
        )
    }

    private fun collectTargetGroups(channels: List<TabChannel>, force: Boolean?): List<List<String>> {
        val seen = LinkedHashSet<String>()
        val groups = ArrayList<List<String>>()
        channels.chunked(CHANNEL_GROUP_SIZE).forEach { channelGroup ->
            val uris = ArrayList<String>()
            for (c in channelGroup) {
                for (s in c.sources) {
                    val uri = s.item.uri.trim()
                    if (uri.isBlank()) continue
                    val need = when (force) {
                        true -> true
                        false -> store.needsCheck(uri)
                        null -> store.needsCheck(uri)
                    }
                    if (need && seen.add(uri)) uris.add(uri)
                }
            }
            if (uris.isNotEmpty()) groups.add(uris)
        }
        return groups
    }

    private fun collectTargets(channels: List<TabChannel>, force: Boolean?): List<String> {
        val set = LinkedHashSet<String>()
        for (c in channels) {
            for (s in c.sources) {
                val uri = s.item.uri.trim()
                if (uri.isBlank()) continue
                val need = when (force) {
                    true -> true
                    false -> store.needsCheck(uri)
                    null -> store.needsCheck(uri)
                }
                if (need) set.add(uri)
            }
        }
        return set.toList()
    }

    private fun collectAllUris(channels: List<TabChannel>): List<String> {
        val set = LinkedHashSet<String>()
        for (c in channels) {
            for (s in c.sources) {
                val uri = s.item.uri.trim()
                if (uri.isNotBlank()) set.add(uri)
            }
        }
        return set.toList()
    }

    private companion object {
        private const val CHANNEL_GROUP_SIZE = 3
    }
}
