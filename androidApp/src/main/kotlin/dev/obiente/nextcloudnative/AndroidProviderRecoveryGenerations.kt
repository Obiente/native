package dev.obiente.nextcloudnative

/** Keeps verified content and its remote mutation precondition in the same recovery lifetime. */
internal class AndroidProviderRecoveryGenerations {
    private val verified = mutableMapOf<String, String>()
    private var reading: String? = null
    private var readEtag: String? = null

    fun recordReadGeneration(documentId: String, etag: String?) {
        check(reading == documentId) { "Recovery read does not match the active verification." }
        readEtag = requireNotNull(etag?.takeIf(String::isNotBlank)) { "Recovery read has no remote generation." }
    }

    fun mutationEtag(documentId: String, isDirectory: Boolean): String {
        require(!isDirectory) { "Directory recovery requires authenticated aggregate generation evidence." }
        return requireNotNull(verified[documentId]) { "Recovery mutation has no verified content generation." }
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
                check(verified.size < MAXIMUM_VERIFIED_DOCUMENTS) { "Recovery generation evidence exceeds its bound." }
                verified[documentId] = etag
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

    private companion object {
        const val MAXIMUM_VERIFIED_DOCUMENTS = 4096
    }
}
