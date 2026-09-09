package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericFormField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedChoiceFieldInteractionTest {
    @Test
    @OptIn(InternalComposeUiApi::class)
    fun keyboardFocusIsExplicitAndDisabledFieldCannotOpenOrSelect() {
        val enabled = mutableStateOf(true)
        var callbacks = 0
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", listOf(option("alpha"), option("beta")), "alpha",
                { callbacks++ }, enabled = enabled.value)
        }) {
            val trigger = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Category") == true &&
                    it.config.getOrNull(SemanticsActions.RequestFocus)?.action != null
            })
            assertEquals(Role.Button, trigger.config.getOrNull(SemanticsProperties.Role))
            assertTrue(trigger.config[SemanticsActions.RequestFocus].action!!.invoke())
            settle()
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Category") == true
            })
            capture("shared-choice-keyboard-focused")
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            settle()
            assertTrue(has("Choice beta"))
            enabled.value = false
            settle()
            assertFalse(has("Choice beta"))
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Category") == true &&
                    it.config.contains(SemanticsProperties.Disabled)
            })
            click("Category")
            assertEquals(0, callbacks)
            capture("shared-choice-disabled")
        }
    }

    @Test
    fun reorderingOpenOptionsKeepsStableSelectionAndExactCallback() {
        val options = mutableStateOf(listOf(option("alpha"), option("beta")))
        var selected: String? = null
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", options.value, "beta", { selected = it })
        }) {
            click("Category")
            options.value = options.value.reversed()
            settle()
            val active = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Selected) == true &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choice beta") == true
            }
            assertNotNull(active)
            click("Choice beta")
            assertEquals("beta", selected)
        }
    }

    @Test
    fun searchMatchesAliasesAndClearsAfterChoosingAnExactId() {
        var selected: String? = null
        val options = (1..12).map { NextcloudChoiceOption("wire:$it", "Choice $it", searchTerms = listOf("Alias $it")) }
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", options, null, { selected = it })
        }) {
            click("Category")
            replaceText("", "Alias 12")
            assertTrue(has("Choice 12"))
            assertFalse(has("Choice 2"))
            click("Choice 12")
            assertEquals("wire:12", selected)
            click("Category")
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" })
            capture("shared-choice-search")
        }
    }

    @Test
    fun disablingFieldClosesPopupAndClearsSearchWithoutCallbacks() {
        val enabled = mutableStateOf(true)
        var callbacks = 0
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", (1..12).map { option(it.toString()) }, null,
                { callbacks++ }, enabled = enabled.value)
        }) {
            click("Category")
            replaceText("", "Choice 12")
            enabled.value = false
            settle()
            assertFalse(has("Search category"))
            click("Category")
            assertEquals(0, callbacks)
            enabled.value = true
            settle()
            assertFalse(has("Search category"))
            click("Category")
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" })
        }
    }

    @Test
    fun unmatchedSearchIsExplicitAndQueryLengthRemainsBounded() {
        var callbacks = 0
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", (1..12).map { option(it.toString()) }, null, { callbacks++ })
        }) {
            click("Category")
            replaceText("", "x".repeat(200))
            assertTrue(has("No matching options"))
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "x".repeat(120) })
            click("No matching options")
            assertEquals(0, callbacks)
            replaceText("x".repeat(120), "Choice 12")
            click("Choice 12")
            assertEquals(1, callbacks)
        }
    }

    @Test
    fun disabledOptionCannotEmitSelection() {
        var callbacks = 0
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", listOf(option("alpha"), option("beta").copy(enabled = false)),
                null, { callbacks++ })
        }) {
            click("Category")
            click("Choice beta")
            assertEquals(0, callbacks)
            click("Choice alpha")
            assertEquals(1, callbacks)
        }
    }

    @Test
    fun unknownValueAndEmptyOptionsNeverSelectAFallback() {
        val options = mutableStateOf(listOf(option("alpha")))
        var callbacks = 0
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", options.value, "unknown:7", { callbacks++ },
                selectedLabelFallback = "Custom category")
        }) {
            assertTrue(has("Custom category"))
            click("Category")
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true })
            options.value = emptyList()
            settle()
            assertFalse(has("Choice alpha"))
            assertTrue(has("Custom category"))
            click("Category")
            assertEquals(0, callbacks)
        }
    }

    @Test
    fun emptyUnselectedFieldRemainsExplicitAndDisabled() {
        var callbacks = 0
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", emptyList(), null, { callbacks++ })
        }) {
            assertTrue(has("Choose an option"))
            click("Category")
            assertFalse(has("No matching options"))
            assertEquals(0, callbacks)
        }
    }

    @Test
    fun shortFontScaledPopupStaysInsidePhoneWidthAndScrollsToLastChoice() {
        var selected: String? = null
        nativeSceneTest(320, 320, fontScale = 1.5f, content = {
            NextcloudChoiceField("Category", (1..24).map { option(it.toString()) }, null, { selected = it })
        }) {
            click("Category")
            val search = assertNotNull(node("Search options for Category"))
            assertTrue(search.boundsInRoot.left >= 0f && search.boundsInRoot.right <= 320f)
            val scroll = assertNotNull(nodes().lastOrNull {
                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            })
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f))
            settle()
            val last = assertNotNull(node("Choice 24"))
            assertTrue(last.boundsInRoot.left >= 0f && last.boundsInRoot.right <= 320f)
            assertTrue(last.boundsInRoot.top >= 0f && last.boundsInRoot.bottom <= 320f)
            click("Choice 24")
            assertEquals("24", selected)
            capture("shared-choice-short-fontscaled")
        }
    }

    @Test
    fun dynamicEnumUsesSameFieldWithRequiredLabelErrorSearchAndAutomationIds() {
        var selected: String? = null
        val field = FieldSpec("repeat", "Repeat", FieldKind.enumeration, required = true, readOnly = false,
            enumValues = (1..10).map { "w:$it" }, enumLabels = (1..10).associate { "w:$it" to "Every $it weeks" })
        nativeSceneTest(390, 700, content = {
            GenericFormField(field, "missing_value", "Choose a supported value", true, null,
                automationFieldId = "event.repeat", onValueChange = { selected = it })
        }) {
            assertTrue(has("Repeat *"))
            assertTrue(has("Missing Value"))
            assertTrue(has("Choose a supported value"))
            click("Choose event.repeat")
            replaceText("", "w:10")
            assertTrue(has("Choose event.repeat option Every 10 weeks"))
            click("Choose event.repeat option Every 10 weeks")
            assertEquals("w:10", selected)
        }
    }

    @Test
    fun optionalLeadingPresentationIsSharedByFieldAndOptions() {
        nativeSceneTest(390, 700, content = {
            NextcloudChoiceField("Category", listOf(option("alpha"), option("beta")), "alpha", {},
                leadingContent = { Icon(NextcloudIcons.Calendar, "Icon for ${it.id}") })
        }) {
            assertTrue(has("Icon for alpha"))
            click("Category")
            assertTrue(has("Icon for beta"))
        }
    }

    private fun option(id: String) = NextcloudChoiceOption(id, "Choice $id")
}
