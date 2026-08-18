package com.example.mdcomments.preview

import com.intellij.ide.util.PropertiesComponent

/**
 * Application-wide on/off switch for markdown commenting, persisted across
 * IDE restarts. Disabled by default so the preview stays purely read-only
 * until the user opts in (Tools > Toggle Markdown Comments).
 */
object CommentSettings {

    private const val KEY = "com.example.mdcomments.enabled"
    private const val EMOJI_KEY = "com.example.mdcomments.showEmoji"

    var isEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value, false)

    /** Emoji markers (💬/✅) next to commented blocks. Off by default —
     *  the colored left border alone marks commented lines. */
    var showEmoji: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(EMOJI_KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(EMOJI_KEY, value, false)

    /** One notification per IDE session is enough; reset on toggle. */
    @Volatile
    var disabledHintShown: Boolean = false
}
