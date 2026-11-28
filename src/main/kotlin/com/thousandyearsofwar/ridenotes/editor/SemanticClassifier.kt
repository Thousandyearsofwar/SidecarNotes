package com.thousandyearsofwar.ridenotes.editor

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import java.util.concurrent.ConcurrentHashMap

/**
 * 给笔记里的代码片段补一层**语义分类**（在词法着色之上）。
 *
 * Rider 的 PSI 基类可用，但 Java 专用的 `PsiClass/PsiField/PsiMethod` 不在平台里，
 * C#/C++ 的语义由各自的 PSI 提供，所以这里做两件事：
 *
 * 1. 文本扫描：`class/struct/enum` 类型名、`#define` 宏、`const/constexpr` 常量
 *    —— 语言无关，任何文件都能用
 * 2. PSI 走树：按元素类名关键字判断（Function/Method/Field/Variable/Parameter/Class…）
 *    —— 对 C#/C++ 的 PSI 有效；节点数上限 6 万，避免大文件卡住
 *
 * 结果按**文件修改戳**缓存：文件不改就一直复用，不给每帧绘制加成本。
 */
object SemanticClassifier {

    data class Facts(
        val stamp: Long,
        val keys: Map<String, TextAttributesKey>,
        val types: Set<String>
    )

    private val CACHE = ConcurrentHashMap<VirtualFile, Facts>()
    private const val MAX_NODES = 60_000
    private const val MAX_PSI_BYTES = 512L * 1024

    fun facts(project: Project, file: VirtualFile): Facts {
        CACHE[file]?.let { if (it.stamp == file.modificationStamp) return it }

        val keys = HashMap<String, TextAttributesKey>()
        val types = HashSet<String>()
        runCatching { seedFromText(file, keys, types) }

        // 大文件不扫 PSI：解析/遍历开销不可控，文本扫描已经覆盖类型/宏/常量
        if (file.length <= MAX_PSI_BYTES) runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<Unit> {
                val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file)
                    ?: return@runReadAction
                var visited = 0
                psi.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: com.intellij.psi.PsiElement) {
                        if (++visited > MAX_NODES) {
                            stopWalking()
                            return
                        }
                        if (element is PsiNamedElement) {
                            val name = element.name
                            if (!name.isNullOrBlank()) {
                                keyFor(element)?.let { keys.putIfAbsent(name, it) }
                            }
                        }
                        super.visitElement(element)
                    }
                })
            }
        }

        val facts = Facts(file.modificationStamp, keys, types)
        CACHE[file] = facts
        return facts
    }

    /** C#/C++ 的 PSI 不实现 Java 的语义接口，按元素类名关键字判断。 */
    private fun keyFor(element: PsiNamedElement): TextAttributesKey? {
        val simple = element.javaClass.simpleName
        return when {
            simple.contains("Macro", true) -> DefaultLanguageHighlighterColors.CONSTANT
            simple.contains("Parameter", true) -> DefaultLanguageHighlighterColors.PARAMETER
            simple.contains("Field", true) -> DefaultLanguageHighlighterColors.INSTANCE_FIELD
            simple.contains("Variable", true) -> DefaultLanguageHighlighterColors.LOCAL_VARIABLE
            simple.contains("Function", true) || simple.contains("Method", true) ->
                DefaultLanguageHighlighterColors.INSTANCE_METHOD

            simple.contains("Class", true) || simple.contains("Struct", true) ||
                simple.contains("Enum", true) || simple.contains("Type", true) ->
                DefaultLanguageHighlighterColors.CLASS_NAME

            simple.contains("Namespace", true) -> DefaultLanguageHighlighterColors.CLASS_NAME
            else -> null
        }
    }

    /** 文本级补充：类型名、宏、常量。 */
    private fun seedFromText(
        file: VirtualFile,
        keys: MutableMap<String, TextAttributesKey>,
        types: MutableSet<String>
    ) {
        val text = runCatching {
            FileDocumentManager.getInstance().getDocument(file)?.text
                ?: String(file.contentsToByteArray(), Charsets.UTF_8)
        }.getOrNull() ?: return
        if (text.isEmpty()) return

        Regex("\\b(?:class|struct|union|enum(?:\\s+class)?)\\s+([A-Za-z_]\\w*)")
            .findAll(text)
            .forEach {
                val name = it.groupValues[1]
                types.add(name)
                keys.putIfAbsent(name, DefaultLanguageHighlighterColors.CLASS_NAME)
            }

        Regex("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)")
            .findAll(text)
            .forEach { keys.putIfAbsent(it.groupValues[1], DefaultLanguageHighlighterColors.CONSTANT) }

        Regex("\\b(?:constexpr|static\\s+const|const)\\b[^;\\n]{0,80}?\\b([A-Za-z_]\\w*)\\s*(?:=|;)")
            .findAll(text)
            .forEach { keys.putIfAbsent(it.groupValues[1], DefaultLanguageHighlighterColors.CONSTANT) }
    }
}
