package com.example.mdcomments.preview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * Deletes the whole comments table from the end of the markdown file and
 * strips every `<!-- md-comment:<id> -->` anchor from the source lines.
 * Destructive and irreversible, so it asks for confirmation first.
 */
class RemoveAllCommentsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            file != null && file.extension.equals("md", ignoreCase = true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val answer = Messages.showYesNoDialog(
            project,
            "Remove the comments table and all comment anchors from this file?\n\nThis cannot be undone.",
            "Remove All Markdown Comments",
            Messages.getWarningIcon()
        )
        if (answer != Messages.YES) return

        CommentTableWriter.removeAllComments(project, file)
        // The write changed the document; repaint markers in open previews.
        CommentPreviewExtension.broadcastCommentRanges()
    }
}
