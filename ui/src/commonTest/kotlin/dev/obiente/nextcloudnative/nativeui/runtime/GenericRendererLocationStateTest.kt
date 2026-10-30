package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GenericRendererLocationStateTest {
    @Test
    fun aLocationIsAViewWithTheRecordItWasOpenedFor() {
        assertEquals("view:tasks", genericRendererLocationKey("tasks", null, null))
        val first = genericRendererLocationKey("tasks", "projects", "project-1")
        assertEquals(first, genericRendererLocationKey("tasks", "projects", "project-1"))
        assertNotEquals(first, genericRendererLocationKey("tasks", "projects", "project-2"))
        assertNotEquals(first, genericRendererLocationKey("tasks", "boards", "project-1"))
        assertNotEquals(first, genericRendererLocationKey("notes", "projects", "project-1"))
    }

    @Test
    fun savedKeysNeverContainRecordIds() {
        val key = genericRendererLocationKey("tasks", "projects", "private-record-7")
        assertFalse("private-record-7" in key)
        assertTrue(key.all { it.code in 0x20..0x7e }, "Keys stay printable ASCII for saved instance state")
    }

    @Test
    fun revisitingKeepsALocationAndOnlyTheOldestBeyondCapacityIsDropped() {
        val retained = GenericRendererRetainedLocations(capacity = 3)
        assertEquals(emptyList(), retained.visit("a"))
        assertEquals(emptyList(), retained.visit("b"))
        assertEquals(emptyList(), retained.visit("c"))
        assertEquals(emptyList(), retained.visit("a"), "Returning to a location refreshes it")
        assertEquals(emptyList(), retained.visit("a"), "Recomposing the same location changes nothing")
        assertEquals(listOf("b"), retained.visit("d"), "The least recently visited location is dropped first")
        assertEquals(listOf("c"), retained.visit("e"))
    }
}
