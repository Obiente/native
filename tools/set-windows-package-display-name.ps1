param(
    [Parameter(Mandatory = $true)]
    [string]$Package
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Change display metadata before signing/attestation without changing launcher or upgrade IDs.
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Package).Path, 1)
try {
    $view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property` = ''ProductName''')
    try {
        $view.Execute()
        $row = $view.Fetch()
        if ($null -eq $row -or $row.StringData(1) -ne "NextcloudNative") {
            throw "Expected the unbranded jpackage product before applying display metadata."
        }
    } finally { $view.Close() }

    $view = $database.OpenView('SELECT `Shortcut`, `Name` FROM `Shortcut`')
    $shortcutIds = [System.Collections.Generic.List[string]]::new()
    try {
        $view.Execute()
        while ($null -ne ($row = $view.Fetch())) {
            if (($row.StringData(2) -split '\|')[-1] -ne "NextcloudNative") {
                throw "Unexpected shortcut name in the generated Windows package."
            }
            $shortcutId = $row.StringData(1)
            if ($shortcutId -notmatch '^[A-Za-z_][A-Za-z0-9_.]*$') {
                throw "Unexpected MSI shortcut identity."
            }
            $shortcutIds.Add($shortcutId)
        }
    } finally { $view.Close() }
    if ($shortcutIds.Count -ne 2) {
        throw "Expected both desktop and Start menu shortcuts."
    }
    $queries = @('UPDATE `Property` SET `Value` = ''nati.ve'' WHERE `Property` = ''ProductName''')
    foreach ($shortcutId in $shortcutIds) {
        $queries += "UPDATE ``Shortcut`` SET ``Name`` = 'nati.ve' WHERE ``Shortcut`` = '$shortcutId'"
    }
    foreach ($query in $queries) {
        $view = $database.OpenView($query)
        try { $view.Execute() } finally { $view.Close() }
    }
    $database.Commit()
} finally {
    [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
}
