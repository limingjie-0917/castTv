package com.bd.casttv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.databinding.ItemCustomDockResourceBinding
import com.bd.casttv.favorites.HealthStatus
import com.bd.casttv.favorites.TabChannel

/**
 * 自定义 Tab 左侧「频道列表」适配器。
 *
 * 复用资源项布局：标题展示频道名（超长跑马灯），副标题展示源数量与可用状态。
 */
class CustomDockChannelAdapter(
    private val onFocused: (TabChannel) -> Unit,
    private val onClick: (TabChannel) -> Unit,
    private val onManage: (TabChannel) -> Unit,
    private val playBoundaryShake: (View) -> Unit,
) : ListAdapter<TabChannel, CustomDockChannelAdapter.VH>(DIFF) {


    private var focusedKey: String? = null
    private var recyclerView: RecyclerView? = null

    class VH(val binding: ItemCustomDockResourceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        if (recyclerView === rv) recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCustomDockResourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = getItem(position)
        holder.binding.textTitle.text = String.format("%02d  %s", position + 1, channel.displayName)
        holder.binding.textTitle.isSelected = holder.binding.root.hasFocus() && channel.displayName.length > 13
        holder.binding.textSubtitle.text = statusText(channel)

        holder.binding.root.isSelected = channel.channelKey == focusedKey
        holder.binding.root.alpha = if (channel.hasPlayable) 1f else 0.55f

        holder.binding.root.setOnFocusChangeListener { v, hasFocus ->
            holder.binding.textTitle.isSelected = hasFocus && channel.displayName.length > 13
            if (hasFocus) {
                focusedKey = channel.channelKey
                // 关键：不要在获得焦点时调用 notifyDataSetChanged()，
                // 否则 RecyclerView 会重建正在获焦的 item 从而丢失焦点（导致「进不去二级导航」）。
                // 直接就地刷新可见 item 的「选中态」即可：当前项选中（暖黄字体），其余取消。
                applySelectedState(v)
                onFocused(channel)
            }
        }
        holder.binding.root.setOnClickListener { onClick(channel) }
        holder.binding.root.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    onManage(channel)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 频道列表内左右方向键只做边界抖动并消费事件，避免焦点切到预览区或触发页面左右切换。
                    playBoundaryShake(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (position == 0) {
                        playBoundaryShake(v)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (position == itemCount - 1) {
                        playBoundaryShake(v)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * 就地刷新可见 item 的「选中态」（暖黄字体），当前获焦项选中、其余取消。
     * 不重建 item，避免打断正在获焦的 View。离屏 item 后续 onBind 时按 focusedKey 补齐。
     */
    private fun applySelectedState(focusedItemView: View) {
        val rv = recyclerView
        if (rv == null) {
            focusedItemView.isSelected = true
            return
        }
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            child.isSelected = child === focusedItemView
        }
    }

    private fun statusText(channel: TabChannel): String {
        val count = "${channel.sourceCount} 个源"
        val abnormal = if (channel.abnormalCount > 0) " · ${channel.abnormalCount} 异常" else ""

        if (!channel.hasPlayable) return "暂无可用源 · $count$abnormal"

        val healthText = channel.activeSource?.let { source ->
            when (source.health.status) {
                HealthStatus.HEALTHY -> "稳定"
                HealthStatus.DEGRADED -> if (source.health.reason.contains("无声")) "无声音" else "卡顿/较慢"
                HealthStatus.UNHEALTHY -> "无法连接"
                HealthStatus.UNKNOWN -> "检测中"
            }
        } ?: "检测中"

        return "$count · $healthText$abnormal"
    }

    override fun submitList(list: List<TabChannel>?) {
        val next = list.orEmpty()
        if (next.none { it.channelKey == focusedKey }) {
            focusedKey = next.firstOrNull()?.channelKey
        }
        super.submitList(next.toList())
    }

    fun positionOf(channel: TabChannel): Int? {
        val idx = currentList.indexOfFirst { it.channelKey == channel.channelKey }
        return idx.takeIf { it >= 0 }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<TabChannel>() {
            override fun areItemsTheSame(oldItem: TabChannel, newItem: TabChannel): Boolean =
                oldItem.channelKey == newItem.channelKey

            override fun areContentsTheSame(oldItem: TabChannel, newItem: TabChannel): Boolean =
                oldItem.displayName == newItem.displayName &&
                    oldItem.sourceCount == newItem.sourceCount &&
                    oldItem.abnormalCount == newItem.abnormalCount &&
                    oldItem.hasPlayable == newItem.hasPlayable &&
                    oldItem.activeSource?.sourceId == newItem.activeSource?.sourceId &&
                    oldItem.activeSource?.health == newItem.activeSource?.health
        }
    }
}
