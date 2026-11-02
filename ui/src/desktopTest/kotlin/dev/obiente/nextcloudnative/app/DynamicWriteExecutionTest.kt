package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAccessLevel
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicContractFacts
import dev.obiente.nextcloudnative.nativeui.model.operationRiskClass
import dev.obiente.nextcloudnative.nativeui.model.DynamicField
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicOperationRiskClass
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
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
import dev.obiente.nextcloudnative.nativeui.runtime.NativeSendAttemptToken
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePrecondition
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreconditionFailure
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteReadBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transport-boundary write path for a synthetic "notebook" app: admission, the exactly-once
 * token, full echo, read-back verification, quarantine, and the in-flight gate.
 */
class DynamicWriteExecutionTest {
    @Test
    fun `administration and unsafeguarded sends are refused before anything is sent`() = runBlocking<Unit> {
        val transport = Transport()
        val admin = executor(transport).execute(submit("settings.admin", mapOf("mode" to "strict")))
        assertEquals(NativeActionFailureOutcome.Rejected, assertIs<NativeActionExecutionResult.Failure>(admin).outcome)
        val send = executor(transport).execute(submit("notes.send", mapOf("noteId" to "7", "to" to "a@example.test")))
        assertEquals(NativeActionFailureOutcome.Rejected, assertIs<NativeActionExecutionResult.Failure>(send).outcome)
        assertTrue(transport.requests.isEmpty())
        assertEquals(DynamicOperationRiskClass.administration, descriptor().actions.single { it.id == "settings.admin" }.riskClass)
    }

    @Test
    fun `a write the package limits to administrators is refused as administration`() = runBlocking<Unit> {
        val adminOnly = descriptor().let { base ->
            base.copy(actions = base.actions.map { action ->
                if (action.id == "notes.update") action.copy(riskClass = null, contractFacts = DynamicContractFacts(access = DynamicAccessLevel.admin)) else action
            })
        }
        assertEquals(DynamicOperationRiskClass.administration, adminOnly.actions.single { it.id == "notes.update" }.operationRiskClass)
        assertTrue(adminOnly.toNativeAppSchema().dedicatedActions.any { it.id == "notes.update" }, "No generic form offers it.")
        val transport = Transport(readBody = stored(title = "New title"))
        val refused = assertIs<NativeActionExecutionResult.Failure>(executor(transport, adminOnly).execute(echoUpdate(adminOnly)))
        assertEquals(NativeActionFailureOutcome.Rejected, refused.outcome)
        assertTrue(transport.requests.isEmpty())

        val userLevel = descriptor().let { base ->
            base.copy(actions = base.actions.map { action ->
                if (action.id == "notes.update") action.copy(contractFacts = DynamicContractFacts(access = DynamicAccessLevel.authenticatedUser)) else action
            })
        }
        assertIs<NativeActionExecutionResult.Success>(executor(Transport(readBody = stored(title = "New title")), userLevel).execute(echoUpdate(userLevel)))
    }

    @Test
    fun `an exactly-once send carries a declared idempotency key and never an undeclared one`() = runBlocking<Unit> {
        val declared = Transport()
        val token = NativeSendAttemptToken("0".repeat(31) + "1", "0".repeat(31) + "2")
        val sent = executor(declared).execute(submit("notes.send", mapOf("noteId" to "7", "to" to "a@example.test")).copy(sendAttempt = token))
        assertIs<NativeActionExecutionResult.Success>(sent)
        assertEquals(token.idempotencyKey, declared.requests.single().idempotencyKey)
        assertEquals(token.idempotencyKey, declared.requests.single().preconditionHeaders["Idempotency-Key"])

        val undeclared = Transport()
        val plain = descriptor(idempotencyHeader = null)
        executor(undeclared, plain).execute(submit("notes.send", mapOf("noteId" to "7", "to" to "a@example.test"), plain).copy(sendAttempt = token))
        assertNull(undeclared.requests.single().idempotencyKey, "A key the contract does not declare is never sent.")
    }

