package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopVirtualFileProviderLocationTest {
    @Test
    fun validatesWritableParentAndEmptyTarget() {
        val parent = Files.createTempDirectory("virtual-provider-parent-").toFile()
        try {
            val location = VirtualFileProviderLocation(parent.absolutePath, "Photography")
            assertEquals(parent.resolve("Photography").toPath(), validateDesktopVirtualFileProviderLocation(location))
            parent.resolve("Photography").mkdirs()
            parent.resolve("Photography/file.txt").writeText("occupied")
            assertFailsWith<IllegalArgumentException> { validateDesktopVirtualFileProviderLocation(location) }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun renamingVisibleFolderKeepsTheSameInternalCacheRoot() {
        val parent = Files.createTempDirectory("virtual-provider-rename-").toFile()
        val otherParent = Files.createTempDirectory("virtual-provider-move-").toFile()
        try {
            val current = VirtualFileProviderLocation(parent.absolutePath, "Nextcloud Native")

            assertEquals(
                false,
                desktopVirtualFileCacheRootChanges(current, parent.resolve("Photography").toPath()),
            )
            assertEquals(
                true,
                desktopVirtualFileCacheRootChanges(current, otherParent.resolve("Photography").toPath()),
            )
        } finally {
            parent.deleteRecursively()
            otherParent.deleteRecursively()
        }
    }

    @Test
    fun unavailableSelectedDriveIsNotRecreatedOnAnotherFilesystem() {
        val temporary = Files.createTempDirectory("virtual-provider-missing-").toFile()
        val missingParent = temporary.resolve("detached-drive")
        try {
            assertFailsWith<IllegalArgumentException> {
                DesktopVirtualRangeCache(
                    root = missingParent.resolve(".nextcloud-native-cache"),
                    policy = { VirtualFileCachePolicy(automaticCleanup = false) },
                    createParentDirectories = false,
                )
            }
            assertEquals(false, missingParent.exists())
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun activationValidationDoesNotRecreateAMissingConfiguredParent() {
        val temporary = Files.createTempDirectory("virtual-provider-activation-").toFile()
        val missingParent = temporary.resolve("detached-drive")
        try {
            assertFailsWith<IllegalArgumentException> {
                validateDesktopVirtualFileProviderLocation(
                    VirtualFileProviderLocation(missingParent.absolutePath, "Nextcloud Native"),
                )
            }
            assertEquals(false, missingParent.exists())
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun internalCacheDirectoryCannotBeUsedAsTheVisibleFolder() {
        assertEquals(false, ".nextcloud-native-cache".isValidVirtualFileProviderFolderName())
        assertEquals(false, ".NEXTCLOUD-NATIVE-CACHE".isValidVirtualFileProviderFolderName())
    }

    @Test
    fun invalidInternalCacheRootIsRejectedBeforeSavingALocation() {
        val parent = Files.createTempDirectory("virtual-provider-invalid-cache-").toFile()
        val invalidCache = parent.resolve(INTERNAL_VIRTUAL_FILE_CACHE_FOLDER_NAME)
        try {
            invalidCache.writeText("not a directory")

            assertEquals(true, hasInvalidDesktopVirtualFileCacheRoot(parent.toPath()))
            assertFailsWith<IllegalArgumentException> {
                validateDesktopVirtualFileProviderLocation(
                    VirtualFileProviderLocation(parent.absolutePath, "Nextcloud Native"),
                )
            }
            assertEquals("not a directory", invalidCache.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun locationResultMessagesRemainBoundedForLongValidPaths() {
        val message = virtualFileLocationActionMessage(
            "Virtual files will appear at ",
            "/${"nested/".repeat(1_000)}Nextcloud Native",
        )

        assertEquals(MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH, message.length)
        assertEquals(true, message.startsWith("Virtual files will appear at ..."))
        VirtualFileStorageActionResult.Completed(message)
    }
}
