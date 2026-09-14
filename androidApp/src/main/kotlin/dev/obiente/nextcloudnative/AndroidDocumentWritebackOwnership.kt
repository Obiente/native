package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal fun legacyAndroidDocumentWritebackOwners(context: Context, session: NextcloudSession): Set<String> {
    val store = AndroidDocumentProviderIncarnationStore(context)
    val incarnation = store.retiredIncarnation(session.accountId.storageKey)
        ?: store.activeIncarnation(session.accountId.storageKey)
    return AndroidDocumentLegacyAliases(context).readVerified(session.accountId.storageKey, incarnation) +
        NextcloudDocumentIds.accountKey(session)
}

/** Migrates only a manifest whose old owner is verified for this account incarnation. */
internal fun parseOwnedAndroidDocumentWriteback(
    root: File,
    manifest: File,
    accountIdentity: String,
    legacyOwners: Set<String>,
): AndroidDocumentPendingWriteback? {
    val pending = parseAndroidDocumentWriteback(root, manifest, expectedAccount = null) ?: return null
    if (pending.accountId == accountIdentity) return pending
    if (pending.accountId !in legacyOwners) return null
    val payload = JSONObject(manifest.readText()).put("account", accountIdentity).toString().encodeToByteArray()
    require(payload.size <= 64 * 1024)
    val temporary = File.createTempFile("manifest-", ".tmp", root)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
    return pending.copy(accountId = accountIdentity)
}
