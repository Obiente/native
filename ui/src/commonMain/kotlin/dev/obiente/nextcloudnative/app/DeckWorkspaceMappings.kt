package dev.obiente.nextcloudnative.app

/** Mappings between Deck records and the workspace's editor and dialog models. */
internal fun DeckCard.toUpdate(
    title: String = this.title,
    description: String = descriptionMarkdown.orEmpty(),
    dueAt: String? = this.dueAt,
    completedAt: String? = this.completedAt,
): DeckCardUpdate = DeckCardUpdate(
    original = this,
    title = title,
    order = order,
    descriptionMarkdown = description,
    dueAt = dueAt,
    startAt = startAt,
    archived = archived,
    completedAt = completedAt,
)

internal fun deckUiInstant(date: String, time: String): String? {
    val normalizedDate = date.trim()
    if (normalizedDate.isEmpty()) return null
    val normalizedTime = time.trim().ifEmpty { "23:59" }
    return deckLocalDateTimeToInstant(normalizedDate, normalizedTime)
}

internal fun deckQuickDueDates(): List<DeckUiDueDateOption> = listOf(
    DeckUiDueDateOption("Today", deckLocalDatePlusDays(0)),
    DeckUiDueDateOption("Tomorrow", deckLocalDatePlusDays(1)),
    DeckUiDueDateOption("Next week", deckLocalDatePlusDays(7)),
)

internal val DeckBoard.canWriteDeckComments: Boolean
    get() = permissions.canRead && !archived

internal fun DeckComment.toDeckUiComment(
    currentUserId: String,
    canWriteComments: Boolean,
): DeckUiComment =
    DeckUiComment(
        key = id.toString(),
        author = authorId?.let { DeckUser(it, authorDisplayName) },
        messageMarkdown = message,
        createdLabel = createdAt ?: "Unknown time",
        edited = false,
        canEdit = canWriteComments && authorId == currentUserId,
        canDelete = canWriteComments && authorId == currentUserId,
        replyToLabel = replyToId?.let { "Reply to comment $it" },
    )

internal fun DeckAttachment.deckUiKey(): String = "${type.serverValue}:$id"

internal fun DeckAttachment.toDeckUiAttachment(
    canEdit: Boolean,
    handoffCapability: ExternalFileHandoffCapability?,
): DeckUiAttachment =
    DeckUiAttachment(
        key = deckUiKey(),
        fileName = name,
        supportingText = listOfNotNull(
            mimeType,
        byteCount?.let(::formatByteSize),
        createdBy,
    ).joinToString(" - ").ifBlank { null },
        canOpen = handoffCapability != null &&
            ExternalFileHandoffAction.OpenWith in handoffCapability.supportedActions,
        canDelete = canEdit,
    )

internal fun ExternalFileHandoffResult.deckAttachmentHandoffMessage(): String? = when (this) {
    is ExternalFileHandoffResult.Launched -> null
    is ExternalFileHandoffResult.Cancelled -> null
    is ExternalFileHandoffResult.NoCompatibleApplication ->
        "No installed app can open this attachment."
    is ExternalFileHandoffResult.Rejected -> message
    is ExternalFileHandoffResult.Unsupported -> reason
}

internal fun Throwable.deckMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "The app could not complete the action."

internal const val DECK_COMMENT_PAGE_SIZE = 20
internal const val DECK_STACK_ORDER_STEP = 1_000L
internal const val DECK_ATTACHMENT_MUTATION_RESPONSE_BYTES = 2L * 1024L * 1024L
