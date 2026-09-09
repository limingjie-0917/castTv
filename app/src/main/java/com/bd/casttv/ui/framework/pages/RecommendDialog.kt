package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.displayThumbPath
import com.bd.casttv.sync.RecommenderIdentity
import com.bd.casttv.sync.RecommendationsStore
import com.bd.casttv.sync.VisibleUsersStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.Thumbnails

/**
 * 「收藏页推荐合集」发起端：设为推荐弹窗（多选 + 二次确认）。
 *
 *  - 宝蓝主题面板 (bg_favorites_dialog_panel) + 圆形贴纸头 + 暖黄标题；
 *  - 视频卡片 5 列网格，复用 `R.layout.item_batch_video` 保持视觉一致；
 *  - 底部按钮：取消 / 全选(全部勾选后隐藏) / 取消勾选(有勾选才显) / 设为推荐(未勾选置灰不可焦)。
 *
 * 网络写入统一通过 [RecommendationsStore.recommend] 完成：CAS 重试 3 次，写侧 GC 非当天条目。
 * 组件保持默认按钮三态（AGENT.md 通用样式）：默认浅灰字/浅灰边框，焦点态仅边框暖黄。
 */
class RecommendDialog(
    private val context: Context,
    private val store: FavoritesStore,
    private val collection: FavoritesStore.FavoriteCollection,
    private val onDone: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val WARM = Color.rgb(255, 215, 0)
    private val SILVER = Color.rgb(192, 192, 192)
    private val LIGHT_ICON_GRAY = Color.rgb(191, 195, 204)

    fun show() {
        val items = collection.items
        if (items.isEmpty()) {
            Toast.makeText(context, "当前合集暂无视频", Toast.LENGTH_SHORT).show()
            return
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_favorites_dialog_panel)
            setPadding(dp(28), dp(20), dp(28), dp(18))
            clipChildren = false
            clipToPadding = false
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "设为推荐 · ${collection.name}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            background = null
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val countText = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            text = "已选择 0 项"
        }
        header.addView(createTitleSticker(), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        header.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(countText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        panel.addView(header)

        val grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 5)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val selectedIds = linkedSetOf<String>()
        val recommendedIds = linkedSetOf<String>()

        val btnCancel = mkButton("取消")
        val btnSelectAll = mkButton("全选")
        val btnUnselect = mkButton("取消勾选")
        val btnCancelRecommend = mkButton("取消推荐")
        val btnRecommend = mkButton("推荐")

        listOf(btnCancel, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend).forEach { b ->
            bottom.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginStart = dp(6); marginEnd = dp(6); topMargin = dp(4); bottomMargin = dp(4)
            })
        }
        panel.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()

        val adapter = RecommendGridAdapter(
            data = items,
            isSelected = { it in selectedIds },
            isRecommended = { it in recommendedIds },
            onToggle = { item, pos ->
                val key = item.recommendationKey()
                if (!selectedIds.add(key)) selectedIds.remove(key)
                grid.adapter?.notifyItemChanged(pos)
                refreshBottom(countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, recommendedIds, items.size)
            }
        )
        grid.adapter = adapter

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSelectAll.setOnClickListener {
            if (!btnSelectAll.isEnabled) return@setOnClickListener
            selectedIds.clear()
            items.forEach { selectedIds.add(it.recommendationKey()) }
            adapter.notifyDataSetChanged()
            refreshBottom(countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, recommendedIds, items.size)
        }
        btnUnselect.setOnClickListener {
            if (!btnUnselect.isEnabled) return@setOnClickListener
            selectedIds.clear()
            adapter.notifyDataSetChanged()
            refreshBottom(countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, recommendedIds, items.size)
            btnSelectAll.post { btnSelectAll.requestFocus() }
        }
        btnCancelRecommend.setOnClickListener {
            if (!btnCancelRecommend.isEnabled) return@setOnClickListener
            val keys = selectedIds.toSet()
            if (keys.isEmpty() || !keys.all { it in recommendedIds }) return@setOnClickListener
            showCancelConfirmDialog(keys.size) {
                doCancelRecommendations(keys) { ok ->
                    if (ok) {
                        recommendedIds.removeAll(keys)
                        selectedIds.clear()
                        adapter.notifyDataSetChanged()
                        refreshBottom(countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, recommendedIds, items.size)
                        Toast.makeText(context, "已取消推荐 ${keys.size} 个视频", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "取消推荐失败，请稍后重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        btnRecommend.setOnClickListener {
            if (!btnRecommend.isEnabled) return@setOnClickListener
            val picked = items.filter { it.recommendationKey() in selectedIds }
            if (picked.isEmpty()) return@setOnClickListener
            showRecommendScopeDialog(picked) { targetUsers ->
                showConfirmDialog(picked.size, targetUsers.size) {
                    doUpload(picked, targetUsers) { ok ->
                        if (ok) {
                            recommendedIds.clear()
                            recommendedIds.addAll(picked.map { it.recommendationKey() })
                            adapter.notifyDataSetChanged()
                            val scopeText = if (targetUsers.isEmpty()) "所有人" else "指定 ${targetUsers.size} 人"
                            Toast.makeText(context, "已推荐 ${picked.size} 个视频给$scopeText", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onDone()
                        } else {
                            Toast.makeText(context, "推荐失败，请稍后重试", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        refreshBottom(countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, recommendedIds, items.size)
        loadRecommendedState(recommendedIds, adapter, countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, items.size)

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val dm = context.resources.displayMetrics
            setLayout((dm.widthPixels * 0.9f).toInt(), (dm.heightPixels * 0.88f).toInt())
        }
        grid.post {
            val holder = grid.findViewHolderForAdapterPosition(0)
            if (holder?.itemView?.requestFocus() != true) {
                grid.postDelayed({ grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: grid.requestFocus() }, 100L)
            }
        }
    }

    private fun refreshBottom(
        countText: TextView,
        btnSelectAll: View,
        btnUnselect: View,
        btnCancelRecommend: View,
        btnRecommend: View,
        selectedIds: Set<String>,
        recommendedIds: Set<String>,
        total: Int
    ) {
        val selected = selectedIds.size
        countText.text = "已选择 $selected 项"
        val allSel = total > 0 && selected >= total
        val hasSel = selected > 0
        val allSelectedRecommended = hasSel && selectedIds.all { it in recommendedIds }
        // 全选/全不选：全部勾选后自动隐藏（与批量弹窗一致），有勾选时才展示取消勾选。
        btnSelectAll.visibility = if (allSel) View.GONE else View.VISIBLE
        btnUnselect.visibility = if (hasSel) View.VISIBLE else View.GONE
        // 「推荐」按钮：未勾选时置灰不可焦；原上传覆盖/合并逻辑保持。
        setEnabled(btnRecommend, hasSel)
        // 「取消推荐」按钮：只有勾选项全部都是已推荐卡片时可用；混选或未选中均置灰。
        setEnabled(btnCancelRecommend, allSelectedRecommended)
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.isFocusable = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    private fun mkButton(text: String): TextView {
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
                setStroke(dp(if (focused) 3 else 2), if (focused) WARM else SILVER)
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

    private fun showRecommendScopeDialog(
        picked: List<FavoritesStore.FavoriteItem>,
        onScopePicked: (List<String>) -> Unit
    ) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_favorites_dialog_panel)
            setPadding(dp(26), dp(22), dp(26), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        val title = TextView(context).apply {
            text = "选择推荐范围"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            background = null
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val message = TextView(context).apply {
            text = "将推荐当前合集「${collection.name}」中已选的 ${picked.size} 个视频。你可以推荐给所有人，也可以只推荐给最多 5 位已开启「推荐可见」的用户。"
            textSize = 15f
            setTextColor(Color.argb(230, 220, 224, 235))
            setPadding(0, dp(14), 0, dp(8))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        val btnCancel = mkButton("取消")
        val btnAll = mkButton("推荐给所有人")
        val btnSpecific = mkButton("推荐给指定用户")
        row.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        row.addView(btnAll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) })
        row.addView(btnSpecific, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) })
        panel.addView(title)
        panel.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAll.setOnClickListener { dialog.dismiss(); onScopePicked(emptyList()) }
        btnSpecific.setOnClickListener {
            dialog.dismiss()
            loadVisibleUsersThenShowPicker(onScopePicked)
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(680), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        btnAll.post { btnAll.requestFocus() }
    }

    private fun loadVisibleUsersThenShowPicker(onScopePicked: (List<String>) -> Unit) {
        Toast.makeText(context, "正在获取可推荐用户…", Toast.LENGTH_SHORT).show()
        Thread({
            val myCreatorId = runCatching { RecommenderIdentity.creatorId(context) }.getOrDefault("")
            val users = try {
                VisibleUsersStore(context).fetchVisibleUsers()
                    ?.filter { it.creatorId != myCreatorId }
                    .orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
            ui.post {
                if (users.isEmpty()) {
                    Toast.makeText(context, "暂无已开启推荐可见的用户", Toast.LENGTH_LONG).show()
                } else {
                    showSpecificUsersDialog(users, onScopePicked)
                }
            }
        }, "recommend-visible-users").start()
    }

    private fun showSpecificUsersDialog(
        users: List<VisibleUsersStore.VisibleUser>,
        onScopePicked: (List<String>) -> Unit
    ) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_favorites_dialog_panel)
            setPadding(dp(26), dp(22), dp(26), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        val selected = linkedSetOf<String>()
        val title = TextView(context).apply {
            text = "选择指定用户"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val countText = TextView(context).apply {
            text = "已选择 0 / 5"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            setPadding(0, dp(8), 0, dp(10))
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        fun refreshCount() {
            countText.text = "已选择 ${selected.size} / 5"
        }
        users.forEach { user ->
            val row = TextView(context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(16), 0, dp(16), 0)
                fun render(focused: Boolean) {
                    val checked = user.creatorId in selected
                    text = "${if (checked) "✓" else "□"}  ${user.deviceName.ifBlank { "未知设备" }}"
                    setTextColor(if (checked) WARM else Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.argb(if (focused) 78 else 42, 27, 31, 38))
                        setStroke(dp(if (focused) 2 else 1), if (focused) WARM else SILVER)
                    }
                    FocusFxHelper.applyFocusFxState(this, focused, cornerRadiusDp = 12)
                }
                render(false)
                setOnFocusChangeListener { _, has -> render(has) }
                setOnClickListener {
                    if (user.creatorId in selected) {
                        selected.remove(user.creatorId)
                    } else if (selected.size >= 5) {
                        Toast.makeText(context, "最多只能选择 5 位用户", Toast.LENGTH_SHORT).show()
                    } else {
                        selected.add(user.creatorId)
                    }
                    refreshCount()
                    render(hasFocus())
                }
            }
            listContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) })
        }
        val scroll = android.widget.ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(listContainer)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        val btnCancel = mkButton("取消")
        val btnConfirm = mkButton("确认选择")
        buttonRow.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        buttonRow.addView(btnConfirm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(14) })
        panel.addView(title)
        panel.addView(countText)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
        panel.addView(buttonRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(context, "请至少选择 1 位用户", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val targets = selected.toList()
            dialog.dismiss()
            onScopePicked(targets)
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(620), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        listContainer.getChildAt(0)?.requestFocus()
    }

    private fun showConfirmDialog(count: Int, targetUserCount: Int, onConfirm: () -> Unit) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_favorites_dialog_panel)
            setPadding(dp(26), dp(22), dp(26), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        val title = TextView(context).apply {
            text = "确认推荐到云端？"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            background = null
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val message = TextView(context).apply {
            text = if (targetUserCount <= 0) {
                "将把当前合集「${collection.name}」中已选的 $count 个视频推荐到云端。\n所有配对设备今天启动 App 时都会看到本次推荐。"
            } else {
                "将把当前合集「${collection.name}」中已选的 $count 个视频推荐给指定 $targetUserCount 位用户。\n只有被选中的用户今天启动 App 时会看到本次推荐。"
            }
            textSize = 15f
            setTextColor(Color.argb(230, 220, 224, 235))
            setPadding(0, dp(14), 0, dp(8))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnCancel = mkButton("取消")
        val btnConfirm = mkButton("确认推荐")
        row.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        row.addView(btnConfirm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(14) })

        panel.addView(title)
        panel.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener { dialog.dismiss(); onConfirm() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(520), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // 默认焦点在「确认推荐」。
        btnConfirm.post { btnConfirm.requestFocus() }
    }

    private fun showCancelConfirmDialog(count: Int, onConfirm: () -> Unit) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_favorites_dialog_panel)
            setPadding(dp(26), dp(22), dp(26), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        val title = TextView(context).apply {
            text = "确认取消推荐？"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            background = null
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val message = TextView(context).apply {
            text = "将从云端推荐中移除当前已选的 $count 个视频。"
            textSize = 15f
            setTextColor(Color.argb(230, 220, 224, 235))
            setPadding(0, dp(14), 0, dp(8))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnCancel = mkButton("取消")
        val btnConfirm = mkButton("确认取消推荐")
        row.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        row.addView(btnConfirm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(14) })

        panel.addView(title)
        panel.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener { dialog.dismiss(); onConfirm() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(520), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        btnConfirm.post { btnConfirm.requestFocus() }
    }

    private fun doCancelRecommendations(keys: Set<String>, onFinished: (Boolean) -> Unit) {
        Toast.makeText(context, "正在取消推荐 ${keys.size} 个视频…", Toast.LENGTH_SHORT).show()
        Thread({
            val ok = try {
                RecommendationsStore(context).cancelMyRecommendations(collection.id, keys)
            } catch (_: Throwable) {
                false
            }
            ui.post { onFinished(ok) }
        }, "recommend-cancel").start()
    }

    private fun loadRecommendedState(
        recommendedIds: MutableSet<String>,
        adapter: RecommendGridAdapter,
        countText: TextView,
        btnSelectAll: View,
        btnUnselect: View,
        btnCancelRecommend: View,
        btnRecommend: View,
        selectedIds: Set<String>,
        total: Int
    ) {
        Thread({
            val keys = try { RecommendationsStore(context).fetchMyRecommendedVideoKeysForToday(collection.id) } catch (_: Throwable) { null }
            ui.post {
                if (keys == null) return@post
                recommendedIds.clear()
                recommendedIds.addAll(keys)
                adapter.notifyDataSetChanged()
                refreshBottom(countText, btnSelectAll, btnUnselect, btnCancelRecommend, btnRecommend, selectedIds, recommendedIds, total)
            }
        }, "recommend-state").start()
    }

    private fun FavoritesStore.FavoriteItem.recommendationKey(): String = itemId.ifBlank { id.ifBlank { uri } }

    private fun doUpload(
        picked: List<FavoritesStore.FavoriteItem>,
        targetUsers: List<String>,
        onFinished: (Boolean) -> Unit
    ) {
        // 简单的进度提示 Toast，云端 IO 走后台线程。
        val scopeText = if (targetUsers.isEmpty()) "所有人" else "指定 ${targetUsers.size} 人"
        Toast.makeText(context, "正在推荐 ${picked.size} 个视频给$scopeText…", Toast.LENGTH_SHORT).show()
        Thread({
            val ok = try {
                RecommendationsStore(context).recommend(collection, picked, targetUsers)
            } catch (_: Throwable) {
                false
            }
            ui.post { onFinished(ok) }
        }, "recommend-upload").start()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /**
     * 复用 `R.layout.item_batch_video` 的视频卡片视图（缩略图+序号+标题+右下对勾）。
     * 视图不改动 XML，仅在 Kotlin 层控制选中态可见性。
     */
    private class RecommendGridAdapter(
        private val data: List<FavoritesStore.FavoriteItem>,
        private val isSelected: (String) -> Boolean,
        private val isRecommended: (String) -> Boolean,
        private val onToggle: (FavoritesStore.FavoriteItem, Int) -> Unit
    ) : RecyclerView.Adapter<RecommendGridAdapter.VH>() {

        class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val thumb: ImageView = root.findViewById(R.id.batchThumb)
            val seq: TextView = root.findViewById(R.id.batchSeq)
            val title: TextView = root.findViewById(R.id.batchTitle)
            val check: ImageView = root.findViewById(R.id.batchCheck)
            val recommendedBadge: TextView = root.findViewById(R.id.batchRecommendedBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_batch_video, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.seq.text = (position + 1).toString()
            holder.title.text = item.title.ifBlank { item.uri }
            Thumbnails.load(holder.thumb, item.displayThumbPath())
            val key = item.itemId.ifBlank { item.id.ifBlank { item.uri } }
            val selected = isSelected(key)
            holder.root.isSelected = selected
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE
            holder.recommendedBadge.visibility = if (isRecommended(key)) View.VISIBLE else View.GONE
            holder.root.setOnClickListener { onToggle(item, holder.bindingAdapterPosition) }
        }
    }
}
