package com.bd.casttv.dlna

import com.bd.casttv.util.NetworkUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight in-memory diagnostics for SSDP discovery debugging.
 *
 * Used to troubleshoot "peer device cannot discover this renderer" cases.
 * This object is process-local (not persisted) and safe to call from background threads.
 */
object SsdpDiagnostics {

    private const val MAX_LOG = 80
    private const val MAX_CAST_EVENTS = 200
    private const val MAX_SERVICE_HEALTH = 100
    private const val MAX_EXCEPTION_MESSAGE = 200
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class MSearchLog(val timeMs: Long, val fromIp: String, val st: String)
    data class ResponseLog(val timeMs: Long, val toIp: String, val location: String)
    data class DescGetLog(val timeMs: Long, val fromIp: String)
    data class HttpGetLog(val timeMs: Long, val fromIp: String, val uri: String, val status: String)
    data class NotifyLog(val timeMs: Long, val type: String, val nt: String, val ip: String)
    data class SoapResultLog(val timeMs: Long, val service: String, val action: String, val status: String, val detail: String)
    data class CloudSyncLog(val timeMs: Long, val message: String)
    data class ServiceHealthEvent(
        val timeMs: Long,
        val module: String,
        val level: Level,
        val event: String,
        val message: String,
        val exceptionMessage: String
    ) {
        enum class Level { INFO, WARN, ERROR }
    }

    /**
     * 结构化投屏事件流水日志。
     * 按时间顺序记录关键投屏节点：搜索 → 发现 → 描述/能力拉取 → 订阅 → SOAP 控制 → 媒体下发 → 播放状态。
     */
    data class CastEvent(val timeMs: Long, val kind: Kind, val detail: String) {
        enum class Kind(val label: String) {
            SEARCH_START("SSDP 搜索请求"),       // 手机发起 M-SEARCH 搜索
            DEVICE_FOUND("SSDP 发现回包"),       // 本机回复 200 OK，被手机发现
            DESCRIPTION_GET("设备描述拉取"),     // 手机拉取 /description.xml
            SCPD_GET("服务能力拉取"),            // 手机拉取 /scpd/*.xml
            EVENT_SUBSCRIBE("事件订阅"),         // GENA SUBSCRIBE / UNSUBSCRIBE
            SOAP_CONTROL("SOAP 控制入口"),       // 普通 SOAP action 入口
            MEDIA_URI_SET("媒体地址下发"),       // SetAVTransportURI / SetNextAVTransportURI
            PLAYBACK_CONTROL("播放控制"),        // Play / Pause / Stop / Seek / Next / Previous 等
            STATUS_QUERY("状态查询"),            // GetTransportInfo / GetPositionInfo / GetMediaInfo 等轮询
            PLAYER_STATE("播放器真实状态"),      // PlaybackController 的真实播放状态变化
            CONNECT_SUCCESS("连接成功"),         // 保留兼容：连接成功类事件
            CONNECT_FAILED("连接失败"),          // SOAP 出错或播放启动失败
            INTERRUPTED("连接中断"),             // 播放中断 / 手机主动 Stop
            EXCEPTION("异常")                    // 其它异常
        }
    }

    @Volatile private var interfaces: List<NetworkUtils.LanInterface> = emptyList()

    private val msearchLogs = ArrayDeque<MSearchLog>()
    private val respLogs = ArrayDeque<ResponseLog>()
    private val descLogs = ArrayDeque<DescGetLog>()
    private val httpGetLogs = ArrayDeque<HttpGetLog>()
    private val notifyLogs = ArrayDeque<NotifyLog>()
    private val soapResultLogs = ArrayDeque<SoapResultLog>()
    private val cloudSyncLogs = ArrayDeque<CloudSyncLog>()
    private val serviceHealthLogs = ArrayDeque<ServiceHealthEvent>()
    private val castEvents = ArrayDeque<CastEvent>()
    private val queryThrottleLock = Any()
    private var queryLogCount = 0
    private var lastQueryLogMs = 0L
    // Throttle DESCRIPTION_GET cast events: same IP re-fetching description.xml
    // within this window only logs once, preventing log flooding when the control
    // point re-discovers due to frequent NOTIFY alive.
    private const val DESC_GET_THROTTLE_MS = 30_000L
    private val descGetThrottleLock = Any()
    private var lastDescGetIp: String = ""
    private var lastDescGetMs: Long = 0L

