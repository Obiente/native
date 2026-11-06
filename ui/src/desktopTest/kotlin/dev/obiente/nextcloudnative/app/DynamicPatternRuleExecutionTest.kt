package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DynamicPatternRuleExecutionTest {
    @Test
    fun `feed planner and executor confirm two differently named apps and evidence tiers`() = runBlocking<Unit> {
        for ((app, version) in listOf("news" to "37.0.0", "unknown-reader" to "99.84-preview")) {
            for (command in listOf("read", "unread", "star", "unstar")) DynamicRuleExecutionFixture(app, version).use { f ->
                if (app != "news") f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map { action ->
                    action.copy(provenance = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic-package", "Synthetic contract")))
                })
                val star = command in setOf("star", "unstar")
                f.readBody = DynamicRuleExecutionFixture.records(unread = command != "unread", starred = command == "unstar")
                f.authority = f.freshAuthority()
                f.afterWrite = DynamicRuleExecutionFixture.records(unread = command == "unread" || star, starred = command == "star")
                val request = f.feedRequest(star)
                assertIs<NativeActionExecutionResult.Success>(f.executor().execute(request))
                assertEquals(1, f.writes)
                assertEquals(NextcloudApiCachePolicy.ForceNetwork, f.requests.last().cachePolicy)
                val record = f.entries().single()
                assertEquals(DynamicWriteRuleStatus.confirmed, record.status)
                assertNull(record.pendingVerification)
                assertTrue(f.draftDirectory.listFiles().orEmpty().none { it.extension == "draft" })
                assertTrue(f.events.any { it.code == DynamicDiagnosticCode.WriteRuleConfirmed.code })
            }
        }
    }

    @Test
    fun `missing inverse wrong parent ambiguous identity and type mismatch refuse without traffic`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture(parent = true).use { f ->
            val original = f.request()
            val schema = f.descriptor.toNativeAppSchema()
            val action = original.action
            val noInverse = schema.copy(actions = schema.actions.filterNot { it.binding.path.endsWith("/unread") })
            assertNull(planNativePatternRule(noInverse, action.id, "41", f.authority))
            val wrongParent = f.authority.copy(navigationValues = emptyMap())
            assertNull(planNativePatternRule(schema, action.id, "41", wrongParent))
            assertNull(planNativePatternRule(schema, action.id, "41", f.freshAuthority(DynamicRuleExecutionFixture.records(extra = ",\"folderId\":9"))))
            for (body in listOf(DynamicRuleExecutionFixture.records(extra = ",\"uid\":\"9001\""),
                DynamicRuleExecutionFixture.records(id = JsonPrimitive("41")))) {
                assertNull(planNativePatternRule(schema, action.id, "41", f.freshAuthority(body)))
            }
            val row = DynamicRuleExecutionFixture.records().removeSurrounding("[", "]")
            for (body in listOf("[$row,$row]", "[${List(MAX_DYNAMIC_NATIVE_RECORDS + 1) { row }.joinToString(",")}]")) {
                assertNull(planNativePatternRule(schema, action.id, "41", f.freshAuthority(body)))
            }
            f.authority = f.authority.copy(state = f.authority.state.copy(records = f.authority.state.records + f.authority.state.records))
            assertNull(planNativePatternRule(schema, action.id, "41", f.authority))
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(original))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun `execution re-resolves plan and refuses forged bindings generation risk and read action`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val request = f.request()
            val plan = assertNotNull(request.rulePlan)
            for (forged in listOf(request.copy(values = mapOf("itemId" to "42")),
                request.copy(rulePlan = plan.copy(readBindings = mapOf("secret" to "arbitrary"))),
                request.copy(rulePlan = plan.copy(verificationReadActionId = "invented")),
                request.copy(rulePlan = plan.copy(identity = plan.identity.copy(observed = true))),
                request.copy(rulePlan = plan.copy(expected = true)), request.copy(rulePlan = plan.copy(ruleVersion = 99)))) {
                assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(forged))
            }
            f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map { if (it.id == request.action.id) it.copy(riskClass = DynamicOperationRiskClass.externalEffect) else it })
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request.copy(action = request.action.copy(riskClass = DynamicOperationRiskClass.idempotentStateSet))))
            assertTrue(f.requests.isEmpty())
        }
        DynamicRuleExecutionFixture().use { f ->
            val request = f.request()
            f.authority = f.freshAuthority()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun `observed ids remain per-action proofs and item read bindings use declared names`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture(itemRead = true).use { f ->
            f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map { if (it.binding.method == HttpMethod.GET) it.copy(responseFieldIds = emptyList()) else it })
            f.authority = f.freshAuthority()
            assertFalse(f.authority.state.records.single().actionSafeIdentity)
            val request = f.request()
            assertTrue(assertNotNull(request.rulePlan).identity.observed)
            assertEquals(mapOf("id" to "41"), request.rulePlan.readBindings)
            assertEquals(mapOf("itemId" to "41"), request.values)
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(request))
            assertEquals(listOf(NextcloudApiMethod.GET, NextcloudApiMethod.POST, NextcloudApiMethod.GET), f.requests.map { it.method })
            assertTrue(f.requests.filter { it.method == NextcloudApiMethod.GET }.all { it.relativePath.endsWith("/41") })
            assertFalse(f.authority.state.records.single().actionSafeIdentity)
        }
    }

    @Test
    fun `declared one-boolean item updates retain actual body evidence and verified execution`() = runBlocking<Unit> {
        for (app in listOf("alternate-feeds", "unknown-version")) DynamicRuleExecutionFixture(app, "0.0.987", booleanUpdate = true).use { f ->
            val request = f.feedRequest()
            assertEquals("declared-boolean-state", request.rulePlan?.ruleId)
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(request))
            val body = Json.parseToJsonElement(f.requests.first().body!!.decodeToString())
            assertEquals(buildJsonObject { put("unread", false) }, body)
        }
    }

    @Test
    fun `declared boolean updates retain proven body identities and reviewed state aliases`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture(booleanUpdate = true).use { f ->
            f.descriptor = f.descriptor.copy(actions = f.descriptor.actions.map { action ->
                if (action.binding.method == HttpMethod.GET) action else action.copy(binding = action.binding.copy(body = action.binding.body!!.copy(
                    schema = Json.parseToJsonElement("""{"type":"object","required":["unread","itemId"],"properties":{"unread":{"type":"boolean"},"itemId":{"type":"integer"}}}"""))))
            })
            f.readBody = DynamicRuleExecutionFixture.records().replace("\"unread\":true", "\"isread\":false")
            f.authority = f.freshAuthority()
            f.afterWrite = DynamicRuleExecutionFixture.records(false).replace("\"unread\":false", "\"isread\":true")
            val request = f.feedRequest()
            assertEquals("isread", request.rulePlan?.stateField)
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(request))
            assertEquals(buildJsonObject { put("unread", false); put("itemId", 41) }, Json.parseToJsonElement(f.requests.first().body!!.decodeToString()))
        }
    }

    @Test
    fun `missing inverse cannot fall through the real executor without a plan`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val request = f.request()
            val retained = f.descriptor.actions.filterNot { it.binding.path.endsWith("/unread") }
            f.descriptor = f.descriptor.copy(actions = retained, forms = f.descriptor.forms.filter { form -> retained.any { it.id == form.actionId } })
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request))
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(request.copy(rulePlan = null)))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun `a definite rejection resolves its token leaves probation and performs no read-back`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 403; f.afterWrite = null
            val result = assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertEquals(NativeActionFailureOutcome.Rejected, result.outcome)
            assertEquals(listOf(NextcloudApiMethod.POST), f.requests.map { it.method })
            assertNull(f.entries().single().pendingVerification)
            assertEquals(DynamicWriteRuleStatus.probation, f.entries().single().status)
        }
    }

    @Test
    fun `complete encrypted evidence and token exist before transport starts`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture(parent = true).use { f ->
            f.onSend = {
                val pending = f.entries().single()
                val evidence = assertNotNull(f.services.ruleRecoveryStore(f.session, pending.pendingVerification!!.operationId)?.load())
                assertEquals(pending.key, evidence.key)
                assertEquals(mapOf("folderId" to "3", "itemId" to "41"), evidence.plan.writeBindings)
                assertEquals(mapOf("folderId" to "3"), evidence.plan.readBindings)
                assertEquals(listOf("3", "41"), evidence.plan.target.identities)
                assertTrue(evidence.plan.baseline); assertFalse(evidence.plan.expected)
            }
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request()))
        }
    }

    @Test
    fun `known-before-send reservation failure clears only its token`() = runBlocking<Unit> {
        for (removeFails in listOf(false, true)) DynamicRuleExecutionFixture().use { f ->
            f.removeFails = removeFails
            val original = f.safety
            f.safety = object : DynamicWriteQuarantineStore by original {
                override suspend fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation, generation: Long): Boolean {
                    check(original.reserve(key, pending, generation)); error("Synthetic post-publication failure")
                }
            }
            assertEquals(NativeActionFailureOutcome.Rejected, assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request())).outcome)
            assertEquals(0, f.writes)
            assertNull(f.entries().single().pendingVerification)
            assertEquals(DynamicWriteRuleStatus.probation, f.entries().single().status)
        }
    }

    @Test
    fun `cancellation during read-back is recovered by a status-only read after restart`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.onRead = { throw CancellationException("Synthetic read cancellation") }
            assertFailsWith<CancellationException> { f.executor().execute(f.request()) }
            assertNotNull(f.entries().single().pendingVerification)
            f.onRead = {}; f.restart()
            assertIs<NativeActionExecutionResult.Success>(f.executor().recoverRuleOperations())
            assertEquals(1, f.writes)
            assertNull(f.entries().single().pendingVerification)
        }
    }

    @Test
    fun `retirement during evidence save prevents reservation and traffic`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.afterSave = {
                AccountPrivateMemoryLifecycle.retireAccount(f.session.accountId.storageKey)
                f.safety.removeAccount(f.session.accountId.storageKey)
                f.storage.removeAccount(f.session.accountId.storageKey)
            }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertEquals(0, f.writes)
            assertTrue(f.entries().isEmpty())
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `unchanged successful response and learned type mismatch quarantine instead of adjusted success`() = runBlocking<Unit> {
        for (body in listOf(DynamicRuleExecutionFixture.records(), DynamicRuleExecutionFixture.records(false).replace("\"unread\":false", "\"unread\":\"false\""))) {
            DynamicRuleExecutionFixture().use { f ->
                f.afterWrite = body
                assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
                assertEquals(DynamicWriteRuleStatus.quarantined, f.entries().single().status)
                assertNull(f.entries().single().pendingVerification)
                val count = f.requests.size
                assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request("star")))
                assertEquals(count, f.requests.size)
            }
        }
    }

    @Test
    fun `ambiguous agreeing read confirms while disagreeing read retains operation without replay`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request()))
            assertEquals(1, f.writes)
        }
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            assertNotNull(f.entries().single().pendingVerification)
            repeat(2) { assertUnknown(f.executor().recoverRuleOperations()) }
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun `missing or duplicate target stays unknown and status checks never replay`() = runBlocking<Unit> {
        for (body in listOf("[]", "[${DynamicRuleExecutionFixture.records(false).removeSurrounding("[", "]")},${DynamicRuleExecutionFixture.records(false).removeSurrounding("[", "]")}]")) {
            DynamicRuleExecutionFixture().use { f ->
                f.afterWrite = body
                assertUnknown(f.executor().execute(f.request()))
                assertUnknown(f.executor().recoverRuleOperations())
                assertNotNull(f.entries().single().pendingVerification)
                assertEquals(1, f.writes)
            }
        }
    }

    @Test
    fun `late read-back cannot resolve a replacement operation token`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            var replacement: DynamicPendingRuleOperation? = null
            f.onRead = {
                val record = f.entries().single()
                val token = assertNotNull(record.pendingVerification)
                assertTrue(f.safety.resolve(record.key, token.operationId, DynamicPendingRuleResolution.rejected, 2_000))
                replacement = token.copy(operationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                assertTrue(f.safety.reserve(record.key, assertNotNull(replacement), f.safety.generation(record.key.accountDigest)))
            }
            assertUnknown(f.executor().execute(f.request()))
            assertEquals(replacement, f.entries().single().pendingVerification)
            assertEquals(DynamicWriteRuleStatus.probation, f.entries().single().status)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun `restart blocks second writes but permits explicit identical R1 repeat after status read`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            val token = f.entries().single().pendingVerification
            f.restart()
            assertTrue(f.executor().canRepeatRuleOperation(f.request()))
            assertFalse(f.executor().canRepeatRuleOperation(f.request("unread")))
            val before = f.requests.size
            assertUnknown(f.executor().execute(f.request()))
            assertEquals(before, f.requests.size)
            assertUnknown(f.executor().execute(f.request("unread").copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            f.authority = f.freshAuthority("[${DynamicRuleExecutionFixture.records().removeSurrounding("[", "]")},${DynamicRuleExecutionFixture.records(id = JsonPrimitive(42)).removeSurrounding("[", "]")}]")
            assertFalse(f.executor().canRepeatRuleOperation(f.request(id = "42")))
            assertUnknown(f.executor().execute(f.request(id = "42").copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            assertEquals(1, f.writes)
            assertEquals(token, f.entries().single().pendingVerification)
            f.writeStatus = 200; f.afterWrite = DynamicRuleExecutionFixture.records(false)
            val start = f.requests.size
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request().copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            assertEquals(listOf(NextcloudApiMethod.GET, NextcloudApiMethod.POST, NextcloudApiMethod.GET), f.requests.drop(start).map { it.method })
            assertEquals(2, f.writes)
            assertNull(f.entries().single().pendingVerification)
        }
    }

    @Test
    fun `repeat rejection after earlier unknown is confirmed only by agreeing authoritative read`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            f.writeStatus = 400; f.afterWrite = DynamicRuleExecutionFixture.records(false)
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request().copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            assertEquals(2, f.writes)
        }
    }

    @Test
    fun `cancellation after reserve send or before persistence preserves the correct boundary`() = runBlocking<Unit> {
        for (boundary in listOf("before-save", "after-save", "after-reserve", "send")) {
            DynamicRuleExecutionFixture().use { f ->
                when (boundary) {
                    "before-save" -> f.beforeSave = { throw CancellationException("Synthetic crash") }
                    "after-save" -> f.afterSave = { throw CancellationException("Synthetic crash") }
                    "after-reserve" -> {
                        val original = f.safety
                        f.safety = object : DynamicWriteQuarantineStore by original {
                            override suspend fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation, generation: Long): Boolean {
                                check(original.reserve(key, pending, generation)); throw CancellationException("Synthetic crash")
                            }
                        }
                    }
                    "send" -> f.onSend = { throw CancellationException("Synthetic crash") }
                }
                assertFailsWith<CancellationException> { f.executor().execute(f.request()) }
                f.restart()
                if (boundary in listOf("after-reserve", "send")) {
                    assertNotNull(f.entries().single().pendingVerification)
                    val before = f.writes
                    assertUnknown(f.executor().execute(f.request()))
                    assertEquals(before, f.writes)
                } else {
                    assertTrue(f.entries().none { it.pendingVerification != null })
                    assertEquals(0, f.writes)
                    assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
                }
            }
        }
    }

    @Test
    fun `save failure refusal read failure and failed safety clear never permit second traffic`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.beforeSave = { error("Synthetic storage full") }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertEquals(0, f.writes)
        }
        DynamicRuleExecutionFixture().use { f ->
            f.readStatus = 503
            assertUnknown(f.executor().execute(f.request()))
            assertUnknown(f.executor().execute(f.request()))
            assertEquals(1, f.writes)
            f.readStatus = 200
            val original = f.safety
            f.safety = object : DynamicWriteQuarantineStore by original {
                override suspend fun resolve(key: DynamicWriteRuleKey, operationId: String, resolution: DynamicPendingRuleResolution, nowEpochMillis: Long): Boolean = error("Synthetic clear failure")
            }
            assertUnknown(f.executor().recoverRuleOperations())
            assertNotNull(f.entries().single().pendingVerification)
            f.safety = original; f.removeFails = true
            assertIs<NativeActionExecutionResult.Success>(f.executor().recoverRuleOperations())
            assertNull(f.entries().single().pendingVerification)
        }
    }

    @Test
    fun `missing corrupt or incompatible encrypted evidence remains pending across restart`() = runBlocking<Unit> {
        for (damage in listOf("missing", "corrupt", "version", "rule", "read-binding", "action")) DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            val record = f.entries().single()
            when (damage) {
                "missing" -> f.draftDirectory.listFiles()!!.forEach { it.delete() }
                "corrupt" -> f.draftDirectory.listFiles()!!.forEach { it.writeText("corrupt synthetic recovery") }
                "version" -> f.descriptor = f.descriptor.copy(app = f.descriptor.app.copy(version = "100.0.0"))
                "rule", "read-binding", "action" -> {
                    val token = record.pendingVerification!!.operationId
                    val store = f.services.ruleRecoveryStore(f.session, token)!!
                    val evidence = store.load()!!
                    store.save(when (damage) {
                        "rule" -> evidence.copy(plan = evidence.plan.copy(ruleVersion = 98))
                        "read-binding" -> evidence.copy(plan = evidence.plan.copy(readBindings = mapOf("id" to "42")))
                        else -> evidence.copy(write = f.descriptor.actions.single { it.binding.path.endsWith("/unread") })
                    })
                }
            }
            f.restart()
            assertUnknown(f.executor().recoverRuleOperations())
            assertUnknown(f.executor().execute(f.request()))
            assertEquals(record.pendingVerification, f.entries().single().pendingVerification)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun `an unverified app version cannot resolve pending operations`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            val before = f.requests.size
            val stale = DynamicNextcloudActionExecutor(f.services, f.session, f.descriptor,
                versionStatus = DynamicContractVersionStatus.LastKnownReadOnly)
            assertUnknown(stale.recoverRuleOperations())
            assertEquals(before, f.requests.size)
            assertNotNull(f.entries().single().pendingVerification)
        }
    }

    @Test
    fun `canonical target gate blocks sibling command and durable route gate blocks other item`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            f.onSend = { entered.complete(Unit); finish.await() }
            val first = async { f.executor().execute(f.request()) }
            entered.await()
            assertUnknown(f.executor().execute(f.request("star")))
            f.authority = f.freshAuthority("[${DynamicRuleExecutionFixture.records().removeSurrounding("[", "]")},${DynamicRuleExecutionFixture.records(id = JsonPrimitive(42)).removeSurrounding("[", "]")}]")
            assertUnknown(f.executor().execute(f.request(id = "42")))
            assertEquals(1, f.writes)
            finish.complete(Unit); first.await()
        }
    }

    @Test
    fun `long canonical routes use the same safety key for ordinary sibling commands`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture(routePadding = 520).use { f ->
            val sibling = f.descriptor.actions.single { it.binding.path.endsWith("/read") }.let { action ->
                action.copy(id = "item-touch", binding = action.binding.copy(path = action.binding.path.removeSuffix("read") + "touch"))
            }
            f.descriptor = f.descriptor.copy(actions = f.descriptor.actions + sibling)
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            val spec = f.descriptor.toNativeAppSchema().actions.single { it.id == sibling.id }
            assertUnknown(f.executor().execute(NativeActionRequest.Submit(spec, mapOf("itemId" to "41"), true)))
            assertEquals(1, f.writes)
            assertNotNull(f.entries().single().pendingVerification)
        }
    }

    @Test
    fun `account retirement fences late completion and removes both stores`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            f.onSend = { entered.complete(Unit); finish.await() }
            val first = async { f.executor().execute(f.request()) }
            entered.await()
            AccountPrivateMemoryLifecycle.retireAccount(f.session.accountId.storageKey)
            f.safety.removeAccount(f.session.accountId.storageKey)
            f.storage.removeAccount(f.session.accountId.storageKey)
            finish.complete(Unit)
            assertUnknown(first.await())
            assertTrue(f.entries().isEmpty())
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `safety json and diagnostics contain no values or payload fingerprints`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            val safety = f.safetyDirectory.listFiles()!!.joinToString { it.readText() }
            for (private in listOf("Synthetic article", "Synthetic content", "writeBindings", "targetId", "expected", "baseline", "fingerprint", "\"41\"")) assertFalse(private in safety)
            val eventFields = f.events.flatMap { it.fields }.map { it.name }.toSet()
            assertTrue(eventFields.all { it in setOf("rule_id", "rule_version", "evidence_tier", "risk_class") })
            val bytes = f.draftDirectory.listFiles()!!.single().readBytes().decodeToString()
            assertFalse("writeBindings" in bytes)
        }
    }

    private fun assertUnknown(result: NativeActionExecutionResult) = assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(result).outcome)
}
