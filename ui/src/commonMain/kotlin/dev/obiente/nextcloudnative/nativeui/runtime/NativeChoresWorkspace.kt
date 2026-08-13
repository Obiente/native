package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

internal enum class NativeChoresWorkspaceKind {
    Team,
    Invitations,
    Chores,
    History,
}

internal data class NativeChoresMetric(val label: String, val value: String)

internal data class NativeChoresItem(
    val record: NativeRecord,
    val kind: NativeChoresWorkspaceKind,
    val title: String,
    val subtitle: String?,
    val metrics: List<NativeChoresMetric>,
)

internal sealed interface NativeChoresContent {
    data object Loading : NativeChoresContent
    data class Ready(val items: List<NativeChoresItem>) : NativeChoresContent
    data class Empty(val title: String, val message: String) : NativeChoresContent
    data class Error(val message: String, val retry: (() -> Unit)?, val retryLabel: String) : NativeChoresContent
}

internal data class NativeChoresPresentation(
    val kind: NativeChoresWorkspaceKind,
    val title: String,
    val subtitle: String,
    val content: NativeChoresContent,
)

/**
 * Recognizes the exact read hierarchy verified from the signed Chores 0.1.0 package.
 * Similar-looking routes from other apps intentionally stay on the generic renderer.
 */
internal fun nativeChoresWorkspaceKind(
    schema: NativeAppSchema,
    view: ViewSpec,
): NativeChoresWorkspaceKind? {
    if (schema.app.id != "chores") return null
    val action = schema.action(view.sourceActionId) ?: return null
    if (
        action.confidence !in setOf(Confidence.high, Confidence.verified) ||
        action.evidence.none { it.source == EvidenceSource.verifiedAppPackage } ||
        action.binding.method != HttpMethod.GET ||
        action.risk != ActionRisk.readOnly ||
        action.intent !in setOf(ActionIntent.list, ActionIntent.read)
    ) {
        return null
    }
    return when (action.binding.path.substringBefore('?').trimEnd('/')) {
        "/apps/chores/api/v1.0/team",
        "/apps/chores/api/v1.0/account/team" -> NativeChoresWorkspaceKind.Team
        "/apps/chores/api/v1.0/account/invites" -> NativeChoresWorkspaceKind.Invitations
        "/apps/chores/api/v1.0/team/{teamId}/chores" -> NativeChoresWorkspaceKind.Chores
        "/apps/chores/api/v1.0/team/{teamId}/work" -> NativeChoresWorkspaceKind.History
        else -> null
    }
}

internal fun nativeChoresPresentation(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    state: NativeScreenState,
): NativeChoresPresentation? {
    val kind = nativeChoresWorkspaceKind(schema, view) ?: return null
    val (title, subtitle) = when (kind) {
        NativeChoresWorkspaceKind.Team -> "Team" to "Members, points, and pending invitations"
        NativeChoresWorkspaceKind.Invitations -> "Invitations" to "Teams you can join"
        NativeChoresWorkspaceKind.Chores -> "All chores" to "Assignments and recurring household work"
        NativeChoresWorkspaceKind.History -> "History" to "Recently completed chores"
    }
    val content = when (state) {
        NativeScreenState.Loading -> NativeChoresContent.Loading
        is NativeScreenState.Error -> NativeChoresContent.Error(state.message, state.retry, state.retryLabel)
        is NativeScreenState.Ready -> if (state.records.isEmpty()) {
            when (kind) {
                NativeChoresWorkspaceKind.Team -> NativeChoresContent.Empty(
                    "No team yet",
                    "Accept an invitation or create a team to start sharing chores.",
                )
                NativeChoresWorkspaceKind.Invitations -> NativeChoresContent.Empty(
                    "No invitations",
                    "Team invitations will appear here.",
                )
                NativeChoresWorkspaceKind.Chores -> NativeChoresContent.Empty(
                    "Nothing to do",
                    "New chores will appear here as soon as they are added.",
                )
                NativeChoresWorkspaceKind.History -> NativeChoresContent.Empty(
                    "No completed chores",
                    "Chores you finish will be recorded here.",
                )
            }
        } else {
            NativeChoresContent.Ready(state.records.map { nativeChoresItem(kind, resource, it) })
        }
    }
    return NativeChoresPresentation(kind, title, subtitle, content)
}

