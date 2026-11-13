package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticSettingsContract
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticSettingsWrite
import dev.obiente.nextcloudnative.nativeui.runtime.declaredEnumValues
import dev.obiente.nextcloudnative.nativeui.runtime.declaredJsonType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal enum class SemanticSettingKind { Toggle, Choice, Folder, Text }

/** One server setting, presented by its value type and the choices the contract declares. */
internal data class SemanticSettingEntry(
    val key: String,
    val label: String,
    val kind: SemanticSettingKind,
    val value: String,
    val options: List<String> = emptyList(),
    val writable: Boolean,
)

/**
 * An app's settings document read from the server.
 *
 * Every key and value comes from the server's own response. A keyed write uses exactly the key the
 * server returned; the client never composes a key format of its own.
 */
internal class SemanticSettings(
    private val descriptor: DynamicAppDescriptor,
    val contract: SemanticSettingsContract,
) {
    fun readRequest(): NextcloudApiRequest =
        buildDynamicApiRequest(descriptor, contract.read, emptyMap())
            .copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork, maximumResponseBytes = SETTINGS_RESPONSE_BYTES)

    fun parse(response: NextcloudApiResponse): List<SemanticSettingEntry> {
        require(response.status in 200..299) { "Settings could not be loaded (HTTP ${response.status})." }
        require(response.body.size <= SETTINGS_RESPONSE_BYTES) { "Settings returned too much data." }
        val root = runCatching { settingsJson.parseToJsonElement(response.body.decodeToString()) }.getOrNull()
            ?: error("Settings returned invalid JSON.")
        val document = ((root as? JsonObject)?.get("ocs") as? JsonObject)?.get("data") as? JsonObject
            ?: root as? JsonObject
            ?: error("Settings returned an unexpected response.")
        val entries = document.entries.asSequence()
            .mapNotNull { (key, element) ->
                val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                if (element is JsonNull || key.isBlank() || key.length > MAX_SETTING_KEY_CHARS) return@mapNotNull null
                entry(key, primitive)
            }
            .take(MAX_SETTINGS)
            .toList()
        // A replacement sends the whole document. When any declared setting is not held here as
        // a plain value, it could not be sent back unchanged, so nothing is offered for editing.
        val readable = entries.mapTo(hashSetOf(), SemanticSettingEntry::key)
        val complete = replacementProperties()?.all { it in readable } != false
        return (if (complete) entries else entries.map { it.copy(writable = false) })
            .sortedWith(compareByDescending<SemanticSettingEntry> { it.writable }.thenBy { it.label })
    }

    /** True when a write replaces the whole settings document instead of one key or a merge. */
    val replacesDocument: Boolean get() = replacementProperties() != null

    /** Whether [current] holds every replaced setting at the value [baseline] was built from. */
    fun replacementBaselineHolds(baseline: List<SemanticSettingEntry>, current: List<SemanticSettingEntry>): Boolean {
        val properties = replacementProperties() ?: return true
        fun List<SemanticSettingEntry>.replaced() = filter { it.key in properties }.associate { it.key to it.value }
        return baseline.replaced() == current.replaced()
    }

    /** The declared settings a document replacement must carry, or null for a keyed or merge write. */
    private fun replacementProperties(): Set<String>? =
        (contract.write as? SemanticSettingsWrite.Document)
            ?.takeIf { it.action.binding.method != HttpMethod.PATCH }
            ?.properties?.keys

    fun writeRequest(entry: SemanticSettingEntry, value: String, current: List<SemanticSettingEntry>): NextcloudApiRequest {
        require(entry.writable) { "This setting cannot be changed from here." }
        if (entry.kind == SemanticSettingKind.Choice) require(value in entry.options) { "Choose one of the offered values." }
        require(value.length <= MAX_SETTING_VALUE_CHARS && value.none(Char::isISOControl)) { "The value is invalid." }
        val request = when (val write = checkNotNull(contract.write)) {
            is SemanticSettingsWrite.Keyed -> buildDynamicApiRequest(
                descriptor,
                write.action,
                mapOf(write.keyParameter to entry.key, write.valueField to value),
                mapOf(write.keyParameter to entry.key),
            )
            is SemanticSettingsWrite.Document -> {
                // A replacement keeps every other declared setting at the value the server returned.
                val unchanged = if (write.action.binding.method == HttpMethod.PATCH) {
                    emptyMap()
                } else {
                    current.filter { it.key in write.properties && it.key != entry.key }.associate { it.key to it.value }
                }
                buildDynamicApiRequest(descriptor, write.action, unchanged + (entry.key to value))
            }
        }
        return request.copy(maximumResponseBytes = SETTINGS_RESPONSE_BYTES)
    }

    private fun entry(key: String, primitive: JsonPrimitive): SemanticSettingEntry {
        val value = primitive.contentOrNull.orEmpty()
        val declared = when (val write = contract.write) {
            is SemanticSettingsWrite.Document -> write.properties[key]
            is SemanticSettingsWrite.Keyed -> write.valueSchema
            null -> null
        }
        val writable = when (val write = contract.write) {
            is SemanticSettingsWrite.Document -> key in write.properties
            is SemanticSettingsWrite.Keyed -> write.valueSchema.accepts(primitive)
            null -> false
        }
        val options = declared?.declaredEnumValues().orEmpty()
        val kind = when {
            primitive.booleanOrNull != null && !primitive.isString -> SemanticSettingKind.Toggle
            options.isNotEmpty() && contract.write is SemanticSettingsWrite.Document -> SemanticSettingKind.Choice
            key.lowercase().let { name -> FOLDER_WORDS.any { it in name } } -> SemanticSettingKind.Folder
            else -> SemanticSettingKind.Text
        }
        return SemanticSettingEntry(
            key = key,
            label = key.humanizedSettingLabel(),
            kind = kind,
            value = value,
            options = if (kind == SemanticSettingKind.Choice) options else emptyList(),
            writable = writable,
        )
    }

    private fun JsonObject.accepts(primitive: JsonPrimitive): Boolean = when (declaredJsonType()) {
        null -> true
        "boolean" -> primitive.booleanOrNull != null && !primitive.isString
        "string" -> primitive.isString
        "integer", "number" -> !primitive.isString && primitive.contentOrNull?.toDoubleOrNull() != null
        else -> false
    }
}

