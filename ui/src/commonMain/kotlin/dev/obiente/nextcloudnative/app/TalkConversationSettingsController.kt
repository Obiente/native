package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Immutable
internal data class TalkConversationSettingsUiState(
    val settings: TalkConversationSettings? = null,
    val participants: List<TalkParticipant>? = null,
    val loadFailure: String? = null,
    val applying: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * Settings for one Talk conversation. Writes use the account's Talk mutation transport; each is
 * checked against a fresh read, and an unknown result is never retried automatically.
 */
internal class TalkConversationSettingsController(
    private val scope: CoroutineScope,
    private val token: String,
    private val execute: suspend (TalkTransportRequest) -> TalkTransportResponse,
) {
    var state by mutableStateOf(TalkConversationSettingsUiState())
        private set

    fun refresh() {
        scope.launch {
            val settings = readSettings()
            val participants = readParticipants()
            state = state.copy(
                settings = settings ?: state.settings,
                participants = participants ?: state.participants,
                loadFailure = if (settings == null) "The conversation settings could not be loaded." else null,
            )
        }
    }

    fun dismissMessage() {
        state = state.copy(message = null, messageIsError = false)
    }

    fun apply(change: TalkConversationChange) {
        val current = state.settings ?: return
        if (state.applying || !current.allows(change)) return
        state = state.copy(applying = true, message = null, messageIsError = false)
        scope.launch {
            val outcome = try {
                talkWriteOutcome(execute(TalkSettingsRequests.change(token, change))) { Unit }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Transport fault boundary: the change may or may not have been stored.
                TalkWriteOutcome.Unknown
            }
            val fresh = readSettings()
            val participants = if (change.isMembershipChange()) readParticipants() else state.participants
            val verified = fresh?.reflects(change) == true && (participants?.reflects(change) ?: !change.isMembershipChange())
            val (message, isError) = when {
                outcome is TalkWriteOutcome.Rejected -> outcome.failure.message to true
                verified -> change.successMessage() to false
                else -> "The server did not confirm the change. The settings were refreshed; check them before trying again." to true
            }
            state = state.copy(
                settings = fresh ?: state.settings,
                participants = participants,
                applying = false,
                message = message,
                messageIsError = isError,
            )
        }
    }

    private suspend fun readSettings(): TalkConversationSettings? = readOrNull {
        parseTalkConversationSettings(execute(TalkChatRequests.conversation(token)))
    }

    private suspend fun readParticipants(): List<TalkParticipant>? = readOrNull {
        parseTalkParticipants(execute(TalkSettingsRequests.participants(token)))
    }
}

/** Client-side gate mirroring Spreed's moderator requirements; the server still decides. */
internal fun TalkConversationSettings.allows(change: TalkConversationChange): Boolean = when (change) {
    is TalkConversationChange.Notifications -> type != TalkConversationType.Changelog
    is TalkConversationChange.Rename -> canRename
    is TalkConversationChange.Describe,
    is TalkConversationChange.ReadOnly,
    -> canModerate && type != TalkConversationType.OneToOne && type != TalkConversationType.NoteToSelf
    is TalkConversationChange.Lobby,
    is TalkConversationChange.RemoveParticipant,
    is TalkConversationChange.Moderator,
    -> canModerate && (type == TalkConversationType.Group || type == TalkConversationType.Public)
    is TalkConversationChange.GuestAccess -> canChangeGuestAccess
}

private fun TalkConversationChange.isMembershipChange(): Boolean =
    this is TalkConversationChange.RemoveParticipant || this is TalkConversationChange.Moderator

internal fun TalkConversationChange.successMessage(): String = when (this) {
    is TalkConversationChange.Rename -> "The conversation was renamed."
    is TalkConversationChange.Describe -> "The description was saved."
    is TalkConversationChange.Notifications -> "Notifications set to ${level.label.lowercase()}."
    is TalkConversationChange.ReadOnly -> if (readOnly) "The conversation is now read-only." else "Participants can write again."
    is TalkConversationChange.Lobby -> if (enabled) "The lobby is on." else "The lobby is off."
    is TalkConversationChange.GuestAccess -> if (allowed) "Guests can join with the link." else "Guest access is off."
    is TalkConversationChange.RemoveParticipant -> "$displayName was removed."
    is TalkConversationChange.Moderator -> if (promote) "The participant is now a moderator." else "The participant is no longer a moderator."
}

/** Talk user privacy preferences, shown in Settings. */
internal class TalkUserSettingsController(
    private val scope: CoroutineScope,
    private val execute: suspend (TalkTransportRequest) -> TalkTransportResponse,
) {
    var settings by mutableStateOf<TalkUserPrivacySettings?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        scope.launch {
            settings = readOrNull { parseTalkUserPrivacy(execute(TalkChatRequests.capabilities())) } ?: settings
            loaded = true
        }
    }

    fun set(key: TalkUserSettingKey, shared: Boolean) {
        if (saving) return
        saving = true
        message = null
        scope.launch {
            val outcome = try {
                talkWriteOutcome(execute(TalkSettingsRequests.userSetting(key, shared))) { Unit }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TalkWriteOutcome.Unknown
            }
            val fresh = readOrNull { parseTalkUserPrivacy(execute(TalkChatRequests.capabilities())) }
            val actual = when (key) {
                TalkUserSettingKey.ReadStatusPrivacy -> fresh?.shareReadStatus
                TalkUserSettingKey.TypingPrivacy -> fresh?.shareTyping
            }
            settings = fresh ?: settings
            message = when {
                outcome is TalkWriteOutcome.Rejected -> outcome.failure.message
                actual == shared -> null
                else -> "The server did not confirm the change. Check the setting before trying again."
            }
            saving = false
        }
    }
}

private suspend fun <T> readOrNull(block: suspend () -> T?): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Read fault boundary: callers keep their last known state.
    null
}
