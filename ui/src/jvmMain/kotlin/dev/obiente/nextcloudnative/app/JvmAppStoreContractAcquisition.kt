package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.CatalogPolicy
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionFailureKind
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionOutcome
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.ContractUnavailableReason
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
    acquire: (ContractAcquisitionRequest, CatalogPolicy) -> ContractAcquisitionOutcome,
): AppStoreContractAcquisition {
    val catalogPolicy = when (request.catalogPolicy) {
        AppStoreCatalogPolicy.UseCache -> CatalogPolicy.UseCache
        AppStoreCatalogPolicy.RefreshCatalog -> CatalogPolicy.Refresh
    }
    val outcome = try {
        acquire(ContractAcquisitionRequest(request.appId, request.serverVersion, request.installedAppVersion), catalogPolicy)
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
    ContractUnavailableReason.RecentlyChecked -> AppStoreContractUnavailableReason.RecentlyChecked
}

private fun ContractAcquisitionFailureKind.toAppStoreKind(): AppStoreContractFailureKind = when (this) {
    ContractAcquisitionFailureKind.Network -> AppStoreContractFailureKind.Network
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
