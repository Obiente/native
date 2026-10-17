package dev.obiente.nextcloudnative.contracts

/** Why an acquisition completed normally without a verified contract. */
enum class ContractUnavailableReason {
    /** The App Store lists no release of this app for the installed or a patch-compatible version. */
    NoMatchingRelease,

    /**
     * The App Store-linked source host refused or rate limited the last fallback request (HTTP 403
     * or 429). A contract may exist, so the result is not evidence that the release has none.
     */
    SourceTemporarilyUnavailable,

    /** Matching releases were inspected and none provides a usable contract or safe read routes. */
    NoUsableContract,

    /**
     * The catalog lists the app, but an entry that would have been selected lacks a required value
     * such as its certificate, download, signature, or signature digest, so it cannot be verified.
     */
    MalformedSource,

    /** A catalog document was truncated, empty, not JSON, or not an array, so no entries could be read. */
    UnreadableCatalog,

    /**
     * An explicit retry asked for a fresh catalog inside its refresh window, and no cached copy was
     * available to reuse, so no App Store request was made. See [CatalogRefreshThrottle].
     */
    RecentlyChecked,
}

/** The typed result of [SignedAppStoreContractAcquirer.acquireOutcome]. Failures are thrown instead. */
sealed interface ContractAcquisitionOutcome {
    data class Acquired(val contract: VerifiedOpenApiContract) : ContractAcquisitionOutcome

    data class Unavailable(val reason: ContractUnavailableReason) : ContractAcquisitionOutcome
}

/**
 * Ends a refresh early when the refresh window refused it. A cached verified contract is served,
 * because nothing newer can be fetched yet; without one and without a cached catalog, nothing was
 * read, which is [ContractUnavailableReason.RecentlyChecked]. Returns null to continue normally.
 */
internal fun refreshShortcut(
    catalog: CatalogReleases,
    cachePolicy: AcquisitionCachePolicy,
    cached: VerifiedOpenApiContract?,
): ContractAcquisitionOutcome? = when {
    cachePolicy != AcquisitionCachePolicy.Refresh || catalog.source == CatalogSource.Downloaded -> null
    cached != null -> ContractAcquisitionOutcome.Acquired(cached)
    catalog.source == CatalogSource.RefreshRefused -> ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.RecentlyChecked)
    else -> null
}

/** Evidence gathered by one uncoalesced acquisition that decides its no-contract reason. */
/**
 * Records what inspecting candidate releases found. When no candidate provides a contract, a
 * temporarily unavailable linked source wins, because a contract may still exist; then an exact
 * installed release that could not be inspected ([exactReleaseUninspected]); and only then
 * [ContractUnavailableReason.NoUsableContract], which claims the inspected releases provide no API.
 */
internal class ContractAcquisitionAttempt(private val exactReleaseUninspected: ContractUnavailableReason? = null) {
    var sourceTemporarilyUnavailable: Boolean = false
        private set

    fun markSourceTemporarilyUnavailable() {
        sourceTemporarilyUnavailable = true
    }

    fun unavailable(): ContractAcquisitionOutcome.Unavailable = ContractAcquisitionOutcome.Unavailable(
        when {
            sourceTemporarilyUnavailable -> ContractUnavailableReason.SourceTemporarilyUnavailable
            exactReleaseUninspected != null -> exactReleaseUninspected
            else -> ContractUnavailableReason.NoUsableContract
        },
    )
}
