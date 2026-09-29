package dev.obiente.nextcloudnative.app

class NextcloudResponseTooLargeException(
    val maximumBytes: Long,
    val responseStatus: Int? = null,
) : IllegalStateException(
    "The server response is larger than the allowed ${formatByteSize(maximumBytes)} limit.",
) {
    init {
        require(maximumBytes > 0L)
        require(responseStatus == null || responseStatus in 100..599)
    }
}
