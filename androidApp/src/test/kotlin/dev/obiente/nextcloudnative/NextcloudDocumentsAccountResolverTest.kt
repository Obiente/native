package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudDocumentsAccountResolverTest {
    private val alice = session("alice")
    private val bob = session("bob")
    private val aliceIncarnation = incarnation("1")
    private val bobIncarnation = incarnation("2")

    @Test
    fun `persisted account document resolves after another account becomes active`() {
        var active = alice
        val resolver = resolver(
            accounts = listOf(alice.accountRecord(), bob.accountRecord()),
            loadSession = { accountId ->
                active = bob
                mapOf(alice.accountId to alice, bob.accountId to bob)[accountId]
            },
        )
        val aliceDocument = NextcloudDocumentIds.documentId(alice, aliceIncarnation, "Documents/report.pdf")

        val resolved = resolver.requireDocument(aliceDocument)

        assertEquals(bob, active)
        assertEquals(alice, resolved.session)
        assertEquals(aliceIncarnation, resolved.reference.incarnation)
        assertEquals("Documents/report.pdf", resolved.reference.path)
    }

    @Test
    fun `missing account fails closed`() {
        val resolver = resolver(listOf(bob.accountRecord()), mapOf(bob.accountId to bob)::get)

        assertFailsWith<IllegalArgumentException> {
            resolver.requireDocument(NextcloudDocumentIds.documentId(alice, aliceIncarnation, "report.pdf"))
        }
    }

    @Test
    fun `wrong or unavailable credential slot fails closed`() {
        val wrongSlot = resolver(listOf(alice.accountRecord()), loadSession = { bob })
        val missingSlot = resolver(listOf(alice.accountRecord()), loadSession = { null })
        val documentId = NextcloudDocumentIds.documentId(alice, aliceIncarnation, "report.pdf")

        assertFailsWith<IllegalArgumentException> { wrongSlot.requireDocument(documentId) }
        assertFailsWith<IllegalArgumentException> { missingSlot.requireDocument(documentId) }
    }

    @Test
    fun `all accounts with exact credential slots and incarnations produce roots`() {
        val sessions = mapOf(alice.accountId to alice, bob.accountId to bob)
        val resolver = resolver(
            accounts = listOf(alice.accountRecord(), bob.accountRecord()),
            loadSession = sessions::get,
        )

        assertEquals(
            listOf(
                ResolvedNextcloudDocumentsAccount(alice, aliceIncarnation),
                ResolvedNextcloudDocumentsAccount(bob, bobIncarnation),
            ),
            resolver.resolvableAccounts(),
        )
        assertEquals(
            ResolvedNextcloudDocumentsAccount(alice, aliceIncarnation),
            resolver.requireRoot(NextcloudDocumentIds.providerRootId(alice, aliceIncarnation)),
        )
        assertEquals(
            ResolvedNextcloudDocumentsAccount(bob, bobIncarnation),
            resolver.requireRoot(NextcloudDocumentIds.providerRootId(bob, bobIncarnation)),
        )
    }

    @Test
    fun `roots omit records without an exact slot or readable incarnation`() {
        val mismatchedAlice = alice.copy(serverUrl = "https://other.example")
        val resolver = resolver(
            accounts = listOf(alice.accountRecord(), bob.accountRecord()),
            loadSession = { accountId ->
                when (accountId) {
                    alice.accountId -> mismatchedAlice
                    bob.accountId -> bob
                    else -> null
                }
            },
            loadIncarnation = { accountIdentity ->
                if (accountIdentity == bob.accountId.storageKey) bobIncarnation else error("unreadable")
            },
        )

        assertEquals(
            listOf(ResolvedNextcloudDocumentsAccount(bob, bobIncarnation)),
            resolver.resolvableAccounts(),
        )
    }

    @Test
    fun `incarnations load by canonical local account identity`() {
        val equivalent = alice.copy(serverUrl = "HTTPS://CLOUD.EXAMPLE:443///")
        var loadedIdentity: String? = null
        val resolver = resolver(
            accounts = listOf(equivalent.accountRecord()),
            loadSession = { equivalent },
            loadIncarnation = { accountIdentity ->
                loadedIdentity = accountIdentity
                aliceIncarnation
            },
        )

        assertEquals(alice.accountId, equivalent.accountId)
        assertEquals(
            listOf(ResolvedNextcloudDocumentsAccount(equivalent, aliceIncarnation)),
            resolver.resolvableAccounts(),
        )
        assertEquals(alice.accountId.storageKey, loadedIdentity)
    }

    @Test
    fun `retained document and root IDs fail after the same account is readded`() {
        val resolver = resolver(
            accounts = listOf(alice.accountRecord()),
            loadSession = { alice },
            loadIncarnation = { incarnation("9") },
        )
        val retainedDocument = NextcloudDocumentIds.documentId(alice, aliceIncarnation, "report.pdf")
        val retainedRoot = NextcloudDocumentIds.providerRootId(alice, aliceIncarnation)

        assertFailsWith<IllegalArgumentException> { resolver.requireDocument(retainedDocument) }
        assertFailsWith<IllegalArgumentException> { resolver.requireRoot(retainedRoot) }
    }

    @Test
    fun `account removal interleaved with exact slot loading fails closed`() {
        var accounts = listOf(alice.accountRecord())
        val resolver = NextcloudDocumentsAccountResolver(
            listAccounts = { accounts },
            loadSession = {
                accounts = emptyList()
                null
            },
            loadIncarnation = { aliceIncarnation },
        )

        assertFailsWith<IllegalArgumentException> {
            resolver.requireDocument(NextcloudDocumentIds.documentId(alice, aliceIncarnation, "report.pdf"))
        }
        assertEquals(emptyList(), resolver.resolvableAccounts())
    }

    private fun resolver(
        accounts: List<NextcloudAccountRecord>,
        loadSession: (NextcloudAccountId) -> NextcloudSession?,
        loadIncarnation: (String) -> NextcloudDocumentIncarnation = { accountIdentity ->
            when (accountIdentity) {
                alice.accountId.storageKey -> aliceIncarnation
                bob.accountId.storageKey -> bobIncarnation
                else -> error("unknown account")
            }
        },
    ) = NextcloudDocumentsAccountResolver({ accounts }, loadSession, loadIncarnation)

    private fun session(loginName: String) = NextcloudSession(
        serverUrl = "https://cloud.example",
        loginName = loginName,
        appPassword = "synthetic-$loginName-password",
    )

    private fun incarnation(digit: String) = NextcloudDocumentIncarnation.Versioned(digit.repeat(32))
}
