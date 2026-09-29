package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class GroupwareContactsMutationSceneTest {
    @Test
    fun successfulDeleteReconcilesAfterDelayedDurableStoreAndTransport() {
        val fixture = Fixture()
        var blocked = false
        nativeSceneTest(800, 700, content = {
            NativeGroupwareContactsScreen(fixture.services, fixture.session, "alice", {},
                onMutationInProgressChanged = { blocked = it })
        }) {
            click("Synthetic contact")
            click("Delete")
            click("Delete")
            repeat(4) { settle() }
            assertEquals(1, fixture.deletes)
            assertTrue(fixture.verifications >= 1, "Successful deletion must verify authoritative absence")
            assertNull(fixture.recovery)
            assertFalse(blocked)
            assertFalse(has("Synthetic contact"))
            assertFalse(has("Resolve contact recovery"))
        }
    }

    @Test
    fun createThenDeleteWithinObservableParentAndMovableWorkspaceReconcilesBothChanges() {
        val fixture = Fixture().apply { deleted = true }
        val blocked = mutableStateOf(false)
        nativeSceneTest(390, 844, content = {
            val workspace = rememberAppWorkspaceContent(rememberSaveableStateHolder(), "contacts", Screen.Contacts) {
                NativeGroupwareContactsScreen(fixture.services, fixture.session, "alice", {},
                    onMutationInProgressChanged = { blocked.value = it })
            }
            dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell(
                selected = dev.obiente.nextcloudnative.app.design.NextcloudDestination.Apps,
                onSelected = {}, identity = null, activeAppId = "contacts",
                navigationEnabled = !blocked.value, content = workspace,
            )
        }) {
            click("Create contact")
            val name = generateSequence(requireNotNull(node("Name"))) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            assertTrue(name.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Synthetic contact")))
            settle()
            click("Save")
            repeat(4) { settle() }
            assertEquals(1, fixture.creates)
            assertNull(fixture.recovery)
            assertFalse(blocked.value)
            assertFalse(has("Resolve contact recovery"))
            click("Synthetic contact")
            click("Delete")
            click("Delete")
            repeat(4) { settle() }
            assertEquals(1, fixture.deletes)
            assertTrue(fixture.verifications >= 2)
            assertNull(fixture.recovery)
            assertFalse(blocked.value)
            assertFalse(has("Synthetic contact"))
            assertFalse(has("Resolve contact recovery"))
        }
    }

    private class Fixture {
        val session = NextcloudSession("https://contacts-scene.invalid", "alice", "synthetic")
        val home = "/remote.php/dav/addressbooks/users/alice/"
        val book = "${home}contacts/"
        var href = "${book}synthetic.vcf"
        var recovery: String? = null
        var deleted = false
        var creates = 0
        var deletes = 0
        var verifications = 0
        var card = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:synthetic\r\nFN:Synthetic contact\r\nEND:VCARD\r\n"
        @Suppress("UNCHECKED_CAST")
        val services = Proxy.newProxyInstance(NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticContactsServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> {
                    val operation: suspend () -> Any? = {
                        delay(20)
                        when (method.name) {
                            "loadDurableMutationRecovery" -> recovery
                            "saveDurableMutationRecovery" -> { recovery = args!![3] as String; true }
                            "clearDurableMutationRecovery" -> {
                                if (recovery == args!![2]) { recovery = null; true } else false
                            }
                            "executeGroupwareDav" -> execute(args!![1] as GroupwareDavRequest)
                            else -> error("Unexpected synthetic contacts operation: ${method.name}")
                        }
                    }
                    operation.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
                }
            }
        } as NextcloudPlatformServices

        private fun execute(request: GroupwareDavRequest): NextcloudApiResponse {
            if (request.method == "PUT") { creates++; href = request.relativePath; card = requireNotNull(request.body).decodeToString(); deleted = false; return response(201) }
            if (request.method == "DELETE") { deletes++; deleted = true; return response(204) }
            if (request.method == "GET") { verifications++; return response(if (deleted) 404 else 200, card) }
            val body = request.body?.decodeToString().orEmpty()
            val xml = when {
                body.contains("current-user-principal") -> "<d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal>"
                body.contains("addressbook-home-set") -> "<c:addressbook-home-set><d:href>$home</d:href></c:addressbook-home-set>"
                request.relativePath == home -> item(book, "<d:displayname>Contacts</d:displayname><d:resourcetype><d:collection/><c:addressbook/></d:resourcetype><d:current-user-privilege-set><d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set>")
                deleted -> ""
                request.method == "REPORT" -> item(href, "<d:getetag>etag</d:getetag><c:address-data>$card</c:address-data>")
                else -> item(href, "<d:getetag>etag</d:getetag>")
            }
            return response(207, "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\">$xml</d:multistatus>")
        }
        private fun item(href: String, props: String) = "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        private fun response(status: Int, body: String = "") = NextcloudApiResponse(status, body.encodeToByteArray(), "application/xml", "etag")
    }
}
