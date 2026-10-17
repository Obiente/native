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
 * | Served a cached contract without inspecting    | no change                                   | same                         |
 * | Invalidation (not a generation)                | fenced = started; latest = invalidated      |                              |
 *
 * Definitive reasons are evidence about the release: [ContractUnavailableReason.NoMatchingRelease],
 * [ContractUnavailableReason.NoUsableContract], and [ContractUnavailableReason.MalformedSource].
 * A transient result, such as a refused linked source, an unreadable catalog, a throttled retry, a
 * network fault, or a cancellation, proves nothing about the contract, so it fences nothing: an
 * older acquisition that verifies a contract afterwards may still publish it.
 *
 * A superseded acquisition never returns its own result. It returns `latest`: the newer contract,
 * the newer definitive outcome, or, after an invalidation, Unavailable with
 * [ContractUnavailableReason.NoUsableContract], because the contract proved unusable.
 *
 * Bookkeeping is in memory, per acquirer, under one lock, and never waits for an acquisition, so a
 * cancelled or failed acquisition cannot hold back later ones. A definitive no-contract result does
 * not remove a previously cached contract: a refresh that finds nothing keeps the cached one.
 */
internal class ContractPublicationFence {
    private class State {
        var started = 0L
        var fenced = 0L
        var latest: ContractAcquisitionOutcome? = null
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
     * Publishes [contract] with [store] unless [generation] is superseded. Returns the outcome the
     * acquisition reports: its own contract, or the newer accepted result.
     */
    fun publish(
        request: ContractAcquisitionRequest,
        generation: Long,
        contract: VerifiedOpenApiContract,
        store: (VerifiedOpenApiContract) -> Unit,
    ): ContractAcquisitionOutcome = synchronized(states) {
        val state = states.getOrPut(request, ::State)
        if (generation <= state.fenced) return state.supersededOutcome()
        store(contract)
        ContractAcquisitionOutcome.Acquired(contract).also { accepted -> state.accept(generation, accepted) }
    }

    /**
     * Records an acquisition that ended without a contract. A definitive outcome fences every older
     * generation; a transient one changes nothing. Returns the outcome the acquisition reports.
     */
    fun complete(
        request: ContractAcquisitionRequest,
        generation: Long,
        outcome: ContractAcquisitionOutcome.Unavailable,
    ): ContractAcquisitionOutcome = synchronized(states) {
        if (!outcome.reason.isDefinitive()) return outcome
        val state = states.getOrPut(request, ::State)
        if (generation <= state.fenced) return state.supersededOutcome()
        outcome.also { accepted -> state.accept(generation, accepted) }
    }

    /** Runs [discard] and supersedes every acquisition for [request] that has already started. */
    fun <T> invalidate(request: ContractAcquisitionRequest, discard: () -> T): T = synchronized(states) {
        val state = states.getOrPut(request, ::State)
        state.fenced = maxOf(state.fenced, state.started)
        state.latest = INVALIDATED
        discard()
    }

    private fun State.accept(generation: Long, outcome: ContractAcquisitionOutcome) {
        fenced = generation
        latest = outcome
    }

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