    private fun <T> push(buf: ArrayDeque<T>, v: T, cap: Int = MAX_LOG) {
        buf.addLast(v)
        while (buf.size > cap) buf.removeFirst()
    }

    fun updateInterfaces(list: List<NetworkUtils.LanInterface>) {
        interfaces = list
    }

    fun logMSearch(fromIp: String, st: String) {
        synchronized(msearchLogs) { push(msearchLogs, MSearchLog(System.currentTimeMillis(), fromIp, st)) }
        // 每次收到手机的 M-SEARCH 视为「开始搜索/建立连接」一步。
        logCastEvent(CastEvent.Kind.SEARCH_START, "$fromIp 发起 M-SEARCH（ST=$st）")
    }

    fun logResponse(toIp: String, location: String) {
        synchronized(respLogs) { push(respLogs, ResponseLog(System.currentTimeMillis(), toIp, location)) }
        // 我们回了 200 OK，等于「设备已被发现」。
        logCastEvent(CastEvent.Kind.DEVICE_FOUND, "回包给 $toIp（LOCATION=$location）")
    }

    fun logDescriptionGet(fromIp: String) {
        synchronized(descLogs) { push(descLogs, DescGetLog(System.currentTimeMillis(), fromIp)) }
        // Throttle cast events for repeated description.xml fetches from the same IP.
        val now = System.currentTimeMillis()
        val shouldLog = synchronized(descGetThrottleLock) {
            if (fromIp == lastDescGetIp && now - lastDescGetMs < DESC_GET_THROTTLE_MS) false
            else { lastDescGetIp = fromIp; lastDescGetMs = now; true }
        }
        if (shouldLog) {
            logCastEvent(CastEvent.Kind.DESCRIPTION_GET, "$fromIp 拉取 /description.xml，准备读取设备描述")
        }
    }

    fun logHttpGet(fromIp: String, uri: String, status: String) {
        synchronized(httpGetLogs) { push(httpGetLogs, HttpGetLog(System.currentTimeMillis(), fromIp, uri, status)) }
        if (uri.startsWith("/scpd/", ignoreCase = true)) {
            logCastEvent(CastEvent.Kind.SCPD_GET, "$fromIp 拉取 $uri，status=$status")
        }
    }

    fun logNotify(type: String, nt: String, ip: String) {
        synchronized(notifyLogs) { push(notifyLogs, NotifyLog(System.currentTimeMillis(), type, nt, ip)) }
    }

    fun logSoapResult(service: String, action: String, status: String, detail: String = "") {
        synchronized(soapResultLogs) {
            push(soapResultLogs, SoapResultLog(System.currentTimeMillis(), service, action.ifBlank { "(空 action)" }, status, detail))
        }
        if (status.startsWith("OK", ignoreCase = true)) return
        logCastEvent(CastEvent.Kind.CONNECT_FAILED, "SOAP 返回异常：service=$service，action=${action.ifBlank { "(空 action)" }}，status=$status，$detail")
    }

