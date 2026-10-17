package dev.obiente.nextcloudnative.contracts

/**
 * Orders publication of verified contracts per request, that is per app, server version, and
 * installed version.
 *
 * Each acquisition takes a generation when it starts. Its result is published only when its
 * generation is newer than the last publication or invalidation for the same request, so a slower
 * acquisition that started before a refresh cannot overwrite the refresh's result, and one that
 * started before an invalidation cannot bring the invalidated contract back. Bookkeeping is in
 * memory, per acquirer, and never waits for an acquisition, so a cancelled or failed acquisition
 * cannot hold back later ones.
 */
internal class ContractPublicationFence {
    private class Generations {
        /** The newest generation handed to an acquisition. */
        var started = 0L

        /** Results from this generation or older are no longer published. */
        var fenced = 0L
    }

    private val generations = HashMap<ContractAcquisitionRequest, Generations>()

    /** Starts an acquisition for [request] and returns its generation. */
    fun start(request: ContractAcquisitionRequest): Long = synchronized(generations) {
        generations.getOrPut(request, ::Generations).let { entry -> ++entry.started }
    }

    /**
     * Runs [publish] and returns true when [generation] is newer than the last publication or
     * invalidation for [request]. Returns false, without publishing, for a superseded result.
     */
    fun publishIfCurrent(request: ContractAcquisitionRequest, generation: Long, publish: () -> Unit): Boolean =
        synchronized(generations) {
            val entry = generations.getOrPut(request, ::Generations)
            if (generation <= entry.fenced) return false
            entry.fenced = generation
            publish()
            true
        }

    /** Runs [discard] and fences out every acquisition for [request] that has already started. */
    fun <T> invalidate(request: ContractAcquisitionRequest, discard: () -> T): T = synchronized(generations) {
        val entry = generations.getOrPut(request, ::Generations)
        entry.fenced = maxOf(entry.fenced, entry.started)
        discard()
    }
}
