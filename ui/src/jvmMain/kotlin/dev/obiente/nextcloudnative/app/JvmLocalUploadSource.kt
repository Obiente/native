package dev.obiente.nextcloudnative.app

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class JvmLocalUploadSourceIOException(cause: Throwable) :
    IOException("The local upload source could not be read.", cause)

fun openJvmLocalUploadSource(openSource: () -> InputStream): InputStream {
    val source = try {
        openSource()
    } catch (failure: IOException) {
        throw JvmLocalUploadSourceIOException(failure)
    } catch (failure: SecurityException) {
        throw JvmLocalUploadSourceIOException(failure)
    } catch (failure: IllegalStateException) {
        throw JvmLocalUploadSourceIOException(failure)
    } catch (failure: IllegalArgumentException) {
        throw JvmLocalUploadSourceIOException(failure)
    }
    return JvmLocalUploadInputStream(source)
}

fun writeJvmPreparedMultipartUpload(
    upload: PreparedMultipartUpload,
    readFile: (ByteArray) -> Int,
    write: (ByteArray, Int, Int) -> Unit,
) {
    try {
        writePreparedMultipartUpload(upload, readFile, write)
    } catch (failure: LocalUploadSourceValidationException) {
        throw JvmLocalUploadSourceIOException(failure)
    }
}

fun Throwable.isJvmLocalUploadSourceFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_LOCAL_UPLOAD_CAUSE_DEPTH) {
        if (current is JvmLocalUploadSourceIOException) return true
        current = current.cause
        depth += 1
    }
    return false
}

fun Throwable.toJvmLocalUploadSourceDiagnosticEvent(
    method: String,
    durationMillis: Long,
): SupportDiagnosticEventDraft = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Error,
    component = SupportDiagnosticComponent.Storage,
    operation = "local-upload.read",
    outcome = "failed",
    code = "LOCAL_UPLOAD_SOURCE_IO",
    durationMillis = durationMillis,
    fields = listOf(
        SupportDiagnosticFieldDraft("method", method.lowercase()),
        SupportDiagnosticFieldDraft("mutation", "true"),
    ),
    exception = toSupportDiagnosticExceptionDraft(),
)

private class JvmLocalUploadInputStream(source: InputStream) : FilterInputStream(source) {
    override fun read(): Int = localUploadRead { super.read() }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        localUploadRead { super.read(bytes, offset, length) }

    override fun close() {
        localUploadRead { super.close() }
    }

    private inline fun <T> localUploadRead(block: () -> T): T = try {
        block()
    } catch (failure: IOException) {
        throw JvmLocalUploadSourceIOException(failure)
    } catch (failure: SecurityException) {
        throw JvmLocalUploadSourceIOException(failure)
    }
}

private const val MAX_LOCAL_UPLOAD_CAUSE_DEPTH = 8
