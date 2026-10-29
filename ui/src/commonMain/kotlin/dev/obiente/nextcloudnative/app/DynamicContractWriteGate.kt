package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
enum class DynamicContractVersionStatus {
    VerifiedCurrent,
    LastKnownReadOnly,
}

internal fun DynamicContractVersionStatus.allows(risk: ActionRisk): Boolean =
    this == DynamicContractVersionStatus.VerifiedCurrent || risk == ActionRisk.readOnly

/** The contract a discovery result authorizes writes for, or null when it authorizes none. */
internal fun DynamicDescriptorDiscovery.writeAuthority(): DynamicAppDescriptor? =
    descriptor.takeIf { versionStatus == DynamicContractVersionStatus.VerifiedCurrent }

/**
 * Holds writes while a previously verified contract is checked again, instead of withdrawing the
 * write actions for the length of the check.
 *
 * The workspace keeps offering the verified contract's actions during the check, so they do not
 * vanish and return. A write started meanwhile waits here before any request is sent, and only
 * continues when the check confirms the same contract it was offered from. A failed check, a
 * changed contract, or an abandoned check rejects it, so nothing runs against an unverified
 * contract. Reads never wait.
 */
class DynamicContractWriteGate {
    private sealed interface State {
        data object Checking : State
        data class Settled(val authorized: DynamicAppDescriptor?) : State
        data object Unverified : State
    }

    private val state = MutableStateFlow<State>(State.Unverified)

    /** A check of the current contract has started; writes wait for its outcome. */
    fun beginCheck() {
        state.value = State.Checking
    }

    /** The check finished; only writes offered from [authorized] may continue. */
    fun settle(authorized: DynamicAppDescriptor?) {
        state.value = State.Settled(authorized)
    }

    /** The check stopped without an outcome; waiting and later writes are rejected. */
    fun abandon() {
        if (state.value == State.Checking) state.value = State.Unverified
    }

    /** Waits for any running check and reports whether [descriptor] may perform a write. */
    suspend fun authorizesWrite(risk: ActionRisk, descriptor: DynamicAppDescriptor): Boolean {
        if (risk == ActionRisk.readOnly) return true
        val outcome = state.first { it != State.Checking }
        return outcome is State.Settled && outcome.authorized == descriptor
    }
}
