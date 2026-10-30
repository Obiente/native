package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contacts list and details at phone, tablet, and desktop widths, over a synthetic CardDAV server. */
class ContactsWorkspaceLayoutTest {
    @Test
    fun phoneOpensContactDetailsInASheetOverTheList() {
        nativeSceneTest(390, 844, content = { Contacts() }) {
            settleUntil(TIMEOUT) { has("Ada Lovelace") }
            click("Ada Lovelace")
            settleUntil { has("Close contact") }
            assertTrue(has("Met at the analytical engine demo"), "The sheet shows the contact's details")
            assertTrue(has("Grace Hopper"), "The list stays behind the sheet")
            settleAt("Close contact")
            click("Close contact")
            settleUntil { !has("Close contact") }
            assertFalse(has("Met at the analytical engine demo"))
        }
    }

    @Test
    fun phoneSheetReopensOnTheSameContactAfterRecreation() {
        val composed = mutableStateOf(true)
        nativeSceneTest(390, 844, content = {
            val holder = rememberSaveableStateHolder()
            if (composed.value) holder.SaveableStateProvider("contacts") { Contacts() }
        }) {
            settleUntil(TIMEOUT) { has("Ada Lovelace") }
            click("Ada Lovelace")
            settleUntil { has("Close contact") }
            composed.value = false
            settleUntil { !has("Close contact") }
            composed.value = true
            settleUntil(TIMEOUT) { has("Close contact") && has("Met at the analytical engine demo") }
            assertTrue(has("Close contact"), "The saved selection reopens the same contact")
        }
    }

    @Test
    fun tabletShowsTheInspectorBesideTheListInsteadOfASheet() {
        nativeSceneTest(1000, 800, content = { Contacts() }) {
            settleUntil(TIMEOUT) { has("Ada Lovelace") }
            click("Ada Lovelace")
            settleUntil { has("Met at the analytical engine demo") }
            assertFalse(has("Close contact"), "Wide layouts use the inspector, not a sheet")
            assertTrue(has("Grace Hopper"))
            assertTrue(has("Edit"))
            click("Grace Hopper")
            settleUntil { has("Compiler pioneer") }
            assertFalse(has("Met at the analytical engine demo"), "Choosing another contact replaces the inspector")
        }
    }

    @Test
    fun desktopFiltersByAddressBookAndKeepsTheInspector() {
        nativeSceneTest(1280, 800, content = { Desktop { Contacts() } }) {
            settleUntil(TIMEOUT) { has("Ada Lovelace") && has("Work") }
            assertTrue(has("All contacts"), "Address books appear as filters")
            click("Open destination Work")
            settleUntil { !has("Ada Lovelace") }
            assertTrue(has("Grace Hopper"), "The Work filter keeps its contacts")
            click("Grace Hopper")
            settleUntil { has("Compiler pioneer") }
            assertFalse(has("Close contact"))
            click("Open destination All contacts")
            settleUntil { has("Ada Lovelace") }
            assertTrue(has("Compiler pioneer"), "Changing the filter keeps the selected contact open")
        }
    }

    @Test
    fun narrowDesktopKeepsTheDetailsDialog() {
        nativeSceneTest(700, 800, content = { Desktop { Contacts() } }) {
            settleUntil(TIMEOUT) { has("Ada Lovelace") }
            click("Ada Lovelace")
            settleUntil { has("Met at the analytical engine demo") }
            assertFalse(has("Close contact"), "Narrow desktop windows use a dialog, not a phone sheet")
            assertTrue(has("Edit") && has("Delete"))
        }
    }

    @Composable
    private fun Contacts() {
        NativeGroupwareContactsScreen(fixtureServices, session, "alice", onBack = {})
    }

    @Composable
    private fun Desktop(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                isDesktop = true,
                usesDenseControls = true,
                supportsAuxiliaryPane = true,
            ),
            content = content,
        )
    }

    private val session = NextcloudSession("https://contacts-layout.invalid", "alice", "synthetic")
    private val home = "/remote.php/dav/addressbooks/users/alice/"
    private val personal = "${home}personal/"
    private val work = "${home}work/"
    private val cards = mapOf(
        personal to listOf("${personal}ada.vcf" to card("ada", "Ada Lovelace", "EMAIL:ada@example.invalid\r\nNOTE:Met at the analytical engine demo")),
        work to listOf("${work}grace.vcf" to card("grace", "Grace Hopper", "TEL:+1 555 0100\r\nNOTE:Compiler pioneer")),
    )

    private val fixtureServices = Proxy.newProxyInstance(
        NextcloudPlatformServices::class.java.classLoader,
        arrayOf(NextcloudPlatformServices::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "SyntheticContactsLayoutServices"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.singleOrNull()
            else -> {
                val operation: suspend () -> Any? = {
                    when (method.name) {
                        "loadDurableMutationRecovery" -> null
                        "executeGroupwareDav" -> execute(args!![1] as GroupwareDavRequest)
                        else -> error("Unexpected synthetic contacts operation: ${method.name}")
                    }
                }
                @Suppress("UNCHECKED_CAST")
                operation.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
            }
        }
    } as NextcloudPlatformServices

    private fun execute(request: GroupwareDavRequest): NextcloudApiResponse {
        val body = request.body?.decodeToString().orEmpty()
        val book = cards.keys.firstOrNull { it == request.relativePath }
        val xml = when {
            body.contains("current-user-principal") ->
                "<d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal>"
            body.contains("addressbook-home-set") ->
                "<c:addressbook-home-set><d:href>$home</d:href></c:addressbook-home-set>"
            request.relativePath == home -> addressBook(personal, "Personal") + addressBook(work, "Work")
            book != null && request.method == "REPORT" -> cards.getValue(book).joinToString("") { (href, vcard) ->
                item(href, "<d:getetag>etag</d:getetag><c:address-data>$vcard</c:address-data>")
            }
            book != null -> cards.getValue(book).joinToString("") { (href, _) -> item(href, "<d:getetag>etag</d:getetag>") }
            else -> error("Unexpected synthetic CardDAV request: ${request.method} ${request.relativePath}")
        }
        return NextcloudApiResponse(
            207,
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\">$xml</d:multistatus>".encodeToByteArray(),
            "application/xml",
            "etag",
        )
    }

    private fun addressBook(href: String, name: String) = item(
        href,
        "<d:displayname>$name</d:displayname><d:resourcetype><d:collection/><c:addressbook/></d:resourcetype>" +
            "<d:current-user-privilege-set><d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set>",
    )

    private fun item(href: String, props: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun card(uid: String, name: String, extra: String) =
        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$uid\r\nFN:$name\r\n$extra\r\nEND:VCARD\r\n"

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
