package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.app.MarketingCaptureAssets
import dev.obiente.nextcloudnative.app.MarketingCaptureScenario
import dev.obiente.nextcloudnative.app.NextcloudNativeMarketingCapture
import dev.obiente.nextcloudnative.app.marketingCaptureScenarios
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

fun main(arguments: Array<String>) {
    require(arguments.isEmpty()) {
        "The capture registry owns every output path and accepts no arguments."
    }
    val repositoryRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val outputs = marketingCaptureScenarios.map { scenario ->
        repositoryRoot.resolve("website/public/screenshots/${scenario.fileName}").normalize()
    }
    val captureDirectory = repositoryRoot.resolve("website/public/screenshots")
    removeObsoleteDeclaredCaptureFiles(
        captureDirectory = captureDirectory,
        manifestPath = captureDirectory.resolve("capture-manifest.json"),
        expected = marketingCaptureScenarios.mapTo(mutableSetOf(), MarketingCaptureScenario::fileName),
    )
    val avatar = loadObienteAvatar()
    val assets = MarketingCaptureAssets(
        avatar = avatar,
    )

    marketingCaptureScenarios.zip(outputs).forEach { (scenario, output) ->
        capture(
            output = output,
            width = scenario.width,
            height = scenario.height,
            density = Density(scenario.density),
            scenario = scenario,
            assets = assets,
        )
    }
    writeCaptureManifest(repositoryRoot, outputs)
    println("Captured ${outputs.size} real Compose scenarios without a device.")
}

private fun capture(
    output: Path,
    width: Int,
    height: Int,
    density: Density,
    scenario: MarketingCaptureScenario,
    assets: MarketingCaptureAssets,
) {
    Files.createDirectories(output.parent)
    val scene = ImageComposeScene(
        width = width,
        height = height,
        density = density,
        coroutineContext = Dispatchers.Unconfined,
    ) {
        NextcloudNativeMarketingCapture(scenario, assets)
    }
    try {
        val encoded = requireNotNull(scene.render().encodeToData(EncodedImageFormat.PNG)) {
            "Compose could not encode ${output.fileName}."
        }
        Files.write(output, encoded.bytes)
    } finally {
        scene.close()
    }
}

private fun loadObienteAvatar(): ImageBitmap {
    val bytes = requireNotNull(
        object {}.javaClass.getResourceAsStream("/marketing/obiente-avatar.png"),
    ) { "The repository-owned Obiente avatar is missing." }.use { it.readBytes() }
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

private fun writeCaptureManifest(
    repositoryRoot: Path,
    outputs: List<Path>,
) {
    val captureSources = discoverCaptureSources(repositoryRoot)
    val sourceDigest = MessageDigest.getInstance("SHA-256")
    captureSources.forEach { relative ->
        sourceDigest.update(relative.encodeToByteArray())
        sourceDigest.update(0.toByte())
        sourceDigest.update(Files.readAllBytes(repositoryRoot.resolve(relative)))
    }
    val avatar = repositoryRoot.resolve(
        "ui/src/desktopMain/resources/marketing/obiente-avatar.png",
    )
    val captures = marketingCaptureScenarios.zip(outputs).joinToString(
        separator = ",\n",
        prefix = "[\n",
        postfix = "\n          ]",
    ) { (scenario, output) ->
        """
            {
              "scenario": "${scenario.id}",
              "file": "${scenario.fileName}",
              "width": ${scenario.width},
              "height": ${scenario.height},
              "density": ${scenario.density},
              "feature": "${scenario.feature}",
              "surface": "${scenario.surface}",
              "state": "${scenario.state}",
              "purpose": "${scenario.purpose.manifestValue}",
              "platform": "${scenario.platform}",
              "viewport": "${scenario.viewport}"${scenario.pullRequest?.let { pullRequest ->
                  ",\n              \"pullRequest\": $pullRequest"
              }.orEmpty()},
              "sha256": "${Files.readAllBytes(output).sha256()}"
            }
        """.trimIndent().prependIndent("            ")
    }
    val manifest = """
        {
          "schemaVersion": 2,
          "renderer": "Compose ImageComposeScene",
          "identity": "Obiente",
          "cloudIdentity": "Nextcloud",
          "networkAccess": false,
          "captureSources": ${captureSources.joinToString(
              prefix = "[\"",
              separator = "\", \"",
              postfix = "\"]",
          )},
          "captureSourceSha256": "${sourceDigest.digest().toHex()}",
          "avatarSha256": "${Files.readAllBytes(avatar).sha256()}",
          "captures": $captures
        }
    """.trimIndent() + "\n"
    Files.writeString(
        repositoryRoot.resolve("website/public/screenshots/capture-manifest.json"),
        manifest,
    )
}

private fun discoverCaptureSources(repositoryRoot: Path): List<String> {
    val inventoryPath = "tools/marketing-capture-inputs.txt"
    val sourceRoots = Files.readAllLines(repositoryRoot.resolve(inventoryPath))
        .map(String::trim)
        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
        .onEach { relative ->
            require(
                !Path.of(relative).isAbsolute &&
                    relative.split('/').none { segment -> segment == ".." },
            ) {
                "Marketing capture inputs must stay inside the repository."
            }
        }
    val discovered = sourceRoots.flatMap { relativeRoot ->
        val root = repositoryRoot.resolve(relativeRoot)
        require(Files.exists(root)) {
            "Marketing capture input does not exist: $relativeRoot"
        }
        if (Files.isRegularFile(root)) {
            listOf(relativeRoot)
        } else {
            Files.walk(root).use { paths ->
                paths.filter { path -> Files.isRegularFile(path) }
                    .map { repositoryRoot.relativize(it).toString().replace('\\', '/') }
                    .toList()
            }
        }
    }
    return (discovered + inventoryPath).distinct().sorted()
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
