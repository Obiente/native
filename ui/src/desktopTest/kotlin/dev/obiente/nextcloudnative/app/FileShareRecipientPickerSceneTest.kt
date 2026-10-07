package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileShareRecipientPickerSceneTest {
    @Test
    fun staleSearchCannotSelectARecipientForAnEditedQuery() {
        val fixture = PickerFixture()
        var selected by mutableStateOf("")
        nativeSceneTest(800, 600, content = {
            FileShareRecipientPicker(
                session = session,
                services = fixture.services,
                target = FileShareTarget.Email,
                file = file,
                selectedRecipient = selected,
                enabled = true,
                onSelected = { selected = it?.id.orEmpty() },
            )
        }) {
            replaceText("", OLD_ADDRESS)
            settleUntil { fixture.staleSearch != null }
            val staleSearch = assertNotNull(fixture.staleSearch, "The first search did not start")

            replaceText(OLD_ADDRESS, NEW_ADDRESS)
            // The superseded search ignores cancellation and returns its exact match late.
            staleSearch.resume(exactEmailResponse(OLD_ADDRESS))
            settleUntil { fixture.currentSearches > 0 }
            settle()

            assertEquals("", selected)
        }
    }

    @Test
    fun typedEmailAddressIsConfirmedWithEnterWhenTheServerReturnsNoMatch() {
        val fixture = PickerFixture()
        var selected by mutableStateOf<FileShareRecipient?>(null)
        nativeSceneTest(800, 600, content = {
            FileShareRecipientPicker(
                session = session,
                services = fixture.services,
                target = FileShareTarget.Email,
                file = file,
                selectedRecipient = selected?.id.orEmpty(),
                enabled = true,
                onSelected = { selected = it },
            )
        }) {
            replaceText("", "reader@example")
            assertTrue(has("Enter a complete domain after the @, such as example.com."))
            performImeAction("reader@example")
            assertNull(selected, "An incomplete address must not become a recipient")

            replaceText("reader@example", NEW_ADDRESS)
            settleUntil { fixture.currentSearches > 0 }
            settle()
            assertTrue(has("Typed address"))
            assertTrue(has("Press Enter or select the address to use it."))
            assertNull(selected, "A typed address needs confirmation unless the server confirms it")

            performImeAction(NEW_ADDRESS)
            assertEquals(FileShareRecipient(NEW_ADDRESS, NEW_ADDRESS, FileShareTarget.Email, exact = true), selected)
            assertTrue(has("Selected: $NEW_ADDRESS"))
        }
    }

    @Test
    fun typedEmailAddressCanBeChosenWhileTheSearchIsStillRunning() {
        val fixture = PickerFixture()
        var selected by mutableStateOf<FileShareRecipient?>(null)
        nativeSceneTest(800, 600, content = {
            FileShareRecipientPicker(
                session = session,
                services = fixture.services,
                target = FileShareTarget.Email,
                file = file,
                selectedRecipient = selected?.id.orEmpty(),
                enabled = true,
                onSelected = { selected = it },
            )
        }) {
            replaceText("", OLD_ADDRESS)
            settleUntil { fixture.staleSearch != null }
            val pendingSearch = assertNotNull(fixture.staleSearch, "The search did not start")

            click("Typed address")
            assertEquals(OLD_ADDRESS, selected?.id)
            assertEquals(FileShareTarget.Email, selected?.target)

            // A late result for the same address must not replace or clear the confirmed choice.
            pendingSearch.resume(exactEmailResponse(OLD_ADDRESS))
            settle()
            assertEquals(OLD_ADDRESS, selected?.id)
        }
    }

    /** Holds the first search open without honoring cancellation; later searches find nothing. */
    private class PickerFixture {
        var staleSearch: Continuation<NextcloudApiResponse>? = null
        var currentSearches = 0

        @Suppress("UNCHECKED_CAST")
        val services = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "SyntheticRecipientServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "executeNextcloudApi" -> {
                    val request = arguments!![1] as NextcloudApiRequest
                    val operation: suspend () -> Any? = {
                        if (request.queryParameters["search"] == OLD_ADDRESS) {
                            suspendCoroutine<NextcloudApiResponse> { staleSearch = it }
                        } else {
                            currentSearches++
                            exactEmailResponse(null)
                        }
                    }
                    operation.startCoroutineUninterceptedOrReturn(arguments.last() as Continuation<Any?>)
                }
                else -> error("Unexpected synthetic recipient operation: ${method.name}")
            }
        } as NextcloudPlatformServices
    }

    private companion object {
        const val OLD_ADDRESS = "old@example.test"
        const val NEW_ADDRESS = "new@example.test"
        val session = NextcloudSession("https://fixture.invalid", "synthetic-user", "synthetic-password")
        val file = NextcloudFile("report.md", "report.md", false, "text/markdown", 0L, null, null, false)

        fun exactEmailResponse(address: String?): NextcloudApiResponse {
            val exact = address?.let { """{"label":"Synthetic person","value":{"shareType":4,"shareWith":"$it"}}""" }.orEmpty()
            return NextcloudApiResponse(
                status = 200,
                body = """
                    {"ocs":{"meta":{"status":"ok","statuscode":200},
                    "data":{"exact":{"emails":[$exact]},"emails":[]}}}
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json",
                etag = null,
            )
        }
    }
}
