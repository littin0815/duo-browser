package com.duo.browser

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 跨屏核心：
 *  - 建一個私有 VirtualDisplay，大小 = 上螢幕寬 × (上螢幕高 + 間距 + 下螢幕依比例放大後的高)
 *  - 在上面用 Presentation 放「唯一一個」WebView，所以整個網頁只有一份 DOM、一個捲動位置
 *  - VirtualDisplay 輸出到 ImageReader，每一格包成硬體 Bitmap，兩個實體螢幕各畫自己那一段
 *  - 實體螢幕上的觸控 / 手把游標，換算座標後注入 WebView
 */
class SpanEngine {
    companion object {
        /** 上下螢幕間距最多可調到多少（虛擬像素）。 */
        const val GAP_MAX = 400
    }

    /** DUAL = 網頁跨兩個螢幕；TOP / BOTTOM = 只剩一個螢幕看得見，變成一般單屏瀏覽器。 */
    enum class Mode { DUAL, TOP, BOTTOM }

    var frame: Bitmap? = null
        private set
    var started = false
        private set

    var vw = 0; private set
    var vh = 0; private set
    private var topH = 0
    private var gap = 0
    /** 雙屏時下屏負責的高度（虛擬顯示器座標）。 */
    private var bottomVirtH = 0
    /** 只剩下屏時（單屏），整個網頁寬度縮進下屏所需的高度。 */
    private var bottomFitH = 0
    /** 雙屏時下屏負責的區域：完整顯示 = 整個寬度；放大裁切 = 和上屏同比例、水平置中的一塊。 */
    private val bottomDual = RectF()
    var dpi = 0; private set
    val topAspect: Float get() = vw.toFloat() / topH
    val bottomAspect: Float get() = bottomDual.width() / bottomDual.height()
    var mode = Mode.DUAL
        private set
    /** 目前網頁實際使用的高度（虛擬顯示器座標）。 */
    private var contentH = 0
    val topRegion = RectF()
    val bottomRegion = RectF()

    var cursorX = 0f; private set
    var cursorY = 0f; private set
    var cursorVisible = false

    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var presentation: WebPresentation? = null
    private val held = ArrayDeque<Image>()
    private val main = Handler(Looper.getMainLooper())

    private var touchOwner: ScreenView? = null
    private var synthActive = false
    private var synthDownTime = 0L

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val webRoot: FrameLayout? get() = presentation?.root
    private val fakeWm: FakeWindowManager? get() = presentation?.fakeWm

