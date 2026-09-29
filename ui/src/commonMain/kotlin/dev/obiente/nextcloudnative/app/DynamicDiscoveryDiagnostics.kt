package dev.obiente.nextcloudnative.app

internal enum class DynamicDiscoveryStage(val diagnosticValue: String) {
    PackageAcquisition("package_acquisition"),
    ContractParsing("contract_parsing"),
    DescriptorCompilation("descriptor_compilation"),
}

/** Discovery diagnostics contain only bounded stage/outcome tokens, never contract or exception text. */
internal suspend fun <T> observeDynamicDiscoveryStage(
    stage: DynamicDiscoveryStage,
    record: (SupportDiagnosticEventDraft) -> Unit,
    block: suspend () -> T,
): Result<T> {
    val result = runCatchingPreservingCancellation(block)
    val outcome = when {
        result.isFailure -> "failed"
        result.getOrNull() == null -> "unavailable"
        else -> "completed"
    }
    // Diagnostics are supplementary: a recorder failure must not discard a usable contract.
    runCatchingPreservingCancellation {
        record(SupportDiagnosticEventDraft(
            severity = if (outcome == "completed") SupportDiagnosticSeverity.Info else SupportDiagnosticSeverity.Warning,
            component = SupportDiagnosticComponent.AdaptiveApps,
            operation = "contract.discover",
            outcome = outcome,
            code = "DYNAMIC_DISCOVERY_STAGE",
            fields = listOf(SupportDiagnosticFieldDraft("stage", stage.diagnosticValue)),
        ))
    }
    return result
}
