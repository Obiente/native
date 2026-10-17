package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicChildCandidateStatus
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.explainDynamicChildNavigation
import kotlinx.serialization.Serializable

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
    // Codes come from typed discovery events and the acquisition kind, never from diagnostic prose.
    diagnosticCodes = (diagnosticCodes + acquisition.importDiagnosticCodes()).map(DynamicDiagnosticCode::code).distinct(),
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

/** Stable support codes for discovery events. The code strings are a public diagnostic contract. */
@Serializable
enum class DynamicDiagnosticCode(val code: String) {
    ApiViewerUnavailable("api-viewer-unavailable"),
    StaticContractUnavailable("static-contract-unavailable"),
    AppStoreAcquisitionFailed("app-store-acquisition-failed"),
    ServerProbeInconclusive("server-probe-inconclusive"),
    ServerProbeAccessDenied("server-probe-access-denied"),
    ServerContractUnsupported("server-contract-unsupported"),
    VerifiedStaticReadRoutes("verified-static-read-routes"),
    VerifiedReadFallbacks("verified-read-fallbacks"),
    SignedPackageImported("signed-package-imported"),
    LinkedSourceTagImported("linked-source-tag-imported"),
    ContractInvalid("contract-invalid"),
    MetadataOnly("metadata-only"),
}

/** The import a verified App Store contract records, derived from its typed acquisition kind. */
internal fun DynamicDescriptorAcquisition.importDiagnosticCodes(): List<DynamicDiagnosticCode> = when (this) {
    DynamicDescriptorAcquisition.SignedAppStorePackage -> listOf(DynamicDiagnosticCode.SignedPackageImported)
    DynamicDescriptorAcquisition.AppStoreLinkedGitHubTag -> listOf(DynamicDiagnosticCode.LinkedSourceTagImported)
    DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
    DynamicDescriptorAcquisition.AppStoreLinkedStaticRoutes,
    -> listOf(DynamicDiagnosticCode.VerifiedStaticReadRoutes)
    DynamicDescriptorAcquisition.SignedAppStoreMergedContract,
    DynamicDescriptorAcquisition.AppStoreLinkedMergedContract,
    -> listOf(DynamicDiagnosticCode.VerifiedReadFallbacks)
    DynamicDescriptorAcquisition.OcsApiViewer,
    DynamicDescriptorAcquisition.StaticAppAsset,
    DynamicDescriptorAcquisition.MetadataFallback,
    -> emptyList()
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
