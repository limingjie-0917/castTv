package com.bd.casttv.sync

import com.bd.casttv.dlna.SsdpDiagnostics
import com.bd.casttv.favorites.FavoritesStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gitee 云同步管理器：封装下载/上传业务逻辑。
 *
 * 云端文件结构：
 * - index.json: 合集元信息列表（id, name, type, updatedAt）
 * - collections/{id}.json: 单合集数据（items 列表）
 */
class GiteeSyncManager(private val store: FavoritesStore) {

    companion object {
        const val DEFAULT_MAX_NON_PRESET_DOWNLOAD = 5
        const val PRESET_DOWNLOAD_COUNT = 5
    }

    data class CloudIndex(
        val collections: List<CloudCollection>,
        val maxNonPresetDownload: Int = DEFAULT_MAX_NON_PRESET_DOWNLOAD
    ) {
        val maxTotalDownload: Int get() = PRESET_DOWNLOAD_COUNT + maxNonPresetDownload.coerceAtLeast(0)
    }

    /** 云端合集元信息。passwordHash 为空表示未加密；非空为 SHA-256 小写十六进制。 */
    data class CloudCollection(
        val id: String,
        val name: String,
        val type: String,
        val updatedAt: Long,
        val itemCount: Int = 0,
        val passwordHash: String = "",
        val creatorId: String = "",
        val downloadCount: Int = 0
    )

    /**
     * 上传/更新密码时的策略：
     *  - Keep : 保持云端已有 passwordHash 不变
     *  - Clear: 将 passwordHash 清空（取消加密）
     *  - Set  : 使用新的 SHA-256 哈希覆盖 passwordHash
     */
    sealed class PasswordAction {
        object Keep : PasswordAction()
        object Clear : PasswordAction()
        data class Set(val hash: String) : PasswordAction()
    }

    /** 上传结果，errorMessage 用于 Toast 展示更明确的失败原因。 */
    data class UploadResult(
        val successCount: Int,
        val errorMessage: String? = null
    )

    /** 获取云端合集列表。 */
    fun fetchCloudIndex(): List<CloudCollection>? = fetchCloudIndexConfig()?.collections

