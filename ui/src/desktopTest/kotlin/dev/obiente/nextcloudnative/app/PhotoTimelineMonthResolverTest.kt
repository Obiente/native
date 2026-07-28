package dev.obiente.nextcloudnative.app

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoTimelineMonthResolverTest {
    @Test
    fun platformResolverUsesTheLocalDstOffsetAtThePhotoInstant() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))
            val resolver = platformLocalPhotoTimelineMonthResolver()
            val lateUtcMarch = requireNotNull(
                parseDavMediaSearchTimestamp("Sun, 31 Mar 2024 23:30:00 GMT"),
            )

            assertEquals(
                PhotoTimelineMonth(2024, 4),
                resolver.resolve(lateUtcMarch),
            )
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
