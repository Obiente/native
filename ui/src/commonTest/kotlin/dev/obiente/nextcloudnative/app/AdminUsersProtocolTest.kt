package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun `reads are forced network GETs bound to the exact account`() {
        val page = AdminUserRequests.usersPage("a&b", offset = 25)
        assertEquals(NextcloudApiMethod.GET, page.method)
        assertEquals("/ocs/v2.php/cloud/users/details", page.relativePath)
        assertEquals("a&b", page.queryParameters["search"])
        assertEquals("25", page.queryParameters["offset"])
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, page.cachePolicy)
        assertEquals("/ocs/v2.php/cloud/users/a%20b", AdminUserRequests.user("a b").relativePath)
        assertEquals("/ocs/v2.php/cloud/groups/details", AdminUserRequests.groups().relativePath)
        assertFailsWith<IllegalArgumentException> { AdminUserRequests.user("../alice") }
        assertFailsWith<IllegalArgumentException> { AdminUserRequests.usersPage("", offset = -1) }
    }

    @Test
    fun `controller drops stale search and detail completions`() = runBlocking {
        val pending = mutableMapOf<String, CompletableDeferred<NextcloudApiResponse>>()
        val controller = AdminUsersController(CoroutineScope(Dispatchers.Unconfined)) { request ->
            val key = request.queryParameters["search"] ?: request.relativePath
            CompletableDeferred<NextcloudApiResponse>().also { pending[key] = it }.await()
        }
        controller.search("al")
        val first = pending.getValue("al")
        controller.search("bo")
        first.complete(response(200, ocs("""{"users":{"alice":{"id":"alice","displayname":"Alice"}}}""")))
        pending.getValue("bo").complete(response(200, ocs("""{"users":{"bob":{"id":"bob","displayname":"Bob"}}}""")))
        assertEquals(listOf("bob"), controller.state.users.map { it.id })

        controller.select("bob")
        val detail = pending.getValue("/ocs/v2.php/cloud/users/bob")
        controller.select(null)
        detail.complete(response(200, ocs("""{"id":"bob","displayname":"Bob","enabled":false}""")))
        assertEquals(null, controller.state.selectedUser)
        assertEquals(true, controller.state.users.single().detailsVisible)
    }

    @Test
    fun `forbidden reads explain the permission instead of an empty list`() = runBlocking {
        val controller = AdminUsersController(CoroutineScope(Dispatchers.Unconfined)) { response(403, "") }
        controller.search("")
        assertTrue(controller.state.users.isEmpty())
        assertEquals(AdminReadResult.Forbidden.failureMessage("accounts"), controller.state.listFailure)
    }

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}"""

    private fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
}
