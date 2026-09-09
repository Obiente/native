package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JvmExactFileComparisonOutputStreamTest {
    @Test
    fun `exact streamed bytes complete without retaining the remote file`() {
        withSource("exact content") { source ->
            JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
                comparison.write("exact ".encodeToByteArray())
                comparison.write("content".encodeToByteArray())
                comparison.requireComplete()
            }
        }
    }

    @Test
    fun `mismatched streamed bytes fail before publish`() {
        withSource("expected") { source ->
            JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
                assertFailsWith<IllegalStateException> {
                    comparison.write("expEcted".encodeToByteArray())
                }
            }
        }
    }

    @Test
    fun `truncated and oversized streams fail closed`() {
        withSource("expected") { source ->
            JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
                comparison.write("expect".encodeToByteArray())
                assertFailsWith<IllegalStateException> { comparison.requireComplete() }
            }
            JvmExactFileComparisonOutputStream(source, source.length()).use { comparison ->
                assertFailsWith<IllegalStateException> {
                    comparison.write("expected!".encodeToByteArray())
                }
            }
        }
    }

    private fun withSource(content: String, block: (java.io.File) -> Unit) {
        val source = Files.createTempFile("nextcloud-exact-comparison", ".tmp").toFile()
        try {
            source.writeText(content)
            block(source)
        } finally {
            source.delete()
        }
    }
}
