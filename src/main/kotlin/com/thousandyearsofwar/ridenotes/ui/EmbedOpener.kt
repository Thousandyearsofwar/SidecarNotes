package com.thousandyearsofwar.ridenotes.ui

import com.intellij.openapi.project.Project
import com.thousandyearsofwar.ridenotes.model.NoteEmbeds
import com.thousandyearsofwar.ridenotes.util.NoteLog

/**
 * 打开笔记里引用的本地文件。
 *
 * - `.html/.htm`：用 IDE 内置浏览器（JCEF）渲染成预览标签页
 * - 其它（`.md` / `.txt`）：用文本编辑器打开
 */
object EmbedOpener {

    fun open(project: Project, href: String): Boolean {
        val file = NoteEmbeds.resolve(project.basePath, href)
        if (file == null) {
            NoteLog.notify(project, "引用文件不存在：$href")
            return false
        }

        val lower = file.name.lowercase()
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return runCatching {
                val html = file.readText()
                com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
                    .openEditor(project, file.name, html)
                true
            }.getOrElse {
                NoteLog.notify(project, "HTML 预览失败：${it.message}")
                false
            }
        }

        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(file.absolutePath)
        if (vf == null) {
            NoteLog.notify(project, "引用文件不存在：$href")
            return false
        }
        return runCatching {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openTextEditor(
                    com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, 0),
                    true
                )
            true
        }.getOrElse {
            NoteLog.notify(project, "打开文件失败：${it.message}")
            false
        }
    }
}
