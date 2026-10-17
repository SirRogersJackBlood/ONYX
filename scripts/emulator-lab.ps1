# SPDX-License-Identifier: AGPL-3.0-only
# ONYX emulator lab: creates test AVDs, boots each one, installs the x86_64 APK and runs
# smoke + stress checks (rotation, dark mode, big fonts, offline, Doze, memory pressure,
# background restriction, process death). Results land in lab-results\<timestamp>\.
#
#   First time:   .\scripts\emulator-lab.ps1 -Apk .\v1.2\onyx-v1.2.3-x86_64-debug.apk -Setup
#   Later runs:   .\scripts\emulator-lab.ps1 -Apk .\v1.2\onyx-v1.2.3-x86_64-debug.apk
#   One device:   ... -Only onyx-api30-lowram
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [switch]$Setup,
    [string]$Only = '',
    [string]$Package = 'io.github.proteu5.onyx',
    [int]$TorWaitSec = 90
)
$ErrorActionPreference = 'Stop'

$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$sdkmanager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdmanager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$emulator   = Join-Path $Sdk 'emulator\emulator.exe'
$adb        = Join-Path $Sdk 'platform-tools\adb.exe'
$Apk = (Resolve-Path $Apk).Path

# Hardware profiles. 'default' images have no Google services, like most F-Droid users' phones.
$Lab = @(
    @{ Name = 'onyx-api30-lowram'; Image = 'system-images;android-30;default;x86_64';     Device = 'pixel_4a';     Ram = 2048; FontScale = '1.0'; Note = 'Android 11 (minSdk), no Google services, 2 GB RAM' }
    @{ Name = 'onyx-api33-small';  Image = 'system-images;android-33;google_apis;x86_64'; Device = 'small_phone';  Ram = 2048; FontScale = '2.0'; Note = 'Android 13, small screen, 200% text' }
    @{ Name = 'onyx-api34-phone';  Image = 'system-images;android-34;google_apis;x86_64'; Device = 'pixel_7';      Ram = 4096; FontScale = '1.0'; Note = 'Android 14, mainstream phone' }
    @{ Name = 'onyx-api35-tablet'; Image = 'system-images;android-35;google_apis;x86_64'; Device = 'pixel_tablet'; Ram = 4096; FontScale = '1.0'; Note = 'Android 15, tablet' }
    @{ Name = 'onyx-api36-fold';   Image = 'system-images;android-36;google_apis;x86_64'; Device = 'pixel_fold';   Ram = 4096; FontScale = '1.0'; Note = 'Android 16 (targetSdk), foldable, edge-to-edge' }
)
if ($Only) { $Lab = @($Lab | Where-Object { $_.Name -eq $Only }); if (-not $Lab) { throw "No lab profile named $Only" } }

foreach ($t in $adb, $emulator) {
    if (-not (Test-Path $t)) { throw "Missing $t. In Android Studio: SDK Manager > SDK Tools > tick 'Android Emulator', 'Android SDK Platform-Tools' and 'Android SDK Command-line Tools (latest)'." }
}

# ------------------------------------------------------------------ setup
if ($Setup) {
    if (-not (Test-Path $sdkmanager)) { throw "Missing $sdkmanager. Install 'Android SDK Command-line Tools (latest)' in SDK Manager > SDK Tools." }
    if (-not $env:JAVA_HOME) {
        $jbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
    }
    Write-Host "Accepting SDK licenses..."
    1..20 | ForEach-Object { 'y' } | & $sdkmanager --licenses | Out-Null
    foreach ($a in $Lab) {
        Write-Host "Downloading $($a.Image) (1.5-3 GB)..."
        & $sdkmanager --install $a.Image
        Write-Host "Creating AVD $($a.Name) ($($a.Device))..."
        'no' | & $avdmanager create avd -n $a.Name -k $a.Image -d $a.Device --force | Out-Null
        $cfg = Join-Path $env:USERPROFILE ".android\avd\$($a.Name).avd\config.ini"
        $keep = Get-Content $cfg | Where-Object { $_ -notmatch '^(hw\.ramSize|hw\.keyboard|disk\.dataPartition\.size)=' }
        $keep + @("hw.ramSize=$($a.Ram)", 'hw.keyboard=yes', 'disk.dataPartition.size=4G') | Set-Content -Encoding ascii $cfg
    }
    Write-Host "Setup done."
}

# ------------------------------------------------------------------ helpers
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$out = Join-Path (Get-Location) "lab-results\$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$results = New-Object System.Collections.Generic.List[object]

