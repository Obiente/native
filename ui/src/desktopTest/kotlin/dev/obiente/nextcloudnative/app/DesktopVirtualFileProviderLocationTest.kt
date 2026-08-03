package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.Path
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
    fun locationResultMessagesRemainBoundedForLongValidPaths() {
        val message = virtualFileLocationActionMessage(
            "Virtual files will appear at ",
            Path.of("/", "nested".repeat(1_000)),
        )

        assertEquals(MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH, message.length)
        assertEquals(true, message.startsWith("Virtual files will appear at ..."))
        VirtualFileStorageActionResult.Completed(message)
    }
}
