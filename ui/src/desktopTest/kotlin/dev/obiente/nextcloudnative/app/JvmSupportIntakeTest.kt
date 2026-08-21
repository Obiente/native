package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient

private fun JvmSupportIntake.submittedRecordId(): String =
    (states().value as SupportDiagnosticsSubmissionState.Submitted).reports.single().recordId

class JvmSupportIntakeTest {
    @Test
    fun supportConversationMessageBodiesCannotBeReplayed() {
        val body = OneShotSupportMessageRequestBody("private reply".encodeToByteArray())
        assertTrue(body.isOneShot())
    }

    @Test
    fun refreshesPrivateConversationAndPersistsReadPosition() = runBlocking {
        testFixture().use { fixture ->
            val maintainerMessageId = UUID.randomUUID().toString()
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("The updater failed.", "nightly", emptyList())
            fixture.server.enqueue(privateStatusResponse(
                "needs_information", listOf(maintainerMessageId to "Which installation stage failed?"),
            ))
            assertEquals(SupportDiagnosticsConversationResult.Updated, fixture.intake.refreshCompletedReports())

            val refreshed = assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
                .reports.single()
            assertEquals("needs_information", refreshed.status)
            assertTrue(refreshed.statusChanged)
            assertEquals(1, refreshed.unreadMaintainerMessages)
            assertEquals("Which installation stage failed?", refreshed.messages.single().body)
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val refreshRequest = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", refreshRequest.method)
            assertTrue(refreshRequest.url.encodedPath.startsWith("/api/v1/reports/"))

            assertTrue(fixture.intake.markCompletedReportRead(fixture.intake.submittedRecordId()))
            fixture.intake.close()
            fixture.newIntake().use { restored ->
                fixture.server.enqueue(privateStatusResponse(
                    "needs_information", listOf(maintainerMessageId to "Which installation stage failed?"),
                ))
                assertEquals(SupportDiagnosticsConversationResult.Updated, restored.refreshCompletedReports())
                val afterRestart = assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
                    .reports.single()
                assertFalse(afterRestart.statusChanged)
                assertEquals(0, afterRestart.unreadMaintainerMessages)
            }
        }
    }

    @Test
    fun sendsReporterReplyThroughPrivateCapabilityWithoutExposingItInStateErrors() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("The updater failed.", "nightly", emptyList())
            fixture.server.enqueue(
                privateStatusResponse(
                    status = "needs_information",
                    messages = emptyList(),
                    reporterMessage = "It failed after the download completed.",
                ),
            )