# Windows PowerShell turns any stderr line from a native tool into an error record, which
# 'Stop' would throw (monkey prints its args to stderr). Run adb with 'Continue' and keep the text.
function Sh([string]$serial, [string]$cmd) {
    $ErrorActionPreference = 'Continue'
    (& $adb -s $serial shell $cmd 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
}
function Adb([string[]]$adbArgs) {
    $ErrorActionPreference = 'Continue'
    (& $adb @adbArgs 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
}
function Alive([string]$serial) { (Sh $serial "pidof $Package") -match '^\d' }
function Launch([string]$serial) { Sh $serial "monkey -p $Package -c android.intent.category.LAUNCHER 1" | Out-Null }
function Crashed([string]$serial) { (Sh $serial 'logcat -b crash -d') -match [regex]::Escape($Package) }
function Add([string]$avd, [string]$check, [string]$result, [string]$detail = '') {
    $results.Add([pscustomobject]@{ Avd = $avd; Check = $check; Result = $result; Detail = $detail })
    $c = @{ PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red' }[$result]
    Write-Host ("  {0,-4} {1} {2}" -f $result, $check, $detail) -ForegroundColor $c
}
function Check([string]$avd, [string]$serial, [string]$check, [int]$waitSec, [string]$failLevel = 'FAIL') {
    Start-Sleep -Seconds $waitSec
    if (Crashed $serial) {
        Add $avd $check 'FAIL' 'crash in logcat'
        Sh $serial 'logcat -b crash -d' | Add-Content -Encoding utf8 (Join-Path $out "$avd-crashes.txt")
        Sh $serial 'logcat -b crash -c' | Out-Null
        return
    }
    if (Alive $serial) { Add $avd $check 'PASS' } else { Add $avd $check $failLevel 'process not running' }
}

# ------------------------------------------------------------------ run
$port = 5580
foreach ($a in $Lab) {
    $serial = "emulator-$port"
    Write-Host "`n=== $($a.Name): $($a.Note) ===" -ForegroundColor Cyan
    $emu = Start-Process $emulator -PassThru -WindowStyle Minimized -ArgumentList @(
        '-avd', $a.Name, '-port', "$port", '-no-snapshot', '-no-boot-anim', '-gpu', 'swiftshader_indirect')
    try {
        Adb @('-s', $serial, 'wait-for-device') | Out-Null
        $booted = $false
        for ($i = 0; $i -lt 150; $i++) { if ((Sh $serial 'getprop sys.boot_completed') -eq '1') { $booted = $true; break }; Start-Sleep 2 }
        if (-not $booted) { Add $a.Name 'boot' 'FAIL' 'emulator did not finish booting in 5 min'; continue }
        Add $a.Name 'boot' 'PASS' (Sh $serial 'getprop ro.build.version.release')

        Sh $serial 'logcat -c' | Out-Null; Sh $serial 'logcat -b crash -c' | Out-Null
        $inst = Adb @('-s', $serial, 'install', '-r', '-g', $Apk)
        if ($inst -notmatch 'Success') { Add $a.Name 'install' 'FAIL' $inst; continue }
        Add $a.Name 'install' 'PASS'

        Sh $serial "settings put system font_scale $($a.FontScale)" | Out-Null
        Launch $serial
        Check $a.Name $serial "launch + Tor bootstrap ($TorWaitSec s)" $TorWaitSec

        Sh $serial 'settings put system accelerometer_rotation 0' | Out-Null
        Sh $serial 'settings put system user_rotation 1' | Out-Null
        Check $a.Name $serial 'rotate to landscape' 6
        Sh $serial 'settings put system user_rotation 0' | Out-Null

        Sh $serial 'cmd uimode night yes' | Out-Null
        Check $a.Name $serial 'dark mode' 6
        Sh $serial 'cmd uimode night no' | Out-Null

        Sh $serial 'cmd connectivity airplane-mode enable' | Out-Null
        Check $a.Name $serial 'offline (airplane mode, 20 s)' 20
        Sh $serial 'cmd connectivity airplane-mode disable' | Out-Null
        Check $a.Name $serial 'back online (30 s)' 30

        Sh $serial 'dumpsys deviceidle force-idle' | Out-Null
        Check $a.Name $serial 'Doze (20 s)' 20 'WARN'
        Sh $serial 'dumpsys deviceidle unforce' | Out-Null

        Sh $serial "am send-trim-memory $Package RUNNING_CRITICAL" | Out-Null
        Check $a.Name $serial 'memory pressure' 5

        Sh $serial "am set-standby-bucket $Package restricted" | Out-Null
        Check $a.Name $serial 'restricted background bucket (20 s)' 20 'WARN'
        Sh $serial "am set-standby-bucket $Package active" | Out-Null

        Sh $serial 'input keyevent KEYCODE_HOME' | Out-Null
        Sh $serial "am kill $Package" | Out-Null
        Launch $serial
        Check $a.Name $serial 'process death + relaunch' 25

        # Screen-lock lifecycle: the vault key must survive a PIN being added, then removed.
        Sh $serial 'locksettings set-pin 1234' | Out-Null
        Sh $serial "am force-stop $Package" | Out-Null
        Launch $serial
        Check $a.Name $serial 'screen lock added' 25
        Sh $serial 'locksettings clear --old 1234' | Out-Null
        Sh $serial "am force-stop $Package" | Out-Null
        Launch $serial
        Check $a.Name $serial 'screen lock removed again' 25

        Sh $serial 'settings put system font_scale 1.0' | Out-Null
        Adb @('-s', $serial, 'logcat', '-d', '-v', 'time') | Set-Content -Encoding utf8 (Join-Path $out "$($a.Name)-logcat.txt")
    }
    catch { Add $a.Name 'script' 'FAIL' $_.Exception.Message }
    finally {
        Adb @('-s', $serial, 'emu', 'kill') | Out-Null
        Start-Sleep 5
        if (-not $emu.HasExited) { Stop-Process -Id $emu.Id -Force -ErrorAction SilentlyContinue }
    }
}

$results | Export-Csv -NoTypeInformation -Encoding ascii (Join-Path $out 'results.csv')
$f = @($results | Where-Object Result -eq 'FAIL').Count
$w = @($results | Where-Object Result -eq 'WARN').Count
Write-Host "`nDone: $($results.Count) checks, $f FAIL, $w WARN. Results: $out" -ForegroundColor Cyan
