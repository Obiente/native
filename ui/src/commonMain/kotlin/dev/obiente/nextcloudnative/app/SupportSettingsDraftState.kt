package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

@Stable
internal class SupportSettingsDraftState(
    refreshRequiredRecordIds: Collection<String> = emptyList(),
) {
    var reportDraft by mutableStateOf("")
        private set
    private val replyDrafts = mutableStateMapOf<String, String>()
    private val repliesRequiringRefresh = mutableStateMapOf<String, Boolean>().apply {
        refreshRequiredRecordIds.forEach { recordId -> put(recordId, true) }
    }

    fun updateReportDraft(value: String) {
        reportDraft = value.take(MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH)
    }

    fun replyDraft(recordId: String): String = replyDrafts[recordId].orEmpty()

    fun updateReplyDraft(recordId: String, value: String) {
        val bounded = value.take(MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH)
        if (bounded.isEmpty()) replyDrafts.remove(recordId) else replyDrafts[recordId] = bounded
    }

    fun replyRequiresRefresh(recordId: String): Boolean = repliesRequiringRefresh[recordId] == true

    fun updateReplyRefreshRequirement(recordId: String, required: Boolean) {
        if (required) repliesRequiringRefresh[recordId] = true else repliesRequiringRefresh.remove(recordId)
    }

    fun clearReplyRefreshRequirements() = repliesRequiringRefresh.clear()

    fun retainReplyDrafts(recordIds: Set<String>) {
        replyDrafts.keys.toList().filterNot(recordIds::contains).forEach(replyDrafts::remove)
        repliesRequiringRefresh.keys.toList().filterNot(recordIds::contains).forEach(repliesRequiringRefresh::remove)
    }

    companion object {
        val Saver = listSaver(
            save = { state -> state.repliesRequiringRefresh.keys.toList() },
            restore = ::SupportSettingsDraftState,
        )
    }
}
