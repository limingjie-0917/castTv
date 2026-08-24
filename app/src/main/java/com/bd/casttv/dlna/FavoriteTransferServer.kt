package com.bd.casttv.dlna

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * 收藏「导入 / 导出」局域网传输服务（NanoHTTPD）。
 *
 * 该服务随收藏弹窗内的「导出 / 导入」二维码弹窗按需开启，弹窗关闭即停止，不常驻。
 * 与 DLNA 渲染服务使用不同端口，避免与投屏 HTTP / SSDP（7000/49152/8895/1900）冲突。
 *
 * 两种模式：
 * - [Mode.EXPORT]：手机扫码后浏览器打开下载页，点击（或自动触发）下载收藏 CSV 备份。
 * - [Mode.IMPORT]：手机扫码后浏览器打开上传页，选择本地 CSV 文件上传；
 *   TV 端收到后回调 [importHandler] 做严格格式校验，校验通过再在电视上弹确认框。
 *
 * 所有页面均内嵌 CSS（蜡笔小新主题：深色背景 + 宝蓝 #4089FD 主色 + 蜡笔黄描边 + 圆角卡片），
 * 不依赖任何外部资源，确保离线局域网环境下也能正常显示。
 */
class FavoriteTransferServer(
    port: Int,
    private val mode: Mode,
    private val exportFileName: String,
    private val exportProvider: () -> String,
    private val importHandler: (String) -> ImportOutcome
) : NanoHTTPD(port) {

    enum class Mode { EXPORT, IMPORT }

    /** 导入回调结果：accepted=是否已被 TV 接受（格式合法），message 为回显给手机的提示。 */
    data class ImportOutcome(val accepted: Boolean, val message: String)

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (mode) {
                Mode.EXPORT -> serveExport(session)
                Mode.IMPORT -> serveImport(session)
            }
        } catch (e: Exception) {
            Log.w(TAG, "serve() failed for ${session.uri}", e)
            html(Response.Status.INTERNAL_ERROR, errorPage("服务器开小差了", e.message ?: "未知错误"))
        }
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------
    private fun serveExport(session: IHTTPSession): Response {
        return when (session.uri) {
            "/download" -> {
                val csv = exportProvider()
                val resp = newFixedLengthResponse(Response.Status.OK, MIME_CSV, csv)
                resp.addHeader("Content-Disposition", "attachment; filename=\"$exportFileName\"")
                resp.addHeader("Cache-Control", "no-store")
                resp
            }
            "/" -> html(Response.Status.OK, exportPage())
            else -> html(Response.Status.NOT_FOUND, errorPage("页面不存在", "请重新扫描电视上的二维码"))
        }
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------
    private fun serveImport(session: IHTTPSession): Response {
        return when {
            (session.uri == "/upload" || session.uri == "/submit") && session.method == Method.POST -> handleUpload(session)
            session.uri == "/" -> html(Response.Status.OK, importPage())
            else -> html(Response.Status.NOT_FOUND, errorPage("页面不存在", "请重新扫描电视上的二维码"))
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        val content: String = try {
            session.parseBody(files)
            val pasted = session.parameters["content"]?.firstOrNull().orEmpty()
            if (pasted.isNotBlank()) {
                pasted
            } else {
                val tmpPath = files["file"]
                if (tmpPath.isNullOrBlank()) {
                    ""
                } else {
                    val f = File(tmpPath)
                    if (f.length() > MAX_UPLOAD_BYTES) {
                        return html(Response.Status.OK, resultPage(false, "文件过大", "收藏备份不应超过 5MB，请确认选择的是收藏 CSV 文件"))
                    }
                    f.readText(Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse upload failed", e)
            return html(Response.Status.OK, resultPage(false, "上传失败", "文件读取出错，请重试"))
        }

        if (content.isBlank()) {
            return html(Response.Status.OK, resultPage(false, "没有收到文件", "请先选择一个收藏 CSV 文件再上传"))
        }

        val outcome = importHandler(content)
        return html(Response.Status.OK, resultPage(outcome.accepted, if (outcome.accepted) "上传成功" else "格式校验未通过", outcome.message))
    }

    // ------------------------------------------------------------------
    // HTML 页面（内嵌蜡笔小新主题 CSS，离线可用）
    // ------------------------------------------------------------------
    private fun html(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", body)

    private fun page(title: String, inner: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>$title</title>
<style>
$CSS
</style>
</head>
<body>
<div class="bg-dot bg-dot-a"></div>
<div class="bg-dot bg-dot-b"></div>
<div class="wrap">
$inner
</div>
</body>
</html>
""".trimIndent()

    private fun exportPage(): String = page(
        "导出收藏 · 蜡笔小新投屏",
        """
        <div class="card">
          <div class="crayon">🖍</div>
          <h1>导出收藏备份</h1>
          <p class="sub">点击下面的按钮，把电视上的收藏合集导出为 CSV 下载到手机吧～</p>
          <a id="dl" class="btn" href="/download" download="$exportFileName">⬇ 下载 CSV 备份</a>
          <p class="hint">文件名：<span class="mono">$exportFileName</span></p>
          <div class="tip">💡 下载完成后，可在另一台电视的「导入收藏」里上传这个 CSV 文件，收藏就搬家啦。</div>
        </div>
        <div class="foot">CastTV · 蜡笔小新主题 🌻</div>
        <script>
          // 打开页面后稍作停顿自动触发一次下载（部分浏览器需用户手动点击，按钮始终可用）。
          setTimeout(function(){ try { document.getElementById('dl').click(); } catch(e){} }, 800);
        </script>
        """.trimIndent()
    )

    private fun importPage(): String = page(
        "导入收藏 · 蜡笔小新投屏",
        """
        <div class="card">
          <div class="crayon">🖍</div>
          <h1>导入收藏备份</h1>
          <p class="sub">粘贴收藏合集 CSV 备份内容，或选择手机里的 CSV 文件上传给电视～</p>
          <form action="/submit" method="post" onsubmit="return onSubmit()">
            <textarea id="content" name="content" placeholder="粘贴合集 CSV 备份内容"></textarea>
            <button id="submitBtn" class="btn" type="submit">📨 提交到电视</button>
          </form>
          <form action="/upload" method="post" enctype="multipart/form-data" onsubmit="return onSubmitFile()">
            <label class="file-box" for="file">
              <span id="fileLabel">📁 或点击选择 CSV 文件</span>
              <input id="file" name="file" type="file" accept=".csv,text/csv" onchange="onPick()">
            </label>
            <button id="fileSubmitBtn" class="btn ghost" type="submit" disabled>⬆ 上传文件</button>
          </form>
          <div class="tip">💡 提交后，电视会先校验格式，通过后需要在电视上按「确认」才会真正导入。</div>
        </div>
        <div class="foot">CastTV · 蜡笔小新主题 🌻</div>
        <script>
          function onPick(){
            var f = document.getElementById('file');
            var lbl = document.getElementById('fileLabel');
            var btn = document.getElementById('fileSubmitBtn');
            if (f.files && f.files.length > 0){
              lbl.textContent = '📄 ' + f.files[0].name;
              btn.disabled = false;
            } else {
              lbl.textContent = '📁 或点击选择 CSV 文件';
              btn.disabled = true;
            }
          }
          function onSubmit(){
            var btn = document.getElementById('submitBtn');
            btn.disabled = true;
            btn.textContent = '⏳ 正在提交...';
            return true;
          }
          function onSubmitFile(){
            var btn = document.getElementById('fileSubmitBtn');
            btn.disabled = true;
            btn.textContent = '⏳ 正在上传...';
            return true;
          }
        </script>
        """.trimIndent()
    )

    private fun resultPage(success: Boolean, title: String, message: String): String {
        val emoji = if (success) "🎉" else "😢"
        val cls = if (success) "ok" else "err"
        val backText = if (success) "上传其它文件" else "重新选择文件"
        val backLink = if (mode == Mode.IMPORT) "/" else "/"
        return page(
            "$title · 蜡笔小新投屏",
            """
            <div class="card">
              <div class="crayon $cls">$emoji</div>
              <h1 class="$cls">$title</h1>
              <p class="sub">${escape(message)}</p>
              <a class="btn ghost" href="$backLink">↩ $backText</a>
            </div>
            <div class="foot">CastTV · 蜡笔小新主题 🌻</div>
            """.trimIndent()
        )
    }

    private fun errorPage(title: String, message: String): String = resultPage(false, title, message)

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        private const val TAG = "FavoriteTransferServer"
        const val MIME_CSV = "text/csv; charset=utf-8"
        private const val MAX_UPLOAD_BYTES = 5L * 1024 * 1024

        /** 蜡笔小新主题内嵌样式：深色背景 + 宝蓝 #4089FD 主色 + 蜡笔黄描边 + 圆角卡片。 */
        private val CSS = """
        * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body { margin: 0; padding: 0; }
        body {
          min-height: 100vh;
          font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
          color: #EAF1FF;
          background: radial-gradient(circle at 20% 0%, #1b2a4a 0%, #0e1116 55%, #090b0f 100%);
          display: flex; align-items: center; justify-content: center;
          padding: 24px 16px; position: relative; overflow-x: hidden;
        }
        .bg-dot { position: fixed; border-radius: 50%; filter: blur(2px); opacity: .16; z-index: 0; }
        .bg-dot-a { width: 220px; height: 220px; background: #4089FD; top: -60px; right: -50px; }
        .bg-dot-b { width: 180px; height: 180px; background: #FFD23F; bottom: -50px; left: -40px; }
        .wrap { position: relative; z-index: 1; width: 100%; max-width: 440px; }
        .card {
          background: rgba(20, 26, 38, .92);
          border: 3px solid #FFD23F;
          border-radius: 26px;
          padding: 30px 24px 26px;
          box-shadow: 0 14px 40px rgba(0,0,0,.45), inset 0 0 0 2px rgba(64,137,253,.18);
          text-align: center;
        }
        .crayon {
          font-size: 46px; line-height: 1;
          width: 78px; height: 78px; margin: 0 auto 14px;
          display: flex; align-items: center; justify-content: center;
          background: linear-gradient(135deg, #4089FD, #2f6fe0);
          border: 3px solid #FFD23F; border-radius: 22px;
          box-shadow: 0 6px 16px rgba(64,137,253,.4);
          transform: rotate(-6deg);
        }
        .crayon.ok { background: linear-gradient(135deg, #35c07a, #1f9e63); box-shadow: 0 6px 16px rgba(53,192,122,.4); }
        .crayon.err { background: linear-gradient(135deg, #ff6b6b, #e04747); box-shadow: 0 6px 16px rgba(255,107,107,.4); }
        h1 { font-size: 23px; margin: 6px 0 10px; font-weight: 800; color: #FFFFFF; letter-spacing: .5px; }
        h1.ok { color: #5be49b; } h1.err { color: #ff8a8a; }
        .sub { font-size: 15px; line-height: 1.6; color: #A9BBD6; margin: 0 0 22px; }
        textarea {
          box-sizing: border-box; width: 100%; min-height: 150px; margin-bottom: 14px;
          border-radius: 16px; border: 2px solid #FFD23F; padding: 12px;
          background: rgba(255,255,255,.06); color: #fff; font-size: 15px;
        }
        .btn {
          display: block; width: 100%;
          background: linear-gradient(135deg, #4089FD, #2f6fe0);
          color: #fff; font-size: 18px; font-weight: 800;
          text-decoration: none; text-align: center;
          padding: 16px 18px; border: none; border-radius: 16px;
          box-shadow: 0 8px 20px rgba(64,137,253,.45);
          cursor: pointer; transition: transform .1s ease, box-shadow .1s ease;
          border-bottom: 3px solid rgba(0,0,0,.22);
        }
        .btn:active { transform: translateY(2px); box-shadow: 0 4px 12px rgba(64,137,253,.4); }
        .btn:disabled { opacity: .5; box-shadow: none; }
        .btn.ghost {
          background: transparent; color: #FFD23F;
          border: 2px solid #FFD23F; box-shadow: none; margin-top: 4px;
        }
        .file-box {
          display: flex; align-items: center; justify-content: center;
          min-height: 92px; margin-bottom: 16px; padding: 14px;
          background: rgba(64,137,253,.08);
          border: 2px dashed #4089FD; border-radius: 16px;
          color: #Bcd2f2; font-size: 16px; font-weight: 600; cursor: pointer;
          word-break: break-all; text-align: center;
        }
        .file-box input { display: none; }
        .hint { font-size: 13px; color: #8496b5; margin: 14px 0 0; }
        .mono { font-family: ui-monospace, Menlo, Consolas, monospace; color: #FFD23F; word-break: break-all; }
        .tip {
          margin-top: 18px; padding: 12px 14px;
          background: rgba(255,210,63,.1);
          border-left: 4px solid #FFD23F; border-radius: 10px;
          font-size: 13px; line-height: 1.6; color: #E4D08a; text-align: left;
        }
        .foot { text-align: center; color: #5c6a82; font-size: 12px; margin-top: 18px; }
        """.trimIndent()
    }
}
