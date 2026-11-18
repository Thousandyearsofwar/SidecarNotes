package com.thousandyearsofwar.ridenotes.model

import java.awt.Color

/**
 * 笔记正文的行内富文本：把 Markdown / HTML 的行内标记解析成"样式片段"。
 *
 * 支持：
 * - Markdown：`**粗**` `*斜*` `` `代码` `` `~~删除~~` `[文字](href)`
 * - HTML：`<b>` `<strong>` `<i>` `<em>` `<u>` `<s>` `<del>` `<code>`
 *         `<a href="...">` `<span style="color:#rrggbb">` `<br>`
 * - 常见实体：`&amp;` `&lt;` `&gt;` `&nbsp;` `&quot;`
 *
 * 无法识别的 `<...>` 按普通文字保留（避免把 `a < b` 吃掉）。
 */
object NoteRichText {

    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val color: Color? = null,
        val href: String? = null
    )

    data class Run(val text: String, val style: Style)

    private val TAG = Regex("</?(b|strong|i|em|u|s|del|strike|code)\\s*/?>", RegexOption.IGNORE_CASE)
    private val LINK_OPEN = Regex(
        "<a\\s+[^>]*?href\\s*=\\s*[\"']?([^\"'\\s>]+)[\"']?[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val LINK_CLOSE = Regex("</a\\s*>", RegexOption.IGNORE_CASE)
    private val SPAN_OPEN = Regex(
        "<span\\s+[^>]*?style\\s*=\\s*[\"'][^\"']*color\\s*:\\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20})[^\"']*[\"'][^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val SPAN_CLOSE = Regex("</span\\s*>", RegexOption.IGNORE_CASE)
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val MD_LINK = Regex("\\[([^\\]\\n]{1,120})]\\(([^)\\s\\n]{1,400})\\)")

    fun parseLine(line: String): List<Run> {
        if (line.isEmpty()) return emptyList()
        val runs = mutableListOf<Run>()
        val buffer = StringBuilder()

        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var code = false
        var color: Color? = null
        var href: String? = null

        fun flush() {
            if (buffer.isEmpty()) return
            runs.add(
                Run(
                    buffer.toString(),
                    Style(bold, italic, underline, strike, code, color, href)
                )
            )
            buffer.setLength(0)
        }

        var i = 0
        while (i < line.length) {
            val rest = line.substring(i)

            when {
                rest.startsWith("**") -> {
                    flush(); bold = !bold; i += 2
                }

                rest.startsWith("~~") -> {
                    flush(); strike = !strike; i += 2
                }

                rest.startsWith("`") -> {
                    flush(); code = !code; i += 1
                }

                rest.startsWith("*") -> {
                    flush(); italic = !italic; i += 1
                }

                rest.startsWith("[") -> {
                    val match = MD_LINK.find(line, i)
                    if (match != null && match.range.first == i) {
                        flush()
                        runs.add(
                            Run(
                                match.groupValues[1],
                                Style(bold, italic, underline = true, strike, code, color, match.groupValues[2])
                            )
                        )
                        i = match.range.last + 1
                    } else {
                        buffer.append('['); i++
                    }
                }

                rest.startsWith("<") -> {
                    val linkOpen = LINK_OPEN.find(line, i)
                    val spanOpen = SPAN_OPEN.find(line, i)
                    val linkClose = LINK_CLOSE.find(line, i)
                    val spanClose = SPAN_CLOSE.find(line, i)
                    val br = BR.find(line, i)
                    val tag = TAG.find(line, i)

                    when {
                        linkOpen != null && linkOpen.range.first == i -> {
                            flush(); href = linkOpen.groupValues[1]; underline = true
                            i = linkOpen.range.last + 1
                        }

                        linkClose != null && linkClose.range.first == i -> {
                            flush(); href = null; underline = false
                            i = linkClose.range.last + 1
                        }

                        spanOpen != null && spanOpen.range.first == i -> {
                            flush(); color = parseColor(spanOpen.groupValues[1])
                            i = spanOpen.range.last + 1
                        }

                        spanClose != null && spanClose.range.first == i -> {
                            flush(); color = null
                            i = spanClose.range.last + 1
                        }

                        br != null && br.range.first == i -> {
                            flush(); buffer.append(' ')
                            i = br.range.last + 1
                        }

                        tag != null && tag.range.first == i -> {
                            flush()
                            val raw = tag.value.lowercase()
                            val closing = raw.startsWith("</")
                            when {
                                "code" in raw -> code = !closing
                                "b" in raw || "strong" in raw -> bold = !closing
                                "i" in raw || "em" in raw -> italic = !closing
                                "u" in raw -> underline = !closing
                                else -> strike = !closing
                            }
                            i = tag.range.last + 1
                        }

                        else -> {
                            // 不是认识的标签：按普通字符处理（还能识别实体）
                            val entity = entityAt(line, i)
                            if (entity != null) {
                                buffer.append(entity.first)
                                i += entity.second
                            } else {
                                buffer.append('<'); i++
                            }
                        }
                    }
                }

                else -> {
                    val entity = entityAt(line, i)
                    if (entity != null) {
                        buffer.append(entity.first)
                        i += entity.second
                    } else {
                        buffer.append(line[i]); i++
                    }
                }
            }
        }
        flush()
        return runs
    }

    /** 纯文字版本（面板预览、tooltip、导出时用）。 */
    fun plain(text: String): String =
        text.split('\n').joinToString("\n") { line ->
            parseLine(line).joinToString("") { it.text }
        }

    private fun parseColor(raw: String): Color? = runCatching {
        if (raw.startsWith("#")) {
            val hex = raw.removePrefix("#")
            when (hex.length) {
                3 -> Color(
                    Integer.parseInt("${hex[0]}${hex[0]}", 16),
                    Integer.parseInt("${hex[1]}${hex[1]}", 16),
                    Integer.parseInt("${hex[2]}${hex[2]}", 16)
                )

                6 -> Color(Integer.parseInt(hex, 16))
                8 -> Color(
                    Integer.parseInt(hex.substring(2), 16),
                    true
                )

                else -> null
            }
        } else {
            namedColor(raw.lowercase())
        }
    }.getOrNull()

    private fun namedColor(name: String): Color? = when (name) {
        "red" -> Color(0xD1, 0x3B, 0x3B)
        "orange" -> Color(0xD1, 0x7A, 0x1F)
        "yellow" -> Color(0xB8, 0x8A, 0x00)
        "green" -> Color(0x2E, 0x8B, 0x4A)
        "blue" -> Color(0x3D, 0x7E, 0xC7)
        "purple" -> Color(0x8A, 0x4F, 0xC0)
        "gray", "grey" -> Color(0x8A, 0x8A, 0x8A)
        else -> null
    }

    private val ENTITIES = listOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&nbsp;" to " "
    )

    /** 当前位置是否是一个 HTML 实体，返回 (字符, 占用长度)。 */
    private fun entityAt(text: String, index: Int): Pair<Char, Int>? {
        if (text[index] != '&') return null
        ENTITIES.forEach { (entity, replacement) ->
            if (text.startsWith(entity, index)) return replacement[0] to entity.length
        }
        return null
    }
}
