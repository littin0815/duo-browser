package com.duo.browser

/**
 * 簡單的 Markdown → HTML（給文字閱讀器排版 .md 檔用）。
 *
 * 安全：所有文字先跳脫再套用 Markdown 規則，Markdown 裡寫的原生 HTML 一律當成文字顯示，不會被執行。
 * 連結只接受 http／https／mailto 和頁內錨點（#…）；圖片只接受 http／https。其他網址顯示成一般文字。
 *
 * 支援：標題（# 和底線式）、段落、強制換行、粗體、斜體、刪除線、行內程式碼、程式碼區塊（``` 或 ~~~）、
 * 引用、項目／編號清單（可巢狀）、待辦清單、表格、分隔線、連結、自動連結、圖片。
 */
object Markdown {

    fun toHtml(src: String): String {
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val sb = StringBuilder(src.length + src.length / 4)
        blocks(lines, sb)
        return sb.toString()
    }

    // ---------------- 區塊 ----------------

    private val heading = Regex("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val hr = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^ {0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private val setextH1 = Regex("^ {0,3}=+\\s*$")
    private val setextH2 = Regex("^ {0,3}-+\\s*$")

    private fun blocks(lines: List<String>, sb: StringBuilder) {
        var i = 0
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isEmpty()) return
            sb.append("<p>")
            para.forEachIndexed { k, l ->
                // 行尾兩個空格（或反斜線）= 強制換行；否則接成同一段
                val hard = l.endsWith("  ") || l.endsWith("\\")
                sb.append(inline(l.trimEnd().removeSuffix("\\").trimStart()))
                if (k < para.size - 1) sb.append(if (hard) "<br>" else "\n")
            }
            sb.append("</p>\n")
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flushPara(); i++; continue }

            // 底線式標題：上一行是段落、這一行是 === 或 ---
            if (para.isNotEmpty() && (setextH1.matches(line) || setextH2.matches(line))) {
                val lvl = if (setextH1.matches(line)) 1 else 2
                val text = para.joinToString(" ") { it.trim() }
                para.clear()
                sb.append("<h").append(lvl).append('>').append(inline(text)).append("</h").append(lvl).append(">\n")
                i++; continue
            }

            val fm = fence.matchEntire(line)
            if (fm != null) {
                flushPara()
                val mark = fm.groupValues[1]
                val lang = fm.groupValues[2]
                i++
                sb.append("<pre class=code")
                if (lang.isNotEmpty()) sb.append(" data-lang='").append(esc(lang)).append("'")
                sb.append("><code>")
                var first = true
                while (i < lines.size && !lines[i].trimStart().startsWith(mark)) {
                    if (!first) sb.append('\n')
                    sb.append(esc(lines[i])); first = false; i++
                }
                sb.append("</code></pre>\n")
                i++ // 結尾的 ```
                continue
            }

            val hm = heading.matchEntire(line)
            if (hm != null) {
                flushPara()
                val lvl = hm.groupValues[1].length
                sb.append("<h").append(lvl).append('>').append(inline(hm.groupValues[2])).append("</h").append(lvl).append(">\n")
                i++
                continue
            }

            if (hr.matches(line)) { flushPara(); sb.append("<hr>\n"); i++; continue }

