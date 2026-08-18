package com.example.mdcomments.preview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction

class ToggleCommentEmojiAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = CommentSettings.showEmoji

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CommentSettings.showEmoji = state
        CommentPreviewExtension.broadcastEmojiState()
    }
}