    /** 记录 DLNA SOAP 入口请求，帮助判断手机端是否进入控制阶段。 */
    fun logSoapAction(service: String, action: String, fromIp: String, userAgent: String) {
        val actionText = action.ifBlank { "(空 action)" }
        val uaText = userAgent.ifBlank { "无 User-Agent" }
        val kind = when {
            actionText in MEDIA_URI_ACTIONS -> CastEvent.Kind.MEDIA_URI_SET
            actionText in PLAYBACK_CONTROL_ACTIONS -> CastEvent.Kind.PLAYBACK_CONTROL
            actionText in STATUS_QUERY_ACTIONS -> CastEvent.Kind.STATUS_QUERY
            else -> CastEvent.Kind.SOAP_CONTROL
        }
        if (kind == CastEvent.Kind.STATUS_QUERY && !shouldLogStatusQuery()) return
        logCastEvent(
            kind,
            "收到 SOAP action：service=$service，action=$actionText，from=$fromIp，UA=$uaText"
        )
    }

    fun logGena(method: String, fromIp: String, callback: String, nt: String, status: String) {
        val detail = buildString {
            append(fromIp.ifBlank { "?" }).append(' ').append(method)
            if (nt.isNotBlank()) append("，NT=").append(nt)
            if (callback.isNotBlank()) append("，CALLBACK=").append(callback.take(120))
            append("，status=").append(status)
        }
        logCastEvent(CastEvent.Kind.EVENT_SUBSCRIBE, detail)
    }

    fun logServiceHealth(
        module: String,
        level: ServiceHealthEvent.Level,
        event: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val safeMessage = sanitizeHealthText(message, max = 240)
        val safeException = sanitizeHealthText(throwable?.message.orEmpty(), max = MAX_EXCEPTION_MESSAGE)
        synchronized(serviceHealthLogs) {
            push(
                serviceHealthLogs,
                ServiceHealthEvent(
                    timeMs = System.currentTimeMillis(),
                    module = sanitizeHealthText(module, max = 48).ifBlank { "unknown" },
                    level = level,
                    event = sanitizeHealthText(event, max = 80).ifBlank { "unknown" },
                    message = safeMessage,
                    exceptionMessage = safeException
                ),
                MAX_SERVICE_HEALTH
            )
        }
    }

    private fun sanitizeHealthText(text: String, max: Int): String {
        if (text.isBlank()) return ""
        val singleLine = text.replace('\r', ' ').replace('\n', ' ').trim()
        return singleLine.take(max.coerceAtLeast(0))
    }

    fun logPlayerState(from: String, to: String, uri: String, positionMs: Long, durationMs: Long) {
        logCastEvent(
            CastEvent.Kind.PLAYER_STATE,
            "$from → $to，uri=${uri.take(120)}，position=${positionMs}ms，duration=${durationMs}ms"
        )
    }

    private fun shouldLogStatusQuery(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(queryThrottleLock) {
            queryLogCount++
            if (now - lastQueryLogMs >= STATUS_QUERY_LOG_INTERVAL_MS || queryLogCount % STATUS_QUERY_LOG_EVERY_N == 1) {
                lastQueryLogMs = now
                return true
            }
        }
        return false
    }

    /** 云同步上传调试日志：复用「网络诊断」页面展示，避免 Toast 被截断。 */
    fun logCloudSync(message: String) {
        // 用户可见的 Gitee 仓库名脱敏为 *******（仅影响展示文本，真实 HTTP 请求地址不变）。
        val masked = redactRepo(message)
        synchronized(cloudSyncLogs) { push(cloudSyncLogs, CloudSyncLog(System.currentTimeMillis(), masked)) }
    }

    /** v1.1.118 新增：追加一条投屏事件流水。可从任意线程调用。 */
    fun logCastEvent(kind: CastEvent.Kind, detail: String) {
        synchronized(castEvents) {
            push(castEvents, CastEvent(System.currentTimeMillis(), kind, detail), MAX_CAST_EVENTS)
        }
    }

