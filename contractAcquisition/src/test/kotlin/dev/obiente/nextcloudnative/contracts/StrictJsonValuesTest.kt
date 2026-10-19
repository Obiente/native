package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONArray
import org.json.JSONObject

class StrictJsonValuesTest {
    private val document = JSONObject()
        .put("string", "3.0.3")
        .put("number", 3.1)
        .put("boolean", true)
        .put("textBoolean", "true")
        .put("object", JSONObject().put("value", "x"))
        .put("array", JSONArray().put("x"))
        .put("null", JSONObject.NULL)

    @Test
    fun `only a JSON string is read as a string`() {
        assertEquals("3.0.3", document.stringValue("string"))
        for (name in listOf("number", "boolean", "object", "array", "null", "absent")) {
            assertEquals(null, document.stringValue(name), name)
        }
    }

    @Test
    fun `only a JSON boolean is read as a boolean, and other values use the stated fallback`() {
        assertEquals(true, document.booleanValue("boolean", absent = false, wrongType = false))
        assertEquals(false, document.booleanValue("absent", absent = false, wrongType = true))
        assertEquals(true, document.booleanValue("absent", absent = true, wrongType = false))
        for (name in listOf("textBoolean", "number", "string", "object", "array", "null")) {
            assertEquals(false, document.booleanValue(name, absent = true, wrongType = false), name)
            assertEquals(true, document.booleanValue(name, absent = false, wrongType = true), name)
        }
    }
}
