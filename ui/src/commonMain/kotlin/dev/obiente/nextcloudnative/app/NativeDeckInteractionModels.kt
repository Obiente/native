package dev.obiente.nextcloudnative.app

data class DeckUiBoardDraft(
    val title: String,
    val color: String,
) {
    fun normalized(): DeckUiBoardDraft = copy(
        title = title.trim(),
        color = color.trim().removePrefix("#").lowercase(),
    )

    fun validationError(): String? {
        val value = normalized()
        return when {
            value.title.isBlank() -> "Enter a board name."
            value.title.length > DECK_UI_BOARD_TITLE_LIMIT -> "Board names can be up to 100 characters."
            value.title.hasControlCharacters() -> "The board name contains unsupported characters."
            !value.color.isDeckUiColor() -> "Choose a valid board color."
            else -> null
        }
    }
}

data class DeckUiStackDraft(
    val title: String,
) {
    fun normalized(): DeckUiStackDraft = copy(title = title.trim())

    fun validationError(): String? {
        val value = normalized()
        return when {
            value.title.isBlank() -> "Enter a list name."
            value.title.length > DECK_UI_STACK_TITLE_LIMIT -> "List names can be up to 100 characters."
            value.title.hasControlCharacters() -> "The list name contains unsupported characters."
            else -> null
        }
    }
}

data class DeckUiCardDraft(
    val title: String,
    val descriptionMarkdown: String,
    val dueDate: String,
    val dueTime: String,
) {
    fun normalized(): DeckUiCardDraft = copy(
        title = title.trim(),
        descriptionMarkdown = descriptionMarkdown.trim(),
        dueDate = dueDate.trim(),
        dueTime = dueTime.trim(),
    )

    fun validationError(): String? {
        val value = normalized()
        return when {
            value.title.isBlank() -> "Enter a card title."
            value.title.length > DECK_UI_CARD_TITLE_LIMIT -> "Card titles can be up to 255 characters."
            value.title.hasControlCharacters() -> "The card title contains unsupported characters."
            value.descriptionMarkdown.length > DECK_UI_CARD_DESCRIPTION_LIMIT ->
                "The description is too long."
            value.dueDate.isBlank() && value.dueTime.isNotBlank() ->
                "Choose a due date before adding a time."
            value.dueDate.isNotBlank() && !isValidDeckUiDate(value.dueDate) ->
                "Use a valid date in YYYY-MM-DD format."
            value.dueTime.isNotBlank() && !isValidDeckUiTime(value.dueTime) ->
                "Use a valid time in HH:MM format."
            else -> null
        }
    }

    val hasDueDate: Boolean
        get() = dueDate.isNotBlank()
}

enum class DeckUiCardPlacement {
    Top,
    Bottom,
}

sealed interface DeckUiInteraction {
    data class BoardEditor(val board: DeckBoard?) : DeckUiInteraction
    data class StackEditor(
        val board: DeckBoard,
        val stack: DeckStack?,
    ) : DeckUiInteraction
    data class CardEditor(
        val stack: DeckStack,
        val card: DeckCard?,
    ) : DeckUiInteraction
    data class MoveCard(val card: DeckCard) : DeckUiInteraction
    data class Labels(val card: DeckCard) : DeckUiInteraction
    data class Assignees(val card: DeckCard) : DeckUiInteraction
    data class DueDate(val card: DeckCard) : DeckUiInteraction
    data class Comments(val card: DeckCard) : DeckUiInteraction
    data class Attachments(val card: DeckCard) : DeckUiInteraction
    data class DeleteBoard(val board: DeckBoard) : DeckUiInteraction
    data class DeleteStack(val stack: DeckStack) : DeckUiInteraction
    data class DeleteCard(val card: DeckCard) : DeckUiInteraction
}

data class DeckUiDueDateOption(
    val label: String,
    val date: String,
) {
    init {
        require(label.isNotBlank()) { "A due date option needs a label." }
        require(isValidDeckUiDate(date)) { "A due date option needs a valid date." }
    }
}

