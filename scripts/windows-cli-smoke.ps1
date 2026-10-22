#requires -Version 7.0
param([Parameter(Mandatory)][string]$AppDirectory)
$ErrorActionPreference = 'Stop'
$taskApp = (Resolve-Path -LiteralPath $AppDirectory).Path
$taskExe = @(Get-ChildItem -LiteralPath $taskApp -Filter *.exe)
if ($taskExe.Count -ne 1) { throw 'AppDirectory must contain exactly one application launcher' }
$taskSandbox = Join-Path ([IO.Path]::GetTempPath()) ('bjtuss-cli-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($taskSandbox) | Out-Null
function Invoke-Agent([string[]]$Command, [int]$Expected) {
    $taskStart = [Diagnostics.ProcessStartInfo]::new($taskExe[0].FullName)
    $taskStart.UseShellExecute = $false
    $taskStart.RedirectStandardOutput = $true
    $taskStart.RedirectStandardError = $true
    $taskStart.StandardOutputEncoding = [Text.Encoding]::UTF8
    $taskStart.StandardErrorEncoding = [Text.Encoding]::UTF8
    $taskStart.Environment['LOCALAPPDATA'] = $taskSandbox
    $taskStart.ArgumentList.Add('agent')
    foreach ($taskArgument in $Command) { $taskStart.ArgumentList.Add($taskArgument) }
    $taskProcess = [Diagnostics.Process]::Start($taskStart)
    $taskOutput = $taskProcess.StandardOutput.ReadToEndAsync()
    $taskErrors = $taskProcess.StandardError.ReadToEndAsync()
    if (-not $taskProcess.WaitForExit(30000)) { $taskProcess.Kill(); throw 'CLI timed out' }
    if ($taskProcess.ExitCode -ne $Expected) { throw ('Unexpected exit: '+$taskProcess.ExitCode) }
    Write-Output $taskOutput.GetAwaiter().GetResult()
}
$taskHelp = Invoke-Agent @('help') 0
if (-not $taskHelp.Contains('cache update')) { throw 'Missing help output' }
$taskStatus = (Invoke-Agent @('status','--json') 2) | ConvertFrom-Json
if ($taskStatus.schemaVersion -ne 1 -or -not $taskStatus.error) { throw 'Invalid no-cache JSON' }
$null = Invoke-Agent @('calendar','--from','2026-13-01') 64
$taskUpdate = (Invoke-Agent @('cache','update','--json') 2) | ConvertFrom-Json
if ($taskUpdate.schemaVersion -ne 1) { throw 'Invalid refresh error JSON' }
$null = Invoke-Agent @('cache','update','shell') 64
if (Test-Path -LiteralPath (Join-Path $taskSandbox 'BJTUselfServiceKMP/agent-ipc.json')) { throw 'Unexpected IPC endpoint left behind' }
Write-Output 'PASS: packaged CLI help, JSON, usage errors, no-cache refresh and unknown-source rejection (isolated LOCALAPPDATA).'
# 保留小型临时目录用于失败诊断；不删除或触碰用户真实缓存与凭据。
