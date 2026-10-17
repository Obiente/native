package dev.obiente.nextcloudnative.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeVideoTransportTest {
    private val playing = NativeVideoTransportState(
        status = NativeVideoTransportStatus.Playing,
        positionMillis = 5_000L,
        durationMillis = 60_000L,
        seekable = true,
    )

    @Test
    fun keysMapToPlayerCommandsAndLeaveArrowsToTheViewer() {
        assertEquals(NativeVideoTransportCommand.TogglePlayback, nativeVideoTransportCommandForKey(Key.Spacebar))
        assertEquals(NativeVideoTransportCommand.TogglePlayback, nativeVideoTransportCommandForKey(Key.K))
        assertEquals(NativeVideoTransportCommand.SeekBy(-10_000L), nativeVideoTransportCommandForKey(Key.J))
        assertEquals(NativeVideoTransportCommand.SeekBy(10_000L), nativeVideoTransportCommandForKey(Key.L))
        assertEquals(NativeVideoTransportCommand.ToggleMute, nativeVideoTransportCommandForKey(Key.M))
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.Escape, Key.Enter)) {
            assertNull(nativeVideoTransportCommandForKey(key), "$key belongs to the media viewer.")
        }
    }

    @Test
    fun seeksClampToTheMediaAndNeedASeekableKnownDuration() {
        assertEquals(15_000L, playing.seekTargetMillis(NativeVideoTransportCommand.SeekBy(10_000L)))
        assertEquals(0L, playing.seekTargetMillis(NativeVideoTransportCommand.SeekBy(-10_000L)))
        assertEquals(60_000L, playing.seekTargetMillis(NativeVideoTransportCommand.SeekTo(90_000L)))
        assertEquals(60_000L, playing.seekTargetMillis(NativeVideoTransportCommand.SeekBy(Long.MAX_VALUE)))
        assertEquals(0L, playing.seekTargetMillis(NativeVideoTransportCommand.SeekBy(Long.MIN_VALUE)))
        assertNull(playing.seekTargetMillis(NativeVideoTransportCommand.ToggleMute))

        assertFalse(playing.copy(seekable = false).canSeek)
        assertNull(playing.copy(seekable = false).seekTargetMillis(NativeVideoTransportCommand.SeekTo(1L)))
        assertNull(playing.copy(durationMillis = null).seekTargetMillis(NativeVideoTransportCommand.SeekTo(1L)))
        assertNull(playing.copy(durationMillis = 0L).seekTargetMillis(NativeVideoTransportCommand.SeekTo(1L)))
        assertTrue(playing.canSeek)
    }

    @Test
    fun timestampsAndStateBoundsAreValidated() {
        assertEquals("0:00", formatVideoTimestamp(-5L))
        assertEquals("0:59", formatVideoTimestamp(59_999L))
        assertEquals("1:01:01", formatVideoTimestamp(3_661_000L))
        assertFailsWith<IllegalArgumentException> { NativeVideoTransportState(positionMillis = -1L) }
        assertFailsWith<IllegalArgumentException> { NativeVideoTransportState(durationMillis = -1L) }
        assertFailsWith<IllegalArgumentException> { NativeVideoTransportState(volumePercent = 101) }
    }
}