internal data class SemanticSettingsUiState(
    val loading: Boolean = true,
    val entries: List<SemanticSettingEntry> = emptyList(),
    val busyKey: String? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
    /** A write could not be confirmed. Changes stay blocked until settings load again. */
    val outcomeUnknown: Boolean = false,
) {
    val canChange: Boolean get() = !loading && busyKey == null && !outcomeUnknown
}

/**
 * Loads settings and changes one at a time. Each change is verified by reading the settings again,
 * so the dialog always shows what the server stored and never assumes its own request was kept.
 */
internal class SemanticSettingsController(
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val settings: SemanticSettings,
    private val onChanged: (key: String) -> Unit = {},
) {
    var state: SemanticSettingsUiState by mutableStateOf(SemanticSettingsUiState())
        private set

    suspend fun load() {
        state = state.copy(loading = true, errorMessage = null)
        state = try {
            state.copy(loading = false, entries = settings.parse(execute(settings.readRequest())), outcomeUnknown = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state.copy(loading = false, errorMessage = failure.message ?: "Settings could not be loaded.")
        }
    }

    suspend fun change(entry: SemanticSettingEntry, value: String) {
        val current = state
        if (!current.canChange || value == entry.value) return
        val request = try {
            settings.writeRequest(entry, value, current.entries)
        } catch (invalid: IllegalArgumentException) {
            state = current.copy(errorMessage = invalid.message)
            return
        }
        state = current.copy(busyKey = entry.key, errorMessage = null, notice = null)
        if (settings.replacesDocument) {
            // The request carries every other setting as loaded; send nothing if any changed since.
            val fresh = try {
                settings.parse(execute(settings.readRequest()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.copy(busyKey = null, errorMessage = "The current settings could not be checked, so nothing was saved.")
                return
            }
            if (!settings.replacementBaselineHolds(current.entries, fresh)) {
                state = state.copy(
                    busyKey = null,
                    entries = fresh,
                    errorMessage = "These settings changed elsewhere, so nothing was saved. Review the current values, then change the setting again.",
                )
                return
            }
        }
        var status: Int? = null
        val writeFailure: Exception? = try {
            status = execute(request).status
            check(status in 200..299) { "The app refused this setting (HTTP $status)." }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        if (writeFailure != null && status != null && status in 400..499) {
            state = state.copy(busyKey = null, errorMessage = writeFailure.message)
            return
        }
        val stored = try {
            settings.parse(execute(settings.readRequest()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state = state.copy(
                busyKey = null,
                outcomeUnknown = true,
                errorMessage = "The app may have saved this setting. Reload settings before changing anything else.",
            )
            return
        }
        val storedValue = stored.firstOrNull { it.key == entry.key }?.value
        state = state.copy(
            busyKey = null,
            entries = stored,
            errorMessage = when {
                storedValue == entry.value -> writeFailure?.message ?: "The app kept the previous value."
                else -> null
            },
            notice = when (storedValue) {
                entry.value -> null
                value -> "${entry.label} saved"
                else -> "The app stored ${entry.label} as \"$storedValue\"."
            },
        )
        if (storedValue != entry.value) onChanged(entry.key)
    }
}

private fun String.humanizedSettingLabel(): String {
    val words = replace(Regex("[_:.-]+"), " ")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .trim()
        .lowercase()
    return words.replaceFirstChar(Char::uppercase).ifBlank { this }
}

private val settingsJson = Json { ignoreUnknownKeys = true }
private val FOLDER_WORDS = listOf("path", "folder", "directory")
private const val SETTINGS_RESPONSE_BYTES = 256L * 1024L
private const val MAX_SETTINGS = 64
private const val MAX_SETTING_KEY_CHARS = 128
private const val MAX_SETTING_VALUE_CHARS = 1_024
