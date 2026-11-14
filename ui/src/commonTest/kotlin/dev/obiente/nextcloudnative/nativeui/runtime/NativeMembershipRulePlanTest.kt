package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.rules.*
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeMembershipRulePlanTest {
    private val raw = Json.parseToJsonElement("""[{"id":3,"title":"Kept"}]""") as JsonArray
    private fun NativeAppSchema.actions() = actions + dedicatedActions
    private fun NativeAppSchema.assign() = actions().single { it.binding.path.endsWith("/assignLabel") }
    private fun NativeAppSchema.read() = actions().single { it.binding.method == HttpMethod.GET && it.binding.path.endsWith("/{id}") }
    private fun authority(schema: NativeAppSchema, labels: JsonElement? = raw, id: JsonPrimitive = JsonPrimitive(41)): NativeItemReadAuthority {
        val read = schema.read()
        val bindings = read.binding.pathParameterNames.associateWith { when (it) { "id" -> id.content; "boardId", "workspaceId" -> "7"; else -> "10" } }
        val record = NativeRecord(id.content, mapOf("id" to id.content), wireValues = mapOf("id" to id),
            membershipObservations = mapOf("labels" to membershipObservation(labels)))
        return NativeItemReadAuthority(NativeScreenState.Ready(listOf(record), 1), read.id, bindings, canEdit = true)
    }

    @Test fun exactItemReadsNeedNoCollectionAndParentsBindByPositionAcrossApps() {
        for (app in listOf("planning-app", "unknown-workspace")) for (nested in listOf(false, true)) {
            val schema = membershipContract(app, nested, renameParents = true).toNativeAppSchema()
            val plan = assertNotNull(planNativeMembershipRule(schema, schema.assign().id, "41", JsonPrimitive(5), authority(schema)))
            assertEquals("5", plan.writeBindings["labelId"])
            assertEquals("41", plan.writeBindings["cardId"])
            assertEquals(schema.read().id, plan.verificationReadActionId)
            if (nested) {
                assertEquals("7", plan.writeBindings["boardId"])
                assertEquals("10", plan.readBindings["laneId"])
            }
        }
    }

    @Test fun onlyChosenWritesExactReadCanSupplyAuthority() {
        val direct = membershipContract().toNativeAppSchema()
        val nested = membershipContract(nested = true).toNativeAppSchema()
        val otherRead = nested.read().copy(id = "another-read")
        val both = direct.copy(actions = direct.actions + otherRead)
        assertNotNull(planNativeMembershipRule(both, both.assign().id, "41", JsonPrimitive(5), authority(direct)))
        assertNull(planNativeMembershipRule(both, both.assign().id, "41", JsonPrimitive(5), authority(nested).copy(readActionId = otherRead.id)))
        val withoutExact = direct.copy(actions = direct.actions.filterNot { it.id == direct.read().id } + otherRead)
        assertNull(SetMembershipRule().proof(withoutExact, PatternRouteFamily(withoutExact.assign(), withoutExact.actions())))
        val ambiguous = direct.copy(actions = direct.actions + direct.read().copy(id = "same-route-second-read"))
        assertNull(planNativeMembershipRule(ambiguous, ambiguous.assign().id, "41", JsonPrimitive(5), authority(direct)))
    }

    @Test fun contractFailuresNeverProduceAPlan() {
        val schema = membershipContract().toNativeAppSchema()
        val assign = schema.assign()
        val inverse = schema.actions().single { it.binding.path.endsWith("/removeLabel") }
        fun refuses(modified: NativeAppSchema) = assertNull(planNativeMembershipRule(modified, assign.id, "41", JsonPrimitive(5), authority(schema)))
        refuses(schema.copy(actions = schema.actions.filterNot { it.id == inverse.id }))
        refuses(schema.copy(actions = schema.actions.map { if (it.id == inverse.id) it.copy(binding = it.binding.copy(bodySchema =
            Json.parseToJsonElement("""{"type":"object","required":["labelId"],"properties":{"labelId":{"type":"string"}}}"""))) else it }))
        for (changed in listOf(assign.copy(binding = assign.binding.copy(requiredBodyFieldNames = emptyList())),
            assign.copy(riskClass = DynamicOperationRiskClass.sharing), assign.copy(label = "Assign user label"),
            assign.copy(binding = assign.binding.copy(path = assign.binding.path.replace("cards", "users"))))) {
            refuses(schema.copy(actions = schema.actions.map { if (it.id == assign.id) changed else it }))
        }
        val relation = assign.copy(binding = assign.binding.copy(path = "/apps/planning-app/api/cards/{cardId}/labels/{labelId}",
            method = HttpMethod.POST, bodySchema = null, bodyContentType = null, bodyFieldNames = emptyList()))
        assertNull(SetMembershipRule().proof(schema, PatternRouteFamily(relation, schema.actions())))
    }

    @Test fun exactReadsDoNotNormalizeDistinctRouteSegments() {
        val schema = membershipContract().toNativeAppSchema()
        val read = schema.read()
        for (path in listOf(read.binding.path + "/", read.binding.path.replace("/cards/", "/cards//"))) {
            val changed = schema.copy(actions = schema.actions.map { if (it.id == read.id) it.copy(binding = it.binding.copy(path = path)) else it })
            assertNull(planNativeMembershipRule(changed, changed.assign().id, "41", JsonPrimitive(5), authority(schema)))
        }
    }

    @Test fun missingWrongTypedDuplicateMalformedAndTruncatedMembershipsCannotBeBaselines() {
        val schema = membershipContract().toNativeAppSchema()
        val bad = listOf(null, JsonNull, JsonPrimitive("[]"), Json.parseToJsonElement("[null]"),
            Json.parseToJsonElement("""[{"id":"3"}]"""), Json.parseToJsonElement("""[{"title":"missing"}]"""),
            JsonArray(raw + raw), JsonArray(listOf(JsonPrimitive("x".repeat(16385)))), JsonArray(List(257) { JsonPrimitive(it) }))
        for (value in bad) assertNull(planNativeMembershipRule(schema, schema.assign().id, "41", JsonPrimitive(5), authority(schema, value)))
        assertIs<NativeMembershipObservation.Absent>(membershipObservation(null))
        assertIs<NativeMembershipObservation.Null>(membershipObservation(JsonNull))
        assertIs<NativeMembershipObservation.Malformed>(membershipObservation(JsonPrimitive(1)))
        assertIs<NativeMembershipObservation.Truncated>(membershipObservation(JsonArray(List(257) { JsonPrimitive(it) })))
        assertIs<NativeMembershipProjectionResult.Duplicate>(JsonArray(raw + raw).projectMembership(NativeMemberScalarType.Integer, NativeMembershipProjection.ObjectId))
    }

    @Test fun staleMissingConflictingParentsAndTargetOrMemberTypesAreRejected() {
        val schema = membershipContract(nested = true, renameParents = true).toNativeAppSchema()
        val valid = authority(schema)
        val record = valid.state.records.single()
        val bad = listOf(valid.copy(currentGeneration = 2), valid.copy(state = valid.state.copy(generation = 0)),
            valid.copy(canEdit = false), valid.copy(readBindings = valid.readBindings - "workspaceId"),
            valid.copy(readBindings = valid.readBindings + ("id" to "5")),
            valid.copy(state = valid.state.copy(records = listOf(record, record))),
            valid.copy(state = valid.state.copy(records = listOf(record.copy(wireValues = record.wireValues + ("boardId" to JsonPrimitive(8)))))),
            valid.copy(state = valid.state.copy(records = listOf(record.copy(bindingContext = mapOf("workspaceId" to "8"))))),
            valid.copy(state = valid.state.copy(records = listOf(record.copy(wireValues = record.wireValues + ("uid" to JsonPrimitive(5)))))),
            authority(schema, id = JsonPrimitive("41")))
        for (value in bad) assertNull(planNativeMembershipRule(schema, schema.assign().id, "41", JsonPrimitive(5), value))
        assertNull(planNativeMembershipRule(schema, schema.assign().id, "41", JsonPrimitive("5"), valid))
        val strings = membershipContract(memberType = "string", targetType = "string").toNativeAppSchema()
        val stringAuthority = authority(strings, Json.parseToJsonElement("""[{"id":"three"}]"""), JsonPrimitive("card-a"))
        assertNotNull(planNativeMembershipRule(strings, strings.assign().id, "card-a", JsonPrimitive("five"), stringAuthority))
        assertNull(planNativeMembershipRule(strings, strings.assign().id, "card-a", JsonPrimitive(5), stringAuthority))
    }

    @Test fun deltaVerificationKeepsEveryUntouchedMemberAndNeverUsesDisplayLabels() {
        val schema = membershipContract().toNativeAppSchema()
        val authority = authority(schema)
        val plan = assertNotNull(planNativeMembershipRule(schema, schema.assign().id, "41", JsonPrimitive(5), authority))
        fun verify(json: String) = plan.verifyRuleState(listOf(authority.state.records.single().copy(
            membershipObservations = mapOf("labels" to membershipObservation(Json.parseToJsonElement(json))))))
        assertIs<NativeWriteVerification.Confirmed>(verify("""[{"id":5,"title":"New"},{"id":3,"title":"Kept"}]"""))
        for (bad in listOf("""[{"id":5}]""", """[{"id":3,"title":"Changed"},{"id":5}]""",
            """[{"id":3,"title":"Kept"},{"id":5},{"id":8}]""", """[{"id":3,"title":"Kept"},{"id":"5"}]""")) {
            assertNotEquals(NativeWriteVerification.Confirmed, verify(bad))
        }
        assertFalse(plan.postcondition.baselineMatches(authority.state.records.single().copy(membershipObservations =
            mapOf("labels" to membershipObservation(Json.parseToJsonElement("""[{"id":3}]"""))))))
    }

    @Test fun scalarAndEmptyMembershipsRetainTypedDeltaSemantics() {
        val schema = membershipContract().toNativeAppSchema()
        for (baseline in listOf("[]", "[3]")) {
            val source = authority(schema, Json.parseToJsonElement(baseline))
            val plan = assertNotNull(planNativeMembershipRule(schema, schema.assign().id, "41", JsonPrimitive(5), source))
            val result = if (baseline == "[]") "[5]" else "[5,3]"
            val record = source.state.records.single().copy(membershipObservations = mapOf("labels" to membershipObservation(Json.parseToJsonElement(result))))
            assertIs<NativeWriteVerification.Confirmed>(plan.verifyRuleState(listOf(record)))
            assertFalse(plan.postcondition.baselineMatches(record))
        }
    }
}