    /** 获取云端 index 配置与合集列表。 */
    fun fetchCloudIndexConfig(): CloudIndex? {
        val result = GiteeApi.getFile("index.json") ?: return null
        return try {
            val root = parseIndexRoot(result.content)
            CloudIndex(
                collections = parseCloudCollections(root.collections),
                maxNonPresetDownload = root.maxNonPresetDownload
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 异步增加云端合集下载次数。
     *
     * 先拉取最新 index.json，再仅更新命中的 downloadCount 字段，失败静默处理，避免影响下载主流程。
     */
    fun incrementDownloadCount(ids: List<String>) {
        val safeIds = ids.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (safeIds.isEmpty()) return
        Thread({
            try {
                val indexResult = GiteeApi.getFileResult("index.json")
                val root = when (indexResult) {
                    is GiteeApi.ApiResult.Success -> parseIndexRoot(indexResult.value.content)
                    GiteeApi.ApiResult.NotFound -> return@Thread
                    is GiteeApi.ApiResult.Error -> return@Thread
                }
                val indexSha = (indexResult as? GiteeApi.ApiResult.Success)?.value?.sha?.takeIf { it.isNotBlank() }
                val newArr = JSONArray()
                var changed = false
                for (i in 0 until root.collections.length()) {
                    val obj = root.collections.optJSONObject(i) ?: continue
                    if (obj.optString("id").trim() in safeIds) {
                        obj.put("downloadCount", obj.optInt("downloadCount", 0).coerceAtLeast(0) + 1)
                        changed = true
                    }
                    newArr.put(obj)
                }
                if (!changed) return@Thread
                val json = serializeIndex(root.rootObject, newArr, root.maxNonPresetDownload)
                when (val putResult = GiteeApi.putFileResult("index.json", json, indexSha, commitMessage = "sync: increment download count")) {
                    is GiteeApi.ApiResult.Success -> SsdpDiagnostics.logCloudSync("下载量更新成功：ids=${safeIds.size}")
                    else -> SsdpDiagnostics.logCloudSync("下载量更新失败：$putResult")
                }
            } catch (_: Throwable) {
                // 下载量统计不能影响下载主流程，异常静默处理。
            }
        }, "gitee-download-count").start()
    }

    /** 下载指定合集并覆盖本地。返回成功下载的合集数量。 */
    fun downloadCollections(ids: List<String>, cloudIndex: List<CloudCollection>): Int {
        var count = 0
        val localPresetCollections = store.collectionsInfo().filter { it.isPreset || it.isDefault }
        val localPresetIds = localPresetCollections.map { it.id }.toSet()
        val maxNonPresetDownload = fetchCloudIndexConfig()?.maxNonPresetDownload
            ?: DEFAULT_MAX_NON_PRESET_DOWNLOAD
        val nonPresetIds = ids.filterNot { it in localPresetIds }.take(maxNonPresetDownload.coerceAtLeast(0))
        val safeIds = ids.filter { it in localPresetIds } + nonPresetIds

        for (id in safeIds) {
            val cloudMeta = cloudIndex.firstOrNull { it.id == id } ?: continue
            val fileResult = GiteeApi.getFile("collections/$id.json") ?: continue
            try {
                val items = parseCloudItems(fileResult.content)
                // 写入本地：覆盖同 id 合集，或新建
                val localCollection = store.collection(id)
                if (localCollection != null) {
                    // 本地已存在：覆盖 items 和 type
                    store.importCloudCollection(
                        id = id,
                        name = cloudMeta.name,
                        type = cloudMeta.type,
                        items = items,
                        passwordHash = cloudMeta.passwordHash,
                        creatorId = cloudMeta.creatorId
                    )
                } else {
                    // 本地不存在：新建
                    store.importCloudCollection(
                        id = id,
                        name = cloudMeta.name,
                        type = cloudMeta.type,
                        items = items,
                        passwordHash = cloudMeta.passwordHash,
                        creatorId = cloudMeta.creatorId
                    )
                }
                count++
            } catch (e: Exception) {
                // skip this collection
            }
        }
        return count
    }

    /**
     * 上传本地合集到云端。
     * @param collections 要上传的本地合集列表
     * @param typeOverrides 管理员可修改 type：collectionId -> newType
     * @param passwordActions 每个合集的密码策略；未指定的合集视为 Keep（保持已有 passwordHash）
     * @return 成功上传数量
     */
    fun uploadCollections(
        collections: List<FavoritesStore.FavoriteCollection>,
        typeOverrides: Map<String, String> = emptyMap(),
        passwordActions: Map<String, PasswordAction> = emptyMap()
    ): UploadResult {
        var successCount = 0
        var failCount = 0
        var firstError: String? = null
        SsdpDiagnostics.logCloudSync("上传开始：合集数量=${collections.size}")

        // 1) 上传每个合集的 collections/{id}.json。
        // 空合集会被序列化为合法的 JSON 空数组 []，可直接创建/更新云端文件。
        for (c in collections) {
            val collectionJson = serializeCollectionItems(c.items)
            SsdpDiagnostics.logCloudSync("上传内容：${c.name}，type=${typeOverrides[c.id] ?: c.type}，items=${c.items.size}，json=${collectionJson.take(200)}")
            val path = "collections/${c.id}.json"

            // 先获取现有 sha：404 表示新文件，sha 必须为空，并使用 POST 创建。
            val existingResult = GiteeApi.getFileResult(path)
            val sha = when (existingResult) {
                is GiteeApi.ApiResult.Success -> existingResult.value.sha.takeIf { it.isNotBlank() }
                GiteeApi.ApiResult.NotFound -> null
                is GiteeApi.ApiResult.Error -> {
                    failCount++
                    firstError = firstError ?: existingResult.message
                    continue
                }
            }

            when (val putResult = GiteeApi.putFileResult(path, collectionJson, sha)) {
                is GiteeApi.ApiResult.Success -> successCount++
                is GiteeApi.ApiResult.Error -> {
                    failCount++
                    firstError = firstError ?: putResult.message
                }
                GiteeApi.ApiResult.NotFound -> {
                    failCount++
                    firstError = firstError ?: "写入 $path 失败：接口返回文件不存在"
                }
            }
        }

        if (successCount == 0) {
            SsdpDiagnostics.logCloudSync("上传结束：成功=0，失败=$failCount")
            return UploadResult(0, firstError)
        }

        // 2) 更新 index.json；如果明细已上传但索引失败，也要暴露具体错误，避免后续下载列表缺失。
        val indexResult = updateCloudIndex(collections, typeOverrides, passwordActions)
        return if (indexResult.isSuccess) {
            SsdpDiagnostics.logCloudSync("上传结束：成功=$successCount，失败=$failCount")
            UploadResult(successCount)
        } else {
            SsdpDiagnostics.logCloudSync("上传结束：成功=$successCount，失败=${failCount + 1}，index 更新失败：${indexResult.errorMessage("更新 index.json 失败")}")
            UploadResult(successCount, indexResult.errorMessage("更新 index.json 失败"))
        }
    }

    /** 删除云端合集（管理员操作）。 */
    fun deleteCloudCollection(id: String): Boolean {
        // 删除 collections/{id}.json
        val collFile = GiteeApi.getFile("collections/$id.json")
        if (collFile != null) {
            GiteeApi.deleteFile("collections/$id.json", collFile.sha)
        }

        // 更新 index.json：移除该条目
        val indexResult = GiteeApi.getFile("index.json") ?: return false
        try {
            val root = parseIndexRoot(indexResult.content)
            val arr = root.collections
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("id") != id) {
                    newArr.put(obj)
                }
            }
            return GiteeApi.putFile("index.json", serializeIndex(root.rootObject, newArr, root.maxNonPresetDownload), indexResult.sha)
        } catch (e: Exception) {
            return false
        }
    }

    private fun updateCloudIndex(
        uploadedCollections: List<FavoritesStore.FavoriteCollection>,
        typeOverrides: Map<String, String>,
        passwordActions: Map<String, PasswordAction> = emptyMap()
    ): GiteeApi.ApiResult<Unit> {
        if (uploadedCollections.isEmpty()) {
            SsdpDiagnostics.logCloudSync("index 构建失败：本次上传合集列表为空")
            return GiteeApi.ApiResult.Error("index 构建失败：本次上传合集列表为空")
        }

        // GET current index.json；404 表示首次创建 index，新建时 sha 必须为空。
        val indexResult = GiteeApi.getFileResult("index.json")
        val root = when (indexResult) {
            is GiteeApi.ApiResult.Success -> parseIndexRoot(indexResult.value.content)
            GiteeApi.ApiResult.NotFound -> IndexRoot(JSONObject(), JSONArray(), DEFAULT_MAX_NON_PRESET_DOWNLOAD)
            is GiteeApi.ApiResult.Error -> return GiteeApi.ApiResult.Error(indexResult.message)
        }
        val existingArr = root.collections
        val indexSha = (indexResult as? GiteeApi.ApiResult.Success)?.value?.sha?.takeIf { it.isNotBlank() }

        // Merge: 旧 index 可能被错误写成空字符串或 {}，这里统一按空索引处理；有 id 的旧条目才保留。
        val existingMap = linkedMapOf<String, JSONObject>()
        for (i in 0 until existingArr.length()) {
            val obj = existingArr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isNotBlank()) existingMap[id] = obj
        }

        val now = System.currentTimeMillis()
        for (c in uploadedCollections) {
            val id = c.id.trim()
            if (id.isBlank()) continue
            val finalType = (typeOverrides[id] ?: c.type).ifBlank { FavoritesStore.TYPE_SHARED }
            // 计算最终 passwordHash：Keep 沿用旧 index 中的值；Clear 置空；Set 覆盖。
            val prevHash = existingMap[id]?.optString("passwordHash", "")?.trim().orEmpty()
            val finalHash = when (val action = passwordActions[id]) {
                is PasswordAction.Set -> action.hash.trim()
                PasswordAction.Clear -> ""
                PasswordAction.Keep, null -> c.passwordHash.ifBlank { prevHash }.trim()
            }
            val entry = JSONObject().apply {
                put("id", id)
                put("name", c.name)
                put("type", finalType)
                put("updatedAt", now)
                put("itemCount", c.items.size)
                put("downloadCount", existingMap[id]?.optInt("downloadCount", 0)?.coerceAtLeast(0) ?: 0)
                if (c.creatorId.isNotBlank()) put("creatorId", c.creatorId.trim())
                if (finalHash.isNotBlank()) put("passwordHash", finalHash)
            }
            existingMap[id] = entry
        }

        if (existingMap.isEmpty()) {
            SsdpDiagnostics.logCloudSync("index 构建失败：没有可写入的有效合集摘要")
            return GiteeApi.ApiResult.Error("index 构建失败：没有可写入的有效合集摘要")
        }

        val newArr = JSONArray()
        existingMap.values.forEach { newArr.put(it) }
        val indexJson = serializeIndex(root.rootObject, newArr, root.maxNonPresetDownload)
        SsdpDiagnostics.logCloudSync("index 构建完成：合集数量=${newArr.length()}，json=${indexJson.take(500)}")

        return GiteeApi.putFileResult("index.json", indexJson, indexSha)
    }

