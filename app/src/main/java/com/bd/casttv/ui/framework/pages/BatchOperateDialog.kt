package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.displayThumbPath
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.Thumbnails

/**
 * 新框架下的收藏页「批量操作」弹窗，1:1 复用旧版
 * [com.bd.casttv.ui.MainActivity.showBatchOperateDialog] 的样式与交互：
 *  - 复用 `R.layout.dialog_batch_operate` + `R.layout.item_batch_video`；
 *  - 5 列网格展示当前合集全部视频，OK 选中/反选，右下角对勾角标；
 *  - 底部动态按钮：全选 / 取消勾选 / 删除 / 移动 / 稍后播放 / 取消；
 *  - 删除/移动写盘走后台线程，结果回主线程刷新；
 *  - 弹窗宽 0.92 屏、高 0.9 屏，深色 Theme.CastTV.Dialog；
 *  - 关闭后调用 [onDone] 触发外部刷新。
 */
class BatchOperateDialog(
    private val context: Context,
    private val store: FavoritesStore,
    private val collectionId: String,
    private val onDone: () -> Unit
) {

    private val ui = Handler(Looper.getMainLooper())

    fun show() {
        val allItems = store.collection(collectionId)?.items.orEmpty()
        if (allItems.isEmpty()) {
            Toast.makeText(context, "当前合集暂无视频", Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_batch_operate, null)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(view).create()

        val grid = view.findViewById<RecyclerView>(R.id.batchGrid)
        val countText = view.findViewById<TextView>(R.id.batchDialogCount)
        val btnSelectAll = view.findViewById<TextView>(R.id.batchBtnSelectAll)
        val btnUnselect = view.findViewById<TextView>(R.id.batchBtnUnselect)
        val btnDelete = view.findViewById<TextView>(R.id.batchBtnDelete)
        val btnMove = view.findViewById<TextView>(R.id.batchBtnMove)
        val btnLater = view.findViewById<TextView>(R.id.batchBtnLater)
        val btnCancel = view.findViewById<TextView>(R.id.batchBtnCancel)

        val selectedItemIds = linkedSetOf<String>()
        listOf(btnSelectAll, btnUnselect, btnDelete, btnMove, btnLater, btnCancel).forEach(::bindDialogButtonFx)

        fun updateBottomButtons() {
            val sel = selectedItemIds.size
            val total = allItems.size
            countText.text = "已选择 $sel 项"
            val hasSel = sel > 0
            val allSel = total > 0 && sel >= total
            btnUnselect.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnDelete.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnMove.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnLater.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnSelectAll.isEnabled = !allSel
            btnSelectAll.isFocusable = !allSel
            btnSelectAll.alpha = if (allSel) 0.4f else 1f
        }

        val adapter = BatchVideoAdapter(
            data = allItems,
            isSelected = { id -> id in selectedItemIds },
            onToggle = { item, pos ->
                if (!selectedItemIds.add(item.itemId)) selectedItemIds.remove(item.itemId)
                grid.adapter?.notifyItemChanged(pos)
                updateBottomButtons()
            }
        )
        grid.layoutManager = GridLayoutManager(context, 5)
        grid.adapter = adapter
        (grid.parent as? ViewGroup)?.clipChildren = false

        btnSelectAll.setOnClickListener {
            if (!btnSelectAll.isEnabled) return@setOnClickListener
            selectedItemIds.clear()
            allItems.forEach { selectedItemIds.add(it.itemId) }
            adapter.notifyDataSetChanged()
            updateBottomButtons()
        }
        btnUnselect.setOnClickListener {
            selectedItemIds.clear()
            adapter.notifyDataSetChanged()
            updateBottomButtons()
            btnSelectAll.post { btnSelectAll.requestFocus() }
        }
        btnDelete.setOnClickListener {
            val ids = selectedItemIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            Thread {
                val result = store.removeItemsByItemId(collectionId, ids)
                ui.post {
                    if (result == FavoritesStore.OpResult.SUCCESS) {
                        Toast.makeText(context, "已删除 ${ids.size} 个内容", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onDone()
                    } else {
                        Toast.makeText(context, opResultMessage(result), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        btnMove.setOnClickListener {
            val ids = selectedItemIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            showBatchMoveTargetDialog(ids) { dialog.dismiss() }
        }
        btnLater.setOnClickListener {
            val ids = selectedItemIds.toSet()
            val items = allItems.filter { it.itemId in ids }
            if (items.isEmpty()) return@setOnClickListener
            val queue = PlayQueueStore.get(context)
            items.forEach { queue.add(it.title, it.uri, it.source.ifBlank { "favorite" }) }
            Toast.makeText(context, "已加入稍后播放：${items.size} 个", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onDone()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { /* onDone by caller after real ops */ }

        updateBottomButtons()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val dm = context.resources.displayMetrics
            setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
        }
        grid.post {
            val vh = grid.findViewHolderForAdapterPosition(0)
            if (vh?.itemView?.requestFocus() != true) {
                grid.postDelayed({
                    grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: grid.requestFocus()
                }, 100L)
            }
        }
    }

    /**
     * 批量「移动」目标合集选择器：Android 原生 AlertDialog 列表（非 Material），套用 App 深色主题。
     * 复用旧版行为：目标列表排除当前合集，选中后后台线程写盘并回主线程刷新。
     */
    private fun showBatchMoveTargetDialog(itemIds: List<String>, onMoved: () -> Unit) {
        val targets = store.collectionsInfo().filter { it.id != collectionId }
        if (targets.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.favorite_move_no_target), Toast.LENGTH_SHORT).show()
            return
        }
        val names = targets.map { it.name }.toTypedArray()
        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setTitle("移动到合集")
            .setItems(names) { d, which ->
                val target = targets[which]
                Thread {
                    val result = store.moveItemsByItemId(collectionId, itemIds, target.id)
                    ui.post {
                        if (result == FavoritesStore.OpResult.SUCCESS) {
                            Toast.makeText(context, "已移动 ${itemIds.size} 个内容到 ${target.name}", Toast.LENGTH_SHORT).show()
                            d.dismiss()
                            onMoved()
                            onDone()
                        } else {
                            Toast.makeText(context, opResultMessage(result), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun bindDialogButtonFx(button: TextView) {
        FocusFxHelper.applyFocusFxState(button, false, cornerRadiusDp = 60)
        button.setOnFocusChangeListener { _, hasFocus ->
            FocusFxHelper.applyFocusFxState(button, hasFocus, cornerRadiusDp = 60)
        }
    }

    private fun opResultMessage(r: FavoritesStore.OpResult): String = when (r) {
        FavoritesStore.OpResult.SUCCESS -> "操作成功"
        FavoritesStore.OpResult.NOT_FOUND -> "未找到目标合集或视频"
        FavoritesStore.OpResult.NOT_ALLOWED -> "该合集不允许此操作"
        FavoritesStore.OpResult.INVALID -> "参数非法"
        FavoritesStore.OpResult.LIMIT_TOTAL -> "已达总收藏上限"
        FavoritesStore.OpResult.LIMIT_COLLECTION_ITEMS -> "目标合集条目已满"
        FavoritesStore.OpResult.LIMIT_COLLECTIONS -> "合集数量已满"
        FavoritesStore.OpResult.FAILED -> "写入失败，请重试"
    }

    private class BatchVideoAdapter(
        private val data: List<FavoritesStore.FavoriteItem>,
        private val isSelected: (String) -> Boolean,
        private val onToggle: (FavoritesStore.FavoriteItem, Int) -> Unit
    ) : RecyclerView.Adapter<BatchVideoAdapter.VH>() {
        class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val thumb: ImageView = root.findViewById(R.id.batchThumb)
            val seq: TextView = root.findViewById(R.id.batchSeq)
            val title: TextView = root.findViewById(R.id.batchTitle)
            val check: TextView = root.findViewById(R.id.batchCheck)
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
            val selected = isSelected(item.itemId)
            holder.root.isSelected = selected
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE
            holder.root.setOnClickListener { onToggle(item, holder.bindingAdapterPosition) }
        }
    }
}
