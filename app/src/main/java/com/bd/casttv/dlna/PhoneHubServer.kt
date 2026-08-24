package com.bd.casttv.dlna

import android.util.Log
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.settings.Settings
import com.bd.casttv.sync.GiteeSyncManager
import com.bd.casttv.util.PasswordUtil
import com.bd.casttv.webparse.JsonAdapterEventBus
import com.bd.casttv.webparse.JsonAdapterSpecBuilder
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebParsePageType
import com.bd.casttv.webparse.WebParseRequestBus
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 「手机交互中心」局域网 HTTP 服务（NanoHTTPD）。
 *
 * 手机端扫码后从浏览器进入本服务提供的四个页面：
 * - 我的收藏 `/favorites`      —— 查看/编辑合集数据、导入/导出 CSV、加入稍后播放
 * - 历史记录 `/history`        —— 查看/编辑历史、加入稍后播放
 * - 稍后播放 `/queue`          —— 拖动排序 / 移除 / 一键推送到电视（覆盖 or 追加）
 * - 设置    `/settings`        —— 查看/编辑设置，标注需要重启服务生效的项
 *
 * 所有页面均内嵌 CSS+JS（蜡笔小新主题：深色背景 #0B0F1A、宝蓝 #4089FD 主色、
 * 蜡笔黄 #FFD23F 描边/按钮、圆角卡片），不依赖任何外部资源，离线可用。
 *
 * ⚠ 端口独立性：默认端口 8899（EXPORT / IMPORT 使用的 8896/8085 之外）；
 * 与 DLNA(49152 / 8895)、AirPlay、SSDP(1900) 全部隔离，避免影响现有投屏能力。
 *
 * 线程模型：NanoHTTPD 工作线程处理请求；数据读写通过 [FavoritesStore] / [PlayQueueStore] /
 * [PlaybackController] 提供的线程安全接口进行。播放推送通过 [pushPlayHandler] 抛给主线程
 * 处理，不阻塞 HTTP 工作线程。
 */
