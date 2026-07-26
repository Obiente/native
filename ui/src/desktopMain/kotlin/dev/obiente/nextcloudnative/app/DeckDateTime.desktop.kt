package dev.obiente.nextcloudnative.app

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual fun deckInstantToLocalDateTime(value: String): DeckLocalDateTime? =
    runCatching {
        val local = Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
        DeckLocalDateTime(
            date = local.toLocalDate().toString(),
            time = local.toLocalTime().format(DECK_LOCAL_TIME_FORMATTER),
        )
    }.getOrNull()

internal actual fun deckLocalDateTimeToInstant(date: String, time: String): String =
    LocalDateTime.parse("${date}T${time}:00")
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toString()

private val DECK_LOCAL_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")
