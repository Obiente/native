package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidFileSyncPickerRequestSlotTest {
    @Test fun cancelledChooserCannotBeReplacedBeforeItsResultArrives() {
        val slots = AndroidFileSyncPickerRequestSlot<Any>()
        val cancelled = Any()
        val replacement = Any()
        slots.begin(cancelled)
        // Cancellation affects result delivery, but the platform chooser still owns this slot.
        assertFailsWith<IllegalStateException> { slots.begin(replacement) }
        assertSame(cancelled, slots.complete())
        slots.begin(replacement)
        assertSame(replacement, slots.complete())
        assertNull(slots.complete())
    }

    @Test fun failedLaunchReleasesOnlyItsOwnRequest() {
        val slots = AndroidFileSyncPickerRequestSlot<Any>()
        val failed = Any()
        val replacement = Any()
        slots.begin(failed)
        slots.launchFailed(failed)
        slots.begin(replacement)
        slots.launchFailed(failed)
        assertSame(replacement, slots.complete())
    }
}
