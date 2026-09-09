package dev.obiente.nextcloudnative.app

internal val WINDOWS_INSTALLER_HANDOFF_SCRIPT = """
    param(
        [Parameter(Mandatory = ${'$'}true)][long]${'$'}ParentProcessId,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}InstallerPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}LauncherPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}UpdateGatePath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}AcknowledgementPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}AcknowledgementToken,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}CancellationPath,
        [Parameter(Mandatory = ${'$'}true)][string]${'$'}CancellationToken
    )

    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}updateGateStream = ${'$'}null
    ${'$'}relaunchApplication = ${'$'}false
    ${'$'}relaunchWithFailure = ${'$'}false
    function Test-HandoffCancellation {
        if (-not (Test-Path -LiteralPath ${'$'}CancellationPath -PathType Leaf)) {
            return ${'$'}false
        }
        ${'$'}cancellationInfo = Get-Item -LiteralPath ${'$'}CancellationPath -ErrorAction SilentlyContinue
        if (${'$'}null -eq ${'$'}cancellationInfo -or
            ${'$'}cancellationInfo.Length -gt 128) {
            return ${'$'}false
        }
        ${'$'}recordedToken = Get-Content -LiteralPath ${'$'}CancellationPath -Raw -ErrorAction SilentlyContinue
        return ${'$'}recordedToken -eq ${'$'}CancellationToken
    }
    try {
        if (-not (Test-Path -LiteralPath ${'$'}InstallerPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath ${'$'}LauncherPath -PathType Leaf)) {
            throw 'The verified installer or application launcher is unavailable.'
        }
        ${'$'}updateGateStream = [System.IO.File]::Open(
            ${'$'}UpdateGatePath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        ${'$'}updateGateStream.SetLength(0)
        ${'$'}gateBytes = [System.Text.Encoding]::ASCII.GetBytes([string]${'$'}PID)
        ${'$'}updateGateStream.Write(${'$'}gateBytes, 0, ${'$'}gateBytes.Length)
        ${'$'}updateGateStream.Flush(${'$'}true)
        Set-Content -LiteralPath ${'$'}AcknowledgementPath -Value ${'$'}AcknowledgementToken -NoNewline -Encoding ascii
        if (Test-HandoffCancellation) {
            throw 'The Windows installer handoff was cancelled before application exit.'
        }
        Wait-Process -Id ${'$'}ParentProcessId -ErrorAction SilentlyContinue
        if (Test-HandoffCancellation) {
            throw 'The Windows installer handoff was cancelled before installer launch.'
        }
        ${'$'}msiexecPath = Join-Path ${'$'}env:SystemRoot 'System32\msiexec.exe'
        if (-not (Test-Path -LiteralPath ${'$'}msiexecPath -PathType Leaf)) {
            throw 'The Windows Installer service executable is unavailable.'
        }
        ${'$'}quotedInstallerPath = '"' + ${'$'}InstallerPath + '"'
        ${'$'}installerProcess = Start-Process -FilePath ${'$'}msiexecPath `
            -ArgumentList @('/i', ${'$'}quotedInstallerPath, '/quiet', '/norestart', 'NEXTCLOUD_NATIVE_UPDATER_HANDOFF=1') `
            -WindowStyle Hidden -PassThru -Wait
        ${'$'}successfulExitCodes = @(0, 1641, 3010)
        if (${'$'}installerProcess.ExitCode -notin ${'$'}successfulExitCodes) {
            throw "The Windows installer exited with code ${'$'}(${'$'}installerProcess.ExitCode)."
        }
        ${'$'}relaunchApplication = ${'$'}true
    } catch {
        if (-not (Get-Process -Id ${'$'}ParentProcessId -ErrorAction SilentlyContinue) -and
            (Test-Path -LiteralPath ${'$'}LauncherPath -PathType Leaf)) {
            ${'$'}relaunchApplication = ${'$'}true
            ${'$'}relaunchWithFailure = ${'$'}true
        }
    } finally {
        Remove-Item -LiteralPath ${'$'}AcknowledgementPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ${'$'}CancellationPath -Force -ErrorAction SilentlyContinue
        if (${'$'}null -ne ${'$'}updateGateStream) {
            ${'$'}updateGateStream.Dispose()
        }
        Remove-Item -LiteralPath ${'$'}UpdateGatePath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ${'$'}PSCommandPath -Force -ErrorAction SilentlyContinue
    }
    if (${'$'}relaunchApplication -and (Test-Path -LiteralPath ${'$'}LauncherPath -PathType Leaf)) {
        try {
            if (${'$'}relaunchWithFailure) {
                Start-Process -FilePath ${'$'}LauncherPath `
                    -ArgumentList @('--update-handoff-failed') `
                    -ErrorAction Stop
            } else {
                Start-Process -FilePath ${'$'}LauncherPath -ErrorAction Stop
            }
        } catch {
            if (-not ${'$'}relaunchWithFailure) {
                Start-Process -FilePath ${'$'}LauncherPath `
                    -ArgumentList @('--update-handoff-failed') `
                    -ErrorAction SilentlyContinue
            }
        }
    }
""".trimIndent() + "\r\n"
