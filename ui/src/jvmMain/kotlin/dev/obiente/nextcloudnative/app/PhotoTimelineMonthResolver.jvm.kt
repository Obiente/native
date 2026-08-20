package dev.obiente.nextcloudnative.app

import java.time.Instant
import java.time.ZoneId

internal actual fun platformLocalPhotoTimelineMonthResolver(): PhotoTimelineMonthResolver {
    val zone = ZoneId.systemDefault()
    return PhotoTimelineMonthResolver { epochSeconds ->
        val local = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        PhotoTimelineMonth(local.year, local.monthValue)
    }
}
