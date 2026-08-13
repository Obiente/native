package dev.obiente.nextcloudnative.nativeui.runtime

/**
 * A reusable semantic projection for apps that expose a group with nested people and invitations.
 * Field aliases are intentionally structural and app-neutral; callers decide whether a roster is
 * the appropriate surface for their verified app workspace.
 */
internal data class NativeRosterPresentation(
    val id: String,
    val name: String,
    val ownerUserId: String?,
    val people: List<NativeRosterPerson>,
    val invitations: List<NativeRosterInvitation>,
)

internal data class NativeRosterPerson(
    val userId: String,
    val displayName: String,
    val score: Int?,
    val owner: Boolean,
)

internal data class NativeRosterInvitation(val userId: String)

internal fun nativeRosterPresentation(record: NativeRecord): NativeRosterPresentation? {
    // Sparse contracts may leave safe response scalars display-only. They are valid presentation
    // evidence, but never action-binding evidence; raw declared values win when both are present.
    val values = buildMap {
        record.displayValues.forEach { (key, value) -> put(key.rosterKey(), value) }
        record.values.forEach { (key, value) -> put(key.rosterKey(), value) }
    }
    val structured = record.structuredValues.entries.associate { (key, value) -> key.rosterKey() to value }
    val owner = values.firstRosterValue("owner", "ownerUserId", "ownerId")
    val people = structured.firstRosterObjects("members", "people", "participants", "users")
        .mapNotNull { person ->
            val userId = person.firstRosterValue("member", "memberUserId", "userId", "uid", "user")
                ?: return@mapNotNull null
            NativeRosterPerson(
                userId = userId,
                displayName = person.firstRosterValue("displayName", "name", "label") ?: userId,
                score = person.firstRosterValue("points", "score", "credits")?.toIntOrNull(),
                owner = owner != null && userId == owner,
            )
        }
    val invitations = structured.firstRosterObjects("invites", "invitations", "pendingInvitations")
        .mapNotNull { invitation ->
            invitation.firstRosterValue("userId", "uid", "user", "invitee")
                ?.let(::NativeRosterInvitation)
        }
    if (people.isEmpty() && invitations.isEmpty()) return null
    return NativeRosterPresentation(
        id = record.id,
        name = values.firstRosterValue("name", "teamName", "title") ?: "Team",
        ownerUserId = owner,
        people = people,
        invitations = invitations,
    )
}

private fun Map<String, String?>.firstRosterValue(vararg aliases: String): String? =
    aliases.firstNotNullOfOrNull { alias -> this[alias.rosterKey()]?.trim()?.takeIf(String::isNotBlank) }

private fun Map<String, NativeStructuredValue>.firstRosterObjects(
    vararg aliases: String,
): List<Map<String, String?>> = aliases.firstNotNullOfOrNull { alias ->
    (this[alias.rosterKey()] as? NativeStructuredValue.ListValue)?.items?.mapNotNull { item ->
        (item as? NativeStructuredValue.ObjectValue)?.entries?.associate { entry ->
            entry.key.rosterKey() to (entry.value as? NativeStructuredValue.Scalar)?.value
        }
    }
}.orEmpty()

private fun String.rosterKey(): String = lowercase().filter(Char::isLetterOrDigit)
