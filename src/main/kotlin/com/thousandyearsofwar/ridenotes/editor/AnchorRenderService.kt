package com.thousandyearsofwar.ridenotes.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.thousandyearsofwar.ridenotes.model.Anchor
import com.thousandyearsofwar.ridenotes.model.Anchoring
import com.thousandyearsofwar.ridenotes.service.NoteAnchorService
import com.thousandyearsofwar.ridenotes.ui.NotePopups
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon
import com.thousandyearsofwar.ridenotes.model.NoteLinks

/** 可视区上下各留多少行做块渲染，超出部分只保留色带和 gutter 图标。 */
private const val MARGIN = 200

/**
 * 编辑器视口尺寸。
 *
 * - contentComponent.width/height 是**内容**尺寸（1 万行时高度达 22 万像素）
 * - scrollingModel.visibleArea 在布局期常为 0
 * 所以优先用外层 editor.component 的 visibleRect。
 */
private fun editorViewportSize(editor: com.intellij.openapi.editor.Editor): java.awt.Dimension {
    val visibleArea = runCatching { editor.scrollingModel.visibleArea }.getOrNull()
    val content = runCatching { editor.contentComponent }.getOrNull()
    val outer = runCatching { editor.component?.visibleRect }.getOrNull()

    // 取所有候选里的**最小**宽度：
    // - visibleArea：未被浮动面板遮挡的视口宽
    // - contentComponent.width：内容宽
    // - outer.visibleRect.width：整个编辑器宽度（可能包含被面板盖住的部分，最大）
    // 取最小可以保证块不会画到面板下面去。
    val widths = listOfNotNull(
        visibleArea?.width?.takeIf { it > 0 },
        content?.width?.takeIf { it > 0 },
        outer?.width?.takeIf { it > 0 }
    )
    val heights = listOfNotNull(
        visibleArea?.height?.takeIf { it > 0 },
        outer?.height?.takeIf { it > 0 }
    )
    return java.awt.Dimension(widths.minOrNull() ?: 800, heights.minOrNull() ?: 600)
}

/** 笔记段首的时间戳格式。 */
private val NOTE_STAMP_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())

/** 8 色调色板。 */
object NotePalette {
    private val RGB = intArrayOf(
        0x4FC3F7, 0xFFF176, 0xAED581, 0xFFB74D,
        0xBA68C8, 0x4DB6AC, 0xFF8A80, 0x90A4AE
    )

    /** 标记用的饱和色：gutter 图标、面板色块、颜色菜单、摘要块左侧竖条。 */
    fun accent(colorIndex: Int): Color {
        val rgb = RGB[colorIndex.mod(RGB.size)]
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    /** 内容底用的低饱和色：代码底色带、摘要块填充。用透明度压住，保证文字可读。 */
    private const val FILL_ALPHA = 46

    fun fill(colorIndex: Int): Color {
        val rgb = RGB[colorIndex.mod(RGB.size)]
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, FILL_ALPHA)
    }

    fun background(colorIndex: Int): Color = fill(colorIndex)

    fun blockBackground(colorIndex: Int): Color = fill(colorIndex)

    /** 把半透明填充色压到编辑器背景上，得到不透明色（用于盖住摘要块的编辑框）。 */
    fun opaque(colorIndex: Int, base: Color): Color {
        val tint = fill(colorIndex)
        val alpha = tint.alpha / 255.0
        return Color(
            (tint.red * alpha + base.red * (1 - alpha)).toInt().coerceIn(0, 255),
            (tint.green * alpha + base.green * (1 - alpha)).toInt().coerceIn(0, 255),
            (tint.blue * alpha + base.blue * (1 - alpha)).toInt().coerceIn(0, 255)
        )
    }

    fun solid(colorIndex: Int): Color {
        val rgb = RGB[colorIndex.mod(RGB.size)]
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
}

/** gutter 上的小方块。 */
class NoteGutterIcon(private val colorIndex: Int) : Icon {
    override fun getIconWidth(): Int = 12
    override fun getIconHeight(): Int = 14

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = NotePalette.accent(colorIndex)
            g2.fillRoundRect(x + 1, y + 1, 10, 12, 3, 3)
            g2.color = Color(0x33, 0x33, 0x33, 160)
            g2.drawRoundRect(x + 1, y + 1, 10, 12, 3, 3)
        } finally {
            g2.dispose()
        }
    }
}

/** 行尾的小胶囊。 */
private class LineEndRenderer(private val colorIndex: Int) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 26

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = NotePalette.accent(colorIndex)
            g2.fillRoundRect(targetRegion.x, targetRegion.y + 2, 22, targetRegion.height - 4, 8, 8)
            g2.color = Color(0x33, 0x33, 0x33, 200)
            g2.font = g2.font.deriveFont(Font.BOLD, 11f)
            g2.drawString("N", targetRegion.x + 7, targetRegion.y + targetRegion.height - 5)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 锚点下方的内联摘要（只读，纯绘制）。
 *
 * 标题和该锚点的所有笔记在**同一个块**里；笔记首行右侧右对齐显示时间。
 */
private val LINK_COLOR: java.awt.Color = java.awt.Color(0x3D, 0x7E, 0xC7)

/**
 * 把 tab 展开成空格。
 *
 * AWT 的 `drawString` 不展开制表符（画出来宽度为 0），笔记里用 Tab 缩进时
 * 看起来会"没有缩进"，所以在绘制/命中测试前先统一展开。
 */
internal fun expandTabs(text: String, tabSize: Int = 4): String {
    if ('\t' !in text) return text
    val sb = StringBuilder(text.length + 8)
    var column = 0
    text.forEach { ch ->
        when (ch) {
            '\n' -> {
                sb.append(ch)
                column = 0
            }

            '\t' -> {
                val next = tabSize - (column % tabSize)
                repeat(next) { sb.append(' ') }
                column += next
            }

            else -> {
                sb.append(ch)
                column++
            }
        }
    }
    return sb.toString()
}

