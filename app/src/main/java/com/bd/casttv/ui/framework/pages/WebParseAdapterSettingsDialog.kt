package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.webparse.AdapterInfo
import com.bd.casttv.webparse.AdapterKind
import com.bd.casttv.webparse.BuiltInAdapters
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebFrameworkType
import com.bd.casttv.webparse.WebParseAdapterStore
import com.bd.casttv.util.ThemeManager

class WebParseAdapterSettingsDialog(
    private val context: Context,
    private val currentUrlProvider: () -> String,
    private val uploadPageUrlProvider: () -> String,
    private val onUseRule: (String) -> Unit
) {
    private val warm = Color.parseColor("#FFD700")
    private val store = WebParseAdapterStore(context)
    private var dialog: AlertDialog? = null
    private var returnFocusView: View? = null
    private var currentKind = ParsePageKind.LIST
    private lateinit var listTab: TextView
    private lateinit var detailTab: TextView
    private lateinit var builtInBox: LinearLayout
    private lateinit var customBox: LinearLayout

    fun show() {
        returnFocusView = (context as? android.app.Activity)?.currentFocus
        val panel = dialogPanel().apply { descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        content.addView(titleView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        listTab = tabButton(
            label = "影片列表解析",
            selected = true,
            click = { switchKind(ParsePageKind.LIST) },
            focusSelect = { switchKind(ParsePageKind.LIST, moveFocusToContent = false) }
        )
        detailTab = tabButton(
            label = "影片详情解析",
            selected = false,
            click = { switchKind(ParsePageKind.DETAIL) },
            focusSelect = { switchKind(ParsePageKind.DETAIL, moveFocusToContent = false) }
        )
        tabs.addView(listTab, LinearLayout.LayoutParams(dp(150), dp(42)).apply { marginEnd = dp(8) })
        tabs.addView(detailTab, LinearLayout.LayoutParams(dp(150), dp(42)))
        content.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { topMargin = dp(12) })

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        builtInBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, dp(10)) }
        customBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, dp(10)) }
        columns.addView(columnView("内置适配器", builtInBox), LinearLayout.LayoutParams(0, dp(300), 1f).apply { marginEnd = dp(8) })
        columns.addView(columnView("自定义适配器", customBox), LinearLayout.LayoutParams(0, dp(300), 1f))
        content.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(8) })

        val footer = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val addButton = dialogButton("新增自定义适配器") {
            JsonAdapterDialog(
                context = context,
                currentUrlProvider = currentUrlProvider,
                uploadPageUrlProvider = uploadPageUrlProvider,
                pageKindProvider = { currentKind },
                onUseRule = { fileName -> refreshContent(); onUseRule(fileName) }
            ).show()
        }
        val closeButton = dialogButton("关闭") { dialog?.dismiss() }
        footer.addView(addButton, LinearLayout.LayoutParams(dp(168), dp(42)).apply { marginEnd = dp(10) })
        footer.addView(closeButton, LinearLayout.LayoutParams(dp(108), dp(42)))
        content.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        panel.addView(content)

        refreshContent()
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener { listTab.requestFocus() }
            d.setOnDismissListener { returnFocusView?.post { returnFocusView?.requestFocus() } }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(760), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }
    private fun switchKind(kind: ParsePageKind, moveFocusToContent: Boolean = true) {
        if (currentKind == kind) return
        currentKind = kind
        refreshTabState()
        refreshContent()
        if (moveFocusToContent) {
            findFirstFocusable(builtInBox)?.requestFocus()
        }
    }

    private fun refreshTabState() {
        refreshTab(listTab, currentKind == ParsePageKind.LIST, listTab.hasFocus())
        refreshTab(detailTab, currentKind == ParsePageKind.DETAIL, detailTab.hasFocus())
    }

    private fun refreshContent() {
        if (!::builtInBox.isInitialized || !::customBox.isInitialized) return
        fillBuiltInColumn(builtInBox, currentKind)
        fillCustomColumn(customBox, currentKind)
    }

    private fun fillBuiltInColumn(target: LinearLayout, kind: ParsePageKind) {
        target.removeAllViews()
        BuiltInAdapters.forPageKind(kind).forEach { adapter ->
            target.addView(adapterRow(adapter, kind), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
        }
    }

    private fun fillCustomColumn(target: LinearLayout, kind: ParsePageKind) {
        target.removeAllViews()
        val bindings = runCatching { store.getAllBindings().filter { it.pageKind == kind && it.adapterKind == AdapterKind.CUSTOM_JSON } }.getOrDefault(emptyList())
        if (bindings.isEmpty()) {
            target.addView(emptyRow("暂无本地 JSON 自定义适配器，可点击底部新增按钮生成或导入"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
        } else {
            bindings.sortedBy { it.adapterName }.forEach { binding ->
                val adapter = AdapterInfo(
                    id = binding.adapterId,
                    name = binding.adapterName.ifBlank { binding.host },
                    kind = AdapterKind.CUSTOM_JSON,
                    frameworkType = WebFrameworkType.CUSTOM,
                    supportedPageKinds = setOf(kind),
                    description = "${if (kind == ParsePageKind.LIST) "列表页" else "详情页"} · ${binding.host}"
                )
                target.addView(customRow(adapter, binding.ruleFileName, kind), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
            }
        }
    }

    private fun adapterRow(adapter: AdapterInfo, kind: ParsePageKind): LinearLayout = baseRow().apply {
        addView(rowText(adapter.name, "${adapter.frameworkType.displayName} · ${adapter.description}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(dialogButton("编辑网址") { showDomainEditor(adapter, "", kind) }, LinearLayout.LayoutParams(dp(100), dp(38)).apply { marginStart = dp(8) })
    }

    private fun customRow(adapter: AdapterInfo, fileName: String, kind: ParsePageKind): LinearLayout = baseRow().apply {
        addView(rowText(adapter.name, adapter.description), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(dialogButton("编辑网址") { showDomainEditor(adapter, fileName, kind) }, LinearLayout.LayoutParams(dp(96), dp(38)).apply { marginStart = dp(8) })
        addView(dialogButton("编辑") { showRuleEditDialog(fileName) }, LinearLayout.LayoutParams(dp(68), dp(38)).apply { marginStart = dp(6) })
        addView(dialogButton("删除") { showDeleteRuleConfirm(fileName) }, LinearLayout.LayoutParams(dp(68), dp(38)).apply { marginStart = dp(6) })
    }

    private fun showDomainEditor(adapter: AdapterInfo, ruleFileName: String, pageKind: ParsePageKind) {
        val box = dialogPanel()
        box.addView(titleView("编辑网址绑定"))
        box.addView(TextView(context).apply {
            text = "当前适配器：${adapter.name}"
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(0, dp(12), 0, 0)
        })
        val bindingList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun redrawBindings() {
            bindingList.removeAllViews()
            val bindings = store.getAllBindings().filter { it.pageKind == pageKind && it.adapterId == adapter.id }
            if (bindings.isEmpty()) {
                bindingList.addView(label("暂无绑定域名"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            } else {
                bindings.forEach { binding ->
                    bindingList.addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(binding.host), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(dialogButton("删除") {
                            store.removeBinding(pageKind, binding.host)
                            redrawBindings()
                        }, LinearLayout.LayoutParams(dp(76), dp(36)))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
                }
            }
        }
        redrawBindings()
        box.addView(bindingList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val input = EditText(context).apply {
            hint = "输入域名或网址"
            setText(store.normalizeHost(currentUrlProvider()))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12), 0, dp(12), 0)
            background = inputBg(false)
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
            setOnClickListener { showKeyboard(this) }
            setOnFocusChangeListener { v, has -> background = inputBg(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        val childDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val add = dialogButton("添加绑定") {
            val binding = WebParseAdapterStore.DomainBinding(
                pageKind = pageKind,
                host = input.text?.toString().orEmpty(),
                adapterId = adapter.id,
                adapterKind = adapter.kind,
                adapterName = adapter.name,
                ruleFileName = ruleFileName,
                frameworkType = adapter.frameworkType,
                updatedAt = System.currentTimeMillis()
            )
            when (val result = store.saveBinding(binding)) {
                WebParseAdapterStore.SaveResult.Success -> { toast("绑定已保存"); redrawBindings() }
                WebParseAdapterStore.SaveResult.Duplicate -> toast("该域名已绑定到当前适配器")
                is WebParseAdapterStore.SaveResult.Conflict -> showReplaceBindingConfirm(binding, result.existingAdapterName) { redrawBindings() }
            }
        }
        val close = dialogButton("关闭") { childDialog.dismiss() }
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(add, LinearLayout.LayoutParams(dp(112), dp(40)).apply { marginEnd = dp(8) })
            addView(close, LinearLayout.LayoutParams(dp(88), dp(40)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        childDialog.setOnShowListener { input.requestFocus() }
        childDialog.show()
        childDialog.window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(600), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showReplaceBindingConfirm(binding: WebParseAdapterStore.DomainBinding, existingName: String, done: () -> Unit) {
        val box = dialogPanel()
        box.addView(titleView("替换网址绑定"))
        box.addView(label("该域名已绑定到「$existingName」，是否替换为「${binding.adapterName}」？"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val ok = dialogButton("替换") { store.forceUpdateBinding(binding); d.dismiss(); done(); toast("绑定已替换") }
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(cancel, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginEnd = dp(8) })
            addView(ok, LinearLayout.LayoutParams(dp(88), dp(40)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
        d.setOnShowListener { cancel.requestFocus() }
        d.show()
        d.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    private fun showRuleEditDialog(fileName: String) {
        val input = EditText(context).apply {
            setText(RuleBasedAdapter.readRuleText(context, fileName))
            setSelection(text.length)
            minLines = 8
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = inputBg(false)
            showSoftInputOnFocus = false
            setOnClickListener { showKeyboard(this) }
            setOnFocusChangeListener { v, has -> background = inputBg(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        val box = dialogPanel().apply {
            addView(titleView("编辑自定义适配器"))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(14) })
        }
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val save = dialogButton("保存") {
            try {
                RuleBasedAdapter.deleteRule(context, fileName)
                RuleBasedAdapter.saveRule(context, input.text?.toString().orEmpty())
                d.dismiss()
                refreshContent()
                toast("适配器已保存")
            } catch (t: Throwable) {
                toast(t.message ?: "JSON 格式错误，请检查内容")
            }
        }
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(cancel, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginEnd = dp(8) })
            addView(save, LinearLayout.LayoutParams(dp(88), dp(40)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        d.setOnShowListener { input.requestFocus() }
        d.show()
        d.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(680), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    private fun showDeleteRuleConfirm(fileName: String) {
        val box = dialogPanel()
        box.addView(titleView("删除自定义适配器"))
        box.addView(label("确认删除「$fileName」吗？删除后该自定义适配器文件将从本地移除。"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val ok = dialogButton("删除") {
            RuleBasedAdapter.deleteRule(context, fileName)
            d.dismiss()
            refreshContent()
            toast("自定义适配器已删除")
        }
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(cancel, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginEnd = dp(8) })
            addView(ok, LinearLayout.LayoutParams(dp(88), dp(40)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
        d.setOnShowListener { cancel.requestFocus() }
        d.show()
        d.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    private fun dialogPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(2), warm)
        }
        clipChildren = false
        clipToPadding = false
    }

    private fun columnView(title: String, body: LinearLayout): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rowBg(false)
        addView(TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        addView(ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipChildren = false
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    private fun findFirstFocusable(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root.isFocusable) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findFirstFocusable(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun titleView(title: String = "影片解析适配器"): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = title
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(warm)
        setPadding(dp(2), dp(8), dp(2), dp(4))
    }

    private fun baseRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rowBg(false)
        clipChildren = false
        clipToPadding = false
    }

    private fun rowText(title: String, desc: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(context).apply {
            text = desc
            textSize = 12.5f
            setTextColor(Color.argb(190, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun emptyRow(text: String): TextView = label(text).apply { gravity = Gravity.CENTER; background = rowBg(false) }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.argb(225, 245, 245, 245))
        maxLines = 3
    }

    private fun tabButton(label: String, selected: Boolean, click: () -> Unit, focusSelect: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isSelected = selected
        isFocusable = true
        isClickable = true
        refreshTab(this, selected, false)
        setOnClickListener { click() }
        setOnFocusChangeListener { v, has ->
            if (has) focusSelect()
            refreshTab(this, isSelected, has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun refreshTab(tab: TextView, selected: Boolean, focused: Boolean) {
        tab.isSelected = selected
        tab.setTextColor(if (selected) warm else Color.argb(235, 245, 245, 245))
        tab.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(if (selected) 44 else 18, 255, 255, 255))
            setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
        }
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.argb(238, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        setOnClickListener { click() }
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun inputBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.argb(32, 32, 34, 40))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
    }

    private fun rowBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(22, 255, 255, 255))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(80, 255, 255, 255))
    }

    private fun boundaryKey(v: View, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val next = v.focusSearch(direction)
        if (next != null && next !== v && next.visibility == View.VISIBLE && next.isFocusable) return false
        BoundaryFocusHandler.shake(v)
        return true
    }

    private fun showKeyboard(view: View) {
        view.postDelayed({
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
