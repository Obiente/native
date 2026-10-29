package dev.obiente.nextcloudnative.contracts

/**
 * Orders the results of acquisitions per request, that is per app, server version, and installed
 * version, so an older acquisition can never undo what a newer one established.
 *
 * Each key keeps `started` (the newest generation handed out), `fenced` (results from this
 * generation or older are superseded), and `latest` (the newest accepted result). Both counters
 * only grow. An acquisition takes generation `g = ++started` in the same locked step that registers
 * it as in flight ([startIfRegistered]); a caller joining it takes none. It ends in one row:
 *
 * | Event for generation g                         | g > fenced                                  | g <= fenced (superseded)     |
 * |------------------------------------------------|---------------------------------------------|------------------------------|
 * | Verified a contract                            | publish; fenced = g; latest = the contract  | not published; return latest |
 * | Definitive Unavailable                         | fenced = g; latest = that outcome           | return latest                |
 * | Transient Unavailable, source or network fault | no change; return or throw its own result   | same                         |
 * | Cancelled                                      | no change; the cancellation propagates      | same                         |
 * | Served a cached contract without inspecting    | serve it, unless `latest` is a no-contract  | return latest                |
 * |                                                | result; then return latest                  |                              |
 * | Read a contract from disk into memory          | promote it unless memory holds one, or      | not promoted                 |
 * |                                                | `latest` is a no-contract result            |                              |
 * | Invalidation (not a generation)                | fenced = started; latest = invalidated      |                              |
 *
 * A contract or definitive result also records the catalogs it rests on, and both rows compose with
 * [CatalogPublicationFence]. A result whose catalog is no longer current, because a download that
 * started later replaced it with different content, is not accepted: the caller gets null and
 * acquires again from the newer catalog. A superseded generation whose catalogs are current is
 * accepted after all when the latest accepted result rests on a catalog that is no longer current,
 * so a result read from an older catalog never outranks one read from the newer catalog.
 *
 * Definitive reasons are evidence about the release: [ContractUnavailableReason.NoMatchingRelease],
 * [ContractUnavailableReason.NoUsableContract], and [ContractUnavailableReason.MalformedSource].
 * A transient result, such as a refused linked source, an unreadable catalog, a throttled retry, a
 * network fault, or a cancellation, proves nothing about the contract, so it fences nothing: an
 * older acquisition that verifies a contract afterwards may still publish it.
 *
 * A cached verified contract is never served against a newer accepted result: once a definitive
 * no-contract result or an invalidation is the latest accepted result for the key, a cache read
 * reports that result instead, until a newer contract is published. The fence is in memory, so a
 * new process starts without that record and serves the cache until a refresh records it again.
 *
 * A superseded acquisition never returns its own result. It returns `latest`: the newer contract,
 * the newer definitive outcome, or, after an invalidation, Unavailable with
 * [ContractUnavailableReason.NoUsableContract], because the contract proved unusable.
 *
 * Bookkeeping is in memory, per acquirer, under one lock, and never waits for an acquisition, so a
 * cancelled or failed acquisition cannot hold back later ones. A definitive no-contract result does
 * not remove a previously cached contract: a refresh that finds nothing keeps the cached one.
 */
internal class ContractPublicationFence(private val catalogs: CatalogPublicationFence = CatalogPublicationFence()) {
    private class State {
        var started = 0L
        var fenced = 0L
        var latest: ContractAcquisitionOutcome? = null
        var latestCatalogs: List<CatalogObservation> = emptyList()
    }

    private val states = HashMap<ContractAcquisitionRequest, State>()

    /** Starts an acquisition for [request] and returns its generation. */
    fun start(request: ContractAcquisitionRequest): Long = checkNotNull(startIfRegistered(request) { true })

    /**
     * Runs [register] under the fence lock and, when it registers a new acquisition, starts its
     * generation in the same step. Returns null when [register] joined an existing acquisition,
     * which takes no generation. No refresh or invalidation can observe a registered acquisition
     * whose generation has not started yet.
     */
    fun startIfRegistered(request: ContractAcquisitionRequest, register: () -> Boolean): Long? = synchronized(states) {
        if (!register()) return null
        states.getOrPut(request, ::State).let { state -> ++state.started }
    }

