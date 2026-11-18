package com.thousandyearsofwar.ridenotes.model

/**
 * 笔记正文里的链接：支持两种写法
 *
 * - Markdown：`[显示文字](anchor:a_1234abcd)` / `[显示文字](https://example.com)`
 * - HTML：`<a href="https://example.com">显示文字</a>` / `<a href="anchor:a_1234">文字</a>`
 *
 * 只解析"行内链接"，不做完整 Markdown/HTML。位置范围是**在传入字符串里的字符下标**，
 * 便于渲染时按段着色、点击时做命中测试；`href` 原样保留（`anchor:` 前缀表示跳到锚点）。
 */
object NoteLinks {

    data class Link(val start: Int, val end: Int, val text: String, val href: String)

    /** `[文字](目标)` —— 目标不限协议，锚点用 `anchor:` 前缀。 */
    private val MARKDOWN = Regex("\\[([^\\]\\n]{1,120})]\\(([^)\\s\\n]{1,400})\\)")

    /** `<a href="目标">文字</a>` —— href 的引号可省。 */
    private val HTML = Regex(
        "<a\\s+[^>]*?href\\s*=\\s*[\"']?([^\"'\\s>]+)[\"']?[^>]*>([^<]{1,200})</a>",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): List<Link> {
        if (text.isEmpty()) return emptyList()

        val found = mutableListOf<Link>()
        MARKDOWN.findAll(text).forEach { match ->
            found.add(
                Link(
                    start = match.range.first,
                    end = match.range.last + 1,
                    text = match.groupValues[1],
                    href = match.groupValues[2]
                )
            )
        }
        HTML.findAll(text).forEach { match ->
            // 落在 markdown 链接内部的 HTML 片段要跳过（避免重复着色/重叠命中）
            val range = match.range
            if (found.any { range.first >= it.start && range.last < it.end }) return@forEach
            found.add(
                Link(
                    start = range.first,
                    end = range.last + 1,
                    text = match.groupValues[2].trim(),
                    href = match.groupValues[1]
                )
            )
        }
        return found.sortedBy { it.start }
    }

    /** 生成 Markdown 链接片段（锚点用 `anchor:` 前缀）。 */
    fun format(displayText: String, href: String): String {
        val clean = displayText.replace("[", "(").replace("]", ")").take(120)
        return "[$clean]($href)"
    }

    /** 把正文里的链接标记还原成纯显示文字（面板预览、悬停提示用）。 */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        // 从后往前替换，避免下标失效
        parse(text).sortedByDescending { it.start }.forEach { link ->
            result = result.substring(0, link.start) + link.text + result.substring(link.end)
        }
        return result
    }

    /** 生成锚点链接。 */
    fun anchorFormat(displayText: String, anchorId: String): String = format(displayText, "anchor:$anchorId")
}
