package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.sync.RecommenderIdentity
import com.bd.casttv.sync.RecommendationsStore
import com.bd.casttv.sync.SeenRecommendationsStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.util.Thumbnails

/**
 * 「收藏页推荐合集」接收端：云端推荐弹窗。
 *
 * 展示按合集分组的新增推荐视频卡片（默认全选、右下角对勾角标）。
 * 底部按钮：取消 / 加入稍后播放 / 播放推荐内容（未勾选时后两个置灰不可焦）。
 *
 * 生命周期：
 *  - onShow 立即将本次拉到的“今日新增视频集合”覆盖写入本地 SharedPreferences，避免同一次启动多次触发；
 *  - 底部 3 个操作按钮任一触发后弹窗自动 dismiss，dismiss 时回调 [onDone]（供 Activity 恢复焦点）。
 *
 * 依赖：
 *  - [PlayQueueStore.add] 用于「加入稍后播放」；
 *  - [PlayQueueStore.prepend] + Activity `startQueuePlayback` 用于「播放推荐内容」，把首个选中视频拉起播放器。
 */
class IncomingRecommendationsDialog(
    private val context: Context,
    private val incoming: List<RecommendationsStore.Recommendation>,
    private val onDone: () -> Unit,
    private val onPlayVideo: (title: String, uri: String, source: String) -> Unit
) {
    private val WARM: Int get() = ThemeManager.currentPalette(context).accent
    private val SILVER = Color.rgb(192, 192, 192)

    /** 平铺后的所有视频，携带其来自的合集，用于跨合集勾选 & 底部操作。 */
    private data class FlatVideo(
        val collectionId: String,
        val collectionName: String,
        val recommenderText: String,
        val isAlreadyRecommended: Boolean,
        val video: RecommendationsStore.RecommendationVideo
    ) {
        val key: String get() = "${collectionId}|${video.uniqueKey()}"
    }

    fun show() {
        if (incoming.isEmpty()) { onDone(); return }
        val myCreatorId = RecommenderIdentity.creatorId(context)
        val flat = incoming.flatMap { rec ->
            val recommenderText = buildRecommenderText(rec.recommenderList)
            val isAlreadyRecommended = rec.recommenderList.any { it.creatorId == myCreatorId }
            rec.videos.map { FlatVideo(rec.collectionId, rec.collectionName, recommenderText, isAlreadyRecommended, it) }
        }
        if (flat.isEmpty()) { onDone(); return }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
            setPadding(dp(28), dp(20), dp(28), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "云端推荐"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val countText = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        header.addView(createTitleSticker(), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        header.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(countText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        panel.addView(header)

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(contentColumn, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        // 底部按钮行。
        val btnCancel = dialogButton("取消")
        val btnCancelRecommend = dialogButton("取消推荐").apply { visibility = View.GONE }
        val btnRecommend = dialogButton("推荐").apply { visibility = View.GONE }
        val btnQueue = dialogButton("加入稍后播放")
        val btnPlay = dialogButton("播放推荐内容")
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(btnCancel, btnCancelRecommend, btnRecommend, btnQueue, btnPlay).forEach { b ->
            bottom.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })
        }
        panel.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val selected = linkedSetOf<String>()
        // 默认全选。
        flat.forEach { selected.add(it.key) }

        // 按合集分组渲染。
        val firstCardHolder = arrayOfNulls<View>(1)
        val adapters = mutableListOf<GridAdapter>()
        val grouped = flat.groupBy { it.collectionId }
        var isFirstGroup = true
        for ((_, list) in grouped) {
            val recommenderText = list.first().recommenderText
            val secLabel = TextView(context).apply {
                text = "「${list.first().collectionName}」 · 来自 $recommenderText · ${list.size} 个视频"
                textSize = 14f
                setTextColor(WARM)
                setPadding(dp(2), dp(if (isFirstGroup) 0 else 10), dp(2), dp(6))
                typeface = Typeface.DEFAULT_BOLD
            }
            contentColumn.addView(secLabel)
            val grid = RecyclerView(context).apply {
                // 每个合集分组下固定 5 列视频卡片；卡片宽度随弹窗整体宽度自适应，缩略图按 16:9 动态计算高度。
                layoutManager = GridLayoutManager(context, 5)
                isNestedScrollingEnabled = false
                clipChildren = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val adapter = GridAdapter(
                data = list,
                isSelected = { it in selected },
                onToggle = { fv, pos, holder ->
                    if (!selected.add(fv.key)) selected.remove(fv.key)
                    adapters.forEach { it.notifyItemChanged(pos) }
                    holder.updateSelection(fv.key in selected)
                    refreshBottom(countText, btnCancelRecommend, btnRecommend, btnQueue, btnPlay, flat, selected, flat.size)
                }
            )
            grid.adapter = adapter
            adapters.add(adapter)
            contentColumn.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (firstCardHolder[0] == null) {
                grid.post {
                    if (firstCardHolder[0] == null) firstCardHolder[0] = grid.findViewHolderForAdapterPosition(0)?.itemView
                }
            }
            isFirstGroup = false
        }

        refreshBottom(countText, btnCancelRecommend, btnRecommend, btnQueue, btnPlay, flat, selected, flat.size)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnCancelRecommend.setOnClickListener {
            if (!btnCancelRecommend.isEnabled) return@setOnClickListener
            val picked = flat.filter { it.key in selected }
            val byColl = picked.groupBy { it.collectionId }
            Toast.makeText(context, "正在取消推荐…", Toast.LENGTH_SHORT).show()
            Thread({
                val store = RecommendationsStore(context)
                var allOk = true
                byColl.forEach { (collId, vList) ->
                    val ok = store.cancelMyRecommendations(collId, vList.map { it.video.uniqueKey() }.toSet())
                    if (!ok) allOk = false
                }
                btnCancelRecommend.post {
                    if (allOk) {
                        Toast.makeText(context, "取消推荐成功", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "部分取消失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }, "incoming-cancel-rec").start()
        }
        btnRecommend.setOnClickListener {
            if (!btnRecommend.isEnabled) return@setOnClickListener
            val picked = flat.filter { it.key in selected }
            val byColl = picked.groupBy { it.collectionId to it.collectionName }
            Toast.makeText(context, "正在同步推荐…", Toast.LENGTH_SHORT).show()
            Thread({
                val store = RecommendationsStore(context)
                var allOk = true
                byColl.forEach { (pair, vList) ->
                    val ok = store.recommendVideos(pair.first, pair.second, vList.map { it.video })
                    if (!ok) allOk = false
                }
                btnRecommend.post {
                    if (allOk) {
                        Toast.makeText(context, "推荐成功", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "部分推荐失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }, "incoming-recommend").start()
        }
        btnQueue.setOnClickListener {
            if (!btnQueue.isEnabled) return@setOnClickListener
            val picked = flat.filter { it.key in selected }
            if (picked.isEmpty()) return@setOnClickListener
            val queue = PlayQueueStore.get(context)
            picked.forEach { fv ->
                queue.add(fv.video.title, fv.video.url, "cloud_recommend")
            }
            Toast.makeText(context, "已加入稍后播放：${picked.size} 个", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        btnPlay.setOnClickListener {
            if (!btnPlay.isEnabled) return@setOnClickListener
            val picked = flat.filter { it.key in selected }
            if (picked.isEmpty()) return@setOnClickListener
            val queue = PlayQueueStore.get(context)
            val first = picked.first()
            // 剩余按用户勾选顺序（迭代顺序）追加到末尾。
            picked.drop(1).forEach { fv -> queue.add(fv.video.title, fv.video.url, "cloud_recommend") }
            // 首条追加到队首并立刻由 Activity 拉起播放器。
            queue.prepend(first.video.title, first.video.url, "cloud_recommend")
            dialog.dismiss()
            onPlayVideo(first.video.title, first.video.url, "cloud_recommend")
        }

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setGravity(Gravity.CENTER)
                setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                (decorView as? ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
            }
            // 覆盖写入本地「今日已见推荐」，本次启动此后不再重复触发（PRD 差集判定基线）。
            SeenRecommendationsStore(context).overwriteToToday(flat.map {
                SeenRecommendationsStore.SeenItem(it.collectionId, it.video.uniqueKey())
            })
            // 默认焦点：播放推荐内容按钮。
            btnPlay.post { btnPlay.requestFocus() }
        }
        dialog.setOnDismissListener { onDone() }
        dialog.show()
    }

    private fun buildRecommenderText(recommenders: List<RecommendationsStore.Recommender>): String {
        if (recommenders.isEmpty()) return "未知用户推荐"
        val unique = linkedMapOf<String, RecommendationsStore.Recommender>()
        recommenders.forEach { rec ->
            val key = rec.creatorId.ifBlank { rec.name }
            if (key.isNotBlank() && unique[key] == null) unique[key] = rec
        }
        val ordered = if (unique.isNotEmpty()) unique.values.toList() else recommenders
        val firstName = ordered.firstOrNull()?.name?.ifBlank { "未知设备" } ?: "未知设备"
        return if (ordered.size <= 1) {
            "$firstName 推荐"
        } else {
            "$firstName 等${ordered.size}名用户推荐"
        }
    }

    private fun refreshBottom(
        countText: TextView,
        btnCancelRecommend: View,
        btnRecommend: View,
        btnQueue: View,
        btnPlay: View,
        flat: List<FlatVideo>,
        selected: Set<String>,
        total: Int
    ) {
        val selectedCount = selected.size
        countText.text = "已选择 $selectedCount / $total"
        val hasSel = selectedCount > 0
        val picked = flat.filter { it.key in selected }
        val allSelectedAlreadyRecommended = hasSel && picked.all { it.isAlreadyRecommended }

        setEnabled(btnCancelRecommend, allSelectedAlreadyRecommended)
        setEnabled(btnRecommend, hasSel)
        setEnabled(btnQueue, hasSel)
        setEnabled(btnPlay, hasSel)
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.isFocusable = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    private fun dialogButton(text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(20), 0, dp(20), 0)
        tv.isFocusable = true
        tv.isFocusableInTouchMode = false
        val refresh: (Boolean) -> Unit = { focused ->
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(60).toFloat()
                setColor(Color.argb(51, 27, 31, 38))
                setStroke(dp(2), if (focused) WARM else SILVER)
            }
            tv.setTextColor(Color.WHITE)
            FocusFxHelper.applyFocusFxState(tv, focused, cornerRadiusDp = 60)
        }
        refresh(false)
        tv.setOnFocusChangeListener { _, has -> refresh(has) }
        return tv
    }

    private fun createTitleSticker(): ClippedImageView = ClippedImageView(context).apply {
        setImageResource(R.drawable.sticker_bochan)
        foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.92f
        rotation = -8f
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        setCircle(true)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private class GridAdapter(
        private val data: List<FlatVideo>,
        private val isSelected: (String) -> Boolean,
        private val onToggle: (FlatVideo, Int, VH) -> Unit
    ) : RecyclerView.Adapter<GridAdapter.VH>() {

        class VH(val root: View) : RecyclerView.ViewHolder(root) {
            private val thumbContainer: View = root.findViewById(R.id.batchThumbContainer)
            val thumb: ImageView = root.findViewById(R.id.batchThumb)
            val seq: TextView = root.findViewById(R.id.batchSeq)
            val title: TextView = root.findViewById(R.id.batchTitle)
            val check: TextView = root.findViewById(R.id.batchCheck)
            val recommendedBadge: TextView = root.findViewById(R.id.batchRecommendedBadge)

            fun updateThumbAspect() {
                root.post {
                    val availableWidth = root.width - root.paddingLeft - root.paddingRight
                    if (availableWidth <= 0) return@post
                    val targetHeight = (availableWidth * 9f / 16f).toInt()
                    val lp = thumbContainer.layoutParams
                    if (lp.height != targetHeight) {
                        lp.height = targetHeight
                        thumbContainer.layoutParams = lp
                    }
                }
            }

            fun updateSelection(selected: Boolean) {
                root.isSelected = selected
                check.visibility = if (selected) View.VISIBLE else View.GONE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_batch_video, parent, false)
            v.setBackgroundResource(R.drawable.bg_incoming_recommendation_video_card)
            v.clipToOutline = false
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.seq.text = (position + 1).toString()
            holder.title.text = item.video.title.ifBlank { item.video.url }
            // 云端 cover 为空则用视频链接生成缩略图占位（Thumbnails.load 兼容非本地路径时会显示占位图）。
            Thumbnails.load(holder.thumb, item.video.cover.ifBlank { item.video.url })
            holder.updateThumbAspect()
            holder.updateSelection(isSelected(item.key))
            holder.recommendedBadge.visibility = if (item.isAlreadyRecommended) View.VISIBLE else View.GONE
            holder.root.setOnClickListener { onToggle(item, holder.bindingAdapterPosition, holder) }
            holder.root.setOnFocusChangeListener { v, has ->
                v.animate()
                    .scaleX(if (has) 1.05f else 1f)
                    .scaleY(if (has) 1.05f else 1f)
                    .setDuration(140L)
                    .start()
            }
        }
    }
}
