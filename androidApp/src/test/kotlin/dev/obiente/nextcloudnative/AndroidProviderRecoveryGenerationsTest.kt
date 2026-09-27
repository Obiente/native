package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidProviderRecoveryGenerationsTest {
    @Test
    fun exactConsumedPermitCarriesTheVerifiedGenerationIntoProviderMutation() {
        val session = dev.obiente.nextcloudnative.app.NextcloudSession("https://cloud.example.test", "alice", "synthetic")
        val id = NextcloudDocumentIds.documentId(session, NextcloudDocumentIncarnation.Legacy, "recovery-file")
        val generations = AndroidProviderRecoveryGenerations()
        withAndroidDocumentsProviderRecoveryPermit(session, id, AndroidDocumentsProviderRecoveryOperation.OpenRead, generations) {
            resolveAndroidDocumentsProviderSession(id, AndroidDocumentsProviderRecoveryOperation.OpenRead, true) { null }
            recordAndroidProviderRecoveryReadGeneration(id, "generation-a")
        }
        withAndroidDocumentsProviderRecoveryPermit(session, id, AndroidDocumentsProviderRecoveryOperation.Delete, generations) {
            resolveAndroidDocumentsProviderSession(id, AndroidDocumentsProviderRecoveryOperation.Delete, true) { null }
            assertEquals("generation-a", androidProviderRecoveryMutationEtag(id, "generation-b", false))
        }
        assertEquals("generation-b", androidProviderRecoveryMutationEtag(id, "generation-b", false))
    }

    @Test
    fun replacementAfterHashCannotChangeTheAuthenticatedMutationPrecondition() {
        for (mutation in listOf(AndroidDocumentsProviderRecoveryOperation.Rename, AndroidDocumentsProviderRecoveryOperation.Delete)) {
            val generations = AndroidProviderRecoveryGenerations()
            var serverEtag = "generation-a"
            generations.run("file", AndroidDocumentsProviderRecoveryOperation.OpenRead) {
                generations.recordReadGeneration("file", serverEtag)
                "authenticated original bytes"
            }
            serverEtag = "generation-b"
            var preserved = true
            generations.run("file", mutation) {
                val condition = generations.mutationEtag("file", isDirectory = false)
                assertEquals("generation-a", condition)
                if (condition == serverEtag) preserved = false
            }
            assertEquals(true, preserved)
            assertFailsWith<IllegalArgumentException> { generations.mutationEtag("file", false) }
        }
    }

    @Test
    fun failedOrCancelledReverificationInvalidatesEarlierProof() {
        for (failure in listOf(IllegalStateException("read failed"), CancellationException("cancelled"))) {
            val generations = AndroidProviderRecoveryGenerations()
            generations.run("file", AndroidDocumentsProviderRecoveryOperation.OpenRead) {
                generations.recordReadGeneration("file", "old")
            }
            assertFailsWith<Exception> {
                generations.run("file", AndroidDocumentsProviderRecoveryOperation.OpenRead) {
                    generations.recordReadGeneration("file", "new")
                    throw failure
                }
            }
            assertFailsWith<IllegalArgumentException> { generations.mutationEtag("file", false) }
        }
    }

    @Test
    fun absentAndCrossDocumentProofNeverAuthorizeMutationOrDirectoryRecovery() {
        val generations = AndroidProviderRecoveryGenerations()
        assertFailsWith<IllegalArgumentException> {
            generations.run("file", AndroidDocumentsProviderRecoveryOperation.OpenRead) { "unbound bytes" }
        }
        generations.run("file", AndroidDocumentsProviderRecoveryOperation.OpenRead) {
            generations.recordReadGeneration("file", "generation-a")
        }
        assertFailsWith<IllegalArgumentException> { generations.mutationEtag("other", false) }
        assertFailsWith<IllegalArgumentException> { generations.mutationEtag("file", true) }
        assertEquals("generation-a", generations.mutationEtag("file", false))
    }
}
