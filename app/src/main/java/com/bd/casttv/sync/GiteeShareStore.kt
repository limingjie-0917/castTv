package com.bd.casttv.sync

import android.content.Context
import com.bd.casttv.util.CreatorIdProvider
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebParseAdapterStore
import com.bd.casttv.webparse.WebParseStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * 云端共享数据管理：适配器配置与解析记录分开存储到 Gitee 仓库。
 * - 适配器路径：shared_data/adapters/{globalAdapterId}.json
 * - 记录路径：shared_data/records/{globalRecordId}.json
 * - 索引路径：shared_data/adapters/_index.json 和 shared_data/records/_index.json
 */
object GiteeShareStore {

    private const val TAG = "GiteeShareStore"
    private const val ADAPTERS_DIR = "shared_data/adapters"
    private const val RECORDS_DIR = "shared_data/records"
    private const val CARTOONS_DIR = "shared_data/cartoons"
    private const val ADAPTERS_INDEX = "$ADAPTERS_DIR/_index.json"
    private const val RECORDS_INDEX = "$RECORDS_DIR/_index.json"
    private const val CARTOONS_INDEX = "$CARTOONS_DIR/_index.json"

    // 内置适配器 ID，不上传
    private val BUILT_IN_ADAPTERS = setOf("maccms", "zyplayer", "nemo", "generic", "snailcms", "snail_cms")

    data class SharedAdapter(
        val globalAdapterId: String,
        val localAdapterId: String,
        val name: String,
        val host: String,
        val pageKind: String,
        val frameworkType: String,
        val creatorId: String,
        val uploadedAt: Long
    )

    data class SharedRecord(
        val globalRecordId: String,
        val title: String,
        val url: String,
        val pageType: String,
        val siteTitle: String,
        val frameworkType: String,
        val adapterKind: String,
        val globalAdapterId: String?,
        val localAdapterId: String?,
        val adapterName: String,
        val creatorId: String,
        val deviceName: String,
        val uploadedAt: Long
    )

    /** 动画城卡片：一条指向详情页 + 适配器的云端记录。 */
    data class SharedCartoon(
        val cartoonId: String,
        val title: String,
        val detailUrl: String,
        val cover: String,
        val globalAdapterId: String?,
        val adapterName: String,
        val episodeCount: Int,
        val creatorId: String,
        val deviceName: String,
        val uploadedAt: Long,
        /** 剧情/简介文案：新增字段，历史记录缺失时为空字符串。 */
        val description: String = ""
    )

    data class CloudIndex(
        val adapters: List<SharedAdapter>,
        val records: List<SharedRecord>
    )