private class SummaryBlockRenderer(
    private val anchor: Anchor,
    private val text: String,
    private val indentColumns: Int,
    private val compact: Boolean,
    private val color: Int,
    private val stamps: Map<Int, String>,
    private val projectRef: com.intellij.openapi.project.Project?,
    private val fileRef: com.intellij.openapi.vfs.VirtualFile?
) : EditorCustomElementRenderer {

    private val settings = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get()
    private val font: Font = settings.summaryFont()

    /** 解析后的显示行：codeBlock >= 0 表示落在 ``` 围栏里。 */
    private data class BlockLine(val text: String, val codeBlock: Int, val original: Int)

    private val blockLines: List<BlockLine> by lazy { parseBlockLines() }

    /** 代码行的词法着色结果，按解析后的行号索引。 */
    private val codeLineTokens: Map<Int, List<CodeToken>> by lazy { buildCodeTokens() }

    // 最近一次绘制时的几何信息，供点击命中测试用（paint 一定先于点击发生）



    /** 行文本 → 链接列表缓存，避免每帧重复跑正则。 */
    private val linkCache = HashMap<String, List<NoteLinks.Link>>()

    private fun linksOf(line: String): List<NoteLinks.Link> {
        linkCache[line]?.let { return it }
        if (linkCache.size > 512) linkCache.clear()
        val parsed = NoteLinks.parse(line)
        linkCache[line] = parsed
        return parsed
    }

    fun linkAt(inlay: Inlay<*>, px: Int, py: Int): String? {
        // 按当前布局重算：滚动/缩放/换行后仍与绘制一致
        val bounds = runCatching { inlay.bounds }.getOrNull() ?: return null
        val component = inlay.editor.contentComponent
        val baseMetrics = component.getFontMetrics(font)
        val lines = blockLines
        val lineHeight = baseMetrics.height

        val top = bounds.y
        if (py < top || py > top + lines.size * lineHeight + 8) return null
        val lineIndex = ((py - top - 2) / lineHeight).coerceIn(0, lines.size - 1)
        val blockLine = lines[lineIndex]
        if (blockLine.codeBlock >= 0) return null   // 代码行里的链接不做命中

        val fmCache = HashMap<Font, java.awt.FontMetrics>()
        fun widthOf(value: String): Int =
            FontFallback.segments(font, value).sumOf { (runFont, part) ->
                fmCache.getOrPut(runFont) { component.getFontMetrics(runFont) }.stringWidth(part)
            }

        fun ellipsizeTo(value: String, maxWidth: Int): String {
            if (value.isEmpty() || maxWidth <= 0) return ""
            if (widthOf(value) <= maxWidth) return value
            var low = 0
            var high = value.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (widthOf(value.substring(0, mid) + "…") <= maxWidth) low = mid else high = mid - 1
            }
            return if (low <= 0) "" else value.substring(0, low) + "…"
        }

        val textLeft = bounds.x + indent(inlay) + 8
        var x = textLeft
        var remaining = (bounds.width - indent(inlay) - 24).coerceAtLeast(20)

        for (part in NoteLinks.segments(blockLine.text)) {
            if (remaining <= 0) break
            val visible = ellipsizeTo(part.text, remaining)
            if (visible.isEmpty()) continue
            val partWidth = widthOf(visible)
            if (part.href != null && px in x..(x + partWidth)) return part.href
            x += partWidth
            remaining -= partWidth
        }
        return null
    }
    /**
     * 宽度：默认跟随窗口（贴着右边缘）；设置里填了 >0 才作为上限。
     * 必须用 scrollingModel.visibleArea（视口），contentComponent.width 是内容宽度，横向滚动时会更大。
     */
    /**
     * 视口尺寸。
     *
     * contentComponent 在各种时机下 visibleRect 高度都是 0，
     * 这里统一改为外层 editor.component（真正被 splitter 布局的那个组件）。
     */
    private fun viewportSize(inlay: Inlay<*>): java.awt.Dimension = editorViewportSize(inlay.editor)

    /**
     * 分配宽度：**永远不小于当前视口宽度**。
     *
     * 真正的宽度在 paint 里按设置/视口收紧。这样即使平台在设置变更后没有及时重新分配，
     * 也不会出现"块被 targetRegion 卡窄"的问题（之前就是被卡在旧宽度上）。
     */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val indent = indent(inlay)
        val viewport = editorViewportSize(inlay.editor).width
        val desired = settings.maxWidth.takeIf { it > 0 } ?: viewport
        val allocated = maxOf(desired, viewport).coerceAtLeast(160)
        return indent + allocated + 8
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = metrics(inlay)
        return (blockLines.size * metrics.height + 6).coerceAtLeast(16)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.clip = targetRegion
            g2.font = font

            val metrics = g2.fontMetrics
            val lines = blockLines
            val blockHeight = lines.size * metrics.height + 4

            // 关键：把绘制裁到编辑器的**可见矩形**。
            // 块 inlay 的 targetRegion 可能超出视口（滚出屏幕的块、宽度超出），
            // 而编辑器组件不在 JViewport 里裁剪，超出的部分会直接画到标签栏、工具窗口上——
            // 这就是"笔记透到面板上"。
            val outerVisible = runCatching { inlay.editor.component?.visibleRect }.getOrNull()
            val clip = if (outerVisible != null && outerVisible.width > 0 && outerVisible.height > 0) {
                targetRegion.intersection(outerVisible)
            } else {
                targetRegion
            }
            g2.clip = if (clip.isEmpty) targetRegion else clip

            val left = targetRegion.x + indent(inlay)

            // 真正的宽度在这里决定：设置 >0 用它，0 则跟随视口；永远留 2px 不压住滚动条
            val viewport = viewportSize(inlay).width
            val width = if (viewport > 0) {
                settings.summaryWidth(viewport, indent(inlay))
            } else {
                settings.maxWidth.takeIf { it > 0 } ?: 880
            }
            if (width < 40) return

            g2.color = NotePalette.blockBackground(color)
            g2.fillRoundRect(left, targetRegion.y, width, blockHeight, 6, 6)
            g2.color = NotePalette.accent(color)
            g2.fillRoundRect(left, targetRegion.y, 3, blockHeight, 2, 2)

            var baseline = targetRegion.y + metrics.ascent + 2
            val metricsCache = HashMap<Font, java.awt.FontMetrics>()

            lines.forEachIndexed { index, blockLine ->
                val line = blockLine.text
                val stamp = stamps[blockLine.original].orEmpty()
                val textLeft = left + 8
                val stampedWidth = if (stamp.isEmpty()) {
                    (width - 16).coerceAtLeast(20)
                } else {
                    (width - 16 - metrics.stringWidth(stamp) - 12).coerceAtLeast(20)
                }

                // ``` 围栏里的代码走词法着色；其它行只保留"链接"这一种行内渲染
                var x = textLeft
                var remaining = stampedWidth

                if (blockLine.codeBlock >= 0) {
                    drawCodeLine(g2, codeLineTokens[index].orEmpty(), x, baseline, remaining, metricsCache)
                    if (stamp.isNotEmpty()) {
                        g2.color = Color(0x99, 0x99, 0x99)
                        g2.drawString(stamp, left + width - 8 - metrics.stringWidth(stamp), baseline)
                    }
                    baseline += metrics.height
                    return@forEachIndexed
                }

                for (part in NoteLinks.segments(line)) {
                    if (remaining <= 0) break
                    val visible = ellipsizeWidth(g2, metricsCache, font, part.text, remaining)
                    if (visible.isEmpty()) continue

                    g2.color = if (part.href != null) LINK_COLOR else JBColor.foreground()
                    val startX = x
                    x = drawWithFallback(g2, visible, x, baseline, font, metricsCache)
                    val runWidth = x - startX
                    if (part.href != null && runWidth > 0) {
                        g2.drawLine(startX, baseline + 2, x, baseline + 2)
                    }
                    remaining -= runWidth
                }

                if (stamp.isNotEmpty()) {
                    g2.color = Color(0x99, 0x99, 0x99)
                    g2.drawString(stamp, left + width - 8 - metrics.stringWidth(stamp), baseline)
                }

                baseline += metrics.height
            }

            // 笔记之间画一条细分隔线（第一条之前不画）
            val noteStarts = stamps.keys.sorted()
            if (noteStarts.size > 1) {
                val divider = Color(0x88, 0x88, 0x88, 120)
                g2.color = divider
                noteStarts.drop(1).forEach { index ->
                    val y = targetRegion.y + 2 + index * metrics.height - 2
                    g2.drawLine(left + 8, y, left + width - 8, y)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun metrics(inlay: Inlay<*>): java.awt.FontMetrics =
        inlay.editor.contentComponent.getFontMetrics(font)

    /**
     * 缩进像素：按**当前**编辑器字体把列数换算成像素。
     *
     * 不能只在创建时算一次——编辑器字号缩放（Ctrl+滚轮）后空格宽度会变，
     * 缓存的像素值就会和代码缩进对不上。
     */
    private fun indent(inlay: Inlay<*>): Int {
        if (indentColumns <= 0) return 0
        val editorMetrics = runCatching {
            inlay.editor.contentComponent.getFontMetrics(
                inlay.editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
            )
        }.getOrNull() ?: return 0
        return indentColumns * editorMetrics.charWidth(' ').coerceAtLeast(1)
    }

    /** 把正文解析成"显示行 + 代码块归属"，顺便吃掉 ``` 围栏。 */
    private fun parseBlockLines(): List<BlockLine> {
        val out = mutableListOf<BlockLine>()
        var block = -1
        var nextBlock = 0
        expandTabs(text).split('\n').forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (block < 0) {
                    block = nextBlock++
                } else {
                    block = -1
                }
                return@forEachIndexed
            }
            out.add(BlockLine(line, block, index))
        }
        return out.ifEmpty { listOf(BlockLine("", -1, 0)) }
    }

    /** 用宿主的语法高亮给每个代码块上色，再按解析后的行号摊平。 */
    private fun buildCodeTokens(): Map<Int, List<CodeToken>> {
        val project = projectRef ?: return emptyMap()
        val file = fileRef ?: return emptyMap()

        val blocks = linkedMapOf<Int, MutableList<String>>()
        val lineIndexes = linkedMapOf<Int, MutableList<Int>>()
        blockLines.forEachIndexed { index, line ->
            if (line.codeBlock < 0) return@forEachIndexed
            blocks.getOrPut(line.codeBlock) { mutableListOf() }.add(line.text)
            lineIndexes.getOrPut(line.codeBlock) { mutableListOf() }.add(index)
        }
        if (blocks.isEmpty()) return emptyMap()

        val tokenized = CodeHighlight.tokenize(project, file, blocks.values.toList())
        val out = HashMap<Int, List<CodeToken>>()
        blocks.keys.forEachIndexed { blockIndex, key ->
            val perLine = tokenized.getOrNull(blockIndex).orEmpty()
            val indexes = lineIndexes[key].orEmpty()
            perLine.forEachIndexed { i, tokens ->
                if (i < indexes.size) out[indexes[i]] = tokens
            }
        }
        return out
    }

    /** 画一行着色后的代码，返回结束 x。 */
    private fun drawCodeLine(
        g2: Graphics2D,
        tokens: List<CodeToken>,
        startX: Int,
        baseline: Int,
        maxWidth: Int,
        metricsCache: MutableMap<Font, java.awt.FontMetrics>
    ): Int {
        var x = startX
        var remaining = maxWidth
        for (token in tokens) {
            if (remaining <= 0) break
            val tokenFont = fontForToken(token)
            val visible = ellipsizeWidth(g2, metricsCache, tokenFont, token.text, remaining)
            if (visible.isEmpty()) continue
            g2.color = token.color ?: JBColor.foreground()
            val before = x
            x = drawWithFallback(g2, visible, x, baseline, tokenFont, metricsCache)
            remaining -= (x - before)
        }
        return x
    }

    private fun fontForToken(token: CodeToken): Font {
        var f = font
        if (token.bold) f = f.deriveFont(f.style or Font.BOLD)
        if (token.italic) f = f.deriveFont(f.style or Font.ITALIC)
        return f
    }
    /** 按字体回退逐段绘制，返回结束 x。 */
    private fun drawWithFallback(
        g: Graphics2D,
        text: String,
        startX: Int,
        baseline: Int,
        baseFont: Font,
        metricsCache: MutableMap<Font, java.awt.FontMetrics>
    ): Int {
        if (text.isEmpty()) return startX
        var x = startX
        FontFallback.segments(baseFont, text).forEach { (runFont, part) ->
            g.font = runFont
            g.drawString(part, x, baseline)
            val m = metricsCache.getOrPut(runFont) { g.getFontMetrics(runFont) }
            x += m.stringWidth(part)
        }
        g.font = baseFont
        return x
    }

    /** 把文本截断到最大宽度（按当前字体测量）。 */
    private fun ellipsizeWidth(
        g: Graphics2D,
        metricsCache: MutableMap<Font, java.awt.FontMetrics>,
        baseFont: Font,
        text: String,
        maxWidth: Int
    ): String {
        if (text.isEmpty() || maxWidth <= 0) return ""
        if (widthOf(g, metricsCache, baseFont, text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = text.substring(0, mid) + "…"
            if (widthOf(g, metricsCache, baseFont, candidate) <= maxWidth) low = mid else high = mid - 1
        }
        return if (low <= 0) "" else text.substring(0, low) + "…"
    }

    private fun widthOf(
        g: Graphics2D,
        metricsCache: MutableMap<Font, java.awt.FontMetrics>,
        baseFont: Font,
        text: String
    ): Int {
        var width = 0
        FontFallback.segments(baseFont, text).forEach { (runFont, part) ->
            val m = metricsCache.getOrPut(runFont) { g.getFontMetrics(runFont) }
            width += m.stringWidth(part)
        }
        return width
    }
    /** 二分截断，别用逐字删（长文本会 O(n²) 卡绘制）。 */
    private fun ellipsize(metrics: java.awt.FontMetrics, text: String, maxWidth: Int): String {
        if (text.isEmpty() || metrics.stringWidth(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (metrics.stringWidth(text.substring(0, mid) + "…") <= maxWidth) low = mid else high = mid - 1
        }
        return text.substring(0, low) + "…"
    }

    private companion object {
        const val MAX_LINES = 3
        const val MAX_WIDTH = 880
    }
}

/**
 * 把锚点画到编辑器上：底色带 + gutter 图标 + 行尾按钮 + 行下摘要。
 */
@Service(Service.Level.PROJECT)
class AnchorRenderService(private val project: Project) {

    private val states = mutableMapOf<Editor, EditorState>()

    companion object {
        val ANCHOR_ID: Key<String> = Key.create("ridenotes.anchorId")
        val NOTE_ID: Key<String> = Key.create("ridenotes.noteId")

        fun get(project: Project): AnchorRenderService = project.service()
    }

    fun attach(editor: Editor, file: VirtualFile) {
        if (states.containsKey(editor)) return
        val state = EditorState(editor, file)
        states[editor] = state
        editor.document.addDocumentListener(state.documentListener, project)
        editor.addEditorMouseListener(state.mouseListener)
        editor.addEditorMouseMotionListener(state.motionListener)
        // 延后一帧再画：项目打开瞬间不要同步去建几千个 highlighter
        ApplicationManager.getApplication().invokeLater {
            if (!editor.isDisposed) render(state)
        }
    }

    fun detach(editor: Editor) {
        val state = states.remove(editor) ?: return
        editor.document.removeDocumentListener(state.documentListener)
        editor.removeEditorMouseListener(state.mouseListener)
        runCatching { editor.removeEditorMouseMotionListener(state.motionListener) }
        state.dispose()
        state.clear()
    }

    fun refresh(file: VirtualFile) {
        states.values.filter { it.file == file }.forEach { render(it) }
    }

    /** 丢掉某条锚点的存活位置（重绑后调用），下一次渲染按新行号重算。 */
    fun invalidate(file: VirtualFile, anchorId: String) {
        states.values.filter { it.file == file }.forEach { it.dropAnchor(anchorId) }
        refresh(file)
    }

    fun refreshAll() {
        states.values.forEach { render(it) }
    }

    private fun render(state: EditorState) {
        val startedAt = System.nanoTime()
        val service = NoteAnchorService.get(project)
        val anchors = service.anchorsFor(state.file)
        val document = state.editor.document

        // 记录现有 highlighter 的位置（它们本身就是 RangeMarker，会跟着编辑走）
        val live = state.painted.associate { it.anchor.id to (it.highlighter.startOffset to it.highlighter.endOffset) }
        val desired = LinkedHashMap<String, Pair<Anchor, com.intellij.openapi.util.TextRange>>()

        anchors.forEach { anchor ->
            // 克隆节点只是面板里的别名，不在代码区渲染任何东西
            if (anchor.mirrorOf != null) return@forEach
            if (!service.effectiveVisible(anchor)) return@forEach

            val liveRange = live[anchor.id]
            val range = if (liveRange != null &&
                liveRange.first in 0..document.textLength &&
                liveRange.second in 0..document.textLength
            ) {
                // 会话内以 marker 的实际位置为准，避免"用户改了这段代码就判过期"
                com.intellij.openapi.util.TextRange(liveRange.first, liveRange.second)
            } else {
                Anchoring.locate(document, anchor)
            }

            if (range == null) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.warn("stale anchor ${anchor.id} in ${state.file.name}")
                return@forEach
            }

            // 会话内改过内容 → 用新文本刷新锚定身份；跨会话只认行号
            if (liveRange != null) {
                val text = document.getText(range)
                val normalized = Anchoring.normalize(text)
                if (normalized != anchor.anchorText) {
                    anchor.anchorText = normalized
                    anchor.anchorSha1 = Anchoring.sha1(text)
                    anchor.contextBefore = Anchoring.contextBefore(document, document.getLineNumber(range.startOffset))
                    anchor.contextAfter = Anchoring.contextAfter(
                        document, document.getLineNumber(range.endOffset.coerceAtMost(document.textLength))
                    )
                }
                anchor.startOffset = range.startOffset
                anchor.endOffset = range.endOffset
            }

            anchor.startLine = document.getLineNumber(range.startOffset) + 1
            anchor.endLine = document.getLineNumber(range.endOffset.coerceAtMost(document.textLength)) + 1
            desired[anchor.id] = anchor to range
        }

        // 1) 删掉已经不该存在的
        state.painted.map { it.anchor.id }.filter { it !in desired }.forEach { state.dropAnchor(it) }

        // 2) 增量更新：位置/颜色没变的保留 highlighter，只按内容签名决定是否重建块
        val window = state.windowOf()
        val existingById = state.painted.associateBy { it.anchor.id }

        desired.forEach { (id, pair) ->
            val (anchor, range) = pair
            val color = service.effectiveColor(anchor)
            val signature = contentSignature(anchor, color)
            val withBlock = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get().showSummary &&
                (!state.shouldCull() || (anchor.endLine >= window.first && anchor.startLine <= window.last))

            val existing = existingById[id]
            val sameRange = existing != null &&
                existing.highlighter.isValid &&
                existing.highlighter.startOffset == range.startOffset &&
                existing.highlighter.endOffset == range.endOffset

            if (existing != null && sameRange && existing.color == color) {
                // 关键：把 Painted 里的锚点引用换成最新的。
                // 否则重建块时会用旧的 Anchor 实例（比如重新加载过库之后），
                // 表现就是"笔记只显示一行/旧内容"，切换一下显示开关才恢复。
                val anchorReplaced = existing.anchor !== anchor
                existing.anchor = anchor
                if (anchorReplaced) {
                    existing.highlighter.gutterIconRenderer =
                        gutterFor(project, anchor, color) { announceActivation(anchor) }
                }

                if (existing.contentSignature != signature || anchorReplaced) {
                    state.disposeInlays(existing)
                    if (withBlock) state.addInlays(existing, signature)
                    existing.contentSignature = signature
                } else if (withBlock && existing.blocks.isEmpty()) {
                    state.addInlays(existing, signature)
                } else if (!withBlock && existing.blocks.isNotEmpty()) {
                    state.disposeInlays(existing)
                }
            } else {
                existing?.let { state.dropAnchor(id) }
                state.add(anchor, range.startOffset, range.endOffset, withBlock, signature)
            }
        }

        com.thousandyearsofwar.ridenotes.util.NoteLog.info(
            "render file=${state.file.name} anchors=${anchors.size} painted=${state.painted.size} " +
                "blocks=${state.painted.sumOf { it.blocks.size }} " +
                "in ${(System.nanoTime() - startedAt) / 1_000_000}ms"
        )
    }

    /** gutter 图标被点击：通知面板把列表滚动并选中到该锚点。 */
    private fun announceActivation(anchor: Anchor) {
        runCatching {
            project.messageBus
                .syncPublisher(com.thousandyearsofwar.ridenotes.ui.AnchorActivationTopic.TOPIC)
                .anchorActivated(anchor.id)
        }
    }

    /** 标题/笔记内容/外观设置/简洁开关变了才需要重建摘要块。 */
    private fun contentSignature(anchor: Anchor, color: Int): String {
        val settings = com.thousandyearsofwar.ridenotes.settings.NotesSettings.get()
        val service = NoteAnchorService.get(project)
        val compact = service.effectiveCompact(anchor)
        return buildString {
            append(settings.maxWidth).append(':').append(settings.fontSize).append(':').append(color)
            append(":plain1")
            append(':').append(if (settings.showSummary) 1 else 0)
            append(':').append(if (compact) 1 else 0)
            append('|').append(service.resolvedTitle(anchor))
            service.resolvedNotes(anchor).forEach { note ->
                append('|').append(note.id).append(':').append(note.updatedAt).append(':').append(note.body.length)
            }
        }
    }

    private inner class EditorState(val editor: Editor, val file: VirtualFile) {

        var lastWindowStart: Int = Int.MIN_VALUE

        /** 滚动时只补/删可视区的块，不重建 highlighter（否则千级锚点会卡死 EDT）。 */
        private val scrollListener = object : com.intellij.openapi.editor.event.VisibleAreaListener {
            override fun visibleAreaChanged(e: com.intellij.openapi.editor.event.VisibleAreaEvent) {
                // 滚动后强制重绘一次：块滚出视口（尤其顶部）时会留下半截像素。
                // repaint() 由 Swing 合并，不涉及任何 inlay/模型操作。
                if (!editor.isDisposed) editor.contentComponent.repaint()

                val window = windowOf()
                if (lastWindowStart != Int.MIN_VALUE && Math.abs(window.first - lastWindowStart) < MARGIN / 2) return
                lastWindowStart = window.first
                ApplicationManager.getApplication().invokeLater {
                    if (!editor.isDisposed) updateBlockVisibility()
                }
            }
        }

        init {
            runCatching { editor.scrollingModel.addVisibleAreaListener(scrollListener) }
            runCatching { editor.contentComponent.addComponentListener(repaintListener) }
            // 外层组件也会被 splitter 改尺寸，两个都挂上
            runCatching { editor.component?.addComponentListener(repaintListener) }
        }

        fun dispose() {
            runCatching { editor.scrollingModel.removeVisibleAreaListener(scrollListener) }
            runCatching { editor.contentComponent.removeComponentListener(repaintListener) }
            runCatching { editor.component?.removeComponentListener(repaintListener) }
            runCatching { restoreTextCursor() }
        }

        /** 鼠标停在正文链接上时用"手型"，移开恢复文本光标。 */
        private fun updateCursorFor(mouseX: Int, mouseY: Int, inlay: Inlay<*>?) {
            val target = inlay?.let {
                (it.renderer as? SummaryBlockRenderer)?.linkAt(it, mouseX, mouseY)
            }
            if (target != null) setHandCursor() else restoreTextCursor()
        }

        private fun setHandCursor() {
            val component = editor.contentComponent
            val hand = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            if (component.cursor != hand) component.cursor = hand
        }

        private fun restoreTextCursor() {
            val component = editor.contentComponent
            val text = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR)
            if (component.cursor != text) component.cursor = text
        }

        val motionListener = object : com.intellij.openapi.editor.event.EditorMouseMotionListener {
            override fun mouseMoved(event: com.intellij.openapi.editor.event.EditorMouseEvent) {
                val mouse = event.mouseEvent
                updateCursorFor(mouse.x, mouse.y, event.inlay)
            }
        }

        /**
         * 任何布局变化（拖任意面板/分隔条、改窗口大小）之后：
         *   1. 等布局落定（invokeLater）
         *   2. 把**当前可见的块**全部销毁重建 —— 位置变了，旧 inlay 的像素会残留
         *   3. 强制重画编辑器
         *
         * 只处理可见的几十个块，不遍历全部锚点；用 pending 标记把连续事件合并成一次。
         * 之前把 render()（1000+ 锚点）塞进 resize 回调，才会把 CPU 打满。
         */
        private var layoutPending = false

        private val repaintListener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (layoutPending || editor.isDisposed) return
                layoutPending = true
                ApplicationManager.getApplication().invokeLater {
                    layoutPending = false
                    if (editor.isDisposed) return@invokeLater
                    // 只处理有块的项；painted 里可能有几千个锚点，全遍历会把 EDT 拖死
                    painted.forEach { if (it.blocks.isNotEmpty()) disposeInlays(it) }
                    updateBlockVisibility()
                    runCatching { editor.contentComponent.repaint() }
                    runCatching { editor.component?.repaint() }
                }
            }
        }

        val painted = mutableListOf<Painted>()
        var lastRenderedWidth: Int = -1

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // inlay 不随文档移动，重挂；highlighter 本身是 marker，自己会跟
                ApplicationManager.getApplication().invokeLater { reposition() }
            }
        }

        val mouseListener = object : EditorMouseListener {
            /** 鼠标离开编辑器：把链接的"手型"光标恢复成文本光标。 */
            override fun mouseExited(event: EditorMouseEvent) {
                restoreTextCursor()
            }

            override fun mousePressed(event: EditorMouseEvent) {
                val inlay = event.inlay ?: return
                val anchorId = inlay.getUserData(ANCHOR_ID) ?: return
                val anchor = NoteAnchorService.get(project).anchorsFor(file).firstOrNull { it.id == anchorId } ?: return

                // 右键：不拦截菜单，但把光标挪到这条锚点的起点，
                // 让右键菜单里的删除动作指向你点的这一条
                if (javax.swing.SwingUtilities.isRightMouseButton(event.mouseEvent)) {
                    moveCaretTo(anchor)
                    return
                }

                // 点中正文里的链接：按 href 分派（anchor: / 本地文件 / 网页）
                val linkTarget = (inlay.renderer as? SummaryBlockRenderer)
                    ?.linkAt(inlay, event.mouseEvent.x, event.mouseEvent.y)
                if (linkTarget != null) {
                    event.consume()
                    openLink(linkTarget)
                    return
                }

                event.consume()
                if (event.mouseEvent.clickCount >= 2) {
                    beginEdit(anchor, inlay, inlay.getUserData(NOTE_ID))
                } else {
                    moveCaretTo(anchor)
                }
            }
        }

        /** 打开链接目标：跳到源文件对应行，并让面板定位到该锚点。 */
        /** 打开笔记里的链接：`anchor:` 跳锚点，本地 md/html 在编辑器里打开，http(s)/mailto 交给浏览器。 */
        private fun openLink(href: String) {
            com.thousandyearsofwar.ridenotes.util.NoteLog.info(
                "open link href=[$href] embed=${com.thousandyearsofwar.ridenotes.model.NoteEmbeds.isEmbedHref(href)}"
            )
            when {
                href.startsWith("anchor:", ignoreCase = true) ->
                    openAnchorLink(href.removePrefix("anchor:").removePrefix("ANCHOR:"))

                href.startsWith("http://", ignoreCase = true) ||
                    href.startsWith("https://", ignoreCase = true) ||
                    href.startsWith("mailto:", ignoreCase = true) -> {
                    runCatching { com.intellij.ide.BrowserUtil.browse(href) }
                        .onFailure {
                            com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "打开链接失败：${it.message}")
                        }
                }

                com.thousandyearsofwar.ridenotes.model.NoteEmbeds.isEmbedHref(href) ->
                    com.thousandyearsofwar.ridenotes.ui.EmbedOpener.open(project, href)
                else -> com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                    project, "不支持的链接：$href"
                )
            }
        }
        private fun openAnchorLink(anchorId: String) {
            val found = NoteAnchorService.get(project).locateAnchor(anchorId)
            if (found == null) {
                // 兼容旧写法：链接被写成 anchor:<本地路径>，按文件引用处理
                if (com.thousandyearsofwar.ridenotes.model.NoteEmbeds.isEmbedHref(anchorId)) {
                    com.thousandyearsofwar.ridenotes.ui.EmbedOpener.open(project, anchorId)
                } else {
                    com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "链接目标已被删除")
                }
                return
            }
            val (path, target) = found
            val base = project.basePath ?: return
            val absolute = if (path.startsWith("/") || path.contains(":")) path else "$base/$path"
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(absolute)
            if (vf == null) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "链接目标文件不存在")
                return
            }
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
            val offset = document?.let {
                runCatching { it.getLineStartOffset((target.startLine - 1).coerceIn(0, it.lineCount - 1)) }.getOrDefault(0)
            } ?: 0
            runCatching {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, offset), true)
                project.messageBus
                    .syncPublisher(com.thousandyearsofwar.ridenotes.ui.AnchorActivationTopic.TOPIC)
                    .anchorActivated(anchorId)
            }
        }
        private fun moveCaretTo(anchor: Anchor) {
            runCatching {
                editor.caretModel.moveToOffset(anchor.startOffset.coerceIn(0, editor.document.textLength))
            }
        }

        /** 就地编辑：编辑框正好盖在摘要块上。 */
        fun beginEdit(anchor: Anchor, inlay: Inlay<*>?, noteId: String? = null) {
            val service = NoteAnchorService.get(project)
            if (anchor.mirrorOf != null) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.notify(
                    project, "这是克隆锚点，请到源锚点编辑"
                )
                return
            }
            if (service.effectiveLocked(anchor)) {
                com.thousandyearsofwar.ridenotes.util.NoteLog.notify(project, "该笔记已锁定，请在 Notes 面板中编辑")
                return
            }
            val note = service.resolvedNotes(anchor).firstOrNull { it.id == noteId } ?: service.resolvedNotes(anchor).firstOrNull()
            val bounds = inlay?.let { runCatching { it.bounds }.getOrNull() }

            // 编辑时先把这一锚点的所有摘要块收起来，避免两层颜色叠加
            painted.firstOrNull { it.anchor.id == anchor.id }?.let { disposeInlays(it) }

            val at = bounds?.let {
                com.intellij.ui.awt.RelativePoint(editor.contentComponent, java.awt.Point(it.x, it.y))
            }
            val size = bounds?.let {
                java.awt.Dimension(
                    (it.width - 8).coerceAtLeast(280),
                    it.height.coerceAtLeast(editor.lineHeight * 4)
                )
            } ?: java.awt.Dimension(420, editor.lineHeight * 5)

            NotePopups.showEditor(
                project = project,
                editor = editor,
                file = file,
                anchor = anchor,
                initial = note?.body ?: "",
                onSave = { body ->
                    if (note != null) {
                        service.updateNote(file, anchor.id, note.id, body)
                    } else if (body.isNotBlank()) {
                        service.addNote(file, anchor.id, body)
                    }
                    this@AnchorRenderService.refresh(file)
                },
                onCancel = { reposition() },
                at = at,
                size = size,
                backgroundColor = NotePalette.opaque(service.effectiveColor(anchor), editor.colorsScheme.defaultBackground)
            )
        }

        fun add(anchor: Anchor, start: Int, end: Int, withBlock: Boolean, signature: String = "") {
            val document = editor.document
            val color = NoteAnchorService.get(project).effectiveColor(anchor)
            val attributes = TextAttributes().apply {
                backgroundColor = NotePalette.background(color)
            }
            val highlighter = editor.markupModel.addRangeHighlighter(
                start, end,
                // 高于光标行背景（CARET_ROW=2000），低于使用处高亮与选区（≈5900/6000）：
                // 既不会盖住 IDE 的高亮，也不会在光标落到该行时被行背景吃掉
                HighlighterLayer.ADDITIONAL_SYNTAX,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighter.setGreedyToLeft(true)
            highlighter.setGreedyToRight(false)

            val item = Painted(anchor, highlighter, color)
            item.contentSignature = signature
            highlighter.gutterIconRenderer = gutterFor(project, anchor, color) { announceActivation(anchor) }
            painted.add(item)
            if (withBlock) addInlays(item, signature)
        }

        /** 可视区（上下各留 MARGIN 行）。 */
        fun windowOf(): IntRange {
            val size = editorViewportSize(editor)
            val height = size.height

            // xyToLogicalPosition 的坐标是**内容坐标**，可见区顶部对应 verticalScrollOffset
            val scrollY = runCatching { editor.scrollingModel.verticalScrollOffset }.getOrDefault(0)
            val top = runCatching { editor.xyToLogicalPosition(java.awt.Point(0, scrollY)).line }.getOrDefault(0)
            val bottom = runCatching { editor.xyToLogicalPosition(java.awt.Point(0, scrollY + height)).line }
                .getOrDefault(top + 30)
            val boundedBottom = if (bottom - top > 400) top + 120 else bottom
            return (top - MARGIN)..(boundedBottom + MARGIN)
        }

        /** 锚点数超过这个阈值才做视口裁剪（太多块会拖慢编辑器）；小文件全保留，避免跳转错位。 */
        private val cullThreshold = 400

        fun shouldCull(): Boolean = painted.size > cullThreshold

        /** 滚动后只增删块，不重建 highlighter。 */
        fun updateBlockVisibility() {
            if (!shouldCull()) {
                // 小文件：所有锚点始终保留块，滚动/原生跳转时排版不跳动
                painted.forEach { if (it.blocks.isEmpty()) addInlays(it) }
                return
            }
            val window = windowOf()
            painted.forEach { item ->
                val first = item.anchor.startLine
                val last = item.anchor.endLine
                val want = last >= window.first && first <= window.last
                if (want && item.blocks.isEmpty()) {
                    addInlays(item, item.contentSignature)
                } else if (!want && item.blocks.isNotEmpty()) {
                    disposeInlays(item)
                }
            }
            // 块增减后重画一次，清掉旧位置的像素
            if (!editor.isDisposed) editor.contentComponent.repaint()
        }

        /** 前导空白的**列数**（tab 按制表位展开）。像素宽度在绘制时按当前字体算，缩放后仍能对齐。 */
        private fun indentColumns(document: com.intellij.openapi.editor.Document, lineStart: Int, firstNonWs: Int): Int {
            if (firstNonWs <= lineStart) return 0
            val tabSize = runCatching { editor.settings.getTabSize(project) }.getOrDefault(4).coerceAtLeast(1)
            var columns = 0
            for (i in lineStart until firstNonWs) {
                when (document.charsSequence[i]) {
                    '\t' -> columns += tabSize - (columns % tabSize)
                    else -> columns += 1
                }
            }
            return columns
        }

        fun addInlays(item: Painted, signature: String = item.contentSignature) {
            val document = editor.document
            val start = item.highlighter.startOffset.coerceIn(0, document.textLength)
            val end = item.highlighter.endOffset.coerceIn(0, document.textLength)

            val startLine = document.getLineNumber(start)

            // 摘要行要和代码缩进对齐：取锚定首行第一个非空白字符的像素位置
            // 缩进要按"文本列数"算，不能用视觉坐标：
            // visualPositionToXY 对**视口外**的位置（跳转前没显示过的区域）返回值不可靠，
            // 会把块横向甩出很远。这里只依赖文档内容和字体宽度，与滚动/视口无关。
            val lineStart = document.getLineStartOffset(startLine)
            val lineEnd = document.getLineEndOffset(startLine)
            var firstNonWs = lineStart
            while (firstNonWs < lineEnd && document.charsSequence[firstNonWs].isWhitespace()) firstNonWs++
            val indentColumns = indentColumns(document, lineStart, firstNonWs)

            val endLine = document.getLineNumber(end)
            val service = NoteAnchorService.get(project)
            val compact = service.effectiveCompact(item.anchor)
            val offset = document.getLineEndOffset(endLine)

            // 摘要总开关：关掉后只有色带 + gutter 图标。
            // 注意要把已有块销毁：否则编辑器仍按块高度排版，原生跳转会落到"旧位置"。
            if (!com.thousandyearsofwar.ridenotes.settings.NotesSettings.get().showSummary) {
                disposeInlays(item)
                return
            }


            // 一条笔记一个块；标题只在面板/悬停里显示，不再占用代码区
            val blocks = mutableListOf<Pair<String?, List<String>>>()

            fun noteLines(note: com.thousandyearsofwar.ridenotes.model.Note): List<String> {
                val body = note.body.trim()
                if (body.isEmpty()) return emptyList()
                return body.lines()
            }

            service.resolvedNotes(item.anchor).forEach { note ->
                val lines = if (compact) note.body.trim().lines().take(1) else noteLines(note)
                if (lines.isEmpty() || lines.all { it.isBlank() }) return@forEach
                blocks.add(note.id to lines)
            }

            if (blocks.isEmpty()) {
                blocks.add(null to listOf("空笔记 · 双击编辑"))
            }

            blocks.forEachIndexed { index, (noteId, lines) ->
                val text = lines.joinToString("\n")
                val note = service.resolvedNotes(item.anchor).firstOrNull { it.id == noteId }
                val stamps = if (note == null) emptyMap() else mapOf(0 to stampOf(note))

                val inlay = editor.inlayModel.addBlockElement(
                    offset, true, false, 50,
                    SummaryBlockRenderer(
                        item.anchor, text, indentColumns, compact, item.color, stamps,
                        project, file
                    )
                )
                if (inlay != null) {
                    inlay.putUserData(ANCHOR_ID, item.anchor.id)
                    if (noteId != null) inlay.putUserData(NOTE_ID, noteId)
                    item.blocks.add(inlay)
                }
            }
        }

        private fun reposition() {
            if (editor.isDisposed) return
            painted.forEach { item ->
                if (!item.highlighter.isValid) return@forEach
                disposeInlays(item)
                addInlays(item)
            }
        }

        fun disposeInlays(item: Painted) {
            item.blocks.forEach { runCatching { it.dispose() } }
            item.blocks.clear()
        }

        fun clear() {
            painted.forEach { item ->
                disposeInlays(item)
                runCatching { editor.markupModel.removeHighlighter(item.highlighter) }
            }
            painted.clear()
        }

        /** 移除单个锚点的视觉（保留数据），让下次渲染重新定位。 */
        fun dropAnchor(anchorId: String) {
            val item = painted.firstOrNull { it.anchor.id == anchorId } ?: return
            painted.remove(item)
            disposeInlays(item)
            runCatching { editor.markupModel.removeHighlighter(item.highlighter) }
        }
    }

    /** 一条笔记的块文本：完整多行，日期时间挂在**首行末尾**。 */
    private fun noteBlockText(note: com.thousandyearsofwar.ridenotes.model.Note): String {
        val body = note.body.trim()
        if (body.isEmpty()) return ""
        val stamp = stampOf(note)
        if (stamp.isEmpty()) return body
        val lines = body.lines()
        return buildString {
            append(lines.firstOrNull().orEmpty())
            append("   ")
            append(stamp)
            lines.drop(1).forEach { append("\n").append(it) }
        }
    }

    private fun stampOf(note: com.thousandyearsofwar.ridenotes.model.Note): String = runCatching {
        NOTE_STAMP_FORMAT.format(java.time.Instant.parse(note.createdAt))
    }.getOrDefault("")

    private fun gutterFor(project: Project, anchor: Anchor, color: Int, onClick: () -> Unit): GutterIconRenderer = object : GutterIconRenderer() {
        override fun getIcon(): Icon = NoteGutterIcon(color)

        override fun getTooltipText(): String = buildString {
            val svc = com.thousandyearsofwar.ridenotes.service.NoteAnchorService.get(project)
            append(svc.resolvedTitle(anchor).ifBlank { anchor.displayTitle })
            val resolved = svc.resolvedNotes(anchor)
            if (resolved.isNotEmpty()) append("  ·  ${resolved.size} 条")
            resolved.firstOrNull()?.let {
                append("\n").append(com.thousandyearsofwar.ridenotes.model.NoteLinks.strip(it.body).take(80))
            }
            if (anchor.mirrorOf != null) append("\n（克隆锚点，跟随源锚点）")
            append("\n双击就地编辑")
        }

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onClick()
        }

        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private class Painted(var anchor: Anchor, val highlighter: RangeHighlighter, val color: Int) {
        /** 标题/笔记内容签名，变了才重建块。 */
        var contentSignature: String = ""
        /** 每条笔记一个块（简洁模式只有一个）。 */
        val blocks = mutableListOf<Inlay<*>>()
    }
}
