package com.example.mdcomments.preview

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.util.UUID

/**
 * Appends comment rows to a `| anchor | comment | resolved |` table at
 * the end of the markdown file.
 *
 * Each commented line gets an invisible anchor appended to it as an HTML
 * comment: `<!-- md-comment:<id> -->`. The table refers to the anchor id,
 * not the line number, so comments stay attached to their line even when
 * the text above is edited (line numbers drift, anchors don't).
 *
 * MVP simplification: this assumes the comments table (once created) stays
 * the last thing in the file, since new rows are always inserted right
 * before end-of-file. Don't hand-edit content below the table.
 */
object CommentTableWriter {

    private const val MARKER = "<!-- markdown-comments -->"
    private const val SECTION_HEADING = "## Comments"
    private const val HEADER_ROW = "| anchor | comment | resolved |"
    private const val DIVIDER_ROW = "|---|---|---|"
    private val ANCHOR_REGEX = Regex("<!--\\s*md-comment:([a-zA-Z0-9-]+)\\s*-->")
    private val DIVIDER_CELL = Regex(":?-+:?")

    /**
     * [sourceOffset] is the character offset the preview reports for the
     * clicked block (its position attribute is an offset range "from..to"),
     * converted back to a line inside the write action where the Document
     * state is stable.
     */
    fun appendComment(project: Project, file: VirtualFile, sourceOffset: Int, comment: String) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val rowComment = sanitize(comment)

