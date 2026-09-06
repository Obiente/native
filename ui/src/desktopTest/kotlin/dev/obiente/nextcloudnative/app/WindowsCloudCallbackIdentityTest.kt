package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WindowsCloudCallbackIdentityTest {
    private val root = WindowsCloudFileIdentity("a".repeat(64), "", "root", 0L, true)

    @Test
    fun rootEnumerationUsesVerifiedRegistrationContextWhenItemIdentityIsAbsent() {
        val bytes = WindowsCloudFileIdentityCodec.encode(root)
        Memory(256).use { structure ->
            memory(bytes).use { context ->
                val info = CfCallbackInfo(structure).apply {
                    fileId = 42L
                    syncRootFileId = 42L
                    syncRootIdentity = context
                    syncRootIdentityLength = bytes.size
                }
                assertContentEquals(bytes, info.windowsCloudCallbackIdentity(true))
                assertNull(info.windowsCloudCallbackIdentity(false))
                info.fileId = 43L
                assertNull(info.windowsCloudCallbackIdentity(true))
                info.fileId = 0L
                info.syncRootFileId = 0L
                assertNull(info.windowsCloudCallbackIdentity(true))
            }
        }
    }

    @Test
    fun itemIdentityTakesPrecedenceAndDoesNotFallBackWhenMalformed() {
        val bytes = WindowsCloudFileIdentityCodec.encode(root.copy(path = "Folder", remoteRevision = "v1"))
        Memory(256).use { structure ->
            memory(bytes).use { item ->
                val info = CfCallbackInfo(structure).apply {
                    fileIdentity = item
                    fileIdentityLength = bytes.size
                }
                assertContentEquals(bytes, info.windowsCloudCallbackIdentity(true))
                info.fileIdentityLength = 4097
                assertFailsWith<IllegalArgumentException> { info.windowsCloudCallbackIdentity(true) }
                info.fileIdentityLength = -1
                assertFailsWith<IllegalArgumentException> { info.windowsCloudCallbackIdentity(true) }
                info.fileIdentityLength = bytes.size
                info.fileIdentity = null
                assertFailsWith<IllegalArgumentException> { info.windowsCloudCallbackIdentity(true) }
            }
        }
    }

    @Test
    fun rootFallbackRejectsInvalidOrNonRootContext() {
        val valid = WindowsCloudFileIdentityCodec.encode(root)
        val child = WindowsCloudFileIdentityCodec.encode(root.copy(path = "Folder"))
        for (bytes in listOf(valid.copyOf().apply { this[lastIndex] = 0 }, child)) {
            Memory(256).use { structure ->
                memory(bytes).use { context ->
                    val info = CfCallbackInfo(structure).apply {
                        fileId = 42L
                        syncRootFileId = 42L
                        syncRootIdentity = context
                        syncRootIdentityLength = bytes.size
                    }
                    assertFailsWith<IllegalArgumentException> { info.windowsCloudCallbackIdentity(true) }
                }
            }
        }
    }

    private fun memory(bytes: ByteArray) = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
}
