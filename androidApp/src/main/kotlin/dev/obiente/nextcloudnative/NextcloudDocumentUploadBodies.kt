package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.jvmFileRangeRequestBody
import java.io.File
import okhttp3.RequestBody

internal fun fileRangeRequestBody(
    source: File,
    offset: Long,
    length: Long,
    cancellation: DocumentRequestCancellation,
): RequestBody = jvmFileRangeRequestBody(source, offset, length, cancellation::throwIfCancelled)