    /**
     * @param cropScale 0 = 完整顯示；> 0 = 放大裁切，數值為「下屏 1 像素等於上屏幾個像素」
     *                  （= 上屏像素密度 / 下屏像素密度，RP Duo 是 401/356）
     */
    fun start(topW: Int, topHeight: Int, bottomW: Int, bottomH: Int, gapPx: Int, dpi: Int, cropScale: Float = 0f,
              sideWidth: Int = 0) {
        if (started) return
        sideW = sideWidth.coerceIn(0, topW / 3)
        vw = topW
        topH = topHeight
        gap = gapPx.coerceAtLeast(0)
        this.dpi = dpi
        bottomPxW = bottomW
        bottomPxH = bottomH
        bottomFitH = (bottomH.toFloat() * topW / bottomW).roundToInt()
        computeBottom(cropScale)
        // 虛擬顯示器一律配置「完整顯示」需要的高度（兩種下屏模式中較高的那個），
        // 之後切換下屏模式只要改 WebView 高度，不必重建，網頁也不會重新載入
        // 另外預留間距可調的最大值，之後調整間距也不必重建
        vh = topH + maxOf(gap, GAP_MAX) + bottomFitH
        cursorX = vw / 2f
        cursorY = topH / 2f

        val r = ImageReader.newInstance(
            vw, vh, PixelFormat.RGBA_8888, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        r.setOnImageAvailableListener({ onImage(it) }, main)
        reader = r

        val dm = Session.app.getSystemService(DisplayManager::class.java)
        // flags = 0 → 私有顯示器，只有本 App 能放內容，不需要任何權限
        val d = dm.createVirtualDisplay("duo-span", vw, vh, dpi, r.surface, 0)
        vd = d

        // 用 Application context：虛擬顯示器和 WebView 不屬於任何一個螢幕的 Activity，
        // 哪個螢幕回到桌面都不影響網頁
        val p = try {
            WebPresentation(Session.app, d.display).also { it.show() }
        } catch (e: Exception) {
            // 萬一系統不允許用 Application context 建 Presentation，退回用目前的螢幕 Activity
            android.util.Log.w("SpanEngine", "Application context 建立 Presentation 失敗，改用 Activity", e)
            val a = Session.dialogActivity() ?: throw e
            WebPresentation(a, d.display).also { it.show() }
        }
        presentation = p
        started = true
        setMode(Mode.DUAL)
    }

    private var bottomPxW = 0
    private var bottomPxH = 0
    private var cropScale = 0f

    /** 目前可即時調整的間距上限（建立時預留的高度）。 */
    val maxGap: Int get() = vh - topH - bottomFitH

    /** 即時調整上下螢幕間距（不重建，網頁不重新載入）。 */
    fun setGap(g: Int) {
        if (!started) return
        gap = g.coerceIn(0, maxGap)
        computeBottom(cropScale)
        setMode(mode)
    }

    /** 依下屏模式算出雙屏時下屏負責的區域。cropScale = 0 → 完整顯示。 */
    private fun computeBottom(cropScale: Float) {
        this.cropScale = cropScale
        val by = (topH + gap).toFloat()
        val cropW = bottomPxW * cropScale
        if (cropScale > 0f && cropW < vw) {
            // 放大裁切：依兩塊面板的實際像素密度換算，讓下屏內容的「實際大小」和上屏一樣，
            // 取網頁正中間對應下屏實際寬度的一塊，內容和上屏對齊，左右超出的部分裁掉。
            // 有側邊工具列時，網頁從工具列右邊開始，下屏改成從網頁最左邊開始取（左邊不裁切）
            val cropH = bottomPxH * cropScale
            bottomVirtH = cropH.roundToInt()
            val left = if (sideW > 0) sideW.toFloat().coerceAtMost(vw - cropW) else (vw - cropW) / 2f
            bottomDual.set(left, by, left + cropW, by + cropH)
        } else {
            // 完整顯示：有工具列時網頁寬度變窄，下屏只放網頁的部分（高度依比例變矮）
            val w = (vw - sideW).toFloat()
            val h = if (sideW > 0) (bottomPxH.toFloat() * w / bottomPxW).roundToInt() else bottomFitH
            bottomVirtH = h
            bottomDual.set(sideW.toFloat(), by, vw.toFloat(), by + h)
        }
    }

    // ---------------- 上屏左側工具列 ----------------

    /** 要求的工具列寬度（虛擬顯示器像素）；0 = 沒有工具列。 */
    private var sideW = 0
    val requestedSideWidth: Int get() = sideW
    /** 目前實際的工具列寬度：只剩下屏時（看不到上屏）沒有工具列。 */
    val sideWidth: Int get() = if (mode != Mode.BOTTOM) sideW else 0
    val topHeight: Int get() = topH

    /** 開關／調整工具列寬度（網頁不重新載入，只重新排版寬度）。 */
    fun setSideWidth(w: Int) {
        if (!started) { sideW = w.coerceAtLeast(0); return }
        sideW = w.coerceIn(0, vw / 3)
        computeBottom(cropScale)
        setMode(mode)
    }

    /** 游標是否在工具列上。 */
    fun cursorOnSideBar(): Boolean = sideWidth > 0 && cursorX < sideWidth && cursorY < topH

    /** 不重建畫面直接切換下屏模式（網頁、影片繼續播放）。 */
    fun setCropScale(cropScale: Float) {
        if (!started) return
        computeBottom(cropScale)
        setMode(mode)
    }

    /**
     * 切換模式時虛擬顯示器大小不變（變大小會讓 Presentation 被系統關掉），
     * 只改變 WebView 的高度：寬度一樣，所以網頁不會重新載入，只是重新排版高度。
     */
    fun setMode(m: Mode) {
        mode = m
        contentH = when (m) {
            // 網頁開了全螢幕圖片檢視器：網頁高度暫時只到上屏，檢視器就會整個顯示在上屏
            Mode.DUAL -> if (viewerOnTop) topH else topH + gap + bottomVirtH
            Mode.TOP -> topH
            Mode.BOTTOM -> bottomFitH
        }
        when (m) {
            Mode.DUAL -> {
                topRegion.set(0f, 0f, vw.toFloat(), topH.toFloat())
                bottomRegion.set(bottomDual)
            }
            Mode.TOP -> {
                topRegion.set(0f, 0f, vw.toFloat(), topH.toFloat())
                bottomRegion.setEmpty()
            }
            Mode.BOTTOM -> {
                topRegion.setEmpty()
                bottomRegion.set(0f, 0f, vw.toFloat(), bottomFitH.toFloat())
            }
        }
        // 有工具列時網頁往右讓出工具列的寬度（上屏左邊那一塊由工具列蓋住）
        presentation?.webLayer?.layoutParams =
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, contentH, Gravity.TOP).apply { leftMargin = sideWidth }
        resetShift()
        customView?.layoutParams =
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, videoHeight(), Gravity.TOP).apply { leftMargin = sideWidth }
        cursorY = cursorY.coerceIn(0f, contentH - 1f)
        Session.invalidateScreens()
    }

    // ---------------- 固定頁面上推（雙屏時） ----------------
    //
    // 剛好一頁高、不能捲動的網頁（例如 Google 登入），重要內容可能正好被轉軸切成兩半。
    // 在上屏往上滑可把整個網頁往上推（最多把下屏內容整個推到上屏），
    // 往回捲時先把網頁推回原位。網頁內有可捲動的區塊時不會搶它的捲動。

    var shift = 0f
        private set
    private var lastTouchY = 0f
    /** 觸控點/游標底下是否有「還能往下捲」的內層區塊（由 JS 非同步檢查，未知時當作有）。 */
    private var innerScrollable = true
    /** 觸控點/游標底下是否有「還能往左捲」的內層區塊（例如橫向輪播），未知時當作有。 */
    private var innerScrollableLeft = true
    /** 觸控點/游標底下是否有「還能往右捲」的內層區塊，未知時當作有。 */
    private var innerScrollableRight = true
    private var innerCheckAt = 0L

    /** 網頁正在假全螢幕（影片固定在上屏）：不能上推／左右移網頁，不然影片會跟著被推出上屏，看起來像離開了全螢幕。 */
    private val pseudoFs: Boolean get() = Session.browser?.let { b -> b.tabs.isNotEmpty() && b.tab.pseudoFs } == true

    private val maxShift: Float get() = if (mode == Mode.DUAL && !pseudoFs) (bottomVirtH + gap).toFloat() else 0f

    fun resetShift() {
        setShift(0f)
        setHShift(0f)
    }

    private fun setShift(s: Float) {
        val v = s.coerceIn(0f, maxShift)
        if (v == shift) return
        shift = v
        presentation?.webLayer?.translationY = -v
    }

    // ---------------- 左右拖看下屏被裁掉的兩邊（放大裁切、沒有工具列時） ----------------
    //
    // 預設下屏置中裁切（上下內容對齊）。
    // 網頁已經在最左邊時再往右拖，整個網頁往右移，最多移到下屏從網頁最左邊開始（上屏左邊露出黑邊）；
    // 網頁已經在最右邊時再往左拖，整個網頁往左移，最多移到下屏顯示到網頁最右邊（上屏右邊露出黑邊）。
    // 往反方向拖時先移回原位。

    /** 網頁左右移動量：正值 = 往右移（看左邊）、負值 = 往左移（看右邊）。 */
    var hShift = 0f
        private set
    private var lastTouchX = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    /** 這次拖動的方向：0 = 還沒決定、1 = 橫向、2 = 直向。 */
    private var dragAxis = 0

    private val hShiftEnabled: Boolean get() = mode == Mode.DUAL && !viewerOnTop && sideW == 0 && cropScale > 0f && !pseudoFs
    /** 最多往右移多少（= 下屏左邊被裁掉的寬度）。 */
    private val maxHShift: Float get() = if (hShiftEnabled) bottomDual.left.coerceAtLeast(0f) else 0f
    /** 最多往左移多少（= 下屏右邊被裁掉的寬度）。 */
    private val maxHShiftLeft: Float get() = if (hShiftEnabled) (vw - bottomDual.right).coerceAtLeast(0f) else 0f

    private fun setHShift(s: Float) {
        val v = s.coerceIn(-maxHShiftLeft, maxHShift)
        if (v == hShift) return
        hShift = v
        presentation?.webLayer?.translationX = v
    }

    /** 手指橫向移動 d（正值 = 往右拖 = 想看左邊；負值 = 往左拖 = 想看右邊）。 */
    private fun hShiftByDrag(d: Float) {
        if (d == 0f || !hShiftEnabled) return
        val w = currentWeb() ?: return
        if (d > 0f) {
            when {
                hShift < 0f -> setHShift((hShift + d).coerceAtMost(0f)) // 先移回原位
                !w.canScrollHorizontally(-1) && !innerScrollableLeft -> setHShift(hShift + d)
            }
        } else {
            when {
                hShift > 0f -> setHShift((hShift + d).coerceAtLeast(0f))
                !w.canScrollHorizontally(1) && !innerScrollableRight -> setHShift(hShift + d)
            }
        }
    }

    // ---------------- 圖片檢視器放上屏 ----------------

    /** 網頁開著全螢幕圖片檢視器（例如 B 站留言的大圖）：雙屏時網頁暫時只用上屏的高度。 */
    private var viewerOnTop = false

    fun setViewerOnTop(on: Boolean) {
        if (viewerOnTop == on || !started) return
        viewerOnTop = on
        setMode(mode)
    }

    /** 上屏佔網頁高度的比例（給網頁的假全螢幕用）；不是雙屏時 0。 */
    fun topRatio(): Float =
        if (started && mode == Mode.DUAL && !viewerOnTop && contentH > 0) topH.toFloat() / contentH else 0f

    /**
     * 給網頁排版用的雙屏位置（佔網頁高度的比例）："上屏底, 下屏頂, 下屏底"；不是雙屏時空字串。
     * 例如 YouTube Shorts 一則放上屏、下一則剛好從下屏頂端開始。
     */
    fun screenGeom(): String =
        if (started && mode == Mode.DUAL && !viewerOnTop && contentH > 0)
            "${topH.toFloat() / contentH},${(topH + gap).toFloat() / contentH},${(topH + gap + bottomVirtH).toFloat() / contentH}"
        else ""

    private fun currentWeb(): WebView? = Session.browser?.web

    /** 非同步檢查 (x,y)（虛擬顯示器座標）底下有沒有還能往下 / 往左捲的內層區塊。 */
    private fun checkInnerScrollable(x: Float, y: Float) {
        val w = currentWeb() ?: return
        val now = SystemClock.uptimeMillis()
        if (now - innerCheckAt < 200) return
        innerCheckAt = now
        @Suppress("DEPRECATION")
        val scale = w.scale.takeIf { it > 0f } ?: 1f
        val cx = (x - sideWidth - hShift) / scale
        val cy = (y + shift) / scale
        // 回傳位元：1 = 有還能往下捲的區塊、2 = 還能往左捲、4 = 還能往右捲
        w.evaluateJavascript(
            "(function(x,y){var e=document.elementFromPoint(x,y),r=0;" +
                "while(e&&e!==document.body&&e!==document.documentElement){" +
                "var cs=getComputedStyle(e),s=cs.overflowY,t=cs.overflowX;" +
                "if((s==='auto'||s==='scroll')&&e.scrollHeight>e.clientHeight+1&&e.scrollTop+e.clientHeight<e.scrollHeight-1)r|=1;" +
                "if((t==='auto'||t==='scroll')&&e.scrollWidth>e.clientWidth+1){" +
                "if(e.scrollLeft>0)r|=2;if(e.scrollLeft+e.clientWidth<e.scrollWidth-1)r|=4;}" +
                "e=e.parentElement;}return r;})($cx,$cy)"
        ) { r ->
            val bits = r?.trim('"')?.toIntOrNull()
            innerScrollable = bits == null || bits and 1 != 0
            innerScrollableLeft = bits != null && bits and 2 != 0
            innerScrollableRight = bits != null && bits and 4 != 0
        }
    }

    private fun canPushPageUp(screenY: Float): Boolean {
        if (maxShift == 0f || viewerOnTop || screenY >= topH) return false
        val w = currentWeb() ?: return false
        return !w.canScrollVertically(-1) && !w.canScrollVertically(1)
    }

    /** 手指移動 d（虛擬顯示器座標，負值=往上滑=往下捲）。 */
    private fun shiftByDrag(d: Float) {
        if (mode != Mode.DUAL || d == 0f) return
        if (d < 0f) {
            if (!innerScrollable && canPushPageUp(touchStartY)) setShift(shift - d)
        } else if (shift > 0f) {
            setShift(shift - d)
        }
    }

    /** 全螢幕影片：雙屏時只放在上屏，單屏時填滿那個螢幕。 */
    private fun videoHeight() = if (mode == Mode.DUAL) topH else contentH

    // ---------------- 分頁：每個分頁一個 WebView，同時只顯示一個 ----------------

    /** incognito = 無痕分頁：用獨立的 WebView 設定檔（Cookie、快取、登入都和一般分頁分開）。 */
    fun createWebView(incognito: Boolean = false): WebView = presentation!!.newWebView(incognito)

    /** 目前顯示的分頁。 */
    private var shownWeb: WebView? = null

    fun showWebView(w: WebView) {
        val layer = presentation?.webLayer ?: return
        shownWeb = w
        w.translationX = 0f
        w.visibility = View.VISIBLE
        w.bringToFront() // 舊的分頁晚一點才藏，這段時間新的蓋在上面
        w.onResume()
        w.requestFocus()
        w.postInvalidate()
        for (i in 0 until layer.childCount) {
            val c = layer.getChildAt(i) as? WebView ?: continue
            if (c === w || c.visibility == View.GONE || c.translationX != 0f) continue
            // 有影片的網頁：不藏起來（WebView 藏起來再顯示時影片畫面會卡住，只有聲音），
            // 網頁自己先暫停影片，WebView 移到畫面外（不畫，但 WebView 仍當作顯示中）。
            // 沒有影片的網頁：先讓網頁拿掉影片畫面層、畫完一格，再藏起來暫停
            c.evaluateJavascript("window.__duoHide&&window.__duoHide()") { r ->
                c.postDelayed({
                    if (shownWeb === c || c.parent == null) return@postDelayed
                    if (r == "\"keep\"") c.translationX = (layer.width.coerceAtLeast(vw) * 2).toFloat()
                    else { c.visibility = View.GONE; c.onPause() }
                }, 150)
            }
        }
    }

    fun destroyWebView(w: WebView) {
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
    }

    private fun onImage(r: ImageReader) {
        val img = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        // 畫面暫停中（全螢幕切換的過程）：丟掉新畫面，螢幕停在切換前的那一張
        if (SystemClock.uptimeMillis() < frozenUntil) {
            img.close(); return
        }
        val hb = img.hardwareBuffer
        if (hb == null) {
            img.close(); return
        }
        val bmp = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
        hb.close()
        if (bmp == null) {
            img.close(); return
        }
        // 保留最近兩張，避免正在顯示的緩衝區被生產者覆寫
        held.addLast(img)
        frame = bmp
        while (held.size > 2) held.removeFirst().close()
        Session.invalidateScreens()
    }

    // ---------------- 切換時暫停畫面 ----------------
    //
    // 網頁全螢幕改成「影片放上屏」的過程中，WebView 會先進真的全螢幕、再退回一般網頁、再排成假全螢幕，
    // 中間幾個畫面一閃一閃的。切換期間螢幕停在原本的畫面，排好之後才一次換過去。

    private var frozenUntil = 0L

    /** 暫停更新畫面最多 ms 毫秒（時間到自動恢復，避免卡住）。 */
    fun freeze(ms: Long) {
        frozenUntil = SystemClock.uptimeMillis() + ms
        main.postDelayed({ if (SystemClock.uptimeMillis() >= frozenUntil) Session.invalidateScreens() }, ms + 50)
    }

    /** 恢復更新畫面；delayMs 之後才恢復（讓網頁先畫好新的版面）。 */
    fun unfreeze(delayMs: Long = 0) {
        if (frozenUntil == 0L) return
        if (delayMs <= 0) { frozenUntil = 0L; Session.invalidateScreens(); return }
        frozenUntil = minOf(frozenUntil, SystemClock.uptimeMillis() + delayMs)
    }

    fun release() {
        presentation?.webLayer?.let { layer ->
            val all = (0 until layer.childCount).mapNotNull { layer.getChildAt(it) as? WebView }
            all.forEach { destroyWebView(it) }
        }
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        vd?.release(); vd = null
        held.forEach { it.close() }; held.clear()
        frame = null
        reader?.close(); reader = null
        started = false
    }

    // ---------------- 長按連結 ----------------
    //
    // WebView 在虛擬顯示器上，長按連結時不會呼叫長按處理、也不會跳出系統選單。
    // 這裡自己計時：按住不動 0.55 秒，問網頁那裡是不是連結／圖片；是的話取消這次觸控、開 App 自己的選單。

    private val lpHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lpRunnable: Runnable? = null
    private var lpStartX = 0f
    private var lpStartY = 0f
    private var lpDown = false
    private var lpSwallow = false

    private fun cancelLongPress() {
        lpRunnable?.let { lpHandler.removeCallbacks(it) }
        lpRunnable = null
    }

    private fun trackLongPress(root: View, e: MotionEvent, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPress()
                lpDown = true
                lpStartX = e.x; lpStartY = e.y
                val x = e.x; val y = e.y
                val r = Runnable {
                    lpRunnable = null
                    if (!lpDown) return@Runnable
                    Session.browser?.linkAt(x, y) { isLink ->
                        if (!isLink || !lpDown) return@linkAt
                        // 是連結：送「取消」給網頁，這次觸控不會變成點擊或選取文字
                        lpSwallow = true
                        val now = android.os.SystemClock.uptimeMillis()
                        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0)
                        root.dispatchTouchEvent(c)
                        c.recycle()
                    }
                }
                lpRunnable = r
                lpHandler.postDelayed(r, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = 24f
                if (abs(e.x - lpStartX) > slop || abs(e.y - lpStartY) > slop) cancelLongPress()
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelLongPress()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { lpDown = false; cancelLongPress() }
        }
    }

    // ---------------- 觸控 ----------------

    fun onScreenTouch(v: ScreenView, ev: MotionEvent) {
        val root = webRoot ?: return
        if (synthActive || v.width == 0 || v.region.isEmpty) return
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (touchOwner != null && touchOwner !== v) return
            touchOwner = v
            if (cursorVisible) {
                cursorVisible = false
                Session.invalidateScreens()
            }
        }
        if (touchOwner !== v) return
        val m = Matrix()
        m.setScale(v.region.width() / v.width, v.region.height() / v.height)
        m.postTranslate(v.region.left, v.region.top)
        val e2 = MotionEvent.obtain(ev)
        e2.transform(m)
        // 長按連結時已經開了選單：這次觸控剩下的部分不送給網頁（不然放開時會變成點擊）
        if (lpSwallow) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { lpSwallow = false; touchOwner = null }
            e2.recycle()
            return
        }
        trackLongPress(root, e2, action)
        if (action == MotionEvent.ACTION_DOWN) {
            fakeWm?.dismissIfOutside(e2.x, e2.y)
            lastTouchY = e2.y
            lastTouchX = e2.x
            touchStartX = e2.x
            touchStartY = e2.y
            dragAxis = 0
            innerScrollable = true
            // 橫向先當作「沒有」內層捲動區塊：網頁還在載入時 JS 常常來不及回答，
            // 當作有的話左右拖就要等網頁載入完才有反應。查到有（例如橫向輪播）再讓給它
            innerScrollableLeft = false
            innerScrollableRight = false
            innerCheckAt = 0L
            checkInnerScrollable(e2.x, e2.y)
        } else if (action == MotionEvent.ACTION_MOVE && e2.pointerCount == 1) {
            // 先調整上推量再送出事件：網頁跟著手指移動，WebView 本身看到的座標不變
            shiftByDrag(e2.y - lastTouchY)
            // 橫向移動：先判斷這次拖動主要是橫的還是直的，直向拖動時不左右移網頁
            if (dragAxis == 0) {
                val ax = abs(e2.x - touchStartX)
                val ay = abs(e2.y - touchStartY)
                if (ax + ay > 24f) {
                    dragAxis = if (ax > ay * 1.5f) 1 else 2
                    // 剛判定為橫向：把判定前累積的移動量一起算進去，網頁才會完全跟著手指
                    if (dragAxis == 1) hShiftByDrag(e2.x - touchStartX)
                }
            } else if (dragAxis == 1) hShiftByDrag(e2.x - lastTouchX)
            lastTouchY = e2.y
            lastTouchX = e2.x
        }
        root.dispatchTouchEvent(e2)
        e2.recycle()
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchOwner = null
    }

    // ---------------- 手把游標 ----------------

    fun moveCursor(dx: Float, dy: Float) {
        var ny = cursorY + dy
        val gTop = topH.toFloat()
        val gBot = (topH + gap).toFloat()
        // 游標穿過兩螢幕之間的隱藏帶時直接跳過去
        if (mode == Mode.DUAL && gap > 0 && ny >= gTop && ny < gBot) ny = if (dy > 0) gBot else gTop - 1f
        cursorX = (cursorX + dx).coerceIn(0f, vw - 1f)
        cursorY = ny.coerceIn(0f, contentH - 1f)
        // 放大裁切時，下屏左右被裁掉的部分看不到，游標不要跑進去
        if (mode == Mode.DUAL && cursorY >= gBot && bottomDual.width() < vw) {
            cursorX = cursorX.coerceIn(bottomDual.left, bottomDual.right - 1f)
        }
        cursorVisible = true
        // 工具列蓋在游標上面：改成讓游標底下的按鈕變亮
        Session.ui?.sideBarHover(if (cursorOnSideBar()) cursorY else -1f)
    }

    fun centerCursor() {
        cursorX = vw / 2f
        cursorY = (if (mode == Mode.DUAL) topH else contentH) / 2f
        cursorVisible = true
        Session.invalidateScreens()
    }

    fun padDown() {
        if (touchOwner != null || webRoot == null) return
        if (cursorOnSideBar()) {
            Session.ui?.sideBarClick(cursorY)
            return
        }
        cursorVisible = true
        Session.invalidateScreens()
        synthActive = true
        synthDownTime = SystemClock.uptimeMillis()
        fakeWm?.dismissIfOutside(cursorX, cursorY)
        injectTouch(MotionEvent.ACTION_DOWN)
    }

    fun padMove() {
        if (synthActive) injectTouch(MotionEvent.ACTION_MOVE)
    }

    fun padUp() {
        if (!synthActive) return
        injectTouch(MotionEvent.ACTION_UP)
        synthActive = false
    }

    private fun injectTouch(action: Int) {
        val root = webRoot ?: return
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(synthDownTime, now, action, cursorX, cursorY, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(e)
        e.recycle()
    }

    /** 用滑鼠滾輪事件捲動，這樣游標底下的內層捲動區塊也能捲。 */
    fun wheel(h: Float, v: Float) {
        val root = webRoot ?: return
        // 橫向：網頁已在最左邊時繼續往左捲 = 網頁往右移，看下屏被裁掉的左邊；
        // 已在最右邊時繼續往右捲 = 網頁往左移，看右邊。往反方向捲先移回原位
        if (hShiftEnabled && h != 0f && abs(h) >= abs(v)) {
            val step = abs(h) * 64f * dpi / 160f
            if (h > 0f && hShift > 0f) {
                setHShift((hShift - step).coerceAtLeast(0f))
                return
            }
            if (h < 0f && hShift < 0f) {
                setHShift((hShift + step).coerceAtMost(0f))
                return
            }
            val w = currentWeb()
            if (w != null && !w.canScrollHorizontally(if (h < 0f) -1 else 1)) {
                checkInnerScrollable(cursorX, cursorY)
                val inner = if (h < 0f) innerScrollableLeft else innerScrollableRight
                if (!inner) {
                    setHShift(if (h < 0f) hShift + step else hShift - step)
                    return
                }
            }
        }
        if (mode == Mode.DUAL && v != 0f) {
            val step = abs(v) * 64f * dpi / 160f
            if (v > 0f && shift > 0f) {
                // 往上捲：先把上推的網頁推回原位
                setShift(shift - step)
                return
            }
            if (v < 0f && canPushPageUp(cursorY)) {
                checkInnerScrollable(cursorX, cursorY)
                if (!innerScrollable) {
                    setShift(shift + step)
                    return
                }
            }
        }
        val now = SystemClock.uptimeMillis()
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val pc = MotionEvent.PointerCoords().apply {
            x = cursorX; y = cursorY
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
        }
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(pp), arrayOf(pc),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        root.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** 讓網頁的 :hover 效果跟著游標。 */
    fun hover() {
        val root = webRoot ?: return
        val now = SystemClock.uptimeMillis()
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val pc = MotionEvent.PointerCoords().apply { x = cursorX; y = cursorY }
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(pp), arrayOf(pc),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        root.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** 關閉網頁開出來的下拉選單等彈出視窗。 */
    fun dismissPopup(): Boolean = fakeWm?.sendBack() == true

    // ---------------- 全螢幕影片：只放在上螢幕 ----------------

    fun showCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
        val root = webRoot ?: run { cb.onCustomViewHidden(); return }
        hideCustomView()
        val box = FrameLayout(root.context).apply { setBackgroundColor(Color.BLACK) }
        box.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, videoHeight(), Gravity.TOP))
        customView = box
        customCallback = cb
    }

    fun hideCustomView() {
        customView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        customView = null
        customCallback = null
    }

    fun exitCustomView(): Boolean {
        val cb = customCallback ?: return false
        cb.onCustomViewHidden()
        hideCustomView()
        return true
    }
}

