package dev.obiente.nextcloudnative.app

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal class DesktopTextEditorDrafts {
    private val root = File(
        System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/state"),
        "nextcloud-native/text-drafts-v1",
    )
    private var cachedKey: SecretKey? = null
    private val storage = JvmTextEditorDraftStorage(root, ::encryptionKey)

    fun bind(
        session: NextcloudSession,
        path: String,
        guard: DesktopAccountOperationGuard,
        accountCredentials: DesktopAccountCredentialPersistence,
    ): TextEditorDraftStore {
        val delegate = storage.bind(session.accountId.storageKey, path)
        return object : TextEditorDraftStore {
            private suspend fun <T> access(action: suspend () -> T): T = guard.withAccountPrivateStatePublication(
                expectedSession = session,
                resolveSession = { accountCredentials.loadSession(session.accountId) },
                unavailable = { error("The account changed before the text draft operation could complete.") },
                publish = action,
            )
            override suspend fun load() = access { delegate.load() }
            override suspend fun save(draft: TextEditorDraft) = access { delegate.save(draft) }
            override suspend fun remove() = access { delegate.remove() }
        }
    }

    fun removeAccount(accountStorageKey: String) = storage.removeAccount(accountStorageKey)

    private fun encryptionKey(): SecretKey = synchronized(KEY_LOCK) {
        cachedKey?.let { return@synchronized it }
        val secrets = defaultDesktopSecretStore()
        val reference = DesktopSecretReference(
            "dev.obiente.nextcloudnative.text-drafts.v1", "nati.ve text draft encryption",
            mapOf("application" to "dev.obiente.nextcloudnative", "purpose" to "text-drafts-v1"),
        )
        fun load(): ByteArray? = secrets.load(reference)?.let { encoded ->
            check(encoded.size <= 64) { "Invalid text draft encryption key." }
            Base64.getDecoder().decode(encoded).also { check(it.size == 32) { "Invalid text draft encryption key." } }
        }
        val key = load() ?: run {
            check(!root.exists() || checkNotNull(root.listFiles()).none { it.name.endsWith(".draft") }) {
                "Text draft encryption key is missing while saved drafts still exist."
            }
            val generated = ByteArray(32).also(SecureRandom()::nextBytes)
            secrets.save(reference, null, Base64.getEncoder().encode(generated))
            check(MessageDigest.isEqual(generated, checkNotNull(load()))) { "Text draft encryption key was not saved." }
            generated
        }
        SecretKeySpec(key, "AES").also { cachedKey = it }
    }

    private companion object {
        val KEY_LOCK = Any()
    }
}
