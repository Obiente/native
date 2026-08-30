package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.saveable.SaverScope
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRepeatableObjectDraftStateTest {
    private val spec = RepeatableObjectInputSpec(0, 32, listOf(
        RepeatableObjectInputFieldSpec("text", "Text", RepeatableObjectInputScalarKind.String, false),
    ))
    private val specs = mapOf("first" to spec, "second" to spec)
    private fun rows(count: Int, text: String = "x".repeat(4_096)) =
        List(count) { RepeatableObjectInputRow(mapOf("text" to text)) }
    private val initial = mapOf("first" to rows(1, "original"), "second" to emptyList())

    @Test
    fun `rejected edits retain previous values and the real saver restores them`() {
        val state = NativeRepeatableObjectDraftState(initial, specs)
        state.update("first", rows(2))
        val accepted = state.values
        state.update("second", rows(2))
        assertEquals(accepted, state.values)
        assertNotNull(state.error)
        val saver = nativeRepeatableObjectDraftStateSaver(specs)
        val saved = with(saver) { assertNotNull(SaverScope { true }.save(state)) }
        assertTrue(saved.sumOf(String::length) <= 16 * 1_024)
        val restored = assertNotNull(saver.restore(saved))
        assertEquals(accepted, restored.values)
        assertTrue(restored.editable)
        restored.update("second", rows(1, "smaller"))
        assertNull(restored.error)
        assertEquals(rows(1, "smaller"), restored.values["second"])
    }

    @Test
    fun `oversized initial values remain read only until explicitly replaced`() {
        val source = mapOf("first" to rows(5), "second" to emptyList())
        val state = NativeRepeatableObjectDraftState(source, specs)
        assertFalse(state.editable)
        assertNotNull(state.error)
        assertTrue(state.values.isEmpty())
        state.update("first", rows(1))
        assertFalse(state.editable)
        assertEquals(5, source.getValue("first").size)
        state.replace(initial)
        assertTrue(state.editable)
        assertEquals(initial, state.values)
        assertNull(state.error)
    }

    @Test
    fun `escaped text and all fields share the same encoded budget`() {
        val state = NativeRepeatableObjectDraftState(initial, specs)
        state.update("first", rows(1, "\u0001".repeat(4_096)))
        assertEquals(initial, state.values)
        assertNotNull(state.error)
        state.update("unknown", emptyList())
        assertEquals(initial, state.values)
    }

    @Test
    fun `oversized scalar paste reaches the draft guard and reports rejection`() {
        val state = NativeRepeatableObjectDraftState(initial, specs)
        val proposed = updateNativeRepeatableObjectValue(
            initial.getValue("first"), 0, spec.fields.single(), "x".repeat(4_097),
        )
        state.update("first", proposed)
        assertEquals(initial, state.values)
        assertNotNull(state.error)
    }

    @Test
    fun `restore rejects old oversized bundles and a changed schema`() {
        val saver = nativeRepeatableObjectDraftStateSaver(specs)
        assertNull(saver.restore(listOf("first", "x".repeat(65_536), "second", "[]")))
        val saved = assertNotNull(encodeNativeRepeatableObjectDraft(initial, specs))
        assertNull(nativeRepeatableObjectDraftStateSaver(mapOf("other" to spec)).restore(saved))
    }
}
