package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicField
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.HttpBody
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.Provenance
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePrecondition
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreconditionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The executor re-reads a replacement's target through the contract's same-route read, bypassing
 * caches, and sends the replacement only when the target is unchanged. Synthetic "notebook" app.
 */
class DynamicReplacementWriteGuardTest {
    @Test
    fun anUnchangedTargetIsReadFreshThenReplaced() = runBlocking {
        val transport = Transport(readBody = """{"id":7,"title":"Synthetic note"}""")
        val result = executor(transport).execute(request(expectedTitle = "Synthetic note"))
        assertIs<NativeActionExecutionResult.Success>(result)
        assertEquals(listOf(NextcloudApiMethod.GET, NextcloudApiMethod.PUT), transport.requests.map { it.method })
        assertEquals("/apps/notebook/api/notes/7", transport.requests.first().relativePath)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, transport.requests.first().cachePolicy,
            "The check never trusts a cached copy.")
    }

    @Test
    fun aConcurrentChangeWithholdsTheReplacement() = runBlocking {
        val transport = Transport(readBody = """{"id":7,"title":"Edited elsewhere"}""")
        val result = assertIs<NativeActionExecutionResult.Failure>(executor(transport).execute(request(expectedTitle = "Synthetic note")))
        assertEquals(NativeWritePreconditionFailure.Changed, result.precondition)
        assertEquals(NativeActionFailureOutcome.Rejected, result.outcome, "Nothing was sent, so the outcome is known.")
        assertEquals(listOf(NextcloudApiMethod.GET), transport.requests.map { it.method })
    }

    @Test
    fun aFailedOrMissingReadWithholdsTheReplacement() = runBlocking {
        val failing = Transport(readBody = "{}", readStatus = 503)
        val failed = assertIs<NativeActionExecutionResult.Failure>(executor(failing).execute(request(expectedTitle = "Synthetic note")))
        assertEquals(NativeWritePreconditionFailure.Unverified, failed.precondition)
        assertEquals(listOf(NextcloudApiMethod.GET), failing.requests.map { it.method })

        val undeclared = Transport(readBody = """{"id":7,"title":"Synthetic note"}""")
        val withoutRead = descriptor().let { it.copy(actions = it.actions.map { action -> action.copy(resultRecoveryActionId = null) }) }
        val refused = assertIs<NativeActionExecutionResult.Failure>(
            executor(undeclared, withoutRead).execute(request(expectedTitle = "Synthetic note", descriptor = withoutRead)),
        )
        assertEquals(NativeWritePreconditionFailure.Unverified, refused.precondition)
        assertEquals(emptyList(), undeclared.requests, "Without a declared same-route read nothing is sent.")
    }

    @Test
    fun cancellationDuringTheCheckStaysCancellation() {
        val transport = Transport(readBody = "{}", cancelRead = true)
        assertFailsWith<CancellationException> {
            runBlocking { executor(transport).execute(request(expectedTitle = "Synthetic note")) }
        }
    }

    @Test
    fun aWriteWithoutAPreconditionIsSentDirectly() = runBlocking {
        val transport = Transport(readBody = """{"id":7,"title":"Synthetic note"}""")
        val plain = request(expectedTitle = "Synthetic note").copy(precondition = null)
        assertIs<NativeActionExecutionResult.Success>(executor(transport).execute(plain))
        assertEquals(listOf(NextcloudApiMethod.PUT), transport.requests.map { it.method })
        assertNull(plain.precondition)
    }

    private class Transport(
        val readBody: String,
        val readStatus: Int = 200,
        val cancelRead: Boolean = false,
    ) {
        val requests = mutableListOf<NextcloudApiRequest>()

        fun services(): NextcloudPlatformServices {
            val unavailable = Proxy.newProxyInstance(
                NextcloudPlatformServices::class.java.classLoader,
                arrayOf(NextcloudPlatformServices::class.java),
            ) { _, method, _ -> error("Unexpected ${method.name}") } as NextcloudPlatformServices
            return object : NextcloudPlatformServices by unavailable {
                override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) = Unit

                override suspend fun executeNextcloudApi(session: NextcloudSession, request: NextcloudApiRequest): NextcloudApiResponse {
                    requests += request
                    if (request.method == NextcloudApiMethod.GET) {
                        if (cancelRead) throw CancellationException("left")
                        return NextcloudApiResponse(readStatus, readBody.encodeToByteArray(), "application/json", etag = null)
                    }
                    return NextcloudApiResponse(200, "{}".encodeToByteArray(), "application/json", etag = null)
                }
            }
        }
    }

    private fun executor(transport: Transport, descriptor: DynamicAppDescriptor = descriptor()) =
        DynamicNextcloudActionExecutor(transport.services(), SESSION, descriptor)

    private fun request(expectedTitle: String, descriptor: DynamicAppDescriptor = descriptor()): NativeActionRequest.Submit {
        val update = descriptor.toNativeAppSchema().action("notes.update")!!
        return NativeActionRequest.Submit(
            action = update,
            values = mapOf("noteId" to "7", "title" to "Synthetic draft"),
            confirmed = true,
            precondition = NativeWritePrecondition(mapOf("noteId" to "7"), targetId = "7") { current ->
                current.values["title"] == expectedTitle
            },
        )
    }

    private fun descriptor(): DynamicAppDescriptor {
        val provenance = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "signed package", "Verified signed contract"))
        val noteId = HttpParameter("noteId", true, json.parseToJsonElement("""{"type":"integer"}"""), ParameterSource.resourceField)
        val read = DynamicAction(
            id = "notes.read", label = "Read note", resourceId = "notes", intent = ActionIntent.read,
            risk = ActionRisk.readOnly, requiresConfirmation = false,
            binding = DynamicHttpBinding(HttpMethod.GET, PATH, pathParameters = listOf(noteId)),
            responseFieldIds = listOf("id", "title"), confidence = Confidence.verified, provenance = provenance,
        )
        val update = read.copy(
            id = "notes.update", label = "Edit note", intent = ActionIntent.update, risk = ActionRisk.mutating,
            requiresConfirmation = true, resultRecoveryActionId = read.id, responseFieldIds = emptyList(),
            binding = read.binding.copy(
                method = HttpMethod.PUT,
                body = HttpBody("application/json", true, json.parseToJsonElement(
                    """{"type":"object","required":["title"],"properties":{"title":{"type":"string"}}}""",
                )),
            ),
        )
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("notebook", "Notebook", "2.0.0"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/notebook/api")),
            resources = listOf(DynamicResource(id = "notes", label = "Notes", collection = true, confidence = Confidence.verified,
                fields = listOf(
                    DynamicField("id", "ID", FieldKind.integer, true, true, false, false, confidence = Confidence.verified),
                    DynamicField("title", "Title", FieldKind.string, true, false, false, false, confidence = Confidence.verified),
                ))),
            actions = listOf(read, update),
        )
    }

    private companion object {
        val json = Json
        const val PATH = "/apps/notebook/api/notes/{noteId}"
        val SESSION = NextcloudSession("https://cloud.example.test", "synthetic-user", "synthetic-app-password")
    }
}