        WriteCommandAction.runWriteCommandAction(project, "Add Markdown Comment", null, {
            if (sourceOffset < 0 || sourceOffset > document.textLength) return@runWriteCommandAction
            val anchor = ensureAnchor(document, document.getLineNumber(sourceOffset) + 1)
                ?: return@runWriteCommandAction
            val row = "| $anchor | $rowComment | false |"
            val text = document.text

            if (findTableMarker(text) == null) {
                val block = buildString {
                    if (text.isNotEmpty() && !text.endsWith("\n")) append("\n")
                    append("\n")
                    append(MARKER).append("\n")
                    append(SECTION_HEADING).append("\n\n")
                    append(HEADER_ROW).append("\n")
                    append(DIVIDER_ROW).append("\n")
                    append(row).append("\n")
                }
                document.insertString(document.textLength, block)
            } else {
                if (!document.text.endsWith("\n")) {
                    document.insertString(document.textLength, "\n")
                }
                document.insertString(document.textLength, row + "\n")
            }
        })
    }

    /**
     * Removes every trace of commenting from the file: drops the whole
     * comments table (from the `<!-- markdown-comments -->` marker to EOF)
     * and strips every `<!-- md-comment:<id> -->` anchor from the source
     * lines. Runs in one write action, so it's a single undoable edit.
     */
    fun removeAllComments(project: Project, file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Remove All Markdown Comments", null, {
            // 1. Drop the table: marker line (plus the blank line before it)
            // through end of file.
            val markerIndex = findTableMarker(document.text)
            if (markerIndex != null) {
                val lineStart = document.getLineStartOffset(document.getLineNumber(markerIndex))
                val deleteStart = if (lineStart > 0) lineStart - 1 else lineStart
                document.deleteString(deleteStart, document.textLength)
            }
            // 2. Strip every anchor comment from the remaining source lines.
            //    Delete back-to-front so earlier offsets stay valid.
            val matches = ANCHOR_REGEX.findAll(document.text).toList()
            for (match in matches.asReversed()) {
                val end = match.range.last + 1
                var start = match.range.first
                // Anchors are appended at end of line (" <!-- md-comment:x -->");
                // when one sits at line end, eat the whitespace before it too.
                val atLineEnd = end >= document.textLength ||
                    document.getText(TextRange(end, end + 1)) in "\r\n"
                if (atLineEnd) {
                    while (start > 0 && document.getText(TextRange(start - 1, start)) in " \t") start--
                }
                document.deleteString(start, end)
            }
        })
    }

    /**
     * Returns the existing anchor on [lineNumber] (1-based) if present,
     * otherwise generates one and appends it to the end of that line as an
     * HTML comment (invisible in the rendered preview).
     */
    private fun ensureAnchor(document: Document, lineNumber: Int): String? {
        if (lineNumber < 1 || lineNumber > document.lineCount) return null
        val lineIndex = lineNumber - 1
        val start = document.getLineStartOffset(lineIndex)
        val end = document.getLineEndOffset(lineIndex)
        val lineText = document.getText(TextRange(start, end))

        val existing = ANCHOR_REGEX.find(lineText)
        if (existing != null) return existing.groupValues[1]

        val anchor = UUID.randomUUID().toString().substring(0, 8)
        document.insertString(end, " <!-- md-comment:$anchor -->")
        return anchor
    }

    /**
     * Character offset of the marker line of a *real* comments table, or null
     * when the file has none.
     *
     * A bare `text.contains(MARKER)` is not enough: README-style docs render
     * the marker inside a ``` fenced code block, and matching that sample
     * would treat it as a live table (a comment click then appends a bare
     * row at EOF with no heading/header). So the marker only counts when it
     * is a standalone line outside any fence, followed by the `## Comments`
     * heading, the `anchor/comment/resolved` header and a dashed divider.
     *
     * The header/divider are compared as parsed cells rather than exact
     * strings: the IDE's Markdown formatter pads separator and cell widths
     * (`|---|---|----------|`), so an exact-string match would miss tables
     * the editor has reformatted.
     */
    private fun findTableMarker(text: String): Int? {
        var inFence = false
        var offset = 0
        val lines = text.lineSequence().toList()
        for (i in lines.indices) {
            val line = lines[i]
            val indent = line.takeWhile { it.isWhitespace() }.length
            when {
                line.trim().startsWith("```") || line.trim().startsWith("~~~") -> inFence = !inFence
                inFence || indent >= 4 -> Unit // fenced or indented code block
                line.trim() != MARKER -> Unit
                else -> {
                    val following = lines.drop(i + 1)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (isCommentsTable(following)) {
                        return offset + line.indexOf(MARKER)
                    }
                }
            }
            offset += line.length + 1 // +1 for the trailing newline
        }
        return null
    }

    /** `## Comments` heading, `anchor/comment/resolved` header, dashed divider. */
    private fun isCommentsTable(following: List<String>): Boolean {
        if (following.size < 3 || following[0] != SECTION_HEADING) return false
        if (tableCells(following[1]) != listOf("anchor", "comment", "resolved")) return false
        return tableCells(following[2]).all { it.matches(DIVIDER_CELL) }
    }

    private fun tableCells(line: String): List<String> =
        line.split("|").map { it.trim() }.filter { it.isNotEmpty() }

    /** Anchor id found between the given offsets, or null. */
    fun existingAnchor(document: Document, start: Int, end: Int): String? {
        return ANCHOR_REGEX.find(document.getText(TextRange(start, end)))?.groupValues?.get(1)
    }

    /**
     * Source-offset ranges (line start..line end) of lines carrying a comment
     * anchor whose table row is not marked resolved. Used to highlight
     * already-commented blocks in the rendered preview.
     */
    fun commentedLineRanges(document: Document): List<Pair<Int, Int>> {
        val text = document.text
        val resolved = resolvedAnchorIds(text)
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until document.lineCount) {
            val start = document.getLineStartOffset(i)
            val end = document.getLineEndOffset(i)
            val match = ANCHOR_REGEX.find(text.substring(start, end)) ?: continue
            if (match.groupValues[1] in resolved) continue
            ranges.add(start to end)
        }
        return ranges
    }

    /** Ranges of anchored lines whose comments are all marked resolved. */
    fun resolvedLineRanges(document: Document): List<Pair<Int, Int>> {
        val text = document.text
        val resolved = resolvedAnchorIds(text)
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until document.lineCount) {
            val start = document.getLineStartOffset(i)
            val end = document.getLineEndOffset(i)
            val match = ANCHOR_REGEX.find(text.substring(start, end)) ?: continue
            if (match.groupValues[1] !in resolved) continue
            ranges.add(start to end)
        }
        return ranges
    }

    /** Anchor ids where every table row has `true` in the resolved column. */
    private fun resolvedAnchorIds(text: String): Set<String> {
        val anchors = mutableSetOf<String>()
        val unresolved = mutableSetOf<String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) continue
            // split("|") on "| a | b | c |" -> ["", "a", "b", "c", ""]
            val cells = trimmed.split("|").map { it.trim().replace("\\|", "|") }
            if (cells.size < 5) continue
            val id = cells[1]
            anchors.add(id)
            // Any non-true row makes the whole anchor unresolved, however
            // many resolved rows it has.
            if (!cells[3].equals("true", ignoreCase = true)) unresolved.add(id)
        }
        return anchors - unresolved
    }

    /**
     * Keep a single comment confined to one table row: escape pipes, turn
     * newlines into <br> so multiline comments still render in the table.
     */
    private fun sanitize(comment: String): String {
        return comment
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", "\n")
            .replace("\n", "<br>")
            .trim()
    }
}
