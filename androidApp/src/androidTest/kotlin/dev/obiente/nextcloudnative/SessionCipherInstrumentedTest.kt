package dev.obiente.nextcloudnative

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCipherInstrumentedTest {
    @Test
    fun invalidBase64EnvelopeIsDefinitivelyRejected() {
        val failure = invalidCiphertextFailure("not-base64.invalid")

        assertTrue(failure.cause is IllegalArgumentException)
    }

    @Test
    fun authenticatedCiphertextCorruptionIsDefinitivelyRejected() {
        val cipher = SessionCipher()
        val encrypted = cipher.encrypt("private upload capability")
        val parts = encrypted.split('.', limit = 2)
        val payload = Base64.decode(parts[1], Base64.NO_WRAP).also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
        }
        val corrupted = parts[0] + "." + Base64.encodeToString(payload, Base64.NO_WRAP)

        val failure = invalidCiphertextFailure(corrupted)

        assertTrue(failure.cause is AEADBadTagException)
    }

    private fun invalidCiphertextFailure(value: String): InvalidSessionCiphertextException = try {
        SessionCipher().decrypt(value)
        error("Corrupt ciphertext was accepted.")
    } catch (failure: InvalidSessionCiphertextException) {
        failure
    }
}
