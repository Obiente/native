package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedSegmentedControlInteractionTest {
    @Test
    fun pointerSelectionEmitsExactIdOnlyForEnabledNonselectedOption() {
        val selected = mutableStateOf<String?>("day")
        val selections = mutableListOf<String>()
        nativeSceneTest(800, 220, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(
                    options = views(), selectedId = selected.value,
                    onSelected = { selections += it; selected.value = it }, accessibilityLabel = "Calendar view",
                )
            }
        }) {
            assertTrue(has("Calendar view"))
            assertEquals(Role.Tab, optionNode("Day").config.getOrNull(SemanticsProperties.Role))
            assertTrue(optionNode("Day").config.getOrNull(SemanticsProperties.Selected) == true)
            click("Day")
            click("Unavailable")
            assertTrue(selections.isEmpty())
            click("Month")
            assertEquals(listOf("month"), selections)
            assertTrue(optionNode("Month").config.getOrNull(SemanticsProperties.Selected) == true)
            click("Month")
            assertEquals(listOf("month"), selections)
            capture("segmented-pointer-selection")
        }
    }

    @Test
    fun disablingTheEntireControlBlocksPointerCallbacksAndExposesDisabledState() {
        var selections = 0
        nativeSceneTest(800, 220, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(views(), "day", { selections++ }, enabled = false)
            }
        }) {
            for (label in listOf("Day", "Month", "Agenda", "Unavailable")) {
                assertTrue(optionNode(label).config.contains(SemanticsProperties.Disabled))
                click(label)
            }
            assertEquals(0, selections)
        }
    }

    @Test
    fun arrowsSkipDisabledOptionsWithoutSelectingAndEnterOrSpaceActivatesExplicitly() {
        val selected = mutableStateOf<String?>("day")
        val selections = mutableListOf<String>()
        nativeSceneTest(800, 220, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(views(), selected.value, { selections += it; selected.value = it })
            }
        }) {
            focusOption("Day")
            press(Key.DirectionRight)
            assertFocused("Month")
            assertTrue(selections.isEmpty(), "Arrow navigation must not change the workspace view")
            assertEquals("day", selected.value)
            press(Key.MoveEnd)
            assertFocused("Agenda")
            press(Key.MoveHome)
            assertFocused("Day")
            press(Key.DirectionRight)
            press(Key.Enter)
            assertEquals(listOf("month"), selections)
            press(Key.DirectionRight)
            assertFocused("Agenda")
            press(Key.Spacebar)
            assertEquals(listOf("month", "agenda"), selections)
            press(Key.Spacebar)
            assertEquals(listOf("month", "agenda"), selections)
            capture("segmented-keyboard-focus")
        }
    }

    @Test
    fun rtlArrowsFollowVisualDirectionWithoutChangingSelection() {
        var selections = 0
        nativeSceneTest(800, 220, content = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SegmentedFixture {
                    NextcloudSegmentedControl(views(), "day", { selections++ })
                }
            }
        }) {
            focusOption("Day")
            press(Key.DirectionLeft)
            assertFocused("Month")
            press(Key.DirectionRight)
            assertFocused("Day")
            press(Key.MoveEnd)
            assertFocused("Agenda")
            assertEquals(0, selections)
            capture("segmented-rtl-keyboard-focus")
        }
    }

    @Test
    fun reorderingRetainsSelectedAndFocusedIdsAndRemovalDoesNotPickAnotherOption() {
        val options = mutableStateOf(views())
        var selections = 0
        nativeSceneTest(800, 220, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(options.value, "month", { selections++ })
            }
        }) {
            focusOption("Month")
            options.value = options.value.reversed()
            settle(); settle()
            assertFocused("Month")
            assertTrue(optionNode("Month").config.getOrNull(SemanticsProperties.Selected) == true)
            options.value = options.value.filterNot { it.id == "month" }
            settle(); settle()
            assertFalse(has("Month"))
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.Selected) == true })
            assertEquals(0, selections, "Removing an option must not silently select a different view")
        }
    }

    @Test
    fun unknownAndNullSelectionStayUnselectedUntilExplicitInput() {
        val selected = mutableStateOf<String?>("missing-option")
        val selections = mutableListOf<String>()
        nativeSceneTest(800, 220, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(views(), selected.value, { selections += it })
            }
        }) {
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.Selected) == true })
            selected.value = null
            settle()
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.Selected) == true })
            assertTrue(selections.isEmpty())
            click("Agenda")
            assertEquals(listOf("agenda"), selections)
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.Selected) == true },
                "The callback must not replace the host's selectedId")
        }
    }

    @Test
    fun labelsAndTouchTargetsRemainCompleteAtLargeFontOnPhoneAndDesktop() {
        val options = listOf(
            NextcloudSegmentedOption("month", "Month view"),
            NextcloudSegmentedOption("agenda", "Day schedule"),
            NextcloudSegmentedOption("schedule", "Upcoming events"),
        )
        for ((width, fontScale) in listOf(320 to 1.5f, 1200 to 1f)) {
            nativeSceneTest(width, 240, fontScale = fontScale, content = {
                SegmentedFixture {
                    NextcloudSegmentedControl(options, "month", {})
                }
            }) {
                for (option in options) {
                    focusOption(option.label)
                    assertFullyVisible(option.label, width)
                    val bounds = optionNode(option.label).boundsInRoot
                    assertTrue(bounds.height >= 48f, "${option.label} needs a minimum48dp touch target")
                    assertCompleteLabel(option.label)
                }
                capture("segmented-labels-$width-font-$fontScale")
            }
        }
    }

    @Test
    fun focusedLastOptionScrollsIntoViewAndStillRequiresExplicitActivation() {
        val options = (1..8).map { NextcloudSegmentedOption("section-$it", "Section $it") }
        val selections = mutableListOf<String>()
        nativeSceneTest(320, 220, fontScale = 1.5f, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(options, "section-1", { selections += it })
            }
        }) {
            focusOption("Section 1")
            press(Key.MoveEnd)
            assertFocused("Section 8")
            assertFullyVisible("Section 8", 320)
            assertCompleteLabel("Section 8")
            assertTrue(selections.isEmpty())
            click("Section 8")
            assertEquals(listOf("section-8"), selections)
            capture("segmented-phone-last-option")
        }
    }

    @Test
    fun externallySelectedOptionScrollsIntoViewWithoutEmittingASelection() {
        val options = (1..8).map { NextcloudSegmentedOption("section-$it", "Section $it") }
        val selected = mutableStateOf<String?>("section-1")
        var selections = 0
        nativeSceneTest(320, 220, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(options, selected.value, { selections++ })
            }
        }) {
            selected.value = "section-8"
            settle(); settle(); settle()
            assertFullyVisible("Section 8", 320)
            assertTrue(optionNode("Section 8").config.getOrNull(SemanticsProperties.Selected) == true)
            assertEquals(0, selections)
        }
    }

    @Test
    fun resizingRevealsSelectionAndThenPrioritizesAnIndependentlyFocusedOption() {
        val options = (1..8).map { NextcloudSegmentedOption("section-$it", "Section $it") }
        val width = mutableStateOf(1000)
        val selected = mutableStateOf<String?>("section-8")
        var selections = 0
        nativeSceneTest(1200, 260, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(options, selected.value, { selections++ }, modifier = Modifier.width(width.value.dp))
            }
        }) {
            width.value = 280
            settle(); settle(); settle()
            assertFullyVisible("Section 8", width.value + 8)
            assertCompleteLabel("Section 8")
            capture("segmented-resize-selected-reveal")
            width.value = 1000
            selected.value = "section-1"
            settle(); settle()
            focusOption("Section 8")
            width.value = 280
            settle(); settle(); settle()
            assertFocused("Section 8")
            assertFullyVisible("Section 8", width.value + 8)
            assertCompleteLabel("Section 8")
            assertEquals("section-1", selected.value)
            assertEquals(0, selections, "Resizing and revealing focus must not activate an option")
            capture("segmented-resize-focused-reveal")
        }
    }

    @Test
    fun reorderingRevealsSelectedAndFocusedStableIdsWithoutChangingSelection() {
        val options = mutableStateOf((1..8).map { NextcloudSegmentedOption("section-$it", "Section $it") })
        val selected = mutableStateOf<String?>("section-8")
        var selections = 0
        nativeSceneTest(320, 260, content = {
            SegmentedFixture { NextcloudSegmentedControl(options.value, selected.value, { selections++ }) }
        }) {
            options.value = options.value.reversed()
            settle(); settle(); settle()
            assertFullyVisible("Section 8", 320)
            assertCompleteLabel("Section 8")
            selected.value = "section-1"
            settle(); settle()
            focusOption("Section 8")
            options.value = options.value.reversed()
            settle(); settle(); settle()
            assertFocused("Section 8")
            assertFullyVisible("Section 8", 320)
            assertCompleteLabel("Section 8")
            assertEquals("section-1", selected.value)
            assertEquals(0, selections)
            capture("segmented-reordered-focused-reveal")
        }
    }

    @Test
    fun unboundedParentUsesNaturalWidthAndParentScrollingKeepsEveryOptionReachable() {
        val options = (1..8).map { NextcloudSegmentedOption("section-$it", "Section $it") }
        var measuredWidth = 0
        val selections = mutableListOf<String>()
        nativeSceneTest(320, 220, content = {
            SegmentedFixture {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    NextcloudSegmentedControl(options, "section-1", { selections += it },
                        modifier = Modifier.onGloballyPositioned { measuredWidth = it.size.width })
                }
            }
        }) {
            assertTrue(measuredWidth > 320, "An unbounded parent should receive the control's natural width")
            val scroll = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            })
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(10_000f, 0f))
            settle(); settle()
            assertFullyVisible("Section 8", 320)
            click("Section 8")
            assertEquals(listOf("section-8"), selections)
            capture("segmented-unbounded-parent-last-option")
        }
    }

    @Test
    fun overflowMenuProvidesAnExplicitPointerRouteToHiddenOptions() {
        val selections = mutableListOf<String>()
        val options = (1..8).map { NextcloudSegmentedOption("section-$it", "Section $it") }
        nativeSceneTest(320, 900, fontScale = 1.5f, content = {
            SegmentedFixture {
                NextcloudSegmentedControl(options, "section-1", { selections += it }, accessibilityLabel = "Calendar view")
            }
        }) {
            val existingScrollers = nodes().filter {
                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            }.map { it.id }.toSet()
            click("Show Calendar view")
            settle(); settle()
            val menuScroll = assertNotNull(nodes().lastOrNull {
                it.id !in existingScrollers && it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            }, "The opened menu must expose its own scroll action")
            assertTrue(menuScroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10_000f))
            settle(); settle()
            click("Section 8")
            assertEquals(listOf("section-8"), selections)
        }
    }

    @Test
    fun configurableRoleIsExposedWithoutChangingSelectionBehavior() {
        nativeSceneTest(800, 220, content = {
            SegmentedFixture { NextcloudSegmentedControl(views(), "day", {}, role = Role.RadioButton) }
        }) {
            assertEquals(Role.RadioButton, optionNode("Month").config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Composable
    private fun SegmentedFixture(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(8.dp)) { content() }
        }
    }

    private fun views() = listOf(
        NextcloudSegmentedOption("day", "Day"),
        NextcloudSegmentedOption("disabled", "Unavailable", enabled = false),
        NextcloudSegmentedOption("month", "Month"),
        NextcloudSegmentedOption("agenda", "Agenda"),
    )

    private fun NativeSceneTestDriver.optionNode(label: String): SemanticsNode {
        var candidate = node(label)
        while (candidate != null) {
            if (candidate.config.getOrNull(SemanticsProperties.Selected) != null) return candidate
            candidate = candidate.parent
        }
        error("No selectable native option labelled '$label'")
    }

    private suspend fun NativeSceneTestDriver.focusOption(label: String) {
        val request = assertNotNull(optionNode(label).config.getOrNull(SemanticsActions.RequestFocus)?.action)
        assertTrue(request.invoke(), "The enabled option $label must accept keyboard focus")
        settle(); settle()
    }

    private fun NativeSceneTestDriver.assertFocused(label: String) {
        assertTrue(optionNode(label).config.getOrNull(SemanticsProperties.Focused) == true, "$label must hold focus")
    }

    private fun NativeSceneTestDriver.assertFullyVisible(label: String, width: Int) {
        val bounds = optionNode(label).boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f, "$label must remain visible")
        assertTrue(bounds.left >= -1f && bounds.right <= width + 1f, "$label must be within the viewport: $bounds")
    }

    private fun NativeSceneTestDriver.assertCompleteLabel(label: String) {
        val text = assertNotNull(node(label))
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(assertNotNull(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals(label, layout.layoutInput.text.text)
            assertTrue(text.boundsInRoot.width + 1f >= layout.size.width,
                "$label must not be clipped by the scrolling viewport")
            assertTrue(layout.lineCount > 0)
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line), "$label must not be ellipsized")
                assertTrue(layout.getLineLeft(line) >= -1f, "$label must fit its measured left edge")
                assertTrue(layout.getLineRight(line) <= layout.size.width + 1f, "$label must fit its measured right edge")
            }
            val last = layout.lineCount - 1
            assertEquals(label.length, layout.getLineEnd(last), "$label must retain every character")
            assertTrue(layout.getLineBottom(last) <= layout.size.height + 1f, "$label must fit its measured height")
        }
    }

    @OptIn(InternalComposeUiApi::class)
    private suspend fun NativeSceneTestDriver.press(key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        settle(); settle()
    }
}
