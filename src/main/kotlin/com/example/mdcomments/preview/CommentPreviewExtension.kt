package com.example.mdcomments.preview

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Injects comments.js/comments.css into the built-in Markdown preview (JCEF)
 * and listens for click events sent back from the browser via
 * window.__IntelliJTools.messagePipe.post(EVENT_CLICKED, sourceOffset).
 *
 * One instance is created per open preview panel (per VirtualFile), mirroring
 * the pattern used internally by CommandRunnerExtension.
 */
class CommentPreviewExtension(
    private val panel: MarkdownHtmlPanel,
    private val provider: Provider
) : MarkdownBrowserPreviewExtension {

    override val priority: MarkdownBrowserPreviewExtension.Priority =
        MarkdownBrowserPreviewExtension.Priority.LOW

    override val scripts: List<String> = listOf(SCRIPT_RESOURCE)
    override val styles: List<String> = listOf(STYLE_RESOURCE)

    override val resourceProvider: ResourceProvider = object : ResourceProvider {
        override fun canProvide(resourceName: String): Boolean {
            return resourceName == SCRIPT_RESOURCE || resourceName == STYLE_RESOURCE
        }

        override fun loadResource(resourceName: String): ResourceProvider.Resource? {
            // Leading "/" -> resolved from the classpath root, i.e.
            // src/main/resources/mdComments/comments.{js,css}
            return ResourceProvider.loadInternalResource<CommentPreviewExtension>(resourceName)
        }
    }

    private val lineClickedHandler = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            if (!CommentSettings.isEnabled) {
                showDisabledHintOnce()
                return true
            }
            val offset = data.toIntOrNull()
            if (offset != null) {
                ApplicationManager.getApplication().invokeLater { promptAndSaveComment(offset) }
            }
            return true
        }
    }

    private fun showDisabledHintOnce() {
        if (CommentSettings.disabledHintShown) return
        CommentSettings.disabledHintShown = true
        val project = panel.project ?: return
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Markdown Comments")
                .createNotification(
                    "Markdown commenting is disabled",
                    "Enable it via Tools | Enable Markdown Comments to add comments from the preview.",
                    NotificationType.INFORMATION
                )
                .notify(project)
        }
    }

    private val stateRequestHandler = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            sendEnabledState()
            sendEmojiState()
            sendCommentRanges()
            return true
        }
    }

    /** Pushes the current enabled flag into the rendered page. */
    private fun sendEnabledState() {
        panel.browserPipe?.send(EVENT_ENABLED_STATE, CommentSettings.isEnabled.toString())
    }

    /** Pushes the emoji-marker flag (off = colored border only). */
    private fun sendEmojiState() {
        panel.browserPipe?.send(EVENT_EMOJI_STATE, CommentSettings.showEmoji.toString())
    }

    /**
     * Pushes the source-offset ranges of already-commented lines into the
     * rendered page as JSON `[[start,end],...]`, so comments.js can mark
     * the corresponding rendered blocks.
     */
    private fun sendCommentRanges() {
        val file = panel.virtualFile ?: return
        val (json, resolvedJson) = ReadAction.compute<Pair<String, String>, Nothing> {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute ("[]" to "[]")
            val open = CommentTableWriter.commentedLineRanges(document)
                .joinToString(prefix = "[", postfix = "]") { "[${it.first},${it.second}]" }
            val resolved = CommentTableWriter.resolvedLineRanges(document)
                .joinToString(prefix = "[", postfix = "]") { "[${it.first},${it.second}]" }
            open to resolved
        }
        panel.browserPipe?.send(EVENT_COMMENT_RANGES, json)
        panel.browserPipe?.send(EVENT_RESOLVED_RANGES, resolvedJson)
    }

    // Debounces marker repaint on edits; anchors and the resolved column can
    // change without a save, so document-save events alone would lag behind.
    private val repaintAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        panel.browserPipe?.subscribe(EVENT_CLICKED, lineClickedHandler)
        panel.browserPipe?.subscribe(EVENT_STATE_REQUEST, stateRequestHandler)
        ACTIVE.add(this)
        // Repaint comment markers when the source changes (manual edits to
        // anchors or the resolved column), not only on comment creation.
        panel.project?.messageBus?.connect(this)
            ?.subscribe(com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
                object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
                    override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                        if (FileDocumentManager.getInstance().getFile(document) == panel.virtualFile) {
                            sendCommentRanges()
                        }
                    }
                })
        // Live editing path: document changes (typing in the editor while the
        // preview is open) debounce into a repaint.
        com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    val file = panel.virtualFile ?: return
                    if (FileDocumentManager.getInstance().getFile(event.document) != file) return
                    repaintAlarm.cancelAllRequests()
                    repaintAlarm.addRequest({ sendCommentRanges() }, 400)
                }
            },
            this
        )
        Disposer.register(this) {
            panel.browserPipe?.removeSubscription(EVENT_CLICKED, lineClickedHandler)
            panel.browserPipe?.removeSubscription(EVENT_STATE_REQUEST, stateRequestHandler)
            ACTIVE.remove(this)
        }
    }

    /** [sourceOffset] is the start character offset reported by the preview. */
    private fun promptAndSaveComment(sourceOffset: Int) {
        val project = panel.project ?: return
        val file = panel.virtualFile ?: return

        // Line number plus the anchor already on that line (null when the
        // click will create a fresh one) so the dialog can show which table
        // row the comment will land in.
        val info = ReadAction.compute<Pair<Int, String?>, Nothing> {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute (-1 to null)
            if (sourceOffset !in 0..document.textLength) return@compute (-1 to null)
            val lineIndex = document.getLineNumber(sourceOffset)
            val start = document.getLineStartOffset(lineIndex)
            val end = document.getLineEndOffset(lineIndex)
            val anchor = CommentTableWriter.existingAnchor(document, start, end)
            (lineIndex + 1) to anchor
        }
        val (displayLine, existingAnchor) = info
        val anchorNote = existingAnchor?.let { " (anchor: $it)" } ?: " (new anchor)"

        val comment = Messages.showMultilineInputDialog(
            project,
            if (displayLine > 0) "Comment for line $displayLine$anchorNote:" else "Comment for selected block:",
            "Add Markdown Comment",
            null,
            Messages.getQuestionIcon(),
            null
        ) ?: return

        val trimmed = comment.trim()
        if (trimmed.isEmpty()) return

        CommentTableWriter.appendComment(project, file, sourceOffset, trimmed)
        // The write may have changed the document; push the refreshed ranges
        // so the newly anchored block renders with the "commented" marker.
        sendCommentRanges()
    }

    override fun dispose() {
        provider.extensions.remove(panel.virtualFile)
    }

    class Provider : MarkdownBrowserPreviewExtension.Provider {
        val extensions = ConcurrentHashMap<VirtualFile, CommentPreviewExtension>()

        override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension? {
            val file = panel.virtualFile ?: return null
            return extensions.computeIfAbsent(file) { CommentPreviewExtension(panel, this) }
        }
    }

    companion object {
        private const val SCRIPT_RESOURCE = "/mdComments/comments.js"
        private const val STYLE_RESOURCE = "/mdComments/comments.css"
        const val EVENT_CLICKED = "mdCommentClicked"
        private const val EVENT_STATE_REQUEST = "mdCommentStateRequest"
        private const val EVENT_ENABLED_STATE = "mdCommentEnabled"
        private const val EVENT_COMMENT_RANGES = "mdCommentRanges"
        private const val EVENT_RESOLVED_RANGES = "mdCommentResolvedRanges"
        private const val EVENT_EMOJI_STATE = "mdCommentEmojiState"

        /** All live extensions (one per open preview panel). */
        private val ACTIVE = java.util.concurrent.CopyOnWriteArrayList<CommentPreviewExtension>()

        /** Pushes the current enabled flag to every open preview. */
        fun broadcastEnabledState() {
            ACTIVE.forEach { it.sendEnabledState() }
        }

        /** Pushes the current emoji flag to every open preview. */
        fun broadcastEmojiState() {
            ACTIVE.forEach { it.sendEmojiState() }
        }

        /** Repaints comment markers in every open preview (e.g. after removing all comments). */
        fun broadcastCommentRanges() {
            ApplicationManager.getApplication().invokeLater {
                ACTIVE.forEach { it.sendCommentRanges() }
            }
        }
    }
}
