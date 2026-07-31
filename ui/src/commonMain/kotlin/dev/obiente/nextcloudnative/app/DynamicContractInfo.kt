package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicChildCandidateStatus
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.explainDynamicChildNavigation

data class DynamicContractInfo(
    val acquisition: String,
    val appVersion: String,
    val sourceSpecFile: String,
    val diagnosticCodes: List<String>,
    val resourceIds: List<String>,
    val layoutIds: List<String>,
    val linkIds: List<String>,
    val actionIds: List<String>,
    val childCandidates: List<DynamicContractChildInfo>,
)

data class DynamicContractChildInfo(
    val resourceId: String,
    val actionId: String,
    val layoutId: String?,
    val status: DynamicChildCandidateStatus,
    val missingContextParameters: List<String>,
)

fun DynamicDescriptorDiscovery.toContractInfo(
    recordContext: DynamicResourceRecordContext?,
): DynamicContractInfo = DynamicContractInfo(
    acquisition = acquisition.safeLabel(),
    appVersion = descriptor.app.version.safeVersion(),
    sourceSpecFile = sourcePath.safeSpecFilename(),
    diagnosticCodes = diagnostics.mapNotNull(String::safeDiagnosticCode).distinct(),
    resourceIds = descriptor.resources.map { it.id.safeContractId() },
    layoutIds = descriptor.layouts.map { it.id.safeContractId() },
    linkIds = descriptor.links.map { it.id.safeContractId() },
    actionIds = descriptor.actions.map { it.id.safeContractId() },
    childCandidates = recordContext?.let { context ->
        descriptor.explainDynamicChildNavigation(context).map { candidate ->
            DynamicContractChildInfo(
                resourceId = candidate.resourceId.safeContractId(),
                actionId = candidate.actionId.safeContractId(),
                layoutId = candidate.layoutId?.safeContractId(),
                status = candidate.status,
                missingContextParameters = candidate.missingContextParameters.map(String::safeContractId),
            )
        }
    }.orEmpty(),
)

private fun DynamicDescriptorAcquisition.safeLabel(): String = when (this) {
    DynamicDescriptorAcquisition.OcsApiViewer -> "OCS API Viewer"
    DynamicDescriptorAcquisition.StaticAppAsset -> "Static app asset"
    DynamicDescriptorAcquisition.SignedAppStorePackage -> "Signed App Store package"
    DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes -> "Verified signed static read routes"
    DynamicDescriptorAcquisition.SignedAppStoreMergedContract -> "Signed API contract with read fallbacks"
    DynamicDescriptorAcquisition.AppStoreLinkedGitHubTag -> "App Store-linked source tag"
    DynamicDescriptorAcquisition.AppStoreLinkedStaticRoutes -> "Verified linked static read routes"
    DynamicDescriptorAcquisition.AppStoreLinkedMergedContract -> "Linked API contract with read fallbacks"
    DynamicDescriptorAcquisition.MetadataFallback -> "Metadata fallback"
}

private fun String?.safeSpecFilename(): String {
    val filename = this
        ?.let { source -> source.substringAfterLast('#', source) }
        ?.substringBefore('?')
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?: return "Unavailable"
    return filename.safeContractId()
}

private fun String.safeVersion(): String = takeIf { value ->
    value.length in 1..48 && value.all { it.isLetterOrDigit() || it in ".+-_" }
} ?: "Unavailable"

private fun String.safeContractId(): String {
    val safe = take(96).filter { it.isLetterOrDigit() || it in ".-_" }
    return safe.takeIf(String::isNotBlank) ?: "Unavailable"
}

private fun String.safeDiagnosticCode(): String? = when {
    contains("viewer", ignoreCase = true) && contains("not installed", ignoreCase = true) ->
        "api-viewer-unavailable"
    contains("static OpenAPI", ignoreCase = true) -> "static-contract-unavailable"
    contains("App Store contract acquisition failed", ignoreCase = true) -> "app-store-acquisition-failed"
    contains("derived", ignoreCase = true) && contains("read-only endpoints", ignoreCase = true) ->
        "verified-static-read-routes"
    contains("verified read", ignoreCase = true) && contains("fallback routes", ignoreCase = true) ->
        "verified-read-fallbacks"
    contains("signed App Store package", ignoreCase = true) -> "signed-package-imported"
    contains("GitHub release tag", ignoreCase = true) -> "linked-source-tag-imported"
    contains("invalid OpenAPI", ignoreCase = true) || contains("could not be compiled", ignoreCase = true) ->
        "contract-invalid"
    contains("metadata-only", ignoreCase = true) ||
        contains("Only app metadata is available", ignoreCase = true) -> "metadata-only"
    else -> null
}

internal fun DynamicContractInfo.countSummary(): String =
    "${resourceIds.size} resources · ${layoutIds.size} layouts · ${linkIds.size} links · ${actionIds.size} actions"

internal fun DynamicContractChildInfo.reasonLabel(): String = when (status) {
    DynamicChildCandidateStatus.included -> "Included"
    DynamicChildCandidateStatus.selfEdge -> "Omitted: self edge"
    DynamicChildCandidateStatus.cycle -> "Omitted: cycle"
    DynamicChildCandidateStatus.missingContext -> "Omitted: missing context"
    DynamicChildCandidateStatus.ancestorOnlyContext -> "Omitted: ancestor context only"
    DynamicChildCandidateStatus.noLayout -> "Omitted: no layout"
    DynamicChildCandidateStatus.noLink -> "Omitted: no link"
}
