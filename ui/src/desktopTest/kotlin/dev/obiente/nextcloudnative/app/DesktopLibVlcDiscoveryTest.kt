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
    private val linuxNames = LINUX_LIBVLC_NAMES

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

    @Test
    fun aRuntimeOnlyMultiarchInstallOnAnotherArchitectureIsFound() {
        val usr = root.resolve("usr").toString()
        val arm = library(root.resolve("usr/lib/aarch64-linux-gnu"), "libvlc.so.5", "libvlccore.so.9")
            .also { it.resolve("vlc/plugins").createDirectories() }
        val linux = LinuxNativeDiscoveryStrategy()
        val strategy = DesktopLibVlcDirectDiscoveryStrategy(linux, linuxNames) {
            linuxLibVlcCandidates(vlcjLinuxCandidates(usr), "aarch64", usr)
        }

        assertEquals(arm.toFile().absolutePath, strategy.discover(), "No -dev symlinks are needed.")
    }

    @Test
    fun theJvmsOwnTripletIsPreferredOverAnotherArchitecturesCopy() {
        val usr = root.resolve("usr").toString()
        val amd = library(root.resolve("usr/lib/x86_64-linux-gnu"), "libvlc.so.5", "libvlccore.so.9")
        val arm = library(root.resolve("usr/lib/aarch64-linux-gnu"), "libvlc.so.5", "libvlccore.so.9")

        for ((osArch, expected) in listOf("aarch64" to arm, "amd64" to amd)) {
            assertEquals(
                expected.toFile().absolutePath,
                firstDirectoryContainingLibraries(
                    linuxLibVlcCandidates(vlcjLinuxCandidates(usr), osArch, usr),
                    linuxNames,
                ),
                osArch,
            )
        }
    }

    @Test
    fun multiarchCandidatesKeepVlcjsOrderAndPutOtherTripletsLast() {
        val usr = "/usr"
        val configured = "/opt/configured-vlc"
        val candidates = linuxLibVlcCandidates(listOf(configured) + vlcjLinuxCandidates(usr) + "/usr/bin", "riscv64", usr)

        assertEquals(listOf(configured, "/usr/lib/riscv64-linux-gnu"), candidates.take(2))
        assertEquals(vlcjLinuxCandidates(usr) + "/usr/bin", candidates.drop(2).take(7))
        assertEquals(
            listOf("aarch64-linux-gnu", "arm-linux-gnueabihf", "powerpc64le-linux-gnu", "s390x-linux-gnu")
                .map { "/usr/lib/$it" },
            candidates.drop(9),
            "Triplets vlcj already names keep their place; nothing appears twice.",
        )
        assertEquals(candidates.distinct(), candidates)
        assertEquals(null, debianMultiarchTriplet("loongarch64"))
        assertEquals(
            vlcjLinuxCandidates(usr).size + 5,
            linuxLibVlcCandidates(vlcjLinuxCandidates(usr), "loongarch64", usr).size,
            "An unknown architecture only appends the known triplets.",
        )
    }

    @Test
    fun aNonStandardCopyBelowASystemDirectoryIsStillNotWalked() {
        val usr = root.resolve("usr").toString()
        library(root.resolve("usr/lib/vlc-custom/lib"), "libvlc.so.5", "libvlccore.so.9")
        library(root.resolve("usr/lib/x86_64-linux-gnu/nested"), "libvlc.so.5", "libvlccore.so.9")
        root.resolve("usr/lib64").createDirectories()

        assertNull(
            firstDirectoryContainingLibraries(linuxLibVlcCandidates(vlcjLinuxCandidates(usr), "amd64", usr), linuxNames),
        )
    }

    /** vlcj 4.12.1's Linux well-known directories, in its order, below [usr]. */
    private fun vlcjLinuxCandidates(usr: String) = listOf(
        "$usr/lib/x86_64-linux-gnu",
        "$usr/lib64",
        "$usr/local/lib64",
        "$usr/lib/i386-linux-gnu",
        "$usr/lib",
        "$usr/local/lib",
    )

    private fun directory(name: String): Path = root.resolve(name).createDirectories()

    private fun library(directory: Path, vararg names: String): Path {
        directory.createDirectories()
        names.forEach { directory.resolve(it).createFile() }
        return directory
    }
}
