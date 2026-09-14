package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object SupportSettingsDraftRegistry {
    private val gate = sharedAccountPrivateMemoryGate
    private val loginDraftState = SupportSettingsDraftState()
    private val states = linkedMapOf<String, SupportSettingsDraftState>()
    private val mutableActivationRevision = MutableStateFlow(0L)
    private val activationRevision = mutableActivationRevision.asStateFlow()
    private val inactiveState = SupportSettingsDraftState.inactive()

    fun loginState(): SupportSettingsDraftState = loginDraftState

    fun stateFor(session: NextcloudSession): SupportSettingsDraftState = stateFor(previewCacheDigest(session))

    internal fun stateFor(accountScopeDigest: String): SupportSettingsDraftState {
        require(accountScopeDigest.length == 64 && accountScopeDigest.all { it in '0'..'9' || it in 'a'..'f' })
        val producer = gate.producer(accountScopeDigest) ?: return inactiveState
        return gate.read(producer, inactiveState) {
            val retained = states.remove(accountScopeDigest)
            if (retained != null) {
                states[accountScopeDigest] = retained
                retained
            } else {
                SupportSettingsDraftState.account(gate, producer).also { created ->
                    states[accountScopeDigest] = created
                    while (states.size > MAX_RETAINED_SUPPORT_DRAFT_ACCOUNTS) {
                        val evictable = states.entries.firstOrNull { (digest, state) ->
                            digest != accountScopeDigest && !state.hasDraftContent()
                        }?.key ?: break
                        states.remove(evictable)
                    }
                }
            }
        }
    }

    internal fun activationRevision(): StateFlow<Long> = activationRevision

    internal fun publishAccountActivated() =
        mutableActivationRevision.update { revision -> revision + 1L }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        states[accountStorageKey]?.let { retired ->
            retired.purgeRetiredAccount()
            states.remove(accountStorageKey)
        }
    }

    internal fun retireAccount(accountStorageKey: String) = gate.retireAccount(accountStorageKey) {
        purgeRetiredAccount(accountStorageKey)
    }

    internal fun activateAccount(accountStorageKey: String) = gate.activateAccount(
        accountStorageKey = accountStorageKey,
        activated = ::publishAccountActivated,
    )
}

private const val MAX_RETAINED_SUPPORT_DRAFT_ACCOUNTS = 4
