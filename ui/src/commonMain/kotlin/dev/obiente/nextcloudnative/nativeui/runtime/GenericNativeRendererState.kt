package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

enum class GenericNativeSurface {
    List,
    Grid,
    Board,
    Mailbox,
    MediaLibrary,
    Insights,
    Table,
    Detail,
    Form,
}

fun ViewSpec.genericSurface(): GenericNativeSurface = when (component) {
    NativeComponent.mediaGrid -> GenericNativeSurface.Grid
    NativeComponent.mailbox -> GenericNativeSurface.Mailbox
    NativeComponent.mediaLibrary -> GenericNativeSurface.MediaLibrary

    NativeComponent.board -> GenericNativeSurface.Board
    NativeComponent.dashboard -> GenericNativeSurface.Insights

    NativeComponent.detail,
    NativeComponent.documentEditor,
    -> GenericNativeSurface.Detail

    NativeComponent.dataTable -> GenericNativeSurface.Table
    NativeComponent.form -> GenericNativeSurface.Form
    else -> GenericNativeSurface.List
}

/**
 * Lets a verified shape-only route become an insights surface after its bounded response fields
 * are observed. This is intentionally narrower than generic numeric detection: only finance or
 * project resources with a recognized measure are promoted.
 */
internal fun ViewSpec.genericSurface(
    resource: ResourceSpec?,
    records: List<NativeRecord>,
): GenericNativeSurface {
    val declared = genericSurface()
    if (resource == null || records.isEmpty()) return declared
    // Endpoint names can conservatively compile to dashboards before response data exists. Once
    // the rows prove they are transactions, prefer the ledger and keep its summary collapsible.
    if (
        declared == GenericNativeSurface.Insights &&
        nativeFinanceCollectionPresentations(resource, records) != null
    ) {
        return GenericNativeSurface.List
    }
    if (declared != GenericNativeSurface.List) return declared
    val words = (resource.id + " " + resource.name)
        .lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()
    if (words.none { it in FINANCE_DATASET_WORDS }) return declared
    // Transaction collections already have a richer ledger presentation with a compact,
    // collapsible summary. Promoting them to the generic insights surface would bypass the
    // amount, payer, and split-aware cards.
    if (nativeFinanceCollectionPresentations(resource, records) != null) return declared
    return if (nativeDatasetInsights(resource, records) != null) {
        GenericNativeSurface.Insights
    } else {
        declared
    }
}

private val FINANCE_DATASET_WORDS = setOf(
    "account", "accounts", "bill", "bills", "budget", "budgets", "category", "categories",
    "expense", "expenses", "finance", "financial", "income", "payment", "payments",
    "project", "projects", "revenue", "spending", "transaction", "transactions",
)

/**
 * Object responses are wrapped as one action-unsafe `record` by the dynamic runtime. That wrapper
 * is transport structure, not a user-facing collection item, so it should open directly. Real
 * singleton collections retain their server identity and remain navigable lists.
 */
internal fun shouldAutoOpenSyntheticRecord(records: List<NativeRecord>): Boolean =
    records.singleOrNull()?.let { record ->
        record.id == "record" &&
            !record.actionSafeIdentity &&
            record.structuredValues.isNotEmpty()
    } == true

/**
 * Finds the safe read surface that can prefill an editable settings form.
 *
 * Create/edit forms for ordinary collections are deliberately excluded: loading a list and using
 * its first record would silently turn a create action into an accidental edit. Settings are
 * identified from explicit observed-body contracts or whole resource-name semantics.
 */
