package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRuleAuthority
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

/** Keeps execution authority current without recreating an executor on every loaded page. */
@Composable
internal fun rememberDynamicActionExecutor(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    runtimeValues: Map<String, String>,
    versionStatus: DynamicContractVersionStatus,
    writeGate: DynamicContractWriteGate,
    state: NativeScreenState,
    sourceActionId: String?,
    onMultipartUploadSucceeded: (LocalUploadFile) -> Unit,
): DynamicNextcloudActionExecutor {
    val authority by rememberUpdatedState((state as? NativeScreenState.Ready)?.let { ready ->
        sourceActionId?.let { NativeRuleAuthority(ready, it, runtimeValues) }
    })
    val release by rememberUpdatedState(onMultipartUploadSucceeded)
    return remember(services, session, descriptor, runtimeValues, versionStatus, writeGate) {
        DynamicNextcloudActionExecutor(services, session, descriptor, runtimeValues, versionStatus, writeGate,
            onMultipartUploadSucceeded = { release(it) }, ruleAuthority = { authority })
    }
}
