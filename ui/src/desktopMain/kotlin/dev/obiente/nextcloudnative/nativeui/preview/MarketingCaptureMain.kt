package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import dev.obiente.nextcloudnative.app.MarketingCaptureAssets
import dev.obiente.nextcloudnative.app.MarketingCaptureScenario
import dev.obiente.nextcloudnative.app.NextcloudNativeMarketingCapture
import dev.obiente.nextcloudnative.app.marketingCaptureScenarios
import dev.obiente.nextcloudnative.app.registryEntry
import dev.obiente.nextcloudnative.app.validateMarketingCaptureRegistry
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

private val captureManifestJson = Json {
    prettyPrint = true
}

fun main(arguments: Array<String>) {
    require(arguments.isEmpty()) {
        "The capture registry owns every output path and accepts no arguments."
    }
    val repositoryRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val registry = marketingCaptureScenarios.map(MarketingCaptureScenario::registryEntry)
    validateMarketingCaptureRegistry(registry)
    val captureSources = discoverCaptureSources(repositoryRoot)
    val captureSourceSha256 = captureSourceDigest(repositoryRoot, captureSources)
    val avatarSha256 = Files.readAllBytes(
        repositoryRoot.resolve(
            "ui/src/desktopMain/resources/marketing/obiente-avatar.png",
        ),
    ).sha256()

    val captureDirectory = repositoryRoot.resolve("website/public/screenshots")
    val transactionDirectory = repositoryRoot.resolve("ui/build/marketing-capture-transactions")
    Files.createDirectories(transactionDirectory)
    val stagedDirectory = Files.createTempDirectory(
        transactionDirectory,
        "screenshots-staging-",
    )

    try {
        stagePreservedCaptureFiles(
            captureDirectory = captureDirectory,
            manifestPath = captureDirectory.resolve("capture-manifest.json"),
            stagedDirectory = stagedDirectory,
        )
        val outputs = marketingCaptureScenarios.map { scenario ->
            captureOutputPath(stagedDirectory, scenario.fileName)
        }
        val assets = MarketingCaptureAssets(
            avatar = loadObienteAvatar(),
            mediaPreview = loadMarketingMediaPreview(),
            services = networkInertMarketingServices(loadRawCaptureFixture()),
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
        writeCaptureManifest(
            captureDirectory = stagedDirectory,
            outputs = outputs,
            captureSources = captureSources,
            captureSourceSha256 = captureSourceSha256,
            avatarSha256 = avatarSha256,
        )
        validateStagedCaptureCatalog(
            stagedDirectory = stagedDirectory,
            registry = registry,
            expectedCaptureSources = captureSources,
            expectedCaptureSourceSha256 = captureSourceSha256,
            expectedAvatarSha256 = avatarSha256,
        )
        require(discoverCaptureSources(repositoryRoot) == captureSources) {
            "Marketing capture sources changed while screenshots were rendering."
        }
        require(captureSourceDigest(repositoryRoot, captureSources) == captureSourceSha256) {
            "Marketing capture source contents changed while screenshots were rendering."
        }
        require(
            Files.readAllBytes(
                repositoryRoot.resolve(
                    "ui/src/desktopMain/resources/marketing/obiente-avatar.png",
                ),
            ).sha256() == avatarSha256,
        ) {
            "The marketing capture avatar changed while screenshots were rendering."
        }
        promoteStagedCaptureDirectory(
            captureDirectory = captureDirectory,
            stagedDirectory = stagedDirectory,
        )
        println("Captured ${outputs.size} real Compose scenarios without a device.")
    } finally {
        if (Files.exists(stagedDirectory)) {
            stagedDirectory.toFile().deleteRecursively()
        }
    }
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
    val rawMediaCapture = RawMediaMarketingCapture.forScenarioOrNull(scenario)
    val nativeTiffCapture = NativeTiffMarketingCapture.forScenarioOrNull(scenario)
    check(rawMediaCapture == null || nativeTiffCapture == null) {
        "${scenario.id} cannot use multiple isolated media renderers."
    }
    val scene = ImageComposeScene(
        width = width,
        height = height,
        density = density,
        coroutineContext = Dispatchers.Unconfined,
    ) {
        when {
            rawMediaCapture != null -> rawMediaCapture.Content()
            nativeTiffCapture != null -> nativeTiffCapture.Content()
            else -> NextcloudNativeMarketingCapture(scenario, assets)
        }
    }
    try {
        scene.render().close()
        val rendered = scene.render()
        rawMediaCapture?.verify()
        nativeTiffCapture?.verify()
        val encoded = rendered.use {
            requireNotNull(it.encodeToData(EncodedImageFormat.PNG)) {
                "Compose could not encode ${output.fileName}."
            }
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

private fun loadMarketingMediaPreview(): ImageBitmap =
    Image.makeFromEncoded(loadRawCaptureFixture()).toComposeImageBitmap()

private fun writeCaptureManifest(
    captureDirectory: Path,
    outputs: List<Path>,
    captureSources: List<String>,
    captureSourceSha256: String,
    avatarSha256: String,
) {
    val captures = buildJsonArray {
        marketingCaptureScenarios.zip(outputs).forEach { (scenario, output) ->
            add(
                buildJsonObject {
                    put("scenario", scenario.id)
                    put("file", scenario.fileName)
                    put("width", scenario.width)
                    put("height", scenario.height)
                    put("density", scenario.density)
                    put("feature", scenario.feature)
                    put("surface", scenario.surface)
                    put("state", scenario.state)
                    put("purpose", scenario.purpose.manifestValue)
                    put("platform", scenario.platform)
                    put("viewport", scenario.viewport)
                    scenario.pullRequest?.let { put("pullRequest", it) }
                    scenario.issue?.let { put("issue", it) }
                    put("sha256", Files.readAllBytes(output).sha256())
                },
            )
        }
    }
    val manifest = buildJsonObject {
        put("schemaVersion", 2)
        put("renderer", "Compose ImageComposeScene")
        put("identity", "Obiente")
        put("cloudIdentity", "Nextcloud")
        put("networkAccess", false)
        put(
            "captureSources",
            buildJsonArray {
                captureSources.forEach { add(it) }
            },
        )
        put("captureSourceSha256", captureSourceSha256)
        put("avatarSha256", avatarSha256)
        put("captures", captures)
    }
    val serialized = captureManifestJson.encodeToString(
        JsonElement.serializer(),
        manifest,
    ) + "\n"
    require(
        captureManifestJson.parseToJsonElement(serialized)
            .jsonObject
            .getValue("captures")
            .jsonArray
            .size == marketingCaptureScenarios.size,
    ) {
        "Capture manifest did not retain every registered scenario."
    }
    Files.writeString(
        captureDirectory.resolve("capture-manifest.json"),
        serialized,
    )
}

internal fun discoverCaptureSources(repositoryRoot: Path): List<String> {
    val normalizedRoot = repositoryRoot.toAbsolutePath().normalize()
    val realRoot = normalizedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val inventoryPath = "tools/marketing-capture-inputs.txt"
    val inventory = normalizedRoot.resolve(inventoryPath)
    requireSafeRepositoryPath(normalizedRoot, realRoot, inventory, inventoryPath)
    val sourceRoots = Files.readAllLines(inventory)
        .map(String::trim)
        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
        .map { entry ->
            val optional = entry.startsWith("?")
            val relative = entry.removePrefix("?")
            require(
                '\\' !in relative &&
                    relative.isNotEmpty() &&
                    !Path.of(relative).isAbsolute &&
                    relative.split('/').none { segment ->
                        segment.isEmpty() || segment == "." || segment == ".."
                    },
            ) {
                "Marketing capture inputs must stay inside the repository."
            }
            optional to relative
        }
    val discovered = sourceRoots.flatMap { (optional, relativeRoot) ->
        val root = normalizedRoot.resolve(relativeRoot).normalize()
        require(root.startsWith(normalizedRoot)) {
            "Marketing capture inputs must stay inside the repository."
        }
        if (optional && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return@flatMap emptyList()
        }
        require(Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            "Marketing capture input does not exist: $relativeRoot"
        }
        requireSafeRepositoryPath(normalizedRoot, realRoot, root, relativeRoot)
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            listOf(relativeRoot)
        } else {
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                "Marketing capture input must be a regular file or directory: $relativeRoot"
            }
            Files.walk(root).use { paths ->
                paths.filter { path ->
                    requireSafeRepositoryPath(
                        normalizedRoot,
                        realRoot,
                        path,
                        normalizedRoot.relativize(path).toString(),
                    )
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                }
                    .map { repositoryRoot.relativize(it).toString().replace('\\', '/') }
                    .toList()
            }
        }
    }
    return (discovered + inventoryPath).distinct().sorted()
}

private fun requireSafeRepositoryPath(
    repositoryRoot: Path,
    realRepositoryRoot: Path,
    path: Path,
    label: String,
) {
    require(path.toAbsolutePath().normalize().startsWith(repositoryRoot)) {
        "Marketing capture input escaped the repository: $label"
    }
    var cursor: Path? = path.toAbsolutePath().normalize()
    while (cursor != null && cursor.startsWith(repositoryRoot)) {
        require(!Files.isSymbolicLink(cursor)) {
            "Marketing capture inputs must not use symbolic links: $label"
        }
        if (cursor == repositoryRoot) break
        cursor = cursor.parent
    }
    require(path.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realRepositoryRoot)) {
        "Marketing capture input escaped the repository: $label"
    }
}

private fun captureSourceDigest(
    repositoryRoot: Path,
    captureSources: List<String>,
): String {
    val sourceDigest = MessageDigest.getInstance("SHA-256")
    captureSources.forEach { relative ->
        sourceDigest.update(relative.encodeToByteArray())
        sourceDigest.update(0.toByte())
        sourceDigest.update(Files.readAllBytes(repositoryRoot.resolve(relative)))
    }
    return sourceDigest.digest().toHex()
}

private fun captureOutputPath(
    stagedDirectory: Path,
    fileName: String,
): Path {
    val normalizedDirectory = stagedDirectory.toAbsolutePath().normalize()
    val output = normalizedDirectory.resolve(fileName).normalize()
    require(output.parent == normalizedDirectory) {
        "Capture output escaped its staging directory."
    }
    return output
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
