package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import kotlinx.coroutines.launch

internal data class NativeDurableCollectionReorderUiState(
    val orderedRecordIds: List<String>,
    val executing: Boolean,
    val error: String?,
    val recoveryAvailable: Boolean,
    val updateOrder: (List<String>) -> Unit,
    val submit: (List<String>) -> Unit,
    val retryRecovery: () -> Unit,
    val discardRecovery: () -> Unit,
)

/**
 * Owns the crash-safe reorder lifecycle shared by categories, checklist lists, and checklist
 * items. A marker is durably staged before transport; every uncertain or successful send is then
 * reconciled only by a refreshed authoritative collection order.
 */
@Composable
internal fun rememberNativeDurableCollectionReorderState(
    plan: NativeCollectionReorderActionPlan?,
    resourceId: String,
    authoritativeOrder: List<String>,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    draggingRecordId: String?,
    pendingOrder: List<String>?,
    pendingRecoveryRequested: Boolean,
    actionExecutor: NativeActionExecutor,
    pendingMutationStore: NativePendingMutationStore?,
    onPendingChanged: (NativeCollectionReorderActionPlan, List<String>?, Boolean) -> Unit,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
): NativeDurableCollectionReorderUiState {
    val scope = rememberCoroutineScope()
    val planScope = plan?.pendingMutationScope(resourceId)
    var orderedRecordIds by remember(plan?.action?.id, planScope, resourceId) {
        mutableStateOf(authoritativeOrder)
    }
    var executing by remember(plan?.action?.id, planScope, resourceId) { mutableStateOf(false) }
    var error by remember(plan?.action?.id, planScope, resourceId) { mutableStateOf<String?>(null) }
    var recoveryAvailable by remember(plan?.action?.id, planScope, resourceId) { mutableStateOf(false) }
    var requestInFlight by remember(plan?.action?.id, planScope, resourceId) { mutableStateOf(false) }
    var durableRestoreChecked by remember(plan?.action?.id, planScope, resourceId) {
        mutableStateOf(plan == null || pendingMutationStore == null)
    }
    val currentExecutor by rememberUpdatedState(actionExecutor)
    val currentOnPendingChanged by rememberUpdatedState(onPendingChanged)
    val currentOnActionSucceeded by rememberUpdatedState(onActionSucceeded)

    LaunchedEffect(plan?.action?.id, planScope, resourceId, pendingMutationStore) {
        val activePlan = plan
        val store = pendingMutationStore
        if (activePlan == null || store == null) {
            durableRestoreChecked = true
            return@LaunchedEffect
        }
        durableRestoreChecked = false
        executing = true
        val pending = runCatching {
            store.load(nativePendingCollectionReorderKey(activePlan, resourceId))
        }.getOrElse { failure ->
            error = failure.message ?: "The saved order recovery marker could not be read."
            recoveryAvailable = true
            durableRestoreChecked = true
            return@LaunchedEffect
        }
        if (pending == null) {
            currentOnPendingChanged(activePlan, null, false)
            executing = false
            recoveryAvailable = false
        } else {
            val restored = decodeNativePendingCollectionReorder(pending)
            if (restored == null) {
                error = "The saved order recovery marker is invalid."
                recoveryAvailable = true
            } else {
                currentOnPendingChanged(
                    activePlan,
                    restored.orderedRecordIds,
                    restored.recoveryRequested,
                )
            }
        }
        durableRestoreChecked = true
    }

    fun submit(submittedOrder: List<String>) {
        val activePlan = plan ?: return
        val store = pendingMutationStore ?: return
        if (submittedOrder == authoritativeOrder) return
        val request = runCatching { activePlan.requestInOrder(submittedOrder) }.getOrElse { failure ->
            error = failure.message ?: "The new order could not be submitted."
            orderedRecordIds = authoritativeOrder
            return
        }
        val stagedValues = encodeNativePendingCollectionReorder(
            submittedOrder,
            recoveryRequested = false,
        )
        if (stagedValues == null) {
            error = "The new order is too large to stage safely."
            orderedRecordIds = authoritativeOrder
            return
        }
        val pendingKey = nativePendingCollectionReorderKey(activePlan, resourceId)
        currentOnPendingChanged(activePlan, submittedOrder, false)
        requestInFlight = true
        executing = true
        error = null
        recoveryAvailable = false
        scope.launch {
            runCatching { store.save(pendingKey, stagedValues) }.onFailure { failure ->
                error = failure.message ?: "The new order could not be staged safely."
                currentOnPendingChanged(activePlan, null, false)
                orderedRecordIds = authoritativeOrder
                executing = false
                requestInFlight = false
                return@launch
            }
            when (val result = currentExecutor.execute(request)) {
                is NativeActionExecutionResult.Success -> {
                    encodeNativePendingCollectionReorder(submittedOrder, recoveryRequested = true)
                        ?.let { values -> runCatching { store.save(pendingKey, values) } }
                    currentOnPendingChanged(activePlan, submittedOrder, true)
                    currentOnActionSucceeded?.invoke(activePlan.action)
                }
                is NativeActionExecutionResult.Failure -> {
                    error = result.message
                    if (result.outcome.requiresMutationReconciliation()) {
                        encodeNativePendingCollectionReorder(submittedOrder, recoveryRequested = true)
                            ?.let { values -> runCatching { store.save(pendingKey, values) } }
                        currentOnPendingChanged(activePlan, submittedOrder, true)
                        currentOnActionSucceeded?.invoke(activePlan.action)
                    } else {
                        runCatching { store.clear(pendingKey) }
                        currentOnPendingChanged(activePlan, null, false)
                        orderedRecordIds = authoritativeOrder
                        executing = false
                        recoveryAvailable = false
                    }
                }
            }
            requestInFlight = false
        }
    }

    LaunchedEffect(
        authoritativeRecordsKey,
        authoritativeOrder,
        plan?.action?.id,
        planScope,
        pendingOrder,
        pendingRecoveryRequested,
        requestInFlight,
        durableRestoreChecked,
        draggingRecordId,
    ) {
        if (!durableRestoreChecked) return@LaunchedEffect
        val activePlan = plan
        val store = pendingMutationStore
        val validPendingOrder = validPendingNativeCollectionOrder(authoritativeOrder, pendingOrder)
        if (pendingOrder != null && validPendingOrder == null) {
            if (activePlan != null && store != null) {
                runCatching { store.clear(nativePendingCollectionReorderKey(activePlan, resourceId)) }
                    .onFailure { failure ->
                        error = failure.message ?: "The obsolete order marker could not be cleared."
                        executing = true
                        return@LaunchedEffect
                    }
                currentOnPendingChanged(activePlan, null, false)
            }
            orderedRecordIds = authoritativeOrder
            executing = false
            error = "The saved order no longer matches the authoritative collection."
            recoveryAvailable = false
        } else if (validPendingOrder != null && authoritativeOrder == validPendingOrder) {
            orderedRecordIds = authoritativeOrder
            if (activePlan != null && store != null) {
                runCatching { store.clear(nativePendingCollectionReorderKey(activePlan, resourceId)) }
                    .onFailure { failure ->
                        error = failure.message ?: "The confirmed order marker could not be cleared."
                        executing = true
                        return@LaunchedEffect
                    }
                currentOnPendingChanged(activePlan, null, false)
            }
            executing = false
            error = null
            recoveryAvailable = false
        } else if (validPendingOrder != null && !requestInFlight && !pendingRecoveryRequested) {
            orderedRecordIds = validPendingOrder
            executing = true
            recoveryAvailable = false
            if (activePlan != null && store != null) {
                val pendingKey = nativePendingCollectionReorderKey(activePlan, resourceId)
                val values = encodeNativePendingCollectionReorder(validPendingOrder, recoveryRequested = true)
                if (values == null) {
                    error = "The saved order could not be prepared for recovery."
                    recoveryAvailable = true
                    return@LaunchedEffect
                }
                runCatching { store.save(pendingKey, values) }.onFailure { failure ->
                    error = failure.message ?: "The order recovery marker could not be updated."
                    recoveryAvailable = true
                    return@LaunchedEffect
                }
                currentOnPendingChanged(activePlan, validPendingOrder, true)
                currentOnActionSucceeded?.invoke(activePlan.action)
            }
        } else if (validPendingOrder != null && pendingRecoveryRequested && !requestInFlight) {
            orderedRecordIds = validPendingOrder
            executing = true
            error = "The submitted order is awaiting authoritative server confirmation."
            recoveryAvailable = true
        } else if (draggingRecordId == null && !executing) {
            orderedRecordIds = authoritativeOrder
            error = null
            recoveryAvailable = false
        }
    }

    fun retryRecovery() {
        val activePlan = plan ?: return
        val callback = currentOnActionSucceeded ?: return
        recoveryAvailable = false
        executing = true
        error = "Checking the authoritative server order again."
        callback(activePlan.action)
    }

    fun discardRecovery() {
        val activePlan = plan ?: return
        val store = pendingMutationStore ?: return
        recoveryAvailable = false
        executing = true
        scope.launch {
            runCatching { store.clear(nativePendingCollectionReorderKey(activePlan, resourceId)) }
                .onSuccess {
                    currentOnPendingChanged(activePlan, null, false)
                    orderedRecordIds = authoritativeOrder
                    executing = false
                    error = null
                }
                .onFailure { failure ->
                    executing = true
                    recoveryAvailable = true
                    error = failure.message ?: "The saved order recovery marker could not be cleared."
                }
        }
    }

    return NativeDurableCollectionReorderUiState(
        orderedRecordIds = orderedRecordIds,
        executing = executing,
        error = error,
        recoveryAvailable = recoveryAvailable,
        updateOrder = { orderedRecordIds = it },
        submit = ::submit,
        retryRecovery = ::retryRecovery,
        discardRecovery = ::discardRecovery,
    )
}
