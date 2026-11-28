package com.duo.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 內建 PDF 閱讀器。WebView 本身不能顯示 PDF，所以：
 * 1. 把 PDF 下載（或從檔案複製）到 cache/pdf/{id}.pdf
 * 2. 在分頁裡載入 https://appassets.androidplatform.net/duo-pdf/{id}/ ，由 shouldInterceptRequest 回應
 * 3. 閱讀器是 pdf.js（assets/pdfjs/）：文字可選取、搜尋、目錄、連結、密碼、清晰縮放
 * 這樣 PDF 就和一般網頁一樣從上屏延伸到下屏、可以捲動、縮放。
 */
object PdfViewer {
    private const val HOST = "appassets.androidplatform.net"
    private const val BASE = "/duo-pdf/"
    private val main = Handler(Looper.getMainLooper())

    private val dir: File get() = File(Session.app.cacheDir, "pdf").apply { mkdirs() }
    /** 無痕分頁下載的 PDF 放另一個資料夾（id 開頭是 i），無痕資料清除時整個刪掉。 */
    private val incDir: File get() = File(Session.app.cacheDir, "pdf-inc").apply { mkdirs() }
    private fun file(id: String) = File(if (id.startsWith("i")) incDir else dir, "$id.pdf")

    /** 刪除無痕分頁下載的 PDF（Incognito.endSession / cleanupOnStart 呼叫）。 */
    fun wipeIncognito() {
        Thread { runCatching { File(Session.app.cacheDir, "pdf-inc").deleteRecursively() } }.start()
    }
    /** src = 網頁上原本的 PDF 網址：暫存被清掉時（只留最近 15 份），從歷史紀錄打開可以重新下載。 */
    private fun viewerUrl(id: String, name: String, src: String? = null) =
        "https://$HOST$BASE$id/?name=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            (src?.let { "&src=" + URLEncoder.encode(it, "UTF-8") } ?: "")

    fun isViewerUrl(url: String?) = url != null && url.startsWith("https://$HOST$BASE")

    /** 網頁的下載是不是 PDF。 */
    fun isPdf(url: String, contentDisposition: String?, mime: String?): Boolean {
        if (mime?.lowercase()?.startsWith("application/pdf") == true) return true
        if (contentDisposition?.lowercase()?.contains(".pdf") == true) return true
        return Uri.parse(url).path?.lowercase()?.endsWith(".pdf") == true
    }