            assertEquals(SupportDiagnosticsConversationResult.Updated, fixture.intake.sendCompletedReportMessage(
                fixture.intake.submittedRecordId(), "It failed after the download completed.",
            ))

            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val reply = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", reply.method)
            assertTrue(reply.url.encodedPath.matches(Regex("/api/v1/reports/[A-Za-z0-9_-]{43}/messages")))
            assertTrue(reply.body?.utf8().orEmpty().contains("It failed after the download completed."))
            val submitted = assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(SupportDiagnosticsMessageAuthor.Reporter, submitted.reports.single().messages.single().author)
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            val uncertain = fixture.intake.sendCompletedReportMessage(
                fixture.intake.submittedRecordId(), "Do not resend without refreshing.")
            assertIs<SupportDiagnosticsConversationResult.ReplyDeliveryUnknown>(uncertain)
            assertFalse(uncertain.message.contains(fixture.statusUrl))
        }
    }

    @Test
    fun submitsSanitizedBundleAndRemovesTemporaryArchive() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.intake.submit("Visit https://private.example.test and refresh.", "nightly", emptyList())

            val submitted = assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals("OBI-ABCDE-23456", submitted.supportCode)
            assertEquals(
                1,
                fixture.completedDescriptors().size,
            )
            val request = fixture.server.takeRequest(2, TimeUnit.SECONDS)
            requireNotNull(request)
            assertEquals("POST", request.method)
            assertEquals("/api/v1/reports", request.url.encodedPath)
            assertTrue(request.headers["Idempotency-Key"].orEmpty().matches(Regex("[A-Za-z0-9_-]{43}")))
            val body = request.body?.utf8().orEmpty()
            assertTrue(body.contains("nextcloud-native"))
            assertFalse(body.contains("private.example.test"))
            assertTrue(body.contains("<url:"))
            assertFalse(body.contains("password"))
        }
    }

    @Test
    fun reconcilesAmbiguousUploadBeforeOfferingRetry() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(2, fixture.server.requestCount)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val reconcile = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], reconcile.headers["Idempotency-Key"])
            assertEquals("/api/v1/receipts", reconcile.url.encodedPath)
            assertEquals(
                1,
                fixture.completedDescriptors().size,
            )
        }
    }

    @Test
    fun permanentRejectionRemovesTemporaryArchive() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder().code(400)
                    .body("""{"contractVersion":1,"code":"invalid_report","message":"Report schema rejected."}""")
                    .build(),
            )

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val rejected = assertIs<SupportDiagnosticsSubmissionState.Rejected>(fixture.intake.states().value)
            assertEquals("Report schema rejected.", rejected.message)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun restoresInterruptedSubmissionAndReusesIdempotencyKey() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val interrupted = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(interrupted.outcomeAmbiguous)
            val firstUpload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val idempotencyKey = requireNotNull(firstUpload.headers["Idempotency-Key"])
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)

            fixture.intake.close()
            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            val retry = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(idempotencyKey, retry.headers["Idempotency-Key"])
            assertEquals(
                1,
                fixture.completedDescriptors().size,
            )
        }
    }

    @Test
    fun restoresAmbiguousSubmissionWhenItsArchiveWasLost() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val archiveName = requireNotNull(
                Regex("\\\"archiveName\\\":\\\"([^\\\"]+)\\\"").find(descriptor.readText())?.groupValues?.get(1),
            )
            fixture.intake.close()
            assertTrue(File(fixture.temporaryRoot, archiveName).delete())

            fixture.newIntake().use { restored ->
                assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
                assertTrue(descriptor.isFile)
                fixture.server.enqueue(receiptResponse(fixture.statusUrl))

                restored.retry()

                assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
                val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", reconciliation.method)
                assertEquals(upload.headers["Idempotency-Key"], reconciliation.headers["Idempotency-Key"])
            }
        }
    }

    @Test
    fun exposesAccountNeutralBlockForAnotherLocalAccount() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            fixture.intake.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)

            val blocked = assertIs<SupportDiagnosticsSubmissionState.BlockedByAnotherAccount>(
                fixture.intake.states().value,
            )
            assertTrue(blocked.message.contains("another signed-in account"))
            fixture.intake.submit("B also failed.", "nightly", emptyList())
            assertIs<SupportDiagnosticsSubmissionState.BlockedByAnotherAccount>(fixture.intake.states().value)
            fixture.intake.retry()
            assertFalse(fixture.intake.cancel())
            assertEquals(1, fixture.server.requestCount)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)

            fixture.intake.setActiveAccountIdentity(TEST_ACCOUNT_IDENTITY)

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            Unit
        }
    }

    @Test
    fun capturesDiagnosticsForTheAccountSnapshottedBySubmission() = runBlocking {
        testFixture().use { fixture ->
            fixture.diagnostics.recordForAccountIdentity(
                TEST_ACCOUNT_IDENTITY,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Network,
                    operation = "network.account_a",
                    outcome = "failed",
                ),
            )
            fixture.diagnostics.recordForAccountIdentity(
                OTHER_ACCOUNT_IDENTITY,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Network,
                    operation = "network.account_b",
                    outcome = "failed",
                ),
            )
            fixture.diagnostics.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val descriptor = File(fixture.temporaryRoot, "pending.json").readText()
            assertTrue(descriptor.contains("network.account_a"))
            assertFalse(descriptor.contains("network.account_b"))
        }
    }

    @Test
    fun restoresSuccessfulReceiptForItsAccount() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            fixture.intake.close()

            val restored = fixture.newIntake()

            val submitted = assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            val report = submitted.reports.single()
            assertEquals("OBI-ABCDE-23456", submitted.supportCode)
            assertEquals(report.recordId, submitted.recordId)
            assertTrue(Instant.parse(report.createdAt).isBefore(Instant.parse(report.retentionUntil)))
            assertFalse(report.toString().contains(fixture.statusUrl))
            assertEquals(1, fixture.completedDescriptors().size)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
            restored.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(restored.states().value)
            assertIs<SupportDiagnosticsDeletionResult.Failed>(restored.deleteCompletedReport(report.recordId))
            assertEquals(1, fixture.server.requestCount)
            Unit
        }
    }

    @Test
    fun deletesSubmittedReceiptAfterAcceptedDeletionIsReconciled() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            assertEquals(1, fixture.completedDescriptors().size)
            fixture.server.enqueue(MockResponse.Builder().code(202).body("{}").build())
            fixture.server.enqueue(MockResponse.Builder().code(404).body("{}").build())

            val result = fixture.intake.deleteCompletedReport(fixture.intake.submittedRecordId())

            assertIs<SupportDiagnosticsDeletionResult.Deleted>(result)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
            assertTrue(fixture.completedDescriptors().isEmpty())
            assertEquals("POST", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
        }
    }

    @Test
    fun keepsSubmittedReceiptWhenEarlyDeletionFails() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            fixture.server.enqueue(MockResponse.Builder().code(503).body("{}").build())

            val result = fixture.intake.deleteCompletedReport(fixture.intake.submittedRecordId())

            assertIs<SupportDiagnosticsDeletionResult.Failed>(result)
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.completedDescriptors().size)
        }
    }

    @Test
    fun reportsCompletedDeletionOnlyAfterLocalReceiptRemovalIsDurable() = runBlocking {
        var failDirectorySync = false
        testFixture(
            directorySync = {
                if (failDirectorySync) throw IOException("Synthetic completed receipt deletion sync failure.")
            },
        ).use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            failDirectorySync = true
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            val firstResult = fixture.intake.deleteCompletedReport(fixture.intake.submittedRecordId())

            assertIs<SupportDiagnosticsDeletionResult.Failed>(firstResult)
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            failDirectorySync = false
            fixture.server.enqueue(MockResponse.Builder().code(404).body("{}").build())

            val retryResult = fixture.intake.deleteCompletedReport(fixture.intake.submittedRecordId())

            assertIs<SupportDiagnosticsDeletionResult.Deleted>(retryResult)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
        }
        Unit
    }

    @Test
    fun deletionFailureFallsBackToIdleWhenTheReceiptExpiresInFlight() = runBlocking {
        testFixture().use { fixture ->
            val retentionUntil = Instant.now().plusSeconds(3)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, retentionUntil = retentionUntil))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            fixture.server.enqueue(
                MockResponse.Builder().code(503).body("{}").headersDelay(4, TimeUnit.SECONDS).build(),
            )

            val result = fixture.intake.deleteCompletedReport(fixture.intake.submittedRecordId())

            assertIs<SupportDiagnosticsDeletionResult.Failed>(result)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
        }
        Unit
    }

    @Test
    fun requiresAnAccountBeforeSupportSubmission() = runBlocking {
        testFixture().use { fixture ->
            fixture.intake.setActiveAccountIdentity(null)

            assertIs<SupportDiagnosticsSubmissionState.AccountRequired>(fixture.intake.states().value)
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.AccountRequired>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun retriesTransientCompletedDescriptorReadWithoutDeletingTheReceipt() = runBlocking {
        val failReads = AtomicBoolean(false)
        testFixture(
            descriptorCleanupRetryMillis = 10L,
            completedDescriptorRead = { descriptor ->
                if (failReads.get()) throw IOException("Synthetic transient completed receipt read failure.")
                descriptor.readText()
            },
        ).use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            val descriptor = fixture.completedDescriptors().single()
            fixture.intake.close()
            failReads.set(true)

            fixture.newIntake().use { restored ->
                val unavailable = assertIs<SupportDiagnosticsSubmissionState.Unsupported>(restored.states().value)
                assertTrue(unavailable.reason.contains("retry automatically"))
                assertTrue(descriptor.isFile)

                failReads.set(false)
                val submitted = withTimeout(5_000) {
                    restored.states().first { state -> state is SupportDiagnosticsSubmissionState.Submitted }
                }
                assertEquals("OBI-ABCDE-23456", assertIs<SupportDiagnosticsSubmissionState.Submitted>(submitted).supportCode)
                assertTrue(descriptor.isFile)
            }
        }
    }

    @Test
    fun retriesDurableCleanupOfRejectedCompletedReceipt() = runBlocking {
        var directorySyncAttempts = 0
        testFixture(
            directorySync = {
                directorySyncAttempts += 1
                if (directorySyncAttempts == 1) throw IOException("Synthetic completed receipt sync failure.")
            },
            descriptorCleanupRetryMillis = 10L,
            invalidCompletedBeforeInitialization = true,
        ).use { fixture ->
            withTimeout(5_000) {
                while (directorySyncAttempts < 2) delay(10)
            }
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun preservesCompletedReceiptsForEachAccountAndReport() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, supportCode = "OBI-ABCDE-23456"))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, supportCode = "OBI-MNPQR-34567"))
            fixture.intake.submit("A second refresh failed.", "nightly", emptyList())

            fixture.intake.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)
            fixture.diagnostics.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, supportCode = "OBI-FGHJK-6789A"))
            fixture.intake.submit("B refresh failed.", "nightly", emptyList())

            assertEquals(3, fixture.completedDescriptors().size)
            fixture.intake.close()
            val restored = fixture.newIntake()
            val accountA = assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            assertEquals(
                setOf("OBI-ABCDE-23456", "OBI-MNPQR-34567"),
                accountA.reports.map { it.supportCode }.toSet(),
            )

            restored.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)

            val accountB = assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            assertEquals(listOf("OBI-FGHJK-6789A"), accountB.reports.map { it.supportCode })
        }
    }

    @Test
    fun rejectsReceiptBeyondTheConsentedRetentionWindow() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, retentionDays = 31))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertTrue(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun rejectsFreshReceiptWithAFutureServerTimestamp() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, createdAtOffsetDays = 1))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertTrue(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun rejectsFreshReceiptThatHasAlreadyExpired() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(
                    fixture.statusUrl,
                    createdAtOffsetDays = -1,
                    retentionUntil = Instant.now().minusSeconds(1),
                ),
            )

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertTrue(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun rejectsReceiptWithAnUnusableDeletionCapability() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(
                    fixture.statusUrl,
                    deletionUrl = "https://support.invalid/r/abcdefghijklmnopqrstuvwxyzABCDEFGH_12345678",
                ),
            )

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertTrue(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun rejectsSupportUploadWhenThePlatformMutationGateIsClosed() = runBlocking {
        var mutationsAllowed = false
        testFixture(supportMutationsAllowed = { mutationsAllowed }).use { fixture ->
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Unsupported>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)

            mutationsAllowed = true
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun rechecksThePlatformMutationGateAtTheUploadBoundary() = runBlocking {
        var gateChecks = 0
        testFixture(supportMutationsAllowed = { ++gateChecks == 1 }).use { fixture ->
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertFalse(retryable.outcomeAmbiguous)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
        }
    }

    @Test
    fun keepsCancellationBusyUntilTheActiveOperationStops() = runBlocking {
        val transportGateEntered = CountDownLatch(1)
        val allowTransportGateToFinish = CountDownLatch(1)
        var gateChecks = 0
        testFixture(
            supportMutationsAllowed = {
                gateChecks += 1
                if (gateChecks == 1) {
                    true
                } else {
                    transportGateEntered.countDown()
                    check(allowTransportGateToFinish.await(5, TimeUnit.SECONDS))
                    true
                }
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(transportGateEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)

            allowTransportGateToFinish.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationAtTheUploadMarkerCannotBeOverwrittenByAStorageRejection() = runBlocking {
        val markerEntered = CountDownLatch(1)
        val allowMarkerTransition = CountDownLatch(1)
        testFixture(
            beforeUploadMarker = {
                markerEntered.countDown()
                check(allowMarkerTransition.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(markerEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)

            allowMarkerTransition.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun transportGateClosingBeforePostStillAllowsLocalCancellation() = runBlocking {
        var gateChecks = 0
        testFixture(
            supportMutationsAllowed = {
                gateChecks += 1
                gateChecks <= 2
            },
        ).use { fixture ->
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)

            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun closedTransportGateCannotReinsertALocallyCancelledSubmission() = runBlocking {
        val gateFailureDispositionEntered = CountDownLatch(1)
        val allowGateFailureDisposition = CountDownLatch(1)
        var gateChecks = 0
        testFixture(
            supportMutationsAllowed = {
                gateChecks += 1
                gateChecks <= 2
            },
            beforeTransportGateFailureDisposition = {
                gateFailureDispositionEntered.countDown()
                check(allowGateFailureDisposition.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(gateFailureDispositionEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowGateFailureDisposition.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun cancellationDuringArchiveValidationCompletesCleanly() = runBlocking {
        val archiveValidationEntered = CountDownLatch(1)
        val allowArchiveValidation = CountDownLatch(1)
        testFixture(
            beforeUploadArchiveValidation = {
                archiveValidationEntered.countDown()
                check(allowArchiveValidation.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(archiveValidationEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowArchiveValidation.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationWinsBeforeTheUploadCallIsRegistered() = runBlocking {
        val registrationEntered = CountDownLatch(1)
        val allowRegistration = CountDownLatch(1)
        testFixture(
            beforeCallRegistration = {
                registrationEntered.countDown()
                check(allowRegistration.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(registrationEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertEquals(0, fixture.server.requestCount)

            allowRegistration.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun cancellationPendingStopsRetryBeforeAnotherUploadStarts() = runBlocking {
        val retryTransitionEntered = CountDownLatch(1)
        val allowRetryTransition = CountDownLatch(1)
        testFixture(
            beforeRetryUploadTransition = {
                retryTransitionEntered.countDown()
                check(allowRetryTransition.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertFalse(retryable.outcomeAmbiguous)
            assertEquals("POST", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val retry = launch(Dispatchers.Default) { fixture.intake.retry() }
            assertTrue(retryTransitionEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowRetryTransition.countDown()
            retry.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(3, fixture.server.requestCount)
            assertNull(fixture.server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun tombstoneRecoveryAgesFromTheLatestUploadAttempt() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertFalse(retryable.outcomeAmbiguous)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val oldCreatedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\\\"createdAtEpochMillis\\\":\\d+"),
                    "\"createdAtEpochMillis\":$oldCreatedAt",
                ),
            )
            fixture.intake.close()
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.newIntake().use { restored ->
                assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)

                restored.retry()

                assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
                val retriedUpload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", retriedUpload.method)
                assertEquals(upload.headers["Idempotency-Key"], retriedUpload.headers["Idempotency-Key"])
            }
        }
    }

    @Test
    fun cancellationWinsAgainstConcurrentRecoveryExpiry() = runBlocking {
        val expiryDispositionEntered = CountDownLatch(1)
        val allowExpiryDisposition = CountDownLatch(1)
        var nowEpochMillis = System.currentTimeMillis()
        testFixture(
            currentTimeMillis = { nowEpochMillis },
            beforeRecoveryExpiryDisposition = {
                expiryDispositionEntered.countDown()
                check(allowExpiryDisposition.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            nowEpochMillis += TimeUnit.DAYS.toMillis(31)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val retry = launch(Dispatchers.Default) { fixture.intake.retry() }
            assertTrue(expiryDispositionEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowExpiryDisposition.countDown()
            retry.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val tombstone = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", tombstone.method)
            assertEquals(upload.headers["Idempotency-Key"], tombstone.headers["Idempotency-Key"])
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun localCancellationDuringRetryPersistenceRemainsTerminal() = runBlocking {
        val retryTransitionCompleted = CountDownLatch(1)
        val allowRetryPersistence = CountDownLatch(1)
        var allowAllMutations = false
        var initialGateChecks = 0
        testFixture(
            supportMutationsAllowed = {
                allowAllMutations || ++initialGateChecks == 1
            },
            afterRetryUploadTransition = {
                retryTransitionCompleted.countDown()
                check(allowRetryPersistence.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            allowAllMutations = true

            val retry = launch(Dispatchers.Default) { fixture.intake.retry() }
            assertTrue(retryTransitionCompleted.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowRetryPersistence.countDown()
            retry.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun markerWriteFailurePreservesThePriorTombstoneCapability() = runBlocking {
        val rejectNextDirectorySync = AtomicBoolean(false)
        var markerCount = 0
        testFixture(
            directorySync = {
                if (rejectNextDirectorySync.compareAndSet(true, false)) {
                    throw IOException("Synthetic marker persistence failure.")
                }
            },
            beforeUploadMarker = {
                markerCount += 1
                if (markerCount == 2) rejectNextDirectorySync.set(true)
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)

            fixture.intake.retry()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertNull(fixture.server.takeRequest(200, TimeUnit.MILLISECONDS))
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val tombstone = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", tombstone.method)
            assertEquals(upload.headers["Idempotency-Key"], tombstone.headers["Idempotency-Key"])
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun cancellationBeforePendingInstallRemainsLocal() = runBlocking {
        val pendingInstallEntered = CountDownLatch(1)
        val allowPendingInstall = CountDownLatch(1)
        testFixture(
            beforePendingSubmissionInstall = {
                pendingInstallEntered.countDown()
                check(allowPendingInstall.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(pendingInstallEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowPendingInstall.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun publishesBusyStateAndCancelsBeforeSubmissionPreparationCompletes() = runBlocking {
        val preparationEntered = CountDownLatch(1)
        val allowPreparation = CountDownLatch(1)
        testFixture(
            beforeSubmissionPreparation = {
                preparationEntered.countDown()
                check(allowPreparation.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(preparationEntered.await(5, TimeUnit.SECONDS))
            assertIs<SupportDiagnosticsSubmissionState.Packaging>(fixture.intake.states().value)

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowPreparation.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun cancellationDuringArchivePromotionRemainsTerminal() = runBlocking {
        val promotionEntered = CountDownLatch(1)
        val allowPromotion = CountDownLatch(1)
        testFixture(
            beforeArchivePromotion = {
                promotionEntered.countDown()
                check(allowPromotion.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(promotionEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowPromotion.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun preservesPreparationBlockAcrossAccountSwitchesUntilTheOperationEnds() = runBlocking {
        val preparationEntered = CountDownLatch(1)
        val allowPreparationFailure = CountDownLatch(1)
        testFixture(
            beforeSubmissionPreparation = {
                preparationEntered.countDown()
                check(allowPreparationFailure.await(5, TimeUnit.SECONDS))
                throw IOException("Synthetic preparation failure.")
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(preparationEntered.await(5, TimeUnit.SECONDS))
            fixture.intake.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)

            val blocked = assertIs<SupportDiagnosticsSubmissionState.BlockedByAnotherAccount>(
                fixture.intake.states().value,
            )
            assertTrue(blocked.message.contains("another signed-in account"))

            allowPreparationFailure.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun cancellationStopsTheActiveCallWhenIntentPersistenceFails() = runBlocking {
        var rejectCancellationWrites = false
        testFixture(
            directorySync = {
                if (rejectCancellationWrites) throw IOException("Synthetic cancellation persistence failure.")
            },
        ).use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build(),
            )
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", upload.method)

            rejectCancellationWrites = true
            assertFalse(fixture.intake.cancel())

            withTimeout(5_000) { submission.join() }
            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(retryable.message.contains("was not sent"))
            assertEquals(1, fixture.server.requestCount)
            assertNull(fixture.server.takeRequest(200, TimeUnit.MILLISECONDS))
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
        }
        Unit
    }

    @Test
    fun concurrentCancellationPersistenceNeverRepopulatesPrivatePayload() = runBlocking {
        val rejectNextCancellationWrite = AtomicBoolean(false)
        val cancellationWriteEntered = CountDownLatch(1)
        val allowCancellationWriteFailure = CountDownLatch(1)
        val uploadResponseCompleted = CountDownLatch(1)
        testFixture(
            directorySync = {
                if (rejectNextCancellationWrite.compareAndSet(true, false)) {
                    cancellationWriteEntered.countDown()
                    assertTrue(allowCancellationWriteFailure.await(3, TimeUnit.SECONDS))
                    throw IOException("Synthetic cancellation persistence failure.")
                }
            },
            afterUploadResponse = { uploadResponseCompleted.countDown() },
        ).use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(1, TimeUnit.SECONDS).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit(
                    "Private concurrent cancellation note.",
                    "nightly",
                    listOf(SupportDiagnosticFieldDraft("private_concurrent_field", "Private concurrent value")),
                )
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            rejectNextCancellationWrite.set(true)
            val cancellation = launch(Dispatchers.Default) {
                assertFalse(fixture.intake.cancel())
            }
            assertTrue(cancellationWriteEntered.await(2, TimeUnit.SECONDS))
            assertTrue(uploadResponseCompleted.await(3, TimeUnit.SECONDS))

            allowCancellationWriteFailure.countDown()
            cancellation.join()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val tombstone = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", tombstone.method)
            assertEquals(upload.headers["Idempotency-Key"], tombstone.headers["Idempotency-Key"])
            assertFalse(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            val descriptor = File(fixture.temporaryRoot, "pending.json").readText()
            assertTrue(descriptor.contains(upload.headers["Idempotency-Key"].orEmpty()))
            assertTrue(descriptor.contains("\"cancellationPending\":true"))
            assertFalse(descriptor.contains("Private concurrent cancellation note."))
            assertFalse(descriptor.contains("private_concurrent_field"))
            assertFalse(descriptor.contains("Private concurrent value"))
        }
    }

    @Test
    fun publishesCancellingWhileAnInterruptedUploadIsReconciled() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build(),
            )
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertEquals(
                "POST",
                requireNotNull(fixture.server.takeRequest(WINDOWS_REQUEST_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)).method,
            )

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)

            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            withTimeout(5_000) { submission.join() }
            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
        }
        Unit
    }

    @Test
    fun packagingFailureDoesNotRestoreAReportCancelledDuringPackaging() = runBlocking {
        val packagingEntered = CountDownLatch(1)
        val allowPackagingFailure = CountDownLatch(1)
        testFixture(
            beforeBundlePackaging = {
                packagingEntered.countDown()
                check(allowPackagingFailure.await(5, TimeUnit.SECONDS))
                throw IOException("Synthetic packaging failure.")
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertTrue(packagingEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            allowPackagingFailure.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun deletesAnArchivePromotedBeforePackagingCancellationIsObserved() = runBlocking {
        testFixture(
            afterBundlePackaging = {
                throw CancellationException("Synthetic cancellation after archive promotion.")
            },
        ).use { fixture ->
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }

            submission.join()

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertFalse(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertFalse(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
        }
    }

    @Test
    fun expiresCompletedReceiptWhileTheProcessRemainsOpen() = runBlocking {
        testFixture().use { fixture ->
            val retentionUntil = Instant.now().plusSeconds(10)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, retentionUntil = retentionUntil))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.completedDescriptors().size)
            withTimeout(15_000) {
                fixture.intake.states().first { it is SupportDiagnosticsSubmissionState.Idle }
            }
            withTimeout(15_000) {
                while (fixture.completedDescriptors().isNotEmpty()) delay(10)
            }
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun keepsSubmittedStateWhenTerminalDirectorySyncNeedsARetry() = runBlocking {
        var cleanupSyncAttempts = 0
        testFixture(
            directorySync = { directory ->
                if (!File(directory, "pending.json").exists()) {
                    cleanupSyncAttempts += 1
                    if (cleanupSyncAttempts == 1) throw IOException("Synthetic directory sync failure.")
                }
            },
            descriptorCleanupRetryMillis = 10L,
        ).use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.completedDescriptors().size)
            withTimeout(5_000) {
                while (cleanupSyncAttempts < 2) delay(10)
            }
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun retriesTerminalCleanupWhenThePendingDescriptorCannotBeRead() = runBlocking {
        var descriptorReads = 0
        val cleanupRetryEntered = CountDownLatch(1)
        val allowCleanupRetry = CountDownLatch(1)
        testFixture(
            pendingDescriptorRead = { descriptor ->
                descriptorReads += 1
                if (descriptorReads == 1) {
                    throw IOException("Synthetic pending descriptor read failure.")
                }
                cleanupRetryEntered.countDown()
                check(allowCleanupRetry.await(5, TimeUnit.SECONDS))
                descriptor.readText()
            },
            descriptorCleanupRetryMillis = 10L,
        ).use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertTrue(cleanupRetryEntered.await(5, TimeUnit.SECONDS))
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            allowCleanupRetry.countDown()
            withTimeout(5_000) {
                while (File(fixture.temporaryRoot, "pending.json").exists()) delay(10)
            }
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
        }
        Unit
    }

    @Test
    fun retriesTerminalArchiveDeletionWithoutChangingSubmittedState() = runBlocking {
        var archiveDeleteAttempts = 0
        val retryEntered = CountDownLatch(1)
        val allowRetry = CountDownLatch(1)
        testFixture(
            privateFileDelete = { archive ->
                archiveDeleteAttempts += 1
                if (archiveDeleteAttempts == 1) {
                    false
                } else {
                    retryEntered.countDown()
                    check(allowRetry.await(5, TimeUnit.SECONDS))
                    archive.delete()
                }
            },
            descriptorCleanupRetryMillis = 10L,
        ).use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            allowRetry.countDown()
            withTimeout(5_000) {
                while (fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" }) delay(10)
            }
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().none { it.extension == "zip" })
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
        }
        Unit
    }

    @Test
    fun retriesDeletionOfOrphanedPendingDescriptorTemporaries() = runBlocking {
        var deleteAttempts = 0
        val retryEntered = CountDownLatch(1)
        val allowRetry = CountDownLatch(1)
        testFixture(
            privateFileDelete = { file ->
                deleteAttempts += 1
                if (deleteAttempts == 1) {
                    false
                } else {
                    retryEntered.countDown()
                    check(allowRetry.await(5, TimeUnit.SECONDS))
                    file.delete()
                }
            },
            descriptorCleanupRetryMillis = 10L,
            pendingTemporaryBeforeInitialization = true,
        ).use { fixture ->
            val orphan = requireNotNull(
                fixture.temporaryRoot.listFiles().orEmpty().singleOrNull {
                    it.name.startsWith(".pending-") && it.extension == "tmp"
                },
            )
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
            assertTrue(orphan.isFile)
            allowRetry.countDown()

            withTimeout(5_000) {
                while (orphan.exists()) delay(10)
            }
            assertFalse(orphan.exists())
            assertTrue(deleteAttempts >= 2)
        }
    }

    @Test
    fun removesArchiveTemporariesLeftByInterruptedPackaging() = runBlocking {
        testFixture(archiveTemporaryBeforeInitialization = true).use { fixture ->
            assertTrue(
                fixture.temporaryRoot.listFiles().orEmpty().none { file ->
                    file.name.startsWith(".support-") && file.name.endsWith(".tmp")
                },
            )
        }
    }

    @Test
    fun reportsUnavailableSubmissionStorageDuringInitialization() = runBlocking {
        testFixture(submissionStorageBlocked = true).use { fixture ->
            val state = assertIs<SupportDiagnosticsSubmissionState.Unsupported>(fixture.intake.states().value)
            assertTrue(state.reason.contains("storage is unavailable"))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Unsupported>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)

            fixture.intake.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)

            assertIs<SupportDiagnosticsSubmissionState.Unsupported>(fixture.intake.states().value)
        }
        Unit
    }

    @Test
    fun reconciledReceiptDoesNotDuplicateAnExistingCompletionAfterRestart() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(1, TimeUnit.SECONDS).build(),
            )
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val persistedPending = File(fixture.temporaryRoot, "pending.json").readText().replace(
                Regex("\\\"archiveName\\\":\\\"[^\\\"]+\\\""),
                "\"archiveName\":null",
            )
            submission.join()
            assertEquals(1, fixture.completedDescriptors().size)

            File(fixture.temporaryRoot, "pending.json").writeText(persistedPending)
            fixture.intake.close()
            fixture.newIntake().use { restored ->
                assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
                fixture.server.enqueue(receiptResponse(fixture.statusUrl))

                restored.retry()

                val submitted = assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
                assertEquals(listOf("OBI-ABCDE-23456"), submitted.reports.map { it.supportCode })
                assertEquals(1, fixture.completedDescriptors().size)
                assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            }
        }
    }

    @Test
    fun doesNotAcceptCancellationAfterReceiptCompletion() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertFalse(fixture.intake.cancel())
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun cancellingAmbiguousSubmissionUsesAuthoritativeServerTombstone() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationDuringReceiptReconciliationSendsAuthoritativeTombstone() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(
                MockResponse.Builder().code(404).headersDelay(10, TimeUnit.SECONDS).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(10, TimeUnit.SECONDS))
            val reconciliation = requireNotNull(fixture.server.takeRequest(10, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)

            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(10, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertEquals(3, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationBeforeReceiptRegistrationStartsTheTombstoneImmediately() = runBlocking {
        val receiptRegistrationEntered = CountDownLatch(1)
        val allowReceiptRegistration = CountDownLatch(1)
        testFixture(
            beforeReceiptCallRegistration = {
                receiptRegistrationEntered.countDown()
                check(allowReceiptRegistration.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(receiptRegistrationEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowReceiptRegistration.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val tombstone = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", tombstone.method)
            assertEquals(upload.headers["Idempotency-Key"], tombstone.headers["Idempotency-Key"])
            assertEquals(2, fixture.server.requestCount)
            assertNull(fixture.server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun cancellationAfterReceiptLookupCompletesSendsAuthoritativeTombstone() = runBlocking {
        val lookupCompleted = CountDownLatch(1)
        val allowLookupResult = CountDownLatch(1)
        testFixture(
            afterReceiptLookup = {
                lookupCompleted.countDown()
                assertTrue(allowLookupResult.await(2, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)
            assertTrue(lookupCompleted.await(2, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            allowLookupResult.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertEquals(3, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationDoesNotCancelTheTombstoneStartedByResponseHandling() = runBlocking {
        val uploadResponseCompleted = CountDownLatch(1)
        val allowUploadResponseDisposition = CountDownLatch(1)
        val cancellationIntentPublished = CountDownLatch(1)
        val allowCancellationContinuation = CountDownLatch(1)
        testFixture(
            afterUploadResponse = {
                uploadResponseCompleted.countDown()
                assertTrue(allowUploadResponseDisposition.await(5, TimeUnit.SECONDS))
            },
            afterCancellationIntentPublished = {
                cancellationIntentPublished.countDown()
                assertTrue(allowCancellationContinuation.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.server.enqueue(
                MockResponse.Builder().code(204).headersDelay(2, TimeUnit.SECONDS).build(),
            )
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertEquals("POST", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(uploadResponseCompleted.await(5, TimeUnit.SECONDS))

            val cancellation = launch(Dispatchers.Default) {
                assertTrue(fixture.intake.cancel())
            }
            assertTrue(cancellationIntentPublished.await(5, TimeUnit.SECONDS))
            allowUploadResponseDisposition.countDown()
            val tombstone = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", tombstone.method)
            assertEquals("/api/v1/receipts", tombstone.url.encodedPath)
            allowCancellationContinuation.countDown()

            cancellation.join()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals(2, fixture.server.requestCount)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun cancellationAfterReceiptIntentCheckContinuesWithAuthoritativeTombstone() = runBlocking {
        val dispositionEntered = CountDownLatch(1)
        val allowDisposition = CountDownLatch(1)
        testFixture(
            beforeReceiptResponseDisposition = {
                dispositionEntered.countDown()
                assertTrue(allowDisposition.await(2, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(dispositionEntered.await(2, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            allowDisposition.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationWinsAgainstPermanentUploadRejectionDisposition() = runBlocking {
        val responseDispositionEntered = CountDownLatch(1)
        val allowResponseDisposition = CountDownLatch(1)
        testFixture(
            beforeUploadResponseDisposition = {
                responseDispositionEntered.countDown()
                check(allowResponseDisposition.await(5, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder().code(400).body(
                    """{"contractVersion":1,"code":"invalid_report","message":"Invalid."}""",
                ).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(responseDispositionEntered.await(5, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.Cancelling>(fixture.intake.states().value)
            allowResponseDisposition.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val tombstone = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", tombstone.method)
            assertEquals(upload.headers["Idempotency-Key"], tombstone.headers["Idempotency-Key"])
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
        }
    }

    @Test
    fun cancellationAfterUploadResponseCompletesSendsAuthoritativeTombstone() = runBlocking {
        val responseCompleted = CountDownLatch(1)
        val allowResponseResult = CountDownLatch(1)
        testFixture(
            afterUploadResponse = {
                responseCompleted.countDown()
                assertTrue(allowResponseResult.await(10, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(10, TimeUnit.SECONDS))
            assertTrue(responseCompleted.await(10, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            allowResponseResult.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(10, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertEquals(2, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationAfterUploadIntentCheckContinuesWithAuthoritativeTombstone() = runBlocking {
        val dispositionEntered = CountDownLatch(1)
        val allowDisposition = CountDownLatch(1)
        testFixture(
            beforeUploadResponseDisposition = {
                dispositionEntered.countDown()
                assertTrue(allowDisposition.await(2, TimeUnit.SECONDS))
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(dispositionEntered.await(2, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            allowDisposition.countDown()
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationAfterReconciledReceiptAbsenceUsesAuthoritativeTombstone() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertEquals(3, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun serverFailureRemainsAmbiguousUntilCancellationIsConfirmed() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(retryable.outcomeAmbiguous)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationDoesNotPollReceiptAbsenceBeforeDiscardingRecovery() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(2, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationRetryWaitsForAuthoritativeTerminalResult() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            assertTrue(descriptor.isFile)
            val firstCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", firstCancellation.method)
            fixture.intake.close()
            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val retryCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", retryCancellation.method)
            assertEquals("/api/v1/receipts", retryCancellation.url.encodedPath)
            assertEquals(
                firstCancellation.headers["Idempotency-Key"],
                retryCancellation.headers["Idempotency-Key"],
            )
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
            restored.close()
        }
    }

    @Test
    fun restoredAmbiguousSubmissionAcceptsAuthoritativeCancellationTombstone() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.intake.close()

            val descriptor = File(fixture.temporaryRoot, "pending.json")
            descriptor.writeText(
                descriptor.readText().replace(
                    "\"cancellationPending\":true",
                    "\"cancellationPending\":false",
                ),
            )
            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(submissionCancelledResponse())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)
            assertEquals("/api/v1/receipts", reconciliation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], reconciliation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
            restored.close()
        }
    }

    @Test
    fun unverifiedGoneUploadResponseRetainsRecovery() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder().code(410).body(
                    """{"contractVersion":1,"code":"not_found","message":"Gone."}""",
                ).build(),
            )

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertTrue(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertEquals("POST", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
        }
    }

    @Test
    fun unverifiedGoneReceiptResponseRetainsRecovery() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(410).body("gone").build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(
                fixture.intake.states().value,
            )
            assertTrue(retryable.outcomeAmbiguous)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            assertEquals("POST", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
        }
    }

    @Test
    fun cancellationRetainsRecoveryUntilTerminalNoContent() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            val nonTerminal = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", nonTerminal.method)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            fixture.intake.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun doesNotForwardPrivateReceiptKeyAcrossRedirects() = runBlocking {
        MockWebServer().use { redirectedServer ->
            redirectedServer.start()
            testFixture().use { fixture ->
                fixture.server.enqueue(
                    MockResponse.Builder().code(307)
                        .addHeader("Location", redirectedServer.url("/capture"))
                        .build(),
                )

                fixture.intake.submit("A refresh failed.", "nightly", emptyList())

                assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
                assertEquals(1, fixture.server.requestCount)
                assertEquals(0, redirectedServer.requestCount)
            }
        }
    }

    @Test
    fun removesOrphanedArchiveWhenPendingDescriptorIsUnreadable() = runBlocking {
        testFixture().use { fixture ->
            require(fixture.temporaryRoot.isDirectory || fixture.temporaryRoot.mkdirs())
            val orphan = File(fixture.temporaryRoot, "support-${UUID.randomUUID()}.zip")
            orphan.writeBytes(byteArrayOf(1, 2, 3))
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            descriptor.writeText("not-json")

            fixture.newIntake()

            assertFalse(orphan.exists())
            assertFalse(descriptor.exists())
        }
    }

    @Test
    fun retriesTransientPendingDescriptorReadWithoutDeletingRecoveryFiles() = runBlocking {
        val failReads = AtomicBoolean(false)
        testFixture(
            descriptorCleanupRetryMillis = 10L,
            pendingDescriptorRead = { descriptor ->
                if (failReads.get()) throw IOException("Synthetic transient descriptor read failure.")
                descriptor.readText()
            },
        ).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val archive = requireNotNull(
                fixture.temporaryRoot.listFiles().orEmpty().singleOrNull { file -> file.extension == "zip" },
            )
            assertTrue(descriptor.isFile)
            assertTrue(archive.isFile)
            fixture.intake.close()
            failReads.set(true)

            fixture.newIntake().use { restored ->
                val unavailable = assertIs<SupportDiagnosticsSubmissionState.Unsupported>(restored.states().value)
                assertTrue(unavailable.reason.contains("retry automatically"))
                assertTrue(descriptor.isFile)
                assertTrue(archive.isFile)

                failReads.set(false)
                withTimeout(5_000) {
                    restored.states().first { state ->
                        state is SupportDiagnosticsSubmissionState.RetryableFailure
                    }
                }
                assertTrue(descriptor.isFile)
                assertTrue(archive.isFile)
            }
        }
    }

    @Test
    fun retriesCleanupOfAnUnreadablePendingDescriptor() = runBlocking {
        var deleteAttempts = 0
        testFixture(
            privateFileDelete = { file ->
                deleteAttempts += 1
                deleteAttempts > 2 && file.delete()
            },
            descriptorCleanupRetryMillis = 1_000L,
            invalidPendingBeforeInitialization = true,
        ).use { fixture ->
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
            assertTrue(
                fixture.temporaryRoot.listFiles().orEmpty().any {
                    it.name.startsWith(".pending-rejected-") && it.extension == "tmp"
                },
            )

            withTimeout(5_000) {
                while (fixture.temporaryRoot.listFiles().orEmpty().any { it.name.startsWith(".pending-rejected-") }) {
                    delay(10)
                }
            }
            assertTrue(deleteAttempts >= 3)
        }
    }

    @Test
    fun serializesConcurrentSubmissionAttempts() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(1, TimeUnit.SECONDS).build(),
            )

            val first = launch(Dispatchers.Default) {
                fixture.intake.submit("The first refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val second = launch(Dispatchers.Default) {
                fixture.intake.submit("The second refresh failed.", "nightly", emptyList())
            }
            second.join()
            first.join()

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun preservesCancellationIntentAcrossRestart() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val firstCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", firstCancellation.method)
            fixture.intake.close()

            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val retryCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], retryCancellation.headers["Idempotency-Key"])
            assertEquals("DELETE", retryCancellation.method)
            assertEquals("/api/v1/receipts", retryCancellation.url.encodedPath)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun retainsThrottledSubmissionAndHonorsRetryAfter() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder().code(429)
                    .addHeader("Retry-After", "1")
                    .body("""{"message":"Try later."}""")
                    .build(),
            )

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val first = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.intake.retry()
            assertEquals(1, fixture.server.requestCount)

            Thread.sleep(1_100)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.retry()

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            val retry = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(first.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
        }
    }

    @Test
    fun reconcilesRequestTimeoutBeforeRetrying() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(408).build())
            fixture.server.enqueue(MockResponse.Builder().code(404).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], reconciliation.headers["Idempotency-Key"])
            assertEquals("GET", reconciliation.method)

            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.intake.retry()

            val retry = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            Unit
        }
    }

    @Test
    fun restoresConfirmedSubmissionInterruptedBeforePackaging() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.intake.submit("Visit https://private.example.test and refresh.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val firstUpload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val persisted = descriptor.readText()
            val archiveName = requireNotNull(
                Regex("\\\"archiveName\\\":\\\"([^\\\"]+)\\\"").find(persisted)?.groupValues?.get(1),
            )
            File(fixture.temporaryRoot, archiveName).delete()
            descriptor.writeText(
                persisted.replace(
                    Regex("\\\"archiveName\\\":\\\"[^\\\"]+\\\""),
                    "\"archiveName\":null",
                ),
            )
            fixture.intake.close()

            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(404).build())

            restored.retry()

            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            val retry = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", retry.method)
            assertEquals(firstUpload.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
            val body = retry.body?.utf8().orEmpty()
            assertFalse(body.contains("private.example.test"))
            assertTrue(body.contains("<url:"))
        }
    }

    @Test
    fun retriesPersistedCancellationWithoutResubmitting() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val failedCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", failedCancellation.method)
            assertEquals("/api/v1/receipts", failedCancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], failedCancellation.headers["Idempotency-Key"])
            fixture.intake.close()

            val restored = fixture.newIntake()
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val retriedCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", retriedCancellation.method)
            assertEquals("/api/v1/receipts", retriedCancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], retriedCancellation.headers["Idempotency-Key"])
            assertEquals(3, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun failedCancellationRetainsOnlyMinimalRecoveryState() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.intake.submit(
                "Private cancellation reproduction note.",
                "nightly",
                listOf(SupportDiagnosticFieldDraft("private_field", "Private value")),
            )
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            assertTrue(fixture.intake.cancel())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertFalse(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            val descriptor = File(fixture.temporaryRoot, "pending.json").readText()
            assertTrue(descriptor.contains(upload.headers["Idempotency-Key"].orEmpty()))
            assertTrue(descriptor.contains("\"cancellationPending\":true"))
            assertFalse(descriptor.contains("Private cancellation reproduction note."))
            assertFalse(descriptor.contains("private_field"))
            assertFalse(descriptor.contains("Private value"))
        }
    }

    @Test
    fun restorationMinimizesPreviouslyPersistedCancellationIntent() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())
            fixture.intake.submit(
                "Private restored cancellation note.",
                "nightly",
                listOf(SupportDiagnosticFieldDraft("private_restored_field", "Private restored value")),
            )
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val descriptor = File(fixture.temporaryRoot, "pending.json")
            assertTrue(descriptor.readText().contains("Private restored cancellation note."))
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            fixture.intake.close()
            descriptor.writeText(
                descriptor.readText().replace(
                    "\"cancellationPending\":false",
                    "\"cancellationPending\":true",
                ),
            )

            val restored = fixture.newIntake()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            assertFalse(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            val minimized = descriptor.readText()
            assertTrue(minimized.contains(upload.headers["Idempotency-Key"].orEmpty()))
            assertTrue(minimized.contains("\"cancellationPending\":true"))
            assertFalse(minimized.contains("Private restored cancellation note."))
            assertFalse(minimized.contains("private_restored_field"))
            assertFalse(minimized.contains("Private restored value"))
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
            restored.close()
        }
    }

    @Test
    fun keepsCancellationKeyAfterTheLocalArchiveRetentionWindow() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(
                fixture.server.takeRequest(WINDOWS_REQUEST_START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(fixture.intake.cancel())
            submission.join()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.intake.close()

            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val agedCreatedAt = Instant.now().minus(25, ChronoUnit.DAYS).toEpochMilli()
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\"createdAtEpochMillis\":\\d+"),
                    "\"createdAtEpochMillis\":$agedCreatedAt",
                ),
            )
            val restored = fixture.newIntake()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            assertTrue(descriptor.isFile)
            assertFalse(fixture.temporaryRoot.listFiles().orEmpty().any { it.extension == "zip" })
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun reconcilesRestoredCancellationBeforeApplyingRecoveryExpiry() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.intake.close()

            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val expiredCreatedAt = Instant.now().minus(31, ChronoUnit.DAYS).toEpochMilli()
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\"createdAtEpochMillis\":\\d+"),
                    "\"createdAtEpochMillis\":$expiredCreatedAt",
                ),
            )
            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals("/api/v1/receipts", cancellation.url.encodedPath)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
            restored.close()
        }
    }

    @Test
    fun restoresCancellationKeyWhenTheWallClockMovesBackward() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.intake.close()

            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val futureCreatedAt = Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\"createdAtEpochMillis\":\\d+"),
                    "\"createdAtEpochMillis\":$futureCreatedAt",
                ),
            )

            val restored = fixture.newIntake()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            assertTrue(descriptor.isFile)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            assertEquals(upload.headers["Idempotency-Key"], cancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun preservesLastCancellationRecordWhenRetryStateCannotBeRewritten() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(
                MockResponse.Builder().code(503).headersDelay(1, TimeUnit.SECONDS).build(),
            )

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            val cancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", cancellation.method)
            val retainedRoot = File(fixture.root, "submissions-retained")
            Files.move(fixture.temporaryRoot.toPath(), retainedRoot.toPath())
            fixture.temporaryRoot.writeText("temporarily unavailable")
            submission.join()

            val state = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(state.message.contains("could not be stored"))
            val descriptor = File(retainedRoot, "pending.json")
            assertTrue(descriptor.isFile)
            assertTrue(descriptor.readText().contains(upload.headers["Idempotency-Key"].orEmpty()))

            assertTrue(fixture.temporaryRoot.delete())
            Files.move(retainedRoot.toPath(), fixture.temporaryRoot.toPath())
            fixture.intake.close()
            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(204).build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val retryCancellation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", retryCancellation.method)
            assertEquals(upload.headers["Idempotency-Key"], retryCancellation.headers["Idempotency-Key"])
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun preservesLastUploadRecordWhenRetryStateCannotBeRewritten() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder().code(503).headersDelay(1, TimeUnit.SECONDS).build(),
            )

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val firstUpload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val retainedRoot = File(fixture.root, "submissions-retained")
            Files.move(fixture.temporaryRoot.toPath(), retainedRoot.toPath())
            fixture.temporaryRoot.writeText("temporarily unavailable")
            submission.join()

            val state = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(state.message.contains("updated retry state"))
            val descriptor = File(retainedRoot, "pending.json")
            assertTrue(descriptor.isFile)
            assertTrue(descriptor.readText().contains(firstUpload.headers["Idempotency-Key"].orEmpty()))

            assertTrue(fixture.temporaryRoot.delete())
            Files.move(retainedRoot.toPath(), fixture.temporaryRoot.toPath())
            fixture.intake.close()
            val restored = fixture.newIntake()
            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val retry = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(firstUpload.headers["Idempotency-Key"], reconciliation.headers["Idempotency-Key"])
            assertEquals(firstUpload.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
        }
    }

    @Test
    fun clearsImplausibleRetryDelayWithoutDiscardingRecovery() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder().code(429)
                    .addHeader("Retry-After", "300")
                    .build(),
            )
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            val firstUpload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            fixture.intake.close()

            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val futureRetryAt = Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\"retryNotBeforeEpochMillis\":\\d+"),
                    "\"retryNotBeforeEpochMillis\":$futureRetryAt",
                ),
            )
            val restored = fixture.newIntake()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            assertTrue(descriptor.isFile)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            val retry = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(firstUpload.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
        }
    }

    @Test
    fun agesAmbiguousRecoveryFromTheLatestUploadAttempt() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(429).build())
            fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            fixture.intake.close()

            val descriptor = File(fixture.temporaryRoot, "pending.json")
            val preparedTwentyNineDaysAgo = Instant.now().minus(29, ChronoUnit.DAYS).toEpochMilli()
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\"createdAtEpochMillis\":\\d+"),
                    "\"createdAtEpochMillis\":$preparedTwentyNineDaysAgo",
                ),
            )
            val lateRetry = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(lateRetry.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            lateRetry.retry()

            val ambiguous = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(lateRetry.states().value)
            assertTrue(ambiguous.outcomeAmbiguous)
            assertTrue(descriptor.readText().contains("\"latestUploadAttemptAtEpochMillis\":"))
            lateRetry.close()

            val preparedThirtyOneDaysAgo = Instant.now().minus(31, ChronoUnit.DAYS).toEpochMilli()
            descriptor.writeText(
                descriptor.readText().replace(
                    Regex("\"createdAtEpochMillis\":\\d+"),
                    "\"createdAtEpochMillis\":$preparedThirtyOneDaysAgo",
                ),
            )
            fixture.newIntake().use { restored ->
                assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
                assertTrue(descriptor.isFile)
            }
        }
    }

    @Test
    fun restrictsPendingSubmissionFilesToTheCurrentUnixUser() = runBlocking {
        testFixture().use { fixture ->
            if (
                Files.getFileAttributeView(
                    fixture.temporaryRoot.toPath(),
                    PosixFileAttributeView::class.java,
                ) == null
            ) {
                return@use
            }
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val expectedDirectoryPermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
            val expectedFilePermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            assertEquals(expectedDirectoryPermissions, Files.getPosixFilePermissions(fixture.temporaryRoot.toPath()))
            fixture.temporaryRoot.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                assertEquals(expectedFilePermissions, Files.getPosixFilePermissions(file.toPath()))
            }
        }
    }

    private fun testFixture(
        supportMutationsAllowed: () -> Boolean = { true },
        directorySync: (File) -> Unit = {},
        descriptorCleanupRetryMillis: Long = 60_000L,
        beforeCallRegistration: () -> Unit = {},
        beforeSubmissionPreparation: () -> Unit = {},
        beforePendingSubmissionInstall: () -> Unit = {},
        beforeBundlePackaging: () -> Unit = {},
        afterBundlePackaging: () -> Unit = {},
        beforeArchivePromotion: () -> Unit = {},
        beforeRetryUploadTransition: () -> Unit = {},
        afterRetryUploadTransition: () -> Unit = {},
        beforeRecoveryExpiryDisposition: () -> Unit = {},
        beforeUploadMarker: () -> Unit = {},
        beforeUploadArchiveValidation: () -> Unit = {},
        beforeTransportGateFailureDisposition: () -> Unit = {},
        beforeReceiptCallRegistration: () -> Unit = {},
        afterCancellationIntentPublished: () -> Unit = {},
        afterUploadResponse: () -> Unit = {},
        beforeUploadResponseDisposition: () -> Unit = {},
        afterReceiptLookup: () -> Unit = {},
        beforeReceiptResponseDisposition: () -> Unit = {},
        privateFileDelete: (File) -> Boolean = File::delete,
        pendingDescriptorRead: (File) -> String = { descriptor -> descriptor.readText() },
        completedDescriptorRead: (File) -> String = { descriptor -> descriptor.readText() },
        submissionStorageBlocked: Boolean = false,
        pendingTemporaryBeforeInitialization: Boolean = false,
        archiveTemporaryBeforeInitialization: Boolean = false,
        invalidPendingBeforeInitialization: Boolean = false,
        invalidCompletedBeforeInitialization: Boolean = false,
        currentTimeMillis: () -> Long = System::currentTimeMillis,
    ): Fixture {
        val root = createTempDirectory("support-intake-test").toFile()
        val diagnosticRoot = File(root, "diagnostics")
        val temporaryRoot = if (submissionStorageBlocked) {
            val blockingParent = File(root, "submission-storage-blocked").apply { writeText("unavailable") }
            File(blockingParent, "submissions")
        } else {
            File(root, "submissions")
        }
        if (pendingTemporaryBeforeInitialization) {
            require(temporaryRoot.mkdirs())
            File(temporaryRoot, ".pending-orphan.tmp").writeText("private context")
        }
        if (archiveTemporaryBeforeInitialization) {
            require(temporaryRoot.isDirectory || temporaryRoot.mkdirs())
            File(temporaryRoot, ".support-${UUID.randomUUID()}.zip.123456789.tmp")
                .writeText("private context")
        }
        if (invalidPendingBeforeInitialization) {
            require(temporaryRoot.isDirectory || temporaryRoot.mkdirs())
            File(temporaryRoot, "pending.json").writeText("not-json")
        }
        if (invalidCompletedBeforeInitialization) {
            require(temporaryRoot.isDirectory || temporaryRoot.mkdirs())
            File(temporaryRoot, "completed-${UUID.randomUUID()}.json").writeText("not-json")
        }
        val environment = SupportDiagnosticsEnvironment(
            appVersion = "0.1.0-test",
            packageVersion = "1",
            platform = "Synthetic desktop",
            operatingSystemVersion = "Synthetic OS",
            architecture = "x86_64",
        )
        val diagnostics = AsyncJvmSupportDiagnostics(diagnosticRoot, environment, "support-intake-test")
        diagnostics.record(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Warning,
                component = SupportDiagnosticComponent.Network,
                operation = "network.synthetic",
                outcome = "failed",
            ),
        )
        val server = MockWebServer().also { it.start() }
        return Fixture(
            root = root,
            temporaryRoot = temporaryRoot,
            diagnostics = diagnostics,
            environment = environment,
            server = server,
            supportMutationsAllowed = supportMutationsAllowed,
            directorySync = directorySync,
            descriptorCleanupRetryMillis = descriptorCleanupRetryMillis,
            beforeCallRegistration = beforeCallRegistration,
            beforeSubmissionPreparation = beforeSubmissionPreparation,
            beforePendingSubmissionInstall = beforePendingSubmissionInstall,
            beforeBundlePackaging = beforeBundlePackaging,
            afterBundlePackaging = afterBundlePackaging,
            beforeArchivePromotion = beforeArchivePromotion,
            beforeRetryUploadTransition = beforeRetryUploadTransition,
            afterRetryUploadTransition = afterRetryUploadTransition,
            beforeRecoveryExpiryDisposition = beforeRecoveryExpiryDisposition,
            beforeUploadMarker = beforeUploadMarker,
            beforeUploadArchiveValidation = beforeUploadArchiveValidation,
            beforeTransportGateFailureDisposition = beforeTransportGateFailureDisposition,
            beforeReceiptCallRegistration = beforeReceiptCallRegistration,
            afterCancellationIntentPublished = afterCancellationIntentPublished,
            afterUploadResponse = afterUploadResponse,
            beforeUploadResponseDisposition = beforeUploadResponseDisposition,
            afterReceiptLookup = afterReceiptLookup,
            beforeReceiptResponseDisposition = beforeReceiptResponseDisposition,
            privateFileDelete = privateFileDelete,
            pendingDescriptorRead = pendingDescriptorRead,
            completedDescriptorRead = completedDescriptorRead,
            currentTimeMillis = currentTimeMillis,
        )
    }

    private fun receiptResponse(
        statusUrl: String,
        supportCode: String = "OBI-ABCDE-23456",
        retentionDays: Long = 30,
        createdAtOffsetDays: Long = 0,
        retentionUntil: Instant? = null,
        deletionUrl: String = statusUrl,
    ): MockResponse {
        val createdAt = Instant.now().plus(createdAtOffsetDays, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val resolvedRetentionUntil = retentionUntil ?: createdAt.plus(retentionDays, ChronoUnit.DAYS)
        return MockResponse.Builder().code(201).body(
            """
                {
                  "contractVersion": 1,
                  "supportCode": "$supportCode",
                  "status": "new",
                  "statusUrl": "$statusUrl",
                  "deletionUrl": "$deletionUrl",
                  "createdAt": "$createdAt",
                  "retentionUntil": "$resolvedRetentionUntil"
                }
            """.trimIndent(),
        ).build()
    }

    private fun submissionCancelledResponse(): MockResponse = MockResponse.Builder().code(410).body(
        """{"contractVersion":1,"code":"submission_cancelled","message":"Submission cancelled."}""",
    ).build()

    private fun privateStatusResponse(
        status: String,
        messages: List<Pair<String, String>>,
        reporterMessage: String? = null,
    ): MockResponse {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val encodedMessages = buildList {
            messages.forEach { (id, body) ->
                add("""{"id":"$id","author":"maintainer","body":"$body","createdAt":"$now"}""")
            }
            reporterMessage?.let { body ->
                add(
                    """{"id":"${UUID.randomUUID()}","author":"reporter","body":"$body","createdAt":"$now"}""",
                )
            }
        }.joinToString(",")
        return MockResponse.Builder().code(if (reporterMessage == null) 200 else 201).body(
            """
                {
                  "contractVersion": 1,
                  "supportCode": "OBI-ABCDE-23456",
                  "productId": "nextcloud-native",
                  "requestType": "bug",
                  "status": "$status",
                  "createdAt": "$now",
                  "updatedAt": "$now",
                  "retentionUntil": "${now.plus(30, ChronoUnit.DAYS)}",
                  "messages": [$encodedMessages]
                }
            """.trimIndent(),
        ).build()
    }

    private data class Fixture(
        val root: File,
        val temporaryRoot: File,
        val diagnostics: AsyncJvmSupportDiagnostics,
        val environment: SupportDiagnosticsEnvironment,
        val server: MockWebServer,
        val supportMutationsAllowed: () -> Boolean,
        val directorySync: (File) -> Unit,
        val descriptorCleanupRetryMillis: Long,
        val beforeCallRegistration: () -> Unit,
        val beforeSubmissionPreparation: () -> Unit,
        val beforePendingSubmissionInstall: () -> Unit,
        val beforeBundlePackaging: () -> Unit,
        val afterBundlePackaging: () -> Unit,
        val beforeArchivePromotion: () -> Unit,
        val beforeRetryUploadTransition: () -> Unit,
        val afterRetryUploadTransition: () -> Unit,
        val beforeRecoveryExpiryDisposition: () -> Unit,
        val beforeUploadMarker: () -> Unit,
        val beforeUploadArchiveValidation: () -> Unit,
        val beforeTransportGateFailureDisposition: () -> Unit,
        val beforeReceiptCallRegistration: () -> Unit,
        val afterCancellationIntentPublished: () -> Unit,
        val afterUploadResponse: () -> Unit,
        val beforeUploadResponseDisposition: () -> Unit,
        val afterReceiptLookup: () -> Unit,
        val beforeReceiptResponseDisposition: () -> Unit,
        val privateFileDelete: (File) -> Boolean,
        val pendingDescriptorRead: (File) -> String,
        val completedDescriptorRead: (File) -> String,
        val currentTimeMillis: () -> Long,
    ) : AutoCloseable {
        val intake = newIntake()
        val statusUrl: String get() = server.url("/r/abcdefghijklmnopqrstuvwxyzABCDEFGH_12345678").toString()

        fun completedDescriptors(): List<File> = temporaryRoot.listFiles().orEmpty()
            .filter { file -> file.name.matches(Regex("completed-[0-9a-f-]{36}\\.json")) }

        fun newIntake() = JvmSupportIntake(
            diagnostics = diagnostics,
            temporaryRoot = temporaryRoot,
            environment = environment,
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            supportBaseUrl = server.url("/").toString(),
            supportMutationsAllowed = supportMutationsAllowed,
            directorySync = directorySync,
            descriptorCleanupRetryMillis = descriptorCleanupRetryMillis,
            beforeCallRegistration = beforeCallRegistration,
            beforeSubmissionPreparation = beforeSubmissionPreparation,
            beforePendingSubmissionInstall = beforePendingSubmissionInstall,
            beforeBundlePackaging = beforeBundlePackaging,
            afterBundlePackaging = afterBundlePackaging,
            beforeArchivePromotion = beforeArchivePromotion,
            beforeRetryUploadTransition = beforeRetryUploadTransition,
            afterRetryUploadTransition = afterRetryUploadTransition,
            beforeRecoveryExpiryDisposition = beforeRecoveryExpiryDisposition,
            beforeUploadMarker = beforeUploadMarker,
            beforeUploadArchiveValidation = beforeUploadArchiveValidation,
            beforeTransportGateFailureDisposition = beforeTransportGateFailureDisposition,
            beforeReceiptCallRegistration = beforeReceiptCallRegistration,
            afterCancellationIntentPublished = afterCancellationIntentPublished,
            afterUploadResponse = afterUploadResponse,
            beforeUploadResponseDisposition = beforeUploadResponseDisposition,
            afterReceiptLookup = afterReceiptLookup,
            beforeReceiptResponseDisposition = beforeReceiptResponseDisposition,
            privateFileDelete = privateFileDelete,
            pendingDescriptorRead = pendingDescriptorRead,
            completedDescriptorRead = completedDescriptorRead,
            currentTimeMillis = currentTimeMillis,
        ).also { intake ->
            intake.setActiveAccountIdentity(TEST_ACCOUNT_IDENTITY)
            runBlocking { intake.awaitInitialization() }
        }

        override fun close() {
            intake.close()
            diagnostics.close()
            server.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val WINDOWS_REQUEST_START_TIMEOUT_SECONDS = 10L
        const val TEST_ACCOUNT_IDENTITY = "0123456789abcdef0123456789abcdef"
        const val OTHER_ACCOUNT_IDENTITY = "fedcba9876543210fedcba9876543210"
    }
}