/** 住在虛擬顯示器上的 Presentation，裡面只有 WebView（和網頁彈出的小視窗）。 */
class WebPresentation(outer: Context, display: Display) :
    Presentation(outer, display, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    lateinit var root: FrameLayout
        private set
    /** 所有分頁的 WebView 都放這裡；彈出選單、全螢幕影片則加在 root 上，蓋在它上面。 */
    lateinit var webLayer: FrameLayout
        private set
    lateinit var fakeWm: FakeWindowManager
        private set
    private lateinit var webCtx: WebContext

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // 網頁往右移／往上推、或檢視器只用上屏時，露出來的地方是黑邊
            it.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        root = FrameLayout(context)
        webLayer = FrameLayout(context)
        root.addView(webLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val realWm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fakeWm = FakeWindowManager(realWm, root)
        webCtx = WebContext(context, fakeWm.proxy)
        setContentView(root)
        setCancelable(false)
        root.post { root.dispatchWindowFocusChanged(true) }
    }

    fun newWebView(incognito: Boolean = false): WebView {
        val w = DuoWebView(webCtx)
        // 設定檔要在 WebView 做任何其他事之前指定
        if (incognito) Incognito.attach(w)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.visibility = View.GONE
        webLayer.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return w
    }

    // 虛擬顯示器上的視窗永遠拿不到系統焦點；騙 WebView 說有焦點，游標/焦點樣式才會正常
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) root.post { root.dispatchWindowFocusChanged(true) }
    }
}

