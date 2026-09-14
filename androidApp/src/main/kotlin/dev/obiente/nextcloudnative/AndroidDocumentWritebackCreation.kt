package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.NextcloudFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal fun createDurableAndroidDocumentWriteback(
    context: android.content.Context?,
    session: NextcloudSession,
    file: NextcloudFile,
    expectedEtag: String,
): AndroidDocumentPendingWriteback {
    val providerContext = requireNotNull(context) { "Provider context is unavailable." }
    val recovery = File(providerContext.filesDir, "documents-recovery").apply { mkdirs() }
    check(recovery.isDirectory) { "Could not prepare document recovery storage." }
    val staging = File.createTempFile("writeback-", ".stage", recovery)
    val manifest = File(recovery, staging.name + ".json")
    try {
        val payload = JSONObject()
            .put("version", 1)
            .put("account", session.accountId.storageKey)
            .put("path", file.path)
            .put("etag", expectedEtag)
            .put("displayName", file.name)
            .put("stage", staging.name)
            .put("startedAt", System.currentTimeMillis())
            .put("ready", false)
            .toString().encodeToByteArray()
        check(payload.size <= 64 * 1024)
        val temporary = File.createTempFile("manifest-", ".tmp", recovery)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
        return AndroidDocumentPendingWriteback(
            staging = staging,
            manifest = manifest,
            accountId = session.accountId.storageKey,
            remotePath = file.path,
            expectedRemoteEtag = expectedEtag,
        )
    } catch (failure: Throwable) {
        staging.delete()
        manifest.delete()
        throw failure
    }
}