internal fun NativeAppSchema.settingsFormPrefillView(form: ViewSpec): ViewSpec? {
    if (form.component != NativeComponent.form) return null
    val write = action(form.sourceActionId) ?: return null
    if (write.risk == ActionRisk.readOnly || write.binding.method == HttpMethod.GET) return null
    val resource = resource(form.resourceId) ?: return null
    val semanticWords = (resource.id + " " + resource.name)
        .lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()
    val settingsResource = write.binding.allowsObservedBodyFields || semanticWords.any { word ->
        word in setOf("config", "configuration", "setting", "settings", "preference", "preferences")
    }
    if (!settingsResource) return null
    return views.asSequence()
        .filter { candidate ->
            candidate.id != form.id && candidate.resourceId == form.resourceId &&
                candidate.component != NativeComponent.form && candidate.sourceActionId.isNotBlank()
        }
        .mapNotNull { candidate -> action(candidate.sourceActionId)?.let { candidate to it } }
        .filter { (_, read) ->
            read.risk == ActionRisk.readOnly && read.binding.method == HttpMethod.GET &&
                read.intent in setOf(ActionIntent.read, ActionIntent.list)
        }
        .sortedBy { (candidate, read) ->
            when {
                read.intent == ActionIntent.read -> 0
                candidate.component == NativeComponent.detail -> 1
                else -> 2
            }
        }
        .map(Pair<ViewSpec, ActionSpec>::first)
        .firstOrNull()
}

internal fun nativeTableFields(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    maximumColumns: Int = 8,
): List<FieldSpec> {
    if (maximumColumns <= 0) return emptyList()
    val populated = resource.fields.filter { field ->
        field.kind !in setOf(FieldKind.objectValue, FieldKind.image, FieldKind.unknown) &&
            records.any { record -> !record.presentationValue(field.id).isNullOrBlank() }
    }
    val preferredIds = listOf("name", "title", "displayName", "subject", "description")
    val primary = preferredIds.firstNotNullOfOrNull { id ->
        populated.firstOrNull { field -> field.id.equals(id, ignoreCase = true) }
    } ?: populated.firstOrNull { !it.isTechnicalTableField() }
        ?: populated.firstOrNull { it.id.equals("id", ignoreCase = true) }
    return buildList {
        primary?.let(::add)
        populated.filterNot { it.id == primary?.id }.forEach(::add)
    }.take(maximumColumns)
}

private fun FieldSpec.isTechnicalTableField(): Boolean {
    val normalized = id.lowercase().filter(Char::isLetterOrDigit)
    return normalized == "id" || normalized.endsWith("id") || normalized in setOf(
        "etag", "href", "token", "permissions", "permission", "createdby", "lasteditby",
    )
}

data class NativeFormattedField(
    val label: String,
    val displayValue: String,
    val safeLink: String? = null,
)

fun formatNativeField(field: FieldSpec, rawValue: String): NativeFormattedField {
    val trimmed = rawValue.trim()
    val formatted = trimmed.takeIf { field.hasDurationSemantics() }?.formatIsoDuration() ?: when (field.kind) {
        FieldKind.boolean -> when (trimmed.lowercase()) {
            "true", "1", "yes", "on" -> "Yes"
            "false", "0", "no", "off" -> "No"
            else -> trimmed
        }
        FieldKind.integer -> trimmed.toLongOrNull()?.toString() ?: trimmed
        FieldKind.decimal -> trimmed.normalizeDecimal()
        FieldKind.currency -> listOfNotNull(field.format?.takeIf(String::isNotBlank), trimmed.normalizeDecimal())
            .joinToString(" ")
        FieldKind.dateTime -> trimmed.formatNativeDateTime()
        FieldKind.userReference -> if (trimmed.startsWith('@')) trimmed else "@$trimmed"
        FieldKind.enumeration -> trimmed.humanizeIdentifier()
        FieldKind.objectValue -> summarizeObjectValue(field, trimmed)
        else -> trimmed
    }
    val declaresLink = field.format?.lowercase() in setOf("url", "uri", "link", "website")
    val link = if (declaresLink || field.kind == FieldKind.image) safeNativeLink(trimmed) else null
    return NativeFormattedField(field.label, formatted, link)
}

