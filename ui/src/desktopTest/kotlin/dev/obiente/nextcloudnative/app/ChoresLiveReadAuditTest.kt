package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.resolveDynamicRecordReadParameters
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeGroupwareItemKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeHouseholdItemKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeGroupwarePresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeHouseholdPresentation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChoresLiveReadAuditTest {
    @Test
    fun `live Chores hierarchy audit is GET only and sanitized`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_CHORES_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        val app = assertNotNull(server.apps.firstOrNull { entry -> entry.id == "chores" })
        val discovery = discoverDynamicAppDescriptor(services, session, app, server.version)
        assertEquals(DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes, discovery.acquisition)
        val descriptor = discovery.descriptor
        val nativeSchema = descriptor.toNativeAppSchema()
        val observed = mutableListOf<NextcloudApiRequest>()

        suspend fun read(action: DynamicAction, values: Map<String, String> = emptyMap()): List<NativeRecord> {
            val request = buildDynamicApiRequest(descriptor, action, values)
            assertEquals(NextcloudApiMethod.GET, request.method)
            assertTrue(request.body == null)
            observed += request
            val response = services.executeNextcloudApi(session, request)
            assertTrue(response.status in 200..299)
            if (response.status == 204 || response.body.isEmpty()) return emptyList()
            val fields = descriptor.resources.firstOrNull { resource -> resource.id == action.resourceId }
                ?.fields.orEmpty().mapTo(linkedSetOf()) { field -> field.id }
            return parseDynamicRecords(action, response, fields)
        }

        val teamAction = descriptor.get("/apps/chores/api/v1.0/team")
        val invitationsAction = descriptor.get("/apps/chores/api/v1.0/account/invites")
        val team = read(teamAction).firstOrNull()
        read(invitationsAction)
        if (team != null) {
            val teamResource = assertNotNull(
                nativeSchema.resources.firstOrNull { resource -> resource.id == teamAction.resourceId },
            )
            assertEquals(
                NativeHouseholdItemKind.Household,
                assertNotNull(nativeHouseholdPresentation(teamResource, team)).kind,
            )
            // Sparse signed response schemas deliberately keep observed fields out of action-bound
            // values. Exercise the same read-only identity resolution used by the UI instead of
            // accidentally requiring an undeclared ID to become mutation-safe.
            val context = DynamicResourceRecordContext(
                resourceId = teamAction.resourceId,
                recordId = team.id,
                fieldValues = team.values,
                actionSafeIdentity = team.actionSafeIdentity,
            )
            val choresAction = descriptor.get("/apps/chores/api/v1.0/team/{teamId}/chores")
            val workAction = descriptor.get("/apps/chores/api/v1.0/team/{teamId}/work")
            val choreParameters = assertNotNull(
                descriptor.resolveDynamicRecordReadParameters(choresAction.id, context),
            )
            val workParameters = assertNotNull(
                descriptor.resolveDynamicRecordReadParameters(workAction.id, context),
            )
            val chores = read(
                choresAction,
                choreParameters,
            )
            val work = read(
                workAction,
                workParameters,
            )
            val choreResource = assertNotNull(
                nativeSchema.resources.firstOrNull { resource -> resource.id == choresAction.resourceId },
            )
            val workResource = assertNotNull(
                nativeSchema.resources.firstOrNull { resource -> resource.id == workAction.resourceId },
            )
            assertTrue(chores.all { chore ->
                nativeGroupwarePresentation(choreResource, chore)?.kind == NativeGroupwareItemKind.Task
            })
            assertTrue(work.all { item ->
                nativeHouseholdPresentation(workResource, item)?.kind == NativeHouseholdItemKind.Completion
            })
        }

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { request ->
            request.method == NextcloudApiMethod.GET &&
                request.body == null &&
                request.relativePath.startsWith("/apps/chores/api/v1.0/")
        })
        println(
            "chores-audit outcome=success requests=get-only hierarchy=verified " +
                "task-content=redacted",
        )
    }

    private fun dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor.get(path: String): DynamicAction =
        actions.single { action -> action.binding.method == HttpMethod.GET && action.binding.path == path }
}