class PhoneHubServer(
    port: Int,
    private val favoritesStore: FavoritesStore,
    private val queueStore: PlayQueueStore,
    private val settings: Settings,
    /** 主线程推送回调：mode = "override" | "append"，triggerPlayFirst = true 时开始播放。 */
    private val pushPlayHandler: (mode: String, triggerPlayFirst: Boolean) -> Unit,
    /** 单条播放回调：手机端点击「播放」按钮时，抛给主线程直接在 TV 端播放该条目。 */
    private val playSingleHandler: (uri: String, title: String) -> Unit,
    /** 设置项发生变化时回调 UI（例如提示重启服务）。 */
    private val settingsChangeHandler: (key: String, needRestart: Boolean) -> Unit
) : NanoHTTPD(port) {

    private val syncManager = GiteeSyncManager(favoritesStore)
    private val cloudWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhoneHubCloudSyncWorker").apply { isDaemon = true }
    }

    // 云同步「查询类接口」必须避免在 NanoHTTPD worker 线程里直接做网络 IO，
    // 否则会拖慢手机端页面响应，甚至影响 DLNA 同端口下的其他请求。
    private val cloudIndexLock = Any()
    @Volatile private var cloudIndexCache: GiteeSyncManager.CloudIndex? = null
    @Volatile private var cloudIndexStatus: String = "idle" // idle/loading/ready/error
    @Volatile private var cloudIndexError: String? = null
    @Volatile private var cloudIndexLastFetchMs: Long = 0L

    private fun ensureCloudIndexAsync() {
        val now = System.currentTimeMillis()
        synchronized(cloudIndexLock) {
            val stale = cloudIndexCache == null || (now - cloudIndexLastFetchMs) > CLOUD_INDEX_STALE_MS
            if (!stale) return
            if (cloudIndexStatus == "loading") return

            cloudIndexStatus = "loading"
            cloudIndexError = null
            cloudWorker.execute {
                val result = try { syncManager.fetchCloudIndexConfig() } catch (_: Throwable) { null }
                synchronized(cloudIndexLock) {
                    cloudIndexLastFetchMs = System.currentTimeMillis()
                    if (result != null) {
                        cloudIndexCache = result
                        cloudIndexStatus = "ready"
                        cloudIndexError = null
                    } else {
                        cloudIndexCache = null
                        cloudIndexStatus = "error"
                        cloudIndexError = "fetchCloudIndex failed"
                    }
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        Log.d(TAG, "${session.method} ${session.uri}")
        // 新框架「已连接设备」跟踪：命中一次即登记远端 IP + UA；不影响任何业务流程。
        try {
            val remote = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
            val ua = session.headers["user-agent"]
            com.bd.casttv.ui.framework.PhoneHubClientTracker.recordHit(remote, ua)
        } catch (_: Throwable) { /* tracker 完全内存态，异常不应影响 HTTP 响应 */ }
        return try {
            when (session.method) {
                Method.GET -> handleGet(uri, session)
                Method.POST -> handlePost(uri, session)
                Method.OPTIONS -> preflight()
                else -> plain(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed uri=$uri", t)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", t.message ?: "internal"))
        }
    }

    // ------------------------------------------------------------------ GET
    private fun handleGet(uri: String, session: IHTTPSession): Response {
        return when {
            uri.isEmpty() || uri == "/" -> html(HtmlPages.home())
            uri == "/favorites" -> html(HtmlPages.favorites())
            uri == "/history" -> html(HtmlPages.history())
            uri == "/queue" -> html(HtmlPages.queue())
            uri == "/cloud" -> html(HtmlPages.cloud())
            uri == "/parse" -> html(HtmlPages.parse())
            uri == "/upload-json-adapter" -> html(HtmlPages.uploadJsonAdapter())
            uri == "/download-json-adapter-spec" -> jsonAdapterSpecDownload(session)
            uri == "/settings" -> html(HtmlPages.settings())
            uri == "/wallpaper" -> html(HtmlPages.wallpaper())

            // ---------- REST 查询 ----------
            uri == "/api/favorites" -> json(Response.Status.OK, favoritesJson())
            uri == "/api/favorites/collection" -> {
                val id = queryParam(session, "id")
                if (id.isBlank()) badRequest("缺少 id") else json(Response.Status.OK, favoriteCollectionJson(id))
            }
            uri == "/api/history" -> json(Response.Status.OK, historyJson())
            uri == "/api/queue" -> json(Response.Status.OK, queueJson())
            uri == "/api/settings" -> json(Response.Status.OK, settingsJson())
            uri == "/api/cloud/status" -> json(Response.Status.OK, cloudStatusJson())
            // v1.1.112：手机端云同步页新增接口，与 TV 端 CloudSyncDialog 语义对齐。
            uri == "/api/cloud/upload_list" -> json(Response.Status.OK, cloudUploadListJson())
            uri == "/api/cloud/download_list" -> json(Response.Status.OK, cloudDownloadListJson())
            uri == "/api/player/status" -> json(Response.Status.OK, playerStatusJson())
            uri == "/api/player/thumbnail" -> playerThumbnailResponse()
            uri == "/api/wallpaper/status" -> json(Response.Status.OK, wallpaperStatusJson())

            // ---------- 收藏导出 ----------
            uri == "/export.csv" -> {
                val csv = favoritesStore.exportToCsv()
                val fileName = "favorites_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
                val res = newFixedLengthResponse(Response.Status.OK, "text/csv; charset=utf-8", csv)
                res.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                cors(res)
            }
            uri == "/export.json" -> {
                val fileName = "favorites_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
                val res = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", favoritesExportJson().toString())
                res.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                cors(res)
            }

            else -> plain(Response.Status.NOT_FOUND, "not found: $uri")
        }
    }

    // ------------------------------------------------------------------ POST
    private fun handlePost(uri: String, session: IHTTPSession): Response {
        // 读取 body（一次性，NanoHTTPD 需要显式调用 parseBody）
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Throwable) {}
        val body = files["postData"] ?: ""

        return when (uri) {
            "/api/queue/add" -> {
                val o = tryJson(body) ?: return badRequest()
                val title = o.optString("title")
                val u = o.optString("uri")
                val src = o.optString("source")
                val id = queueStore.add(title, u, src)
                json(Response.Status.OK, okObj().put("id", id))
            }
            "/api/queue/remove" -> {
                val o = tryJson(body) ?: return badRequest()
                val ok = queueStore.remove(o.optString("id"))
                json(Response.Status.OK, okObj().put("removed", ok))
            }
            "/api/queue/reorder" -> {
                val o = tryJson(body) ?: return badRequest()
                val arr = o.optJSONArray("ids") ?: JSONArray()
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) ids.add(arr.optString(i))
                queueStore.reorder(ids)
                json(Response.Status.OK, okObj())
            }
            "/api/queue/clear" -> {
                queueStore.clear()
                json(Response.Status.OK, okObj())
            }
            "/api/queue/push" -> {
                val o = tryJson(body) ?: return badRequest()
                val mode = o.optString("mode", "override").ifBlank { "override" }
                val triggerPlay = o.optBoolean("triggerPlayFirst", true)
                pushPlayHandler(mode, triggerPlay)
                json(Response.Status.OK, okObj().put("mode", mode))
            }

            "/api/history/remove" -> {
                val o = tryJson(body) ?: return badRequest()
                val ok = PlaybackController.removeHistoryByUri(o.optString("uri"))
                json(Response.Status.OK, okObj().put("removed", ok))
            }
            "/api/player/play" -> {
                val o = tryJson(body) ?: return badRequest()
                val playUri = o.optString("uri").trim()
                if (playUri.isBlank()) return badRequest("缺少 uri")
                val title = o.optString("title").ifBlank { playUri.substringAfterLast('/').substringBefore('?').ifBlank { "手机端播放" } }
                // 手机端 HTTP「播放」不是标准 DLNA SetAVTransportURI 流程；若 PlayerActivity 已在前台，
                // 旧 MainActivity 的 launchPlayer 会因存在 command callback 而跳过 startActivity。
                // 这里先同步 PlaybackController 状态并下发 onSetUri，确保已有播放器实例能立即切源播放。
                PlaybackController.onSetAvTransportUri(playUri, title, "phone_hub")
                playSingleHandler(playUri, title)
                json(Response.Status.OK, okObj())
            }
            "/api/web_parse/submit" -> {
                val o = tryJson(body) ?: return badRequest()
                val u = o.optString("url").trim()
                val pageType = WebParsePageType.from(o.optString("type", "detail"))
                if (u.isBlank()) return badRequest("缺少 url")
                WebParseRequestBus.submit(u, pageType)
                json(Response.Status.OK, okObj().put("message", "已发送到电视端"))
            }
            "/api/history/clear" -> {
                PlaybackController.clearHistoryAll()
                json(Response.Status.OK, okObj())
            }

            "/api/favorites/rename_collection" -> {
                val o = tryJson(body) ?: return badRequest()
                val cid = o.optString("id")
                val name = jsonString(o, "name")
                val res = favoritesStore.renameCollection(cid, name)
                json(Response.Status.OK, okObj().put("renamed", res == FavoritesStore.OpResult.SUCCESS).put("code", res.name))
            }
            "/api/favorites/delete_collection" -> {
                val o = tryJson(body) ?: return badRequest()
                val cid = o.optString("id")
                val res = favoritesStore.deleteCollection(cid)
                json(Response.Status.OK, okObj().put("deleted", res == FavoritesStore.OpResult.SUCCESS).put("code", res.name))
            }
            "/api/favorites/add_collection" -> {
                val o = tryJson(body) ?: return badRequest()
                val name = jsonString(o, "name")
                val pair = favoritesStore.createCollection(name.ifBlank { "新合集" })
                json(Response.Status.OK, okObj()
                    .put("id", pair.second ?: "")
                    .put("code", pair.first.name))
            }
            "/api/favorites/rename_item" -> {
                val o = tryJson(body) ?: return badRequest()
                val cid = o.optString("collectionId")
                val u = o.optString("uri")
                val title = jsonString(o, "title")
                val res = favoritesStore.updateItemTitle(cid, u, title)
                json(Response.Status.OK, okObj().put("renamed", res == FavoritesStore.OpResult.SUCCESS).put("code", res.name))
            }
            "/api/favorites/delete_item" -> {
                val o = tryJson(body) ?: return badRequest()
                val cid = o.optString("collectionId")
                val u = o.optString("uri")
                val res = favoritesStore.removeItem(cid, u)
                json(Response.Status.OK, okObj().put("deleted", res == FavoritesStore.OpResult.SUCCESS).put("code", res.name))
            }
            "/api/favorites/move_item" -> {
                val o = tryJson(body) ?: return badRequest()
                val fromId = o.optString("fromCollectionId")
                val toId = o.optString("toCollectionId")
                val u = o.optString("uri")
                val res = favoritesStore.moveItem(fromId, u, toId)
                json(Response.Status.OK, okObj().put("moved", res == FavoritesStore.OpResult.SUCCESS).put("code", res.name))
            }

            "/api/settings/update" -> {
                val o = tryJson(body) ?: return badRequest()
                val key = o.optString("key")
                val value = o.optString("value")
                val needRestart = applySettingsUpdate(key, value)
                settingsChangeHandler(key, needRestart)
                json(Response.Status.OK, okObj().put("needRestart", needRestart))
            }

            "/api/cloud/upload" -> cloudJson(cloudUploadJson())
            "/api/cloud/download" -> cloudJson(cloudDownloadJson())

            // v1.1.112：手机端云同步页新增接口。
            "/api/cloud/verify_password" -> {
                val o = tryJson(body) ?: return badRequest()
                val id = o.optString("id")
                val pw = o.optString("password")

                ensureCloudIndexAsync()
                val index = synchronized(cloudIndexLock) { cloudIndexCache }
                if (index == null) {
                    return json(Response.Status.OK, okObj().put("status", "loading"))
                }

                val meta = index.collections.firstOrNull { it.id == id }
                val matched = meta != null && PasswordUtil.matches(pw, meta.passwordHash)
                json(Response.Status.OK, okObj().put("matched", matched))
            }
            "/api/cloud/upload_selected" -> cloudJson(cloudUploadSelectedJson(body))
            "/api/cloud/download_selected" -> cloudJson(cloudDownloadSelectedJson(body))

            "/upload-json-adapter" -> handleJsonAdapterUpload(files)
            "/upload-json-adapter-text" -> handleJsonAdapterText(body, session)

            "/import.csv" -> {
                // CSV 上传：从表单文件字段中读取本地 tmp 文件路径再读文本。
                val tmpPath = files["csv"] ?: files["file"] ?: files["upload"]
                if (tmpPath.isNullOrBlank()) return badRequest("缺少 csv 字段")
                val csv = try { java.io.File(tmpPath).readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                if (csv.isBlank()) return badRequest("CSV 内容为空")
                val preview = favoritesStore.validateImportCsv(csv)
                val result = when (preview) {
                    is FavoritesStore.ImportPreview.Invalid -> Triple(false, preview.reason, 0)
                    is FavoritesStore.ImportPreview.Valid -> {
                        val res = favoritesStore.applyImportedCsv(preview.normalizedJson)
                        val ok = res == FavoritesStore.OpResult.SUCCESS
                        Triple(ok, if (ok) "已导入 ${preview.itemCount} 条到 ${preview.collectionCount} 个合集" else "写入失败：${res.name}", preview.itemCount)
                    }
                }
                json(Response.Status.OK, okObj()
                    .put("valid", result.first)
                    .put("message", result.second)
                    .put("count", result.third))
            }

            "/api/wallpaper/upload" -> {
                val tmpPath = files["wallpaper"] ?: files["file"] ?: files["upload"]
                if (tmpPath.isNullOrBlank()) return badRequest("缺少 wallpaper 字段")
                val srcFile = java.io.File(tmpPath)
                if (!srcFile.exists() || srcFile.length() == 0L) return badRequest("文件为空")
                val nextSlot = com.bd.casttv.ui.framework.WallpaperManager.nextCustomSlot(
                    favoritesStore.appContext
                )
                if (nextSlot == null) {
                    return json(Response.Status.OK, JSONObject()
                        .put("ok", false)
                        .put("error", "自定义壁纸最多 13 张，请先删除部分壁纸"))
                }
                try {
                    srcFile.copyTo(nextSlot, overwrite = true)
                    json(Response.Status.OK, okObj()
                        .put("message", "上传成功：${nextSlot.name}")
                        .put("filename", nextSlot.name))
                } catch (e: Throwable) {
                    json(Response.Status.OK, JSONObject()
                        .put("ok", false)
                        .put("error", "保存文件失败：${e.message}"))
                }
            }

            "/api/wallpaper/delete" -> {
                val o = tryJson(body) ?: return badRequest()
                val name = o.optString("name")
                if (name.isBlank() || !name.startsWith("custom_wallpaper_")) {
                    return badRequest("无效的文件名")
                }
                val wallpaperDir = com.bd.casttv.ui.framework.WallpaperManager.customWallpaperDir(
                    favoritesStore.appContext
                )
                val target = java.io.File(wallpaperDir, name)
                val deleted = target.exists() && target.delete()
                json(Response.Status.OK, okObj().put("deleted", deleted))
            }

            else -> plain(Response.Status.NOT_FOUND, "not found: $uri")
        }
    }

    private fun handleJsonAdapterUpload(files: Map<String, String>): Response {
        val tmpPath = files["json"] ?: files["file"] ?: files["upload"]
        if (tmpPath.isNullOrBlank()) return html(HtmlPages.uploadJsonAdapterResult(false, "缺少 JSON 文件"))
        val src = File(tmpPath)
        if (!src.exists() || src.length() == 0L) return html(HtmlPages.uploadJsonAdapterResult(false, "文件为空"))
        return saveJsonAdapterText(runCatching { src.readText(Charsets.UTF_8) }.getOrDefault(""), fromPaste = false, pageKind = ParsePageKind.DETAIL)
    }

    private fun handleJsonAdapterText(body: String, session: IHTTPSession): Response {
        val text = (session.parameters["jsonText"]?.firstOrNull().orEmpty().ifBlank {
            formValue(body, "jsonText")
        }).trim()
        val pageKindValue = (session.parameters["pageKind"]?.firstOrNull().orEmpty().ifBlank {
            formValue(body, "pageKind")
        }).trim().lowercase(Locale.US)
        val pageKind = when (pageKindValue) {
            "list", "list_page", "列表页" -> ParsePageKind.LIST
            else -> ParsePageKind.DETAIL
        }
        if (text.isBlank()) return html(HtmlPages.uploadJsonAdapterResult(false, "请先粘贴 JSON 内容"))
        return saveJsonAdapterText(text, fromPaste = true, pageKind = pageKind)
    }

    private fun saveJsonAdapterText(text: String, fromPaste: Boolean, pageKind: ParsePageKind): Response {
        if (text.isBlank()) return html(HtmlPages.uploadJsonAdapterResult(false, if (fromPaste) "请先粘贴 JSON 内容" else "文件为空"))
        return try {
            val info = if (pageKind == ParsePageKind.LIST) {
                RuleBasedAdapter.saveRule(favoritesStore.appContext, text, "", ParsePageKind.LIST)
            } else {
                RuleBasedAdapter.saveRule(favoritesStore.appContext, text)
            }
            JsonAdapterEventBus.notifyImported(info.fileName, pageKind)
            html(HtmlPages.uploadJsonAdapterResult(true, if (fromPaste) "规则已保存，TV 端正在解析…" else "导入成功：${info.name} · ${info.version}，电视端正在重新解析"))
        } catch (_: JSONException) {
            html(HtmlPages.uploadJsonAdapterResult(false, "JSON 格式错误，请检查内容"))
        } catch (t: Throwable) {
            html(HtmlPages.uploadJsonAdapterResult(false, t.message ?: "JSON 格式错误，请检查内容"))
        }
    }

    private fun formValue(body: String, key: String): String {
        if (body.isBlank()) return ""
        return body.split('&').firstNotNullOfOrNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2 && pieces[0] == key) {
                runCatching { URLDecoder.decode(pieces[1], "UTF-8") }.getOrDefault(pieces[1])
            } else null
        }.orEmpty()
    }

    // ------------------------------------------------------------------ JSON payloads
    private fun favoritesJson(): JSONObject {
        // 只取轻量元信息快照，不触发各合集 items 大对象加载；JSONObject 构造在 Store 锁外完成。
        val infos = favoritesStore.collectionsInfo()
        val defaultId = infos.firstOrNull { it.isDefault }?.id.orEmpty()
        val root = JSONObject()
        val cols = JSONArray()
        for (c in infos) {
            val jo = JSONObject()
            jo.put("id", c.id)
            jo.put("name", c.name)
            jo.put("isDefault", c.id == defaultId)
            jo.put("isPreset", c.isPreset)
            jo.put("itemCount", c.itemCount)
            jo.put("updatedAt", c.updatedAt)
            cols.put(jo)
        }
        root.put("collections", cols)
        root.put("defaultCollectionId", defaultId)
        return root
    }

    private fun favoriteCollectionJson(id: String): JSONObject {
        val c = favoritesStore.collection(id)
        return if (c == null) {
            JSONObject().put("ok", false).put("error", "collection not found").put("id", id)
        } else {
            JSONObject()
                .put("ok", true)
                .put("collection", favoriteCollectionObject(c))
        }
    }

    private fun favoritesExportJson(): JSONObject {
        val root = JSONObject()
        val cols = JSONArray()
        val defaultId = favoritesStore.defaultCollectionId()
        for (c in favoritesStore.collections()) {
            cols.put(favoriteCollectionObject(c).put("isDefault", c.id == defaultId))
        }
        root.put("collections", cols)
        root.put("defaultCollectionId", defaultId)
        return root
    }

    private fun favoriteCollectionObject(c: FavoritesStore.FavoriteCollection): JSONObject {
        val jo = JSONObject()
        jo.put("id", c.id)
        jo.put("name", c.name)
        jo.put("isDefault", c.isDefault)
        jo.put("isPreset", c.isPreset)
        jo.put("createdAt", c.createdAt)
        jo.put("updatedAt", c.updatedAt)
        val items = JSONArray()
        for (item in c.items) {
            val io = JSONObject()
            io.put("title", item.title)
            io.put("uri", item.uri)
            io.put("source", item.source)
            io.put("time", item.time)
            items.put(io)
        }
        jo.put("items", items)
        jo.put("itemCount", c.items.size)
        return jo
    }

    private fun cloudStatusJson(): JSONObject {
        val local = favoritesStore.collectionsInfo()

        ensureCloudIndexAsync()
        val snapshot = synchronized(cloudIndexLock) { cloudIndexCache }
        val status = synchronized(cloudIndexLock) { cloudIndexStatus }
        val error = synchronized(cloudIndexLock) { cloudIndexError }

        val cloud = JSONArray()
        snapshot?.collections?.forEach { c ->
            cloud.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("type", c.type)
                    .put("updatedAt", c.updatedAt)
            )
        }

        val resp = okObj()
            .put("localCount", local.size)
            .put("localItemCount", local.sumOf { it.itemCount })
            .put("cloudAvailable", snapshot != null)
            .put("cloudCount", snapshot?.collections?.size ?: 0)
            .put("cloudCollections", cloud)

        if (snapshot == null) {
            resp.put("status", if (status == "error") "error" else "loading")
            if (status == "error") resp.put("message", error ?: "获取云端列表失败")
        }
        return resp
    }

    private fun cloudUploadJson(): JSONObject {
        val totalCount = favoritesStore.collectionsInfo().size
        enqueueCloudTask("upload_all") {
            val collections = favoritesStore.collections()
            val result = syncManager.uploadCollections(collections)
            Log.i(TAG, "cloud upload_all finished successCount=${result.successCount}/${collections.size}, error=${result.errorMessage}")
        }
        return okObj()
            .put("status", "processing")
            .put("success", true)
            .put("successCount", 0)
            .put("totalCount", totalCount)
            .put("message", "上传任务已入队，正在后台处理")
    }

    private fun cloudDownloadJson(): JSONObject {
        enqueueCloudTask("download_all") {
            val cloudIndex = syncManager.fetchCloudIndex()
            if (cloudIndex == null) {
                Log.w(TAG, "cloud download_all skipped: fetchCloudIndex failed")
                return@enqueueCloudTask
            }
            val ids = cloudIndex.map { it.id }
            val count = syncManager.downloadCollections(ids, cloudIndex)
            Log.i(TAG, "cloud download_all finished downloadCount=$count/${ids.size}")
        }
        return okObj()
            .put("status", "processing")
            .put("success", true)
            .put("downloadCount", 0)
            .put("message", "下载任务已入队，正在后台处理")
    }

    // ---------------- v1.1.112 手机端云同步页支撑接口 ----------------

    /** 上传 Tab 列表：保留现有展示逻辑——自己创建的合集（private/shared）可见，非本人创建仅 shared 可见。 */
    private fun cloudUploadListJson(): JSONObject {
        val local = favoritesStore.collectionsInfo()
        val currentCreatorId = favoritesStore.currentCreatorId()

        ensureCloudIndexAsync()
        val snapshot = synchronized(cloudIndexLock) { cloudIndexCache }
        if (snapshot == null) {
            // 让前端轮询等待，避免 HTTP worker 线程被网络 IO 阻塞。
            return okObj().put("status", "loading").put("collections", JSONArray())
        }

        val cloudById = snapshot.collections.associateBy { it.id }
        val arr = JSONArray()
        for (c in local) {
            val isCreator = c.creatorId.isNotBlank() && c.creatorId == currentCreatorId
            val isPrivate = c.type == FavoritesStore.TYPE_PRIVATE
            val canShow = isCreator || c.type == FavoritesStore.TYPE_SHARED
            if (!canShow) continue
            val cloudHash = cloudById[c.id]?.passwordHash.orEmpty()
            val hadCloudHash = cloudHash.isNotBlank()
            val locked = (hadCloudHash || c.passwordHash.isNotBlank()) && !isPrivate
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("itemCount", c.itemCount)
                    .put("type", c.type)
                    .put("private", isPrivate)
                    .put("locked", locked)
                    .put("hadCloudHash", hadCloudHash)
                    .put("isCreator", isCreator)
                    .put("canOperateLock", isCreator && !isPrivate)
            )
        }
        return okObj().put("collections", arr)
    }

    /**
     * 下载 Tab 列表：预置合集独立分组（直接可勾选、无需解锁）；非预置只展示 shared、隐藏 private。
     * 同时返回上限配置（预置固定 5 + 云端 maxNonPresetDownload）。
     */
    private fun cloudDownloadListJson(): JSONObject {
        ensureCloudIndexAsync()
        val config = synchronized(cloudIndexLock) { cloudIndexCache }

        if (config == null) {
            // 让前端轮询等待（避免阻塞 HTTP worker）。
            return okObj().put("status", "loading")
        }

        val cloudIndex = config.collections
        val maxNonPreset = config.maxNonPresetDownload.coerceAtLeast(0)
        val presetCount = GiteeSyncManager.PRESET_DOWNLOAD_COUNT
        val maxTotal = presetCount + maxNonPreset

        val localPreset = favoritesStore.collectionsInfo().filter { it.isPreset || it.isDefault }
        val presetIds = localPreset.map { it.id }.toSet()
        val presetNames = localPreset.map { it.name }.toSet()
        val cloudById = cloudIndex.associateBy { it.id }

        val presetGroup = JSONArray()
        localPreset.mapNotNull { cloudById[it.id] }.forEach { presetGroup.put(cloudCollectionRow(it)) }

        val nonPresetGroup = JSONArray()
        cloudIndex
            .filterNot { it.id in presetIds || it.name in presetNames }
            .filter { it.type == FavoritesStore.TYPE_SHARED }
            .forEach { nonPresetGroup.put(cloudCollectionRow(it)) }

        return okObj()
            .put("available", true)
            .put("presetGroup", presetGroup)
            .put("nonPresetGroup", nonPresetGroup)
            .put("maxNonPresetDownload", maxNonPreset)
            .put("presetDownloadCount", presetCount)
            .put("maxTotalDownload", maxTotal)
    }

    private fun cloudCollectionRow(c: GiteeSyncManager.CloudCollection): JSONObject =
        JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("type", c.type)
            .put("encrypted", c.passwordHash.isNotBlank())
            .put("updatedAt", c.updatedAt)

    /** 上传所选合集：仅允许上传自己创建的合集与他人 shared 合集；锁操作仅合集创建者本人可改。 */
    private fun cloudUploadSelectedJson(body: String): JSONObject {
        val o = tryJson(body) ?: return okObj().put("success", false).put("message", "参数错误")
        val itemsArr = o.optJSONArray("items") ?: JSONArray()
        val currentCreatorId = favoritesStore.currentCreatorId()
        val collectionInfoById = favoritesStore.collectionsInfo().associateBy { it.id }
        val actionById = linkedMapOf<String, GiteeSyncManager.PasswordAction>()
        val selectedIds = mutableSetOf<String>()
        for (i in 0 until itemsArr.length()) {
            val it = itemsArr.optJSONObject(i) ?: continue
            val id = it.optString("id")
            if (id.isBlank()) continue
            val info = collectionInfoById[id] ?: continue
            val isCreator = info.creatorId.isNotBlank() && info.creatorId == currentCreatorId
            val canUpload = isCreator || info.type == FavoritesStore.TYPE_SHARED
            if (!canUpload) continue
            val action = it.optString("action", "clear")
            val pw = it.optString("password")
            if (isCreator) {
                actionById[id] = when (action) {
                    "set" -> GiteeSyncManager.PasswordAction.Set(PasswordUtil.sha256(pw))
                    "keep" -> GiteeSyncManager.PasswordAction.Keep
                    else -> GiteeSyncManager.PasswordAction.Clear
                }
            }
            selectedIds.add(id)
        }
        val toUploadIds = selectedIds.toList()
        val uploadableCount = collectionInfoById.values.count { info ->
            val isCreator = info.creatorId.isNotBlank() && info.creatorId == currentCreatorId
            info.id in selectedIds && (isCreator || info.type == FavoritesStore.TYPE_SHARED)
        }
        if (uploadableCount == 0) {
            return okObj().put("success", false).put("message", "本次没有可上传的合集")
        }
        enqueueCloudTask("upload_selected") {
            val toUpload = toUploadIds.mapNotNull { id -> favoritesStore.collection(id) }
                .filter { collection ->
                    val isCreator = collection.creatorId.isNotBlank() && collection.creatorId == currentCreatorId
                    isCreator || collection.type == FavoritesStore.TYPE_SHARED
                }
            if (toUpload.isEmpty()) {
                Log.w(TAG, "cloud upload_selected skipped: no uploadable collections")
                return@enqueueCloudTask
            }
            val result = syncManager.uploadCollections(collections = toUpload, passwordActions = actionById)
            Log.i(TAG, "cloud upload_selected finished successCount=${result.successCount}/${toUpload.size}, error=${result.errorMessage}")
        }
        return okObj()
            .put("status", "processing")
            .put("success", true)
            .put("successCount", 0)
            .put("totalCount", uploadableCount)
            .put("message", "上传任务已入队，正在后台处理")
    }

    /** 下载所选合集：downloadCollections 内部会再按上限（预置 5 + maxNonPresetDownload）二次约束。 */
    private fun cloudDownloadSelectedJson(body: String): JSONObject {
        val o = tryJson(body) ?: return okObj().put("success", false).put("message", "参数错误")
        val arr = o.optJSONArray("ids") ?: JSONArray()
        val ids = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) ids.add(s)
        }
        if (ids.isEmpty()) return okObj().put("success", false).put("message", "未选择任何合集")
        enqueueCloudTask("download_selected") {
            val cloudIndex = syncManager.fetchCloudIndex()
            if (cloudIndex == null) {
                Log.w(TAG, "cloud download_selected skipped: fetchCloudIndex failed")
                return@enqueueCloudTask
            }
            val count = syncManager.downloadCollections(ids, cloudIndex)
            Log.i(TAG, "cloud download_selected finished downloadCount=$count/${ids.size}")
        }
        return okObj()
            .put("status", "processing")
            .put("success", true)
            .put("downloadCount", 0)
            .put("totalCount", ids.size)
            .put("message", "下载任务已入队，正在后台处理")
    }

    private fun historyJson(): JSONObject {
        val root = JSONObject()
        val arr = JSONArray()
        for (h in PlaybackController.history()) {
            val o = JSONObject()
            o.put("title", h.title)
            o.put("uri", h.uri)
            o.put("source", h.source)
            o.put("time", h.time)
            arr.put(o)
        }
        root.put("items", arr)
        return root
    }

    private fun queueJson(): JSONObject {
        val root = JSONObject()
        val arr = JSONArray()
        for (item in queueStore.all()) {
            val o = JSONObject()
            o.put("id", item.id)
            o.put("title", item.title)
            o.put("uri", item.uri)
            o.put("source", item.source)
            o.put("status", item.status.raw)
            o.put("addedAt", item.addedAt)
            arr.put(o)
        }
        root.put("items", arr)
        return root
    }

    private fun playerStatusJson(): JSONObject {
        val root = JSONObject()
        val title = PlaybackController.currentTitle
        val uri = PlaybackController.currentUri
        val thumb = PlaybackController.currentThumbPath()
        val hasThumb = !thumb.isNullOrBlank() && java.io.File(thumb).exists()
        root.put("ok", true)
        root.put("title", title)
        root.put("uri", uri)
        root.put("state", PlaybackController.transportState.upnp)
        root.put("hasContent", uri.isNotBlank() || title.isNotBlank())
        root.put("hasThumbnail", hasThumb)
        root.put("thumbnailUrl", if (hasThumb) "/api/player/thumbnail?t=${System.currentTimeMillis()}" else "")
        return root
    }

    private fun playerThumbnailResponse(): Response {
        val path = PlaybackController.currentThumbPath()
        if (path.isNullOrBlank()) return plain(Response.Status.NOT_FOUND, "thumbnail not found")
        val file = java.io.File(path)
        if (!file.exists() || file.length() <= 0) return plain(Response.Status.NOT_FOUND, "thumbnail not found")
        val mime = when (file.extension.lowercase(Locale.US)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val r = newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
        r.addHeader("Cache-Control", "no-store")
        return cors(r)
    }

    private fun settingsJson(): JSONObject {
        val root = JSONObject()
        val list = JSONArray()
        val entries = listOf(
            SettingEntry("deviceName", "设备名称", settings.deviceName, "string", true, "手机端投屏时看到的名称"),
            SettingEntry("passwordMode", "投屏密码", if (settings.passwordMode) "on" else "off", "toggle", true, "开启后手机投屏时需输入密码"),
            SettingEntry("password", "密码内容", settings.password, "password", true, "4-8 位数字密码"),
            SettingEntry("muteOnCast", "投屏时静音", if (settings.muteOnCast) "on" else "off", "toggle", false, "投屏开始瞬间将 TV 输出静音"),
            SettingEntry("quality", "画质偏好", settings.quality, "quality", false, "auto / 1080 / 4k"),
            SettingEntry("bootAutoStart", "开机自启", if (settings.bootAutoStart) "on" else "off", "toggle", true, "TV 开机后自动启动接收端"),
            SettingEntry("screensaverStyle", "屏保海报墙样式", settings.screensaverStyle, "screensaver", false, "横向轨道流 / 多列瀑布流 / 随机砖块网格")
        )
        for (e in entries) {
            val o = JSONObject()
            o.put("key", e.key)
            o.put("label", e.label)
            o.put("value", e.value)
            o.put("type", e.type)
            o.put("needRestart", e.needRestart)
            o.put("desc", e.desc)
            list.put(o)
        }
        root.put("items", list)
        return root
    }

    /** 返回该项设置修改后是否需要重启接收服务生效。 */
    private fun applySettingsUpdate(key: String, value: String): Boolean {
        return when (key) {
            "deviceName" -> { settings.deviceName = value; true }
            "passwordMode" -> { settings.passwordMode = value == "on" || value == "true"; true }
            "password" -> { settings.password = value; true }
            "muteOnCast" -> { settings.muteOnCast = value == "on" || value == "true"; false }
            "quality" -> { settings.quality = value.ifBlank { "auto" }; false }
            "bootAutoStart" -> { settings.bootAutoStart = value == "on" || value == "true"; true }
            "screensaverStyle" -> { settings.screensaverStyle = value; false }
            else -> false
        }
    }

    private data class SettingEntry(
        val key: String, val label: String, val value: String,
        val type: String, val needRestart: Boolean, val desc: String
    )

    // ------------------------------------------------------------------ helpers

    private fun wallpaperStatusJson(): JSONObject {
        val ctx = favoritesStore.appContext
        val customFiles = com.bd.casttv.ui.framework.WallpaperManager.listCustomWallpapers(ctx)
        val root = JSONObject()
        root.put("count", customFiles.size)
        root.put("max", com.bd.casttv.ui.framework.WallpaperManager.MAX_CUSTOM_WALLPAPERS)
        val arr = JSONArray()
        for (f in customFiles) arr.put(f.name)
        root.put("files", arr)
        return root
    }

    private fun tryJson(body: String): JSONObject? = try {
        if (body.isBlank()) JSONObject() else JSONObject(body)
    } catch (_: Throwable) {
        // 部分 Android TV / NanoHTTPD 组合会把 UTF-8 body 先按 ISO-8859-1 解码，
        // 导致 JSON 中中文字段在保存后展示为乱码；这里按原始字节尝试恢复再解析。
        try {
            val repaired = String(body.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (repaired.isBlank()) JSONObject() else JSONObject(repaired)
        } catch (_: Throwable) { null }
    }

    /** 读取 JSON 字符串并修复常见 UTF-8→Latin1 mojibake（如“æµè¯”）。 */
    private fun jsonString(o: JSONObject, key: String): String = repairUtf8Mojibake(o.optString(key))

    private fun queryParam(session: IHTTPSession, key: String): String =
        session.parameters[key]?.firstOrNull().orEmpty()

    private fun repairUtf8Mojibake(value: String): String {
        if (value.isBlank()) return value
        return try {
            val repaired = String(value.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (looksLikeMojibake(value) && !looksLikeMojibake(repaired)) repaired else value
        } catch (_: Throwable) { value }
    }

    private fun looksLikeMojibake(value: String): Boolean {
        return value.any { ch -> ch == 'Ã' || ch == 'Â' || ch == 'æ' || ch == 'è' || ch == 'å' || ch.code in 0x0080..0x009F }
    }

    private fun okObj() = JSONObject().put("ok", true)

    private fun enqueueCloudTask(name: String, task: () -> Unit) {
        cloudWorker.execute {
            try {
                Log.i(TAG, "cloud task started: $name")
                task()
            } catch (t: Throwable) {
                Log.e(TAG, "cloud task failed: $name", t)
            }
        }
    }

    private fun badRequest(msg: String = "bad request"): Response =
        json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", msg))

    private fun jsonAdapterSpecDownload(session: IHTTPSession): Response {
        val pageKind = when (queryParam(session, "pageKind").trim().lowercase(Locale.US)) {
            "list", "list_page", "列表页" -> ParsePageKind.LIST
            else -> ParsePageKind.DETAIL
        }
        val text = try {
            JsonAdapterSpecBuilder.build(favoritesStore.appContext, pageKind = pageKind)
        } catch (t: Throwable) {
            return plain(Response.Status.NOT_FOUND, "json adapter spec not found")
        }
        val r = newFixedLengthResponse(Response.Status.OK, "text/markdown; charset=utf-8", text)
        r.addHeader("Content-Disposition", "attachment; filename=\"casttv-adapter-spec.md\"")
        return cors(r)
    }

    private fun html(text: String): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", text)
        return cors(r)
    }
    private fun json(status: Response.Status, body: JSONObject): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
        return cors(r)
    }
    private fun cloudJson(body: JSONObject): Response {
        // 为尽量兼容旧前端/调用方，后台任务采用 200 + status=processing，而不是强制切到 202。
        return json(Response.Status.OK, body)
    }
    private fun plain(status: Response.Status, text: String): Response {
        val r = newFixedLengthResponse(status, "text/plain; charset=utf-8", text)
        return cors(r)
    }
    private fun preflight(): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        return cors(r)
    }
    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    companion object {
        private const val TAG = "PhoneHubServer"
        private const val CLOUD_INDEX_STALE_MS = 30_000L
    }
}
