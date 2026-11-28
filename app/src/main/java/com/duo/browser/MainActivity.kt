package com.duo.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 桌面圖示 / 外部連結的入口（跳板，不顯示任何畫面）。
 * 在哪個螢幕點圖示，就在那個螢幕開啟瀏覽器畫面；第一次啟動時會同時開另一個螢幕。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        val view = intent?.takeIf { it.action == Intent.ACTION_VIEW }
        val data = view?.data
        if (data?.scheme == "content" || data?.scheme == "file") {
            // 從檔案管理員開啟的 PDF／文字檔：讀取權限只給這個跳板，所以要在 finish() 前先把檔案打開；
            // 複製到暫存在背景做（大檔案不會讓畫面卡住）
            val pdf = Session.isPdfFile(data, view?.type)
            val src = if (pdf) PdfViewer.openUri(data) else TextViewer.openUri(data)
            Session.launch(this, null)
            if (src != null) { if (pdf) Session.openPdf { src } else Session.openText { src } }
            else android.widget.Toast.makeText(this, L.t("無法開啟這個檔案", "无法打开这个文件", "Couldn't open this file"),
                android.widget.Toast.LENGTH_SHORT).show()
        } else {
            Session.launch(this, view?.dataString)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
