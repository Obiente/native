package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.MarketingCaptureRegistryEntry
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.jetbrains.skia.Image
import org.json.JSONObject

private val captureFileName = Regex("[a-z0-9-]+\\.png")
private val preservedCaptureFileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val sha256Digest = Regex("[a-f0-9]{64}")
private val pngSignature = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a,
)

internal val preservedMarketingCaptureFiles: Set<String> = emptySet()

internal fun declaredCaptureFiles(manifestPath: Path): Set<String> {
    if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) return emptySet()
    require(!Files.isSymbolicLink(manifestPath)) {
        "The existing capture manifest must not be a symbolic link."
    }
    require(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
        "The existing capture manifest must be a regular file."
    }
    val manifest = JSONObject(Files.readString(manifestPath))
    val captures = manifest.optJSONArray("captures") ?: return emptySet()
    val declared = buildSet {
        for (index in 0 until captures.length()) {
            val fileName = captures.optJSONObject(index)?.optString("file").orEmpty()
            require(fileName.matches(captureFileName)) {
                "The existing capture manifest contains an unsafe file name."
            }
            require(add(fileName)) {
                "The existing capture manifest contains duplicate file names."
            }
        }
    }
    return declared
}

internal fun stagePreservedCaptureFiles(
    captureDirectory: Path,
    manifestPath: Path,
    stagedDirectory: Path,
    preservedFileNames: Set<String> = preservedMarketingCaptureFiles,
) {
    validatePreservedFileNames(preservedFileNames)
    requireDirectoryWithoutSymlinks(stagedDirectory, "Capture staging directory")
    if (!Files.exists(captureDirectory, LinkOption.NOFOLLOW_LINKS)) return
    requireDirectoryWithoutSymlinks(captureDirectory, "Existing capture directory")

    val declared = declaredCaptureFiles(manifestPath)
    Files.list(captureDirectory).use { paths ->
        paths.forEach { source ->
            val fileName = source.fileName.toString()
            require(!Files.isSymbolicLink(source)) {
                "The capture directory contains a symbolic link: $fileName"
            }
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                "The capture directory contains a non-regular entry: $fileName"
            }
            when {
                fileName == "capture-manifest.json" -> Unit
                fileName in declared -> Unit
                fileName in preservedFileNames -> Files.copy(
                    source,
                    safeChild(stagedDirectory, fileName),
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS,
                )
                else -> error(
                    "The capture directory contains an undeclared file that is not allowlisted: " +
                        fileName,
                )
            }
        }
    }
}

