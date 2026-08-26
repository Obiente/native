package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

fun InputStream.copyBoundedNetworkResponseTo(
    output: OutputStream,
    maxBytes: Long,
    onLimitExceeded: () -> Nothing,
    onNetworkReadFailure: (IOException) -> Unit,
    shouldContinue: () -> Boolean = { true },
): Long {
    require(maxBytes > 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        if (!shouldContinue()) throw CancellationException("Network response copy cancelled.")
        val read = try {
            read(buffer)
        } catch (failure: IOException) {
            onNetworkReadFailure(failure)
            throw failure
        }
        if (read == -1) break
        total += read
        if (total > maxBytes) onLimitExceeded()
        output.write(buffer, 0, read)
    }
    return total
}
