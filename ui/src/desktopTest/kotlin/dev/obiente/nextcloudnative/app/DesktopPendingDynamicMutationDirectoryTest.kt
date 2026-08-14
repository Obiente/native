package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
