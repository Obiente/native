package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.DurableMutationRecoveryKind
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccountMutationRecoveryCleanupTest {
    @Test
    fun accountCleanupPurgesEveryDurableKindAndOwnedPendingFile() {
        val root = Files.createTempDirectory("android-account-mutation-cleanup-").toFile()
        val outside = Files.createTempFile(
            requireNotNull(root.parentFile).toPath(),
            "retained-mutation-",
            ".json",
        ).toFile()
        try {
            val removed = "a".repeat(64)
            val retained = "b".repeat(64)
            val values = linkedMapOf<String, String>()
            DurableMutationRecoveryKind.entries.forEach { kind ->
                values[androidDurableMutationRecoveryKey(removed, kind)] = "removed-${kind.storageKey}"
                values[androidDurableMutationRecoveryKey(retained, kind)] = "retained-${kind.storageKey}"
            }
            values["unrelated-preference"] = "retained"
            val digest = "1".repeat(64)
            val removedPublished = File(root, "$removed-notes-$digest.json").apply { writeText("removed") }
            val removedStaging = File(root, "$removed-calendar-$digest.json.part").apply { writeText("removed") }
            val retainedPublished = File(root, "$retained-notes-$digest.json").apply { writeText("retained") }
            val malformedLookalike = File(root, "$removed-bad app-$digest.json").apply { writeText("retained") }
            val cleanup = AndroidAccountMutationRecoveryCleanup(recordingPreferences(values), root)

            repeat(2) {
                cleanup.clearDurableRecoveries(removed)
                cleanup.clearPendingDynamicMutations(removed)
            }

            DurableMutationRecoveryKind.entries.forEach { kind ->
                assertFalse(androidDurableMutationRecoveryKey(removed, kind) in values)
                assertEquals("retained-${kind.storageKey}", values[androidDurableMutationRecoveryKey(retained, kind)])
            }
            assertEquals("retained", values["unrelated-preference"])
            assertFalse(removedPublished.exists())
            assertFalse(removedStaging.exists())
            assertTrue(retainedPublished.isFile)
            assertTrue(malformedLookalike.isFile)
            assertTrue(outside.isFile)
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun invalidPendingIdentityCannotEscapeTheMutationDirectory() {
        val root = Files.createTempDirectory("android-account-mutation-confinement-").toFile()
        val outside = Files.createTempFile(
            requireNotNull(root.parentFile).toPath(),
            "outside-mutation-",
            ".json",
        ).toFile()
        try {
            val cleanup = AndroidAccountMutationRecoveryCleanup(recordingPreferences(linkedMapOf()), root)

            assertFailsWith<IllegalArgumentException> {
                cleanup.clearPendingDynamicMutations("../${outside.name}")
            }

            assertTrue(outside.isFile)
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun retryPersistsCleanupAfterFailedCommitRemovedOnlyTheMemoryValues() {
        val removed = "c".repeat(64)
        val diskValues = DurableMutationRecoveryKind.entries.associateTo(linkedMapOf<String, Any>()) { kind ->
            androidDurableMutationRecoveryKey(removed, kind) to "pending-${kind.storageKey}"
        }
        val preferences = failedThenSuccessfulPreferences(diskValues)
        val root = Files.createTempDirectory("android-account-mutation-retry-").toFile()
        try {
            val cleanup = AndroidAccountMutationRecoveryCleanup(preferences.preferences, root)

            assertFailsWith<IllegalStateException> { cleanup.clearDurableRecoveries(removed) }
            assertTrue(DurableMutationRecoveryKind.entries.all { kind ->
                androidDurableMutationRecoveryKey(removed, kind) in preferences.diskValues
            })
            assertTrue(DurableMutationRecoveryKind.entries.all { kind ->
                !preferences.preferences.contains(androidDurableMutationRecoveryKey(removed, kind))
            })

            cleanup.clearDurableRecoveries(removed)

            assertEquals(2, preferences.commitCalls())
            assertTrue(DurableMutationRecoveryKind.entries.all { kind ->
                androidDurableMutationRecoveryKey(removed, kind) !in preferences.diskValues
            })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun recordingPreferences(
        values: MutableMap<String, String>,
    ): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "contains" -> values.containsKey(requireNotNull(arguments)[0] as String)
            "getBoolean" -> arguments?.get(1) as Boolean
            "edit" -> recordingEditor(values)
            else -> error("Unexpected SharedPreferences call: ${method.name}")
        }
    } as SharedPreferences

    private fun recordingEditor(
        values: MutableMap<String, String>,
    ): SharedPreferences.Editor {
        val removals = linkedSetOf<String>()
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "remove" -> proxy.also { removals += requireNotNull(arguments)[0] as String }
                "putBoolean" -> proxy
                "commit" -> true.also { removals.forEach(values::remove) }
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }

    private fun failedThenSuccessfulPreferences(
        initialDiskValues: MutableMap<String, Any>,
    ): RestartFaithfulPreferences {
        val diskValues = initialDiskValues.toMutableMap()
        val memoryValues = initialDiskValues.toMutableMap()
        var commitCalls = 0
        lateinit var preferences: SharedPreferences
        preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "contains" -> requireNotNull(arguments)[0] in memoryValues
                "getBoolean" -> memoryValues[requireNotNull(arguments)[0] as String] as? Boolean ?: arguments[1]
                "edit" -> {
                    val removals = linkedSetOf<String>()
                    val booleans = linkedMapOf<String, Boolean>()
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { proxy, editorMethod, editorArguments ->
                        when (editorMethod.name) {
                            "remove" -> proxy.also {
                                removals += requireNotNull(editorArguments)[0] as String
                            }
                            "putBoolean" -> proxy.also {
                                booleans[requireNotNull(editorArguments)[0] as String] = editorArguments[1] as Boolean
                            }
                            "commit" -> {
                                commitCalls += 1
                                removals.forEach(memoryValues::remove)
                                memoryValues.putAll(booleans)
                                (commitCalls > 1).also { persisted ->
                                    if (persisted) {
                                        removals.forEach(diskValues::remove)
                                        diskValues.putAll(booleans)
                                    }
                                }
                            }
                            else -> error("Unexpected SharedPreferences.Editor call: ${editorMethod.name}")
                        }
                    } as SharedPreferences.Editor
                }
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        return RestartFaithfulPreferences(preferences, diskValues) { commitCalls }
    }

    private data class RestartFaithfulPreferences(
        val preferences: SharedPreferences,
        val diskValues: Map<String, Any>,
        val commitCalls: () -> Int,
    )
}
