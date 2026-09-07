package com.bd.casttv.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.databinding.DialogDockManageBinding
import com.bd.casttv.databinding.ItemDockManageTabBinding
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.settings.CustomDockTabsStore
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager

/** 首页侧边栏管理弹窗：只维护「自定义 Tab → 绑定合集」配置，不修改收藏数据。 */
class DockManagePage(
    private val inflater: LayoutInflater,
    private val favoritesStore: FavoritesStore,
    private val store: CustomDockTabsStore,
    private val playBoundaryShake: (View) -> Unit,
    private val onDockTabsChanged: () -> Unit,
    private val onRequestClose: () -> Unit,
) { 

    private var binding: DialogDockManageBinding? = null
    private var root: View? = null
    private var selectedTabId: String? = null

    private val adapter = DockTabAdapter(
        onFocused = { tab -> selectedTabId = tab.id },
        onEdit = { tab -> showEditTabDialog(tab) },
        onDelete = { tab -> showDeleteTabDialog(tab) },
        playBoundaryShake = playBoundaryShake,
        focusAddButton = { binding?.btnAddDockTab?.requestFocus() }
    )

    fun ensureInflated(parent: ViewGroup): View {
        if (root != null) return root!!
        val b = DialogDockManageBinding.inflate(inflater, parent, false)
        binding = b
        root = b.root
        b.root.background = themedDialogBackground(parent.context)

        // 标题贴纸：圆形 + 圆形边框（符合弹窗设计规范）
        b.dockManageSticker.apply {
            setCircle(true)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = parent.context.getDrawable(R.drawable.fg_sticker_circle_border)
        }

        applyDialogButtonStyle(b.btnCancelDockManage)
        applyDialogButtonStyle(b.btnAddDockTab)

        b.btnCancelDockManage.setOnClickListener { onRequestClose() }
        b.btnAddDockTab.setOnClickListener { showAddTabDialog() }

        b.dockTabList.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.VERTICAL, false)
        b.dockTabList.adapter = adapter
        b.dockTabList.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapter.itemCount == 0) {
                b.btnAddDockTab.requestFocus()
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && adapter.itemCount == 0) {
                playBoundaryShake(v)
                return@setOnKeyListener true
            }
            false
        }

        b.btnAddDockTab.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    b.btnCancelDockManage.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playBoundaryShake(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusSelectedOrLastItem()
                    true
                }
                else -> false
            }
        }

        b.btnCancelDockManage.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    b.btnAddDockTab.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playBoundaryShake(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusSelectedOrLastItem()
                    true
                }
                else -> false
            }
        }

        refresh()
        return b.root
    }

    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun refresh() {
        val tabs = store.list()
        if (selectedTabId == null || tabs.none { it.id == selectedTabId }) {
            selectedTabId = tabs.firstOrNull()?.id
        }
        adapter.submitList(tabs, selectedTabId)
        updateListContainerHeight(tabs.size)
    }

    fun requestInitialFocus() {
        binding?.btnAddDockTab?.requestFocus()
    }

    fun configureDialogWindow(dialog: android.app.Dialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setGravity(Gravity.CENTER)
            val params = window.attributes
            params.gravity = Gravity.CENTER
            params.y = 0
            window.attributes = params
            window.setLayout(dp(630), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showAddTabDialog() {
        val ctx = inflater.context
        val collections = favoritesStore.collectionsInfo()
        if (collections.isEmpty()) {
            android.widget.Toast.makeText(ctx, "暂无可用合集", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        var selectedCollectionId = collections.first().id
        var selectedIconKey = CustomDockTabsStore.DEFAULT_ICON_KEY
        val selectedName = { collections.firstOrNull { it.id == selectedCollectionId }?.name ?: "已失效" }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground(ctx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val sticker = ClippedImageView(ctx).apply {
            setImageResource(R.drawable.sticker_shinchan)
            setCircle(true)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = ctx.getDrawable(R.drawable.fg_sticker_circle_border)
        }
        val titleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(ctx).apply {
            text = "新建 Tab"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(ctx).apply {
            text = "设置标题、绑定合集与图标"
            setTextColor(0xD9FFFFFF.toInt())
            textSize = 13f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        header.addView(sticker, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val nameInput = EditText(ctx).apply {
            setText(selectedName())
            setSelection(text?.length ?: 0)
            maxLines = 1
            hint = "Tab 标题"
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(1), Color.argb(170, 210, 214, 222))
            }
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(46)
        }
        val chooseCollectionButton = TextView(ctx).apply {
            text = "绑定合集：${selectedName()}"
            isFocusable = true
            isClickable = true
        }
        val iconPreview = ImageView(ctx).apply {
            setImageResource(CustomDockIconPresets.iconFor(selectedIconKey).drawableRes)
            setColorFilter(ctx.getColor(R.color.text_primary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(36, 32, 34, 40))
                setStroke(dp(1), Color.argb(120, 210, 214, 222))
            }
        }
        val chooseIconButton = TextView(ctx).apply {
            text = "选择图标：${CustomDockIconPresets.iconFor(selectedIconKey).label}"
            isFocusable = true
            isClickable = true
        }
        applyDialogButtonStyle(chooseCollectionButton)
        applyDialogButtonStyle(chooseIconButton)
        content.addView(TextView(ctx).apply {
            text = "Tab 标题"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        content.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        content.addView(chooseCollectionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(14) })
        val iconRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconRow.addView(iconPreview, LinearLayout.LayoutParams(dp(48), dp(48)))
        iconRow.addView(chooseIconButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(10) })
        content.addView(iconRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        chooseCollectionButton.setOnClickListener {
            showCollectionPickerDialog(
                title = "选择绑定合集",
                collections = collections,
                selectedCollectionId = selectedCollectionId,
                onSelected = { collection ->
                    selectedCollectionId = collection.id
                    chooseCollectionButton.text = "绑定合集：${selectedName()}"
                    if (nameInput.text?.toString().orEmpty().isBlank()) nameInput.setText(selectedName())
                    chooseCollectionButton.requestFocus()
                }
            )
        }
        chooseIconButton.setOnClickListener {
            showIconPicker(selectedIconKey) { preset ->
                selectedIconKey = preset.key
                iconPreview.setImageResource(preset.drawableRes)
                chooseIconButton.text = "选择图标：${preset.label}"
                chooseIconButton.requestFocus()
            }
        }

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        val btnCancel = TextView(ctx).apply { text = "取消"; isFocusable = true; isClickable = true }
        val btnSave = TextView(ctx).apply { text = "保存"; isFocusable = true; isClickable = true }
        applyDialogButtonStyle(btnCancel)
        applyDialogButtonStyle(btnSave)
        footer.addView(btnCancel, LinearLayout.LayoutParams(dp(110), dp(44)))
        footer.addView(btnSave, LinearLayout.LayoutParams(dp(110), dp(44)).apply { leftMargin = dp(12) })
        content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val tab = store.add(
                name = nameInput.text?.toString().orEmpty().trim().ifBlank { selectedName() },
                collectionId = selectedCollectionId,
                iconKey = selectedIconKey
            )
            selectedTabId = tab.id
            onDockTabsChanged()
            dialog.dismiss()
            refresh()
            focusSelectedOrLastItem()
        }
        dialog.setOnShowListener { nameInput.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showEditTabDialog(tab: CustomDockTabsStore.CustomDockTab) {
        val ctx = inflater.context
        val collections = favoritesStore.collectionsInfo()
        var selectedCollectionId = tab.collectionId
        var selectedIconKey = tab.iconKey
        val selectedName = { collections.firstOrNull { it.id == selectedCollectionId }?.name ?: "已失效" }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground(ctx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val sticker = ClippedImageView(ctx).apply {
            setImageResource(R.drawable.sticker_shinchan)
            setCircle(true)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = ctx.getDrawable(R.drawable.fg_sticker_circle_border)
        }
        val titleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(ctx).apply {
            text = "编辑 Tab"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(ctx).apply {
            text = "调整标题、绑定合集与图标"
            setTextColor(0xD9FFFFFF.toInt())
            textSize = 13f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        header.addView(sticker, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val nameInput = EditText(ctx).apply {
            setText(tab.name)
            setSelection(text?.length ?: 0)
            maxLines = 1
            hint = "Tab 标题"
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(1), Color.argb(170, 210, 214, 222))
            }
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(46)
        }
        val chooseButton = TextView(ctx).apply {
            text = "绑定合集：${selectedName()}"
            isFocusable = true
            isClickable = true
        }
        val iconPreview = ImageView(ctx).apply {
            setImageResource(CustomDockIconPresets.iconFor(selectedIconKey).drawableRes)
            setColorFilter(ctx.getColor(R.color.text_primary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(36, 32, 34, 40))
                setStroke(dp(1), Color.argb(120, 210, 214, 222))
            }
        }
        val chooseIconButton = TextView(ctx).apply {
            text = "选择图标：${CustomDockIconPresets.iconFor(selectedIconKey).label}"
            isFocusable = true
            isClickable = true
        }
        applyDialogButtonStyle(chooseButton)
        applyDialogButtonStyle(chooseIconButton)
        content.addView(TextView(ctx).apply {
            text = "Tab 标题"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        content.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        content.addView(chooseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(14) })
        val iconRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconRow.addView(iconPreview, LinearLayout.LayoutParams(dp(48), dp(48)))
        iconRow.addView(chooseIconButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(10) })
        content.addView(iconRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        chooseButton.setOnClickListener {
            if (collections.isEmpty()) return@setOnClickListener
            showCollectionPickerDialog(
                title = "选择绑定合集",
                collections = collections,
                selectedCollectionId = selectedCollectionId,
                onSelected = { collection ->
                    selectedCollectionId = collection.id
                    chooseButton.text = "绑定合集：${selectedName()}"
                    chooseButton.requestFocus()
                }
            )
        }
        chooseIconButton.setOnClickListener {
            showIconPicker(selectedIconKey) { preset ->
                selectedIconKey = preset.key
                iconPreview.setImageResource(preset.drawableRes)
                chooseIconButton.text = "选择图标：${preset.label}"
                chooseIconButton.requestFocus()
            }
        }

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        val btnCancel = TextView(ctx).apply { text = "取消"; isFocusable = true; isClickable = true }
        val btnSave = TextView(ctx).apply { text = "保存"; isFocusable = true; isClickable = true }
        applyDialogButtonStyle(btnCancel)
        applyDialogButtonStyle(btnSave)
        footer.addView(btnCancel, LinearLayout.LayoutParams(dp(110), dp(44)))
        footer.addView(btnSave, LinearLayout.LayoutParams(dp(110), dp(44)).apply { leftMargin = dp(12) })
        content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val nextName = nameInput.text?.toString().orEmpty().trim().ifBlank { tab.name }
            store.update(tab.copy(name = nextName, collectionId = selectedCollectionId, iconKey = selectedIconKey))
            selectedTabId = tab.id
            onDockTabsChanged()
            dialog.dismiss()
            refresh()
            focusSelectedOrLastItem()
        }
        dialog.setOnShowListener { nameInput.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showIconPicker(
        selectedIconKey: String,
        onSelected: (CustomDockIconPresets.Preset) -> Unit
    ) {
        val ctx = inflater.context
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground(ctx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(createDialogHeader(ctx, "选择 Tab 图标", "挑一个喜欢的图标样式吧～"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val grid = GridLayout(ctx).apply {
            columnCount = 5
            setPadding(0, 0, 0, 0)
        }
        val scrollView = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            setPadding(0, dp(18), 0, 0)
            addView(grid)
        }
        content.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        CustomDockIconPresets.presets.forEach { preset ->
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                isSelected = preset.key == selectedIconKey
                background = ctx.getDrawable(R.drawable.bg_standard_action_button)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val icon = ImageView(ctx).apply {
                setImageResource(preset.drawableRes)
                imageTintList = ctx.getColorStateList(R.color.standard_action_button_text)
                setDuplicateParentStateEnabled(true)
            }
            val label = TextView(ctx).apply {
                text = preset.label
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(ctx.getColorStateList(R.color.standard_action_button_text))
                setDuplicateParentStateEnabled(true)
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
            item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            item.setOnClickListener {
                onSelected(preset)
                dialog.dismiss()
            }
            grid.addView(item, ViewGroup.MarginLayoutParams(dp(76), dp(72)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(8)
            })
        }

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelButton = TextView(ctx).apply { text = "取消"; isFocusable = true; isClickable = true }
        applyDialogButtonStyle(cancelButton)
        footer.addView(cancelButton, LinearLayout.LayoutParams(dp(110), dp(44)))
        content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener { grid.getChildAt(0)?.requestFocus() ?: cancelButton.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showDeleteTabDialog(tab: CustomDockTabsStore.CustomDockTab) {
        val ctx = inflater.context
        val current = store.list()
        val oldIndex = current.indexOfFirst { it.id == tab.id }.coerceAtLeast(0)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground(ctx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(
            createDialogHeader(ctx, "删除 Tab", "确认后仅移除首页入口，不会删除合集和资源"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        content.addView(TextView(ctx).apply {
            text = "确定删除「${tab.name}」吗？"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        content.addView(TextView(ctx).apply {
            text = "此操作只会删除设置页里的自定义 Tab 入口，不会删除已绑定的合集和里面的资源。"
            setTextColor(0xD9FFFFFF.toInt())
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelButton = TextView(ctx).apply { text = "取消"; isFocusable = true; isClickable = true }
        val deleteButton = TextView(ctx).apply { text = "删除"; isFocusable = true; isClickable = true }
        applyDialogButtonStyle(cancelButton)
        applyDialogButtonStyle(deleteButton)
        footer.addView(cancelButton, LinearLayout.LayoutParams(dp(110), dp(44)))
        footer.addView(deleteButton, LinearLayout.LayoutParams(dp(110), dp(44)).apply { leftMargin = dp(12) })
        content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        cancelButton.setOnClickListener { dialog.dismiss() }
        deleteButton.setOnClickListener {
            store.remove(tab.id)
            val next = store.list()
            selectedTabId = next.getOrNull(oldIndex)?.id ?: next.getOrNull(oldIndex - 1)?.id
            onDockTabsChanged()
            dialog.dismiss()
            refresh()
            if (next.isEmpty()) binding?.btnAddDockTab?.requestFocus() else focusSelectedOrLastItem()
        }
        dialog.setOnShowListener { cancelButton.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showCollectionPickerDialog(
        title: String,
        collections: List<FavoritesStore.CollectionInfo>,
        selectedCollectionId: String?,
        onSelected: (FavoritesStore.CollectionInfo) -> Unit
    ) {
        val ctx = inflater.context
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground(ctx)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(createDialogHeader(ctx, title, "选择一个合集绑定到当前 Tab"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        val scrollView = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(listContainer)
        }
        content.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()

        collections.forEachIndexed { index, collection ->
            val row = TextView(ctx).apply {
                text = "${collection.name}（${collection.itemCount} 个资源）"
                isFocusable = true
                isClickable = true
                isSelected = collection.id == selectedCollectionId
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }
            applyDialogButtonStyle(row)
            row.setOnClickListener {
                onSelected(collection)
                dialog.dismiss()
            }
            listContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                if (index > 0) topMargin = dp(10)
            })
        }

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelButton = TextView(ctx).apply { text = "取消"; isFocusable = true; isClickable = true }
        applyDialogButtonStyle(cancelButton)
        footer.addView(cancelButton, LinearLayout.LayoutParams(dp(110), dp(44)))
        content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener { listContainer.getChildAt(0)?.requestFocus() ?: cancelButton.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun createDialogHeader(ctx: android.content.Context, title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val sticker = ClippedImageView(ctx).apply {
            setImageResource(R.drawable.sticker_shinchan)
            setCircle(true)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = ctx.getDrawable(R.drawable.fg_sticker_circle_border)
        }
        val titleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(0xD9FFFFFF.toInt())
            textSize = 13f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        header.addView(sticker, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        return header
    }

    private fun themedDialogBackground(ctx: android.content.Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        ThemeManager.currentPalette(ctx).dialogTitleGradient
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(2), WARM)
    }

    private fun focusSelectedOrLastItem() {
        val b = binding ?: return
        if (adapter.itemCount == 0) {
            b.btnAddDockTab.requestFocus()
            return
        }
        val pos = adapter.positionOf(selectedTabId).takeIf { it >= 0 } ?: (adapter.itemCount - 1)
        b.dockTabList.scrollToPosition(pos)
        b.dockTabList.post {
            val vh = b.dockTabList.findViewHolderForAdapterPosition(pos)
            val card = (vh as? DockTabAdapter.VH)?.binding?.tabContentCard
            if (card?.requestFocus() != true && vh?.itemView?.requestFocus() != true) {
                b.dockTabList.requestFocus()
            }
        }
    }

    private fun updateListContainerHeight(itemCount: Int) {
        val list = binding?.dockTabList ?: return
        list.doOnLayout {
            val child = list.getChildAt(0)
            val perItemHeight = child?.let { firstChild ->
                val lp = firstChild.layoutParams as? ViewGroup.MarginLayoutParams
                firstChild.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            } ?: measureDockTabItemHeight(list)
            val params = list.layoutParams
            if (itemCount <= 4) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                list.isNestedScrollingEnabled = false
            } else {
                params.height = perItemHeight * 4 + list.paddingTop + list.paddingBottom
                list.isNestedScrollingEnabled = true
            }
            list.layoutParams = params
        }
    }

    private fun measureDockTabItemHeight(parent: RecyclerView): Int {
        val probe = ItemDockManageTabBinding.inflate(inflater, parent, false).root
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width.takeIf { it > 0 } ?: dp(540), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        probe.measure(widthSpec, heightSpec)
        val lp = probe.layoutParams as? ViewGroup.MarginLayoutParams
        return probe.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
    }

    private fun dp(value: Int): Int = (value * inflater.context.resources.displayMetrics.density + 0.5f).toInt()

    private fun applyDialogButtonStyle(tv: TextView) {
        tv.textSize = 15f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.minHeight = dp(44)
        tv.setPadding(dp(16), 0, dp(16), 0)
        tv.isFocusable = true
        tv.isFocusableInTouchMode = false
        tv.isClickable = true
        fun refresh(focused: Boolean) {
            tv.setTextColor(if (tv.isSelected) WARM else Color.argb(235, 245, 245, 245))
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        tv.setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
    }

    private inner class DockTabAdapter(
        private val onFocused: (CustomDockTabsStore.CustomDockTab) -> Unit,
        private val onEdit: (CustomDockTabsStore.CustomDockTab) -> Unit,
        private val onDelete: (CustomDockTabsStore.CustomDockTab) -> Unit,
        private val playBoundaryShake: (View) -> Unit,
        private val focusAddButton: () -> Unit,
    ) : RecyclerView.Adapter<DockTabAdapter.VH>() {

        private var data: List<CustomDockTabsStore.CustomDockTab> = emptyList()
        private var selectedId: String? = null
        private val collectionInfoById: Map<String, FavoritesStore.CollectionInfo>
            get() = favoritesStore.collectionsInfo().associateBy { it.id }

        inner class VH(val binding: ItemDockManageTabBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemDockManageTabBinding.inflate(inflater, parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab = data[position]
            val info = collectionInfoById[tab.collectionId]
            holder.binding.textTabTitle.text = tab.name
            val collectionName = info?.name ?: "已失效"
            val itemCount = info?.itemCount ?: 0
            holder.binding.textTabSubtitle.text = "绑定合集：$collectionName · ${itemCount} 个资源"
            holder.binding.imgTabIcon.setImageResource(CustomDockIconPresets.iconFor(tab.iconKey).drawableRes)
            holder.binding.tabContentCard.isSelected = tab.id == selectedId

            holder.binding.tabContentCard.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val oldPos = positionOf(selectedId)
                    selectedId = tab.id
                    onFocused(tab)
                    if (oldPos in 0 until itemCount && oldPos != position) notifyItemChanged(oldPos)
                    notifyItemChanged(position)
                }
                FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 14)
            }
            holder.binding.btnEditDockTab.setOnFocusChangeListener { v, hasFocus ->
                FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 22)
            }
            holder.binding.btnDeleteDockTab.setOnFocusChangeListener { v, hasFocus ->
                FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 22)
            }
            holder.binding.tabContentCard.setOnClickListener { onEdit(tab) }
            holder.binding.btnEditDockTab.setOnClickListener { onEdit(tab) }
            holder.binding.btnDeleteDockTab.setOnClickListener { onDelete(tab) }

            holder.binding.tabContentCard.setOnKeyListener(itemKeyListener(position))
            holder.binding.btnEditDockTab.setOnKeyListener(actionKeyListener(position, holder.binding.tabContentCard, holder.binding.btnDeleteDockTab))
            holder.binding.btnDeleteDockTab.setOnKeyListener(actionKeyListener(position, holder.binding.btnEditDockTab, null))
        }

        fun submitList(next: List<CustomDockTabsStore.CustomDockTab>, selected: String?) {
            data = next
            selectedId = selected
            notifyDataSetChanged()
        }

        fun positionOf(id: String?): Int = data.indexOfFirst { it.id == id }

        private fun itemKeyListener(position: Int) = View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val holder = binding?.dockTabList?.findViewHolderForAdapterPosition(position) as? VH
                    holder?.binding?.btnEditDockTab?.requestFocus() ?: false
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    playBoundaryShake(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (position == itemCount - 1) {
                        focusAddButton()
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (position == 0) {
                        playBoundaryShake(v)
                        true
                    } else false
                }
                else -> false
            }
        }

        private fun actionKeyListener(position: Int, leftTarget: View, rightTarget: View?) = View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    leftTarget.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (rightTarget != null) {
                        rightTarget.requestFocus()
                        true
                    } else {
                        playBoundaryShake(v)
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (position == itemCount - 1) {
                        focusAddButton()
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (position == 0) {
                        playBoundaryShake(v)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
    }
}
