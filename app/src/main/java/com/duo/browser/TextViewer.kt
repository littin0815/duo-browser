package com.duo.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 內建文字檔閱讀器（.txt、.md、.log、.csv、.json…）。做法和 PDF 閱讀器一樣：
 * 1. 把檔案複製到 cache/text/{id}.txt
 * 2. 在分頁裡載入 https://appassets.androidplatform.net/duo-text/{id}/?name=… ，由 shouldInterceptRequest 回應
 * 3. 回應的是一個簡單的 HTML 頁面（文字一律跳脫，不會被當成網頁執行），所以一樣能跨上下兩個螢幕捲動、縮放。
 * 編碼自動判斷（UTF-8／UTF-16／Big5／GBK），判斷錯了可以在頁面上方切換。
 */
object TextViewer {
    private const val HOST = "appassets.androidplatform.net"
    private const val BASE = "/duo-text/"
    /** 太大的檔案不開（整份要放進一個網頁裡） */
    private const val MAX_BYTES = 30L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())

    private val dir: File get() = File(Session.app.cacheDir, "text").apply { mkdirs() }
    private fun file(id: String) = File(dir, "$id.txt")

    fun isViewerUrl(url: String?) = url != null && url.startsWith("https://$HOST$BASE")

    /** raw = true：Markdown 檔顯示原始文字（不排版）。 */
    private fun viewerUrl(id: String, name: String, enc: String? = null, raw: Boolean = false) =
        "https://$HOST$BASE$id/?name=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            (enc?.let { "&enc=$it" } ?: "") + (if (raw) "&raw=1" else "")

    private fun idFor(source: String): String =
        MessageDigest.getInstance("SHA-1").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    /** 打開 content:// 文字檔（只查名稱並開檔；複製在 [importAsync] 的背景執行緒做）。 */
    fun openUri(uri: Uri): PdfViewer.Source? = if (!PdfViewer.fileAllowed(uri)) null else try {
        val cr = Session.app.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "text.txt"
        var size = if (uri.scheme == "file") uri.path?.let { File(it).length() }?.takeIf { it > 0 } ?: -1L else -1L
        if (uri.scheme == "content") cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) null
        else cr.openInputStream(uri)?.let { PdfViewer.Source(it, name, size, idFor("text|$uri|$name|$size")) }
    } catch (e: Exception) { null }

    /** 在背景複製到暫存，完成後在主執行緒拿到閱讀器網址（失敗是 null）。 */
    fun importAsync(open: () -> PdfViewer.Source?, onProgress: PdfViewer.Progress, onReady: (String?) -> Unit) {
        Thread {
            val r = try {
                open()?.let { src ->
                    src.input.use { input ->
                        val out = file(src.id)
                        val tmp = File(out.path + ".part")
                        tmp.outputStream().use { os ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                done += n
                                if (done > MAX_BYTES) { tmp.delete(); throw IllegalStateException("too large") }
                                os.write(buf, 0, n)
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastReport >= 120) {
                                    lastReport = now
                                    val d = done
                                    main.post { onProgress.update(d, src.size) }
                                }
                            }
                        }
                        if (!looksLikeText(tmp)) { tmp.delete(); throw IllegalStateException("not text") }
                        out.delete(); tmp.renameTo(out)
                        // cache 只保留最近 15 份
                        dir.listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { it.delete() }
                    }
                    viewerUrl(src.id, src.name)
                }
            } catch (e: Exception) { android.util.Log.w("DuoText", "import failed", e); null }
            main.post { onReady(r) }
        }.start()
    }

    /** 開頭 8KB 裡有 NUL（又不是 UTF-16）就當作不是文字檔（例如圖片、壓縮檔被誤開）。 */
    private fun looksLikeText(f: File): Boolean {
        val head = ByteArray(8192)
        val n = f.inputStream().use { it.read(head) }.coerceAtLeast(0)
        if (n >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) || (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))) return true
        for (i in 0 until n) if (head[i] == 0.toByte()) return false
        return true
    }

    // ---------------- 編碼 ----------------

    private val ENCODINGS = listOf("utf-8", "big5", "gbk")

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) { null }

    /** 常用字（繁簡都有）：亂碼裡很少出現，用來判斷 Big5 / GBK 哪個才對。 */
    private const val COMMON = "的一是不了人我在有他她這这中大來来上國国個个到說说們们為为子和你地出道也時时年就那要下以會会可對对生能而著着麼么過过後后自之去還还"

    private fun score(s: String): Int = s.count { COMMON.indexOf(it) >= 0 }

    /** 解碼：回傳（文字, 實際用的編碼）。[want] 是使用者指定的（auto 或 utf-8 / big5 / gbk）。 */
    private fun decode(bytes: ByteArray, want: String): Pair<String, String> {
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() && want == "auto")
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "utf-8"
        if (bytes.size >= 2 && want == "auto") {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "utf-16"
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "utf-16"
        }
        if (want == "utf-8") return String(bytes, Charsets.UTF_8) to "utf-8"
        if (want == "big5") return String(bytes, Charset.forName("Big5")) to "big5"
        if (want == "gbk") return String(bytes, Charset.forName("GB18030")) to "gbk"
        strict(bytes, Charsets.UTF_8)?.let { return it to "utf-8" }
        // 不是 UTF-8：Big5 和 GBK 都解看看，常用字比較多的那個才是對的
        val sample = bytes.copyOf(minOf(bytes.size, 128 * 1024))
        val big5 = String(sample, Charset.forName("Big5"))
        val gbk = String(sample, Charset.forName("GB18030"))
        val useBig5 = score(big5) > score(gbk) ||
            (score(big5) == score(gbk) && L.current != L.Lang.ZH_CN && strict(sample.copyOf(sample.size - trailCut(sample)), Charset.forName("Big5")) != null)
        return if (useBig5) String(bytes, Charset.forName("Big5")) to "big5"
        else String(bytes, Charset.forName("GB18030")) to "gbk"
    }

    /** 取樣可能把最後一個雙位元組字切一半：嚴格解碼時去掉結尾不完整的位元組。 */
    private fun trailCut(b: ByteArray): Int = if (b.isNotEmpty() && b.last() < 0) 1 else 0

    // ---------------- 顯示 ----------------

    /** WebViewClient.shouldInterceptRequest 呼叫；不是文字閱讀器的網址就回傳 null。 */
    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        val path = uri.path ?: return null
        if (!path.startsWith(BASE)) return null
        val id = path.removePrefix(BASE).trimEnd('/').takeIf { it.matches(Regex("[0-9a-f]{20}")) } ?: return notFound()
        val f = file(id)
        if (!f.exists()) return notFound()
        val name = uri.getQueryParameter("name") ?: "text.txt"
        val want = uri.getQueryParameter("enc")?.takeIf { it in ENCODINGS } ?: "auto"
        return try {
            page(id, name, want, f.readBytes(), uri.getQueryParameter("raw") == "1")
        } catch (e: Throwable) { // 包含記憶體不足
            android.util.Log.w("DuoText", "serve failed", e)
            notFound()
        }
    }

    private fun page(id: String, name: String, want: String, bytes: ByteArray, raw: Boolean): WebResourceResponse {
        val (text, used) = decode(bytes, want)
        val ext = name.substringAfterLast('.', "").lowercase()
        val isMd = ext == "md" || ext == "markdown"
        val rendered = isMd && !raw // Markdown 預設排版，選單可以切回原始文字
        val mono = ext in setOf("log", "json", "csv", "tsv", "xml", "yaml", "yml", "ini", "conf", "cfg", "toml", "srt", "js", "kt", "py", "c", "h", "cpp", "java", "sh", "bat", "ps1")
        val sb = StringBuilder(text.length + text.length / 8 + 4096)
        sb.append("<!doctype html><html><head><meta charset=utf-8>")
            .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
            .append("<title>").append(esc(name)).append("</title><style>")
            .append(":root{color-scheme:dark}")
            .append("body{margin:0;background:#16151a;color:#e8e6ee;font:19px/1.75 sans-serif;-webkit-text-size-adjust:100%}")
            // 功能列：一行，檔名 + 文字大小 + 選項（編碼、等寬字型收在下拉選單裡）
            // 尺寸一律用 em：App 的「文字大小」會放大字，但不會放大 px 寫死的框
            .append("header{position:sticky;top:0;z-index:2;display:flex;align-items:center;gap:.6em;padding:.3em 1em;padding-block:.3em;background:#211f28;border-bottom:1px solid #34313f;font:15px/1 sans-serif}")
            .append("header b{flex:1;min-width:0;font-size:1.05em;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
            .append("header button{font:inherit;color:#ddd7f0;background:#302c3c;border:1px solid #45405a;height:2.4em;min-height:52px;padding:0 .9em;border-radius:1.2em;display:flex;align-items:center;gap:.4em;white-space:nowrap}")
            .append("header button:active{background:#3c3750}")
            .append("header button:focus-visible,#menu a:focus-visible,#menu .row:focus-visible{outline:2px solid #a48bff;outline-offset:2px}")
            .append(".sz{display:flex;align-items:stretch;background:#302c3c;border:1px solid #45405a;border-radius:1.2em;height:2.4em;min-height:52px;overflow:hidden}")
            .append(".sz button{border:0;background:none;width:2.5em;height:auto;min-height:0;padding:0;justify-content:center;border-radius:0}")
            .append("#s1{font-size:.85em;width:2.95em}#s2{font-size:1.2em;width:2.1em;padding-right:.15em}")
            .append(".sz span{display:flex;align-items:center;justify-content:center;min-width:2.5em;padding:0 .15em;font-size:.85em;color:#b3acd0;font-variant-numeric:tabular-nums;border-left:1px solid #45405a;border-right:1px solid #45405a}")
            .append("#mb[aria-expanded=true]{background:#3c3750;border-color:#7c5cff}")
            .append(".aa{font-size:.8em;font-weight:700;color:#a48bff}")
            .append(".tag{font-size:.75em;color:#b3acd0;display:none}body.mono .tag{display:inline}")
            .append(".car{width:.8em;height:.8em;opacity:.75;margin-left:.25em}")
            .append("#menu{position:absolute;right:1em;top:calc(100% + .4em);min-width:14em;background:#26232f;border:1px solid #45405a;border-radius:.9em;padding:.35em;box-shadow:0 10px 30px rgba(0,0,0,.5);display:none;max-height:calc(100vh - 100% - 1.5em);overflow-y:auto;overscroll-behavior:contain}")
            .append("#menu.open{display:block}")
            .append("#menu h6{margin:.6em 0 .2em;padding-left:3.86em;font-size:.75em;font-weight:600;color:#a39cbd;letter-spacing:.04em}")
            .append("#menu a,#menu .row{display:flex;align-items:center;gap:.6em;min-height:max(2.6em,50px);padding:0 .8em;border-radius:.6em;color:#e8e6ee;text-decoration:none;font-size:1em;box-sizing:border-box;width:100%;background:none;border:0;height:auto}")
            .append("#menu a:active,#menu .row:active{background:#34303f}")
            .append("#menu a[role=menuitemradio][aria-checked=true]{background:#2f2a40}")
            .append("#menu .ck{width:1.3em;flex:none;display:flex;justify-content:center;font-size:1.15em}")
            .append("#menu small{margin-left:auto;padding-left:1em;color:#a39cbd;font-size:.85em}")
            .append("#menu hr{border:0;border-top:1px solid #34313f;margin:.3em .5em}")
            .append(".sw{margin-left:auto;font-size:1.25em;width:2.3em;height:1.35em;border-radius:.675em;background:#3a3550;box-shadow:inset 0 0 0 1px #57506e;position:relative;flex:none}")
            .append(".sw:after{content:'';position:absolute;left:.15em;top:.15em;width:1.05em;height:1.05em;border-radius:50%;background:#cfc8e8;transition:left .15s}")
            .append("body.mono #f .sw,.sw.on{background:#7c5cff;box-shadow:none}body.mono #f .sw:after,.sw.on:after{left:1.1em;background:#fff}")
            // Markdown 排版
            .append("main.md{line-height:1.7}main.md h1,main.md h2,main.md h3,main.md h4,main.md h5,main.md h6{line-height:1.3;margin:1.4em 0 .5em;color:#f2f0f8}")
            .append("main.md h1{font-size:1.75em;padding-bottom:.25em;border-bottom:1px solid #34313f}main.md h2{font-size:1.4em;padding-bottom:.2em;border-bottom:1px solid #2c2936}")
            .append("main.md h3{font-size:1.2em}main.md h4{font-size:1.05em}main.md h5,main.md h6{font-size:.95em;color:#b3acd0}")
            .append("main.md>:first-child{margin-top:0}main.md p{margin:.7em 0;white-space:normal}")
            .append("main.md a{color:#b9a4ff;text-decoration:underline;text-underline-offset:.15em}")
            .append("main.md strong{color:#fff}main.md del{color:#8d86a8}")
            .append("main.md code{font-family:monospace;font-size:.88em;background:#26232f;border:1px solid #34313f;border-radius:.3em;padding:.08em .35em}")
            .append("main.md pre.code{background:#1f1d26;border:1px solid #34313f;border-radius:.6em;padding:.8em 1em;overflow-x:auto;white-space:pre;line-height:1.5;margin:.9em 0}")
            .append("main.md pre.code code{background:none;border:0;padding:0;font-size:.85em}")
            .append("main.md blockquote{margin:.9em 0;padding:.2em 1em;border-left:.25em solid #7c5cff;background:#1f1d27;color:#cfc9e2;border-radius:0 .4em .4em 0}")
            .append("main.md ul,main.md ol{padding-left:1.6em;margin:.6em 0}main.md ol{padding-left:2em}main.md li{margin:.25em 0}main.md li>ul,main.md li>ol{margin:.2em 0}")
            .append("main.md li.task{list-style:none;margin-left:-1.4em}main.md .box{display:inline-block;width:.9em;height:.9em;border:2px solid #8d86a8;border-radius:.2em;margin-right:.5em;vertical-align:-.1em}")
            .append("main.md .box.done{background:#7c5cff url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='M3.5 8.5l3 3 6-7' fill='none' stroke='white' stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\") center/100% no-repeat;border-color:#7c5cff}")
            .append("main.md hr{border:0;border-top:1px solid #3d3950;margin:1.6em 0}")
            .append("main.md img{max-width:100%;height:auto;border-radius:.4em}")
            .append("main.md .tbl{overflow-x:auto;margin:.9em 0}main.md table{border-collapse:collapse;font-size:.92em}")
            .append("main.md th,main.md td{border:1px solid #3d3950;padding:.4em .8em}main.md th{background:#26232f;text-align:left}main.md tr:nth-child(2n) td{background:#1c1a22}")
            .append("main{padding:16px 20px 60vh;max-width:60em;font-size:var(--fs,19px)}")
            .append("pre,main p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}")
            .append("main p.gap::before{content:'\\200b'}")
            .append("body.mono main{font-family:monospace;font-size:calc(var(--fs,19px)*.85);line-height:1.55;max-width:none}")
            .append("</style></head><body").append(if (mono) " class=mono" else "").append("><header><b>").append(esc(name)).append("</b>")
        // 文字大小：小 A｜數字｜大 A（記在這台裝置，下次開文字檔沿用）
        sb.append("<div class=sz><button id=s1 aria-label='").append(L.t("字變小", "字变小", "Smaller text")).append("'>A</button>")
            .append("<span id=sv></span><button id=s2 aria-label='").append(L.t("字變大", "字变大", "Larger text")).append("'>A</button></div>")
        // 選項：Aa + 目前的編碼（等寬開著時加「等寬」）；編碼、等寬字型在下拉選單（不用 <select>：它的選單會跑到別的螢幕）
        val usedLabel = when (used) { "big5" -> "Big5"; "gbk" -> "GBK"; "utf-16" -> "UTF-16"; else -> "UTF-8" }
        val chevron = "<svg class=car viewBox='0 0 10 10'><path d='M2 3.5l3 3 3-3' fill=none stroke=currentColor stroke-width=1.8 stroke-linecap=round stroke-linejoin=round /></svg>"
        val check = "<svg width='1em' height='1em' viewBox='0 0 16 16'><path d='M3 8.5l3.2 3L13 4.5' fill=none stroke='#a48bff' stroke-width=2 stroke-linecap=round stroke-linejoin=round /></svg>"
        sb.append("<button id=mb aria-haspopup=true aria-expanded=false aria-label='").append(L.t("顯示選項", "显示选项", "Display options")).append("'>")
            .append("<span class=aa>Aa</span>").append(usedLabel)
            .append("<span class=tag>· ").append(L.t("等寬", "等宽", "Mono")).append("</span>").append(chevron).append("</button>")
            .append("<div id=menu role=menu><h6>").append(L.t("文字編碼", "文字编码", "Encoding")).append("</h6>")
        val big5Note = L.t("繁體中文", "繁体中文", "Traditional Chinese")
        val gbkNote = L.t("簡體中文", "简体中文", "Simplified Chinese")
        for ((key, label, note) in listOf(
            Triple("auto", L.t("自動判斷", "自动判断", "Auto-detect"), if (want == "auto") L.t("偵測為 ", "检测为 ", "Detected: ") + usedLabel else null),
            Triple("utf-8", "UTF-8", null), Triple("big5", "Big5", big5Note), Triple("gbk", "GBK", gbkNote))) {
            val on = key == want
            sb.append("<a role=menuitemradio aria-checked=").append(on).append(" href='").append(esc(viewerUrl(id, name, key.takeIf { it != "auto" }, raw)))
                .append("'><span class=ck>").append(if (on) check else "").append("</span>").append(label)
            if (note != null) sb.append("<small>").append(esc(note)).append("</small>")
            sb.append("</a>")
        }
        sb.append("<hr><h6>").append(L.t("顯示", "显示", "Display")).append("</h6>")
        // Markdown 檔：排版／原始文字切換（換頁重新產生）
        if (isMd) sb.append("<a class=row role=menuitemcheckbox aria-checked=").append(rendered).append(" href='")
            .append(esc(viewerUrl(id, name, want.takeIf { it != "auto" }, raw = rendered))).append("'><span class=ck></span>")
            .append(L.t("Markdown 排版", "Markdown 排版", "Format Markdown"))
            .append("<span class='sw").append(if (rendered) " on" else "").append("'></span></a>")
        sb.append("<button class=row id=f role=menuitemcheckbox><span class=ck></span>")
            .append(L.t("等寬字型", "等宽字体", "Monospace font"))
            .append("<span class=sw></span></button></div></header>")
            .append(if (rendered) "<main class=md>" else "<main>")
        val n = text.length
        val lines = text.count { it == '\n' } + 1
        if (rendered) {
            sb.append(Markdown.toHtml(text))
        } else if (lines <= 60000) {
            // 每一行一個段落：網頁翻譯（例如沉浸式翻譯）是一段一段翻的，整份放在一個區塊裡它會翻不動。
            // 空行用 .gap（偽元素撐出一行高，沒有文字，翻譯不會去翻它）
            var i = 0
            while (i <= n) {
                var end = text.indexOf('\n', i)
                if (end < 0) end = n
                var e = end
                while (e > i && (text[e - 1] == '\r' || text[e - 1] == ' ' || text[e - 1] == '\t')) e--
                if (e == i) sb.append("<p class=gap></p>") else { sb.append("<p>"); escInto(sb, text, i, e); sb.append("</p>") }
                i = end + 1
            }
        } else {
            // 行數太多（大型 log）：分塊放進 <pre>，避免網頁元素多到排版變慢
            var i = 0
            while (i < n) {
                var end = minOf(n, i + 32 * 1024)
                if (end < n) { val nl = text.lastIndexOf('\n', end); if (nl > i) end = nl + 1 }
                sb.append("<pre>")
                escInto(sb, text, i, end)
                sb.append("</pre>")
                i = end
            }
        }
        sb.append("</main><script>(function(){var $=function(i){return document.getElementById(i)},fs=19;" +
            "try{fs=parseInt(localStorage.getItem('duoTextFs'))||19}catch(e){}" +
            "function set(v){fs=Math.max(12,Math.min(44,v));document.body.style.setProperty('--fs',fs+'px');$('sv').textContent=fs;" +
            "try{localStorage.setItem('duoTextFs',fs)}catch(e){}}" +
            "set(fs);$('s1').onclick=function(){set(fs-2)};$('s2').onclick=function(){set(fs+2)};" +
            "var mb=$('mb'),mn=$('menu');function show(o){mn.classList.toggle('open',o);mb.setAttribute('aria-expanded',o)}" +
            "mb.onclick=function(e){e.stopPropagation();var o=!mn.classList.contains('open');show(o);if(o&&e.detail===0)mn.querySelector('a').focus()};" +
            "addEventListener('keydown',function(e){if(e.key==='Escape'&&mn.classList.contains('open')){show(false);mb.focus();e.preventDefault()}});" +
            "mn.onclick=function(e){e.stopPropagation()};document.addEventListener('click',function(){show(false)});" +
            "addEventListener('scroll',function(){show(false)},{passive:true});" +
            "$('f').onclick=function(){document.body.classList.toggle('mono')};})();</script></body></html>")
        // 文字內容一律跳脫，不會被當成網頁執行。不限制連線：網頁翻譯這類使用者腳本要連到翻譯服務。
        // 只擋掉用不到又可能被濫用的：外掛物件、改寫 <base>、送出表單
        val headers = mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "object-src 'none'; base-uri 'none'; form-action 'none'",
        )
        return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(sb.toString().toByteArray()))
    }

    private fun notFound(): WebResourceResponse {
        val msg = L.t("找不到這個文字檔（暫存檔可能已被清除），請重新開啟。",
            "找不到这个文本文件（缓存文件可能已被清除），请重新打开。",
            "This text file is no longer available (the cache may have been cleared). Please open it again.")
        val body = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='background:#222;color:#ddd;font:20px sans-serif;padding:40px'>${esc(msg)}"
        return WebResourceResponse("text/html", "utf-8", 404, "Not Found", null, ByteArrayInputStream(body.toByteArray()))
    }

    private fun esc(s: String) = StringBuilder().also { escInto(it, s, 0, s.length) }.toString()

    private fun escInto(sb: StringBuilder, s: String, from: Int, to: Int) {
        for (k in from until to) {
            when (val c = s[k]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\'' -> sb.append("&#39;")
                '"' -> sb.append("&quot;")
                '\r' -> {}
                else -> sb.append(c)
            }
        }
    }
}
