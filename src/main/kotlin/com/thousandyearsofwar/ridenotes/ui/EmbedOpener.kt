package com.thousandyearsofwar.ridenotes.ui

import com.intellij.openapi.project.Project
import com.thousandyearsofwar.ridenotes.model.NoteEmbeds
import com.thousandyearsofwar.ridenotes.util.NoteLog
import java.awt.Desktop

/**
 * 打开笔记里引用的本地文件。
 *
 * 优先交给**系统默认程序**（Windows 上 .html 就是默认浏览器，.md 是关联的编辑器），
 * 不在 IDE 里渲染：JCEF 预览大页面时会拖慢 Rider。
 *
 * 系统调用失败时回退到 IDE 打开（文本文件用编辑器，html 用内置预览）。
 */
object EmbedOpener {

    fun open(project: Project, href: String): Boolean {
        val file = NoteEmbeds.resolve(project.basePath, href)
        if (file == null) {
            NoteLog.notify(project, "引用文件不存在：$href")
            return false
        }

        if (openWithSystemApp(project, file)) return true
        return openInsideIde(project, file)
    }

    private fun openWithSystemApp(project: Project, file: java.io.File): Boolean {
        if (!Desktop.isDesktopSupported()) return false
        return runCatching {
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) return false
            desktop.open(file)
            true
        }.getOrElse {
            NoteLog.warn("open with system app failed for ${file.name}: ${it.message}")
            false
        }
    }

    private fun openInsideIde(project: Project, file: java.io.File): Boolean {
        val lower = file.name.lowercase()
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return runCatching {
                com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
                    .openEditor(project, file.name, file.readText())
                true
            }.getOrElse {
                NoteLog.notify(project, "打开失败：${it.message}")
                false
            }
        }

        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(file.absolutePath)
        if (vf == null) {
            NoteLog.notify(project, "引用文件不存在：${file.name}")
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
