package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json

class DynamicStructuredFormTest {
    @Test
    fun `repeatable nullable fields preserve explicit null distinctly from absence`() {
        val spec = RepeatableObjectInputSpec(
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

        assertEquals(
            """[{"note":null}]""",
            spec.encode(
                listOf(RepeatableObjectInputRow(nullFieldIds = setOf("note"))),
            ),
        )
        assertEquals(
            """[{}]""",
            spec.encode(listOf(RepeatableObjectInputRow())),
        )
        assertEquals(
            """[{"note":null}]""",
            spec.canonicalJson("""[{"note":null}]""").toString(),
        )
    }

    @Test
    fun `repeatable decimal values preserve exact JSON precision and exact bounds`() {
        val unbounded = decimalSpec()

        assertEquals(
            """[{"amount":9007199254740993}]""",
            unbounded.encode(
                listOf(RepeatableObjectInputRow(mapOf("amount" to "9007199254740993"))),
            ),
        )
        assertEquals(
            """[{"amount":1.234567890123456789e-40}]""",
            unbounded.encode(
                listOf(RepeatableObjectInputRow(mapOf("amount" to "1.234567890123456789e-40"))),
            ),
        )
        assertEquals(
            """[{"amount":9007199254740993}]""",
            unbounded.canonicalJson("""[{"amount":9007199254740993}]""").toString(),
        )

        val bounded = decimalSpec(
            minimum = "9007199254740992.999999999999999999",
            maximum = "9007199254740993.000000000000000001",
        )
        assertEquals(
            """[{"amount":9007199254740993}]""",
            bounded.encode(
                listOf(RepeatableObjectInputRow(mapOf("amount" to "9007199254740993"))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            bounded.encode(
                listOf(
                    RepeatableObjectInputRow(
                        mapOf("amount" to "9007199254740992.999999999999999998"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bounded.encode(
                listOf(
                    RepeatableObjectInputRow(
                        mapOf("amount" to "9007199254740993.000000000000000002"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `repeatable decimal schema retains exact contract bounds`() {
        val spec = assertNotNull(
            Json.parseToJsonElement(
                """
                {
                  "type":"array",
                  "format":"$DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT",
                  "minItems":1,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["amount"],
                    "properties":{
                      "amount":{
                        "type":"number",
                        "minimum":9007199254740992.999999999999999999,
                        "maximum":9007199254740993.000000000000000001
                      }
                    }
                  }
                }
                """.trimIndent(),
            ).repeatableObjectInputSpec(),
        )

        assertEquals(
            """[{"amount":9007199254740993}]""",
            spec.encode(
                listOf(RepeatableObjectInputRow(mapOf("amount" to "9007199254740993"))),
            ),
        )
    }

    @Test
    fun `repeatable enumeration preserves audited labels separately from wire values`() {
        val spec = assertNotNull(
            Json.parseToJsonElement(
                """
                {
                  "type":"array",
                  "format":"$DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT",
                  "minItems":1,
                  "maxItems":1,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["repeat"],
                    "properties":{
                      "repeat":{
                        "type":"string",
                        "enum":["s:1:-","d:1"],
                        "$ENUM_LABELS_EXTENSION":{
                          "s:1:-":"Does not repeat",
                          "d:1":"Every day"
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ).repeatableObjectInputSpec(),
        )
        val repeat = spec.fields.single()

        assertEquals(listOf("s:1:-", "d:1"), repeat.enumValues)
        assertEquals(
            mapOf("s:1:-" to "Does not repeat", "d:1" to "Every day"),
            repeat.enumLabels,
        )
        assertEquals(
            """[{"repeat":"d:1"}]""",
            spec.encode(listOf(RepeatableObjectInputRow(mapOf("repeat" to "d:1")))),
        )
    }

    @Test
    fun `repeatable schema normalizes proven read only response members`() {
        val spec = assertNotNull(
            Json.parseToJsonElement(
                """
                {
                  "type":"array",
                  "format":"$DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT",
                  "minItems":1,
                  "maxItems":1,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["serverId","name"],
                    "properties":{
                      "serverId":{"type":"integer","readOnly":true},
                      "name":{"type":"string"}
                    }
                  }
                }
                """.trimIndent(),
            ).repeatableObjectInputSpec(),
        )

        assertEquals(listOf("name"), spec.fields.map(RepeatableObjectInputFieldSpec::id))
        assertEquals(setOf("serverId"), spec.observedReadOnlyFieldIds)
        assertEquals(
            """[{"name":"Milk"}]""",
            spec.canonicalJson("""[{"serverId":42,"name":"Milk"}]""").toString(),
        )
        assertFailsWith<IllegalStateException> {
            spec.canonicalJson("""[{"unknown":42,"name":"Milk"}]""")
        }
    }

    private fun decimalSpec(
        minimum: String? = null,
        maximum: String? = null,
    ): RepeatableObjectInputSpec = RepeatableObjectInputSpec(
        minimumItems = 1,
        maximumItems = 2,
        fields = listOf(
            RepeatableObjectInputFieldSpec(
                id = "amount",
                label = "Amount",
                kind = RepeatableObjectInputScalarKind.Decimal,
                required = true,
                minimum = minimum,
                maximum = maximum,
            ),
        ),
    )
}
