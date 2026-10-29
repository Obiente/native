package dev.obiente.nextcloudnative.contracts

import java.security.MessageDigest

/** The content of one catalog, as read by an acquisition: its URL and the SHA-256 of its bytes. */
internal data class CatalogObservation(val url: String, val digest: String) {
    companion object {
        fun of(url: String, bytes: ByteArray) = CatalogObservation(
            url,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )
    }
}

/**
 * Orders catalog downloads per catalog URL, so an older download can never replace a newer catalog
 * in the catalog cache, in memory or on disk.
 *
 * Each URL keeps `started` (the newest download generation handed out), `written` (the newest
 * generation whose catalog was accepted), and the digest of that catalog. A download takes
 * `g = ++started` before its request is sent:
 *
 * | Event for download g                              | g > written                      | g <= written      |
 * |---------------------------------------------------|----------------------------------|-------------------|
 * | Readable catalog                                  | store it; written = g; digest    | not stored        |
 * | Unreadable catalog, HTTP or network fault, cancel | no change                        | no change         |
 *
 * As in [ContractPublicationFence], only a result that is evidence fences: a newer download that
 * fails proves nothing about the catalog, so an older download that succeeds afterwards is still
 * stored. Nothing waits, so a cancelled or failed download leaves nothing held.
 *
 * The recorded digest is the newest catalog this process accepted for the URL, even when storing it
 * failed. A catalog an acquisition read is current when it matches that digest, or when nothing was
 * recorded yet. [ContractPublicationFence] accepts a result only while every catalog it rests on is
 * current, and a cached copy that is not current is read as a miss.
 */
internal class CatalogPublicationFence {
    private class State {
        var started = 0L
        var written = 0L
        var digest: String? = null
    }

    private val states = HashMap<String, State>()

    /** Starts a download of [url] and returns its generation. */
    fun start(url: String): Long = synchronized(states) { ++states.getOrPut(url, ::State).started }

    /** Runs [store] for [observation] unless a download of the same URL that started later was accepted. */
    fun publish(observation: CatalogObservation, generation: Long, store: () -> Unit): Boolean = synchronized(states) {
        val state = states.getOrPut(observation.url, ::State)
        if (generation <= state.written) return false
        store()
        state.written = generation
        state.digest = observation.digest
        true
    }

    /** Whether [observation] is the newest catalog accepted for its URL, or nothing was accepted yet. */
    fun isCurrent(observation: CatalogObservation): Boolean = synchronized(states) {
        val digest = states[observation.url]?.digest
        digest == null || digest == observation.digest
    }
}
