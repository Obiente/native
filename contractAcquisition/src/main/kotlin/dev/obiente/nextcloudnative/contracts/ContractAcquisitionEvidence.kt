package dev.obiente.nextcloudnative.contracts

/** What inspecting one candidate release found, when it did not end the acquisition with a contract. */
internal enum class InspectedCandidateResult {
    /** The signed package has no contract; a linked source may still supply one. */
    PackageWithoutContract,

    /** The signed package supplied only verified read routes. */
    ReadRoutesOnly,
}

/**
 * Everything one acquisition inspected for the requested app: each catalog's readability and
 * dropped entries ([CatalogReleases.malformed], including top-level elements whose identity could
 * not be read), the candidates inspected and what each provided, and whether a linked source
 * refused a request. The no-contract reason is derived only from this record, in this order:
 *
 * 1. a linked source refused or limited a request: [ContractUnavailableReason.SourceTemporarilyUnavailable],
 *    because a contract may exist there;
 * 2. the refresh window refused a catalog and no cached copy existed, so it was not read at all:
 *    [ContractUnavailableReason.RecentlyChecked]. The window is shared per catalog URL across apps
 *    and versions, so one catalog can be refused while the other was downloaded fresh; candidates
 *    from the fresh one are still inspected first, and only when none provides a contract does the
 *    refused catalog decide the reason. It ranks above an unreadable catalog because waiting out
 *    the window is the next step either way;
 * 3. a catalog document could not be read: [ContractUnavailableReason.UnreadableCatalog];
 * 4. malformed evidence that could have matched: a dropped entry for the exact version or with an
 *    unreadable version in either catalog, a dropped patch-compatible entry in the platform catalog
 *    (the only source of compatible candidates), an app without a readable release list, differing
 *    duplicate records for the app, or a top-level element whose identity could not be read where
 *    the app was not found:
 *    [ContractUnavailableReason.MalformedSource];
 * 5. every candidate that could have matched was inspected and none provided a contract:
 *    [ContractUnavailableReason.NoUsableContract];
 * 6. the catalogs were fully readable, nothing relevant was dropped, and nothing matched:
 *    [ContractUnavailableReason.NoMatchingRelease].
 *
 * Steps 5 and 6 claim that nothing usable exists, so they are reached only when steps 1 to 4 found
 * nothing that was left uninspected.
 */
internal class ContractAcquisitionEvidence(
    private val installedVersion: String?,
    private val platformCatalog: CatalogReleases,
    private val fullCatalog: CatalogReleases?,
) {
    private var sourceTemporarilyUnavailable = false
    private val inspected = mutableListOf<Pair<String, InspectedCandidateResult>>()

    fun markSourceTemporarilyUnavailable() {
        sourceTemporarilyUnavailable = true
    }

    fun recordInspected(release: AppStoreRelease, result: InspectedCandidateResult) {
        inspected += release.version to result
    }

    fun unavailable(): ContractAcquisitionOutcome.Unavailable = ContractAcquisitionOutcome.Unavailable(reason())

    private fun reason(): ContractUnavailableReason = when {
        sourceTemporarilyUnavailable -> ContractUnavailableReason.SourceTemporarilyUnavailable
        platformCatalog.source == CatalogSource.RefreshRefused || fullCatalog?.source == CatalogSource.RefreshRefused ->
            ContractUnavailableReason.RecentlyChecked
        !platformCatalog.documentReadable || fullCatalog?.documentReadable == false ->
            ContractUnavailableReason.UnreadableCatalog
        hasRelevantMalformedEvidence() -> ContractUnavailableReason.MalformedSource
        inspected.isNotEmpty() -> ContractUnavailableReason.NoUsableContract
        else -> ContractUnavailableReason.NoMatchingRelease
    }

    private fun hasRelevantMalformedEvidence(): Boolean =
        platformCatalog.malformed.any { entry ->
            entry.couldBeExact() || isCompatibleReleaseVersion(requireNotNull(entry.version), installedVersion)
        } || fullCatalog?.malformed.orEmpty().any { entry -> entry.couldBeExact() }

    /** An entry without a readable version, including an unidentified app, could be the exact release. */
    private fun MalformedCatalogEntry.couldBeExact(): Boolean = version == null || version == installedVersion
}
