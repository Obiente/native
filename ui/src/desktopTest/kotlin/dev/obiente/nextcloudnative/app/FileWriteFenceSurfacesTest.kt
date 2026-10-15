package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Surfaces outside the Files workspace consult the same account-scoped write fence. */
class FileWriteFenceSurfacesTest {
    private val session = NextcloudSession("https://fence-scene.invalid", "alice", "synthetic")
    private val block = FileWriteBlock.RefreshRequired("Projects/Old")
    private val fence = FileWriteFence { path -> block.takeIf { path == "Projects/Old" || path.startsWith("Projects/Old/") } }

    @Test
    fun `the folder picker refuses creating a folder inside one being deleted`(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val operations = remoteFolderPickerOperations(services(calls), session, "alice", writeFence = fence)

        val refused = assertFailsWith<FileWriteBlockedException> { operations.createDirectoryIfAbsent("Projects/Old/New") }
        operations.createDirectoryIfAbsent("Projects/New")

        assertSame(block, refused.block)
        assertEquals(block.message, refused.message)
        assertEquals(listOf("createDirectoryIfAbsent:Projects/New"), calls, "Only the unblocked folder is created.")
    }

    @Test
    fun `restoring a version of a file inside a folder being deleted is refused with the typed reason`() {
        val calls = mutableListOf<String>()
        nativeSceneTest(800, 900, content = {
            FileVersionHistorySection(services(calls), fence, session, "alice", file("Projects/Old/notes.txt"), initiallyExpanded = true)
        }) {
            settleUntil { has("Restore as current file") }
            click("Restore as current file")
            click("Restore version")

            assertTrue(has(block.message), "The reason names the folder to open or refresh.")
            assertTrue(calls.none { it.startsWith("restoreFileVersion") }, "Nothing was restored: $calls")
        }
    }

    @Test
    fun `restoring a version elsewhere is unaffected`() {
        val calls = mutableListOf<String>()
        nativeSceneTest(800, 900, content = {
            FileVersionHistorySection(services(calls), fence, session, "alice", file("Projects/notes.txt"), initiallyExpanded = true)
        }) {
            settleUntil { has("Restore as current file") }
            click("Restore as current file")
            click("Restore version")
            settleUntil { calls.any { it.startsWith("restoreFileVersion") } }

            assertTrue(calls.any { it == "restoreFileVersion:Projects/notes.txt" }, "$calls")
        }
    }

    private fun file(path: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "application/octet-stream",
        size = 1,
        lastModified = null,
        fileId = 7L,
        hasPreview = false,
        etag = "v1",
        permissions = "RGDNVW",
    )

    private fun services(calls: MutableList<String>): NextcloudPlatformServices =
        Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticFenceServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "getExternalFileHandoffSupport" -> ExternalFileHandoffSupport.Unsupported("synthetic")
                "listFileVersions" -> FileVersionHistory(
                    fileId = 7L,
                    versions = listOf(NextcloudFileVersion(7L, "1700000000", 1L, "Older", "e0", null, null)),
                )
                "createDirectoryIfAbsent" -> {
                    calls += "createDirectoryIfAbsent:${args!![2]}"
                    true
                }
                "restoreFileVersion" -> {
                    calls += "restoreFileVersion:${(args!![2] as NextcloudFile).path}"
                    Unit
                }
                else -> error("Unexpected synthetic operation: ${method.name}")
            }
        } as NextcloudPlatformServices
}
