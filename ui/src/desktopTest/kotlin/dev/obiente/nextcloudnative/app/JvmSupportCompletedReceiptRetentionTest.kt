package dev.obiente.nextcloudnative.app

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/**
 * Obiente Support keeps reports until they are deleted. Contract v1 receipts still carry
 * `retentionUntil = createdAt + 30 days`, but the app treats it as informational and keeps the
 * receipt until the reporter deletes it, the service confirms it is gone, or the account is removed.
 */
class JvmSupportCompletedReceiptRetentionTest {
    @Test
    fun completedReceiptOlderThanItsInformationalRetentionStaysViewableAcrossRestart() = runBlocking {
        RetentionFixture(receiptAgeDays = 45).use { fixture ->
            val submitted = assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(SUPPORT_CODE, submitted.reports.single().supportCode)
            fixture.server.enqueue(fixture.privateStatusResponse())

            assertIs<SupportDiagnosticsConversationResult.Updated>(fixture.intake.refreshCompletedReports())

            assertEquals("accepted", fixture.submittedReport().status)
            fixture.intake.close()
            fixture.newIntake().use { restored ->
                assertIs<SupportDiagnosticsSubmissionState.Submitted>(restored.states().value)
            }
            assertEquals(1, fixture.completedDescriptors().size)
        }
    }

    @Test
    fun freshContractV1ReceiptWithThirtyDayRetentionValidates() = runBlocking {
        RetentionFixture(receiptAgeDays = null).use { fixture ->
            fixture.server.enqueue(fixture.freshReceiptResponse())

            fixture.intake.submit("A refresh failed.", "nightly", emptyList())

            assertEquals(SUPPORT_CODE, fixture.submittedReport().supportCode)
            assertEquals(1, fixture.completedDescriptors().size)
        }
    }

    @Test
    fun notFoundStatusRemovesTheReceiptWithTheExistingMessage(): Unit = assertGoneStatusRemovesReceipt(404)

    @Test
    fun goneStatusRemovesTheReceiptWithTheExistingMessage(): Unit = assertGoneStatusRemovesReceipt(410)

    private fun assertGoneStatusRemovesReceipt(code: Int): Unit = runBlocking {
        RetentionFixture(receiptAgeDays = 45).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(code).body("{}").build())

            val result = fixture.intake.refreshCompletedReports()

            assertEquals(
                "This private support report is no longer available.",
                assertIs<SupportDiagnosticsConversationResult.Failed>(result).message,
            )
            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
            assertTrue(fixture.completedDescriptors().isEmpty())
            fixture.intake.close()
            fixture.newIntake().use { restored ->
                assertIs<SupportDiagnosticsSubmissionState.Idle>(restored.states().value)
            }
        }
    }

    @Test
    fun transientStatusFailureKeepsTheReceipt() = runBlocking {
        RetentionFixture(receiptAgeDays = 45).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(503).body("{}").build())

            assertIs<SupportDiagnosticsConversationResult.Failed>(fixture.intake.refreshCompletedReports())

            assertIs<SupportDiagnosticsSubmissionState.Submitted>(fixture.intake.states().value)
            assertEquals(1, fixture.completedDescriptors().size)
        }
    }

    @Test
    fun userDeletionRemovesAnOldReceipt() = runBlocking {
        RetentionFixture(receiptAgeDays = 45).use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(404).body("{}").build())

            val result = fixture.intake.deleteCompletedReport(fixture.submittedReport().recordId)

            assertIs<SupportDiagnosticsDeletionResult.Deleted>(result)
            assertEquals("DELETE", fixture.server.takeRequest().method)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
            assertTrue(fixture.completedDescriptors().isEmpty())
        }
    }

    @Test
    fun accountRemovalRemovesAnOldReceipt() = runBlocking {
        RetentionFixture(receiptAgeDays = 45).use { fixture ->
            fixture.intake.removeAccount(ACCOUNT_IDENTITY)

            assertTrue(fixture.completedDescriptors().isEmpty())
            fixture.intake.setActiveAccountIdentity(ACCOUNT_IDENTITY)
            assertIs<SupportDiagnosticsSubmissionState.Idle>(fixture.intake.states().value)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    private class RetentionFixture(receiptAgeDays: Long?) : AutoCloseable {
        val root: File = createTempDirectory("support-receipt-retention").toFile()
        val temporaryRoot = File(root, "submissions")
        val environment = SupportDiagnosticsEnvironment("0.1.0-test", "1", "Synthetic desktop", "Synthetic OS", "x86_64")
        val diagnostics = AsyncJvmSupportDiagnostics(File(root, "diagnostics"), environment, "support-receipt-retention")
        val server = MockWebServer().also { it.start() }
        val statusUrl: String = server.url("/r/abcdefghijklmnopqrstuvwxyzABCDEFGH_12345678").toString()

        init {
            diagnostics.record(
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Network,
                    operation = "network.synthetic",
                    outcome = "failed",
                ),
            )
            if (receiptAgeDays != null) {
                require(temporaryRoot.mkdirs())
                val createdAt = Instant.now().minus(receiptAgeDays, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
                File(temporaryRoot, "completed-${UUID.randomUUID()}.json").writeText(
                    """{"originAccountIdentity":"$ACCOUNT_IDENTITY","receipt":${receiptJson(createdAt)}}""",
                )
            }
        }

        val intake = newIntake()

        fun newIntake() = JvmSupportIntake(
            diagnostics = diagnostics,
            temporaryRoot = temporaryRoot,
            environment = environment,
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            supportBaseUrl = server.url("/").toString(),
            directorySync = {},
        ).also { intake ->
            intake.setActiveAccountIdentity(ACCOUNT_IDENTITY)
            runBlocking { intake.awaitInitialization() }
        }

        fun completedDescriptors(): List<File> = temporaryRoot.listFiles().orEmpty()
            .filter { file -> file.name.matches(Regex("completed-[0-9a-f-]{36}\\.json")) }

        fun submittedReport(): SupportDiagnosticsSubmissionState.SubmittedReport =
            assertIs<SupportDiagnosticsSubmissionState.Submitted>(intake.states().value).reports.single()

        fun receiptJson(createdAt: Instant): String =
            """{"contractVersion":1,"supportCode":"$SUPPORT_CODE","status":"new","statusUrl":"$statusUrl",""" +
                """"deletionUrl":"$statusUrl","createdAt":"$createdAt",""" +
                """"retentionUntil":"${createdAt.plus(30, ChronoUnit.DAYS)}"}"""

        fun freshReceiptResponse(): MockResponse = MockResponse.Builder().code(201)
            .body(receiptJson(Instant.now().truncatedTo(ChronoUnit.SECONDS)))
            .build()

        fun privateStatusResponse(): MockResponse {
            val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            return MockResponse.Builder().code(200).body(
                """{"contractVersion":1,"supportCode":"$SUPPORT_CODE","productId":"nextcloud-native",""" +
                    """"requestType":"bug","status":"accepted","createdAt":"$now","updatedAt":"$now",""" +
                    """"retentionUntil":"${now.plus(30, ChronoUnit.DAYS)}","messages":[]}""",
            ).build()
        }

        override fun close() {
            intake.close()
            diagnostics.close()
            server.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val ACCOUNT_IDENTITY = "0123456789abcdef0123456789abcdef"
        const val SUPPORT_CODE = "OBI-ABCDE-23456"
    }
}
