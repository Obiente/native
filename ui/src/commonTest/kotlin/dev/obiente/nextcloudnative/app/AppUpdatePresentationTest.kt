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
}
