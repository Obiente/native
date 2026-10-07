package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class NativeScreenLoadGenerationTest {
    private val records = listOf(NativeRecord("row-1", mapOf("amount" to "12")))

    @Test
    fun identicalAuthoritativeLoadsRemainDistinguishable() {
        val first = NativeScreenState.Ready.authoritative(records)
        val refreshed = NativeScreenState.Ready.authoritative(records)
        // The refresh returned the same records, but it is still a new authoritative result.
        assertEquals(first.records, refreshed.records)
        assertNotEquals(first.generation, refreshed.generation)
        assertNotEquals<NativeScreenState>(first, refreshed)
    }

    @Test
    fun cachedAndDerivedStatesKeepValueEquality() {
        assertEquals(0L, NativeScreenState.Ready(records).generation)
        assertEquals<NativeScreenState>(NativeScreenState.Ready(records), NativeScreenState.Ready(records))
    }

    @Test
    fun presentedCollectionStateKeepsTheLoadGeneration() {
        val loaded = NativeScreenState.Ready.authoritative(records)
        val presented = assertIs<NativeScreenState.Ready>(
            nativeDedicatedCollectionState(loaded, records, emptyList(), searchableCollection = true),
        )
        assertEquals(emptyList(), presented.records)
        assertEquals(loaded.generation, presented.generation)
    }
}
