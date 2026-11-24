package com.duo.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Build
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URLEncoder

/** incognito = 無痕分頁（獨立的 WebView 設定檔，不記歷史、不存分頁，見 Incognito）。 */
class Tab(val web: WebView, val incognito: Boolean = false) {
    var title: String = ""
    var url: String = ""
    val label: String get() = title.ifBlank { url.ifBlank { L.t("新分頁", "新标签页", "New tab") } }
    /** 這個分頁註冊的使用者腳本（換腳本時要先移除）。 */
    val usHandlers = ArrayList<androidx.webkit.ScriptHandler>()
    /** 目前網頁的腳本選單指令（GM_registerMenuCommand），換頁時清空。 */
    val gmMenus = ArrayList<UserScripts.GmMenu>()
    /** 這一頁已經載入過的大型腳本（見 UserScripts.bundle），換頁時清空。 */
    val lazyLoaded = HashSet<String>()
    /** 由哪個分頁的連結開出來的（target=_blank 等）；在第一頁按返回 = 關掉這個分頁回到它。 */
    var opener: Tab? = null
    /** 啟動時還原、但還沒載入的分頁（切到這個分頁時才載入，App 開啟時不會同時載入所有分頁）。 */
    var pendingLoad = false
    /** 這一頁擋掉的廣告／追蹤請求數（背景執行緒會加）。 */
    val adBlocked = java.util.concurrent.atomic.AtomicInteger()
    /** 目前網頁網址，給背景執行緒的廣告阻擋判斷用。 */
    @Volatile var pageUrl: String? = null
    /** 這個分頁目前用電腦版（true）還是手機版（false）的 User-Agent。 */
    @Volatile var desktop: Boolean = Session.prefs.desktop
    /** 目前用 WebView 原本的身分（不偽裝 Chrome），給有 Cloudflare 驗證的網站用（見 Prefs.siteCompat）。 */
    @Volatile var compat: Boolean = false
    /** 網頁正在「假全螢幕」（雙屏時全螢幕元素只放上屏，見 INJECT_JS）。 */
    @Volatile var pseudoFs = false
    /** 使用者正在打字的輸入框在哪個子框架（iframe）裡；null = 在主網頁。 */
    var inputFrame: androidx.webkit.JavaScriptReplyProxy? = null
}

/** 分頁管理、WebView 設定、導覽，以及「網頁輸入框 ↔ 原生輸入面板」的橋接。 */
@SuppressLint("SetJavaScriptEnabled")
class Browser {
    private val prefs = Session.prefs
    private var lastZoomAt = 0L

    val tabs = ArrayList<Tab>()
    var current = 0
        private set
    val tab: Tab get() = tabs[current]
    val web: WebView get() = tab.web

    // ---- 讓網站把這裡當成一般的 Chrome（Google 會封鎖「內嵌 WebView」登入） ----
    private val defaultUa: String = WebSettings.getDefaultUserAgent(Session.app)
    private val chromeFull: String = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "130.0.0.0"
    private val chromeMajor: String = chromeFull.substringBefore('.')
    /** 手機版：拿掉 WebView 特有的「; wv」與「Version/4.0」 */
    private val mobileUa: String = defaultUa.replace("; wv)", ")").replace(Regex("Version/[\\d.]+ "), "")
    /**
     * 電腦版：和 Android 版 Chrome「電腦版網站」相同，回報 Linux。
     * 以前回報 Windows，但網頁讀得到 navigator.platform 是 Linux、又有觸控，前後不一致，
     * YouTube 的防機器人檢查會因此拒絕字幕（字幕請求回傳空白）。
     */
    private val desktopUa: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"

    // ---------------- 分頁 ----------------

    /** 啟動：優先還原系統保存的狀態，其次是上次開著的分頁網址，最後是首頁。 */
    fun start(saved: Bundle?, openUrl: String?) {
        val count = saved?.getInt("tabCount", 0) ?: 0
        if (count > 0) {
            for (i in 0 until count) {
                val inc = saved?.getBoolean("inc$i") == true && Incognito.supported
                val t = newTab(null, switch = false, atEnd = true, incognito = inc)
                saved?.getBundle("tab$i")?.let { t.web.restoreState(it) }
            }
            current = (saved?.getInt("tabCur", 0) ?: 0).coerceIn(0, tabs.size - 1)
        } else {
            // 先讀出上次的分頁位置：建立分頁時會呼叫 persistTabs()，把存的位置覆蓋成 0
            val urls = prefs.tabUrls.ifEmpty { listOf(prefs.lastUrl ?: prefs.home) }
            val savedIndex = prefs.tabIndex.coerceIn(0, urls.size - 1)
            val titles = prefs.tabTitles.takeIf { it.size == urls.size } ?: emptyList()
            restoring = true
            // 只載入正在看的那個分頁，其他分頁先只記網址，切過去時才載入
            // （以前開 App 時所有分頁一起載入，正在看的網頁要搶網路和 CPU，很慢）
            urls.forEachIndexed { i, u ->
                // 從外部連結開 App 時，馬上要看的是新開的那個網頁，舊分頁一個都先不載入
                val t = if (i == savedIndex && openUrl == null) newTab(u, switch = false, atEnd = true)
                else newTab(null, switch = false, atEnd = true).apply { url = normalize(u); pendingLoad = true }
                // 沒存到標題（例如舊版存的分頁）就用歷史紀錄裡同一個網址的標題
                t.title = titles.getOrNull(i)?.takeIf { it.isNotBlank() }
                    ?: History.all().firstOrNull { it.url == t.url }?.title.orEmpty()
            }
            restoring = false
            current = savedIndex
        }
        // 有外部連結時直接切到新分頁（不先選舊分頁，選了就會開始載入它）
        if (openUrl != null) newTab(openUrl, switch = true) else select(current, announce = false)
    }

    fun saveState(out: Bundle) {
        out.putInt("tabCount", tabs.size)
        out.putInt("tabCur", current)
        tabs.forEachIndexed { i, t ->
            val b = Bundle()
            t.web.saveState(b)
            out.putBundle("tab$i", b)
            out.putBoolean("inc$i", t.incognito) // 只在 App 執行中重建畫面時用（不會寫到磁碟）
        }
    }

    fun newTab(url: String?, switch: Boolean, atEnd: Boolean = false, incognito: Boolean = false): Tab {
        // 不支援無痕設定檔時不建立（絕不用一般設定檔假裝無痕）
        if (incognito && !Incognito.supported) {
            Session.ui?.flash(L.t("這台裝置的 WebView 版本太舊，不支援無痕分頁", "这台设备的 WebView 版本太旧，不支持无痕标签页", "This device's WebView is too old for incognito tabs"))
            return if (tabs.isEmpty()) newTab(url, switch, atEnd) else tab
        }
        val w = Session.engine.createWebView(incognito)
        val t = Tab(w, incognito)
        configure(t)
        val index = if (atEnd || tabs.isEmpty()) tabs.size else current + 1
        tabs.add(index, t)
        // 先記下網址：網頁還沒開始載入前存分頁清單，才不會把這個分頁漏掉
        if (url != null) normalize(url).let { t.url = it; loadIn(t, it) }
        if (switch) select(index) else persistTabs()
        return t
    }

    fun select(i: Int, announce: Boolean = true) {
        if (tabs.isEmpty()) return
        current = i.coerceIn(0, tabs.size - 1)
        val t = tabs[current]
        if (t.pendingLoad) {
            t.pendingLoad = false
            loadIn(t, t.url)
        }
        Session.engine.showWebView(t.web)
        t.web.evaluateJavascript("window.__duoShown&&window.__duoShown()", null)
        Session.engine.setViewerOnTop(false)
        Session.engine.resetShift()
        Session.ui?.onUrl(t.url.ifBlank { t.web.url })
        Session.ui?.onProgress(t.web.progress)
        Session.ui?.onTabsChanged()
        if (announce) {
            // 位置以同一組（一般／無痕）來算
            val group = tabs.filter { it.incognito == t.incognito }
            Session.ui?.flash((if (t.incognito) L.t("無痕分頁", "无痕标签页", "Incognito tab") else L.t("分頁", "标签页", "Tab")) +
                " ${group.indexOf(t) + 1}/${group.size}: ${t.label}")
        }
        persistTabs()
    }

    /** 和 Chrome 一樣：一般分頁和無痕分頁各自一組，切換分頁只在同一組裡輪流。 */
    fun switchTab(delta: Int) {
        val group = tabs.indices.filter { tabs[it].incognito == tab.incognito }
        if (group.size < 2) {
            Session.ui?.flash(L.t("只有一個分頁", "只有一个标签页", "Only one tab")); return
        }
        val pos = group.indexOf(current).coerceAtLeast(0)
        select(group[(pos + delta + group.size) % group.size])
    }

