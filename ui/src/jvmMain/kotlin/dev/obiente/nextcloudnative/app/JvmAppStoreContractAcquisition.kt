package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.AcquisitionCachePolicy
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionFailureKind
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionOutcome
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.ContractUnavailableReason
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractRemoval
import dev.obiente.nextcloudnative.contracts.VerifiedContractStore
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import dev.obiente.nextcloudnative.contracts.VerifiedOpenApiContract
import dev.obiente.nextcloudnative.contracts.classifyContractAcquisitionFailure
import kotlinx.coroutines.CancellationException

/**
 * Shared Android and desktop boundary between the contract acquisition module and discovery.
 *
 * Acquisition failures are translated once into [AppStoreContractAcquisitionException]. A
 * [LinkageError] is caught here deliberately: acquisition code that cannot load or initialize on
 * this runtime must become an honest metadata fallback instead of escaping with a class name as
 * its message. Other errors, such as running out of memory, still propagate.
 */
fun acquireAppStoreContract(
    request: AppStoreContractRequest,
    acquirer: SignedAppStoreContractAcquirer,
    onDiagnostic: (SupportDiagnosticEventDraft) -> Unit = {},
): AppStoreContractAcquisition {
    if (request.cachePolicy != AppStoreCachePolicy.DiscardCachedContract) {
        return acquireAppStoreContract(request) { contractRequest, cachePolicy ->
            acquirer.acquireOutcome(contractRequest, cachePolicy).also { outcome ->
                val cacheStore = (outcome as? ContractAcquisitionOutcome.Acquired)?.cacheStore
                if (cacheStore != null && cacheStore != VerifiedContractStore.Stored) {
                    onDiagnostic(verifiedContractStoreSkipped(cacheStore))
                }
            }
        }
    }
    val removal = acquirer.invalidateVerifiedContract(request.toContractAcquisitionRequest())
    if (removal != VerifiedContractRemoval.Removed) onDiagnostic(verifiedContractDeletionFailure(removal))
    return AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoUsableContract)
}

/** A verified contract served from memory because it could not be persisted; only the typed result is recorded. */
private fun verifiedContractStoreSkipped(cacheStore: VerifiedContractStore) = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Warning,
    component = SupportDiagnosticComponent.Cache,
    operation = "contract.cache.store",
    outcome = "not_persisted",
    code = "VERIFIED_CONTRACT_NOT_PERSISTED",
    fields = listOf(SupportDiagnosticFieldDraft("store", cacheStore.name)),
)

/** A cached contract that could not be deleted stays invalidated; only the typed removal is recorded. */
private fun verifiedContractDeletionFailure(removal: VerifiedContractRemoval) = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Warning,
    component = SupportDiagnosticComponent.Cache,
    operation = "contract.cache.discard",
    outcome = "deletion_failed",
    code = "VERIFIED_CONTRACT_DELETION_FAILED",
    fields = listOf(SupportDiagnosticFieldDraft("removal", removal.name)),
)

fun acquireAppStoreContract(
    request: AppStoreContractRequest,
    acquire: (ContractAcquisitionRequest, AcquisitionCachePolicy) -> ContractAcquisitionOutcome,
): AppStoreContractAcquisition {
    val cachePolicy = when (request.cachePolicy) {
        AppStoreCachePolicy.UseCache -> AcquisitionCachePolicy.UseCache
        AppStoreCachePolicy.Refresh -> AcquisitionCachePolicy.Refresh
        AppStoreCachePolicy.DiscardCachedContract ->
            throw IllegalArgumentException("Discarding a cached contract needs the acquirer, not an acquisition.")
    }
    val outcome = try {
        acquire(request.toContractAcquisitionRequest(), cachePolicy)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw AppStoreContractAcquisitionException(classifyContractAcquisitionFailure(failure).toAppStoreKind(), failure)
    } catch (failure: LinkageError) {
        throw AppStoreContractAcquisitionException(AppStoreContractFailureKind.RuntimeIncompatible, failure)
    }
    return when (outcome) {
        is ContractAcquisitionOutcome.Acquired -> AppStoreContractAcquisition.Acquired(outcome.contract.toAcquiredOpenApiContract())
        is ContractAcquisitionOutcome.Unavailable -> AppStoreContractAcquisition.Unavailable(outcome.reason.toAppStoreReason())
    }
}

private fun ContractUnavailableReason.toAppStoreReason(): AppStoreContractUnavailableReason = when (this) {
    ContractUnavailableReason.NoMatchingRelease -> AppStoreContractUnavailableReason.NoMatchingRelease
    ContractUnavailableReason.SourceTemporarilyUnavailable ->
        AppStoreContractUnavailableReason.SourceTemporarilyUnavailable
    ContractUnavailableReason.NoUsableContract -> AppStoreContractUnavailableReason.NoUsableContract
    ContractUnavailableReason.MalformedSource -> AppStoreContractUnavailableReason.MalformedSource
    ContractUnavailableReason.UnreadableCatalog -> AppStoreContractUnavailableReason.UnreadableCatalog
    ContractUnavailableReason.RecentlyChecked -> AppStoreContractUnavailableReason.RecentlyChecked
}

private fun AppStoreContractRequest.toContractAcquisitionRequest() =
    ContractAcquisitionRequest(appId, serverVersion, installedAppVersion)

private fun ContractAcquisitionFailureKind.toAppStoreKind(): AppStoreContractFailureKind = when (this) {
    ContractAcquisitionFailureKind.Network -> AppStoreContractFailureKind.Network
    ContractAcquisitionFailureKind.LinkedSourceUnreachable -> AppStoreContractFailureKind.LinkedSourceUnreachable
    ContractAcquisitionFailureKind.SourceUnavailable -> AppStoreContractFailureKind.SourceUnavailable
    ContractAcquisitionFailureKind.VerificationFailed -> AppStoreContractFailureKind.VerificationFailed
    ContractAcquisitionFailureKind.InvalidRequest -> AppStoreContractFailureKind.InvalidRequest
    ContractAcquisitionFailureKind.SourceUnusable -> AppStoreContractFailureKind.SourceUnusable
    ContractAcquisitionFailureKind.RuntimeIncompatible -> AppStoreContractFailureKind.RuntimeIncompatible
    ContractAcquisitionFailureKind.Unexpected -> AppStoreContractFailureKind.Unexpected
}

private fun VerifiedOpenApiContract.toAcquiredOpenApiContract(): AcquiredOpenApiContract = AcquiredOpenApiContract(
    appId = appId,
    appVersion = appVersion,
    contractVersion = contractVersion,
    specFile = specFile,
    document = document,
    packageUrl = packageUrl,
    sourceUrl = sourceUrl,
    sourceKind = when (sourceKind) {
        OpenApiContractSourceKind.SignedAppPackage -> AcquiredOpenApiContractSourceKind.SignedAppPackage
        OpenApiContractSourceKind.SignedCompatibleAppPackage ->
            AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage
        OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
            AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag
        OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
            AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag
    },
    contractKind = when (contractKind) {
        VerifiedContractKind.OpenApi -> AcquiredContractKind.OpenApi
        VerifiedContractKind.VerifiedReadRoutes -> AcquiredContractKind.VerifiedReadRoutes
        VerifiedContractKind.OpenApiWithVerifiedReadRoutes -> AcquiredContractKind.OpenApiWithVerifiedReadRoutes
    },
)
