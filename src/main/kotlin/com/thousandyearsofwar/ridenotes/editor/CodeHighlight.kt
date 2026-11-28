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

                val facts = SemanticClassifier.facts(project, file)
                val identifierColor = scheme.getAttributes(
                    com.intellij.openapi.editor.DefaultLanguageHighlighterColors.IDENTIFIER
                )?.foregroundColor

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
                    applySemantic(out, facts, scheme, identifierColor)
                    out
                }
            }
        }.getOrElse { plain(blocks) }
    }

    private val IDENTIFIER = Regex("^[A-Za-z_]\\w*$")

    /**
     * 在词法结果上补语义色：
     * - 文件里声明过的类型 / 宏 / 常量 → 用对应 key
     * - 后面紧跟 `(` 的标识符 → 方法
     * - `->` / `.` 后的标识符 → 字段；`::` 后 → 静态字段
     * - 全大写（如 MAX_NUM）→ 常量
     *
     * 只覆盖"词法器当作普通标识符"的 token，不会把关键字改色。
     */
    private fun applySemantic(
        lines: List<MutableList<CodeToken>>,
        facts: SemanticClassifier.Facts,
        scheme: com.intellij.openapi.editor.colors.EditorColorsScheme,
        identifierColor: Color?
    ) {
        fun isIdentifier(token: CodeToken): Boolean =
            IDENTIFIER.matches(token.text) && (token.color == null || token.color == identifierColor)

        lines.forEachIndexed { lineIndex, tokens ->
            tokens.forEachIndexed { i, token ->
                if (!isIdentifier(token)) return@forEachIndexed

                val next = tokens.drop(i + 1).firstOrNull { it.text.isNotBlank() }?.text?.trimStart()
                    ?: lines.getOrNull(lineIndex + 1)?.firstOrNull { it.text.isNotBlank() }?.text?.trimStart()
                val prev = tokens.take(i).lastOrNull { it.text.isNotBlank() }?.text?.trimEnd()
                    ?: lines.getOrNull(lineIndex - 1)?.lastOrNull { it.text.isNotBlank() }?.text?.trimEnd()

                val key = when {
                    next?.startsWith("(") == true ->
                        com.intellij.openapi.editor.DefaultLanguageHighlighterColors.INSTANCE_METHOD

                    prev?.endsWith("::") == true ->
                        com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STATIC_FIELD

                    prev?.endsWith("->") == true || prev?.endsWith(".") == true ->
                        com.intellij.openapi.editor.DefaultLanguageHighlighterColors.INSTANCE_FIELD

                    facts.keys.containsKey(token.text) -> facts.keys[token.text]

                    token.text.length >= 2 && token.text.any { it.isLetter() } &&
                        token.text.all { it.isUpperCase() || it.isDigit() || it == '_' } ->
                        com.intellij.openapi.editor.DefaultLanguageHighlighterColors.CONSTANT

                    else -> null
                } ?: return@forEachIndexed

                val attrs = runCatching { scheme.getAttributes(key) }.getOrNull() ?: return@forEachIndexed
                tokens[i] = token.copy(
                    color = attrs.foregroundColor ?: token.color,
                    bold = token.bold || (attrs.fontType and Font.BOLD != 0),
                    italic = token.italic || (attrs.fontType and Font.ITALIC != 0)
                )
            }
        }
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
