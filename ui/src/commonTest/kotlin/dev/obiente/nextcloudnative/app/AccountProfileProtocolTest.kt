package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountProfileProtocolTest {
    @Test
    fun `profile parses values, scopes, and server-editable fields`() {
        val profile = loaded(profileJson(), fieldsJson("displayname", "phone", "biography"))
        assertEquals("alice", profile.userId)
        val name = assertNotNullValue(profile.value(AccountProfileField.DisplayName))
        assertEquals("Alice Example", name.value)
        assertEquals(AccountPropertyScope.Federated, name.scope)
        assertTrue(name.editable)
        val email = assertNotNullValue(profile.value(AccountProfileField.Email))
        assertFalse(email.editable)
        // Legacy scope names still map onto the current scope model.
        assertEquals(AccountPropertyScope.Local, profile.value(AccountProfileField.Phone)?.scope)
        assertEquals(listOf("team@example.test"), profile.additionalEmails.map { it.address })
        assertEquals(AccountPropertyScope.Private, profile.additionalEmails.single().scope)
        assertEquals(true, profile.profileEnabled)
        assertEquals(listOf("staff"), profile.groups)
        assertEquals(1_024L, profile.quota?.usedBytes)
    }

    @Test
    fun `without the editable-field list every value stays read-only`() {
        val profile = loaded(profileJson(), null)
        assertTrue(profile.values.none { it.editable })
        assertFalse(profile.additionalEmailsEditable)
    }

    @Test
    fun `missing, unauthorized, and malformed responses are distinct`() {
        assertEquals(AccountProfileLoadResult.Unauthorized, parseAccountProfile(response(401, ""), null))
        assertEquals(AccountProfileLoadResult.Unavailable, parseAccountProfile(response(503, ""), null))
        assertEquals(AccountProfileLoadResult.Malformed, parseAccountProfile(response(200, "not json"), null))
        assertEquals(AccountProfileLoadResult.Malformed, parseAccountProfile(response(200, ocs("""{"displayname":"x"}""")), null))
        assertEquals(AccountProfileLoadResult.Malformed, parseAccountProfile(response(200, ocs("""{"id":"../x"}""")), null))
    }

    @Test
    fun `edits contain only changed editable values, then changed scopes`() {
        val profile = loaded(profileJson(), fieldsJson("displayname", "phone"))
        val edits = accountProfileEdits(
            profile,
            draftValues = mapOf(
                AccountProfileField.DisplayName to "  Alice Example  ",
                AccountProfileField.Phone to "+1 555 0199",
                AccountProfileField.Email to "changed@example.test",
            ),
            draftScopes = mapOf(AccountProfileField.DisplayName to AccountPropertyScope.Local),
        )
        assertEquals(
            listOf<AccountProfileEdit>(
                AccountProfileEdit.Value(AccountProfileField.Phone, "+1 555 0199"),
                AccountProfileEdit.Scope(AccountProfileField.DisplayName, AccountPropertyScope.Local),
            ),
            edits,
        )
    }

    @Test
    fun `edit request targets the exact account through OCS v2`() {
        val request = AccountProfileRequests.edit("alice smith", AccountProfileEdit.Scope(AccountProfileField.Phone, AccountPropertyScope.Private))
        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/ocs/v2.php/cloud/users/alice%20smith", request.relativePath)
        assertEquals("key=phoneScope&value=v2-private", request.body?.decodeToString())
        assertFailsWith<IllegalArgumentException> {
            AccountProfileRequests.edit("alice", AccountProfileEdit.Value(AccountProfileField.Phone, "1\u0000"))
        }
        AccountProfileRequests.edit("alice", AccountProfileEdit.Value(AccountProfileField.Biography, "line one\nline two"))
        assertFailsWith<IllegalArgumentException> { AccountProfileRequests.edit("a/b", AccountProfileEdit.ProfileEnabled(true)) }
    }

    @Test
    fun `postcondition check uses the fresh profile`() {
        val profile = loaded(profileJson(), null)
        assertTrue(profile.reflects(AccountProfileEdit.Value(AccountProfileField.DisplayName, "Alice Example")))
        assertFalse(profile.reflects(AccountProfileEdit.Value(AccountProfileField.DisplayName, "Someone else")))
        assertTrue(profile.reflects(AccountProfileEdit.Value(AccountProfileField.Email, "ALICE@example.test")))
        assertTrue(profile.reflects(AccountProfileEdit.Scope(AccountProfileField.DisplayName, AccountPropertyScope.Federated)))
        assertTrue(profile.reflects(AccountProfileEdit.ProfileEnabled(true)))
    }

    @Test
    fun `batch save stops after a rejected password and reports what was not sent`() = runBlocking {
        val session = NextcloudSession("https://cloud.example.test", "alice", "app-token")
        var phone = "+1 555 0100"
        val controller = AccountProfileController(
            CoroutineScope(Dispatchers.Unconfined), session,
            execute = { request ->
                if (request.relativePath.endsWith("/fields")) response(200, fieldsJson("phone", "website"))
                else response(200, profileJson(phone = phone))
            },
            transport = null,
        )
        var sends = 0
        val transport = AccountPasswordConfirmedTransport { _, request, _ ->
            sends++
            if (sends == 1) {
                phone = "+1 555 0199"
                PasswordConfirmedApiResult.Delivered(response(200, ocs("[]")))
            } else {
                PasswordConfirmedApiResult.Delivered(response(401, ""))
            }.also { assertTrue(request.relativePath.endsWith("/alice")) }
        }
        val edits = listOf(
            AccountProfileEdit.Value(AccountProfileField.Phone, "+1 555 0199"),
            AccountProfileEdit.Value(AccountProfileField.Website, "https://example.test"),
            AccountProfileEdit.Scope(AccountProfileField.Phone, AccountPropertyScope.Private),
        )
        val report = controller.applyAccountProfileEdits(transport, "alice", edits, AccountPasswordConfirmationSecret("pw".toCharArray()))
        assertEquals(listOf(edits[0]), report.applied)
        assertEquals(edits[1], report.failed.single().first)
        assertEquals(listOf(edits[2]), report.skipped)
        assertEquals(2, sends)
        assertTrue(report.failureSummary().orEmpty().contains("1 change(s) were saved"))
    }

    private fun loaded(profile: String, fields: String?): AccountProfile = assertIs<AccountProfileLoadResult.Loaded>(
        parseAccountProfile(response(200, profile), fields?.let { response(200, it) }),
    ).profile

    private fun <T> assertNotNullValue(value: T?): T {
        assertTrue(value != null)
        return value
    }

    private fun profileJson(phone: String = "+1 555 0100") = ocs(
        """
        {"id":"alice","displayname":"Alice Example","displaynameScope":"v2-federated",
         "email":"alice@example.test","emailScope":"v2-local","phone":"$phone","phoneScope":"contacts",
         "website":"","websiteScope":"v2-local","biography":"","biographyScope":"v2-local",
         "additional_mail":["team@example.test"],"additional_mailScope":["v2-private"],
         "profile_enabled":"1","language":"en","locale":"en_US","groups":["staff"],
         "quota":{"used":1024,"total":2048,"relative":50,"quota":-3}}
        """.trimIndent(),
    )

    private fun fieldsJson(vararg keys: String) = ocs(keys.joinToString(",", "[", "]") { "\"$it\"" })

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}"""

    private fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)

    @Test
    fun `unknown scope stays unknown`() {
        assertNull(AccountPropertyScope.fromWire("v9-unknown"))
    }
}
