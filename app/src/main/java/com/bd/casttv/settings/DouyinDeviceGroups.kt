package com.bd.casttv.settings

import com.bd.casttv.dlna.DeviceIdentity

/**
 * 抖音投屏适配可选设备名称组。
 *
 * 每组包含若干「同族备选身份」（friendlyName / manufacturer / modelName …），
 * 用户切换到某一组时，默认使用 [DouyinDeviceGroup.defaultIndex] 指定的成员；
 * 若还是搜不到设备，可让用户在组内轮换其他成员或换到另一组。
 *
 * 覆盖抖音识别度较高的国内主流电视 5 家 + 主流投影 4 家 + 通用 DLNA 兜底 1 组，
 * 共 10 组。
 */
data class DouyinDeviceGroup(
    val id: String,
    val label: String,
    val members: List<DeviceIdentity>,
    val defaultIndex: Int = 0
) {
    fun memberAt(index: Int): DeviceIdentity =
        members[index.coerceIn(0, members.size - 1)]
}

object DouyinDeviceGroups {

    val ALL: List<DouyinDeviceGroup> = listOf(
        // ---------------- 国内主流电视 5 组 ----------------
        DouyinDeviceGroup(
            id = "xiaomi",
            label = "小米电视组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "小米电视",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "https://www.mi.com",
                    modelName = "Xiaomi TV",
                    modelDescription = "Xiaomi TV DLNA Media Renderer",
                    modelNumber = "MiTV"
                ),
                DeviceIdentity(
                    friendlyName = "Redmi 电视",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "https://www.mi.com",
                    modelName = "Redmi TV",
                    modelDescription = "Redmi Smart TV DLNA Media Renderer",
                    modelNumber = "RedmiTV"
                ),
                DeviceIdentity(
                    friendlyName = "小米盒子",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "https://www.mi.com",
                    modelName = "MI Box",
                    modelDescription = "Xiaomi Mi Box DLNA Media Renderer",
                    modelNumber = "MiBox"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "huawei",
            label = "华为智慧屏组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "华为智慧屏",
                    manufacturer = "Huawei",
                    manufacturerUrl = "https://www.huawei.com",
                    modelName = "HUAWEI Vision",
                    modelDescription = "HUAWEI Vision DLNA Media Renderer",
                    modelNumber = "Vision-1"
                ),
                DeviceIdentity(
                    friendlyName = "荣耀智慧屏",
                    manufacturer = "Honor",
                    manufacturerUrl = "https://www.honor.com",
                    modelName = "HONOR Vision",
                    modelDescription = "HONOR Vision DLNA Media Renderer",
                    modelNumber = "HonorVision"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "hisense",
            label = "海信电视组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "海信电视",
                    manufacturer = "Hisense",
                    manufacturerUrl = "https://www.hisense.com",
                    modelName = "Hisense TV",
                    modelDescription = "Hisense TV DLNA Media Renderer",
                    modelNumber = "HisenseTV"
                ),
                DeviceIdentity(
                    friendlyName = "Vidda 电视",
                    manufacturer = "Hisense",
                    manufacturerUrl = "https://www.hisense.com",
                    modelName = "Vidda TV",
                    modelDescription = "Vidda TV DLNA Media Renderer",
                    modelNumber = "ViddaTV"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "skyworth",
            label = "创维酷开组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "创维电视",
                    manufacturer = "Skyworth",
                    manufacturerUrl = "https://www.skyworth.com",
                    modelName = "Skyworth TV",
                    modelDescription = "Skyworth TV DLNA Media Renderer",
                    modelNumber = "SkyworthTV"
                ),
                DeviceIdentity(
                    friendlyName = "酷开电视",
                    manufacturer = "Coocaa",
                    manufacturerUrl = "https://www.coocaa.com",
                    modelName = "Coocaa TV",
                    modelDescription = "Coocaa TV DLNA Media Renderer",
                    modelNumber = "CoocaaTV"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "tcl",
            label = "TCL 雷鸟组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "TCL 电视",
                    manufacturer = "TCL",
                    manufacturerUrl = "https://www.tcl.com",
                    modelName = "TCL TV",
                    modelDescription = "TCL TV DLNA Media Renderer",
                    modelNumber = "TCLTV"
                ),
                DeviceIdentity(
                    friendlyName = "雷鸟电视",
                    manufacturer = "FFalcon",
                    manufacturerUrl = "https://www.ffalcon.com",
                    modelName = "FFalcon TV",
                    modelDescription = "FFalcon TV DLNA Media Renderer",
                    modelNumber = "FFalconTV"
                )
            )
        ),

        // ---------------- 主流投影 4 组 ----------------
        DouyinDeviceGroup(
            id = "xgimi",
            label = "极米投影组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "极米投影",
                    manufacturer = "XGIMI",
                    manufacturerUrl = "https://www.xgimi.com",
                    modelName = "XGIMI Projector",
                    modelDescription = "XGIMI Projector DLNA Media Renderer",
                    modelNumber = "XGIMI"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "dangbei",
            label = "当贝投影组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "当贝投影",
                    manufacturer = "Dangbei",
                    manufacturerUrl = "https://www.dangbei.com",
                    modelName = "Dangbei Projector",
                    modelDescription = "Dangbei Projector DLNA Media Renderer",
                    modelNumber = "Dangbei"
                ),
                DeviceIdentity(
                    friendlyName = "当贝盒子",
                    manufacturer = "Dangbei",
                    manufacturerUrl = "https://www.dangbei.com",
                    modelName = "Dangbei Box",
                    modelDescription = "Dangbei Box DLNA Media Renderer",
                    modelNumber = "DangbeiBox"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "jmgo",
            label = "坚果投影组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "坚果投影",
                    manufacturer = "JmGO",
                    manufacturerUrl = "https://www.jmgo.com",
                    modelName = "JmGO Projector",
                    modelDescription = "JmGO Projector DLNA Media Renderer",
                    modelNumber = "JmGO"
                )
            )
        ),
        DouyinDeviceGroup(
            id = "formovie",
            label = "峰米投影组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "峰米投影",
                    manufacturer = "Formovie",
                    manufacturerUrl = "https://www.formovie.com",
                    modelName = "Formovie Projector",
                    modelDescription = "Formovie Projector DLNA Media Renderer",
                    modelNumber = "Formovie"
                )
            )
        ),

        // ---------------- 通用 DLNA 兜底 1 组 ----------------
        DouyinDeviceGroup(
            id = "generic",
            label = "通用 DLNA 组",
            members = listOf(
                DeviceIdentity(
                    friendlyName = "客厅的电视",
                    manufacturer = "Generic",
                    manufacturerUrl = "https://casttv.local",
                    modelName = "DLNA MediaRenderer",
                    modelDescription = "Generic DLNA Media Renderer",
                    modelNumber = "1.0"
                ),
                DeviceIdentity(
                    friendlyName = "卧室的电视",
                    manufacturer = "Generic",
                    manufacturerUrl = "https://casttv.local",
                    modelName = "DLNA MediaRenderer",
                    modelDescription = "Generic DLNA Media Renderer",
                    modelNumber = "1.0"
                )
            )
        )
    )

    const val DEFAULT_GROUP_ID = "xiaomi"

    fun findGroup(id: String?): DouyinDeviceGroup =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_GROUP_ID }
}
