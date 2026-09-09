package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class SupportSettingsDraftState {
    var reportDraft by mutableStateOf("")
        private set
    private val replyDrafts = mutableStateMapOf<String, String>()

    fun updateReportDraft(value: String) {
        reportDraft = value.take(MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH)
    }

    fun replyDraft(recordId: String): String = replyDrafts[recordId].orEmpty()

    fun updateReplyDraft(recordId: String, value: String) {
        val bounded = value.take(MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH)
        if (bounded.isEmpty()) replyDrafts.remove(recordId) else replyDrafts[recordId] = bounded
    }

    fun retainReplyDrafts(recordIds: Set<String>) {
        replyDrafts.keys.toList().filterNot(recordIds::contains).forEach(replyDrafts::remove)
    }

    fun clearDrafts() {
        reportDraft = ""
        replyDrafts.clear()
    }

    fun hasDraftContent(): Boolean = reportDraft.isNotEmpty() || replyDrafts.isNotEmpty()
}

internal fun supportReplyMessageByteCount(value: String): Int = value.encodeToByteArray().size
internal fun supportReplyMessageIsWithinLimit(value: String): Boolean =
    supportReplyMessageByteCount(value) <= MAX_SUPPORT_REPLY_MESSAGE_BYTES
internal const val MAX_SUPPORT_REPLY_MESSAGE_BYTES = 8 * 1_024
