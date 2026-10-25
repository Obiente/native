package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Process death at every file step of a cache operation leaves either the invalidated entry with its
 * marker, or no invalidated entry; a restarted cache never reads an invalidated contract.
 */
class VerifiedContractCrashRecoveryTest {
    private val root: File = Files.createTempDirectory("verified-contract-crash").toFile()
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `death during a store over an invalidated entry never makes it readable again`() {
        // Steps: delete the entry, delete the marker, write the temporary file; then the move.
        for (step in deathPoints(steps = 3)) {
            val directory = File(root, "store-$step").apply { mkdirs() }
            val files = DyingFiles()
            val cache = invalidatedOldEntry(directory, files)
            files.dieAt(step)

            val completed = survives { cache.store(request, contract("new")) }

            val loaded = FileVerifiedContractCache(directory, { NOW }).load(request)?.document
            if (completed) assertEquals("new", loaded, "step $step") else assertNull(loaded, "step $step")
            assertRecoverable(directory)
        }
    }

    @Test
    fun `death during a load of an invalidated entry never makes it readable again`() {
        // Steps: delete the entry, delete the marker.
        for (step in deathPoints(steps = 2)) {
            val directory = File(root, "load-$step").apply { mkdirs() }
            val files = DyingFiles()
            val cache = invalidatedOldEntry(directory, files)
            files.dieAt(step)

            survives { assertNull(cache.load(request)) }

            assertNull(FileVerifiedContractCache(directory, { NOW }).load(request), "step $step")
            assertRecoverable(directory)
        }
    }

    @Test
    fun `death during a store over a valid entry leaves the older or the newer contract, never a partial one`() {
        // Steps: write the temporary file, delete the older entry; then the move.
        for (step in deathPoints(steps = 2)) {
            val directory = File(root, "replace-$step").apply { mkdirs() }
            val files = DyingFiles()
            val cache = FileVerifiedContractCache(directory, { NOW }, files)
            cache.store(request, contract("old"))
            files.dieAt(step)

            val completed = survives { cache.store(request, contract("new")) }

            val loaded = FileVerifiedContractCache(directory, { NOW }).load(request)?.document
            if (completed) assertEquals("new", loaded, "step $step") else assertTrue(loaded in setOf("old", null), "step $step")
            assertRecoverable(directory)
        }
    }

    @Test
    fun `a temporary file left by a dead store is never read as the entry`() {
        val directory = File(root, "leftover").apply { mkdirs() }
        val cache = FileVerifiedContractCache(directory, { NOW })
        cache.store(request, contract("old"))
        val entry = directory.listFiles().orEmpty().single()
        entry.copyTo(File(directory, "${entry.name}.tmp-1"))
        assertTrue(entry.delete())

        assertNull(FileVerifiedContractCache(directory, { NOW }).load(request))
        assertEquals(VerifiedContractStore.Stored, FileVerifiedContractCache(directory, { NOW }).store(request, contract("new")))
        assertEquals("new", FileVerifiedContractCache(directory, { NOW }).load(request)?.document)
    }

    /** Each step index dies before or after that file operation; the final index never dies. */
    private fun deathPoints(steps: Int) = (0 until steps).flatMap { index ->
        listOf(DeathPoint(index, afterOperation = false), DeathPoint(index, afterOperation = true))
    } + DeathPoint(steps, afterOperation = false)

    /** Runs [operation] and returns false when the simulated process died during it. */
    private fun survives(operation: () -> Unit): Boolean = try {
        operation()
        true
    } catch (_: ProcessDeath) {
        false
    }

    /** A restarted cache can always store and read a newer contract afterwards. */
    private fun assertRecoverable(directory: File) {
        val restarted = FileVerifiedContractCache(directory, { NOW })
        assertEquals(VerifiedContractStore.Stored, restarted.store(request, contract("recovered")))
        assertEquals("recovered", FileVerifiedContractCache(directory, { NOW }).load(request)?.document)
    }

    private fun invalidatedOldEntry(directory: File, files: DyingFiles): FileVerifiedContractCache {
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("old"))
        files.entryDeletable = false
        assertEquals(VerifiedContractRemoval.MarkedInvalid, cache.remove(request))
        files.entryDeletable = true
        return cache
    }

    private data class DeathPoint(val index: Int, val afterOperation: Boolean) {
        override fun toString() = "$index ${if (afterOperation) "after" else "before"}"
    }

    /** Not an [Exception], so the cache cannot handle it: the process is gone at this point. */
    private class ProcessDeath : Error("Simulated process death.")

    /** File operations that kill the simulated process at one numbered operation. */
    private class DyingFiles : VerifiedContractFileOperations {
        var entryDeletable = true
        private var death: DeathPoint? = null
        private var operations = 0

        fun dieAt(point: DeathPoint) {
            death = point
            operations = 0
        }

        override fun delete(file: File): Boolean = step {
            (file.name.endsWith(".invalid") || entryDeletable) && file.delete()
        }

        override fun createMarker(file: File): Boolean = step { file.createNewFile() }

        override fun write(file: File, bytes: ByteArray) {
            // A death during the write leaves a partial temporary file.
            step(beforeDeath = { file.writeBytes(bytes.copyOf(1)) }) { file.writeBytes(bytes) }
        }

        private fun <T> step(beforeDeath: () -> Unit = {}, operation: () -> T): T {
            val point = death
            val index = operations++
            if (point == null || point.index != index) return operation()
            if (point.afterOperation) operation() else beforeDeath()
            throw ProcessDeath()
        }
    }

    private fun contract(document: String) = VerifiedOpenApiContract(
        appId = "cospend",
        appVersion = "4.0.2",
        contractVersion = "4.0.2",
        specFile = "openapi.json",
        document = document,
        catalogUrl = "https://fixture.invalid/apps.json",
        packageUrl = "https://fixture.invalid/cospend.tar.gz",
        sourceUrl = "https://fixture.invalid/cospend.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )

    private companion object {
        const val NOW = 50_000_000L
    }
}
