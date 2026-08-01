param(
    [Parameter(Mandatory = $true)]
    [string]$PackageDirectory,

    [Parameter(Mandatory = $true)]
    [string]$ArgumentsFile,

    [Parameter(Mandatory = $true)]
    [string]$JpackageResourceDirectory,

    [Parameter(Mandatory = $true)]
    [string]$GeneratedResourceDirectory,

    [Parameter(Mandatory = $true)]
    [string]$AppImage,

    [Parameter(Mandatory = $true)]
    [string]$Jpackage
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

foreach ($path in @($PackageDirectory, $ArgumentsFile, $JpackageResourceDirectory, $AppImage, $Jpackage)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required MSI packaging input does not exist: $path"
    }
}

$arguments = @(
    Get-Content -LiteralPath $ArgumentsFile | ForEach-Object {
        if ($_.Length -ge 2 -and $_.StartsWith('"') -and $_.EndsWith('"')) {
            $_.Substring(1, $_.Length - 2)
        } else {
            $_
        }
    }
)
$typeIndex = [Array]::IndexOf($arguments, "--type")
$resourceIndex = [Array]::IndexOf($arguments, "--resource-dir")
if ($typeIndex -lt 0 -or $arguments[$typeIndex + 1] -ne "msi" -or $resourceIndex -lt 0) {
    throw "The captured jpackage arguments do not describe an MSI with a resource directory."
}

function Get-ArgumentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    $index = [Array]::IndexOf($arguments, $Name)
    if ($index -lt 0 -or $index + 1 -ge $arguments.Count -or $arguments[$index + 1].StartsWith("--")) {
        throw "The captured jpackage arguments do not contain a value for $Name."
    }
    $arguments[$index + 1]
}

foreach ($requiredSwitch in @("--win-menu", "--win-shortcut", "--win-per-user-install")) {
    if ([Array]::IndexOf($arguments, $requiredSwitch) -lt 0) {
        throw "The captured jpackage arguments do not contain $requiredSwitch."
    }
}

$name = Get-ArgumentValue "--name"
$description = Get-ArgumentValue "--description"
$copyright = Get-ArgumentValue "--copyright"
$version = Get-ArgumentValue "--app-version"
$vendor = Get-ArgumentValue "--vendor"
$license = Get-ArgumentValue "--license-file"
$icon = Get-ArgumentValue "--icon"
$menuGroup = Get-ArgumentValue "--win-menu-group"
$upgradeUuid = Get-ArgumentValue "--win-upgrade-uuid"
$destination = Get-ArgumentValue "--dest"
if ($name -ne "NextcloudNative" -or
    -not (Test-Path -LiteralPath $license -PathType Leaf) -or
    -not (Test-Path -LiteralPath $icon -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $AppImage "NextcloudNative.exe") -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $AppImage "app/.jpackage.xml") -PathType Leaf)) {
    throw "The captured package metadata or application image is invalid."
}

if (Test-Path -LiteralPath $GeneratedResourceDirectory) {
    Remove-Item -LiteralPath $GeneratedResourceDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $GeneratedResourceDirectory | Out-Null
Get-ChildItem -LiteralPath $JpackageResourceDirectory -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $GeneratedResourceDirectory -Recurse -Force
}

$mainWixPath = Join-Path $GeneratedResourceDirectory "main.wxs"
$javaHome = Split-Path -Parent (Split-Path -Parent $Jpackage)
$jmod = Join-Path $javaHome "bin/jmod.exe"
$jpackageModule = Join-Path $javaHome "jmods/jdk.jpackage.jmod"
$moduleResources = Join-Path $GeneratedResourceDirectory "jdk-module"
if (-not (Test-Path -LiteralPath $jmod -PathType Leaf) -or
    -not (Test-Path -LiteralPath $jpackageModule -PathType Leaf)) {
    throw "The selected JDK does not contain the jpackage module resources."
}
& $jmod extract --dir $moduleResources $jpackageModule
if ($LASTEXITCODE -ne 0) {
    throw "Could not extract the selected JDK's jpackage resources."
}
$defaultMainWix = Join-Path $moduleResources "classes/jdk/jpackage/internal/resources/main.wxs"
if (-not (Test-Path -LiteralPath $defaultMainWix -PathType Leaf)) {
    throw "The selected Windows JDK does not contain the default main.wxs resource."
}
Copy-Item -LiteralPath $defaultMainWix -Destination $mainWixPath
Remove-Item -LiteralPath $moduleResources -Recurse -Force
$mainWix = Get-Content -LiteralPath $mainWixPath -Raw
$actionDefinition = @'
    <CustomAction
      Id="UnregisterNextcloudNativeSyncRoot"
      Directory="INSTALLDIR"
      ExeCommand="&quot;[INSTALLDIR]NextcloudNative.exe&quot; --unregister-windows-sync-root"
      Return="check" />

'@
$actionSequence = @'
      <Custom Action="UnregisterNextcloudNativeSyncRoot" Before="RemoveFiles">
        REMOVE~="ALL" AND NOT UPGRADINGPRODUCTCODE
      </Custom>
'@
if (-not $mainWix.Contains("    <InstallExecuteSequence>")) {
    throw "The jpackage WiX template has no InstallExecuteSequence insertion point."
}
if (-not $mainWix.Contains("    </InstallExecuteSequence>")) {
    throw "The jpackage WiX template has no uninstall sequence insertion point."
}
$mainWix = $mainWix.Replace(
    "    <InstallExecuteSequence>",
    $actionDefinition + "    <InstallExecuteSequence>"
).Replace(
    "    </InstallExecuteSequence>",
    $actionSequence + "    </InstallExecuteSequence>"
)
Set-Content -LiteralPath $mainWixPath -Value $mainWix -Encoding utf8NoBOM

$packages = @(Get-ChildItem -LiteralPath $PackageDirectory -Filter "*.msi" -File)
if ($packages.Count -ne 1) {
    throw "Expected exactly one initial MSI package, found $($packages.Count)."
}
Remove-Item -LiteralPath $packages[0].FullName
$resourceDirectory = (Resolve-Path -LiteralPath $GeneratedResourceDirectory).Path
$applicationImage = (Resolve-Path -LiteralPath $AppImage).Path

$rebuildArguments = @(
    "--type", "msi",
    "--app-image", $applicationImage,
    "--resource-dir", $resourceDirectory,
    "--dest", $destination,
    "--name", $name,
    "--description", $description,
    "--copyright", $copyright,
    "--app-version", $version,
    "--vendor", $vendor,
    "--license-file", $license,
    "--icon", $icon,
    "--win-menu",
    "--win-menu-group", $menuGroup,
    "--win-shortcut",
    "--win-per-user-install",
    "--win-upgrade-uuid", $upgradeUuid
)
& $Jpackage @rebuildArguments
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed while rebuilding the MSI with uninstall cleanup."
}
$rebuilt = @(Get-ChildItem -LiteralPath $PackageDirectory -Filter "*.msi" -File)
if ($rebuilt.Count -ne 1) {
    throw "Expected exactly one rebuilt MSI package, found $($rebuilt.Count)."
}
