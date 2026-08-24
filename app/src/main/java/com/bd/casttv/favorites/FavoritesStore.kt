package com.bd.casttv.favorites

import android.content.Context
import com.bd.casttv.util.CreatorIdProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 收藏数据的读写管理器（合集架构）。
 *
 * ## 新存储结构（内部实现，public API 语义保持不变）
 *
 * ```
 * filesDir/
 *   favorites/
 *     index.json               # 合集元信息 + uri 反向索引（不含 items 明细）
 *     collections/
 *       {collectionId}.json    # 单合集 items 明细（JSONArray）
 * ```
 *
 * 迁移：若检测到旧版 `favorites.json`，启动时会自动迁移到新目录结构，迁移成功后删除旧文件。
 *
 * 线程模型：
 * - 所有读写操作统一走同一把「进程内全局锁」，保证 PlayerActivity / MainActivity / PhoneHubServer
 *   即使各自创建了 FavoritesStore 实例，也不会出现并发写导致的数据损坏。
 */
class FavoritesStore(context: Context) {

    val appContext: Context = context.applicationContext

    // 旧版单文件存储（v1.1.77 及之前）
    private val legacyFile: File by lazy { File(appContext.filesDir, LEGACY_FILE_NAME) }

    // 新版目录结构（v1.1.78 起）
    private val favoritesDir: File by lazy { File(appContext.filesDir, FAVORITES_DIR_NAME) }
    private val indexFile: File by lazy { File(favoritesDir, INDEX_FILE_NAME) }
    private val collectionsDir: File by lazy { File(favoritesDir, COLLECTIONS_DIR_NAME) }

    // Step5：导入覆盖的事务目录
    private val tmpDir: File by lazy { File(appContext.filesDir, TMP_DIR_NAME) }

    /** 单条收藏记录。title 为用户自定义标题，uri 为投屏资源地址。
     * thumbPath 为持久化的缩略图文件绝对路径（`filesDir/thumbnails_favorites/thumb_*.jpg`）；
     * 旧数据 / 截图失败时为 null，UI 层展示默认兜底图。
     * artworkPath 为 DLNA 元数据封面（`upnp:albumArtURI`）下载落盘后的本地路径，
     * 与 thumbPath 并存、优先级更高；旧数据无此字段时反序列化为 null。 */
    data class FavoriteItem(
        val id: String = UUID.randomUUID().toString(),
        /**
         * 稳定业务 id（毫秒时间戳的 16 进制），用于批量操作按条目定位。
         * 播放器收藏时生成；旧数据读取时若为空会自动补生成（见 [parseItems]）。
         */
        val itemId: String = "",
        val title: String,
        val uri: String,
        val source: String,
        val time: Long,
        val thumbPath: String? = null,
        val artworkPath: String? = null,
        val durationMs: Long = 0L,
        val description: String = "",
        val resolution: String = "",
        val isLive: Boolean = false
    )

    /**
     * 合集。id 唯一，name 可重复（查询以 id 为准）。
     * isDefault 标记默认合集（名称不可改、不可删除）；
     * isPreset 标记 App 预置合集（不可删除；当前实现保持与旧版本一致：预置合集名称不可修改）。
     * type 标记合集可见性：shared（公开可下载）/ private（仅管理员可见）。
     */
    data class FavoriteCollection(
        val id: String,
        val name: String,
        val isDefault: Boolean,
        val isPreset: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val items: List<FavoriteItem>,
        val type: String = TYPE_SHARED,
        val passwordHash: String = "",
        val creatorId: String = ""
    )

    /** Step4：轻量合集信息（不触发 items 加载）。 */
    data class CollectionInfo(
        val id: String,
        val name: String,
        val isDefault: Boolean,
        val isPreset: Boolean,
        val itemCount: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val type: String = TYPE_SHARED,
        val passwordHash: String = "",
        val creatorId: String = ""
    )

    /** 通用操作结果。UI 依据结果给出对应提示。 */
    enum class OpResult {
        SUCCESS,
        LIMIT_TOTAL,            // 达到总收藏上限
        LIMIT_COLLECTION_ITEMS, // 目标合集条目已满
        LIMIT_COLLECTIONS,      // 合集数量已满
        NOT_FOUND,              // 合集 / 条目不存在
        NOT_ALLOWED,            // 默认合集不允许改名 / 删除
        INVALID,                // 参数非法（如标题为空）
        FAILED                  // 写入失败
    }

    // ------------------------------------------------------------------
    // Step2：内存缓存（index + items 按需）
    // ------------------------------------------------------------------

    private data class CollectionMeta(
        val id: String,
        val name: String,
        val isDefault: Boolean,
        val isPreset: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val itemCount: Int,
        val type: String = TYPE_SHARED,
        val passwordHash: String = "",
        val creatorId: String = ""
    ) {
        fun toInfo(): CollectionInfo = CollectionInfo(
            id = id,
            name = name,
            isDefault = isDefault,
            isPreset = isPreset,
            itemCount = itemCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
            type = type,
            passwordHash = passwordHash,
            creatorId = creatorId
        )
    }

    private data class Index(
        val collections: List<CollectionMeta>,
        /** Step6：uri -> [collectionId, ...] */
        val uriIndex: Map<String, List<String>>,
    )

    @Volatile private var cachedIndex: Index? = null
    @Volatile private var cachedIndexStamp: Long = Long.MIN_VALUE

    // itemsCache: collectionId -> items（多读并发下要求线程安全，改用 ConcurrentHashMap）
    private val itemsCache = ConcurrentHashMap<String, List<FavoriteItem>>()
    private val itemsStamp = ConcurrentHashMap<String, Long>()

    /**
     * P0-2：内存缓存版本号，单调递增。
     * 每次任何写操作（新增/修改/删除/迁移/清理缓存等）成功落盘后 +1，
     * 外部观察者可通过 [cacheVersion] 判断本地缓存是否变化，用于跳过重复刷新。
     */
    private val cacheVersion = AtomicLong(0L)

    /** 返回当前缓存版本号，写操作递增；观察者可用于差分刷新。 */
    fun cacheVersion(): Long = cacheVersion.get()

    private fun bumpCacheVersionLocked() {
        cacheVersion.incrementAndGet()
    }

    private fun indexStamp(): Long = fileStamp(indexFile)

    private fun collectionFile(collectionId: String): File = File(collectionsDir, "$collectionId.json")

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** Step4：轻量合集元信息（不触发 items 加载）。 */
    fun collectionsInfo(): List<CollectionInfo> {
        // 读锁快照路径：命中缓存直接返回，多读并发。
        readCachedIndexOrNull()?.let { idx ->
            return idx.collections.map { it.toInfo() }
        }
        return withWriteLock {
            loadIndexLocked().collections.map { it.toInfo() }
        }
    }

    /** 读取全部合集（全量，包含 items；默认合集排在最前）。 */
    fun collections(): List<FavoriteCollection> {
        // 读锁快照路径：命中 index 缓存并且所有 items 缓存均新鲜，直接返回
        val cachedIdx = readCachedIndexOrNull()
        if (cachedIdx != null) {
            val pairs = ArrayList<Pair<CollectionMeta, List<FavoriteItem>>>(cachedIdx.collections.size)
            var allWarm = true
            for (meta in cachedIdx.collections) {
                val items = readCachedItemsOrNull(meta.id)
                if (items == null) { allWarm = false; break }
                pairs.add(meta to items)
            }
            if (allWarm) {
                return pairs.map { (meta, items) ->
                    FavoriteCollection(
                        id = meta.id,
                        name = meta.name,
                        isDefault = meta.isDefault,
                        isPreset = meta.isPreset,
                        createdAt = meta.createdAt,
                        updatedAt = meta.updatedAt,
                        items = items,
                        type = meta.type,
                        passwordHash = meta.passwordHash,
                        creatorId = meta.creatorId
                    )
                }
            }
        }
        return withWriteLock {
            val idx = loadIndexLocked()
            idx.collections.mapNotNull { meta ->
                val items = loadItemsLocked(meta.id)
                FavoriteCollection(
                    id = meta.id,
                    name = meta.name,
                    isDefault = meta.isDefault,
                    isPreset = meta.isPreset,
                    createdAt = meta.createdAt,
                    updatedAt = meta.updatedAt,
                    items = items,
                    type = meta.type,
                    passwordHash = meta.passwordHash,
                    creatorId = meta.creatorId
                )
            }
        }
    }

    fun currentCreatorId(): String = CreatorIdProvider.get(appContext)

    fun canModifyCollectionType(id: String, isAdmin: Boolean = false): Boolean {
        readCachedIndexOrNull()?.let { idx ->
            val meta = idx.collections.firstOrNull { it.id == id } ?: return false
            return isAdmin || (meta.creatorId.isNotBlank() && meta.creatorId == currentCreatorId())
        }
        return withWriteLock {
            val meta = loadIndexLocked().collections.firstOrNull { it.id == id } ?: return@withWriteLock false
            isAdmin || (meta.creatorId.isNotBlank() && meta.creatorId == currentCreatorId())
        }
    }

    /** 默认合集 id。 */
    fun defaultCollectionId(): String {
        readCachedIndexOrNull()?.let { idx ->
            idx.collections.firstOrNull { it.isDefault }?.id?.let { return it }
        }
        return withWriteLock {
            val idx = ensureDefaultCollectionLocked(loadIndexLocked())
            idx.collections.firstOrNull { it.isDefault }?.id.orEmpty()
        }
    }

    /** 按 id 读取单个合集，不存在返回 null；Step3：按需读单文件。 */
    fun collection(id: String): FavoriteCollection? {
        readCachedIndexOrNull()?.let { idx ->
            val meta = idx.collections.firstOrNull { it.id == id } ?: return null
            val items = readCachedItemsOrNull(meta.id)
            if (items != null) {
                return FavoriteCollection(
                    id = meta.id,
                    name = meta.name,
                    isDefault = meta.isDefault,
                    isPreset = meta.isPreset,
                    createdAt = meta.createdAt,
                    updatedAt = meta.updatedAt,
                    items = items,
                    type = meta.type,
                    passwordHash = meta.passwordHash,
                    creatorId = meta.creatorId
                )
            }
        }
        return withWriteLock {
            val idx = loadIndexLocked()
            val meta = idx.collections.firstOrNull { it.id == id } ?: return@withWriteLock null
            val items = loadItemsLocked(id)
            FavoriteCollection(
                id = meta.id,
                name = meta.name,
                isDefault = meta.isDefault,
                isPreset = meta.isPreset,
                createdAt = meta.createdAt,
                updatedAt = meta.updatedAt,
                items = items,
                type = meta.type,
                passwordHash = meta.passwordHash,
                creatorId = meta.creatorId
            )
        }
    }

