package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.launch

@OptIn(ExperimentalRichTextApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun GenericMailMessageDetail(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    message: NativeMailMessageDetailPresentation,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val structured = remember(resource, record) { nativeStructuredDetail(resource, record) }
    val threadMessages = remember(resource, record) { nativeMailThreadPresentations(resource, record) }
    val attachments = structured.sections.filter { section ->
        section.fieldId.lowercase().filter(Char::isLetterOrDigit) in setOf("attachments", "inlineattachments")
    }
    val attachmentItems = remember(attachments) { attachments.flatMap { section -> section.value.mailAttachments() } }
    val htmlBody = remember(message.body, message.htmlBody) {
        message.body?.takeIf { value -> message.htmlBody || value.contains('<') && value.contains('>') }
            ?.let(::sanitizeNativeMailHtml)
    }
    val richTextState = rememberRichTextState()
    LaunchedEffect(htmlBody) {
        if (!htmlBody.isNullOrBlank()) richTextState.setHtml(htmlBody)
    }
    val plainBody = remember(message.body, htmlBody) {
        message.body?.takeIf { htmlBody == null }?.trim()
    }
    val messageActions = remember(schema, resource, record, datasetContext) {
        nativeMailMessageActionPlan(schema, resource, record, datasetContext)
    }
    var runningAction by remember(schema, resource, record) {
        mutableStateOf<NativeMailMessageActionKind?>(null)
    }
    var pendingDestructiveAction by remember(schema, resource, record) {
        mutableStateOf<NativeMailMessageActionPlan?>(null)
    }
    var actionError by remember(schema, resource, record) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun executeMailAction(plan: NativeMailMessageActionPlan) {
        runningAction = plan.kind
        actionError = null
        scope.launch {
            try {
                runCatchingUnlessCancelled {
                    actionExecutor.execute(plan.request())
                }.fold(
                    onSuccess = { result ->
                    when (result) {
                        is NativeActionExecutionResult.Success -> {
                            if (
                                plan.kind in setOf(
                                    NativeMailMessageActionKind.Archive,
                                    NativeMailMessageActionKind.Delete,
                                )
                            ) {
                                onActionSucceeded?.invoke(plan.action)
                            } else {
                                (onInlineActionSucceeded ?: onActionSucceeded)?.invoke(plan.action)
                            }
                        }
                        is NativeActionExecutionResult.Failure -> actionError = result.message
                    }
                    },
                    onFailure = { failure ->
                        actionError = failure.message?.takeIf(String::isNotBlank)
                            ?: "The mail action failed before the server returned a result."
                    },
                )
            } finally {
                runningAction = null
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Text(message.subject, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.extraLarge) {
                Icon(
                    NextcloudIcons.app("mail"),
                    contentDescription = null,
                    modifier = Modifier.padding(NextcloudSpacing.Medium).size(26.dp),
                    tint = NextcloudTheme.colors.appIcon,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    nativeMailSenderLabel(message.sender) ?: "Unknown sender",
                    style = MaterialTheme.typography.titleMedium,
                )
                message.recipients?.let { recipients ->
                    Text(
                        "To $recipients",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            nativeMailTimestampLabel(message.timestamp)?.let { timestamp ->
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (messageActions.all.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
            ) {
                messageActions.all.forEach { plan ->
                    OutlinedButton(
                        enabled = runningAction == null,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (plan.kind == NativeMailMessageActionKind.Delete) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.primary,
                        ),
                        onClick = {
                            if (plan.kind == NativeMailMessageActionKind.Delete) {
                                pendingDestructiveAction = plan
                            } else {
                                executeMailAction(plan)
                            }
                        },
                    ) {
                        if (runningAction == plan.kind) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(plan.label)
                        }
                    }
                }
            }
            actionError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (messageActions.all.isEmpty()) {
            Text(
                "Reading view. Message actions are unavailable here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (threadMessages.size > 1) {
            Text(
                "${threadMessages.size} messages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            threadMessages.forEach { threadMessage ->
                GenericMailThreadMessage(threadMessage)
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                SelectionContainer {
                    if (!htmlBody.isNullOrBlank()) {
                        RichText(
                            state = richTextState,
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(
                            plainBody?.takeIf(String::isNotBlank) ?: "This message has no readable body.",
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        if (message.attachmentCount > 0 || attachmentItems.isNotEmpty()) {
            Text("Attachments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        attachmentItems.forEach { attachment ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Icon(NextcloudIcons.File, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                        listOfNotNull(attachment.mime, attachment.size).joinToString(" · ")
                            .takeIf(String::isNotBlank)?.let { metadata ->
                                Text(
                                    metadata,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            }
        }
        if (attachmentItems.isEmpty()) {
            attachments.forEach { section -> GenericStructuredDetailSection(section) }
        }
    }
    pendingDestructiveAction?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDestructiveAction = null },
            title = { Text("Delete this message?") },
            text = { Text("This removes the message from the mail server. This action may not be reversible.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDestructiveAction = null
                        executeMailAction(plan)
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructiveAction = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun GenericMailThreadMessage(message: NativeMailMessageDetailPresentation) {
    val htmlBody = remember(message.body, message.htmlBody) {
        message.body?.takeIf { value -> message.htmlBody || value.contains('<') && value.contains('>') }
            ?.let(::sanitizeNativeMailHtml)
    }
    val richTextState = rememberRichTextState()
    LaunchedEffect(htmlBody) {
        if (!htmlBody.isNullOrBlank()) richTextState.setHtml(htmlBody)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    nativeMailSenderLabel(message.sender) ?: "Unknown sender",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                nativeMailTimestampLabel(message.timestamp)?.let { timestamp ->
                    Text(
                        timestamp,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                if (!htmlBody.isNullOrBlank()) {
                    RichText(
                        state = richTextState,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        message.body?.takeIf(String::isNotBlank) ?: "This message has no readable body.",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

private data class NativeMailAttachment(val name: String, val mime: String?, val size: String?)

private fun NativeStructuredValue.mailAttachments(): List<NativeMailAttachment> = when (this) {
    is NativeStructuredValue.ListValue -> items.flatMap(NativeStructuredValue::mailAttachments)
    is NativeStructuredValue.ObjectValue -> {
        val values = entries.associate { entry ->
            entry.key.lowercase().filter(Char::isLetterOrDigit) to entry.value.scalarText()
        }
        val name = listOf("filename", "name", "title").firstNotNullOfOrNull(values::get)
        if (name.isNullOrBlank()) emptyList() else listOf(
            NativeMailAttachment(
                name = name,
                mime = listOf("mime", "mimetype", "contenttype").firstNotNullOfOrNull(values::get),
                size = listOf("size", "filesize", "bytes").firstNotNullOfOrNull(values::get)
                    ?.toLongOrNull()
                    ?.formatNativeByteSize(),
            ),
        )
    }
    is NativeStructuredValue.Scalar -> emptyList()
}

private fun NativeStructuredValue.scalarText(): String? = when (this) {
    is NativeStructuredValue.Scalar -> value
    else -> null
}

private fun Long.formatNativeByteSize(): String = when {
    this >= 1_048_576 -> "${(this / 104_857.6).toLong() / 10.0} MB"
    this >= 1_024 -> "${(this / 102.4).toLong() / 10.0} KB"
    else -> "$this B"
}

/** Converts untrusted mail HTML into readable inert text without executing or embedding it. */
internal fun emailBodyToPlainText(html: String): String {
    val output = StringBuilder(html.length.coerceAtMost(64_000))
    var cursor = 0
    var hiddenTag: String? = null
    while (cursor < html.length && output.length < 64_000) {
        if (html[cursor] != '<') {
            if (hiddenTag == null) output.append(html[cursor])
            cursor += 1
            continue
        }
        val close = html.indexOf('>', startIndex = cursor + 1)
        if (close < 0) {
            if (hiddenTag == null) output.append(html[cursor])
            cursor += 1
            continue
        }
        val rawTag = html.substring(cursor + 1, close).trim().lowercase()
        val closing = rawTag.startsWith('/')
        val tagName = rawTag.removePrefix("/").takeWhile { character ->
            character.isLetterOrDigit() || character == '-'
        }
        when {
            hiddenTag != null && closing && tagName == hiddenTag -> hiddenTag = null
            hiddenTag != null -> Unit
            !closing && tagName in setOf("script", "style", "head") -> hiddenTag = tagName
            tagName in setOf("br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "blockquote") -> {
                if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            }
        }
        cursor = close + 1
    }
    return output.toString()
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .lines()
        .map(String::collapseMailWhitespace)
        .dropWhile(String::isBlank)
        .dropLastWhile(String::isBlank)
        .joinToString("\n")
}

private fun String.collapseMailWhitespace(): String {
    val output = StringBuilder(length)
    var previousWhitespace = false
    trim().forEach { character ->
        val whitespace = character == ' ' || character == '\t' || character == '\r'
        if (!whitespace || !previousWhitespace) output.append(if (whitespace) ' ' else character)
        previousWhitespace = whitespace
    }
    return output.toString()
}
