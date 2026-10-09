package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionFailureKind
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
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
    request: ContractAcquisitionRequest,
    acquire: (ContractAcquisitionRequest) -> VerifiedOpenApiContract?,
): AcquiredOpenApiContract? {
    val contract = try {
        acquire(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw AppStoreContractAcquisitionException(classifyContractAcquisitionFailure(failure).toAppStoreKind(), failure)
    } catch (failure: LinkageError) {
        throw AppStoreContractAcquisitionException(AppStoreContractFailureKind.RuntimeIncompatible, failure)
    }
    return contract?.toAcquiredOpenApiContract()
}

private fun ContractAcquisitionFailureKind.toAppStoreKind(): AppStoreContractFailureKind = when (this) {
    ContractAcquisitionFailureKind.Network -> AppStoreContractFailureKind.Network
    ContractAcquisitionFailureKind.SourceUnavailable -> AppStoreContractFailureKind.SourceUnavailable
    ContractAcquisitionFailureKind.VerificationFailed -> AppStoreContractFailureKind.VerificationFailed
    ContractAcquisitionFailureKind.InvalidRequest -> AppStoreContractFailureKind.InvalidRequest
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
