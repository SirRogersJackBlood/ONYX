# SPDX-License-Identifier: AGPL-3.0-only
# Stage debug APKs for a pre-release under change control.
# Release builds: use scripts/build-release.ps1 instead (F-Droid reproducible signing; see RELEASING.md).
# Usage (from the repo root):  .\scripts\stage-release.ps1 -Version 1.1.0
param([Parameter(Mandatory = $true)][string]$Version, [switch]$Release, [switch]$Sign, [string]$SignKey = 'sirrogersjackblood@proton.me')
$ErrorActionPreference = 'Stop'

$type = if ($Release) { 'release' } else { 'debug' }   # -Release: signed release build (.\gradlew :app:assembleRelease)
$src  = "app\build\outputs\apk\$type"
$dest = "v$($Version -replace '^(\d+\.\d+).*','$1')"     # 1.1.0 -> v1.1
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$apks = Get-ChildItem "$src\app-*-$type.apk"
if (-not $apks) { throw "No $type APKs in $src. Run .\gradlew :app:assemble$((Get-Culture).TextInfo.ToTitleCase($type)) first." }

$lines = @()
foreach ($a in $apks) {
    $abi    = $a.BaseName -replace '^app-','' -replace "-$type$",''
    $target = Join-Path $dest "onyx-v$Version-$abi-$type.apk"
    if (Test-Path $target) { throw "$target already exists (no-clobber). Remove it or bump the version." }
    Move-Item $a.FullName $target
    $h = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    $lines += "$h  $(Split-Path $target -Leaf)"
    Write-Host ("{0,-45} {1,8:N1} MB  {2}" -f (Split-Path $target -Leaf), ((Get-Item $target).Length / 1MB), $h)
}
if (Test-Path "$src\output-metadata.json") { Move-Item "$src\output-metadata.json" $dest -Force }
# LF line endings so `sha256sum -c` also works on Linux/macOS
[IO.File]::WriteAllText((Join-Path (Resolve-Path $dest) 'SHA256SUMS.txt'), (($lines -join "`n") + "`n"), [Text.Encoding]::ASCII)
if ($Sign) {
    # PGP: one signature over the hash list, plus one per APK. gpg asks for the key passphrase.
    $sums = Join-Path $dest 'SHA256SUMS.txt'
    & gpg --yes --local-user $SignKey --armor --detach-sign --output "$sums.asc" $sums
    if ($LASTEXITCODE -ne 0) { throw "gpg signing failed" }
    Get-ChildItem (Join-Path $dest "onyx-v$Version-*.apk") | ForEach-Object {
        & gpg --yes --local-user $SignKey --armor --detach-sign --output "$($_.FullName).asc" $_.FullName
        if ($LASTEXITCODE -ne 0) { throw "gpg signing failed for $($_.Name)" }
    }
    & gpg --verify "$sums.asc" $sums
    Write-Host "Signed SHA256SUMS.txt and each APK with $SignKey."
}
Write-Host "`nStaged in $dest. Paste SHA256SUMS.txt into RELEASE.md and tick the change record."
