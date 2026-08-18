# Markdown Preview Comments (MVP)

Commenting is **disabled by default** — enable it via **Tools → Enable
Markdown Comments** (the choice persists across restarts). Then click a
block in IntelliJ's built-in **Markdown preview pane** (not the raw source
editor) to attach a review comment. Comments are appended as rows to a table
at the end of the `.md` file:

```
<!-- markdown-comments -->
## Comments

| anchor | comment | resolved |
|---|---|---|
| 4f2c1a9e | Should this be async? | false |
```

The anchor id is also written into the commented line itself as an HTML
comment, e.g. `some text <!-- md-comment:4f2c1a9e -->`, which is invisible
in the rendered preview.

**Tools → Remove All Comments** deletes the whole comments table from the
end of the file and strips every `<!-- md-comment:<id> -->` anchor from the
source lines (one undoable edit, with a confirmation dialog first).

## How it works

- `CommentPreviewExtension` plugs into the Markdown plugin's own
  `org.intellij.markdown.browserPreviewExtensionProvider` extension point,
  which is the same mechanism JetBrains uses internally (e.g. the "run this
  command" icons in code fences) to inject JS/CSS into the JCEF-based
  preview browser without building a separate renderer.
- `comments.js` runs inside that embedded browser. It reads the
  `markdown-position-attribute-name` `<meta>` tag that the preview HTML
  always includes — this tells it, at runtime, which HTML attribute maps a
  rendered element back to its source position — so it isn't hardcoded and
  won't silently break on IDE updates. The attribute holds a source
  character-offset range `"from..to"` (same as the bundled `ScrollSync.js`
  consumes), not a line number.
- On click, the JS posts the block's start offset back to Kotlin over
  `window.__IntelliJTools.messagePipe`, the Markdown plugin's built-in
  message channel (`BrowserPipe` on the Kotlin side), which maps the offset
  to a source line via `Document.getLineNumber`.
- `CommentTableWriter` writes the new row into the `Document` inside a
  `WriteCommandAction`, so it's undoable like any normal edit.

## Run it

```bash
./gradlew runIde
```

This launches a sandboxed IDE instance with the plugin installed. Open any
`.md` file, switch to the split editor or Preview-only view, hover a
paragraph/heading/list item (it highlights), and click it.

You'll need a local JDK 21 for the Gradle build itself; the sandbox IDE
downloads its own runtime.

## Install in your own IDE

Build the distributable zip:

```bash
./gradlew buildPlugin
```

The output lands in `build/distributions/markdown-comments-plugin-0.1.0.zip`.
Then in your IDE:

1. **Settings** (⌘,) → **Plugins**
2. Gear icon → **Install Plugin from Disk...**
3. Select the zip and restart the IDE

Compatibility is `sinceBuild = 251` (IDEA 2025.1+) with no upper bound, but
note the click-to-line mapping assumes the 2026.1 position-attribute format
(source offset range `"from..to"`); older builds that still use plain line
numbers will anchor comments to wrong lines.

## Known limitations (MVP, by design)

- **Anchors survive edits, deleting a line orphans its comment.** Comments
  reference `<!-- md-comment:<id> -->` anchors embedded in the line, not
  line numbers, so edits above a comment don't break it. But if someone
  deletes the line (or the anchor comment itself), the table row points at
  nothing. There is no cleanup for orphaned rows.
- **One table, assumed to be last in the file.** New rows are always
  inserted at the very end. If you manually add content below the
  `<!-- markdown-comments -->` table, new comments will land after it, not
  merge into the table correctly.
- **No "resolved" toggle UI yet** — it's just always written as `false`.
  Flipping it to `true` today means hand-editing the table.
- Comment text is escaped for `|` and newlines only; there's no validation
  beyond that.
- Tested conceptually against the current (2026.1-era) Markdown plugin
  internals — `browserPreviewExtensionProvider` is marked
  `@ApiStatus.Obsolete` upstream (still shipped and used internally, but a
  signal JetBrains may eventually redesign the preview-extension API). If a
  future IDE update breaks this, the `runIde` sandbox with the DevTools
  registry flag (`ide.browser.jcef.debug.port`) is the fastest way to
  re-inspect the live preview DOM.