/**
 * 選取文字時，WebView 會在自己的視窗（虛擬顯示器上）開系統的「複製 / 分享 / 全部選取」浮動選單。
 * 那是另一個系統視窗，轉送過去的觸控碰不到它（看得到、按不到）。
 * 所以改成自己接手：選單項目交給 [SelectionActionMode]，在實體螢幕上用 App 自己的按鈕列顯示。
 */
class DuoWebView(ctx: Context) : WebView(ctx) {
    override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? =
        callback?.let { SelectionActionMode.start(this, it) }

    override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? =
        callback?.let { SelectionActionMode.start(this, it) }
}

/** 不顯示系統浮動選單的 ActionMode：選單項目改由 BrowserUi 的選取工具列顯示。 */
class SelectionActionMode private constructor(
    private val view: View,
    private val cb: android.view.ActionMode.Callback,
) : android.view.ActionMode() {
    private val menu: android.view.Menu = android.widget.PopupMenu(view.context, view).menu
    private var finished = false

    companion object {
        /** 目前顯示中的選取選單（同時只有一個）。 */
        var current: SelectionActionMode? = null
            private set

        fun start(view: View, cb: android.view.ActionMode.Callback): android.view.ActionMode? {
            current?.finish()
            val m = SelectionActionMode(view, cb)
            m.type = TYPE_FLOATING
            if (!cb.onCreateActionMode(m, m.menu)) return null
            cb.onPrepareActionMode(m, m.menu)
            current = m
            Session.ui?.showSelectionBar(m)
            return m
        }
    }

    /** 要顯示的項目（有標題、看得見、可用）。 */
    fun items(): List<android.view.MenuItem> =
        (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible && it.isEnabled && !it.title.isNullOrBlank() }

    fun click(item: android.view.MenuItem) {
        if (!finished) cb.onActionItemClicked(this, item)
    }

    override fun finish() {
        if (finished) return
        finished = true
        if (current === this) current = null
        cb.onDestroyActionMode(this)
        Session.ui?.hideSelectionBar(this)
    }

    override fun invalidate() {
        if (finished) return
        cb.onPrepareActionMode(this, menu)
        if (current === this) Session.ui?.showSelectionBar(this)
    }

    /** 選取範圍移動了（拖曳選取的把手）：工具列跟著移過去。 */
    override fun invalidateContentRect() {
        if (!finished && current === this) Session.ui?.positionSelectionBar()
    }

    /** 選取的文字在虛擬顯示器上的範圍（工具列放在它旁邊）；拿不到時回傳 null。 */
    fun contentRect(): android.graphics.Rect? {
        val c2 = cb as? android.view.ActionMode.Callback2 ?: return null
        val r = android.graphics.Rect()
        return try {
            c2.onGetContentRect(this, view, r)
            if (r.isEmpty) return null
            // WebView 在虛擬顯示器視窗裡的位置（包含上推、左右移、側邊工具列的位移）
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            r.offset(loc[0], loc[1])
            r
        } catch (e: Exception) { null }
    }

    override fun setTitle(title: CharSequence?) {}
    override fun setTitle(resId: Int) {}
    override fun setSubtitle(subtitle: CharSequence?) {}
    override fun setSubtitle(resId: Int) {}
    override fun setCustomView(view: View?) {}
    override fun getMenu(): android.view.Menu = menu
    override fun getTitle(): CharSequence? = null
    override fun getSubtitle(): CharSequence? = null
    override fun getCustomView(): View? = null
    override fun getMenuInflater(): android.view.MenuInflater = android.view.MenuInflater(view.context)
}

