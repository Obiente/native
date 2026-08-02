package dev.obiente.nextcloudnative.app

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @Test
    fun laterLaunchActivatesTheExistingInstanceAndExits() {
        val runtime = createTempDirectory("nextcloud-native-instance").toFile()
        val primary = assertIs<DesktopSingleInstanceStart.Primary>(
            DesktopSingleInstance.acquire(runtime, forwardAttempts = 2, forwardDelayMillis = 1),
        )
        try {
            val endpoint = requireNotNull(runtime.listFiles()?.single { it.name.endsWith(".endpoint") })
            val port = endpoint.readLines().first().toInt()
            Socket(InetAddress.getLoopbackAddress(), port).close()
            val forwarded = DesktopSingleInstance.acquire(runtime, forwardAttempts = 10, forwardDelayMillis = 5)
            assertIs<DesktopSingleInstanceStart.Forwarded>(forwarded)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (primary.instance.activations.value.sequence == 0L && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            assertTrue(primary.instance.activations.value.sequence > 0L)
            assertEquals(DesktopActivationKind.ShowWindow, primary.instance.activations.value.kind)

            val recovery = DesktopSingleInstance.acquire(
                runtime,
                forwardAttempts = 10,
                forwardDelayMillis = 5,
                activationKind = DesktopActivationKind.UpdateHandoffFailed,
            )
            assertIs<DesktopSingleInstanceStart.Forwarded>(recovery)
            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (primary.instance.activations.value.sequence < 2L && System.nanoTime() < recoveryDeadline) {
                Thread.sleep(5)
            }
            assertEquals(2L, primary.instance.activations.value.sequence)
            assertEquals(DesktopActivationKind.UpdateHandoffFailed, primary.instance.activations.value.kind)
        } finally {
            primary.instance.close()
        }

        val replacement = assertIs<DesktopSingleInstanceStart.Primary>(
            DesktopSingleInstance.acquire(runtime, forwardAttempts = 2, forwardDelayMillis = 1),
        )
        replacement.instance.close()
    }
}
