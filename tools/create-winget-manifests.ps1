param(
    [Parameter(Mandatory = $true)]
    [string]$PackageDirectory,

    [Parameter(Mandatory = $true)]
    [string]$PackageVersion,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseTag,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [string]$Repository = "Obiente/nc-native"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($PackageVersion -notmatch "^[0-9A-Za-z][0-9A-Za-z.+-]*$") {
    throw "The WinGet package version contains unsupported characters."
}
if ($ReleaseTag -ne "v$PackageVersion") {
    throw "The release tag must be v followed by the WinGet package version."
}
if ($Repository -notmatch "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$") {
    throw "The repository must use the owner/name form."
}

$packages = @(Get-ChildItem -LiteralPath $PackageDirectory -Filter "*.msi" -File)
if ($packages.Count -ne 1) {
    throw "Expected exactly one Windows MSI package, found $($packages.Count)."
}
$package = $packages[0]
if ($package.Name -notmatch "^[A-Za-z0-9._-]+\.msi$") {
    throw "The MSI filename is not safe for an immutable release URL."
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember(
    "OpenDatabase",
    "InvokeMethod",
    $null,
    $installer,
    @($package.FullName, 0)
)

function Read-MsiProperty {
    param([Parameter(Mandatory = $true)][string]$Name)

    if ($Name -notmatch "^[A-Za-z][A-Za-z0-9]*$") {
        throw "Invalid MSI property name."
    }
    $query = "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$Name'"
    $view = $database.GetType().InvokeMember(
        "OpenView",
        "InvokeMethod",
        $null,
        $database,
        @($query)
    )
    try {
        $view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null) | Out-Null
        $record = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)
        if ($null -eq $record) {
            throw "MSI property $Name is missing."
        }
        return $record.GetType().InvokeMember(
            "StringData",
            "GetProperty",
            $null,
            $record,
            @(1)
        )
    } finally {
        $view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null) | Out-Null
    }
}

$productName = Read-MsiProperty -Name "ProductName"
$displayVersion = Read-MsiProperty -Name "ProductVersion"
$publisher = Read-MsiProperty -Name "Manufacturer"
$productCode = Read-MsiProperty -Name "ProductCode"
$upgradeCode = Read-MsiProperty -Name "UpgradeCode"

if ($productName -ne "NextcloudNative") {
    throw "Unexpected Windows product name: $productName"
}
if ($publisher -ne "Obiente") {
    throw "Unexpected Windows package manufacturer: $publisher"
}
if ($displayVersion -notmatch "^[0-9]+\.[0-9]+\.[0-9]+$") {
    throw "The MSI display version is not compatible with WinGet metadata."
}
$guidPattern = "^\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\}$"
if ($productCode -notmatch $guidPattern) {
    throw "The MSI ProductCode is invalid."
}
$expectedUpgradeCode = "{81237D85-C511-47A7-B8DC-C87A5F5C5823}"
if ($upgradeCode.ToUpperInvariant() -ne $expectedUpgradeCode) {
    throw "Unexpected Windows upgrade identity."
}

$packageIdentifier = "Obiente.NextcloudNative"
$installerSha256 = (Get-FileHash -LiteralPath $package.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
$installerUrl = "https://github.com/$Repository/releases/download/$ReleaseTag/$($package.Name)"
$licenseUrl = "https://github.com/$Repository/blob/$ReleaseTag/LICENSE"
$releaseNotesUrl = "https://github.com/$Repository/releases/tag/$ReleaseTag"
$manifestDirectory = Join-Path $OutputDirectory "manifests/o/Obiente/NextcloudNative/$PackageVersion"
if (
    (Test-Path -LiteralPath $manifestDirectory) -and
    (Get-ChildItem -LiteralPath $manifestDirectory -Force | Select-Object -First 1)
) {
    throw "The WinGet manifest destination is not empty: $manifestDirectory"
}
New-Item -ItemType Directory -Path $manifestDirectory -Force | Out-Null

$versionManifest = @"
# yaml-language-server: `$schema=https://aka.ms/winget-manifest.version.1.12.0.schema.json

PackageIdentifier: $packageIdentifier
PackageVersion: $PackageVersion
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.12.0
"@

$installerManifest = @"
# yaml-language-server: `$schema=https://aka.ms/winget-manifest.installer.1.12.0.schema.json

PackageIdentifier: $packageIdentifier
PackageVersion: $PackageVersion
InstallerLocale: en-US
Platform:
- Windows.Desktop
InstallerType: wix
Scope: user
InstallModes:
- interactive
- silent
- silentWithProgress
UpgradeBehavior: install
ProductCode: '$productCode'
AppsAndFeaturesEntries:
- DisplayVersion: $displayVersion
  ProductCode: '$productCode'
  UpgradeCode: '$($upgradeCode.ToUpperInvariant())'
Installers:
- Architecture: x64
  InstallerUrl: $installerUrl
  InstallerSha256: $installerSha256
ManifestType: installer
ManifestVersion: 1.12.0
"@

$localeManifest = @"
# yaml-language-server: `$schema=https://aka.ms/winget-manifest.defaultLocale.1.12.0.schema.json

PackageIdentifier: $packageIdentifier
PackageVersion: $PackageVersion
PackageLocale: en-US
Publisher: Obiente
PublisherUrl: https://nc-native.obiente.dev/
PublisherSupportUrl: https://github.com/$Repository/issues
Author: Obiente
PackageName: Nextcloud Native
PackageUrl: https://nc-native.obiente.dev/
License: AGPL-3.0-or-later
LicenseUrl: $licenseUrl
Copyright: Copyright 2026 Obiente
ShortDescription: A native client for Nextcloud Files and installed Nextcloud apps.
Description: |-
  Nextcloud Native is an independent native client for Nextcloud Files and
  installed Nextcloud apps. It does not embed the Nextcloud web interface.
Moniker: nextcloud-native
Tags:
- cloud
- file-sync
- nextcloud
- productivity
- utility
ReleaseNotesUrl: $releaseNotesUrl
ManifestType: defaultLocale
ManifestVersion: 1.12.0
"@

$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
function Write-Manifest {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $path = Join-Path $manifestDirectory $Name
    $normalized = $Content.Replace("`r`n", "`n").TrimEnd() + "`n"
    [System.IO.File]::WriteAllText($path, $normalized, $utf8WithoutBom)
}

Write-Manifest -Name "$packageIdentifier.yaml" -Content $versionManifest
Write-Manifest -Name "$packageIdentifier.installer.yaml" -Content $installerManifest
Write-Manifest -Name "$packageIdentifier.locale.en-US.yaml" -Content $localeManifest

Write-Host "Created WinGet manifest candidate for $($package.Name) in $manifestDirectory."
