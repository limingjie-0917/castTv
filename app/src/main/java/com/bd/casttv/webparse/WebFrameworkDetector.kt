package com.bd.casttv.webparse

import java.util.Locale

object WebFrameworkDetector {
    fun detect(html: String): WebFrameworkType {
        val lower = html.lowercase(Locale.US)
        return when {
            lower.contains("maccms") ||
                lower.contains("mac_cms") ||
                html.contains("苹果CMS", ignoreCase = true) ||
                lower.contains("/api.php/provide/vod") ||
                lower.contains("player_aaaa") -> WebFrameworkType.MAC_CMS
            lower.contains("zyplayer") ||
                html.contains("ZyPlayer") ||
                lower.contains("zy_player") -> WebFrameworkType.ZY_PLAYER
            lower.contains("snailcms") ||
                html.contains("SnailCMS") ||
                html.contains("蜗牛CMS", ignoreCase = true) -> WebFrameworkType.SNAIL_CMS
            lower.contains("nemocms") || lower.contains("nemo-cms") -> WebFrameworkType.NEMO
            else -> WebFrameworkType.UNKNOWN
        }
    }
}
