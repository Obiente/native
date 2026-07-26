package dev.obiente.nextcloudnative.nativeui.preview

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketingCaptureOwnershipTest {
    @Test
    fun `cleanup removes only obsolete manifest-owned png files`() {
        val directory = Files.createTempDirectory("capture-ownership")
        try {
            val manifest = directory.resolve("capture-manifest.json")
            Files.writeString(
                manifest,
                """
                    {
                      "captures": [
                        { "file": "current-owned.png" },
                        { "file": "obsolete-owned.png" },
                        { "file": "../outside.png" }
                      ]
                    }
                """.trimIndent(),
            )
            Files.write(directory.resolve("current-owned.png"), byteArrayOf(1))
            Files.write(directory.resolve("obsolete-owned.png"), byteArrayOf(2))
            Files.write(directory.resolve("hand-authored.png"), byteArrayOf(3))

            removeObsoleteDeclaredCaptureFiles(
                captureDirectory = directory,
                manifestPath = manifest,
                expected = setOf("current-owned.png"),
            )

            assertTrue(Files.exists(directory.resolve("current-owned.png")))
            assertFalse(Files.exists(directory.resolve("obsolete-owned.png")))
            assertTrue(Files.exists(directory.resolve("hand-authored.png")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
