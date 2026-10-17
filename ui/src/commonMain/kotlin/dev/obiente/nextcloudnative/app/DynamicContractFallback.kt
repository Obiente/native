package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/** Typed reason an App Store contract could not be acquired. Product copy derives from this value. */
enum class AppStoreContractFailureKind {
    /** The App Store or a linked source could not be reached. */
    Network,

    /** A contract source answered without the requested release or resource. */
    SourceUnavailable,

    /** A signature, certificate, package identity, or source origin check rejected the source. */
    VerificationFailed,

    /** The app ID or version reported by the server cannot select a release safely. */
    InvalidRequest,

    /** A source returned data that cannot be used, such as a response over its size limit. */
    SourceUnusable,

    /** Contract acquisition code could not load or initialize on this runtime. */
    RuntimeIncompatible,

    /** Any other failure. */
    Unexpected,
}

/** Why the App Store boundary completed normally without a verified contract. */
enum class AppStoreContractUnavailableReason {
    /** The App Store lists no release for the installed or a patch-compatible version. */
    NoMatchingRelease,

    /** The linked source refused or rate limited the last fallback request, so a contract may exist. */
    SourceTemporarilyUnavailable,

    /** Matching releases were inspected and none provides a usable contract or safe read routes. */
    NoUsableContract,

    /** The catalog lists the app, but the entry that would be selected lacks data needed to verify it. */
    MalformedSource,

    /** A catalog document could not be read at all, so no entries were available to check. */
    UnreadableCatalog,

    /** An explicit retry fell inside the catalog refresh window and no cached catalog could be reused. */
    RecentlyChecked,
}

/** Whether App Store acquisition may reuse its cached catalog. Only an explicit user retry refreshes it. */
enum class AppStoreCachePolicy {
    UseCache,

    /** Look past cached catalogs and verified contracts; cached copies stay until replaced. */
    Refresh,

    /** Forget the verified contract cached for this exact request after it proved unusable. */
    DiscardCachedContract,
}

/** What discovery asks of [NextcloudPlatformServices.acquireSignedOpenApiContract]. */
data class AppStoreContractRequest(
    val appId: String,
    val serverVersion: String,
    val installedAppVersion: String?,
    val cachePolicy: AppStoreCachePolicy = AppStoreCachePolicy.UseCache,
)

/** Discovery services whose App Store requests use [policy]; every other member delegates unchanged. */
internal fun NextcloudPlatformServices.withAppStoreCachePolicy(policy: AppStoreCachePolicy): NextcloudPlatformServices {
    if (policy == AppStoreCachePolicy.UseCache) return this
    val delegate = this
    return object : NextcloudPlatformServices by delegate {
        override suspend fun acquireSignedOpenApiContract(request: AppStoreContractRequest): AppStoreContractAcquisition =
            // Only a plain acquisition takes the policy; a discard request keeps its own intent.
            delegate.acquireSignedOpenApiContract(
                if (request.cachePolicy == AppStoreCachePolicy.UseCache) request.copy(cachePolicy = policy) else request,
            )
    }
}

/**
 * The first discovery of an app screen uses the cached catalog. A discovery started by, or after, an
 * explicit retry on that screen asks for a fresh catalog; the platform acquirer bounds how often.
 */
internal fun NextcloudPlatformServices.forDiscoveryAttempt(attempt: Int): NextcloudPlatformServices =
    withAppStoreCachePolicy(if (attempt > 0) AppStoreCachePolicy.Refresh else AppStoreCachePolicy.UseCache)

/** Result of [NextcloudPlatformServices.acquireSignedOpenApiContract]. Failures are thrown instead. */
sealed interface AppStoreContractAcquisition {
    data class Acquired(val contract: AcquiredOpenApiContract) : AppStoreContractAcquisition

    data class Unavailable(val reason: AppStoreContractUnavailableReason) : AppStoreContractAcquisition
}

