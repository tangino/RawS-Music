# ==============================================================================
# RawS-Music - Version Bump Script (PowerShell / Windows)
# ==============================================================================

param (
    [string]$TypeOrVersion = "",
    [int]$ExplicitCode = 0
)

$ErrorActionPreference = "Stop"

# Auto locate app/build.gradle.kts
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$GradleFile = Join-Path $ScriptDir "app\build.gradle.kts"

if (-not (Test-Path $GradleFile)) {
    if (Test-Path "app\build.gradle.kts") {
        $GradleFile = "app\build.gradle.kts"
    } elseif (Test-Path "RawS-Music\app\build.gradle.kts") {
        $GradleFile = "RawS-Music\app\build.gradle.kts"
    } else {
        Write-Host "[ERROR] Could not find app\build.gradle.kts" -ForegroundColor Red
        exit 1
    }
}

# Read content
$content = [System.IO.File]::ReadAllText((Resolve-Path $GradleFile).Path, [System.Text.Encoding]::UTF8)

$codePattern = 'versionCode\s*=\s*([0-9]+)'
$namePattern = 'versionName\s*=\s*"([^"]+)"'

$codeMatch = [System.Text.RegularExpressions.Regex]::Match($content, $codePattern)
$nameMatch = [System.Text.RegularExpressions.Regex]::Match($content, $namePattern)

if (-not $codeMatch.Success -or -not $nameMatch.Success) {
    Write-Host "[ERROR] Failed to parse version from $GradleFile !" -ForegroundColor Red
    exit 1
}

$CurrentCode = [int]$codeMatch.Groups[1].Value
$CurrentName = $nameMatch.Groups[1].Value

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "            RawS-Music Version Tool                   " -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "Current: " -NoNewline
Write-Host "versionName = `"$CurrentName`"" -ForegroundColor Green -NoNewline
Write-Host " | " -NoNewline
Write-Host "versionCode = $CurrentCode" -ForegroundColor Green
Write-Host ""

# Parse semantic version (e.g. 1.0.13 or 1.0.13-release)
$parts = $CurrentName.Split('.')
$majorStr = if ($parts.Length -ge 1) { [System.Text.RegularExpressions.Regex]::Match($parts[0], '^\d+').Value } else { "0" }
$minorStr = if ($parts.Length -ge 2) { [System.Text.RegularExpressions.Regex]::Match($parts[1], '^\d+').Value } else { "0" }
$patchStr = if ($parts.Length -ge 3) { [System.Text.RegularExpressions.Regex]::Match($parts[2], '^\d+').Value } else { "0" }

$major = if ([string]::IsNullOrEmpty($majorStr)) { 0 } else { [int]$majorStr }
$minor = if ([string]::IsNullOrEmpty($minorStr)) { 0 } else { [int]$minorStr }
$patch = if ([string]::IsNullOrEmpty($patchStr)) { 0 } else { [int]$patchStr }

# Extract suffix (e.g. -release or -beta)
$suffixMatch = [System.Text.RegularExpressions.Regex]::Match($CurrentName, '[-_][a-zA-Z0-9._-]+$')
$suffix = if ($suffixMatch.Success) { $suffixMatch.Value } else { "" }

$SuggestPatchName = "$major.$minor.$($patch + 1)$suffix"
$SuggestMinorName = "$major.$($minor + 1).0$suffix"
$SuggestMajorName = "$($major + 1).0.0$suffix"
$SuggestCode = $CurrentCode + 1

$NewName = ""
$NewCode = 0

if ($TypeOrVersion -ne "") {
    switch ($TypeOrVersion.ToLower()) {
        "patch" { $NewName = $SuggestPatchName; $NewCode = $SuggestCode }
        "p"     { $NewName = $SuggestPatchName; $NewCode = $SuggestCode }
        "minor" { $NewName = $SuggestMinorName; $NewCode = $SuggestCode }
        "m"     { $NewName = $SuggestMinorName; $NewCode = $SuggestCode }
        "major" { $NewName = $SuggestMajorName; $NewCode = $SuggestCode }
        default {
            $NewName = $TypeOrVersion
            $NewCode = if ($ExplicitCode -gt 0) { $ExplicitCode } else { $SuggestCode }
        }
    }
} else {
    Write-Host "Select upgrade type:"
    Write-Host "  1) Patch : $SuggestPatchName (versionCode: $SuggestCode)" -ForegroundColor Yellow
    Write-Host "  2) Minor : $SuggestMinorName (versionCode: $SuggestCode)" -ForegroundColor Yellow
    Write-Host "  3) Major : $SuggestMajorName (versionCode: $SuggestCode)" -ForegroundColor Yellow
    Write-Host "  4) Custom version input" -ForegroundColor Yellow
    Write-Host "  5) Exit" -ForegroundColor Yellow
    Write-Host ""
    $choice = Read-Host "Enter option [1-5] (default 1)"
    if ([string]::IsNullOrWhiteSpace($choice)) {
        $choice = "1"
    } else {
        $choice = $choice.Trim()
    }

    switch ($choice) {
        "1" { $NewName = $SuggestPatchName; $NewCode = $SuggestCode }
        "2" { $NewName = $SuggestMinorName; $NewCode = $SuggestCode }
        "3" { $NewName = $SuggestMajorName; $NewCode = $SuggestCode }
        "4" {
            $inputName = Read-Host "Enter new versionName (e.g. $SuggestPatchName)"
            if ([string]::IsNullOrWhiteSpace($inputName)) {
                Write-Host "[ERROR] versionName cannot be empty!" -ForegroundColor Red
                exit 1
            }
            $NewName = $inputName
            $inputCode = Read-Host "Enter new versionCode (press Enter for $SuggestCode)"
            if ([string]::IsNullOrWhiteSpace($inputCode)) {
                $NewCode = $SuggestCode
            } else {
                $NewCode = [int]$inputCode
            }
        }
        "5" { Write-Host "Operation cancelled."; exit 0 }
        default { Write-Host "[ERROR] Invalid choice!" -ForegroundColor Red; exit 1 }
    }
}

Write-Host ""
Write-Host "------------------------------------------------------" -ForegroundColor Cyan
Write-Host "Updating version:"
Write-Host "  versionName : $CurrentName -> $NewName" -ForegroundColor Green
Write-Host "  versionCode : $CurrentCode -> $NewCode" -ForegroundColor Green
Write-Host "------------------------------------------------------" -ForegroundColor Cyan

# Replace in content
$newContent = [System.Text.RegularExpressions.Regex]::Replace($content, 'versionCode\s*=\s*[0-9]+', "versionCode = $NewCode")
$newContent = [System.Text.RegularExpressions.Regex]::Replace($newContent, 'versionName\s*=\s*"[^"]+"', "versionName = `"$NewName`"")

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $GradleFile).Path, $newContent, $utf8NoBom)

Write-Host "[SUCCESS] Version successfully updated to $GradleFile !" -ForegroundColor Green
Write-Host ""
