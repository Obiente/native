package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

internal class PhotoTimelineUiState(
    private val accountKey: String,
    private val producer: AccountPrivateMemoryProducer?,
    private val gate: AccountPrivateMemoryGate,
) {
    private var retired = false
    private val clearValues = mutableListOf<() -> Unit>()
    val timeline = retainedState(PhotoTimelineState(pageSize = MAX_PHOTO_TIMELINE_PAGE_SIZE))
    val backupStatuses = retainedState<Map<String, MediaBackupStatus>>(emptyMap())
    val initialLoadCompleted = retainedState(false)

    fun retire() {
        retired = true
        clearValues.forEach { it() }
    }

    private fun <T> retainedState(empty: T): MutableState<T> = object : MutableState<T> {
        private val state = mutableStateOf(empty).also { value -> clearValues += { value.value = empty } }
        override var value: T
            get() {
                var result = empty
                gate.mutate(accountKey, producer) { if (!retired) result = state.value }
                return result
            }
            set(value) { gate.mutate(accountKey, producer) { if (!retired) state.value = value } }
        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }
}

internal object PhotoTimelineUiStateRepository {
    private const val MAXIMUM_ACCOUNT_STATES = 4
    private val gate = sharedAccountPrivateMemoryGate
    private val accountStates = linkedMapOf<String, PhotoTimelineUiState>()

    fun stateFor(session: NextcloudSession): PhotoTimelineUiState {
        val key = session.accountId.storageKey
        var state = PhotoTimelineUiState(key, null, gate)
        val producer = gate.producer(key)
        gate.mutate(key, producer) {
            state = accountStates.remove(key) ?: PhotoTimelineUiState(key, producer, gate)
            accountStates[key] = state
            while (accountStates.size > MAXIMUM_ACCOUNT_STATES) {
                accountStates.remove(accountStates.keys.first())?.retire()
            }
        }
        return state
    }

    fun removeAccount(accountStorageKey: String) = gate.retireAccount(accountStorageKey) {
        purgeRetiredAccount(accountStorageKey)
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) = gate.withLock {
        accountStates.remove(accountStorageKey)?.retire()
    }
}