/**
 * WebView 用的 Context：
 *  - PopupWindow（下拉選單、自動完成）拿到假的 WindowManager → 變成 root 裡的子 View，才點得到
 *  - Dialog 會把 WindowManager 強制轉型成 WindowManagerImpl，所以給它真的（目前操作螢幕 Activity 的）
 *    → 對話框出現在實體螢幕
 *  - 刻意不讓 Chromium 找到任何 Activity：否則 WebView 會跟著那個 Activity 的生命週期暫停，
 *    其中一個螢幕回到桌面時網頁就會凍結。JS alert/confirm/prompt 改由 Session.jsDialog 顯示。
 */
class WebContext(base: Context, private val wm: WindowManager) : ContextWrapper(base) {
    override fun getSystemService(name: String): Any? {
        if (name == Context.WINDOW_SERVICE) {
            if (calledFromDialog()) {
                Session.dialogActivity()?.let { return it.getSystemService(name) }
                return super.getSystemService(name)
            }
            return wm
        }
        return super.getSystemService(name)
    }

    private fun calledFromDialog(): Boolean =
        Throwable().stackTrace.any { it.className == "android.app.Dialog" && it.methodName == "<init>" }
}

/** 把「新增視窗」改成「加到 root 裡當子 View」的 WindowManager 代理。 */
class FakeWindowManager(private val real: WindowManager, private val root: FrameLayout) : InvocationHandler {
    val proxy: WindowManager = Proxy.newProxyInstance(
        WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java), this
    ) as WindowManager

    private val views = ArrayList<View>()

    override fun invoke(p: Any, m: Method, args: Array<out Any?>?): Any? {
        when (m.name) {
            "addView" -> {
                val v = args!![0] as View
                val lp = args[1] as ViewGroup.LayoutParams
                (v.parent as? ViewGroup)?.removeView(v)
                root.addView(v, toFrame(lp))
                views.add(v)
                return null
            }
            "updateViewLayout" -> {
                val v = args!![0] as View
                if (v.parent === root) v.layoutParams = toFrame(args[1] as ViewGroup.LayoutParams)
                return null
            }
            "removeView", "removeViewImmediate" -> {
                val v = args!![0] as View
                views.remove(v)
                if (v.parent === root) root.removeView(v)
                return null
            }
            "equals" -> return p === args?.getOrNull(0)
            "hashCode" -> return System.identityHashCode(p)
            "toString" -> return "FakeWindowManager"
        }
        return try {
            if (args == null) m.invoke(real) else m.invoke(real, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun toFrame(lp: ViewGroup.LayoutParams): FrameLayout.LayoutParams {
        val f = FrameLayout.LayoutParams(lp.width, lp.height)
        if (lp is WindowManager.LayoutParams) {
            f.gravity = if (lp.gravity == 0) Gravity.TOP or Gravity.START else lp.gravity
            f.leftMargin = lp.x
            f.topMargin = lp.y
        }
        return f
    }

    /**
     * 點到彈出視窗外面就關掉它（真正的視窗系統會送 ACTION_OUTSIDE，這裡要自己做）。
     * 選取文字時同時有好幾個彈出視窗（「複製」選單和兩端的拉桿），
     * 點到其中任何一個都不算外面，否則按「複製」時選取會先被取消。
     */
    fun dismissIfOutside(x: Float, y: Float) {
        if (views.isEmpty()) return
        val inside = views.any { v -> v.isShown && x >= v.left && x < v.right && y >= v.top && y < v.bottom }
        if (!inside) sendBack()
    }

    fun sendBack(): Boolean {
        val v = views.lastOrNull() ?: return false
        v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        return true
    }
}