private fun String.formatNativeDateTime(): String {
    val isoCandidate = if (length > 10 && this[10] == ' ') replaceRange(10, 11, "T") else this
    return runCatching {
        Instant.parse(isoCandidate).toString().replace('T', ' ').take(16)
    }.getOrElse {
        replace('T', ' ').removeSuffix("Z").let { value ->
            if (value.length >= 16 && value.take(16).all { character ->
                    character.isDigit() || character in setOf('-', ' ', ':')
                }
            ) value.take(16) else value
        }
    }
}

private fun FieldSpec.hasDurationSemantics(): Boolean {
    val semantic = (id + label).lowercase().filter(Char::isLetterOrDigit)
    return semantic == "duration" || semantic.endsWith("duration") ||
        setOf("preptime", "cooktime", "totaltime").any(semantic::contains)
}

internal fun String.formatIsoDuration(): String? {
    val source = trim().uppercase()
    if (!source.startsWith("P") || source.length < 3) return null
    var inTime = false
    var number = ""
    var days = 0L
    var hours = 0L
    var minutes = 0L
    var seconds = 0L
    source.drop(1).forEach { character ->
        when {
            character == 'T' && number.isEmpty() && !inTime -> inTime = true
            character.isDigit() -> number += character
            character in setOf('D', 'H', 'M', 'S') && number.isNotEmpty() -> {
                val value = number.toLongOrNull() ?: return null
                number = ""
                when (character) {
                    'D' -> if (!inTime) days = value else return null
                    'H' -> if (inTime) hours = value else return null
                    'M' -> if (inTime) minutes = value else return null
                    'S' -> if (inTime) seconds = value else return null
                }
            }
            else -> return null
        }
    }
    if (number.isNotEmpty()) return null
    val parts = listOfNotNull(
        days.takeIf { it > 0 }?.let { "$it ${if (it == 1L) "day" else "days"}" },
        hours.takeIf { it > 0 }?.let { "$it hr" },
        minutes.takeIf { it > 0 }?.let { "$it min" },
        seconds.takeIf { it > 0 }?.let { "$it sec" },
    )
    return parts.joinToString(" ").ifBlank { "0 min" }
}

