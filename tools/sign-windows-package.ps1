param(
    [Parameter(Mandatory = $true)]
    [string]$PackageDirectory,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersion
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$requiredEnvironment = @(
    "WINDOWS_SIGNING_CERTIFICATE_BASE64",
    "WINDOWS_SIGNING_CERTIFICATE_PASSWORD",
    "WINDOWS_SIGNING_CERTIFICATE_SHA256"
)
foreach ($name in $requiredEnvironment) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required Windows signing secret $name is unavailable."
    }
}

$packages = @(Get-ChildItem -LiteralPath $PackageDirectory -Filter "*.msi" -File)
if ($packages.Count -ne 1) {
    throw "Expected exactly one Windows MSI package, found $($packages.Count)."
}
$package = $packages[0]
$certificatePath = Join-Path $env:RUNNER_TEMP "nextcloud-native-windows-signing.pfx"
$certificateBytes = [Convert]::FromBase64String($env:WINDOWS_SIGNING_CERTIFICATE_BASE64)
[System.IO.File]::WriteAllBytes($certificatePath, $certificateBytes)
[Array]::Clear($certificateBytes, 0, $certificateBytes.Length)

try {
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $certificatePath,
        $env:WINDOWS_SIGNING_CERTIFICATE_PASSWORD,
        [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
    )
    try {
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            $digestBytes = $sha256.ComputeHash($certificate.RawData)
        } finally {
            $sha256.Dispose()
        }
        $actualDigest = ([System.BitConverter]::ToString($digestBytes)).Replace("-", "").ToLowerInvariant()
        $expectedDigest = $env:WINDOWS_SIGNING_CERTIFICATE_SHA256.Replace(":", "").Trim().ToLowerInvariant()
        if ($expectedDigest -notmatch "^[a-f0-9]{64}$" -or $actualDigest -ne $expectedDigest) {
            throw "The protected Windows signing certificate does not match the expected release identity."
        }
    } finally {
        $certificate.Dispose()
    }

    $signTool = Get-ChildItem `
        -Path "${env:ProgramFiles(x86)}\Windows Kits\10\bin" `
        -Filter "signtool.exe" `
        -Recurse `
        -File |
        Where-Object { $_.DirectoryName -match "\\x64$" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $signTool) {
        throw "Windows SDK SignTool was not found."
    }

    & $signTool.FullName sign `
        /f $certificatePath `
        /p $env:WINDOWS_SIGNING_CERTIFICATE_PASSWORD `
        /fd SHA256 `
        /tr "https://timestamp.digicert.com" `
        /td SHA256 `
        /d "Nextcloud Native" `
        /du "https://github.com/Obiente/nc-native" `
        $package.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "SignTool could not sign the Windows MSI."
    }

    & "$PSScriptRoot/verify-windows-package.ps1" `
        -PackageDirectory $PackageDirectory `
        -ExpectedVersion $ExpectedVersion `
        -RequireSignature `
        -ExpectedCertificateSha256 $env:WINDOWS_SIGNING_CERTIFICATE_SHA256
} finally {
    if (Test-Path -LiteralPath $certificatePath) {
        Remove-Item -LiteralPath $certificatePath -Force
    }
}