    /** 全部合集条目总数。 */
    fun totalCount(): Int {
        readCachedIndexOrNull()?.let { idx ->
            return idx.collections.sumOf { it.itemCount.coerceAtLeast(0) }
        }
        return withWriteLock {
            loadIndexLocked().collections.sumOf { it.itemCount.coerceAtLeast(0) }
        }
    }

    /** 默认合集最近 [count] 条收藏，供首页收藏区域展示。 */
    fun recentFromDefault(count: Int): List<FavoriteItem> {
        readCachedIndexOrNull()?.let { idx ->
            val defaultId = idx.collections.firstOrNull { it.isDefault }?.id
            if (defaultId != null) {
                val items = readCachedItemsOrNull(defaultId)
                if (items != null) return items.take(count)
            }
        }
        return withWriteLock {
            val idx = ensureDefaultCollectionLocked(loadIndexLocked())
            val defaultId = idx.collections.firstOrNull { it.isDefault }?.id ?: return@withWriteLock emptyList()
            loadItemsLocked(defaultId).take(count)
        }
    }

    /** 判断某个资源地址是否已在任意合集被收藏。 */
    fun contains(uri: String): Boolean {
        if (uri.isBlank()) return false
        readCachedIndexOrNull()?.let { idx ->
            val ids = idx.uriIndex[uri]
            return !ids.isNullOrEmpty()
        }
        return withWriteLock {
            val idx = loadIndexLocked()
            val ids = idx.uriIndex[uri]
            !ids.isNullOrEmpty()
        }
    }

    /**
     * 读锁快照：当 [cachedIndex] 有效（文件戳一致）时返回，否则返回 null。
     * 无副作用，纯查缓存。
     */
    private fun readCachedIndexOrNull(): Index? {
        if (!migrationChecked) return null
        return withReadLock {
            val c = cachedIndex
            if (c != null && cachedIndexStamp == indexStamp()) c else null
        }
    }

    /**
     * 读锁快照：当 items 缓存有效（文件戳一致）时返回，否则返回 null。
     * 无副作用，纯查缓存。
     */
    private fun readCachedItemsOrNull(collectionId: String): List<FavoriteItem>? {
        if (!migrationChecked) return null
        return withReadLock {
            val cached = itemsCache[collectionId] ?: return@withReadLock null
            val stamp = itemsStamp[collectionId] ?: return@withReadLock null
            val file = collectionFile(collectionId)
            if (stamp == fileStamp(file)) cached else null
        }
    }

    // ------------------------------------------------------------------
    // 收藏条目操作
    // ------------------------------------------------------------------

    /**
     * 新增收藏到默认合集（播放页收藏按钮调用）。title / uri 必须非空。
     * 若默认合集已存在相同 uri，则覆盖并移动到最前（去重，不占用新配额）。
     */
    fun addToDefault(
        title: String,
        uri: String,
        source: String,
        thumbPath: String? = null,
        durationMs: Long = 0L,
        description: String = "",
        resolution: String = ""
    ): OpResult = addToCollection(
        defaultCollectionId(),
        title,
        uri,
        source,
        thumbPath,
        durationMs,
        description,
        resolution
    )

    /**
     * 新增收藏到指定合集。title / uri 必须非空。
     * 若目标合集已存在相同 uri，则覆盖并移动到最前（去重，不占用新配额）。
     */
    fun addToCollection(
        collectionId: String,
        title: String,
        uri: String,
        source: String,
        thumbPath: String? = null,
        durationMs: Long = 0L,
        description: String = "",
        resolution: String = "",
        isLive: Boolean = false
    ): OpResult = withLock {
        if (title.isBlank() || uri.isBlank()) return@withLock OpResult.INVALID

        val idx0 = ensureDefaultCollectionLocked(loadIndexLocked())
        val metaIndex = idx0.collections.indexOfFirst { it.id == collectionId }
        if (metaIndex < 0) return@withLock OpResult.NOT_FOUND

        val meta = idx0.collections[metaIndex]
        val items = loadItemsLocked(collectionId)
        val prev = items.firstOrNull { it.uri == uri }
        val exists = prev != null

        // 新增（非覆盖）时校验总量与单合集上限。
        if (!exists) {
            val total = idx0.collections.sumOf { if (it.id == collectionId) items.size else it.itemCount.coerceAtLeast(0) }
            if (total >= MAX_TOTAL_FAVORITES) return@withLock OpResult.LIMIT_TOTAL
            if (items.size >= MAX_ITEMS_PER_COLLECTION) return@withLock OpResult.LIMIT_COLLECTION_ITEMS
        }

        val now = System.currentTimeMillis()
        val finalThumb = thumbPath ?: prev?.thumbPath
        val finalArtwork = prev?.artworkPath
        val finalDurationMs = durationMs.takeIf { it > 0L } ?: prev?.durationMs ?: 0L
        val finalDescription = description.trim().ifBlank { prev?.description.orEmpty() }
        val finalResolution = resolution.trim().ifBlank { prev?.resolution.orEmpty() }
        val finalIsLive = isLive || (prev?.isLive == true)

        val newItems = items.filterNot { it.uri == uri }.toMutableList()
        newItems.add(
            0,
            FavoriteItem(
                id = prev?.id ?: UUID.randomUUID().toString(),
                // 覆盖已有条目时沿用其 itemId，否则按「毫秒时间戳 16 进制」生成新的稳定 id。
                itemId = prev?.itemId?.takeIf { it.isNotBlank() } ?: java.lang.Long.toHexString(now),
                title = title.trim(),
                uri = uri,
                source = source,
                time = now,
                thumbPath = finalThumb,
                artworkPath = finalArtwork,
                durationMs = finalDurationMs,
                description = finalDescription,
                resolution = finalResolution,
                isLive = finalIsLive
            )
        )

        // Step6：维护 uriIndex
        val newUriIndex = idx0.uriIndex.toMutableMap()
        val owners = newUriIndex[uri].orEmpty().toMutableSet()
        owners.add(collectionId)
        newUriIndex[uri] = owners.toList()

        val newCollections = idx0.collections.toMutableList()
        newCollections[metaIndex] = meta.copy(updatedAt = now, itemCount = newItems.size)

        val newIndex = idx0.copy(collections = newCollections, uriIndex = newUriIndex)
        val ok = updateCollectionsAndIndexLocked(mapOf(collectionId to newItems), newIndex)
        if (!ok) return@withLock OpResult.FAILED

        OpResult.SUCCESS
    }

