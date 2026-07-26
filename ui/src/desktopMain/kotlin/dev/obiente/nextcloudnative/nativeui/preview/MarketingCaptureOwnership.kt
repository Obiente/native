package dev.obiente.nextcloudnative.nativeui.preview

import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject

private val captureFileName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*\\.png")

internal fun declaredCaptureFiles(manifestPath: Path): Set<String> {
    if (!Files.isRegularFile(manifestPath)) return emptySet()
    val manifest = JSONObject(Files.readString(manifestPath))
    val captures = manifest.optJSONArray("captures") ?: return emptySet()
    return buildSet {
        for (index in 0 until captures.length()) {
            val fileName = captures.optJSONObject(index)?.optString("file").orEmpty()
            if (fileName.matches(captureFileName)) add(fileName)
        }
    }
}

internal fun obsoleteDeclaredCaptureFiles(
    previouslyDeclared: Set<String>,
    expected: Set<String>,
): Set<String> = previouslyDeclared - expected

internal fun removeObsoleteDeclaredCaptureFiles(
    captureDirectory: Path,
    manifestPath: Path,
    expected: Set<String>,
) {
    obsoleteDeclaredCaptureFiles(
        previouslyDeclared = declaredCaptureFiles(manifestPath),
        expected = expected,
    ).forEach { fileName ->
        Files.deleteIfExists(captureDirectory.resolve(fileName))
    }
}
