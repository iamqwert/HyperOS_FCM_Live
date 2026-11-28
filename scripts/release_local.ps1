# 本机发版（GitHub Actions 被限制/排队不出 runner 时的兜底）
#
# 复刻 .github/workflows/android.yml 的 build + release 两步，只少了 CI 的
# artifact 中转：APK 直接留在 HyperGreeze/build/outputs/apk/release/。
#
#   只出包：            pwsh scripts/release_local.ps1
#   出包并装到设备：    pwsh scripts/release_local.ps1 -Install
#   出包并发 Release：  pwsh scripts/release_local.ps1 -Publish
#
# 签名：CI 用 secrets.SIGNING_KEY 里的正式 keystore 签名，所以本地要发布就必须
# 在 local.properties 里配好 storeFile/storePassword/keyAlias/keyPassword 四行。
# 没配时脚本会拒绝 -Publish —— debug 签名包（公开密钥）一旦发出去，任何人都能
# 用同签名包覆盖安装，而且正式签名用户从此无法再升级到正式包。
# 只出内部测试包时加 -AllowDebugSigned 显式放行。
#
# tag / 发行说明的推导与 CI 完全一致：
#   tag = v<versionName.versionCode>（如 v4.5.0.58）
#   发行说明 = CHANGELOG.md 的 `## 4.5.0` 小节 + .github/release-notes.md

#Requires -Version 7
param(
    [switch]$Publish,
    [switch]$Install,
    [switch]$SkipTests,
    [switch]$AllowDebugSigned
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) {
    Write-Host "✗ $msg" -ForegroundColor Red
    exit 1
}

# ── 版本号（单一来源：build.gradle；发版前记得手动 bump）──────────────────
$gradleFile = Join-Path $root 'HyperGreeze/build.gradle'
$gradleText = Get-Content $gradleFile -Raw
if ($gradleText -notmatch '(?m)^\s*versionCode\s+(\d+)') { Fail "读不到 versionCode：$gradleFile" }
$versionCode = $Matches[1]
if ($gradleText -notmatch '(?m)^\s*versionName\s+"([^"]+)"') { Fail "读不到 versionName：$gradleFile" }
$versionName = $Matches[1]
$fullVersion = "$versionName.$versionCode"      # 4.5.0.58
$tag = "v$fullVersion"

# ── 签名条件（与 build.gradle 的判据一致：看 storeFile 键，不看文件在不在）──
$localProps = Join-Path $root 'local.properties'
$hasSigning = (Test-Path $localProps) -and
    ((Get-Content $localProps) -match '^\s*storeFile\s*=')
if ($hasSigning) {
    $storeLine = (Get-Content $localProps) -match '^\s*storeFile\s*=' | Select-Object -First 1
    $storeRel = ($storeLine -split '=', 2)[1].Trim().Trim("'", '"')
    $storePath = if ([System.IO.Path]::IsPathRooted($storeRel)) { $storeRel } else { Join-Path $root $storeRel }
    if (-not (Test-Path $storePath)) { Fail "local.properties 指向的 keystore 不存在：$storePath" }
}

Write-Host "版本      : $fullVersion  (tag $tag)"
Write-Host "签名      : $(if ($hasSigning) { '正式 keystore' } else { 'debug（公开密钥）' })"

if (-not $hasSigning -and $Publish -and -not $AllowDebugSigned) {
    Fail "未配置正式签名，拒绝 -Publish。在 local.properties 补上 storeFile/storePassword/keyAlias/keyPassword，或加 -AllowDebugSigned 只出内部测试包。"
}

# ── 工作区状态（CI 构建的是 commit，本地构建的是工作树；不干净就别发版）────
$dirty = git status --porcelain
if ($dirty) {
    if ($Publish) { Fail "工作区有未提交改动，-Publish 会发出与 tag 不一致的包。先提交或 stash。" }
    Write-Host "! 工作区有未提交改动（只出包不发布，允许）" -ForegroundColor Yellow
}

