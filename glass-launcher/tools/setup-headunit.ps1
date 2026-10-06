# Glass Launcher - head unit setup over ADB (Windows PowerShell).
#
# Installs or updates the launcher and applies the theme to the head unit. Every step first
# checks what this unit's Android version and firmware allow: what it can change it changes,
# everything else is left exactly as it is and reported as "left as is". Nothing is deleted
# and the stock launcher stays installed.
#
# Usage (or double-click setup-headunit.bat):
#   powershell -ExecutionPolicy Bypass -File tools\setup-headunit.ps1 [-Theme Violet|Ocean|Emerald|Rose]
#       [-Layout dashboard|showcase|minimal|drive] [-Apk path] [-NoDark] [-NoWallpaper]
#       [-NoDefaultHome] [-Fresh] [-Serial DEVICE]
param(
    [ValidateSet('Violet', 'Ocean', 'Emerald', 'Rose')] [string]$Theme = 'Violet',
    [ValidateSet('', 'dashboard', 'showcase', 'minimal', 'drive')] [string]$Layout = '',
    [string]$Apk = (Join-Path $PSScriptRoot '..\dist\GlassLauncher.apk'),
    [switch]$NoDark, [switch]$NoWallpaper, [switch]$NoDefaultHome, [switch]$Fresh,
    [string]$Serial = ''
)

$Pkg = 'com.adnan.glasslauncher'
$Act = "$Pkg/.MainActivity"
$Listener = "$Pkg/$Pkg.MediaListenerService"
$script:Changed = @(); $script:Left = @()
function Ok($m) { Write-Host "  [ok]   $m" -ForegroundColor Green; $script:Changed += $m }
function Skip($m, $why) { Write-Host "  [--]   $m (left as is: $why)" -ForegroundColor DarkGray; $script:Left += "${m}: $why" }
function Adb { if ($Serial) { & adb -s $Serial @args 2>&1 } else { & adb @args 2>&1 } }
function Sh { (Adb shell @args | Out-String).Trim() }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host 'adb not found. Install Android platform-tools and add it to PATH.'; exit 1
}
if ((Adb get-state | Out-String).Trim() -ne 'device') {
    Write-Host 'No head unit found over ADB. Connect USB, enable USB debugging (often in the head unit''s'
    Write-Host 'developer/factory settings, sometimes behind a code) and accept the prompt on the screen.'; exit 1
}

$Sdk = 0; [int]::TryParse((Sh getprop ro.build.version.sdk), [ref]$Sdk) | Out-Null
$Rel = Sh getprop ro.build.version.release
Write-Host ("Head unit: {0} {1}" -f (Sh getprop ro.product.manufacturer), (Sh getprop ro.product.model))
Write-Host ("Android:   {0} (API {1}) - build {2}" -f $Rel, $Sdk, (Sh getprop ro.build.display.id))
Write-Host ("Screen:    {0} - density {1}" -f ((Sh wm size) -replace '.*: ', ''), ((Sh wm density) -replace '.*: ', ''))
Write-Host ''
if ($Sdk -lt 21) { Write-Host 'This unit runs Android older than 5.0, which Glass Launcher cannot run on.'; exit 1 }

Write-Host '1. Install / update'
if (-not (Test-Path $Apk)) { Write-Host "APK not found at $Apk"; exit 1 }
if ($Fresh) { Adb uninstall $Pkg | Out-Null }
$out = (Adb install -r $Apk | Out-String)
if ($out -match 'Success') { Ok 'Glass Launcher installed/updated' }
elseif ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
    Write-Host '  The installed copy was signed with a different key. Run again with -Fresh'
    Write-Host '  (uninstalls it first; the launcher''s own settings are reset).'; exit 1
} else { Write-Host "  Install failed: $out"; exit 1 }

Write-Host '2. Permissions'
if ($Sdk -ge 23) {
    foreach ($p in 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION') {
        if ((Sh pm grant $Pkg "android.permission.$p") -match '(?i)exception|error') { Skip $p 'not grantable on this firmware' }
        else { Ok "$p granted (weather)" }
    }
    if ($Sdk -ge 31) {
        if ((Sh pm grant $Pkg android.permission.BLUETOOTH_CONNECT) -match '(?i)exception|error') { Skip 'Nearby devices' 'not grantable' }
        else { Ok 'Nearby devices granted (phone status)' }
    }
} else { Skip 'Runtime permissions' "Android $Rel grants them at install" }

Write-Host '3. Now-playing access (notification listener)'
$done = $false
if ($Sdk -ge 27 -and -not ((Sh cmd notification allow_listener $Listener) -match '(?i)exception|error|unknown')) { $done = $true }
if (-not $done) {
    $cur = Sh settings get secure enabled_notification_listeners
    if ($cur -notmatch [regex]::Escape($Listener)) {
        $new = if (-not $cur -or $cur -eq 'null') { $Listener } else { "${cur}:$Listener" }
        Sh settings put secure enabled_notification_listeners "`"$new`"" | Out-Null
    }
}
if ((Sh settings get secure enabled_notification_listeners) -match [regex]::Escape($Listener)) { Ok 'Now-playing access on' }
else { Skip 'Now-playing access' 'firmware blocks it; allow it once from the music bar' }

Write-Host '4. Keep running in the background'
if ($Sdk -ge 23 -and ((Sh dumpsys deviceidle whitelist "+$Pkg") -match '(?i)added')) { Ok 'Battery optimisation off for the launcher' }
else { Skip 'Battery optimisation' 'not available on this firmware' }

Write-Host '5. Default home app'
if (-not $NoDefaultHome) {
    if ($Sdk -ge 24 -and ((Sh cmd package set-home-activity $Act) -match '(?i)success')) { Ok 'Glass Launcher is the default home' }
    else { Skip 'Default home' 'press Home on the unit and choose Glass Launcher > Always' }
} else { Skip 'Default home' '-NoDefaultHome' }

Write-Host '6. Theme'
$extra = @('--es', 'theme', $Theme)
$map = @{ 'dashboard' = 0; 'showcase' = 1; 'minimal' = 2; 'drive' = 3 }
if ($Layout) { $extra += @('--ei', 'home_layout', $map[$Layout]) }
if (-not $NoWallpaper) { $extra += @('--ez', 'apply_system_theme', 'true') }
if ((Sh am start -n $Act @extra) -match '(?i)error') { Skip 'Launcher theme' "launcher didn't start" }
else {
    Ok ("Launcher theme set to $Theme" + $(if ($Layout) { ", home layout $Layout" } else { '' }))
    if (-not $NoWallpaper) { Ok "System wallpaper matched to $Theme (the launcher shows what it could change)" }
}
if (-not $NoDark) {
    if ($Sdk -ge 29) {
        Sh cmd uimode night yes | Out-Null
        if ((Sh cmd uimode night) -match '(?i)yes') { Ok 'System dark mode on' } else { Skip 'Dark mode' "firmware doesn't allow changing it" }
    } else { Skip 'Dark mode' "Android $Rel has no system dark mode" }
}
Skip "Other apps' colours, icons and fonts" "Android doesn't allow this without root"
Skip 'Boot logo, button backlight, steering keys, EQ' 'vendor settings; open Car settings in the launcher'

Write-Host ''
Write-Host ("Done. Changed {0} item(s); left {1} as is." -f $script:Changed.Count, $script:Left.Count)
Write-Host 'To go back to the stock launcher: Android Settings > Apps > Default apps > Home app.'
