package com.thousandyearsofwar.ridenotes.model

import java.io.File

/**
 * 笔记里的「文件引用」：通过链接把本地 Markdown / HTML 文件的内容渲染进代码区的块里。
 *
 * 写法与普通链接一致，只是 href 指向本地文件：
 *
 * - `[说明](docs/design.md)`
 * - `<a href="docs/notes.html">说明</a>`
 *
 * 渲染时把文件内容展开成多行文本（Markdown 保留行内标记，交给 [NoteRichText] 渲染；
 * HTML 则先粗略转换成纯文本）。
 */
object NoteEmbeds {

    private const val MAX_FILE_BYTES = 64 * 1024
    private const val MAX_LINES_PER_EMBED = 40

    private val EXTENSIONS = listOf(".md", ".markdown", ".html", ".htm", ".txt")

    fun isEmbedHref(href: String): Boolean {
        if (href.startsWith("anchor:", ignoreCase = true)) return false
        if (href.contains("://") && !href.startsWith("file://", ignoreCase = true)) return false
        val path = href.substringBefore('#').substringBefore('?').lowercase()
        return EXTENSIONS.any { path.endsWith(it) }
    }

    /**
     * 把本地路径解析成绝对文件。
     *
     * 支持三种写法：
     * - 项目相对路径：`diagrams/x.html`
     * - 绝对路径：`E:/foo/x.html`（注意：不能用 `File(base, child)`，
     *   在 Java 里绝对路径会拼到 base 后面）
     * - `file:///E:/foo/x.html`
     */
    fun resolve(basePath: String?, href: String): File? {
        var clean = href.substringBefore('#').substringBefore('?')
        clean = when {
            clean.startsWith("file:///", ignoreCase = true) -> clean.removePrefix("file:///").removePrefix("FILE:///")
            clean.startsWith("file://", ignoreCase = true) -> clean.removePrefix("file://").removePrefix("FILE://")
            else -> clean
        }
        // URL 编码（路径里的 %20 空格等）在 Windows 上很常见
        clean = runCatching {
            java.net.URLDecoder.decode(clean.replace("+", "%2B"), Charsets.UTF_8)
        }.getOrDefault(clean)
        // Windows 上前缀可能是 /E:/...
        if (clean.length > 3 && clean[0] == '/' && clean[2] == ':') clean = clean.substring(1)

        val asIs = File(clean)
        val file = if (asIs.isAbsolute) {
            asIs
        } else {
            val base = basePath ?: return null
            File(base, clean)
        }
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }
    }

    /**
     * 把一行的引用展开成多行；没有引用返回 null。
     */
    fun expandLine(basePath: String?, line: String): List<String>? {
        val links = NoteLinks.parse(line).filter { isEmbedHref(it.href) }
        if (links.isEmpty()) return null

        val out = mutableListOf<String>()
        var cursor = 0
        links.forEach { link ->
            if (link.start > cursor) out.add(line.substring(cursor, link.start))
            val file = resolve(basePath, link.href)
            if (file == null) {
                out.add("（引用文件不存在：${link.href}）")
            } else {
                out.add("📄 ${file.name}")
                val text = runCatching { file.readText() }.getOrDefault("")
                val converted = if (link.href.substringBefore('#').lowercase().endsWith(".html") ||
                    link.href.lowercase().endsWith(".htm")
                ) {
                    htmlToLines(text)
                } else {
                    markdownToLines(text)
                }
                out.addAll(converted.take(MAX_LINES_PER_EMBED))
            }
            cursor = link.end
        }
        if (cursor < line.length) out.add(line.substring(cursor))
        return out
    }

    /** 整段正文按行展开引用。 */
    fun expandBody(basePath: String?, body: String): List<String> {
        val out = mutableListOf<String>()
        body.lines().forEach { line ->
            val expanded = expandLine(basePath, line)
            if (expanded != null) out.addAll(expanded) else out.add(line)
        }
        return out
    }

    /** Markdown → 文本行：去掉块级标记，保留行内标记（由 NoteRichText 渲染）。 */
    private fun markdownToLines(text: String): List<String> {
        val out = mutableListOf<String>()
        var inFence = false
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    inFence = !inFence
                }

                inFence -> out.add("`" + line.trimEnd() + "`")

                line.isBlank() -> out.add("")

                line.matches(Regex("^\\s*([-*_])\\1{2,}\\s*$")) -> out.add("")

                line.startsWith("#") -> out.add(line.trimStart('#').trim())

                line.trimStart().startsWith("> ") -> out.add(line.trimStart().removePrefix("> "))

                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    out.add("• " + line.trimStart().substring(2))

                else -> out.add(line)
            }
        }
        return out.ifEmpty { listOf("（空文件）") }
    }

    /** HTML → 文本行：先粗略去掉块级标签，行内标记留给 NoteRichText。 */
    private fun htmlToLines(text: String): List<String> {
        var s = text
        s = Regex("(?is)<!--.*?-->").replace(s, "")
        s = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(s, "")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</(p|div|li|tr|h[1-6])>").replace(s, "\n")
        s = Regex("(?i)<li[^>]*>").replace(s, "• ")
        s = Regex("(?i)<(p|div|ul|ol|table|tr|h[1-6])[^>]*>").replace(s, "")
        s = Regex("(?i)<[^>]+>").replace(s, "")
        return s.lines()
            .map { it.trim() }
            .filterIndexed { i, l -> l.isNotEmpty() || i > 0 }
            .ifEmpty { listOf("（空文件）") }
    }
}