    /** 拖動分頁改順序：把 [t] 放到 [target] 的前面或後面（[after]）；目前的分頁不變。 */
    fun moveTab(t: Tab, target: Tab, after: Boolean) {
        if (t === target || t !in tabs || target !in tabs) return
        val cur = tabs[current]
        tabs.remove(t)
        tabs.add(tabs.indexOf(target) + if (after) 1 else 0, t)
        current = tabs.indexOf(cur)
        persistTabs()
        Session.ui?.onTabsChanged()
    }

    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs[i]
        if (tabs.size == 1) {
            if (!t.incognito) {
                web.loadUrl(prefs.home)
                Session.ui?.flash(L.t("已是最後一個分頁，回到首頁", "已是最后一个标签页，回到首页", "Last tab — going to home page"))
                return
            }
            // 只剩一個無痕分頁：先開一個一般分頁，再關掉它（回到一般模式）
            newTab(prefs.home, switch = false, atEnd = true)
        }
        tabs.removeAt(i)
        Session.engine.destroyWebView(t.web)
        // 最後一個無痕分頁關掉：清除無痕資料（Cookie、登入、網站儲存空間）
        if (t.incognito && tabs.none { it.incognito }) Incognito.endSession()
        if (i < current || current >= tabs.size) current--
        // 關掉後留在同一組（無痕關到沒有時回到一般分頁），選最靠近的那個
        val same = tabs.indices.filter { tabs[it].incognito == t.incognito }
        val target = same.minByOrNull { kotlin.math.abs(it - i) } ?: tabs.indices.lastOrNull { !tabs[it].incognito } ?: current
        select(target.coerceIn(0, tabs.size - 1), announce = false)
    }

    /** 關閉所有無痕分頁（Chrome 的「關閉所有無痕分頁」），回到一般分頁。 */
    fun closeAllIncognito() {
        if (tabs.none { it.incognito }) return
        if (tabs.all { it.incognito }) newTab(prefs.home, switch = false, atEnd = true)
        val wasInc = tab.incognito
        val cur = tab
        tabs.filter { it.incognito }.forEach { t -> tabs.remove(t); Session.engine.destroyWebView(t.web) }
        Incognito.endSession()
        current = if (wasInc) tabs.indices.last() else tabs.indexOf(cur).coerceAtLeast(0)
        select(current, announce = false)
        Session.ui?.flash(L.t("已關閉所有無痕分頁", "已关闭所有无痕标签页", "Closed all incognito tabs"))
    }

    /** 新的無痕分頁：和 Chrome 一樣先顯示說明頁（不直接開首頁）。 */
    fun newIncognitoTab(): Tab {
        val t = newTab(null, switch = true, incognito = true)
        if (t.incognito) {
            t.title = L.t("新無痕分頁", "新无痕标签页", "New incognito tab")
            t.web.loadDataWithBaseURL(null, incognitoPage(), "text/html", "utf-8", null)
            Session.ui?.onTabsChanged()
        }
        return t
    }

    /** 無痕分頁的說明頁（深灰底，和 Chrome 的無痕新分頁同樣的資訊）。 */
    private fun incognitoPage(): String {
        fun e(s: String) = android.text.TextUtils.htmlEncode(s)
        val title = L.t("你已開啟無痕分頁", "你已打开无痕标签页", "You've gone incognito")
        val lead = L.t("在無痕分頁瀏覽的內容不會留在這台裝置上。關掉所有無痕分頁後，這段時間的 Cookie、登入和網站資料都會清除。",
            "在无痕标签页浏览的内容不会留在这台设备上。关掉所有无痕标签页后，这段时间的 Cookie、登录和网站数据都会清除。",
            "Pages you view in incognito tabs won't stay on this device. When you close all incognito tabs, their cookies, logins and site data are cleared.")
        val notSaved = L.t("不會儲存", "不会保存", "Won't be saved")
        val ns = listOf(L.t("瀏覽紀錄", "浏览记录", "Your browsing history"), L.t("Cookie 和網站資料", "Cookie 和网站数据", "Cookies and site data"),
            L.t("網站圖示、使用者腳本的資料", "网站图标、用户脚本的数据", "Site icons and userscript data"))
        val visible = L.t("仍然可能看得到你的活動", "仍然可能看到你的活动", "Your activity might still be visible to")
        val vs = listOf(L.t("你造訪的網站", "你访问的网站", "Websites you visit"), L.t("你的雇主或學校", "你的雇主或学校", "Your employer or school"),
            L.t("你的網路服務供應商", "你的网络服务提供商", "Your internet service provider"))
        val keep = L.t("下載的檔案和加入的書籤會保留。", "下载的文件和加入的书签会保留。", "Downloads and bookmarks are kept.")
        val icon = "<svg width='56' height='56' viewBox='0 0 24 24' fill='none' stroke='#E8EAED' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>" +
            "<path d='M3 11.5h18M6 11.5l1.6-6q.4-1.3 1.7-.9L12 5.6l2.7-1q1.3-.4 1.7.9l1.6 6'/><circle cx='7.8' cy='17' r='3'/><circle cx='16.2' cy='17' r='3'/><path d='M10.8 16.6q1.2-.8 2.4 0'/></svg>"
        fun ul(items: List<String>) = items.joinToString("") { "<li>${e(it)}</li>" }
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>${e(L.t("新無痕分頁", "新无痕标签页", "New incognito tab"))}</title><style>" +
            "html,body{margin:0;background:#202124;color:#BDC1C6;font-family:sans-serif}" +
            ".w{max-width:640px;margin:0 auto;padding:48px 24px}h1{color:#E8EAED;font-size:24px;font-weight:500;margin:20px 0 12px}" +
            "p{line-height:1.6;font-size:15px}.c{display:flex;gap:32px;flex-wrap:wrap;margin-top:24px}.c div{flex:1;min-width:220px}" +
            "b{color:#E8EAED;font-weight:500;font-size:14px}ul{padding-left:20px;line-height:1.8;font-size:14px}.k{font-size:13px;color:#9AA0A6;margin-top:24px}" +
            "</style></head><body><div class='w'>$icon<h1>${e(title)}</h1><p>${e(lead)}</p>" +
            "<div class='c'><div><b>${e(notSaved)}</b><ul>${ul(ns)}</ul></div><div><b>${e(visible)}</b><ul>${ul(vs)}</ul></div></div>" +
            "<p class='k'>${e(keep)}</p></div></body></html>"
    }

    /**
     * 記下目前開著的分頁和正在看的是哪一個，下次開 App 還原。
     * 沒有網址的分頁（空白頁）不存，所以「目前分頁」的位置要用存下來的清單重新算，
     * 否則前面有空白分頁時，下次會開到別的分頁。
     */
    /** 啟動還原分頁的過程中不要存（分頁還沒載入完，會存成不完整的清單）。 */
    private var restoring = false

    private fun persistTabs() {
        if (restoring) return
        val urls = ArrayList<String>()
        val titles = ArrayList<String>()
        var idx = 0
        tabs.forEachIndexed { i, t ->
            val u = t.url.ifBlank { t.web.url ?: "" }
            if (u.startsWith("http") && !t.incognito) { // 無痕分頁不存（下次開 App 不會出現）
                if (i <= current) idx = urls.size
                urls.add(u)
                titles.add(t.title)
            }
        }
        prefs.tabUrls = urls
        prefs.tabTitles = titles
        prefs.tabIndex = idx
    }

    private fun isCurrent(t: Tab) = tabs.getOrNull(current) === t

    // ---------------- WebView 設定 ----------------

    private fun configure(t: Tab) {
        val w = t.web
        w.setBackgroundColor(Color.WHITE)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true) // target=_blank / window.open → 開新分頁
            javaScriptCanOpenWindowsAutomatically = false
            // 和一般瀏覽器一樣允許 https 網頁裡的 http 圖片／影片（仍擋 http 腳本）。
            // 預設全部擋掉，B 站留言的大圖檢視器用 http 網址載入原圖，會顯示「The image cannot be loaded」
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = prefs.textZoom
        }
        applyUa(w, t.desktop)
        // 無痕分頁的 Cookie 在自己的設定檔裡
        val cm = if (t.incognito) WebViewCompat.getProfile(w).cookieManager else CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)


        w.addJavascriptInterface(Bridge(t), "DuoBridge")
        w.addJavascriptInterface(UserScripts.Bridge(t), "DuoGM")
        w.addJavascriptInterface(AdBlock.Bridge(), "DuoAd")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // 必須最先註冊：在網頁和使用者腳本之前把 DuoGM 固定成 window.__duoGM
            WebViewCompat.addDocumentStartJavaScript(w, CAPTCHA_GUARD + UserScripts.CORE_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(w, CAPTCHA_GUARD + INJECT_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(w, CAPTCHA_GUARD + AdBlock.INJECT_JS, setOf("*"))
        }
        // 子框架（iframe）裡的輸入框（例如巴哈姆特的登入視窗）：evaluateJavascript 只跑在主網頁，
        // 打的字送不進去。改用 WebMessageListener，直接回傳給那個框架（文字不經過主網頁）。
        // 只接受和主網頁同一個網站的框架，廣告等第三方框架不能搶走使用者打的字
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(w, "DuoInput", setOf("*")) { _, msg, origin, isMain, reply ->
                if (isMain) return@addWebMessageListener
                val o = try { JSONObject(msg.data ?: return@addWebMessageListener) } catch (e: Exception) { return@addWebMessageListener }
                if (o.optString("t") != "focus") return@addWebMessageListener
                if (!trustedInputFrame(origin, t.pageUrl)) return@addWebMessageListener
                t.inputFrame = reply
                if (isCurrent(t)) Session.ui?.showEditor(o.optString("v"), o.optString("type", "text"), o.optString("hint"), o.optBoolean("multi"),
                    o.optInt("s", -1), o.optInt("e", -1))
            }
        }
        applyUserScripts(t)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && UserScripts.isScriptUrl(request.url.toString())) {
                    Session.ui?.installScriptFrom(request.url.toString())
                    return true
                }
                // 點連結到別的網站：那個網站上次用的是電腦版還是手機版不同時，換 User-Agent 重新載入
                // （轉址不處理，避免兩個網站互相轉址時不停重新載入）
                if (request.isForMainFrame && request.method == "GET" && !request.isRedirect) {
                    val u = request.url.toString()
                    val s = request.url.scheme?.lowercase()
                    if ((s == "http" || s == "https") && desktopFor(u) != t.desktop) {
                        loadIn(t, u)
                        return true
                    }
                }
                return handleUrl(view, request.url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                PdfViewer.intercept(request.url)
                    ?: AdBlock.intercept(request, t.pageUrl) { t.adBlocked.incrementAndGet() }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null) t.url = url
                t.pageUrl = url
                t.adBlocked.set(0)
                view.settings.setSupportZoom(true) // 換頁時恢復（圖片檢視器開著時會暫時關掉）
                t.gmMenus.clear()
                t.lazyLoaded.clear()
                t.pseudoFs = false
                t.inputFrame = null
                if (isCurrent(t)) {
                    Session.ui?.onUrl(url)
                    Session.ui?.onWebBlur() // 換頁了：收起幫網頁打字的輸入框
                    Session.engine.setViewerOnTop(false)
                    Session.engine.resetShift()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(INJECT_JS, null)
                checkChallenge(t, view, url)
                // 使用者腳本需要 document-start 注入（在網頁腳本之前取得 GM 介面，token 才不會外洩）；
                // 舊版 WebView 不支援時就不執行使用者腳本
                if (!t.incognito && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    // 保險：App 剛啟動的第一頁，大型腳本有時沒在開頭載入，載入完成後補一次
                    UserScripts.ensureLazyLoaded(t)
                }
                if (url != null) t.url = url
                t.title = view.title ?: t.title
                if (!t.incognito) History.updateTitle(url, view.title)
                if (isCurrent(t) && !t.incognito && url != null && url.startsWith("http")) prefs.lastUrl = url
                persistTabs()
                Session.ui?.onTabsChanged()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null) { t.url = url; t.pageUrl = url }
                if (isCurrent(t)) Session.ui?.onUrl(url)
                // YouTube 這類網頁內換頁不會觸發 onPageFinished，這裡也要記下來，下次才會開在同一頁
                persistTabs()
                // 這時 view.title 可能還是上一頁的標題，標題等 onReceivedTitle / onPageFinished 再補
                if (!t.incognito) History.record(url, null)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (isCurrent(t)) Session.ui?.onProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                t.title = title ?: ""
                if (!t.incognito) History.updateTitle(view.url, title)
                persistTabs() // 記住標題，重開 App 時分頁列表顯示網頁名稱
                Session.ui?.onTabsChanged()
            }

            // 網站圖示：存起來給分頁卡片用
            override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
                if (!t.incognito) Favicons.put(view.url, icon) // 無痕分頁不存網站圖示
                Session.ui?.onTabsChanged()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // 擋掉非使用者觸發的彈出視窗（剛執行腳本指令時例外）
                if (!isUserGesture && SystemClock.uptimeMillis() > UserScripts.popupAllowedUntil) return false
                // 無痕分頁開出來的新分頁也是無痕（同一個設定檔，Cookie/登入不外洩到一般分頁）
                val nt = newTab(null, switch = true, incognito = t.incognito)
                nt.opener = t
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                val i = tabs.indexOfFirst { it.web === window }
                if (i >= 0) closeTab(i)
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
                Session.jsDialog(message, false, null, result)

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
                Session.jsDialog(message, true, null, result)

            override fun onJsPrompt(
                view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult
            ): Boolean = Session.jsDialog(message, true, defaultValue ?: "", result)

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // 雙屏時先問網頁要不要改成假全螢幕（影片放上屏、下屏繼續顯示網頁）：
                // 網頁接手就馬上離開真的全螢幕，網頁收到 fullscreenchange 時換成假全螢幕
                // （真的全螢幕要等這個畫面放上去才會完成，網頁也才知道是哪個元素，所以先照常顯示）
                val ask = isCurrent(t) && Session.engine.topRatio() > 0f
                // 切換過程中螢幕先停在原本的畫面（不讓真的全螢幕、退回網頁這幾個中間畫面閃出來）
                if (ask) Session.engine.freeze(1500)
                Session.engine.showCustomView(view, callback)
                if (ask) {
                    pendingCustom = view to callback
                    t.web.evaluateJavascript("window.__duoNativeFs?window.__duoNativeFs():''") { r ->
                        if (r != "\"1\"") { pendingCustom = null; Session.engine.unfreeze() }
                    }
                }
            }

            override fun onHideCustomView() {
                pendingCustom = null
                Session.engine.hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams
            ): Boolean = Session.pickFiles(filePathCallback, fileChooserParams)
        }

        w.setDownloadListener { url, ua, cd, mime, _ ->
            val scheme = Uri.parse(url).scheme?.lowercase()
            if (UserScripts.isScriptUrl(url)) {
                Session.ui?.installScriptFrom(url)
            } else if ((scheme == "http" || scheme == "https") && PdfViewer.isPdf(url, cd, mime)) {
                val what = L.t("下載 PDF", "下载 PDF", "Downloading PDF")
                Session.ui?.showFileProgress(what, 0, -1)
                PdfViewer.download(url, ua, cd, mime, { done, total -> Session.ui?.showFileProgress(what, done, total) },
                    cookies = if (t.incognito) WebViewCompat.getProfile(t.web).cookieManager else CookieManager.getInstance(),
                    incognito = t.incognito) { viewer ->
                    if (viewer == null) {
                        Session.ui?.endFileProgress(L.t("PDF 下載失敗", "PDF 下载失败", "Couldn't download the PDF"))
                    } else {
                        Session.ui?.endFileProgress(null)
                        if (tabs.contains(t)) t.web.loadUrl(viewer) // 在同一個分頁開啟，按返回可回到原本的網頁
                    }
                }
            } else openExternal(Uri.parse(url))
        }
    }

    // ---------------- 電腦版 / 手機版 ----------------

    /**
     * 子框架可以接收使用者打的字嗎：和主網頁同一個網站（主網域相同），而且主網頁是 https 時框架也要是 https。
     * 大家共用的網域（github.io 等，每個子網域是不同人的網站）要完全同一個主機才算。
     */
    private fun trustedInputFrame(origin: Uri, pageUrl: String?): Boolean {
        val page = pageUrl?.let { Uri.parse(it) } ?: return false
        val ph = page.host?.lowercase() ?: return false
        val fh = origin.host?.lowercase() ?: return false
        if (page.scheme == "https" && origin.scheme != "https") return false
        if (fh == ph) return true
        val site = AdBlock.baseDomain(ph)
        if (site in SHARED_HOSTS) return false
        return AdBlock.baseDomain(fh) == site
    }

    /** 網站的主網域（電腦版／手機版記錄用）。 */
    private fun siteOf(url: String?): String? =
        url?.let { Uri.parse(it).host?.lowercase() }?.let { AdBlock.baseDomain(it) }

    /** 這個網址要用電腦版嗎：網站有記錄就照記錄，沒有就用預設。 */
    fun desktopFor(url: String?): Boolean = siteOf(url)?.let { prefs.siteDesktop(it) } ?: prefs.desktop

    /** 在分頁裡載入網址：先依那個網站的記錄換成電腦版或手機版。 */
    private fun loadIn(t: Tab, url: String) {
        val d = desktopFor(url)
        val c = siteOf(url)?.let { prefs.siteCompat(it) } ?: false
        if (d != t.desktop || c != t.compat) {
            t.desktop = d
            t.compat = c
            applyUa(t.web, d, c)
        }
        t.url = url
        t.web.loadUrl(url)
    }

    /**
     * 網頁是 Cloudflare 的人機驗證頁：偽裝成 Chrome 的 WebView 會被判定不一致，按了「驗證您是人類」
     * 也一直重來。改用 WebView 原本的身分重新載入，並記住這個網站以後都這樣用。
     */
    private fun checkChallenge(t: Tab, view: WebView, url: String?) {
        if (t.compat || url == null || !url.startsWith("http")) return
        view.evaluateJavascript("!!(window._cf_chl_opt||document.querySelector('script[src*=\"/cdn-cgi/challenge-platform/\"]'))") { r ->
            if (r != "true" || t.compat || t.web.url != url) return@evaluateJavascript
            // 無痕分頁不記（記了就留下去過這個網站的紀錄）
            if (!t.incognito) siteOf(url)?.let { prefs.addSiteCompat(it) }
            t.compat = true
            applyUa(t.web, t.desktop, true)
            t.web.reload()
        }
    }

    /** WebView 原本的 Client Hints（偽裝前先存起來，相容模式要還原）。 */
    private var defaultMd: UserAgentMetadata? = null

    @SuppressLint("RequiresFeature")
    private fun applyUa(w: WebView, desktop: Boolean, compat: Boolean = false) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) && defaultMd == null) {
            defaultMd = WebSettingsCompat.getUserAgentMetadata(w.settings)
        }
        if (compat) {
            // 相容模式：UA 和 Client Hints 都用 WebView 原本的（電腦版仍靠 viewport 寬度排版）
            w.settings.userAgentString = null
            defaultMd?.let { WebSettingsCompat.setUserAgentMetadata(w.settings, it) }
            return
        }
        w.settings.userAgentString = if (desktop) desktopUa else mobileUa
        // Client Hints（Sec-CH-UA 標頭與 navigator.userAgentData）預設會回報「Android WebView」品牌
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            fun brand(name: String, major: String, full: String) =
                UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion(full).build()
            val md = UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(
                        brand("Chromium", chromeMajor, chromeFull),
                        brand("Google Chrome", chromeMajor, chromeFull),
                        brand("Not)A;Brand", "99", "99.0.0.0"),
                    )
                )
                .setFullVersion(chromeFull)
                .setPlatform(if (desktop) "Linux" else "Android")
                .setPlatformVersion(if (desktop) "" else Build.VERSION.RELEASE)
                .setArchitecture(if (desktop) "x86" else "arm")
                .setModel(if (desktop) "" else Build.MODEL)
                .setMobile(!desktop)
                .setBitness(64)
                .build()
            WebSettingsCompat.setUserAgentMetadata(w.settings, md)
        }
        // 不送出 X-Requested-With（內含 App 套件名稱，Google 會用它判斷是內嵌 WebView）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
        }
    }

    /** 目前分頁是電腦版嗎。 */
    val isDesktop: Boolean get() = tab.desktop

    /**
     * 切換目前網頁的電腦版／手機版：只記在這個網站上（下次開同一個網站照這個設定）。
     * 沒有記錄的網站一律用預設（電腦版），不會因為在某個網站切成手機版就全部變手機版。
     */
    fun setDesktop(on: Boolean) {
        val t = tab
        if (t.desktop == on) return
        val w = t.web
        val url = t.url.ifBlank { w.url }
        val loading = w.progress < 100
        // 無痕分頁不記這個網站用哪一版（記了就留下去過這個網站的紀錄）
        if (!t.incognito) siteOf(url)?.let { prefs.setSiteDesktop(it, on) }
        // 載入中換 UA 會觸發 WebView 自動重載；先停下來，再用新設定開啟目標網址。
        if (loading) w.stopLoading()
        t.desktop = on
        applyUa(w, on, t.compat)
        Session.ui?.onDesktopModeChanged()
        if (loading && !url.isNullOrBlank()) w.loadUrl(url) else w.reload()
        Session.ui?.flash(if (on) L.t("電腦版網頁", "电脑版网页", "Desktop site") else L.t("手機版網頁", "手机版网页", "Mobile site"))
    }

    fun setDesktopWidth(px: Int) {
        prefs.desktopWidth = px
        if (tab.desktop) web.reload()
    }

    // ---------------- 導覽 ----------------

    private fun handleUrl(view: WebView, uri: Uri): Boolean {
        when (uri.scheme?.lowercase()) {
            "http", "https", "about", "data", "javascript", "blob", "file" -> return false
            "intent" -> {
                try {
                    val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    // 網頁傳來的 intent 只允許開「可瀏覽」的 App，不允許指定元件
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    try {
                        Session.startExternal(i)
                    } catch (e: ActivityNotFoundException) {
                        // 備用網址只接受 http／https（不讓網頁藉此載入 javascript:、file: 等）
                        safeLink(i.getStringExtra("browser_fallback_url"))?.let { view.loadUrl(it) }
                    }
                } catch (_: Exception) {
                }
                return true
            }
            else -> {
                openExternal(uri); return true
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            Session.startExternal(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            Session.ui?.flash(L.t("沒有可以開啟這個連結的 App", "没有可以打开这个链接的应用", "No app can open this link"))
        }
    }

    fun load(input: String) {
        val url = normalize(input)
        if (UserScripts.isScriptUrl(url)) Session.ui?.installScriptFrom(url) else loadIn(tab, url)
    }

    /** 使用者腳本有變動：重新註冊到每個分頁（下次載入網頁時生效）。 */
    fun applyUserScripts() = tabs.forEach { applyUserScripts(it) }

    @SuppressLint("RequiresFeature")
    private fun applyUserScripts(t: Tab) {
        t.usHandlers.forEach { try { it.remove() } catch (_: Exception) {} }
        t.usHandlers.clear()
        // 無痕分頁不執行使用者腳本（腳本可以用 GM_setValue 把資料永久存下來，像 Chrome 擴充功能在無痕模式預設停用）
        if (t.incognito) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        UserScripts.bundle().forEach { js ->
            try { t.usHandlers.add(WebViewCompat.addDocumentStartJavaScript(t.web, CAPTCHA_GUARD + js, setOf("*"))) } catch (_: Exception) {}
        }
    }
    /**
     * 返回：有上一頁就回上一頁；已經是這個分頁的第一頁、而且分頁是從別的網頁開出來的，
     * 就關掉這個分頁，回到開它的那個網頁。回傳是否有動作。
     */
    fun back(): Boolean {
        if (web.canGoBack()) { web.goBack(); return true }
        val o = tab.opener
        if (o == null || o === tab || o !in tabs) return false
        val closing = tab
        tabs.remove(closing)
        // 由被關掉的分頁開出來的分頁，改成接到更上一層
        tabs.forEach { if (it.opener === closing) it.opener = closing.opener }
        Session.engine.destroyWebView(closing.web)
        current = tabs.indexOf(o)
        select(current, announce = false)
        Session.ui?.flash(L.t("已關閉分頁，回到原本的網頁", "已关闭标签页，回到原来的网页", "Tab closed — back to the previous page"))
        return true
    }
    fun forward() { if (web.canGoForward()) web.goForward() else Session.ui?.flash(L.t("沒有下一頁", "没有下一页", "No next page")) }
    fun reload() = web.reload()
    fun home() = loadIn(tab, prefs.home)

    fun zoomText(delta: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastZoomAt < 150) return
        lastZoomAt = now
        val z = (prefs.textZoom + delta).coerceIn(50, 300)
        prefs.textZoom = z
        tabs.forEach { it.web.settings.textZoom = z }
        Session.ui?.flash(L.t("文字大小", "文字大小", "Text size") + " $z%")
    }

    fun clearData() {
        tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        Favicons.clear()
        Session.ui?.flash(L.t("已清除 Cookie、快取與網站資料", "已清除 Cookie、缓存和网站数据", "Cookies, cache and site data cleared"))
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return prefs.home
        val lower = t.lowercase()
        if (listOf("http://", "https://", "about:", "data:", "file:", "javascript:").any { lower.startsWith(it) }) return t
        if (!t.contains(' ') && (t.contains('.') || lower.startsWith("localhost"))) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    // ---------------- 長按連結／圖片 ----------------

    /** 只開 http／https（javascript:、file:、intent: 等不從選單開）。 */
    private fun safeLink(u: String?): String? =
        u?.trim()?.takeIf { val s = Uri.parse(it).scheme?.lowercase(); s == "http" || s == "https" }

    /**
     * 長按（SpanEngine 自己偵測，WebView 在虛擬顯示器上不會呼叫長按處理）：
     * 問網頁那個位置是不是連結或圖片。是的話顯示自己的選單（開啟、在新分頁開啟、在無痕分頁開啟、複製連結），
     * onResult(true)；不是的話 onResult(false)，照常交給網頁（例如長按選取文字）。
     * vx, vy = 虛擬顯示器座標。
     */
    fun linkAt(vx: Float, vy: Float, onResult: (Boolean) -> Unit) {
        if (tabs.isEmpty()) { onResult(false); return }
        val t = tab
        val loc = IntArray(2)
        t.web.getLocationInWindow(loc)
        val lx = vx - loc[0]; val ly = vy - loc[1]
        val js = "(function(x,y){var v=window.visualViewport,s=v?v.scale:1,d=window.devicePixelRatio||1;" +
            "var e=document.elementFromPoint(x/(d*s)+(v?v.offsetLeft:0),y/(d*s)+(v?v.offsetTop:0));if(!e)return '';" +
            "var a=e.closest&&e.closest('a[href]');var i=e.tagName==='IMG'?e:(e.closest&&e.closest('a[href]')?e.closest('a[href]').querySelector('img'):null);" +
            "return JSON.stringify({a:a?a.href:null,i:i?(i.currentSrc||i.src):null});})($lx,$ly)"
        t.web.evaluateJavascript(js) { raw ->
            val o = try { JSONObject(org.json.JSONTokener(raw).nextValue() as? String ?: "") } catch (e: Exception) { null }
            val link = safeLink(o?.optString("a")?.takeIf { it.isNotEmpty() && it != "null" })
            val img = safeLink(o?.optString("i")?.takeIf { it.isNotEmpty() && it != "null" })
            if ((link != null || img != null) && isCurrent(t)) {
                Session.ui?.showLinkMenu(link, img, t.incognito, vx, vy)
                onResult(true)
            } else onResult(false)
        }
    }
    /**
     * 從長按選單開連結：where = 0 這個分頁、1 新分頁（無痕分頁裡開的新分頁也是無痕）、2 新的無痕分頁。
     */
    fun openLink(url: String, where: Int) {
        val u = safeLink(url) ?: return
        when (where) {
            0 -> load(u)
            1 -> newTab(u, switch = true, incognito = tab.incognito)
            else -> newTab(u, switch = true, incognito = true)
        }
    }

    // ---------------- 網頁輸入框 ----------------

    /** 把指令送到正在打字的輸入框所在的框架：子框架用 WebMessage，主網頁用 evaluateJavascript。 */
    private fun toInput(cmd: String, v: String? = null, js: String, selStart: Int = -1, selEnd: Int = -1) {
        val f = tab.inputFrame
        if (f != null) {
            try {
                f.postMessage(JSONObject().put("t", cmd).apply {
                    if (v != null) put("v", v)
                    if (selStart >= 0) { put("s", selStart); put("e", selEnd) }
                }.toString())
                return
            } catch (_: Exception) {}
        }
        web.evaluateJavascript(js, null)
    }

    /** 輸入框的新內容；selStart/selEnd = 打字框的游標位置，網頁上的游標跟著放到同一個地方（可以在中間插字）。 */
    fun setEditValue(v: String, selStart: Int = -1, selEnd: Int = -1) =
        toInput("set", v, "window.__duoSet&&window.__duoSet(${JSONObject.quote(v)},$selStart,$selEnd)", selStart, selEnd)

    fun submitEdit() = toInput("submit", js = "window.__duoSubmit&&window.__duoSubmit()")

    /** 等網頁決定的真全螢幕（見 onShowCustomView）。 */
    private var pendingCustom: Pair<View, WebChromeClient.CustomViewCallback>? = null

    /** take = true：網頁改用假全螢幕，離開真的全螢幕；false：照原本的全螢幕（影片放上屏）。 */
    private fun decideCustom(take: Boolean) {
        val p = pendingCustom ?: return
        pendingCustom = null
        if (take) { p.second.onCustomViewHidden(); Session.engine.hideCustomView() } // 畫面等 fsState(true) 再恢復
        else Session.engine.unfreeze()
    }

    /** 返回鍵：離開假全螢幕。 */
    fun exitPseudoFs(): Boolean {
        if (tabs.isEmpty() || !tab.pseudoFs) return false
        // 下屏開著圖片檢視器時先關檢視器（影片繼續全螢幕）；沒有才離開全螢幕（網頁會用 fsState(false) 回報）
        web.evaluateJavascript("(window.__duoCloseViewer&&window.__duoCloseViewer())||(window.__duoExitFs&&window.__duoExitFs())", null)
        return true
    }

    fun blurEdit() = toInput("blur", js = "window.__duoEl&&window.__duoEl.blur&&window.__duoEl.blur()")

    inner class Bridge(private val t: Tab) {
        @JavascriptInterface
        fun onEditFocus(value: String?, type: String?, hint: String?, multiline: Boolean, selStart: Int, selEnd: Int) {
            Session.main.post {
                t.inputFrame = null // 主網頁的輸入框
                if (isCurrent(t)) Session.ui?.showEditor(value ?: "", type ?: "text", hint ?: "", multiline, selStart, selEnd)
            }
        }

        /**
         * 網頁的全螢幕圖片檢視器狀態。沒放大時只用上屏；放大時跨上下兩個螢幕。
         * 檢視器開著時關掉 WebView 自己的縮放：檢視器用「點兩下」放大圖片，
         * 不關的話 WebView 也會同時把整個網頁放大，縮回去之後畫面就錯位了。
         */
        @JavascriptInterface
        fun onEditBlur() {
            Session.main.post { if (isCurrent(t)) Session.ui?.onWebBlur() }
        }

        @JavascriptInterface
        fun viewerState(open: Boolean, zoomed: Boolean) {
            Session.main.post {
                if (!tabs.contains(t)) return@post
                t.web.settings.setSupportZoom(!open)
                if (isCurrent(t)) Session.engine.setViewerOnTop(open && !zoomed)
            }
        }

        /** 假全螢幕要佔網頁高度的幾分之幾（= 上屏高度 / 網頁高度）；0 = 不用假全螢幕（不是雙屏）。 */
        @JavascriptInterface
        fun fsTopRatio(): Float = try { if (isCurrent(t)) Session.engine.topRatio() else 0f } catch (e: Exception) { 0f } // 網頁執行緒，分頁清單可能正在變動

        /** 雙屏位置（見 SpanEngine.screenGeom）。 */
        @JavascriptInterface
        fun screenGeom(): String = try { if (isCurrent(t)) Session.engine.screenGeom() else "" } catch (e: Exception) { "" }

        /** 網頁決定要不要接手真的全螢幕（見 onShowCustomView）。 */
        @JavascriptInterface
        fun fsDecision(take: Boolean) {
            Session.main.post { if (isCurrent(t)) decideCustom(take) }
        }

        @JavascriptInterface
        fun fsState(on: Boolean) {
            t.pseudoFs = on
            Session.main.post {
                if (isCurrent(t)) Session.engine.resetShift()
                // 假全螢幕排好了：等網頁畫出新版面再讓螢幕換過去
                if (on) Session.engine.unfreeze(250)
            }
        }

        /** 電腦版時要強制的版面寬度（CSS px），0 = 不強制。網頁在載入最開頭就會呼叫。 */
        @JavascriptInterface
        fun desktopWidth(): Int = if (t.desktop && !PdfViewer.isViewerUrl(t.pageUrl)) prefs.desktopWidth else 0 // PDF 閱讀器（pdf.js）用原本的寬度

        @JavascriptInterface
        fun compat(): Boolean = t.compat
    }

    companion object {
        /**
         * 人機驗證的框架（Cloudflare Turnstile、hCaptcha、reCAPTCHA）裡不執行 App 注入的任何腳本：
         * 驗證會檢查環境有沒有被改過，改過就一直驗證不過。在腳本最前面丟出例外，整段腳本就不會執行。
         */
        const val CAPTCHA_GUARD = "if(/(^|\\.)(challenges\\.cloudflare\\.com|hcaptcha\\.com|recaptcha\\.net)$/.test(location.hostname)" +
            "||(/(^|\\.)google\\.com$/.test(location.hostname)&&location.pathname.indexOf('/recaptcha/')===0))throw 0;\n"

        fun isCaptchaHost(host: String, path: String?): Boolean =
            host == "challenges.cloudflare.com" || host.endsWith(".hcaptcha.com") || host == "hcaptcha.com" ||
                host.endsWith("recaptcha.net") || (path?.startsWith("/recaptcha/") == true && (host == "google.com" || host.endsWith(".google.com"))) ||
                path?.startsWith("/cdn-cgi/challenge-platform/") == true

        /** 子網域屬於不同人的共用網域（見 trustedInputFrame）。 */
        private val SHARED_HOSTS = setOf(
            "github.io", "gitlab.io", "blogspot.com", "appspot.com", "pages.dev", "workers.dev", "vercel.app",
            "netlify.app", "herokuapp.com", "web.app", "firebaseapp.com", "glitch.me", "azurewebsites.net",
            "cloudfront.net", "amazonaws.com", "wordpress.com", "tumblr.com", "wixsite.com", "neocities.org",
        )

        /**
         * 1. 電腦版：把 <meta name=viewport> 改成固定寬度，網頁才會用電腦版版面排版。
         * 2. 虛擬顯示器上的 WebView 拿不到系統輸入法 → 偵測使用者點了輸入框，
         *    改在實體螢幕開原生輸入面板，再把文字寫回去。
         */
        const val INJECT_JS = """
(function(){
  if (window.__duoInit) return; window.__duoInit = true;

  // 一般的 Chrome 都有 window.chrome；WebView 沒有，部分網站（例如 Google 登入）會拿來判斷
  // 相容模式（Cloudflare 驗證的網站）不偽裝
  var compat = false; try { compat = DuoBridge.compat(); } catch (x) {}
  try {
    if (!window.chrome && !compat) {
      Object.defineProperty(window, 'chrome', { value: {
        app: { isInstalled: false, InstallState: {}, RunningState: {} },
        runtime: {},
        loadTimes: function(){ return {}; },
        csi: function(){ return {}; }
      }, configurable: true, writable: true });
    }
  } catch (x) {}

  var dw = 0; try { dw = DuoBridge.desktopWidth(); } catch (x) {}
  if (dw > 0 && window.top === window) {
    var want = 'width=' + dw;
    var fix = function(){
      var root = document.head || document.documentElement; if (!root) return;
      var ms = document.querySelectorAll('meta[name="viewport"]');
      if (!ms.length) { var m = document.createElement('meta'); m.name = 'viewport'; m.content = want; root.appendChild(m); return; }
      for (var i = 0; i < ms.length; i++) if (ms[i].content !== want) ms[i].content = want;
    };
    fix();
    var mo = new MutationObserver(fix);
    var startObs = function(){ if (document.documentElement) mo.observe(document.documentElement, {childList: true, subtree: true}); else setTimeout(startObs, 10); };
    startObs();
    document.addEventListener('DOMContentLoaded', fix);
    window.addEventListener('load', function(){ fix(); setTimeout(function(){ fix(); mo.disconnect(); }, 2000); });

    // 電腦版網頁整個縮小顯示，沉浸式翻譯的懸浮球跟著變得很小：放大 2 倍（貼著螢幕邊緣放大）
    var imtFix = function(){
      var h = document.getElementById('immersive-translate-popup');
      var sr = h && h.shadowRoot;
      if (!sr || sr.getElementById('duo-imt-scale')) return;
      var st = document.createElement('style'); st.id = 'duo-imt-scale';
      st.textContent = '.imt-fb-container{transform:scale(2);transform-origin:right center}' +
        '.imt-fb-container.left{transform-origin:left center}';
      sr.appendChild(st);
    };
    setInterval(imtFix, 1000);

    // 電腦版 YouTube 整個縮小顯示，播放器底下的控制列（播放、音量、設定、全螢幕…）太小：放大 1.5 倍。
    // 寬度先減半再放大，剛好佔滿播放器寬度；字幕在控制列出現時多往上移，不會被蓋住。
    // 放大後放不下：拿掉用不到的「自動播放」開關、劇院模式、迷你播放器、投放按鈕，時間才不會被擠掉
    if (/(^|\.)youtube\.com$/.test(location.hostname)) {
      var ytBig = function(){
        if (document.getElementById('duo-yt-big')) return;
        var root = document.head || document.documentElement;
        if (!root) { setTimeout(ytBig, 50); return; }
        var s = document.createElement('style'); s.id = 'duo-yt-big';
        s.textContent = '.html5-video-player .ytp-chrome-bottom{transform:scale(1.5)!important;transform-origin:left bottom!important;width:calc((100% - 24px) / 1.5)!important}' +
          '.html5-video-player:not(.ytp-autohide) .ytp-caption-window-container{transform:translateY(-30px)}' +
          '.html5-video-player .ytp-autonav-toggle-button-container,.html5-video-player .ytp-autonav-toggle,' +
          '.html5-video-player .ytp-size-button,.html5-video-player .ytp-miniplayer-button,.html5-video-player .ytp-remote-button{display:none!important}' +
          '.html5-video-player .ytp-time-display{flex-shrink:0!important}';
        root.appendChild(s);
      };
      ytBig();
      document.addEventListener('DOMContentLoaded', ytBig);

      // Shorts：原本一則影片幾乎和上下兩個螢幕一樣高，卡在轉軸上。雙屏時把每則縮成上屏高，
      // 每則之間的距離剛好是「上屏頂到下屏頂」：上屏播目前這則，下一則從下屏頂端開始
      var shortsKey = '';
      var shorts = function(){
        var st = document.getElementById('duo-shorts');
        var g = '';
        if (location.pathname.indexOf('/shorts') === 0) { try { g = DuoBridge.screenGeom(); } catch (x) {} }
        if (!g) { if (st) { st.remove(); shortsKey = ''; } return; }
        var p = g.split(','), ih = innerHeight;
        var topCss = ih * +p[0], bStart = ih * +p[1];
        var items = document.querySelectorAll('.reel-video-in-sequence-new');
        if (!items.length) return;
        // 目前停在畫面上的那則（頂端在 0 以下最靠近的）決定每則的起點
        var cur = null;
        for (var i = 0; i < items.length; i++) { var t0 = items[i].getBoundingClientRect().top; if (t0 >= -2) { cur = items[i]; break; } }
        if (!cur) return;
        var T = Math.max(0, cur.getBoundingClientRect().top);
        var P = Math.max(300, Math.round(topCss - T - 16));          // 播放器高度：上屏放得下
        var sub = Math.round(ih - 96 - P);
        if (!st) { st = document.createElement('style'); st.id = 'duo-shorts'; (document.head || document.documentElement).appendChild(st); }
        var h = Math.round(cur.getBoundingClientRect().height);
        var key = [ih, p.join(','), Math.round(T), h].join('|');
        if (key === shortsKey) return;
        shortsKey = key;
        var mb = Math.max(16, Math.round(bStart - T - h));
        // 留言等面板：YouTube 在窄畫面會把它疊在影片下面（還會把影片調暗）。改成和一般電腦版一樣放在影片右邊，
        // 高度和影片相同，都在上屏
        var cb = cur.getBoundingClientRect(), right = cb.right;
        cur.querySelectorAll('button,[role="button"]').forEach(function(b){ var r = b.getBoundingClientRect(); if (r.width > 0 && r.right > right) right = r.right; });
        right = Math.round(right + 12);
        var pw = Math.max(280, Math.min(480, innerWidth - right - 12));
        if (right + pw > innerWidth) right = Math.max(0, innerWidth - pw);
        st.textContent = 'ytd-shorts{--ytd-shorts-player-height-subtraction:' + sub + 'px!important}' +
          '.reel-video-in-sequence-new{margin-bottom:' + mb + 'px!important}' +
          'ytd-shorts #anchored-panel{position:fixed!important;left:' + right + 'px!important;top:' + Math.round(T) + 'px!important;' +
          'width:' + pw + 'px!important;height:' + h + 'px!important;max-height:' + h + 'px!important;bottom:auto!important;right:auto!important;' +
          'transform:none!important;z-index:3000!important}' +
          'ytd-shorts #anchored-panel ytd-engagement-panel-section-list-renderer{width:100%!important;height:100%!important;max-height:100%!important}' +
          'ytd-shorts #anchored-panel-scrim{display:none!important}';
        // 播放器高度改了，下一輪再依新的高度算一次間距
        setTimeout(shorts, 300);
      };
      setInterval(shorts, 1000);
      window.addEventListener('resize', function(){ shortsKey = ''; setTimeout(shorts, 200); });
    }
  }

  // 全螢幕圖片檢視器（PhotoSwipe，例如 B 站留言的大圖）開著時通知原生：雙屏時改成只用上屏顯示，
  // 不然圖片會卡在兩個螢幕正中間（轉軸上）
  // 影片不要用 WebView 的「獨立影片層」（硬體疊加層）顯示：雙屏時網頁畫在虛擬螢幕上，
  // 分頁切走（WebView 藏起來或移到畫面外）後那一層常常留在原地沒拿掉，蓋住目前分頁的影片——
  // 看起來就是影片卡住、只有聲音，捲動或重播都沒用。加一個看不出來的濾鏡，影片就和網頁一起畫
  (function(){
    function add(){
      if (document.getElementById('duo-video-flat')) return;
      var st = document.createElement('style'); st.id = 'duo-video-flat';
      st.textContent = 'video{filter:brightness(1.0001)!important}';
      (document.head || document.documentElement).appendChild(st);
    }
    if (document.documentElement) add(); else document.addEventListener('DOMContentLoaded', add);
  })();

  // 視窗真正的高度（下屏檢視器期間 window.innerHeight 會被暫時改成下屏的高度）
  var IHD = Object.getOwnPropertyDescriptor(window, 'innerHeight');
  window.__duoIH = function(){ try { return IHD && IHD.get ? IHD.get.call(window) : innerHeight; } catch (x) { return innerHeight; } };
  var __duoIH = window.__duoIH;
  //
  // 影片假全螢幕（影片在上屏）時開檢視器：檢視器改放滿下屏，影片繼續在上屏播放。
  // PhotoSwipe 用 window.innerHeight 當畫面高度：開著時暫時回報下屏的高度，圖片就會符合下屏
  if (window.top === window) {
    var vState = '', bv = null;
    function bvOff(){
      if (!bv) return;
      if (IHD) { try { Object.defineProperty(window, 'innerHeight', IHD); } catch (x) {} }
      if (bv.el && bv.el.isConnected) ['position', 'top', 'left', 'width', 'height', 'bottom', 'right', 'z-index'].forEach(function(k){ bv.el.style.removeProperty(k); });
      bv = null;
      window.dispatchEvent(new Event('resize'));
    }
    function bvOn(el){
      var g = ''; try { g = DuoBridge.screenGeom(); } catch (x) {}
      if (!g) { bvOff(); return; }
      var p = g.split(','), ih = __duoIH();
      var top = Math.round(ih * +p[1]), h = Math.round(ih * (+p[2] - +p[1]));
      if (bv && bv.el === el && bv.top === top && bv.h === h) return;
      bv = {el: el, top: top, h: h};
      try { Object.defineProperty(window, 'innerHeight', {get: function(){ return bv ? bv.h : __duoIH(); }, configurable: true}); } catch (x) {}
      var set = {position: 'fixed', top: top + 'px', left: '0', right: 'auto', bottom: 'auto', width: '100vw', height: h + 'px', 'z-index': '2147483647'};
      for (var k in set) el.style.setProperty(k, set[k], 'important');
      window.dispatchEvent(new Event('resize'));
      [300, 800].forEach(function(ms){ setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, ms); });
    }
    setInterval(function(){
      // 放大圖片（pswp--zoomed-in）時恢復成跨上下兩個螢幕，放大後的圖片可以延伸到下屏
      var el = document.querySelector('.pswp--open');
      var open = !!el;
      var zoomed = open && !!document.querySelector('.pswp--zoomed-in');
      var inFs = open && !!(window.__duoFsOn && window.__duoFsOn());
      if (inFs) bvOn(el); else bvOff();
      var s = open + ',' + zoomed + ',' + inFs;
      if (s !== vState) {
        // 假全螢幕時網頁高度不變（影片留在上屏），只有一般瀏覽時才改成只用上屏
        vState = s; try { DuoBridge.viewerState(open && !inFs, zoomed); } catch (x) {}
        // 網頁高度改變時檢視器可能還在動畫中，不會重新縮放：稍後再補送 resize，讓圖片重新符合畫面大小
        if (open && !inFs) [400, 900].forEach(function(ms){ setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, ms); });
      }
    }, 300);
    // 返回鍵：下屏開著檢視器時先關檢視器，再按一次才離開全螢幕
    window.__duoCloseViewer = function(){
      var el = document.querySelector('.pswp--open'); if (!el) return false;
      var c = el.querySelector('.pswp__button--close');
      if (c) c.click(); else document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', keyCode: 27, bubbles: true}));
      return true;
    };
  }

  // 雙屏時網頁要求全螢幕（例如 YouTube 影片）：改成「假全螢幕」，把那個元素固定在上屏，
  // 下屏繼續顯示網頁（留言等）。真的全螢幕時整個網頁只剩影片，下屏會全黑。
  //
  // 不改寫瀏覽器內建的 requestFullscreen 等函式（YouTube 的防機器人檢查會發現，字幕就抓不到）：
  // 網頁照常要求真的全螢幕 → 原生的 onShowCustomView 先呼叫 __duoNativeFs() 記下是哪個元素 →
  // 原生離開真的全螢幕 → 這裡收到 fullscreenchange 時改成假全螢幕。只有假全螢幕期間才在 document 上暫時加東西
  if (window.top === window) (function(){
    var DP = Document.prototype;
    var EXITS = ['exitFullscreen', 'webkitExitFullscreen', 'webkitCancelFullScreen'];
    // fs = 實際固定在上屏的元素（document.fullscreenElement 回報這個）；asked = 網頁要求全螢幕的元素。
    // 電腦版 YouTube 要求 <html>、實際固定播放器；回報 <html> 的話 YouTube 會換成自己的全螢幕版面（下屏全黑）
    var fs = null, asked = null, st = null, ph = null, r = 0;
    var ELP = ['fullscreenElement', 'webkitFullscreenElement', 'webkitCurrentFullScreenElement'];
    var BP = ['fullscreen', 'webkitIsFullScreen'];
    function ratio(){ try { return +DuoBridge.fsTopRatio() || 0; } catch (x) { return 0; } }
    function fire(el){
      ['fullscreenchange', 'webkitfullscreenchange'].forEach(function(n){
        try { (el && el.isConnected ? el : document).dispatchEvent(new Event(n, {bubbles: true})); } catch (x) {}
      });
      setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 50);
    }
    function css(e){
      if (e) { r = ratio(); if (!(r > 0 && r < 1)) { exit(); return; } } // 視窗大小變了（例如切換成單屏）
      var h = Math.round(__duoIH() * r);
      // 選擇器加重權重：網頁自己也有 !important 的規則（例如 YouTube 的 overflow:visible!important）
      var S = ':root [data-duo-fs][data-duo-fs]';
      st.textContent = S + '{position:fixed!important;left:0!important;top:0!important;right:auto!important;bottom:auto!important;' +
        'width:100vw!important;max-width:none!important;min-width:0!important;height:' + h + 'px!important;max-height:none!important;min-height:0!important;' +
        'margin:0!important;transform:none!important;z-index:2147483647!important;background:#000!important;overflow:hidden!important}' +
        // 播放器會依整個視窗的大小排影片（例如 YouTube）：影片直接填滿上屏
        S + ' video{width:100vw!important;height:' + h + 'px!important;left:0!important;top:0!important;object-fit:contain!important}' +
        S + ' [data-duo-fs-fit][data-duo-fs-fit]{height:' + h + 'px!important;max-height:' + h + 'px!important;min-height:0!important}' +
        ':root [data-duo-fs-anc][data-duo-fs-anc]{z-index:2147483647!important}' +
        '#duo-fs-ph{display:block!important;width:100%;height:' + h + 'px}';
      if (ph) ph.style.height = h + 'px';
      // 重要的幾項也直接寫在元素上（!important）：有的網站（例如 B 站直播）收到 fullscreenchange 後
      // 會加上自己的全螢幕 class，裡面的 !important 規則權重比樣式表高
      pin(fs, {position: 'fixed', left: '0', top: '0', right: 'auto', bottom: 'auto', width: '100vw', 'max-width': 'none',
        'min-width': '0', height: h + 'px', 'max-height': 'none', 'min-height': '0', margin: '0', transform: 'none',
        'z-index': '2147483647', overflow: 'hidden'});
      extras();
      fit();
    }
    // B 站直播：全螢幕時網頁會把右邊的聊天室藏起來（body.fullscreen-fix）：改成把聊天室放滿下屏
    function extras(){
      if (!/(^|\.)live\.bilibili\.com$/.test(location.hostname)) return;
      var g = ''; try { g = DuoBridge.screenGeom(); } catch (x) {}
      var s = document.getElementById('duo-blive');
      if (!g) { if (s) s.remove(); return; }
      var p = g.split(','), ih = __duoIH();
      var bs = Math.round(ih * +p[1]), bh = Math.round(ih * (+p[2] - +p[1]));
      if (!s) { s = document.createElement('style'); s.id = 'duo-blive'; (document.head || document.documentElement).appendChild(s); }
      s.textContent = '#aside-area-vm{display:flex!important;flex-direction:column!important;position:fixed!important;left:0!important;right:auto!important;' +
        'top:' + bs + 'px!important;width:100vw!important;height:' + bh + 'px!important;z-index:2147483646!important}';
    }
    // 寫在元素上的樣式：記下原本的值，離開時只拿掉自己加的
    var pinned = [];
    function pin(e, props){
      var rec = null;
      for (var i = 0; i < pinned.length; i++) if (pinned[i].e === e) { rec = pinned[i]; break; }
      if (!rec) { rec = {e: e, orig: {}}; pinned.push(rec); }
      for (var k in props) {
        if (!(k in rec.orig)) rec.orig[k] = [e.style.getPropertyValue(k), e.style.getPropertyPriority(k)];
        e.style.setProperty(k, props[k], 'important');
      }
    }
    function unpinAll(){
      pinned.forEach(function(rec){
        for (var k in rec.orig) { var o = rec.orig[k]; if (o[0]) rec.e.style.setProperty(k, o[0], o[1]); else rec.e.style.removeProperty(k); }
      });
      pinned = [];
    }
    // 網頁進入全螢幕後常把播放器裡面的層設成整個視窗高（100vh 等）：比上屏高的層縮成上屏高，
    // 播放器會依新的大小重新排影片；上層元素拉到最上面，網頁其他東西才不會蓋在影片上
    function fit(){
      if (!fs) return;
      var h = Math.round(__duoIH() * r), n = 0;
      var list = fs.querySelectorAll('*');
      for (var i = 0; i < list.length && i < 3000; i++) {
        var e = list[i], t = e.tagName;
        if (t === 'VIDEO' || t === 'CANVAS' || t === 'IFRAME' || t === 'IMG') continue;
        if (e.hasAttribute('data-duo-fs-fit')) { pin(e, {height: h + 'px', 'max-height': h + 'px', 'min-height': '0'}); continue; }
        var b = e.getBoundingClientRect();
        if (b.height > h + 2 && Math.abs(b.top) <= 2) {
          e.setAttribute('data-duo-fs-fit', '');
          pin(e, {height: h + 'px', 'max-height': h + 'px', 'min-height': '0'});
          n++;
        }
      }
      if (fs) pin(fs, {position: 'fixed', top: '0', height: h + 'px'}); // 網頁改了 style 屬性時補回來
      for (var a = fs.parentElement; a && a !== document.body && a !== document.documentElement; a = a.parentElement) a.setAttribute('data-duo-fs-anc', '');
      if (n) window.dispatchEvent(new Event('resize'));
    }
    function enter(el){
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute' && el.parentNode && el.parentNode.nodeType === 1) {
        // 原本在版面裡的元素改成固定後，下面的內容會往上縮：補一塊同高的空白，網頁內容從下屏開始
        ph = document.createElement('div'); ph.id = 'duo-fs-ph';
        el.parentNode.insertBefore(ph, el);
      }
      fs = el;
      el.setAttribute('data-duo-fs', '');
      st = document.createElement('style'); st.id = 'duo-fs';
      (document.head || document.documentElement).appendChild(st);
      css();
      var b = el.getBoundingClientRect();
      if (Math.abs(b.top) > 2 || Math.abs(b.height - Math.round(__duoIH() * r)) > 4) { cleanup(); return false; } // 上層有 transform 等，固定不了
      ELP.forEach(function(p){ Object.defineProperty(document, p, {get: function(){ return fs; }, configurable: true}); });
      BP.forEach(function(p){ Object.defineProperty(document, p, {get: function(){ return true; }, configurable: true}); });
      // 網頁按自己的「離開全螢幕」會呼叫 document.exitFullscreen()：假全螢幕期間暫時接過來。
      // 只在使用者剛按了播放器裡的東西（或按鍵）時才離開：B 站播放器在網頁捲動時也會自己呼叫，
      // 真的全螢幕不會捲動所以沒事，假全螢幕時下屏一滑網頁就被退出
      EXITS.forEach(function(p){ try { Object.defineProperty(document, p, {value: function(){ if (Date.now() - lastAct < 1000) exit(); return Promise.resolve(); }, configurable: true, writable: true}); } catch (x) {} });
      window.addEventListener('resize', css);
      try { DuoBridge.fsState(true); } catch (x) {}
      fire(el);
      // 網頁收到 fullscreenchange 後才改版面（有的還是非同步）：之後再檢查幾次
      [100, 400, 1000, 2000].forEach(function(ms){ setTimeout(fit, ms); });
      return true;
    }
    function cleanup(){
      unpinAll();
      document.querySelectorAll('[data-duo-fs-fit],[data-duo-fs-anc]').forEach(function(e){
        e.removeAttribute('data-duo-fs-fit'); e.removeAttribute('data-duo-fs-anc');
      });
      if (fs) fs.removeAttribute('data-duo-fs');
      if (st) st.remove(); if (ph) ph.remove();
      var bl = document.getElementById('duo-blive'); if (bl) bl.remove();
      st = null; ph = null; fs = null; asked = null;
    }
    function exit(){
      if (!fs) { try { DuoBridge.fsState(false); } catch (x) {} return; } // 原生記的狀態也要清掉（返回鍵靠它判斷）
      var el = fs;
      cleanup();
      ELP.concat(BP, EXITS).forEach(function(p){ delete document[p]; });
      window.removeEventListener('resize', css);
      try { DuoBridge.fsState(false); } catch (x) {}
      fire(el);
      if (frameWin) { var w = frameWin; frameWin = null; try { w.__duoFsClear(); } catch (x) {} }
    }
    window.__duoExitFs = exit;
    window.__duoFsOn = function(){ return !!fs; };
    // 使用者最近一次在全螢幕的播放器上按下／點擊、或按鍵盤的時間（捲動會先送 pointercancel，不會有 pointerup）
    var lastAct = 0;
    function act(e){ if (fs && (e.type === 'keydown' || (e.target && e.target.nodeType === 1 && fs.contains(e.target)))) lastAct = Date.now(); }
    ['pointerup', 'click', 'keydown'].forEach(function(n){ document.addEventListener(n, act, true); });
    // 真的全螢幕元素（不經過假全螢幕加上去的屬性）
    function realFs(d){
      var a = Object.getOwnPropertyDescriptor(DP, 'fullscreenElement'), b = Object.getOwnPropertyDescriptor(DP, 'webkitFullscreenElement');
      try { return (a && a.get ? a.get.call(d) : null) || (b && b.get ? b.get.call(d) : null) || null; } catch (x) { return null; }
    }
    // 同網站子框架（iframe）裡的播放器要全螢幕（例如 B 站賽事直播的播放器是 live.bilibili.com/blanc 框架）：
    // 把整個框架固定在上屏；框架裡的網頁自己假裝成全螢幕（見下面子框架的那段）
    var frameWin = null, pending = null, pendingExit = false;
    // 原生 onShowCustomView 呼叫（這時網頁的 fullscreenElement 可能還沒設好）：回傳 '1' = 這裡會決定，
    // 等真的全螢幕元素出來後呼叫 DuoBridge.fsDecision(接手與否)；'' = 照原本的全螢幕
    var armed = false;
    window.__duoNativeFs = function(){
      if (!fs) { r = ratio(); if (!(r > 0 && r < 1)) return ''; }
      armed = true;
      if (realFs(document)) setTimeout(decide, 0);
      return '1';
    };
    function tell(take){ try { DuoBridge.fsDecision(take); } catch (x) {} }
    function decide(){
      if (!armed) return;
      var el = realFs(document);
      if (!el) return;
      armed = false;
      if (fs) {
        // 假全螢幕時網頁又要求全螢幕（例如電腦版 YouTube 再按一次全螢幕鍵）：當成離開
        pendingExit = true;
        tell(true);
        return;
      }
      var target = el, win = null, inner = null;
      // 整個網頁要全螢幕（例如電腦版 YouTube 對 <html> 要求）：改成只把影片播放器放上屏
      if (el === document.documentElement || el === document.body) target = player();
      if (el.tagName === 'IFRAME') {
        try { win = el.contentWindow; inner = realFs(el.contentDocument); if (!win.__duoFakeFs) target = null; } catch (x) { target = null; } // 不同來源的框架照原本的全螢幕
      }
      if (!target) { tell(false); return; }
      pending = {target: target, asked: el, win: win, inner: inner};
      tell(true);
    }
    // 真的全螢幕開始時決定要不要接手；結束時（原生剛剛離開）改成假全螢幕。
    // 用 capture 比網頁自己的處理先跑，網頁看到的就是假全螢幕
    function onReal(){
      if (realFs(document)) { decide(); return; }
      if (pendingExit) { pendingExit = false; exit(); return; }
      var p = pending; if (!p) return;
      pending = null;
      asked = p.asked;
      var ok = false;
      try { ok = enter(p.target); } catch (x) { try { cleanup(); } catch (y) {} }
      if (ok && p.win) { frameWin = p.win; try { p.win.__duoFakeFs(p.inner); } catch (x) {} }
    }
    document.addEventListener('fullscreenchange', onReal, true);
    document.addEventListener('webkitfullscreenchange', onReal, true);
    // 播放器代替整個網頁全螢幕時（電腦版 YouTube），網頁自己記的狀態是「沒有全螢幕」，
    // 按播放器的全螢幕鍵不會離開：直接攔下這個按鍵，由這裡離開
    //
    // 觸控時有的播放器（例如全螢幕狀態的 YouTube）只有按下／放開、不會產生 click，所以放開（pointerup）就處理；
    // 處理過之後緊接著的 click 吞掉，避免網頁自己又切換一次
    var lastFsTap = 0;
    function swallow(e){ e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); }
    // 放開的位置要和按下的是同一個元素（拖過按鈕再放開不算）
    var downEl = null;
    document.addEventListener('pointerdown', function(e){ downEl = e.target; }, true);
    function recentClick(e){
      if (e.type === 'click' && Date.now() - lastFsTap < 700) { swallow(e); return true; }
      return e.type === 'pointerup' && e.target !== downEl;
    }
    // B 站直播的控制鍵沒有任何文字標示：右邊控制區最後一個圖示就是全螢幕鍵
    function biliFsBtn(t){
      if (!/(^|\.)live\.bilibili\.com$/.test(location.hostname) || !t || !t.closest) return null;
      var ic = t.closest('.icon'), area = ic && ic.closest('.web-player-controller-wrap .right-area');
      if (!area) return null;
      var all = area.querySelectorAll('.icon');
      return all[all.length - 1] === ic ? ic : null;
    }
    function exitTap(e){
      if (recentClick(e)) return;
      if (!fs || !asked || asked === fs || e.button) return;
      var bi = biliFsBtn(e.target);
      if (bi && fs.contains(bi)) { swallow(e); lastFsTap = Date.now(); exit(); return; }
      var b = e.target && e.target.closest && e.target.closest('button,[role="button"]');
      if (!b || !fs.contains(b)) return;
      var bb = b.getBoundingClientRect();
      if (bb.width > 160 || bb.height > 160 || b.querySelector('video')) return;
      // 只認全螢幕鍵本身（class 是 *fullscreen-button/icon，或標籤寫全螢幕），全螢幕時的推薦影片等其他按鈕不攔
      var c = String(b.className || '').toLowerCase();
      var s = ((b.getAttribute('aria-label') || '') + ' ' + (b.title || '')).toLowerCase();
      if (/fullscreen-(button|icon)/.test(c) || /fullscreen|full screen|全螢幕|全屏|全画面/.test(s)) { swallow(e); lastFsTap = Date.now(); exit(); }
    }
    document.addEventListener('pointerup', exitTap, true);
    document.addEventListener('click', exitTap, true);
    // 按播放器的全螢幕鍵：直接排成假全螢幕，不經過真的全螢幕。
    // 經過真的全螢幕要先進去再退出來（約 1.7 秒），退出後影片畫面還要再等幾秒才會出來，中間一直閃
    function enterTap(e){
      if (recentClick(e)) return;
      if (fs || e.button) return;
      var t = e.target && e.target.closest ? e.target : null; if (!t) return;
      var b = biliFsBtn(t);
      if (!b) {
        b = t.closest('button,[role="button"],[class*="full"],[aria-label],[title]');
        if (!b || b === document.body) return;
        // 只認按鈕大小的元素：播放器外框本身的 class 也常有 fullscreen 字樣（例如 YouTube），點影片不能當成按全螢幕鍵
        var bb = b.getBoundingClientRect();
        if (bb.width > 160 || bb.height > 160 || b.querySelector('video')) return;
        var c = String(b.className && b.className.baseVal !== undefined ? b.className.baseVal : b.className || '').toLowerCase();
        var s = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.getAttribute('data-title') || '')).toLowerCase();
        var isFs = /fullscreen|full-screen|ctrl-full\b|btn-full\b/.test(c) || /fullscreen|full screen|全螢幕|全屏|全画面/.test(s);
        if (!isFs || /exit|退出|離開|结束|結束|web-?fullscreen|网页全屏|網頁全螢幕/.test(s + ' ' + c)) return;
      }
      // 全螢幕鍵所在的播放器：往上找第一個裡面有影片的元素；YouTube 用 #movie_player
      var target = document.getElementById('movie_player');
      if (!target || !target.contains(b)) target = b.closest('#fullscreen-container'); // B 站直播自己全螢幕用的外框
      if (!target) {
        target = null;
        for (var a = b.parentElement; a && a !== document.body; a = a.parentElement) {
          var v = a.querySelector('video');
          if (v && v.getBoundingClientRect().width > 100) { target = a; break; }
        }
      }
      if (!target) return;
      r = ratio();
      if (!(r > 0 && r < 1)) return;
      swallow(e); lastFsTap = Date.now();
      asked = document.documentElement; // 再按一次全螢幕鍵時由上面那段攔下來離開
      var ok = false;
      try { ok = enter(target); } catch (x) { try { cleanup(); } catch (y) {} }
      if (!ok) { asked = null; try { target.requestFullscreen(); } catch (x) {} } // 排不成就照原本的全螢幕
    }
    document.addEventListener('pointerup', enterTap, true);
    document.addEventListener('click', enterTap, true);
    // 網頁裡的主要影片播放器：YouTube 用 #movie_player；其他網站取最大的影片，往上找大小差不多的外框（含控制列）
    function player(){
      var yt = document.getElementById('movie_player');
      if (yt && yt.getBoundingClientRect().width > 100) return yt;
      var best = null, area = 0;
      document.querySelectorAll('video').forEach(function(v){
        var b = v.getBoundingClientRect(), a = b.width * b.height;
        if (a > area && b.width > 100) { area = a; best = v; }
      });
      if (!best) return null;
      var vb = best.getBoundingClientRect(), el = best;
      for (var p = best.parentElement; p && p !== document.body; p = p.parentElement) {
        var pb = p.getBoundingClientRect();
        if (pb.width > vb.width * 1.05 + 2 || pb.height > vb.height * 1.3 + 2) break;
        el = p;
      }
      return el;
    }
  })();

  // 子框架：和主網頁同一個來源（主網頁拿得到）時，全螢幕改請主網頁把這個框架固定在上屏，
  // 這裡只回報「已經全螢幕」讓播放器換成全螢幕的樣子（填滿框架）。不同來源的框架照原本的全螢幕
  if (window.top !== window && window.parent === window.top) (function(){
    var EXITS = ['exitFullscreen', 'webkitExitFullscreen', 'webkitCancelFullScreen'];
    var cur = null;
    var PROPS = ['fullscreenElement', 'webkitFullscreenElement', 'webkitCurrentFullScreenElement'];
    function fire(el){
      ['fullscreenchange', 'webkitfullscreenchange'].forEach(function(n){
        try { (el && el.isConnected ? el : document).dispatchEvent(new Event(n, {bubbles: true})); } catch (x) {}
      });
      setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 50);
    }
    // 要全螢幕的元素直接填滿整個框架（網頁自己的全螢幕樣式有的靠 :fullscreen，假的全螢幕不會套用）
    var FILL = {position: 'fixed', left: '0', top: '0', right: 'auto', bottom: 'auto', width: '100vw', height: '100vh',
      'max-width': 'none', 'max-height': 'none', 'min-width': '0', 'min-height': '0', margin: '0', transform: 'none', 'z-index': '2147483647'};
    var orig = null;
    function fill(el){
      orig = {};
      for (var k in FILL) { orig[k] = [el.style.getPropertyValue(k), el.style.getPropertyPriority(k)]; el.style.setProperty(k, FILL[k], 'important'); }
    }
    function unfill(el){
      if (!orig) return;
      for (var k in orig) { var o = orig[k]; if (o[0]) el.style.setProperty(k, o[0], o[1]); else el.style.removeProperty(k); }
      orig = null;
    }
    window.__duoFsClear = function(){
      if (!cur) return;
      var el = cur; cur = null;
      unfill(el);
      PROPS.concat(['fullscreen', 'webkitIsFullScreen'], EXITS).forEach(function(p){ delete document[p]; });
      fire(el);
    };
    // 主網頁把這個框架固定在上屏後呼叫：el = 剛才真的全螢幕的元素，這裡假裝它還在全螢幕並填滿框架
    window.__duoFakeFs = function(el){
      if (!el) el = document.documentElement;
      cur = el;
      if (el !== document.documentElement && el !== document.body) fill(el);
      PROPS.forEach(function(p){ Object.defineProperty(document, p, {get: function(){ return cur; }, configurable: true}); });
      ['fullscreen', 'webkitIsFullScreen'].forEach(function(p){ Object.defineProperty(document, p, {get: function(){ return true; }, configurable: true}); });
      EXITS.forEach(function(p){
        try { Object.defineProperty(document, p, {value: function(){ try { window.top.__duoExitFs(); } catch (x) { window.__duoFsClear(); } return Promise.resolve(); }, configurable: true, writable: true}); } catch (x) {}
      });
      fire(el);
    };
  })();

  // 切換分頁後（網頁被暫停過），再按播放影片時畫面會卡住、只有聲音，要捲動一下才會恢復：
  // 網頁被藏起來過的話，影片開始播放時自動「捲動 1 像素再捲回來」，讓畫面重新繪製
  if (window.top === window) (function(){
    // 切回這個分頁時 App 會呼叫 __duoShown()
    var wasHidden = false;
    document.addEventListener('visibilitychange', function(){ if (document.hidden) wasHidden = true; });
    // 影片的畫面層拿掉再放回來：WebView 被藏起來時還在播的影片，解碼出來的畫面一直被佔著沒有還回去，
    // 到上限後畫面就停住（只有聲音）。拿掉畫面層會把佔著的畫面還回去，放回來時換一個新的
    var parked = [];
    function vids(){ return Array.prototype.slice.call(document.querySelectorAll('video')); }
    function hideLayer(v){
      if (v.__duoParked) return;
      v.__duoParked = [v.style.getPropertyValue('display'), v.style.getPropertyPriority('display')];
      v.style.setProperty('display', 'none', 'important');
      parked.push(v);
    }
    function showLayer(v){
      var o = v.__duoParked; if (!o) return;
      v.__duoParked = null;
      if (o[0]) v.style.setProperty('display', o[0], o[1]); else v.style.removeProperty('display');
    }
    function relayer(v){ hideLayer(v); requestAnimationFrame(function(){ requestAnimationFrame(function(){ showLayer(v); parked = parked.filter(function(x){ return x !== v; }); }); }); }
    // App 要把這個分頁藏起來之前呼叫：影片繼續播（聲音照常，和 Chrome 一樣），只是先不畫
    // 回傳 'keep' = 這頁有影片：App 不把 WebView 藏起來（藏起來再顯示時影片畫面會卡住，
    // 時間在走、畫面停住，捲動、暫停再播、重新插入影片都救不回來），只移到畫面外；影片由這裡暫停，切回來再繼續
    window.__duoHide = function(){
      var vs = vids(), keep = vs.some(function(v){ return v.readyState > 0 || v.currentTime > 0; });
      vs.forEach(function(v){
        v.__duoWasPlaying = !v.paused && !v.ended;
        if (keep) { if (v.__duoWasPlaying) { v.__duoSelfPause = true; v.pause(); } parked.push(v); }
        else hideLayer(v);
      });
      return keep ? 'keep' : '';
    };
    // 原地跳一下（設成目前的時間）：解碼器重新送畫面，卡住的畫面就會動
    function kick(v){ try { if (v.readyState > 0 && isFinite(v.currentTime)) v.currentTime = v.currentTime; } catch (x) {} }
    window.__duoShown = function(){
      wasHidden = true;
      var p = parked; parked = [];
      p.forEach(showLayer);
      // 藏起來的分頁 WebView 會自己暫停影片、釋放解碼器；切回來時重建的解碼器有時不再出畫面（時間在走、畫面停住），
      // YouTube 還會把這次暫停當成使用者按了暫停。藏之前在播的影片：沒在播就繼續播，再原地跳一下讓畫面出來
      p.forEach(function(v){
        if (!v.__duoWasPlaying) return;
        v.__duoWasPlaying = false;
        var self = v.__duoSelfPause; v.__duoSelfPause = false;
        setTimeout(function(){
          if (v.paused && !v.ended) v.play().then(function(){ if (!self) setTimeout(function(){ kick(v); }, 300); }).catch(function(){});
          else if (!self) kick(v);
        }, self ? 50 : 300);
      });
      // 沒經過 __duoHide 的（例如舊版本或被系統暫停過）：正在播的影片也換一個新的畫面層
      vids().forEach(function(v){ if (!v.paused && p.indexOf(v) < 0) relayer(v); });
    };
    function nudge(){
      var se = document.scrollingElement || document.documentElement;
      var d = (se && se.scrollTop + innerHeight + 1 >= se.scrollHeight) ? -1 : 1;
      window.scrollBy(0, d);
      requestAnimationFrame(function(){ window.scrollBy(0, -d); });
    }
    document.addEventListener('playing', function(e){
      if (!wasHidden || !e.target || e.target.tagName !== 'VIDEO') return;
      wasHidden = false;
      // 假全螢幕時影片是固定位置，捲動不會讓它重畫：直接換一個新的畫面層
      relayer(e.target);
      nudge();
      setTimeout(nudge, 300); setTimeout(nudge, 1000); // 影片解碼慢一點才開始時再補幾次
    }, true);
  })();

  var hookDI = function(){};
  var lastDown = 0;
  document.addEventListener('pointerdown', function(){ lastDown = Date.now(); }, true);
  document.addEventListener('touchstart', function(){ lastDown = Date.now(); }, true);
  function host(e){ while (e && e.parentElement && e.parentElement.isContentEditable) e = e.parentElement; return e; }
  function editable(e){
    if (!e || e.disabled || e.readOnly) return false;
    if (e.isContentEditable) return true;
    if (e.tagName === 'TEXTAREA') return true;
    if (e.tagName === 'INPUT') {
      var ty = (e.getAttribute('type') || 'text').toLowerCase();
      return ['text','search','email','url','tel','password','number'].indexOf(ty) >= 0;
    }
    return false;
  }
  function report(e){
    if (!editable(e)) return;
    if (e.isContentEditable) e = host(e);
    if (Date.now() - lastDown > 1500) return; // 只回應使用者的點擊，不理會頁面自動 focus
    window.__duoEl = e;
    var type = e.isContentEditable ? 'contenteditable' : (e.tagName === 'TEXTAREA' ? 'textarea' : (e.getAttribute('type') || 'text').toLowerCase());
    var hint = e.getAttribute('placeholder') || e.getAttribute('aria-label') || e.getAttribute('name') || '';
    var multi = e.isContentEditable || (e.tagName === 'TEXTAREA' && !searchy(e));
    window.__duoSearchy = e.tagName === 'TEXTAREA' && !multi;
    // 游標位置（點在文字中間就從中間開始打，不是一律接在最後面）
    var c = caret(e);
    if (window.top !== window) {
      // 子框架（iframe）：經由 DuoInput 通知原生，打的字會直接送回這個框架
      hookDI();
      try { window.DuoInput && DuoInput.postMessage(JSON.stringify({t: 'focus', v: e.isContentEditable ? e.innerText : (e.value || ''), type: type, hint: hint, multi: multi, s: c[0], e: c[1]})); } catch (x) {}
      return;
    }
    try { DuoBridge.onEditFocus(e.isContentEditable ? e.innerText : (e.value || ''), type, hint, multi, c[0], c[1]); } catch (x) {}
  }
  // 輸入框裡的游標／選取範圍（字元位置）；拿不到時回傳 -1
  function caret(e){
    try {
      if (e.isContentEditable) {
        var s = window.getSelection(); if (!s || !s.rangeCount) return [-1, -1];
        var r = s.getRangeAt(0); if (!e.contains(r.startContainer)) return [-1, -1];
        var a = document.createRange(); a.selectNodeContents(e);
        a.setEnd(r.startContainer, r.startOffset); var st = a.toString().length;
        a.setEnd(r.endContainer, r.endOffset); return [st, a.toString().length];
      }
      if (typeof e.selectionStart === 'number') return [e.selectionStart, e.selectionEnd];
    } catch (x) {}
    return [-1, -1];
  }
  // 把游標放回第 s～en 個字元（contenteditable 要走文字節點找位置）
  function setCaret(e, s, en){
    if (!(s >= 0)) return;
    if (!(en >= s)) en = s;
    try {
      if (!e.isContentEditable) { e.setSelectionRange(s, en); return; }
      var w = document.createTreeWalker(e, NodeFilter.SHOW_TEXT), n, pos = 0, r = document.createRange(), a = null, b = null;
      while ((n = w.nextNode())) {
        var L = n.nodeValue.length;
        if (!a && s <= pos + L) a = [n, s - pos];
        if (!b && en <= pos + L) { b = [n, en - pos]; break; }
        pos += L;
      }
      if (!a) return;
      r.setStart(a[0], a[1]); if (b) r.setEnd(b[0], b[1]); else r.collapse(true);
      var sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(r);
    } catch (x) {}
  }
  // 外觀是 textarea、其實是搜尋框（例如 Google 搜尋列）：Enter 要直接送出，不是換行
  function searchy(e){
    var role = (e.getAttribute('role') || '').toLowerCase();
    var ek = (e.getAttribute('enterkeyhint') || '').toLowerCase();
    if (role === 'combobox' || role === 'searchbox') return true;
    if (['search','go','send','done','next'].indexOf(ek) >= 0) return true;
    if (e.getAttribute('aria-autocomplete')) return true;
    var f = e.form || e.closest('form');
    if (f && ((f.getAttribute('role') || '') === 'search' || /search/i.test(f.getAttribute('action') || ''))) return true;
    return false;
  }
  // 網頁元件（shadow DOM，例如 B 站留言框）裡的輸入框：document 收到的 ev.target 會被換成外層元件，
  // 要用 composedPath() 取真正被點到／取得焦點的元素
  function real(ev){
    var p = ev.composedPath ? ev.composedPath() : null;
    var e = (p && p.length) ? p[0] : ev.target;
    if (e && e.nodeType !== 1) e = e.parentElement || (e.host || null);
    return e;
  }
  document.addEventListener('focusin', function(ev){ report(real(ev)); }, true);
  // 網頁輸入框失去焦點（點了網頁別的地方）：通知原生收起看不見的打字框
  document.addEventListener('focusout', function(ev){
    var e = real(ev); if (!e || e !== window.__duoEl) return;
    setTimeout(function(){
      var r = e.getRootNode ? e.getRootNode() : document;
      if (r.activeElement === e || (e.contains && e.contains(r.activeElement))) return;
      try { DuoBridge.onEditBlur(); } catch (x) {}
    }, 200);
  }, true);
  document.addEventListener('click', function(ev){ report(real(ev)); }, true);
  // v = 整個輸入框的新內容；s/en = 打字框的游標位置（網頁上的游標跟著放到同一個地方）
  window.__duoSet = function(v, s, en){
    var e = window.__duoEl; if (!e) return;
    if (e.isContentEditable) {
      try {
        e.focus();
        // 選取整個輸入框內容（在 shadow DOM 裡 selectAll 不可靠，直接用 Range）
        var sel = window.getSelection(), r = document.createRange();
        r.selectNodeContents(e); sel.removeAllRanges(); sel.addRange(r);
        if (document.execCommand('insertText', false, v)) { setCaret(e, s, en); return; }
      } catch (x) {}
      e.innerText = v;
    } else {
      var proto = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) d.set.call(e, v); else e.value = v;   // 用原生 setter，React 等框架才會察覺
    }
    e.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
    setCaret(e, s, en);
  };
  window.__duoSubmit = function(){
    var e = window.__duoEl; if (!e) return;
    e.dispatchEvent(new Event('change', {bubbles: true}));
    var o = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true};
    var ok = e.dispatchEvent(new KeyboardEvent('keydown', o));
    e.dispatchEvent(new KeyboardEvent('keypress', o));
    e.dispatchEvent(new KeyboardEvent('keyup', o));
    if (ok && e.form && (e.tagName === 'INPUT' || window.__duoSearchy)) {
      if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit();
    }
  };
  // 子框架：接收原生送來的文字／送出／收起
  var setVal = window.__duoSet, submitVal = window.__duoSubmit;
  hookDI = function(){
    if (window.top === window || !window.DuoInput || DuoInput.onmessage) return;
    DuoInput.onmessage = function(ev){
      var m; try { m = JSON.parse(ev.data); } catch (x) { return; }
      if (m.t === 'set') setVal(String(m.v), m.s, m.e);
      else if (m.t === 'submit') submitVal();
      else if (m.t === 'blur' && window.__duoEl && window.__duoEl.blur) window.__duoEl.blur();
    };
  };
  hookDI();
})();
"""
    }
}
