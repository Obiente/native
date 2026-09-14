package dev.obiente.nextcloudnative

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class InvalidSessionCiphertextException(
    message: String,
    cause: Throwable? = null,
) : GeneralSecurityException(message, cause)

class SessionCipher {
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(SEPARATOR) {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    fun decrypt(value: String): String {
        val (iv, encrypted) = decodeEnvelope(value)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (failure: InvalidAlgorithmParameterException) {
            throw InvalidSessionCiphertextException("Invalid encrypted session envelope.", failure)
        } catch (failure: KeyPermanentlyInvalidatedException) {
            throw InvalidSessionCiphertextException("Encrypted session key is no longer valid.", failure)
        } catch (failure: AEADBadTagException) {
            throw InvalidSessionCiphertextException("Encrypted session authentication failed.", failure)
        }
    }

    private fun decodeEnvelope(value: String): Pair<ByteArray, ByteArray> = try {
        val parts = value.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid encrypted session." }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES) { "Invalid encrypted session IV." }
        require(encrypted.size >= GCM_TAG_BYTES) { "Invalid encrypted session payload." }
        iv to encrypted
    } catch (failure: IllegalArgumentException) {
        throw InvalidSessionCiphertextException("Invalid encrypted session envelope.", failure)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.obiente.nextcloudnative.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SEPARATOR = "."
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
    }
}
