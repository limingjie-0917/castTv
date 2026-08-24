package com.bd.casttv.ui

import com.bd.casttv.R

object CustomDockIconPresets {
    data class Preset(val key: String, val label: String, val drawableRes: Int)

    val presets: List<Preset> = listOf(
        Preset("collection", "合集", R.drawable.ic_collection_list),
        Preset("tv", "电视", R.drawable.ic_preset_tv),
        Preset("live", "直播", R.drawable.ic_preset_live),
        Preset("movie", "电影", R.drawable.ic_preset_movie),
        Preset("sport", "体育", R.drawable.ic_preset_sport),
        Preset("music", "音乐", R.drawable.ic_preset_music),
        Preset("news", "新闻", R.drawable.ic_preset_news),
        Preset("kids", "少儿", R.drawable.ic_preset_kids),
        Preset("game", "游戏", R.drawable.ic_preset_game),
        Preset("education", "学习", R.drawable.ic_preset_education),
        Preset("documentary", "纪实", R.drawable.ic_preset_documentary),
        Preset("travel", "旅行", R.drawable.ic_preset_travel),
        Preset("food", "美食", R.drawable.ic_preset_food),
        Preset("star", "精选", R.drawable.ic_preset_star),
        Preset("hot", "热门", R.drawable.ic_fire),
        Preset("heart", "喜欢", R.drawable.ic_preset_heart),
        Preset("cloud", "云端", R.drawable.ic_preset_cloud),
        Preset("phone", "手机", R.drawable.ic_phone_hub),
        Preset("history", "历史", R.drawable.ic_history_tv),
        Preset("settings", "设置", R.drawable.ic_settings_tv),
    )

    fun iconFor(key: String?): Preset = presets.firstOrNull { it.key == key } ?: presets.first()
}
