[CmdletBinding()]
param(
    [string]$GuardBin,
    [string]$RealGemini,
    [string]$TargetDir = (Join-Path $HOME "bin"),
    [string]$TargetName = "gemini.cmd",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    throw $Message
}

$scriptDir = Split-Path -Parent $PSCommandPath
$repoRoot = Split-Path -Parent $scriptDir
$defaultGuardBin = Join-Path $repoRoot "app-cli\build\install\llm-guard\bin\llm-guard.bat"
$resolvedGuardBin = if ([string]::IsNullOrWhiteSpace($GuardBin)) { $defaultGuardBin } else { $GuardBin }
$resolvedGuardBin = [System.IO.Path]::GetFullPath($resolvedGuardBin)

if (-not (Test-Path $resolvedGuardBin -PathType Leaf)) {
    Fail "llm-guard binary was not found at '$resolvedGuardBin'. Run .\gradlew.bat :app-cli:installDist first."
}

if ([string]::IsNullOrWhiteSpace($RealGemini)) {
    $command = Get-Command gemini -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        Fail "Could not resolve real gemini executable. Pass -RealGemini <path>."
    }
    $RealGemini = $command.Source
}

$resolvedRealGemini = [System.IO.Path]::GetFullPath($RealGemini)
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

$targetPath = Join-Path $TargetDir $TargetName
$resolvedTargetPath = [System.IO.Path]::GetFullPath($targetPath)

if ((Test-Path $resolvedTargetPath) -and -not $Force) {
    Fail "Target already exists: $resolvedTargetPath. Re-run with -Force to overwrite."
}

if ($resolvedRealGemini -ieq $resolvedTargetPath) {
    Fail "Real gemini path resolves to the wrapper target. Pass -RealGemini with the underlying executable path."
}

$wrapperContent = @"
@echo off
set "LLM_GUARD_REAL_GEMINI=$resolvedRealGemini"
call "$resolvedGuardBin" gemini %*
"@

Set-Content -Path $resolvedTargetPath -Value $wrapperContent -Encoding Ascii

Write-Host "Installed Gemini wrapper:"
Write-Host "  $resolvedTargetPath"
Write-Host ""
Write-Host "Real Gemini:"
Write-Host "  $resolvedRealGemini"
Write-Host ""
Write-Host "llm-guard:"
Write-Host "  $resolvedGuardBin"
Write-Host ""
Write-Host "Next:"
Write-Host "  1. Ensure $TargetDir is before the real Gemini location in PATH."
Write-Host '  2. From a project with llm-policy.yaml, run: gemini --guard-dry-run -p "test"'