    /** v1.1.118 新增：清空所有诊断日志（点击「清空日志」按钮触发）。 */
    fun clearAll() {
        synchronized(msearchLogs) { msearchLogs.clear() }
        synchronized(respLogs) { respLogs.clear() }
        synchronized(descLogs) { descLogs.clear() }
        synchronized(httpGetLogs) { httpGetLogs.clear() }
        synchronized(notifyLogs) { notifyLogs.clear() }
        synchronized(soapResultLogs) { soapResultLogs.clear() }
        synchronized(cloudSyncLogs) { cloudSyncLogs.clear() }
        synchronized(serviceHealthLogs) { serviceHealthLogs.clear() }
        synchronized(castEvents) { castEvents.clear() }
        synchronized(queryThrottleLock) {
            queryLogCount = 0
            lastQueryLogMs = 0L
        }
    }

    /**
     * 把云同步日志里用户可见的 Gitee 仓库标识脱敏为 *******。
     * 仅替换展示字符串，不影响 GiteeApi 里用于真实请求的 BASE_URL 常量。
     * - 匹配 `owner/repo` 组合（如 bdCasttv/video-source）
     * - 兼容 gitee 域名下的 `.../repos/owner/repo/...` 与 `gitee.com/owner/repo/...` 形态
     */
    private fun redactRepo(text: String): String {
        var result = text
        // gitee API 形态：.../repos/<owner>/<repo>/...
        result = result.replace(
            Regex("(gitee\\.com/api/v5/repos/)[^/\\s]+/[^/\\s?]+"),
            "$1*******"
        )
        // gitee raw/网页形态：gitee.com/<owner>/<repo>/...
        result = result.replace(
            Regex("(gitee\\.com/)(?!api/)[^/\\s]+/[^/\\s?]+"),
            "$1*******"
        )
        // 兜底：裸 owner/repo 组合
        result = result.replace("bdCasttv/video-source", "*******")
        // v1.1.106：项目名 / project name / repo name 也一并脱敏。
        result = result.replace("video-source", "*******")
        result = result.replace("bdCasttv", "*******")
        return result
    }

    private fun fmt(t: Long): String = timeFmt.format(Date(t))

    private val MEDIA_URI_ACTIONS = setOf("SetAVTransportURI", "SetNextAVTransportURI")
    private val PLAYBACK_CONTROL_ACTIONS = setOf("Play", "Pause", "Stop", "Seek", "Next", "Previous")
    private val STATUS_QUERY_ACTIONS = setOf(
        "GetTransportInfo", "GetPositionInfo", "GetMediaInfo",
        "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions"
    )
    private const val STATUS_QUERY_LOG_INTERVAL_MS = 60_000L // 1分钟
    private const val STATUS_QUERY_LOG_EVERY_N = 10 // 或每10次

    /** 当前绑定网卡/IP 信息（用于「网络诊断」Tab：绑定网卡）。 */
    fun snapshotInterfacesText(): String {
        val sb = StringBuilder()
        val ifs = interfaces
        if (ifs.isEmpty()) {
            sb.append("- (无)\n")
        } else {
            for (i in ifs) sb.append("- ").append(i.name).append("  ").append(i.ip).append('\n')
        }
        return sb.toString()
    }

