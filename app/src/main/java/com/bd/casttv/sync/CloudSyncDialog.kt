package com.bd.casttv.sync

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.SharedLiveSourceStore
import com.bd.casttv.util.PasswordUtil
import com.bd.casttv.util.ThemeManager
import java.util.LinkedHashSet

/**
 * 云同步弹窗控制器：管理下载/上传流程、管理员模式解锁，以及合集加密。
 *
 * 合集加密说明：
 * - 每个云端合集可选设置密码；index.json 中记录 passwordHash（SHA-256 小写十六进制）。
 * - 下载有密码的合集需要先输入密码并通过 SHA-256 校验。
 * - 连续输错 3 次进入 30 秒冷却期。
 * - 支持通过「合集内已存在的视频标题关键词」找回密码，验证通过即可重置为新密码。
 * - 管理员和普通用户共用同一套密码流程，无特权绕过。
 *
 * 管理员解锁：TV 端弹窗打开期间连续按 OK 键 5 次；手机端连续快速点击标题 5 次。
 * 第 5 次触发时解锁并 Toast 提示，不关闭弹窗。弹窗关闭后计数重置。
 */
class CloudSyncDialog(
    private val context: Context,
    private val store: FavoritesStore,
    private val onSyncComplete: () -> Unit
) {

    private val syncManager = GiteeSyncManager(store)

    /** 密码尝试与冷却状态（进程内共享，跨对话保留）。 */
    private data class AttemptState(var wrongCount: Int = 0, var cooldownUntilMs: Long = 0L)

    private companion object {
        private const val DEFAULT_MAX_NON_PRESET_DOWNLOAD = GiteeSyncManager.DEFAULT_MAX_NON_PRESET_DOWNLOAD
        private const val PRESET_DOWNLOAD_COUNT = GiteeSyncManager.PRESET_DOWNLOAD_COUNT
        const val ADMIN_UNLOCK_INTERVAL = 1500L
        const val ADMIN_UNLOCK_COUNT = 5

        /** 连续错误多少次进入冷却 */
        const val PASSWORD_MAX_WRONG = 3

        /** 冷却时长 30s（毫秒） */
        const val PASSWORD_COOLDOWN_MS = 30_000L

        /** 全局 passwordId -> AttemptState，进程内共享。 */
        private val passwordAttempts = mutableMapOf<String, AttemptState>()
    }

    @Volatile
    private var isAdmin = false

    private var okPressCount = 0
    private var lastOkPressTime = 0L
    private var titleClickCount = 0
    private var lastTitleClickTime = 0L

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_sync, null)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(view)
            .create()

        val titleView = view.findViewById<TextView>(R.id.syncTitle)
        val subtitleView = view.findViewById<TextView>(R.id.syncSubtitle)
        val btnDownload = view.findViewById<TextView>(R.id.btnDownload)
        val btnUpload = view.findViewById<TextView>(R.id.btnUpload)
        val btnDeleteCloud = view.findViewById<TextView>(R.id.btnDeleteCloud)
        val btnEditDownloadLimit = view.findViewById<TextView>(R.id.btnEditDownloadLimit)
        val btnManageLiveSources = view.findViewById<TextView>(R.id.btnManageLiveSources)

        if (isAdmin) {
            subtitleView.text = "🔑 管理员模式"
            btnDeleteCloud.visibility = View.VISIBLE
            btnEditDownloadLimit.visibility = View.VISIBLE
            btnManageLiveSources.visibility = View.VISIBLE
        }

        dialog.setOnDismissListener {
            okPressCount = 0
            lastOkPressTime = 0L
            titleClickCount = 0
            lastTitleClickTime = 0L
        }

        dialog.setOnKeyListener(DialogInterface.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
                if (!isAdmin) {
                    val now = System.currentTimeMillis()
                    if (now - lastOkPressTime > ADMIN_UNLOCK_INTERVAL) {
                        okPressCount = 0
                    }
                    lastOkPressTime = now
                    okPressCount++

                    if (okPressCount >= ADMIN_UNLOCK_COUNT) {
                        isAdmin = true
                        okPressCount = 0
                        titleClickCount = 0
                        Toast.makeText(context, "🔓 已进入管理员模式", Toast.LENGTH_SHORT).show()
                        subtitleView.text = "🔑 管理员模式"
                        btnDeleteCloud.visibility = View.VISIBLE
                        btnEditDownloadLimit.visibility = View.VISIBLE
                        btnManageLiveSources.visibility = View.VISIBLE
                        return@OnKeyListener true
                    }
                }
            }
            false
        })

        titleView.setOnClickListener {
            if (!isAdmin) {
                val now = System.currentTimeMillis()
                if (now - lastTitleClickTime > ADMIN_UNLOCK_INTERVAL) {
                    titleClickCount = 0
                }
                lastTitleClickTime = now
                titleClickCount++

                if (titleClickCount >= ADMIN_UNLOCK_COUNT) {
                    isAdmin = true
                    titleClickCount = 0
                    okPressCount = 0
                    Toast.makeText(context, "🔓 已进入管理员模式", Toast.LENGTH_SHORT).show()
                    subtitleView.text = "🔑 管理员模式"
                    btnDeleteCloud.visibility = View.VISIBLE
                    btnEditDownloadLimit.visibility = View.VISIBLE
                    btnManageLiveSources.visibility = View.VISIBLE
                }
            }
        }

        btnDownload.setOnClickListener {
            dialog.dismiss()
            showDownloadDialog()
        }

        btnUpload.setOnClickListener {
            dialog.dismiss()
            showUploadDialog()
        }

        btnDeleteCloud.setOnClickListener {
            dialog.dismiss()
            showDeleteDialog()
        }

        btnEditDownloadLimit.setOnClickListener {
            // 需求 3：从主弹窗（删除云端合集下方）进入下载上限配置。
            showDownloadLimitConfigDialog()
        }
        btnManageLiveSources.setOnClickListener {
            showManageSharedLiveSourcesDialog()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnDownload.requestFocus()
    }

    // ------------------------------------------------------------------
    // 下载流程
    // ------------------------------------------------------------------

    private fun showDownloadDialog() {
        Toast.makeText(context, "正在获取云端合集列表...", Toast.LENGTH_SHORT).show()
        Thread {
            val cloudConfig = syncManager.fetchCloudIndexConfig()
            val cloudIndex = cloudConfig?.collections
            val maxNonPresetFromCloud = cloudConfig?.maxNonPresetDownload ?: DEFAULT_MAX_NON_PRESET_DOWNLOAD
            val localPresetCollections = try {
                store.collectionsInfo().filter { it.isPreset || it.isDefault }
            } catch (_: Throwable) {
                emptyList()
            }
            val localPresetIds = localPresetCollections.map { it.id }.toSet()
            val localPresetNames = localPresetCollections.map { it.name }.toSet()
            val cloudById = cloudIndex?.associateBy { it.id }.orEmpty()
            runOnUi {
                if (cloudIndex == null || cloudIndex.isEmpty()) {
                    Toast.makeText(context, "❌ 获取云端列表失败或为空", Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }

                val presetCloudList = localPresetCollections.mapNotNull { local -> cloudById[local.id] }.take(PRESET_DOWNLOAD_COUNT)
                val currentCreatorId = store.currentCreatorId()

                // 下载弹窗可见范围：管理员可见全部非预置；普通用户可见共享合集与自己创建的合集。
                val nonPresetCloudList = cloudIndex
                    .filterNot { it.id in localPresetIds || it.name in localPresetNames }
                    .filter { cloud ->
                        isAdmin ||
                            cloud.type == FavoritesStore.TYPE_SHARED ||
                            (cloud.creatorId.isNotBlank() && cloud.creatorId == currentCreatorId)
                    }

                if (presetCloudList.isEmpty() && nonPresetCloudList.isEmpty()) {
                    Toast.makeText(context, "云端没有可下载的合集", Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }

                val maxNonPresetDownload = maxNonPresetFromCloud.coerceAtLeast(0)
                val listItems = mutableListOf<ListItem>()

                // 预置合集：固定第一行，默认已勾选；不展示 type 与锁入口。
                presetCloudList.forEach {
                    listItems.add(
                        ListItem(
                            id = it.id,
                            label = it.name,
                            checkedByDefault = true,
                            enabled = true,
                            name = it.name,
                            itemCount = it.itemCount,
                            currentType = it.type,
                            isPresetDownload = true
                        )
                    )
                }
                // 非预置合集：默认未勾选；需解锁的加密合集点击卡片或复选框后弹密码框。
                nonPresetCloudList.forEach {
                    val encrypted = it.passwordHash.isNotBlank()
                    val isCreator = it.creatorId.isNotBlank() && it.creatorId == currentCreatorId
                    val needsUnlock = encrypted && (isAdmin || !isCreator)
                    listItems.add(
                        ListItem(
                            id = it.id,
                            label = it.name,
                            checkedByDefault = false,
                            enabled = true,
                            name = it.name,
                            itemCount = it.itemCount,
                            currentType = it.type,
                            isPresetDownload = false,
                            downloadLocked = needsUnlock,
                            lockState = LockState(locked = encrypted, encrypted = encrypted)
                        )
                    )
                }

                showCollectionListDialog(
                    title = "⬇️ 从云端下载",
                    hint = "1. App预置合集，默认可选择下载\n" +
                        "2. 自己创建的合集(共享合集+私有合集)，支持下载\n" +
                        "3. 其他用户上传的共享合集，支持下载(加锁的合集需解锁后下载)",
                    items = listItems,
                    maxSelect = maxNonPresetDownload,
                    cloudIndex = cloudIndex,
                    enforceDownloadLimits = true,
                    initialMaxNonPresetDownload = maxNonPresetDownload
                ) { selectedIds ->
                    performDownload(selectedIds, cloudIndex)
                }
            }
        }.start()
    }

    private fun lockIcon(c: GiteeSyncManager.CloudCollection): String =
        if (c.passwordHash.isNotBlank()) "🔒 " else ""

    /**
     * 逐个校验密码后再下载。
     *
     * 遍历用户勾选的合集，对每个需要密码的合集依次弹出输入框：
     *  - 输入正确：加入待下载列表
     *  - 取消/关闭：跳过该合集
     *  - 输错 3 次：进入 30s 冷却，跳过该合集
     *  - 忘记密码：进入关键词找回流程，成功重置后自动加入待下载列表
     */
    private fun verifyPasswordsThenDownload(
        ids: List<String>,
        cloudIndex: List<GiteeSyncManager.CloudCollection>
    ) {
        val queue = ids.mapNotNull { id -> cloudIndex.firstOrNull { it.id == id } }.toMutableList()
        val approvedIds = mutableListOf<String>()

        fun next() {
            if (queue.isEmpty()) {
                if (approvedIds.isEmpty()) {
                    Toast.makeText(context, "没有可下载的合集", Toast.LENGTH_SHORT).show()
                } else {
                    performDownload(approvedIds, cloudIndex)
                }
                return
            }
            val c = queue.removeAt(0)
            if (c.passwordHash.isBlank()) {
                approvedIds.add(c.id)
                next()
                return
            }
            // 冷却期检查
            val state = passwordAttempts.getOrPut(c.id) { AttemptState() }
            val remainMs = state.cooldownUntilMs - System.currentTimeMillis()
            if (remainMs > 0) {
                Toast.makeText(
                    context,
                    "「${c.name}」密码错误次数过多，请 ${(remainMs / 1000) + 1}s 后重试",
                    Toast.LENGTH_SHORT
                ).show()
                next()
                return
            }
            promptDownloadPassword(c,
                onOk = {
                    approvedIds.add(c.id)
                    passwordAttempts[c.id] = AttemptState() // 通过后重置
                    next()
                },
                onCancel = { next() },
                onForgot = {
                    startForgotPasswordFlow(c) { newHash ->
                        // 密码已重置成功，自动加入下载列表
                        approvedIds.add(c.id)
                        passwordAttempts[c.id] = AttemptState()
                        // newHash 仅用于回调告知调用方，具体密码内容不需要在此处复用。
                        newHash.let { /* no-op */ }
                        next()
                    }
                }
            )
        }
        next()
    }

    private fun performDownload(ids: List<String>, cloudIndex: List<GiteeSyncManager.CloudCollection>) {
        Toast.makeText(context, "正在下载 ${ids.size} 个合集...", Toast.LENGTH_SHORT).show()
        Thread {
            val count = syncManager.downloadCollections(ids, cloudIndex)
            runOnUi {
                if (count > 0) {
                    syncManager.incrementDownloadCount(ids)
                    Toast.makeText(context, "✅ 成功下载 $count 个合集", Toast.LENGTH_SHORT).show()
                    onSyncComplete()
                } else {
                    Toast.makeText(context, "❌ 下载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 上传流程
    // ------------------------------------------------------------------

    private fun showUploadDialog() {
        Toast.makeText(context, "正在读取本地合集...", Toast.LENGTH_SHORT).show()
        Thread {
            // 上传弹窗可见范围：管理员可见全部；普通用户可见共享合集与自己创建的合集。
            val collections = try { store.collections() } catch (_: Throwable) { emptyList() }
            if (collections.isEmpty()) {
                runOnUi {
                    Toast.makeText(context, "没有可上传的合集", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            runOnUi {
                Toast.makeText(context, "正在检查云端加密状态...", Toast.LENGTH_SHORT).show()
            }
            val cloudIndex = syncManager.fetchCloudIndex() ?: emptyList()
            val cloudById = cloudIndex.associateBy { it.id }
            runOnUi {
                val currentCreatorId = store.currentCreatorId()
                val passwordActions = mutableMapOf<String, GiteeSyncManager.PasswordAction>()
                val typeOverrides = mutableMapOf<String, String>()
                val visibleCollections = collections.filter { c ->
                    val isCreator = c.creatorId.isNotBlank() && c.creatorId == currentCreatorId
                    isAdmin || c.type == FavoritesStore.TYPE_SHARED || isCreator
                }
                val presetCollections = visibleCollections.filter { it.isPreset || it.isDefault }.take(PRESET_DOWNLOAD_COUNT)
                val normalCollections = visibleCollections.filterNot { c -> presetCollections.any { it.id == c.id } }
                val orderedCollections = presetCollections + normalCollections.sortedBy { it.name }
                val items = orderedCollections.map { c ->
                    val isPresetCollection = c.isPreset || c.isDefault
                    val isCreator = c.creatorId.isNotBlank() && c.creatorId == currentCreatorId
                    val isOwnOrAdmin = isAdmin || isCreator
                    val canModifyType = !isPresetCollection && isOwnOrAdmin
                    val canOperateLock = !isPresetCollection && isOwnOrAdmin
                    val canUpload = isAdmin || isCreator || c.type == FavoritesStore.TYPE_SHARED
                    val cloudHash = cloudById[c.id]?.passwordHash.orEmpty()
                    val locked = cloudHash.isNotBlank() || c.passwordHash.isNotBlank()
                    if (locked) passwordActions[c.id] = GiteeSyncManager.PasswordAction.Keep
                    typeOverrides[c.id] = c.type
                    ListItem(
                        id = c.id,
                        label = c.name,
                        checkedByDefault = false,
                        enabled = true,
                        name = c.name,
                        itemCount = c.items.size,
                        lockState = if (isPresetCollection) null else LockState(locked = locked, encrypted = true, uploadCollection = c),
                        blockedToast = if (canUpload) null else "他人私有合集不可上传",
                        currentType = c.type,
                        canToggleType = canModifyType,
                        canToggleLock = canOperateLock,
                        canShowMenu = !isPresetCollection && isOwnOrAdmin,
                        isPresetDownload = isPresetCollection
                    )
                }

                if (items.isEmpty()) {
                    Toast.makeText(context, "没有可上传的合集", Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }

                showCollectionListDialog(
                    title = "⬆️ 上传到云端",
                    hint = if (isAdmin) {
                        "· 管理员可见并可上传全部合集\n· 任意合集均可通过菜单修改加锁/解锁和私有/共享"
                    } else {
                        "· 可上传共享合集与自己创建的合集\n· 仅自己创建的合集可通过菜单修改加锁/解锁和私有/共享"
                    },
                    items = items,
                    maxSelect = collections.size.coerceAtLeast(20),
                    uploadPasswordActions = passwordActions,
                    uploadTypeOverrides = typeOverrides
                ) { selectedIds ->
                    val toUpload = collections.filter { it.id in selectedIds }
                    val actions = selectedIds.associateWith { id ->
                        passwordActions[id] ?: GiteeSyncManager.PasswordAction.Clear
                    }
                    performUpload(toUpload, actions, typeOverrides.filterKeys { it in selectedIds })
                }
            }
        }.start()
    }

    /** 上传前先拉取云端 index 判断每个合集当前是否有密码，再逐个问用户想怎么做。 */
    private fun startUploadPasswordFlow(selectedIds: List<String>) {
        val allCollections = store.collections()
        val toUpload = allCollections.filter { it.id in selectedIds }
        if (toUpload.isEmpty()) return

        Toast.makeText(context, "正在检查云端加密状态...", Toast.LENGTH_SHORT).show()
        Thread {
            val cloudIndex = syncManager.fetchCloudIndex() ?: emptyList()
            val cloudById = cloudIndex.associateBy { it.id }
            runOnUi {
                val actions = mutableMapOf<String, GiteeSyncManager.PasswordAction>()
                val queue = toUpload.toMutableList()

                fun next() {
                    if (queue.isEmpty()) {
                        performUpload(toUpload, actions)
                        return
                    }
                    val c = queue.removeAt(0)
                    val cloudMeta = cloudById[c.id]
                    val cloudHash = cloudMeta?.passwordHash.orEmpty()

                    if (cloudHash.isNotBlank()) {
                        // 场景 B：云端已有密码 → 保持/修改/取消加密
                        showEncryptedUploadStrategy(c, cloudHash,
                            onKeep = {
                                actions[c.id] = GiteeSyncManager.PasswordAction.Keep
                                next()
                            },
                            onChange = { newHash ->
                                actions[c.id] = GiteeSyncManager.PasswordAction.Set(newHash)
                                next()
                            },
                            onClear = {
                                actions[c.id] = GiteeSyncManager.PasswordAction.Clear
                                Toast.makeText(
                                    context,
                                    "「${c.name}」已改为公开，任何人无需密码可下载",
                                    Toast.LENGTH_SHORT
                                ).show()
                                next()
                            },
                            onCancel = {
                                // 用户彻底取消该合集的上传：从上传列表移除
                                Toast.makeText(context, "已跳过 ${c.name}", Toast.LENGTH_SHORT).show()
                                actions.remove(c.id)
                                next()
                            }
                        )
                    } else {
                        // 场景 A / C：云端未加密 → 询问是否设置密码
                        showUnencryptedUploadStrategy(c,
                            isFirstUpload = (cloudMeta == null),
                            onSet = { newHash ->
                                actions[c.id] = GiteeSyncManager.PasswordAction.Set(newHash)
                                next()
                            },
                            onSkip = {
                                actions[c.id] = GiteeSyncManager.PasswordAction.Keep
                                next()
                            },
                            onCancel = {
                                Toast.makeText(context, "已跳过 ${c.name}", Toast.LENGTH_SHORT).show()
                                actions.remove(c.id)
                                next()
                            }
                        )
                    }
                }
                next()
            }
        }.start()
    }

    private fun performUpload(
        toUpload: List<FavoritesStore.FavoriteCollection>,
        passwordActions: Map<String, GiteeSyncManager.PasswordAction>,
        typeOverrides: Map<String, String> = emptyMap()
    ) {
        // 只上传 actions 中存在（即用户没有彻底取消）的合集
        val effective = toUpload.filter { passwordActions.containsKey(it.id) }
        if (effective.isEmpty()) {
            Toast.makeText(context, "本次没有可上传的合集", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "正在上传 ${effective.size} 个合集...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = syncManager.uploadCollections(
                collections = effective,
                typeOverrides = typeOverrides,
                passwordActions = passwordActions
            )
            runOnUi {
                if (result.successCount > 0 && result.errorMessage == null) {
                    Toast.makeText(context, "✅ 成功上传 ${result.successCount} 个合集", Toast.LENGTH_SHORT).show()
                } else if (result.successCount > 0) {
                    Toast.makeText(context, "⚠️ 已上传 ${result.successCount} 个合集，详情请看网络诊断", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "❌ 上传失败，详情请看网络诊断", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 删除流程（管理员）
    // ------------------------------------------------------------------

    private fun showDeleteDialog() {
        if (!isAdmin) return
        Toast.makeText(context, "正在获取云端合集列表...", Toast.LENGTH_SHORT).show()
        Thread {
            val cloudIndex = syncManager.fetchCloudIndex()
            runOnUi {
                if (cloudIndex == null || cloudIndex.isEmpty()) {
                    Toast.makeText(context, "❌ 获取云端列表失败或为空", Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }
                val deletable = cloudIndex.filter { !it.id.startsWith("preset-") }
                if (deletable.isEmpty()) {
                    Toast.makeText(context, "云端暂无可删除的非预置合集", Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }

                showCollectionListDialog(
                    title = "🗑️ 删除云端合集",
                    hint = "⚠️ 选中的合集将从云端永久删除！",
                    items = deletable.map {
                        ListItem(it.id, "${lockIcon(it)}${it.name} [${it.type}]", false)
                    },
                    maxSelect = 20
                ) { selectedIds ->
                    performDelete(selectedIds)
                }
            }
        }.start()
    }

    private fun performDelete(ids: List<String>) {
        Toast.makeText(context, "正在删除 ${ids.size} 个云端合集...", Toast.LENGTH_SHORT).show()
        Thread {
            var count = 0
            for (id in ids) {
                if (syncManager.deleteCloudCollection(id)) count++
            }
            runOnUi {
                if (count > 0) {
                    Toast.makeText(context, "✅ 已删除 $count 个云端合集", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "❌ 删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 修改下载上限（管理员，主弹窗入口）
    // ------------------------------------------------------------------

    /**
     * 需求 3：从主云同步弹窗进入的「修改下载上限」独立弹窗。
     * 管理员可修改云端 index.json 的 maxNonPresetDownload（可下载非预置合集数），
     * 保存后通过 Gitee API 写回云端，对所有用户生效。
     */
    private fun showDownloadLimitConfigDialog() {
        if (!isAdmin) return
        Toast.makeText(context, "正在读取云端配置...", Toast.LENGTH_SHORT).show()
        Thread {
            val current = syncManager.fetchCloudIndexConfig()?.maxNonPresetDownload
                ?: DEFAULT_MAX_NON_PRESET_DOWNLOAD
            runOnUi {
                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = themedDownloadLimitDialogBackground()
                    setPadding(dp(24), dp(20), dp(24), dp(20))
                    clipChildren = false
                    clipToPadding = false
                    minimumWidth = dp(480)
                }
                root.addView(buildDownloadLimitDialogTitle("修改下载上限"), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(58)
                ))
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    clipChildren = false
                    clipToPadding = false
                }
                content.addView(TextView(context).apply {
                    text = "总下载上限 = 预置合集 $PRESET_DOWNLOAD_COUNT 个 + 可下载非预置合集数"
                    textSize = 14f
                    setTextColor(Color.argb(235, 245, 245, 245))
                    setPadding(0, 0, 0, dp(12))
                })
                val nonPresetInput = EditText(context).apply {
                    hint = "可下载非预置合集数"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_input)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setHintTextColor(ContextCompat.getColor(context, R.color.text_hint))
                    setText(current.toString())
                    setSelection(text.length)
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                content.addView(nonPresetInput, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                root.addView(content, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(18) })

                val buttonRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.RIGHT
                    clipChildren = false
                    clipToPadding = false
                }
                val cancelButton = TextView(context).apply {
                    text = "取消"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    minWidth = dp(92)
                }
                val saveButton = TextView(context).apply {
                    text = "保存"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    minWidth = dp(92)
                }
                styleDialogChoiceButton(cancelButton)
                styleDialogChoiceButton(saveButton)
                buttonRow.addView(cancelButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
                buttonRow.addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(12) })
                root.addView(buttonRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(18) })

                val configDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
                    .setView(root)
                    .create()
                cancelButton.setOnClickListener { configDialog.dismiss() }
                saveButton.setOnClickListener {
                    val nonPreset = nonPresetInput.text.toString().toIntOrNull()
                    if (nonPreset == null || nonPreset <= 0) {
                        Toast.makeText(context, "请输入正整数", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(context, "正在保存到云端...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val ok = syncManager.updateMaxNonPresetDownload(nonPreset)
                        runOnUi {
                            if (ok) {
                                configDialog.dismiss()
                                Toast.makeText(context, "已保存，对所有用户生效", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "保存失败，详情请看网络诊断", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
                configDialog.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            configDialog.dismiss()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (nonPresetInput.hasFocus()) {
                                shakeBoundary(root)
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (cancelButton.hasFocus() || saveButton.hasFocus()) {
                                shakeBoundary(root)
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                configDialog.setOnShowListener {
                    configDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    configDialog.window?.setGravity(Gravity.CENTER)
                    configDialog.window?.setLayout(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT)
                    nonPresetInput.requestFocus()
                }
                configDialog.show()
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 共享直播源管理（管理员）
    // ------------------------------------------------------------------

    private fun showManageSharedLiveSourcesDialog() {
        if (!isAdmin) return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_sync_list, null)
        val titleView = view.findViewById<TextView>(R.id.listTitle)
        val hintView = view.findViewById<TextView>(R.id.listHint)
        val limitTip = view.findViewById<TextView>(R.id.listLimitTip)
        val listContainer = view.findViewById<LinearLayout>(R.id.listContainer)
        val btnAdd = view.findViewById<TextView>(R.id.btnSelectAll)
        val btnClose = view.findViewById<TextView>(R.id.btnCancel)
        val btnRefresh = view.findViewById<TextView>(R.id.btnConfirm)
        titleView.text = "📡 管理 M3U 直播源地址"
        hintView.text = "支持编辑名称/地址、删除，以及为地址设置推荐等标签；标签会展示在云端共享 Tab 的直播源名称右侧。"
        limitTip.visibility = View.GONE
        btnAdd.text = "新增地址"
        btnClose.text = "关闭"
        btnRefresh.text = "刷新"
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(view)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            btnAdd.requestFocus()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        lateinit var reload: () -> Unit
        fun renderRows(sources: List<SharedLiveSourceStore.SharedSource>) {
            listContainer.removeAllViews()
            if (sources.isEmpty()) {
                listContainer.addView(buildManageEmptyView("暂无共享直播源，可先新增地址"))
                return
            }
            sources.forEach { source ->
                listContainer.addView(buildManageSourceRow(source, dialog) { reload() })
            }
        }
        reload = {
            listContainer.removeAllViews()
            listContainer.addView(buildManageEmptyView("正在加载云端直播源..."))
            Thread {
                when (val result = SharedLiveSourceStore.fetchSources()) {
                    is SharedLiveSourceStore.LoadResult.Success -> runOnUi {
                        if (dialog.isShowing) renderRows(result.sources)
                    }
                    is SharedLiveSourceStore.LoadResult.Error -> runOnUi {
                        if (!dialog.isShowing) return@runOnUi
                        listContainer.removeAllViews()
                        listContainer.addView(buildManageEmptyView("加载失败：${result.message}"))
                        Toast.makeText(context, "加载失败：${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        btnAdd.setOnClickListener {
            showEditSharedLiveSourceDialog(source = null) { reload() }
        }
        btnRefresh.setOnClickListener { reload() }
        dialog.show()
        reload()
    }

    private fun buildManageSourceRow(
        source: SharedLiveSourceStore.SharedSource,
        parentDialog: AlertDialog,
        onChanged: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_shared_source_card)
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (12 * density).toInt()
        }
        card.layoutParams = lp

        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val nameView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = source.name
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        nameRow.addView(nameView)
        val tagText = source.tags.joinToString(" · ")
        if (tagText.isNotBlank()) {
            nameRow.addView(buildTagView(tagText))
        }
        card.addView(nameRow)

        card.addView(TextView(context).apply {
            text = source.url
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })
        card.addView(TextView(context).apply {
            text = "${source.groupCount} 个分组，${source.channelCount} 个频道 · 上传于 ${SharedLiveSourceStore.displayTime(source.uploadedAt)} · 👍 ${source.likeCount}"
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 12f
            setPadding(0, (6 * density).toInt(), 0, 0)
        })

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val btnEdit = buildIconActionButton(R.drawable.ic_dock_tab_edit, "编辑")
        val btnDelete = buildIconActionButton(R.drawable.ic_dock_tab_delete, "删除")
        actionRow.addView(btnEdit)
        actionRow.addView(btnDelete, LinearLayout.LayoutParams(
            (42 * density).toInt(),
            (42 * density).toInt()
        ).apply { leftMargin = (10 * density).toInt() })
        card.addView(actionRow)

        btnEdit.setOnClickListener {
            showEditSharedLiveSourceDialog(source) { onChanged() }
        }
        btnDelete.setOnClickListener {
            showDeleteSharedLiveSourceConfirm(source, parentDialog, onChanged)
        }
        return card
    }

    private fun showDeleteSharedLiveSourceConfirm(
        source: SharedLiveSourceStore.SharedSource,
        parentDialog: AlertDialog,
        onDeleted: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_favorites_dialog_root_crayon)
            setPadding((28 * density).toInt(), (24 * density).toInt(), (28 * density).toInt(), (24 * density).toInt())
        }
        container.addView(TextView(context).apply {
            text = "🗑️ 删除直播源"
            setTextColor(Color.parseColor("#101217"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_crayon_header)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        })
        container.addView(TextView(context).apply {
            text = "确认删除「${source.name}」吗？删除后将从云端共享 Tab 中移除。"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            setPadding(0, (20 * density).toInt(), 0, 0)
        })
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, (24 * density).toInt(), 0, 0)
        }
        val btnCancel = buildActionButton("取消")
        val btnConfirm = buildActionButton("确认删除", isDanger = true)
        actionRow.addView(btnCancel)
        actionRow.addView(btnConfirm, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (10 * density).toInt() })
        container.addView(actionRow)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(container)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            btnCancel.requestFocus()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            btnConfirm.isEnabled = false
            btnCancel.isEnabled = false
            btnConfirm.text = "删除中..."
            Thread {
                val result = SharedLiveSourceStore.deleteSource(source.id)
                runOnUi {
                    if (!dialog.isShowing) return@runOnUi
                    when (result) {
                        SharedLiveSourceStore.DeleteResult.Success -> {
                            dialog.dismiss()
                            if (parentDialog.isShowing) onDeleted()
                            Toast.makeText(context, "已删除直播源", Toast.LENGTH_SHORT).show()
                        }
                        is SharedLiveSourceStore.DeleteResult.Error -> {
                            btnConfirm.isEnabled = true
                            btnCancel.isEnabled = true
                            btnConfirm.text = "确认删除"
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }
        dialog.show()
    }

    private fun showEditSharedLiveSourceDialog(
        source: SharedLiveSourceStore.SharedSource?,
        onSaved: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val presetTags = linkedSetOf("推荐", "稳定", "高清", "体育", "央视", "地方")
        val selectedTags = LinkedHashSet<String>().apply { addAll(source?.tags.orEmpty()) }
        val cardPadding = (28 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_favorites_dialog_root_crayon)
            setPadding(cardPadding, (24 * density).toInt(), cardPadding, (24 * density).toInt())
        }
        root.addView(TextView(context).apply {
            text = if (source == null) "➕ 新增直播源地址" else "✏️ 编辑直播源地址"
            setTextColor(Color.parseColor("#101217"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_crayon_header)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        })
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        scrollView.addView(form)
        root.addView(scrollView)

        fun addLabel(text: String) {
            form.addView(TextView(context).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.crayon_yellow))
                textSize = 14f
                setPadding(0, 0, 0, (8 * density).toInt())
            })
        }
        fun buildInput(hint: String, value: String, singleLine: Boolean = true): EditText = EditText(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_input)
            setText(value)
            setHint(hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 15f
            isSingleLine = singleLine
            if (!singleLine) minLines = 2
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }

        addLabel("直播源名称")
        val nameInput = buildInput("如：北京移动 IPTV", source?.name.orEmpty())
        form.addView(nameInput)

        addLabel("直播源地址")
        val urlInput = buildInput("粘贴 M3U/M3U8 或直播源 JSON 直链", source?.url.orEmpty(), singleLine = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        form.addView(urlInput)

        addLabel("选择标签")
        val tagHint = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
            text = "点击下方标签即可切换，也支持新增自定义标签。"
        }
        form.addView(tagHint)
        val tagGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        form.addView(tagGroup)
        val selectedPreview = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 12f
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        form.addView(selectedPreview)

        fun refreshSelectedPreview() {
            selectedPreview.text = if (selectedTags.isEmpty()) "当前未设置标签" else "当前标签：${selectedTags.joinToString(" · ")}"
        }

        fun rebuildTagGroup() {
            tagGroup.removeAllViews()
            val allTags = (presetTags + selectedTags).distinct()
            allTags.chunked(5).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { tag ->
                    row.addView(
                        buildToggleTagChip(tag, selectedTags.contains(tag)) {
                            if (selectedTags.contains(tag)) selectedTags.remove(tag) else selectedTags.add(tag)
                            refreshSelectedPreview()
                            rebuildTagGroup()
                        }
                    )
                }
                tagGroup.addView(row)
            }
        }
        rebuildTagGroup()
        refreshSelectedPreview()

        addLabel("新增自定义标签")
        val customTagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val customTagInput = buildInput("输入标签，如：推荐", "").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAddCustomTag = buildActionButton("确认")
        customTagRow.addView(customTagInput)
        customTagRow.addView(btnAddCustomTag, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (10 * density).toInt() })
        form.addView(customTagRow)
        btnAddCustomTag.setOnClickListener {
            val extraTags = customTagInput.text?.toString().orEmpty()
                .split('，', ',', '、', '|', '/', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (extraTags.isEmpty()) {
                Toast.makeText(context, "请输入自定义标签", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            presetTags.addAll(extraTags)
            selectedTags.addAll(extraTags)
            customTagInput.setText("")
            refreshSelectedPreview()
            rebuildTagGroup()
            Toast.makeText(context, "已添加到选择标签", Toast.LENGTH_SHORT).show()
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, (24 * density).toInt(), 0, 0)
        }
        val btnCancel = buildActionButton("取消")
        val saveButtonText = if (source == null) "保存新增" else "保存修改"
        val btnSave = buildActionButton(saveButtonText)
        buttonRow.addView(btnCancel)
        buttonRow.addView(btnSave, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (10 * density).toInt() })
        root.addView(buttonRow)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(root)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            nameInput.requestFocus()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val extraTags = customTagInput.text?.toString().orEmpty()
                .split('，', ',', '、', '|', '/', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (extraTags.isNotEmpty()) {
                presetTags.addAll(extraTags)
                selectedTags.addAll(extraTags)
            }
            val finalUrl = urlInput.text?.toString().orEmpty().trim()
            if (finalUrl.isEmpty()) {
                Toast.makeText(context, "请输入直播源地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            btnCancel.isEnabled = false
            btnSave.text = "保存中..."
            val finalName = nameInput.text?.toString().orEmpty()
            val finalTags = selectedTags.toList()
            Thread {
                val result = if (source == null) {
                    val displayName = finalName.trim().ifBlank { finalUrl }
                    SharedLiveSourceStore.shareSource(displayName, finalUrl, 0, 0).let {
                        when (it) {
                            SharedLiveSourceStore.ShareResult.Success -> SharedLiveSourceStore.UpdateResult.Success
                            SharedLiveSourceStore.ShareResult.AlreadyShared -> SharedLiveSourceStore.UpdateResult.Error("该直播源地址已存在")
                            is SharedLiveSourceStore.ShareResult.Error -> SharedLiveSourceStore.UpdateResult.Error(it.message)
                        }
                    }
                } else {
                    SharedLiveSourceStore.updateSource(source.id, finalName, finalUrl, finalTags)
                }
                runOnUi {
                    if (!dialog.isShowing) return@runOnUi
                    when (result) {
                        SharedLiveSourceStore.UpdateResult.Success -> {
                            if (source == null && finalTags.isNotEmpty()) {
                                Thread {
                                    when (val reloadResult = SharedLiveSourceStore.fetchSources()) {
                                        is SharedLiveSourceStore.LoadResult.Success -> {
                                            val created = reloadResult.sources.firstOrNull { it.url.trim() == finalUrl }
                                            if (created != null) {
                                                SharedLiveSourceStore.updateSource(created.id, created.name, created.url, finalTags)
                                            }
                                            runOnUi {
                                                if (dialog.isShowing) {
                                                    dialog.dismiss()
                                                    onSaved()
                                                    Toast.makeText(context, "已保存直播源", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        is SharedLiveSourceStore.LoadResult.Error -> runOnUi {
                                            btnSave.isEnabled = true
                                            btnCancel.isEnabled = true
                                            btnSave.text = saveButtonText
                                            Toast.makeText(context, reloadResult.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }.start()
                                return@runOnUi
                            }
                            dialog.dismiss()
                            onSaved()
                            Toast.makeText(context, "已保存直播源", Toast.LENGTH_SHORT).show()
                        }
                        is SharedLiveSourceStore.UpdateResult.Error -> {
                            btnSave.isEnabled = true
                            btnCancel.isEnabled = true
                            btnSave.text = saveButtonText
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }
        dialog.show()
    }

    private fun buildManageEmptyView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setPadding(0, 32, 0, 32)
    }

    private fun buildIconActionButton(iconRes: Int, description: String): ImageView {
        val density = context.resources.displayMetrics.density
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_dock_manage_icon_button)
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(context, R.color.dock_manage_icon_tint)
            contentDescription = description
            isFocusable = true
            isClickable = true
            setPadding((9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun shakeBoundary(viewToShake: View) {
        val d = 6f * context.resources.displayMetrics.density
        viewToShake.animate().translationX(d)
            .setInterpolator(android.view.animation.CycleInterpolator(2f))
            .setDuration(200)
            .withEndAction { viewToShake.translationX = 0f }
            .start()
    }

    private fun buildCrayonDialogTitle(title: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(com.bd.casttv.ui.ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun themedDownloadLimitDialogBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        ThemeManager.currentPalette(context).dialogTitleGradient
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(2), ContextCompat.getColor(context, R.color.crayon_yellow))
    }

    private fun buildDownloadLimitDialogTitle(title: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_crayon_header)
        setPadding(dp(12), dp(7), dp(16), dp(7))
        addView(com.bd.casttv.ui.ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
            contentDescription = null
            isFocusable = false
            isClickable = false
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildActionButton(text: String, isDanger: Boolean = false): TextView = TextView(context).apply {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        this.text = text
        background = ContextCompat.getDrawable(context, R.drawable.bg_standard_action_button)
        isFocusable = true
        isClickable = true
        setPadding(dp(20), dp(14), dp(20), dp(14))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(
            if (isDanger) ColorStateList.valueOf(Color.parseColor("#FF6B6B"))
            else ContextCompat.getColorStateList(context, R.color.standard_action_button_text)
        )
    }

    private fun styleDialogChoiceButton(button: TextView) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val warm = ContextCompat.getColor(context, R.color.crayon_yellow)
        val normalText = Color.argb(235, 245, 245, 245)
        val normalStroke = Color.argb(170, 210, 214, 222)
        val normalBg = Color.argb(52, 32, 34, 40)
        fun refresh(focused: Boolean) {
            button.setTextColor(if (button.isSelected) warm else normalText)
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(normalBg)
                setStroke(dp(if (focused) 2 else 1), if (focused) warm else normalStroke)
            }
        }
        button.isFocusable = true
        button.isFocusableInTouchMode = false
        button.isClickable = true
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.setPadding(dp(18), dp(10), dp(18), dp(10))
        refresh(false)
        button.setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            com.bd.casttv.ui.framework.FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
    }

    private fun buildTagView(text: String): TextView = TextView(context).apply {
        this.text = text
        background = ContextCompat.getDrawable(context, R.drawable.bg_shared_source_tag)
        setTextColor(ContextCompat.getColor(context, R.color.crayon_yellow))
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(10, 4, 10, 4)
    }

    private fun buildToggleTagChip(
        tag: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = tag
        isFocusable = true
        isClickable = true
        setTypeface(typeface, Typeface.BOLD)
        textSize = 13f
        setPadding(18, 10, 18, 10)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.TRANSPARENT)
            setStroke(2, if (selected) ContextCompat.getColor(context, R.color.crayon_yellow) else Color.parseColor("#55FFFFFF"))
        }
        background = bg
        setTextColor(if (selected) ContextCompat.getColor(context, R.color.crayon_yellow) else ContextCompat.getColor(context, R.color.text_primary))
        (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = 8
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = 8
            bottomMargin = 8
        }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------
    // 密码相关弹窗
    // ------------------------------------------------------------------

    /** 下载时弹出的密码输入弹窗，支持忘记密码入口。 */
    private fun promptDownloadPassword(
        c: GiteeSyncManager.CloudCollection,
        onOk: () -> Unit,
        onCancel: () -> Unit,
        onForgot: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        val header = view.findViewById<TextView>(R.id.pwdDialogHeader)
        val subtitle = view.findViewById<TextView>(R.id.pwdDialogSubtitle)
        val input = view.findViewById<EditText>(R.id.pwdInputField)
        val errorTip = view.findViewById<TextView>(R.id.pwdErrorTip)
        val btnForgot = view.findViewById<TextView>(R.id.btnForgotPwd)
        val btnCancel = view.findViewById<TextView>(R.id.btnPwdCancel)
        val btnOk = view.findViewById<TextView>(R.id.btnPwdOk)

        header.text = "🔒 输入合集密码"
        subtitle.text = "「${c.name}」已加密，请输入密码后下载。"

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(view).create()

        btnOk.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isEmpty()) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "请输入密码"
                return@setOnClickListener
            }
            if (PasswordUtil.matches(text, c.passwordHash)) {
                dialog.dismiss()
                onOk()
                return@setOnClickListener
            }
            // 错误：累加尝试
            val state = passwordAttempts.getOrPut(c.id) { AttemptState() }
            state.wrongCount++
            if (state.wrongCount >= PASSWORD_MAX_WRONG) {
                state.cooldownUntilMs = System.currentTimeMillis() + PASSWORD_COOLDOWN_MS
                state.wrongCount = 0
                dialog.dismiss()
                Toast.makeText(
                    context,
                    "密码连续错误 $PASSWORD_MAX_WRONG 次，${PASSWORD_COOLDOWN_MS / 1000}s 后再试",
                    Toast.LENGTH_LONG
                ).show()
                onCancel()
            } else {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "密码错误（还剩 ${PASSWORD_MAX_WRONG - state.wrongCount} 次机会）"
                input.selectAll()
            }
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        btnForgot.setOnClickListener {
            dialog.dismiss()
            onForgot()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        input.requestFocus()
    }

    /**
     * 云端已加密的合集重新上传时的策略选择：
     *  - 保持原密码：onKeep
     *  - 修改密码：先验证旧密码，再让用户输入两次新密码，onChange(newHash)
     *  - 取消加密：先验证旧密码，onClear
     */
    private fun showEncryptedUploadStrategy(
        c: FavoritesStore.FavoriteCollection,
        cloudHash: String,
        onKeep: () -> Unit,
        onChange: (String) -> Unit,
        onClear: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setTitle("「${c.name}」已加密")
            .setMessage("请选择本次上传时的密码处理方式：")
            .setPositiveButton("保持原密码") { d, _ ->
                d.dismiss()
                onKeep()
            }
            .setNeutralButton("修改密码") { d, _ ->
                d.dismiss()
                verifyOldThen(c, cloudHash) {
                    inputNewPasswordTwice(c, "设置新密码") { newHash -> onChange(newHash) }
                }
            }
            .setNegativeButton("取消加密") { d, _ ->
                d.dismiss()
                verifyOldThen(c, cloudHash) { onClear() }
            }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun showUnencryptedUploadStrategy(
        c: FavoritesStore.FavoriteCollection,
        isFirstUpload: Boolean,
        onSet: (String) -> Unit,
        onSkip: () -> Unit,
        onCancel: () -> Unit
    ) {
        val title = if (isFirstUpload) "「${c.name}」首次上传" else "「${c.name}」当前未加密"
        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setTitle(title)
            .setMessage("是否为该合集设置访问密码？未设置的合集所有人都可下载。")
            .setPositiveButton("设置密码") { d, _ ->
                d.dismiss()
                inputNewPasswordTwice(c, "设置密码") { newHash -> onSet(newHash) }
            }
            .setNegativeButton("跳过") { d, _ ->
                d.dismiss()
                onSkip()
            }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /** 先弹出旧密码校验，通过后回调 onPass；输错允许在同弹窗内重试，取消则终止。 */
    private fun verifyOldThen(
        c: FavoritesStore.FavoriteCollection,
        cloudHash: String,
        onPass: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        val header = view.findViewById<TextView>(R.id.pwdDialogHeader)
        val subtitle = view.findViewById<TextView>(R.id.pwdDialogSubtitle)
        val input = view.findViewById<EditText>(R.id.pwdInputField)
        val errorTip = view.findViewById<TextView>(R.id.pwdErrorTip)
        val btnForgot = view.findViewById<TextView>(R.id.btnForgotPwd)
        val btnCancel = view.findViewById<TextView>(R.id.btnPwdCancel)
        val btnOk = view.findViewById<TextView>(R.id.btnPwdOk)

        header.text = "🔒 验证旧密码"
        subtitle.text = "为「${c.name}」变更密码前，请先输入现有密码。"
        btnForgot.visibility = View.GONE

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(view).create()
        btnOk.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isEmpty()) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "请输入密码"
                return@setOnClickListener
            }
            if (PasswordUtil.matches(text, cloudHash)) {
                dialog.dismiss()
                onPass()
            } else {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "密码错误，请重试"
                input.selectAll()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        input.requestFocus()
    }

    /** 两次输入新密码，一致则回调 SHA-256 哈希。 */
    private fun inputNewPasswordTwice(
        c: FavoritesStore.FavoriteCollection,
        title: String,
        onOk: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        val header = view.findViewById<TextView>(R.id.pwdDialogHeader)
        val subtitle = view.findViewById<TextView>(R.id.pwdDialogSubtitle)
        val input1 = view.findViewById<EditText>(R.id.pwdInputField)
        val input2 = view.findViewById<EditText>(R.id.pwdInputField2)
        val errorTip = view.findViewById<TextView>(R.id.pwdErrorTip)
        val btnForgot = view.findViewById<TextView>(R.id.btnForgotPwd)
        val btnCancel = view.findViewById<TextView>(R.id.btnPwdCancel)
        val btnOk = view.findViewById<TextView>(R.id.btnPwdOk)

        header.text = "🔒 $title"
        subtitle.text = "为「${c.name}」设置访问密码，需要输入两次确认。"
        input1.hint = "请输入新密码"
        input2.visibility = View.VISIBLE
        btnForgot.visibility = View.GONE

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(view).create()
        btnOk.setOnClickListener {
            val a = input1.text?.toString().orEmpty()
            val b = input2.text?.toString().orEmpty()
            if (a.isEmpty() || b.isEmpty()) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "请输入两次密码"
                return@setOnClickListener
            }
            if (a != b) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "两次密码不一致，请重新输入"
                return@setOnClickListener
            }
            if (a.length < 1) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "密码不能为空"
                return@setOnClickListener
            }
            dialog.dismiss()
            onOk(PasswordUtil.sha256(a))
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        input1.requestFocus()
    }

    // ------------------------------------------------------------------
    // 忘记密码：关键词模糊匹配 → 设置新密码 → 立即上传更新 passwordHash
    // ------------------------------------------------------------------

    /**
     * 关键词方案找回密码：
     *  1) 同步拉取云端合集数据（仅用于校验）
     *  2) 视频数为 0 或网络失败 → 提示原因终止
     *  3) 提示输入合集内已存在的视频标题关键词，模糊匹配（包含即通过，大小写不敏感）
     *  4) 匹配成功 → 输入新密码（两次） → SHA-256 → 立即上传 passwordHash
     *  5) 上传成功 → Toast 提示 → 触发 onSuccess(newHash)
     *  6) 匹配失败 → Toast 提示可重试
     */
    private fun startForgotPasswordFlow(
        c: GiteeSyncManager.CloudCollection,
        onSuccess: (String) -> Unit
    ) {
        Toast.makeText(context, "正在同步云端数据用于校验...", Toast.LENGTH_SHORT).show()
        Thread {
            val items = syncManager.fetchCloudItems(c.id)
            runOnUi {
                if (items == null) {
                    Toast.makeText(context, "❌ 找回密码需联网，请检查网络", Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }
                if (items.isEmpty()) {
                    Toast.makeText(context, "合集内无视频，无法通过内容校验找回密码", Toast.LENGTH_LONG).show()
                    return@runOnUi
                }
                promptKeywordVerify(c, items, onSuccess)
            }
        }.start()
    }

    private fun promptKeywordVerify(
        c: GiteeSyncManager.CloudCollection,
        items: List<FavoritesStore.FavoriteItem>,
        onSuccess: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        val header = view.findViewById<TextView>(R.id.pwdDialogHeader)
        val subtitle = view.findViewById<TextView>(R.id.pwdDialogSubtitle)
        val input = view.findViewById<EditText>(R.id.pwdInputField)
        val errorTip = view.findViewById<TextView>(R.id.pwdErrorTip)
        val btnForgot = view.findViewById<TextView>(R.id.btnForgotPwd)
        val btnCancel = view.findViewById<TextView>(R.id.btnPwdCancel)
        val btnOk = view.findViewById<TextView>(R.id.btnPwdOk)

        header.text = "🔎 找回密码"
        subtitle.text = "请输入您记得的「${c.name}」合集内某个视频标题关键词，用于身份验证。"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        input.hint = "输入视频标题关键词"
        btnForgot.visibility = View.GONE

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(view).create()
        btnOk.setOnClickListener {
            val kw = input.text?.toString()?.trim().orEmpty()
            if (kw.isEmpty()) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "请输入关键词"
                return@setOnClickListener
            }
            val hit = items.any { it.title.contains(kw, ignoreCase = true) }
            if (!hit) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "未找到匹配视频，请换个关键词重试"
                input.selectAll()
                return@setOnClickListener
            }
            dialog.dismiss()
            // 关键词通过 → 让用户设置新密码
            promptResetNewPassword(c, onSuccess)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        input.requestFocus()
    }

    private fun promptResetNewPassword(
        c: GiteeSyncManager.CloudCollection,
        onSuccess: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        val header = view.findViewById<TextView>(R.id.pwdDialogHeader)
        val subtitle = view.findViewById<TextView>(R.id.pwdDialogSubtitle)
        val input1 = view.findViewById<EditText>(R.id.pwdInputField)
        val input2 = view.findViewById<EditText>(R.id.pwdInputField2)
        val errorTip = view.findViewById<TextView>(R.id.pwdErrorTip)
        val btnForgot = view.findViewById<TextView>(R.id.btnForgotPwd)
        val btnCancel = view.findViewById<TextView>(R.id.btnPwdCancel)
        val btnOk = view.findViewById<TextView>(R.id.btnPwdOk)

        header.text = "🔐 设置新密码"
        subtitle.text = "校验通过，请为「${c.name}」设置新密码（需要输入两次）。"
        input1.hint = "请输入新密码"
        input2.visibility = View.VISIBLE
        btnForgot.visibility = View.GONE

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(view).create()
        btnOk.setOnClickListener {
            val a = input1.text?.toString().orEmpty()
            val b = input2.text?.toString().orEmpty()
            if (a.isEmpty() || b.isEmpty()) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "请输入两次密码"
                return@setOnClickListener
            }
            if (a != b) {
                errorTip.visibility = View.VISIBLE
                errorTip.text = "两次密码不一致，请重新输入"
                return@setOnClickListener
            }
            val newHash = PasswordUtil.sha256(a)
            dialog.dismiss()
            Toast.makeText(context, "正在更新云端密码...", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = syncManager.updatePasswordHash(c.id, newHash)
                runOnUi {
                    if (ok) {
                        Toast.makeText(context, "✅ 密码已重置，正在使用新密码下载", Toast.LENGTH_SHORT).show()
                        // 清空冷却，允许后续正常输入
                        passwordAttempts[c.id] = AttemptState()
                        onSuccess(newHash)
                    } else {
                        Toast.makeText(context, "❌ 更新云端密码失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        input1.requestFocus()
    }

    // ------------------------------------------------------------------
    // 通用列表选择弹窗
    // ------------------------------------------------------------------

    private data class LockState(
        var locked: Boolean,
        val encrypted: Boolean,
        val uploadCollection: FavoritesStore.FavoriteCollection? = null
    )

    private data class ListItem(
        val id: String,
        val label: String,
        val checkedByDefault: Boolean,
        val enabled: Boolean = true,
        val isHeader: Boolean = false,
        val lockState: LockState? = null,
        val isPresetDownload: Boolean = false,
        // 下载：非预置加密合集，勾选前需先输入一次密码解锁（解锁后本次弹窗内所有有锁合集均可勾选）。
        val downloadLocked: Boolean = false,
        // 上传：无权限合集不可勾选时的提示文案。
        val blockedToast: String? = null,
        // 上传：当前 type 只作为本次上传覆盖值，不直接改写本地 FavoritesStore。
        val currentType: String? = null,
        // 上传：仅管理员或合集创建者可切换非预置合集 type。
        val canToggleType: Boolean = false,
        // 上传：仅管理员或合集创建者可切换非预置合集加密状态。
        val canToggleLock: Boolean = true,
        // 上传：仅管理员或合集创建者展示卡片菜单入口。
        val canShowMenu: Boolean = false,
        val name: String = label,
        val itemCount: Int = 0
    ) {
        companion object {
            fun header(label: String) = ListItem("", label, false, enabled = false, isHeader = true)
        }
    }

    private data class SelectionRow(
        val checkBox: CheckBox,
        val card: FrameLayout,
        val id: String,
        val initiallyEnabled: Boolean,
        val isPresetDownload: Boolean,
        // 下载：加密未解锁状态；本次弹窗内一次验证通过后统一置为 false。
        var downloadLocked: Boolean = false,
        // 上传：无权限合集不可勾选时的提示文案。
        val blockedToast: String? = null,
        // 该卡片中心锁图标（下载/上传均可能存在），解锁后统一切换为开锁图标。
        var lockView: ImageView? = null
    )

    private fun showCollectionListDialog(
        title: String,
        hint: String,
        items: List<ListItem>,
        maxSelect: Int,
        showTypeToggle: Boolean = false,
        cloudIndex: List<GiteeSyncManager.CloudCollection> = emptyList(),
        uploadPasswordActions: MutableMap<String, GiteeSyncManager.PasswordAction>? = null,
        uploadTypeOverrides: MutableMap<String, String>? = null,
        enforceDownloadLimits: Boolean = false,
        initialMaxNonPresetDownload: Int = DEFAULT_MAX_NON_PRESET_DOWNLOAD,
        onConfirm: (List<String>) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val warm = ContextCompat.getColor(context, R.color.crayon_yellow)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_sync_list, null)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(view)
            .create()

        val titleView = view.findViewById<TextView>(R.id.listTitle)
        val hintView = view.findViewById<TextView>(R.id.listHint)
        titleView.text = title
        hintView.text = hint

        val container = view.findViewById<LinearLayout>(R.id.listContainer)
        val limitTip = view.findViewById<TextView>(R.id.listLimitTip)
        val selectAllBtn = view.findViewById<TextView>(R.id.btnSelectAll)
        val btnCancel = view.findViewById<TextView>(R.id.btnCancel)
        val btnConfirm = view.findViewById<TextView>(R.id.btnConfirm)
        listOf(selectAllBtn, btnCancel, btnConfirm).forEach { styleDialogChoiceButton(it) }

        container.removeAllViews()
        container.clipChildren = false
        container.clipToPadding = false
        val gridColumns = 5
        val columnGap = dp(8)
        val grid = GridLayout(context).apply {
            columnCount = gridColumns
            rowCount = ((items.count { !it.isHeader } + gridColumns - 1) / gridColumns).coerceAtLeast(1)
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        container.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val checkBoxes = mutableListOf<SelectionRow>()
        val focusCards = mutableListOf<FrameLayout>()
        val cardMenuHandlers = mutableMapOf<FrameLayout, () -> Boolean>()
        var listDialogCleaned = false
        var currentMaxNonPresetDownload = initialMaxNonPresetDownload.coerceAtLeast(0)
        fun maxNonPresetDownload(): Int = currentMaxNonPresetDownload
        fun maxTotalDownload(): Int = PRESET_DOWNLOAD_COUNT + currentMaxNonPresetDownload

        fun selectedTotalCount(): Int = checkBoxes.count { it.checkBox.isChecked }
        fun selectedNonPresetCount(): Int = checkBoxes.count { it.checkBox.isChecked && !it.isPresetDownload }
        fun currentDownloadHint(): String = "预置合集默认已选；可见非预置合集默认未选；需要解锁的加密合集需先输入密码。总下载数最多 ${maxTotalDownload()} 个，非预置最多 ${maxNonPresetDownload()} 个"

        fun refreshCardVisual(row: SelectionRow) {
            val available = row.blockedToast == null && !row.downloadLocked
            val focused = row.card.hasFocus()
            row.card.alpha = if (row.blockedToast == null) 1f else 0.48f
            row.card.isSelected = row.checkBox.isChecked
            row.card.findViewWithTag<View>("checkBadge")?.visibility = if (row.checkBox.isChecked) View.VISIBLE else View.INVISIBLE
            row.card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(
                    when {
                        focused -> Color.TRANSPARENT
                        available -> Color.parseColor("#12FFFFFF")
                        else -> Color.argb(18, 255, 255, 255)
                    }
                )
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(120, 210, 214, 222))
            }
        }

        fun refreshAllCards() = checkBoxes.forEach { refreshCardVisual(it) }

        fun trimSelectionToLimits() {
            if (!enforceDownloadLimits) return
            var total = 0
            var nonPreset = 0
            val totalLimit = maxTotalDownload()
            val nonPresetLimit = maxNonPresetDownload()
            checkBoxes.forEach { row ->
                if (row.checkBox.isChecked) {
                    val overTotal = total >= totalLimit
                    val overNonPreset = !row.isPresetDownload && nonPreset >= nonPresetLimit
                    if (overTotal || overNonPreset) {
                        row.checkBox.isChecked = false
                    } else {
                        total++
                        if (!row.isPresetDownload) nonPreset++
                    }
                }
            }
        }

        fun selectableRows(): List<SelectionRow> =
            checkBoxes.filter { it.checkBox.isEnabled && it.blockedToast == null && !it.downloadLocked }

        fun updateSelectAllLabel() {
            selectAllBtn.text = if (selectedTotalCount() > 0) "取消全选" else "全选"
        }

        fun refreshLimitTips() {
            if (enforceDownloadLimits) {
                limitTip.visibility = View.VISIBLE
                hintView.text = currentDownloadHint()
                limitTip.text = "已选 ${selectedTotalCount()} / 最多 ${maxTotalDownload()} 个；非预置已选 ${selectedNonPresetCount()} / 最多 ${maxNonPresetDownload()} 个"
            } else {
                limitTip.visibility = View.GONE
            }
            updateSelectAllLabel()
            refreshAllCards()
        }

        fun canSelect(row: SelectionRow): Boolean {
            if (!enforceDownloadLimits) return true
            val overTotal = selectedTotalCount() >= maxTotalDownload()
            val overNonPreset = !row.isPresetDownload && selectedNonPresetCount() >= maxNonPresetDownload()
            if (overTotal || overNonPreset) {
                Toast.makeText(context, "已达下载上限（${selectedTotalCount()}/${maxTotalDownload()}）", Toast.LENGTH_SHORT).show()
                return false
            }
            return true
        }

        fun shake(viewToShake: View) {
            val d = 6f * density
            viewToShake.animate().translationX(d)
                .setInterpolator(android.view.animation.CycleInterpolator(2f))
                .setDuration(200)
                .withEndAction { viewToShake.translationX = 0f }
                .start()
        }

        fun cancelPendingViewWork(root: View) {
            root.animate().cancel()
            root.clearAnimation()
            root.translationX = 0f
            root.translationZ = 0f
            root.scaleX = 1f
            root.scaleY = 1f
            root.setOnFocusChangeListener(null)
            root.setOnKeyListener(null)
            root.setOnClickListener(null)
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    cancelPendingViewWork(root.getChildAt(i))
                }
            }
        }

        fun dismissListDialog() {
            if (listDialogCleaned) return
            listDialogCleaned = true
            val focused = dialog.currentFocus
            focused?.clearFocus()
            focusCards.forEach { cancelPendingViewWork(it) }
            checkBoxes.clear()
            focusCards.clear()
            cardMenuHandlers.clear()
            grid.removeAllViews()
            container.removeAllViews()
            dialog.dismiss()
        }

        fun applyGridCardWidths() {
            val measuredGridWidth = grid.width.takeIf { it > 0 }
                ?: container.width.takeIf { it > 0 }
                ?: dialog.window?.decorView?.width?.takeIf { it > 0 }
                ?: dp(900)
            val availableWidth = (measuredGridWidth - grid.paddingLeft - grid.paddingRight).coerceAtLeast(dp(320))
            val cardWidth = ((availableWidth - dp(12) - columnGap * (gridColumns - 1)) / gridColumns).coerceAtLeast(dp(96))
            focusCards.forEachIndexed { index, card ->
                val col = index % gridColumns
                val lp = (card.layoutParams as? GridLayout.LayoutParams) ?: GridLayout.LayoutParams()
                lp.width = cardWidth
                lp.height = dp(116)
                lp.setMargins(0, dp(6), if (col == gridColumns - 1) 0 else columnGap, dp(6))
                card.layoutParams = lp
            }
            grid.requestLayout()
        }

        fun unlockDownloadRow(row: SelectionRow) {
            row.downloadLocked = false
            row.checkBox.isEnabled = true
            row.lockView?.visibility = View.GONE
            refreshLimitTips()
        }

        fun promptDownloadUnlock(cloud: GiteeSyncManager.CloudCollection, autoCheckRow: SelectionRow?) {
            val attemptState = passwordAttempts.getOrPut(cloud.id) { AttemptState() }
            val remainMs = attemptState.cooldownUntilMs - System.currentTimeMillis()
            if (remainMs > 0) {
                Toast.makeText(context, "密码错误次数过多，请 ${(remainMs / 1000) + 1}s 后重试", Toast.LENGTH_SHORT).show()
                return
            }
            fun afterUnlock() {
                passwordAttempts[cloud.id] = AttemptState()
                autoCheckRow?.let { row ->
                    unlockDownloadRow(row)
                    if (canSelect(row)) row.checkBox.isChecked = true
                    refreshLimitTips()
                }
            }
            promptDownloadPassword(cloud,
                onOk = { afterUnlock() },
                onCancel = {},
                onForgot = { startForgotPasswordFlow(cloud) { afterUnlock() } }
            )
        }

        fun toggleRow(row: SelectionRow) {
            if (row.blockedToast != null) {
                row.checkBox.isChecked = false
                Toast.makeText(context, row.blockedToast, Toast.LENGTH_SHORT).show()
                refreshLimitTips()
                return
            }
            if (row.downloadLocked) {
                row.checkBox.isChecked = false
                val cloud = cloudIndex.firstOrNull { it.id == row.id }
                if (cloud != null) promptDownloadUnlock(cloud, row)
                refreshLimitTips()
                return
            }
            if (row.checkBox.isChecked) {
                row.checkBox.isChecked = false
            } else if (canSelect(row)) {
                row.checkBox.isChecked = true
            }
            refreshLimitTips()
        }

        fun lockCircleBackground(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(150, 8, 10, 14))
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }

        fun showCollectionCardMenu(titleText: String, actions: List<Pair<String, () -> Unit>>) {
            if (actions.isEmpty()) {
                Toast.makeText(context, "当前合集暂无可用菜单操作", Toast.LENGTH_SHORT).show()
                return
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_crayon_panel)
                setPadding(dp(28), dp(24), dp(28), dp(28))
            }
            root.addView(TextView(context).apply {
                text = titleText
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#101217"))
                background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_crayon_header)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val menuDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
                .setView(root)
                .create()
            actions.forEachIndexed { index, action ->
                val button = TextView(context).apply {
                    text = action.first
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                }
                styleDialogChoiceButton(button)
                button.setOnClickListener {
                    menuDialog.dismiss()
                    action.second.invoke()
                }
                root.addView(button, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) dp(18) else dp(10) })
            }
            val cancel = TextView(context).apply {
                text = "取消"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }
            styleDialogChoiceButton(cancel)
            cancel.setOnClickListener { menuDialog.dismiss() }
            root.addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
            menuDialog.setOnShowListener {
                menuDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                (root.getChildAt(1) ?: cancel).requestFocus()
            }
            menuDialog.show()
        }

        fun buildCard(item: ListItem): SelectionRow {
            val cb = CheckBox(context).apply {
                isChecked = item.checkedByDefault
                isEnabled = item.enabled
            }
            val card = FrameLayout(context).apply {
                isFocusable = item.enabled
                isFocusableInTouchMode = false
                isClickable = item.enabled
                clipChildren = false
                clipToPadding = false
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val cardLp = GridLayout.LayoutParams().apply {
                width = dp(1)
                height = dp(116)
                setMargins(0, dp(6), columnGap, dp(6))
            }
            card.layoutParams = cardLp

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            card.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val iconBox = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
            content.addView(iconBox, LinearLayout.LayoutParams(dp(60), dp(60)))
            iconBox.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_collection)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))

            val topActionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            card.addView(topActionRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.LEFT
            ).apply {
                topMargin = dp(2)
                leftMargin = dp(6)
                rightMargin = 0
            })

            var currentType = item.currentType ?: FavoritesStore.TYPE_SHARED
            var typeIcon: ImageView? = null
            fun toggleTypeFromCard() {
                if (!(item.canToggleType || showTypeToggle) || uploadTypeOverrides == null) {
                    Toast.makeText(context, "仅管理员或合集创建者可切换类型", Toast.LENGTH_SHORT).show()
                    return
                }
                currentType = if (currentType == FavoritesStore.TYPE_SHARED) FavoritesStore.TYPE_PRIVATE else FavoritesStore.TYPE_SHARED
                uploadTypeOverrides[item.id] = currentType
                typeIcon?.setImageResource(if (currentType == FavoritesStore.TYPE_PRIVATE) R.drawable.ic_type_private else R.drawable.ic_type_shared)
                Toast.makeText(context, "本次上传将切换为 $currentType", Toast.LENGTH_SHORT).show()
            }

            if (!item.isPresetDownload && uploadTypeOverrides != null) {
                typeIcon = ImageView(context).apply {
                    setImageResource(if (currentType == FavoritesStore.TYPE_PRIVATE) R.drawable.ic_type_private else R.drawable.ic_type_shared)
                    background = lockCircleBackground()
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    alpha = if (item.canToggleType || showTypeToggle) 1f else 0.55f
                    isClickable = item.canToggleType || showTypeToggle
                    isFocusable = false
                    setOnClickListener { toggleTypeFromCard() }
                }
            }

            val row = SelectionRow(
                checkBox = cb,
                card = card,
                id = item.id,
                initiallyEnabled = item.enabled,
                isPresetDownload = item.isPresetDownload,
                downloadLocked = item.downloadLocked,
                blockedToast = item.blockedToast
            )

            var lockViewRef: ImageView? = null
            fun toggleLockFromCard() {
                val state = item.lockState ?: return
                if (state.uploadCollection != null) {
                    if (!item.canToggleLock) {
                        Toast.makeText(context, "仅管理员或合集创建者可切换加密状态", Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (state.locked) {
                        state.locked = false
                        lockViewRef?.visibility = View.GONE
                        uploadPasswordActions?.set(item.id, GiteeSyncManager.PasswordAction.Clear)
                        Toast.makeText(context, "已取消「${state.uploadCollection.name}」加密", Toast.LENGTH_SHORT).show()
                    } else {
                        inputNewPasswordTwice(state.uploadCollection, "设置密码") { newHash ->
                            state.locked = true
                            lockViewRef?.setImageResource(R.drawable.ic_lock_closed)
                            lockViewRef?.visibility = View.VISIBLE
                            uploadPasswordActions?.set(item.id, GiteeSyncManager.PasswordAction.Set(newHash))
                            Toast.makeText(context, "已为「${state.uploadCollection.name}」设置密码", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (state.encrypted && row.downloadLocked) {
                    val cloud = cloudIndex.firstOrNull { it.id == item.id } ?: return
                    promptDownloadUnlock(cloud, row)
                }
            }

            if (!item.isPresetDownload && item.lockState != null) {
                val lockView = ImageView(context).apply {
                    setImageResource(R.drawable.ic_lock_closed)
                    background = lockCircleBackground()
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    alpha = 1f
                    visibility = if (item.lockState.locked || row.downloadLocked) View.VISIBLE else View.GONE
                    isClickable = false
                    isFocusable = false
                    contentDescription = "合集已加锁"
                }
                lockViewRef = lockView
                row.lockView = lockView
                iconBox.addView(lockView, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            }

            fun openCardMenu() {
                if (item.isPresetDownload) return
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                item.lockState?.let { state ->
                    if (state.uploadCollection != null && item.canToggleLock) {
                        actions.add(Pair(if (state.locked) "切换为解锁" else "切换为加锁", { toggleLockFromCard() }))
                    } else if (state.encrypted && row.downloadLocked) {
                        actions.add(Pair("输入密码解锁", { toggleLockFromCard() }))
                    } else {
                        Unit
                    }
                }
                if ((item.canToggleType || showTypeToggle) && uploadTypeOverrides != null) {
                    actions.add(Pair(if (currentType == FavoritesStore.TYPE_SHARED) "切换为私有" else "切换为共享", { toggleTypeFromCard() }))
                }
                showCollectionCardMenu("${item.name.ifBlank { item.label }} 操作", actions)
            }

            cardMenuHandlers[card] = {
                if (item.isPresetDownload || uploadTypeOverrides == null || !item.canShowMenu) {
                    false
                } else {
                    openCardMenu()
                    true
                }
            }

            if (!item.isPresetDownload && uploadTypeOverrides != null && item.canShowMenu) {
                val menuIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_collection_menu)
                    background = lockCircleBackground()
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    alpha = 0.9f
                    isClickable = true
                    isFocusable = false
                    contentDescription = "合集菜单"
                    setOnClickListener { openCardMenu() }
                }
                topActionRow.addView(menuIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
            }
            typeIcon?.let { icon ->
                topActionRow.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    leftMargin = dp(4)
                })
            }

            val nameView = TextView(context).apply {
                text = item.name.ifBlank { item.label }
                setTextColor(textPrimary)
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            content.addView(nameView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
            content.addView(TextView(context).apply {
                text = "(${item.itemCount}条)"
                setTextColor(textSecondary)
                textSize = 10f
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) })

            val checkBadge = TextView(context).apply {
                tag = "checkBadge"
                text = "✓"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#101217"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(warm)
                }
            }
            card.addView(checkBadge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.TOP).apply {
                topMargin = dp(6)
                rightMargin = dp(6)
            })

            fun refreshBadge() {
                checkBadge.visibility = if (cb.isChecked) View.VISIBLE else View.INVISIBLE
            }
            refreshBadge()
            card.setOnFocusChangeListener { v, hasFocus ->
                refreshCardVisual(row)
                com.bd.casttv.ui.framework.FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 18)
            }
            card.setOnClickListener {
                toggleRow(row)
                refreshBadge()
            }
            card.setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val index = focusCards.indexOf(card)
                if (index < 0) return@setOnKeyListener false
                val col = index % gridColumns
                when (keyCode) {
                    KeyEvent.KEYCODE_MENU -> {
                        if (event.repeatCount == 0) cardMenuHandlers[card]?.invoke() == true else true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        toggleRow(row)
                        refreshBadge()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (col == 0) { shake(v); true } else { focusCards.getOrNull(index - 1)?.requestFocus(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (col == gridColumns - 1 || index + 1 >= focusCards.size) { shake(v); true } else { focusCards.getOrNull(index + 1)?.requestFocus(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (index < gridColumns) { shake(v); true } else { focusCards.getOrNull(index - gridColumns)?.requestFocus(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (index + gridColumns < focusCards.size) { focusCards[index + gridColumns].requestFocus(); true } else false
                    }
                    else -> false
                }
            }
            return row
        }

        items.filterNot { it.isHeader }.forEach { item ->
            val row = buildCard(item)
            grid.addView(row.card)
            checkBoxes.add(row)
            focusCards.add(row.card)
            refreshCardVisual(row)
        }
        refreshLimitTips()

        fun focusedCardForMenu(): FrameLayout? {
            var v: View? = dialog.currentFocus
            while (v != null) {
                if (v is FrameLayout && cardMenuHandlers.containsKey(v)) return v
                v = (v.parent as? View)
            }
            return null
        }

        dialog.setOnKeyListener(DialogInterface.OnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_MENU) return@OnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener true
            if (event.repeatCount != 0) return@OnKeyListener true
            val focusedCard = focusedCardForMenu() ?: return@OnKeyListener false
            cardMenuHandlers[focusedCard]?.invoke() == true
        })

        selectAllBtn.setOnClickListener {
            if (selectedTotalCount() > 0) {
                checkBoxes.forEach { it.checkBox.isChecked = false }
            } else {
                selectableRows().forEach { it.checkBox.isChecked = true }
                trimSelectionToLimits()
            }
            refreshLimitTips()
        }
        btnCancel.setOnClickListener { dismissListDialog() }
        btnConfirm.setOnClickListener {
            val selected = checkBoxes.filter { it.checkBox.isChecked }.map { it.id }
            val selectedTotal = checkBoxes.count { it.checkBox.isChecked }
            val selectedNonPreset = checkBoxes.count { it.checkBox.isChecked && !it.isPresetDownload }
            if (selected.isEmpty()) {
                Toast.makeText(context, "请至少选择一个合集", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (enforceDownloadLimits && selectedTotal > maxTotalDownload()) {
                Toast.makeText(context, "最多可下载 ${maxTotalDownload()} 个合集", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (enforceDownloadLimits && selectedNonPreset > maxNonPresetDownload()) {
                Toast.makeText(context, "非预置合集最多选 ${maxNonPresetDownload()} 个", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!enforceDownloadLimits && selectedTotal > maxSelect) {
                Toast.makeText(context, "最多选择 $maxSelect 个合集", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dismissListDialog()
            onConfirm(selected)
        }

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                val targetWidth = (context.resources.displayMetrics.widthPixels * 0.95f).toInt()
                window.setLayout(targetWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            }
            grid.post {
                applyGridCardWidths()
                focusCards.firstOrNull()?.requestFocus() ?: btnConfirm.requestFocus()
            }
        }
        dialog.setOnDismissListener {
            dismissListDialog()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun runOnUi(block: () -> Unit) {
        (context as? android.app.Activity)?.runOnUiThread(block) ?: block()
    }
}
