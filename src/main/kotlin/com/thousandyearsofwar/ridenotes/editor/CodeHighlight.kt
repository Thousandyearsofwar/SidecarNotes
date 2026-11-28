package com.thousandyearsofwar.ridenotes.editor

import com.intellij.lang.LanguageUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import java.awt.Color
import java.awt.Font

/** 一段已着色的代码文本（同一行内可有多段）。 */
data class CodeToken(
    val text: String,
    val color: Color?,
    val bold: Boolean = false,
    val italic: Boolean = false
)

/**
 * 用 IDE 自己的词法器给笔记里的代码片段上色。
 *
 * 之所以复用宿主文件的 `Language`：C++/C#/JS 等语言的颜色直接取自当前编辑器配色方案，
 * 深色/浅色主题切换后与编辑器里完全一致，不用自己维护关键词表。
 *
 * 只做**词法**着色（关键字/字符串/数字/注释/类型名），不做语义分析；
 * 多行注释与字符串因为整段喂给同一个 Lexer，跨行状态是对的。
 */
object CodeHighlight {

    /**
     * @param blocks 每个元素是一段连续的代码行
     * @return 与 blocks 同构的"每行若干 token"
     */
    fun tokenize(
        project: Project,
        file: VirtualFile?,
        blocks: List<List<String>>
    ): List<List<List<CodeToken>>> {
        if (file == null || blocks.isEmpty()) return plain(blocks)

        return runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<List<List<List<CodeToken>>>> {
                val language = LanguageUtil.getFileLanguage(file) ?: return@runReadAction plain(blocks)
                val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file)
                    ?: return@runReadAction plain(blocks)
                val lexer = highlighter.highlightingLexer
                val scheme = EditorColorsManager.getInstance().globalScheme

                blocks.map { lines ->
                    val out = lines.map { mutableListOf<CodeToken>() }
                    val joined = lines.joinToString("\n")
                    var lineIndex = 0
                    lexer.start(joined)
                    while (true) {
                        val type = lexer.tokenType ?: break
                        val tokenText = joined.substring(lexer.tokenStart, lexer.tokenEnd)
                        val attributes = attributesFor(highlighter, scheme, type)
                        tokenText.split('\n').forEachIndexed { i, part ->
                            if (i > 0) lineIndex++
                            if (part.isNotEmpty() && lineIndex in out.indices) {
                                out[lineIndex].add(
                                    CodeToken(
                                        text = part,
                                        color = attributes?.foregroundColor,
                                        bold = attributes != null && attributes.fontType and Font.BOLD != 0,
                                        italic = attributes != null && attributes.fontType and Font.ITALIC != 0
                                    )
                                )
                            }
                        }
                        lexer.advance()
                    }
                    out
                }
            }
        }.getOrElse { plain(blocks) }
    }

    private fun plain(blocks: List<List<String>>): List<List<List<CodeToken>>> =
        blocks.map { lines -> lines.map { line -> listOf(CodeToken(line, null)) } }

    private fun attributesFor(
        highlighter: com.intellij.openapi.fileTypes.SyntaxHighlighter,
        scheme: com.intellij.openapi.editor.colors.EditorColorsScheme,
        type: com.intellij.psi.tree.IElementType
    ): TextAttributes? {
        val keys: Array<TextAttributesKey> = runCatching { highlighter.getTokenHighlights(type) }
            .getOrDefault(emptyArray())
        keys.forEach { key ->
            val attrs = runCatching { scheme.getAttributes(key) }.getOrNull()
            if (attrs != null) return attrs
        }
        return null
    }
}
