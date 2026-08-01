package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.MarketingCaptureRegistryEntry
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingCaptureOwnershipTest {
    @Test
    fun `capture typography is repository owned and weight stable`() {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val repositoryRoot = if (Files.exists(workingDirectory.resolve("tools/marketing-capture-inputs.txt"))) {
            workingDirectory
        } else {
            requireNotNull(workingDirectory.parent)
        }
        val typography = deterministicCaptureTypography(repositoryRoot)
        val standardStyles = listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
            typography.titleMedium,
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
        )
        val fontFamily = assertNotNull(standardStyles.first().fontFamily)
        assertTrue(standardStyles.all { style -> style.fontFamily == fontFamily })

        val captureSources = discoverCaptureSources(repositoryRoot)
        listOf("Regular", "Medium", "SemiBold", "Bold").forEach { weight ->
            assertTrue(
                "ui/src/desktopMain/resources/marketing/fonts/NotoSans-$weight.ttf" in captureSources,
            )
        }
        assertTrue("ui/src/desktopMain/resources/marketing/fonts/OFL.txt" in captureSources)
    }

    @Test
    fun `preservation requires an explicit allowlist and rejects symlinks`() {
        withTemporaryDirectory("capture-preservation") { parent ->
            val current = Files.createDirectory(parent.resolve("current"))
            val staged = Files.createDirectory(parent.resolve("staged"))
            Files.writeString(
                current.resolve("capture-manifest.json"),
                """{"captures":[{"file":"old-owned.png"}]}""",
            )
            Files.write(current.resolve("old-owned.png"), byteArrayOf(1))
            Files.write(current.resolve("hand-authored.png"), byteArrayOf(2))

            assertFailsWith<IllegalStateException> {
                stagePreservedCaptureFiles(
                    current,
                    current.resolve("capture-manifest.json"),
                    staged,
                )
            }
            stagePreservedCaptureFiles(
                current,
                current.resolve("capture-manifest.json"),
                staged,
                setOf("hand-authored.png"),
            )
            assertContentEquals(
                byteArrayOf(2),
                Files.readAllBytes(staged.resolve("hand-authored.png")),
            )

            val symlinkDirectory = Files.createDirectory(parent.resolve("symlink-current"))
            val external = Files.write(parent.resolve("external.png"), byteArrayOf(4))
            Files.createSymbolicLink(symlinkDirectory.resolve("linked.png"), external)
            assertFailsWith<IllegalArgumentException> {
                stagePreservedCaptureFiles(
                    symlinkDirectory,
                    symlinkDirectory.resolve("capture-manifest.json"),
                    Files.createDirectory(parent.resolve("symlink-staged")),
                    setOf("linked.png"),
                )
            }
        }
    }

    @Test
    fun `staged catalog fully validates image schema dimensions and contents`() {
        withTemporaryDirectory("capture-validation") { staged ->
            val entry = registryEntry()
            writePng(staged.resolve(entry.fileName), entry.width, entry.height)
            writeManifest(staged, entry)

            validateStagedCaptureCatalog(
                stagedDirectory = staged,
                registry = listOf(entry),
                expectedCaptureSources = listOf("source.kt"),
                expectedCaptureSourceSha256 = "1".repeat(64),
                expectedAvatarSha256 = "2".repeat(64),
                preservedFileNames = emptySet(),
            )

            writePng(staged.resolve(entry.fileName), entry.width + 1, entry.height)
            writeManifest(staged, entry)
            assertFailsWith<IllegalArgumentException> {
                validateStagedCaptureCatalog(
                    staged,
                    listOf(entry),
                    listOf("source.kt"),
                    "1".repeat(64),
                    "2".repeat(64),
                    emptySet(),
                )
            }

            Files.write(staged.resolve(entry.fileName), byteArrayOf(1, 2, 3))
            writeManifest(staged, entry)
            assertFailsWith<IllegalArgumentException> {
                validateStagedCaptureCatalog(
                    staged,
                    listOf(entry),
                    listOf("source.kt"),
                    "1".repeat(64),
                    "2".repeat(64),
                    emptySet(),
                )
            }
        }
    }

    @Test
    fun `staged catalog rejects undeclared files and symbolic links`() {
        withTemporaryDirectory("capture-validation-adversarial") { staged ->
            val entry = registryEntry()
            writePng(staged.resolve(entry.fileName), entry.width, entry.height)
            writeManifest(staged, entry)
            Files.writeString(staged.resolve("undeclared.txt"), "unexpected")
            assertFailsWith<IllegalArgumentException> {
                validateCatalog(staged, entry)
            }
            Files.delete(staged.resolve("undeclared.txt"))
            val external = Files.write(
                requireNotNull(staged.parent).resolve("external-capture.png"),
                byteArrayOf(1),
            )
            Files.createSymbolicLink(staged.resolve("linked.png"), external)
            assertFailsWith<IllegalArgumentException> {
                validateCatalog(staged, entry)
            }
            Files.deleteIfExists(external)
        }
    }

    @Test
    fun `failed non-atomic promotion restores byte-identical old catalog`() {
        withTemporaryDirectory("capture-rollback") { parent ->
            val current = Files.createDirectory(parent.resolve("screenshots"))
            val staged = Files.createDirectory(parent.resolve("staged"))
            Files.writeString(current.resolve("capture-manifest.json"), "old manifest")
            Files.write(current.resolve("old.png"), byteArrayOf(1, 2, 3))
            Files.writeString(staged.resolve("capture-manifest.json"), "new manifest")
            Files.write(staged.resolve("new.png"), byteArrayOf(9, 8, 7))
            val before = snapshot(current)
            var moveCount = 0

            assertFailsWith<IOException> {
                promoteStagedCaptureDirectory(current, staged) { source, target ->
                    moveCount += 1
                    when (moveCount) {
                        1, 3 -> Files.move(source, target)
                        2 -> {
                            Files.createDirectory(target)
                            Files.copy(
                                source.resolve("new.png"),
                                target.resolve("new.png"),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            throw IOException("injected partial move failure")
                        }
                        else -> error("Unexpected move call")
                    }
                }
            }

            assertEquals(3, moveCount)
            assertEquals(before.keys, snapshot(current).keys)
            before.forEach { (name, bytes) ->
                assertContentEquals(bytes, snapshot(current).getValue(name))
            }
            assertFalse(Files.exists(current.resolve("new.png")))
        }
    }

    @Test
    fun `successful promotion replaces the catalog as one directory`() {
        withTemporaryDirectory("capture-promotion") { parent ->
            val current = Files.createDirectory(parent.resolve("screenshots"))
            val staged = Files.createDirectory(parent.resolve("staged"))
            Files.write(current.resolve("old.png"), byteArrayOf(1))
            Files.write(staged.resolve("new.png"), byteArrayOf(2))

            promoteStagedCaptureDirectory(current, staged)

            assertFalse(Files.exists(current.resolve("old.png")))
            assertContentEquals(byteArrayOf(2), Files.readAllBytes(current.resolve("new.png")))
            assertFalse(Files.exists(staged))
        }
    }

    private fun validateCatalog(staged: Path, entry: MarketingCaptureRegistryEntry) {
        validateStagedCaptureCatalog(
            staged,
            listOf(entry),
            listOf("source.kt"),
            "1".repeat(64),
            "2".repeat(64),
            emptySet(),
        )
    }

    private fun registryEntry() = MarketingCaptureRegistryEntry(
        id = "capture",
        baseScenario = "capture",
        fileName = "capture.png",
        theme = "dark",
        feature = "Files",
        surface = "Browser",
        state = "Ready",
        purpose = "showcase",
        platform = "desktop",
        viewport = "wide",
        pullRequest = 1,
        issue = 2,
        width = 4,
        height = 3,
        density = 1f,
    )

    private fun writePng(path: Path, width: Int, height: Int) {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", path.toFile())
    }

    private fun writeManifest(directory: Path, entry: MarketingCaptureRegistryEntry) {
        val digest = sha256(Files.readAllBytes(directory.resolve(entry.fileName)))
        Files.writeString(
            directory.resolve("capture-manifest.json"),
            """
                {
                  "schemaVersion": 3,
                  "renderer": "Compose ImageComposeScene",
                  "identity": "Obiente",
                  "cloudIdentity": "Nextcloud",
                  "networkAccess": false,
                  "captureSources": ["source.kt"],
                  "captureSourceSha256": "${"1".repeat(64)}",
                  "avatarSha256": "${"2".repeat(64)}",
                  "captures": [{
                    "scenario": "${entry.id}",
                    "baseScenario": "${entry.baseScenario}",
                    "file": "${entry.fileName}",
                    "theme": "${entry.theme}",
                    "width": ${entry.width},
                    "height": ${entry.height},
                    "density": ${entry.density},
                    "feature": "${entry.feature}",
                    "surface": "${entry.surface}",
                    "state": "${entry.state}",
                    "purpose": "${entry.purpose}",
                    "platform": "${entry.platform}",
                    "viewport": "${entry.viewport}",
                    "pullRequest": ${entry.pullRequest},
                    "issue": ${entry.issue},
                    "sha256": "$digest"
                  }]
                }
            """.trimIndent(),
        )
    }

    private fun snapshot(directory: Path): Map<String, ByteArray> =
        Files.list(directory).use { paths ->
            val snapshot = linkedMapOf<String, ByteArray>()
            paths.sorted().forEach { path ->
                snapshot[path.name] = Files.readAllBytes(path)
            }
            snapshot
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private inline fun withTemporaryDirectory(prefix: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
