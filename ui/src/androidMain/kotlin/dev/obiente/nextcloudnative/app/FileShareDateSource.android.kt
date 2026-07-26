package dev.obiente.nextcloudnative.app

import java.util.Calendar

internal actual fun currentDeviceLocalFileShareDate(): FileShareCalendarDate {
    val calendar = Calendar.getInstance()
    return FileShareCalendarDate(
        year = calendar.get(Calendar.YEAR),
        month = calendar.get(Calendar.MONTH) + 1,
        day = calendar.get(Calendar.DAY_OF_MONTH),
    )
}