            if (line.trimStart().startsWith(">")) {
                flushPara()
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val t = lines[i].trimStart()
                    inner.add(if (t.startsWith(">")) t.removePrefix(">").removePrefix(" ") else t)
                    i++
                }
                sb.append("<blockquote>\n")
                blocks(inner, sb)
                sb.append("</blockquote>\n")
                continue
            }

            if (listItem.matches(line) && (para.isEmpty() || !line.startsWith(" "))) {
                flushPara()
                i = list(lines, i, sb)
                continue
            }

            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                i = table(lines, i, sb)
                continue
            }

            para.add(line)
            i++
        }
        flushPara()
    }

    private fun indentOf(s: String): Int {
        var n = 0
        for (c in s) { if (c == ' ') n++ else if (c == '\t') n += 4 else break }
        return n
    }

    /** 清單：依縮排做巢狀；回傳清單結束後的下一行。 */
    private fun list(lines: List<String>, start: Int, sb: StringBuilder): Int {
        // 堆疊：每一層的（縮排, 是不是編號清單）
        val stack = ArrayList<Pair<Int, Boolean>>()
        var i = start
        var openLi = false
        var blankBefore = false
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { blankBefore = true; i++; continue }
            val m = listItem.matchEntire(line)
            if (m == null) {
                // 清單項目的延續行（有縮排，而且前面沒有空行）
                if (!blankBefore && openLi && indentOf(line) > 0) {
                    sb.append(' ').append(inline(line.trim()))
                    i++; continue
                }
                break
            }
            if (blankBefore && stack.isNotEmpty() && indentOf(line) < stack[0].first) break
            blankBefore = false
            val ind = indentOf(m.groupValues[1])
            val ordered = m.groupValues[2].first().isDigit()
            while (stack.isNotEmpty() && ind < stack.last().first) {
                sb.append("</li></").append(if (stack.last().second) "ol" else "ul").append(">")
                stack.removeAt(stack.size - 1)
                openLi = true
            }
            // 同一層換成另一種清單（項目 ↔ 編號）：結束舊的，開新的
            if (stack.isNotEmpty() && ind == stack.last().first && ordered != stack.last().second) {
                sb.append("</li></").append(if (stack.last().second) "ol" else "ul").append(">")
                stack.removeAt(stack.size - 1)
                openLi = stack.isNotEmpty()
            }
            if (stack.isEmpty() || ind > stack.last().first) {
                if (stack.isNotEmpty() && !openLi) sb.append("<li>")
                val tag = if (ordered) "ol" else "ul"
                sb.append('<').append(tag)
                if (ordered) {
                    val n = m.groupValues[2].dropLast(1).toIntOrNull() ?: 1
                    if (n != 1) sb.append(" start=").append(n)
                }
                sb.append('>')
                stack.add(ind to ordered)
            } else if (openLi) {
                sb.append("</li>")
            }
            var text = m.groupValues[3]
            // 待辦清單：- [ ] 或 - [x]
            val task = Regex("^\\[([ xX])]\\s+(.*)$").matchEntire(text)
            sb.append("<li")
            if (task != null) {
                val done = task.groupValues[1] != " "
                sb.append(" class=task><span class='box").append(if (done) " done" else "").append("'></span>")
                text = task.groupValues[2]
            } else sb.append('>')
            sb.append(inline(text))
            openLi = true
            i++
        }
        while (stack.isNotEmpty()) {
            sb.append("</li></").append(if (stack.last().second) "ol" else "ul").append(">")
            stack.removeAt(stack.size - 1)
        }
        sb.append('\n')
        return i
    }

    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        // 反斜線跳脫的 \| 不算分隔
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '|') { out.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
            k++
        }
        out.add(cur.toString().trim())
        return out
    }

    private fun table(lines: List<String>, start: Int, sb: StringBuilder): Int {
        val head = cells(lines[start])
        val aligns = cells(lines[start + 1]).map {
            when {
                it.startsWith(":") && it.endsWith(":") -> "center"
                it.endsWith(":") -> "right"
                else -> null
            }
        }
        fun cell(tag: String, text: String, col: Int) {
            sb.append('<').append(tag)
            aligns.getOrNull(col)?.let { sb.append(" style='text-align:").append(it).append("'") }
            sb.append('>').append(inline(text)).append("</").append(tag).append('>')
        }
        sb.append("<div class=tbl><table><thead><tr>")
        head.forEachIndexed { c, t -> cell("th", t, c) }
        sb.append("</tr></thead><tbody>")
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
            sb.append("<tr>")
            val row = cells(lines[i])
            for (c in head.indices) cell("td", row.getOrNull(c) ?: "", c)
            sb.append("</tr>")
            i++
        }
        sb.append("</tbody></table></div>\n")
        return i
    }

    // ---------------- 行內 ----------------

    private val codeSpan = Regex("(`+)(.+?)\\1")
    // 網址可以有一層成對的括號（例如維基百科的網址），其他括號都算結尾
    private const val URL_PART = "((?:[^\\s()]|\\([^\\s()]*\\))+)"
    private val image = Regex("!\\[([^\\]]*)]\\(\\s*$URL_PART(?:\\s+&quot;[^&]*&quot;)?\\s*\\)")
    private val link = Regex("\\[([^\\]]+)]\\(\\s*$URL_PART(?:\\s+&quot;[^&]*&quot;)?\\s*\\)")
    private val autoLink = Regex("&lt;((?:https?://|mailto:)[^\\s&]+(?:&amp;[^\\s&]+)*)&gt;")
    private val bold = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val italicStar = Regex("(?<![*\\w])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![*\\w])")
    private val italicUnder = Regex("(?<![_\\w])_(?=\\S)(.+?)(?<=\\S)_(?![_\\w])")
    private val strike = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    private val escaped = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~>])")

    /** 一行文字 → HTML：先整段跳脫，再套 Markdown 規則（產生的標籤都是我們自己的）。 */
    private fun inline(raw: String): String {
        val holds = ArrayList<String>()
        fun hold(html: String): String { holds.add(html); return "\u0000${holds.size - 1}\u0000" }

        var s = esc(raw)
        // 反斜線跳脫的符號先保護起來
        s = escaped.replace(s) { hold(it.groupValues[1]) }
        s = codeSpan.replace(s) { hold("<code>" + it.groupValues[2].trim() + "</code>") }
        s = image.replace(s) { m ->
            val url = safeUrl(m.groupValues[2], image = true)
            if (url == null) m.value
            else hold("<img src='$url' alt='${m.groupValues[1]}' loading=lazy referrerpolicy=no-referrer>")
        }
        s = link.replace(s) { m ->
            val url = safeUrl(m.groupValues[2], image = false)
            // 標籤先保護起來，後面的粗體／斜體規則才不會動到網址
            if (url == null) m.groupValues[1]
            else hold("<a href='$url' rel='noopener noreferrer'>") + m.groupValues[1] + hold("</a>")
        }
        s = autoLink.replace(s) { m ->
            val url = safeUrl(m.groupValues[1], image = false) ?: return@replace m.value
            hold("<a href='$url' rel='noopener noreferrer'>${m.groupValues[1]}</a>")
        }
        s = bold.replace(s) { "<strong>" + it.groupValues[2] + "</strong>" }
        s = italicStar.replace(s) { "<em>" + it.groupValues[1] + "</em>" }
        s = italicUnder.replace(s) { "<em>" + it.groupValues[1] + "</em>" }
        s = strike.replace(s) { "<del>" + it.groupValues[1] + "</del>" }
        // 還原保護的片段（可能有巢狀，重複幾次）
        repeat(3) {
            s = Regex("\u0000(\\d+)\u0000").replace(s) { holds[it.groupValues[1].toInt()] }
        }
        return s
    }

    /**
     * 網址檢查（傳進來的已經是跳脫過的文字，可以直接放進屬性）。
     * 只放行 http／https（圖片）或 http／https／mailto／#錨點（連結）。
     */
    private fun safeUrl(escapedUrl: String, image: Boolean): String? {
        val plain = escapedUrl.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()
        val lower = plain.lowercase()
        val ok = lower.startsWith("https://") || lower.startsWith("http://") ||
            (!image && (lower.startsWith("mailto:") || plain.startsWith("#")))
        if (!ok) return null
        // 屬性值用單引號包；跳脫過的文字裡已經沒有 ' < >
        return escapedUrl
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '\'' -> sb.append("&#39;")
            '"' -> sb.append("&quot;")
            '\u0000' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
