package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Reviewed Cospend bill writes.
 *
 * The Cospend OpenAPI document declares every bill field as an optional scalar. The reviewed
 * server source defines their meaning and the values a new bill must carry:
 * - LocalProjectService::createBill requires a one-letter `repeat` frequency ("n" is no repeat),
 *   a `date` as YYYY-MM-DD when no `timestamp` is sent, an `amount`, a `payer` member ID, and
 *   `payedFor` as a comma-separated list of member IDs of the same project:
 *   https://github.com/julien-nc/cospend-nc/blob/v4.3.0/lib/Service/LocalProjectService.php
 * - ApiController requires access level 2 (participant) for bill create, edit, and delete:
 *   https://github.com/julien-nc/cospend-nc/blob/v4.3.0/lib/Controller/ApiController.php
 *   https://github.com/julien-nc/cospend-nc/blob/v4.3.0/lib/AppInfo/Application.php
 *
 * Members, categories, and payment modes come from the authoritative project record that opened
 * the bill list. Every action still comes from the verified contract.
 */

internal data class NativeCospendMember(val id: Long, val name: String, val active: Boolean)

internal data class NativeCospendBillPlan(
    val projectId: String,
    val members: List<NativeCospendMember>,
    val categories: List<NativeReviewedChoice>,
    val paymentModes: List<NativeReviewedChoice>,
    val create: NativeReviewedAction?,
    val update: NativeReviewedAction?,
    val delete: NativeReviewedAction?,
) {
    val activeMembers: List<NativeCospendMember> get() = members.filter(NativeCospendMember::active)
}

internal data class NativeCospendBillDraft(
    val what: String = "",
    val amount: String = "",
    val date: String = "",
    val payerId: String = "",
    val owerIds: Set<String> = emptySet(),
    val categoryId: String = "",
    val paymentModeId: String = "",
    val comment: String = "",
)

internal data class NativeCospendBill(val id: Long, val draft: NativeCospendBillDraft)

internal enum class NativeCospendBillField { What, Amount, Date, Payer, Owers }

internal sealed interface NativeCospendBillRequestResult {
    data class Ready(val request: NativeActionRequest.Submit) : NativeCospendBillRequestResult

    data class Invalid(val errors: Map<NativeCospendBillField, String>) : NativeCospendBillRequestResult

    data object Unavailable : NativeCospendBillRequestResult
}

internal fun NativeAppSchema.supportsReviewedCospendBills(): Boolean =
    app.id == "cospend" && app.reviewedMajorMinor()?.first == 4

/** Plans bill writes for the bill collection of one project, or null when they are not safe. */
internal fun nativeCospendBillPlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    datasetContext: NativeDatasetContext,
): NativeCospendBillPlan? {
    if (!schema.supportsReviewedCospendBills() || resource.id != "bills") return null
    val project = datasetContext.parentRecord?.takeIf { record ->
        record.actionSafeIdentity && record.actionBindingProvenanceValid && datasetContext.parentResourceId == "projects"
    } ?: return null
    val projectId = project.id.takeIf(String::isSafeCospendProjectId) ?: return null
    datasetContext.bindingValues["projectId"]?.let { bound -> if (bound != projectId) return null }
    val accessLevel = project.values["myaccesslevel"]?.trim()?.toIntOrNull() ?: return null
    if (accessLevel < COSPEND_PARTICIPANT) return null
    val members = parseCospendMembers(project.values["members"]) ?: return null
    if (members.none(NativeCospendMember::active)) return null
    val categories = parseCospendChoices(project.values["categories"]) ?: return null
    val paymentModes = parseCospendChoices(project.values["paymentmodes"]) ?: return null
    val create = schema.reviewedAction(HttpMethod.POST, BILLS_ROUTE, CREATE_FIELDS)
    val update = schema.reviewedAction(HttpMethod.PUT, BILL_ROUTE, CREATE_FIELDS - "repeat")
    val delete = schema.reviewedAction(HttpMethod.DELETE, BILL_ROUTE)
    if (create == null && update == null && delete == null) return null
    return NativeCospendBillPlan(projectId, members, categories, paymentModes, create, update, delete)
}

