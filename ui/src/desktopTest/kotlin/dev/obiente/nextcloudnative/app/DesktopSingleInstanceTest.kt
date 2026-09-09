package dev.obiente.nextcloudnative.app

import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @Test
    fun serviceHandoffCanForwardWithoutCompetingForTheProcessLock() {
        val runtime = createTempDirectory("nextcloud-native-instance-service-handoff").toFile()
        val primary = assertIs<DesktopSingleInstanceStart.Primary>(DesktopSingleInstance.acquire(runtime))
        try {
            val activation = runBlocking {
                val received = async { primary.instance.activations.first() }
                yield()
                assertTrue(
                    DesktopSingleInstance.forwardToExisting(
                        activationKind = DesktopActivationKind.ShowWindow,
                        runtimeDirectory = runtime,
                    ),
                )
                received.await()
            }
            assertEquals(DesktopActivationKind.ShowWindow, activation.kind)
        } finally {
            primary.instance.close()
        }
    }

    @Test
    fun supervisedStandbyBecomesPrimaryAfterTheExistingProcessStops() {
        val runtime = createTempDirectory("nextcloud-native-instance-service-standby").toFile()
        val primary = assertIs<DesktopSingleInstanceStart.Primary>(DesktopSingleInstance.acquire(runtime))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val standby = executor.submit<DesktopSingleInstanceStart.Primary?> {
                DesktopSingleInstance.waitForPrimary(runtime, retryDelayMillis = 5L)
            }
            Thread.sleep(25L)
            primary.instance.close()

            val replacement = requireNotNull(standby.get(2L, TimeUnit.SECONDS))
            replacement.instance.close()
        } finally {
            primary.instance.close()
            executor.shutdownNow()
        }
    }

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
            val background = DesktopSingleInstance.acquire(
                runtime,
                forwardAttempts = 10,
                forwardDelayMillis = 5,
                activationKind = DesktopActivationKind.Background,
            )
            assertIs<DesktopSingleInstanceStart.Forwarded>(background)
            val activations = runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                    primary.instance.activations.take(3).toList()
                }
            }
            assertEquals(listOf(1L, 2L, 3L), activations.map(DesktopActivationRequest::sequence))
            assertEquals(
                listOf(
                    DesktopActivationKind.UpdateHandoffFailed,
                    DesktopActivationKind.ShowWindow,
                    DesktopActivationKind.Background,
                ),
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
