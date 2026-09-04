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
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.webparse.AdapterInfo
import com.bd.casttv.webparse.AdapterKind
import com.bd.casttv.webparse.BuiltInAdapters
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebFrameworkType
import com.bd.casttv.webparse.WebParseAdapterStore
import com.bd.casttv.webparse.WebParseStore
import com.bd.casttv.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WebParseAdapterSettingsDialog(
    private val context: Context,
    private val currentUrlProvider: () -> String,
    private val uploadPageUrlProvider: () -> String,
    private val onUseRule: (String) -> Unit
) {
    private val warm = Color.parseColor("#FFD700")
    private val store = WebParseAdapterStore(context)
    private val parseStore = WebParseStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var dialog: AlertDialog? = null
    private var returnFocusView: View? = null
    private var currentTab = 0
    private var cloudAdapters: List<GiteeShareStore.SharedAdapter> = emptyList()
    private var cloudRecords: List<GiteeShareStore.SharedRecord> = emptyList()

    private lateinit var cloudTab: TextView
    private lateinit var localTab: TextView
    private lateinit var cloudBox: LinearLayout
    private lateinit var localBox: LinearLayout

    fun show() {
        returnFocusView = (context as? android.app.Activity)?.currentFocus
        val panel = dialogPanel().apply { descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = true
            clipToPadding = false
        }
        content.addView(titleView(), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        cloudTab = tabButton("云端适配器", true, { switchTab(0) }, { switchTab(0, false) })
        localTab = tabButton("本地适配器", false, { switchTab(1) }, { switchTab(1, false) })
        tabs.addView(cloudTab, lparams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        tabs.addView(localTab, lparams(0, dp(42), 1f).apply { marginStart = dp(4) })
        content.addView(tabs, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(12) })

        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipChildren = true
            clipToPadding = false
        }
        cloudBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, dp(8), 0, dp(10))
        }
        localBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, dp(8), 0, dp(10)); visibility = View.GONE
        }
        scroll.addView(RelativeLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(cloudBox, RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
            addView(localBox, RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Tab 以下区域按剩余高度的百分比分配，避免固定高度在不同屏幕上挤压列表或按钮栏。
        val tabContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            weightSum = 100f
            clipChildren = true
            clipToPadding = false
        }
        tabContent.addView(scroll, lparams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 84f).apply {
            topMargin = dp(8)
        })

        val footer = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        }
        val closeButton = dialogButton("关闭") { dialog?.dismiss() }
        footer.addView(closeButton, lparams(dp(108), dp(42)))
        tabContent.addView(footer, lparams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 16f).apply {
            topMargin = dp(6)
        })
        content.addView(tabContent, lparams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(content, lparams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshCloudTab()
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener { cloudTab.requestFocus() }
            d.setOnDismissListener {
                scope.cancel()
                returnFocusView?.post { returnFocusView?.requestFocus() }
            }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                val maxDialogHeight = (context.resources.displayMetrics.heightPixels * 0.9f).toInt()
                setLayout(dp(532), minOf(dp(600), maxDialogHeight))
            }
        }
    }

    private fun switchTab(tab: Int, moveFocus: Boolean = true) {
        if (currentTab == tab) return
        currentTab = tab
        refreshTabState()
        cloudBox.visibility = if (tab == 0) View.VISIBLE else View.GONE
        localBox.visibility = if (tab == 1) View.VISIBLE else View.GONE
        refreshCurrentTab()
        if (moveFocus) {
            val target = if (tab == 0) cloudBox else localBox
            target.post { findFirstFocusable(target)?.requestFocus() }
        }
    }

    private fun refreshTabState() {
        refreshTab(cloudTab, currentTab == 0, cloudTab.hasFocus())
        refreshTab(localTab, currentTab == 1, localTab.hasFocus())
    }

    private fun refreshCurrentTab() {
        if (currentTab == 0) refreshCloudTab() else refreshLocalTab()
    }

    // ==================== 云端适配器 ====================

    private fun refreshCloudTab() {
        if (!::cloudBox.isInitialized) return
        cloudBox.removeAllViews()
        cloudBox.addView(circularLoadingView(), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scope.launch {
            val adapterResult = withContext(Dispatchers.IO) { GiteeShareStore.fetchAdaptersIndex() }
            val recordResult = withContext(Dispatchers.IO) { GiteeShareStore.fetchRecordsIndex() }
            if (dialog?.isShowing != true) return@launch
            cloudBox.removeAllViews()
            val adapters = (adapterResult as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
            val records = (recordResult as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
            cloudAdapters = adapters
            cloudRecords = records
            if (adapters.isEmpty()) {
                cloudBox.addView(emptyRow("暂无云端适配器"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
            } else {
                adapters.sortedByDescending { it.uploadedAt }.forEach { adapter ->
                    val refCount = records.count { it.globalAdapterId == adapter.globalAdapterId }
                    val pageLabel = if (adapter.pageKind == "LIST") "列表页" else "详情页"
                    cloudBox.addView(cloudAdapterCard(adapter, refCount, pageLabel), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
                }
                cloudBox.post { findFirstFocusable(cloudBox)?.requestFocus() }
            }
        }
    }

    private fun cloudAdapterCard(adapter: GiteeShareStore.SharedAdapter, refCount: Int, pageLabel: String): LinearLayout = baseRow().apply {
        addView(rowText(adapter.name.ifBlank { adapter.host }, "已关联${refCount}个网页解析 · $pageLabel · ${adapter.host}"), lparams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(dialogButton("下载") { downloadCloudAdapter(adapter) }, lparams(dp(76), dp(38)).apply { marginStart = dp(6) })
        addView(dialogButton("编辑") { editCloudAdapter(adapter) }, lparams(dp(68), dp(38)).apply { marginStart = dp(6) })
        addView(dialogButton("删除") { deleteCloudAdapterConfirm(adapter) }, lparams(dp(68), dp(38)).apply { marginStart = dp(6) })
    }

    private fun downloadCloudAdapter(adapter: GiteeShareStore.SharedAdapter) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { GiteeShareStore.downloadAdapterById(context, adapter.globalAdapterId, adapter) }
            when (result) {
                is GiteeApi.ApiResult.Success -> { toast("已下载到本地"); refreshLocalTab() }
                is GiteeApi.ApiResult.Error -> toast("下载失败: ${result.message}")
                is GiteeApi.ApiResult.NotFound -> toast("适配器文件不存在")
            }
        }
    }

    private fun editCloudAdapter(adapter: GiteeShareStore.SharedAdapter) {
        scope.launch {
            val path = "shared_data/adapters/${adapter.globalAdapterId}.json"
            val result = withContext(Dispatchers.IO) { GiteeApi.getFileResult(path) }
            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    val ruleText = runCatching {
                        val obj = JSONObject(result.value.content)
                        obj.optJSONObject("rule")?.toString(2) ?: result.value.content
                    }.getOrDefault(result.value.content)
                    val pageKind = runCatching { ParsePageKind.valueOf(adapter.pageKind) }.getOrDefault(ParsePageKind.DETAIL)
                    val fwType = runCatching { WebFrameworkType.valueOf(adapter.frameworkType) }.getOrDefault(WebFrameworkType.CUSTOM)
                    val bindings = store.getAllBindings().filter { it.adapterId == adapter.globalAdapterId }
                    showEditDialog("编辑云端适配器", adapter.name, ruleText, bindings, pageKind, adapter.globalAdapterId, bindings.firstOrNull()?.ruleFileName.orEmpty(), fwType) { newName, newRule, newHosts ->
                        scope.launch {
                            val savedRule = withContext(Dispatchers.IO) { RuleBasedAdapter.saveRule(context, newRule, newName, pageKind) }
                            removeBindingsForAdapter(adapter.globalAdapterId, pageKind)
                            val firstHost = newHosts.firstOrNull() ?: adapter.host
                            newHosts.forEach { host ->
                                store.forceUpdateBinding(WebParseAdapterStore.DomainBinding(pageKind, host, adapter.globalAdapterId, AdapterKind.CUSTOM_JSON, newName, savedRule.fileName, fwType, System.currentTimeMillis()))
                            }
                            val uploadBinding = WebParseAdapterStore.DomainBinding(pageKind, firstHost, adapter.globalAdapterId, AdapterKind.CUSTOM_JSON, newName, savedRule.fileName, fwType, System.currentTimeMillis())
                            val upResult = withContext(Dispatchers.IO) { GiteeShareStore.upsertSharedAdapter(context, uploadBinding, newRule) }
                            when (upResult) {
                                is GiteeApi.ApiResult.Success -> { toast("已保存并上传到云端"); refreshCloudTab() }
                                is GiteeApi.ApiResult.Error -> toast("已保存本地，上传失败: ${upResult.message}")
                                is GiteeApi.ApiResult.NotFound -> toast("已保存本地，上传失败")
                            }
                        }
                    }
                }
                is GiteeApi.ApiResult.Error -> toast("获取适配器失败: ${result.message}")
                is GiteeApi.ApiResult.NotFound -> toast("适配器文件不存在")
            }
        }
    }

    private fun deleteCloudAdapterConfirm(adapter: GiteeShareStore.SharedAdapter) {
        val refCount = cloudRecords.count { it.globalAdapterId == adapter.globalAdapterId }
        val msg = if (refCount > 0) "确认删除云端适配器「${adapter.name}」吗？\n该适配器已关联${refCount}个网页解析记录，删除后引用将失效。"
        else "确认删除云端适配器「${adapter.name}」吗？"
        val box = dialogPanel()
        box.addView(titleView("删除云端适配器"))
        box.addView(label(msg), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val ok = dialogButton("删除") {
            d.dismiss()
            scope.launch {
                withContext(Dispatchers.IO) { GiteeShareStore.deleteCloudAdapter(adapter.globalAdapterId) }
                toast("已从云端删除")
                refreshCloudTab()
            }
        }
        box.addView(buttonRow(cancel, ok), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
        d.setOnShowListener { cancel.requestFocus() }
        d.show()
        d.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    // ==================== 本地适配器 ====================

    private fun refreshLocalTab() {
        if (!::localBox.isInitialized) return
        localBox.removeAllViews()
        localBox.addView(sectionTitle("App内置适配器"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        BuiltInAdapters.all.forEach { adapter ->
            val refCount = parseStore.getParseHistory().count { it.adapterId == adapter.id }
            localBox.addView(builtInCard(adapter, refCount), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
        }
        localBox.addView(sectionTitle("自定义适配器"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val bindings = store.getAllBindings().filter { it.adapterKind == AdapterKind.CUSTOM_JSON }.distinctBy { it.adapterId }
        if (bindings.isEmpty()) {
            localBox.addView(emptyRow("暂无自定义适配器"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
        } else {
            bindings.sortedBy { it.adapterName }.forEach { binding ->
                val refCount = parseStore.getParseHistory().count { it.adapterId == binding.adapterId }
                localBox.addView(customCard(binding, refCount), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
            }
        }
    }

    private fun builtInCard(adapter: AdapterInfo, refCount: Int): LinearLayout = baseRow().apply {
        addView(taggedRowText("App内置", Color.argb(180, 76, 175, 80), adapter.name, "已关联${refCount}个网页解析 · ${adapter.frameworkType.displayName}"), lparams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(dialogButton("编辑网址") { showDomainEditor(adapter, "", ParsePageKind.DETAIL) }, lparams(dp(96), dp(38)).apply { marginStart = dp(8) })
    }

    private fun customCard(binding: WebParseAdapterStore.DomainBinding, refCount: Int): LinearLayout = baseRow().apply {
        addView(taggedRowText("自定义", Color.argb(180, 255, 152, 0), binding.adapterName.ifBlank { binding.host }, "已关联${refCount}个网页解析 · ${binding.host}"), lparams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(dialogButton("编辑") { editLocalAdapter(binding) }, lparams(dp(68), dp(38)).apply { marginStart = dp(6) })
        addView(dialogButton("删除") { deleteLocalConfirm(binding) }, lparams(dp(68), dp(38)).apply { marginStart = dp(6) })
        addView(dialogButton("上传") { uploadLocalAdapter(binding) }, lparams(dp(68), dp(38)).apply { marginStart = dp(6) })
    }

    private fun editLocalAdapter(binding: WebParseAdapterStore.DomainBinding) {
        val ruleText = RuleBasedAdapter.readRuleText(context, binding.ruleFileName)
        val bindings = store.getAllBindings().filter { it.adapterId == binding.adapterId && it.pageKind == binding.pageKind }
        showEditDialog("编辑自定义适配器", binding.adapterName, ruleText, bindings, binding.pageKind, binding.adapterId, binding.ruleFileName, binding.frameworkType) { newName, newRule, newHosts ->
            val savedRule = runCatching { RuleBasedAdapter.saveRule(context, newRule, newName, binding.pageKind) }.getOrElse {
                toast(it.message ?: "JSON 格式错误"); return@showEditDialog
            }
            if (binding.ruleFileName.isNotBlank() && binding.ruleFileName != savedRule.fileName) {
                RuleBasedAdapter.deleteRule(context, binding.ruleFileName)
            }
            removeBindingsForAdapter(binding.adapterId, binding.pageKind)
            newHosts.forEach { host ->
                store.forceUpdateBinding(WebParseAdapterStore.DomainBinding(binding.pageKind, host, binding.adapterId, AdapterKind.CUSTOM_JSON, newName, savedRule.fileName, binding.frameworkType, System.currentTimeMillis()))
            }
            toast("已保存")
            refreshLocalTab()
        }
    }

    private fun deleteLocalConfirm(binding: WebParseAdapterStore.DomainBinding) {
        val box = dialogPanel()
        box.addView(titleView("删除自定义适配器"))
        box.addView(label("确认删除「${binding.adapterName}」吗？删除后规则文件和域名绑定将从本地移除。"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val ok = dialogButton("删除") {
            d.dismiss()
            RuleBasedAdapter.deleteRule(context, binding.ruleFileName)
            removeBindingsForAdapter(binding.adapterId, binding.pageKind)
            toast("已删除")
            refreshLocalTab()
        }
        box.addView(buttonRow(cancel, ok), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
        d.setOnShowListener { cancel.requestFocus() }
        d.show()
        d.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    private fun uploadLocalAdapter(binding: WebParseAdapterStore.DomainBinding) {
        val ruleText = RuleBasedAdapter.readRuleText(context, binding.ruleFileName)
        if (ruleText.isBlank()) { toast("规则文件为空"); return }
        toast("正在上传…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { GiteeShareStore.upsertSharedAdapter(context, binding, ruleText) }
            when (result) {
                is GiteeApi.ApiResult.Success -> toast("已上传到云端")
                is GiteeApi.ApiResult.Error -> toast("上传失败: ${result.message}")
                is GiteeApi.ApiResult.NotFound -> toast("上传失败")
            }
        }
    }

    // ==================== 编辑弹窗（名称+网址+JSON） ====================

    private fun showEditDialog(
        title: String,
        adapterName: String,
        ruleText: String,
        bindings: List<WebParseAdapterStore.DomainBinding>,
        pageKind: ParsePageKind,
        adapterId: String,
        ruleFileName: String,
        frameworkType: WebFrameworkType,
        onSave: (newName: String, newRule: String, newHosts: List<String>) -> Unit
    ) {
        val box = dialogPanel()
        box.addView(titleView(title))

        // 名称
        box.addView(label("名称"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val nameInput = EditText(context).apply {
            setText(adapterName); setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12), 0, dp(12), 0); background = inputBg(false)
            showSoftInputOnFocus = false; isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener { showKeyboard(this) }
            setOnFocusChangeListener { v, has -> background = inputBg(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        box.addView(nameInput, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })

        // 网址绑定
        box.addView(label("关联网址"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val bindingList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val currentHosts = bindings.map { it.host }.toMutableList()
        fun redrawBindings() {
            bindingList.removeAllViews()
            if (currentHosts.isEmpty()) {
                bindingList.addView(label("暂无绑定域名"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
            }
            currentHosts.toList().forEach { host ->
                bindingList.addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(label(host), lparams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(dialogButton("删除") { currentHosts.remove(host); redrawBindings() }, lparams(dp(76), dp(34)))
                }, lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        redrawBindings()
        box.addView(bindingList, lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val hostInput = EditText(context).apply {
            hint = "输入域名或网址"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12), 0, dp(12), 0); background = inputBg(false)
            showSoftInputOnFocus = false; isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener { showKeyboard(this) }
            setOnFocusChangeListener { v, has -> background = inputBg(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        val addHostBtn = dialogButton("添加") {
            val host = store.normalizeHost(hostInput.text?.toString().orEmpty())
            if (host.isNotBlank() && host !in currentHosts) { currentHosts.add(host); hostInput.setText(""); redrawBindings() }
        }
        box.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(hostInput, lparams(0, dp(40), 1f))
            addView(addHostBtn, lparams(dp(76), dp(40)).apply { marginStart = dp(8) })
        }, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })

        // JSON 规则
        box.addView(label("JSON 规则"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val ruleInput = EditText(context).apply {
            setText(ruleText); setSelection(text.length)
            minLines = 8; maxLines = 12; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = inputBg(false)
            showSoftInputOnFocus = false; typeface = Typeface.MONOSPACE
            setOnClickListener { showKeyboard(this) }
            setOnFocusChangeListener { v, has -> background = inputBg(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        box.addView(ruleInput, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(6) })

        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val save = dialogButton("保存") {
            val newName = nameInput.text?.toString().orEmpty().ifBlank { adapterName }
            val newRule = ruleInput.text?.toString().orEmpty()
            val newHosts = currentHosts.toList()
            d.dismiss()
            onSave(newName, newRule, newHosts)
        }
        box.addView(buttonRow(cancel, save), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        d.setOnShowListener { nameInput.requestFocus() }
        d.show()
        d.window?.apply {
            setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(680), WindowManager.LayoutParams.WRAP_CONTENT)
            decorView.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_DOWN) { cancel.performClick(); true } else false
            }
        }
    }

    // ==================== 域名编辑器（内置适配器用） ====================

    private fun showDomainEditor(adapter: AdapterInfo, ruleFileName: String, pageKind: ParsePageKind) {
        val box = dialogPanel()
        box.addView(titleView("编辑网址绑定"))
        box.addView(TextView(context).apply {
            text = "当前适配器：${adapter.name}"; textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255)); setPadding(0, dp(12), 0, 0)
        })
        val bindingList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun redrawBindings() {
            bindingList.removeAllViews()
            val bindings = store.getAllBindings().filter { it.pageKind == pageKind && it.adapterId == adapter.id }
            if (bindings.isEmpty()) {
                bindingList.addView(label("暂无绑定域名"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            } else {
                bindings.forEach { binding ->
                    bindingList.addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(binding.host), lparams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(dialogButton("删除") { store.removeBinding(pageKind, binding.host); redrawBindings() }, lparams(dp(76), dp(36)))
                    }, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
                }
            }
        }
        redrawBindings()
        box.addView(bindingList, lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val input = EditText(context).apply {
            hint = "输入域名或网址"; setText(store.normalizeHost(currentUrlProvider()))
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(12), 0, dp(12), 0); background = inputBg(false)
            showSoftInputOnFocus = false; isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener { showKeyboard(this) }
            setOnFocusChangeListener { v, has -> background = inputBg(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        box.addView(input, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        val childDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val add = dialogButton("添加绑定") {
            val binding = WebParseAdapterStore.DomainBinding(pageKind, input.text?.toString().orEmpty(), adapter.id, adapter.kind, adapter.name, ruleFileName, adapter.frameworkType, System.currentTimeMillis())
            when (val result = store.saveBinding(binding)) {
                WebParseAdapterStore.SaveResult.Success -> { toast("绑定已保存"); redrawBindings() }
                WebParseAdapterStore.SaveResult.Duplicate -> toast("该域名已绑定到当前适配器")
                is WebParseAdapterStore.SaveResult.Conflict -> showReplaceBindingConfirm(binding, result.existingAdapterName) { redrawBindings() }
            }
        }
        val close = dialogButton("关闭") { childDialog.dismiss() }
        box.addView(buttonRow(add, close, dp(112), dp(88)), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        childDialog.setOnShowListener { input.requestFocus() }
        childDialog.show()
        childDialog.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(600), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    private fun showReplaceBindingConfirm(binding: WebParseAdapterStore.DomainBinding, existingName: String, done: () -> Unit) {
        val box = dialogPanel()
        box.addView(titleView("替换网址绑定"))
        box.addView(label("该域名已绑定到「$existingName」，是否替换为「${binding.adapterName}」？"), lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        val cancel = dialogButton("取消") { d.dismiss() }
        val ok = dialogButton("替换") { store.forceUpdateBinding(binding); d.dismiss(); done(); toast("绑定已替换") }
        box.addView(buttonRow(cancel, ok), lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
        d.setOnShowListener { cancel.requestFocus() }
        d.show()
        d.window?.apply { setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent); setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT) }
    }

    // ==================== 辅助方法 ====================

    private fun removeBindingsForAdapter(adapterId: String, pageKind: ParsePageKind) {
        store.getAllBindings().filter { it.adapterId == adapterId && it.pageKind == pageKind }
            .forEach { store.removeBinding(pageKind, it.host) }
    }

    private fun dialogPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(2), warm)
        }
        clipChildren = true; clipToPadding = false
    }

    private fun titleView(title: String = "影片解析适配器"): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        clipChildren = false; clipToPadding = false
        addView(ClippedImageView(context).apply {
            setCircle(true); setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP; foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, lparams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm); setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, lparams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(warm); setPadding(dp(2), dp(8), dp(2), dp(4))
    }

    private fun baseRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = rowBg(false)
        clipChildren = false; clipToPadding = false
    }

    private fun rowText(title: String, desc: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = title; textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(context).apply {
            text = desc; textSize = 12.5f; setTextColor(Color.argb(190, 255, 255, 255))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun taggedRowText(tag: String, tagColor: Int, title: String, desc: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tagView(tag, tagColor), lparams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(19)).apply { marginEnd = dp(6) })
            addView(TextView(context).apply {
                text = title; textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, lparams(0, dp(19), 1f))
        }, lparams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)))
        addView(TextView(context).apply {
            text = desc; textSize = 12.5f; setTextColor(Color.argb(190, 255, 255, 255))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lparams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun tagView(text: String, color: Int): TextView = TextView(context).apply {
        this.text = text; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat(); setColor(color)
            setStroke(dp(1), Color.argb(200, 255, 255, 255))
        }
    }

    private fun emptyRow(text: String): TextView = label(text).apply { gravity = Gravity.CENTER; background = rowBg(false) }

    private fun loadingRow(text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 14f; setTextColor(Color.argb(200, 245, 245, 245))
        gravity = Gravity.CENTER; background = rowBg(false)
    }

    private fun circularLoadingView(): View = FrameLayout(context).apply {
        minimumHeight = dp(400)
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            indeterminateDrawable.colorFilter = android.graphics.PorterDuffColorFilter(warm, android.graphics.PorterDuff.Mode.SRC_IN)
        }, FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER })
        addView(TextView(context).apply {
            text = "正在从云端加载…"; textSize = 13f
            setTextColor(Color.argb(180, 245, 245, 245))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER; topMargin = dp(72)
        })
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 14f
        setTextColor(Color.argb(225, 245, 245, 245)); maxLines = 3
    }

    private fun tabButton(label: String, selected: Boolean, click: () -> Unit, focusSelect: () -> Unit): TextView = TextView(context).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        isSelected = selected; isFocusable = true; isClickable = true
        refreshTab(this, selected, false)
        setOnClickListener { click() }
        setOnFocusChangeListener { v, has -> if (has) focusSelect(); refreshTab(this, isSelected, has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
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
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        isFocusable = true; isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.argb(238, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        setOnClickListener { click() }
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun buttonRow(left: TextView, right: TextView, leftW: Int = dp(88), rightW: Int = dp(88)): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        addView(left, lparams(leftW, dp(40)).apply { marginEnd = dp(8) })
        addView(right, lparams(rightW, dp(40)))
    }

    private fun inputBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat(); setColor(Color.argb(32, 32, 34, 40))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
    }

    private fun rowBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat(); setColor(Color.argb(22, 255, 255, 255))
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
        BoundaryFocusHandler.shake(v); return true
    }

    private fun findFirstFocusable(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root.isFocusable) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findFirstFocusable(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun showKeyboard(view: View) {
        view.postDelayed({
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun lparams(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun lparams(width: Int, height: Int, weight: Float) = LinearLayout.LayoutParams(width, height, weight)
}
