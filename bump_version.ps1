# ==============================================================================
# RawS-Music - 版本号修改/升级脚本 (PowerShell)
# ==============================================================================

param (
    [string]$TypeOrVersion = "",
    [int]$ExplicitCode = 0
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
$GradleFile = "app/build.gradle.kts"

if (-not (Test-Path $GradleFile)) {
    Write-Host "[ERROR] 未找到 $GradleFile 文件，请在项目根目录下运行。" -ForegroundColor Red
    exit 1
}

# 读取并提取当前版本
$content = [System.IO.File]::ReadAllText((Resolve-Path $GradleFile).Path, [System.Text.Encoding]::UTF8)

$codePattern = 'versionCode\s*=\s*([0-9]+)'
$namePattern = 'versionName\s*=\s*"([^"]+)"'

$codeMatch = [System.Text.RegularExpressions.Regex]::Match($content, $codePattern)
$nameMatch = [System.Text.RegularExpressions.Regex]::Match($content, $namePattern)

if (-not $codeMatch.Success -or -not $nameMatch.Success) {
    Write-Host "[ERROR] 无法从 $GradleFile 解析当前版本号！" -ForegroundColor Red
    exit 1
}

$CurrentCode = [int]$codeMatch.Groups[1].Value
$CurrentName = $nameMatch.Groups[1].Value

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "            RawS-Music 版本管理工具                 " -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "当前版本: " -NoNewline
Write-Host "versionName = `"$CurrentName`"" -ForegroundColor Green -NoNewline
Write-Host " | " -NoNewline
Write-Host "versionCode = $CurrentCode" -ForegroundColor Green
Write-Host ""

# 解析语义化版本号 (兼容 0.2.8-1 / 0.2.8_1 等带后缀版本号)
$parts = $CurrentName.Split('.')
$majorStr = if ($parts.Length -ge 1) { [System.Text.RegularExpressions.Regex]::Match($parts[0], '^\d+').Value } else { "0" }
$minorStr = if ($parts.Length -ge 2) { [System.Text.RegularExpressions.Regex]::Match($parts[1], '^\d+').Value } else { "0" }
$patchStr = if ($parts.Length -ge 3) { [System.Text.RegularExpressions.Regex]::Match($parts[2], '^\d+').Value } else { "0" }

$major = if ([string]::IsNullOrEmpty($majorStr)) { 0 } else { [int]$majorStr }
$minor = if ([string]::IsNullOrEmpty($minorStr)) { 0 } else { [int]$minorStr }
$patch = if ([string]::IsNullOrEmpty($patchStr)) { 0 } else { [int]$patchStr }

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
    Write-Host "请选择升级类型:"
    Write-Host "  1) Patch (补丁升级)   : $SuggestPatchName (versionCode: $SuggestCode)" -ForegroundColor Yellow
    Write-Host "  2) Minor (次版本升级) : $SuggestMinorName (versionCode: $SuggestCode)" -ForegroundColor Yellow
    Write-Host "  3) Major (主版本升级) : $SuggestMajorName (versionCode: $SuggestCode)" -ForegroundColor Yellow
    Write-Host "  4) 自定义版本号输入" -ForegroundColor Yellow
    Write-Host "  5) 退出" -ForegroundColor Yellow
    Write-Host ""
    $choice = Read-Host "请输入选项 [1-5] (默认 1)"
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
            $inputName = Read-Host "请输入新的 versionName (例如 $SuggestPatchName)"
            if ([string]::IsNullOrWhiteSpace($inputName)) {
                Write-Host "[ERROR] versionName 不能为空！" -ForegroundColor Red
                exit 1
            }
            $NewName = $inputName
            $inputCode = Read-Host "请输入新的 versionCode (回车默认 $SuggestCode)"
            if ([string]::IsNullOrWhiteSpace($inputCode)) {
                $NewCode = $SuggestCode
            } else {
                $NewCode = [int]$inputCode
            }
        }
        "5" { Write-Host "已取消操作。"; exit 0 }
        default { Write-Host "[ERROR] 无效选择！" -ForegroundColor Red; exit 1 }
    }
}

Write-Host ""
Write-Host "------------------------------------------------------" -ForegroundColor Cyan
Write-Host "准备更新版本:"
Write-Host "  versionName : $CurrentName -> $NewName" -ForegroundColor Green
Write-Host "  versionCode : $CurrentCode -> $NewCode" -ForegroundColor Green
Write-Host "------------------------------------------------------" -ForegroundColor Cyan

# 执行文本替换
$newContent = [System.Text.RegularExpressions.Regex]::Replace($content, 'versionCode\s*=\s*[0-9]+', "versionCode = $NewCode")
$newContent = [System.Text.RegularExpressions.Regex]::Replace($newContent, 'versionName\s*=\s*"[^"]+"', "versionName = `"$NewName`"")

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $GradleFile).Path, $newContent, $utf8NoBom)

Write-Host "[SUCCESS] 版本号已成功更新到 $GradleFile !" -ForegroundColor Green
Write-Host ""
