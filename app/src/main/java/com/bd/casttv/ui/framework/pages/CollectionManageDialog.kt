package com.bd.casttv.ui.framework.pages

import android.animation.ObjectAnimator
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore

/**
 * 合集管理弹窗（v1.1.125 逻辑迁移到新框架的独立封装版本）：
 *  - 只展示「非预置 && 非默认」的合集参与排序 / 删除；
 *  - 支持勾选、全选、上移 / 下移（单选或连续多选）、批量删除、保存排序；
 *  - 点击 Back / 取消不保存；点击「确认」调用 [FavoritesStore.reorderCollections] 落盘。
 */
class CollectionManageDialog(
    private val context: Context,
    private val store: FavoritesStore,
    private val onDone: () -> Unit
) {
    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_collection_manage, null)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(view).create()

        val listView = view.findViewById<RecyclerView>(R.id.collectionManageList)
        val emptyView = view.findViewById<TextView>(R.id.collectionManageEmpty)
        val countView = view.findViewById<TextView>(R.id.collectionManageCount)
        val btnUp = view.findViewById<TextView>(R.id.btnCollectionManageMoveUp)
        val btnDown = view.findViewById<TextView>(R.id.btnCollectionManageMoveDown)
        val btnSelectAll = view.findViewById<TextView>(R.id.btnCollectionManageSelectAll)
        val btnClear = view.findViewById<TextView>(R.id.btnCollectionManageClear)
        val btnDelete = view.findViewById<TextView>(R.id.btnCollectionManageDelete)
        val btnCancel = view.findViewById<TextView>(R.id.btnCollectionManageCancel)
        val btnConfirm = view.findViewById<TextView>(R.id.btnCollectionManageConfirm)

        val data: MutableList<FavoritesStore.CollectionInfo> = try {
            store.collectionsInfo().filter { !it.isDefault && !it.isPreset }.toMutableList()
        } catch (_: Throwable) { mutableListOf() }
        val selected = linkedSetOf<String>()

        listView?.layoutManager = LinearLayoutManager(context)
        val adapter = Adapter(data, selected)
        listView?.adapter = adapter

        fun refreshCount() {
            countView?.text = context.getString(R.string.fav_collection_manage_selected_count, selected.size)
            emptyView?.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            listView?.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        }
        adapter.onSelectionChanged = { refreshCount() }
        refreshCount()

        fun isContinuous(): Boolean {
            if (selected.isEmpty()) return false
            val idxs = data.mapIndexedNotNull { i, c -> if (c.id in selected) i else null }
            if (idxs.size != selected.size) return false
            for (k in 1 until idxs.size) if (idxs[k] != idxs[k - 1] + 1) return false
            return true
        }

        fun move(direction: Int) {
            if (!isContinuous()) {
                Toast.makeText(context, "请勾选单个或连续多个合集后再排序", Toast.LENGTH_SHORT).show()
                return
            }
            val idxs = data.mapIndexedNotNull { i, c -> if (c.id in selected) i else null }
            val hi = idxs.first(); val lo = idxs.last()
            if ((direction < 0 && hi == 0) || (direction > 0 && lo == data.size - 1)) {
                shake(listView ?: view)
                return
            }
            if (direction < 0) {
                val above = data.removeAt(hi - 1)
                data.add(lo, above)
            } else {
                val below = data.removeAt(lo + 1)
                data.add(hi, below)
            }
            adapter.notifyDataSetChanged()
            refreshCount()
        }
        btnUp?.setOnClickListener { move(-1) }
        btnDown?.setOnClickListener { move(1) }
        btnSelectAll?.setOnClickListener { selected.clear(); data.forEach { selected.add(it.id) }; adapter.notifyDataSetChanged(); refreshCount() }
        btnClear?.setOnClickListener { selected.clear(); adapter.notifyDataSetChanged(); refreshCount() }
        btnDelete?.setOnClickListener {
            if (selected.isEmpty()) { Toast.makeText(context, "请先勾选要删除的合集", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
                .setTitle(R.string.fav_collection_manage_delete_title)
                .setMessage(context.getString(R.string.fav_collection_manage_delete_confirm, selected.size))
                .setPositiveButton(R.string.fav_collection_manage_delete_ok) { d, _ ->
                    val deletedCount = store.deleteCollections(selected.toList())
                    Toast.makeText(context, context.getString(R.string.fav_collection_manage_delete_result, deletedCount), Toast.LENGTH_SHORT).show()
                    data.removeAll { it.id in selected }
                    selected.clear(); adapter.notifyDataSetChanged(); refreshCount()
                    d.dismiss()
                }
                .setNegativeButton(R.string.fav_collection_manage_delete_cancel) { d, _ -> d.dismiss() }
                .show()
        }
        btnCancel?.setOnClickListener { dialog.dismiss() }
        btnConfirm?.setOnClickListener {
            val newOrder = mutableListOf<String>()
            store.collectionsInfo().forEach { info -> if (info.isDefault || info.isPreset) newOrder.add(info.id) }
            data.forEach { newOrder.add(it.id) }
            store.reorderCollections(newOrder)
            Toast.makeText(context, "已保存排序", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setOnDismissListener { onDone() }
        dialog.show()
    }

    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -8f, 8f, -6f, 6f, -3f, 3f, 0f).setDuration(260).start()
    }

    private class Adapter(
        private val data: MutableList<FavoritesStore.CollectionInfo>,
        private val selected: MutableSet<String>
    ) : RecyclerView.Adapter<Adapter.VH>() {
        var onSelectionChanged: (() -> Unit)? = null
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_collection_manage, parent, false)
            return VH(v)
        }
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.name.text = c.name
            holder.itemCount.text = holder.itemView.context.getString(R.string.fav_collection_manage_item_count, c.itemCount)
            val isChecked = c.id in selected
            holder.checkBox.isSelected = isChecked
            holder.checkMark.visibility = if (isChecked) View.VISIBLE else View.GONE
            holder.root.isSelected = isChecked
            val toggle = View.OnClickListener {
                val id = data[holder.bindingAdapterPosition].id
                if (id in selected) selected.remove(id) else selected.add(id)
                notifyItemChanged(holder.bindingAdapterPosition)
                onSelectionChanged?.invoke()
            }
            holder.root.setOnClickListener(toggle)
            holder.root.setOnKeyListener { _, kc, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER)) { toggle.onClick(holder.root); true } else false
            }
        }
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view
            val checkBox: View = view.findViewById(R.id.collectionManageCheckBox)
            val checkMark: View = view.findViewById(R.id.collectionManageCheckMark)
            val name: TextView = view.findViewById(R.id.collectionManageName)
            val itemCount: TextView = view.findViewById(R.id.collectionManageItemCount)
        }
    }
}