/**
 * The only failure [NextcloudPlatformServices.acquireSignedOpenApiContract] reports.
 *
 * The message is a fixed token. The original failure remains available as [cause] for local
 * diagnostics and never becomes product copy.
 */
class AppStoreContractAcquisitionException(
    val kind: AppStoreContractFailureKind,
    cause: Throwable? = null,
) : Exception("App Store contract acquisition failed: ${kind.name}.", cause)

/** Why discovery fell back to app metadata. Verified and persisted contracts carry no reason. */
@Serializable
enum class DynamicContractFallbackReason {
    /** Matching releases were inspected and none provides a verified contract for this version. */
    NoVerifiedContract,
    NoMatchingRelease,
    SourceTemporarilyUnavailable,
    MalformedSource,
    UnreadableCatalog,
    RecentlyChecked,
    SourceUnusable,

    /** The same-origin server probe was inconclusive and the App Store yielded no contract. */
    ServerProbeInconclusive,

    /** The server rejected the account's credentials during the probe (HTTP 401). */
    ServerAuthenticationRequired,

    /** The account may not read the server's API catalog or contract (HTTP 403). */
    ServerPermissionDenied,
    VersionUnavailable,
    AppStoreUnreachable,
    AppStoreUnavailable,
    VerificationFailed,
    UnsupportedContract,
    ClientFault,
}

internal fun AppStoreContractFailureKind.fallbackReason(): DynamicContractFallbackReason = when (this) {
    AppStoreContractFailureKind.Network -> DynamicContractFallbackReason.AppStoreUnreachable
    AppStoreContractFailureKind.SourceUnavailable -> DynamicContractFallbackReason.AppStoreUnavailable
    AppStoreContractFailureKind.VerificationFailed -> DynamicContractFallbackReason.VerificationFailed
    AppStoreContractFailureKind.InvalidRequest -> DynamicContractFallbackReason.VersionUnavailable
    AppStoreContractFailureKind.SourceUnusable -> DynamicContractFallbackReason.SourceUnusable
    AppStoreContractFailureKind.RuntimeIncompatible,
    AppStoreContractFailureKind.Unexpected,
    -> DynamicContractFallbackReason.ClientFault
}

internal fun AppStoreContractUnavailableReason.fallbackReason(): DynamicContractFallbackReason = when (this) {
    AppStoreContractUnavailableReason.NoMatchingRelease -> DynamicContractFallbackReason.NoMatchingRelease
    AppStoreContractUnavailableReason.SourceTemporarilyUnavailable ->
        DynamicContractFallbackReason.SourceTemporarilyUnavailable
    AppStoreContractUnavailableReason.NoUsableContract -> DynamicContractFallbackReason.NoVerifiedContract
    AppStoreContractUnavailableReason.MalformedSource -> DynamicContractFallbackReason.MalformedSource
    AppStoreContractUnavailableReason.UnreadableCatalog -> DynamicContractFallbackReason.UnreadableCatalog
    AppStoreContractUnavailableReason.RecentlyChecked -> DynamicContractFallbackReason.RecentlyChecked
}

/** Classifies a failure from the platform acquisition boundary without reading exception text. */
internal fun Throwable.appStoreContractFailureKind(): AppStoreContractFailureKind =
    (this as? AppStoreContractAcquisitionException)?.kind ?: AppStoreContractFailureKind.Unexpected

/**
 * Keeps the metadata fallback and records why no verified contract replaced it.
 *
 * This is the single precedence point for every App Store path that ends without a usable contract:
 * no release selected, no contract, a typed or unexpected acquisition failure, or an acquired contract
 * that cannot be parsed or compiled. Any recorded same-origin probe result wins over the App Store
 * reason, because the server may still offer a contract, or offers one that explains the outcome
 * better. Among probe results, access problems come first, then inconclusive results, then an
 * unsupported contract (see [DynamicServerProbeFailure]). A definitive server absence leaves the App
 * Store reason in charge. Diagnostics always keep both sources.
 */
