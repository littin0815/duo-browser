package com.duo.browser

/**
 * 發行相關的設定，集中在這裡。在 itch.io 建好頁面、申請好客服信箱後填進來再重新建置。
 * 留空的項目在「關於」頁不會顯示；ITCH_TARGET 留空時不會檢查更新。
 */
object AppInfo {
    /** itch.io 商品頁網址，例如 "https://cyt.itch.io/duo-browser" */
    const val ITCH_URL = "https://cytworkshop.itch.io/duo-browser"

    /** 原始碼（GitHub，GPL-3.0） */
    const val SOURCE_URL = "https://github.com/littin0815/duo-browser"

    /** 客服信箱 */
    const val SUPPORT_EMAIL = "heaps.writers_9@icloud.com"

    /**
     * 檢查更新用：itch.io 的「使用者名稱/遊戲網址名稱」，例如 "cyt/duo-browser"。
     * 必須用 butler 上傳並加上 --userversion，itch.io 才查得到版本號：
     *   butler push DuoBrowser-1.0.0.apk cyt/duo-browser:android --userversion 1.0.0
     */
    const val ITCH_TARGET = "cytworkshop/duo-browser"
    const val ITCH_CHANNEL = "android"
}
