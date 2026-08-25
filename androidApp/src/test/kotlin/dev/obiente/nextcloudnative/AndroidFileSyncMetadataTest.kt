package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidFileSyncMetadataTest {
    @Test
    fun platformModificationTimesRemainOptionalAndNormalized() {
        assertNull(knownAndroidFileSyncModifiedEpochMillis(0L))
        assertNull(knownAndroidFileSyncModifiedEpochMillis(-1L))
        assertEquals(1_784_800_800_000L, knownAndroidFileSyncModifiedEpochMillis(1_784_800_800_000L))

        assertEquals(
            1_784_800_800_000L,
            "Thu, 23 Jul 2026 10:00:00 GMT".androidFileSyncModifiedEpochMillis(),
        )
        assertNull("not-a-date".androidFileSyncModifiedEpochMillis())
        assertNull((null as String?).androidFileSyncModifiedEpochMillis())
    }
}