    @Test
    fun `a full echo sends untouched values exactly as read and confirms by reading back`() = runBlocking<Unit> {
        val transport = Transport(readBody = stored(title = "New title"))
        val result = executor(transport).execute(echoUpdate())
        assertEquals("Saved.", assertIs<NativeActionExecutionResult.Success>(result).message)
        assertEquals(listOf(NextcloudApiMethod.PUT, NextcloudApiMethod.GET), transport.requests.map { it.method })
        val body = Json.parseToJsonElement(transport.requests.first().body!!.decodeToString()) as JsonObject
        assertEquals(JsonPrimitive("2026-01-05T10:30:00+00:00"), body["due"], "The stored date-time is not re-encoded.")
        assertEquals(JsonNull, body["archivedAt"], "A nullable field stored as null is echoed as null.")
        assertEquals(JsonPrimitive("New title"), body["title"])
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, transport.requests.last().cachePolicy)
    }

    @Test
    fun `2xx then a not-applied read-back quarantines the route until the person clears it`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val transport = Transport(readBody = stored(title = "Old title"))
        val result = assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(echoUpdate()))
        assertEquals(NativeActionFailureOutcome.Rejected, result.outcome)
        val records = store.records(SESSION.accountId.storageKey, "notebook")
        assertEquals(DynamicWriteRuleStatus.quarantined, records.single().status)
        assertEquals(DynamicWriteQuarantineReason.notAppliedAfterSuccess, records.single().reason)
        assertEquals("PUT $PATH", records.single().key.route)
        assertTrue(transport.codes.containsAll(listOf("write-postcondition-mismatch", "write-quarantined")), "${transport.codes}")
        assertTrue(transport.events.all { event -> event.fields.none { it.value.contains("Old title") || it.value.contains("New title") } },
            "Diagnostics never carry payload values.")

        val before = transport.requests.size
        val refused = assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(echoUpdate()))
        assertTrue("paused" in refused.message, refused.message)
        assertEquals(before, transport.requests.size, "A quarantined route sends nothing.")

        store.clearApp(SESSION.accountId.storageKey, "notebook")
        val resumed = Transport(readBody = stored(title = "New title"))
        assertIs<NativeActionExecutionResult.Success>(executor(resumed, store = store).execute(echoUpdate()))
    }

    @Test
    fun `drift on an untouched field saves, quarantines, and offers a baseline restore`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val transport = Transport(readBody = stored(title = "New title", body = "Edited elsewhere"))
        val edit = echoUpdate().copy(precondition = unchangedPrecondition())
        val result = assertIs<NativeActionExecutionResult.Success>(executor(transport, store = store).execute(edit))
        val postcondition = assertNotNull(result.postcondition)
        assertEquals(setOf("body"), postcondition.drifted)
        val restore = assertNotNull(postcondition.restoreBaseline)
        assertEquals(JsonPrimitive("Old title"), restore.readBack?.untouched?.get("title"), "The restore writes the values from before the edit.")
        assertTrue("title" !in restore.values)
        assertEquals(DynamicWriteRuleStatus.quarantined, store.records(SESSION.accountId.storageKey, "notebook").single().status)
        val unguarded = Transport(readBody = stored(title = "New title", body = "Edited elsewhere"))
        val withoutPreRead = assertIs<NativeActionExecutionResult.Success>(executor(unguarded).execute(echoUpdate()))
        assertNull(withoutPreRead.postcondition?.restoreBaseline, "A restore is offered only with a pre-read compare.")
    }

    @Test
    fun `a failed read-back is unknown and quarantines nothing`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val transport = Transport(readBody = "{}", readStatus = 503)
        val result = assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(echoUpdate()))
        assertEquals(NativeActionFailureOutcome.Unknown, result.outcome)
        assertTrue(store.records(SESSION.accountId.storageKey, "notebook").isEmpty())
    }

    @Test
    fun `an unreadable write safety state refuses writes`() = runBlocking<Unit> {
        val broken = object : DynamicWriteQuarantineStore by MemoryDynamicWriteQuarantineStore() {
            override suspend fun record(key: DynamicWriteRuleKey): DynamicWriteRuleRecord? = error("unreadable")
        }
        val transport = Transport(readBody = stored(title = "New title"))
        val result = assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = broken).execute(echoUpdate()))
        assertEquals(NativeActionFailureOutcome.Rejected, result.outcome)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a second write to the same target waits for the first`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = Transport(readBody = stored(title = "New title"), holdWrite = gate)
        val first = async { executor(transport).execute(echoUpdate()) }
        while (transport.requests.isEmpty()) kotlinx.coroutines.yield()
        val second = assertIs<NativeActionExecutionResult.Failure>(executor(transport).execute(echoUpdate()))
        assertTrue("still being saved" in second.message, second.message)
        gate.complete(Unit)
        assertIs<NativeActionExecutionResult.Success>(first.await())
        assertIs<NativeActionExecutionResult.Success>(executor(transport).execute(echoUpdate()), "The gate is released afterwards.")
    }

    @Test
    fun `the offered baseline restore runs while the route is quarantined and a normal edit stays refused`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val drifted = stored(title = "New title", body = "Edited elsewhere")
        val transport = Transport(readBody = stored(title = "Old title"), afterWrites = ArrayDeque(listOf(drifted, stored(title = "Old title"))))
        val edit = echoUpdate().copy(precondition = unchangedPrecondition())
        val result = assertIs<NativeActionExecutionResult.Success>(executor(transport, store = store).execute(edit))
        val restore = assertNotNull(result.postcondition?.restoreBaseline)
        assertTrue(restore.baselineRestore)
        assertEquals(DynamicWriteRuleStatus.quarantined, store.records(SESSION.accountId.storageKey, "notebook").single().status)

        val beforeRefusals = transport.requests.size
        val refused = assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(edit))
        assertTrue("paused" in refused.message, refused.message)
        val forged = assertIs<NativeActionExecutionResult.Failure>(
            executor(transport, store = store).execute(restore.copy(values = restore.values + ("title" to "Something else"))),
        )
        assertEquals(NativeActionFailureOutcome.Rejected, forged.outcome)
        assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(edit.copy(baselineRestore = true)))
        assertEquals(beforeRefusals, transport.requests.size, "A normal edit and a forged restore send nothing.")

        val restored = assertIs<NativeActionExecutionResult.Success>(executor(transport, store = store).execute(restore))
        assertEquals("Saved.", restored.message)
        val sent = transport.requests.drop(beforeRefusals)
        assertEquals(listOf(NextcloudApiMethod.GET, NextcloudApiMethod.PUT, NextcloudApiMethod.GET), sent.map { it.method },
            "Pre-read compare, one full-echo send, and a read-back.")
        val body = Json.parseToJsonElement(sent[1].body!!.decodeToString()) as JsonObject
        assertEquals(JsonPrimitive("Old title"), body["title"])
        assertEquals(JsonPrimitive("Synthetic body"), body["body"], "The drifted field is restored to its baseline.")
        assertEquals(JsonPrimitive("2026-01-05T10:30:00+00:00"), body["due"])

        val afterRestore = transport.requests.size
        assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(restore), "A restore is accepted once.")
        assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(edit), "The route stays paused.")
        assertEquals(afterRestore, transport.requests.size)
        assertEquals(DynamicWriteRuleStatus.quarantined, store.records(SESSION.accountId.storageKey, "notebook").single().status)
    }

    @Test
    fun `a baseline restore stops when the item changed again after the drift`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val transport = Transport(
            readBody = stored(title = "Old title"),
            afterWrites = ArrayDeque(listOf(stored(title = "New title", body = "Edited elsewhere"))),
        )
        val result = assertIs<NativeActionExecutionResult.Success>(
            executor(transport, store = store).execute(echoUpdate().copy(precondition = unchangedPrecondition())),
        )
        val restore = assertNotNull(result.postcondition?.restoreBaseline)
        transport.readBody = stored(title = "New title", body = "Edited a second time")
        val before = transport.requests.size
        val stopped = assertIs<NativeActionExecutionResult.Failure>(executor(transport, store = store).execute(restore))
        assertEquals(NativeWritePreconditionFailure.Changed, stopped.precondition)
        assertEquals(listOf(NextcloudApiMethod.GET), transport.requests.drop(before).map { it.method }, "Nothing is sent.")
    }

    @Test
    fun `an app version outside the key alphabet still quarantines its route`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val odd = descriptor().let { it.copy(app = it.app.copy(version = "2.0.0 beta~1")) }
        val transport = Transport(readBody = stored(title = "Old title"))
        assertIs<NativeActionExecutionResult.Failure>(executor(transport, odd, store).execute(echoUpdate(odd)))
        val record = store.records(SESSION.accountId.storageKey, "notebook").single()
        assertEquals(DynamicWriteRuleStatus.quarantined, record.status)
        assertTrue(record.key.appVersion.startsWith("#") && record.key.appVersion.length == 65, record.key.appVersion)

        val before = transport.requests.size
        val refused = assertIs<NativeActionExecutionResult.Failure>(executor(transport, odd, store).execute(echoUpdate(odd)))
        assertTrue("paused" in refused.message, refused.message)
        assertEquals(before, transport.requests.size, "The digest-keyed quarantine holds.")
    }

    @Test
    fun `a reverted edit is not applied even when a parent input reads back as another JSON type`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        val scoped = descriptor().let { base ->
            base.copy(actions = base.actions.map { action ->
                if (action.id != "notes.update") return@map action
                action.copy(binding = action.binding.copy(body = body(
                    """"title":{"type":"string"},"body":{"type":"string"},"due":{"type":"string"},""" +
                        """"archivedAt":{"type":"string","nullable":true},"notebookId":{"type":"string"}""",
                )))
            })
        }
        val transport = Transport(readBody = stored(title = "Old title", extra = ""","notebookId":3"""))
        val request = echoUpdate(scoped).let { it.copy(values = it.values + ("notebookId" to "3")) }
        val result = assertIs<NativeActionExecutionResult.Failure>(executor(transport, scoped, store).execute(request))
        val sent = Json.parseToJsonElement(transport.requests.first().body!!.decodeToString()) as JsonObject
        assertEquals(JsonPrimitive("3"), sent["notebookId"], "The parent input travels as the declared string.")
        assertTrue("not applied" in result.message, result.message)
        assertEquals(DynamicWriteQuarantineReason.notAppliedAfterSuccess, store.records(SESSION.accountId.storageKey, "notebook").single().reason)
    }

    @Test
    fun `a share is sent only when the target states that the actor may share it`() = runBlocking<Unit> {
        val allowed = Transport(readBody = stored(title = "Old title", extra = ""","canShare":true"""))
        assertIs<NativeActionExecutionResult.Success>(executor(allowed).execute(share()))
        assertEquals(listOf(NextcloudApiMethod.GET, NextcloudApiMethod.POST), allowed.requests.map { it.method })
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, allowed.requests.first().cachePolicy)

        val denied = Transport(readBody = stored(title = "Old title", extra = ""","canShare":false"""))
        val refused = assertIs<NativeActionExecutionResult.Failure>(executor(denied).execute(share()))
        assertEquals(NativeActionFailureOutcome.Rejected, refused.outcome)
        assertTrue("may not share" in refused.message, refused.message)

        listOf(
            Transport(readBody = stored(title = "Old title", extra = ""","canShare":"true"""")),
            Transport(readBody = stored(title = "Old title", extra = ""","canShare":1""")),
            Transport(readBody = stored(title = "Old title")),
            Transport(readBody = "{}", readStatus = 503),
        ).forEach { transport ->
            val unverified = assertIs<NativeActionExecutionResult.Failure>(executor(transport).execute(share()))
            assertTrue("could not be checked" in unverified.message, unverified.message)
            assertTrue(transport.requests.none { it.method != NextcloudApiMethod.GET }, "Nothing is shared.")
        }
        assertTrue(denied.requests.none { it.method != NextcloudApiMethod.GET })
    }

    private fun share() = submit("notes.share", mapOf("noteId" to "7", "shareWith" to "synthetic-friend"))

    private fun unchangedPrecondition() = NativeWritePrecondition(mapOf("noteId" to "7"), "7") { true }

    private fun echoUpdate(descriptor: DynamicAppDescriptor = descriptor()): NativeActionRequest.Submit =
        submit("notes.update", mapOf("noteId" to "7", "title" to "New title"), descriptor).copy(
            readBack = NativeWriteReadBack(
                targetId = "7",
                previous = mapOf("title" to JsonPrimitive("Old title")),
                untouched = mapOf(
                    "body" to JsonPrimitive("Synthetic body"),
                    "due" to JsonPrimitive("2026-01-05T10:30:00+00:00"),
                    "archivedAt" to JsonNull,
                ),
                echoUntouched = true,
            ),
        )

    private fun submit(actionId: String, values: Map<String, String>, descriptor: DynamicAppDescriptor = descriptor()) =
        NativeActionRequest.Submit(
            action = (descriptor.toNativeAppSchema().let { it.actions + it.dedicatedActions }).single { it.id == actionId },
            values = values,
            confirmed = true,
        )

    private fun stored(title: String, body: String = "Synthetic body", extra: String = "") =
        """{"id":7,"title":"$title","body":"$body","due":"2026-01-05T10:30:00+00:00","archivedAt":null$extra}"""

    private class Transport(
        var readBody: String = "{}",
        val readStatus: Int = 200,
        val holdWrite: CompletableDeferred<Unit>? = null,
        /** What reads return after each successive write, so a write can change the stored state. */
        val afterWrites: ArrayDeque<String> = ArrayDeque(),
    ) {
        val requests = mutableListOf<NextcloudApiRequest>()
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        val codes: List<String> get() = events.mapNotNull { it.code }

        fun services(store: DynamicWriteQuarantineStore?): NextcloudPlatformServices {
            val unavailable = Proxy.newProxyInstance(
                NextcloudPlatformServices::class.java.classLoader,
                arrayOf(NextcloudPlatformServices::class.java),
            ) { _, method, _ -> error("Unexpected ${method.name}") } as NextcloudPlatformServices
            return object : NextcloudPlatformServices by unavailable {
                override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) {
                    events += event
                }

                override fun dynamicWriteQuarantineStore(): DynamicWriteQuarantineStore? = store

                override suspend fun executeNextcloudApi(session: NextcloudSession, request: NextcloudApiRequest): NextcloudApiResponse {
                    requests += request
                    if (request.method == NextcloudApiMethod.GET) {
                        return NextcloudApiResponse(readStatus, readBody.encodeToByteArray(), "application/json", etag = null)
                    }
                    holdWrite?.await()
                    afterWrites.removeFirstOrNull()?.let { readBody = it }
                    return NextcloudApiResponse(200, "{}".encodeToByteArray(), "application/json", etag = null)
                }
            }
        }
    }

    private fun executor(
        transport: Transport,
        descriptor: DynamicAppDescriptor = descriptor(),
        store: DynamicWriteQuarantineStore? = null,
    ) = DynamicNextcloudActionExecutor(transport.services(store), SESSION, descriptor, clock = { 1_000L })

    private fun descriptor(idempotencyHeader: String? = "Idempotency-Key"): DynamicAppDescriptor {
        val provenance = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "signed package", "Verified signed contract"))
        val noteId = HttpParameter("noteId", true, Json.parseToJsonElement("""{"type":"integer"}"""), ParameterSource.resourceField)
        val read = DynamicAction(
            id = "notes.read", label = "Read note", resourceId = "notes", intent = ActionIntent.read,
            risk = ActionRisk.readOnly, requiresConfirmation = false,
            binding = DynamicHttpBinding(HttpMethod.GET, PATH, pathParameters = listOf(noteId)),
            responseFieldIds = listOf("id", "title", "body", "due", "archivedAt", "canShare"), confidence = Confidence.verified, provenance = provenance,
        )
        val update = read.copy(
            id = "notes.update", label = "Edit note", intent = ActionIntent.update, risk = ActionRisk.mutating,
            requiresConfirmation = false, resultRecoveryActionId = read.id, responseFieldIds = emptyList(),
            riskClass = DynamicOperationRiskClass.replacement,
            binding = read.binding.copy(method = HttpMethod.PUT, body = body(
                """"title":{"type":"string"},"body":{"type":"string"},"due":{"type":"string"},"archivedAt":{"type":"string","nullable":true}""",
            )),
        )
        val send = read.copy(
            id = "notes.send", label = "Send note", intent = ActionIntent.execute, risk = ActionRisk.mutating,
            requiresConfirmation = false, responseFieldIds = emptyList(), riskClass = DynamicOperationRiskClass.externalEffect,
            idempotencyKeyHeader = idempotencyHeader,
            binding = DynamicHttpBinding(HttpMethod.POST, "$PATH/send", pathParameters = listOf(noteId), body = body(""""to":{"type":"string"}""", "to")),
        )
        val admin = read.copy(
            id = "settings.admin", label = "Change admin settings", resourceId = "settings", intent = ActionIntent.update,
            risk = ActionRisk.mutating, requiresConfirmation = false, responseFieldIds = emptyList(),
            binding = DynamicHttpBinding(HttpMethod.PUT, "/apps/notebook/api/admin/settings", body = body(""""mode":{"type":"string"}""")),
            riskClass = DynamicOperationRiskClass.administration,
        )
        val share = read.copy(
            id = "notes.share", label = "Share note", intent = ActionIntent.execute, risk = ActionRisk.mutating,
            requiresConfirmation = true, responseFieldIds = emptyList(), riskClass = DynamicOperationRiskClass.sharing,
            readBackActionId = read.id, sharePermissionFieldId = "canShare",
            binding = DynamicHttpBinding(HttpMethod.POST, "$PATH/share", pathParameters = listOf(noteId), body = body(""""shareWith":{"type":"string"}""", "shareWith")),
        )
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("notebook", "Notebook", "2.0.0"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/notebook/api")),
            resources = listOf(
                DynamicResource(
                    id = "notes", label = "Notes", collection = true, confidence = Confidence.verified,
                    fields = listOf("id", "title", "body", "due", "archivedAt", "to", "canShare", "notebookId", "shareWith").map { id ->
                        DynamicField(id, id, FieldKind.string, false, id == "id", id == "archivedAt", false, confidence = Confidence.verified)
                    },
                ),
                DynamicResource(id = "settings", label = "Settings", collection = false, confidence = Confidence.verified),
            ),
            actions = listOf(read, update, send, admin, share),
        )
    }

    private fun body(properties: String, vararg required: String): HttpBody = HttpBody(
        "application/json", true,
        Json.parseToJsonElement("""{"type":"object","required":[${required.joinToString(",") { "\"$it\"" }}],"properties":{$properties}}"""),
    )

    private companion object {
        const val PATH = "/apps/notebook/api/notes/{noteId}"
        val SESSION = NextcloudSession("https://cloud.example.test", "synthetic-user", "synthetic-app-password")
    }
}
