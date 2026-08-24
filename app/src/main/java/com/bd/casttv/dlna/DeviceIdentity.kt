package com.bd.casttv.dlna

/**
 * 对外暴露的 DLNA/UPnP 设备身份。整组一起切换，用于兼容抖音等对
 * `friendlyName / manufacturer / modelName / modelDescription / modelNumber`
 * 组合识别较严格的控制端。
 */
data class DeviceIdentity(
    val friendlyName: String,
    val manufacturer: String,
    val manufacturerUrl: String,
    val modelName: String,
    val modelDescription: String,
    val modelNumber: String
) {
    companion object {
        /** 早期兼容用的 Xiaomi 兜底身份（保持 v1.2.20 行为不变）。 */
        val XIAOMI_FALLBACK = DeviceIdentity(
            friendlyName = "小米电视",
            manufacturer = "Xiaomi",
            manufacturerUrl = "https://www.mi.com",
            modelName = "Xiaomi TV",
            modelDescription = "Xiaomi TV DLNA Media Renderer",
            modelNumber = "MiTV"
        )
    }
}
