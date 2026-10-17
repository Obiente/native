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
 * Serves [cached] when a refresh could not download every catalog it read, because the refresh
 * window served a catalog from cache or refused it: nothing newer than the cached verified contract
 * can be established. Returns null when there is no cached contract or every catalog was fresh. A
 * refused catalog never means absence; without a cached contract it is uninspected evidence, see
 * [ContractAcquisitionEvidence].
 */
internal fun servedOnLimitedRefresh(
    cachePolicy: AcquisitionCachePolicy,
    cached: VerifiedOpenApiContract?,
    vararg catalogs: CatalogReleases?,
): ContractAcquisitionOutcome? = cached
    ?.takeIf { cachePolicy == AcquisitionCachePolicy.Refresh }
    ?.takeIf { catalogs.any { catalog -> catalog != null && catalog.source != CatalogSource.Downloaded } }
    ?.let(ContractAcquisitionOutcome::Acquired)
