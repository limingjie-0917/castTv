package com.bd.casttv.favorites

import com.bd.casttv.util.NaturalSorter

/**
 * 频道聚合模型（仅作用于首页「自定义 Tab」的展示层，不改动收藏数据本身）。
 *
 * 设计要点：
 * - 频道 = 一组「标题归一后相同」的资源；用户在自定义 Tab 里看到的是频道，而不是每条源。
 * - 每个频道保留一个主源 + 多个备源；重复源作为备源保留，不删除（后续可能恢复）。
 * - 异常源仅打标，不参与播放，但仍保留在频道内。
 * - 无可用源的频道仍然展示，只是不可播放。
 */

/** 频道下的单个源（对应一条收藏资源）。 */
data class TabChannelSource(
    val item: FavoritesStore.FavoriteItem,
    val isPrimary: Boolean,
    val abnormal: Boolean,
    val health: SourceHealthRecord = SourceHealthRecord.UNKNOWN,
) {
    /** 源身份：使用收藏项内部唯一 id，聚合/配置均以此为准。 */
    val sourceId: String get() = item.id

    /** 可播放 = 未打标异常且有有效地址。 */
    val playable: Boolean get() = !abnormal && item.uri.isNotBlank()
}

/** 聚合后的频道；sources 已按「主源优先 → 正常源 → 异常源」排序。 */
data class TabChannel(
    val channelKey: String,
    val displayName: String,
    val sources: List<TabChannelSource>,
) {
    /** 当前用于播放/预览的源：优先用户主源，其次第一个可播放源。 */
    val activeSource: TabChannelSource?
        get() = sources.firstOrNull { it.isPrimary && it.playable }
            ?: sources.firstOrNull { it.playable }

    val playableSources: List<TabChannelSource> get() = sources.filter { it.playable }
    val hasPlayable: Boolean get() = playableSources.isNotEmpty()
    val sourceCount: Int get() = sources.size
    val abnormalCount: Int get() = sources.count { it.abnormal }
}

/** 单个频道的用户自定义配置（主源指定 + 异常打标）。 */
data class ChannelOverride(
    val primaryId: String?,
    val abnormalIds: Set<String>,
)

/**
 * 频道名归一：把「同一频道的不同写法」映射为同一个 key。
 *
 * 第一期保持保守：只做大小写、全半角、空格标点、清晰度后缀的归一，
 * 不做激进的中文别名合并（如「中央一套」↔「CCTV-1」），避免误合并；
 * 别名映射留待第二期。
 */
object ChannelNormalizer {

    private val QUALITY = Regex(
        "(超高清|高清|超清|标清|蓝光|hdr|uhd|fhd|hd|sd|4k|2k|1080p?|720p?|576p?|540p?|480p?|h?265|h?264)",
        RegexOption.IGNORE_CASE,
    )
    private val NON_KEY = Regex("[^0-9a-z\\u4e00-\\u9fa5]")

    fun normalize(title: String): String {
        if (title.isBlank()) return ""
        val half = toHalfWidth(title).lowercase().trim()
        var s = QUALITY.replace(half, "")
        s = NON_KEY.replace(s, "")
        // 归一后若被清空（例如标题本身就是清晰度词），退回到去标点的原始串兜底。
        return s.ifBlank { NON_KEY.replace(half, "") }.ifBlank { half }
    }

    private fun toHalfWidth(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when {
                    c.code == 0x3000 -> ' '
                    c.code in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}

/** 把合集资源聚合为频道列表，并套用用户的主源/异常配置。 */
object TabChannelAggregator {

    fun aggregate(
        items: List<FavoritesStore.FavoriteItem>,
        overrides: Map<String, ChannelOverride> = emptyMap(),
        health: Map<String, SourceHealthRecord> = emptyMap(),
    ): List<TabChannel> {
        val order = ArrayList<String>()
        val groups = LinkedHashMap<String, MutableList<FavoritesStore.FavoriteItem>>()
        val seenUri = HashSet<String>()

        for (item in items) {
            // 同一条 URL 完全重复的只保留第一次出现（保守去重，不动带参数的不同地址）。
            val uriKey = item.uri.trim()
            if (uriKey.isNotEmpty() && !seenUri.add(uriKey)) continue

            val key = ChannelNormalizer.normalize(item.title).ifBlank { item.id }
            groups.getOrPut(key) { order.add(key); ArrayList() }.add(item)
        }

        val channels = ArrayList<TabChannel>(groups.size)
        for (key in order) {
            val groupItems = groups[key] ?: continue
            val override = overrides[key]
            val abnormalIds = override?.abnormalIds ?: emptySet()

            fun sortScore(record: SourceHealthRecord): Int {
                return if (record.status == HealthStatus.UNKNOWN) SourceHealthRecord.UNKNOWN.score else record.score
            }

            fun healthOf(item: FavoritesStore.FavoriteItem): SourceHealthRecord {
                return health[item.uri.trim()] ?: SourceHealthRecord.UNKNOWN
            }

            fun healthScore(item: FavoritesStore.FavoriteItem): Int = sortScore(healthOf(item))

            val configuredPrimary = override?.primaryId
            val candidates = groupItems.filter { it.id !in abnormalIds && it.uri.isNotBlank() }
            val chosenPrimaryId = when {
                configuredPrimary != null && candidates.any { it.id == configuredPrimary } -> configuredPrimary
                else -> {
                    var best: FavoritesStore.FavoriteItem? = null
                    var bestScore = Int.MIN_VALUE
                    for (item in candidates) {
                        val s = healthScore(item)
                        if (best == null || s > bestScore) {
                            best = item
                            bestScore = s
                        }
                    }
                    best?.id
                }
            }

            val sources = groupItems
                .map {
                    TabChannelSource(
                        item = it,
                        isPrimary = it.id == chosenPrimaryId,
                        abnormal = it.id in abnormalIds,
                        health = healthOf(it),
                    )
                }
                // stable sort：主源置顶 → 其余按健康分降序 → 异常源沉底（同分保持原始顺序）。
                .sortedWith { a, b ->
                    when {
                        a.isPrimary != b.isPrimary -> if (a.isPrimary) -1 else 1
                        a.abnormal != b.abnormal -> if (a.abnormal) 1 else -1
                        else -> {
                            val sa = sortScore(a.health)
                            val sb = sortScore(b.health)
                            if (sa != sb) sb - sa else 0
                        }
                    }
                }

            val display = (sources.firstOrNull { it.isPrimary } ?: sources.firstOrNull())
                ?.item?.title
                ?: groupItems.first().title

            channels.add(TabChannel(channelKey = key, displayName = display, sources = sources))
        }
        // 按频道 displayName 自然排序正序（第1集→第30集），与收藏页 sortedFavoriteItems 行为一致。
        // 修复：addToCollection 每条目 add(0,item) 导致磁盘存储倒序，自定义Tab此前未做排序，
        //       因此呈现 30→1；收藏页通过自然排序纠正为 1→30。此处统一策略，确保两端展示一致。
        channels.sortWith { a, b ->
            val cmp = NaturalSorter.compare(a.displayName, b.displayName)
            if (cmp != 0) cmp else a.channelKey.compareTo(b.channelKey)
        }
        return channels
    }
}