internal fun DynamicDescriptorDiscovery.withFallback(
    reason: DynamicContractFallbackReason,
    diagnostic: String,
    code: DynamicDiagnosticCode? = null,
): DynamicDescriptorDiscovery = copy(
    diagnostics = diagnostics + diagnostic,
    diagnosticCodes = diagnosticCodes + listOfNotNull(code),
    fallbackReason = fallbackReason.takeIf { it in SERVER_PROBE_FALLBACK_REASONS } ?: reason,
)

/** Keeps the metadata fallback after a typed or unexpected App Store acquisition failure. */
internal fun DynamicDescriptorDiscovery.withAcquisitionFailure(kind: AppStoreContractFailureKind): DynamicDescriptorDiscovery =
    withFallback(
        kind.fallbackReason(),
        "App Store contract acquisition failed: ${kind.name}.",
        DynamicDiagnosticCode.AppStoreAcquisitionFailed,
    )

/** Which step found an acquired App Store contract unusable. */
internal enum class UnusableContractStage(val diagnostic: String) {
    Parsing("The acquired App Store contract contained invalid JSON."),
    Compilation("The acquired App Store contract could not be compiled into a native read surface."),
}

/**
 * Keeps the metadata fallback when an acquired contract cannot be parsed or compiled, and discards the
 * verified contract cached for exactly this request, so the next automatic discovery acquires it again
 * instead of serving it until the cache expires. Other cached contracts stay.
 */
internal suspend fun DynamicDescriptorDiscovery.withUnusableAcquiredContract(
    services: NextcloudPlatformServices,
    request: AppStoreContractRequest,
    stage: UnusableContractStage,
): DynamicDescriptorDiscovery {
    // Invalidation is best effort; the fallback stays truthful even when the cache cannot be cleared.
    runCatchingPreservingCancellation {
        services.acquireSignedOpenApiContract(request.copy(cachePolicy = AppStoreCachePolicy.DiscardCachedContract))
    }
    return withFallback(DynamicContractFallbackReason.UnsupportedContract, stage.diagnostic, DynamicDiagnosticCode.ContractInvalid)
}

/** Keeps the metadata fallback when the App Store boundary completed without a contract. */
internal fun DynamicDescriptorDiscovery.withUnavailable(
    reason: AppStoreContractUnavailableReason,
    appId: String,
    coreVersion: String,
): DynamicDescriptorDiscovery = withFallback(
    reason.fallbackReason(),
    "No App Store contract for $appId on Nextcloud $coreVersion: ${reason.name}. Only app metadata is available.",
    DynamicDiagnosticCode.MetadataOnly,
)

/** Keeps the metadata fallback when no App Store release can be selected without a server version. */
internal fun DynamicDescriptorDiscovery.withVersionUnavailable(): DynamicDescriptorDiscovery = withFallback(
    DynamicContractFallbackReason.VersionUnavailable,
    "The server version is unavailable, so an App Store release could not be selected safely.",
)

private val SERVER_PROBE_FALLBACK_REASONS = setOf(
    DynamicContractFallbackReason.ServerAuthenticationRequired,
    DynamicContractFallbackReason.ServerPermissionDenied,
    DynamicContractFallbackReason.ServerProbeInconclusive,
    DynamicContractFallbackReason.UnsupportedContract,
)

/** Shown when discovery itself fails before producing even a metadata fallback. */
internal const val DYNAMIC_DISCOVERY_FAILURE_MESSAGE = "Could not check this app's native API. Try again."

internal data class DynamicContractFallbackNotice(
    val title: String,
    val message: String,
    val actionLabel: String,
)

