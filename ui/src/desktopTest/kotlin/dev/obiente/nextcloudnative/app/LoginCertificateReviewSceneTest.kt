package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginCertificateReviewSceneTest {
    @Test
    fun certificateReviewAppearsForTheAddressThatWasTried() {
        val fixture = CertificateFixture()
        nativeSceneTest(800, 900, content = { LoginScreen(fixture.services) {} }) {
            replaceText("", OLD_ADDRESS)
            click("Connect")
            settleUntil { fixture.inspection != null }
            assertNotNull(fixture.inspection).resume(review())
            settle()
            assertTrue(has(REVIEW_TITLE))
        }
    }

    @Test
    fun certificateReviewIsDroppedWhenTheAddressChangesDuringInspection() {
        val fixture = CertificateFixture()
        nativeSceneTest(800, 900, content = { LoginScreen(fixture.services) {} }) {
            replaceText("", OLD_ADDRESS)
            click("Connect")
            settleUntil { fixture.inspection != null }
            replaceText(OLD_ADDRESS, NEW_ADDRESS)
            // The old server's certificate must not be offered for the new address.
            assertNotNull(fixture.inspection).resume(review())
            settle()
            assertFalse(has(REVIEW_TITLE))
        }
    }

    /** Sign-in always fails with a certificate error; inspection waits until the test releases it. */
    private class CertificateFixture {
        var inspection: Continuation<ServerCertificateReview?>? = null

        @Suppress("UNCHECKED_CAST")
        val services = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "SyntheticCertificateServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "trustedServerCertificate" -> null
                "beginLogin", "inspectServerCertificateFailure" -> {
                    val operation: suspend () -> Any? = if (method.name == "beginLogin") {
                        { throw IllegalStateException("Synthetic untrusted certificate") }
                    } else {
                        { suspendCoroutine<ServerCertificateReview?> { inspection = it } }
                    }
                    operation.startCoroutineUninterceptedOrReturn(arguments!!.last() as Continuation<Any?>)
                }
                else -> error("Unexpected synthetic login operation: ${method.name}")
            }
        } as NextcloudPlatformServices
    }

    private companion object {
        const val OLD_ADDRESS = "https://old.example.test"
        const val NEW_ADDRESS = "https://new.example.test"
        const val REVIEW_TITLE = "Unverified server certificate"

        fun review() = ServerCertificateReview(
            serverOrigin = OLD_ADDRESS,
            serverDisplayName = "old.example.test",
            subject = "CN=old.example.test",
            issuer = "CN=Synthetic issuer",
            sha256Fingerprint = "00:11:22:33",
            validFrom = "2026-01-01",
            validUntil = "2027-01-01",
        )
    }
}
