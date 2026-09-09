package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRepeatableObjectUiStateTest {
    private val spec = RepeatableObjectInputSpec(
        minimumItems = 1,
        maximumItems = 2,
        fields = listOf(
            RepeatableObjectInputFieldSpec(
                id = "label",
                label = "Label",
                kind = RepeatableObjectInputScalarKind.String,
                required = true,
            ),
            RepeatableObjectInputFieldSpec(
                id = "enabled",
                label = "Enabled",
                kind = RepeatableObjectInputScalarKind.Boolean,
                required = true,
            ),
        ),
    )
    private val field = FieldSpec(
        id = "entries",
        label = "Entries",
        kind = FieldKind.objectValue,
        required = true,
        readOnly = false,
        repeatableObjectInput = spec,
    )

    @Test
    fun `initial rows satisfy the minimum and initialize required booleans`() {
        assertEquals(
            mapOf(
                "entries" to listOf(
                    RepeatableObjectInputRow(mapOf("enabled" to "false")),
                ),
            ),
            initialNativeRepeatableObjectDraft(listOf(field), emptyMap()),
        )
    }

    @Test
    fun `create draft treats a blank scalar placeholder as an absent structured value`() {
        assertEquals(
            mapOf(
                "entries" to listOf(
                    RepeatableObjectInputRow(mapOf("enabled" to "false")),
                ),
            ),
            initialNativeCreateRepeatableObjectDraft(
                fields = listOf(field),
                initialValues = mapOf("entries" to ""),
            ),
        )
        assertNull(
            initialNativeRepeatableObjectDraft(
                fields = listOf(field),
                initialValues = mapOf("entries" to ""),
            ),
        )
    }

    @Test
    fun `add and remove remain inside schema bounds`() {
        val initial = listOf(RepeatableObjectInputRow())
        val two = addNativeRepeatableObjectRow(initial, spec)

        assertEquals(2, two.size)
        assertEquals(two, addNativeRepeatableObjectRow(two, spec))
        assertEquals(initial, removeNativeRepeatableObjectRow(two, 1, spec))
        assertEquals(initial, removeNativeRepeatableObjectRow(initial, 0, spec))
    }

    @Test
    fun `row updates preserve identities and remove blank scalar values`() {
        val initial = listOf(RepeatableObjectInputRow(mapOf("enabled" to "false")))
        val withLabel = updateNativeRepeatableObjectValue(
            rows = initial,
            rowIndex = 0,
            field = spec.fields.first(),
            value = "Milk",
        )

        assertEquals(
            mapOf("enabled" to "false", "label" to "Milk"),
            withLabel.single().values,
        )
        assertEquals(
            mapOf("enabled" to "false"),
            updateNativeRepeatableObjectValue(
                rows = withLabel,
                rowIndex = 0,
                field = spec.fields.first(),
                value = "",
            ).single().values,
        )
    }

    @Test
    fun `structured drafts round trip without exposing raw JSON state`() {
        val values = mapOf(
            "entries" to listOf(
                RepeatableObjectInputRow(
                    mapOf("label" to "Milk", "enabled" to "true"),
                ),
            ),
        )
        val specs = mapOf("entries" to spec)
        val saved = encodeNativeRepeatableObjectDraft(values, specs)

        assertEquals(values, saved?.let { decodeNativeRepeatableObjectDraft(it, specs) })
        assertNull(decodeNativeRepeatableObjectDraft(listOf("entries", "not-json"), specs))
    }

    @Test
    fun `nullable structured value preserves and edits explicit null state`() {
        val nullableSpec = RepeatableObjectInputSpec(
            minimumItems = 1,
            maximumItems = 1,
            fields = listOf(
                RepeatableObjectInputFieldSpec(
                    id = "note",
                    label = "Note",
                    kind = RepeatableObjectInputScalarKind.String,
                    required = false,
                    nullable = true,
                ),
            ),
        )
        val nullableField = field.copy(repeatableObjectInput = nullableSpec)
        val initial = initialNativeRepeatableObjectDraft(
            fields = listOf(nullableField),
            initialValues = mapOf("entries" to """[{"note":null}]"""),
        )
        val rows = initial?.get("entries").orEmpty()

        assertEquals(setOf("note"), rows.single().nullFieldIds)
        assertTrue(rows.single().values.isEmpty())

        val specs = mapOf("entries" to nullableSpec)
        assertEquals(
            initial,
            encodeNativeRepeatableObjectDraft(initial.orEmpty(), specs)
                ?.let { decodeNativeRepeatableObjectDraft(it, specs) },
        )

        val withoutNull = updateNativeRepeatableObjectNull(
            rows = rows,
            rowIndex = 0,
            field = nullableSpec.fields.single(),
            explicitNull = false,
        )
        assertTrue(withoutNull.single().nullFieldIds.isEmpty())

        val typed = updateNativeRepeatableObjectValue(
            rows = rows,
            rowIndex = 0,
            field = nullableSpec.fields.single(),
            value = "Keep this",
        )
        assertEquals(mapOf("note" to "Keep this"), typed.single().values)
        assertTrue(typed.single().nullFieldIds.isEmpty())
    }

    @Test
    fun `initial rows discard only contract proven read only members`() {
        val writableSpec = spec.copy(observedReadOnlyFieldIds = setOf("serverId"))
        val writableField = field.copy(repeatableObjectInput = writableSpec)

        assertEquals(
            listOf(
                RepeatableObjectInputRow(
                    mapOf("label" to "Milk", "enabled" to "true"),
                ),
            ),
            initialNativeRepeatableObjectDraft(
                fields = listOf(writableField),
                initialValues = mapOf(
                    "entries" to """[{"serverId":"42","label":"Milk","enabled":true}]""",
                ),
            )?.get("entries"),
        )
        assertNull(
            initialNativeRepeatableObjectDraft(
                fields = listOf(writableField),
                initialValues = mapOf(
                    "entries" to """[{"unknown":"unsafe","label":"Milk","enabled":true}]""",
                ),
            ),
        )
    }

    @Test
    fun `structured draft saver preserves incomplete required rows`() {
        val values = mapOf(
            "entries" to listOf(
                RepeatableObjectInputRow(mapOf("enabled" to "false")),
            ),
        )
        val specs = mapOf("entries" to spec)

        val saved = encodeNativeRepeatableObjectDraft(values, specs)

        assertEquals(values, saved?.let { decodeNativeRepeatableObjectDraft(it, specs) })
    }

    @Test
    fun `submit encoding returns field validation instead of throwing`() {
        val invalid = encodeNativeRepeatableObjectSubmitValues(
            values = mapOf(
                "entries" to listOf(RepeatableObjectInputRow(mapOf("enabled" to "false"))),
            ),
            specs = mapOf("entries" to spec),
        )
        val failure = assertIs<NativeRepeatableObjectSubmitEncoding.Invalid>(invalid)
        assertTrue(failure.fieldErrors.getValue("entries").contains("Label is required"))

        val valid = encodeNativeRepeatableObjectSubmitValues(
            values = mapOf(
                "entries" to listOf(
                    RepeatableObjectInputRow(mapOf("label" to "Milk", "enabled" to "true")),
                ),
            ),
            specs = mapOf("entries" to spec),
        )
        assertEquals(
            """[{"label":"Milk","enabled":true}]""",
            assertIs<NativeRepeatableObjectSubmitEncoding.Ready>(valid).values.getValue("entries"),
        )
    }

    @Test
    fun `structured draft saver does not apply scalar submission validation`() {
        val numberSpec = RepeatableObjectInputSpec(
            minimumItems = 1,
            maximumItems = 1,
            fields = listOf(
                RepeatableObjectInputFieldSpec(
                    id = "quantity",
                    label = "Quantity",
                    kind = RepeatableObjectInputScalarKind.Integer,
                    required = true,
                    minimum = "1",
                ),
            ),
        )
        val values = mapOf(
            "entries" to listOf(
                RepeatableObjectInputRow(mapOf("quantity" to "-")),
            ),
        )
        val specs = mapOf("entries" to numberSpec)

        val saved = encodeNativeRepeatableObjectDraft(values, specs)

        assertEquals(values, saved?.let { decodeNativeRepeatableObjectDraft(it, specs) })
    }

    @Test
    fun `structured draft restore rejects undeclared fields and non-string values`() {
        val specs = mapOf("entries" to spec)

        assertNull(
            decodeNativeRepeatableObjectDraft(
                listOf("entries", """[{"unknown":"value"}]"""),
                specs,
            ),
        )
        assertNull(
            decodeNativeRepeatableObjectDraft(
                listOf("entries", """[{"enabled":false}]"""),
                specs,
            ),
        )
    }

    @Test
    fun `structured draft saver rejects oversized forms instead of filling the activity bundle`() {
        val largeSpec = RepeatableObjectInputSpec(
            minimumItems = 0,
            maximumItems = 32,
            fields = listOf(
                RepeatableObjectInputFieldSpec(
                    id = "content",
                    label = "Content",
                    kind = RepeatableObjectInputScalarKind.String,
                    required = false,
                ),
            ),
        )
        val values = mapOf(
            "entries" to List(17) {
                RepeatableObjectInputRow(mapOf("content" to "x".repeat(4_096)))
            },
        )
        val specs = mapOf("entries" to largeSpec)

        val saved = encodeNativeRepeatableObjectDraft(values, specs)

        assertNull(saved)
    }

    @Test
    fun `invalid initial structured value fails closed`() {
        assertNull(
            initialNativeRepeatableObjectDraft(
                fields = listOf(field),
                initialValues = mapOf("entries" to """[{"unknown":"value"}]"""),
            ),
        )
    }
}
