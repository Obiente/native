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
