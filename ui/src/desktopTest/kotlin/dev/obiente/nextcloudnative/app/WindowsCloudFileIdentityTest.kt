package dev.obiente.nextcloudnative.app

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsCloudFileIdentityTest {
    private val shortDirectory = WindowsCloudFileIdentity("a".repeat(64), "Dir", "\"0123456789abc\"", 0L, true)

    @Test
    fun shortItemIdentitiesUseThePaddedEnvelope() {
        for (nameLength in 1..32) {
            val identity = shortDirectory.copy(path = "n".repeat(nameLength))
            val encoded = WindowsCloudFileIdentityCodec.encode(identity)
            assertEquals(256, encoded.size)
            assertEquals(3, encoded[5].toInt())
            assertEquals(identity, WindowsCloudFileIdentityCodec.decode(encoded))
        }
    }

    @Test
    fun longerIdentitiesRoundTripWithoutTruncation() {
        val identity = shortDirectory.copy(path = "long-" + "n".repeat(240))
        val encoded = WindowsCloudFileIdentityCodec.encode(identity)
        assertTrue(encoded.size > 256)
        assertEquals(identity, WindowsCloudFileIdentityCodec.decode(encoded))
    }

    @Test
    fun paddingIsStrictlyValidatedEvenWithARecomputedChecksum() {
        val payload = WindowsCloudFileIdentityCodec.encode(shortDirectory).dropLast(32).toByteArray()
        val invalid = listOf(
            payload.copyOf().apply { this[lastIndex] = 1 },
            payload.copyOf(payload.size - 1),
            payload + byteArrayOf(0),
            payload.copyOf().apply { this[5] = 4 },
        )
        invalid.forEach { changed ->
            assertFailsWith<IllegalArgumentException> {
                WindowsCloudFileIdentityCodec.decode(changed.withChecksum())
            }
        }
    }

    @Test
    fun legacyItemIdentitiesStillDecode() {
        val encoded = WindowsCloudFileIdentityCodec.encode(shortDirectory)
        val length = 29 + 64 + 3 + 15
        val versionTwo = encoded.copyOfRange(0, length).apply { this[5] = 2 }
        assertEquals(shortDirectory, WindowsCloudFileIdentityCodec.decode(versionTwo.withChecksum()))
        val versionOne = (versionTwo.copyOfRange(0, 15) + versionTwo.copyOfRange(23, versionTwo.size))
            .apply { this[5] = 1 }
        assertEquals(shortDirectory, WindowsCloudFileIdentityCodec.decode(versionOne.withChecksum()))
    }

    @Test
    fun registeredRootContextRemainsByteIdentical() {
        val root = WindowsCloudFileIdentity("a".repeat(64), "", "root", 0L, true)
        val expected = "4e4346560002010000000000000000ffffffffffffffff00406161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616100000004726f6f74cd1caa4b0547c9a4f510bddf5f7166b33d240914ed30eea742cb24f698f1fe6e"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, WindowsCloudFileIdentityCodec.encode(root))
    }

    private fun ByteArray.withChecksum() = this + MessageDigest.getInstance("SHA-256").digest(this)
}
