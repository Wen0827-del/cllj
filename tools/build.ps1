<#
.SYNOPSIS
    在 Windows / macOS / Linux 上把 MagnetRush 编译成 APK。

.DESCRIPTION
    这个脚本只做三件事：找 Java、找 Android SDK、找 Gradle，然后跑 assembleDebug。
    任何一步缺失都会给出明确的修复指引，而不是丢一堆 Gradle 报错。

.PARAMETER Abis
    要打包的 CPU 架构，逗号分隔。默认 arm64-v8a。
    其他可选：armeabi-v7a, x86, x86_64

.PARAMETER Variant
    Debug 或 Release。默认 Debug（用 Android 默认调试签名，可以直接装到手机）。

.PARAMETER Clean
    编译前先 clean。

.EXAMPLE
    .\tools\build.ps1
    .\tools\build.ps1 -Abis "arm64-v8a,armeabi-v7a" -Variant Release -Clean
#>
[CmdletBinding()]
param(
    [string]$Abis = "arm64-v8a",
    [ValidateSet("Debug", "Release")]
    [string]$Variant = "Debug",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "    $msg" -ForegroundColor Yellow }
function Fail($msg) {
    Write-Host ""
    Write-Host "构建中止：$msg" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------ 定位工程根目录
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptDir
if (-not (Test-Path (Join-Path $root "settings.gradle"))) {
    Fail "找不到工程根目录（$root 下没有 settings.gradle）"
}
Write-Step "工程目录：$root"

# ------------------------------------------------------------------ Java 17
Write-Step "检查 JDK"
$javaHome = $env:JAVA_HOME
$javaExe = $null

if ($javaHome -and (Test-Path (Join-Path $javaHome "bin/java.exe"))) {
    $javaExe = Join-Path $javaHome "bin/java.exe"
} elseif ($javaHome -and (Test-Path (Join-Path $javaHome "bin/java"))) {
    $javaExe = Join-Path $javaHome "bin/java"
} else {
    # 退而求其次：从常见安装位置找 JDK 17
    $candidates = @()
    if ($env:ProgramFiles) {
        $candidates += Get-ChildItem -Path (Join-Path $env:ProgramFiles "Microsoft") -Filter "jdk-17*" -Directory -ErrorAction SilentlyContinue
        $candidates += Get-ChildItem -Path (Join-Path $env:ProgramFiles "Eclipse Adoptium") -Filter "jdk-17*" -Directory -ErrorAction SilentlyContinue
        $candidates += Get-ChildItem -Path (Join-Path $env:ProgramFiles "Java") -Filter "jdk-17*" -Directory -ErrorAction SilentlyContinue
        $candidates += Get-ChildItem -Path (Join-Path $env:ProgramFiles "Android/Android Studio/jbr") -Directory -ErrorAction SilentlyContinue
    }
    foreach ($c in $candidates) {
        $exe = Join-Path $c.FullName "bin/java.exe"
        if (Test-Path $exe) { $javaExe = $exe; $javaHome = $c.FullName; break }
    }
}

if (-not $javaExe) {
    Fail @"
没有找到 JDK 17。Android Gradle Plugin 8.x 必须用 JDK 17（JDK 8 不行）。

装一个就行，任选其一：
  winget install Microsoft.OpenJDK.17
  或 https://adoptium.net/temurin/releases/?version=17

装完设置环境变量：  setx JAVA_HOME "C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot"
然后重开一个终端再跑本脚本。
"@
}

$env:JAVA_HOME = $javaHome
$versionOutput = & $javaExe -version 2>&1 | Out-String
if ($versionOutput -notmatch '"17\.' -and $versionOutput -notmatch 'version "2[0-9]\.') {
    Write-Warn2 "JAVA_HOME 指向的未必是 JDK 17：$versionOutput"
    Write-Warn2 "如果构建报 'Unsupported class file major version'，请换成 JDK 17。"
} else {
    Write-Ok ($versionOutput.Trim() -split "`n" | Select-Object -First 1)
}

# ------------------------------------------------------------------ Android SDK
Write-Step "检查 Android SDK"
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $guess = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $guess) { $sdk = $guess }
}