private fun nativeChoresItem(
    kind: NativeChoresWorkspaceKind,
    resource: ResourceSpec,
    record: NativeRecord,
): NativeChoresItem {
    val values = ChoresValues(record)
    return when (kind) {
        NativeChoresWorkspaceKind.Team -> {
            val semantic = nativeHouseholdPresentation(resource, record)
            NativeChoresItem(
                record = record,
                kind = kind,
                title = semantic?.title ?: values.string("name") ?: "Team",
                subtitle = (semantic?.owner ?: values.string("owner"))?.let { "Owned by $it" },
                metrics = listOfNotNull(
                    (semantic?.memberCount ?: values.structuredCount("members"))
                        ?.let { NativeChoresMetric("Members", it.toString()) },
                    (semantic?.invitationCount ?: values.structuredCount("invites"))
                        ?.let { NativeChoresMetric("Pending", it.toString()) },
                ),
            )
        }
        NativeChoresWorkspaceKind.Invitations -> NativeChoresItem(
            record = record,
            kind = kind,
            title = values.string("teamName") ?: "Team invitation",
            subtitle = "You have been invited to join this team",
            metrics = emptyList(),
        )
        NativeChoresWorkspaceKind.Chores -> {
            val semantic = nativeGroupwarePresentation(resource, record)
                ?.takeIf { it.kind == NativeGroupwareItemKind.Task }
            val points = semantic?.effortPoints ?: values.int("points")
            NativeChoresItem(
                record = record,
                kind = kind,
                title = semantic?.title ?: values.string("name") ?: "Untitled chore",
                subtitle = (semantic?.assignee ?: values.string("assignee"))?.let { "Assigned to $it" },
                metrics = listOfNotNull(
                    points?.let { NativeChoresMetric("Points", it.toString()) },
                    (semantic?.due ?: values.string("due"))?.compactSemanticDateTime()
                        ?.let { NativeChoresMetric("Due", it) },
                    (semantic?.recurrenceRule ?: values.string("repeat"))?.taskRecurrenceLabel()
                        ?.let { NativeChoresMetric("Repeats", it) },
                ),
            )
        }
        NativeChoresWorkspaceKind.History -> {
            val semantic = nativeHouseholdPresentation(resource, record)
            val points = semantic?.points ?: values.int("points")
            NativeChoresItem(
                record = record,
                kind = kind,
                title = semantic?.title ?: values.string("name") ?: "Completed chore",
                subtitle = (semantic?.member ?: values.string("member"))?.let { "Completed by $it" },
                metrics = listOfNotNull(
                    points?.let { NativeChoresMetric("Points", it.toString()) },
                    (semantic?.completedAt ?: values.string("work_time"))
                        ?.compactSemanticDateTime()?.let { NativeChoresMetric("Done", it) },
                ),
            )
        }
    }
}

private class ChoresValues(record: NativeRecord) {
    private val values = buildMap {
        record.values.forEach { (key, value) -> value?.let { put(key.choresKey(), it) } }
        record.displayValues.forEach { (key, value) -> put(key.choresKey(), value) }
    }
    private val structured = record.structuredValues.mapKeys { (key, _) -> key.choresKey() }

    fun string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        values[name.choresKey()]?.trim()?.takeIf(String::isNotBlank)
    }

    fun int(vararg names: String): Int? = string(*names)?.toIntOrNull()

    fun structuredCount(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        (structured[name.choresKey()] as? NativeStructuredValue.ListValue)?.let { it.items.size + it.omittedItems }
    }
}

private fun String.choresKey(): String = lowercase().filter(Char::isLetterOrDigit)
