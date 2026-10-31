package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountProfileProtocolTest {
    @Test
    fun `profile parses values and visibility scopes`() {
        val profile = loaded(profileJson())
        assertEquals("alice", profile.userId)
        val name = assertNotNull(profile.value(AccountProfileField.DisplayName))
        assertEquals("Alice Example", name.value)
        assertEquals(AccountPropertyScope.Federated, name.scope)
        // Legacy scope names still map onto the current scope model.
        assertEquals(AccountPropertyScope.Local, profile.value(AccountProfileField.Phone)?.scope)
        assertEquals(listOf("team@example.test"), profile.additionalEmails.map { it.address })
        assertEquals(AccountPropertyScope.Private, profile.additionalEmails.single().scope)
        assertEquals(true, profile.profileEnabled)
        assertEquals(listOf("staff"), profile.groups)
        assertEquals(1_024L, profile.quota?.usedBytes)
        // Negative server quota values mean unlimited or not computed, not a byte count.
        assertNull(profile.quota?.quotaBytes)
    }

    @Test
    fun `values are bounded and absent fields are omitted`() {
        val long = "x".repeat(200)
        val profile = loaded(ocs("""{"id":"alice","displayname":"$long","phone":null}"""))
        assertEquals(64, profile.value(AccountProfileField.DisplayName)?.value?.length)
        assertEquals("", profile.value(AccountProfileField.Phone)?.value)
        assertNull(profile.value(AccountProfileField.Biography))
    }

    @Test
    fun `missing, unauthorized, and malformed responses are distinct`() {
        assertEquals(AccountProfileLoadResult.Unauthorized, parseAccountProfile(response(401, "")))
        assertEquals(AccountProfileLoadResult.Unavailable, parseAccountProfile(response(503, "")))
        assertEquals(AccountProfileLoadResult.Malformed, parseAccountProfile(response(200, "not json")))
        assertEquals(AccountProfileLoadResult.Malformed, parseAccountProfile(response(200, ocs("""{"displayname":"x"}"""))))
        assertEquals(AccountProfileLoadResult.Malformed, parseAccountProfile(response(200, ocs("""{"id":"../x"}"""))))
        assertEquals(
            AccountProfileLoadResult.Malformed,
            parseAccountProfile(response(200, """{"ocs":{"meta":{"status":"failure","statuscode":997},"data":{"id":"alice"}}}""")),
        )
    }

    @Test
    fun `profile read is a forced network GET of the signed-in account`() {
        val request = AccountProfileRequests.currentUser()
        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/ocs/v2.php/cloud/user", request.relativePath)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, request.cachePolicy)
        assertTrue(request.ocsApiRequest)
    }

    @Test
    fun `controller keeps the last profile when a refresh fails and drops stale completions`() = runBlocking {
        val responses = ArrayDeque<CompletableDeferred<NextcloudApiResponse>>()
        val requests = mutableListOf<NextcloudApiRequest>()
        val controller = AccountProfileController(CoroutineScope(Dispatchers.Unconfined)) { request ->
            requests += request
            CompletableDeferred<NextcloudApiResponse>().also(responses::addLast).await()
        }
        controller.refresh()
        responses.removeFirst().complete(response(200, profileJson()))
        assertEquals("alice", controller.state.profile?.userId)

        controller.refresh()
        val stale = responses.removeFirst()
        controller.refresh()
        // The first refresh was cancelled by the second; its late answer must not apply.
        stale.complete(response(200, ocs("""{"id":"mallory"}""")))
        responses.removeFirst().complete(response(503, ""))
        assertEquals("alice", controller.state.profile?.userId)
        assertTrue(controller.state.loadFailure.orEmpty().contains("could not be loaded"))
        assertTrue(requests.all { it.method == NextcloudApiMethod.GET })
    }

    @Test
    fun `transport faults become an unavailable state`() = runBlocking {
        val controller = AccountProfileController(CoroutineScope(Dispatchers.Unconfined)) { error("socket closed") }
        controller.refresh()
        assertNull(controller.state.profile)
        assertEquals(AccountProfileLoadResult.Unavailable.loadFailureMessage(), controller.state.loadFailure)
    }

    @Test
    fun `unknown scope stays unknown`() {
        assertNull(AccountPropertyScope.fromWire("v9-unknown"))
    }

    private fun loaded(profile: String): AccountProfile =
        assertIs<AccountProfileLoadResult.Loaded>(parseAccountProfile(response(200, profile))).profile

    private fun profileJson() = ocs(
        """
        {"id":"alice","displayname":"Alice Example","displaynameScope":"v2-federated",
         "email":"alice@example.test","emailScope":"v2-local","phone":"+1 555 0100","phoneScope":"contacts",
         "website":"","websiteScope":"v2-local","biography":"","biographyScope":"v2-local",
         "additional_mail":["team@example.test"],"additional_mailScope":["v2-private"],
         "profile_enabled":"1","language":"en","locale":"en_US","groups":["staff"],
         "quota":{"used":1024,"total":2048,"relative":50,"quota":-3}}
        """.trimIndent(),
    )

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}"""

    private fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
}
