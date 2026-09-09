package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

internal fun requireSafeProcessGeneration(value: String) {
    require(value.length in 16..96 && value.all { it.isLetterOrDigit() || it == '-' }) {
        "The picker capability process generation is invalid."
    }
}

internal fun resumeLocalUploadSelectionResult(
    continuation: CancellableContinuation<LocalUploadSelectionResult>,
    result: LocalUploadSelectionResult,
    releaseSelected: (LocalUploadFile) -> Unit,
) {
    continuation.resume(result) { _, undeliveredResult, _ ->
        if (undeliveredResult is LocalUploadSelectionResult.Selected) {
            runCatching { releaseSelected(undeliveredResult.file) }
        }
    }
}
