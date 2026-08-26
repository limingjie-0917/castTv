package com.bd.casttv.dlna

import java.util.Locale

/**
 * 对外暴露的 DLNA/UPnP 设备身份。整组一起切换，用于兼容抖音等对
 * `friendlyName / manufacturer / modelName / modelDescription / modelNumber`
 * 组合识别较严格的控制端。
 *
 * v1.2.xxx+：补齐 SSDP SERVER 头、X_DLNACAP、图标列表等细节字段，
 * 避免 description.xml / NOTIFY 报文里出现 "casttv.local"、"0001"、"CastTV/1.0"
 * 等一眼就能被识别为伪装设备的破绽。
 */
data class DeviceIdentity(
    val friendlyName: String,
    val manufacturer: String,
    val manufacturerUrl: String,
    val modelName: String,
    val modelDescription: String,
    val modelNumber: String,
    /** 写在 <modelURL> 里；优先使用制造商官网，不要出现 casttv.local 等自报家门的域名。 */
    val modelUrl: String,
    /**
     * SSDP NOTIFY / M-SEARCH 响应头里的 SERVER 字段。
     * 真机常见值示例：
     *   - 小米电视："Linux/4.4 UPnP/1.0 Cling/2.1.20"
     *   - 华为 / 雷鸟 / 极米："Allegro-Software-RomPager/4.34 UPnP/1.0 Intel_SDK_for_UPnP_Devices/1.3"
     * 这里千万不要出现 "CastTV"，否则抖音很容易按字符串黑名单过滤。
     */
    val ssdpServer: String,
    /**
     * X_DLNACAP 的逗号分隔 DLNA profile 列表；
     * 取自主流 DMR 设备声明，覆盖抖音最常探的 MP4/HLS/TS 封装。
     */
    val dlnaProfiles: String,
    /**
     * 图标列表（由 UpnpXml 直接拼 <iconList>）。
     * 留空时 UpnpXml 会退化为一组通用占位图标，保证 description.xml 合法。
     */
    val icons: List<DlnaIcon> = emptyList(),
    /**
     * <presentationURL>；真电视多指向主页，设为 "/" 即可（即 http://device-ip:port/）。
     * 不能留空也不能写 casttv.local。
     */
    val presentationUrl: String = "/"
) {
    companion object {
        /**
         * 通用默认 SERVER 头（当某组未指定时的兜底）。
         * 参考 BubbleUPnP / Rygel 等被广泛兼容的开源 DMR 实现。
         */
        const val DEFAULT_SSDP_SERVER = "Allegro-Software-RomPager/4.34 UPnP/1.0 CastTV/1.2"

        /** 常见 DMR 都声明的一组 DLNA profile。 */
        const val DEFAULT_DLNA_PROFILES =
            "DLNA.ORG_PN=MPEG1;DLNA.ORG_PN=MP4;DLNA.ORG_PN=AVC_TS_HD_24_AC3_T;DLNA.ORG_PN=MPEG4_P2_TS_SD_AC3_T;DLNA.ORG_PN=WMV;DLNA.ORG_PN=JPEG_SM;DLNA.ORG_PN=JPEG_MED;DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_PN=PNG_SM;DLNA.ORG_PN=PNG_LRG"

        /** 早期兼容用的 Xiaomi 兜底身份（保持 v1.2.20 行为不变）。 */
        val XIAOMI_FALLBACK = DeviceIdentity(
            friendlyName = "小米电视",
            manufacturer = "Xiaomi",
            manufacturerUrl = "https://www.mi.com",
            modelName = "Xiaomi TV",
            modelDescription = "Xiaomi TV DLNA Media Renderer",
            modelNumber = "MiTV",
            modelUrl = "https://www.mi.com/tv",
            ssdpServer = "Linux/4.9 UPnP/1.0 Cling/2.1.20",
            dlnaProfiles = DEFAULT_DLNA_PROFILES,
            presentationUrl = "/"
        )

        /**
         * 通用兜底图标集（PNG 不需要真实资源——description.xml 只是声明支持什么尺寸，
         * 控制点通常不真的拉取）。这样即便组内没配 icons，description.xml 也是合格的。
         *
         * 图标的 mimetype 用 image/png；width/height/depth 按真电视常见值填写。
         * URL 指向一个不存在的 /icon 端点是安全的（DlnaHttpServer 返回 404 即可，
         * 抖音不会校验图标文件）。
         */
        val DEFAULT_ICONS: List<DlnaIcon> = listOf(
            DlnaIcon(mimetype = "image/png", width = 120, height = 120, depth = 32, url = "/icon/120.png"),
            DlnaIcon(mimetype = "image/png", width = 48, height = 48, depth = 32, url = "/icon/48.png"),
            DlnaIcon(mimetype = "image/jpeg", width = 120, height = 120, depth = 24, url = "/icon/120.jpg")
        )

        /** 基于 UDN 稳定派生 8 位十六进制的"序列号"，避免全部设备都写 0001。 */
        fun deriveSerialNumber(udn: String): String {
            val h = if (udn.startsWith("uuid:")) udn.drop(5) else udn
            val v = h.hashCode().toLong() and 0xFFFFFFFFL
            return v.toString(16).padStart(8, '0').uppercase(Locale.US)
        }
    }
}

/**
 * description.xml 中的 <icon> 元素。
 * 颜色深度 depth 用 24 或 32，都是通用值。
 */
data class DlnaIcon(
    val mimetype: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val url: String
)