if (-not $sdk -or -not (Test-Path $sdk)) {
    Fail @"
没有找到 Android SDK。

两条路，选一条：

【A】装 Android Studio（最省心，自带 SDK）
    winget install Google.AndroidStudio
    装完打开一次，让它下载 SDK，然后在项目根目录建一个 local.properties：
        sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk

【B】只要命令行（体积小）
    1) 下载 commandline-tools：
       https://developer.android.com/studio#command-tools
    2) 解压到 C:\Android\Sdk\cmdline-tools\latest\
    3) 然后执行：
       C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-34" "build-tools;34.0.0"
       setx ANDROID_HOME "C:\Android\Sdk"
"@
}

Write-Ok "ANDROID_HOME = $sdk"

# 写 local.properties，让 Gradle 一定找得到 SDK
$localProps = Join-Path $root "local.properties"
$escaped = $sdk -replace '\\', '\\'
"sdk.dir=$escaped" | Out-File -FilePath $localProps -Encoding ascii -NoNewline
Write-Ok "已写入 $localProps"

# ------------------------------------------------------------------ Gradle
Write-Step "检查 Gradle"
$gradleCmd = $null
$g = Get-Command gradle -ErrorAction SilentlyContinue
if ($g) {
    $gradleCmd = $g.Source
    Write-Ok "使用 PATH 里的 gradle：$gradleCmd"
} else {
    # 找 Gradle 解压目录
    $gradleRoots = @(
        (Join-Path $env:USERPROFILE ".gradle\wrapper\dists"),
        "C:\Gradle",
        (Join-Path $env:LOCALAPPDATA "Programs\Gradle")
    )
    foreach ($r in $gradleRoots) {
        if (-not (Test-Path $r)) { continue }
        $found = Get-ChildItem -Path $r -Recurse -Filter "gradle.bat" -File -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($found) { $gradleCmd = $found.FullName; break }
    }
    if ($gradleCmd) { Write-Ok "使用本地 Gradle：$gradleCmd" }
}

if (-not $gradleCmd) {
    Fail @"
没有找到 Gradle 8.7+。

最简单的方式是让 Gradle Wrapper 自己下载（推荐）：
    cd "$root"
    gradle wrapper --gradle-version 8.7
    .\gradlew.bat assembleDebug

或者手动装：
    winget install Gradle.Gradle
    或 https://gradle.org/releases/ 下载 8.7 解压后把 bin 加进 PATH
"@
}

# ------------------------------------------------------------------ 开始构建
$task = if ($Variant -eq "Release") { ":app:assembleRelease" } else { ":app:assembleDebug" }

if ($Clean) {
    Write-Step "clean"
    & $gradleCmd clean --no-daemon -p $root
    if ($LASTEXITCODE -ne 0) { Fail "clean 失败" }
}

Write-Step "构建 $Variant（架构：$Abis）"
Write-Host ""

& $gradleCmd $task "-Pabis=$Abis" --no-daemon --stacktrace -p $root
$code = $LASTEXITCODE

Write-Host ""
if ($code -ne 0) {
    Fail @"
Gradle 返回 $code。

常见原因：
  1) 构建期间需要联网下载 androidx / libtorrent4j 依赖 —— 确认网络能访问
     maven.google.com 和 repo1.maven.org（国内可配镜像，见 README「国内网络」一节）。
  2) JDK 版本不对 —— 必须是 17。
  3) SDK 缺组件 —— 执行 sdkmanager "platforms;android-34" "build-tools;34.0.0"。
"@
}

# ------------------------------------------------------------------ 找产物
Write-Step "查找 APK"
$apkDir = Join-Path $root "app\build\outputs\apk"
$apks = Get-ChildItem -Path $apkDir -Recurse -Filter "*.apk" -File -ErrorAction SilentlyContinue

if (-not $apks -or $apks.Count -eq 0) {
    Fail "构建成功但没找到 APK，请检查 $apkDir"
}

Write-Host ""
Write-Host "构建成功！产物：" -ForegroundColor Green
foreach ($a in $apks) {
    $sizeMb = [math]::Round($a.Length / 1MB, 1)
    Write-Host ("  {0}  ({1} MB)" -f $a.FullName, $sizeMb) -ForegroundColor Green
}
Write-Host ""
Write-Host "安装到手机（需要开 USB 调试）：" -ForegroundColor Cyan
Write-Host ("  adb install -r `"{0}`"" -f $apks[0].FullName)
Write-Host ""