data class DeckUiComment(
    val key: String,
    val author: DeckUser?,
    val messageMarkdown: String,
    val createdLabel: String,
    val edited: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val replyToLabel: String? = null,
) {
    init {
        require(key.isNotBlank()) { "A comment needs a stable key." }
        require(messageMarkdown.isNotBlank()) { "A comment cannot be empty." }
    }
}

data class DeckUiCommentsState(
    val comments: List<DeckUiComment>,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val submitting: Boolean = false,
    val canComment: Boolean = false,
    val errorMessage: String? = null,
)

data class DeckUiAttachment(
    val key: String,
    val fileName: String,
    val supportingText: String?,
    val canOpen: Boolean,
    val canDelete: Boolean,
) {
    init {
        require(key.isNotBlank()) { "An attachment needs a stable key." }
        require(fileName.isNotBlank()) { "An attachment needs a file name." }
    }
}

data class DeckUiAttachmentsState(
    val attachments: List<DeckUiAttachment>,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val adding: Boolean = false,
    val canAdd: Boolean = false,
    val errorMessage: String? = null,
)

fun validateDeckUiComment(message: String): String? {
    val value = message.trim()
    return when {
        value.isBlank() -> "Write a comment first."
        value.length > DECK_UI_COMMENT_LIMIT -> "Comments can be up to 1,000 characters."
        else -> null
    }
}

internal fun isValidDeckUiDate(value: String): Boolean {
    if (value.length != 10 || value[4] != '-' || value[7] != '-') return false
    if (!value.positionsAreDigits(0..3) ||
        !value.positionsAreDigits(5..6) ||
        !value.positionsAreDigits(8..9)
    ) {
        return false
    }
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (year !in 1..9999 || month !in 1..12) return false
    val monthLength = when (month) {
        2 -> if (year.isLeapYear()) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..monthLength
}

internal fun isValidDeckUiTime(value: String): Boolean {
    if (value.length != 5 || value[2] != ':') return false
    if (!value.positionsAreDigits(0..1) || !value.positionsAreDigits(3..4)) return false
    return value.substring(0, 2).toInt() in 0..23 &&
        value.substring(3, 5).toInt() in 0..59
}

internal fun deckUiDateToEpochMillis(value: String): Long? {
    if (!isValidDeckUiDate(value)) return null
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    var adjustedYear = year
    if (month <= 2) adjustedYear -= 1
    val era = if (adjustedYear >= 0) adjustedYear / 400 else (adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val epochDay = era * 146_097L + dayOfEra - 719_468L
    return epochDay * MILLIS_PER_DAY
}

internal fun deckUiDateFromEpochMillis(value: Long): String? {
    var epochDay = value / MILLIS_PER_DAY
    if (value < 0L && value % MILLIS_PER_DAY != 0L) epochDay -= 1L
    val zeroDay = epochDay + 719_468L
    val era = if (zeroDay >= 0L) zeroDay / 146_097L else (zeroDay - 146_096L) / 146_097L
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra = (
        dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L
        ) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPart = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPart + 2L) / 5L + 1L
    val month = monthPart + if (monthPart < 10L) 3L else -9L
    if (month <= 2L) year += 1L
    if (year !in 1L..9999L) return null
    return buildString(10) {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
    }.takeIf(::isValidDeckUiDate)
}

private fun String.positionsAreDigits(range: IntRange): Boolean =
    range.all { index -> getOrNull(index)?.isDigit() == true }

private fun String.hasControlCharacters(): Boolean = any(Char::isISOControl)

private fun String.isDeckUiColor(): Boolean =
    length == 6 && all { character ->
        character.isDigit() || character.lowercaseChar() in 'a'..'f'
    }

private fun Int.isLeapYear(): Boolean =
    this % 4 == 0 && (this % 100 != 0 || this % 400 == 0)

private const val DECK_UI_BOARD_TITLE_LIMIT = 100
private const val DECK_UI_STACK_TITLE_LIMIT = 100
private const val DECK_UI_CARD_TITLE_LIMIT = 255
private const val DECK_UI_CARD_DESCRIPTION_LIMIT = 64 * 1024
private const val DECK_UI_COMMENT_LIMIT = 1_000
private const val MILLIS_PER_DAY = 86_400_000L
