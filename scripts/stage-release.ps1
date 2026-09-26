# SPDX-License-Identifier: AGPL-3.0-only
# Stage debug APKs for a release under change control.
# Usage (from the repo root):  .\scripts\stage-release.ps1 -Version 1.1.0
param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = 'Stop'

$src  = 'app\build\outputs\apk\debug'
$dest = "v$($Version -replace '^(\d+\.\d+).*','$1')"     # 1.1.0 -> v1.1
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$apks = Get-ChildItem "$src\app-*-debug.apk"
if (-not $apks) { throw "No debug APKs in $src. Run .\gradlew :app:assembleDebug first." }

$lines = @()
foreach ($a in $apks) {
    $abi    = $a.BaseName -replace '^app-','' -replace '-debug$',''
    $target = Join-Path $dest "onyx-v$Version-$abi-debug.apk"
    if (Test-Path $target) { throw "$target already exists (no-clobber). Remove it or bump the version." }
    Move-Item $a.FullName $target
    $h = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
    $lines += "$h  $(Split-Path $target -Leaf)"
    Write-Host ("{0,-45} {1,8:N1} MB  {2}" -f (Split-Path $target -Leaf), ((Get-Item $target).Length / 1MB), $h)
}
if (Test-Path "$src\output-metadata.json") { Move-Item "$src\output-metadata.json" $dest -Force }
# LF line endings so `sha256sum -c` also works on Linux/macOS
[IO.File]::WriteAllText((Join-Path (Resolve-Path $dest) 'SHA256SUMS.txt'), (($lines -join "`n") + "`n"), [Text.Encoding]::ASCII)
Write-Host "`nStaged in $dest. Paste SHA256SUMS.txt into RELEASE.md and tick the change record."
