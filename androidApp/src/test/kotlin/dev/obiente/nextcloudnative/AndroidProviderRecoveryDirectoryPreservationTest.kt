package dev.obiente.nextcloudnative

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidProviderRecoveryDirectoryPreservationTest {
    @Test
    fun directoryProofReturnsFromIoToTheExactConsumedProviderPermit() {
        val session = dev.obiente.nextcloudnative.app.NextcloudSession("https://cloud.example.test", "alice", "synthetic")
        val id = NextcloudDocumentIds.documentId(session, NextcloudDocumentIncarnation.Legacy, "backup")
        val generations = AndroidProviderRecoveryGenerations()
        val caller = Thread.currentThread()
        generations.verifyDirectory(id) {
            withAndroidDocumentsProviderRecoveryPermit(session, id, AndroidDocumentsProviderRecoveryOperation.QueryChildren, generations) {
                resolveAndroidDocumentsProviderSession(id, AndroidDocumentsProviderRecoveryOperation.QueryChildren, true) { null }
                loadAndroidProviderDirectoryListing(id) {
                    assertTrue(Thread.currentThread() !== caller)
                    AndroidProviderDirectoryListing(emptyList(), "directory-a")
                }
            }
        }
        assertEquals("directory-a", generations.mutationEtag(id, true))
    }

    @Test
    fun verifiedDirectoryMovePreservesChildrenChangedOrAddedAfterTheHash() {
        val generations = AndroidProviderRecoveryGenerations()
        val children = mutableMapOf("original" to "verified bytes")
        val directories = mutableMapOf("parent/backup" to children)
        generations.verifyDirectory("directory") {
            generations.recordDirectoryGeneration("directory", "directory-a")
            generations.run("child", AndroidDocumentsProviderRecoveryOperation.OpenRead) {
                generations.recordReadGeneration("child", "child-a")
            }
        }
        children["original"] = "concurrent edit"
        children["new child"] = "new bytes"
        val destination = preserveAndroidRecoveryDirectory("parent/backup",
            generations.mutationEtag("directory", true), { "unique" }) { target, etag ->
            assertEquals("directory-a", etag)
            check(target !in directories)
            directories[target] = directories.remove("parent/backup")!!
        }
        assertEquals<Map<String, String>?>(mapOf("original" to "concurrent edit", "new child" to "new bytes"), directories[destination])
        assertTrue(destination.startsWith("parent/Recovered folder - "))
        assertEquals(1, directories.size)
    }

    @Test
    fun collisionAndFailureDoNotOverwriteOrLoseTheDirectoryAndAmbiguousMoveKeepsItsContents() {
        for (failure in listOf("collision", "before", "after")) {
            val source = "parent/backup"
            val destination = "parent/Recovered folder - unique"
            val directories = mutableMapOf(source to "original descendants")
            if (failure == "collision") directories[destination] = "unrelated descendants"
            assertFailsWith<IOException> {
                preserveAndroidRecoveryDirectory(source, "directory-a", { "unique" }) { target, _ ->
                    if (target in directories || failure == "before") throw IOException("move rejected")
                    directories[target] = directories.remove(source)!!
                    throw IOException("move response lost")
                }
            }
            if (failure == "after") assertEquals("original descendants", directories[destination])
            else assertEquals("original descendants", directories[source])
            if (failure == "collision") assertEquals("unrelated descendants", directories[destination])
        }
    }

    @Test
    fun interruptedDirectoryVerificationCannotAuthorizeAnyMutation() {
        val generations = AndroidProviderRecoveryGenerations()
        assertFailsWith<IOException> {
            generations.verifyDirectory("directory") {
                generations.recordDirectoryGeneration("directory", "directory-a")
                throw IOException("child read failed")
            }
        }
        assertFailsWith<IllegalArgumentException> { generations.mutationEtag("directory", true) }
        assertFailsWith<IllegalArgumentException> {
            generations.verifyDirectory("directory") {
                generations.recordDirectoryGeneration("directory", "directory-a")
                generations.recordDirectoryGeneration("directory", "directory-b")
            }
        }
        assertFailsWith<IllegalArgumentException> { generations.mutationEtag("directory", true) }
    }
}