    /** 回填指定 uri 的收藏缩略图路径；Step6：通过 uriIndex 快速定位需要更新的合集。 */
    fun updateItemThumb(uri: String, thumbPath: String): OpResult = withLock {
        if (uri.isBlank() || thumbPath.isBlank()) return@withLock OpResult.INVALID
        val idx0 = loadIndexLocked()
        val targetIds = idx0.uriIndex[uri].orEmpty().distinct()
        if (targetIds.isEmpty()) return@withLock OpResult.NOT_FOUND

        val now = System.currentTimeMillis()
        val newMetas = idx0.collections.toMutableList()
        val changedCollections = mutableMapOf<String, List<FavoriteItem>>()

        for (cid in targetIds) {
            val metaPos = newMetas.indexOfFirst { it.id == cid }
            if (metaPos < 0) continue
            val items = loadItemsLocked(cid)
            var localChanged = false
            val newItems = items.map { item ->
                if (item.uri == uri) {
                    localChanged = true
                    item.copy(thumbPath = thumbPath)
                } else item
            }
            if (localChanged) {
                changedCollections[cid] = newItems
                newMetas[metaPos] = newMetas[metaPos].copy(updatedAt = now)
            }
        }

        if (changedCollections.isEmpty()) return@withLock OpResult.NOT_FOUND
        val ok = updateCollectionsAndIndexLocked(changedCollections, idx0.copy(collections = newMetas))
        if (!ok) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    /** 回填指定 uri 的收藏封面路径（DLNA 元数据封面 artworkPath）；Step6：通过 uriIndex 快速定位。 */
    fun updateItemArtwork(uri: String, artworkPath: String): OpResult = withLock {
        if (uri.isBlank() || artworkPath.isBlank()) return@withLock OpResult.INVALID
        val idx0 = loadIndexLocked()
        val targetIds = idx0.uriIndex[uri].orEmpty().distinct()
        if (targetIds.isEmpty()) return@withLock OpResult.NOT_FOUND

        val now = System.currentTimeMillis()
        val newMetas = idx0.collections.toMutableList()
        val changedCollections = mutableMapOf<String, List<FavoriteItem>>()

        for (cid in targetIds) {
            val metaPos = newMetas.indexOfFirst { it.id == cid }
            if (metaPos < 0) continue
            val items = loadItemsLocked(cid)
            var localChanged = false
            val newItems = items.map { item ->
                if (item.uri == uri) {
                    localChanged = true
                    item.copy(artworkPath = artworkPath)
                } else item
            }
            if (localChanged) {
                changedCollections[cid] = newItems
                newMetas[metaPos] = newMetas[metaPos].copy(updatedAt = now)
            }
        }

        if (changedCollections.isEmpty()) return@withLock OpResult.NOT_FOUND
        val ok = updateCollectionsAndIndexLocked(changedCollections, idx0.copy(collections = newMetas))
        if (!ok) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    /** 删除指定合集内某个 uri 的收藏。 */
    fun removeItem(collectionId: String, uri: String): OpResult = withLock {
        if (collectionId.isBlank() || uri.isBlank()) return@withLock OpResult.INVALID

        val idx0 = loadIndexLocked()
        val metaIndex = idx0.collections.indexOfFirst { it.id == collectionId }
        if (metaIndex < 0) return@withLock OpResult.NOT_FOUND
        val meta = idx0.collections[metaIndex]

        val items = loadItemsLocked(collectionId)
        val removedCount = items.count { it.uri == uri }
        if (removedCount <= 0) return@withLock OpResult.NOT_FOUND
        val newItems = items.filterNot { it.uri == uri }

        val now = System.currentTimeMillis()
        val newCollections = idx0.collections.toMutableList()
        newCollections[metaIndex] = meta.copy(updatedAt = now, itemCount = newItems.size)

        // Step6：维护 uriIndex（该 uri 在本合集内已被彻底移除）
        val newUriIndex = idx0.uriIndex.toMutableMap()
        val owners = newUriIndex[uri].orEmpty().toMutableList().filterNot { it == collectionId }
        if (owners.isEmpty()) newUriIndex.remove(uri) else newUriIndex[uri] = owners

        val newIndex = idx0.copy(collections = newCollections, uriIndex = newUriIndex)
        val ok = updateCollectionsAndIndexLocked(mapOf(collectionId to newItems), newIndex)
        if (!ok) return@withLock OpResult.FAILED

        OpResult.SUCCESS
    }

    /** 批量删除指定合集内多个 uri 的收藏。 */
    fun removeItems(collectionId: String, uris: Collection<String>): OpResult = withLock {
        val targets = uris.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (collectionId.isBlank() || targets.isEmpty()) return@withLock OpResult.INVALID

        val idx0 = loadIndexLocked()
        val metaIndex = idx0.collections.indexOfFirst { it.id == collectionId }
        if (metaIndex < 0) return@withLock OpResult.NOT_FOUND
        val meta = idx0.collections[metaIndex]

        val items = loadItemsLocked(collectionId)
        val newItems = items.filterNot { it.uri in targets }
        if (newItems.size == items.size) return@withLock OpResult.NOT_FOUND

        val now = System.currentTimeMillis()
        val newCollections = idx0.collections.toMutableList()
        newCollections[metaIndex] = meta.copy(updatedAt = now, itemCount = newItems.size)

        // Step6：维护 uriIndex
        val newUriIndex = idx0.uriIndex.toMutableMap()
        for (u in targets) {
            val owners = newUriIndex[u].orEmpty().toMutableList().filterNot { it == collectionId }
            if (owners.isEmpty()) newUriIndex.remove(u) else newUriIndex[u] = owners
        }

        val newIndex = idx0.copy(collections = newCollections, uriIndex = newUriIndex)
        val ok = updateCollectionsAndIndexLocked(mapOf(collectionId to newItems), newIndex)
        if (!ok) return@withLock OpResult.FAILED

        OpResult.SUCCESS
    }

    /** 批量剪切：从源合集移除多个条目，并加入目标合集顶部。目标已有同 uri 时覆盖。 */
    fun moveItems(fromCollectionId: String, uris: Collection<String>, toCollectionId: String): OpResult = withLock {
        val targets = uris.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return@withLock OpResult.INVALID
        if (fromCollectionId == toCollectionId) return@withLock OpResult.SUCCESS

        val idx0 = loadIndexLocked()
        val collections = idx0.collections.toMutableList()
        val fromIdx = collections.indexOfFirst { it.id == fromCollectionId }
        val toIdx = collections.indexOfFirst { it.id == toCollectionId }
        if (fromIdx < 0 || toIdx < 0) return@withLock OpResult.NOT_FOUND

        val fromMeta = collections[fromIdx]
        val toMeta = collections[toIdx]

        val fromItems = loadItemsLocked(fromCollectionId)
        val moving = fromItems.filter { it.uri in targets }
        if (moving.isEmpty()) return@withLock OpResult.NOT_FOUND

        val movingUris = moving.map { it.uri }.toSet()

        val toItems = loadItemsLocked(toCollectionId)
        val toRemovingCount = toItems.count { it.uri in movingUris }
        val finalToSize = toItems.size - toRemovingCount + moving.size
        if (finalToSize > MAX_ITEMS_PER_COLLECTION) return@withLock OpResult.LIMIT_COLLECTION_ITEMS

        val now = System.currentTimeMillis()

        val newFromItems = fromItems.filterNot { it.uri in movingUris }
        val newToItems = toItems.filterNot { it.uri in movingUris }.toMutableList().apply { addAll(0, moving) }

        collections[fromIdx] = fromMeta.copy(updatedAt = now, itemCount = newFromItems.size)
        collections[toIdx] = toMeta.copy(updatedAt = now, itemCount = newToItems.size)

        // Step6：维护 uriIndex
        val newUriIndex = idx0.uriIndex.toMutableMap()
        for (u in movingUris) {
            val owners = newUriIndex[u].orEmpty().toMutableSet()
            owners.remove(fromCollectionId)
            owners.add(toCollectionId)
            if (owners.isEmpty()) newUriIndex.remove(u) else newUriIndex[u] = owners.toList()
        }

        val newIndex = idx0.copy(collections = collections, uriIndex = newUriIndex)
        val ok = updateCollectionsAndIndexLocked(
            mapOf(fromCollectionId to newFromItems, toCollectionId to newToItems),
            newIndex
        )
        if (!ok) return@withLock OpResult.FAILED

        OpResult.SUCCESS
    }

    /**
     * 按 item_id 批量删除：先把 itemId 解析为合集内的 uri，再复用 [removeItems]。
     * 合集内 uri 唯一，故等价于按条目删除，且复用已有校验/索引维护逻辑。
     */
    fun removeItemsByItemId(collectionId: String, itemIds: Collection<String>): OpResult {
        if (collectionId.isBlank()) return OpResult.INVALID
        val ids = itemIds.mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }.toSet()
        if (ids.isEmpty()) return OpResult.INVALID
        val uris = collection(collectionId)?.items
            ?.filter { it.itemId in ids }
            ?.map { it.uri }
            ?: return OpResult.NOT_FOUND
        if (uris.isEmpty()) return OpResult.NOT_FOUND
        return removeItems(collectionId, uris)
    }

    /**
     * 按 item_id 批量移动：先把 itemId 解析为源合集内的 uri，再复用 [moveItems]。
     */
    fun moveItemsByItemId(fromCollectionId: String, itemIds: Collection<String>, toCollectionId: String): OpResult {
        val ids = itemIds.mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }.toSet()
        if (ids.isEmpty()) return OpResult.INVALID
        val uris = collection(fromCollectionId)?.items
            ?.filter { it.itemId in ids }
            ?.map { it.uri }
            ?: return OpResult.NOT_FOUND
        if (uris.isEmpty()) return OpResult.NOT_FOUND
        return moveItems(fromCollectionId, uris, toCollectionId)
    }

