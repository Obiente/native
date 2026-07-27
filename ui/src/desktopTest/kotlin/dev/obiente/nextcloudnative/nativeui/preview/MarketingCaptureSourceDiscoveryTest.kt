package dev.obiente.nextcloudnative.nativeui.preview

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketingCaptureSourceDiscoveryTest {
    @Test
    fun `source discovery returns stable repository relative files`() {
        withRepository { repository ->
            Files.createDirectories(repository.resolve("tools"))
            Files.createDirectories(repository.resolve("ui/source"))
            Files.writeString(repository.resolve("tools/marketing-capture-inputs.txt"), "ui/source\n")
            Files.writeString(repository.resolve("ui/source/B.kt"), "b")
            Files.writeString(repository.resolve("ui/source/A.kt"), "a")

            assertEquals(
                listOf(
                    "tools/marketing-capture-inputs.txt",
                    "ui/source/A.kt",
                    "ui/source/B.kt",
                ),
                discoverCaptureSources(repository),
            )
        }
    }

    @Test
    fun `source discovery rejects escaping and platform dependent paths`() {
        listOf("../outside", "ui\\source", "/absolute").forEach { entry ->
            withRepository { repository ->
                Files.createDirectories(repository.resolve("tools"))
                Files.writeString(
                    repository.resolve("tools/marketing-capture-inputs.txt"),
                    "$entry\n",
                )
                assertFailsWith<IllegalArgumentException> {
                    discoverCaptureSources(repository)
                }
            }
        }
    }

    @Test
    fun `source discovery rejects symbolic link inputs`() {
        withRepository { repository ->
            Files.createDirectories(repository.resolve("tools"))
            val outside = Files.createTempDirectory("capture-source-outside")
            try {
                Files.writeString(outside.resolve("source.kt"), "outside")
                Files.createSymbolicLink(repository.resolve("linked"), outside)
                Files.writeString(
                    repository.resolve("tools/marketing-capture-inputs.txt"),
                    "linked\n",
                )
                assertFailsWith<IllegalArgumentException> {
                    discoverCaptureSources(repository)
                }
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    private inline fun withRepository(block: (Path) -> Unit) {
        val repository = Files.createTempDirectory("capture-source-repository")
        try {
            block(repository)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }
}
