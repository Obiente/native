package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.app.NextcloudNativeMarketingCapture
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat

fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "Pass the repository-owned desktop screenshot output path." }
    val repositoryRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val allowed = repositoryRoot
        .resolve("website/public/screenshots/desktop-home.png")
        .normalize()
    val output = Path.of(arguments.single()).toAbsolutePath().normalize()
    require(output == allowed) { "Marketing capture output must use the repository-owned path." }

    Files.createDirectories(output.parent)
    val scene = ImageComposeScene(
        width = 1_440,
        height = 900,
        density = Density(1f),
        coroutineContext = Dispatchers.Unconfined,
    ) {
        NextcloudNativeMarketingCapture(NextcloudPresentation.Desktop)
    }
    try {
        val encoded = requireNotNull(scene.render().encodeToData(EncodedImageFormat.PNG)) {
            "Compose could not encode the desktop capture."
        }
        Files.write(output, encoded.bytes)
    } finally {
        scene.close()
    }
    println("Captured real Compose desktop UI to ${output.fileName}.")
}