    /** 修改指定合集内某个 uri 收藏的标题。newTitle 必须非空。 */
    fun updateItemTitle(collectionId: String, uri: String, newTitle: String): OpResult = withLock {
        if (newTitle.isBlank()) return@withLock OpResult.INVALID

        val idx0 = loadIndexLocked()
        val metaIndex = idx0.collections.indexOfFirst { it.id == collectionId }
        if (metaIndex < 0) return@withLock OpResult.NOT_FOUND
        val meta = idx0.collections[metaIndex]

        val items = loadItemsLocked(collectionId)
        val itemIdx = items.indexOfFirst { it.uri == uri }
        if (itemIdx < 0) return@withLock OpResult.NOT_FOUND
        val newItems = items.toMutableList()
        newItems[itemIdx] = newItems[itemIdx].copy(title = newTitle.trim())

        val now = System.currentTimeMillis()
        val newCollections = idx0.collections.toMutableList()
        newCollections[metaIndex] = meta.copy(updatedAt = now, itemCount = newItems.size)
        val newIndex = idx0.copy(collections = newCollections)

        val ok = updateCollectionsAndIndexLocked(mapOf(collectionId to newItems), newIndex)
        if (!ok) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    /** 将条目从 [fromCollectionId] 移动到 [toCollectionId]。目标已有同 uri 则覆盖。 */
    fun moveItem(fromCollectionId: String, uri: String, toCollectionId: String): OpResult = withLock {
        if (fromCollectionId == toCollectionId) return@withLock OpResult.SUCCESS

        val idx0 = loadIndexLocked()
        val collections = idx0.collections.toMutableList()
        val fromIdx = collections.indexOfFirst { it.id == fromCollectionId }
        val toIdx = collections.indexOfFirst { it.id == toCollectionId }
        if (fromIdx < 0 || toIdx < 0) return@withLock OpResult.NOT_FOUND

        val fromMeta = collections[fromIdx]
        val toMeta = collections[toIdx]

        val fromItems = loadItemsLocked(fromCollectionId)
        val movingItems = fromItems.filter { it.uri == uri }
        if (movingItems.isEmpty()) return@withLock OpResult.NOT_FOUND

        val toItems = loadItemsLocked(toCollectionId)
        val toRemovingCount = toItems.count { it.uri == uri }
        val finalToSize = toItems.size - toRemovingCount + movingItems.size
        if (finalToSize > MAX_ITEMS_PER_COLLECTION) return@withLock OpResult.LIMIT_COLLECTION_ITEMS

        val now = System.currentTimeMillis()

        val newFromItems = fromItems.filterNot { it.uri == uri }
        val newToItems = toItems.filterNot { it.uri == uri }.toMutableList().apply { addAll(0, movingItems) }

        collections[fromIdx] = fromMeta.copy(updatedAt = now, itemCount = newFromItems.size)
        collections[toIdx] = toMeta.copy(updatedAt = now, itemCount = newToItems.size)

        // Step6：维护 uriIndex
        val newUriIndex = idx0.uriIndex.toMutableMap()
        val owners = newUriIndex[uri].orEmpty().toMutableSet()
        owners.remove(fromCollectionId)
        owners.add(toCollectionId)
        if (owners.isEmpty()) newUriIndex.remove(uri) else newUriIndex[uri] = owners.toList()

        val newIndex = idx0.copy(collections = collections, uriIndex = newUriIndex)
        val ok = updateCollectionsAndIndexLocked(
            mapOf(fromCollectionId to newFromItems, toCollectionId to newToItems),
            newIndex
        )
        if (!ok) return@withLock OpResult.FAILED

        OpResult.SUCCESS
    }

    // ------------------------------------------------------------------
    // 合集操作
    // ------------------------------------------------------------------

    /** 新建合集（默认名 [NEW_COLLECTION_NAME]）。达到数量上限返回 [OpResult.LIMIT_COLLECTIONS]。 */
    fun createCollection(name: String = NEW_COLLECTION_NAME, type: String = TYPE_SHARED): Pair<OpResult, String?> = withLock {
        val idx0 = ensureDefaultCollectionLocked(loadIndexLocked())
        if (idx0.collections.size >= MAX_COLLECTIONS) return@withLock OpResult.LIMIT_COLLECTIONS to null

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val meta = CollectionMeta(
            id = id,
            name = name.ifBlank { NEW_COLLECTION_NAME }.trim(),
            isDefault = false,
            isPreset = false,
            createdAt = now,
            updatedAt = now,
            itemCount = 0,
            type = type,
            creatorId = currentCreatorId()
        )

        // 先写空 items 文件，确保结构完整。
        if (!writeCollectionItemsLocked(id, emptyList())) return@withLock OpResult.FAILED to null

        val newCollections = idx0.collections.toMutableList().apply { add(meta) }
        val newIndex = idx0.copy(collections = newCollections)
        if (!writeIndexLocked(newIndex)) {
            // index 写失败：删除刚创建的空合集文件，避免留下孤儿文件。
            try { collectionFile(id).delete() } catch (_: Throwable) {}
            itemsCache.remove(id)
            itemsStamp.remove(id)
            cachedIndex = null
            cachedIndexStamp = Long.MIN_VALUE
            return@withLock OpResult.FAILED to null
        }

        OpResult.SUCCESS to id
    }

    /** 重命名合集。预置合集（含默认合集）均不允许改名（返回 [OpResult.NOT_ALLOWED]）。 */
    fun renameCollection(id: String, newName: String): OpResult = withLock {
        if (newName.isBlank()) return@withLock OpResult.INVALID

        var idx = loadIndexLocked()
        val pos = idx.collections.indexOfFirst { it.id == id }
        if (pos < 0) return@withLock OpResult.NOT_FOUND
        val c = idx.collections[pos]
        if (c.isPreset) return@withLock OpResult.NOT_ALLOWED

        val now = System.currentTimeMillis()
        val newCollections = idx.collections.toMutableList()
        newCollections[pos] = c.copy(name = newName.trim(), updatedAt = now)
        idx = idx.copy(collections = newCollections)
        if (!writeIndexLocked(idx)) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    /** 删除合集及其内部条目。默认合集与预置合集均不允许删除（返回 [OpResult.NOT_ALLOWED]）。 */
    fun deleteCollection(id: String): OpResult = withLock {
        val idx0 = ensureDefaultCollectionLocked(loadIndexLocked())
        val target = idx0.collections.firstOrNull { it.id == id } ?: return@withLock OpResult.NOT_FOUND
        if (target.isDefault || target.isPreset) return@withLock OpResult.NOT_ALLOWED
        // 读取一次 items 用于清理 uriIndex。
        val items = loadItemsLocked(id)
        val removingUris = items.map { it.uri }.filter { it.isNotBlank() }.toSet()

        val newCollections = idx0.collections.filterNot { it.id == id }
        val newUriIndex = idx0.uriIndex.toMutableMap()
        for (u in removingUris) {
            val owners = newUriIndex[u].orEmpty().toMutableList().filterNot { it == id }
            if (owners.isEmpty()) newUriIndex.remove(u) else newUriIndex[u] = owners
        }

        val newIndex = idx0.copy(collections = newCollections, uriIndex = newUriIndex)

        // 先写 index.json，确保“逻辑删除”先成功；后续删文件失败最多留下孤儿文件（可后续清理），不会丢数据。
        if (!writeIndexLocked(newIndex)) return@withLock OpResult.FAILED

        // 删除集合文件（best-effort）
        try { collectionFile(id).delete() } catch (_: Throwable) {}
        itemsCache.remove(id)
        itemsStamp.remove(id)

        OpResult.SUCCESS
    }

    /**
     * v1.1.125：按外部指定顺序重排合集。
     *
     * 语义与约束：
     *   1) 默认合集（isDefault）与预置合集（isPreset）位置固定，不允许被排序影响。
     *      在最终结果中，默认合集永远位于最前，其后是预置合集（保持原相对顺序），
     *      再之后才是 [orderedIds] 里出现的普通合集顺序。
     *   2) [orderedIds] 只能包含普通（非默认、非预置）合集 id。
     *      未在 [orderedIds] 中出现的普通合集会按原有相对顺序追加在末尾，
     *      保证任何未涵盖的合集都不会丢失。
     *   3) 如果 [orderedIds] 与实际合集完全无关（例如 id 全部无效），返回 [OpResult.NOT_FOUND]。
     *
     * 该方法不修改任何合集内 items，仅调整 index.json 中的顺序。
     */
    fun reorderCollections(orderedIds: List<String>): OpResult = withLock {
        val idx0 = ensureDefaultCollectionLocked(loadIndexLocked())
        val metas = idx0.collections
        if (metas.isEmpty()) return@withLock OpResult.NOT_FOUND

        val idToMeta = metas.associateBy { it.id }
        // 只关心存在且是可管理（非默认 && 非预置）的 id，去重保留首次出现顺序。
        val cleanedOrder = LinkedHashSet<String>()
        for (id in orderedIds) {
            val m = idToMeta[id] ?: continue
            if (m.isDefault || m.isPreset) continue
            cleanedOrder.add(id)
        }
        if (cleanedOrder.isEmpty()) return@withLock OpResult.NOT_FOUND

        val defaults = metas.filter { it.isDefault }
        val presets = metas.filter { !it.isDefault && it.isPreset }
        val normalOriginal = metas.filter { !it.isDefault && !it.isPreset }

        // 按 cleanedOrder 先摆放，剩余普通合集追加。
        val orderedNormal = ArrayList<CollectionMeta>(normalOriginal.size)
        val used = HashSet<String>()
        for (id in cleanedOrder) {
            val m = idToMeta[id] ?: continue
            if (m.isDefault || m.isPreset) continue
            orderedNormal.add(m)
            used.add(id)
        }
        for (m in normalOriginal) {
            if (m.id !in used) orderedNormal.add(m)
        }

        val newCollections = ArrayList<CollectionMeta>(metas.size)
        newCollections.addAll(defaults)
        newCollections.addAll(presets)
        newCollections.addAll(orderedNormal)

        // 若最终顺序与原始一致，直接成功，避免无谓 IO。
        if (newCollections.map { it.id } == metas.map { it.id }) return@withLock OpResult.SUCCESS

        val newIndex = idx0.copy(collections = newCollections)
        if (!writeIndexLocked(newIndex)) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    /**
     * v1.1.125：批量删除普通合集。
     *
     * 与 [deleteCollection] 语义一致：默认合集与预置合集会被过滤掉，不参与批量删除。
     * 返回删除成功的合集数量；即使部分失败也会尽力删除其余合集。
     */
    fun deleteCollections(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        var success = 0
        for (id in ids.toList()) {
            if (id.isBlank()) continue
            if (deleteCollection(id) == OpResult.SUCCESS) success++
        }
        return success
    }

    // ------------------------------------------------------------------
    // 云同步（Gitee）
    // ------------------------------------------------------------------

    /**
     * 导入云端合集：覆盖或新建本地合集（按 id 匹配）。
     * 用于 Gitee 云同步下载后写入本地。
     */
    fun importCloudCollection(
        id: String,
        name: String,
        type: String,
        items: List<FavoriteItem>,
        passwordHash: String = "",
        creatorId: String = ""
    ): OpResult = withLock {
        val idx0 = loadIndexLocked()
        val existingIdx = idx0.collections.indexOfFirst { it.id == id }
        val now = System.currentTimeMillis()

        val newCollections = idx0.collections.toMutableList()
        if (existingIdx >= 0) {
            // 覆盖已有合集。预置/默认合集只更新内容数量和时间，保留本地 name/type 等元信息。
            val old = newCollections[existingIdx]
            newCollections[existingIdx] = if (old.isPreset || old.isDefault) {
                old.copy(
                    updatedAt = now,
                    itemCount = items.size
                )
            } else {
                old.copy(
                    name = name,
                    type = type,
                    passwordHash = passwordHash.trim(),
                    creatorId = creatorId.ifBlank { old.creatorId },
                    updatedAt = now,
                    itemCount = items.size
                )
            }
        } else {
            // 新建
            if (newCollections.size >= MAX_COLLECTIONS) return@withLock OpResult.LIMIT_COLLECTIONS
            newCollections.add(
                CollectionMeta(
                    id = id,
                    name = name,
                    isDefault = false,
                    isPreset = false,
                    createdAt = now,
                    updatedAt = now,
                    itemCount = items.size,
                    type = type,
                    passwordHash = passwordHash.trim(),
                    creatorId = creatorId.ifBlank { currentCreatorId() }
                )
            )
        }

        // 重建 uriIndex
        // 先移除旧 uri（如果存在）
        val newUriIndex = idx0.uriIndex.toMutableMap()
        if (existingIdx >= 0) {
            // 清理旧 items 的 uriIndex 引用
            val oldItems = loadItemsLocked(id)
            for (item in oldItems) {
                if (item.uri.isNotBlank()) {
                    val owners = newUriIndex[item.uri].orEmpty().toMutableList()
                    owners.remove(id)
                    if (owners.isEmpty()) newUriIndex.remove(item.uri)
                    else newUriIndex[item.uri] = owners
                }
            }
        }
        // 添加新 items 的 uriIndex 引用
        for (item in items) {
            if (item.uri.isNotBlank()) {
                val owners = newUriIndex[item.uri].orEmpty().toMutableSet()
                owners.add(id)
                newUriIndex[item.uri] = owners.toList()
            }
        }

        val newIndex = idx0.copy(collections = newCollections, uriIndex = newUriIndex)
        val ok = updateCollectionsAndIndexLocked(mapOf(id to items), newIndex)
        if (!ok) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    /** 更新合集的 type 字段（仅管理员或合集创建者本人可操作）。 */
    fun updateCollectionType(id: String, newType: String, isAdmin: Boolean = false): OpResult = withLock {
        val idx = loadIndexLocked()
        val pos = idx.collections.indexOfFirst { it.id == id }
        if (pos < 0) return@withLock OpResult.NOT_FOUND

        val c = idx.collections[pos]
        val currentCreatorId = currentCreatorId()
        if (!isAdmin && (c.creatorId.isBlank() || c.creatorId != currentCreatorId)) {
            return@withLock OpResult.NOT_ALLOWED
        }
        val newCollections = idx.collections.toMutableList()
        newCollections[pos] = c.copy(type = newType, updatedAt = System.currentTimeMillis())
        val newIndex = idx.copy(collections = newCollections)
        if (!writeIndexLocked(newIndex)) return@withLock OpResult.FAILED
        OpResult.SUCCESS
    }

    // ------------------------------------------------------------------
    // 导入 / 导出（局域网 HTTP 传输）
    // ------------------------------------------------------------------

    /**
     * 导出当前收藏库为 CSV 文本。
     *
     * CSV 约定：
     * - 文件名：casttv_favorites.csv（由上层 HTTP 服务决定）
     * - 表头：合集名称,内容标题,资源地址
     */
    fun exportToCsv(): String = withLock {
        val idx = loadIndexLocked()
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\n")
        idx.collections.forEach { meta ->
            val items = loadItemsLocked(meta.id)
            items.forEach { item ->
                val title = item.title.ifBlank { item.uri }
                sb.append(csvEscape(meta.name)).append(',')
                    .append(csvEscape(title)).append(',')
                    .append(csvEscape(item.uri)).append("\n")
            }
        }
        sb.toString()
    }

    /** 导入校验结果：合法时携带合集/条目统计与规范化后的 JSON（内部存储用）；非法时携带原因。 */
    sealed class ImportPreview {
        data class Valid(
            val collectionCount: Int,
            val itemCount: Int,
            val normalizedJson: String
        ) : ImportPreview()

        data class Invalid(val reason: String) : ImportPreview()
    }

    /**
     * 严格校验 CSV 并转换为内部收藏结构。
     *
     * 校验规则：
     * 1) 第一行表头必须严格等于「合集名称,内容标题,资源地址」三列（英文逗号分隔，允许 BOM）。
     * 2) 数据行必须至少三列；「内容标题」「资源地址」不能为空。
     */
    fun validateImportCsv(text: String): ImportPreview {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ImportPreview.Invalid("文件内容为空")

        val lines = trimmed.splitToSequence("\n").map { it.trimEnd('\r') }.toList()
        if (lines.isEmpty()) return ImportPreview.Invalid("CSV 文件为空")

        val headerLine = lines.first().removePrefix("\uFEFF").trim()
        val headerCols = parseCsvLine(headerLine)
        if (headerCols.size != 3 || headerCols.joinToString(",") != CSV_HEADER) {
            return ImportPreview.Invalid("CSV 表头不正确，必须为：$CSV_HEADER")
        }

        // 解析数据行
        val itemsByCollectionName = linkedMapOf<String, MutableList<FavoriteItem>>()
        // v1.1.116 item_id 兼容性补全：CSV 导入（本地/HTTP 页面）显式生成稳定 item_id，
        // 避免仅依赖读取时的兜底补生成，导致早期批量指令拿不到 item_id。
        val csvImportBaseTs = System.currentTimeMillis()
        var csvGlobalIndex = 0
        var rowIndex: Int
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            rowIndex = i + 1
            val cols = parseCsvLine(line)
            if (cols.size < 3) {
                return ImportPreview.Invalid("第 $rowIndex 行列数不足 3 列")
            }
            val collectionNameRaw = cols[0].trim()
            val title = cols[1].trim()
            val uri = cols[2].trim()
            if (title.isBlank()) return ImportPreview.Invalid("第 $rowIndex 行：内容标题不能为空")
            if (uri.isBlank()) return ImportPreview.Invalid("第 $rowIndex 行：资源地址不能为空")
            val collectionName = collectionNameRaw.ifBlank { DEFAULT_COLLECTION_NAMES.firstOrNull() ?: "默认合集" }

            val list = itemsByCollectionName.getOrPut(collectionName) { mutableListOf() }
            if (list.any { it.uri == uri }) {
                continue
            }
            list.add(
                FavoriteItem(
                    // v1.1.116：显式补生成 item_id = 毫秒时间戳 16 进制 + 全局下标，
                    // 覆盖「合集列表下的本地导入」与「HTTP 页面的本地导入」两个入口。
                    itemId = java.lang.Long.toHexString(csvImportBaseTs) + "-" + csvGlobalIndex,
                    title = title,
                    uri = uri,
                    source = "",
                    time = 0L,
                    thumbPath = null
                )
            )
            csvGlobalIndex += 1
        }

        if (itemsByCollectionName.isEmpty()) return ImportPreview.Invalid("CSV 中没有有效数据行")

        val now = System.currentTimeMillis()
        val collections = itemsByCollectionName.entries.mapIndexed { index, e ->
            val id = UUID.randomUUID().toString()
            FavoriteCollection(
                id = id,
                name = e.key,
                isDefault = index == 0,
                isPreset = false,
                createdAt = now,
                updatedAt = now,
                items = e.value,
                creatorId = currentCreatorId()
            )
        }
        val lib = Library(defaultCollectionId = collections.first().id, collections = collections)
        val normalized = enforceCaps(lib)
        val itemCount = normalized.collections.sumOf { it.items.size }
        return ImportPreview.Valid(normalized.collections.size, itemCount, serializeLegacyLibrary(normalized))
    }

    /**
     * Step5：应用 CSV 导入（覆盖）：传入应为 [validateImportCsv] 返回的 normalizedJson。
     * 解析后会再次裁剪做防御；写入采用事务化目录替换，避免中途失败损坏旧数据。
     */
    fun applyImportedCsv(normalizedJson: String): OpResult = withLock {
        val lib = try { parseLegacyText(normalizedJson) } catch (_: Exception) { null } ?: return@withLock OpResult.INVALID
        if (lib.collections.isEmpty()) return@withLock OpResult.INVALID

        val enforced = enforceCaps(lib)
        val ok = writeLibraryTransactionallyLocked(enforced)
        if (!ok) return@withLock OpResult.FAILED

        // 导入完成后清缓存，确保后续读取来自新目录。
        clearCachesLocked()
        OpResult.SUCCESS
    }

    // ------------------------------------------------------------------
    // CSV helpers
    // ------------------------------------------------------------------

    /** CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号用 "" 转义。 */
    private fun csvEscape(value: String): String {
        val v = value
        val needsQuote = v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')
        if (!needsQuote) return v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    /** 解析单行 CSV（支持双引号包裹与 "" 转义），返回列数组。 */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(3)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"') {
                    // "" => "
                    val next = if (i + 1 < line.length) line[i + 1] else 0.toChar()
                    if (next == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i++
                        continue
                    }
                } else {
                    sb.append(ch)
                    i++
                    continue
                }
            } else {
                when (ch) {
                    '"' -> {
                        inQuotes = true
                        i++
                    }
                    ',' -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                        i++
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
        }
        out.add(sb.toString())
        return out
    }

    // ------------------------------------------------------------------
    // Step1：全局锁统一
    // P0-1：由 synchronized(Any) 升级为 ReentrantReadWriteLock：
    //   - 读读并发（readLock 可重入且多读并发）
    //   - 写才互斥（writeLock 独占）
    //   - 迁移一次性守卫：只有首次进入才需要拿写锁执行 legacy 迁移
    //   - 磁盘 IO 挪到锁块外：write 分为「锁内 mutate 内存 + 快照」和「锁外落盘」两步；
    //     由 diskLock 串行化多个 writer 的落盘顺序，保证 disk 顺序与 memory 顺序一致
    // ------------------------------------------------------------------

    /** 首次访问需要迁移旧版数据；用 volatile 标记，避免每次都进 writeLock。 */
    @Volatile private var migrationChecked = false

    private inline fun <T> withReadLock(block: () -> T): T {
        ensureMigrated()
        return GLOBAL_LOCK.read { block() }
    }

    private inline fun <T> withWriteLock(block: () -> T): T {
        ensureMigrated()
        return GLOBAL_LOCK.write { block() }
    }

    /**
     * 兼容旧调用点：语义等价于持有写锁（含 legacy 迁移），保守派发到 writeLock。
     * 新代码请直接使用 [withReadLock] 或 [withWriteLock]。
     */
    private inline fun <T> withLock(block: () -> T): T = withWriteLock(block)

    private fun ensureMigrated() {
        if (migrationChecked) return
        GLOBAL_LOCK.write {
            if (!migrationChecked) {
                ensureMigratedLocked()
                migrationChecked = true
            }
        }
    }

    /**
     * P0-1（1.2/1.3）：write 路径的「落盘」序列化锁。
     * 使用独立 lock 而不是 GLOBAL_LOCK.writeLock，是为了让「锁内更新内存/生成快照」
     * 与「锁外落盘」两阶段能各自并行发挥作用：
     *   - GLOBAL_LOCK.writeLock 尽可能短，只覆盖内存结构变更
     *   - diskLock 独立串行化磁盘写入，保证多 writer 之间的落盘顺序
     * 当前重构主要落地在关键读路径与内存缓存，后续新增写路径按此模式拆分。
     */
    private val diskLock = Any()

    private inline fun <T> withDiskLock(block: () -> T): T = synchronized(diskLock) { block() }

    // ------------------------------------------------------------------
    // Step2/3：index + collection 文件读写（按需加载）
    // ------------------------------------------------------------------

    private fun backupDir(): File = File(appContext.filesDir, "${FAVORITES_DIR_NAME}_backup")

    private fun ensureDirsLocked() {
        // 若上次事务写入中途失败，可能留下 favorites_backup；这里做一次兜底恢复，避免数据“看起来消失”。
        val backup = backupDir()
        if (!favoritesDir.exists() && backup.exists()) {
            try { backup.renameTo(favoritesDir) } catch (_: Throwable) {}
        }

        if (!favoritesDir.exists()) favoritesDir.mkdirs()
        if (!collectionsDir.exists()) collectionsDir.mkdirs()
    }

    private fun clearCachesLocked() {
        cachedIndex = null
        cachedIndexStamp = Long.MIN_VALUE
        itemsCache.clear()
        itemsStamp.clear()
        bumpCacheVersionLocked()
    }

    private fun isNewStoreValidLocked(): Boolean {
        if (!favoritesDir.exists()) return false
        if (!collectionsDir.exists()) return false
        if (!indexFile.exists()) return false

        return try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray(KEY_INDEX_COLLECTIONS) ?: return false
            if (arr.length() <= 0) return false

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return false
                val id = o.optString(KEY_ID)
                if (id.isBlank()) return false
                if (!collectionFile(id).exists()) return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 启动时检测旧 favorites.json 并迁移到新结构；迁移成功后删除旧文件。 */
    private fun ensureMigratedLocked() {
        if (!legacyFile.exists()) return

        // 已有新结构且看起来完整：只做 best-effort 清理旧文件，避免重复迁移。
        if (isNewStoreValidLocked()) {
            try { legacyFile.delete() } catch (_: Throwable) {}
            return
        }

        val legacyText = try { legacyFile.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
        val lib = parseLegacyText(legacyText) ?: return

        // 将迁移也走一次事务化写入，降低半截结构风险。
        val ok = writeLibraryTransactionallyLocked(lib)
        if (ok) {
            try { legacyFile.delete() } catch (_: Throwable) {}
            clearCachesLocked()
        }
    }

    private fun loadIndexLocked(): Index {
        ensureDirsLocked()

        val stamp = indexStamp()
        val c = cachedIndex
        if (c != null && stamp == cachedIndexStamp) return c

        val parsed = readIndexLocked()
        if (parsed != null) {
            val migrated = dedupePresetCollectionsLocked(migratePresetCollectionIdsLocked(parsed))
            if (migrated != parsed) writeIndexLocked(migrated)
            cachedIndex = migrated
            cachedIndexStamp = indexStamp()
            return migrated
        }

        // 首次使用或损坏：初始化默认 5 个合集，其中第 1 个为默认合集。
        val freshLib = createDefaultLegacyLibrary(migratedItems = emptyList())
        writeLibraryToCurrentDirLocked(freshLib)
        val freshIndex = readIndexLocked() ?: Index(collections = emptyList(), uriIndex = emptyMap())
        cachedIndex = freshIndex
        cachedIndexStamp = indexStamp()
        return freshIndex
    }

    private fun readIndexLocked(): Index? {
        if (!indexFile.exists()) return null
        return try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray(KEY_INDEX_COLLECTIONS) ?: return null
            val metas = ArrayList<CollectionMeta>(arr.length())
            var uriIndexMissing = !root.has(KEY_URI_INDEX)

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString(KEY_ID)
                if (id.isBlank()) continue
                metas.add(
                    CollectionMeta(
                        id = id,
                        name = o.optString(KEY_NAME).ifBlank { NEW_COLLECTION_NAME },
                        isDefault = o.optBoolean(KEY_IS_DEFAULT, false),
                        isPreset = o.optBoolean(KEY_IS_PRESET, false),
                        createdAt = o.optLong(KEY_CREATED_AT, 0L),
                        updatedAt = o.optLong(KEY_UPDATED_AT, 0L),
                        itemCount = o.optInt(KEY_ITEM_COUNT, -1),
                        type = parseCollectionType(o),
                        passwordHash = o.optString(KEY_PASSWORD_HASH, "").trim(),
                        creatorId = o.optString(KEY_CREATOR_ID, "").trim()
                    )
                )
            }
            if (metas.isEmpty()) return null

            // itemCount 兼容：若缺失（-1），按文件实际重新计算一次。
            val fixedMetas = metas.map { m ->
                val count = if (m.itemCount >= 0) m.itemCount else {
                    val items = loadItemsLocked(m.id, allowCache = false)
                    items.size
                }
                m.copy(itemCount = count)
            }

            // uriIndex 兼容：若缺失则全量重建一次（最多 20*100 条，开销可控）。
            val uriIndex = if (uriIndexMissing) {
                buildUriIndexLocked(fixedMetas)
            } else {
                parseUriIndex(root.optJSONObject(KEY_URI_INDEX))
            }

            val normalized = normalizeIndexLocked(Index(collections = fixedMetas, uriIndex = uriIndex))
            // 若规范化导致数据修正（例如缺失 default / 缺失 uriIndex），回写一次 index.json。
            if (uriIndexMissing || normalized.collections != fixedMetas) {
                writeIndexLocked(normalized)
            }
            normalized
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseUriIndex(obj: JSONObject?): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val uri = keys.next()
            val arr = obj.optJSONArray(uri) ?: continue
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) ids.add(id)
            }
            if (ids.isNotEmpty()) out[uri] = ids
        }
        return out
    }

