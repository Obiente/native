package dev.obiente.nextcloudnative

/** Keeps verified content and its remote mutation precondition in the same recovery lifetime. */
internal class AndroidProviderRecoveryGenerations {
    private data class Proof(val etag: String, val directory: Boolean)
    private val verified = mutableMapOf<String, Proof>()
    private var directory: String? = null
    private var directoryEtag: String? = null
    private var reading: String? = null
    private var readEtag: String? = null

    fun recordReadGeneration(documentId: String, etag: String?) {
        check(reading == documentId) { "Recovery read does not match the active verification." }
        readEtag = requireNotNull(etag?.takeIf(String::isNotBlank)) { "Recovery read has no remote generation." }
    }

    fun mutationEtag(documentId: String, isDirectory: Boolean): String {
        val proof = requireNotNull(verified[documentId]) { "Recovery mutation has no verified content generation." }
        require(proof.directory == isDirectory) { "Recovery content kind changed." }
        return proof.etag
    }

    fun <Result> run(documentId: String, operation: AndroidDocumentsProviderRecoveryOperation, action: () -> Result): Result {
        if (operation == AndroidDocumentsProviderRecoveryOperation.OpenRead) {
            check(reading == null) { "Recovery content verification cannot nest." }
            verified.remove(documentId)
            reading = documentId
            readEtag = null
            return try {
                val result = action()
                val etag = requireNotNull(readEtag) { "Recovery content verification did not bind a generation." }
                if (directory == null) {
                    check(verified.size < MAXIMUM_VERIFIED_DOCUMENTS) { "Recovery generation evidence exceeds its bound." }
                    verified[documentId] = Proof(etag, directory = false)
                }
                result
            } finally {
                reading = null
                readEtag = null
            }
        }
        val mutation = operation == AndroidDocumentsProviderRecoveryOperation.Rename ||
            operation == AndroidDocumentsProviderRecoveryOperation.Delete
        return try {
            action()
        } finally {
            if (mutation) verified.remove(documentId)
        }
    }

    fun recordDirectoryGeneration(documentId: String, etag: String?) {
        if (directory != documentId) return
        val observed = requireNotNull(etag?.takeIf(String::isNotBlank)) { "Recovery directory has no generation." }
        require(directoryEtag == null || directoryEtag == observed) { "Recovery directory changed during verification." }
        directoryEtag = observed
    }

    fun <Result> verifyDirectory(documentId: String, action: () -> Result): Result {
        check(directory == null && reading == null) { "Recovery directory verification cannot nest." }
        verified.remove(documentId)
        directory = documentId
        directoryEtag = null
        return try {
            val result = action()
            val etag = requireNotNull(directoryEtag) { "Recovery directory verification did not bind its generation." }
            check(verified.size < MAXIMUM_VERIFIED_DOCUMENTS) { "Recovery generation evidence exceeds its bound." }
            verified[documentId] = Proof(etag, directory = true)
            result
        } finally {
            directory = null
            directoryEtag = null
        }
    }

    private companion object {
        const val MAXIMUM_VERIFIED_DOCUMENTS = 4096
    }
}