    private data class IndexRoot(
        val rootObject: JSONObject?,
        val collections: JSONArray,
        val maxNonPresetDownload: Int
    )

    private fun parseCloudCollections(arr: JSONArray): List<CloudCollection> {
        val list = mutableListOf<CloudCollection>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                CloudCollection(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    type = obj.optString("type", FavoritesStore.TYPE_SHARED),
                    updatedAt = obj.optLong("updatedAt", 0L),
                    itemCount = obj.optInt("itemCount", 0),
                    passwordHash = obj.optString("passwordHash", "").trim(),
                    creatorId = obj.optString("creatorId", "").trim(),
                    downloadCount = obj.optInt("downloadCount", 0).coerceAtLeast(0)
                )
            )
        }
        return list
    }

    private fun parseIndexRoot(content: String): IndexRoot {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return IndexRoot(null, JSONArray(), DEFAULT_MAX_NON_PRESET_DOWNLOAD)
        return try {
            when {
                trimmed.startsWith("[") -> IndexRoot(null, JSONArray(trimmed), DEFAULT_MAX_NON_PRESET_DOWNLOAD)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    IndexRoot(
                        rootObject = obj,
                        collections = obj.optJSONArray("collections") ?: obj.optJSONArray("items") ?: JSONArray(),
                        maxNonPresetDownload = obj.optInt("maxNonPresetDownload", DEFAULT_MAX_NON_PRESET_DOWNLOAD).coerceAtLeast(0)
                    )
                }
                else -> IndexRoot(null, JSONArray(), DEFAULT_MAX_NON_PRESET_DOWNLOAD)
            }
        } catch (_: Exception) {
            IndexRoot(null, JSONArray(), DEFAULT_MAX_NON_PRESET_DOWNLOAD)
        }
    }

    private fun parseIndexArray(content: String): JSONArray = parseIndexRoot(content).collections

    private fun serializeIndex(rootObject: JSONObject?, collections: JSONArray, maxNonPresetDownload: Int): String {
        return (rootObject ?: JSONObject()).apply {
            put("maxNonPresetDownload", maxNonPresetDownload.coerceAtLeast(0))
            put("collections", collections)
            remove("items")
        }.toString()
    }

    fun updateMaxNonPresetDownload(value: Int): Boolean {
        val safeValue = value.coerceAtLeast(0)
        val indexResult = GiteeApi.getFileResult("index.json")
        val root = when (indexResult) {
            is GiteeApi.ApiResult.Success -> parseIndexRoot(indexResult.value.content)
            GiteeApi.ApiResult.NotFound -> IndexRoot(JSONObject(), JSONArray(), DEFAULT_MAX_NON_PRESET_DOWNLOAD)
            is GiteeApi.ApiResult.Error -> return false
        }
        val indexSha = (indexResult as? GiteeApi.ApiResult.Success)?.value?.sha?.takeIf { it.isNotBlank() }
        val json = serializeIndex(root.rootObject, root.collections, safeValue)
        return when (val putResult = GiteeApi.putFileResult("index.json", json, indexSha)) {
            is GiteeApi.ApiResult.Success -> true
            else -> {
                SsdpDiagnostics.logCloudSync("updateMaxNonPresetDownload 写入 index.json 失败：$putResult")
                false
            }
        }
    }

    private fun parseCloudItems(json: String): List<FavoritesStore.FavoriteItem> {
        val trimmed = json.trim()
        val arr = if (trimmed.startsWith("{")) {
            JSONObject(trimmed).optJSONArray("items") ?: JSONArray()
        } else {
            JSONArray(trimmed)
        }
        val items = mutableListOf<FavoritesStore.FavoriteItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(
                FavoritesStore.FavoriteItem(
                    id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    // 云端若带 item_id 则沿用，否则本地持久化时会自动补生成。
                    itemId = obj.optString("item_id"),
                    title = obj.optString("title"),
                    uri = obj.optString("uri"),
                    source = obj.optString("source"),
                    time = obj.optLong("time", 0L),
                    thumbPath = null, // 云端不保存本地路径
                    artworkPath = null,
                    durationMs = obj.optLong("durationMs", 0L),
                    description = obj.optString("description"),
                    resolution = obj.optString("resolution"),
                    isLive = obj.optBoolean("isLive", false)
                )
            )
        }
        return items
    }

    private fun serializeCollection(
        collection: FavoritesStore.FavoriteCollection,
        type: String
    ): String {
        return JSONObject().apply {
            put("id", collection.id)
            put("name", collection.name)
            put("type", type)
            put("isDefault", collection.isDefault)
            put("isPreset", collection.isPreset)
            put("createdAt", collection.createdAt)
            put("updatedAt", System.currentTimeMillis())
            put("items", JSONArray(serializeCollectionItems(collection.items)))
        }.toString()
    }

    private fun serializeCollectionItems(items: List<FavoritesStore.FavoriteItem>): String {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                if (item.itemId.isNotBlank()) put("item_id", item.itemId)
                put("title", item.title)
                put("uri", item.uri)
                put("source", item.source)
                put("time", item.time)
                put("durationMs", item.durationMs)
                if (item.description.isNotBlank()) put("description", item.description)
                if (item.resolution.isNotBlank()) put("resolution", item.resolution)
                if (item.isLive) put("isLive", true)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * 只更新 index.json 中某个合集的 passwordHash（用于「忘记密码」重置流程）。
     * newHash 为空表示清除密码；非空为已计算好的 SHA-256 哈希。
     * 返回 true 表示成功写回云端。
     */
    fun updatePasswordHash(id: String, newHash: String): Boolean {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return false
        val indexResult = GiteeApi.getFileResult("index.json")
        val root = when (indexResult) {
            is GiteeApi.ApiResult.Success -> parseIndexRoot(indexResult.value.content)
            GiteeApi.ApiResult.NotFound -> return false
            is GiteeApi.ApiResult.Error -> return false
        }
        val existingArr = root.collections
        val indexSha = (indexResult as? GiteeApi.ApiResult.Success)?.value?.sha?.takeIf { it.isNotBlank() }
        val newArr = JSONArray()
        var hit = false
        for (i in 0 until existingArr.length()) {
            val obj = existingArr.optJSONObject(i) ?: continue
            if (obj.optString("id").trim() == cleanId) {
                hit = true
                if (newHash.isBlank()) {
                    obj.remove("passwordHash")
                } else {
                    obj.put("passwordHash", newHash.trim())
                }
                obj.put("updatedAt", System.currentTimeMillis())
            }
            newArr.put(obj)
        }
        if (!hit) return false
        return when (val putResult = GiteeApi.putFileResult("index.json", serializeIndex(root.rootObject, newArr, root.maxNonPresetDownload), indexSha)) {
            is GiteeApi.ApiResult.Success -> true
            else -> {
                SsdpDiagnostics.logCloudSync("updatePasswordHash 写入 index.json 失败：$putResult")
                false
            }
        }
    }

    /**
     * 获取云端指定合集的视频条目（不写入本地）。用于「忘记密码」时的关键词模糊校验。
     * 返回 null 表示获取失败（网络或不存在）。
     */
    fun fetchCloudItems(id: String): List<FavoritesStore.FavoriteItem>? {
        val fileResult = GiteeApi.getFile("collections/${id}.json") ?: return null
        return try {
            parseCloudItems(fileResult.content)
        } catch (_: Exception) {
            null
        }
    }
}