    private fun parseCollectionType(o: JSONObject): String {
        val explicitType = o.optString(KEY_TYPE).trim()
        if (explicitType.isNotBlank()) return explicitType
        val isDefault = o.optBoolean(KEY_IS_DEFAULT, false)
        val isPreset = o.optBoolean(KEY_IS_PRESET, false)
        return if (isDefault || isPreset) TYPE_PRIVATE else TYPE_SHARED
    }

    private fun normalizeIndexLocked(index: Index): Index {
        // 过滤掉不存在的合集文件引用（防御）
        val existingIds = index.collections.map { it.id }.toSet()
        val cleanedUriIndex = index.uriIndex.mapValues { (_, ids) -> ids.filter { it in existingIds }.distinct() }
            .filterValues { it.isNotEmpty() }

        var metas = index.collections

        // 确保默认合集存在
        val defaultPos = metas.indexOfFirst { it.isDefault }
        metas = if (defaultPos >= 0) {
            metas.mapIndexed { i, m -> m.copy(isDefault = i == defaultPos) }
        } else {
            metas.mapIndexed { i, m -> m.copy(isDefault = i == 0) }
        }

        // 默认合集排在最前，其余保持原顺序
        metas = metas.sortedByDescending { it.isDefault }

        // 确保每个合集文件都存在（缺失则补空文件并修正 itemCount）
        metas.forEachIndexed { idx, m ->
            val f = collectionFile(m.id)
            if (!f.exists()) {
                writeCollectionItemsLocked(m.id, emptyList())
                metas = metas.toMutableList().also { it[idx] = m.copy(itemCount = 0) }
            }
        }

        return Index(collections = metas, uriIndex = cleanedUriIndex)
    }

