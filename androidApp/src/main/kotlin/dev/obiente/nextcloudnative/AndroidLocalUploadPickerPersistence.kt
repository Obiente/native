package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import kotlinx.coroutines.CancellableContinuation
import org.json.JSONObject
import kotlin.coroutines.resume

internal fun requireSafeProcessGeneration(value: String) {
    require(value.length in 16..96 && value.all { it.isLetterOrDigit() || it == '-' }) {
        "The picker capability process generation is invalid."
    }
}

internal fun JSONObject.optionalStrictString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return requireStrictString(key)
}

internal fun JSONObject.requireStrictString(key: String): String = get(key).let { value ->
    require(value is String) { "The $key value changed type." }
    value
}

internal fun JSONObject.optionalStrictBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return get(key).let { value ->
        require(value is Boolean) { "The $key value changed type." }
        value
    }
}

internal fun persistedDurableUploadGrantPreExisting(payload: JSONObject): Boolean =
    payload.optionalStrictBoolean("grantPreExisting") ?: false

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
