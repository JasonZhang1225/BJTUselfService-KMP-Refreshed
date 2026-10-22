param([Parameter(Mandatory)][string]$MsiOrDirectory)
# Use a fresh ProductCode for same-version upgrades; preserve UpgradeCode.
# Keep this script ASCII-compatible with Windows PowerShell 5.1.
$ErrorActionPreference = 'Stop'
$msi = if (Test-Path -LiteralPath $MsiOrDirectory -PathType Leaf) {
    Get-Item -LiteralPath $MsiOrDirectory
} else {
    Get-ChildItem -LiteralPath $MsiOrDirectory -Filter '*.msi' | Select-Object -First 1
}
if (-not $msi) { throw "No MSI in $MsiOrDirectory" }
$installer = New-Object -ComObject WindowsInstaller.Installer
try {
    $db = $installer.OpenDatabase($msi.FullName, 1)
    $new = [guid]::NewGuid().ToString('B').ToUpper()
    $view = $db.OpenView("UPDATE `Property` SET `Value`='$new' WHERE `Property`='ProductCode'")
    $view.Execute() | Out-Null
    $view.Close()
    $db.Commit()
    Write-Output "Rewrote ProductCode to $new ($($msi.Name))"
} finally {
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($installer)
}
