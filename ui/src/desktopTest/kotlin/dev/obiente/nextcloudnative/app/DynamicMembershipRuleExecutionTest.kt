package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DynamicMembershipRuleExecutionTest {
    @Test fun forgedMemberBindingsAndOmittedBodyCannotUseEitherExecutionPath() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val request = f.request()
            for (forged in listOf(request.copy(values = request.values - "labelId"),
                request.copy(values = request.values + ("labelId" to "8")), request.copy(membershipPlan = null),
                request.copy(membershipPlan = request.membershipPlan!!.copy(targetId = "5")))) {
                assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(forged))
            }
            assertTrue(f.transport.requests.isEmpty())
        }
    }

    @Test fun cancellationAfterDurableReservationKeepsTokenAndEvidenceWithoutSending() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val storage = f.transport.safety
            f.transport.safety = object : DynamicWriteQuarantineStore by storage {
                override suspend fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation, generation: Long): Boolean {
                    check(storage.reserve(key, pending, generation))
                    throw CancellationException("Synthetic interruption after reservation")
                }
            }
            assertFailsWith<CancellationException> { f.executor().execute(f.request()) }
            assertEquals(0, f.transport.writes)
            val record = f.transport.entries().single()
            val token = assertNotNull(record.pendingVerification)
            assertNotNull(f.transport.services.ruleRecoveryStore(f.transport.session, token.operationId)?.load())
            f.restart()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            assertEquals(token, f.transport.entries().single().pendingVerification)
            assertEquals(0, f.transport.writes)
        }
    }

    @Test fun twoUnknownAppsAndBothRouteShapesSendOnceAndVerifyFullMembership() = runBlocking<Unit> {
        for (app in listOf("planning-app", "other-planning")) for (nested in listOf(false, true)) {
            MembershipExecutionFixture(app, nested).use { f ->
                if (app == "other-planning") f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map {
                    it.copy(provenance = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic-package", "Synthetic contract")))
                })
                val request = f.request()
                f.transport.onSend = {
                    val record = f.transport.entries().single()
                    val evidence = assertNotNull(f.transport.services.ruleRecoveryStore(f.transport.session, record.pendingVerification!!.operationId)?.load())
                    assertEquals(2, evidence.formatVersion)
                    assertIs<NativeMembershipRulePlan>(evidence.plan)
                    assertNotNull(evidence.inverseWrite)
                }
                assertIs<NativeActionExecutionResult.Success>(f.executor().execute(request))
                assertEquals(1, f.transport.writes)
                assertEquals(listOf(NextcloudApiMethod.GET, NextcloudApiMethod.POST, NextcloudApiMethod.GET), f.transport.requests.map { it.method })
                assertTrue(f.transport.requests.filter { it.method == NextcloudApiMethod.GET }.all { it.cachePolicy == NextcloudApiCachePolicy.ForceNetwork })
                val sent = Json.parseToJsonElement(f.transport.requests.single { it.method == NextcloudApiMethod.POST }.body!!.decodeToString())
                assertEquals(Json.parseToJsonElement("""{"labelId":5}"""), sent)
                assertNull(f.transport.entries().single().pendingVerification)
                assertEquals(DynamicWriteRuleStatus.confirmed, f.transport.entries().single().status)
                f.authority = f.freshAuthority()
                f.transport.afterWrite = MembershipExecutionFixture.body()
                assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request(assign = false)))
                assertEquals(2, f.transport.writes)
            }
        }
    }

    @Test fun changedBaselineAndStaleAuthorityNeverSend() = runBlocking<Unit> {
        MembershipExecutionFixture(nested = true).use { f ->
            val request = f.request()
            f.transport.readBody = MembershipExecutionFixture.body("""[{"id":3,"title":"Changed"}]""")
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request))
            assertEquals(0, f.transport.writes)
            assertTrue(f.transport.entries().none { it.pendingVerification != null })
            f.transport.requests.clear()
            f.authority = f.freshAuthority()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request))
            assertTrue(f.transport.requests.isEmpty())
        }
    }

    @Test fun wrongTargetDuplicateTargetMalformedAndConcurrentMembershipQuarantineWithoutInverse() = runBlocking<Unit> {
        for (body in listOf(MembershipExecutionFixture.body("[]"), MembershipExecutionFixture.body("null"),
            MembershipExecutionFixture.body("""[{"id":3,"title":"Kept"},{"id":5},{"id":8}]"""),
            MembershipExecutionFixture.body("""[{"id":3,"title":"Changed"},{"id":5}]"""),
            MembershipExecutionFixture.body("""[{"id":3,"title":"Kept"},{"id":5},{"id":5}]"""))) {
            MembershipExecutionFixture().use { f ->
                f.transport.afterWrite = body
                assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
                assertEquals(1, f.transport.writes)
                assertEquals(DynamicWriteRuleStatus.quarantined, f.transport.entries().single().status)
                assertTrue(f.transport.requests.none { it.relativePath.endsWith("/removeLabel") })
            }
        }
        for (body in listOf(MembershipExecutionFixture.body().replace("\"id\":41", "\"id\":5"),
            "[${MembershipExecutionFixture.body()},${MembershipExecutionFixture.body()}]")) MembershipExecutionFixture().use { f ->
            f.transport.afterWrite = body
            val result = assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertEquals(NativeActionFailureOutcome.Unknown, result.outcome)
            assertNotNull(f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun unknownRestartStatusOnlyAndExplicitExactRepeat() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            val first = f.request()
            assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(first)).outcome)
            val token = f.transport.entries().single().pendingVerification
            f.restart()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            assertEquals(1, f.transport.writes)
            assertEquals(token, f.transport.entries().single().pendingVerification)
            for (wrong in listOf(f.request(member = JsonPrimitive(8)), f.request(assign = false))) {
                assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(wrong.copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            }
            assertEquals(1, f.transport.writes)
            f.transport.writeStatus = 200; f.transport.afterWrite = MembershipExecutionFixture.body("""[{"id":3,"title":"Kept"},{"id":5}]""")
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request().copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            assertEquals(2, f.transport.writes)
            assertNull(f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun changedBaselineContractVersionAndInverseContractBlockRepeat() = runBlocking<Unit> {
        for (change in listOf("baseline", "read", "inverse", "version")) MembershipExecutionFixture().use { f ->
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            f.executor().execute(f.request())
            val request = f.request().copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)
            when (change) {
                "baseline" -> { f.transport.readBody = MembershipExecutionFixture.body("""[{"id":3,"title":"Changed"}]"""); f.authority = f.freshAuthority() }
                "read" -> f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map { if (it.id == f.read.id) it.copy(label = "Changed contract") else it })
                "inverse" -> f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map { if (it.binding.path.endsWith("/removeLabel")) it.copy(label = "Changed contract") else it })
                "version" -> f.descriptor = f.descriptor.copy(app = f.descriptor.app.copy(version = "999.1"))
            }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request))
            assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            assertEquals(1, f.transport.writes)
            assertNotNull(f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun cancellationBeforeReservationAndAfterSendPreserveCorrectRecoveryBoundary() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.beforeSave = { throw CancellationException("synthetic cancellation") }
            assertFailsWith<CancellationException> { f.executor().execute(f.request()) }
            assertEquals(0, f.transport.writes)
            assertTrue(f.transport.entries().none { it.pendingVerification != null })
        }
        MembershipExecutionFixture().use { f ->
            f.transport.onSend = { throw CancellationException("synthetic cancellation") }
            assertFailsWith<CancellationException> { f.executor().execute(f.request()) }
            assertNotNull(f.transport.entries().single().pendingVerification)
            f.restart(); f.transport.onSend = {}
            f.executor().recoverRuleOperations()
            assertEquals(1, f.transport.writes)
            assertNotNull(f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun mismatchedRecordedInverseCannotAuthorizeARepeat() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            f.executor().execute(f.request())
            val token = f.transport.entries().single().pendingVerification!!
            val storage = f.transport.services.ruleRecoveryStore(f.transport.session, token.operationId)!!
            val evidence = storage.load()!!
            storage.save(evidence.copy(inverseWrite = evidence.write))
            f.restart()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request().copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            assertEquals(1, f.transport.writes)
            assertEquals(token, f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun saveAndCleanupFailuresDoNotCauseAnUnrecordedSendOrReplay() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.beforeSave = { error("synthetic disk full") }
            assertEquals(NativeActionFailureOutcome.Rejected, assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request())).outcome)
            assertEquals(0, f.transport.writes)
        }
        MembershipExecutionFixture().use { f ->
            f.transport.removeFails = true
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request()))
            assertNull(f.transport.entries().single().pendingVerification)
            f.restart(); f.executor().recoverRuleOperations()
            assertEquals(1, f.transport.writes)
        }
    }

    @Test fun stoppingMembershipRecoveryRequiresExactTokenAndSuccessfulEvidenceRemoval() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            val executor = f.executor()
            executor.execute(f.request())
            val record = f.transport.entries().single()
            val handle = DynamicPendingRuleHandle(record.key, record.pendingVerification!!.operationId)
            assertFalse(executor.stopCheckingRuleOperation(handle.copy(operationId = "stale-token")))
            f.transport.removeFails = true
            assertFalse(executor.stopCheckingRuleOperation(handle))
            assertNotNull(f.transport.entries().single().pendingVerification)
            f.transport.removeFails = false
            assertTrue(executor.stopCheckingRuleOperation(handle))
            assertNull(f.transport.entries().single().pendingVerification)
            assertEquals(DynamicWriteQuarantineReason.abandonedUnknown, f.transport.entries().single().reason)
            assertIs<NativeActionExecutionResult.Failure>(executor.execute(f.request()))
            assertEquals(1, f.transport.writes)
        }
    }

    @Test fun generationRetirementAndStaleTokenCannotConfirm() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val request = f.request()
            f.transport.afterSave = { f.authority = f.freshAuthority() }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request))
            assertEquals(0, f.transport.writes)
        }
        MembershipExecutionFixture().use { f ->
            val executor = f.executor()
            f.transport.onSend = { AccountPrivateMemoryLifecycle.retireAccount(f.transport.session.accountId.storageKey) }
            assertIs<NativeActionExecutionResult.Failure>(executor.execute(f.request()))
            assertEquals(1, f.transport.writes)
            assertNotNull(f.transport.entries().single().pendingVerification)
        }
        MembershipExecutionFixture().use { f ->
            f.transport.onSend = {
                val record = f.transport.entries().single()
                assertTrue(f.transport.safety.resolve(record.key, record.pendingVerification!!.operationId, DynamicPendingRuleResolution.rejected, 1))
                assertTrue(f.transport.safety.reserve(record.key, DynamicPendingRuleOperation("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 2), f.transport.safety.generation(record.key.accountDigest)))
            }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", f.transport.entries().single().pendingVerification?.operationId)
        }
    }

    @Test fun versionOneBooleanEvidenceStillRecoversWithoutSendingOrChangingToken() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            f.executor().execute(f.request())
            val record = f.entries().single()
            val path = "nextcloud-native:rule-operation:${record.pendingVerification!!.operationId}"
            val draft = f.services.textEditorDraftStore(f.session, path)!!
            val stored = draft.load()!!
            val root = Json.parseToJsonElement(stored.text).jsonObject
            val legacy = JsonObject(root.filterKeys { it != "inverseWrite" } + ("formatVersion" to JsonPrimitive(1)) +
                ("plan" to JsonObject(root.getValue("plan").jsonObject - "type")))
            draft.save(stored.copy(text = legacy.toString()))
            f.restart(); f.readBody = DynamicRuleExecutionFixture.records(unread = false)
            assertIs<NativeActionExecutionResult.Success>(f.executor().recoverRuleOperations())
            assertEquals(1, f.writes)
            assertNull(f.entries().single().pendingVerification)
        }
    }

    @Test fun aMalformedLegacyVersionCannotBeTreatedAsVersionOne() = runBlocking<Unit> {
        for (version in listOf(JsonNull, JsonPrimitive("1"), JsonPrimitive("unknown"), JsonPrimitive(true), JsonObject(emptyMap()))) {
            DynamicRuleExecutionFixture().use { f ->
                f.writeStatus = 503; f.afterWrite = null
                f.executor().execute(f.request())
                val token = f.entries().single().pendingVerification!!
                val draft = f.services.textEditorDraftStore(f.session, "nextcloud-native:rule-operation:${token.operationId}")!!
                val stored = draft.load()!!
                val root = Json.parseToJsonElement(stored.text).jsonObject
                val malformed = JsonObject(root.filterKeys { it != "inverseWrite" } + ("formatVersion" to version) +
                    ("plan" to JsonObject(root.getValue("plan").jsonObject - "type")))
                draft.save(stored.copy(text = malformed.toString()))
                f.restart(); f.readBody = DynamicRuleExecutionFixture.records(unread = false)
                assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
                assertEquals(token, f.entries().single().pendingVerification)
                assertEquals(1, f.writes)
            }
        }
    }
}