private fun summarizeObjectValue(field: FieldSpec, value: String): String {
    val element = runCatching { Json.parseToJsonElement(value) }.getOrNull() ?: return "Structured data"
    return when (element) {
        is JsonArray -> {
            val label = field.label.trim().lowercase().ifBlank { "items" }
            val countLabel = if (element.size == 1) label.removeSuffix("s") else label
            "${element.size} $countLabel"
        }
        is JsonObject -> {
            listOf("name", "title", "displayName", "label")
                .firstNotNullOfOrNull { key -> (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                ?: "${element.size} fields"
        }
        else -> "Structured data"
    }
}

/**
 * Only absolute HTTP(S) links are handed to the host platform. The renderer never embeds them.
 * User-info, whitespace, control characters, and backslashes are rejected to avoid ambiguous URLs.
 */
fun safeNativeLink(value: String): String? {
    val candidate = value.trim()
    if (candidate.length !in 1..2_048 || candidate.any { it.isWhitespace() || it.isISOControl() }) return null
    if ('\\' in candidate) return null
    val schemeLength = when {
        candidate.startsWith("https://", ignoreCase = true) -> 8
        candidate.startsWith("http://", ignoreCase = true) -> 7
        else -> return null
    }
    val remainder = candidate.drop(schemeLength)
    val authority = remainder.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority || authority == "." || authority == "..") return null
    return candidate
}

data class NativeFormDraft(
    val values: Map<String, String> = emptyMap(),
    val touchedFields: Set<String> = emptySet(),
) {
    fun update(fieldId: String, value: String): NativeFormDraft = copy(
        values = values + (fieldId to value),
        touchedFields = touchedFields + fieldId,
    )
}

internal fun NativeFormDraft.hasChangesFrom(initial: NativeFormDraft): Boolean = values != initial.values

fun initialNativeFormDraft(
    resource: ResourceSpec,
    action: ActionSpec,
    initialRecord: NativeRecord? = null,
): NativeFormDraft = NativeFormDraft(
    values = editableNativeFields(resource, action).associate { field ->
        field.id to (
            initialRecord?.nativeFormValue(field)
                ?: if (field.kind == FieldKind.boolean) "false" else ""
            )
    },
)

private fun NativeRecord.nativeFormValue(field: FieldSpec): String? {
    if (field.format == SETTINGS_BOOLEAN_MAP_FORMAT) {
        val entries = (structuredValues[field.id] as? NativeStructuredValue.ObjectValue)
            ?.entries
            ?.mapNotNull { entry ->
                val scalar = entry.value as? NativeStructuredValue.Scalar ?: return@mapNotNull null
                if (scalar.kind != NativeStructuredScalarKind.boolean) return@mapNotNull null
                scalar.value?.toBooleanStrictOrNull()?.let { entry.key to it }
            }
        if (!entries.isNullOrEmpty()) {
            return buildJsonObject {
                entries.forEach { (key, enabled) -> put(key, enabled) }
            }.toString()
        }
    }
    if (field.format in setOf(DYNAMIC_STRING_LIST_FORMAT, DYNAMIC_STRING_ARRAY_FORMAT)) {
        val values = (structuredValues[field.id] as? NativeStructuredValue.ListValue)
            ?.items
            ?.mapNotNull { item -> (item as? NativeStructuredValue.Scalar)?.value }
        if (!values.isNullOrEmpty()) return values.joinToString("\n")
    }
    return presentationValue(field.id)
}

internal const val SETTINGS_BOOLEAN_MAP_FORMAT = "nextcloud-boolean-map"

internal fun ResourceSpec.withObservedSettingsFormTypes(
    action: ActionSpec,
    record: NativeRecord?,
): ResourceSpec {
    if (!action.binding.allowsObservedBodyFields || record == null) return this
    val fields = fields.map { field ->
        val value = record.structuredValues[field.id]
        val booleanMap = (value as? NativeStructuredValue.ObjectValue)?.entries
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_BOOLEAN_MAP_SETTINGS }
            ?.all { entry ->
                val scalar = entry.value as? NativeStructuredValue.Scalar
                scalar?.kind == NativeStructuredScalarKind.boolean &&
                    scalar.value?.toBooleanStrictOrNull() != null &&
                    entry.key.isSafeObservedSettingKey()
            } == true
        if (field.kind == FieldKind.objectValue && booleanMap) {
            field.copy(format = SETTINGS_BOOLEAN_MAP_FORMAT)
        } else {
            field
        }
    }
    return copy(fields = fields)
}

/**
 * Carries scalar types observed by a settings GET into the renderer-local write action.
 *
 * A sparse, verified route contract can prove that configuration is readable and writable
 * without declaring its properties. The response parser still knows whether each primitive was
 * JSON text, a boolean, or a number. Preserving that fact here prevents a form submission from
 * turning every edited setting into a JSON string.
 */
internal fun ActionSpec.withObservedSettingsInputTypes(resource: ResourceSpec): ActionSpec {
    if (!binding.allowsObservedBodyFields) return this
    val existing = inputSchema as? JsonObject
    val existingProperties = existing?.get("properties") as? JsonObject
    val observedProperties = resource.fields.mapNotNull { field ->
        val type = when (field.kind) {
            FieldKind.boolean -> "boolean"
            FieldKind.integer -> "integer"
            FieldKind.decimal,
            FieldKind.currency,
            -> "number"
            FieldKind.string,
            FieldKind.longText,
            FieldKind.date,
            FieldKind.dateTime,
            FieldKind.enumeration,
            -> "string"
            else -> null
        } ?: return@mapNotNull null
        field.id to buildJsonObject { put("type", type) }
    }.toMap()
    if (observedProperties.isEmpty()) return this
    return copy(
        inputSchema = buildJsonObject {
            existing?.forEach { (key, value) ->
                if (key != "properties") put(key, value)
            }
            put("properties", buildJsonObject {
                existingProperties?.forEach { (key, value) -> put(key, value) }
                observedProperties.forEach { (key, value) ->
                    if (existingProperties?.containsKey(key) != true) put(key, value)
                }
            })
        },
    )
}

