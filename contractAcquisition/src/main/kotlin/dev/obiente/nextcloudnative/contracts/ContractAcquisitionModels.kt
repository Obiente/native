package dev.obiente.nextcloudnative.contracts

data class ContractAcquisitionRequest(
    val appId: String,
    val serverVersion: String,
    val installedAppVersion: String? = null,
)

data class VerifiedOpenApiContract(
    val appId: String,
    /** The version actually installed on the server when it could be discovered. */
    val appVersion: String,
    /** The signed release/tag from which this contract was acquired. */
    val contractVersion: String,
    val specFile: String,
    val document: String,
    val catalogUrl: String,
    val packageUrl: String,
    val sourceUrl: String,
    val sourceKind: OpenApiContractSourceKind,
    val contractKind: VerifiedContractKind = VerifiedContractKind.OpenApi,
)

enum class VerifiedContractKind {
    OpenApi,
    VerifiedReadRoutes,
    OpenApiWithVerifiedReadRoutes,
}

enum class OpenApiContractSourceKind {
    SignedAppPackage,
    SignedCompatibleAppPackage,
    AppStoreLinkedExactGitHubTag,
    AppStoreLinkedCompatibleGitHubTag,
}

data class GitHubSourceReference(
    val owner: String,
    val repository: String,
    val tag: String,
)

data class AppStoreRelease(
    val appId: String,
    val version: String,
    val downloadUrl: String,
    val signature: String,
    val signatureDigest: String,
    val certificatePem: String,
    val catalogUrl: String,
    val platformVersionSpec: String? = null,
    val githubSources: List<GitHubSourceReference> = emptyList(),
)

interface AppPackageTrustVerifier {
    fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract
}

data class VerifiedPackageContract(
    val appId: String,
    val appVersion: String,
    val specFile: String,
    val document: String,
    val contractKind: VerifiedContractKind = VerifiedContractKind.OpenApi,
)
