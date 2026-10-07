package dev.obiente.nextcloudnative

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.obiente.nextcloudnative.app.JvmTextEditorDraftStorage
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.TextEditorDraft
import dev.obiente.nextcloudnative.app.TextEditorDraftStore
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidTextEditorDrafts(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "text-drafts-v1")
    private var cachedKey: SecretKey? = null
    private val storage = JvmTextEditorDraftStorage(root, ::encryptionKey)

    fun bind(
        session: NextcloudSession,
        path: String,
        accountCredentials: AndroidAccountCredentialController,
    ): TextEditorDraftStore {
        val delegate = storage.bind(session.accountId.storageKey, path)
        return object : TextEditorDraftStore {
            private suspend fun <T> access(action: suspend () -> T): T = withAndroidAccountPrivateStatePublication(
                expectedSession = session,
                credentialMutationMutex = ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX,
                guard = ANDROID_ACCOUNT_OPERATION_GUARD,
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
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            check(!root.exists() || checkNotNull(root.listFiles()).none { it.name.endsWith(".draft") }) {
                "Text draft encryption key is missing while saved drafts still exist."
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
        key.also { cachedKey = it }
    }

    private companion object {
        const val KEY_ALIAS = "dev.obiente.nextcloudnative.text-drafts.v1"
        val KEY_LOCK = Any()
    }
}
