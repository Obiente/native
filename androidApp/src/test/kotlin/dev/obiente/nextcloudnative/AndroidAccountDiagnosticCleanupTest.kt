package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccountDiagnosticCleanupTest {
    @Test fun rotatedScopesSurviveFailedCleanupAndRestartUntilBothSinksArePurged(): Unit = runBlocking {
        val root = Files.createTempDirectory("diagnostic-scope-aliases-").toFile()
        try {
            val original = NextcloudSession("https://CLOUD.example.test:443", "reader", "old")
            val replacement = original.copy(serverUrl = "https://cloud.example.test", appPassword = "new")
            val other = original.copy(loginName = "other")
            val incarnation = NextcloudDocumentIncarnation.Legacy
            fun aliases() = AndroidDocumentLegacyAliases(
                { key -> File(root, key).takeIf(File::isFile)?.readText() },
                { key, value -> File(root, key).writeText(value); true },
            )
            aliases().remember(replacement, original, incarnation)
            aliases().remember(other, null, incarnation)
            val owned = setOf(original, replacement).mapTo(linkedSetOf(), NextcloudDocumentIds::accountKey)
            val otherScope = NextcloudDocumentIds.accountKey(other)
            val primary = (owned + otherScope).associateWith { "synthetic private path" }.toMutableMap()
            val shared = primary.toMutableMap()
            var retired = false
            assertFailsWith<IllegalStateException> {
                retireAndroidDiagnosticScopes(owned, { primary.remove(it) }, { error("synthetic sink failure") }) {
                    retired = true
                    aliases().clear(replacement.accountId.storageKey)
                }
            }
            assertFalse(retired)
            val restored = aliases().read(replacement.accountId.storageKey, incarnation)
            assertEquals(owned, restored)
            retireAndroidDiagnosticScopes(restored, { primary.remove(it) }, { shared.remove(it) }) {
                retired = true
                aliases().clear(replacement.accountId.storageKey)
            }
            assertTrue(retired)
            assertEquals(setOf(otherScope), primary.keys)
            assertEquals(setOf(otherScope), shared.keys)
            assertTrue(aliases().read(replacement.accountId.storageKey, incarnation).isEmpty())
            assertEquals(setOf(otherScope), aliases().read(other.accountId.storageKey, incarnation))
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationDoesNotRetireScopeProvenance(): Unit = runBlocking {
        var retired = false
        assertFailsWith<CancellationException> {
            retireAndroidDiagnosticScopes(setOf("a".repeat(32)), { throw CancellationException("synthetic") }, {}) { retired = true }
        }
        assertFalse(retired)
    }
}
