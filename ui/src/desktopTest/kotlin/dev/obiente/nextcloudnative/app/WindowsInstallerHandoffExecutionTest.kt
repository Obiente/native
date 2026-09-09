package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class WindowsInstallerHandoffExecutionTest {
    @Test fun quietUpdateRelaunchesAfterSuccess() = exerciseHandoff(0, false)
    @Test fun rebootRequiredDoesNotForceAReboot() = exerciseHandoff(3010, false)
    @Test fun failedUpdateRelaunchesWithFailureNotice() = exerciseHandoff(1603, true)

    private fun exerciseHandoff(exitCode: Int, failed: Boolean) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("quiet-update-fixture-").toFile()
        try {
            val script = File(directory, "handoff.ps1").apply { writeText(WINDOWS_INSTALLER_HANDOFF_SCRIPT) }
            val wrapper = File(directory, "fixture.ps1").apply { writeText(FIXTURE) }
            File(directory, "package with spaces.msi").writeText("synthetic package")
            File(directory, "launcher.exe").writeText("synthetic launcher")
            val powershell = File(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
            val process = ProcessBuilder(
                powershell.absolutePath, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", wrapper.absolutePath, "-Root", directory.absolutePath, "-ExitCode", exitCode.toString(),
            ).redirectErrorStream(true).redirectOutput(File(directory, "process.log")).start()
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The synthetic handoff must complete.")
                assertEquals(0, process.exitValue(), "The PowerShell fixture must finish successfully.")
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
            val calls = File(directory, "calls.txt").readLines()
            assertEquals("waited", calls.first())
            assertTrue("/quiet" in calls)
            assertTrue("/norestart" in calls)
            assertTrue("NEXTCLOUD_NATIVE_UPDATER_HANDOFF=1" in calls)
            assertTrue("hidden=Hidden" in calls)
            assertTrue("\"${File(directory, "package with spaces.msi").absolutePath}\"" in calls)
            assertEquals(1, calls.count { it == "launcher" })
            assertEquals(failed, "--update-handoff-failed" in calls)
            assertTrue("gate-released" in calls)
            assertFalse(File(directory, "gate.lock").exists())
            assertFalse(File(directory, "ready.ack").exists())
            assertFalse(script.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

private val FIXTURE = """
    param([string]${'$'}Root, [int]${'$'}ExitCode)
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}script:waited = ${'$'}false
    ${'$'}calls = Join-Path ${'$'}Root 'calls.txt'
    function Wait-Process {
        param(${ '$' }Id, ${ '$' }ErrorAction)
        ${'$'}script:waited = ${'$'}true
        Add-Content -LiteralPath ${'$'}calls -Value 'waited'
    }
    function Get-Process { param(${ '$' }Id, ${ '$' }ErrorAction) return ${'$'}null }
    function Start-Process {
        param(${ '$' }FilePath, ${ '$' }ArgumentList, ${ '$' }WindowStyle,
              [switch]${'$'}PassThru, [switch]${'$'}Wait, ${ '$' }ErrorAction)
        if (${ '$' }FilePath -like '*msiexec.exe') {
            if (-not ${'$'}script:waited) { throw 'Installer started before app exit.' }
            Add-Content -LiteralPath ${'$'}calls -Value ${'$'}ArgumentList
            Add-Content -LiteralPath ${'$'}calls -Value "hidden=${'$'}WindowStyle"
            return [pscustomobject]@{ ExitCode = ${'$'}ExitCode }
        }
        Add-Content -LiteralPath ${'$'}calls -Value 'launcher'
        if (${ '$' }ArgumentList) { Add-Content -LiteralPath ${'$'}calls -Value ${'$'}ArgumentList }
        if (Test-Path -LiteralPath (Join-Path ${'$'}Root 'gate.lock')) { throw 'Update gate retained during relaunch.' }
        Add-Content -LiteralPath ${'$'}calls -Value 'gate-released'
    }
    & (Join-Path ${'$'}Root 'handoff.ps1') -ParentProcessId 42 `
        -InstallerPath (Join-Path ${'$'}Root 'package with spaces.msi') `
        -LauncherPath (Join-Path ${'$'}Root 'launcher.exe') `
        -UpdateGatePath (Join-Path ${'$'}Root 'gate.lock') `
        -AcknowledgementPath (Join-Path ${'$'}Root 'ready.ack') -AcknowledgementToken 'ready-token' `
        -CancellationPath (Join-Path ${'$'}Root 'cancel.ack') -CancellationToken 'cancel-token'
""".trimIndent()