/** Reads one bill record of [plan]'s project. Bills naming unknown members are not editable. */
internal fun NativeCospendBillPlan.bill(record: NativeRecord): NativeCospendBill? {
    if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return null
    val id = record.id.reviewedPositiveId() ?: return null
    val memberIds = members.mapTo(hashSetOf()) { it.id.toString() }
    val payer = record.values["payer_id"]?.trim()?.takeIf { it in memberIds } ?: return null
    val owers = record.values["owerIds"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        ?.takeIf { ids -> ids.isNotEmpty() && ids.all { it in memberIds } } ?: return null
    val amount = record.values["amount"]?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    return NativeCospendBill(
        id,
        NativeCospendBillDraft(
            what = record.values["what"].orEmpty(),
            amount = amount.toString().removeSuffix(".0"),
            date = record.values["date"]?.trim()?.takeIf(String::isCospendDate).orEmpty(),
            payerId = payer,
            owerIds = owers,
            categoryId = record.values["categoryid"]?.trim()?.takeIf { id -> categories.any { it.id == id } }.orEmpty(),
            paymentModeId = record.values["paymentmodeid"]?.trim()
                ?.takeIf { id -> paymentModes.any { it.id == id } }.orEmpty(),
            comment = record.values["comment"].orEmpty(),
        ),
    )
}

internal fun NativeCospendBillPlan.newDraft(today: String): NativeCospendBillDraft = NativeCospendBillDraft(
    date = today.takeIf(String::isCospendDate).orEmpty(),
    owerIds = activeMembers.mapTo(linkedSetOf()) { it.id.toString() },
)

internal fun NativeCospendBillPlan.validate(draft: NativeCospendBillDraft): Map<NativeCospendBillField, String> {
    val activeIds = activeMembers.mapTo(hashSetOf()) { it.id.toString() }
    val knownIds = members.mapTo(hashSetOf()) { it.id.toString() }
    return buildMap {
        if (draft.what.isBlank()) put(NativeCospendBillField.What, "Describe what was paid for.")
        if (draft.amount.trim().toDoubleOrNull()?.takeIf(Double::isFinite) == null) {
            put(NativeCospendBillField.Amount, "Enter an amount.")
        }
        if (!draft.date.trim().isCospendDate()) put(NativeCospendBillField.Date, "Use YYYY-MM-DD.")
        if (draft.payerId !in activeIds) put(NativeCospendBillField.Payer, "Choose who paid.")
        if (draft.owerIds.isEmpty() || !knownIds.containsAll(draft.owerIds)) {
            put(NativeCospendBillField.Owers, "Choose at least one person this was paid for.")
        }
    }
}

internal fun NativeCospendBillPlan.createRequest(draft: NativeCospendBillDraft): NativeCospendBillRequestResult {
    val create = create ?: return NativeCospendBillRequestResult.Unavailable
    validate(draft).takeIf(Map<*, *>::isNotEmpty)?.let { return NativeCospendBillRequestResult.Invalid(it) }
    return ready(create, listOf(projectId), draft.values() + ("repeat" to "n"))
}

/** Sends every editable field so repeating the same request leaves the same bill. */
internal fun NativeCospendBillPlan.updateRequest(bill: NativeCospendBill, draft: NativeCospendBillDraft): NativeCospendBillRequestResult {
    val update = update ?: return NativeCospendBillRequestResult.Unavailable
    validate(draft).filterKeys { field ->
        // An existing bill may keep a payer who has since been deactivated.
        !(field == NativeCospendBillField.Payer && draft.payerId == bill.draft.payerId)
    }.takeIf(Map<*, *>::isNotEmpty)?.let { return NativeCospendBillRequestResult.Invalid(it) }
    return ready(update, listOf(projectId, bill.id.toString()), draft.values())
}

/** Cospend moves a deleted bill to the project trash by default; this keeps that default. */
internal fun NativeCospendBillPlan.deleteRequest(bill: NativeCospendBill): NativeCospendBillRequestResult {
    val delete = delete ?: return NativeCospendBillRequestResult.Unavailable
    val trash = if ("moveToTrash" in delete.action.binding.bodyFieldNames + delete.action.binding.queryParameterNames) {
        mapOf("moveToTrash" to "true")
    } else {
        emptyMap()
    }
    return ready(delete, listOf(projectId, bill.id.toString()), trash)
}

private fun NativeCospendBillDraft.values(): Map<String, String> = buildMap {
    put("what", what.trim())
    put("amount", amount.trim())
    put("date", date.trim())
    put("payer", payerId)
    put("payedFor", owerIds.mapNotNull(String::toLongOrNull).sorted().joinToString(","))
    // An empty category or payment mode means none; Cospend stores 0 for both.
    put("categoryId", categoryId.ifEmpty { "0" })
    put("paymentModeId", paymentModeId.ifEmpty { "0" })
    put("comment", comment)
}

private fun ready(action: NativeReviewedAction, identities: List<String>, values: Map<String, String>) =
    NativeCospendBillRequestResult.Ready(
        NativeActionRequest.Submit(
            action.action,
            values.filterKeys { key ->
                key in action.action.binding.bodyFieldNames || key in action.action.binding.queryParameterNames
            } + action.placeholders.zip(identities),
            confirmed = true,
        ),
    )

private fun parseCospendMembers(raw: String?): List<NativeCospendMember>? {
    val array = raw.reviewedJsonOrNull() as? JsonArray ?: return null
    if (array.size > MAX_COSPEND_ENTRIES) return null
    val members = array.map { element ->
        val member = element as? JsonObject ?: return null
        val id = (member["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val name = (member["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val active = (member["activated"] as? JsonPrimitive)?.booleanOrNull ?: return null
        NativeCospendMember(id, name.take(200), active)
    }
    return members.takeIf { list -> list.map(NativeCospendMember::id).distinct().size == list.size }
}

/** Categories and payment modes are objects keyed by ID. An absent or empty map means none. */
private fun parseCospendChoices(raw: String?): List<NativeReviewedChoice>? {
    if (raw.isNullOrBlank()) return emptyList()
    val element: JsonElement = raw.reviewedJsonOrNull() ?: return null
    if (element is JsonArray && element.isEmpty()) return emptyList()
    val entries = (element as? JsonObject)?.values ?: return null
    if (entries.size > MAX_COSPEND_ENTRIES) return null
    return entries.map { entry ->
        val choice = entry as? JsonObject ?: return null
        val id = (choice["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val name = (choice["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: "Unnamed"
        NativeReviewedChoice(id.toString(), name.take(200))
    }.distinctBy(NativeReviewedChoice::id)
}

private fun String.isSafeCospendProjectId(): Boolean =
    length in 1..64 && all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && this != "." && this != ".."

internal fun String.isCospendDate(): Boolean {
    if (length != 10 || this[4] != '-' || this[7] != '-') return false
    val month = substring(5, 7).toIntOrNull() ?: return false
    val day = substring(8, 10).toIntOrNull() ?: return false
    return substring(0, 4).toIntOrNull() != null && month in 1..12 && day in 1..31
}

private val BILLS_ROUTE = reviewedRoute("/ocs/v2.php/apps/cospend/api/v1/projects/{}/bills")
private val BILL_ROUTE = reviewedRoute("/ocs/v2.php/apps/cospend/api/v1/projects/{}/bills/{}")
private val CREATE_FIELDS = setOf("what", "amount", "date", "payer", "payedFor", "repeat")
private const val COSPEND_PARTICIPANT = 2
private const val MAX_COSPEND_ENTRIES = 500
