package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** Opaque identity of the pending entry the person explicitly chose, not its private payload. */
data class DynamicPendingRuleHandle(val key: DynamicWriteRuleKey, val operationId: String)

/** Local terminal resolution only. No request is sent and the route remains paused. */
internal suspend fun stopCheckingDynamicRuleOperation(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    appId: String,
    handle: DynamicPendingRuleHandle,
    now: Long,
    producer: AccountPrivateMemoryProducer?,
): Boolean {
    if (handle.key.accountDigest != session.accountId.storageKey || handle.key.appId != dynamicWriteAppKey(appId) ||
        !sharedAccountPrivateMemoryGate.read(producer, false) { true }) return false
    return try {
        val store = services.dynamicWriteQuarantineStore() ?: return false
        withRuleRecoveryMaintenance {
            if (store.record(handle.key)?.pendingVerification?.operationId != handle.operationId) return@withRuleRecoveryMaintenance false
            // The explicit choice identifies this exact path even when its encrypted data is
            // damaged. Keep its token until deletion succeeds so a failed cleanup stays retryable.
            val recovery = services.ruleRecoveryStore(session, handle.operationId) ?: return@withRuleRecoveryMaintenance false
            recovery.remove()
            if (!store.resolve(handle.key, handle.operationId, DynamicPendingRuleResolution.abandonedUnknown, now)) return@withRuleRecoveryMaintenance false
            try {
                services.recordSupportDiagnostic(SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning, component = SupportDiagnosticComponent.AdaptiveApps,
                    operation = "dynamic-rule-recovery", outcome = "abandoned-unknown", code = DynamicDiagnosticCode.WriteRecoveryStopped.code,
                    fields = listOf(SupportDiagnosticFieldDraft("rule_id", handle.key.ruleId),
                        SupportDiagnosticFieldDraft("rule_version", handle.key.ruleVersion.toString())),
                ))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Diagnostics do not change the terminal resolution. */ }
            true
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
