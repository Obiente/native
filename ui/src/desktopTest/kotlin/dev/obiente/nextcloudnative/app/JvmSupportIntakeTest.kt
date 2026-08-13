package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
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
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
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
            assertTrue(fixture.temporaryRoot.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun hidesPendingSubmissionFromAnotherLocalAccount() = runBlocking {
        testFixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).build())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertIs<SupportDiagnosticsSubmissionState.RetryableFailure>(fixture.intake.states().value)
            assertTrue(File(fixture.temporaryRoot, "pending.json").isFile)
            fixture.intake.setActiveAccountIdentity(OTHER_ACCOUNT_IDENTITY)

            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
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

    private fun testFixture(): Fixture {
        val root = createTempDirectory("support-intake-test").toFile()
        val diagnosticRoot = File(root, "diagnostics")
        val temporaryRoot = File(root, "submissions")
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
        )
    }

    private fun receiptResponse(statusUrl: String): MockResponse {
        val createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val retentionUntil = createdAt.plus(30, ChronoUnit.DAYS)
        return MockResponse.Builder().code(201).body(
            """
                {
                  "contractVersion": 1,
                  "supportCode": "OBI-ABCDE-23456",
                  "status": "new",
                  "statusUrl": "$statusUrl",
                  "deletionUrl": "$statusUrl",
                  "createdAt": "$createdAt",
                  "retentionUntil": "$retentionUntil"
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
    ) : AutoCloseable {
        val intake = newIntake()
        val statusUrl: String get() = server.url("/r/abcdefghijklmnopqrstuvwxyzABCDEFGH_12345678").toString()

        fun newIntake() = JvmSupportIntake(
            diagnostics = diagnostics,
            temporaryRoot = temporaryRoot,
            environment = environment,
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            supportBaseUrl = server.url("/").toString(),
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
