package dev.obiente.nextcloudnative.app

internal data class FileShareCalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<FileShareCalendarDate> {
    init {
        require(year in 1970..9999)
        require(month in 1..12)
        require(day in 1..31)
    }

    override fun compareTo(other: FileShareCalendarDate): Int =
        compareValuesBy(this, other, FileShareCalendarDate::year, FileShareCalendarDate::month, FileShareCalendarDate::day)
}

internal fun interface FileShareDateSource {
    fun currentDeviceLocalDate(): FileShareCalendarDate
}

/**
 * Uses the calendar date in the device's configured local time zone.
 *
 * The client rejects dates strictly before this local date. It still allows today because the
 * server remains authoritative for its own time zone, minimum-duration, and expiration policy.
 */
internal object DeviceLocalFileShareDateSource : FileShareDateSource {
    override fun currentDeviceLocalDate(): FileShareCalendarDate = currentDeviceLocalFileShareDate()
}

internal expect fun currentDeviceLocalFileShareDate(): FileShareCalendarDate
