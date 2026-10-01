<#
.SYNOPSIS
    用 GitHub API 直接把 workflow 文件创建到仓库里（完全不需要打开网页、不需要 git）。

.DESCRIPTION
    适用场景：GitHub 网页打不开、或者网页上传不了隐藏目录（.github/workflows/）。
    只需要一个 Personal Access Token。

.PARAMETER Token
    GitHub Personal Access Token（classic，勾 repo 权限）
    生成地址：https://github.com/settings/tokens

.PARAMETER Repo
    仓库全名，格式 用户名/仓库名

.PARAMETER File
    要上传的 workflow 文件路径

.PARAMETER Branch
    目标分支，默认 main

.EXAMPLE
    .\tools\upload-workflow.ps1 -Token "ghp_xxxxxxxx" -Repo "Wen0827-del/cllj"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][string]$Repo,
    [string]$File,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Continue"

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host ""; Write-Host "失败：$m" -ForegroundColor Red; exit 1 }

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptDir

if (-not $File) {
    $File = Join-Path $root "build-apk.yml"
}
if (-not (Test-Path $File)) {
    Fail "找不到要上传的 workflow 文件：$File"
}

Write-Step "仓库：$Repo"
Write-Step "分支：$Branch"
Write-Step "文件：$File"

# 强制 TLS 1.2（Windows PowerShell 5.1 默认可能用 TLS 1.0，会被 GitHub 拒绝）
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$content = [System.IO.File]::ReadAllText($File, [System.Text.Encoding]::UTF8)
Write-Host ("    内容长度：" + $content.Length + " 字符")

$targetPath = ".github/workflows/build-apk.yml"
$body = @{
    message = "Add GitHub Actions workflow: build APK"
    content = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($content))
    branch  = $Branch
} | ConvertTo-Json -Compress

$uri = "https://api.github.com/repos/$Repo/contents/$targetPath"
$headers = @{
    "Authorization"        = "Bearer $Token"
    "Accept"               = "application/vnd.github+json"
    "User-Agent"           = "MagnetRush-Uploader"
    "X-GitHub-Api-Version" = "2022-11-28"
}

Write-Step "正在上传到 $targetPath …"
try {
    $resp = Invoke-RestMethod -Uri $uri -Method Put -Headers $headers `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
            -ContentType "application/json; charset=utf-8" -TimeoutSec 60
} catch {
    $msg = $_.Exception.Message
    $detail = ""
    try {
        $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $detail = $sr.ReadToEnd()
    } catch {}

    if ($detail -match 'sha.*was not supplied|already exists') {
        Fail @"
仓库里已经存在这个文件了（$targetPath）。

说明它已经被创建过 —— 那就直接去 Actions 页面看看：
    https://github.com/$Repo/actions

如果那里还是空的，可能是文件的 YAML 有语法错误，
把文件内容发我，我检查。
"@
    }
    if ($msg -match '401|Unauthorized|Bad credentials') {
        Fail @"
Token 无效（401）。

检查：
  1) Token 有没有复制完整（ghp_ 开头，通常 40 位）
  2) 是不是过期了
  3) 重新去 https://github.com/settings/tokens 生成一个，勾选 repo 权限
"@
    }
    if ($msg -match '404|Not Found') {
        Fail @"
找不到仓库（404）。

检查：
  1) -Repo 参数格式对不对：应该是 用户名/仓库名（例如 Wen0827-del/cllj）
  2) Token 有没有这个仓库的写权限（classic token 要勾 repo）
"@
    }
    Fail "上传出错：$msg`n$detail"
}

Write-Host ""
Write-Host "上传成功！" -ForegroundColor Green
Write-Host ("    文件：" + $resp.content.path)
Write-Host ("    提交：" + $resp.commit.sha.Substring(0, 7))
Write-Host ""
Write-Host "接下来：" -ForegroundColor Cyan
Write-Host "  1. 打开 https://github.com/$Repo/actions"
Write-Host "  2. 左侧应该出现 'Build APK'"
Write-Host "  3. 点进去 → 右边 'Run workflow' → 绿色按钮"
Write-Host "  4. 等 3~6 分钟，页面底部 Artifacts 下载 MagnetRush-APK-arm64-v8a"
Write-Host ""
