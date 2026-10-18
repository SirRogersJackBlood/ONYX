# SPDX-License-Identifier: AGPL-3.0-only
# Reproducible, F-Droid-compatible release build.
#
# For each ABI this builds EXACTLY what the F-Droid recipe builds (unsigned, -PonyxAbi=<abi>), then signs it
# with apksigner: v2 + v3 only (no v1/JAR metadata, no v4) and --alignment-preserved, so apksigner keeps the
# build's own zip padding instead of rewriting it (without it, the bytes no longer match F-Droid's build). F-Droid rebuilds
# the same APK from source, copies our signature onto it and checks they match, then publishes our signed APK.
#
#   .\scripts\build-release.ps1 -Version 1.3.1 -Sign                 # build, sign, hash, PGP-sign into v1.3\
#   .\scripts\build-release.ps1 -Version 1.3.0 -OutDir $env:TEMP\onyx-check -Expect .\v1.3\SHA256SUMS-release.txt
#                                                                    # self-test: rebuild and compare with a published set
#
# Needs: $env:ONYX_KS_PASS (and ONYX_KEY_PASS if different), local.properties onyx.keystore / onyx.keyAlias.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string[]]$Abis = @('armeabi-v7a', 'arm64-v8a', 'x86_64'),
    [string]$OutDir = '',
    [string]$Expect = '',
    [switch]$Sign,
    [string]$SignKey = 'sirrogersjackblood@proton.me'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
Set-Location $root

# ---- keystore + tools ----
$props = @{}
Get-Content (Join-Path $root 'local.properties') | ForEach-Object { if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $props[$matches[1]] = $matches[2] } }
$ks = $props['onyx.keystore']; $alias = if ($props['onyx.keyAlias']) { $props['onyx.keyAlias'] } else { 'onyx' }
if (-not $ks -or -not (Test-Path $ks)) { throw "onyx.keystore missing or not found in local.properties" }
if (-not $env:ONYX_KS_PASS) { throw "Set `$env:ONYX_KS_PASS first" }
if (-not $env:ONYX_KEY_PASS) { $env:ONYX_KEY_PASS = $env:ONYX_KS_PASS }
$sdk = if ($props['sdk.dir']) { $props['sdk.dir'] -replace '\\\\', '\' -replace '\\:', ':' } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '-.*$', '') } | Select-Object -Last 1
$apksigner = Join-Path $bt.FullName 'apksigner.bat'
if (-not (Test-Path $apksigner)) { throw "apksigner not found in $($bt.FullName)" }
Write-Host "apksigner: $apksigner"

$dest = if ($OutDir) { $OutDir } else { Join-Path $root "v$($Version -replace '^(\d+\.\d+).*','$1')" }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$unsignedDir = 'app\build\outputs\apk\release'
$lines = @()

foreach ($abi in $Abis) {
    $target = Join-Path $dest "onyx-v$Version-$abi-release.apk"
    if (Test-Path $target) { throw "$target already exists (no-clobber)." }
    Write-Host "`n=== ${abi}: unsigned build (same as F-Droid) ===" -ForegroundColor Cyan
    & .\gradlew --no-daemon clean ":app:assembleRelease" "-PonyxAbi=$abi" "-PonyxUnsigned"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed for $abi" }
    $unsigned = Get-ChildItem "$unsignedDir\app-$abi-release-unsigned.apk", "$unsignedDir\app-release-unsigned.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $unsigned) { throw "No unsigned APK found for $abi in $unsignedDir" }

    & $apksigner sign --ks $ks --ks-key-alias $alias --ks-pass env:ONYX_KS_PASS --key-pass env:ONYX_KEY_PASS `
        --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --alignment-preserved `
        --out $target $unsigned.FullName
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed for $abi" }
    & $apksigner verify --print-certs $target | Select-String 'SHA-256 digest'
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $abi" }

    $h = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    $lines += "$h  $(Split-Path $target -Leaf)"
    Write-Host ("{0,-42} {1,7:N1} MB  {2}" -f (Split-Path $target -Leaf), ((Get-Item $target).Length / 1MB), $h)
}

$sums = Join-Path (Resolve-Path $dest) 'SHA256SUMS-release.txt'
[IO.File]::WriteAllText($sums, (($lines -join "`n") + "`n"), [Text.Encoding]::ASCII)

if ($Expect) {
    $want = @{}
    Get-Content $Expect | ForEach-Object { $p = $_.Trim() -split '\s+'; if ($p.Count -ge 2) { $want[$p[1]] = $p[0].ToLower() } }
    $bad = 0
    foreach ($l in $lines) {
        $p = $l -split '\s+'
        if ($want[$p[1]] -eq $p[0]) { Write-Host "MATCH     $($p[1])" -ForegroundColor Green }
        else { Write-Host "DIFFERENT $($p[1])  built $($p[0])  expected $($want[$p[1]])" -ForegroundColor Red; $bad++ }
    }
    if ($bad) { throw "$bad APK(s) differ from $Expect" } else { Write-Host "Reproduced the published release byte for byte." -ForegroundColor Green }
}

if ($Sign) {
    & gpg --yes --local-user $SignKey --armor --detach-sign --output "$sums.asc" $sums
    if ($LASTEXITCODE -ne 0) { throw "gpg signing failed" }
    & gpg --verify "$sums.asc" $sums
}
Write-Host "`nDone: $dest" -ForegroundColor Cyan
