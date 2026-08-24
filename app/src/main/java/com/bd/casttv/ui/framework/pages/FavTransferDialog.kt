package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.R
import com.bd.casttv.dlna.FavoriteTransferServer
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.ThemeManager
import java.net.URL

/** 收藏「导出 / 导入」局域网互传弹窗。 */
class FavTransferDialog(
    private val context: Context,
    private val store: FavoritesStore,
    private val mode: FavoriteTransferServer.Mode,
    private val onImported: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private var server: FavoriteTransferServer? = null
    private var startedPort: Int = 0
    private val WARM = Color.rgb(245, 196, 81)
    private val GREY = 0xFFB8B8B8.toInt()
    private val LIGHT_TEXT = 0xFFEEE8DA.toInt()

    fun show() {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            Toast.makeText(context, "未连接到局域网，请先连接 Wi-Fi / 网线", Toast.LENGTH_LONG).show()
            return
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        addHeader(panel, if (mode == FavoriteTransferServer.Mode.EXPORT) "导出合集" else "导入合集")

        val desc = if (mode == FavoriteTransferServer.Mode.EXPORT) {
            "生成当前收藏合集备份；手机扫码可在局域网页面查看并下载 CSV 文件。"
        } else {
            "可手动粘贴合集备份 CSV / URL，也可手机扫码在网页上粘贴后提交到电视。"
        }
        panel.addView(TextView(context).apply {
            text = desc
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val status = TextView(context).apply { textSize = 13f; setTextColor(LIGHT_TEXT); setPadding(0, 0, 0, dp(10)) }
        panel.addView(status)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        val started = startServer(status, dialog)
        val url = if (started != null) "http://$ip:$startedPort/" else ""

        if (mode == FavoriteTransferServer.Mode.EXPORT) buildExportContent(panel, status, url, dialog) else buildImportContent(panel, status, url, dialog)

        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        dialog.setOnDismissListener { server?.stop(); server = null }
        dialog.show()
        dialog.window?.setLayout(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun addHeader(panel: LinearLayout, title: String) {
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply { text = title; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }

    private fun buildExportContent(panel: LinearLayout, status: TextView, url: String, dialog: AlertDialog) {
        if (url.isBlank()) {
            status.text = "⚠️ 导出服务启动失败，请稍后重试"
        } else {
            status.text = "手机浏览器打开或扫码：$url\n进入后会自动触发下载，也可点击页面按钮下载。"
            val bmp = QrCodeGenerator.encode(url, dp(260))
            if (bmp != null) panel.addView(ImageView(context).apply {
                setImageBitmap(bmp); setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(276), dp(276)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
            panel.addView(TextView(context).apply { text = url; textSize = 12f; setTextColor(WARM); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        }
        val closeBtn = makeButton("关闭")
        closeBtn.setOnClickListener { dialog.dismiss() }
        panel.addView(closeBtn, LinearLayout.LayoutParams(dp(112), dp(44)).apply { gravity = Gravity.RIGHT })
    }

    private fun buildImportContent(panel: LinearLayout, status: TextView, url: String, dialog: AlertDialog) {
        status.text = if (url.isBlank()) "⚠️ 局域网服务启动失败，可手动粘贴备份内容" else "点击右侧「📷 扫码输入」，用手机扫码在网页粘贴 CSV 内容提交后，TV 会弹出确认导入。"
        val edit = EditText(context).apply {
            hint = "粘贴合集 CSV 备份内容，或粘贴 http(s) 备份地址"
            textSize = 14f; minLines = 4; maxLines = 6
            setTextColor(LIGHT_TEXT); setHintTextColor(GREY)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), WARM); setColor(Color.argb(32, 255, 255, 255)) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        panel.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)).apply { bottomMargin = dp(12) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT }
        val scanBtn = makeButton("📷 扫码输入")
        val importBtn = makeButton("导入")
        val closeBtn = makeButton("关闭")
        row.addView(scanBtn, LinearLayout.LayoutParams(dp(126), dp(44)).apply { rightMargin = dp(10) })
        row.addView(closeBtn, LinearLayout.LayoutParams(dp(96), dp(44)).apply { rightMargin = dp(10) })
        row.addView(importBtn, LinearLayout.LayoutParams(dp(96), dp(44)))
        panel.addView(row)
        scanBtn.setOnClickListener { showQrDialog(url, "扫码输入合集", "用手机扫描此二维码，在手机网页粘贴合集 CSV 后提交。") }
        closeBtn.setOnClickListener { dialog.dismiss() }
        importBtn.setOnClickListener { importManual(edit.text.toString(), status, dialog) }
    }

    private fun showQrDialog(url: String, title: String, desc: String) {
        if (url.isBlank()) { Toast.makeText(context, "局域网入口不可用", Toast.LENGTH_SHORT).show(); return }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), WARM) }
        }
        box.addView(TextView(context).apply { text = title; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        box.addView(TextView(context).apply { text = desc; textSize = 13.5f; setTextColor(Color.argb(220,255,255,255)); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(12)) })
        val bmp = QrCodeGenerator.encode(url, dp(260))
        if (bmp != null) box.addView(ImageView(context).apply { setImageBitmap(bmp); setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }, LinearLayout.LayoutParams(dp(276), dp(276)))
        box.addView(TextView(context).apply {
            text = "手机扫码或浏览器打开：$url"
            textSize = 13f; setTextColor(WARM); gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(2))
        })
        box.addView(TextView(context).apply {
            text = "在手机网页粘贴 CSV 内容提交后，TV 会弹出确认导入。"
            textSize = 12f; setTextColor(Color.argb(200, 255, 255, 255)); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        d.setOnShowListener { d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        d.show(); d.window?.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun startServer(status: TextView, dialog: AlertDialog): FavoriteTransferServer? {
        val candidates = intArrayOf(18889, 18890, 18891, 18892)
        for (p in candidates) {
            try {
                val srv = FavoriteTransferServer(p, mode, "casttv_favorites.csv", { store.exportToCsv() }, { content -> handleImport(content, status, dialog) })
                srv.start(); server = srv; startedPort = p; return srv
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun importManual(input: String, status: TextView, dialog: AlertDialog) {
        val text = input.trim()
        if (text.isBlank()) { Toast.makeText(context, "请先粘贴合集备份内容或 URL", Toast.LENGTH_SHORT).show(); return }
        status.text = "⏳ 正在读取并校验…"
        Thread {
            val content = try { if (text.startsWith("http://") || text.startsWith("https://")) URL(text).readText(Charsets.UTF_8) else text } catch (t: Throwable) { "" }
            ui.post { if (content.isBlank()) status.text = "❌ 内容为空或 URL 读取失败" else handleImport(content, status, dialog) }
        }.start()
    }

    private fun handleImport(content: String, status: TextView, dialog: AlertDialog): FavoriteTransferServer.ImportOutcome {
        return when (val preview = store.validateImportCsv(content)) {
            is FavoritesStore.ImportPreview.Invalid -> {
                ui.post { status.text = "❌ 收到无效文件：${preview.reason}" }
                FavoriteTransferServer.ImportOutcome(false, "文件格式校验未通过：${preview.reason}")
            }
            is FavoritesStore.ImportPreview.Valid -> {
                ui.post {
                    status.text = "📥 已收到备份（${preview.collectionCount} 个合集 / ${preview.itemCount} 条），请在电视上确认导入"
                    AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
                        .setTitle("确认导入合集")
                        .setMessage("收到一份合集备份：\n· 合集 ${preview.collectionCount} 个\n· 收藏 ${preview.itemCount} 条\n\n导入后将【覆盖】当前电视上的全部收藏，确定继续吗？")
                        .setPositiveButton("覆盖导入") { d, _ ->
                            d.dismiss(); status.text = "⏳ 正在导入…"
                            Thread {
                                val r = store.applyImportedCsv(preview.normalizedJson)
                                ui.post {
                                    if (r == FavoritesStore.OpResult.SUCCESS) { Toast.makeText(context, "导入成功 🎉 已更新合集", Toast.LENGTH_LONG).show(); onImported(); dialog.dismiss() }
                                    else { Toast.makeText(context, "导入失败，请重试", Toast.LENGTH_SHORT).show(); status.text = "❌ 导入写入失败，请重试" }
                                }
                            }.start()
                        }
                        .setNegativeButton("取消") { d, _ -> status.text = "已取消本次导入，可重新上传或粘贴"; d.dismiss() }
                        .show()
                }
                FavoriteTransferServer.ImportOutcome(true, "已发送到电视，请在电视上确认导入（${preview.collectionCount} 个合集 / ${preview.itemCount} 条收藏）")
            }
        }
    }

    private fun makeButton(textValue: String): TextView = TextView(context).apply {
        text = textValue; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        val lightText = Color.parseColor("#F1F1F5")
        val lightBorder = Color.parseColor("#66FFFFFF")
        fun refresh(focused: Boolean) {
            setTextColor(if (focused) WARM else lightText)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(51, 27, 31, 38))
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else lightBorder)
            }
        }
        refresh(false)
        setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
