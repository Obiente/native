package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlinx.serialization.Serializable

internal data class DynamicNavigationSnapshot(
    val viewId: String,
    val resourceId: String,
    val record: NativeRecord?,
    val recordResourceId: String?,
    val pathParameterValues: Map<String, String>,
)

@Serializable
internal data class SavedDynamicNavigationSnapshot(
    val viewId: String,
    val resourceId: String,
    val recordId: String? = null,
    val recordResourceId: String? = null,
    val pathParameterValues: Map<String, String> = emptyMap(),
)

@Serializable
internal data class DynamicAppNavigationState(
    val selectedViewId: String? = null,
    val selectedRecord: NativeRecord? = null,
    val selectedRecordResourceId: String? = null,
    val pathParameterValues: Map<String, String> = emptyMap(),
    val history: List<SavedDynamicNavigationSnapshot> = emptyList(),
)

internal fun DynamicAppNavigationState.hasPersistedDynamicLocation(): Boolean =
    selectedViewId != null || selectedRecord != null || history.isNotEmpty()

internal fun DynamicAppNavigationState.toSavedDynamicAppNavigationState(): SavedDynamicAppNavigationState {
    val savedParameters = pathParameterValues.toSavedDynamicNavigationParameters().orEmpty()
    val savedRecordId = selectedRecord?.id?.takeIf { value ->
        value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_RECORD_ID_CHARS)
    }
    return SavedDynamicAppNavigationState(
        selectedViewId = selectedViewId?.takeIf { value ->
            value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)
        },
        selectedRecordId = savedRecordId,
        selectedRecordResourceId = selectedRecordResourceId?.takeIf { value ->
            savedRecordId != null &&
                value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)
        },
        pathParameterValues = savedParameters,
        history = normalizeSavedDynamicNavigationHistory(history),
    )
}

internal fun SavedDynamicAppNavigationState.toDynamicAppNavigationState(): DynamicAppNavigationState {
    val restoredRecordId = selectedRecordId?.takeIf { value ->
        value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_RECORD_ID_CHARS)
    }
    return DynamicAppNavigationState(
        selectedViewId = selectedViewId?.takeIf { value ->
            value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)
        },
        selectedRecord = restoredRecordId?.let { recordId ->
            NativeRecord(id = recordId, values = emptyMap(), actionSafeIdentity = false)
        },
        selectedRecordResourceId = selectedRecordResourceId?.takeIf { value ->
            restoredRecordId != null &&
                value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)
        },
        pathParameterValues = pathParameterValues.toSavedDynamicNavigationParameters().orEmpty(),
        history = normalizeSavedDynamicNavigationHistory(history),
    )
}

private fun normalizeSavedDynamicNavigationHistory(
    history: List<SavedDynamicNavigationSnapshot>,
): List<SavedDynamicNavigationSnapshot> =
    saveDynamicNavigationHistory(restoreDynamicNavigationHistory(history))

internal fun saveDynamicNavigationHistory(
    history: List<DynamicNavigationSnapshot>,
): List<SavedDynamicNavigationSnapshot> = history
    .takeLast(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY)
    .mapNotNull(DynamicNavigationSnapshot::toSavedDynamicNavigationSnapshot)

internal fun restoreDynamicNavigationHistory(
    history: List<SavedDynamicNavigationSnapshot>,
): List<DynamicNavigationSnapshot> = history
    .takeLast(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY)
    .mapNotNull(SavedDynamicNavigationSnapshot::toDynamicNavigationSnapshot)

private fun DynamicNavigationSnapshot.toSavedDynamicNavigationSnapshot(): SavedDynamicNavigationSnapshot? {
    if (!viewId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    if (!resourceId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    val savedParameters = pathParameterValues.toSavedDynamicNavigationParameters() ?: return null
    val savedRecordId = record?.id?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_RECORD_ID_CHARS) }
            ?: return null
    }
    val savedRecordResourceId = recordResourceId?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS) }
            ?: return null
    }?.takeIf { savedRecordId != null }
    return SavedDynamicNavigationSnapshot(
        viewId = viewId,
        resourceId = resourceId,
        recordId = savedRecordId,
        recordResourceId = savedRecordResourceId,
        pathParameterValues = savedParameters,
    )
}

private fun SavedDynamicNavigationSnapshot.toDynamicNavigationSnapshot(): DynamicNavigationSnapshot? {
    if (!viewId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    if (!resourceId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    val restoredParameters = pathParameterValues.toSavedDynamicNavigationParameters() ?: return null
    val restoredRecordId = recordId?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_RECORD_ID_CHARS) }
            ?: return null
    }
    val restoredRecordResourceId = recordResourceId?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS) }
            ?: return null
    }?.takeIf { restoredRecordId != null }
    return DynamicNavigationSnapshot(
        viewId = viewId,
        resourceId = resourceId,
        record = restoredRecordId?.let { recordId ->
            NativeRecord(
                id = recordId,
                values = emptyMap(),
                // A persisted identity can reload a detail route, but only the authoritative
                // read response may authorize a mutation after process restoration.
                actionSafeIdentity = false,
            )
        },
        recordResourceId = restoredRecordResourceId,
        pathParameterValues = restoredParameters,
    )
}

private fun Map<String, String>.toSavedDynamicNavigationParameters(): Map<String, String>? {
    if (size > MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETERS) return null
    if (any { (key, value) ->
            !key.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_NAME_CHARS) ||
                !value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_VALUE_CHARS)
        }
    ) {
        return null
    }
    return toMap()
}

internal fun String.isSafeSavedDynamicNavigationValue(maximumChars: Int): Boolean =
    isNotBlank() && length <= maximumChars && none(Char::isISOControl)

internal const val MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY = 16
private const val MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETERS = 8
private const val MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS = 128
private const val MAX_SAVED_DYNAMIC_RECORD_ID_CHARS = 256
private const val MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_NAME_CHARS = 64
private const val MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_VALUE_CHARS = 256

/** A restored ID is navigation context, not a title or renewed mutation authority. */
internal fun NativeRecord.dynamicContextLabel(resourceLabel: String? = null): String =
    listOf("name", "title", "displayName", "subject", "what", "merchant", "label", "description")
        .firstNotNullOfOrNull { key -> (displayValues[key] ?: values[key])?.takeIf(String::isNotBlank) }
        ?: resourceLabel?.takeIf { it.isNotBlank() && it != id && it.any(Char::isLetter) }
        ?: "Selected item"