package com.duo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlin.math.abs
import kotlin.math.roundToInt
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText

/**
 * 整個 App 的共用狀態（跟任何一個畫面都無關）：
 * 虛擬顯示器、WebView／分頁、介面、手把。
 *
 * 每個實體螢幕各有一個 ScreenActivity，它們只負責回報「我看得見／看不見」，
 * Session 依照目前看得見的螢幕切換成雙屏、只剩上屏、只剩下屏三種模式。
 * 所以上屏手勢回到桌面時，下屏會自動變成一般的單屏瀏覽器，
 * 之後在上屏再打開 App，就會重新擴展成雙屏。
 */
@SuppressLint("StaticFieldLeak")
object Session {
    private const val TAG = "DuoSession"

    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    val isReady: Boolean get() = ::prefs.isInitialized
    lateinit var engine: SpanEngine
        private set
    lateinit var gamepad: GamepadController
        private set
    var browser: Browser? = null
        private set
    var ui: BrowserUi? = null
        private set
    val main = Handler(Looper.getMainLooper())

    /** displayId → 該螢幕上的 ScreenActivity */
    private val screens = LinkedHashMap<Int, ScreenActivity>()
    private val visible = HashSet<Int>()

    var topDisplay = Display.DEFAULT_DISPLAY
        private set
    var bottomDisplay = Display.DEFAULT_DISPLAY
        private set
    /** 只有一個實體螢幕（一般手機）時，在同一個畫面上下分割模擬。 */
    var splitMode = true
        private set
    private var detected = false