# ── 检查 + 单测（与 CI 的 Validate docs / Run unit tests 对应）─────────────
Write-Host "`n== 文档与钩子目标检查 ==" -ForegroundColor Cyan
python scripts/check_docs.py
if ($LASTEXITCODE -ne 0) { Fail "check_docs.py 失败" }
python scripts/check_hook_targets.py
if ($LASTEXITCODE -ne 0) { Fail "check_hook_targets.py 失败" }

if (-not $SkipTests) {
    Write-Host "`n== 单元测试 ==" -ForegroundColor Cyan
    ./gradlew.bat --no-daemon :HyperGreeze:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { Fail "单元测试失败" }
}

# ── 构建 ────────────────────────────────────────────────────────────────
Write-Host "`n== assembleRelease ==" -ForegroundColor Cyan
$env:GRADLE_OPTS = '-Xmx4g'
if ($hasSigning) {
    ./gradlew.bat --no-daemon :HyperGreeze:assembleRelease
} else {
    Write-Host "! 未配置 SIGNING_KEY 等价物，产物用 debug 签名" -ForegroundColor Yellow
    ./gradlew.bat --no-daemon -PallowDebugSignedRelease :HyperGreeze:assembleRelease
}
if ($LASTEXITCODE -ne 0) { Fail "构建失败" }

$apkDir = Join-Path $root 'HyperGreeze/build/outputs/apk/release'
$apk = Get-ChildItem $apkDir -Filter *.apk | Select-Object -First 1
if (-not $apk) { Fail "没找到 APK：$apkDir" }
Write-Host "产物      : $($apk.FullName)  ($([math]::Round($apk.Length / 1MB, 2)) MB)"
if ($apk.BaseName -notlike "*$fullVersion*") {
    Write-Host "! APK 文件名里没有 $fullVersion，确认 build.gradle 是否已 bump" -ForegroundColor Yellow
}

# ── 安装 ────────────────────────────────────────────────────────────────
if ($Install) {
    Write-Host "`n== adb install ==" -ForegroundColor Cyan
    adb start-server | Out-Null          # 无线调试：起 server 后约 15 s 才重新发现设备
    adb install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { Fail "adb install 失败（设备没连上？）" }
}

if (-not $Publish) {
    Write-Host "`n完成（未发布）。要发 Release 加 -Publish。" -ForegroundColor Green
    exit 0
}

# ── 发布（对应 release job）──────────────────────────────────────────────
Write-Host "`n== 发布 $tag ==" -ForegroundColor Cyan
$existing = gh release view $tag --repo iamqwert/HyperOS_FCM_Live --json url 2>$null
if ($LASTEXITCODE -eq 0) {
    Fail "$tag 已存在：$existing 。同一版本号重复发布会覆盖资产，先 bump 版本或手工处理。"
}

$lines = Get-Content (Join-Path $root 'CHANGELOG.md')
$start = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match "^## $([regex]::Escape($versionName))(\s|$)") { $start = $i + 1; break }
}
if ($start -lt 0) {
    Write-Host "! CHANGELOG.md 里没有 $versionName 小节，发行说明只剩模板" -ForegroundColor Yellow
    $section = @()
} else {
    $section = @()
    for ($i = $start; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^## ') { break }
        $section += $lines[$i]
    }
}

$body = Join-Path ([System.IO.Path]::GetTempPath()) "hypergreeze-release-$tag.md"
$section | Set-Content $body -Encoding utf8
$templatePath = Join-Path $root '.github/release-notes.md'
if (Test-Path $templatePath) { Add-Content $body -Value (Get-Content $templatePath -Raw) -Encoding utf8 }

$sha = git rev-parse HEAD
gh release create $tag $apk.FullName `
    --repo iamqwert/HyperOS_FCM_Live `
    --title "HyperGreeze $fullVersion" `
    --notes-file $body `
    --target $sha `
    --latest
if ($LASTEXITCODE -ne 0) { Fail "gh release create 失败" }

Remove-Item $body -ErrorAction SilentlyContinue
Write-Host "`n已发布 $tag" -ForegroundColor Green
