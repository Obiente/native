package dev.obiente.nextcloudnative.app

import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            val recovery = DesktopSingleInstance.acquire(
                runtime,
                forwardAttempts = 10,
                forwardDelayMillis = 5,
                activationKind = DesktopActivationKind.UpdateHandoffFailed,
            )
            assertIs<DesktopSingleInstanceStart.Forwarded>(recovery)
            val forwarded = DesktopSingleInstance.acquire(runtime, forwardAttempts = 10, forwardDelayMillis = 5)
            assertIs<DesktopSingleInstanceStart.Forwarded>(forwarded)
            val activations = runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                    primary.instance.activations.take(2).toList()
                }
            }
            assertEquals(listOf(1L, 2L), activations.map(DesktopActivationRequest::sequence))
            assertEquals(
                listOf(DesktopActivationKind.UpdateHandoffFailed, DesktopActivationKind.ShowWindow),
                activations.map(DesktopActivationRequest::kind),
            )
        } finally {
            primary.instance.close()
        }

        val replacement = assertIs<DesktopSingleInstanceStart.Primary>(
            DesktopSingleInstance.acquire(runtime, forwardAttempts = 2, forwardDelayMillis = 1),
        )
        replacement.instance.close()
    }

    @Test
    fun launchBecomesPrimaryWhenTheIncumbentExitsDuringForwarding() {
        val runtime = createTempDirectory("nextcloud-native-instance-race").toFile()
        val lockPath = runtime.resolve("nextcloud-native.lock").toPath()
        val incumbentChannel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val incumbentLock = incumbentChannel.lock()
        val releaser = Thread {
            Thread.sleep(20)
            incumbentLock.release()
            incumbentChannel.close()
        }
        releaser.start()

        try {
            val replacement = assertIs<DesktopSingleInstanceStart.Primary>(
                DesktopSingleInstance.acquire(runtime, forwardAttempts = 200, forwardDelayMillis = 5),
            )
            replacement.instance.close()
        } finally {
            releaser.join()
            if (incumbentLock.isValid) incumbentLock.release()
            if (incumbentChannel.isOpen) incumbentChannel.close()
        }
    }
}
