package com.duo.browser

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 瀏覽器介面（全部用程式建，不用 XML）。V2：紫透色機殼風格，見 docs/design-v2.md 與 Theme.kt。
 *  - 上螢幕：細進度條、提示訊息、（可選）左側直式工具列
 *  - 下螢幕：右下角選單鈕、選單面板（網址列＋頁籤：分頁／書籤／歷史／設定，底部按鍵提示）
 *  - 輸入面板（雙屏時在上屏，鍵盤由 RP Duo 雙屏助手固定在下屏）
 * 所有文字都用 L.t(繁體, 简体, English) 三種語言。
 */
class BrowserUi(
    private val top: ScreenHost,
    private val bottom: ScreenHost,
    /** 打字用的輸入面板放在哪個螢幕（雙屏時放上屏，見 Session.applyLayout 的說明）。 */
    private val editorHost: ScreenHost = bottom,
    /** 上屏（看得到時）：放左側直式工具列。 */
    private val sideHost: ScreenHost? = null,
) {
    private val ctx: Context = bottom.context
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()
    private fun dp(v: Int) = dp(v.toFloat())

    // 輸入面板用自己螢幕的 context（不同螢幕的 dpi 不同）
    private val ectx: Context = editorHost.context
    private val editorOnTop = editorHost !== bottom
    private fun edp(v: Int) = (v * ectx.resources.displayMetrics.density).roundToInt()

    private val appName get() = L.t("雙屏瀏覽器", "双屏浏览器", "Duo Browser")

    private val progress = ProgressBar(top.context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
        progressTintList = android.content.res.ColorStateList.valueOf(T.ACCENT)
        indeterminateTintList = android.content.res.ColorStateList.valueOf(T.ACCENT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66000000)
    }
    private val toast = TextView(top.context).apply {
        setTextColor(T.TEXT)
        textSize = 15f
        val d = context.resources.displayMetrics.density
        val pad = (16 * d).roundToInt()
        setPadding(pad, (10 * d).roundToInt(), pad, (10 * d).roundToInt())
        background = ShellDrawable(d, T.solid(0xF0), 999f, T.EDGE_STRONG)
        visibility = View.GONE
    }
    private val hideToast = Runnable {
        toast.animate().alpha(0f).translationY(8f * density).setDuration(T.DUR_OUT).setInterpolator(T.EASE_IN)
            .withEndAction { toast.visibility = View.GONE }.start()
    }

    private val menuBtn = ImageView(ctx).apply {
        setImageDrawable(Icon("menu", T.TEXT, dp(24)))
        scaleType = ImageView.ScaleType.CENTER
        background = ShellDrawable(density, T.solid(0xE0), 999f, T.EDGE_STRONG)
        alpha = 0.85f
        contentDescription = L.t("選單", "菜单", "Menu")
        setOnClickListener { openMenu(fromPad = false, focusUrl = false) }
        Motion.polish(this, focusGrow = false)
    }

    // ---- 選單面板 ----
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ShellDrawable(density, T.BG_GLASS, 0f, grid = !T.incognito, sheen = true)
        setPadding(dp(14), dp(12), dp(14), dp(6))
        isClickable = true // 擋住面板後面的網頁
        visibility = View.GONE
    }
    /** 網址列：只顯示網址，點了才開輸入面板（輸入面板可能在另一個螢幕上）。 */
    private val urlEdit = TextView(ctx).apply {
        setTextColor(T.TEXT)
        setHintTextColor(T.TEXT3)
        textSize = 16f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(16), 0)
        compoundDrawablePadding = dp(10)
        setCompoundDrawablesRelative(Icon("search", T.TEXT2, dp(20)), null, null, null)
        hint = L.t("輸入網址或搜尋關鍵字", "输入网址或搜索关键字", "Enter URL or search")
        isFocusable = true
        background = pillBg(999f)
        setOnClickListener { promptUrl() }
        Motion.polish(this)
    }
    private val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val listScroll = object : ScrollView(ctx) {
        // 手把移動焦點時多捲一點：選到的卡片不會貼在頁籤列底下、被上下的淡出邊蓋掉一半
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
            val r = android.graphics.Rect(rect).apply { inset(0, -dp(32)) }
            return super.computeScrollDeltaToGetChildRectOnScreen(r)
        }
    }.apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isSmoothScrollingEnabled = true }
    private var currentUrl: String = ""
    private val hintBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    // ---- 輸入面板（網頁輸入框代理 / 網址列 / 設定欄位共用） ----
    private val editPanel = LinearLayout(ectx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(edp(16), edp(14), edp(16), edp(14))
        isClickable = true
        visibility = View.GONE
    }
    private val editHint = TextView(ectx).apply { setTextColor(T.TEXT2); textSize = 14f; letterSpacing = 0.02f }
    private val editField = editText(ectx)
    private val editButtons = LinearLayout(ectx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var suppressWatcher = false
    private var editorOpenedAt = 0L
    /** 非 null = 一般輸入（網址、首頁…），按送出時呼叫；null = 正在幫網頁上的輸入框打字。 */
    private var promptSubmit: ((String) -> Unit)? = null

    private enum class ListMode { HOME, BOOKMARKS, SETTINGS, SCRIPTS, HISTORY }
    private var listMode = ListMode.HOME
    private var desktopModeToggle: ToggleView? = null

    private var scrollToMode = false

    // ---- 頁籤（分頁／書籤／歷史／設定），選到的那個底下有一塊會滑動的亮色塊 ----
    private val segFrame = FrameLayout(ctx)
    private val segIndicator = View(ctx).apply { background = ShellDrawable(density, T.ACCENT, 999f) }
    private val segRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val segViews = mutableListOf<View>()
    private val segLabels = mutableListOf<TextView>()
    private val segIcons = mutableListOf<Icon>()
    private var segIndex = 0
    private var segAnim: ValueAnimator? = null

    // ---- 上下螢幕間距調整列（下屏底部，邊調邊看網頁） ----
    private val gapBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ShellDrawable(density, T.BG_GLASS, 22f, T.EDGE_STRONG, topOnly = true, sheen = true)
        setPadding(dp(16), dp(14), dp(16), dp(10))
        isClickable = true
        visibility = View.GONE
    }
    private val gapTitle = TextView(ctx).apply { setTextColor(T.TEXT2); textSize = 13f }

    val panelOpen: Boolean get() = panel.visibility == View.VISIBLE || editPanel.visibility == View.VISIBLE ||
        gapBar.visibility == View.VISIBLE || linkSheet != null

    // ---- 選取文字時的工具列（複製 / 分享 / 全部選取…，下屏底部） ----
    private val selectionBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ShellDrawable(density, T.solid(0xF5), 999f, T.EDGE_STRONG)
        setPadding(dp(4), dp(3), dp(4), dp(3))
        elevation = 8f * density
        isClickable = true
        visibility = View.GONE
    }
    private var selectionMode: SelectionActionMode? = null

    /** WebView 選取了文字：把系統選單的項目做成按鈕（系統的浮動選單在虛擬顯示器上，按不到）。 */
    fun showSelectionBar(mode: SelectionActionMode) {
        val wasShown = selectionBar.visibility == View.VISIBLE
        selectionMode = mode
        selectionBar.removeAllViews()
        fun add(label: CharSequence, onClick: () -> Unit) {
            selectionBar.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(T.TEXT)
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 999f))
                    addState(intArrayOf(), ShellDrawable(density, Color.TRANSPARENT, 999f))
                }
                setOnClickListener { onClick() }
                Motion.polish(this, focusGrow = false)
            })
        }
        mode.items().forEach { item -> add(item.title ?: "") { mode.click(item) } }
        selectionBar.addView(iconBtn("close", L.t("關閉", "关闭", "Close"), sizeDp = 32, iconDp = 16, quiet = true) { mode.finish() })
        selectionBar.visibility = View.VISIBLE
        if (!wasShown) selectionBar.alpha = 0f
        // 量好大小後再放到選取的文字旁邊
        selectionBar.post {
            positionSelectionBar(animate = wasShown)
            if (!wasShown) selectionBar.animate().alpha(1f).setDuration(T.DUR).setInterpolator(T.EASE).start()
        }
    }

    /**
     * 選取工具列放在選取的文字上方（太靠上放不下就放下方，避開選取把手）；
     * 選取的文字在上屏時，放在下屏最上面（最靠近那段文字的地方）。
     */
    fun positionSelectionBar(animate: Boolean = true) {
        val m = selectionMode ?: return
        if (selectionBar.visibility != View.VISIBLE) return
        val host = selectionBar.parent as? View ?: return
        val W = host.width.toFloat(); val H = host.height.toFloat()
        val bw = selectionBar.width.toFloat(); val bh = selectionBar.height.toFloat()
        if (W <= 0f || bw <= 0f) return
        val reg = Session.engine.bottomRegion
        val r = m.contentRect()
        val margin = dp(12).toFloat()
        var x = (W - bw) / 2f
        var y = H - bh - margin - dp(56) // 拿不到位置時：下屏底部（右下角留給選單鈕）
        if (r != null && reg.width() > 0f && reg.height() > 0f) {
            val sx = W / reg.width(); val sy = H / reg.height()
            val cx = (r.exactCenterX() - reg.left) * sx
            val top = (r.top - reg.top) * sy
            val bot = (r.bottom - reg.top) * sy
            x = cx - bw / 2f
            y = when {
                bot < 0f -> margin                              // 選取在上屏：放在下屏最上面
                top - bh - margin >= margin -> top - bh - margin // 放在文字上方
                else -> bot + dp(36)                            // 上方放不下：放在下方（讓出選取把手）
            }
        }
        x = x.coerceIn(margin, (W - bw - margin).coerceAtLeast(margin))
        y = y.coerceIn(margin, (H - bh - margin).coerceAtLeast(margin))
        if (animate) selectionBar.animate().translationX(x).translationY(y).setDuration(T.DUR).setInterpolator(T.EASE).start()
        else { selectionBar.translationX = x; selectionBar.translationY = y }
    }

    fun hideSelectionBar(mode: SelectionActionMode) {
        if (selectionMode !== mode) return
        selectionMode = null
        selectionBar.visibility = View.GONE
        selectionBar.removeAllViews()
    }

    // ---- 上屏左側直式工具列 ----
    private var sideBar: LinearLayout? = null
    private var sideTabsBtn: TextView? = null
    private var sideModeBtn: TextView? = null
    private var sideBookmarkBtn: TextView? = null
    private var menuBookmarkBtn: ImageView? = null

    init {
        // 上螢幕（工具列最先加，輸入面板、提示等會蓋在它上面）
        buildSideBar()
        top.overlay.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (4 * top.context.resources.displayMetrics.density).roundToInt(), Gravity.TOP))
        top.overlay.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (28 * top.context.resources.displayMetrics.density).roundToInt()
        })

        // 下螢幕
        bottom.overlay.addView(menuBtn, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(10), dp(10))
        })
        // 位置由 positionSelectionBar 決定（放在選取的文字旁邊）
        bottom.overlay.addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        SelectionActionMode.current?.let { showSelectionBar(it) } // 介面重建時選取還在
        buildPanel()
        bottom.overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildGapBar()
        bottom.overlay.addView(gapBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        buildEditPanel()
        // 放在上屏時貼齊頂端（鍵盤會在下屏，不會擋住輸入框）
        editorHost.overlay.addView(editPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (editorOnTop) Gravity.TOP else Gravity.BOTTOM))
        refreshSideTabs() // 頁籤建好後補上分頁數
    }

    private fun buildSideBar() {
        val host = sideHost ?: return
        val eng = Session.engine
        val sw = eng.sideWidth
        if (!Session.prefs.sideBar || sw <= 0 || eng.vw <= 0) return
        val c = host.context
        val d = c.resources.displayMetrics.density
        // 虛擬顯示器像素 → 上屏像素（上屏區域就是虛擬顯示器的整個寬度）
        val sx = if (host.width > 0) host.width.toFloat() / eng.vw else 1f
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = ShellDrawable(d, T.BG, 0f, grid = true)
            val p = (4 * d).roundToInt()
            setPadding(p, p, p, p)
            isClickable = true // 按鈕之間的空隙也不要點到網頁
        }
        fun item(icon: String, name: String, onClick: () -> Unit): TextView = TextView(c).apply {
            text = name
            gravity = Gravity.CENTER
            setTextColor(T.TEXT2)
            textSize = 11f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = (3 * d).roundToInt()
            if (icon.isNotEmpty()) setCompoundDrawablesRelative(null, Icon(icon, T.TEXT, (22 * d).roundToInt()), null, null)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(d, T.SURFACE_HI, 14f, T.EDGE_STRONG))
                addState(intArrayOf(android.R.attr.state_selected), ShellDrawable(d, T.SURFACE_HI, 14f, T.FOCUS, 2f))
                addState(intArrayOf(), ShellDrawable(d, T.SURFACE, 14f, T.EDGE))
            }
            setOnClickListener { onClick() }
            Motion.polish(this, focusGrow = false)
            bar.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                val m = (3 * d).roundToInt()
                setMargins(0, m, 0, m)
            })
        }
        item("back", L.t("上一頁", "上一页", "Back")) { Session.browser?.back() }
        item("forward", L.t("下一頁", "下一页", "Forward")) { Session.browser?.forward() }
        item("reload", L.t("重新整理", "刷新", "Reload")) { Session.browser?.reload() }
        item("home", L.t("首頁", "首页", "Home")) { Session.browser?.home() }
        item("search", L.t("網址", "网址", "URL")) { openMenu(fromPad = false, focusUrl = true) }
        sideBookmarkBtn = item("star", L.t("加書籤", "加书签", "Bookmark")) { addBookmark() }
        // 再按一次 = 關掉分頁列表回到網頁
        sideTabsBtn = item("tabs", "") {
            if (panel.visibility == View.VISIBLE && listMode == ListMode.HOME) closeAll()
            else openMenu(fromPad = false, focusUrl = false)
        }
        // 下屏模式：圖示跟著目前模式變（放大裁切 = 四角往外；完整顯示 = 四角往內）
        sideModeBtn = item("", "") {
            toggleBottomMode()
            Session.main.post { Session.applyBottomMode() }
        }
        refreshSideMode()
        item("menu", L.t("選單", "菜单", "Menu")) {
            if (panel.visibility == View.VISIBLE) closeAll() else openMenu(fromPad = false, focusUrl = false)
        }
        refreshSideTabs()
        host.overlay.addView(bar, FrameLayout.LayoutParams((sw * sx).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.TOP))
        sideBar = bar
    }

    private fun refreshSideMode() {
        val b = sideModeBtn ?: return
        val crop = Session.prefs.bottomCrop
        val d = b.resources.displayMetrics.density
        b.setCompoundDrawablesRelative(null, Icon(if (crop) "zoom" else "fit", T.TEXT, (22 * d).roundToInt()), null, null)
        b.text = if (crop) L.t("放大裁切", "放大裁切", "Zoom") else L.t("完整顯示", "完整显示", "Fit")
    }

    private fun refreshSideTabs() {
        val n = Session.browser?.tabs?.size ?: 0
        sideTabsBtn?.text = L.t("分頁", "标签页", "Tabs") + if (n > 0) " $n" else ""
        segLabels.getOrNull(0)?.text = L.t("分頁", "标签页", "Tabs") + if (n > 0) " $n" else ""
    }

    /** 游標移到工具列上（y = 虛擬顯示器座標；< 0 = 不在工具列上）：游標底下的按鈕變亮。 */
    fun sideBarHover(y: Float) {
        val bar = sideBar ?: return
        val hit = if (y < 0f) null else sideBarChildAt(y)
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            val sel = v === hit
            if (v.isSelected != sel) {
                v.isSelected = sel
                v.animate().scaleX(if (sel) 1.06f else 1f).scaleY(if (sel) 1.06f else 1f).setDuration(T.DUR).setInterpolator(T.EASE).start()
            }
        }
    }

    /** 手把在工具列上按下點擊鍵。 */
    fun sideBarClick(y: Float) {
        sideBarChildAt(y)?.performClick()
    }

    private fun sideBarChildAt(y: Float): View? {
        val bar = sideBar ?: return null
        val h = Session.engine.topHeight
        val sy = if (h > 0 && bar.height > 0) y * bar.height / h else y
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            if (sy >= v.top && sy < v.bottom) return v
        }
        return null
    }

    private fun buildPanel() {
        // 第一列：導覽圖示、網址、書籤、關閉
        val row1 = hRow()
        row1.addView(iconBtn("back", L.t("上一頁", "上一页", "Back")) { Session.browser?.back() })
        row1.addView(iconBtn("forward", L.t("下一頁", "下一页", "Forward")) { Session.browser?.forward() })
        row1.addView(iconBtn("reload", L.t("重新整理", "刷新", "Reload")) { Session.browser?.reload(); closeAll() })
        row1.addView(iconBtn("home", L.t("首頁", "首页", "Home")) { Session.browser?.home(); closeAll() })
        row1.addView(urlEdit, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) })
        menuBookmarkBtn = iconBtn("star", L.t("書籤", "书签", "Bookmark")) { addBookmark() }
        row1.addView(menuBookmarkBtn)
        // 關閉鈕固定在第一列最右邊
        row1.addView(iconBtn("close", L.t("關閉", "关闭", "Close")) { closeAll() })
        panel.addView(row1)

        // 第二列：頁籤 + 新分頁、開 PDF
        val row2 = hRow()
        buildSegments()
        row2.addView(segFrame, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), dp(8), dp(8), dp(8)) })
        row2.addView(iconBtn("plus", L.t("新分頁", "新标签页", "New tab")) {
            Session.browser?.newTab(null, switch = true); Session.browser?.home(); closeAll()
        })
        // 無痕分頁：不記歷史，Cookie／登入和一般分頁分開，關掉就全部清除
        row2.addView(iconBtn("incognito", L.t("新無痕分頁", "新无痕标签页", "New incognito tab")) { newIncognitoTab() })
        // PDF 圖示單獨看不出意思，加上文字
        row2.addView(btn("PDF", icon = "pdf") { closeAll(); Session.pickPdf() }.apply {
            contentDescription = L.t("開啟 PDF", "打开 PDF", "Open PDF")
            minimumHeight = dp(48); minHeight = dp(48)
        })
        panel.addView(row2)
        refreshBookmarkState()

        listScroll.addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listBox.setPadding(dp(4), dp(10), dp(4), dp(44))
        // 上下邊緣淡出：捲動的內容不會被頁籤列和提示列硬切一半
        listScroll.isVerticalFadingEdgeEnabled = true
        listScroll.setFadingEdgeLength(dp(28))
        // 右邊細細的紫色捲動條：看得出下面還有內容
        listScroll.isVerticalScrollBarEnabled = true
        listScroll.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        listScroll.verticalScrollbarThumbDrawable = ShellDrawable(density, T.EDGE_STRONG, 999f)
        listScroll.verticalScrollbarTrackDrawable = null
        listScroll.scrollBarSize = dp(3)
        panel.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 按鍵提示列：自己的底和上緣細線，像機身底部的一條飾條
        hintBar.setPadding(dp(14), dp(8), dp(14), dp(8))
        hintBar.background = object : Drawable() {
            private val fill = android.graphics.Paint().apply { color = T.withAlpha(T.BG, 0xEB) }
            private val line = android.graphics.Paint().apply { color = T.withAlpha(T.cur.grid, 0x2E) }
            override fun draw(c: Canvas) {
                val b = bounds
                c.drawRect(b, fill)
                c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + density, line)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
        panel.addView(hintBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(-dp(14), 0, -dp(14), -dp(6))
        })
    }

    private fun buildSegments() {
        segFrame.background = ShellDrawable(density, 0x14FFFFFF, 999f, T.EDGE)
        segFrame.setPadding(dp(4), dp(4), dp(4), dp(4))
        segFrame.addView(segIndicator, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        segFrame.addView(segRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val items = listOf(
            "tabs" to L.t("分頁", "标签页", "Tabs"),
            "bookmark" to L.t("書籤", "书签", "Bookmarks"),
            "history" to L.t("歷史", "历史", "History"),
            "settings" to L.t("設定", "设置", "Settings"),
        )
        items.forEachIndexed { i, (icon, name) ->
            // 圖示和文字一起置中（compound drawable 會貼在最左邊，和文字分開）
            val ic = Icon(icon, T.TEXT2, dp(20))
            val tv = TextView(ctx).apply {
                text = name
                textSize = 15f
                isSingleLine = true
                setTextColor(T.TEXT2)
                setPadding(dp(8), 0, 0, 0)
            }
            val seg = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isFocusable = true
                addView(ImageView(ctx).apply { setImageDrawable(ic) })
                addView(tv)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, Color.TRANSPARENT, 999f, T.FOCUS, 2f))
                    addState(intArrayOf(), ShellDrawable(density, Color.TRANSPARENT, 999f))
                }
                contentDescription = name
                setOnClickListener { openSegment(i) }
                Motion.polish(this, focusGrow = false)
            }
            segViews.add(seg); segLabels.add(tv); segIcons.add(ic)
            segRow.addView(seg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        // 版面變了（例如第一次排好）時把亮色塊放到位；滑動動畫進行中不要打斷
        if (!segListening) {
            segListening = true
            segRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (segAnim?.isRunning != true) placeIndicator(animate = false) }
        }
    }
    private var segListening = false

    private fun openSegment(i: Int) {
        when (i) {
            0 -> showHome()
            1 -> showBookmarks()
            2 -> showHistory()
            else -> showSettings()
        }
    }

    /** 頁籤的亮色塊滑到選到的那一個，文字和圖示顏色跟著變。 */
    private fun setSegment(i: Int) {
        if (i == segIndex && segIndicator.width > 0) return
        segIndex = i
        segLabels.forEachIndexed { k, v ->
            val on = k == i
            v.setTextColor(if (on) T.ON_ACCENT else T.TEXT2)
            segIcons[k].tint(if (on) T.ON_ACCENT else T.TEXT2)
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        placeIndicator(animate = true)
    }

    private fun placeIndicator(animate: Boolean) {
        val v = segViews.getOrNull(segIndex) ?: return
        if (v.width == 0) return
        val lp = segIndicator.layoutParams
        val fromX = segIndicator.translationX; val fromW = lp.width
        val toX = v.left.toFloat(); val toW = v.width
        segAnim?.cancel()
        if (!animate || fromW == 0) {
            segIndicator.translationX = toX
            if (lp.width != toW) { lp.width = toW; segIndicator.layoutParams = lp }
            return
        }
        segAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280; interpolator = T.EASE
            addUpdateListener {
                val f = it.animatedValue as Float
                segIndicator.translationX = fromX + (toX - fromX) * f
                lp.width = (fromW + (toW - fromW) * f).roundToInt()
                segIndicator.layoutParams = lp
            }
            start()
        }
    }

    /** 底部的按鍵提示（依目前的按鍵設定）：完整的按鍵說明在「按鍵設定」頁。 */
    private fun refreshHelp() {
        hintBar.removeAllViews()
        val g = Session.gamepad
        fun hint(all: String, what: String) {
            // 同一個功能有好幾個鍵時只顯示第一個（例如「A/十字鍵 ←」→「A」），提示列保持簡短
            val keys = all.substringBefore('/').trim()
            if (keys.isEmpty()) return
            // 16 Bit、GC 這種彩色按鍵的機身：A/B/X/Y 的提示也用機身上按鍵的顏色
            val kc = T.cur.keyColors?.get(keys)
            hintBar.addView(TextView(ctx).apply {
                text = keys
                textSize = 12f
                setTextColor(if (kc == null) T.ON_ACCENT else if (luma(kc) > 0.6f) 0xFF1A1A1A.toInt() else Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(26)
                setPadding(dp(7), dp(1), dp(7), dp(1))
                background = ShellDrawable(density, kc ?: T.TEXT2, 999f)
            })
            hintBar.addView(TextView(ctx).apply {
                text = what
                textSize = 13f
                setTextColor(T.TEXT3)
                setPadding(dp(6), 0, dp(16), 0)
            })
        }
        // 返回在左、選擇在右（和實機 B 在左下、A 在右邊的位置一致）
        hint(g.keysFor(PadAction.BACK), L.t("返回", "返回", "Back"))
        hint(g.keysFor(PadAction.CLICK), L.t("選擇", "选择", "Select"))
        val l = g.keysFor(PadAction.PREV_TAB).substringBefore('/'); val r = g.keysFor(PadAction.NEXT_TAB).substringBefore('/')
        if (l.isNotEmpty() && r.isNotEmpty()) hint("$l $r", L.t("切換頁籤", "切换页签", "Switch section"))
        hint(g.keysFor(PadAction.MENU), L.t("關閉選單", "关闭菜单", "Close menu"))
        // 最右邊：退出應用（兩個螢幕一起關，結束整個程式）；選單每一頁都看得到
        hintBar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        hintBar.addView(btn(L.t("退出應用", "退出应用", "Exit app"), icon = "power", small = true) { Session.exitApp() }.apply {
            setPadding(dp(12), dp(3), dp(14), dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    /** 依目前的按鍵對應產生說明文字（教學頁用）。 */
    private fun controlsList(): List<Pair<String, String>> {
        val g = Session.gamepad
        val swap = Session.prefs.swapSticks
        val cursor = L.t("游標", "光标", "Cursor")
        val scroll = L.t("捲動", "滚动", "Scroll")
        val out = mutableListOf(
            L.t("左搖桿", "左摇杆", "Left stick") to if (swap) scroll else cursor,
            L.t("右搖桿", "右摇杆", "Right stick") to if (swap) cursor else scroll,
        )
        PadAction.entries.filter { it != PadAction.NONE }.forEach { a ->
            val k = g.keysFor(a)
            if (k.isNotEmpty()) out.add(k to a.label)
        }
        return out
    }

    fun onTabsChanged() {
        refreshSideTabs()
        if (panel.visibility != View.VISIBLE || listMode != ListMode.HOME || draggingTab) return
        val b = Session.browser ?: return
        val inc = tabGroupIncognito ?: curIncognito
        // 分頁沒增減、順序沒變：只更新卡片上的標題和圖示，不重建整頁。
        // 網頁載入時標題、圖示一直在變，重建會讓正在按的 ✕ 消失，按了沒反應。
        if (b.tabs.filter { it.incognito == inc } == shownTabs && b.tabs.getOrNull(b.current) === shownCurrent) {
            tabUpdaters.forEach { it() }
        } else showHome(animate = false)
    }

    /** 比手把焦點浮起的高度（6dp）更高：卡片上的 ✕、標記、亮線用，才不會被選到的卡片蓋住。 */
    private val ABOVE_FOCUS: Float get() = dp(10).toFloat()

    /** 分頁頁目前顯示的分頁（順序）和目前的分頁；用來判斷要整頁重建還是只更新內容。 */
    private var shownTabs: List<Tab> = emptyList()
    private var shownCurrent: Tab? = null
    private val tabUpdaters = ArrayList<() -> Unit>()
    /** 正在拖動分頁（拖完才重建）。 */
    private var draggingTab = false

    private fun buildGapBar() {
        gapBar.addView(gapTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        val row = hRow()
        row.addView(btn(L.t("下屏往上", "下屏往上", "Bottom up"), icon = "up") { stepGap(+1) })
        row.addView(btn(L.t("下屏往下", "下屏往下", "Bottom down"), icon = "down") { stepGap(-1) })
        row.addView(btn(L.t("重設", "重置", "Reset"), icon = "reload") { setGap(0) })
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(primaryBtn(L.t("完成", "完成", "Done"), icon = "check") { closeGapBar() })
        gapBar.addView(row)
    }

    private fun refreshGapTitle() {
        gapTitle.text = L.t(
            "看著轉軸附近的文字或圖片，調到上下接得起來（十字鍵 ↑↓ 也可以調）",
            "看着转轴附近的文字或图片，调到上下接得起来（十字键 ↑↓ 也可以调）",
            "Watch text near the hinge and adjust until top and bottom line up (D-pad ↑↓ works too)")
    }

    /** 開啟間距調整列：關掉選單，直接看著網頁調整。 */
    fun showGapBar() {
        closeAll()
        refreshGapTitle()
        gapBar.visibility = View.VISIBLE
        Motion.rise(gapBar, 40f)
        menuBtn.visibility = View.GONE
        firstFocusable(gapBar)?.requestFocusFromTouch()
    }

    private fun stepGap(dir: Int) = setGap(Session.prefs.gapPx + dir * 10)

    private fun setGap(g: Int) {
        val v = g.coerceIn(0, Session.engine.maxGap.coerceAtLeast(0))
        Session.prefs.gapPx = v
        Session.applyGap(v)
        if (v == 0 && g < 0) flash(L.t("已經到最下面了", "已经到最下面了", "Can't go lower"))
        else if (g > v) flash(L.t("已經到最上面了", "已经到最上面了", "Can't go higher"))
    }

    private fun closeGapBar() {
        if (gapBar.visibility != View.VISIBLE) return
        gapBar.visibility = View.GONE
        if (panel.visibility != View.VISIBLE && editPanel.visibility != View.VISIBLE) showMenuBtn()
    }

    private fun buildEditPanel() {
        editPanel.addView(editHint)
        editPanel.addView(editField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, edp(8), 0, edp(8))
        })
        editField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher && promptSubmit == null)
                    Session.browser?.setEditValue(s?.toString() ?: "", editField.selectionStart, editField.selectionEnd)
            }
        })
        editField.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_GO ||
                id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE ||
                id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_NULL || ev?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                // Enter 按鍵會送 DOWN、UP 兩次：只在第一次（面板還開著時）送出，避免送出兩次
                if (editPanel.visibility == View.VISIBLE && (ev == null || ev.action == KeyEvent.ACTION_DOWN)) submitEditor()
                true
            } else false
        }
        // 保險：有些鍵盤按確定是直接輸入換行字元，看到換行就當成送出
        editField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher || s == null || promptSubmit != null) return
                val i = s.indexOf('\n')
                if (i < 0 || editPanel.visibility != View.VISIBLE) return
                suppressWatcher = true
                s.delete(i, i + 1)
                suppressWatcher = false
                submitEditor()
            }
        })
        val row = editButtons
        row.addView(primaryBtn(L.t("送出", "提交", "Go"), ectx, icon = "check") { submitEditor() })
        row.addView(btn(L.t("清除", "清除", "Clear"), ectx, icon = "close") { editField.setText("") })
        row.addView(View(ectx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(btn(L.t("取消", "取消", "Cancel"), ectx) { closeEditor(blur = true) })
        editPanel.addView(row)
    }

    /**
     * hidden = true：幫網頁輸入框打字時，不顯示輸入面板（文字會即時出現在網頁的輸入框裡），
     * 只留一個看不見的輸入框接收鍵盤；按鍵盤的 Enter 送出、返回鍵結束。
     * false：一般輸入（網址、設定欄位）照常顯示。
     */
    private fun setEditorHidden(hidden: Boolean) {
        editHint.visibility = if (hidden) View.GONE else View.VISIBLE
        editButtons.visibility = if (hidden) View.GONE else View.VISIBLE
        editField.alpha = if (hidden) 0f else 1f
        (editField.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.height = if (hidden) 1 else ViewGroup.LayoutParams.WRAP_CONTENT
            lp.setMargins(0, if (hidden) 0 else edp(8), 0, if (hidden) 0 else edp(8))
            editField.layoutParams = lp
        }
        val d = ectx.resources.displayMetrics.density
        editPanel.background = if (hidden) null
        else ShellDrawable(d, T.BG_GLASS, 22f, T.EDGE_STRONG, grid = true, sheen = true)
        val pad = if (hidden) 0 else edp(16)
        editPanel.setPadding(pad, if (hidden) 0 else edp(14), pad, if (hidden) 0 else edp(14))
        (editPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val m = if (hidden) 0 else edp(12)
            lp.setMargins(m, m, m, m)
            editPanel.layoutParams = lp
        }
    }

    /** 一般輸入（網址、首頁等）：開輸入面板，按送出時把文字交給 onSubmit。 */
    private fun showPrompt(title: String, value: String, inputType: Int, onSubmit: (String) -> Unit) {
        editorOpenedAt = android.os.SystemClock.uptimeMillis()
        promptSubmit = onSubmit
        setEditorHidden(false)
        editHint.text = title
        editField.inputType = inputType
        editField.isSingleLine = true
        editField.maxLines = 1
        // NO_EXTRACT_UI：不然下屏（橫的）鍵盤會變成蓋滿下屏的白色全螢幕打字框
        editField.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        suppressWatcher = true
        editField.setText(value)
        editField.selectAll()
        suppressWatcher = false
        val was = editPanel.visibility == View.VISIBLE
        editPanel.visibility = View.VISIBLE
        if (!was) { if (editorOnTop) Motion.drop(editPanel, 24f) else Motion.rise(editPanel, 24f, T.DUR) }
        menuBtn.visibility = View.GONE
        focusEditor()
    }

    private fun promptUrl() {
        showPrompt(
            L.t("輸入網址或搜尋關鍵字", "输入网址或搜索关键字", "Enter URL or search"),
            currentUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ) { text ->
            Session.browser?.load(text)
            closeAll()
        }
    }

    /** 讓輸入框所在的視窗取得焦點，再叫出鍵盤。 */
    private fun focusEditor() {
        // 輸入面板在上屏時，要先讓上屏成為系統的「焦點螢幕」，鍵盤才會出現
        if (editorOnTop) Session.focusTopScreen()
        editField.requestFocusFromTouch()
        showIme(editField)
    }

    // ---------------- 對外介面 ----------------

    fun onUrl(url: String?) {
        if (url == null) return
        // 無痕說明頁（本機的頁面）網址列留空，和 Chrome 的新分頁一樣
        val shown = if (url == "about:blank" || url.startsWith("data:")) "" else url
        currentUrl = shown
        urlEdit.text = shown
        refreshIncognitoLook()
        refreshBookmarkState()
        onDesktopModeChanged()
    }

    fun onDesktopModeChanged() {
        val toggle = desktopModeToggle ?: return
        val on = Session.browser?.isDesktop ?: return
        if (toggle.on != on) toggle.animateTo(on)
    }

    private val curIncognito: Boolean get() = Session.browser?.let { it.tabs.isNotEmpty() && it.tab.incognito } == true

    /**
     * 無痕模式的外觀（和 Chrome 一樣整組換成中性灰，一眼就看得出現在是無痕）：
     * 看的是無痕分頁組（或目前分頁是無痕）時，T 換成灰色色票，選單的外框（網址列、頁籤、按鈕、提示列）重建。
     */
    private fun refreshIncognitoLook() {
        val inc = (if (panel.visibility == View.VISIBLE) tabGroupIncognito else null) ?: curIncognito
        if (inc != T.incognito) {
            T.incognito = inc
            restyleChrome()
        }
        urlEdit.setCompoundDrawablesRelative(Icon(if (inc) "incognito" else "search", if (inc) T.TEXT else T.TEXT2, dp(20)), null, null, null)
    }

    /** 選單外框用目前的 T 重建（清單內容由呼叫的人重畫）。 */
    private fun restyleChrome() {
        panel.removeAllViews()
        (urlEdit.parent as? ViewGroup)?.removeView(urlEdit)
        (segFrame.parent as? ViewGroup)?.removeView(segFrame)
        listScroll.removeAllViews()
        segFrame.removeAllViews(); segRow.removeAllViews()
        segViews.clear(); segLabels.clear(); segIcons.clear()
        panel.background = ShellDrawable(density, T.BG_GLASS, 0f, grid = !T.incognito, sheen = true)
        segIndicator.background = ShellDrawable(density, T.ACCENT, 999f)
        urlEdit.setTextColor(T.TEXT); urlEdit.setHintTextColor(T.TEXT3); urlEdit.background = pillBg(999f)
        buildPanel()
        refreshHelp()
        refreshSideTabs()
        val keep = segIndex
        segIndex = -1
        setSegment(keep.coerceAtLeast(0))
    }

    private fun newIncognitoTab() {
        val b = Session.browser ?: return
        if (!Incognito.supported) {
            flash(L.t("這台裝置的 WebView 版本太舊，不支援無痕分頁", "这台设备的 WebView 版本太旧，不支持无痕标签页", "This device's WebView is too old for incognito tabs"))
            return
        }
        b.newIncognitoTab()
        tabGroupIncognito = true
        closeAll()
    }


    /** 書籤鈕顯示目前網頁是否已在書籤裡（☆ 加入 / ★ 取消）。 */
    private fun refreshBookmarkState() {
        val url = Session.browser?.web?.url ?: currentUrl
        val on = url.isNotEmpty() && Session.prefs.bookmarks().any { it.url == url }
        sideBookmarkBtn?.let { b ->
            val d = b.resources.displayMetrics.density
            b.text = if (on) L.t("取消書籤", "取消书签", "Unmark") else L.t("加書籤", "加书签", "Bookmark")
            b.setCompoundDrawablesRelative(null, Icon(if (on) "star_on" else "star", if (on) T.ACCENT else T.TEXT, (22 * d).roundToInt()), null, null)
        }
        menuBookmarkBtn?.let { b ->
            b.setImageDrawable(Icon(if (on) "star_on" else "star", if (on) T.ACCENT else T.TEXT, dp(22)))
            b.contentDescription = if (on) L.t("取消書籤", "取消书签", "Remove bookmark") else L.t("加入書籤", "加入书签", "Bookmark")
        }
    }

    /** 檔案下載／讀取進度：上屏頂端的進度條 + 底部一直顯示的文字（直到 [endFileProgress]）。 */
    fun showFileProgress(what: String, done: Long, total: Long) {
        fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1048576.0)
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        toast.text = when {
            pct >= 0 -> "$what  $pct%（${mb(done)} / ${mb(total)} MB）"
            done <= 0 -> "$what…"
            else -> "$what  ${mb(done)} MB"
        }
        showToast()
        toast.removeCallbacks(hideToast)
        progress.isIndeterminate = pct < 0
        if (pct >= 0) progress.progress = pct
        progress.visibility = View.VISIBLE
    }

    fun endFileProgress(msg: String?) {
        progress.isIndeterminate = false
        progress.visibility = View.GONE
        if (msg != null) flash(msg) else toast.visibility = View.GONE
    }

    fun onProgress(p: Int) {
        progress.isIndeterminate = false
        progress.progress = p
        progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
    }

    private fun showToast() {
        if (toast.visibility != View.VISIBLE || toast.alpha < 1f) {
            toast.visibility = View.VISIBLE
            Motion.rise(toast, 14f, T.DUR)
        }
    }

    fun flash(msg: String) {
        toast.text = msg
        showToast()
        toast.removeCallbacks(hideToast)
        toast.postDelayed(hideToast, 1800)
    }

    private fun showPanel() {
        if (panel.visibility == View.VISIBLE) return
        panel.visibility = View.VISIBLE
        menuBtn.visibility = View.GONE
        Motion.rise(panel, 28f)
    }

    private fun showMenuBtn() {
        if (menuBtn.visibility == View.VISIBLE) return
        menuBtn.visibility = View.VISIBLE
        menuBtn.scaleX = 0.6f; menuBtn.scaleY = 0.6f; menuBtn.alpha = 0f
        menuBtn.animate().scaleX(1f).scaleY(1f).alpha(0.85f).setDuration(T.DUR + 80).setInterpolator(T.SPRING).start()
    }

    fun openMenu(fromPad: Boolean, focusUrl: Boolean) {
        tabGroupIncognito = null
        refreshIncognitoLook()
        closeEditor(blur = false)
        urlEdit.text = currentUrl
        refreshHelp()
        showHome()
        showPanel()
        if (focusUrl) {
            promptUrl()
        } else if (fromPad) {
            // 用手把開選單：焦點停在目前的分頁卡片（沒有就第一個）
            listBox.post { (currentTabCard ?: firstFocusable(listBox) ?: firstFocusable(panel))?.requestFocusFromTouch() }
        }
    }

    fun showEditor(value: String, type: String, hint: String, multiline: Boolean, selStart: Int = -1, selEnd: Int = -1) {
        val now = android.os.SystemClock.uptimeMillis()
        if (editPanel.visibility == View.VISIBLE && now - editorOpenedAt < 600) {
            // focusin + click 連發：點擊時網頁才把游標放到點的位置，只更新游標
            if (promptSubmit == null && editField.text.toString() == value) placeCaret(selStart, selEnd)
            return
        }
        editorOpenedAt = now
        promptSubmit = null
        panel.visibility = View.GONE
        setEditorHidden(true)
        val title = L.t("網頁輸入", "网页输入", "Web input")
        editHint.text = if (hint.isNotBlank()) "$title: $hint" else title
        editField.inputType = when (type) {
            "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            "tel" -> InputType.TYPE_CLASS_PHONE
            else -> if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT
        }
        // 輸入面板看不見，沒有「送出」按鈕可按：一律單行，鍵盤右下角的確定鍵就是送出
        // （多行輸入框以前按 Enter 是換行，現在看不到面板就沒辦法送出）
        if (multiline) editField.inputType = InputType.TYPE_CLASS_TEXT
        editField.isSingleLine = true
        editField.maxLines = 1
        // NO_EXTRACT_UI：下屏是橫的，鍵盤預設會改成全螢幕的白色打字框，蓋住整個下屏
        editField.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        suppressWatcher = true
        editField.setText(value)
        if (selStart >= 0) placeCaret(selStart, selEnd) else editField.setSelection(editField.text.length)
        suppressWatcher = false
        editPanel.visibility = View.VISIBLE
        editPanel.alpha = 1f; editPanel.translationY = 0f
        // 打字框是看不見的，選單鈕照常顯示
        focusEditor()
    }

    // ---------------- 長按連結／圖片的選單 ----------------

    private var linkSheet: FrameLayout? = null
    private var linkCard: LinearLayout? = null

    /**
     * 長按連結或圖片時的選單（下屏中間的卡片，點外面關閉）：
     * 開啟、在新分頁開啟、在無痕分頁開啟、複製連結；圖片另有「在新分頁開啟圖片」「複製圖片網址」。
     * fromIncognito：在無痕分頁裡長按（新分頁本來就是無痕，不再另外列「無痕分頁」）。
     */
    fun showLinkMenu(link: String?, image: String?, fromIncognito: Boolean, vx: Float = -1f, vy: Float = -1f) {
        hideLinkMenu(animate = false)
        closeEditor(blur = false)
        if (panel.visibility == View.VISIBLE) { panel.visibility = View.GONE }
        val b = Session.browser ?: return
        // 淡淡的遮罩：點選單外面就關閉
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x33000000)
            isClickable = true
            setOnClickListener { hideLinkMenu() }
        }
        // 小巧的浮動選單（緊湊的列、小字、小圖示），出現在手指按的位置旁邊
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ShellDrawable(density, T.solid(0xF8), 16f, T.EDGE_STRONG, sheen = true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            elevation = dp(8).toFloat()
        }
        card.addView(TextView(ctx).apply {
            text = (link ?: image)?.removePrefix("https://")?.removePrefix("http://")
            textSize = 11f
            setTextColor(T.TEXT3)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(12), dp(6), dp(12), dp(4))
        })
        fun item(icon: String, text: String, f: () -> Unit) {
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(dp(12), 0, dp(14), 0)
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 10f))
                    addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, T.SURFACE_HI, 10f, T.FOCUS, 2f))
                    addState(intArrayOf(), ShellDrawable(density, Color.TRANSPARENT, 10f))
                }
                addView(ImageView(ctx).apply { setImageDrawable(Icon(icon, T.TEXT2, dp(18))) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
                addView(TextView(ctx).apply { this.text = text; textSize = 14f; setTextColor(T.TEXT); isSingleLine = true })
                setOnClickListener { hideLinkMenu(); f() }
                Motion.polish(this, focusGrow = false)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (link != null) {
            item("link", L.t("開啟", "打开", "Open")) { b.openLink(link, 0) }
            item("tabs", if (fromIncognito) L.t("在新的無痕分頁開啟", "在新的无痕标签页打开", "Open in new incognito tab")
                else L.t("在新分頁開啟", "在新标签页打开", "Open in new tab")) { b.openLink(link, 1) }
            if (!fromIncognito) item("incognito", L.t("在無痕分頁開啟", "在无痕标签页打开", "Open in incognito tab")) { b.openLink(link, 2) }
            item("copy", L.t("複製連結", "复制链接", "Copy link")) { copyText(link, L.t("已複製連結", "已复制链接", "Link copied")) }
        }
        if (image != null) {
            item("image", L.t("在新分頁開啟圖片", "在新标签页打开图片", "Open image in new tab")) { b.openLink(image, 1) }
            item("copy", L.t("複製圖片網址", "复制图片网址", "Copy image address")) { copyText(image, L.t("已複製圖片網址", "已复制图片网址", "Image address copied")) }
        }
        val cw = dp(248)
        scrim.addView(card, FrameLayout.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        bottom.overlay.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        linkSheet = scrim; linkCard = card
        menuBtn.visibility = View.GONE
        // 放在按的位置旁邊（下屏座標）；按在上屏的連結就放在下屏最上面、同一個橫向位置
        card.visibility = View.INVISIBLE
        card.post {
            val W = scrim.width.toFloat(); val H = scrim.height.toFloat()
            val ch = card.height.toFloat(); val m = dp(10).toFloat()
            val reg = Session.engine.bottomRegion
            var x = (W - cw) / 2f; var y = (H - ch) / 2f
            if (vx >= 0f && reg.width() > 0f && reg.height() > 0f) {
                val px = (vx - reg.left) * W / reg.width()
                val py = (vy - reg.top) * H / reg.height()
                x = px + dp(16)                                  // 手指右邊一點
                if (x + cw > W - m) x = px - cw - dp(16)         // 右邊放不下：放左邊
                y = if (py < 0f) m else py - ch / 3f
            }
            card.translationX = x.coerceIn(m, (W - cw - m).coerceAtLeast(m))
            card.translationY = y.coerceIn(m, (H - ch - m).coerceAtLeast(m))
            card.visibility = View.VISIBLE
            card.pivotX = 0f; card.pivotY = 0f
            card.scaleX = 0.92f; card.scaleY = 0.92f; card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(T.DUR).setInterpolator(T.EASE).start()
            if (!card.isInTouchMode) firstFocusable(card)?.requestFocusFromTouch()
        }
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(T.DUR).setInterpolator(T.EASE).start()
    }
    private fun hideLinkMenu(animate: Boolean = true) {
        val s = linkSheet ?: return
        linkSheet = null; linkCard = null
        if (animate) s.animate().alpha(0f).setDuration(T.DUR_OUT).setInterpolator(T.EASE_IN)
            .withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
        else (s.parent as? ViewGroup)?.removeView(s)
        if (panel.visibility != View.VISIBLE && editPanel.visibility != View.VISIBLE) showMenuBtn()
    }

    private fun copyText(text: String, done: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        flash(done)
    }

    /** 打字框的游標放到網頁輸入框的同一個位置（超出範圍就放最後）。 */
    private fun placeCaret(selStart: Int, selEnd: Int) {
        val n = editField.text.length
        if (selStart < 0) return
        val s = selStart.coerceIn(0, n)
        val e = selEnd.coerceIn(s, n)
        editField.setSelection(s, e)
    }

    /** 網頁輸入框失去焦點或換頁：收起幫網頁打字的（看不見的）輸入框。 */
    fun onWebBlur() {
        if (editPanel.visibility == View.VISIBLE && promptSubmit == null &&
            android.os.SystemClock.uptimeMillis() - editorOpenedAt > 500) closeEditor(blur = false)
    }

    /** 螢幕組合改變時，把這組介面從舊的畫面上拿掉。 */
    fun detach() {
        hideIme()
        toast.removeCallbacks(hideToast)
        listOfNotNull(progress, toast, menuBtn, selectionBar, panel, gapBar, editPanel, sideBar, linkSheet).forEach { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun closeAll() {
        hideLinkMenu(animate = false)
        if (panel.visibility == View.VISIBLE) fadeOutGhost(panel)
        panel.visibility = View.GONE
        gapBar.visibility = View.GONE
        closeEditor(blur = false)
        showMenuBtn()
        hideIme()
    }

    /**
     * 關閉的動畫：面板本身馬上隱藏（其他程式看到的狀態不變），
     * 拍一張面板的畫面蓋在原位淡出、往下滑。
     */
    private fun fadeOutGhost(v: View) {
        val parent = v.parent as? ViewGroup ?: return
        if (v.width <= 0 || v.height <= 0) return
        val bmp = try {
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        } catch (e: Throwable) { return }
        val ghost = ImageView(v.context).apply { setImageBitmap(bmp) }
        parent.addView(ghost, parent.indexOfChild(v) + 1, FrameLayout.LayoutParams(v.width, v.height).apply {
            leftMargin = v.left; topMargin = v.top
        })
        ghost.animate().alpha(0f).translationY(18f * density).setDuration(T.DUR_OUT).setInterpolator(T.EASE_IN)
            .withEndAction { (ghost.parent as? ViewGroup)?.removeView(ghost); bmp.recycle() }.start()
    }

    /**
     * 選單開著時的手把操作（只在按下時反應，避免開選單那一下的放開又把它關掉）：
     * 十字鍵固定用來移動焦點；對應到「點擊/確定」的鍵 = 確認；
     * 「上一個／下一個分頁」的鍵 = 切換頁籤；「返回」「選單」「網址列」的鍵 = 關閉。
     */
    fun onPadKey(code: Int, down: Boolean, action: PadAction): Boolean {
        if (!down) return true
        // 長按連結的選單：十字鍵選、確定鍵按、返回／選單鍵關閉
        linkCard?.let { lc ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> moveFocus(lc, View.FOCUS_UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(lc, View.FOCUS_DOWN)
                else -> when (action) {
                    PadAction.CLICK -> lc.findFocus()?.performClick()
                    PadAction.BACK, PadAction.MENU, PadAction.URL_BAR -> hideLinkMenu()
                    else -> {}
                }
            }
            return true
        }
        if (gapBar.visibility == View.VISIBLE && editPanel.visibility != View.VISIBLE) {
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> stepGap(+1)
                code == KeyEvent.KEYCODE_DPAD_DOWN -> stepGap(-1)
                code == KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(gapBar, View.FOCUS_LEFT)
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(gapBar, View.FOCUS_RIGHT)
                action == PadAction.CLICK -> gapBar.findFocus()?.performClick()
                action == PadAction.BACK || action == PadAction.MENU -> closeGapBar()
            }
            return true
        }
        val root = if (editPanel.visibility == View.VISIBLE) editPanel else panel
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveFocus(root, View.FOCUS_UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveFocus(root, View.FOCUS_DOWN); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveFocus(root, View.FOCUS_LEFT); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveFocus(root, View.FOCUS_RIGHT); return true }
        }
        when (action) {
            PadAction.CLICK -> {
                val f = root.findFocus()
                if (f is EditText) focusEditor() else f?.performClick()
            }
            // 肩鍵切換頁籤（像遊戲機的系統選單）
            PadAction.PREV_TAB, PadAction.NEXT_TAB -> if (root === panel) {
                val n = segViews.size
                val i = ((if (listMode == ListMode.HOME) 0 else if (listMode == ListMode.BOOKMARKS) 1
                    else if (listMode == ListMode.HISTORY) 2 else 3) + (if (action == PadAction.NEXT_TAB) 1 else n - 1)) % n
                openSegment(i)
                listBox.post { firstFocusable(listBox)?.requestFocusFromTouch() }
            }
            // 網址列鍵再按一次 = 整個關掉回到網頁（不要停在選單）
            PadAction.URL_BAR -> {
                if (editPanel.visibility == View.VISIBLE) closeEditor(blur = promptSubmit == null)
                closeAll()
            }
            PadAction.BACK, PadAction.MENU ->
                if (editPanel.visibility == View.VISIBLE) closeEditor(blur = true) else closeAll()
            else -> {}
        }
        return true
    }

    // ---------------- 內部 ----------------

    private fun submitEditor() {
        val text = editField.text.toString()
        val p = promptSubmit
        if (p != null) {
            closeEditor(blur = false)
            p(text)
            return
        }
        Session.browser?.setEditValue(text)
        Session.browser?.submitEdit()
        closeEditor(blur = false)
    }

    private fun closeEditor(blur: Boolean) {
        if (editPanel.visibility != View.VISIBLE) return
        val wasPrompt = promptSubmit != null
        promptSubmit = null
        if (wasPrompt) fadeOutGhost(editPanel)
        editPanel.visibility = View.GONE
        if (panel.visibility != View.VISIBLE) showMenuBtn()
        hideIme()
        if (blur && !wasPrompt) Session.browser?.blurEdit()
    }

    private fun addBookmark() {
        val web = Session.browser?.web ?: return
        val url = web.url ?: return
        val list = Session.prefs.bookmarks()
        // 已經在書籤裡 → 再按一次就取消書籤
        if (list.none { it.url == url }) {
            list.add(Bookmark(web.title?.takeIf { it.isNotBlank() } ?: url, url))
            Session.prefs.saveBookmarks(list)
            flash(L.t("已加入書籤", "已加入书签", "Bookmark added"))
        } else {
            list.removeAll { it.url == url }
            Session.prefs.saveBookmarks(list)
            flash(L.t("已取消書籤", "已取消书签", "Bookmark removed"))
        }
        refreshBookmarkState()
        menuBookmarkBtn?.let { b -> b.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction {
            b.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(T.SPRING).start() }.start() }
        if (panel.visibility == View.VISIBLE && listMode == ListMode.BOOKMARKS) showBookmarks(animate = false)
    }

    /** 換頁：清空清單、切頁籤、捲到頂端。 */
    private fun beginPage(mode: ListMode, seg: Int) {
        listMode = mode
        desktopModeToggle = null
        listBox.removeAllViews()
        setSegment(seg)
        scrollListToTop()
    }

    private var currentTabCard: View? = null

    /** 選單首頁：（有新版時的提示）+ 分頁卡片。 */
    private fun showHome(animate: Boolean = true) {
        beginPage(ListMode.HOME, 0)
        currentTabCard = null
        tabUpdaters.clear()
        shownTabs = emptyList()
        shownCurrent = null
        if (UpdateChecker.updateAvailable) {
            val c = card()
            row(c, "bell", L.t("有新版本 ", "有新版本 ", "New version ") + Session.prefs.latestVersion,
                L.t("目前 ", "当前 ", "Current ") + UpdateChecker.currentVersion(),
                trailing = if (AppInfo.ITCH_URL.isNotBlank()) chip(L.t("前往下載", "前往下载", "Download"), T.ACCENT) else null,
                onClick = if (AppInfo.ITCH_URL.isNotBlank()) ({ openUrl(AppInfo.ITCH_URL) }) else null)
        }
        val b = Session.browser
        if (b != null) {
            // 和 Chrome 一樣：一般分頁、無痕分頁分成兩組，上面切換要看哪一組
            val inc = tabGroupIncognito ?: curIncognito
            val normalN = b.tabs.count { !it.incognito }
            val incN = b.tabs.count { it.incognito }
            val head = hRow().apply { setPadding(dp(4), 0, dp(4), dp(6)); gravity = Gravity.CENTER_VERTICAL }
            // 這一列的膠囊和按鈕一樣高
            fun headLp(start: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = start }
            fun group(incognito: Boolean) {
                tabGroupIncognito = incognito
                refreshIncognitoLook()
                showHome()
            }
            head.addView(choice(L.t("一般", "普通", "Tabs") + "  $normalN", "tabs", !inc) { group(false) }, headLp(dp(6)))
            head.addView(choice(L.t("無痕", "无痕", "Incognito") + "  $incN", "incognito", inc) { group(true) }, headLp(dp(6)))
            head.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            if (inc && incN > 0) head.addView(choice(L.t("關閉所有無痕分頁", "关闭所有无痕标签页", "Close all incognito tabs"), "close", false) {
                b.closeAllIncognito()
                group(false)
            }, headLp(0))
            listBox.addView(head)
            val idx = b.tabs.indices.filter { b.tabs[it].incognito == inc }
            if (inc && idx.isEmpty()) {
                emptyState("incognito", L.t("沒有無痕分頁", "没有无痕标签页", "No incognito tabs"),
                    L.t("無痕分頁不記歷史紀錄，關掉後 Cookie 和登入都會清除", "无痕标签页不记历史记录，关掉后 Cookie 和登录都会清除",
                        "No history is kept; cookies and logins are cleared when you close them"))
                val r = actionsRow()
                r.gravity = Gravity.CENTER
                r.addView(primaryBtn(L.t("開啟無痕分頁", "打开无痕标签页", "New incognito tab"), icon = "incognito") { newIncognitoTab() })
            } else {
                shownTabs = idx.map { b.tabs[it] }
                shownCurrent = b.tabs.getOrNull(b.current)
                val views = idx.map { tabCard(b, b.tabs[it], Session.prefs.tabsGrid) } + newTabCard(inc)
                if (Session.prefs.tabsGrid) addGrid(views, 104) else addList(views, 64)
            }
        }
        if (animate) Motion.stagger(listBox)
    }

    /** 方塊排列：一列 3 個，最後一列補空位讓卡片寬度一致。 */
    private fun addGrid(views: List<View>, heightDp: Int) {
        val cols = 3
        var line: LinearLayout? = null
        views.forEachIndexed { k, v ->
            if (k % cols == 0) {
                line = hRow().apply { gravity = Gravity.TOP; clipChildren = false } // 拖動時的插入亮線畫在卡片之間
                listBox.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            line!!.addView(v, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        repeat((cols - views.size % cols) % cols) {
            line!!.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
    }

    /** 條列排列：一行一個。 */
    private fun addList(views: List<View>, heightDp: Int) {
        listBox.clipChildren = false // 拖動時的插入亮線畫在卡片之間
        views.forEach { v ->
            listBox.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { setMargins(dp(5), dp(4), dp(5), dp(4)) })
        }
    }

    /** 分頁頁面目前看的是哪一組；null = 跟著目前的分頁 */
    private var tabGroupIncognito: Boolean? = null

    private fun hostOf(url: String?): String = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.")

    /**
     * 網站標誌：無痕分頁是無痕圖示、PDF 是文件圖示、有網站圖示用圖示，都沒有才用首字母圓章
     * （每個網站一個固定的柔和色調）。
     */
    private fun siteBadge(url: String?, label: String, incognito: Boolean): View {
        val host = hostOf(url)
        val fav = if (incognito) null else Favicons.get(url)
        return when {
            incognito -> ImageView(ctx).apply {
                setImageDrawable(Icon("incognito", T.ON_ACCENT, dp(18)))
                scaleType = ImageView.ScaleType.CENTER
                background = ShellDrawable(density, T.TEXT2, 999f)
            }
            PdfViewer.isViewerUrl(url) -> ImageView(ctx).apply {
                setImageDrawable(Icon("pdf", 0xFFFFFFFF.toInt(), dp(17)))
                scaleType = ImageView.ScaleType.CENTER
                background = ShellDrawable(density, 0xFFD9534F.toInt(), 8f) // PDF 慣用的紅色
            }
            fav != null -> ImageView(ctx).apply {
                setImageBitmap(fav)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = dp(4); setPadding(p, p, p, p)
                background = ShellDrawable(density, 0xFFF3EFFF.toInt(), 8f)
            }
            else -> TextView(ctx).apply {
                val key = host.ifEmpty { label }
                val hsv = floatArrayOf(((key.hashCode() and 0x7fffffff) % 360).toFloat(), 0.38f, 0.92f)
                text = (host.firstOrNull() ?: label.firstOrNull() ?: '•').uppercaseChar().toString()
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFF1C1A22.toInt()) // 圓章是淺色，字一律深色
                background = ShellDrawable(density, Color.HSVToColor(hsv), 999f)
            }
        }
    }

    /** 分頁的網域那一行：PDF 顯示「PDF」，本機頁面（about:blank）不顯示網址。 */
    private fun tabWhere(t: Tab): String {
        val where = if (PdfViewer.isViewerUrl(t.url)) "PDF"
        else hostOf(t.url).ifEmpty { t.url.takeUnless { it == "about:blank" || it.startsWith("data:") }.orEmpty() }
        return if (t.incognito) L.t("無痕", "无痕", "Incognito") + (if (where.isNotEmpty()) " · $where" else "") else where
    }

    /** 分頁標題：標題本身是網址時只顯示網域；PDF 還沒讀到標題時用檔名。 */
    private fun tabTitle(t: Tab): String {
        if (PdfViewer.isViewerUrl(t.url) && (t.title.isBlank() || t.title == "PDF.js viewer")) {
            Uri.parse(t.url).getQueryParameter("name")?.let { return it }
        }
        return t.label.takeUnless { it.contains("://") || it.startsWith("www.") } ?: hostOf(t.url).ifEmpty { t.label }
    }

    /**
     * 分頁卡片（方塊或條列）：網站標誌、網域、標題；右上角關閉；目前的分頁底色亮＋左邊紫色標記。
     * 長按可以拖到別的分頁上改順序。標題、圖示變了只更新內容（見 onTabsChanged）。
     */
    private fun tabCard(b: Browser, t: Tab, grid: Boolean): View {
        val cur = b.tabs.getOrNull(b.current) === t
        val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val body = LinearLayout(ctx).apply {
            orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (!grid) gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(if (grid) 12 else 8), dp(if (grid) 14 else 52), dp(if (grid) 12 else 8))
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 18f, T.EDGE_STRONG))
                addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, T.SURFACE_HI, 18f, T.FOCUS, 2.5f))
                // 目前的分頁：底色亮一點＋左邊的紫色標記（亮框只留給手把焦點，兩者才分得出來）
                addState(intArrayOf(), ShellDrawable(density, if (cur) T.SURFACE_HI else T.SURFACE, 18f, if (cur) T.EDGE_STRONG else T.EDGE))
            }
            contentDescription = t.label
            setOnClickListener { b.tabs.indexOf(t).takeIf { it >= 0 }?.let { b.select(it) }; closeAll() }
            Motion.polish(this)
        }
        val badgeBox = FrameLayout(ctx)
        val whereView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(T.TEXT3)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleView = TextView(ctx).apply {
            textSize = 15f
            setTextColor(T.TEXT)
            maxLines = if (grid) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }
        var lastBadge = ""
        fun update() {
            whereView.text = tabWhere(t)
            titleView.text = tabTitle(t)
            // 標誌只在種類改變時換（網站圖示讀到了、變成 PDF…），避免一直重畫
            val kind = when {
                t.incognito -> "inc"
                PdfViewer.isViewerUrl(t.url) -> "pdf"
                Favicons.get(t.url) != null -> "fav:" + hostOf(t.url)
                else -> "letter:" + hostOf(t.url) + t.label.take(1)
            }
            if (kind != lastBadge) {
                lastBadge = kind
                badgeBox.removeAllViews()
                badgeBox.addView(siteBadge(t.url, t.label, t.incognito), FrameLayout.LayoutParams(dp(26), dp(26)))
            }
        }
        update()
        tabUpdaters.add(::update)
        if (grid) {
            // 第一行：標誌＋網域；下面是標題（最多兩行）
            val head = hRow()
            head.addView(badgeBox, LinearLayout.LayoutParams(dp(26), dp(26)))
            whereView.setPadding(dp(8), 0, dp(30), 0)
            head.addView(whereView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(head)
            titleView.setPadding(0, dp(10), dp(6), 0)
            body.addView(titleView)
        } else {
            // 條列：標誌｜標題＋網域
            body.addView(badgeBox, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(14) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(titleView)
            whereView.setPadding(0, dp(2), 0, 0)
            texts.addView(whereView)
            body.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        frame.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 卡片被手把選到時會放大並浮起（translationZ 6dp，見 Motion.polish），觸控會先送給比較高的那層；
        // 標記和 ✕ 一律放在更高的一層，否則選到的卡片會蓋住 ✕，按了變成「切到這個分頁並關選單」
        if (cur) frame.addView(View(ctx).apply { background = ShellDrawable(density, T.ACCENT_DEEP, 999f); elevation = ABOVE_FOCUS },
            FrameLayout.LayoutParams(dp(4), dp(36), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(4) })
        var closing = false
        frame.addView(iconBtn("close", L.t("關閉分頁", "关闭标签页", "Close tab"), sizeDp = 34, iconDp = 16, quiet = true) {
            if (closing) return@iconBtn
            closing = true
            frame.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(T.DUR_OUT).setInterpolator(T.EASE_IN)
                .withEndAction {
                    // 用分頁本身找位置（動畫期間別的分頁可能已經關掉或移動）
                    b.tabs.indexOf(t).takeIf { it >= 0 }?.let { b.closeTab(it) }
                    showHome(animate = false)
                }.start()
        }.apply { elevation = ABOVE_FOCUS }, FrameLayout.LayoutParams(dp(34), dp(34), if (grid) Gravity.TOP or Gravity.END else Gravity.CENTER_VERTICAL or Gravity.END).apply {
            setMargins(0, if (grid) dp(6) else 0, dp(8), 0)
        })
        enableTabDrag(b, t, frame, body, grid)
        if (cur) currentTabCard = body
        return frame
    }

    /** 分頁的拖動排序（拖動期間不重建分頁頁，拖完才重建）。 */
    private fun enableTabDrag(b: Browser, t: Tab, frame: FrameLayout, body: View, grid: Boolean) =
        enableReorderDrag(t, frame, body, grid, isTab = true,
            onStart = { draggingTab = true },
            onDrop = { dragged, after -> draggingTab = false; b.moveTab(dragged as Tab, t, after) },
            onEnd = {
                if (draggingTab) {
                    draggingTab = false
                    // 沒放到別的分頁上：整頁重建一次（恢復透明度）
                    if (listMode == ListMode.HOME) showHome(animate = false)
                }
            })

    /** 書籤的拖動排序：放開後存檔並重建書籤頁。 */
    private fun enableBookmarkDrag(bm: Bookmark, frame: FrameLayout, body: View, grid: Boolean) =
        enableReorderDrag(bm, frame, body, grid, isTab = false,
            onStart = {},
            onDrop = { dragged, after ->
                val d = dragged as Bookmark
                val l = Session.prefs.bookmarks()
                fun idx(x: Bookmark) = l.indexOfFirst { it.url == x.url && it.title == x.title }
                val from = idx(d)
                if (from >= 0 && idx(bm) >= 0 && from != idx(bm)) {
                    val item = l.removeAt(from)
                    l.add(idx(bm) + if (after) 1 else 0, item)
                    Session.prefs.saveBookmarks(l)
                }
                frame.post { if (listMode == ListMode.BOOKMARKS) showBookmarks(animate = false) }
            },
            onEnd = { if (listMode == ListMode.BOOKMARKS) showBookmarks(animate = false) })

    /**
     * 長按開始拖動（分頁、書籤共用）。拖到另一張卡片上時，那張卡片的左邊或右邊（條列是上面或下面）
     * 出現一條亮線，表示放開後會插在那裡；放開就移過去。
     */
    private fun enableReorderDrag(
        item: Any, frame: FrameLayout, body: View, grid: Boolean, isTab: Boolean,
        onStart: () -> Unit, onDrop: (dragged: Any, after: Boolean) -> Unit, onEnd: () -> Unit,
    ) {
        // 插入位置的亮線（平常隱藏）：方塊放在卡片左右兩側的空隙，條列放在上下
        val line = View(ctx).apply {
            background = ShellDrawable(density, T.ACCENT, 999f)
            visibility = View.GONE
            elevation = ABOVE_FOCUS
        }
        frame.addView(line, if (grid) FrameLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT)
            else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
        var after = false
        fun showLine(a: Boolean) {
            after = a
            val lp = line.layoutParams as FrameLayout.LayoutParams
            if (grid) {
                lp.gravity = if (a) Gravity.END else Gravity.START
                lp.marginStart = if (a) 0 else -dp(8); lp.marginEnd = if (a) -dp(8) else 0
            } else {
                lp.gravity = if (a) Gravity.BOTTOM else Gravity.TOP
                lp.topMargin = if (a) 0 else -dp(7); lp.bottomMargin = if (a) -dp(7) else 0
            }
            line.layoutParams = lp
            line.visibility = View.VISIBLE
        }
        var dropped = false
        body.setOnLongClickListener {
            onStart()
            // 條列：拖動的影子縮小一半、放在手指右邊，不會蓋住卡片上下的亮線
            val shadow = if (grid) View.DragShadowBuilder(frame) else object : View.DragShadowBuilder(frame) {
                override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
                    size.set((frame.width * 0.5f).toInt().coerceAtLeast(1), (frame.height * 0.5f).toInt().coerceAtLeast(1))
                    touch.set(0, size.y / 2)
                }
                override fun onDrawShadow(canvas: Canvas) {
                    canvas.scale(0.5f, 0.5f)
                    frame.draw(canvas)
                }
            }
            val ok = frame.startDragAndDrop(android.content.ClipData.newPlainText("item", ""), shadow, item, 0)
            if (ok) {
                frame.alpha = 0.35f
                val what = if (isTab) L.t("分頁", "标签页", "tab") else L.t("書籤", "书签", "bookmark")
                flash(if (grid) L.t("拖到${what}的左邊或右邊，亮線處就是新位置", "拖到${what}的左边或右边，亮线处就是新位置", "Drop on the left or right of a $what — the glowing line shows where it goes")
                    else L.t("拖到${what}的上面或下面，亮線處就是新位置", "拖到${what}的上面或下面，亮线处就是新位置", "Drop above or below a $what — the glowing line shows where it goes"))
            } else onEnd()
            ok
        }
        frame.setOnDragListener { v, e ->
            // 只接受同一種東西（分頁只能放到分頁上、書籤只能放到書籤上）
            val dragged = e.localState?.takeIf { it.javaClass == item.javaClass }
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> { dropped = false; dragged != null }
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    if (dragged !== item) showLine(if (grid) e.x > v.width / 2f else e.y > v.height / 2f)
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { line.visibility = View.GONE; true }
                android.view.DragEvent.ACTION_DROP -> {
                    line.visibility = View.GONE
                    if (dragged != null && dragged !== item) onDrop(dragged, after)
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    line.visibility = View.GONE
                    // 被拖的那張卡片收到結束：沒放到別的卡片上就恢復（重建頁面）
                    if (v.alpha < 1f && !dropped) { dropped = true; v.alpha = 1f; v.post { onEnd() } }
                    true
                }
                else -> true
            }
        }
    }

    private fun newTabCard(incognito: Boolean = false): View = LinearLayout(ctx).apply {
        val grid = Session.prefs.tabsGrid
        orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 18f, T.EDGE_STRONG))
            addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, T.SURFACE_HI, 18f, T.FOCUS, 2.5f))
            addState(intArrayOf(), ShellDrawable(density, 0x0AFFFFFF, 18f, T.EDGE))
        }
        addView(ImageView(ctx).apply { setImageDrawable(Icon(if (incognito) "incognito" else "plus", T.TEXT2, dp(if (grid) 28 else 22))) })
        addView(TextView(ctx).apply {
            text = if (incognito) L.t("新無痕分頁", "新无痕标签页", "New incognito tab") else L.t("新分頁", "新标签页", "New tab")
            textSize = 14f; setTextColor(T.TEXT2)
            if (grid) setPadding(0, dp(6), 0, 0) else setPadding(dp(10), 0, 0, 0)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(if (grid) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            if (incognito) newIncognitoTab()
            else { Session.browser?.newTab(null, switch = true); Session.browser?.home(); closeAll() }
        }
        Motion.polish(this)
    }

    /** 書籤頁。 */
    private fun showBookmarks(animate: Boolean = true) {
        beginPage(ListMode.BOOKMARKS, 1)
        val list = Session.prefs.bookmarks()
        if (list.isEmpty()) {
            emptyState("star", L.t("還沒有書籤", "还没有书签", "No bookmarks yet"),
                L.t("在網頁上按網址列旁的 ☆ 加入", "在网页上按地址栏旁的 ☆ 加入", "Tap ☆ next to the address bar to add one"))
        } else {
            fun remove(bm: Bookmark) {
                val l = Session.prefs.bookmarks()
                l.indexOfFirst { it.url == bm.url && it.title == bm.title }.takeIf { it >= 0 }?.let { l.removeAt(it) }
                Session.prefs.saveBookmarks(l); refreshBookmarkState(); showBookmarks(animate = false)
            }
            val views = list.map { bm -> bookmarkCard(bm, Session.prefs.bookmarksGrid, { Session.browser?.load(bm.url); closeAll() }) { remove(bm) } }
            if (Session.prefs.bookmarksGrid) addGrid(views, 104) else addList(views, 64)
        }
        if (animate) Motion.stagger(listBox)
    }

    /** 書籤卡片（方塊或條列）：網站標誌、網域、標題；右邊刪除。 */
    private fun bookmarkCard(bm: Bookmark, grid: Boolean, onOpen: () -> Unit, onRemove: () -> Unit): View {
        val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false } // 拖動的亮線畫在卡片外側
        val body = LinearLayout(ctx).apply {
            orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (!grid) gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(if (grid) 12 else 8), dp(if (grid) 14 else 52), dp(if (grid) 12 else 8))
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 18f, T.EDGE_STRONG))
                addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, T.SURFACE_HI, 18f, T.FOCUS, 2.5f))
                addState(intArrayOf(), ShellDrawable(density, T.SURFACE, 18f, T.EDGE))
            }
            contentDescription = bm.title
            setOnClickListener { onOpen() }
            Motion.polish(this)
        }
        val badge = siteBadge(bm.url, bm.title, false)
        val where = if (PdfViewer.isViewerUrl(bm.url)) "PDF" else hostOf(bm.url)
        val whereView = TextView(ctx).apply {
            text = where; textSize = 12f; setTextColor(T.TEXT3); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        val titleView = TextView(ctx).apply {
            text = bm.title.ifBlank { bm.url }; textSize = 15f; setTextColor(T.TEXT)
            maxLines = if (grid) 2 else 1; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.08f)
        }
        if (grid) {
            val head = hRow()
            head.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)))
            whereView.setPadding(dp(8), 0, dp(30), 0)
            head.addView(whereView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(head)
            titleView.setPadding(0, dp(10), dp(6), 0)
            body.addView(titleView)
        } else {
            body.addView(badge, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(14) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(titleView)
            whereView.setPadding(0, dp(2), 0, 0)
            texts.addView(whereView)
            body.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        frame.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(iconBtn("close", L.t("刪除書籤", "删除书签", "Remove bookmark"), sizeDp = 34, iconDp = 16, quiet = true) { onRemove() }
            .apply { elevation = ABOVE_FOCUS }, // 放在被選到（浮起）的卡片上面，見 tabCard
            FrameLayout.LayoutParams(dp(34), dp(34), if (grid) Gravity.TOP or Gravity.END else Gravity.CENTER_VERTICAL or Gravity.END).apply {
                setMargins(0, if (grid) dp(6) else 0, dp(8), 0)
            })
        enableBookmarkDrag(bm, frame, body, grid)
        return frame
    }

    /** 歷史紀錄頁：依日期分組，最新的在最上面；點了開啟，✕ 刪除單筆。 */
    private fun showHistory(limit: Int = 150) {
        beginPage(ListMode.HISTORY, 2)
        val all = History.all()
        if (all.isEmpty()) {
            emptyState("history", L.t("還沒有歷史紀錄", "还没有历史记录", "No history yet"),
                L.t("看過的網頁會依日期列在這裡，只存在這台裝置", "看过的网页会按日期列在这里，只保存在这台设备", "Pages you visit show up here by day, stored on this device only"))
            Motion.stagger(listBox)
            return
        }
        var armed = false
        lateinit var clearBtn: Button
        clearBtn = btn(L.t("清除全部", "清除全部", "Clear all"), icon = "trash") {
            if (!armed) {
                armed = true
                clearBtn.text = L.t("再按一次確認", "再按一次确认", "Press again")
                clearBtn.setTextColor(T.DANGER)
            } else {
                History.clear()
                Favicons.clear() // 網站圖示也看得出去過哪些網站，一起清掉
                flash(L.t("歷史紀錄已清除", "历史记录已清除", "History cleared"))
                showHistory()
            }
        }
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val today = dayFmt.format(java.util.Date())
        val yesterday = dayFmt.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        var lastDay = ""
        var c: LinearLayout? = null
        all.take(limit).forEach { e ->
            val day = dayFmt.format(java.util.Date(e.time))
            if (day != lastDay) {
                val first = lastDay.isEmpty()
                lastDay = day
                c = section(when (day) {
                    today -> L.t("今天", "今天", "Today")
                    yesterday -> L.t("昨天", "昨天", "Yesterday")
                    else -> day
                }, trailing = if (first) clearBtn else null)
            }
            val isPdf = PdfViewer.isViewerUrl(e.url)
            val host = if (isPdf) "PDF" else hostOf(e.url)
            val title = e.title.takeUnless { it.isBlank() || it == "PDF.js viewer" }
                ?: (if (isPdf) Uri.parse(e.url).getQueryParameter("name") else null) ?: e.url
            row(c!!, if (isPdf) "pdf" else null, title,
                timeFmt.format(java.util.Date(e.time)) + if (host.isNotEmpty()) "  ·  $host" else "",
                trailing = iconBtn("close", L.t("刪除這筆", "删除这条", "Remove"), sizeDp = 38, iconDp = 18, quiet = true) {
                    History.remove(e); showHistory(limit)
                },
                onClick = { Session.browser?.load(e.url); closeAll() })
        }
        if (all.size > limit) {
            listBox.addView(btn(L.t("顯示更多", "显示更多", "Show more") + " (${all.size - limit})", icon = "down") { showHistory(limit + 150) })
        }
        Motion.stagger(listBox)
    }

    // ---------------- 使用者腳本 ----------------

    /** 打開選單面板並顯示 listBox 的某一頁（從網頁或手把觸發時用）。 */
    private fun openPanelPage(fill: () -> Unit) {
        closeEditor(blur = false)
        urlEdit.text = currentUrl
        refreshHelp()
        fill()
        showPanel()
        firstFocusable(listBox)?.requestFocusFromTouch()
    }

    /** 點到 .user.js 連結：下載後顯示安裝確認頁。 */
    fun installScriptFrom(url: String) {
        val what = L.t("下載腳本", "下载脚本", "Downloading script")
        showFileProgress(what, 0, -1)
        UserScripts.fetchAsync(url) { code ->
            endFileProgress(null)
            offerScriptInstall(code, url)
        }
    }

    fun offerScriptInstall(code: String?, source: String?) {
        val s = code?.let { UserScripts.parse(it, source) }
        if (code == null) { flash(L.t("下載腳本失敗", "下载脚本失败", "Couldn't download the script")); return }
        if (s == null) { flash(L.t("這不是有效的使用者腳本（找不到 ==UserScript== 區塊）", "这不是有效的用户脚本（找不到 ==UserScript== 区块）", "Not a valid userscript (no ==UserScript== block)")); return }
        openPanelPage { showScriptInstall(s, code) }
    }

    private fun showScriptInstall(s: UserScripts.Script, code: String) {
        beginPage(ListMode.SCRIPTS, 3)
        val existing = UserScripts.all().find { it.id == s.id }
        val c = section(if (existing != null) L.t("更新使用者腳本", "更新用户脚本", "Update userscript")
            else L.t("安裝使用者腳本", "安装用户脚本", "Install userscript"))
        row(c, "script", "${s.name}  ${s.version}",
            listOfNotNull(
                existing?.let { L.t("目前 ", "当前 ", "Installed ") + it.version },
                s.author.takeIf { it.isNotBlank() }?.let { L.t("作者：", "作者：", "Author: ") + it },
            ).joinToString("  ·  ").ifEmpty { null })
        if (s.description.isNotBlank()) c.addView(cardNote(s.description))
        val sites = (s.matches + s.includes).ifEmpty { listOf("*") }
        row(c, "globe", L.t("會在這些網站執行", "会在这些网站运行", "Runs on"),
            sites.take(6).joinToString("、") + if (sites.size > 6) " …(+${sites.size - 6})" else "")
        row(c, "warn", L.t("只安裝你信任的腳本", "只安装你信任的脚本", "Only install scripts you trust"),
            L.t("腳本可以讀取、修改它執行的網頁內容，也可以連線到其他網站。", "脚本可以读取、修改它运行的网页内容，也可以连接到其他网站。",
                "A script can read and change the pages it runs on and connect to other sites."), titleColor = T.WARN)
        val r = actionsRow()
        r.addView(primaryBtn(if (existing != null) L.t("更新", "更新", "Update") else L.t("安裝", "安装", "Install"), icon = "download") {
            showFileProgress(L.t("安裝腳本", "安装脚本", "Installing script"), 0, -1)
            UserScripts.install(s, code) { err ->
                endFileProgress(err ?: L.t("已安裝「", "已安装「", "Installed \"") + s.name +
                    L.t("」，重新整理網頁後生效", "」，刷新网页后生效", "\" — reload the page to use it"))
                if (err == null) showScripts()
            }
        })
        r.addView(btn(L.t("取消", "取消", "Cancel")) { showScripts() })
        Motion.stagger(listBox)
    }

    private var confirmDelete: String? = null
    private val IMMERSIVE_TRANSLATE = "https://download.immersivetranslate.com/immersive-translate.user.js"

    private fun showScripts() {
        beginPage(ListMode.SCRIPTS, 3)
        backLink { showSettings() }
        val tab = Session.browser?.tab
        val menus = tab?.gmMenus.orEmpty()
        if (menus.isNotEmpty()) {
            val c = section(L.t("這個網頁的腳本指令", "这个网页的脚本命令", "Script commands for this page"))
            menus.toList().forEach { m ->
                row(c, "play", m.label, m.scriptName.ifBlank { null }, onClick = { closeAll(); UserScripts.runMenu(tab!!, m) })
            }
        }
        val list = UserScripts.all()
        val c = section(L.t("已安裝的腳本", "已安装的脚本", "Installed scripts") + "  ${list.size}",
            caption = L.t("變更在重新整理網頁後生效", "更改在刷新网页后生效", "Changes apply after reloading the page"))
        if (list.isEmpty()) c.addView(cardNote(L.t("還沒有腳本。可以到 Greasy Fork 找油猴腳本，點「安裝」就會出現安裝畫面。",
            "还没有脚本。可以到 Greasy Fork 找油猴脚本，点「安装」就会出现安装画面。",
            "No scripts yet. Find userscripts on Greasy Fork and tap Install.")))
        list.forEach { s ->
            val right = hRow()
            if (s.source != null) right.addView(iconBtn("reload", L.t("更新", "更新", "Update"), sizeDp = 40, iconDp = 18, quiet = true) { installScriptFrom(s.source) })
            right.addView(if (confirmDelete == s.id) btn(L.t("確定刪除？", "确定删除？", "Delete?")) { deleteScript(s) }.apply { setTextColor(T.DANGER) }
                else iconBtn("trash", L.t("刪除", "删除", "Delete"), sizeDp = 40, iconDp = 18, quiet = true) { confirmDelete = s.id; showScripts() })
            val tg = ToggleView(ctx, s.enabled)
            right.addView(tg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            row(c, "script", s.name, s.version, trailing = right, titleColor = if (s.enabled) T.TEXT else T.TEXT3, onClick = {
                tg.animateTo(!s.enabled)
                listBox.postDelayed({ UserScripts.setEnabled(s.id, !s.enabled); showScripts() }, 220)
            })
        }
        val add = section(L.t("安裝腳本", "安装脚本", "Add scripts"))
        row(add, "language", L.t("沉浸式翻譯", "沉浸式翻译", "Immersive Translate"),
            L.t("一鍵安裝，網頁雙語對照", "一键安装，网页双语对照", "One tap; bilingual web pages"), chevron = true) { installScriptFrom(IMMERSIVE_TRANSLATE) }
        row(add, "search", L.t("到 Greasy Fork 找腳本", "到 Greasy Fork 找脚本", "Browse Greasy Fork"), null, chevron = true) {
            Session.browser?.newTab(L.t("https://greasyfork.org/zh-TW", "https://greasyfork.org/zh-CN", "https://greasyfork.org/en"), switch = true)
            closeAll()
        }
        row(add, "globe", L.t("從網址安裝", "从网址安装", "From URL"), null, chevron = true) {
            showPrompt(L.t("輸入 .user.js 腳本的網址", "输入 .user.js 脚本的网址", "Enter the .user.js URL"), "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { u ->
                val url = u.trim().let { if (it.contains("://")) it else "https://$it" }
                installScriptFrom(url)
            }
        }
        row(add, "pdf", L.t("從檔案安裝", "从文件安装", "From file"), null, chevron = true) { Session.pickScriptFile() }
        Motion.stagger(listBox)
    }

    private fun deleteScript(s: UserScripts.Script) {
        confirmDelete = null
        UserScripts.remove(s.id)
        flash(L.t("已刪除「", "已删除「", "Deleted \"") + s.name + L.t("」", "」", "\""))
        showScripts()
    }

    private fun showSettings() {
        val p = Session.prefs
        beginPage(ListMode.SETTINGS, 3)

        // ---- 一般 ----
        val gen = section(L.t("一般", "通用", "General"))
        // 語言（固定同時顯示中英文，選錯語言時也看得懂）
        row(gen, "language", "語言 / 语言 / Language", null, trailing = valueText(L.displayName(L.chosen), T.ACCENT), chevron = true, onClick = {
            val all = L.Lang.entries
            val next = all[(all.indexOf(L.chosen) + 1) % all.size]
            p.language = next.code
            // 重建整組介面，回到設定頁
            Session.main.post { Session.refreshUi { it.reopenSettings() } }
        })
        lateinit var homeRow: View
        val useCur = btn(L.t("用目前頁面", "用当前页面", "Use current"), small = true) {
            if (currentUrl.isNotEmpty()) {
                p.home = currentUrl
                flash(L.t("首頁已儲存", "首页已保存", "Home page saved"))
                showSettings()
            }
        }
        homeRow = row(gen, "home", L.t("首頁", "首页", "Home page"), p.home, trailing = useCur, onClick = {
            showPrompt(L.t("首頁網址", "首页网址", "Home page URL"), p.home,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { text ->
                p.home = text.trim().ifEmpty { Prefs.DEFAULT_HOME }
                flash(L.t("首頁已儲存", "首页已保存", "Home page saved"))
                if (panel.visibility == View.VISIBLE && listMode == ListMode.SETTINGS) showSettings()
            }
        })
        val desktopToggle = ToggleView(ctx, Session.browser?.isDesktop ?: p.desktop)
        desktopModeToggle = desktopToggle
        row(gen, "desktop", L.t("這個網站用電腦版", "这个网站用电脑版", "Desktop site here"),
            L.t("每個網站分開記住", "每个网站分开记住", "Remembered per site"), trailing = desktopToggle) {
            Session.browser?.let { it.setDesktop(!it.isDesktop) }
        }
        valueRow(gen, "width", L.t("電腦版版面寬度", "电脑版页面宽度", "Desktop layout width"), null, { "${p.desktopWidth}px" },
            { Session.browser?.setDesktopWidth((p.desktopWidth - 128).coerceAtLeast(768)) },
            { Session.browser?.setDesktopWidth((p.desktopWidth + 128).coerceAtMost(1920)) })
        valueRow(gen, "text", L.t("文字大小", "文字大小", "Text size"), null, { "${p.textZoom}%" },
            { Session.browser?.zoomText(-10) }, { Session.browser?.zoomText(10) })
        // 分頁、書籤頁的排列方式：方塊或條列
        fun layoutPick(grid: Boolean, set: (Boolean) -> Unit) = hRow().apply {
            addView(choice(L.t("方塊", "方块", "Grid"), "grid", grid) { if (!grid) { set(true); showSettings() } })
            addView(choice(L.t("條列", "列表", "List"), "list", !grid) { if (grid) { set(false); showSettings() } })
        }
        row(gen, "tabs", L.t("分頁顯示方式", "标签页显示方式", "Tabs layout"), null,
            trailing = layoutPick(p.tabsGrid) { p.tabsGrid = it })
        row(gen, "bookmark", L.t("書籤顯示方式", "书签显示方式", "Bookmarks layout"), null,
            trailing = layoutPick(p.bookmarksGrid) { p.bookmarksGrid = it })

        // ---- 雙螢幕 ----
        val modeHeader = sectionHeader(L.t("雙螢幕", "双屏幕", "Dual screen"))
        val dual = card()
        val modeTrail = hRow()
        modeTrail.addView(choice(L.t("放大裁切", "放大裁切", "Zoom"), "zoom", p.bottomCrop) { if (!p.bottomCrop) { toggleBottomMode(); applyModeAndReturn() } })
        modeTrail.addView(choice(L.t("完整顯示", "完整显示", "Fit"), "fit", !p.bottomCrop) { if (p.bottomCrop) { toggleBottomMode(); applyModeAndReturn() } })
        row(dual, "hinge", L.t("下屏顯示模式", "下屏显示模式", "Bottom screen"),
            if (p.bottomCrop) L.t("和上屏對齊，適合漫畫、小說", "和上屏对齐，适合漫画、小说", "Lines up with the top screen; great for comics")
            else L.t("整個網頁寬度，適合 YouTube", "整个网页宽度，适合 YouTube", "Full page width; best for YouTube"),
            trailing = modeTrail)
        if (p.bottomCrop) {
            // 裁切比例 = 下屏 1 像素等於上屏幾個像素；預設依面板實際規格（RP Duo：401/356 ≈ 112.6%）
            val def = Session.currentDefaultCropScale()
            fun cur() = if (p.cropScale > 0f) p.cropScale else def
            valueRow(dual, "zoom", L.t("裁切比例", "裁切比例", "Crop scale"),
                L.t("下屏比上屏大就按 −，小就按 ＋", "下屏比上屏大就按 −，小就按 ＋", "Bottom larger than top → −, smaller → ＋"),
                { "%.1f%%".format(cur() * 100) + if (p.cropScale <= 0f) L.t(" 自動", " 自动", " auto") else "" },
                { p.cropScale = (cur() - 0.005f).coerceAtLeast(0.8f); applyModeAndReturn() },
                { p.cropScale = (cur() + 0.005f).coerceAtMost(1.5f); applyModeAndReturn() },
                extra = if (p.cropScale > 0f) btn(L.t("自動", "自动", "Auto"), small = true) { p.cropScale = 0f; applyModeAndReturn() } else null)
        }
        row(dual, "hinge", L.t("上下螢幕間距", "上下屏幕间距", "Gap between screens"),
            L.t("讓跨過轉軸的內容接得起來", "让跨过转轴的内容接得起来", "Line content up across the hinge"), chevron = true) { showGapBar() }
        val sideHeaderAnchor = toggleRow(dual, "sidebar", L.t("上屏側邊工具列", "上屏侧边工具栏", "Top-screen side toolbar"),
            L.t("放在上屏左邊，網頁左緣在下屏不會被裁掉", "放在上屏左边，网页左缘在下屏不会被裁掉", "Keeps the page's left edge visible on the bottom screen"), p.sideBar) {
            p.sideBar = !p.sideBar
            Session.main.post { Session.applySideBar { it.reopenSideBarSettings() } }
        }

        if (scrollToMode) {
            scrollToMode = false
            modeHeader.post { listScroll.scrollTo(0, modeHeader.top) }
        }
        if (scrollToSide) {
            scrollToSide = false
            sideHeaderAnchor.post { listScroll.scrollTo(0, modeHeader.top) }
        }

        // ---- 手把 ----
        val pad = section(L.t("手把", "手柄", "Controller"))
        row(pad, "gamepad", L.t("按鍵設定", "按键设置", "Button mapping"),
            L.t("看手把圖，更改每個按鍵的功能", "看手柄图，更改每个按键的功能", "Change what each button does"), chevron = true) { showKeyMapPage() }
        valueRow(pad, "cursor", L.t("游標速度", "光标速度", "Cursor speed"), null, { "%.1f".format(p.cursorSpeed) },
            { p.cursorSpeed = (p.cursorSpeed - 0.2f).coerceAtLeast(0.2f) },
            { p.cursorSpeed = (p.cursorSpeed + 0.2f).coerceAtMost(4f) })
        valueRow(pad, "scroll", L.t("捲動速度", "滚动速度", "Scroll speed"), null, { "%.1f".format(p.scrollSpeed) },
            { p.scrollSpeed = (p.scrollSpeed - 0.2f).coerceAtLeast(0.2f) },
            { p.scrollSpeed = (p.scrollSpeed + 0.2f).coerceAtMost(4f) })
        addImeRow(pad)

        addAdBlockSettings()

        // ---- 主題：五種 RP Duo 機身配色（放在廣告阻擋下面） ----
        addThemePicker()

        // ---- 更多 ----
        val more = section(L.t("更多", "更多", "More"))
        row(more, "script", L.t("使用者腳本", "用户脚本", "User scripts"),
            L.t("沉浸式翻譯、油猴腳本", "沉浸式翻译、油猴脚本", "Immersive Translate, userscripts"), chevron = true) { showScripts() }
        row(more, "data", L.t("清除 Cookie、快取與網站資料", "清除 Cookie、缓存和网站数据", "Clear cookies, cache and site data"), null) {
            Session.browser?.clearData()
        }
        row(more, "book", L.t("使用教學", "使用教程", "Tutorial"), null, chevron = true) { showTutorial(0) }
        row(more, "info", L.t("關於", "关于", "About"), "${L.t("版本 ", "版本 ", "Version ")}${UpdateChecker.currentVersion()}", chevron = true) { showAbout() }
        Motion.stagger(listBox)
    }

    /**
     * 主題選擇：一排五台迷你 RP Duo（照官方配色順序），點了整組介面換成那個配色，
     * 桌面圖示也一起換成同色。
     */
    private fun addThemePicker(inTutorial: Boolean = false) {
        val c = if (inTutorial) section(L.t("先選一個主題", "先选一个主题", "Pick a theme"),
            caption = L.t("你的 RP Duo 是哪個顏色？App 圖示也會一起換，之後可在 設定 → 主題 更改",
                "你的 RP Duo 是哪个颜色？应用图标也会一起换，之后可在 设置 → 主题 更改",
                "Which color is your RP Duo? The app icon changes too. You can change it later in Settings → Theme"))
        else section(L.t("主題", "主题", "Theme"),
            caption = L.t("配合你的 RP Duo 機身配色，App 圖示也會一起換", "配合你的 RP Duo 机身配色，应用图标也会一起换",
                "Match your RP Duo's color. The app icon changes too"))
        // 格子放大（手把焦點、按下）時不要被裁掉
        val line = hRow().apply { gravity = Gravity.TOP; setPadding(dp(8), dp(12), dp(8), dp(12)); clipChildren = false; clipToPadding = false }
        Palette.ALL.forEach { pal ->
            val sel = pal.id == T.base.id
            val tile = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(10), dp(8), dp(8))
                isFocusable = true
                contentDescription = pal.label
                // 目前的主題：亮一點的底＋細亮邊，名稱前面一個勾勾（不壓到機身小圖）；粗的焦點框只給手把焦點
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 16f, T.EDGE_STRONG))
                    addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, if (sel) T.SURFACE_HI else T.SURFACE, 16f, T.FOCUS, 2.5f))
                    addState(intArrayOf(), ShellDrawable(density, if (sel) T.SURFACE_HI else Color.TRANSPARENT, 16f, if (sel) T.ACCENT else 0, 1.5f))
                }
                addView(ImageView(ctx).apply { setImageDrawable(DuoSwatch(pal, density)) }, LinearLayout.LayoutParams(dp(72), dp(76)))
                addView(TextView(ctx).apply {
                    // 名稱一律兩行（中文／英文），五格高度一樣，機身小圖才會對齊
                    val name = pal.label.replaceFirst(" ", "\n")
                    text = if (!sel) name else android.text.SpannableString("  $name").apply {
                        val ic = Icon("check", T.ACCENT, dp(14)).apply { setBounds(0, 0, dp(14), dp(14)) }
                        setSpan(android.text.style.ImageSpan(ic, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    textSize = 12f
                    gravity = Gravity.CENTER
                    minLines = 2; maxLines = 2
                    setTextColor(if (sel) T.TEXT else T.TEXT2)
                    if (sel) typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(6), 0, 0)
                })
                setOnClickListener { if (!sel) applyTheme(pal, inTutorial) }
                Motion.polish(this)
            }
            tile.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            line.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        c.addView(line)
    }

    private fun applyTheme(pal: Palette, inTutorial: Boolean = false) {
        Session.prefs.theme = pal.id
        T.cur = pal
        IconSwitcher.apply(pal.id)
        // 整組介面用新顏色重建，回到原本的頁面（設定頁或教學第一頁）
        Session.main.post {
            Session.refreshUi { ui ->
                if (inTutorial) ui.showTutorial(0) else ui.reopenSettings()
                ui.flash(L.t("已換成「", "已换成「", "Theme: ") + pal.label + L.t("」，桌面圖示幾秒後更新", "」，桌面图标几秒后更新", " — the app icon updates in a few seconds"))
            }
        }
    }

    private var scrollToAd = false

    /** 設定頁的「廣告阻擋」區：開關、清單狀態、立即更新、這個網站不擋。 */
    private fun addAdBlockSettings() {
        val p = Session.prefs
        val header = sectionHeader(L.t("廣告阻擋", "广告拦截", "Ad blocker"))
        val c = card()
        fun refresh() { scrollToAd = true; showSettings() }

        toggleRow(c, "shield", L.t("內建廣告阻擋", "内置广告拦截", "Built-in ad blocker"),
            L.t("⚠ 請謹慎使用：有些網站可能出現無法預期的情況", "⚠ 请谨慎使用：有些网站可能出现无法预期的情况",
                "⚠ Use with care: some sites may behave unexpectedly"), p.adBlock, captionColor = T.WARN) {
            AdBlock.setEnabled(!p.adBlock)
            Session.browser?.reload()
            refresh()
        }

        if (p.adBlock) {
            val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val tab = Session.browser?.tab
            val host = tab?.url?.let { Uri.parse(it).host }
            val status = when {
                AdBlock.updating -> L.t("正在下載／載入過濾清單…", "正在下载／加载过滤列表…", "Downloading / loading filter lists…")
                !AdBlock.ready -> L.t("還沒有過濾清單，請按「立即更新」", "还没有过滤列表，请按「立即更新」", "No filter lists yet — press Update now")
                else -> "%,d".format(AdBlock.ruleCount) + L.t(" 條規則", " 条规则", " rules") +
                    (if (p.adBlockUpdated > 0) "  ·  " + L.t("更新於 ", "更新于 ", "updated ") + df.format(java.util.Date(p.adBlockUpdated)) else "") +
                    (if (tab != null && host != null) "  ·  " + L.t("這一頁擋了 ", "这一页拦了 ", "blocked here: ") + tab.adBlocked.get() else "")
            }
            row(c, "info", L.t("過濾清單", "过滤列表", "Filter lists"), status,
                trailing = btn(L.t("立即更新", "立即更新", "Update now"), small = true, icon = "reload") {
                    flash(L.t("正在更新過濾清單…", "正在更新过滤列表…", "Updating filter lists…"))
                    AdBlock.update(force = true) { err ->
                        flash(if (err == null) L.t("過濾清單已更新", "过滤列表已更新", "Filter lists updated")
                        else L.t("部分清單更新失敗：", "部分列表更新失败：", "Some lists failed: ") + err)
                        if (panel.visibility == View.VISIBLE && listMode == ListMode.SETTINGS) refresh()
                    }
                    refresh()
                })
            if (host != null) {
                val allowed = AdBlock.isAllowed(host)
                toggleRow(c, "globe", L.t("這個網站不要擋", "这个网站不要拦截", "Allow ads on this site"),
                    AdBlock.baseDomain(host.lowercase()), allowed) {
                    // 存最上層的主網域，子網域一起適用
                    AdBlock.setAllowed(if (allowed) host.lowercase().let { h ->
                        p.adAllowHosts.firstOrNull { h == it || h.endsWith(".$it") } ?: h
                    } else AdBlock.baseDomain(host.lowercase()), !allowed)
                    Session.browser?.reload()
                    refresh()
                }
            }
            c.addView(cardNote(L.t("使用 EasyList、EasyPrivacy、EasyList China（和 uBlock Origin 相同），每 7 天自動更新。YouTube 影片廣告擋不掉。",
                "使用 EasyList、EasyPrivacy、EasyList China（和 uBlock Origin 相同），每 7 天自动更新。YouTube 视频广告拦不掉。",
                "Uses EasyList, EasyPrivacy and EasyList China (same as uBlock Origin), updated every 7 days. YouTube video ads can't be blocked.")))
        }
        if (scrollToAd) {
            scrollToAd = false
            header.post { listScroll.scrollTo(0, header.top) }
        }
    }

    fun reopenSettings() {
        openMenu(fromPad = false, focusUrl = false)
        showSettings()
    }

    /** 按鍵設定頁：手把圖，點某個按鍵就列出可選的功能。 */
    private fun showKeyMapPage() {
        val p = Session.prefs
        beginPage(ListMode.SETTINGS, 3)
        backLink { showSettings() }
        sectionHeader(L.t("按鍵設定", "按键设置", "Button mapping"),
            L.t("點手把上的按鍵（或用十字鍵選到再按確定）來更改功能", "点手柄上的按键（或用十字键选中再按确定）来更改功能",
                "Tap a button (or select it with the D-pad and press OK) to change it"))
        val km = p.keyMap()
        val cursorLabel = L.t("游標", "光标", "Cursor")
        val scrollLabel = L.t("捲動", "滚动", "Scroll")
        val diagram = PadDiagram(
            ctx,
            actionLabel = { code -> (km[code] ?: PadAction.NONE).label },
            leftStickLabel = if (p.swapSticks) scrollLabel else cursorLabel,
            rightStickLabel = if (p.swapSticks) cursorLabel else scrollLabel,
            makeButton = { text, onClick -> btn(text, onClick) },
            onPick = { code, name -> showKeyPicker(code, name) },
        )
        listBox.addView(diagram, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), dp(4), dp(4), dp(10))
        })

        val c = card()
        toggleRow(c, "swap", L.t("左右搖桿功能交換", "左右摇杆功能交换", "Swap sticks"),
            if (p.swapSticks) L.t("左＝捲動、右＝游標", "左＝滚动、右＝光标", "Left = scroll, right = cursor")
            else L.t("左＝游標、右＝捲動", "左＝光标、右＝滚动", "Left = cursor, right = scroll"), p.swapSticks) {
            p.swapSticks = !p.swapSticks
            Session.gamepad.reloadMap()
            refreshHelp()
            showKeyMapPage()
        }
        row(c, "reload", L.t("按鍵恢復預設", "按键恢复默认", "Reset to default"), null) {
            p.resetKeyMap()
            Session.gamepad.reloadMap()
            refreshHelp()
            flash(L.t("按鍵已恢復預設", "按键已恢复默认", "Buttons reset"))
            showKeyMapPage()
        }
        Motion.stagger(listBox)
        if (!listBox.isInTouchMode) diagram.getChildAt(0)?.requestFocusFromTouch()
    }

    /** 下屏模式/比例改變：直接套用（不重建、網頁不重新載入），再回到設定頁的「下屏顯示模式」位置。 */
    private fun applyModeAndReturn() {
        Session.main.post {
            Session.applyBottomMode()
            Session.ui?.reopenDisplayModeSettings()
        }
    }

    /** 下屏模式的使用建議（按鍵名稱依目前的按鍵設定）。 */
    private fun bottomModeTip(): String {
        val k = Session.gamepad.keysFor(PadAction.TOGGLE_BOTTOM_MODE)
        return L.t("「放大裁切」和上屏對齊，適合漫畫、小說；「完整顯示」看得到整個網頁寬度，看 YouTube 留言用這個。",
            "「放大裁切」和上屏对齐，适合漫画、小说；「完整显示」看得到整个网页宽度，看 YouTube 评论用这个。",
            "Zoom & crop lines up with the top screen (comics, novels); Fit shows the full page width (YouTube comments).") +
            if (k.isNotEmpty()) L.t("隨時按 $k 切換。", "随时按 $k 切换。", " Press $k to switch anytime.") else ""
    }

    /** 切換下屏模式（放大裁切 ⇄ 完整顯示）並顯示提示。設定頁的按鈕和 Select 鍵共用。 */
    fun toggleBottomMode() {
        val p = Session.prefs
        p.bottomCrop = !p.bottomCrop
        refreshSideMode()
        flash(if (p.bottomCrop) L.t("下屏：放大裁切", "下屏：放大裁切", "Bottom: zoom & crop")
        else L.t("下屏：完整顯示", "下屏：完整显示", "Bottom: fit"))
    }

    private var scrollToSide = false

    fun reopenSideBarSettings() {
        openMenu(fromPad = false, focusUrl = false)
        scrollToSide = true
        showSettings()
    }

    fun reopenDisplayModeSettings() {
        openMenu(fromPad = false, focusUrl = false)
        scrollToMode = true
        showSettings()
    }

    private fun scrollListToTop() {
        listScroll.post { listScroll.scrollTo(0, 0) }
    }

    /** 選擇某個按鍵的功能。 */
    private fun showKeyPicker(code: Int, name: String) {
        val p = Session.prefs
        val km = p.keyMap()
        val cur = km[code] ?: PadAction.NONE
        listBox.removeAllViews()
        scrollListToTop()
        backLink { showKeyMapPage() }
        val c = section(L.t("「$name」鍵要做什麼？", "「$name」键要做什么？", "What should \"$name\" do?"),
            caption = L.t("目前：", "当前：", "Now: ") + cur.label)
        var focusTarget: View? = null
        PadAction.entries.forEach { a ->
            val r = row(c, null, a.label, null,
                trailing = if (a == cur) ImageView(ctx).apply { setImageDrawable(Icon("check", T.ACCENT, dp(22))) } else null,
                titleColor = if (a == cur) T.ACCENT else T.TEXT) {
                km[code] = a
                p.saveKeyMap(km)
                Session.gamepad.reloadMap()
                refreshHelp()
                flash("$name → ${a.label}")
                showKeyMapPage()
            }
            if (a == cur) focusTarget = r
        }
        Motion.stagger(listBox)
        // 用手把操作時（不在觸控模式），焦點直接停在目前的功能上
        if (!listBox.isInTouchMode) focusTarget?.requestFocusFromTouch()
    }

    // ---------------- 關於 ----------------

    private fun showAbout() {
        beginPage(ListMode.SETTINGS, 3)
        backLink { showSettings() }

        // 品牌 logo + App 名稱與版本
        val head = hRow()
        head.setPadding(dp(8), dp(8), dp(8), dp(12))
        ScreenView.logo(ctx)?.let { bmp ->
            head.addView(ImageView(ctx).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                setMargins(0, 0, dp(18), 0)
            })
        }
        val title = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(ctx).apply {
            text = appName; setTextColor(T.TEXT); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        })
        val vr = hRow().apply { setPadding(0, dp(6), 0, 0) }
        vr.addView(chip(L.t("版本 ", "版本 ", "Version ") + UpdateChecker.currentVersion(), T.ACCENT))
        vr.addView(TextView(ctx).apply { text = "  by CYT Workshop"; setTextColor(T.TEXT3); textSize = 14f })
        title.addView(vr)
        head.addView(title)
        listBox.addView(head)

        // 聯絡與更新
        if (AppInfo.ITCH_URL.isNotBlank() || AppInfo.SUPPORT_EMAIL.isNotBlank() || UpdateChecker.enabled) {
            val c = section(L.t("支援", "支持", "Support"))
            if (AppInfo.ITCH_URL.isNotBlank()) row(c, "globe", L.t("itch.io 頁面", "itch.io 页面", "itch.io page"), null, chevron = true) { openUrl(AppInfo.ITCH_URL) }
            if (AppInfo.SUPPORT_EMAIL.isNotBlank()) row(c, "mail", L.t("聯絡客服", "联系客服", "Contact"), AppInfo.SUPPORT_EMAIL, chevron = true) { sendEmail() }
            if (UpdateChecker.enabled) row(c, "download", L.t("檢查更新", "检查更新", "Check for updates"), null) {
                flash(L.t("檢查中…", "检查中…", "Checking…"))
                UpdateChecker.check(force = true) { r ->
                    flash(when (r) {
                        true -> L.t("有新版本 ", "有新版本 ", "New version ") + Session.prefs.latestVersion
                        false -> L.t("已是最新版本", "已是最新版本", "You're up to date")
                        null -> L.t("無法檢查更新，請確認網路連線", "无法检查更新，请确认网络连接", "Couldn't check — please check your connection")
                    })
                    showAbout()
                }
            }
        }

        val pr = section(L.t("隱私", "隐私", "Privacy"))
        pr.addView(cardNote(L.t(
            "本 App 不蒐集、不上傳任何個人資料，也沒有廣告或分析追蹤。瀏覽紀錄、Cookie、書籤與設定都只存在這台裝置上。檢查更新時只會向 itch.io 查詢最新的版本號碼。你造訪的網站有它們自己的隱私政策。",
            "本应用不收集、不上传任何个人数据，也没有广告或分析追踪。浏览记录、Cookie、书签和设置都只保存在这台设备上。检查更新时只会向 itch.io 查询最新的版本号。你访问的网站有它们自己的隐私政策。",
            "This app does not collect or upload any personal data, and has no ads or analytics. History, cookies, bookmarks and settings stay on this device. Update checks only ask itch.io for the latest version number. Websites you visit have their own privacy policies.",
        )))

        val lic = section(L.t("開放原始碼授權", "开源许可", "Open-source licenses"))
        lic.addView(cardNote(
            "AndroidX WebKit — Apache License 2.0\n" +
                "Kotlin Standard Library — Apache License 2.0\n" +
                "https://www.apache.org/licenses/LICENSE-2.0\n\n" +
                L.t("網頁顯示由系統的 Android System WebView（Chromium）提供。",
                    "网页显示由系统的 Android System WebView（Chromium）提供。",
                    "Web rendering is provided by the system's Android System WebView (Chromium).") + "\n\n" +
                L.t("PDF 閱讀器使用：", "PDF 阅读器使用：", "The PDF viewer uses:") + "\n" +
                "pdf.js © Mozilla Foundation — Apache License 2.0\n" +
                "OpenJPEG — BSD 2-Clause\n" +
                "qcms © Mozilla — MIT\n" +
                "PDFium JBIG2 decoder © The PDFium Authors — BSD 3-Clause\n" +
                "Foxit fonts © Foxit Software — BSD-style\n" +
                "Liberation fonts © Red Hat — SIL Open Font License 1.1\n\n" +
                L.t("Retroid 與 Retroid Pocket 為其所有者的商標；本 App 為第三方作品，與 Retroid 無關。",
                    "Retroid 与 Retroid Pocket 为其所有者的商标；本应用为第三方作品，与 Retroid 无关。",
                    "Retroid and Retroid Pocket are trademarks of their respective owners. This is an independent app, not affiliated with Retroid.") +
                "\n\n© CYT Workshop"
        ))
        // 授權全文（App 內附的檔案）
        val base = "https://appassets.androidplatform.net/pdfjs/"
        listOf(
            "pdf.js (Apache 2.0)" to "LICENSE",
            "OpenJPEG" to "web/wasm/LICENSE_OPENJPEG",
            "qcms" to "web/wasm/LICENSE_QCMS",
            "PDFium JBIG2" to "web/wasm/LICENSE_JBIG2",
            "Foxit fonts" to "web/standard_fonts/LICENSE_FOXIT",
            "Liberation fonts" to "web/standard_fonts/LICENSE_LIBERATION",
        ).forEach { (n, p) ->
            row(lic, "info", n, L.t("授權全文", "许可全文", "Full license text"), chevron = true) { openUrl(base + p) }
        }
        Motion.stagger(listBox)
    }

    private fun openUrl(url: String) {
        // 自己的頁面直接在瀏覽器裡開（就是這個 App）
        Session.browser?.newTab(url, switch = true)
        closeAll()
    }

    private fun sendEmail() {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + AppInfo.SUPPORT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "$appName ${UpdateChecker.currentVersion()}")
        try {
            Session.startExternal(i)
        } catch (e: Exception) {
            flash(AppInfo.SUPPORT_EMAIL)
        }
    }

    // ---------------- 第一次使用教學 ----------------

    /** 下屏鍵盤設定：讀取 RP Duo 雙屏助手的「鍵盤固定在下屏」（讀不到時回傳 null）。 */
    private fun imeOnBottomEnabled(): Boolean? = try {
        val v = Settings.System.getInt(ctx.contentResolver, "ime_show_on_second", -1)
        if (v < 0) null else v == 1
    } catch (e: Exception) {
        null
    }

    /** 設定頁和教學共用：「鍵盤固定在下螢幕」的狀態；沒開時才顯示怎麼開。 */
    private fun addImeRow(c: LinearLayout) {
        val ime = imeOnBottomEnabled()
        val howTo = L.t("在 RP Duo 的「雙屏助手」開啟「鍵盤固定在下螢幕」，打字時鍵盤就不會擋住上屏",
            "在 RP Duo 的「双屏助手」打开「键盘固定在下屏幕」，打字时键盘就不会挡住上屏",
            "Turn on \"keyboard on bottom screen\" in the RP Duo dual-screen assistant so the keyboard never covers the top screen")
        row(c, "keyboard", L.t("鍵盤固定在下螢幕", "键盘固定在下屏幕", "Keyboard on bottom screen"),
            if (ime == true) null else howTo,
            trailing = when (ime) {
                true -> valueText(L.t("已開啟", "已开启", "On"), T.ACCENT)
                false -> valueText(L.t("未開啟", "未开启", "Off"), T.WARN)
                null -> valueText(L.t("無法讀取", "无法读取", "Unknown"), T.TEXT3)
            })
    }

    fun showTutorial(page: Int) {
        if (panel.visibility != View.VISIBLE) {
            closeEditor(blur = false)
            refreshHelp()
            showPanel()
        }
        beginPage(ListMode.SETTINGS, 3)
        val pages = 3
        val n = page.coerceIn(0, pages - 1)

        // 第一列：進度點＋上一步／略過／下一步（按鈕放上面，整頁不用捲動也看得到）
        val nav = hRow().apply { setPadding(dp(8), 0, dp(4), 0) }
        for (k in 0 until pages) nav.addView(View(ctx).apply {
            background = ShellDrawable(density, if (k == n) T.ACCENT else T.EDGE_STRONG, 999f)
        }, LinearLayout.LayoutParams(if (k == n) dp(22) else dp(8), dp(8)).apply { rightMargin = dp(6) })
        nav.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (n > 0) nav.addView(btn(L.t("上一步", "上一步", "Back"), icon = "back") { showTutorial(n - 1) })
        if (n < pages - 1) {
            nav.addView(btn(L.t("略過", "跳过", "Skip")) { finishTutorial() })
            nav.addView(primaryBtn(L.t("下一步", "下一步", "Next"), icon = "forward") { showTutorial(n + 1) })
        } else {
            nav.addView(primaryBtn(L.t("開始使用", "开始使用", "Start browsing"), icon = "check") { finishTutorial() })
        }
        listBox.addView(nav)

        when (n) {
            0 -> {
                val head = hRow()
                head.setPadding(dp(8), dp(2), dp(8), dp(8))
                // App 圖示（紫透色機身＋跨屏地球），圓角方形
                head.addView(ImageView(ctx).apply {
                    // 跟著目前主題的 App 圖示
                    val res = ctx.resources.getIdentifier("ic_launcher_" + T.base.id, "drawable", ctx.packageName)
                    setImageDrawable(ctx.getDrawable(if (res != 0) res else R.drawable.ic_launcher))
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, 22f * density)
                        }
                    }
                    clipToOutline = true
                }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(0, 0, dp(16), 0) })
                head.addView(TextView(ctx).apply {
                    text = L.t("歡迎使用", "欢迎使用", "Welcome to ") + appName
                    setTextColor(T.TEXT); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
                })
                listBox.addView(head)
                addThemePicker(inTutorial = true)
                val c = card()
                row(c, "touch", L.t("一個網頁橫跨上下兩個螢幕", "一个网页横跨上下两个屏幕", "One page spans both screens"),
                    L.t("兩個螢幕都可以觸控、滑動、雙指縮放", "两个屏幕都可以触控、滑动、双指缩放", "Touch, swipe and pinch on either screen"))
                row(c, "menu", L.t("下屏右下角 ☰ 打開選單", "下屏右下角 ☰ 打开菜单", "☰ at the bottom-right opens the menu"),
                    L.t("網址、分頁、書籤、歷史、設定（手把按 Start）", "网址、标签页、书签、历史、设置（手柄按 Start）", "URL, tabs, bookmarks, history, settings (or press Start)"))
                row(c, "hinge", L.t("上屏回到桌面時，下屏自己繼續瀏覽", "上屏回到桌面时，下屏自己继续浏览", "If the top screen goes home, the bottom keeps browsing"),
                    L.t("再從上屏開啟 App 就恢復雙屏", "再从上屏打开应用就恢复双屏", "Open the app on the top screen again to span both"))
                if (Session.splitMode) row(c, "warn", L.t("這台裝置只有一個螢幕", "这台设备只有一个屏幕", "This device has one screen"),
                    L.t("目前以「單螢幕上下分割」模擬雙屏", "目前以「单屏上下分割」模拟双屏", "Running in split-screen simulation mode"), titleColor = T.WARN)
            }
            1 -> {
                sectionHeader(L.t("按鍵操作", "按键操作", "Controls"),
                    L.t("可在 設定 → 手把 → 按鍵設定 更改", "可在 设置 → 手柄 → 按键设置 更改", "Change them in Settings → Controller → Button mapping"))
                val c = card()
                val list = controlsList()
                var line: LinearLayout? = null
                list.forEachIndexed { i, (k, what) ->
                    if (i % 2 == 0) { line = hRow(); c.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
                    val cell = hRow().apply { setPadding(dp(14), dp(7), dp(8), dp(7)) }
                    cell.addView(chip(k, T.TEXT2))
                    cell.addView(TextView(ctx).apply { text = what; textSize = 14f; setTextColor(T.TEXT); setPadding(dp(10), 0, 0, 0); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
                    line!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val tips = card()
                row(tips, "up", L.t("把網頁往上推", "把网页往上推", "Push the page up"),
                    L.t("固定頁面可從上屏往上滑，移開被轉軸切到的內容", "固定页面可从上屏往上滑，移开被转轴挡住的内容",
                        "Swipe up on the top screen to move a fixed page away from the hinge"))
                row(tips, "hinge", L.t("下屏顯示模式", "下屏显示模式", "Bottom screen mode"), bottomModeTip())
            }
            2 -> {
                sectionHeader(L.t("打字與音量", "打字与音量", "Typing & volume"))
                val c = card()
                addImeRow(c)
                row(c, "volume", L.t("音量鍵只調整上螢幕", "音量键只调整上屏幕", "Volume keys change the top screen"),
                    L.t("下螢幕的音量請在雙屏助手裡調整", "下屏幕的音量请在双屏助手里调整", "Adjust the bottom-screen volume in the dual-screen assistant"))
            }
        }

        Motion.stagger(listBox)
        if (!listBox.isInTouchMode) nav.getChildAt(nav.childCount - 1)?.requestFocusFromTouch()
    }

    private fun finishTutorial() {
        Session.prefs.tutorialDone = true
        closeAll()
    }

    // ---------------- 小工具 ----------------

    private fun moveFocus(root: ViewGroup, dir: Int) {
        val f = root.findFocus()
        if (f == null) {
            firstFocusable(root)?.requestFocusFromTouch(); return
        }
        val next = f.focusSearch(dir) ?: return
        if (isDescendant(next, root)) next.requestFocusFromTouch()
    }

    private fun isDescendant(v: View, root: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    private fun firstFocusable(root: ViewGroup): View? =
        root.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown }

    /**
     * 叫出鍵盤。視窗剛取得焦點時（例如剛把上屏切成焦點螢幕）第一次要求可能會被忽略，
     * 所以隔一小段時間再要求幾次，直到鍵盤真的出現。
     */
    private fun showIme(v: View) {
        val imm = v.context.getSystemService(InputMethodManager::class.java)
        fun attempt() {
            if (editPanel.visibility != View.VISIBLE && v === editField) return
            if (!v.hasFocus()) v.requestFocusFromTouch()
            if (Build.VERSION.SDK_INT >= 30) v.windowInsetsController?.show(WindowInsets.Type.ime())
            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
        fun shown() = Build.VERSION.SDK_INT >= 30 &&
            v.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        v.post { attempt() }
        listOf(250L, 600L, 1200L).forEach { d -> v.postDelayed({ if (!shown()) attempt() }, d) }
    }

    private fun hideIme() {
        listOf(editField, panel).forEach { w ->
            val token = w.windowToken ?: return@forEach
            w.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(token, 0)
        }
    }

    // ---------------- 樣式（V2：顏色都來自 Theme.kt 的 T） ----------------

    /** 一般按鈕／膠囊的底：按下變亮、手把焦點亮紫外框。 */
    private fun pillBg(radius: Float = 14f, d: Float = density): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(d, T.SURFACE_HI, radius, T.EDGE_STRONG))
        addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(d, T.SURFACE_HI, radius, T.FOCUS, 2.5f))
        addState(intArrayOf(), ShellDrawable(d, T.SURFACE, radius, T.EDGE))
    }

    private fun btn(text: String, onClick: () -> Unit): Button = btn(text, ctx, onClick = onClick)

    private fun btn(text: String, c: Context = ctx, icon: String? = null, small: Boolean = false, onClick: () -> Unit): Button = Button(c).apply {
        val d = c.resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        this.text = text
        isAllCaps = false
        setTextColor(T.TEXT)
        textSize = if (small) 13f else 15f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(px(if (small) 12 else 16), px(if (small) 7 else 10), px(if (small) 12 else 16), px(if (small) 7 else 10))
        if (icon != null) {
            compoundDrawablePadding = px(8)
            setCompoundDrawablesRelative(Icon(icon, T.TEXT, px(if (small) 16 else 20)), null, null, null)
        }
        background = pillBg(999f, d)
        stateListAnimator = null
        setOnClickListener { onClick() }
        Motion.polish(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px(4), px(4), px(4), px(4))
        }
    }

    /** 主要動作（一頁最多一個）：實心薰衣草紫。 */
    private fun primaryBtn(text: String, c: Context = ctx, icon: String? = null, onClick: () -> Unit): Button = btn(text, c, icon, onClick = onClick).apply {
        val d = c.resources.displayMetrics.density
        setTextColor(T.ON_ACCENT)
        typeface = Typeface.DEFAULT_BOLD
        (compoundDrawablesRelative[0] as? Icon)?.tint(T.ON_ACCENT)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(d, T.ACCENT_DEEP, 999f))
            addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(d, T.ACCENT, 999f, T.FOCUS, 3f))
            addState(intArrayOf(), ShellDrawable(d, T.ACCENT, 999f))
        }
    }

    /** 圓形圖示按鈕。quiet = 平常沒有底色（卡片裡的小 ✕）。 */
    private fun iconBtn(kind: String, desc: String, c: Context = ctx, sizeDp: Int = 48, iconDp: Int = 22, quiet: Boolean = false, onClick: () -> Unit): ImageView =
        ImageView(c).apply {
            val d = c.resources.displayMetrics.density
            setImageDrawable(Icon(kind, if (quiet) T.TEXT2 else T.TEXT, (iconDp * d).roundToInt()))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(d, T.SURFACE_HI, 999f, T.EDGE_STRONG))
                addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(d, T.SURFACE_HI, 999f, T.FOCUS, 2.5f))
                addState(intArrayOf(), if (quiet) ShellDrawable(d, Color.TRANSPARENT, 999f) else ShellDrawable(d, T.SURFACE, 999f, T.EDGE))
            }
            setOnClickListener { onClick() }
            Motion.polish(this)
            val px = (sizeDp * d).roundToInt()
            layoutParams = LinearLayout.LayoutParams(px, px).apply { val m = (4 * d).roundToInt(); setMargins(m, m, m, m) }
        }

    /** 小標籤（狀態、數值）。 */
    private fun chip(text: String, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(color)
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = ShellDrawable(density, (color and 0x00FFFFFF) or 0x24000000, 999f, (color and 0x00FFFFFF) or 0x55000000)
    }

    private fun luma(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** 列右邊顯示的值（不是按鈕，所以沒有外框）。 */
    private fun valueText(text: String, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(color)
        isSingleLine = true
        setPadding(dp(8), 0, dp(4), 0)
    }

    /** 兩個以上選一個的小膠囊（例如下屏模式）。 */
    private fun choice(text: String, icon: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        isSingleLine = true
        setTextColor(if (selected) T.ON_ACCENT else T.TEXT2)
        if (selected) typeface = Typeface.DEFAULT_BOLD
        compoundDrawablePadding = dp(8)
        setCompoundDrawablesRelative(Icon(icon, if (selected) T.ON_ACCENT else T.TEXT2, dp(18)), null, null, null)
        setPadding(dp(16), dp(11), dp(18), dp(11))
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, if (selected) T.ACCENT else T.SURFACE_HI, 999f, T.FOCUS, 2.5f))
            addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 999f, T.EDGE_STRONG))
            addState(intArrayOf(), ShellDrawable(density, if (selected) T.ACCENT else 0x0AFFFFFF, 999f, if (selected) 0 else T.EDGE))
        }
        setOnClickListener { onClick() }
        Motion.polish(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
    }

    private fun editText(c: Context = ctx): EditText = EditText(c).apply {
        val d = c.resources.displayMetrics.density
        setTextColor(T.TEXT)
        setHintTextColor(T.TEXT3)
        highlightColor = T.withAlpha(T.ACCENT_DEEP, 0x66)
        textSize = 17f
        setPadding((16 * d).roundToInt(), (12 * d).roundToInt(), (16 * d).roundToInt(), (12 * d).roundToInt())
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(d, 0x40000000, 14f, T.ACCENT, 2f))
            addState(intArrayOf(), ShellDrawable(d, 0x40000000, 14f, T.EDGE))
        }
    }

    private fun label(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(T.TEXT)
        textSize = 15f
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    /** 卡片裡的說明段落。 */
    private fun cardNote(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(T.TEXT3)
        textSize = 13f
        setLineSpacing(0f, 1.25f)
        setPadding(dp(18), dp(10), dp(18), dp(14))
    }

    private fun hRow() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 區塊標題（小、紫、字距寬），可帶一行說明或右邊的按鈕。回傳標題那一列（捲動定位用）。 */
    private fun sectionHeader(title: String, caption: String? = null, trailing: View? = null): View {
        // 頁面第一個標題不用再空一大段（清單本身已經有上邊距）
        val topPad = if (listBox.childCount == 0) 4 else 18
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(topPad), dp(10), dp(6)) }
        val r = hRow()
        r.addView(TextView(ctx).apply {
            text = title; textSize = 13f; setTextColor(T.ACCENT); letterSpacing = 0.12f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) { (trailing.parent as? ViewGroup)?.removeView(trailing); r.addView(trailing) }
        wrap.addView(r)
        if (caption != null) wrap.addView(TextView(ctx).apply { text = caption; textSize = 13f; setTextColor(T.TEXT3); setPadding(0, dp(2), 0, 0) })
        listBox.addView(wrap)
        return wrap
    }

    /** 卡片：同一組的列放在一起，半透明紫底＋亮邊。 */
    private fun card(): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ShellDrawable(density, T.SURFACE, 20f, T.EDGE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false; clipToPadding = false
        }
        listBox.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), dp(4), dp(4), dp(6))
        })
        return c
    }

    private fun section(title: String, caption: String? = null, trailing: View? = null): LinearLayout {
        sectionHeader(title, caption, trailing)
        return card()
    }

    /** 動作按鈕列（放在卡片裡或清單裡）。 */
    private fun actionsRow(parent: LinearLayout = listBox): LinearLayout {
        val r = hRow().apply { setPadding(dp(8), dp(10), dp(8), dp(8)) }
        parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    /** 子頁面左上角的「‹ 設定」。 */
    private fun backLink(onClick: () -> Unit) {
        val r = hRow()
        r.addView(btn(L.t("設定", "设置", "Settings"), icon = "back", small = true, onClick = onClick))
        listBox.addView(r)
    }

    /** 空白頁：大圖示＋一句話。 */
    private fun emptyState(icon: String, title: String, caption: String) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(56), dp(16), dp(24)) }
        box.addView(ImageView(ctx).apply {
            setImageDrawable(Icon(icon, T.ACCENT, dp(40)))
            scaleType = ImageView.ScaleType.CENTER
            background = ShellDrawable(density, T.SURFACE, 999f, T.EDGE)
        }, LinearLayout.LayoutParams(dp(84), dp(84)))
        box.addView(TextView(ctx).apply { text = title; textSize = 18f; setTextColor(T.TEXT); setPadding(0, dp(16), 0, dp(4)); gravity = Gravity.CENTER })
        box.addView(TextView(ctx).apply { text = caption; textSize = 14f; setTextColor(T.TEXT3); gravity = Gravity.CENTER })
        listBox.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * 卡片裡的一列：左邊圖示，中間標題＋一行說明，右邊控制項（開關、數值、›）。
     * 有 onClick 時整列可以點、手把可以選到。
     */
    private fun row(
        c: LinearLayout, icon: String?, title: String, caption: String?,
        trailing: View? = null, chevron: Boolean = false,
        titleColor: Int = T.TEXT, captionColor: Int = T.TEXT3,
        onClick: (() -> Unit)? = null,
    ): View {
        if (c.childCount > 0 && c.getChildAt(c.childCount - 1).tag != "note") c.addView(View(ctx).apply { setBackgroundColor(T.GRID) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(dp(if (icon != null) 58 else 18), 0, dp(14), 0) })
        val r = hRow().apply {
            minimumHeight = dp(60)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            if (onClick != null) {
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ShellDrawable(density, T.SURFACE_HI, 16f))
                    addState(intArrayOf(android.R.attr.state_focused), ShellDrawable(density, T.SURFACE_HI, 16f, T.FOCUS, 2.5f))
                    addState(intArrayOf(), ShellDrawable(density, Color.TRANSPARENT, 16f))
                }
                setOnClickListener { onClick() }
                Motion.polish(this)
            }
        }
        if (icon != null) r.addView(ImageView(ctx).apply { setImageDrawable(Icon(icon, T.TEXT2, dp(22))) },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(16) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = title; textSize = 16f; setTextColor(titleColor)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        if (!caption.isNullOrEmpty()) texts.addView(TextView(ctx).apply {
            text = caption; textSize = 13f; setTextColor(captionColor)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            (trailing.parent as? ViewGroup)?.removeView(trailing)
            r.addView(trailing, (trailing.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (chevron) r.addView(ImageView(ctx).apply { setImageDrawable(Icon("chevron", T.TEXT3, dp(20))) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) })
        c.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    /** 開關列：點整列，開關先滑過去，動畫跑完再套用（套用常會重建頁面）。 */
    private fun toggleRow(c: LinearLayout, icon: String, title: String, caption: String?, on: Boolean,
                          captionColor: Int = T.TEXT3, onToggle: () -> Unit): View {
        val tg = ToggleView(ctx, on)
        var busy = false
        return row(c, icon, title, caption, trailing = tg, captionColor = captionColor) {
            if (busy) return@row
            busy = true
            tg.animateTo(!tg.on)
            listBox.postDelayed({ onToggle() }, 220)
        }
    }

    /** 數值列：− 數值 ＋。 */
    private fun valueRow(c: LinearLayout, icon: String, title: String, caption: String?, value: () -> String,
                         minus: () -> Unit, plus: () -> Unit, extra: View? = null) {
        val right = hRow()
        val v = TextView(ctx).apply {
            text = value(); textSize = 15f; setTextColor(T.TEXT); gravity = Gravity.CENTER; minWidth = dp(76)
            typeface = Typeface.DEFAULT_BOLD
        }
        fun bump(dir: Int) {
            v.text = value()
            v.translationY = dir * 6f * density; v.alpha = 0.4f
            v.animate().translationY(0f).alpha(1f).setDuration(T.DUR).setInterpolator(T.EASE).start()
        }
        if (extra != null) right.addView(extra)
        right.addView(iconBtn("minus", "−", sizeDp = 42, iconDp = 18) { minus(); bump(1) })
        right.addView(v)
        right.addView(iconBtn("plus", "＋", sizeDp = 42, iconDp = 18) { plus(); bump(-1) })
        row(c, icon, title, caption, trailing = right)
    }

    companion object {
        /** 無痕模式的選單底色（Chrome 無痕的中性深灰） */
    }
}

/**
 * 「放大／縮小」圖示（類似全螢幕／離開全螢幕）：
 * expand = 四個角往外（放大裁切）；否則四個角往內縮（完整顯示）。
 */
class ZoomIcon(private val expand: Boolean, color: Int, strokePx: Float) : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val inset = paint.strokeWidth
        val l = b.left + inset; val t = b.top + inset; val r = b.right - inset; val btm = b.bottom - inset
        val w = r - l; val h = btm - t
        val arm = w * 0.32f
        path.reset()
        if (expand) {
            path.moveTo(l, t + arm); path.lineTo(l, t); path.lineTo(l + arm, t)
            path.moveTo(r - arm, t); path.lineTo(r, t); path.lineTo(r, t + arm)
            path.moveTo(l, btm - arm); path.lineTo(l, btm); path.lineTo(l + arm, btm)
            path.moveTo(r - arm, btm); path.lineTo(r, btm); path.lineTo(r, btm - arm)
        } else {
            val cx = l + w * 0.36f; val cy = t + h * 0.36f
            val cx2 = r - w * 0.36f; val cy2 = btm - h * 0.36f
            path.moveTo(cx - arm, cy); path.lineTo(cx, cy); path.lineTo(cx, cy - arm)
            path.moveTo(cx2 + arm, cy); path.lineTo(cx2, cy); path.lineTo(cx2, cy - arm)
            path.moveTo(cx - arm, cy2); path.lineTo(cx, cy2); path.lineTo(cx, cy2 + arm)
            path.moveTo(cx2 + arm, cy2); path.lineTo(cx2, cy2); path.lineTo(cx2, cy2 + arm)
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
