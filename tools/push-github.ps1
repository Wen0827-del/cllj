<#
.SYNOPSIS
    把 MagnetRush 推到 GitHub。

.DESCRIPTION
    这个脚本是为了绕开一个具体环境限制：本机的 HTTPS 出口被完全封死，
    只有 SSH(22) 能通。所以它用工作区里生成的那把专用密钥走 SSH 推送。

    运行前请先把 .ghkeys\magnetrush_rsa.pub 的内容加到 GitHub：
    https://github.com/settings/ssh/new

.PARAMETER Repo
    GitHub 仓库的 SSH 地址，形如 git@github.com:用户名/MagnetRush.git

.PARAMETER Branch
    要推送的分支名，默认 main。

.PARAMETER KeyPath
    私钥路径，默认为脚本上一级目录的 .ghkeys\magnetrush_rsa

.EXAMPLE
    .\tools\push-github.ps1 -Repo "git@github.com:someone/MagnetRush.git"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Repo,

    [string]$Branch = "main",

    [string]$KeyPath
)

$ErrorActionPreference = "Stop"

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Write-Warn2($m){ Write-Host "    $m" -ForegroundColor Yellow }
function Fail($m) {
    Write-Host ""
    Write-Host "中止：$m" -ForegroundColor Red
    exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptDir

if (-not (Test-Path (Join-Path $root ".git"))) {
    Fail "工程根目录不是 git 仓库：$root"
}

if (-not $KeyPath) {
    $KeyPath = Join-Path (Split-Path -Parent $root) ".ghkeys\magnetrush_rsa"
}
if (-not (Test-Path $KeyPath)) {
    Fail @"
找不到私钥：$KeyPath

如果你已经在本机配好 GitHub 凭据，其实可以直接推：
    cd "$root"
    git remote add origin $Repo
    git push -u origin $Branch
"@
}

# 校验仓库地址格式
if ($Repo -notmatch '^(git@|ssh://)') {
    Fail @"
这个脚本只支持 SSH 地址（因为本机 HTTPS 出口被封）。

你给的是：$Repo

请换成 SSH 形式，例如：
    git@github.com:你的用户名/MagnetRush.git

（在仓库页面的 Code → SSH 里可以复制到）
"@
}

# 私钥路径在 Windows 上可能出现反斜杠，ssh 需要正斜杠
$keyForSsh = $KeyPath -replace '\\', '/'
$knownHosts = Join-Path (Split-Path -Parent $KeyPath) "known_hosts"
$knownForSsh = $knownHosts -replace '\\', '/'

$env:GIT_SSH_COMMAND = "ssh -i `"$keyForSsh`" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=`"$knownForSsh`""

Write-Step "仓库地址：$Repo"
Write-Step "使用私钥：$KeyPath"
Write-Host ""

# ------------------------------------------------------------------ 认证测试
Write-Step "测试 GitHub SSH 认证"
$testOutput = & ssh -i $keyForSsh -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new `
    -o "UserKnownHostsFile=$knownForSsh" -o BatchMode=yes -o ConnectTimeout=20 -T git@github.com 2>&1 | Out-String

if ($testOutput -match 'successfully authenticated') {
    Write-Ok "认证成功"
    if ($testOutput -match 'Hi ([^!]+)!') {
        Write-Ok "已登录为：$($Matches[1])"
    }
} elseif ($testOutput -match 'Permission denied \(publickey\)') {
    Fail @"
认证被拒 —— 说明公钥还没加到 GitHub（或者加错了）。

请打开 https://github.com/settings/ssh/new
把下面这个文件的内容整行粘进去（ssh-rsa 开头，一行不能断）：

    $KeyPath.pub

加完之后重新运行本脚本。
"@
} else {
    Write-Warn2 "认证测试输出异常，仍继续尝试推送："
    Write-Warn2 $testOutput.Trim()
}
Write-Host ""

# ------------------------------------------------------------------ 提交检查
Write-Step "检查工作区"
Push-Location $root
$dirty = git status --porcelain 2>&1 | Out-String
if ($dirty.Trim().Length -gt 0) {
    Write-Warn2 "有未提交的改动，先提交："
    Write-Host $dirty
    git add -A
    git -c user.name="Wen" -c user.email="abc1253825716@163.com" commit -m "更新" | Out-Null
    Write-Ok "已提交"
} else {
    Write-Ok "工作区干净"
}

$currentBranch = (git rev-parse --abbrev-ref HEAD 2>&1 | Out-String).Trim()
if ($currentBranch -ne $Branch) {
    Write-Warn2 "当前分支是 $currentBranch，将重命名为 $Branch"
    git branch -M $Branch
}
Pop-Location
Write-Host ""

# ------------------------------------------------------------------ 设置 remote
Write-Step "配置 remote origin"
Push-Location $root
$existing = git remote 2>&1 | Out-String
if ($existing -match 'origin') {
    git remote set-url origin $Repo
    Write-Ok "已更新 origin -> $Repo"
} else {
    git remote add origin $Repo
    Write-Ok "已添加 origin -> $Repo"
}

# ------------------------------------------------------------------ 推送
Write-Step "推送到 GitHub"
git push -u origin $Branch 2>&1 | ForEach-Object { Write-Host "    $_" }
$code = $LASTEXITCODE
Pop-Location

Write-Host ""
if ($code -ne 0) {
    Fail @"
推送失败（git 返回 $code）。

常见原因：
  1) 仓库还不存在 —— 先去 https://github.com/new 建一个**空的**仓库
     （不要勾选 Add README / .gitignore / license，否则会有冲突）
  2) 仓库名或用户名拼错
  3) 公钥加到了别的 GitHub 账号上
"@
}

Write-Host "推送成功！" -ForegroundColor Green
Write-Host ""
Write-Host "接下来：" -ForegroundColor Cyan
Write-Host "  1. 打开你的仓库页面 → Actions 标签页"
Write-Host "  2. 等 'Build APK' 跑完（约 3~6 分钟）"
Write-Host "  3. 页面底部 Artifacts 里下载 MagnetRush-APK-arm64-v8a"
Write-Host "  4. 解压出来就是可以直接装到手机的 APK"
Write-Host ""
