package dev.obiente.nextcloudnative

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.JvmTextEditorDraftStorage
import dev.obiente.nextcloudnative.app.TextEditorDraft
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shared envelope using the actual Android Keystore and app-private filesystem. */
@RunWith(AndroidJUnit4::class)
class TextEditorDraftStorageInstrumentedTest {
    @Test
    fun encryptedRecoverySurvivesStoreRecreationAndAccountCleanup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "text-draft-test-").toFile()
        val alias = "dev.obiente.nextcloudnative.test.text-drafts." + UUID.randomUUID()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
            fun newStorage() = JvmTextEditorDraftStorage(root, {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
            })
            val account = "a".repeat(64)
            val otherAccount = "b".repeat(64)
            val draft = TextEditorDraft("synthetic original", "synthetic unsaved edits", "revision", true)
            val stale = newStorage().bind(account, "/example.txt")
            stale.save(draft)
            val saved = root.listFiles()!!.single()
            assertFalse(saved.readBytes().decodeToString().contains(draft.text))
            assertEquals(draft, newStorage().bind(account, "/example.txt").load())
            assertNull(newStorage().bind(otherAccount, "/example.txt").load())
            newStorage().bind(otherAccount, "/example.txt").save(draft.copy(text = "other account"))
            val retainedPath = "nextcloud-native:rule-operation:pending-test"
            val orphanPath = "nextcloud-native:rule-operation:resolved-test"
            val recovery = TextEditorDraft("", "synthetic recovery", null, true)
            newStorage().bind(account, retainedPath).save(recovery)
            newStorage().bind(account, orphanPath).save(recovery)
            assertEquals(1, stale.pruneUnreferenced(setOf(retainedPath)) {
                if (it == recovery) orphanPath else null
            })
            assertNull(newStorage().bind(account, orphanPath).load())
            assertEquals(recovery, newStorage().bind(account, retainedPath).load())
            assertEquals(draft, stale.load())
            assertEquals("other account", newStorage().bind(otherAccount, "/example.txt").load()!!.text)
            newStorage().removeAccount(account)
            assertNull(newStorage().bind(account, "/example.txt").load())
            assertEquals("other account", newStorage().bind(otherAccount, "/example.txt").load()!!.text)
            var rejected = false
            try {
                stale.save(draft)
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue("Retired producers must not recreate the draft", rejected)
        } finally {
            root.deleteRecursively()
            keyStore.deleteEntry(alias)
        }
    }
}
