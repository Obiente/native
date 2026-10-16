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
}

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

    /** The same-origin server probe was inconclusive and the App Store yielded no contract. */
    ServerProbeInconclusive,
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
}

/** Classifies a failure from the platform acquisition boundary without reading exception text. */
internal fun Throwable.appStoreContractFailureKind(): AppStoreContractFailureKind =
    (this as? AppStoreContractAcquisitionException)?.kind ?: AppStoreContractFailureKind.Unexpected

/** Keeps the metadata fallback and records why no verified contract replaced it. */
internal fun DynamicDescriptorDiscovery.withFallback(
    reason: DynamicContractFallbackReason,
    diagnostic: String,
): DynamicDescriptorDiscovery = copy(diagnostics = diagnostics + diagnostic, fallbackReason = reason)

/**
 * Keeps the metadata fallback when the App Store boundary completed without a contract.
 *
 * Precedence: an inconclusive same-origin probe wins over every App Store no-contract reason,
 * because the server may still offer a contract and checking again can help. A definitive server
 * absence leaves the App Store reason in charge. Diagnostics keep both sources.
 */
internal fun DynamicDescriptorDiscovery.withUnavailable(
    reason: AppStoreContractUnavailableReason,
    appId: String,
    coreVersion: String,
): DynamicDescriptorDiscovery = withFallback(
    fallbackReason.takeIf { it == DynamicContractFallbackReason.ServerProbeInconclusive } ?: reason.fallbackReason(),
    "No App Store contract for $appId on Nextcloud $coreVersion: ${reason.name}. Only app metadata is available.",
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
        "This app's verified API contract cannot be shown as a native view yet. Only app details are shown.",
        "Check again",
    )
    DynamicContractFallbackReason.ClientFault -> DynamicContractFallbackNotice(
        "Contract check failed",
        "nati.ve could not complete the API contract check on this device. " +
            "Try again, and update nati.ve if this continues.",
        "Try again",
    )
}
