package com.bd.casttv.routine

import java.util.Calendar

/**
 * 「24 小时生活作息」气泡文案数据源（内容底座，可直接按需增删改）。
 *
 * 按用户定稿：全天划分为 20 个细分时间段，每段提供两套文案：
 * - 表 A（常规状态）：当天累计前台使用 < 2 小时时展示，以通用健康作息为主。
 * - 表 B（健康提醒状态）：当天累计前台使用 ≥ 2 小时后展示，更强调「多运动 / 注意休息 /
 *   调整身体状态」，并给出简单收益说明。
 *
 * 每段还带一个 [emoji] 前导图标，用于在气泡左侧做轻量的时段氛围点缀（早/午/晚/夜）。
 */
object RoutineContent {

    /** 单个时间段的文案定义。[startMin] 为该段起点（距 00:00 的分钟数）。 */
    data class Segment(
        val startMin: Int,
        val emoji: String,
        val mainA: String,
        val subA: String,
        val mainB: String,
        val subB: String,
    )

    /** 一条待展示的气泡内容。 */
    data class Bubble(
        val emoji: String,
        val main: String,
        val sub: String,
    )

    /**
     * 20 个细分时间段，按起点分钟升序排列，覆盖 00:00–24:00。
     * 命中规则：取「起点 <= 当前分钟」中最大的一段。
     */
    private val SEGMENTS: List<Segment> = listOf(
        Segment(0,    "🌙", "该收尾了，准备睡觉",   "放下手机，睡得更踏实",     "该休息了，别再硬撑",       "少刷屏，深睡更利恢复"),
        Segment(30,   "🌙", "夜间以睡眠为主",       "少刷屏，醒得更少更舒服",   "夜间请优先睡眠",           "睡得更深，第二天更有劲"),
        Segment(420,  "🌅", "起床先喝水见光",       "见光 3 分钟，精神更快回来", "起床先让身体开机",         "喝水见光，精神更快回来"),
        Segment(450,  "🍳", "规律早餐，状态更稳",   "蛋白 + 主食更抗饿更专注",   "早餐规律，别空腹硬扛",     "能量更稳，不易疲劳"),
        Segment(510,  "📝", "先做最重要的一件",     "先推进 30 分钟，效率更高", "先做最重要的一件",         "少切换任务，脑子更省力"),
        Segment(570,  "💡", "进入专注时段",         "关通知，只做一件事",       "久坐易累，记得动一动",     "站起来走 1 分钟就有效"),
        Segment(630,  "🧍", "起身活动一下",         "走两步 + 远眺 20 秒",       "现在适合活动肩颈",         "远眺 20 秒，缓解眼疲劳"),
        Segment(640,  "💡", "继续推进但别久坐",     "每 50 分钟停 2 分钟",       "继续推进，但别一直坐",     "每 50 分钟停 2 分钟更舒服"),
        Segment(720,  "🍽️", "午餐慢一点更舒适",    "饭后走 10 分钟，下午更轻松", "午间走一走更轻松",         "饭后走 10 分钟，消化更好"),
        Segment(780,  "😴", "午休 15 分钟更提神",   "短睡不拖沓，醒来更清醒",   "午休一下恢复效率",         "眯 10–15 分钟就够"),
        Segment(800,  "🤝", "下午适合沟通推进",     "一次对齐关键点，减少反复", "下午多补水更抗疲劳",       "去接杯水，让身体动起来"),
        Segment(900,  "🧘", "伸展补水，眼睛休息",   "转转肩颈，缓解酸胀",       "到点伸展一下",             "肩颈放松，状态更稳"),
        Segment(910,  "📦", "收口交付更安心",       "清待办，别把压力带回家",   "收口之后给自己松口气",     "清待办，减少压迫感"),
        Segment(1050, "🎒", "下班切换，放松一下",   "关掉工作通知，开始休息",   "下班后建议走动放松",       "让身体切到休息模式"),
        Segment(1110, "🍚", "晚餐别太晚更好睡",     "七分饱更舒服",             "晚餐别太晚更利睡眠",       "七分饱更轻松"),
        Segment(1170, "🚶", "晚间散步更助睡眠",     "走 20 分钟，身心放松",     "晚间散步最适合",           "走 20 分钟，缓解紧绷"),
        Segment(1230, "📖", "做点喜欢的事就好",     "阅读/兴趣 30 分钟，心情更稳", "少信息，多放松",         "关掉部分通知，脑子更安静"),
        Segment(1290, "🌛", "现在开始降速更好睡",   "少刺激信息，慢慢放松",     "现在开始降速更好睡",       "洗漱收拾，慢慢放松"),
        Segment(1350, "🛏️", "11 点前睡更利恢复",   "提前洗漱，调暗屏幕",       "11 点前睡更利恢复",         "调暗屏幕，准备入睡"),
        Segment(1410, "💤", "上床就别再处理事情",   "写下担心的事，明天再解决", "上床就别再处理事情",       "放下焦虑，明天再继续"),
    )

    /** 命中当前分钟所属时间段。 */
    private fun segmentFor(minuteOfDay: Int): Segment {
        var hit = SEGMENTS.first()
        for (seg in SEGMENTS) {
            if (minuteOfDay >= seg.startMin) hit = seg else break
        }
        return hit
    }

    /**
     * 取当前应展示的气泡内容。
     * @param health 是否为健康提醒状态（当天累计使用 ≥ 2 小时）；true 用表 B，false 用表 A。
     */
    fun bubbleFor(now: Calendar = Calendar.getInstance(), health: Boolean): Bubble {
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val seg = segmentFor(minuteOfDay)
        return if (health) Bubble(seg.emoji, seg.mainB, seg.subB)
        else Bubble(seg.emoji, seg.mainA, seg.subA)
    }
}
