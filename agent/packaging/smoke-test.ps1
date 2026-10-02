# Starts what agent/packaging/build.py made (spec 2026-10-02-agent-exe-design.md, 3): the app must pass its
# self-check, and the setup, in update mode, must install an app that passes it. Both .exe files are built without
# a console, so their exit codes are the answers. Everything happens in a temporary LOCALAPPDATA.
$ErrorActionPreference = "Stop"
$dist = Join-Path $PSScriptRoot "..\..\dist"
$version = (Get-Content (Join-Path $dist "School-Life-Assistant\version.txt") -Raw).Trim()
$env:LOCALAPPDATA = Join-Path ([System.IO.Path]::GetTempPath()) ("sla-smoke-" + [guid]::NewGuid())
New-Item -ItemType Directory $env:LOCALAPPDATA | Out-Null
$agentHome = Join-Path $env:LOCALAPPDATA "SchoolLifeAssistant"

function Start-Checked($program, [string[]] $arguments) {
    $process = Start-Process -FilePath $program -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        Get-ChildItem $agentHome -Filter *.log -ErrorAction SilentlyContinue | ForEach-Object { Get-Content $_.FullName }
        throw "$program $($arguments -join ' ') exited with $($process.ExitCode)"
    }
}

Start-Checked (Join-Path $dist "School-Life-Assistant\School-Life-Assistant.exe") @("self-check", $version)
Start-Checked (Join-Path $dist "School-Life-Assistant.exe") @("--update", "0")
Start-Checked (Join-Path $agentHome "app\School-Life-Assistant.exe") @("self-check", $version)
Write-Output "The app and the setup of School-Life-Assistant $version work."
