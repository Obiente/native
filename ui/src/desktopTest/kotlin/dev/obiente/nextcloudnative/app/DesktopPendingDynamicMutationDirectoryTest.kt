package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.CachedDynamicApiResponse
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPendingDynamicMutationDirectoryTest {
    @Test
    fun `pending mutations use durable platform state roots`() {
        val home = File("/test-home")

        assertEquals(
            File("/state/nextcloud-native/pending-mutations-v1").absoluteFile,
            desktopPendingDynamicMutationDirectory(
                osName = "Linux",
                environment = mapOf("XDG_STATE_HOME" to "/state", "XDG_CACHE_HOME" to "/cache"),
                userHome = home,
            ),
        )
        assertEquals(
            File("/windows-local/Nextcloud Native/State/Pending Mutations").absoluteFile,
            desktopPendingDynamicMutationDirectory(
                osName = "Windows 11",
                environment = mapOf("LOCALAPPDATA" to "/windows-local"),
                userHome = home,
            ),
        )
        assertEquals(
            File("/test-home/Library/Application Support/Nextcloud Native/Pending Mutations").absoluteFile,
            desktopPendingDynamicMutationDirectory(
                osName = "Mac OS X",
                environment = emptyMap(),
                userHome = home,
            ),
        )
    }

    @Test
    fun `pending mutation payload and directory are owner only on posix stores`() {
        val root = createTempDirectory("pending-mutation-permissions-").toFile()
        try {
            if (!Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) return
            val directory = File(root, "nested/pending")
            val target = File(directory, "marker.json")
            val payload = "private chore payload".encodeToByteArray()

            writePrivatePendingMutationFile(directory, target, payload)

            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(directory.toPath()),
            )
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target.toPath()),
            )
            assertContentEquals(payload, target.readBytes())
            assertEquals(emptyList(), directory.listFiles().orEmpty().filter { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `account cleanup removes only path confined pending mutation entries`() {
        val directory = createTempDirectory("pending-mutation-cleanup-").toFile()
        val accountId = "a".repeat(64)
        val otherAccountId = "b".repeat(64)
        try {
            ensurePrivatePendingMutationDirectory(directory)
            val owned = directory.resolve("$accountId-deck-${"1".repeat(64)}.json")
            val ownedTemporary = directory.resolve("${owned.name}-retry.part")
            val retained = directory.resolve("$otherAccountId-deck-${"2".repeat(64)}.json")
            listOf(owned, ownedTemporary, retained).forEach { file ->
                file.writeText("private")
                setPrivatePendingMutationFilePermissions(file)
            }

            removeDesktopPendingDynamicMutations(directory, accountId)

            assertFalse(owned.exists())
            assertFalse(ownedTemporary.exists())
            assertTrue(retained.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `account cleanup fails closed on an unrecognized owned entry`() {
        val directory = createTempDirectory("pending-mutation-cleanup-unsafe-").toFile()
        val accountId = "c".repeat(64)
        try {
            ensurePrivatePendingMutationDirectory(directory)
            val unsafe = directory.resolve("$accountId-unknown")
            unsafe.writeText("private")
            setPrivatePendingMutationFilePermissions(unsafe)

            assertFailsWith<IllegalStateException> {
                removeDesktopPendingDynamicMutations(directory, accountId)
            }
            assertTrue(unsafe.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `desktop account cleanup fences a late GET before deleting its cache`() = runBlocking {
        supervisorScope {
            val root = createTempDirectory("desktop-dynamic-cache-cleanup-").toFile()
            try {
                val accountId = "d".repeat(64)
                val requestIdentity = "GET /dashboard/widgets"
                val cache = DynamicApiResponseCache(root)
                val coalescer = DynamicApiRequestCoalescer<CachedDynamicApiResponse>()
                val memoryCache = DynamicNativeMemoryCache()
                val session = NextcloudSession("https://cloud.example.test", "alice", "password")
                val screenKey = dynamicScreenCacheKey(session, "dashboard", "widgets", null, emptyMap())
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val response = CachedDynamicApiResponse(200, "private".encodeToByteArray(), null, null)
                cache.store(accountId, requestIdentity, response)
                memoryCache.storeScreen(
                    screenKey,
                    DynamicScreenSnapshot(emptyList(), emptyMap()),
                    requireNotNull(memoryCache.producer(screenKey)),
                )
                val read = async {
                    coalescer.execute(accountId, requestIdentity, load = {
                        started.complete(Unit)
                        release.await()
                        response
                    }, commit = { cache.store(accountId, requestIdentity, it) })
                }
                started.await()

                clearDesktopDynamicApiState(
                    accountId,
                    coalescer,
                    cache,
                    session.accountId.storageKey,
                    memoryCache,
                )
                release.complete(Unit)

                assertFailsWith<Exception> { read.await() }
                kotlin.test.assertNull(cache.load(accountId, requestIdentity, 1_024))
                kotlin.test.assertNull(memoryCache.screen(screenKey))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `desktop memory retirement survives a rejected disk cache purge`() = runBlocking {
        val root = createTempDirectory("desktop-dynamic-cache-rejected-cleanup-").toFile()
        try {
            val accountId = "e".repeat(64)
            val accountDirectory = root.resolve(accountId).apply { mkdirs() }
            accountDirectory.resolve("unsafe-entry").mkdir()
            val cache = DynamicApiResponseCache(root)
            val coalescer = DynamicApiRequestCoalescer<CachedDynamicApiResponse>()
            val memoryCache = DynamicNativeMemoryCache()
            val session = NextcloudSession("https://cloud.example.test", "alice", "password")
            val screenKey = dynamicScreenCacheKey(session, "dashboard", "widgets", null, emptyMap())
            val staleProducer = requireNotNull(memoryCache.producer(screenKey))
            memoryCache.storeScreen(screenKey, DynamicScreenSnapshot(emptyList(), emptyMap()), staleProducer)

            assertFailsWith<IllegalStateException> {
                clearDesktopDynamicApiState(
                    accountId,
                    coalescer,
                    cache,
                    session.accountId.storageKey,
                    memoryCache,
                )
            }

            kotlin.test.assertNull(memoryCache.screen(screenKey))
            memoryCache.storeScreen(screenKey, DynamicScreenSnapshot(emptyList(), emptyMap()), staleProducer)
            kotlin.test.assertNull(memoryCache.screen(screenKey))
            assertFailsWith<Exception> {
                coalescer.execute(accountId, "GET /dashboard/widgets", load = { error("must remain fenced") })
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