    /** 生成全局唯一适配器 ID：creatorId + host + pageKind 的 SHA-256 前 12 位 */
    fun generateGlobalAdapterId(creatorId: String, host: String, pageKind: String): String {
        val raw = "$creatorId|$host|$pageKind"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) append('0')
                append(Integer.toHexString(v))
            }
        }.take(12)
    }

    /** 生成动画城卡片 ID：detailUrl 的 SHA-256 前 12 位（同 URL = 同卡片，upsert 幂等） */
    fun generateCartoonId(detailUrl: String): String {
        val raw = detailUrl.trim()
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) append('0')
                append(Integer.toHexString(v))
            }
        }.take(12)
    }

    /** 判断是否为内置适配器（不用上传） */
    fun isBuiltInAdapter(adapterId: String): Boolean {
        return BUILT_IN_ADAPTERS.contains(adapterId.trim().lowercase())
    }

    // ===================== 拉取云端索引 =====================

    /** 拉取云端索引，用于本地 diff */
    fun fetchCloudIndex(): GiteeApi.ApiResult<CloudIndex> {
        val adapters = fetchAdaptersIndex()
        val records = fetchRecordsIndex()
        return when {
            adapters is GiteeApi.ApiResult.Error -> adapters
            records is GiteeApi.ApiResult.Error -> records
            else -> GiteeApi.ApiResult.Success(CloudIndex(
                adapters = (adapters as? GiteeApi.ApiResult.Success)?.value ?: emptyList(),
                records = (records as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
            ))
        }
    }

    private fun fetchAdaptersIndex(): GiteeApi.ApiResult<List<SharedAdapter>> {
        return when (val result = GiteeApi.getFileResult(ADAPTERS_INDEX)) {
            is GiteeApi.ApiResult.Success -> {
                val list = parseAdaptersIndex(result.value.content)
                GiteeApi.ApiResult.Success(list)
            }
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.Success(emptyList())
            is GiteeApi.ApiResult.Error -> result
        }
    }

    fun fetchRecordsIndex(): GiteeApi.ApiResult<List<SharedRecord>> {
        return when (val result = GiteeApi.getFileResult(RECORDS_INDEX)) {
            is GiteeApi.ApiResult.Success -> {
                val list = parseRecordsIndex(result.value.content)
                GiteeApi.ApiResult.Success(list)
            }
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.Success(emptyList())
            is GiteeApi.ApiResult.Error -> result
        }
    }

    private fun parseAdaptersIndex(content: String): List<SharedAdapter> {
        return runCatching {
            val array = JSONArray(content)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(SharedAdapter(
                        globalAdapterId = obj.optString("globalAdapterId"),
                        localAdapterId = obj.optString("localAdapterId"),
                        name = obj.optString("name"),
                        host = obj.optString("host"),
                        pageKind = obj.optString("pageKind"),
                        frameworkType = obj.optString("frameworkType"),
                        creatorId = obj.optString("creatorId"),
                        uploadedAt = obj.optLong("uploadedAt")
                    ))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun parseRecordsIndex(content: String): List<SharedRecord> {
        return runCatching {
            val array = JSONArray(content)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(SharedRecord(
                        globalRecordId = obj.optString("globalRecordId"),
                        title = obj.optString("title"),
                        url = obj.optString("url"),
                        pageType = obj.optString("pageType"),
                        siteTitle = obj.optString("siteTitle"),
                        frameworkType = obj.optString("frameworkType"),
                        adapterKind = obj.optString("adapterKind"),
                        globalAdapterId = obj.optString("globalAdapterId").ifBlank { null },
                        localAdapterId = obj.optString("localAdapterId").ifBlank { null },
                        adapterName = obj.optString("adapterName"),
                        creatorId = obj.optString("creatorId"),
                        deviceName = obj.optString("deviceName"),
                        uploadedAt = obj.optLong("uploadedAt")
                    ))
                }
            }
        }.getOrElse { emptyList() }
    }

    // ===================== 上传 =====================

    data class UploadItem(
        val history: WebParseStore.ParseHistory,
        val isSelected: Boolean
    )

    data class UploadResult(
        val successCount: Int,
        val failCount: Int,
        val adaptersUploaded: Int,
        val errors: List<String>
    )

    /**
     * 上传选中的解析记录及其关联的自定义适配器到云端。
     * - 内置适配器跳过，仅上传 adapterKind=CUSTOM_JSON 的适配器
     * - 适配器与记录分开存储
     */
    fun uploadShared(
        context: Context,
        items: List<UploadItem>
    ): GiteeApi.ApiResult<UploadResult> {
        val creatorId = CreatorIdProvider.get(context)
        val deviceName = com.bd.casttv.settings.Settings(context).deviceName
        val selected = items.filter { it.isSelected }
        if (selected.isEmpty()) return GiteeApi.ApiResult.Error("没有选中任何记录")

        val errors = mutableListOf<String>()
        val adapterStore = WebParseAdapterStore(context)
        val now = System.currentTimeMillis()

        // Step1: 提取并去重自定义适配器
        val customAdapters = mutableMapOf<String, Pair<String, WebParseAdapterStore.DomainBinding?>>()
        selected.forEach { item ->
            val h = item.history
            if (h.adapterId.isBlank() || isBuiltInAdapter(h.adapterId)) return@forEach
            if (customAdapters.containsKey(h.adapterId)) return@forEach
            // 查找本地域名绑定获取 ruleFileName
            val host = adapterStore.normalizeHost(h.url)
            val pageKind = if (h.pageType.equals("list", true)) 
                com.bd.casttv.webparse.ParsePageKind.LIST 
            else 
                com.bd.casttv.webparse.ParsePageKind.DETAIL
            val binding = adapterStore.getBinding(pageKind, host)
            customAdapters[h.adapterId] = h.adapterId to binding
        }

        // Step2: 上传适配器
        var adaptersUploaded = 0
        val adapterIdMap = mutableMapOf<String, String>() // localId -> globalId
        for ((localId, pair) in customAdapters) {
            val (_, binding) = pair
            if (binding == null || binding.ruleFileName.isBlank()) {
                errors.add("适配器 $localId 无规则文件，跳过")
                adapterIdMap[localId] = ""
                continue
            }
            val ruleText = RuleBasedAdapter.readRuleText(context, binding.ruleFileName)
            if (ruleText.isBlank()) {
                errors.add("适配器 $localId 规则文件为空，跳过")
                adapterIdMap[localId] = ""
                continue
            }
            val globalId = generateGlobalAdapterId(creatorId, binding.host, binding.pageKind.name)
            val adapterJson = buildAdapterJson(globalId, localId, binding, ruleText, creatorId, now)
            val path = "$ADAPTERS_DIR/$globalId.json"
            when (val r = GiteeApi.putFileResult(path, adapterJson, null, "share adapter $globalId")) {
                is GiteeApi.ApiResult.Success -> { adaptersUploaded++; adapterIdMap[localId] = globalId }
                is GiteeApi.ApiResult.Error -> { errors.add("适配器上传失败: ${r.message}"); adapterIdMap[localId] = "" }
                is GiteeApi.ApiResult.NotFound -> { errors.add("适配器上传失败: NotFound"); adapterIdMap[localId] = "" }
            }
        }

        // Step3: 上传记录
        var successCount = 0
        var failCount = 0
        for (item in selected) {
            val h = item.history
            val globalRecordId = UUID.randomUUID().toString()
            val isBuiltIn = h.adapterId.isBlank() || isBuiltInAdapter(h.adapterId)
            val globalAdapterId = if (isBuiltIn) null else adapterIdMap[h.adapterId].orEmpty().ifBlank { null }
            val recordJson = buildRecordJson(globalRecordId, h, globalAdapterId, creatorId, deviceName, now)
            val path = "$RECORDS_DIR/$globalRecordId.json"
            when (val r = GiteeApi.putFileResult(path, recordJson, null, "share record $globalRecordId")) {
                is GiteeApi.ApiResult.Success -> successCount++
                is GiteeApi.ApiResult.Error -> { failCount++; errors.add("记录上传失败: ${r.message}") }
                is GiteeApi.ApiResult.NotFound -> { failCount++; errors.add("记录上传失败: NotFound") }
            }
        }

        // Step4: 更新索引
        updateAdaptersIndex(creatorId, customAdapters, adapterIdMap, now)
        updateRecordsIndex(creatorId, deviceName, selected, adapterIdMap, now)

        return GiteeApi.ApiResult.Success(UploadResult(successCount, failCount, adaptersUploaded, errors))
    }

    private fun buildAdapterJson(
        globalId: String, localId: String,
        binding: WebParseAdapterStore.DomainBinding,
        ruleText: String, creatorId: String, now: Long
    ): String {
        val meta = JSONObject().apply {
            put("globalAdapterId", globalId)
            put("localAdapterId", localId)
            put("name", binding.adapterName)
            put("host", binding.host)
            put("pageKind", binding.pageKind.name)
            put("frameworkType", binding.frameworkType.name)
            put("creatorId", creatorId)
            put("uploadedAt", now)
        }
        val obj = JSONObject().apply {
            put("meta", meta)
            put("rule", JSONObject(ruleText))
        }
        return obj.toString(2)
    }

    private fun buildRecordJson(
        globalRecordId: String,
        h: WebParseStore.ParseHistory,
        globalAdapterId: String?,
        creatorId: String,
        deviceName: String,
        now: Long
    ): String {
        val obj = JSONObject().apply {
            put("globalRecordId", globalRecordId)
            put("title", h.title)
            put("url", h.url)
            put("pageType", h.pageType)
            put("siteTitle", h.siteTitle)
            put("frameworkType", h.frameworkType)
            put("adapterKind", if (globalAdapterId != null) "CUSTOM_JSON" else "BUILT_IN")
            put("globalAdapterId", globalAdapterId ?: JSONObject.NULL)
            put("localAdapterId", h.adapterId)
            put("adapterName", h.adapterName)
            put("creatorId", creatorId)
            put("deviceName", deviceName)
            put("uploadedAt", now)
        }
        return obj.toString(2)
    }

    private fun updateAdaptersIndex(
        creatorId: String,
        customAdapters: Map<String, Pair<String, WebParseAdapterStore.DomainBinding?>>,
        adapterIdMap: Map<String, String>,
        now: Long
    ) {
        // 拉取现有索引
        val existing = (fetchAdaptersIndex() as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
        val merged = existing.associateBy { it.globalAdapterId }.toMutableMap()
        for ((localId, pair) in customAdapters) {
            val (_, binding) = pair
            val globalId = adapterIdMap[localId].orEmpty()
            if (globalId.isBlank() || binding == null) continue
            merged[globalId] = SharedAdapter(
                globalAdapterId = globalId,
                localAdapterId = localId,
                name = binding.adapterName,
                host = binding.host,
                pageKind = binding.pageKind.name,
                frameworkType = binding.frameworkType.name,
                creatorId = creatorId,
                uploadedAt = now
            )
        }
        val array = JSONArray()
        merged.values.forEach { a ->
            array.put(JSONObject().apply {
                put("globalAdapterId", a.globalAdapterId)
                put("localAdapterId", a.localAdapterId)
                put("name", a.name)
                put("host", a.host)
                put("pageKind", a.pageKind)
                put("frameworkType", a.frameworkType)
                put("creatorId", a.creatorId)
                put("uploadedAt", a.uploadedAt)
            })
        }
        val sha = GiteeApi.getFile(ADAPTERS_INDEX)?.sha
        GiteeApi.putFile(ADAPTERS_INDEX, array.toString(2), sha)
    }

    private fun updateRecordsIndex(
        creatorId: String,
        deviceName: String,
        selected: List<UploadItem>,
        adapterIdMap: Map<String, String>,
        now: Long
    ) {
        val existing = (fetchRecordsIndex() as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
        val merged = existing.associateBy { it.globalRecordId }.toMutableMap()
        for (item in selected) {
            val h = item.history
            val globalRecordId = UUID.randomUUID().toString()
            val isBuiltIn = h.adapterId.isBlank() || isBuiltInAdapter(h.adapterId)
            val globalAdapterId = if (isBuiltIn) null else adapterIdMap[h.adapterId].orEmpty().ifBlank { null }
            merged[globalRecordId] = SharedRecord(
                globalRecordId = globalRecordId,
                title = h.title,
                url = h.url,
                pageType = h.pageType,
                siteTitle = h.siteTitle,
                frameworkType = h.frameworkType,
                adapterKind = if (globalAdapterId != null) "CUSTOM_JSON" else "BUILT_IN",
                globalAdapterId = globalAdapterId,
                localAdapterId = h.adapterId.ifBlank { null },
                adapterName = h.adapterName,
                creatorId = creatorId,
                deviceName = deviceName,
                uploadedAt = now
            )
        }
        val array = JSONArray()
        merged.values.forEach { r ->
            array.put(JSONObject().apply {
                put("globalRecordId", r.globalRecordId)
                put("title", r.title)
                put("url", r.url)
                put("pageType", r.pageType)
                put("siteTitle", r.siteTitle)
                put("frameworkType", r.frameworkType)
                put("adapterKind", r.adapterKind)
                put("globalAdapterId", r.globalAdapterId ?: JSONObject.NULL)
                put("localAdapterId", r.localAdapterId ?: JSONObject.NULL)
                put("adapterName", r.adapterName)
                put("creatorId", r.creatorId)
                put("deviceName", r.deviceName)
                put("uploadedAt", r.uploadedAt)
            })
        }
        val sha = GiteeApi.getFile(RECORDS_INDEX)?.sha
        GiteeApi.putFile(RECORDS_INDEX, array.toString(2), sha)
    }

    // ===================== 下载 =====================

    data class DownloadResult(
        val recordsSaved: Int,
        val adaptersSaved: Int,
        val skippedRecords: Int,
        val skippedAdapters: Int,
        val errors: List<String>
    )

    /**
     * 从云端下载选中的记录及关联适配器，增量保存到本地。
     * - 记录：按 url 去重（已存在的跳过），使用 saveParseHistory
     * - 适配器：CUSTOM_JSON 的下载规则文件，写入 filesDir/json_adapters/，按 host + pageKind 绑定
     */
    fun downloadShared(
        context: Context,
        selectedGlobalRecordIds: List<String>,
        allRecords: List<SharedRecord>,
        allAdapters: List<SharedAdapter>
    ): GiteeApi.ApiResult<DownloadResult> {
        if (selectedGlobalRecordIds.isEmpty()) return GiteeApi.ApiResult.Error("没有选中任何记录")

        val errors = mutableListOf<String>()
        val store = WebParseStore(context)
        var recordsSaved = 0
        var skippedRecords = 0
        var adaptersSaved = 0
        var skippedAdapters = 0

        val existingUrls = store.getParseHistory().map { it.url }.toSet()
        val adapterMap = allAdapters.associateBy { it.globalAdapterId }
        val selectedRecords = allRecords.filter { selectedGlobalRecordIds.contains(it.globalRecordId) }

        // 收集需要下载的适配器（去重）
        val adapterIdsToDownload = selectedRecords
            .mapNotNull { it.globalAdapterId }
            .distinct()
            .filter { it.isNotBlank() }

        // Step1: 下载并保存适配器（复用 downloadAdapterById，保持单一下载入口）
        for (globalAdapterId in adapterIdsToDownload) {
            val meta = adapterMap[globalAdapterId]
            if (meta == null) {
                errors.add("适配器 $globalAdapterId 无元数据，跳过")
                continue
            }
            when (val r = downloadAdapterById(context, globalAdapterId, meta)) {
                is GiteeApi.ApiResult.Success -> adaptersSaved++
                is GiteeApi.ApiResult.NotFound -> { errors.add("适配器文件不存在: ${meta.name}"); skippedAdapters++ }
                is GiteeApi.ApiResult.Error -> { errors.add("适配器下载失败: ${meta.name}, ${r.message}"); skippedAdapters++ }
            }
        }

        // Step2: 保存记录
        for (record in selectedRecords) {
            if (existingUrls.contains(record.url)) {
                skippedRecords++
                continue
            }
            // adapterId：内置的用 adapterId，自定义的用 globalAdapterId（和写入的 binding adapterId 一致）
            val adapterIdForSave = if (record.adapterKind == "BUILT_IN") {
                record.localAdapterId.orEmpty()
            } else {
                record.globalAdapterId.orEmpty()
            }
            runCatching {
                store.saveParseHistory(
                    title = record.title,
                    url = record.url,
                    pageType = record.pageType,
                    siteTitle = record.siteTitle,
                    frameworkType = record.frameworkType,
                    adapterName = record.adapterName,
                    adapterId = adapterIdForSave
                )
            }.onSuccess { recordsSaved++ }
                .onFailure { errors.add("记录保存失败: ${record.title}, ${it.message}") }
        }

        return GiteeApi.ApiResult.Success(DownloadResult(recordsSaved, adaptersSaved, skippedRecords, skippedAdapters, errors))
    }

    /**
     * 按 globalAdapterId 从云端下载适配器规则文件，保存到本地 filesDir/json_adapters/，
     * 并更新 WebParseAdapterStore 的 DomainBinding。
     * 动画城打开动画时按需调用：若本地已存在同名规则文件则覆盖更新。
     * @return Success(fileName)=保存成功并返回规则文件名；NotFound/Error=失败
     */
    fun downloadAdapterById(
        context: Context,
        globalAdapterId: String,
        meta: SharedAdapter
    ): GiteeApi.ApiResult<String> {
        val path = "$ADAPTERS_DIR/$globalAdapterId.json"
        return when (val result = GiteeApi.getFileResult(path)) {
            is GiteeApi.ApiResult.Success -> {
                val ruleText = runCatching {
                    val obj = JSONObject(result.value.content)
                    obj.optJSONObject("rule")?.toString(2) ?: result.value.content
                }.getOrElse { result.value.content }
                val pageKind = runCatching {
                    com.bd.casttv.webparse.ParsePageKind.valueOf(meta.pageKind)
                }.getOrElse { com.bd.casttv.webparse.ParsePageKind.DETAIL }
                val frameworkType = runCatching {
                    com.bd.casttv.webparse.WebFrameworkType.valueOf(meta.frameworkType)
                }.getOrElse { com.bd.casttv.webparse.WebFrameworkType.UNKNOWN }
                val ruleInfo = runCatching {
                    RuleBasedAdapter.saveRule(context, ruleText, meta.name, pageKind)
                }.getOrElse {
                    return GiteeApi.ApiResult.Error("适配器保存失败: ${meta.name}")
                }
                val binding = WebParseAdapterStore.DomainBinding(
                    pageKind = pageKind,
                    host = meta.host,
                    adapterId = globalAdapterId,
                    adapterKind = com.bd.casttv.webparse.AdapterKind.CUSTOM_JSON,
                    adapterName = meta.name,
                    ruleFileName = ruleInfo.fileName,
                    frameworkType = frameworkType,
                    updatedAt = meta.uploadedAt
                )
                WebParseAdapterStore(context).forceUpdateBinding(binding)
                GiteeApi.ApiResult.Success(ruleInfo.fileName)
            }
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.NotFound
            is GiteeApi.ApiResult.Error -> result
        }
    }

    // ===================== 删除 =====================

    /**
     * 检查指定记录关联的适配器是否被其他记录引用。
     * @return 适配器关联的其他记录数量（当前记录除外）
     */
    fun countAdapterReferences(
        record: SharedRecord,
        allRecords: List<SharedRecord>
    ): Int {
        val globalAdapterId = record.globalAdapterId ?: return 0
        if (globalAdapterId.isBlank()) return 0
        return allRecords.count { other ->
            other.globalRecordId != record.globalRecordId &&
                other.globalAdapterId == globalAdapterId
        }
    }

    /**
     * 删除云端记录文件并更新索引。
     * 如果记录关联的适配器没有被其他记录引用，适配器也一并删除。
     * @param globalRecordId 要删除的记录全局 ID
     * @param allRecords 当前列表（用于重建索引和判断适配器引用）
     */
    fun deleteSharedRecord(
        globalRecordId: String,
        allRecords: List<SharedRecord>
    ): GiteeApi.ApiResult<Boolean> {
        // 查找记录
        val record = allRecords.firstOrNull { it.globalRecordId == globalRecordId }

        // Step1: 删除记录文件
        val path = "$RECORDS_DIR/$globalRecordId.json"
        val fileResult = GiteeApi.getFile(path)
        if (fileResult != null) {
            if (!GiteeApi.deleteFile(path, fileResult.sha)) {
                return GiteeApi.ApiResult.Error("删除记录文件失败")
            }
        }
        // Step2: 重建记录索引（排除被删除的记录）
        val remaining = allRecords.filterNot { it.globalRecordId == globalRecordId }
        val array = JSONArray()
        remaining.forEach { r ->
            array.put(JSONObject().apply {
                put("globalRecordId", r.globalRecordId)
                put("title", r.title)
                put("url", r.url)
                put("pageType", r.pageType)
                put("siteTitle", r.siteTitle)
                put("frameworkType", r.frameworkType)
                put("adapterKind", r.adapterKind)
                put("globalAdapterId", r.globalAdapterId ?: JSONObject.NULL)
                put("localAdapterId", r.localAdapterId ?: JSONObject.NULL)
                put("adapterName", r.adapterName)
                put("creatorId", r.creatorId)
                put("deviceName", r.deviceName)
                put("uploadedAt", r.uploadedAt)
            })
        }
        val sha = GiteeApi.getFile(RECORDS_INDEX)?.sha
        GiteeApi.putFile(RECORDS_INDEX, array.toString(2), sha)

        // Step3: 如果记录关联的适配器没有被其他记录引用，一起删除适配器
        if (record != null) {
            val globalAdapterId = record.globalAdapterId
            if (globalAdapterId != null && globalAdapterId.isNotBlank()) {
                val refCount = remaining.count { it.globalAdapterId == globalAdapterId }
                if (refCount == 0) {
                    deleteCloudAdapter(globalAdapterId)
                }
            }
        }

        return GiteeApi.ApiResult.Success(true)
    }

    /**
     * 单个适配器上传：写适配器文件 + 更新索引。
     * 用于「添加到动画城」时上传当前页面的自定义 JSON 规则（非 BUILT_IN）。
     * 幂等：按 generateGlobalAdapterId(creatorId, host, pageKind.name) 覆盖更新。
     * @return Success(globalAdapterId) 或 Error(message)
     */
    fun upsertSharedAdapter(
        context: Context,
        binding: WebParseAdapterStore.DomainBinding,
        ruleText: String
    ): GiteeApi.ApiResult<String> {
        if (ruleText.isBlank()) return GiteeApi.ApiResult.Error("规则为空")
        if (isBuiltInAdapter(binding.adapterId)) return GiteeApi.ApiResult.Success("")
        val creatorId = CreatorIdProvider.get(context)
        val now = System.currentTimeMillis()
        val globalId = generateGlobalAdapterId(creatorId, binding.host, binding.pageKind.name)
        val adapterJson = buildAdapterJson(globalId, binding.adapterId, binding, ruleText, creatorId, now)
        val path = "$ADAPTERS_DIR/$globalId.json"
        val existing = GiteeApi.getFile(path)
        when (val r = GiteeApi.putFileResult(path, adapterJson, existing?.sha, "upsert adapter $globalId")) {
            is GiteeApi.ApiResult.Success -> { /* ok */ }
            is GiteeApi.ApiResult.Error -> return GiteeApi.ApiResult.Error("适配器上传失败: ${r.message}")
            is GiteeApi.ApiResult.NotFound -> return GiteeApi.ApiResult.Error("适配器上传失败: NotFound")
        }
        // 更新索引（单条目 upsert）
        val existingIndex = (fetchAdaptersIndex() as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
        val merged = existingIndex.associateBy { it.globalAdapterId }.toMutableMap()
        merged[globalId] = SharedAdapter(
            globalAdapterId = globalId,
            localAdapterId = binding.adapterId,
            name = binding.adapterName,
            host = binding.host,
            pageKind = binding.pageKind.name,
            frameworkType = binding.frameworkType.name,
            creatorId = creatorId,
            uploadedAt = now
        )
        val array = JSONArray()
        merged.values.forEach { a ->
            array.put(JSONObject().apply {
                put("globalAdapterId", a.globalAdapterId)
                put("localAdapterId", a.localAdapterId)
                put("name", a.name)
                put("host", a.host)
                put("pageKind", a.pageKind)
                put("frameworkType", a.frameworkType)
                put("creatorId", a.creatorId)
                put("uploadedAt", a.uploadedAt)
            })
        }
        val idxResult = putIndexWithRetry(ADAPTERS_INDEX, array.toString(2), "upsert adapter index $globalId")
        return when (idxResult) {
            is GiteeApi.ApiResult.Success -> GiteeApi.ApiResult.Success(globalId)
            is GiteeApi.ApiResult.Error -> GiteeApi.ApiResult.Error("适配器索引写入失败: ${idxResult.message}")
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.Error("适配器索引写入失败: NotFound")
        }
    }

    /** 删除云端适配器文件和索引条目。调用方需先确认无其他引用。 */
    private fun deleteCloudAdapter(globalAdapterId: String) {
        val adapterPath = "$ADAPTERS_DIR/$globalAdapterId.json"
        val adapterFile = GiteeApi.getFile(adapterPath)
        if (adapterFile != null) {
            GiteeApi.deleteFile(adapterPath, adapterFile.sha)
        }
        val adapterIndexResult = fetchAdaptersIndex()
        val adapterIndex = (adapterIndexResult as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
        val remainingAdapters = adapterIndex.filterNot { it.globalAdapterId == globalAdapterId }
        val adapterArray = JSONArray()
        remainingAdapters.forEach { a ->
            adapterArray.put(JSONObject().apply {
                put("globalAdapterId", a.globalAdapterId)
                put("localAdapterId", a.localAdapterId)
                put("name", a.name)
                put("host", a.host)
                put("pageKind", a.pageKind)
                put("frameworkType", a.frameworkType)
                put("creatorId", a.creatorId)
                put("uploadedAt", a.uploadedAt)
            })
        }
        // ponytail: 索引写入失败只记录不抛，防止级联删除失败（后续下次 upsert 会重建）。
        runCatching { putIndexWithRetry(ADAPTERS_INDEX, adapterArray.toString(2), "delete adapter index $globalAdapterId") }
    }

    // ===================== 动画城 =====================

    /** 拉取云端动画城索引。 */
    fun fetchCartoonsIndex(): GiteeApi.ApiResult<List<SharedCartoon>> {
        return when (val result = GiteeApi.getFileResult(CARTOONS_INDEX)) {
            is GiteeApi.ApiResult.Success -> {
                GiteeApi.ApiResult.Success(parseCartoonsIndex(result.value.content))
            }
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.Success(emptyList())
            is GiteeApi.ApiResult.Error -> result
        }
    }

    /** 取 _index.json 的原始文本（供脏数据自检用）。 */
    fun fetchRawCartoonsIndexText(): GiteeApi.ApiResult<String> {
        return when (val result = GiteeApi.getFileResult(CARTOONS_INDEX)) {
            is GiteeApi.ApiResult.Success -> GiteeApi.ApiResult.Success(result.value.content)
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.Success("[]")
            is GiteeApi.ApiResult.Error -> result
        }
    }

    /** 只用于诊断 UI：返回 token 状态（绝不外泄明文）。 */
    fun tokenSummary(): String = runCatching { GiteeApi.tokenStateSummary() }.getOrDefault("UNKNOWN")

    private fun parseCartoonsIndex(content: String): List<SharedCartoon> {
        return runCatching {
            val raw = content.trim()
            val array = JSONArray(raw)
            val out = buildList<SharedCartoon> {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(com.bd.casttv.cartoon.CartoonStore.decodeSharedCartoon(obj))
                }
            }
            android.util.Log.i(TAG, "parseCartoonsIndex: parsed ${out.size} from ${raw.length}B, JSONArray=${array.length()}")
            out
        }.getOrElse {
            android.util.Log.e(TAG, "parseCartoonsIndex: parse failed, contentHead=${content.take(200)}", it)
            emptyList()
        }
    }

    /**
     * 创建或更新动画城卡片（按 detailUrl 幂等）。
     *
     * 新字段 cartoonId：如果调用方指定了明确的 cartoonId（例如动画城结果页回写既有卡片），
     * 就用指定 id；否则按 [generateCartoonId] 从 detailUrl 生成，保证 URL=ID 幂等。
     *
     * @return Success(SharedCartoon)=写入成功的卡片
     */
    fun upsertCartoon(
        context: Context,
        title: String,
        detailUrl: String,
        cover: String,
        globalAdapterId: String?,
        adapterName: String,
        episodeCount: Int,
        description: String = "",
        cartoonId: String? = null
    ): GiteeApi.ApiResult<SharedCartoon> {
        val creatorId = CreatorIdProvider.get(context)
        val deviceName = com.bd.casttv.settings.Settings(context).deviceName
        val resolvedId = cartoonId?.trim()?.ifBlank { null } ?: generateCartoonId(detailUrl)
        val now = System.currentTimeMillis()
        val cartoon = SharedCartoon(
            cartoonId = resolvedId,
            title = title.trim().ifBlank { detailUrl },
            detailUrl = detailUrl.trim(),
            cover = cover.trim(),
            globalAdapterId = globalAdapterId?.takeIf { it.isNotBlank() },
            adapterName = adapterName.trim(),
            episodeCount = episodeCount,
            creatorId = creatorId,
            deviceName = deviceName,
            uploadedAt = now,
            description = description.trim()
        )
        val cartoonJson = JSONObject().apply {
            put("cartoonId", cartoon.cartoonId)
            put("title", cartoon.title)
            put("detailUrl", cartoon.detailUrl)
            put("cover", cartoon.cover)
            put("globalAdapterId", cartoon.globalAdapterId ?: JSONObject.NULL)
            put("adapterName", cartoon.adapterName)
            put("episodeCount", cartoon.episodeCount)
            put("creatorId", cartoon.creatorId)
            put("deviceName", cartoon.deviceName)
            put("uploadedAt", cartoon.uploadedAt)
            if (cartoon.description.isNotEmpty()) put("description", cartoon.description)
        }
        val path = "$CARTOONS_DIR/$resolvedId.json"
        val sha = GiteeApi.getFile(path)?.sha
        when (val r = GiteeApi.putFileResult(path, cartoonJson.toString(2), sha, "upsert cartoon $resolvedId")) {
            is GiteeApi.ApiResult.Success -> { /* ok */ }
            is GiteeApi.ApiResult.NotFound -> return GiteeApi.ApiResult.Error("上传失败: NotFound")
            is GiteeApi.ApiResult.Error -> return GiteeApi.ApiResult.Error("上传失败: ${r.message}")
        }
        // 更新索引（upsert by cartoonId）
        val existing = (fetchCartoonsIndex() as? GiteeApi.ApiResult.Success)?.value ?: emptyList()
        val merged = existing.associateBy { it.cartoonId }.toMutableMap()
        merged[resolvedId] = cartoon
        val array = JSONArray()
        merged.values.forEach { c ->
            array.put(JSONObject().apply {
                put("cartoonId", c.cartoonId)
                put("title", c.title)
                put("detailUrl", c.detailUrl)
                put("cover", c.cover)
                put("globalAdapterId", c.globalAdapterId ?: JSONObject.NULL)
                put("adapterName", c.adapterName)
                put("episodeCount", c.episodeCount)
                put("creatorId", c.creatorId)
                put("deviceName", c.deviceName)
                put("uploadedAt", c.uploadedAt)
                if (c.description.isNotEmpty()) put("description", c.description)
            })
        }
        val idx = putIndexWithRetry(CARTOONS_INDEX, array.toString(2), "upsert cartoon index $resolvedId")
        return when (idx) {
            is GiteeApi.ApiResult.Success -> GiteeApi.ApiResult.Success(cartoon)
            is GiteeApi.ApiResult.Error -> GiteeApi.ApiResult.Error("动画索引写入失败: ${idx.message}")
            is GiteeApi.ApiResult.NotFound -> GiteeApi.ApiResult.Error("动画索引写入失败: NotFound")
        }
    }

    /**
     * 检查指定动画卡片关联的适配器是否被其他卡片或记录引用。
     * @return 适配器关联的其他卡片+记录数量（当前卡片除外）
     */
    fun countCartoonAdapterReferences(
        cartoon: SharedCartoon,
        allCartoons: List<SharedCartoon>,
        allRecords: List<SharedRecord>
    ): Int {
        val globalAdapterId = cartoon.globalAdapterId ?: return 0
        if (globalAdapterId.isBlank()) return 0
        val cartoonRefs = allCartoons.count { other ->
            other.cartoonId != cartoon.cartoonId && other.globalAdapterId == globalAdapterId
        }
        val recordRefs = allRecords.count { it.globalAdapterId == globalAdapterId }
        return cartoonRefs + recordRefs
    }

    /**
     * 删除云端动画卡片并更新索引。
     * 若卡片关联的适配器没有被其他卡片或记录引用，适配器也一并删除。
     */
    fun deleteCartoon(
        cartoonId: String,
        allCartoons: List<SharedCartoon>,
        allRecords: List<SharedRecord>
    ): GiteeApi.ApiResult<Boolean> {
        val cartoon = allCartoons.firstOrNull { it.cartoonId == cartoonId }
        // Step1: 删除 cartoon 文件
        val path = "$CARTOONS_DIR/$cartoonId.json"
        val fileResult = GiteeApi.getFile(path)
        if (fileResult != null) {
            if (!GiteeApi.deleteFile(path, fileResult.sha)) {
                return GiteeApi.ApiResult.Error("删除卡片文件失败")
            }
        }
        // Step2: 重建索引（排除被删除的卡片）
        val remaining = allCartoons.filterNot { it.cartoonId == cartoonId }
        val array = JSONArray()
        remaining.forEach { c ->
            array.put(JSONObject().apply {
                put("cartoonId", c.cartoonId)
                put("title", c.title)
                put("detailUrl", c.detailUrl)
                put("cover", c.cover)
                put("globalAdapterId", c.globalAdapterId ?: JSONObject.NULL)
                put("adapterName", c.adapterName)
                put("episodeCount", c.episodeCount)
                put("creatorId", c.creatorId)
                put("deviceName", c.deviceName)
                put("uploadedAt", c.uploadedAt)
            })
        }
        val del = putIndexWithRetry(CARTOONS_INDEX, array.toString(2), "delete cartoon index $cartoonId")
        if (del is GiteeApi.ApiResult.Error) return GiteeApi.ApiResult.Error("动画索引删除更新失败: ${del.message}")
        // Step3: 如果卡片关联的适配器没有被其他卡片或记录引用，一起删除适配器
        if (cartoon != null) {
            val globalAdapterId = cartoon.globalAdapterId
            if (globalAdapterId != null && globalAdapterId.isNotBlank()) {
                val cartoonRefCount = remaining.count { it.globalAdapterId == globalAdapterId }
                val recordRefCount = allRecords.count { it.globalAdapterId == globalAdapterId }
                if (cartoonRefCount == 0 && recordRefCount == 0) {
                    deleteCloudAdapter(globalAdapterId)
                }
            }
        }
        return GiteeApi.ApiResult.Success(true)
    }

    /**
     * 索引类文件写入：先读 sha → PUT。若因 Gitee 端写后读一致性延迟或 sha 陈旧导致 4xx，
     * 再拉一次最新 sha 重试一次，覆盖掉 boolean putFile 静默吞错的旧写法。
     * ponytail: 不做无限重试，1 次足以覆盖常见冲突；更大的冲突由调用方/用户下次 upsert 重建。
     */
    private fun putIndexWithRetry(path: String, content: String, commitMsg: String): GiteeApi.ApiResult<Unit> {
        var sha = GiteeApi.getFile(path)?.sha
        val first = GiteeApi.putFileResult(path, content, sha, commitMsg)
        if (first is GiteeApi.ApiResult.Success) return first
        // 刷新 sha 再重试一次
        sha = GiteeApi.getFile(path)?.sha
        return GiteeApi.putFileResult(path, content, sha, commitMsg)
    }
}
