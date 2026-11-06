package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.runtime.NativePatternRulePlan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Sensitive recovery evidence, stored exclusively by the encrypted text-draft adapter. */
@Serializable
internal data class DynamicRuleRecoveryEvidence(
    val formatVersion: Int = 1,
    val key: DynamicWriteRuleKey,
    val pending: DynamicPendingRuleOperation,
    val appId: String,
    val appVersion: String,
    val plan: NativePatternRulePlan,
    // Exact operation contracts prevent a changed descriptor from reinterpreting an old payload.
    val write: DynamicAction,
    val read: DynamicAction,
    val sourceRead: DynamicAction,
) {
    override fun toString(): String = "DynamicRuleRecoveryEvidence(<encrypted evidence>)"
}

internal class TextDraftBackedDynamicRuleRecoveryStore(private val draft: TextEditorDraftStore) {
    suspend fun save(value: DynamicRuleRecoveryEvidence) {
        val encoded = recoveryJson.encodeToString(DynamicRuleRecoveryEvidence.serializer(), value)
        check(encoded.encodeToByteArray().size <= MAX_RECOVERY_BYTES) { "Rule recovery evidence is too large." }
        draft.save(TextEditorDraft("", encoded, null, verificationRequired = true))
    }

    suspend fun load(): DynamicRuleRecoveryEvidence? {
        val stored = draft.load() ?: return null
        check(stored.verificationRequired && stored.text.encodeToByteArray().size <= MAX_RECOVERY_BYTES) { "Rule recovery evidence is unreadable." }
        val decoded = try {
            recoveryJson.decodeFromString(DynamicRuleRecoveryEvidence.serializer(), stored.text)
        } catch (_: SerializationException) {
            error("Rule recovery evidence is unreadable.")
        } catch (_: IllegalArgumentException) {
            error("Rule recovery evidence is unreadable.")
        }
        check(decoded.formatVersion == 1) { "Rule recovery evidence has an unsupported version." }
        return decoded
    }

    suspend fun remove() = draft.remove()
}

internal fun NextcloudPlatformServices.ruleRecoveryStore(
    session: NextcloudSession,
    token: String,
): TextDraftBackedDynamicRuleRecoveryStore? = textEditorDraftStore(session, ruleRecoveryPath(token))
    ?.let(::TextDraftBackedDynamicRuleRecoveryStore)

/** Serializes save/reserve with sweeping, so in-progress evidence cannot look orphaned. */
internal suspend fun <T> withRuleRecoveryMaintenance(block: suspend () -> T): T = ruleRecoveryMaintenance.withLock { block() }

internal suspend fun NextcloudPlatformServices.pruneRuleRecovery(session: NextcloudSession, safety: DynamicWriteQuarantineStore) {
    val retained = safety.pendingOperationIds(session.accountId.storageKey).mapTo(mutableSetOf(), ::ruleRecoveryPath)
    textEditorDraftStore(session, ruleRecoveryPath("maintenance"))?.pruneUnreferenced(retained) { draft ->
        if (!draft.verificationRequired || draft.originalText.isNotEmpty() || draft.text.encodeToByteArray().size > MAX_RECOVERY_BYTES) null
        else {
            val evidence = try { recoveryJson.decodeFromString(DynamicRuleRecoveryEvidence.serializer(), draft.text) }
            catch (_: SerializationException) { null }
            catch (_: IllegalArgumentException) { null }
            evidence?.takeIf { it.formatVersion == 1 && it.key.accountDigest == session.accountId.storageKey }
                ?.let { ruleRecoveryPath(it.pending.operationId) }
        }
    }
}

private fun ruleRecoveryPath(token: String): String = "nextcloud-native:rule-operation:$token"
private val ruleRecoveryMaintenance = Mutex()
private val recoveryJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
private const val MAX_RECOVERY_BYTES = 192 * 1024
