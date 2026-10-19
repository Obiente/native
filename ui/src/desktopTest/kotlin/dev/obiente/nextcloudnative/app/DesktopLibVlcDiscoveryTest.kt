package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy

/**
 * libVLC discovery checks each candidate directory itself and never walks below it, so a machine
 * without libVLC settles quickly instead of scanning whole system trees.
 */
class DesktopLibVlcDiscoveryTest {
    private val root: Path = Files.createTempDirectory("nc-libvlc-discovery")
    private val linuxNames = listOf(Regex("libvlc\\.so(?:\\.\\d)*"), Regex("libvlccore\\.so(?:\\.\\d)*"))

    @AfterTest
    fun deleteFixture() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun theFirstCandidateHoldingBothLibrariesWinsAndSubdirectoriesAreNeverSearched() {
        val nested = directory("nested").also { library(it.resolve("vlc/lib"), "libvlc.so.5", "libvlccore.so.9") }
        val direct = library(directory("direct"), "libvlc.so.5", "libvlccore.so.9")
        val later = library(directory("later"), "libvlc.so", "libvlccore.so")

        assertEquals(
            direct.toFile().absolutePath,
            firstDirectoryContainingLibraries(listOf(nested, direct, later).map(Path::toString), linuxNames),
        )
        assertNull(
            firstDirectoryContainingLibraries(listOf(nested.toString()), linuxNames),
            "A library below a candidate is not found by walking into it.",
        )
    }

    @Test
    fun incompleteMissingAndNonDirectoryCandidatesAreSkipped() {
        val onlyLibVlc = library(directory("only-libvlc"), "libvlc.so.5")
        val misnamed = library(directory("misnamed"), "libvlc.so.5.bak", "libvlccore.so.9")
        val coreAsDirectory = library(directory("core-as-directory"), "libvlc.so.5")
            .also { it.resolve("libvlccore.so.9").createDirectories() }
        val plainFile = root.resolve("plain-file").createFile()
        val missing = root.resolve("missing")

        assertNull(
            firstDirectoryContainingLibraries(
                listOf(onlyLibVlc, misnamed, coreAsDirectory, plainFile, missing).map(Path::toString) + "",
                linuxNames,
            ),
        )
    }

    @Test
    fun aSymbolicLinkLoopIsNotFollowed() {
        val looping = directory("looping")
        try {
            Files.createSymbolicLink(looping.resolve("again"), looping)
        } catch (failure: Exception) {
            assumeTrue("This host cannot create directory symbolic links: $failure", false)
        }

        assertNull(firstDirectoryContainingLibraries(listOf(looping.toString()), linuxNames))
    }

    @Test
    fun theStrategyReportsTheDirectCandidateAndKeepsVlcjsPlatformSupport() {
        val direct = library(directory("direct"), "libvlc.so.5", "libvlccore.so.9")
        val delegate = LinuxNativeDiscoveryStrategy()
        val strategy = DesktopLibVlcDirectDiscoveryStrategy(delegate, linuxNames) {
            listOf(directory("empty").toString(), direct.toString())
        }

        assertEquals(direct.toFile().absolutePath, strategy.discover())
        assertEquals(delegate.supported(), strategy.supported())
        assertEquals(
            1,
            desktopLibVlcDiscoveryStrategies().count { it.supported() },
            "Exactly one platform strategy applies on any desktop host.",
        )
    }

    private fun directory(name: String): Path = root.resolve(name).createDirectories()

    private fun library(directory: Path, vararg names: String): Path {
        directory.createDirectories()
        names.forEach { directory.resolve(it).createFile() }
        return directory
    }
}