    private var pendingUrl: String? = null
    private var savedTabs: Bundle? = null
    private var paused = false
    private var uiInfo: ScreenHost? = null
    private var uiControl: ScreenHost? = null
    private var uiEditor: ScreenHost? = null
    var lastInput: Activity? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        // 測試版（debug）才開放 chrome://inspect 除錯網頁；正式版不開
        if ((app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        prefs = Prefs(app)
        T.cur = Palette.byId(prefs.theme) // 介面主題要在建任何畫面之前決定
        Incognito.cleanupOnStart() // 上次留下的無痕設定檔（含 App 被關掉時還開著的無痕分頁）：資料一律不留
        engine = SpanEngine()
        gamepad = GamepadController()
    }

    // ---------------- 螢幕偵測 ----------------

    fun ensureDetected() {
        if (!detected) detectDisplays()
    }

    fun detectDisplays() {
        detected = true
        val dm = app.getSystemService(DisplayManager::class.java)
        val def = dm.getDisplay(Display.DEFAULT_DISPLAY)
        fun ok(d: Display) = d.displayId != Display.DEFAULT_DISPLAY &&
            (d.flags and Display.FLAG_PRIVATE) == 0 && d.isValid
        val other = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull(::ok)
            ?: dm.displays.firstOrNull(::ok)
        if (def == null || other == null) {
            splitMode = true
            topDisplay = Display.DEFAULT_DISPLAY
            bottomDisplay = Display.DEFAULT_DISPLAY
            return
        }
        splitMode = false
        // 預設：解析度較大的是上螢幕（RP Duo 上 1920×1080、下 1280×960）
        val defIsTop = area(def) >= area(other)
        topDisplay = if (defIsTop) def.displayId else other.displayId
        bottomDisplay = if (defIsTop) other.displayId else def.displayId
    }

    /**
     * 注意：Android 12 之後，用 App 的 context 問「某個螢幕的大小」（getRealMetrics），
     * 回答的常常是 App 目前所在那個螢幕的大小（從下屏啟動時，上屏會被當成 1280×960 → 畫面被拉伸）。
     * 所以這裡用不受影響的實體解析度（Display.Mode）＋旋轉方向來估算，
     * 真正的大小則以各螢幕畫面實際量到的為準（reportSize）。
     */
    private fun estimateSize(id: Int): Pair<Int, Int> {
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(id) ?: return 1920 to 1080
        val mode = d.mode
        var w = mode.physicalWidth
        var h = mode.physicalHeight
        if (d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270) {
            val t = w; w = h; h = t
        }
        return w to h
    }

    private fun estimateDpi(id: Int): Int {
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(id)
            ?: return app.resources.displayMetrics.densityDpi
        return app.createDisplayContext(d).resources.displayMetrics.densityDpi
    }

    private fun area(d: Display): Long = d.mode.physicalWidth.toLong() * d.mode.physicalHeight

    // ---------------- 實際量到的螢幕大小 ----------------

    const val SPLIT_TOP = -100
    const val SPLIT_BOTTOM = -101

    private data class Measured(val w: Int, val h: Int, val dpi: Int)

    /** key = displayId（單螢幕分割模式則是 SPLIT_TOP / SPLIT_BOTTOM） */
    private val measured = HashMap<Int, Measured>()
    private var waitSince = 0L
    private var autoRebuilds = 0

    private fun keyFor(top: Boolean) =
        if (splitMode) (if (top) SPLIT_TOP else SPLIT_BOTTOM) else (if (top) topDisplay else bottomDisplay)

    fun reportSize(key: Int, w: Int, h: Int, dpi: Int) {
        measured[key] = Measured(w, h, dpi)
        if (!engine.started) scheduleLayout() else scheduleGeometryCheck()
    }

    private val geometryCheck = Runnable {
        if (!engine.started || autoRebuilds >= 3) return@Runnable
        fun off(top: Boolean): Boolean {
            val m = measured[keyFor(top)] ?: return false
            val want = m.w.toFloat() / m.h
            val have = if (top) engine.topAspect else engine.bottomAspect
            return abs(want - have) / have > 0.06f
        }
        val dpiOff = measured[keyFor(true)]?.let { it.dpi != engine.dpi } ?: false
        if (off(true) || off(false) || dpiOff) {
            Log.i(TAG, "螢幕比例與虛擬顯示器不符，重建（$measured）")
            autoRebuilds++
            rebuild()
        }
    }

    private fun scheduleGeometryCheck() {
        main.removeCallbacks(geometryCheck)
        main.postDelayed(geometryCheck, 600)
    }

    // ---------------- 啟動 ----------------

    /**
     * 由桌面圖示（MainActivity 跳板）呼叫。
     * - 在上屏點、或第一次啟動：開上屏畫面 → 雙屏（下屏用 Presentation）
     * - App 已在下屏單屏執行時，在下屏點：把下屏帶到前面
     */
    fun launch(from: Activity, url: String?) {
        init(from)
        detectDisplays()
        if (url != null) openUrl(url)
        bottomHidden = false
        val d = from.displayCompat().displayId
        val fresh = screens.isEmpty()
        val bottomAct = screens[bottomDisplay]
        if (!splitMode && !fresh && d == bottomDisplay && bottomAct != null && !bottomAct.isFinishing) {
            moveToFront(bottomAct.taskId)
        } else {
            openScreen(from, topDisplay)
        }
        scheduleLayout()
    }

    private fun openScreen(from: Activity, d: Int) {
        val existing = screens[d]
        if (existing != null && !existing.isFinishing) {
            moveToFront(existing.taskId)
            return
        }
        val i = Intent(from, ScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val o = ActivityOptions.makeBasic().setLaunchDisplayId(d)
        try {
            from.startActivity(i, o.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "無法在螢幕 $d 開啟畫面", e)
        }
    }

    private fun moveToFront(taskId: Int) {
        try {
            app.getSystemService(ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo.taskId == taskId }?.moveToFront()
        } catch (e: Exception) {
            Log.w(TAG, "moveToFront 失敗", e)
        }
    }

    /** 讓上屏成為系統的焦點螢幕（鍵盤只會出現在焦點螢幕上有焦點的視窗）。 */
    fun focusTopScreen() {
        // 注意：不能用 hasWindowFocus() 判斷，上屏畫面在「自己的螢幕」上一直都有焦點，
        // 但系統的焦點螢幕可能還停在下屏；moveToFront 會把焦點螢幕切到上屏。
        val a = screens[topDisplay]?.takeIf { !it.isFinishing } ?: return
        moveToFront(a.taskId)
    }

    fun openUrl(url: String) {
        val b = browser
        if (b != null) b.newTab(url, switch = true) else pendingUrl = url
    }

    // ---------------- 畫面生命週期 ----------------

    fun register(a: ScreenActivity) {
        main.removeCallbacks(shutdownIfEmpty)
        screens[a.displayKey] = a
        main.post { cleanupStaleTasks(); updateRecents() }
    }

    /**
     * App 被系統回收或強制關閉後，舊的畫面 task 會殘留在多工畫面（點了也只是重開）。
     * 把「已經沒有在執行、也不是目前畫面」的 ScreenActivity task 清掉，多工畫面才會只剩一個。
     */
    private fun cleanupStaleTasks() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val live = screens.values.map { it.taskId }.toSet()
        try {
            app.getSystemService(ActivityManager::class.java).appTasks.forEach { t ->
                val info = t.taskInfo
                val base = info.baseActivity?.className ?: info.baseIntent?.component?.className
                if (base == ScreenActivity::class.java.name && info.taskId !in live && !info.isRunning) {
                    t.finishAndRemoveTask()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理舊 task 失敗", e)
        }
    }

    private var closingAll = false

    /** removedByUser = 使用者在多工畫面把 App 滑掉（Activity 被結束，不是被系統回收）。 */
    fun unregister(a: ScreenActivity, removedByUser: Boolean) {
        if (screens[a.displayKey] === a) {
            screens.remove(a.displayKey)
            visible.remove(a.displayKey)
        }
        if (lastInput === a) lastInput = null
        if (removedByUser && !closingAll) {
            // 對使用者來說是同一個 App：滑掉一個就兩個螢幕一起關
            closingAll = true
            screens.values.toList().forEach { it.finishedBySession = true; it.finishAndRemoveTask() }
            dismissBottomPresentation()
            bottomHidden = true
            closingAll = false
        }
        scheduleLayout()
        updateRecents()
        if (screens.isEmpty()) main.postDelayed(shutdownIfEmpty, 1500)
    }

    /**
     * 設定頁的「退出應用」：兩個螢幕一起關掉並結束整個程序（分頁平常就隨時存好；
     * 無痕分頁的資料下次啟動時由 Incognito.cleanupOnStart 清除）。
     */
    fun exitApp() {
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) {}
        closingAll = true
        screens.values.toList().forEach { it.finishedBySession = true; it.finishAndRemoveTask() }
        dismissBottomPresentation()
        closingAll = false
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    // ---------------- 下屏 Presentation（雙屏模式用） ----------------

    private var bottomPres: ScreenPresentation? = null
    /** 使用者在下屏按返回離開（或整個 App 被關掉）：雙屏時不再顯示下屏，直到下次從圖示開啟。 */
    private var bottomHidden = false
    /** 最後一次輸入來自下屏 Presentation（用來決定「返回到底」時要收哪個螢幕）。 */
    var inputFromPresentation = false
    private var topGoneSince = 0L
    private var bottomStartRequestedAt = 0L

    private fun canPresentOnBottom() = !splitMode && bottomDisplay != Display.DEFAULT_DISPLAY

    private fun ensureBottomPresentation(): ScreenPresentation? {
        bottomPres?.let { return it }
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(bottomDisplay) ?: return null
        return try {
            ScreenPresentation(app, d).also { it.show(); bottomPres = it }
        } catch (e: Exception) {
            Log.w(TAG, "下屏 Presentation 建立失敗，改用 Activity", e)
            null
        }
    }

    private fun dismissBottomPresentation() {
        val p = bottomPres ?: return
        bottomPres = null
        p.host.screen.detach()
        try { p.dismiss() } catch (_: Exception) {}
    }

    /** 上屏回到桌面後，下屏要變成獨立的 Activity（單屏瀏覽器），不然系統會把 App 當成背景。 */
    private fun startBottomActivity() {
        val now = SystemClock.uptimeMillis()
        if (now - bottomStartRequestedAt < 3000) return
        bottomStartRequestedAt = now
        val i = Intent(app, ScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val o = ActivityOptions.makeBasic().setLaunchDisplayId(bottomDisplay)
        try {
            app.startActivity(i, o.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "無法在下屏開啟畫面", e)
        }
    }

    private fun isInteractive(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true

    /**
     * 多工畫面只顯示一個 App：保留上屏的 task，把其他螢幕的 task 從最近使用中隱藏。
     * 上屏不存在時（只剩下屏），下屏就會出現在多工畫面裡。
     */
    private fun updateRecents() {
        val am = app.getSystemService(ActivityManager::class.java)
        val tasks = try { am.appTasks } catch (e: Exception) { return }
        val topAlive = screens[topDisplay]?.takeIf { !it.isFinishing } != null
        for ((d, a) in screens) {
            val exclude = !splitMode && topAlive && d != topDisplay
            try {
                tasks.firstOrNull { it.taskInfo.taskId == a.taskId }?.setExcludeFromRecents(exclude)
            } catch (_: Exception) {
            }
        }
    }

    fun setVisible(a: ScreenActivity, v: Boolean) {
        if (screens[a.displayKey] !== a) return
        if (v) visible.add(a.displayKey) else visible.remove(a.displayKey)
        scheduleLayout()
    }

    private val layoutRunnable = Runnable { applyLayout() }

    private fun scheduleLayout() {
        main.removeCallbacks(layoutRunnable)
        main.postDelayed(layoutRunnable, 60)
    }

    private fun applyLayout() {
        val topAct = screens[topDisplay]?.takeIf { topDisplay in visible && !it.isFinishing }
        val botAct = screens[bottomDisplay]?.takeIf { bottomDisplay in visible && !it.isFinishing }
        val th: ScreenHost?
        var bh: ScreenHost? = null
        if (splitMode) {
            th = topAct?.topHost; bh = topAct?.bottomHost
        } else {
            th = topAct?.host
            val now = SystemClock.uptimeMillis()
            if (topAct != null) {
                // 雙屏：下屏用 Presentation；若下屏還留著單屏用的 Activity，就收掉它
                topGoneSince = 0L
                if (bottomHidden) {
                    dismissBottomPresentation()
                } else if (canPresentOnBottom()) {
                    val p = ensureBottomPresentation()
                    if (p != null) {
                        bh = p.host
                        screens[bottomDisplay]?.let { a ->
                            if (!a.isFinishing) { a.finishedBySession = true; a.finishAndRemoveTask() }
                        }
                    }
                }
                if (bh == null && !bottomHidden) bh = botAct?.host
            } else if (bottomPres != null && isInteractive()) {
                // 上屏不見了（回到桌面）：把下屏交給獨立的 Activity，接手後再關 Presentation
                if (botAct != null) {
                    bh = botAct.host
                    dismissBottomPresentation()
                } else {
                    bh = bottomPres?.host
                    if (topGoneSince == 0L) topGoneSince = now
                    if (now - topGoneSince >= 400) startBottomActivity()
                    main.postDelayed(layoutRunnable, 450)
                }
            } else {
                bh = botAct?.host
            }
        }

        screens.values.forEach { a -> a.allHosts().forEach { it.screen.detach() } }
        if (bottomPres?.host !== bh) bottomPres?.host?.screen?.detach()

        if (th == null && bh == null) {
            // 兩個螢幕都看不見：暫停網頁，但保留所有分頁
            if (!paused) browser?.web?.onPause()
            paused = true
            ui?.detach(); ui = null; uiInfo = null; uiControl = null
            return
        }

        if (!engine.started) {
            // 第一次建立前，先等上屏量到真正的大小（最多等 1.5 秒，等不到就用估算值）
            val now = SystemClock.uptimeMillis()
            if (waitSince == 0L) waitSince = now
            if (measured[keyFor(true)] == null && now - waitSince < 1500) {
                main.postDelayed(layoutRunnable, 150)
                return
            }
            startEngine()
        }
        if (paused) {
            browser?.web?.onResume(); paused = false
        }

        engine.setMode(
            when {
                th != null && bh != null -> SpanEngine.Mode.DUAL
                th != null -> SpanEngine.Mode.TOP
                else -> SpanEngine.Mode.BOTTOM
            }
        )
        th?.screen?.attach(engine, engine.topRegion)
        bh?.screen?.attach(engine, engine.bottomRegion)

        // 介面：選單/輸入面板放在下屏（沒有下屏就放上屏），進度條/提示放在上屏（沒有就放下屏）
        val info = th ?: bh!!
        val control = bh ?: th!!
        // 打字用的輸入面板：下屏是 Presentation 時改放上屏。
        // 原因：下屏沒有 App 畫面時，RP Duo 的雙屏助手會在下屏搶走輸入焦點，鍵盤一出現就被收掉。
        val editor = if (th != null && control === bottomPres?.host) th else control
        if (ui == null || uiInfo !== info || uiControl !== control || uiEditor !== editor) {
            ui?.detach()
            val u = BrowserUi(info, control, editor, sideHost = th)
            ui = u; uiInfo = info; uiControl = control; uiEditor = editor
            browser?.let { u.onUrl(it.tab.url.ifBlank { it.web.url }) }
            // 第一次使用：顯示教學（等畫面穩定一下再開）
            if (!prefs.tutorialDone && !tutorialShown) {
                tutorialShown = true
                main.postDelayed({ ui?.showTutorial(0) }, 900)
            }
        }
        invalidateScreens()
    }

    private fun startEngine() {
        val (tw, tHeight) = screenSize(top = true)
        val (bw, bHeight) = screenSize(top = false)
        val dpi = measured[keyFor(true)]?.dpi ?: estimateDpi(topDisplay)
        Log.i(TAG, "建立虛擬顯示器：上 ${tw}x$tHeight、下 ${bw}x$bHeight、dpi $dpi")
        engine.start(tw, tHeight, bw, bHeight, prefs.gapPx, dpi,
            cropScale = if (prefs.bottomCrop) cropScale(tw, tHeight, bw, bHeight) else 0f,
            sideWidth = sideBarWidth(tw, tHeight, bw, bHeight))
        val b = Browser()
        browser = b
        b.start(savedTabs, pendingUrl)
        savedTabs = null
        pendingUrl = null
        UpdateChecker.check() // 一天最多查一次
        AdBlock.init()        // 背景載入廣告過濾清單（第一次會下載，之後每 7 天更新）
    }

    private var tutorialShown = false

    /** 介面語言改變：重建整組介面（網頁、分頁不受影響），再執行 afterUi。 */
    fun refreshUi(afterUi: ((BrowserUi) -> Unit)? = null) {
        ui?.detach(); ui = null; uiInfo = null; uiControl = null; uiEditor = null
        applyLayout()
        if (afterUi != null) ui?.let(afterUi)
    }

    /**
     * 放大裁切的比例（下屏 1 像素 = 上屏幾個像素）。
     * 系統回報的像素密度不準（兩塊都說約 320 DPI），所以 RP Duo 直接用實際規格：
     * 上屏 401 DPI（121.8 mm 寬）、下屏 356 DPI（91.4 mm 寬）。使用者可在設定裡微調。
     */
    fun cropScale(tw: Int, th: Int, bw: Int, bh: Int): Float {
        if (prefs.cropScale > 0f) return prefs.cropScale
        return defaultCropScale(tw, th, bw, bh)
    }

    fun defaultCropScale(tw: Int, th: Int, bw: Int, bh: Int): Float {
        val isRpDuo = maxOf(tw, th) == 1920 && minOf(tw, th) == 1080 && maxOf(bw, bh) == 1280 && minOf(bw, bh) == 960
        return if (isRpDuo) 401f / 356f else 1f
    }

    /** 套用目前的下屏模式/裁切比例設定，不重建畫面（網頁、影片不中斷）。 */
    fun applyBottomMode() {
        if (!engine.started) return
        val (tw, th) = screenSize(top = true)
        val (bw, bh) = screenSize(top = false)
        // 裁切比例改變時工具列寬度也跟著變，要重建介面
        val sw = sideBarWidth(tw, th, bw, bh)
        val sideChanged = sw != engine.requestedSideWidth
        engine.setSideWidth(sw)
        engine.setCropScale(if (prefs.bottomCrop) cropScale(tw, th, bw, bh) else 0f)
        if (sideChanged) refreshUi() else applyLayout() // 重新把新的下屏區域交給畫面
    }

    /**
     * 上屏左側工具列的寬度（虛擬顯示器像素）= 上下螢幕左緣的實際距離差
     * （放大裁切時下屏左邊裁掉的寬度），所以打開工具列後網頁最左邊在下屏不會被裁掉。
     * 兩塊螢幕一樣寬（例如單螢幕手機的模擬模式）時給一個最小寬度。
     */
    fun sideBarWidth(tw: Int, th: Int, bw: Int, bh: Int): Int {
        if (!prefs.sideBar) return 0
        val diff = ((tw - bw * cropScale(tw, th, bw, bh)) / 2f).roundToInt()
        val min = (56 * app.resources.displayMetrics.density).roundToInt()
        return maxOf(diff, min)
    }

    /** 開關上屏工具列（網頁不重新載入），afterUi 同 refreshUi。 */
    fun applySideBar(afterUi: ((BrowserUi) -> Unit)? = null) {
        if (engine.started) {
            val (tw, th) = screenSize(top = true)
            val (bw, bh) = screenSize(top = false)
            engine.setSideWidth(sideBarWidth(tw, th, bw, bh))
        }
        refreshUi(afterUi)
    }

    /** 即時套用上下螢幕間距（不重建，網頁不重新載入）。 */
    fun applyGap(g: Int) {
        if (!engine.started) return
        engine.setGap(g)
        applyLayout()
    }

    /** 目前螢幕的預設裁切比例（設定頁顯示用）。 */
    fun currentDefaultCropScale(): Float {
        val (tw, th) = screenSize(top = true)
        val (bw, bh) = screenSize(top = false)
        return defaultCropScale(tw, th, bw, bh)
    }

    private fun screenSize(top: Boolean): Pair<Int, Int> {
        measured[keyFor(top)]?.let { return it.w to it.h }
        if (!splitMode) return estimateSize(if (top) topDisplay else bottomDisplay)
        // 單螢幕手機：上 16:9、下 4:3 疊在一起（和 ScreenActivity 的分割比例一致）
        val (w, h) = estimateSize(topDisplay)
        val total = 9f / 16f + 3f / 4f
        val topH = (h * (9f / 16f) / total).toInt()
        return if (top) w to topH else w to (h - topH)
    }

    /**
     * 版面設定（間距、上下對調、下屏模式）改變後重建虛擬顯示器，分頁狀態保留。
     * @param afterUi 重建完、新的介面建立後要做的事（例如回到設定頁）
     */
    fun rebuild(afterUi: ((BrowserUi) -> Unit)? = null) {
        val b = Bundle()
        browser?.saveState(b)
        savedTabs = b
        ui?.detach(); ui = null; uiInfo = null; uiControl = null
        browser = null
        engine.release()
        detectDisplays()
        applyLayout()
        updateRecents()
        if (afterUi != null) ui?.let(afterUi)
    }

    // ---------------- 音量 ----------------

    /**
     * 音量鍵只調「媒體音量」，可以一路調到靜音和最大聲。
     *
     * （1.0.1 以前把媒體音量限制在 1～14，避免 RP Duo 雙屏助手在「已到頂/到底還繼續按」時改動下屏音量，
     * 但這樣就無法靜音、也無法調到最大。雙屏時 App 的聲音屬於上屏，下屏音量不會影響它，所以改回完整範圍。）
     */
    fun handleVolumeKey(e: android.view.KeyEvent): Boolean {
        val dir = when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> 1
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        if (e.action == android.view.KeyEvent.ACTION_DOWN) {
            val am = app.getSystemService(android.media.AudioManager::class.java)
            val s = android.media.AudioManager.STREAM_MUSIC
            val min = if (android.os.Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(s) else 0
            val cur = am.getStreamVolume(s)
            val target = (cur + dir).coerceIn(min, am.getStreamMaxVolume(s))
            if (target != cur) am.setStreamVolume(s, target, android.media.AudioManager.FLAG_SHOW_UI)
            else am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    private val shutdownIfEmpty = Runnable {
        if (screens.isNotEmpty()) return@Runnable
        dismissBottomPresentation()
        gamepad.stop()
        ui?.detach(); ui = null; uiInfo = null; uiControl = null
        browser = null
        engine.release()
        paused = false
        waitSince = 0L
        autoRebuilds = 0
    }

    // ---------------- 給其他元件用的工具 ----------------

    fun invalidateScreens() {
        screens.values.forEach { a -> a.allHosts().forEach { it.screen.postInvalidateOnAnimation() } }
        bottomPres?.host?.screen?.postInvalidateOnAnimation()
    }

    /** 要顯示對話框、開其他 App 時用的 Activity：優先用下屏（操作螢幕）。 */
    fun dialogActivity(): Activity? {
        fun usable(a: ScreenActivity?) = a?.takeIf { !it.isFinishing && !it.isDestroyed }
        return usable(screens[bottomDisplay]?.takeIf { bottomDisplay in visible })
            ?: usable(screens[topDisplay]?.takeIf { topDisplay in visible })
            ?: screens.values.firstOrNull { !it.isFinishing && !it.isDestroyed }
    }

    fun startExternal(i: Intent) {
        val a = dialogActivity()
        if (a != null) a.startActivity(i) else app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun onBackAction() {
        val u = ui
        if (u != null && u.panelOpen) { u.closeAll(); return }
        if (engine.exitCustomView()) return
        if (engine.dismissPopup()) return
        if (browser?.exitPseudoFs() == true) return
        val b = browser
        if (b != null && b.back()) return
        // 沒有上一頁：留在 App 裡，只提示一下（要離開請用系統的回桌面手勢）
        ui?.flash(L.t("已經是第一頁了", "已经是第一页了", "No previous page"))
    }

    fun pickFiles(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val a = dialogActivity() ?: return false
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        return try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(params.createIntent(), REQ_FILE)
            true
        } catch (e: Exception) {
            fileCallback = null
            false
        }
    }

    /** 本機檔案是不是 PDF（不是就當文字檔）：看類型，類型不明時看副檔名。 */
    fun isPdfFile(uri: Uri, type: String?): Boolean {
        val t = (type ?: runCatching { app.contentResolver.getType(uri) }.getOrNull())?.lowercase()
        if (t != null && t.contains("pdf")) return true
        if (t != null && (t.startsWith("text/") || t.contains("json") || t.contains("xml"))) return false
        return uri.toString().lowercase().endsWith(".pdf") || uri.toString().lowercase().endsWith("%2epdf")
    }

    /** 開啟本機檔案（content://）：PDF 用 PDF 閱讀器，其他用文字閱讀器。 */
    fun openFileUri(uri: Uri, type: String? = null) {
        if (isPdfFile(uri, type)) openPdfUri(uri) else openText { TextViewer.openUri(uri) }
    }

    /** 選單的「檔案」：用系統的檔案選擇器挑一個 PDF 或文字檔。 */
    fun pickFile() {
        val a = dialogActivity() ?: return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            // octet-stream：有些系統把 .md 這類檔案標成「未知類型」，不列進來就選不到（開啟時會再檢查是不是文字檔）
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/*", "application/json", "application/xml", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, REQ_PDF)
        } catch (e: Exception) {
            ui?.flash(L.t("找不到檔案管理員", "找不到文件管理器", "No file picker available"))
        }
    }

    /** 本機的 PDF（content://）：複製到暫存後開在新分頁。 */
    fun openPdfUri(uri: Uri) = openPdf { PdfViewer.openUri(uri) }

    /** 在背景複製文字檔到暫存，完成後開在新分頁。 */
    fun openText(open: () -> PdfViewer.Source?) {
        val what = L.t("讀取文字檔", "读取文本文件", "Loading text file")
        ui?.showFileProgress(what, 0, -1)
        TextViewer.importAsync(open, { done, total -> ui?.showFileProgress(what, done, total) }) { viewer ->
            if (viewer != null) { ui?.endFileProgress(null); openUrl(viewer); return@importAsync }
            val msg = L.t("無法開啟這個檔案（不是文字檔，或超過 30 MB）", "无法打开这个文件（不是文本文件，或超过 30 MB）",
                "Couldn't open this file (not a text file, or larger than 30 MB)")
            val u = ui
            if (u != null) u.endFileProgress(msg) else android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 在背景複製 PDF 到暫存（大檔案不會卡住畫面），完成後開在新分頁。 */
    fun openPdf(open: () -> PdfViewer.Source?) {
        val what = L.t("讀取 PDF", "读取 PDF", "Loading PDF")
        ui?.showFileProgress(what, 0, -1)
        PdfViewer.importAsync(open, { done, total -> ui?.showFileProgress(what, done, total) }) { viewer ->
            if (viewer != null) { ui?.endFileProgress(null); openUrl(viewer); return@importAsync }
            val msg = L.t("無法開啟這個 PDF", "无法打开这个 PDF", "Couldn't open this PDF")
            val u = ui
            if (u != null) u.endFileProgress(msg) else android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 腳本管理的「從檔案安裝」：選一個 .user.js 檔。 */
    fun pickScriptFile() {
        val a = dialogActivity() ?: return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, REQ_SCRIPT)
        } catch (e: Exception) {
            ui?.flash(L.t("找不到檔案管理員", "找不到文件管理器", "No file picker available"))
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQ_SCRIPT) {
            val uri = data?.data?.takeIf { resultCode == Activity.RESULT_OK } ?: return true
            Thread {
                val code = try { app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
                main.post { ui?.offerScriptInstall(code, null) }
            }.start()
            return true
        }
        if (requestCode == REQ_PDF) {
            data?.data?.takeIf { resultCode == Activity.RESULT_OK }?.let { openFileUri(it) }
            return true
        }
        if (requestCode != REQ_FILE) return false
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
        return true
    }

    /** 網頁的 alert / confirm / prompt：顯示在實體螢幕上（WebView 本身在看不到的虛擬顯示器上）。 */
    fun jsDialog(message: String?, cancelable: Boolean, defaultValue: String?, result: JsResult): Boolean {
        val a = dialogActivity() ?: return false
        val input = defaultValue?.let { EditText(a).apply { setText(it) } }
        val b = AlertDialog.Builder(a)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (result is JsPromptResult && input != null) result.confirm(input.text.toString()) else result.confirm()
            }
            .setOnCancelListener { result.cancel() }
        if (input != null) b.setView(input)
        if (cancelable) b.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
        try {
            b.show()
        } catch (e: Exception) {
            return false
        }
        return true
    }

    private const val REQ_FILE = 42
    private const val REQ_PDF = 43
    private const val REQ_SCRIPT = 44
}

@Suppress("DEPRECATION")
fun Activity.displayCompat(): Display =
    if (android.os.Build.VERSION.SDK_INT >= 30) display!! else windowManager.defaultDisplay
