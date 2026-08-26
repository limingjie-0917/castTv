package com.bd.casttv.settings

import android.content.Context
import com.bd.casttv.dlna.DeviceIdentity

/**
 * 抖音投屏适配可选设备名称组。
 *
 * 每组包含若干「同族备选身份」（friendlyName / manufacturer / modelName / modelDescription
 * / modelNumber / modelUrl / ssdpServer / dlnaProfiles …），用户切换到某一组时默认使用
 * [DouyinDeviceGroup.defaultIndex] 指定的成员；若还是搜不到设备，可在组内轮换其他成员。
 *
 * 选型原则（v1.2.xxx 调优）：
 *   1. manufacturer / modelName / modelNumber 用国内真机抓包常见值，不再用 "Xiaomi TV" 这类
 *      直译英语；抖音黑名单规则大概率会按特征字符串过滤 "XX TV" 通用写法。
 *   2. modelUrl 统一指向制造商真实官网产品页，绝不出现 casttv.local 等自报家门域名。
 *   3. SSDP SERVER 头按品牌写真机常见的 WebServer / Cling 值，绝不含 "CastTV"。
 *   4. X_DLNACAP（dlnaProfiles）统一用 DeviceIdentity.DEFAULT_DLNA_PROFILES 兜底，
 *      让 description.xml 看起来像一台真 DMR 而不是空壳。
 *   5. 覆盖抖音识别度较高的国内主流电视 5 家 + 主流投影 4 家 + 通用 DLNA 兜底 1 组，共 10 组。
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

    /** 抓包兼容：小米/Redmi 真电视 SSDP 响应里最常看到的 Web Server 头。 */
    private const val SSDP_CLING = "Linux/4.9 UPnP/1.0 Cling/2.1.20"
    /** 抓包兼容：华为 / 雷鸟 / 海信等常见 ROM-Pager + Intel SDK 组合。 */
    private const val SSDP_ALLEGRO = "Allegro-Software-RomPager/4.34 UPnP/1.0 Intel_SDK_for_UPnP_Devices/1.3"
    /** 抓包兼容：极米 / 当贝 / 坚果 / 峰米等投影大多用 Rygel + Cling。 */
    private const val SSDP_RYGEL = "Rygel/0.40.0 UPnP/1.0 Cling/2.1.20"
    /** 通用兜底：Allegro 头是最多 App 都放行的字符串。 */
    private const val SSDP_GENERIC = "Allegro-Software-RomPager/4.34 UPnP/1.0"

    private const val DEFAULT_PROFILES = DeviceIdentity.DEFAULT_DLNA_PROFILES

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
                    modelName = "L65M6-OTA",
                    modelDescription = "MIBOX MediaRenderer",
                    modelNumber = "MiTV-OLED",
                    modelUrl = "https://www.mi.com/tv",
                    ssdpServer = SSDP_CLING,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "Redmi 电视",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "https://www.mi.com",
                    modelName = "L55M6-RA",
                    modelDescription = "Redmi Smart TV MediaRenderer",
                    modelNumber = "Redmi-Max",
                    modelUrl = "https://www.mi.com/redmitv",
                    ssdpServer = SSDP_CLING,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "小米盒子",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "https://www.mi.com",
                    modelName = "MIBOX4",
                    modelDescription = "Mi Box MediaRenderer",
                    modelNumber = "MiBox-4S",
                    modelUrl = "https://www.mi.com/mibox",
                    ssdpServer = SSDP_CLING,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "Vision-65",
                    modelDescription = "HUAWEI Vision DMR",
                    modelNumber = "HEGE-560",
                    modelUrl = "https://consumer.huawei.com/cn/tv/",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "荣耀智慧屏",
                    manufacturer = "Honor",
                    manufacturerUrl = "https://www.honor.com",
                    modelName = "HONOR-Vision",
                    modelDescription = "Honor Vision MediaRenderer",
                    modelNumber = "OSCA-550A",
                    modelUrl = "https://www.honorstore.cn/tv",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "HZ65A77E",
                    modelDescription = "Hisense TV MediaRenderer",
                    modelNumber = "A7F",
                    modelUrl = "https://tv.hisense.cn/",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "Vidda 电视",
                    manufacturer = "Hisense",
                    manufacturerUrl = "https://www.hisense.com",
                    modelName = "Vidda-65V3H",
                    modelDescription = "Vidda TV MediaRenderer",
                    modelNumber = "V3H-Pro",
                    modelUrl = "https://www.vidda.com/",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "55H80",
                    modelDescription = "Skyworth TV MediaRenderer",
                    modelNumber = "H90-Pro",
                    modelUrl = "https://www.skyworth.com/topic/tv",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "酷开电视",
                    manufacturer = "Coocaa",
                    manufacturerUrl = "https://www.coocaa.com",
                    modelName = "Coocaa-65P50",
                    modelDescription = "Coocaa TV MediaRenderer",
                    modelNumber = "P50-Pro",
                    modelUrl = "https://www.coocaa.com/tv",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "65Q10H",
                    modelDescription = "TCL TV MediaRenderer",
                    modelNumber = "C11G-Pro",
                    modelUrl = "https://www.tcl.com/cn/televisions",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "雷鸟电视",
                    manufacturer = "FFalcon",
                    manufacturerUrl = "https://www.ffalcon.com",
                    modelName = "FFALCON-65R685C",
                    modelDescription = "FFalcon TV MediaRenderer",
                    modelNumber = "R685C-PRO",
                    modelUrl = "https://www.ffalcon.com/product/tv",
                    ssdpServer = SSDP_ALLEGRO,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "XGIMI-H6",
                    modelDescription = "XGIMI Projector MediaRenderer",
                    modelNumber = "HORIZON-Ultra",
                    modelUrl = "https://www.xgimi.com/projector",
                    ssdpServer = SSDP_RYGEL,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "Dangbei-X3",
                    modelDescription = "Dangbei Projector MediaRenderer",
                    modelNumber = "X3-Pro",
                    modelUrl = "https://www.dangbei.com/projector",
                    ssdpServer = SSDP_RYGEL,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "当贝盒子",
                    manufacturer = "Dangbei",
                    manufacturerUrl = "https://www.dangbei.com",
                    modelName = "Dangbei-Box-B3",
                    modelDescription = "Dangbei Box MediaRenderer",
                    modelNumber = "B3-Pro",
                    modelUrl = "https://www.dangbei.com/box",
                    ssdpServer = SSDP_RYGEL,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "JmGO-N1",
                    modelDescription = "JmGO Projector MediaRenderer",
                    modelNumber = "N1-Ultra",
                    modelUrl = "https://www.jmgo.com/Projector",
                    ssdpServer = SSDP_RYGEL,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    modelName = "Formovie-T1",
                    modelDescription = "Formovie Projector MediaRenderer",
                    modelNumber = "C3-TriColor",
                    modelUrl = "https://www.formovie.com/projectors",
                    ssdpServer = SSDP_RYGEL,
                    dlnaProfiles = DEFAULT_PROFILES
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
                    manufacturer = "Changhong",
                    manufacturerUrl = "https://www.changhong.com",
                    modelName = "CHiQ-Q9T",
                    modelDescription = "Changhong MediaRenderer DLNA",
                    modelNumber = "55Q9T",
                    modelUrl = "https://www.changhong.com/ch/television",
                    ssdpServer = SSDP_GENERIC,
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "卧室的电视",
                    manufacturer = "Skyworth",
                    manufacturerUrl = "https://www.skyworth.com",
                    modelName = "Skyworth-G32",
                    modelDescription = "Skyworth TV MediaRenderer",
                    modelNumber = "G32-Pro",
                    modelUrl = "https://www.skyworth.com/topic/tv",
                    ssdpServer = SSDP_GENERIC,
                    dlnaProfiles = DEFAULT_PROFILES
                )
            )
        )
    )

    const val DEFAULT_GROUP_ID = "xiaomi"

    /**
     * 内置组 + 用户克隆组（合并）。
     *
     * 内置组在前，自定义组在后；自定义组若与内置组同 id（"加入现有组"模式产生的扩展组），
     * 用户切换到该 id 时会优先命中内置 [findGroup]，但 [Settings.currentDlnaIdentity]
     * 实际取 member 的字段已与扩展组一致（自定义组覆盖了 member 列表）。
     *
     * 这里返回顺序：先内置，再自定义（去重 id 后追加）。
     */
    fun allIncludingCustom(context: Context): List<DouyinDeviceGroup> {
        val builtin = ALL
        val custom = try { CustomDouyinDeviceGroupsStore.list(context) } catch (_: Throwable) { emptyList() }
        if (custom.isEmpty()) return builtin
        val builtinIds = builtin.map { it.id }.toMutableSet()
        val merged = builtin.toMutableList()
        for (g in custom) {
            // 若 id 已在内置中存在（扩展组场景），则用自定义版本替换内置版本
            // —— 自定义组包含用户加的额外 member，是"扩展后的全集"
            if (g.id in builtinIds) {
                val idx = merged.indexOfFirst { it.id == g.id }
                if (idx >= 0) merged[idx] = g
            } else {
                merged.add(g)
            }
        }
        return merged
    }

    /**
     * 按 id 查找：先内置，再自定义。
     * 默认组兜底（不返回 null，避免上层崩溃）。
     */
    fun findGroup(id: String?): DouyinDeviceGroup =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_GROUP_ID }

    /** 同 [findGroup] 但合并自定义组。 */
    fun findGroupIncludingCustom(context: Context, id: String?): DouyinDeviceGroup {
        val all = allIncludingCustom(context)
        return all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_GROUP_ID }
    }

    /** 是否为自定义组（用于 UI 显示删除按钮等）。 */
    fun isCustomGroupId(context: Context, id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (ALL.any { it.id == id }) return false
        return try { CustomDouyinDeviceGroupsStore.list(context).any { it.id == id } } catch (_: Throwable) { false }
    }
}
