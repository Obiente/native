package dev.obiente.nextcloudnative.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal enum class MarkdownEditAction {
    Heading,
    Bold,
    Italic,
    Checklist,
    Quote,
    InlineCode,
    Link,
}

/** Applies a small, predictable Markdown edit while retaining a useful text selection. */
internal fun applyMarkdownEdit(value: TextFieldValue, action: MarkdownEditAction): TextFieldValue = when (action) {
    MarkdownEditAction.Heading -> toggleLinePrefix(value, "# ")
    MarkdownEditAction.Bold -> toggleSelectionWrapper(value, "**", "**", "bold text")
    MarkdownEditAction.Italic -> toggleSelectionWrapper(value, "_", "_", "italic text")
    MarkdownEditAction.Checklist -> toggleLinePrefix(value, "- [ ] ")
    MarkdownEditAction.Quote -> toggleLinePrefix(value, "> ")
    MarkdownEditAction.InlineCode -> toggleSelectionWrapper(value, "`", "`", "code")
    MarkdownEditAction.Link -> wrapSelection(value, "[", "](https://)", "link text")
}

private fun toggleSelectionWrapper(
    value: TextFieldValue,
    prefix: String,
    suffix: String,
    placeholder: String,
): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val hasWrapper = start >= prefix.length &&
        end + suffix.length <= value.text.length &&
        value.text.substring(start - prefix.length, start) == prefix &&
        value.text.substring(end, end + suffix.length) == suffix

    if (hasWrapper) {
        val text = value.text.removeRange(end, end + suffix.length)
            .removeRange(start - prefix.length, start)
        return TextFieldValue(
            text = text,
            selection = TextRange(start - prefix.length, end - prefix.length),
        )
    }
    return wrapSelection(value, prefix, suffix, placeholder)
}

private fun wrapSelection(
    value: TextFieldValue,
    prefix: String,
    suffix: String,
    placeholder: String,
): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end).ifEmpty { placeholder }
    val replacement = prefix + selected + suffix
    return TextFieldValue(
        text = value.text.replaceRange(start, end, replacement),
        selection = TextRange(start + prefix.length, start + prefix.length + selected.length),
    )
}

private fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val firstLineStart = if (start == 0) {
        0
    } else {
        value.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    }
    val lastLineEnd = value.text.indexOf('\n', end).let { if (it < 0) value.text.length else it }
    val block = value.text.substring(firstLineStart, lastLineEnd)
    val lines = if (block.isEmpty()) listOf("") else block.split('\n')
    val nonBlankLines = lines.filter(String::isNotBlank)
    val removePrefix = nonBlankLines.isNotEmpty() && nonBlankLines.all { it.startsWith(prefix) }
    val transformed = lines.joinToString("\n") { line ->
        when {
            line.isBlank() && start != end -> line
            removePrefix -> line.removePrefix(prefix)
            else -> prefix + line
        }
    }
    val delta = transformed.length - block.length
    val selectionStart = when {
        start == end -> (start + if (removePrefix) -prefix.length else prefix.length)
        else -> firstLineStart
    }.coerceIn(0, value.text.length + delta)
    val selectionEnd = when {
        start == end -> selectionStart
        else -> lastLineEnd + delta
    }.coerceIn(selectionStart, value.text.length + delta)
    return TextFieldValue(
        text = value.text.replaceRange(firstLineStart, lastLineEnd, transformed),
        selection = TextRange(selectionStart, selectionEnd),
    )
}
