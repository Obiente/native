package dev.obiente.nextcloudnative.app

import java.time.LocalDate

internal actual fun currentDeviceLocalFileShareDate(): FileShareCalendarDate {
    val date = LocalDate.now()
    return FileShareCalendarDate(date.year, date.monthValue, date.dayOfMonth)
}
