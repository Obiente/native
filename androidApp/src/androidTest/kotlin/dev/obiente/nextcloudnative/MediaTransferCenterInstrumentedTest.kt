package dev.obiente.nextcloudnative

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.obiente.nextcloudnative.app.LocalMediaObject
import dev.obiente.nextcloudnative.app.MediaBackupLedgerRecord
import dev.obiente.nextcloudnative.app.MediaBackupReceipt
import dev.obiente.nextcloudnative.app.MediaBackupTransferState
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTransferCenterInstrumentedTest {
    @Test
    fun syntheticTransferHistorySupportsNavigationPagingAndClearConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val session = NextcloudSession(
            serverUrl = "https://fixture.invalid",
            loginName = "fixture-user",
            appPassword = "fixture-password",
        )
        val accountId = NextcloudDocumentIds.accountKey(session)
        val services = AndroidNextcloudServices(context)
        seedSyntheticHistory(context, accountId)
        services.saveSession(session)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openSettings(device)
            assertTrue(scrollUntilVisible(device, "Media transfers"))
            device.findObject(By.text("Media transfers")).click()

            assertVisible(device, "Transfers")
            device.findObject(By.text("Failed 1")).click()
            assertVisible(device, "upload-failed.jpg")

            device.findObject(By.text("Completed 2")).click()
            assertVisible(device, "upload-complete-1.jpg")
            assertTrue(device.wait(Until.hasObject(By.desc("Actions for transfer history")), WAIT_MILLIS))
            device.findObject(By.desc("Actions for transfer history")).click()
            assertVisible(device, "Clear completed history")
            device.findObject(By.text("Clear completed history")).click()
            assertVisible(device, "Clear completed transfer history?")
            assertVisible(device, "Keep history")
            assertVisible(device, "Clear local history")
            device.findObject(By.text("Clear local history")).click()

            assertVisible(device, "No completed uploads are in local history.")
            assertFalse(device.hasObject(By.text("upload-complete-1.jpg")))

            device.findObject(By.text("Pending 55")).click()
            assertTrue(scrollUntilVisible(device, "Older", TRANSFER_SCROLL_ATTEMPTS))
            device.findObject(By.text("Older")).click()
            assertVisible(device, "Newer")
        } finally {
            scenario.close()
            services.clearSession()
            clearSyntheticHistory(context, accountId)
        }
    }

    @Test
    fun syntheticLedgerFailureExplainsTheProblemAndOffersRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val session = NextcloudSession(
            serverUrl = "https://fixture.invalid",
            loginName = "fixture-user",
            appPassword = "fixture-password",
        )
        val services = AndroidNextcloudServices(context)
        resetLedgerFiles(context)
        ledgerFile(context).writeText("Synthetic invalid SQLite fixture")
        services.saveSession(session)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openSettings(device)
            assertTrue(scrollUntilVisible(device, "Media transfers"))
            device.findObject(By.text("Media transfers")).click()

            assertVisible(device, "Could not open the media backup ledger.")
            assertVisible(device, "Try again")
        } finally {
            scenario.close()
            services.clearSession()
            resetLedgerFiles(context)
        }
    }

    private fun openSettings(device: UiDevice) {
        assertVisible(device, "Settings")
        device.findObject(By.text("Settings")).click()
        assertVisible(device, "Appearance")
    }

    private fun assertVisible(device: UiDevice, text: String) {
        assertTrue(
            "Expected visible text: $text",
            device.wait(Until.hasObject(By.text(text)), WAIT_MILLIS),
        )
    }

    private fun scrollUntilVisible(
        device: UiDevice,
        text: String,
        attempts: Int = SETTINGS_SCROLL_ATTEMPTS,
    ): Boolean {
        repeat(attempts) {
            if (device.hasObject(By.text(text))) return true
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 4 / 5,
                device.displayWidth / 2,
                device.displayHeight / 3,
                20,
            )
            device.waitForIdle()
        }
        return device.hasObject(By.text(text))
    }

    private fun seedSyntheticHistory(
        context: android.content.Context,
        accountId: String,
    ) = runBlocking {
        resetLedgerFiles(context)
        val store = createAndroidMediaBackupLedgerStore(
            context = context,
            recoverInterruptedTransfers = false,
        )
        try {
            store.deleteAccount(accountId)
            store.upsertAll(
                List(55) { index ->
                    syntheticRecord(
                        accountId = accountId,
                        key = "pending-$index",
                        displayName = "upload-pending-$index.jpg",
                        state = MediaBackupTransferState.Pending,
                        updatedAtEpochMillis = 10_000L - index,
                    )
                } + listOf(
                    syntheticRecord(
                        accountId = accountId,
                        key = "uploading-1",
                        displayName = "upload-active.jpg",
                        state = MediaBackupTransferState.Uploading,
                        updatedAtEpochMillis = 9_000L,
                    ),
                    syntheticRecord(
                        accountId = accountId,
                        key = "failed-1",
                        displayName = "upload-failed.jpg",
                        state = MediaBackupTransferState.Failed,
                        updatedAtEpochMillis = 8_000L,
                    ),
                    syntheticRecord(
                        accountId = accountId,
                        key = "complete-1",
                        displayName = "upload-complete-1.jpg",
                        state = MediaBackupTransferState.Succeeded,
                        updatedAtEpochMillis = 7_000L,
                    ),
                    syntheticRecord(
                        accountId = accountId,
                        key = "complete-2",
                        displayName = "upload-complete-2.jpg",
                        state = MediaBackupTransferState.Succeeded,
                        updatedAtEpochMillis = 6_000L,
                    ),
                ),
            )
        } finally {
            store.close()
        }
    }

    private fun clearSyntheticHistory(
        context: android.content.Context,
        accountId: String,
    ) = runBlocking {
        val store = createAndroidMediaBackupLedgerStore(
            context = context,
            recoverInterruptedTransfers = false,
        )
        try {
            store.deleteAccount(accountId)
        } finally {
            store.close()
        }
    }

    private fun syntheticRecord(
        accountId: String,
        key: String,
        displayName: String,
        state: MediaBackupTransferState,
        updatedAtEpochMillis: Long,
    ): MediaBackupLedgerRecord {
        val local = LocalMediaObject(
            key = key,
            displayName = displayName,
            size = 4_096,
            revision = "fixture-revision",
        )
        val receipt = if (state == MediaBackupTransferState.Succeeded) {
            MediaBackupReceipt(
                localKey = key,
                localRevision = local.revision,
                localSize = local.size,
                remotePath = "Photos/Fixture/$displayName",
                remoteEtag = "\"fixture-etag-$key\"",
                verifiedAtEpochMillis = updatedAtEpochMillis,
            )
        } else {
            null
        }
        return MediaBackupLedgerRecord(
            accountId = accountId,
            local = local,
            receipt = receipt,
            transferState = state,
            attemptCount = if (state == MediaBackupTransferState.Pending) 0 else 1,
            updatedAtEpochMillis = updatedAtEpochMillis,
            failureMessage = if (state == MediaBackupTransferState.Failed) {
                "Synthetic network interruption"
            } else {
                null
            },
        )
    }

    private fun resetLedgerFiles(context: android.content.Context) {
        val database = ledgerFile(context)
        listOf(
            database,
            File(database.parentFile, "${database.name}-wal"),
            File(database.parentFile, "${database.name}-shm"),
        ).forEach { file ->
            check(!file.exists() || file.delete()) {
                "Could not reset the synthetic media ledger fixture."
            }
        }
    }

    private fun ledgerFile(context: android.content.Context): File =
        File(context.noBackupFilesDir, "media-backup-ledger.db")

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val SETTINGS_SCROLL_ATTEMPTS = 8
        const val TRANSFER_SCROLL_ATTEMPTS = 40
    }
}
