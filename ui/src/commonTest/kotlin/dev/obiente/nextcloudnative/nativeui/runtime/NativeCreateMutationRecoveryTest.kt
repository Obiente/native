package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeCreateMutationRecoveryTest {
    @Test
    fun `create marker scopes the exact parent and retains its pre-submit baseline`() {
        val fixture = fixture("7")
        val sibling = fixture("8")
        assertNotEquals(fixture.plan.pendingKey, sibling.plan.pendingKey)

        val staged = assertNotNull(
            fixture.plan.stage(fixture.request, NativeCreateMutationPhase.Staged),
        )
        val pending = assertNotNull(
            nativeCreateMutationPostcondition(fixture.plan.pendingKey, staged),
        )
        assertEquals("7", pending.bindingValues["teamId"])
        assertEquals(setOf("11", "12"), pending.baselineRecordIds)
        assertEquals(
            mapOf(
                "name" to "Clean kitchen",
                "points" to "3",
                "due" to "2026-08-15T18:00:00Z",
                "repeat" to "w:1:-",
            ),
            pending.expectedRecordValues,
        )
        assertTrue(
            pending.matches(
                NativeRecord(
                    id = "13",
                    values = pending.expectedRecordValues,
                    bindingContext = mapOf("teamId" to "7"),
                ),
            ),
        )
        assertTrue(
            !pending.matches(
                NativeRecord(
                    id = "11",
                    values = pending.expectedRecordValues,
                    bindingContext = mapOf("teamId" to "7"),
                ),
            ),
        )
    }

    @Test
    fun `durable create stages before transport and never replays an ambiguous send`() = runBlocking {
        val fixture = fixture("7")
        var stored: Map<String, String>? = null
        var created = false
        val events = mutableListOf<String>()
        val store = object : NativePendingMutationStore {
            override suspend fun load(key: NativePendingMutationKey): Map<String, String>? {
                events += "load"
                return stored
            }

            override suspend fun save(key: NativePendingMutationKey, values: Map<String, String>) {
                events += "save:${nativeCreateMutationPostcondition(key, values)?.phase}"
                stored = values
            }

            override suspend fun postconditionSatisfied(
                key: NativePendingMutationKey,
                values: Map<String, String>,
            ): Boolean {
                events += "reconcile"
                return created
            }

            override suspend fun clear(key: NativePendingMutationKey) {
                events += "clear"
                stored = null
            }
        }
        val unknown = executeNativeCreateMutation(
            plan = fixture.plan,
            request = fixture.request,
            actionExecutor = NativeActionExecutor {
                events += "execute"
                NativeActionExecutionResult.Failure(
                    "Connection ended before the response arrived.",
                    NativeActionFailureOutcome.Unknown,
                )
            },
            pendingMutationStore = store,
        )
        assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(unknown).outcome)
        assertEquals(
            listOf(
                "load",
                "save:Staged",
                "save:TransportMayHaveObserved",
                "execute",
                "reconcile",
            ),
            events,
        )
        assertEquals(
            NativeCreateMutationPhase.TransportMayHaveObserved,
            nativeCreateMutationPostcondition(fixture.plan.pendingKey, assertNotNull(stored))?.phase,
        )

        events.clear()
        val stillUnknown = executeNativeCreateMutation(
            plan = fixture.plan,
            request = fixture.request,
            actionExecutor = NativeActionExecutor { error("An ambiguous create must not be sent twice.") },
            pendingMutationStore = store,
        )
        assertIs<NativeActionExecutionResult.Failure>(stillUnknown)
        assertEquals(listOf("load", "reconcile"), events)

        created = true
        events.clear()
        val reconciled = executeNativeCreateMutation(
            plan = fixture.plan,
            request = fixture.request,
            actionExecutor = NativeActionExecutor { error("A reconciled create must not be sent twice.") },
            pendingMutationStore = store,
        )
        assertIs<NativeActionExecutionResult.Success>(reconciled)
        assertEquals(listOf("load", "reconcile", "clear"), events)
        assertNull(stored)
    }

    @Test
    fun `a staged request resumes the exact persisted payload before entering transport`() = runBlocking {
        val fixture = fixture("7")
        val staged = assertNotNull(
            fixture.plan.stage(fixture.request, NativeCreateMutationPhase.Staged),
        )
        var stored: Map<String, String>? = staged
        var created = false
        val events = mutableListOf<String>()
        val store = object : NativePendingMutationStore {
            override suspend fun load(key: NativePendingMutationKey): Map<String, String>? = stored.also {
                events += "load"
            }

            override suspend fun save(key: NativePendingMutationKey, values: Map<String, String>) {
                events += "save:${nativeCreateMutationPostcondition(key, values)?.phase}"
                stored = values
            }

            override suspend fun postconditionSatisfied(
                key: NativePendingMutationKey,
                values: Map<String, String>,
            ): Boolean = created.also { events += "reconcile" }

            override suspend fun clear(key: NativePendingMutationKey) {
                events += "clear"
                stored = null
            }
        }
        val result = executeNativeCreateMutation(
            plan = fixture.plan,
            request = fixture.request.copy(
                values = fixture.request.values + ("chores" to "a different retry payload"),
            ),
            actionExecutor = NativeActionExecutor { request ->
                events += "execute"
                assertEquals(fixture.request, request)
                created = true
                NativeActionExecutionResult.Success("Created")
            },
            pendingMutationStore = store,
        )

        assertIs<NativeActionExecutionResult.Success>(result)
        assertEquals(
            listOf(
                "load",
                "reconcile",
                "save:TransportMayHaveObserved",
                "execute",
                "reconcile",
                "clear",
            ),
            events,
        )
        assertNull(stored)
    }

    private data class Fixture(
        val plan: NativeCreateMutationRecoveryPlan,
        val request: NativeActionRequest.Submit,
    )

    private fun fixture(teamId: String): Fixture {
        val fields = listOf(
            field("id", FieldKind.integer, readOnly = true),
            field("name", FieldKind.string),
            field("points", FieldKind.integer),
            field("due", FieldKind.dateTime),
            field("repeat", FieldKind.string),
        )
        val resource = ResourceSpec(
            id = "chores",
            name = "Chores",
            confidence = Confidence.verified,
            fields = fields,
        )
        val evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "Signed package route"))
        val read = ActionSpec(
            id = "chores.list",
            label = "List chores",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/chores/api/v1.0/team/{teamId}/chores",
                operationId = "chores.list",
                pathParameterNames = listOf("teamId"),
                requiredPathParameterNames = listOf("teamId"),
            ),
            intent = ActionIntent.list,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            evidence = evidence,
            effect = ActionEffect.list,
        )
        val itemProperties = fields.filterNot { field -> field.id == "id" }.associate { field ->
            field.id to JsonObject(mapOf("type" to JsonPrimitive("string")))
        }
        val bodySchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "chores" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "maxItems" to JsonPrimitive(1),
                                "items" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("object"),
                                        "properties" to JsonObject(itemProperties),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val create = ActionSpec(
            id = "chores.create",
            label = "Add chore",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = read.binding.path,
                operationId = "chores.create",
                pathParameterNames = listOf("teamId"),
                requiredPathParameterNames = listOf("teamId"),
                bodyFieldNames = listOf("chores"),
                requiredBodyFieldNames = listOf("chores"),
                bodyContentType = "application/json",
                bodySchema = bodySchema,
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            evidence = evidence,
            effect = ActionEffect.create,
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("chores", "Chores", "0.1.0"),
            confidence = Confidence.verified,
            resources = listOf(resource),
            actions = listOf(read, create),
        )
        val records = listOf("11", "12").map { id ->
            NativeRecord(
                id = id,
                values = mapOf("id" to id),
                bindingContext = mapOf("teamId" to teamId),
            )
        }
        val createPlan = NativeRecordFormActionPlan(
            kind = NativeRecordFormActionKind.Create,
            action = create,
            fields = listOf(
                FieldSpec(
                    id = "chores",
                    label = "Chores",
                    kind = FieldKind.objectValue,
                    required = true,
                    readOnly = false,
                ),
            ),
            initialValues = emptyMap(),
            bindingValues = mapOf("teamId" to teamId),
        )
        val plan = assertNotNull(
            nativeCreateMutationRecoveryPlan(
                schema = schema,
                activeReadAction = read,
                resource = resource,
                createPlan = createPlan,
                records = records,
                navigationContext = mapOf("teamId" to teamId),
                collectionComplete = true,
            ),
        )
        val request = NativeActionRequest.Submit(
            action = create,
            values = mapOf(
                "teamId" to teamId,
                "chores" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("Clean kitchen"),
                                "points" to JsonPrimitive(3),
                                "due" to JsonPrimitive("2026-08-15T18:00:00Z"),
                                "repeat" to JsonPrimitive("w:1:-"),
                            ),
                        ),
                    ),
                ).toString(),
            ),
            confirmed = false,
        )
        return Fixture(plan, request)
    }

    private fun field(id: String, kind: FieldKind, readOnly: Boolean = false) = FieldSpec(
        id = id,
        label = id,
        kind = kind,
        required = id != "id",
        readOnly = readOnly,
    )
}