    /**
     * Publishes [contract], read from [catalogs], with [store] unless [generation] is superseded.
     * Returns the outcome the acquisition reports: its own contract, or the newer accepted result.
     * Returns null when one of [catalogs] is no longer current, so the acquisition must run again.
     */
    fun publish(
        request: ContractAcquisitionRequest,
        generation: Long,
        contract: VerifiedOpenApiContract,
        catalogs: List<CatalogObservation> = emptyList(),
        store: (VerifiedOpenApiContract) -> Unit,
    ): ContractAcquisitionOutcome? = synchronized(states) {
        if (!catalogs.areCurrent()) return null
        val state = states.getOrPut(request, ::State)
        if (state.supersedes(generation)) return state.supersededOutcome()
        store(contract)
        ContractAcquisitionOutcome.Acquired(contract).also { accepted -> state.accept(generation, accepted, catalogs) }
    }

    /**
     * Records an acquisition that ended without a contract after reading [catalogs]. A definitive
     * outcome fences every older generation; a transient one changes nothing. Returns the outcome the
     * acquisition reports, or null when a definitive outcome rests on a catalog that is no longer
     * current, so the acquisition must run again.
     */
    fun complete(
        request: ContractAcquisitionRequest,
        generation: Long,
        outcome: ContractAcquisitionOutcome.Unavailable,
        catalogs: List<CatalogObservation> = emptyList(),
    ): ContractAcquisitionOutcome? = synchronized(states) {
        if (!outcome.reason.isDefinitive()) return outcome
        if (!catalogs.areCurrent()) return null
        val state = states.getOrPut(request, ::State)
        if (state.supersedes(generation)) return state.supersededOutcome()
        outcome.also { accepted -> state.accept(generation, accepted, catalogs) }
    }

    /**
     * Reports [cached], read by [generation] without inspecting releases, unless a newer result was
     * accepted since the read started, or the latest accepted result says there is no usable contract.
     */
    fun serveCached(
        request: ContractAcquisitionRequest,
        generation: Long,
        cached: VerifiedOpenApiContract,
    ): ContractAcquisitionOutcome = synchronized(states) {
        val state = states.getOrPut(request, ::State)
        val latest = state.latest
        when {
            generation <= state.fenced -> state.supersededOutcome()
            latest is ContractAcquisitionOutcome.Unavailable -> latest
            else -> ContractAcquisitionOutcome.Acquired(cached)
        }
    }

    /**
     * Runs [promote] for a contract that [generation] read from the persistent cache, unless a newer
     * result was accepted since the read started, or the latest accepted result says there is no
     * usable contract. A read that a newer result superseded can therefore never replace it in memory.
     */
    fun promote(request: ContractAcquisitionRequest, generation: Long, promote: () -> Unit) {
        synchronized(states) {
            val state = states.getOrPut(request, ::State)
            if (generation > state.fenced && state.latest !is ContractAcquisitionOutcome.Unavailable) promote()
        }
    }

    /** Runs [discard] and supersedes every acquisition for [request] that has already started. */
    fun <T> invalidate(request: ContractAcquisitionRequest, discard: () -> T): T = synchronized(states) {
        val state = states.getOrPut(request, ::State)
        state.fenced = maxOf(state.fenced, state.started)
        state.latest = INVALIDATED
        state.latestCatalogs = emptyList()
        discard()
    }

    /** Whether [generation] is older than the latest accepted result, which still rests on current catalogs. */
    private fun State.supersedes(generation: Long): Boolean = generation <= fenced && latestCatalogs.areCurrent()

    private fun State.accept(generation: Long, outcome: ContractAcquisitionOutcome, catalogs: List<CatalogObservation>) {
        fenced = maxOf(fenced, generation)
        latest = outcome
        latestCatalogs = catalogs
    }

    private fun List<CatalogObservation>.areCurrent(): Boolean = all(catalogs::isCurrent)

    /** Only reached after an accepted result or an invalidation, so `latest` is always set. */
    private fun State.supersededOutcome(): ContractAcquisitionOutcome = checkNotNull(latest)

    /** Whether a no-contract reason is evidence about the release, rather than a failure to look. */
    private fun ContractUnavailableReason.isDefinitive(): Boolean = when (this) {
        ContractUnavailableReason.NoMatchingRelease,
        ContractUnavailableReason.NoUsableContract,
        ContractUnavailableReason.MalformedSource,
        -> true
        ContractUnavailableReason.SourceTemporarilyUnavailable,
        ContractUnavailableReason.UnreadableCatalog,
        ContractUnavailableReason.RecentlyChecked,
        -> false
    }

    private companion object {
        val INVALIDATED = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoUsableContract)
    }
}
