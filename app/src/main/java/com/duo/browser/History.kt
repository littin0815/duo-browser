package com.duo.browser

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 瀏覽歷史紀錄：存在 App 自己的資料夾（history.json），不會離開裝置。
 * 同一個網址只保留最近一次，最多 [MAX] 筆，最新的在最前面。
 */
object History {
    data class Entry(var title: String, val url: String, var time: Long)

    private const val MAX = 1000
    private val main = Handler(Looper.getMainLooper())
    private var list: MutableList<Entry>? = null
    private val file: File get() = File(Session.app.filesDir, "history.json")

    private fun items(): MutableList<Entry> {
        list?.let { return it }
        val l = try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optString("t"), o.optString("u"), o.optLong("d"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
        list = l
        return l
    }

    fun all(): List<Entry> = items()

    private fun recordable(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) &&
            // App 內部頁面不記，但 PDF 閱讀器要記（看過的 PDF 也要出現在歷史紀錄）
            (!url.startsWith("https://appassets.androidplatform.net/") || PdfViewer.isViewerUrl(url) || TextViewer.isViewerUrl(url))

    /** 造訪了一個網址（包含 YouTube 這類網頁內換頁）。 */
    fun record(url: String?, title: String?) {
        if (url == null || !recordable(url)) return
        val l = items()
        val old = l.firstOrNull { it.url == url }
        if (old != null) l.remove(old)
        val t = title?.takeIf { it.isNotBlank() && it != url } ?: old?.title ?: ""
        l.add(0, Entry(t, url, System.currentTimeMillis()))
        while (l.size > MAX) l.removeAt(l.size - 1)
        scheduleSave()
    }

    /** 網頁標題出來了：更新最近一筆同網址的紀錄。 */
    fun updateTitle(url: String?, title: String?) {
        if (url == null || title.isNullOrBlank() || title == url) return
        val e = items().firstOrNull { it.url == url } ?: return
        if (e.title == title) return
        e.title = title
        scheduleSave()
    }

    fun remove(e: Entry) {
        items().remove(e)
        scheduleSave()
    }

    fun clear() {
        items().clear()
        scheduleSave()
    }

    // 連續造訪時合併寫入，避免每換一頁就寫檔
    private val saveRunnable = Runnable { save() }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    private fun save() {
        val arr = JSONArray()
        items().forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        val text = arr.toString()
        Thread {
            try {
                val tmp = File(file.parentFile, "history.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            } catch (_: Exception) {}
        }.start()
    }
}
