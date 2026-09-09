package dev.obiente.nextcloudnative.contracts

internal fun isCanonicalTarRootDirectory(path: String, type: Char): Boolean =
    type == '5' && (path == "." || path == "./")

internal fun decodeTarLongPath(payload: ByteArray): String {
    val terminator = payload.indexOf(0)
    val pathBytes = if (terminator >= 0) payload.copyOfRange(0, terminator) else payload
    if (terminator >= 0) {
        require(payload.drop(terminator).all { byte -> byte == 0.toByte() }) {
            "The app package contains an invalid long path record."
        }
    }
    return pathBytes.decodeToString(throwOnInvalidSequence = true).also { path ->
        require(path.isNotBlank()) { "The app package contains an empty long path record." }
    }
}
