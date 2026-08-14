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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

class JvmSupportIntakeTest {
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
            assertEquals("OBI-ABCDE-23456", submitted.supportCode)
            assertEquals(fixture.statusUrl, submitted.statusUrl)
            assertEquals(1, fixture.completedDescriptors().size)
            assertFalse(File(fixture.temporaryRoot, "pending.json").exists())
            restored.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(restored.states().value)
            Unit
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
    fun cancellationStopsTheActiveCallWhenIntentPersistenceFails() = runBlocking {
        var directorySyncs = 0
        testFixture(
            directorySync = {
                directorySyncs += 1
                if (directorySyncs == 4) throw IOException("Synthetic cancellation persistence failure.")
            },
        ).use { fixture ->
            fixture.server.enqueue(
                receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            assertEquals("POST", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)

            assertFalse(fixture.intake.cancel())

            withTimeout(5_000) { submission.join() }
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
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
    fun expiresCompletedReceiptWhileTheProcessRemainsOpen() = runBlocking {
        testFixture().use { fixture ->
            val retentionUntil = Instant.now().plusSeconds(2)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl, retentionUntil = retentionUntil))

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.completedDescriptors().size)
            withTimeout(5_000) {
                fixture.intake.states().first { it is SupportDiagnosticsSubmissionState.Idle }
            }
            withTimeout(5_000) {
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
    fun cancellingAmbiguousSubmissionRequiresDeletionReconciliation() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(fixture.intake.cancel())
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)

            fixture.server.enqueue(MockResponse.Builder().code(404).build())
            fixture.intake.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun serverFailureRemainsAmbiguousUntilDiscardReconcilesAndDeletes() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            val retryable = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(retryable.outcomeAmbiguous)
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            fixture.intake.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val deletion = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], reconciliation.headers["Idempotency-Key"])
            assertEquals("GET", reconciliation.method)
            assertEquals("DELETE", deletion.method)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationReconcilesAndDeletesReceiptAcceptedDuringUpload() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            val upload = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            val reconcile = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val deletion = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], reconcile.headers["Idempotency-Key"])
            assertEquals("GET", reconcile.method)
            assertEquals("DELETE", deletion.method)
            assertTrue(deletion.url.encodedPath.startsWith("/api/v1/reports/"))
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationStillDeletesWhenReceiptPersistenceFails() = runBlocking {
        var directorySyncs = 0
        testFixture(
            directorySync = {
                directorySyncs += 1
                if (directorySyncs == 5) throw IOException("Synthetic receipt persistence failure.")
            },
        ).use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))

            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals("GET", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
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
            val firstReconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", firstReconciliation.method)
            fixture.intake.close()

            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val retryReconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val deletion = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(upload.headers["Idempotency-Key"], retryReconciliation.headers["Idempotency-Key"])
            assertEquals("GET", retryReconciliation.method)
            assertEquals("DELETE", deletion.method)
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
    fun retriesPersistedDeletionCapabilityWithoutResubmitting() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val failedDeletion = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)
            assertEquals("DELETE", failedDeletion.method)
            fixture.intake.close()

            val restored = fixture.newIntake()
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            val retriedDeletion = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", retriedDeletion.method)
            assertEquals(4, fixture.server.requestCount)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun acceptedDeletionKeepsReceiptUntilStatusConfirmsRemoval() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(202).body("{}").build())
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            val reconciliation = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val acceptedDeletion = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val statusCheck = requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", reconciliation.method)
            assertEquals("DELETE", acceptedDeletion.method)
            assertEquals("GET", statusCheck.method)

            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            fixture.intake.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(fixture.intake.states().value)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun keepsDeletionCapabilityAfterTheLocalArchiveRetentionWindow() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
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
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun restoresDeletionCapabilityWhenTheWallClockMovesBackward() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            submission.join()
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
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
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun preservesLastPersistedReceiptWhenRetryStateCannotBeRewritten() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(receiptResponse(fixture.statusUrl).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
            fixture.server.enqueue(receiptResponse(fixture.statusUrl))
            fixture.server.enqueue(
                MockResponse.Builder().code(503).headersDelay(1, TimeUnit.SECONDS).build(),
            )

            val submission = launch(Dispatchers.Default) {
                fixture.intake.submit("A refresh failed.", "nightly", emptyList())
            }
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.intake.cancel())
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            val retainedRoot = File(fixture.root, "submissions-retained")
            Files.move(fixture.temporaryRoot.toPath(), retainedRoot.toPath())
            fixture.temporaryRoot.writeText("temporarily unavailable")
            submission.join()

            val state = assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(state.message.contains("could not be stored"))
            val descriptor = File(retainedRoot, "pending.json")
            assertTrue(descriptor.isFile)
            assertTrue(descriptor.readText().contains("OBI-ABCDE-23456"))

            assertTrue(fixture.temporaryRoot.delete())
            Files.move(retainedRoot.toPath(), fixture.temporaryRoot.toPath())
            fixture.intake.close()
            val restored = fixture.newIntake()
            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(restored.states().value)
            fixture.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            restored.retry()

            assertIs<SupportDiagnosticsSubmissionState.Cancelled>(restored.states().value)
            assertEquals("DELETE", requireNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS)).method)
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
        beforeBundlePackaging: () -> Unit = {},
        privateFileDelete: (File) -> Boolean = File::delete,
        submissionStorageBlocked: Boolean = false,
        pendingTemporaryBeforeInitialization: Boolean = false,
        invalidPendingBeforeInitialization: Boolean = false,
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
        if (invalidPendingBeforeInitialization) {
            require(temporaryRoot.isDirectory || temporaryRoot.mkdirs())
            File(temporaryRoot, "pending.json").writeText("not-json")
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
            beforeBundlePackaging = beforeBundlePackaging,
            privateFileDelete = privateFileDelete,
        )
    }

    private fun receiptResponse(
        statusUrl: String,
        supportCode: String = "OBI-ABCDE-23456",
        retentionDays: Long = 30,
        createdAtOffsetDays: Long = 0,
        retentionUntil: Instant? = null,
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
                  "deletionUrl": "$statusUrl",
                  "createdAt": "$createdAt",
                  "retentionUntil": "$resolvedRetentionUntil"
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
        val beforeBundlePackaging: () -> Unit,
        val privateFileDelete: (File) -> Boolean,
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
            beforeBundlePackaging = beforeBundlePackaging,
            privateFileDelete = privateFileDelete,
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
        const val TEST_ACCOUNT_IDENTITY = "0123456789abcdef0123456789abcdef"
        const val OTHER_ACCOUNT_IDENTITY = "fedcba9876543210fedcba9876543210"
    }
}