/** Product copy for a metadata fallback. It explains what happened and whether checking again can help. */
internal fun DynamicContractFallbackReason?.fallbackNotice(): DynamicContractFallbackNotice = when (this) {
    null -> DynamicContractFallbackNotice(
        "App unavailable",
        "This app's content could not be loaded. Try again.",
        "Try again",
    )
    DynamicContractFallbackReason.NoVerifiedContract -> DynamicContractFallbackNotice(
        "No verified native API",
        "Neither the server nor the signed App Store release for this app version provides an API " +
            "contract or safe read routes. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.NoMatchingRelease -> DynamicContractFallbackNotice(
        "No matching App Store release",
        "The Nextcloud App Store lists no signed release for this app version, so its API cannot be " +
            "verified. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.SourceTemporarilyUnavailable -> DynamicContractFallbackNotice(
        "App source busy",
        "The app's linked source refused or limited requests, so its API contract could not be checked " +
            "yet. Try again later.",
        "Try again",
    )
    DynamicContractFallbackReason.MalformedSource -> DynamicContractFallbackNotice(
        "App Store entry incomplete",
        "The Nextcloud App Store entry for this app version is missing data needed to verify its signed " +
            "release, so no native view is offered. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.ServerProbeInconclusive -> DynamicContractFallbackNotice(
        "Server not checked",
        "The server's API catalog did not respond, returned an error, or limited requests, and the App " +
            "Store offered no verified contract. Check the connection and try again.",
        "Try again",
    )
    DynamicContractFallbackReason.ServerAuthenticationRequired -> DynamicContractFallbackNotice(
        "Sign in again",
        "The server did not accept this account while checking for this app's API, and the App Store " +
            "offered no verified contract. Sign in again to continue.",
        "Try again",
    )
    DynamicContractFallbackReason.ServerPermissionDenied -> DynamicContractFallbackNotice(
        "Server API not permitted",
        "Your account does not have permission to read this server's API catalog, which may be limited " +
            "to administrators, and the App Store offered no verified contract. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.UnreadableCatalog -> DynamicContractFallbackNotice(
        "App Store catalog unreadable",
        "The Nextcloud App Store returned a catalog that could not be read, so no app entries were " +
            "available to check. Try again later.",
        "Try again",
    )
    DynamicContractFallbackReason.RecentlyChecked -> DynamicContractFallbackNotice(
        "Checked moments ago",
        "nati.ve checked the App Store for this app less than a minute ago and has no saved catalog to " +
            "reuse. Wait a minute, then try again.",
        "Try again",
    )
    DynamicContractFallbackReason.SourceUnusable -> DynamicContractFallbackNotice(
        "App Store data unusable",
        "The Nextcloud App Store returned data this version cannot use, such as a response over its size " +
            "limit, so no native view is offered. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.VersionUnavailable -> DynamicContractFallbackNotice(
        "App version not confirmed",
        "The server or app version could not be confirmed, so no matching signed App Store release " +
            "could be selected. Check the connection and try again.",
        "Try again",
    )
    DynamicContractFallbackReason.AppStoreUnreachable -> DynamicContractFallbackNotice(
        "App Store not reachable",
        "The Nextcloud App Store could not be reached to verify this app's API. " +
            "Check the connection and try again.",
        "Try again",
    )
    DynamicContractFallbackReason.AppStoreUnavailable -> DynamicContractFallbackNotice(
        "App Store release unavailable",
        "The Nextcloud App Store or the app's linked source did not provide this release. Try again later.",
        "Try again",
    )
    DynamicContractFallbackReason.VerificationFailed -> DynamicContractFallbackNotice(
        "App package not verified",
        "The signed App Store release for this app could not be verified, so no native view is offered. " +
            "Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.UnsupportedContract -> DynamicContractFallbackNotice(
        "API contract not supported",
        "This app's API contract cannot be shown as a native view yet. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.ClientFault -> DynamicContractFallbackNotice(
        "Contract check failed",
        "nati.ve could not complete the API contract check on this device. " +
            "Try again, and update nati.ve if this continues.",
        "Try again",
    )
}
