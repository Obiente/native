package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class SupportSettingsDraftState private constructor(
    private val access: SupportSettingsDraftAccess,
) {
    constructor() : this(StandaloneSupportSettingsDraftAccess())

    private var storedReportDraft by mutableStateOf("")
    private val replyDrafts = mutableStateMapOf<String, String>()

    val reportDraft: String
        get() = access.read("") { storedReportDraft }

    fun updateReportDraft(value: String) {
        access.mutate { storedReportDraft = value.take(MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH) }
    }

    fun replyDraft(recordId: String): String = access.read("") { replyDrafts[recordId].orEmpty() }

    fun updateReplyDraft(recordId: String, value: String) {
        val bounded = value.take(MAX_SUPPORT_CONVERSATION_MESSAGE_LENGTH)
        access.mutate {
            if (bounded.isEmpty()) replyDrafts.remove(recordId) else replyDrafts[recordId] = bounded
        }
    }

    fun retainReplyDrafts(recordIds: Set<String>) {
        access.mutate {
            replyDrafts.keys.toList().filterNot(recordIds::contains).forEach(replyDrafts::remove)
        }
    }

    fun clearDrafts() {
        access.mutate(::clearDraftsUnchecked)
    }

    fun hasDraftContent(): Boolean = access.read(false) {
        storedReportDraft.isNotEmpty() || replyDrafts.isNotEmpty()
    }

    internal fun purgeRetiredAccount() = access.purge(::clearDraftsUnchecked)

    private fun clearDraftsUnchecked() {
        storedReportDraft = ""
        replyDrafts.clear()
    }

    internal companion object {
        fun account(
            gate: AccountPrivateMemoryGate,
            producer: AccountPrivateMemoryProducer,
        ) = SupportSettingsDraftState(AccountSupportSettingsDraftAccess(gate, producer))

        fun inactive() = SupportSettingsDraftState(InactiveSupportSettingsDraftAccess)
    }
}

private interface SupportSettingsDraftAccess {
    fun <T> read(unavailable: T, action: () -> T): T

    fun mutate(action: () -> Unit)

    fun purge(action: () -> Unit)
}

private class StandaloneSupportSettingsDraftAccess : SupportSettingsDraftAccess {
    private val lock = DynamicNativeMemoryCacheLock()

    override fun <T> read(unavailable: T, action: () -> T): T = lock.withLock(action)

    override fun mutate(action: () -> Unit) = lock.withLock(action)

    override fun purge(action: () -> Unit) = lock.withLock(action)
}

private class AccountSupportSettingsDraftAccess(
    private val gate: AccountPrivateMemoryGate,
    private val producer: AccountPrivateMemoryProducer,
) : SupportSettingsDraftAccess {
    override fun <T> read(unavailable: T, action: () -> T): T = gate.read(producer, unavailable, action)

    override fun mutate(action: () -> Unit) {
        gate.mutate(producer.accountStorageKey, producer, action)
    }

    override fun purge(action: () -> Unit) = gate.withLock(action)
}

private object InactiveSupportSettingsDraftAccess : SupportSettingsDraftAccess {
    override fun <T> read(unavailable: T, action: () -> T): T = unavailable

    override fun mutate(action: () -> Unit) = Unit

    override fun purge(action: () -> Unit) = Unit
}

internal fun supportReplyMessageByteCount(value: String): Int = value.encodeToByteArray().size
internal fun supportReplyMessageIsWithinLimit(value: String): Boolean =
    supportReplyMessageByteCount(value) <= MAX_SUPPORT_REPLY_MESSAGE_BYTES
internal const val MAX_SUPPORT_REPLY_MESSAGE_BYTES = 8 * 1_024