    /** 结构化投屏事件流水（时间顺序，用于「投屏事件流水」Tab）。 */
    fun snapshotCastEventsText(): String {
        val sb = StringBuilder()
        val evs = synchronized(castEvents) { castEvents.toList() }
        if (evs.isEmpty()) {
            sb.append("- (暂无，等待手机发起投屏)\n")
        } else {
            for (e in evs) {
                sb.append("[").append(fmt(e.timeMs)).append("] ")
                    .append(e.kind.label)
                if (e.detail.isNotEmpty()) sb.append("（").append(e.detail).append("）")
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** SSDP NOTIFY alive/byebye（用于「NOTIFY」Tab）。 */
    fun snapshotNotifyText(): String {
        val sb = StringBuilder()
        val ns = synchronized(notifyLogs) { notifyLogs.toList() }
        if (ns.isEmpty()) sb.append("- (暂无)\n")
        else ns.takeLast(80).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  ")
            .append(it.type).append("  NT=").append(it.nt).append("  IP=").append(it.ip).append('\n') }
        return sb.toString()
    }

    /** SOAP 调用结果/响应（用于「SOAP 响应」Tab）。 */
    fun snapshotSoapResultsText(): String {
        val sb = StringBuilder()
        val ss = synchronized(soapResultLogs) { soapResultLogs.toList() }
        if (ss.isEmpty()) sb.append("- (暂无)\n")
        else ss.takeLast(80).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  ")
            .append(it.service).append('#').append(it.action).append("  ").append(it.status)
            .append(if (it.detail.isBlank()) "" else "  ${it.detail}").append('\n') }
        return sb.toString()
    }

    /** 服务健康日志（用于「服务健康」Tab）。 */
    fun snapshotServiceHealthText(): String {
        val sb = StringBuilder()
        val hs = synchronized(serviceHealthLogs) { serviceHealthLogs.toList() }
        if (hs.isEmpty()) {
            sb.append("- (暂无)\n")
        } else {
            hs.takeLast(MAX_SERVICE_HEALTH).forEach {
                sb.append("- ").append(fmt(it.timeMs)).append("  ")
                    .append(it.level.name).append("  ")
                    .append(it.module).append('#').append(it.event)
                if (it.message.isNotBlank()) sb.append("  ").append(it.message)
                if (it.exceptionMessage.isNotBlank()) sb.append("  exception=").append(it.exceptionMessage)
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** 全量快照：历史兼容（其他地方若仍依赖老格式，可继续用）。 */
    fun snapshotText(): String {
        val sb = StringBuilder()
        sb.append("【当前绑定网卡/IP】\n")
        sb.append(snapshotInterfacesText())

        sb.append("\n【投屏事件流水（时间顺序）】\n")
        sb.append(snapshotCastEventsText())

        sb.append("\n【收到的 SSDP M-SEARCH】\n")
        val ms = synchronized(msearchLogs) { msearchLogs.toList() }
        if (ms.isEmpty()) sb.append("- (暂无)\n")
        else ms.takeLast(40).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  ")
            .append(it.fromIp).append("  ST=").append(it.st).append('\n') }

        sb.append("\n【发出的 SSDP 200 OK 回包】\n")
        val rs = synchronized(respLogs) { respLogs.toList() }
        if (rs.isEmpty()) sb.append("- (暂无)\n")
        else rs.takeLast(40).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  → ")
            .append(it.toIp).append("  LOCATION=").append(it.location).append('\n') }

        sb.append("\n【收到的 /description.xml GET】\n")
        val ds = synchronized(descLogs) { descLogs.toList() }
        if (ds.isEmpty()) sb.append("- (暂无)\n")
        else ds.takeLast(40).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  ")
            .append(it.fromIp).append('\n') }

        sb.append("\n【发出的 NOTIFY alive/byebye】\n")
        sb.append(snapshotNotifyText())

        sb.append("\n【HTTP GET / SCPD 请求】\n")
        val hs = synchronized(httpGetLogs) { httpGetLogs.toList() }
        if (hs.isEmpty()) sb.append("- (暂无)\n")
        else hs.takeLast(40).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  ")
            .append(it.fromIp).append("  ").append(it.uri).append("  ").append(it.status).append('\n') }

        sb.append("\n【SOAP 响应状态】\n")
        sb.append(snapshotSoapResultsText())

        sb.append("\n【服务健康】\n")
        sb.append(snapshotServiceHealthText())

        sb.append("\n【云同步上传调试】\n")
        val cs = synchronized(cloudSyncLogs) { cloudSyncLogs.toList() }
        if (cs.isEmpty()) sb.append("- (暂无)\n")
        else cs.takeLast(80).forEach { sb.append("- ").append(fmt(it.timeMs)).append("  ")
            .append(it.message).append('\n') }

        return sb.toString()
    }
}
