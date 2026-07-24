package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.AppDistributionChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidProjectContentClientTest {
    @Test
    fun storeOwnedAndDirectApkChannelsRemainDistinct() {
        assertEquals(
            AppDistributionChannel.GooglePlay,
            classifyAndroidDistribution("com.android.vending", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.FDroid,
            classifyAndroidDistribution("org.fdroid.fdroid", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.OtherStore,
            classifyAndroidDistribution("com.example.store", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.DirectApk,
            classifyAndroidDistribution(null, debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.DirectApk,
            classifyAndroidDistribution("com.android.packageinstaller", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.Development,
            classifyAndroidDistribution(null, debugBuild = true),
        )
    }
}
