package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdatePresentationTest {
    @Test
    fun cancellationLabelMatchesPackageResumeSupport() {
        assertEquals(
            "Cancel download",
            appUpdateDownloadCancellationLabel(AppDistributionChannel.DirectDesktopPackage),
        )
        assertEquals(
            "Pause download",
            appUpdateDownloadCancellationLabel(AppDistributionChannel.DirectApk),
        )
    }

    @Test
    fun updateChangelogIncludesOnlyChangesAfterTheInstalledSource() {
        val release = AndroidDirectRelease(
            schemaVersion = 1,
            channel = "nightly-v1",
            versionName = "nightly-20260816-1200-run1-abcdef12",
            versionCode = 20_000_151,
            packageName = "dev.obiente.nextcloudnative",
            minimumAndroidSdk = 26,
            apkUrl = "https://example.invalid/update.apk",
            apkSize = 1,
            apkSha256 = "a".repeat(64),
            signingCertificateSha256Digests = listOf("b".repeat(64)),
            releaseNotesUrl = "https://example.invalid/release",
            changes = listOf(
                AppUpdateChange("already-installed", "fix", "Already present.", listOf("all"), 10),
                AppUpdateChange("new-change", "feature", "New in the target.", listOf("all"), 11),
                AppUpdateChange("later-change", "fix", "Not in the target.", listOf("all"), 16),
            ),
        )

        assertEquals(
            listOf("new-change"),
            appUpdateChangesSince(20_000_101, release).map(AppUpdateChange::id),
        )
    }
}