internal fun parseNativeBooleanMap(value: String): Map<String, Boolean>? = runCatching {
    val objectValue = Json.parseToJsonElement(value) as? JsonObject ?: return null
    if (objectValue.isEmpty() || objectValue.size > MAX_BOOLEAN_MAP_SETTINGS) return null
    objectValue.entries.associate { (key, element) ->
        if (!key.isSafeObservedSettingKey()) return null
        val enabled = (element as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return null
        key to enabled
    }
}.getOrNull()

internal fun updateNativeBooleanMap(value: String, key: String, enabled: Boolean): String {
    val current = parseNativeBooleanMap(value).orEmpty()
    return buildJsonObject {
        (current + (key to enabled)).forEach { (entryKey, entryValue) -> put(entryKey, entryValue) }
    }.toString()
}

private fun String.isSafeObservedSettingKey(): Boolean =
    length in 1..64 && all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

private const val MAX_BOOLEAN_MAP_SETTINGS = 32

data class NativeFormValidation(
    val values: Map<String, String>,
    val errors: Map<String, String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

fun validateNativeForm(
    resource: ResourceSpec,
    action: ActionSpec,
    values: Map<String, String>,
): NativeFormValidation {
    val fields = editableNativeFields(resource, action)
    val requiredByInput = action.inputSchema
        .let { it as? JsonObject }
        ?.get("required")
        .let { it as? JsonArray }
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
        .toSet()
    val normalized = fields.associate { field -> field.id to values[field.id].orEmpty().trim() }
    val errors = buildMap {
        fields.forEach { field ->
            val value = normalized.getValue(field.id)
            val required = field.required || field.id in requiredByInput
            val error = when {
                required && value.isBlank() -> "${field.label} is required."
                value.isBlank() -> null
                field.kind == FieldKind.integer && value.toLongOrNull() == null -> "Enter a whole number."
                field.kind in setOf(FieldKind.decimal, FieldKind.currency) && !value.isPlainDecimal() ->
                    "Enter a valid number."
                field.kind == FieldKind.boolean && value !in setOf("true", "false") ->
                    "Choose yes or no."
                field.kind == FieldKind.date && !value.isValidIsoDate() -> "Use YYYY-MM-DD."
                field.kind == FieldKind.dateTime && !value.isValidIsoDateTime() ->
                    "Use an ISO date and time."
                field.kind == FieldKind.enumeration && field.enumValues?.let { value !in it } == true ->
                    "Choose one of the available options."
                else -> null
            }
            if (error != null) put(field.id, error)
        }
    }
    return NativeFormValidation(normalized, errors)
}

fun editableNativeFields(resource: ResourceSpec, action: ActionSpec): List<FieldSpec> {
    val properties = (action.inputSchema as? JsonObject)?.get("properties") as? JsonObject
    return resource.fields.filter { field ->
        (!field.readOnly || action.binding.allowsObservedBodyFields) &&
            (!action.binding.allowsObservedBodyFields || !field.hasSensitiveSettingSemantics()) &&
            (field.kind !in setOf(FieldKind.objectValue, FieldKind.image, FieldKind.unknown) ||
                field.format in setOf(
                    SETTINGS_BOOLEAN_MAP_FORMAT,
                    DYNAMIC_STRING_LIST_FORMAT,
                    DYNAMIC_STRING_ARRAY_FORMAT,
                )) &&
            (action.binding.allowsObservedBodyFields || properties == null || field.id in properties)
    }
}

/**
 * Open observed-settings bodies are learned from a successful read, not a declared write schema.
 * Credential-like values therefore remain display-only even if an app accidentally includes them
 * in the same response. Explicitly declared OpenAPI forms are unaffected.
 */
private fun FieldSpec.hasSensitiveSettingSemantics(): Boolean {
    val normalized = (id + " " + label).lowercase().filter(Char::isLetterOrDigit)
    return normalized.contains("password") ||
        normalized.contains("passphrase") ||
        normalized.contains("secret") ||
        normalized.contains("credential") ||
        normalized.contains("privatekey") ||
        normalized.contains("apikey") ||
        normalized.contains("accesstoken") ||
        normalized.contains("refreshtoken") ||
        normalized.contains("recoverykey")
}

/**
 * Response-derived detail views must not turn account connection internals into a credential
 * dashboard. Explicit typed edit forms keep their declared fields; this filter affects display
 * metadata only.
 */
internal fun FieldSpec.isSafeNativeDetailField(resource: ResourceSpec): Boolean {
    if (hasSensitiveSettingSemantics()) return false
    val resourceIdentity = (resource.id + resource.name).lowercase().filter(Char::isLetterOrDigit)
    if (!resourceIdentity.contains("account")) return true
    val fieldIdentity = (id + label).lowercase().filter(Char::isLetterOrDigit)
    return fieldIdentity !in ACCOUNT_INTERNAL_DETAIL_FIELDS &&
        ACCOUNT_INTERNAL_DETAIL_PREFIXES.none(fieldIdentity::startsWith)
}

private val ACCOUNT_INTERNAL_DETAIL_FIELDS = setOf(
    "authmethod",
    "authentication",
    "encryption",
    "host",
    "hostname",
    "port",
    "server",
    "serverurl",
    "ssl",
    "sslmode",
    "tls",
    "tlsmode",
    "user",
    "username",
)

private val ACCOUNT_INTERNAL_DETAIL_PREFIXES = setOf(
    "imap",
    "smtp",
    "inbound",
    "outbound",
    "mailserver",
)

sealed interface NativeRequestBuildResult {
    data class Ready(val request: NativeActionRequest) : NativeRequestBuildResult
    data class Invalid(val message: String, val fieldErrors: Map<String, String> = emptyMap()) : NativeRequestBuildResult
}

fun buildNativeLoadRequest(schema: NativeAppSchema, view: ViewSpec): NativeRequestBuildResult {
    val action = schema.action(view.sourceActionId)
        ?: return NativeRequestBuildResult.Invalid("This view has no declared load action.")
    if (action.resourceId != view.resourceId || action.intent !in setOf(ActionIntent.list, ActionIntent.read) ||
        action.risk != ActionRisk.readOnly
    ) {
        return NativeRequestBuildResult.Invalid("The declared source action is not a safe read action for this view.")
    }
    return NativeRequestBuildResult.Ready(NativeActionRequest.Load(action))
}

fun buildNativeSubmitRequest(
    schema: NativeAppSchema,
    view: ViewSpec,
    values: Map<String, String>,
    confirmed: Boolean,
): NativeRequestBuildResult {
    if (view.genericSurface() != GenericNativeSurface.Form) {
        return NativeRequestBuildResult.Invalid("Only a schema-declared form can submit values.")
    }
    val action = schema.action(view.sourceActionId)
        ?: return NativeRequestBuildResult.Invalid("This form has no declared action.")
    val resource = schema.resource(view.resourceId)
        ?: return NativeRequestBuildResult.Invalid("This form references an unknown resource.")
    if (action.resourceId != resource.id ||
        action.intent in setOf(ActionIntent.list, ActionIntent.read) ||
        action.risk == ActionRisk.readOnly ||
        action.binding.method == HttpMethod.GET
    ) {
        return NativeRequestBuildResult.Invalid("The declared action cannot submit this form.")
    }
    val validation = validateNativeForm(resource, action, values)
    if (!validation.isValid) {
        return NativeRequestBuildResult.Invalid("Check the highlighted fields.", validation.errors)
    }
    return NativeRequestBuildResult.Ready(
        NativeActionRequest.Submit(action, validation.values, confirmed),
    )
}

fun interface NativeActionExecutor {
    suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult
}

sealed interface NativeActionExecutionResult {
    data class Success(val message: String? = null) : NativeActionExecutionResult
    data class Failure(val message: String) : NativeActionExecutionResult
}

sealed interface NativeActionExecutionState {
    data object Idle : NativeActionExecutionState
    data class ValidationFailed(val message: String, val fieldErrors: Map<String, String>) : NativeActionExecutionState
    data class AwaitingConfirmation(val request: NativeActionRequest.Submit) : NativeActionExecutionState
    data class Running(val request: NativeActionRequest.Submit) : NativeActionExecutionState
    data class Succeeded(val message: String?) : NativeActionExecutionState
    data class Failed(val message: String) : NativeActionExecutionState
}

class NativeActionCoordinator(
    private val schema: NativeAppSchema,
    private val view: ViewSpec,
    private val executor: NativeActionExecutor,
) {
    var state: NativeActionExecutionState by mutableStateOf(NativeActionExecutionState.Idle)
        private set

    suspend fun submit(values: Map<String, String>) {
        val built = buildNativeSubmitRequest(schema, view, values, confirmed = false)
        when (built) {
            is NativeRequestBuildResult.Invalid -> {
                state = NativeActionExecutionState.ValidationFailed(built.message, built.fieldErrors)
            }
            is NativeRequestBuildResult.Ready -> {
                val request = built.request as NativeActionRequest.Submit
                if (request.action.needsExplicitConfirmation()) {
                    state = NativeActionExecutionState.AwaitingConfirmation(request)
                } else {
                    execute(request)
                }
            }
        }
    }

    suspend fun confirm() {
        val pending = (state as? NativeActionExecutionState.AwaitingConfirmation)?.request ?: return
        execute(pending.copy(confirmed = true))
    }

    fun cancelConfirmation() {
        if (state is NativeActionExecutionState.AwaitingConfirmation) state = NativeActionExecutionState.Idle
    }

    fun clearStatus() {
        if (state !is NativeActionExecutionState.Running) state = NativeActionExecutionState.Idle
    }

    private suspend fun execute(request: NativeActionRequest.Submit) {
        state = NativeActionExecutionState.Running(request)
        state = try {
            when (val result = executor.execute(request)) {
                is NativeActionExecutionResult.Success -> NativeActionExecutionState.Succeeded(result.message)
                is NativeActionExecutionResult.Failure -> NativeActionExecutionState.Failed(result.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            NativeActionExecutionState.Failed(failure.message ?: "The action failed.")
        }
    }
}

fun ActionSpec.needsExplicitConfirmation(): Boolean =
    risk == ActionRisk.destructive || requiresConfirmation

private fun String.normalizeDecimal(): String =
    if (isPlainDecimal()) trim().removeSuffix(".0") else trim()

private fun String.isPlainDecimal(): Boolean = trim().matches(Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"))

private fun String.isValidIsoDate(): Boolean {
    val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(this) ?: return false
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    if (month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}

private fun String.isValidIsoDateTime(): Boolean {
    val parts = split('T', ' ', limit = 2)
    if (parts.size != 2 || !parts[0].isValidIsoDate()) return false
    val match = Regex("(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d{1,9})?)?(?:Z|([+-])(\\d{2}):(\\d{2}))?")
        .matchEntire(parts[1]) ?: return false
    if (match.groupValues[1].toInt() !in 0..23 || match.groupValues[2].toInt() !in 0..59) return false
    if (match.groupValues[3].isNotEmpty() && match.groupValues[3].toInt() !in 0..59) return false
    if (match.groupValues[5].isNotEmpty() && match.groupValues[5].toInt() !in 0..23) return false
    return match.groupValues[6].isEmpty() || match.groupValues[6].toInt() in 0..59
}

private fun String.humanizeIdentifier(): String =
    replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
