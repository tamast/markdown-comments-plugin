package com.example.mdcomments.preview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction

class ToggleMarkdownCommentingAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = CommentSettings.isEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CommentSettings.isEnabled = state
        CommentSettings.disabledHintShown = false
        // Update hover highlighting in already-open previews immediately.
        CommentPreviewExtension.broadcastEnabledState()
    }
}