    /**
     * 仅做元信息归一化（不触碰磁盘），用于事务目录生成 index.json。
     * - 过滤 uriIndex 中不存在的 collectionId
     * - 保证恰好一个默认合集，并把默认合集放到第一个
     */
    private fun normalizeIndexInMemory(index: Index): Index {
        val existingIds = index.collections.map { it.id }.toSet()
        val cleanedUriIndex = index.uriIndex.mapValues { (_, ids) -> ids.filter { it in existingIds }.distinct() }
            .filterValues { it.isNotEmpty() }

        var metas = index.collections
        val defaultPos = metas.indexOfFirst { it.isDefault }
        metas = if (defaultPos >= 0) {
            metas.mapIndexed { i, m -> m.copy(isDefault = i == defaultPos) }
        } else {
            metas.mapIndexed { i, m -> m.copy(isDefault = i == 0) }
        }
        metas = metas.sortedByDescending { it.isDefault }

        return Index(collections = metas, uriIndex = cleanedUriIndex)
    }

    private fun dedupePresetCollectionsLocked(index: Index): Index {
        val metas = index.collections
        val keepIds = linkedSetOf<String>()
        val removeIds = mutableSetOf<String>()

        PRESET_COLLECTION_FIXED_IDS.forEach { (name, fixedId) ->
            val candidates = metas.filter { it.isPreset && (it.id == fixedId || it.name == name) }
            if (candidates.isNotEmpty()) {
                val keeper = candidates.firstOrNull { it.id == fixedId } ?: candidates.first()
                keepIds.add(keeper.id)
                candidates.filter { it.id != keeper.id }.forEach { removeIds.add(it.id) }
            }
        }

        metas.filter { it.isPreset }
            .groupBy { it.name }
            .forEach { (_, group) ->
                if (group.size > 1) {
                    val keeper = group.firstOrNull { it.id in keepIds } ?: group.first()
                    keepIds.add(keeper.id)
                    group.filter { it.id != keeper.id }.forEach { removeIds.add(it.id) }
                }
            }

        if (removeIds.isEmpty()) return index

        val keptCollectionIds = metas.map { it.id }.filter { it !in removeIds }.toSet()
        removeIds.forEach { id ->
            itemsCache.remove(id)
            itemsStamp.remove(id)
            try {
                if (id !in keptCollectionIds) collectionFile(id).delete()
            } catch (_: Throwable) {}
        }
        val cleanedCollections = metas.filter { it.id !in removeIds }
        val cleanedUriIndex = index.uriIndex.mapValues { (_, ids) -> ids.filter { it !in removeIds }.distinct() }
            .filterValues { it.isNotEmpty() }
        return normalizeIndexLocked(Index(collections = cleanedCollections, uriIndex = cleanedUriIndex))
    }

    private fun migratePresetCollectionIdsLocked(index: Index): Index {
        val idMapping = mutableMapOf<String, String>()
        val migratedMetas = index.collections.map { meta ->
            val fixedId = PRESET_COLLECTION_FIXED_IDS[meta.name]
            if ((meta.isPreset || meta.isDefault) && fixedId != null && meta.id != fixedId) {
                idMapping[meta.id] = fixedId
                val oldFile = collectionFile(meta.id)
                val newFile = collectionFile(fixedId)
                try {
                    when {
                        oldFile.exists() && !newFile.exists() -> oldFile.renameTo(newFile)
                        oldFile.exists() && newFile.exists() -> oldFile.delete()
                    }
                } catch (_: Throwable) {}
                itemsCache.remove(meta.id)
                itemsStamp.remove(meta.id)
                meta.copy(id = fixedId)
            } else {
                meta
            }
        }
        if (idMapping.isEmpty()) return index
        val migratedUriIndex = index.uriIndex.mapValues { (_, ids) ->
            ids.map { idMapping[it] ?: it }.distinct()
        }
        return normalizeIndexLocked(Index(collections = migratedMetas, uriIndex = migratedUriIndex))
    }

    private fun ensureDefaultCollectionLocked(index: Index): Index {
        val hasDefault = index.collections.any { it.isDefault }
        return if (hasDefault) index else {
            val fixed = normalizeIndexLocked(index)
            writeIndexLocked(fixed)
            fixed
        }
    }

