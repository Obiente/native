param(
    [Parameter(Mandatory = $true)]
    [string]$PackageDirectory,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersion
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$packages = @(Get-ChildItem -LiteralPath $PackageDirectory -Filter "*.msi" -File)
if ($packages.Count -ne 1) {
    throw "Expected exactly one Windows MSI package, found $($packages.Count)."
}
$package = $packages[0]
if ($package.Length -le 0) {
    throw "The Windows MSI package is empty."
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

function Read-MsiFileNames {
    $view = $database.GetType().InvokeMember(
        "OpenView",
        "InvokeMethod",
        $null,
        $database,
        @("SELECT ``FileName`` FROM ``File``")
    )
    try {
        $view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null) | Out-Null
        $names = [System.Collections.Generic.List[string]]::new()
        while ($true) {
            $record = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)
            if ($null -eq $record) {
                break
            }
            $encodedName = $record.GetType().InvokeMember(
                "StringData",
                "GetProperty",
                $null,
                $record,
                @(1)
            )
            foreach ($name in ($encodedName -split "\|")) {
                $names.Add($name)
            }
        }
        return $names
    } finally {
        $view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null) | Out-Null
    }
}

$productName = Read-MsiProperty -Name "ProductName"
$productVersion = Read-MsiProperty -Name "ProductVersion"
$manufacturer = Read-MsiProperty -Name "Manufacturer"
$upgradeCode = Read-MsiProperty -Name "UpgradeCode"

if ($productName -ne "NextcloudNative") {
    throw "Unexpected Windows product name: $productName"
}
if ($productVersion -ne $ExpectedVersion) {
    throw "Unexpected Windows package version: $productVersion"
}
if ($manufacturer -ne "Obiente") {
    throw "Unexpected Windows package manufacturer: $manufacturer"
}
$expectedUpgradeCode = "{81237D85-C511-47A7-B8DC-C87A5F5C5823}"
if ($upgradeCode.ToUpperInvariant() -ne $expectedUpgradeCode) {
    throw "Unexpected Windows upgrade identity."
}

$packagedFiles = @(Read-MsiFileNames)
foreach ($requiredFile in @("NextcloudNativeShellRegistrar.exe", "NextcloudNative.ico")) {
    if ($requiredFile -notin $packagedFiles) {
        throw "The Windows MSI does not contain $requiredFile."
    }
}

$signature = Get-AuthenticodeSignature -LiteralPath $package.FullName
if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::NotSigned) {
    throw "Expected an explicitly unsigned Windows MSI, found signature status $($signature.Status)."
}
if ($null -ne $signature.SignerCertificate) {
    throw "The unsigned Windows MSI unexpectedly reports a signer certificate."
}

Write-Host "Verified unsigned Windows MSI $($package.Name) ($productVersion)."
