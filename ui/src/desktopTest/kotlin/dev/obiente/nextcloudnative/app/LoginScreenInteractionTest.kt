package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginScreenInteractionTest {
    @Test
    fun landscapeLoginCanScrollToCancelAndEditTheAddressAgain() {
        val fixture = LoginFixture(LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED"))
        nativeSceneTest(840, 320, fontScale = 1.5f, content = {
            LoginScreen(fixture.services) { error("Synthetic retry must not authenticate") }
        }) {
            replaceText("", "https://cloud.example.test")
            val scroll = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            })
            assertTrue(scroll.boundsInRoot.width <= 460f, "Landscape sign-in must retain a readable form width")
            assertTrue(scroll.boundsInRoot.left > 0f, "The bounded form must remain centered")
            assertTrue(assertNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 1_000f))
            settle()
            click("Connect")
            assertTrue(assertNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 1_000f))
            settle()
            val cancel = assertNotNull(node("Cancel sign-in"))
            assertTrue(cancel.boundsInRoot.top >= 0f)
            assertTrue(cancel.boundsInRoot.bottom <= 320f)
            click("Cancel sign-in")
            replaceText("https://cloud.example.test", "https://replacement.example.test")
            assertEquals(1, fixture.finishes)
        }
    }

    @Test
    fun replacingServicesDisposesTheOldAttemptAndReleasesItsChallenge() {
        val first = LoginFixture(LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED"))
        val second = LoginFixture(LoginPollResult.Pending)
        val activeServices = mutableStateOf(first.services)
        nativeSceneTest(390, 1000, content = {
            LoginScreen(activeServices.value) { error("Synthetic retry must not authenticate") }
        }) {
            replaceText("", "https://cloud.example.test")
            click("Connect")
            assertTrue(has("Retrying connection..."))
            activeServices.value = second.services
            settle()
            assertEquals(1, first.finishes)
            assertTrue(has("Connect"))
            assertFalse(has("Retrying connection..."))
            assertEquals(0, second.begins)
        }
    }

    @Test
    fun networkRetryHasItsOwnLabelAndCancelRestoresAddressEditing() {
        val fixture = LoginFixture(LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED"))
        nativeSceneTest(390, 1000, content = {
            LoginScreen(fixture.services) { error("Synthetic retry must not authenticate") }
        }) {
            replaceText("", "https://cloud.example.test")
            click("Connect")
            assertTrue(has("Retrying connection..."))
            assertFalse(has("Waiting for approval"))
            assertTrue(has("Cancel sign-in"))
            val pendingAddress = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://cloud.example.test"
            })
            assertTrue(pendingAddress.config.contains(SemanticsProperties.Disabled))

            click("Cancel sign-in")
            assertFalse(has("Cancel sign-in"))
            assertFalse(has("Retrying connection..."))
            assertTrue(has("Connect"))
            replaceText("https://cloud.example.test", "https://replacement.example.test")
            val editableAddress = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://replacement.example.test"
            })
            assertFalse(editableAddress.config.contains(SemanticsProperties.Disabled))
            assertEquals(1, fixture.begins)
            assertEquals(1, fixture.browserHandoffs)
            assertEquals(1, fixture.finishes)
        }
    }

    @Test
    fun pendingBrowserApprovalStaysDistinctFromNetworkRetryAndCanBeCancelled() {
        val fixture = LoginFixture(LoginPollResult.Pending)
        nativeSceneTest(390, 1000, content = {
            LoginScreen(fixture.services) { error("Synthetic pending request must not authenticate") }
        }) {
            replaceText("", "https://cloud.example.test")
            click("Connect")
            assertTrue(has("Waiting for approval"))
            assertTrue(has("Finish signing in in your browser, then return here."))
            assertFalse(has("Retrying connection..."))
            click("Cancel sign-in")
            assertFalse(has("Waiting for approval"))
            assertTrue(has("Connect"))
            assertEquals(1, fixture.finishes)
        }
    }

    /** Only sign-in methods are allowed; an unexpected service call fails the synthetic scene. */
    private class LoginFixture(result: LoginPollResult) {
        var begins = 0
        var browserHandoffs = 0
        var finishes = 0
        val services = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "SyntheticLoginServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "trustedServerCertificate" -> null
                "beginLogin" -> {
                    begins++
                    LoginChallenge(
                        enteredServerUrl = "https://cloud.example.test",
                        pollEndpoint = "https://cloud.example.test/login/v2/poll",
                        pollFallbackEndpoint = null,
                        token = "synthetic-token",
                        loginUrl = "https://cloud.example.test/login/v2/flow",
                    )
                }
                "openLoginUrl" -> { browserHandoffs++; Unit }
                "pollLogin" -> result
                "awaitLoginNetworkAvailability" -> Unit
                "finishLoginPolling" -> { finishes++; Unit }
                else -> error("Unexpected synthetic login operation: ${method.name}")
            }
        } as NextcloudPlatformServices
    }
}