    private fun loadItemsLocked(collectionId: String, allowCache: Boolean = true): List<FavoriteItem> {
        ensureDirsLocked()
        val file = collectionFile(collectionId)
        val stamp = fileStamp(file)

        if (allowCache) {
            val cached = itemsCache[collectionId]
            val cachedStamp = itemsStamp[collectionId]
            if (cached != null && cachedStamp != null && cachedStamp == stamp) return cached
        }

        val items = if (!file.exists()) {
            emptyList()
        } else {
            try {
                val text = file.readText(Charsets.UTF_8)
                val arr = JSONArray(text)
                parseItems(arr)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        itemsCache[collectionId] = items
        itemsStamp[collectionId] = stamp
        return items
    }

    private fun writeCollectionItemsLocked(collectionId: String, items: List<FavoriteItem>): Boolean {
        ensureDirsLocked()
        val file = collectionFile(collectionId)
        return try {
            val arr = JSONArray()
            items.forEachIndexed { index, item ->
                val obj = JSONObject()
                    .put(KEY_ID, item.id)
                    .put(KEY_ITEM_ID, item.itemId.ifBlank { genFallbackItemId(index) })
                    .put(KEY_TITLE, item.title)
                    .put(KEY_URI, item.uri)
                    .put(KEY_SOURCE, item.source)
                    .put(KEY_TIME, item.time)
                item.thumbPath?.let { obj.put(KEY_THUMB_PATH, it) }
                item.artworkPath?.let { obj.put(KEY_ARTWORK_PATH, it) }
                obj.put(KEY_DURATION_MS, item.durationMs)
                if (item.description.isNotBlank()) obj.put(KEY_DESCRIPTION, item.description)
                if (item.resolution.isNotBlank()) obj.put(KEY_RESOLUTION, item.resolution)
                if (item.isLive) obj.put(KEY_IS_LIVE, true)
                arr.put(obj)
            }
            writeAtomic(file, arr.toString())

            // 刷新缓存
            itemsCache[collectionId] = items
            itemsStamp[collectionId] = fileStamp(file)
            bumpCacheVersionLocked()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeIndexLocked(index: Index): Boolean {
        ensureDirsLocked()
        return try {
            val arr = JSONArray()
            index.collections.forEach { c ->
                arr.put(
                    JSONObject()
                        .put(KEY_ID, c.id)
                        .put(KEY_NAME, c.name)
                        .put(KEY_IS_DEFAULT, c.isDefault)
                        .put(KEY_IS_PRESET, c.isPreset)
                        .put(KEY_CREATED_AT, c.createdAt)
                        .put(KEY_UPDATED_AT, c.updatedAt)
                        .put(KEY_ITEM_COUNT, c.itemCount)
                        .put(KEY_TYPE, c.type)
                        .apply {
                            if (c.passwordHash.isNotBlank()) put(KEY_PASSWORD_HASH, c.passwordHash.trim())
                            if (c.creatorId.isNotBlank()) put(KEY_CREATOR_ID, c.creatorId.trim())
                        }
                )
            }
            val uriIndexObj = JSONObject()
            index.uriIndex.forEach { (uri, ids) ->
                val idsArr = JSONArray()
                ids.forEach { idsArr.put(it) }
                uriIndexObj.put(uri, idsArr)
            }
            val root = JSONObject()
                .put(KEY_INDEX_SCHEMA_VERSION, INDEX_SCHEMA_VERSION)
                .put(KEY_INDEX_COLLECTIONS, arr)
                .put(KEY_URI_INDEX, uriIndexObj)

            writeAtomic(indexFile, root.toString())

            cachedIndex = index
            cachedIndexStamp = indexStamp()
            bumpCacheVersionLocked()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 小事务提交：先写入所有变更的合集文件，再写入 index.json。
     * 若 index 写入失败，则尽最大努力回滚刚才写入的合集文件与 index 文件内容，避免数据半成功。
     */
    private fun updateCollectionsAndIndexLocked(
        changedCollections: Map<String, List<FavoriteItem>>,
        newIndex: Index
    ): Boolean {
        ensureDirsLocked()

        // 备份旧 index 文本（用于回滚）；失败时允许为 null（回滚尽力而为）。
        val oldIndexText = try { if (indexFile.exists()) indexFile.readText(Charsets.UTF_8) else null } catch (_: Throwable) { null }

        // 备份旧合集数据（按需读取）。
        val oldItems = mutableMapOf<String, List<FavoriteItem>>()
        for (cid in changedCollections.keys) {
            oldItems[cid] = loadItemsLocked(cid, allowCache = false)
        }

        val written = mutableListOf<String>()
        for ((cid, items) in changedCollections) {
            if (!writeCollectionItemsLocked(cid, items)) {
                // 回滚已写入的合集
                for (w in written.asReversed()) {
                    val rollback = oldItems[w] ?: emptyList()
                    writeCollectionItemsLocked(w, rollback)
                }
                // index 未写入，不需要回滚 index
                cachedIndex = null
                cachedIndexStamp = Long.MIN_VALUE
                return false
            }
            written.add(cid)
        }

        if (!writeIndexLocked(newIndex)) {
            // 回滚合集
            for (w in written.asReversed()) {
                val rollback = oldItems[w] ?: emptyList()
                writeCollectionItemsLocked(w, rollback)
            }
            // 回滚 index
            try {
                if (oldIndexText != null) {
                    writeAtomic(indexFile, oldIndexText)
                } else {
                    if (indexFile.exists()) indexFile.delete()
                }
            } catch (_: Throwable) {}

            cachedIndex = null
            cachedIndexStamp = Long.MIN_VALUE
            return false
        }

        return true
    }

    private fun buildUriIndexLocked(metas: List<CollectionMeta>): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        metas.forEach { meta ->
            val items = loadItemsLocked(meta.id, allowCache = false)
            items.forEach { item ->
                val uri = item.uri
                if (uri.isNotBlank()) map.getOrPut(uri) { mutableSetOf() }.add(meta.id)
            }
        }
        return map.mapValues { it.value.toList() }
    }

    // ------------------------------------------------------------------
    // Step5：事务化写入（favorites_tmp -> favorites）
    // ------------------------------------------------------------------

    private fun writeLibraryTransactionallyLocked(lib: Library): Boolean {
        // 事务目录：filesDir/favorites_tmp
        val tmpFavoritesDir = tmpDir
        val tmpIndex = File(tmpFavoritesDir, INDEX_FILE_NAME)
        val tmpCollectionsDir = File(tmpFavoritesDir, COLLECTIONS_DIR_NAME)
        val backup = backupDir()

        // 1) 清理 tmp：清理失败直接返回，避免误覆盖。
        try {
            if (tmpFavoritesDir.exists() && !tmpFavoritesDir.deleteRecursively()) return false
        } catch (_: Throwable) {
            return false
        }

        var backupCreated = false

        return try {
            tmpFavoritesDir.mkdirs()
            tmpCollectionsDir.mkdirs()

            // 2) 写入 tmp 的 collection files + 构造 index 元信息与 uriIndex
            val metas = mutableListOf<CollectionMeta>()
            val uriIndex = mutableMapOf<String, MutableSet<String>>()
            lib.collections.forEach { c ->
                val f = File(tmpCollectionsDir, "${c.id}.json")
                val arr = JSONArray()
                c.items.forEachIndexed { index, item ->
                    val obj = JSONObject()
                        .put(KEY_ID, item.id)
                        .put(KEY_ITEM_ID, item.itemId.ifBlank { genFallbackItemId(index) })
                        .put(KEY_TITLE, item.title)
                        .put(KEY_URI, item.uri)
                        .put(KEY_SOURCE, item.source)
                        .put(KEY_TIME, item.time)
                    item.thumbPath?.let { obj.put(KEY_THUMB_PATH, it) }
                    item.artworkPath?.let { obj.put(KEY_ARTWORK_PATH, it) }
                    obj.put(KEY_DURATION_MS, item.durationMs)
                    if (item.description.isNotBlank()) obj.put(KEY_DESCRIPTION, item.description)
                    if (item.resolution.isNotBlank()) obj.put(KEY_RESOLUTION, item.resolution)
                    if (item.isLive) obj.put(KEY_IS_LIVE, true)
                    arr.put(obj)

                    if (item.uri.isNotBlank()) uriIndex.getOrPut(item.uri) { mutableSetOf() }.add(c.id)
                }
                writeAtomic(f, arr.toString())
                metas.add(
                    CollectionMeta(
                        id = c.id,
                        name = c.name,
                        isDefault = c.isDefault,
                        isPreset = c.isPreset,
                        createdAt = c.createdAt,
                        updatedAt = c.updatedAt,
                        itemCount = c.items.size,
                        type = c.type,
                        passwordHash = c.passwordHash.trim(),
                        creatorId = c.creatorId.trim()
                    )
                )
            }

            // 默认合集排前（仅内存归一化，不触碰磁盘）
            val normalized = normalizeIndexInMemory(Index(metas, uriIndex.mapValues { it.value.toList() }))

            // 3) 写 index.json 到 tmp
            val arrMeta = JSONArray()
            normalized.collections.forEach { c ->
                arrMeta.put(
                    JSONObject()
                        .put(KEY_ID, c.id)
                        .put(KEY_NAME, c.name)
                        .put(KEY_IS_DEFAULT, c.isDefault)
                        .put(KEY_IS_PRESET, c.isPreset)
                        .put(KEY_CREATED_AT, c.createdAt)
                        .put(KEY_UPDATED_AT, c.updatedAt)
                        .put(KEY_ITEM_COUNT, c.itemCount)
                        .put(KEY_TYPE, c.type)
                        .apply {
                            if (c.passwordHash.isNotBlank()) put(KEY_PASSWORD_HASH, c.passwordHash.trim())
                            if (c.creatorId.isNotBlank()) put(KEY_CREATOR_ID, c.creatorId.trim())
                        }
                )
            }
            val uriIndexObj = JSONObject()
            normalized.uriIndex.forEach { (uri, ids) ->
                val idsArr = JSONArray()
                ids.forEach { idsArr.put(it) }
                uriIndexObj.put(uri, idsArr)
            }
            val root = JSONObject()
                .put(KEY_INDEX_SCHEMA_VERSION, INDEX_SCHEMA_VERSION)
                .put(KEY_INDEX_COLLECTIONS, arrMeta)
                .put(KEY_URI_INDEX, uriIndexObj)
            writeAtomic(tmpIndex, root.toString())

            // 4) 目录级原子替换（尽最大努力保证失败可回滚）
            // 清理旧 backup（若清理失败，直接失败返回，避免 rename 行为不确定）
            if (backup.exists()) {
                if (!backup.deleteRecursively()) return false
            }

            // 备份旧 favorites（必须成功；失败不得删除旧数据）
            if (favoritesDir.exists()) {
                if (!favoritesDir.renameTo(backup)) return false
                backupCreated = true
            }

            // tmp -> favorites 失败必须回滚
            if (!tmpFavoritesDir.renameTo(favoritesDir)) {
                try { if (favoritesDir.exists()) favoritesDir.deleteRecursively() } catch (_: Throwable) {}
                if (backupCreated && backup.exists() && !favoritesDir.exists()) {
                    try { backup.renameTo(favoritesDir) } catch (_: Throwable) {}
                }
                try { if (tmpFavoritesDir.exists()) tmpFavoritesDir.deleteRecursively() } catch (_: Throwable) {}
                return false
            }

            // 成功：清理 backup（best-effort）
            try { if (backup.exists()) backup.deleteRecursively() } catch (_: Throwable) {}
            true
        } catch (_: Throwable) {
            // 异常兜底：若 favorites 不存在但 backup 存在，尽力恢复
            if (!favoritesDir.exists() && backupCreated && backup.exists()) {
                try { backup.renameTo(favoritesDir) } catch (_: Throwable) {}
            }
            try { if (tmpFavoritesDir.exists()) tmpFavoritesDir.deleteRecursively() } catch (_: Throwable) {}
            false
        }
    }

    /** 将 Library 写入当前 favorites 目录（非事务，仅用于初始化兜底）。 */
    private fun writeLibraryToCurrentDirLocked(lib: Library): Boolean {
        // 复用事务写入（逻辑更稳）；只是不清理 legacy。
        return writeLibraryTransactionallyLocked(lib)
    }

    // ------------------------------------------------------------------
    // 旧 favorites.json（schema v2）解析/序列化：仅用于迁移与导入
    // ------------------------------------------------------------------

    private data class Library(
        val defaultCollectionId: String,
        val collections: List<FavoriteCollection>
    )

    /** 从任意 JSON 文本解析为 [Library]（支持 schema v2 对象与旧版扁平数组）；无法解析返回 null。 */
    private fun parseLegacyText(text: String): Library? {
        val t = text.trim()
        if (t.isBlank()) return null
        return when {
            t.startsWith("{") -> parseLegacyObject(JSONObject(t))
            t.startsWith("[") -> migrateLegacyArray(JSONArray(t))
            else -> null
        }
    }

    private fun parseLegacyObject(root: JSONObject): Library? {
        val arr = root.optJSONArray(KEY_COLLECTIONS) ?: return null
        val collections = ArrayList<FavoriteCollection>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString(KEY_ID)
            if (id.isBlank()) continue
            collections.add(
                FavoriteCollection(
                    id = id,
                    name = o.optString(KEY_NAME).ifBlank { NEW_COLLECTION_NAME },
                    isDefault = o.optBoolean(KEY_IS_DEFAULT, false),
                    isPreset = o.optBoolean(KEY_IS_PRESET, false),
                    createdAt = o.optLong(KEY_CREATED_AT, 0L),
                    updatedAt = o.optLong(KEY_UPDATED_AT, 0L),
                    items = parseItems(o.optJSONArray(KEY_ITEMS)),
                    type = parseCollectionType(o),
                    passwordHash = o.optString(KEY_PASSWORD_HASH, "").trim(),
                    creatorId = o.optString(KEY_CREATOR_ID, "").trim()
                )
            )
        }
        if (collections.isEmpty()) return null
        var defaultId = root.optString(KEY_DEFAULT_COLLECTION_ID)
        if (collections.none { it.id == defaultId }) {
            val marked = collections.firstOrNull { it.isDefault }
            defaultId = marked?.id ?: collections.first().id
        }
        val normalized = collections.map { it.copy(isDefault = it.id == defaultId) }
            .sortedByDescending { it.id == defaultId }
        return Library(defaultCollectionId = defaultId, collections = normalized)
    }

    /**
     * 生成兜底的稳定 item_id：毫秒时间戳 16 进制 + 列表下标，保证同一批次内唯一。
     * 用于旧数据无 item_id、CSV 导入等场景的补生成。
     */
    private fun genFallbackItemId(index: Int): String =
        java.lang.Long.toHexString(System.currentTimeMillis()) + "-" + index

    private fun parseItems(arr: JSONArray?): List<FavoriteItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<FavoriteItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString(KEY_URI)
            if (uri.isBlank()) continue
            list.add(
                FavoriteItem(
                    id = o.optString(KEY_ID).takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    // 旧数据兼容：item_id 为空时用「当前时间戳 16 进制 + 列表下标」补生成，保证唯一。
                    itemId = o.optString(KEY_ITEM_ID).takeIf { it.isNotBlank() } ?: genFallbackItemId(i),
                    title = o.optString(KEY_TITLE),
                    uri = uri,
                    source = o.optString(KEY_SOURCE),
                    time = o.optLong(KEY_TIME, 0L),
                    thumbPath = o.optString(KEY_THUMB_PATH).takeIf { it.isNotBlank() },
                    artworkPath = o.optString(KEY_ARTWORK_PATH).takeIf { it.isNotBlank() },
                    durationMs = o.optLong(KEY_DURATION_MS, 0L),
                    description = o.optString(KEY_DESCRIPTION),
                    resolution = o.optString(KEY_RESOLUTION),
                    isLive = o.optBoolean(KEY_IS_LIVE, false)
                )
            )
        }
        return list
    }

    /** 旧版扁平数组迁移：全部并入默认合集。 */
    private fun migrateLegacyArray(arr: JSONArray): Library {
        val legacy = parseItems(arr).take(MAX_ITEMS_PER_COLLECTION)
        return createDefaultLegacyLibrary(migratedItems = legacy)
    }

    private fun createDefaultLegacyLibrary(migratedItems: List<FavoriteItem>): Library {
        val now = System.currentTimeMillis()
        val collections = ArrayList<FavoriteCollection>(DEFAULT_COLLECTION_COUNT)
        DEFAULT_COLLECTION_NAMES.forEachIndexed { index, name ->
            val isDefault = index == 0
            collections.add(
                FavoriteCollection(
                    id = PRESET_COLLECTION_FIXED_IDS[name] ?: UUID.randomUUID().toString(),
                    name = name,
                    isDefault = isDefault,
                    isPreset = true,
                    createdAt = now,
                    updatedAt = now,
                    items = if (isDefault) migratedItems else emptyList(),
                    type = TYPE_PRIVATE,
                    creatorId = ""
                )
            )
        }
        return Library(defaultCollectionId = collections.first().id, collections = collections)
    }

    /** 将 [Library] 序列化为旧版单文件 JSON 字符串（schema v2）。仅用于导入 preview 的 normalizedJson。 */
    private fun serializeLegacyLibrary(lib: Library): String {
        val arr = JSONArray()
        lib.collections.forEach { c ->
            val itemsArr = JSONArray()
            c.items.forEach { item ->
                val obj = JSONObject()
                    .put(KEY_ID, item.id)
                    .put(KEY_TITLE, item.title)
                    .put(KEY_URI, item.uri)
                    .put(KEY_SOURCE, item.source)
                    .put(KEY_TIME, item.time)
                item.thumbPath?.let { obj.put(KEY_THUMB_PATH, it) }
                item.artworkPath?.let { obj.put(KEY_ARTWORK_PATH, it) }
                obj.put(KEY_DURATION_MS, item.durationMs)
                if (item.description.isNotBlank()) obj.put(KEY_DESCRIPTION, item.description)
                if (item.resolution.isNotBlank()) obj.put(KEY_RESOLUTION, item.resolution)
                if (item.isLive) obj.put(KEY_IS_LIVE, true)
                itemsArr.put(obj)
            }
            arr.put(
                JSONObject()
                    .put(KEY_ID, c.id)
                    .put(KEY_NAME, c.name)
                    .put(KEY_IS_DEFAULT, c.isDefault)
                    .put(KEY_IS_PRESET, c.isPreset)
                    .put(KEY_CREATED_AT, c.createdAt)
                    .put(KEY_UPDATED_AT, c.updatedAt)
                    .put(KEY_ITEMS, itemsArr)
                    .apply {
                        if (c.passwordHash.isNotBlank()) put(KEY_PASSWORD_HASH, c.passwordHash.trim())
                        if (c.creatorId.isNotBlank()) put(KEY_CREATOR_ID, c.creatorId.trim())
                    }
            )
        }
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, LEGACY_SCHEMA_VERSION)
            .put(KEY_DEFAULT_COLLECTION_ID, lib.defaultCollectionId)
            .put(KEY_COLLECTIONS, arr)
        return root.toString()
    }

    /** 按上限裁剪导入库：合集数量、单合集条目、总条目均不超过约束，并确保默认合集有效且置顶。 */
    private fun enforceCaps(lib: Library): Library {
        var collections = lib.collections
        if (collections.size > MAX_COLLECTIONS) collections = collections.take(MAX_COLLECTIONS)
        var remaining = MAX_TOTAL_FAVORITES
        val capped = collections.map { c ->
            val allowed = minOf(c.items.size, MAX_ITEMS_PER_COLLECTION, remaining).coerceAtLeast(0)
            remaining -= allowed
            c.copy(items = c.items.take(allowed))
        }
        val defaultId = if (capped.any { it.id == lib.defaultCollectionId }) {
            lib.defaultCollectionId
        } else {
            capped.firstOrNull { it.isDefault }?.id ?: capped.firstOrNull()?.id ?: lib.defaultCollectionId
        }
        val normalized = capped.map { it.copy(isDefault = it.id == defaultId) }
            .sortedByDescending { it.id == defaultId }
        return Library(defaultCollectionId = defaultId, collections = normalized)
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    private fun fileStamp(file: File): Long =
        if (file.exists()) file.lastModified() * 31 + file.length() else -1L

    /** 原子写入单文件：先写临时文件再 rename 覆盖，避免中途留下半截文件。 */
    private fun writeAtomic(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        // Step1：进程内全局锁（所有实例共用）
        // P0-1：升级为读写锁，读读并发、写才互斥。
        private val GLOBAL_LOCK = ReentrantReadWriteLock()

        private const val LEGACY_FILE_NAME = "favorites.json"
        private const val LEGACY_SCHEMA_VERSION = 2

        private const val FAVORITES_DIR_NAME = "favorites"
        private const val TMP_DIR_NAME = "favorites_tmp"
        private const val INDEX_FILE_NAME = "index.json"
        private const val COLLECTIONS_DIR_NAME = "collections"
        private const val INDEX_SCHEMA_VERSION = 1

        /** 总收藏上限。 */
        const val MAX_TOTAL_FAVORITES = 2000
        /** 合集数量上限。 */
        const val MAX_COLLECTIONS = 50
        /** 单合集条目上限。 */
        const val MAX_ITEMS_PER_COLLECTION = 300
        /** 初始默认合集数量（含 1 个默认合集）。 */
        const val DEFAULT_COLLECTION_COUNT = 5
        /** 新建合集默认名。 */
        const val NEW_COLLECTION_NAME = "新合集"

        /** 合集类型常量。 */
        const val TYPE_SHARED = "shared"
        const val TYPE_PRIVATE = "private"

        /** CSV 导入/导出表头（英文逗号分隔）。 */
        private const val CSV_HEADER = "合集名称,内容标题,资源地址"

        /** 首次初始化的 5 个合集名称，第 1 个为默认合集。 */
        private val DEFAULT_COLLECTION_NAMES = listOf(
            "默认合集",
            "电影剧场版",
            "动画番剧",
            "综艺娱乐",
            "音乐现场"
        )

        private val PRESET_COLLECTION_FIXED_IDS = mapOf(
            "默认合集" to "preset-00000000-0000-0000-0000-000000000001",
            "动画番剧" to "preset-00000000-0000-0000-0000-000000000002",
            "综艺娱乐" to "preset-00000000-0000-0000-0000-000000000003",
            "音乐现场" to "preset-00000000-0000-0000-0000-000000000004",
            "电影剧场版" to "preset-00000000-0000-0000-0000-000000000005"
        )

        // index.json keys
        private const val KEY_INDEX_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_INDEX_COLLECTIONS = "collections"
        private const val KEY_URI_INDEX = "uriIndex"
        private const val KEY_ITEM_COUNT = "itemCount"

        // legacy favorites.json keys
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_DEFAULT_COLLECTION_ID = "defaultCollectionId"
        private const val KEY_COLLECTIONS = "collections"

        // common keys
        private const val KEY_ID = "id"
        private const val KEY_ITEM_ID = "item_id"
        private const val KEY_NAME = "name"
        private const val KEY_IS_DEFAULT = "isDefault"
        private const val KEY_IS_PRESET = "isPreset"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_ITEMS = "items"
        private const val KEY_TITLE = "title"
        private const val KEY_URI = "uri"
        private const val KEY_SOURCE = "source"
        private const val KEY_TIME = "time"
        private const val KEY_THUMB_PATH = "thumbPath"
        private const val KEY_ARTWORK_PATH = "artworkPath"
        private const val KEY_DURATION_MS = "durationMs"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_IS_LIVE = "isLive"
        private const val KEY_TYPE = "type"
        private const val KEY_PASSWORD_HASH = "passwordHash"
        private const val KEY_CREATOR_ID = "creatorId"
    }
}

/**
 * 收藏卡片缩略图取值口径统一入口（与历史侧口径一致）：
 *  优先展示 DLNA 元数据封面（[FavoritesStore.FavoriteItem.artworkPath]），
 *  其次回落到本地截图（[FavoritesStore.FavoriteItem.thumbPath]）。
 * 两者都要求路径非空且文件真实存在，否则返回 null（UI 层展示默认兜底图）。
 */
fun FavoritesStore.FavoriteItem.displayThumbPath(): String? =
    artworkPath?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
        ?: thumbPath?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
