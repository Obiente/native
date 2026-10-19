package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class JsonEquivalenceTest {
    @Test
    fun `key order does not matter`() {
        assertTrue(jsonEquivalent(JSONObject("""{"a":1,"b":"x"}"""), JSONObject("""{"b":"x","a":1}""")))
    }

    @Test
    fun `nested objects and arrays are compared structurally`() {
        val left = JSONObject("""{"releases":[{"version":"1","tags":["a","b"]}],"meta":{"n":{"m":true}}}""")
        val same = JSONObject("""{"meta":{"n":{"m":true}},"releases":[{"tags":["a","b"],"version":"1"}]}""")
        val reordered = JSONObject("""{"meta":{"n":{"m":true}},"releases":[{"tags":["b","a"],"version":"1"}]}""")
        val deeper = JSONObject("""{"meta":{"n":{"m":false}},"releases":[{"tags":["a","b"],"version":"1"}]}""")
        assertTrue(jsonEquivalent(left, same))
        assertFalse(jsonEquivalent(left, reordered), "Array order matters.")
        assertFalse(jsonEquivalent(left, deeper))
        assertFalse(jsonEquivalent(JSONArray("""[1,2]"""), JSONArray("""[1,2,3]""")))
    }

    @Test
    fun `numbers are compared by value`() {
        assertTrue(jsonEquivalent(JSONObject("""{"n":1}"""), JSONObject("""{"n":1.0}""")))
        assertTrue(jsonEquivalent(JSONObject("""{"n":10000000000}"""), JSONObject("""{"n":1e10}""")))
        assertFalse(jsonEquivalent(JSONObject("""{"n":1}"""), JSONObject("""{"n":2}""")))
        assertFalse(jsonEquivalent(JSONObject("""{"n":1}"""), JSONObject("""{"n":"1"}""")), "A string is not a number.")
    }

    @Test
    fun `an explicit null differs from a missing key and from other values`() {
        assertFalse(jsonEquivalent(JSONObject("""{"a":null}"""), JSONObject("""{}""")))
        assertFalse(jsonEquivalent(JSONObject("""{}"""), JSONObject("""{"a":null}""")))
        assertTrue(jsonEquivalent(JSONObject("""{"a":null}"""), JSONObject("""{"a":null}""")))
        assertFalse(jsonEquivalent(JSONObject("""{"a":null}"""), JSONObject("""{"a":false}""")))
        assertFalse(jsonEquivalent(JSONObject("""{"a":{}}"""), JSONObject("""{"a":[]}""")))
    }
}
