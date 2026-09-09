package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotesEtagTest {
    @Test
    fun opaqueTagsPreserveBackslashesAndEveryAllowedByte() {
        listOf("rev\\1", "ends\\", "", "!#~", "\u0080\u009f\u00ff").forEach { opaque ->
            listOf("", "W/").forEach { prefix ->
                val tag = "$prefix\"$opaque\""
                assertEquals(mapOf("If-Match" to tag), notesMutationHeaders(tag))
            }
        }
        assertEquals(mapOf("If-Match" to "\"rev\\1\""), notesMutationHeaders("rev\\1"))
        (0x21..0xff).filter { it != 0x22 && it != 0x7f }.forEach { code ->
            val tag = "\"rev${code.toChar()}1\""
            assertEquals(mapOf("If-Match" to tag), notesMutationHeaders(tag))
        }
    }

    @Test
    fun malformedAndOversizedTagsCannotBecomeMutationHeaders() {
        listOf("\"rev\"1\"", "\"rev\\\"1\"", "\"rev 1\"", "\"rev\t1\"", "\"rev\r\nX:1\"",
            "\"rev\u00001\"", "\"rev\u007f1\"", "\"rev\u01001\"", "W/bare", "\"missing", "rev 1",
            "\r\n\"rev\"", "\"rev\"\r\n", "\u0000rev",
            "\"${"x".repeat(511)}\"").forEach { tag ->
            assertFailsWith<IllegalArgumentException> { notesMutationHeaders(tag) }
        }
    }
}
