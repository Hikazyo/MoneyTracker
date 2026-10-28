# 打包一个可以安全外发的源码归档。
#
# 存在的理由：本项目出过一次事故——分发的 AndroidProjects.rar 里同时含
# 发布签名私钥、明文口令、口令说明文件和 debug 安装包。拿到的人可以签出
# 被系统认可的「升级包」，把用户账本一次性传走；debug 包还能被 adb run-as 直接读库。
#
# 那次事故的根因不是疏忽，而是**打包命令没有排除规则**：手动右键压缩不会帮你过滤。
# 所以这里把「该发什么」写成脚本，让安全打包成为默认动作，而不是靠记性。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\make-dist.ps1
#   powershell -ExecutionPolicy Bypass -File tools\make-dist.ps1 -VerifyOnly
#
# 注意：本脚本的**所有字符串字面量都用 ASCII**，中文只出现在注释里。
# 原因是 PowerShell 5.1 会把无 BOM 的 UTF-8 脚本按 ANSI 读取；一旦中文字符串
# 被错误解码，其中的字节可能被当成引号，直接破坏语法。注释里出现同样的问题
# 却无害（注释到行尾就结束）。这样处理之后，脚本有没有 BOM 都能正常跑。

[CmdletBinding()]
param(
    [string]$OutputDirectory = "dist",
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot

# 绝不允许出现在分发包里的内容
$forbiddenNamePatterns = @(
    # 交接文档只属于本机：里面记了本机路径、凭证位置与事故细节。
    # 它没进版本库（.git/info/exclude），但本脚本扫的是整个工作目录，所以必须在这里排除。
    'HANDOFF.md',
    'keystore.properties',
    'keystore-password.txt',
    'local.properties',
    '.jks',
    '.keystore',
    '.apk',
    '.aab',
    '.rar',
    '.zip'
)

# 目录级排除：构建产物、缓存、版本库元数据
$forbiddenDirectories = @('build', '.gradle', '.kotlin', '.git', '.idea')

function Get-RelativePath([string]$base, [string]$full) {
    $baseUri = [Uri]((Resolve-Path $base).Path + [IO.Path]::DirectorySeparatorChar)
    $fullUri = [Uri]$full
    return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($fullUri).ToString()) -replace '/', '\'
}

function Test-Forbidden([string]$relativePath) {
    $leaf = Split-Path -Leaf $relativePath
    foreach ($pattern in $forbiddenNamePatterns) {
        if ($leaf -like "*$pattern*") { return "name matches '$pattern'" }
    }
    foreach ($segment in $relativePath -split '\\') {
        if ($forbiddenDirectories -contains $segment) { return "inside '$segment'" }
    }
    return $null
}

function Test-Sensitive([string]$reason) {
    return $reason -match 'keystore|\.jks|\.keystore|\.apk|\.aab|password|HANDOFF'
}

Write-Host 'Scanning project...' -ForegroundColor Cyan

$allFiles = Get-ChildItem -Path $projectRoot -Recurse -File -Force -ErrorAction SilentlyContinue
$violations = @()
$included = @()
$includedBytes = 0

foreach ($file in $allFiles) {
    $relative = Get-RelativePath $projectRoot $file.FullName
    $reason = Test-Forbidden $relative
    if ($reason) {
        $violations += [pscustomobject]@{ File = $relative; Reason = $reason }
    }
    else {
        $included += $relative
        $includedBytes += $file.Length
    }
}

Write-Host ("  will include {0} files ({1:N0} KB)" -f $included.Count, ($includedBytes / 1KB))

# 只列出**敏感**的排除项。构建产物与 .git 内部文件有几千个，
# 全列出来会把真正需要警惕的那几行淹掉，反而看不出问题。
$critical = $violations | Where-Object { Test-Sensitive $_.Reason }

if ($critical) {
    Write-Host ''
    Write-Host 'EXCLUDED sensitive files (must never be shipped):' -ForegroundColor Yellow
    $critical | Sort-Object File | ForEach-Object {
        Write-Host ("  {0}  <- {1}" -f $_.File, $_.Reason) -ForegroundColor Yellow
    }
}
else {
    Write-Host '  (no sensitive files to exclude - keys live outside the project)' -ForegroundColor DarkGray
}
Write-Host ("  excluded {0} files total (build output + VCS metadata)" -f $violations.Count) -ForegroundColor DarkGray

# 再核对一遍：包含列表里不能有任何敏感项
$leaked = $included | Where-Object { Test-Forbidden $_ }
if ($leaked) {
    Write-Host 'ABORT: sensitive files found in the include list!' -ForegroundColor Red
    $leaked | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host 'OK: no keys, passwords, APKs or build output in the package.' -ForegroundColor Green

if ($VerifyOnly) { exit 0 }

$outputPath = Join-Path $projectRoot $OutputDirectory
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$archive = Join-Path $outputPath "MoneyTracker-src-$stamp.zip"

if (Test-Path $archive) { Remove-Item $archive -Force }

$temp = Join-Path ([IO.Path]::GetTempPath()) "mt-dist-$stamp"
New-Item -ItemType Directory -Path $temp -Force | Out-Null

foreach ($relative in $included) {
    $source = Join-Path $projectRoot $relative
    $target = Join-Path $temp $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item $source $target -Force
}

Compress-Archive -Path (Join-Path $temp '*') -DestinationPath $archive -Force
Remove-Item $temp -Recurse -Force

Write-Host ''
Write-Host ("Created: {0}" -f $archive) -ForegroundColor Green
Write-Host ("Size: {0:N0} KB" -f ((Get-Item $archive).Length / 1KB))
Write-Host ''
Write-Host 'NOTE: signing keys live in %USERPROFILE%\.android-keys\moneytracker\' -ForegroundColor Yellow
Write-Host 'They are outside the project, so this script never packages them.' -ForegroundColor Yellow
Write-Host 'Back that directory up separately - losing it means you can never' -ForegroundColor Yellow
Write-Host 'publish an update that existing installs will accept.' -ForegroundColor Yellow