internal fun validateStagedCaptureCatalog(
    stagedDirectory: Path,
    registry: List<MarketingCaptureRegistryEntry>,
    expectedCaptureSources: List<String>,
    expectedCaptureSourceSha256: String,
    expectedAvatarSha256: String,
    preservedFileNames: Set<String> = preservedMarketingCaptureFiles,
) {
    validatePreservedFileNames(preservedFileNames)
    requireDirectoryWithoutSymlinks(stagedDirectory, "Capture staging directory")
    require(expectedCaptureSourceSha256.matches(sha256Digest)) {
        "The expected capture source digest is invalid."
    }
    require(expectedAvatarSha256.matches(sha256Digest)) {
        "The expected avatar digest is invalid."
    }

    val manifestPath = safeChild(stagedDirectory, "capture-manifest.json")
    require(!Files.isSymbolicLink(manifestPath)) {
        "The staged capture manifest must not be a symbolic link."
    }
    require(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
        "The staged capture manifest is missing."
    }
    val manifest = JSONObject(Files.readString(manifestPath))
    require(
        manifest.keySet() == setOf(
            "schemaVersion",
            "renderer",
            "identity",
            "cloudIdentity",
            "networkAccess",
            "captureSources",
            "captureSourceSha256",
            "avatarSha256",
            "captures",
        ),
    ) {
        "The staged capture manifest has an unexpected top-level schema."
    }
    require(manifest.getInt("schemaVersion") == 2)
    require(manifest.getString("renderer") == "Compose ImageComposeScene")
    require(manifest.getString("identity") == "Obiente")
    require(manifest.getString("cloudIdentity") == "Nextcloud")
    require(!manifest.getBoolean("networkAccess"))
    require(manifest.getString("captureSourceSha256") == expectedCaptureSourceSha256)
    require(manifest.getString("avatarSha256") == expectedAvatarSha256)

    val declaredSources = manifest.getJSONArray("captureSources").let { sources ->
        List(sources.length()) { index -> sources.getString(index) }
    }
    require(declaredSources == expectedCaptureSources) {
        "The staged capture source inventory is stale."
    }

    val captures = manifest.getJSONArray("captures")
    require(captures.length() == registry.size) {
        "The staged capture manifest does not contain every registry entry."
    }
    registry.forEachIndexed { index, entry ->
        val capture = captures.getJSONObject(index)
        val expectedKeys = buildSet {
            addAll(
                listOf(
                    "scenario",
                    "file",
                    "width",
                    "height",
                    "density",
                    "feature",
                    "surface",
                    "state",
                    "purpose",
                    "platform",
                    "viewport",
                    "sha256",
                ),
            )
            if (entry.pullRequest != null) add("pullRequest")
            if (entry.issue != null) add("issue")
        }
        require(capture.keySet() == expectedKeys) {
            "${entry.id} has unexpected or missing manifest fields."
        }
        require(capture.getString("scenario") == entry.id)
        require(capture.getString("file") == entry.fileName)
        require(capture.getInt("width") == entry.width)
        require(capture.getInt("height") == entry.height)
        require(capture.getDouble("density") == entry.density.toDouble())
        require(capture.getString("feature") == entry.feature)
        require(capture.getString("surface") == entry.surface)
        require(capture.getString("state") == entry.state)
        require(capture.getString("purpose") == entry.purpose)
        require(capture.getString("platform") == entry.platform)
        require(capture.getString("viewport") == entry.viewport)
        if (entry.pullRequest != null) {
            require(capture.getInt("pullRequest") == entry.pullRequest)
        }
        if (entry.issue != null) {
            require(capture.getInt("issue") == entry.issue)
        }

        val imagePath = safeChild(stagedDirectory, entry.fileName)
        require(!Files.isSymbolicLink(imagePath)) {
            "${entry.fileName} must not be a symbolic link."
        }
        require(Files.isRegularFile(imagePath, LinkOption.NOFOLLOW_LINKS)) {
            "${entry.fileName} is missing from the staged capture catalog."
        }
        val bytes = Files.readAllBytes(imagePath)
        require(bytes.size >= pngSignature.size && bytes.copyOf(pngSignature.size).contentEquals(pngSignature)) {
            "${entry.fileName} is not a PNG image."
        }
        val image = Image.makeFromEncoded(bytes)
        try {
            require(image.width == entry.width && image.height == entry.height) {
                "${entry.fileName} dimensions do not match the registry."
            }
        } finally {
            image.close()
        }
        val digest = bytes.sha256Hex()
        require(capture.getString("sha256") == digest) {
            "${entry.fileName} does not match its manifest digest."
        }
    }

    val expectedFiles = buildSet {
        add("capture-manifest.json")
        registry.mapTo(this, MarketingCaptureRegistryEntry::fileName)
        addAll(preservedFileNames)
    }
    val stagedFiles = Files.list(stagedDirectory).use { paths ->
        val files = mutableSetOf<String>()
        paths.forEach { path ->
            val fileName = path.fileName.toString()
            require(!Files.isSymbolicLink(path)) {
                "The staged capture catalog contains a symbolic link: $fileName"
            }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "The staged capture catalog contains a non-regular entry: $fileName"
            }
            require(files.add(fileName)) {
                "The staged capture catalog contains duplicate file names."
            }
        }
        files
    }
    require(stagedFiles == expectedFiles) {
        "The staged capture catalog contains missing or unexpected files."
    }
}

internal fun promoteStagedCaptureDirectory(
    captureDirectory: Path,
    stagedDirectory: Path,
    moveDirectory: (Path, Path) -> Unit = ::moveCaptureDirectory,
) {
    requireDirectoryWithoutSymlinks(stagedDirectory, "Capture staging directory")
    val parent = requireNotNull(captureDirectory.parent) {
        "Capture directory must have a parent."
    }
    Files.createDirectories(parent)
    if (!Files.exists(captureDirectory, LinkOption.NOFOLLOW_LINKS)) {
        try {
            moveDirectory(stagedDirectory, captureDirectory)
        } catch (failure: Throwable) {
            deleteDirectoryTreeIfPresent(captureDirectory, failure)
            throw failure
        }
        return
    }
    requireDirectoryWithoutSymlinks(captureDirectory, "Existing capture directory")

    val transactionDirectory = requireNotNull(stagedDirectory.parent) {
        "Capture staging directory must have a parent."
    }
    val backupDirectory = Files.createTempDirectory(transactionDirectory, "screenshots-backup-")
    Files.delete(backupDirectory)
    try {
        moveDirectory(captureDirectory, backupDirectory)
    } catch (failure: Throwable) {
        restoreBackupAfterFailure(
            captureDirectory = captureDirectory,
            backupDirectory = backupDirectory,
            moveDirectory = moveDirectory,
            failure = failure,
        )
        throw failure
    }

    try {
        moveDirectory(stagedDirectory, captureDirectory)
    } catch (failure: Throwable) {
        restoreBackupAfterFailure(
            captureDirectory = captureDirectory,
            backupDirectory = backupDirectory,
            moveDirectory = moveDirectory,
            failure = failure,
        )
        throw failure
    }
    deleteDirectoryTree(backupDirectory)
}

