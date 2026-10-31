package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdminUsersProtocolTest {
    @Test
    fun `user page parses details and keeps id-only entries read-only`() {
        val page = assertIs<AdminReadResult.Loaded<AdminUserPage>>(
            parseAdminUserPage(
                response(200, ocs("""{"users":{
                    "alice":{"id":"alice","displayname":"Alice","email":"alice@example.test","enabled":true,
                      "groups":["staff","admin"],"subadmin":["staff"],"quota":{"used":10,"quota":1073741824},"lastLogin":1700000000000},
                    "ldap-ghost":{"id":"ldap-ghost"}
                }}""")),
                offset = 0,
                requestedLimit = 2,
            ),
        ).value
        assertEquals(listOf("alice", "ldap-ghost"), page.users.map { it.id })
        val alice = page.users.first()
        assertEquals(true, alice.enabled)
        assertEquals(listOf("staff"), alice.subadminGroups)
        assertEquals(1_073_741_824L, alice.quota?.quotaBytes)
        assertFalse(page.users.last().detailsVisible)
        assertTrue(page.mayHaveMore)
    }

    @Test
    fun `empty, forbidden, and malformed pages are distinct`() {
        val empty = assertIs<AdminReadResult.Loaded<AdminUserPage>>(parseAdminUserPage(response(200, ocs("""{"users":[]}""")), 0)).value
        assertTrue(empty.users.isEmpty())
        assertFalse(empty.mayHaveMore)
        assertEquals(AdminReadResult.Forbidden, parseAdminUserPage(response(403, ""), 0))
        assertEquals(AdminReadResult.Malformed, parseAdminUserPage(response(200, ocs("""{"users":["alice"]}""")), 0))
        assertEquals(AdminReadResult.Malformed, parseAdminUserPage(response(200, "<html>"), 0))
        // A record whose ID disagrees with its key is not trusted.
        val mismatched = assertIs<AdminReadResult.Loaded<AdminUserPage>>(
            parseAdminUserPage(response(200, ocs("""{"users":{"alice":{"id":"mallory"}}}""")), 0),
        ).value
        assertTrue(mismatched.users.isEmpty())
    }

    @Test
    fun `groups parse counts and reject invalid IDs`() {
        val groups = assertIs<AdminReadResult.Loaded<List<AdminGroup>>>(
            parseAdminGroups(response(200, ocs("""{"groups":[
                {"id":"staff","displayname":"Staff","usercount":4,"disabled":1,"canAdd":true,"canRemove":true},
                {"id":"","displayname":"Broken"},
                {"id":"staff","displayname":"Duplicate"}
            ]}"""))),
        ).value
        assertEquals(1, groups.size)
        assertEquals(4, groups.single().userCount)
        assertEquals(1, groups.single().disabledCount)
    }

    @Test
    fun `mutations map to exact provisioning routes`() {
        fun plan(mutation: AdminUserMutation) = AdminUserRequests.mutation(mutation)
        plan(AdminUserMutation.SetEnabled("a b", false)).let {
            assertEquals(NextcloudApiMethod.PUT, it.method)
            assertEquals("/ocs/v2.php/cloud/users/a%20b/disable", it.relativePath)
        }
        plan(AdminUserMutation.AddToGroup("alice", "staff")).let {
            assertEquals(NextcloudApiMethod.POST, it.method)
            assertEquals("groupid=staff", it.body?.decodeToString())
        }
        plan(AdminUserMutation.RemoveFromGroup("alice", "staff")).let {
            assertEquals(NextcloudApiMethod.DELETE, it.method)
            assertEquals("staff", it.queryParameters["groupid"])
            assertEquals("/ocs/v2.php/cloud/users/alice/groups", it.relativePath)
        }
        plan(AdminUserMutation.SetQuota("alice", AdminQuotaPreset.Unlimited)).let {
            assertEquals("key=quota&value=none", it.body?.decodeToString())
        }
        plan(AdminUserMutation.Delete("alice")).let {
            assertEquals(NextcloudApiMethod.DELETE, it.method)
            assertEquals("/ocs/v2.php/cloud/users/alice", it.relativePath)
        }
        assertFailsWith<IllegalArgumentException> { plan(AdminUserMutation.SetEnabled("../alice", true)) }
        assertFailsWith<IllegalArgumentException> { plan(AdminUserMutation.SetEmail("alice", "not-an-email")) }
    }

    @Test
    fun `new accounts need a password or a welcome email and never print the password`() {
        val withPassword = AdminNewUser("new.user", "New User", "", "s3cret-pass", listOf("staff"), AdminQuotaPreset.FiveGigabytes)
        val body = AdminUserRequests.createUser(withPassword).body?.decodeToString().orEmpty()
        assertTrue(body.startsWith("userid=new.user&password="))
        assertTrue("groups%5B%5D=staff" in body)
        assertTrue("quota=5%20GB" in body)
        assertFalse("s3cret" in withPassword.toString())
        assertFailsWith<IllegalArgumentException> { AdminUserRequests.createUser(withPassword.copy(password = "", email = "")) }
        AdminUserRequests.createUser(withPassword.copy(password = "", email = "new@example.test"))
        assertFailsWith<IllegalArgumentException> { AdminUserRequests.createUser(withPassword.copy(userId = "bad/id")) }
        assertFailsWith<IllegalArgumentException> { AdminUserRequests.createUser(withPassword.copy(userId = " padded")) }
    }

    @Test
    fun `group deletion refuses the admin group`() {
        assertFailsWith<IllegalArgumentException> { AdminUserRequests.groupMutation(AdminGroupMutation.Delete("admin")) }
        assertEquals(
            "/ocs/v2.php/cloud/groups/team%20a",
            AdminUserRequests.groupMutation(AdminGroupMutation.Delete("team a")).relativePath,
        )
    }

    @Test
    fun `postconditions and self-protection`() {
        val user = AdminUser("bob", "Bob", null, enabled = false, groups = listOf("staff"), subadminGroups = emptyList(),
            quota = AccountQuota(0, null, null, 5 * 1_073_741_824L), lastLoginEpochMillis = null, backend = "Database")
        assertTrue(user.reflects(AdminUserMutation.SetEnabled("bob", false)))
        assertFalse(user.reflects(AdminUserMutation.SetEnabled("bob", true)))
        assertTrue(user.reflects(AdminUserMutation.RemoveFromGroup("bob", "other")))
        assertTrue(user.reflects(AdminUserMutation.SetQuota("bob", AdminQuotaPreset.FiveGigabytes)))
        assertFalse(user.reflects(AdminUserMutation.SetQuota("bob", AdminQuotaPreset.TenGigabytes)))
        assertFalse(user.reflects(AdminUserMutation.AddToGroup("someone-else", "staff")))
        assertFalse(adminUserActionsAllowed(user, signedInUserId = "bob"))
        assertTrue(adminUserActionsAllowed(user, signedInUserId = "admin"))
        assertFalse(adminUserActionsAllowed(user.copy(detailsVisible = false), signedInUserId = "admin"))
    }

    @Test
    fun `app lifecycle sends the reviewed plan and verifies the new catalog state`() = runBlocking {
        val app = NativeManagedApp("sample_app", "Sample", installedVersion = "1.0.0", installed = true, enabled = true)
        val catalog = NativeAppCatalog(NativeAppCatalogContract.ProvisioningOcsV1, listOf(app), true, false, false)
        val session = NextcloudSession("https://cloud.example.test", "admin", "app-token")
        val sent = mutableListOf<NextcloudApiRequest>()
        val transport = AccountPasswordConfirmedTransport { _, request, _ ->
            sent += request
            PasswordConfirmedApiResult.Delivered(response(200, """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":[]}}"""))
        }
        val secret = AccountPasswordConfirmationSecret("pw".toCharArray())
        val outcome = runNativeAppLifecycle(transport, session, catalog, app, NativeAppLifecycleAction.Disable, secret) {
            NativeAppCatalogResult.Available(catalog.copy(apps = listOf(app.copy(enabled = false))))
        }
        assertEquals(ConfirmedWriteOutcome.Applied, outcome)
        assertTrue(secret.isCleared)
        assertEquals(NextcloudApiMethod.DELETE, sent.single().method)
        assertEquals("/ocs/v1.php/cloud/apps/sample_app", sent.single().relativePath)

        val stale = runNativeAppLifecycle(transport, session, catalog, app.copy(installedVersion = "2.0.0"),
            NativeAppLifecycleAction.Disable, AccountPasswordConfirmationSecret("pw".toCharArray())) { error("not read") }
        assertEquals(ConfirmedWriteRejection.Invalid, (stale as ConfirmedWriteOutcome.Rejected).reason)
        assertEquals(1, sent.size)

        val unchanged = runNativeAppLifecycle(transport, session, catalog, app, NativeAppLifecycleAction.Disable,
            AccountPasswordConfirmationSecret("pw".toCharArray())) { NativeAppCatalogResult.Available(catalog) }
        assertEquals(ConfirmedWriteOutcome.Unverified, unchanged)
    }

    @Test
    fun `lifecycle postconditions`() {
        val before = NativeManagedApp("a", "A", installedVersion = "1", availableVersion = "2", installed = true, enabled = true)
        assertTrue(nativeAppLifecycleApplied(before, NativeAppLifecycleAction.Update, before.copy(installedVersion = "2", availableVersion = null)))
        assertFalse(nativeAppLifecycleApplied(before, NativeAppLifecycleAction.Update, before))
        assertTrue(nativeAppLifecycleApplied(before, NativeAppLifecycleAction.Uninstall, null))
        assertTrue(nativeAppLifecycleApplied(before, NativeAppLifecycleAction.Uninstall, before.copy(installed = false, enabled = false)))
        assertFalse(nativeAppLifecycleApplied(before, NativeAppLifecycleAction.Enable, before.copy(enabled = false)))
        assertEquals(NativeAdminAuthorization.AccountPasswordOnRequest, nativeAppRequiredAuthorization(NativeAppLifecycleAction.Enable))
    }

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}"""

    private fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
}
