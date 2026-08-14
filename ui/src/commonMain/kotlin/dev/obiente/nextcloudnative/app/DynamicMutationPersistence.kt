package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DYNAMIC_PENDING_MUTATION_VERSION = 1
const val MAX_PERSISTED_DYNAMIC_MUTATION_BYTES = 128 * 1024
private const val MAX_PENDING_MUTATION_ID_LENGTH = 256
private const val MAX_PENDING_MUTATION_VALUE_LENGTH = 65_536
private const val MAX_PENDING_MUTATION_VALUES = 32

@Serializable
private data class PersistedDynamicMutation(
    val version: Int = DYNAMIC_PENDING_MUTATION_VERSION,
    val appId: String,
    val actionId: String,
    val targetRecordId: String,
    val values: Map<String, String>,
)

private val dynamicPendingMutationJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

fun encodePersistedDynamicMutation(
    appId: String,
    actionId: String,
    targetRecordId: String,
    values: Map<String, String>,
): String? {
    if (!appId.isSafePendingMutationId() || !actionId.isSafePendingMutationId()) return null
    if (!targetRecordId.isSafePendingMutationId()) return null
    if (!values.isSafePendingMutationValues()) return null
    return runCatching {
        dynamicPendingMutationJson.encodeToString(
            PersistedDynamicMutation(
                appId = appId,
                actionId = actionId,
                targetRecordId = targetRecordId,
                values = values.toSortedMap(),
            ),
        )
    }.getOrNull()?.takeIf { encoded ->
        encoded.encodeToByteArray().size <= MAX_PERSISTED_DYNAMIC_MUTATION_BYTES
    }
}

fun decodePersistedDynamicMutation(
    encoded: String,
    expectedAppId: String,
    expectedActionId: String,
    expectedTargetRecordId: String,
): Map<String, String>? {
    if (encoded.encodeToByteArray().size !in 1..MAX_PERSISTED_DYNAMIC_MUTATION_BYTES) return null
    val persisted = runCatching {
        dynamicPendingMutationJson.decodeFromString<PersistedDynamicMutation>(encoded)
    }.getOrNull() ?: return null
    if (
        persisted.version != DYNAMIC_PENDING_MUTATION_VERSION ||
        persisted.appId != expectedAppId ||
        persisted.actionId != expectedActionId ||
        persisted.targetRecordId != expectedTargetRecordId ||
        !persisted.values.isSafePendingMutationValues()
    ) {
        return null
    }
    return persisted.values
}

fun String.isSafePendingMutationId(): Boolean =
    length in 1..MAX_PENDING_MUTATION_ID_LENGTH &&
        all { character -> character in 'a'..'z' || character in 'A'..'Z' || character.isDigit() || character in "._:-" }

private fun Map<String, String>.isSafePendingMutationValues(): Boolean =
    size in 1..MAX_PENDING_MUTATION_VALUES &&
        entries.all { (key, value) ->
            key.isSafePendingMutationId() &&
                value.length <= MAX_PENDING_MUTATION_VALUE_LENGTH &&
                value.none(Char::isISOControl)
        }
