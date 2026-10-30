package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NextcloudMotionTest {
    @Test
    fun contentSpecIsShortAndCalm() {
        assertTrue(NextcloudMotion.MediumMillis in 220..300)
        val spec = assertIs<TweenSpec<*>>(NextcloudMotion.content<Float>(reduced = false))
        assertEquals(NextcloudMotion.MediumMillis, spec.durationMillis)
        assertEquals(NextcloudMotion.ContentEasing, spec.easing)
    }

    @Test
    fun contentEasingStartsQuicklyAndSettlesWithoutOvershoot() {
        val easing = NextcloudMotion.ContentEasing
        assertEquals(0f, easing.transform(0f), 0.001f)
        assertEquals(1f, easing.transform(1f), 0.001f)
        assertTrue(easing.transform(0.25f) > 0.5f, "Content should cover most of its travel early")
        var previous = 0f
        for (step in 1..100) {
            val value = easing.transform(step / 100f)
            assertTrue(value >= previous - 0.0001f && value <= 1.0001f, "Content easing must not overshoot")
            previous = value
        }
    }

    @Test
    fun selectionSpringOvershootsOnceAndSettlesQuickly() {
        val spec = NextcloudMotion.selection<Float>(reduced = false)
        val spring = assertIs<SpringSpec<*>>(spec)
        assertTrue(spring.dampingRatio in 0.5f..0.95f, "Selection should bounce slightly, not oscillate")
        val values = sampleFloatAnimation(spec)
        assertTrue(values.max() > 1.005f, "The highlight should pass its target slightly")
        assertTrue(values.max() < 1.1f, "The overshoot must stay subtle")
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 1f)
        assertTrue(animation.durationNanos < 700_000_000L, "Selection must settle in well under a second")
    }

    @Test
    fun reducedMotionSettlesEverySpecImmediately() {
        assertIs<SnapSpec<*>>(NextcloudMotion.selection<Float>(reduced = true))
        assertIs<SnapSpec<*>>(NextcloudMotion.content<Float>(reduced = true))
        assertIs<SnapSpec<*>>(NextcloudMotion.color<Float>(reduced = true))
        assertIs<SnapSpec<*>>(NextcloudMotion.entrance<Float>(reduced = true))
        assertEquals(EnterTransition.None, NextcloudMotion.sidePaneEnter(reduced = true))
        assertEquals(ExitTransition.None, NextcloudMotion.sidePaneExit(reduced = true))
        assertNotEquals(EnterTransition.None, NextcloudMotion.sidePaneEnter(reduced = false))
        assertNotEquals(ExitTransition.None, NextcloudMotion.sidePaneExit(reduced = false))
    }

    @Test
    fun onlyAZeroAnimatorScaleMeansReducedMotion() {
        assertTrue(nextcloudReducedMotionForAnimatorScale(0f))
        assertTrue(nextcloudReducedMotionForAnimatorScale(-0f))
        assertFalse(nextcloudReducedMotionForAnimatorScale(1f))
        assertFalse(nextcloudReducedMotionForAnimatorScale(0.5f))
        assertFalse(nextcloudReducedMotionForAnimatorScale(10f))
        assertFalse(nextcloudReducedMotionForAnimatorScale(Float.NaN), "An unreadable scale keeps full motion")
    }

    @Test
    fun entranceFrameFadesInAndLandsAtRest() {
        assertEquals(NextcloudDestinationEntranceFrame(alpha = 0f, offsetFraction = 1f), nextcloudDestinationEntranceFrame(0f))
        assertEquals(NextcloudDestinationEntranceFrame(alpha = 1f, offsetFraction = 0f), nextcloudDestinationEntranceFrame(1f))
        assertEquals(nextcloudDestinationEntranceFrame(1f), nextcloudDestinationEntranceFrame(1.4f))
        assertEquals(nextcloudDestinationEntranceFrame(0f), nextcloudDestinationEntranceFrame(-0.2f))
        val faded = nextcloudDestinationEntranceFrame(NextcloudMotion.EntranceFadeFraction)
        assertEquals(1f, faded.alpha, 0.0001f, "The screen is fully readable before the rise ends")
        assertTrue(faded.offsetFraction in 0.001f..0.2f, "The rise is still settling when the fade ends")
        var previous = nextcloudDestinationEntranceFrame(0f)
        for (step in 1..50) {
            val frame = nextcloudDestinationEntranceFrame(step / 50f)
            assertTrue(frame.alpha >= previous.alpha && frame.offsetFraction <= previous.offsetFraction)
            previous = frame
        }
    }

    @Test
    fun entranceRunsOnLinearTimeAndFadesFasterThanItRises() {
        val spec = assertIs<TweenSpec<*>>(NextcloudMotion.entrance<Float>(reduced = false))
        assertEquals(NextcloudMotion.EntranceMillis, spec.durationMillis)
        assertTrue(NextcloudMotion.EntranceMillis in 360..520, "Long enough to read as smooth, short enough for frequent switching")
        val fadeMillis = NextcloudMotion.EntranceMillis * NextcloudMotion.EntranceFadeFraction
        assertTrue(fadeMillis in 200f..300f, "The new screen becomes readable quickly")
        assertIs<SnapSpec<*>>(NextcloudMotion.entrance<Float>(reduced = true))
    }

    @Test
    fun destinationKeyFollowsTheOpenAppAndIgnoresAliases() {
        assertEquals(
            NextcloudShellDestinationKey.Root(NextcloudDestination.Home),
            nextcloudShellDestinationKey(NextcloudDestination.Home, activeAppId = null),
        )
        assertEquals(
            NextcloudShellDestinationKey.App("calendar"),
            nextcloudShellDestinationKey(NextcloudDestination.Apps, activeAppId = "calendar"),
        )
        assertEquals(
            nextcloudShellDestinationKey(NextcloudDestination.Apps, activeAppId = "talk"),
            nextcloudShellDestinationKey(NextcloudDestination.Home, activeAppId = "spreed"),
            "An app alias is the same workspace and must not replay the entrance",
        )
        assertNotEquals(
            nextcloudShellDestinationKey(NextcloudDestination.Home, activeAppId = null),
            nextcloudShellDestinationKey(NextcloudDestination.Settings, activeAppId = null),
        )
    }

    private fun sampleFloatAnimation(spec: AnimationSpec<Float>): List<Float> {
        val animation = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 1f)
        val stepNanos = 4_000_000L
        return (0..(animation.durationNanos / stepNanos)).map { animation.getValueFromNanos(it * stepNanos) }
    }
}
