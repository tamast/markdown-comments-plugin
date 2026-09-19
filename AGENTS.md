# AGENTS.md

## What this is

IntelliJ Platform plugin (single Gradle module, Kotlin) that adds click-to-comment to the
bundled Markdown preview. It extends the Markdown plugin's JCEF preview rather than
rendering anything itself. `README.md` has a solid "How it works" section — read it
before touching the preview/JS bridge.

## Commands

- `./gradlew build` — compile + verify (no tests exist in this repo)
- `./gradlew runIde` — launch a sandbox IDE with the plugin installed; this is the
  **only** way to test changes (there is no test suite)
- `./gradlew buildPlugin` — zip for install-from-disk, lands in `build/distributions/`

Requires a local JDK 21 for the Gradle build (toolchain is pinned via
`kotlin { jvmToolchain(21) }`); the Gradle wrapper downloads the IDE itself.

## Debugging the preview

The plugin's JS runs inside JCEF. To inspect the live preview DOM/console, enable the
registry flag `ide.browser.jcef.debug.port` in the sandbox IDE. UI changes in
`src/main/resources/mdComments/comments.{js,css}` can only be verified this way.

## Architecture facts that will bite you

- **Depends on Markdown plugin internals**: everything hangs off
  `org.intellij.markdown.browserPreviewExtensionProvider`, which is `@ApiStatus.Obsolete`
  upstream. `build.gradle.kts` pins `intellijIdea("2026.1.4")` and README warns the
  offset→line mapping assumes the 2026.1 position-attribute format (`from..to` character
  offsets, not line numbers). Bumping the IDE version can silently break anchoring —
  re-verify with `runIde`.
- **Kotlin ⇄ JS contract**: event names (`mdCommentClicked`, `mdCommentRanges`, …) are
  string constants duplicated in `CommentPreviewExtension.kt` (companion object) and in
  `comments.js`. Keep both sides in sync; there is no compile-time check.
- **Persistence model**: comments live *in the markdown file itself* — a table guarded by
  `<!-- markdown-comments -->` at end-of-file plus `<!-- md-comment:<id> -->` anchors in
  commented lines. Don't change `CommentTableWriter`'s format without updating
  `comments.js` (it re-parses ranges to paint markers) and the README.
- One `CommentPreviewExtension` instance per open preview panel, tracked in the `ACTIVE`
  list; settings toggles broadcast to all instances via `broadcastEnabledState()` /
  `broadcastEmojiState()`.

## Style

Package is `com.example.mdcomments.preview`; plugin XML is minimal
(`src/main/resources/META-INF/plugin.xml`, actions appended to the Tools menu).
Follow the existing comment style: comments explain *why* (threading, debouncing,
upstream API quirks), not what the code does.
