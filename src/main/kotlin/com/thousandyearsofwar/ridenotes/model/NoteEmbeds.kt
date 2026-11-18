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
}