private fun restoreBackupAfterFailure(
    captureDirectory: Path,
    backupDirectory: Path,
    moveDirectory: (Path, Path) -> Unit,
    failure: Throwable,
) {
    try {
        if (Files.exists(captureDirectory, LinkOption.NOFOLLOW_LINKS)) {
            deleteDirectoryTree(captureDirectory)
        }
        if (Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
            moveDirectory(backupDirectory, captureDirectory)
        }
    } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
    }
}

private fun moveCaptureDirectory(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        val sourceSnapshot = directoryContentSnapshot(source)
        try {
            copyDirectoryTree(source, target)
            require(directoryContentSnapshot(target) == sourceSnapshot) {
                "The copied capture directory does not match its source."
            }
        } catch (failure: Throwable) {
            deleteDirectoryTreeIfPresent(target, failure)
            throw failure
        }
        deleteDirectoryTree(source)
    }
}

private fun copyDirectoryTree(
    source: Path,
    target: Path,
) {
    requireDirectoryWithoutSymlinks(source, "Capture transaction source")
    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        "Capture transaction target already exists."
    }
    Files.walkFileTree(
        source,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                require(!attributes.isSymbolicLink) {
                    "Capture transactions do not follow symbolic links."
                }
                val relative = source.relativize(directory)
                Files.createDirectories(target.resolve(relative))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                    "Capture transactions copy only regular files."
                }
                Files.copy(
                    file,
                    target.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS,
                )
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun directoryContentSnapshot(directory: Path): Map<String, String> {
    requireDirectoryWithoutSymlinks(directory, "Capture transaction directory")
    return Files.walk(directory).use { paths ->
        val snapshot = mutableMapOf<String, String>()
        paths.filter { path -> path != directory }.forEach { path ->
            require(!Files.isSymbolicLink(path)) {
                "Capture transactions do not follow symbolic links."
            }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "Capture transaction directories may contain only regular files."
            }
            snapshot[directory.relativize(path).toString().replace('\\', '/')] =
                Files.readAllBytes(path).sha256Hex()
        }
        snapshot
    }
}

private fun deleteDirectoryTreeIfPresent(
    directory: Path,
    originalFailure: Throwable,
) {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
    try {
        deleteDirectoryTree(directory)
    } catch (cleanupFailure: Throwable) {
        originalFailure.addSuppressed(cleanupFailure)
    }
}

private fun deleteDirectoryTree(directory: Path) {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        directory,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                exception: java.io.IOException?,
            ): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun requireDirectoryWithoutSymlinks(
    directory: Path,
    label: String,
) {
    require(!Files.isSymbolicLink(directory)) {
        "$label must not be a symbolic link."
    }
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        "$label must be a directory."
    }
}

private fun validatePreservedFileNames(fileNames: Set<String>) {
    fileNames.forEach { fileName ->
        require(fileName.matches(preservedCaptureFileName)) {
            "Invalid preserved capture file name: $fileName"
        }
        require(fileName != "capture-manifest.json") {
            "The capture manifest cannot be a preserved file."
        }
    }
}

private fun safeChild(
    directory: Path,
    fileName: String,
): Path {
    require('/' !in fileName && '\\' !in fileName && fileName != "." && fileName != "..") {
        "Capture file names must be plain relative names."
    }
    val normalizedDirectory = directory.toAbsolutePath().normalize()
    val resolved = normalizedDirectory.resolve(fileName).normalize()
    require(resolved.parent == normalizedDirectory) {
        "Capture output escaped its directory."
    }
    return resolved
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
