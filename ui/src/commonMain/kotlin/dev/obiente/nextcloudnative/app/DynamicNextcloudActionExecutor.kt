package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.requireValid
import dev.obiente.nextcloudnative.nativeui.runtime.*

class DynamicNextcloudActionExecutor(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val descriptor: DynamicAppDescriptor,
    private val runtimeContext: Map<String, String> = emptyMap(),
    private val versionStatus: DynamicContractVersionStatus = DynamicContractVersionStatus.VerifiedCurrent,
    private val writeGate: DynamicContractWriteGate? = null,
    private val onMultipartUploadSucceeded: (LocalUploadFile) -> Unit = {},
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val ruleAuthority: () -> NativeRuleAuthority? = { null },
    private val membershipAuthority: () -> NativeItemReadAuthority? = { null },
) : NativeActionExecutor, NativeResultRecoveryReader, NativeRuleRecoveryExecutor {
    private val accountProducer = sharedAccountPrivateMemoryGate.producer(session.accountId.storageKey)

    internal suspend fun stopCheckingRuleOperation(handle: DynamicPendingRuleHandle): Boolean =
        stopCheckingDynamicRuleOperation(services, session, descriptor.app.id, handle, clock(), accountProducer)

    override suspend fun recoverRuleOperations(): NativeActionExecutionResult =
        if (!versionStatus.allows(ActionRisk.mutating) || writeGate?.authorizesWrite(ActionRisk.mutating, descriptor) == false) {
            NativeActionExecutionResult.Failure("Reconnect to verify the app version before checking earlier changes.", NativeActionFailureOutcome.Unknown)
        } else recoverDynamicRuleOperations(services, session, descriptor, clock, accountProducer)

    override suspend fun canRepeatRuleOperation(request: NativeActionRequest.Submit): Boolean {
        val action = descriptor.actions.singleOrNull { it.id == request.action.id } ?: return false
        if (!versionStatus.allows(action.risk) || writeGate?.authorizesWrite(action.risk, descriptor) == false) return false
        return canRepeatDynamicRuleOperation(DynamicWriteContext(services, session, descriptor, action, runtimeContext,
            null, onMultipartUploadSucceeded, clock, ruleAuthority, membershipAuthority, accountProducer), request)
    }

    init {
        descriptor.requireValid()
    }

    override suspend fun readResult(request: NativeActionRequest.Submit): NativeResultRecoveryRead =
        readDynamicResultRecovery(services, session, descriptor, request, runtimeContext)

    override suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult {
        val action = descriptor.actions.firstOrNull { it.id == request.action.id }
            ?: return NativeActionExecutionResult.Failure(
                message = "The dynamic action is no longer available.",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        if (!versionStatus.allows(action.risk) || writeGate?.authorizesWrite(action.risk, descriptor) == false) {
            return NativeActionExecutionResult.Failure(
                message = "Reconnect to verify the server and app versions before changing cloud data.",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        }
        val values = (request as? NativeActionRequest.Submit)?.values.orEmpty()
        val observedInputSchema = (request as? NativeActionRequest.Submit)?.action?.takeIf { submitted ->
            submitted.id == action.id && submitted.binding.allowsObservedBodyFields && action.binding.body?.schema.observesSettingsBody()
        }?.inputSchema
        if (request is NativeActionRequest.Submit && action.requiresConfirmation && !request.confirmed) {
            return NativeActionExecutionResult.Failure(
                message = "Confirm this action before changing server data.",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        }
        (request as? NativeActionRequest.Submit)?.takeIf { action.binding.method != HttpMethod.GET }?.let { submit ->
            val context = DynamicWriteContext(services, session, descriptor, action, runtimeContext, observedInputSchema,
                onMultipartUploadSucceeded, clock, ruleAuthority, membershipAuthority, accountProducer,
                writeIsAuthorized = { versionStatus.allows(action.risk) && writeGate?.authorizesWriteNow(action.risk, descriptor) != false })
            return executeDynamicWrite(context, submit)
        }
        return runCatching {
            val execution = executeDynamicAction(services, session, descriptor, action, values, runtimeContext, observedInputSchema)
            execution.response.toDynamicActionExecutionResult(action).also { result ->
                releaseMultipartUploadAfterSuccess(
                    result = result,
                    file = execution.multipartFile,
                    release = onMultipartUploadSucceeded,
                )
            }
        }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            NativeActionExecutionResult.Failure(failure.message ?: "The dynamic action failed.")
        }
    }
}
