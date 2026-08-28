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

    private const val ADAPTERS_DIR = "shared_data/adapters"
    private const val RECORDS_DIR = "shared_data/records"
    private const val ADAPTERS_INDEX = "$ADAPTERS_DIR/_index.json"
    private const val RECORDS_INDEX = "$RECORDS_DIR/_index.json"

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

    private fun fetchRecordsIndex(): GiteeApi.ApiResult<List<SharedRecord>> {
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
        val adapterStore = WebParseAdapterStore(context)
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

        // Step1: 下载并保存适配器
        for (globalAdapterId in adapterIdsToDownload) {
            val meta = adapterMap[globalAdapterId]
            if (meta == null) {
                errors.add("适配器 $globalAdapterId 无元数据，跳过")
                continue
            }
            val path = "$ADAPTERS_DIR/$globalAdapterId.json"
            val result = GiteeApi.getFileResult(path)
            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    // 解析 JSON 规则内容（rule 字段）
                    val ruleText = runCatching {
                        val obj = JSONObject(result.value.content)
                        obj.optJSONObject("rule")?.toString(2) ?: result.value.content
                    }.getOrElse { result.value.content }

                    // 写入本地规则文件
                    val pageKind = runCatching {
                        com.bd.casttv.webparse.ParsePageKind.valueOf(meta.pageKind)
                    }.getOrElse { com.bd.casttv.webparse.ParsePageKind.DETAIL }
                    val frameworkType = runCatching {
                        com.bd.casttv.webparse.WebFrameworkType.valueOf(meta.frameworkType)
                    }.getOrElse { com.bd.casttv.webparse.WebFrameworkType.UNKNOWN }

                    val ruleInfo = runCatching {
                        RuleBasedAdapter.saveRule(context, ruleText, meta.name, pageKind)
                    }.getOrElse {
                        errors.add("适配器保存失败: ${meta.name}")
                        null
                    }
                    if (ruleInfo != null) {
                        // 写入域名绑定
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
                        adapterStore.forceUpdateBinding(binding)
                        adaptersSaved++
                    }
                }
                is GiteeApi.ApiResult.NotFound -> { errors.add("适配器文件不存在: ${meta.name}"); skippedAdapters++ }
                is GiteeApi.ApiResult.Error -> { errors.add("适配器下载失败: ${meta.name}, ${result.message}"); skippedAdapters++ }
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
                    val adapterPath = "$ADAPTERS_DIR/$globalAdapterId.json"
                    val adapterFile = GiteeApi.getFile(adapterPath)
                    if (adapterFile != null) {
                        GiteeApi.deleteFile(adapterPath, adapterFile.sha)
                    }
                    // 从适配器索引中移除
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
                    val adapterSha = GiteeApi.getFile(ADAPTERS_INDEX)?.sha
                    GiteeApi.putFile(ADAPTERS_INDEX, adapterArray.toString(2), adapterSha)
                }
            }
        }

        return GiteeApi.ApiResult.Success(true)
    }
}