    /** 檔名（octet-stream 時系統會把副檔名猜成 .bin，所以先看網址本身）。 */
    private fun fileName(url: String, contentDisposition: String?, mime: String?): String {
        val fromCd = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition.orEmpty())?.groupValues?.get(1)?.let { Uri.decode(it) }
        val name = fromCd ?: Uri.parse(url).lastPathSegment ?: URLUtil.guessFileName(url, contentDisposition, mime)
        return if (name.lowercase().endsWith(".pdf")) name else "$name.pdf"
    }

    private fun idFor(source: String): String =
        MessageDigest.getInstance("SHA-1").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    // ---------------- 取得 PDF ----------------

    /** 下載網頁上的 PDF（帶上 cookie，登入後才能下載的檔案也可以），完成後在 [onReady] 拿到閱讀器網址。 */
    fun download(
        url: String, ua: String?, contentDisposition: String?, mime: String?,
        onProgress: Progress,
        /** 下載用哪個設定檔的 Cookie（無痕分頁用無痕的，不能帶一般分頁的登入） */
        cookies: CookieManager = CookieManager.getInstance(),
        incognito: Boolean = false,
        onReady: (String?) -> Unit,
    ) {
        val name = fileName(url, contentDisposition, mime)
        Thread {
            val result = try {
                val id = (if (incognito) "i" else "") + idFor(url)
                val out = file(id)
                var target = URL(url)
                var cn: HttpURLConnection? = null
                var hops = 0
                while (cn == null) {
                    val c1 = target.openConnection() as HttpURLConnection
                    c1.instanceFollowRedirects = false
                    c1.connectTimeout = 15000; c1.readTimeout = 30000
                    if (!ua.isNullOrBlank()) c1.setRequestProperty("User-Agent", ua)
                    // 只送目前這個網址自己的 Cookie：被轉址到別的網站時，不能把原網站的 Cookie 帶過去
                    val c = cookies.getCookie(target.toString())
                    if (!c.isNullOrBlank()) c1.setRequestProperty("Cookie", c)
                    val loc = if (c1.responseCode in 300..399 && hops++ < 8) c1.getHeaderField("Location") else null
                    if (loc != null) { c1.disconnect(); target = URL(target, loc) } else cn = c1
                }
                val conn = cn!!
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                conn.inputStream.use { save(it, out, conn.contentLengthLong, onProgress) }
                conn.disconnect()
                // 無痕分頁不帶原網址（網址會出現在分頁網址裡）
                viewerUrl(id, name, if (incognito) null else url)
            } catch (e: Exception) { android.util.Log.w("DuoPdf", "download failed (${Uri.parse(url).host})", e); null } // 只記網域：網址可能帶登入用的參數
            main.post { onReady(result) }
        }.start()
    }

    /** 已經打開、還沒複製的本機 PDF。 */
    class Source(val input: InputStream, val name: String, val size: Long, val id: String)

    /**
     * 打開 content:// 檔案（從檔案管理員 / 其他 App 傳來）。只查名稱並開檔，很快；
     * 真正的複製在 [importAsync] 的背景執行緒做。讀取權限只給收到 Intent 的畫面時，
     * 要在那個畫面結束前先打開（已經打開的檔案在權限收回後仍可讀）。
     */
    fun openUri(uri: Uri): Source? = if (!fileAllowed(uri)) null else try {
        val cr = Session.app.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document.pdf"
        var size = if (uri.scheme == "file") uri.path?.let { File(it).length() }?.takeIf { it > 0 } ?: -1L else -1L
        if (uri.scheme == "content") cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        cr.openInputStream(uri)?.let { Source(it, name, size, idFor("$uri|$name|$size")) }
    } catch (e: Exception) { null }

    /**
     * file:// 只接受共用儲存空間（/storage、/sdcard）裡的檔案：
     * 不然別的 App 可以傳 file:///data/data/本App/… 過來，讓本 App 把自己的 Cookie、歷史紀錄開出來。
     */
    fun fileAllowed(uri: Uri): Boolean {
        if (uri.scheme != "file") return true
        val p = try { File(uri.path ?: return false).canonicalPath } catch (e: Exception) { return false }
        return (p.startsWith("/storage/") || p.startsWith("/sdcard/")) && !p.contains(Session.app.packageName)
    }

    /** 在背景打開（[open]）並複製到暫存，完成後在主執行緒拿到閱讀器網址（失敗是 null）。 */
    fun importAsync(open: () -> Source?, onProgress: Progress, onReady: (String?) -> Unit) {
        Thread {
            val r = try {
                open()?.let { src ->
                    src.input.use { save(it, file(src.id), src.size, onProgress) }
                    viewerUrl(src.id, src.name)
                }
            } catch (e: Exception) { android.util.Log.w("DuoPdf", "import failed", e); null }
            main.post { onReady(r) }
        }.start()
    }

    /** 進度回報（在主執行緒呼叫）：已下載的位元組數、總大小（不知道時是 -1）。 */
    fun interface Progress { fun update(done: Long, total: Long) }

    private fun save(input: InputStream, out: File, total: Long, onProgress: Progress?) {
        val tmp = File(out.path + ".part")
        tmp.outputStream().use { os ->
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastReport = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
                done += n
                val now = android.os.SystemClock.uptimeMillis()
                if (onProgress != null && now - lastReport >= 120) {
                    lastReport = now
                    val d = done
                    main.post { onProgress.update(d, total) }
                }
            }
            if (onProgress != null) main.post { onProgress.update(done, total) }
        }
        // 檢查是不是真的 PDF（開頭是 %PDF）
        val head = ByteArray(5)
        tmp.inputStream().use { it.read(head) }
        if (String(head, Charsets.ISO_8859_1).indexOf("%PDF") < 0) { tmp.delete(); throw IllegalStateException("not pdf") }
        out.delete(); tmp.renameTo(out)
        // cache 只保留最近 15 份
        dir.listFiles { f -> f.name.endsWith(".pdf") }?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { it.delete() }
    }

    // ---------------- 顯示（pdf.js） ----------------

    /** pdf.js（Mozilla，Apache 2.0）放在 assets/pdfjs/，由這個路徑提供。 */
    private const val PDFJS = "/pdfjs/"

    /** WebViewClient.shouldInterceptRequest 呼叫；不是閱讀器的網址就回傳 null。 */
    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        val path = uri.path ?: return null
        if (path.startsWith(PDFJS)) return asset(path.removePrefix(PDFJS))
        if (!path.startsWith(BASE)) return null
        val parts = path.removePrefix(BASE).split('/')
        // 一般分頁 20 碼、無痕分頁 i + 20 碼
        val id = parts.getOrNull(0)?.takeIf { it.matches(Regex("i?[0-9a-f]{20}")) } ?: return notFound()
        val sub = parts.getOrNull(1).orEmpty()
        return try {
            if (sub.isEmpty()) viewer(id, uri.getQueryParameter("name") ?: "document.pdf", uri.getQueryParameter("src"))
            else pdfFile(id) ?: notFound()
        } catch (e: Exception) {
            android.util.Log.w("DuoPdf", "serve failed", e)
            notFound()
        }
    }

    /** pdf.js 的檔案（只允許 assets/pdfjs/ 底下、不能用 .. 跳出去）。 */
    private fun asset(rel: String): WebResourceResponse {
        if (rel.isEmpty() || rel.split('/').any { it == ".." || it.isEmpty() }) return notFound()
        val name = rel.substringAfterLast('/')
        val mime = if (name.startsWith("LICENSE")) "text/plain" else when (name.substringAfterLast('.').lowercase()) {
            "mjs", "js" -> "text/javascript"
            "css" -> "text/css"
            "html" -> "text/html"
            "json" -> "application/json"
            "ftl" -> "text/plain"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
        return try {
            val input = Session.app.assets.open("pdfjs/$rel")
            WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json")) "utf-8" else null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400"), input)
        } catch (e: Exception) { notFound() }
    }

    /** PDF 本身。 */
    private fun pdfFile(id: String): WebResourceResponse? {
        val f = file(id)
        if (!f.exists()) return null
        return WebResourceResponse("application/pdf", null, 200, "OK",
            mapOf("Content-Length" to f.length().toString(), "Cache-Control" to "no-store"), f.inputStream())
    }

    /**
     * pdf.js 的閱讀器頁面：照原檔提供，只在 <head> 最前面加上
     * 1. <base>：讓相對路徑（viewer.css、../build/pdf.mjs…）指到 /pdfjs/web/
     * 2. 設定：要開哪個檔案、語言、深色主題、關掉 PDF 內的 JavaScript
     * 3. 隱藏 WebView 用不到的按鈕（列印、下載、開啟檔案、簡報模式、編輯註解）
     */
    private fun viewer(id: String, name: String, src: String?): WebResourceResponse {
        if (!file(id).exists()) return notFound(src)
        val html = Session.app.assets.open("pdfjs/web/viewer.html").use { String(it.readBytes(), Charsets.UTF_8) }
        val lang = when (L.current) { L.Lang.ZH_TW -> "zh-TW"; L.Lang.ZH_CN -> "zh-CN"; else -> "en-US" }
        // 檔名放在 PDF 網址最後一段：pdf.js 沒有標題時用它當頁面標題
        val fileUrl = BASE + id + "/" + Uri.encode(name.ifBlank { "document.pdf" })
        val js = """
document.addEventListener('webviewerloaded', function () {
  var o = window.PDFViewerApplicationOptions;
  o.set('disablePreferences', true);
  o.set('defaultUrl', ${jsStr(fileUrl)});
  o.set('localeProperties', { lang: ${jsStr(lang)} });
  o.set('viewerCssTheme', 2);
  o.set('toolbarDensity', 2); // 觸控用的大按鈕
  o.set('enableScripting', false);
  o.set('isEvalSupported', false);
  o.set('annotationEditorMode', -1);
  o.set('supportsPrinting', false);
  o.set('supportsDownloading', false);
  o.set('enableAltText', false);
  o.set('enableSignatureEditor', false);
  o.set('enableComment', false);
});"""
        val hide = listOf(
            "#editorModeButtons", "#editorModeSeparator", "#printButton", "#downloadButton",
            "#secondaryOpenFile", "#secondaryPrint", "#secondaryDownload", "#presentationMode",
            "#viewsManagerAddFileButton", "#imageAltTextSettings", "#signaturePropertiesButton",
        ).joinToString(",") + "{display:none!important}"
        val head = "<base href=\"$PDFJS" + "web/\"><script>$js</script><style>$hide</style>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return notFound()
        val out = html.substring(0, m.range.last + 1) + head + html.substring(m.range.last + 1)
        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(out.toByteArray()))
    }

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c") + "'"

    /** 找不到 PDF。有原本的網頁網址（src）時直接重新下載（下載完會回到閱讀器）。 */
    private fun notFound(src: String? = null): WebResourceResponse {
        val web = src?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val msg = if (web != null) L.t("暫存檔已被清除，正在重新下載…", "缓存文件已被清除，正在重新下载…", "The cached copy was cleared. Downloading again…")
        else L.t("找不到這個 PDF（暫存檔可能已被清除），請重新開啟。",
            "找不到这个 PDF（缓存文件可能已被清除），请重新打开。",
            "This PDF is no longer available (the cache may have been cleared). Please open it again.")
        val go = web?.let { "<script>location.replace(${jsStr(it)})</script>" } ?: ""
        val body = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='background:#222;color:#ddd;font:20px sans-serif;padding:40px'>${esc(msg)}$go"
        return WebResourceResponse("text/html", "utf-8", 404, "Not Found", null, ByteArrayInputStream(body.toByteArray()))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}
