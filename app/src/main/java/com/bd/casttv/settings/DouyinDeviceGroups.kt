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
 * 选型原则（v1.2.xxx 调优；参考开源项目 wechat-finder-dlna 已验证可被国内 App 识别的小米真机描述符）：
 *   1. modelName / modelDescription 用 DLNA 栈自报的 "{Brand} MediaRenderer" 风格，
 *      不再用营销整机型号（L65M6-OTA 等）——指纹库收录的是 DLNA 栈广播值而非型号名。
 *   2. modelNumber 留空 → description.xml 不输出 <modelNumber>（参考真机描述符无此字段）。
 *   3. manufacturerUrl / modelUrl 用制造商官网 http://www.{brand}.com/ 形式。
 *   4. SSDP SERVER 头按 "Linux/4.9 UPnP/1.0 DLNADOC/1.50 {Brand}-DLNA/1.0" 拼装
 *      （wechat-finder-dlna 已验证该风格可被微信/B站/爱优腾等识别）。
 *   5. X_DLNACAP（dlnaProfiles）统一用 DeviceIdentity.DEFAULT_DLNA_PROFILES 兜底。
 *   6. 当贝组已被抖音实测可搜到（播控正常），保持原样不动。
 */
data class DouyinDeviceGroup(
    val id: String,
    val label: String,
    val members: List<DeviceIdentity>,
    val defaultIndex: Int = 0,
    /** UI-only 标识：true=扫描发现的就是本机 DLNA 设备（不可克隆）。不参与持久化。 */
    val isLocalDevice: Boolean = false,
    /** UI-only 标识：true=该克隆组已在用户自定义组里（重复克隆会被过滤成这个标记，并仍展示提示用户）。不参与持久化。 */
    val isAlreadyAdded: Boolean = false
) {
    fun memberAt(index: Int): DeviceIdentity =
        members[index.coerceIn(0, members.size - 1)]
}

object DouyinDeviceGroups {

    /**
     * 已验证可被国内 App 识别的 DLNA 栈 SERVER 头风格（wechat-finder-dlna 小米真机仿真）：
     * "Linux/4.9 UPnP/1.0 DLNADOC/1.50 {Brand}-DLNA/1.0"。
     */
    private fun ssdpBrandServer(brand: String) = "Linux/4.9 UPnP/1.0 DLNADOC/1.50 $brand-DLNA/1.0"

    /** 当贝组已被抖音实测可搜到，SSDP 头保持原样。 */
    private const val SSDP_RYGEL = "Rygel/0.40.0 UPnP/1.0 Cling/2.1.20"

    private const val DEFAULT_PROFILES = DeviceIdentity.DEFAULT_DLNA_PROFILES

    val ALL: List<DouyinDeviceGroup> = listOf(
        // ---------------- 国内主流电视 5 组 ----------------
        DouyinDeviceGroup(
            id = "xiaomi",
            label = "小米电视组",
            members = listOf(
                // 默认成员：wechat-finder-dlna 已验证可被识别的小米真机描述符（1:1 移植）
                DeviceIdentity(
                    friendlyName = "小米电视",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "http://www.xiaomi.com/",
                    modelName = "Xiaomi MediaRenderer",
                    modelDescription = "Xiaomi MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.xiaomi.com/",
                    ssdpServer = ssdpBrandServer("Xiaomi"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "Redmi 电视",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "http://www.xiaomi.com/",
                    modelName = "Redmi MediaRenderer",
                    modelDescription = "Redmi MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.xiaomi.com/",
                    ssdpServer = ssdpBrandServer("Xiaomi"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "小米盒子",
                    manufacturer = "Xiaomi",
                    manufacturerUrl = "http://www.xiaomi.com/",
                    modelName = "MiBOX MediaRenderer",
                    modelDescription = "MiBOX MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.xiaomi.com/",
                    ssdpServer = ssdpBrandServer("Xiaomi"),
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
                    manufacturerUrl = "http://www.huawei.com/",
                    modelName = "Huawei MediaRenderer",
                    modelDescription = "Huawei MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.huawei.com/",
                    ssdpServer = ssdpBrandServer("Huawei"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "荣耀智慧屏",
                    manufacturer = "Honor",
                    manufacturerUrl = "http://www.honor.com/",
                    modelName = "Honor MediaRenderer",
                    modelDescription = "Honor MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.honor.com/",
                    ssdpServer = ssdpBrandServer("Honor"),
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
                    manufacturerUrl = "http://www.hisense.com/",
                    modelName = "Hisense MediaRenderer",
                    modelDescription = "Hisense MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.hisense.com/",
                    ssdpServer = ssdpBrandServer("Hisense"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "Vidda 电视",
                    manufacturer = "Vidda",
                    manufacturerUrl = "http://www.vidda.com/",
                    modelName = "Vidda MediaRenderer",
                    modelDescription = "Vidda MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.vidda.com/",
                    ssdpServer = ssdpBrandServer("Vidda"),
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
                    manufacturerUrl = "http://www.skyworth.com/",
                    modelName = "Skyworth MediaRenderer",
                    modelDescription = "Skyworth MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.skyworth.com/",
                    ssdpServer = ssdpBrandServer("Skyworth"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "酷开电视",
                    manufacturer = "Coocaa",
                    manufacturerUrl = "http://www.coocaa.com/",
                    modelName = "Coocaa MediaRenderer",
                    modelDescription = "Coocaa MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.coocaa.com/",
                    ssdpServer = ssdpBrandServer("Coocaa"),
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
                    manufacturerUrl = "http://www.tcl.com/",
                    modelName = "TCL MediaRenderer",
                    modelDescription = "TCL MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.tcl.com/",
                    ssdpServer = ssdpBrandServer("TCL"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "雷鸟电视",
                    manufacturer = "FFalcon",
                    manufacturerUrl = "http://www.ffalcon.com/",
                    modelName = "FFalcon MediaRenderer",
                    modelDescription = "FFalcon MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.ffalcon.com/",
                    ssdpServer = ssdpBrandServer("FFalcon"),
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
                    manufacturerUrl = "http://www.xgimi.com/",
                    modelName = "XGIMI MediaRenderer",
                    modelDescription = "XGIMI MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.xgimi.com/",
                    ssdpServer = ssdpBrandServer("XGIMI"),
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
                    manufacturerUrl = "http://www.jmgo.com/",
                    modelName = "JmGO MediaRenderer",
                    modelDescription = "JmGO MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.jmgo.com/",
                    ssdpServer = ssdpBrandServer("JmGO"),
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
                    manufacturerUrl = "http://www.formovie.com/",
                    modelName = "Formovie MediaRenderer",
                    modelDescription = "Formovie MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.formovie.com/",
                    ssdpServer = ssdpBrandServer("Formovie"),
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
                    manufacturerUrl = "http://www.changhong.com/",
                    modelName = "Changhong MediaRenderer",
                    modelDescription = "Changhong MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.changhong.com/",
                    ssdpServer = ssdpBrandServer("Changhong"),
                    dlnaProfiles = DEFAULT_PROFILES
                ),
                DeviceIdentity(
                    friendlyName = "卧室的电视",
                    manufacturer = "Skyworth",
                    manufacturerUrl = "http://www.skyworth.com/",
                    modelName = "Skyworth MediaRenderer",
                    modelDescription = "Skyworth MediaRenderer",
                    modelNumber = "",
                    modelUrl = "http://www.skyworth.com/",
                    ssdpServer = ssdpBrandServer("Skyworth"),
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
