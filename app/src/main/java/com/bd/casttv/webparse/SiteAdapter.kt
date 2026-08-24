package com.bd.casttv.webparse

// 数据模型
data class ParsedEpisode(val name: String, val playPageUrl: String, var resolvedUrl: String? = null)
data class ParsedSource(val name: String, val episodes: List<ParsedEpisode>)

data class ParsedMovie(
    val title: String,
    val coverUrl: String,
    val description: String,
    val category: String,
    val year: String,
    val area: String,
    val director: String,
    val actors: String,
    val sources: List<ParsedSource>
)

interface SiteAdapter {
    fun canHandle(url: String, html: String): Boolean
    suspend fun parseDetail(url: String, html: String): ParsedMovie
    suspend fun resolvePlayUrl(playPageUrl: String): String?
}

enum class ParseStep { RECEIVED, FETCHING_HTML, PARSING_INFO, LOADING_DONE, ERROR }

data class ParseProgress(
    val step: ParseStep,
    val message: String = "",
    val error: String = ""
)

/** 页面类型：列表页 / 详情页 */
enum class ParsePageKind { LIST, DETAIL }

/** 网页框架类型 */
enum class WebFrameworkType(val displayName: String) {
    MAC_CMS("MacCMS"),
    ZY_PLAYER("ZyPlayer"),
    NEMO("Nemo"),
    SNAIL_CMS("SnailCMS"),
    GENERIC("通用"),
    CUSTOM("自定义"),
    UNKNOWN("未知")
}

/** 适配器来源类型 */
enum class AdapterKind { BUILT_IN, CUSTOM_JSON }

/** 适配器元信息 */
data class AdapterInfo(
    val id: String,
    val name: String,
    val kind: AdapterKind,
    val frameworkType: WebFrameworkType,
    val supportedPageKinds: Set<ParsePageKind>,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val description: String = ""
)

/** 适配器选择结果 */
data class AdapterSelectResult(
    val adapterInfo: AdapterInfo,
    val detectedFramework: WebFrameworkType,
    val source: SelectSource,
    val confidence: Float = 1f
) {
    enum class SelectSource { DOMAIN_BINDING, FRAMEWORK_MATCH, CUSTOM_JSON_MATCH, GENERIC_FALLBACK }
}
