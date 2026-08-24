package com.bd.casttv.webparse

object BuiltInAdapters {
    const val ID_LIST_GENERIC = "built_in_list_generic"
    const val ID_LIST_MAC_CMS = "built_in_list_maccms"
    const val ID_DETAIL_GENERIC = "built_in_detail_generic"
    const val ID_DETAIL_MAC_CMS = "built_in_detail_maccms"

    val all: List<AdapterInfo> = listOf(
        AdapterInfo(
            id = ID_LIST_GENERIC,
            name = "通用列表解析",
            kind = AdapterKind.BUILT_IN,
            frameworkType = WebFrameworkType.GENERIC,
            supportedPageKinds = setOf(ParsePageKind.LIST),
            priority = 50,
            description = "通用宽松正则，适用于大多数影视列表页"
        ),
        AdapterInfo(
            id = ID_LIST_MAC_CMS,
            name = "MacCMS 列表解析",
            kind = AdapterKind.BUILT_IN,
            frameworkType = WebFrameworkType.MAC_CMS,
            supportedPageKinds = setOf(ParsePageKind.LIST),
            priority = 80,
            description = "针对 MacCMS/苹果CMS 框架优化的列表页解析"
        ),
        AdapterInfo(
            id = ID_DETAIL_GENERIC,
            name = "通用详情解析",
            kind = AdapterKind.BUILT_IN,
            frameworkType = WebFrameworkType.GENERIC,
            supportedPageKinds = setOf(ParsePageKind.DETAIL),
            priority = 50,
            description = "通用详情页播放地址提取"
        ),
        AdapterInfo(
            id = ID_DETAIL_MAC_CMS,
            name = "MacCMS 详情解析",
            kind = AdapterKind.BUILT_IN,
            frameworkType = WebFrameworkType.MAC_CMS,
            supportedPageKinds = setOf(ParsePageKind.DETAIL),
            priority = 80,
            description = "针对 MacCMS/苹果CMS 框架优化的详情页解析"
        )
    )

    fun forPageKind(kind: ParsePageKind) = all.filter { kind in it.supportedPageKinds }
    fun findById(id: String) = all.firstOrNull { it.id == id }
}
