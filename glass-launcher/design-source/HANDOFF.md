# NAM5240T Glass Launcher — build brief for Claude Code

You are building an **installable Android home-screen launcher (APK)** for a Nakamichi NAM5240T Android car head unit. The complete visual design and interaction behaviour already exist as HTML prototypes in `design/`. Rebuild them natively. Treat this file as the spec; open the prototypes to check exact layout, colours, spacing and copy.

---

## 1. Device and context

| Item | Value | Confidence |
|---|---|---|
| Head unit | Nakamichi NAM5240T (variants A9/AX/A9Z/AXZ) | Stated by owner |
| Screen | Designed for **1280×720** (10.1" model). The 9" model is 1360×800 | From the NAM5240 manual. **Check `adb shell wm size` on the unit** |
| Android version | Manual says "Android NK13.0". The actual API level is **unknown** | **Check with `adb shell getprop ro.build.version.sdk`** before choosing `targetSdk` |
| Input | Capacitive touch. Steering-wheel keys are handled by the vendor MCU, not this app | — |
| Driving side | **Right-hand drive (Bangladesh).** Driver sits on the right, so primary controls go on the right edge | Stated by owner |
| Storage | 32 GB | From the manual |
| APK install | Allowed: the manual lists "APK download, installation and removal" | From the manual |

**Do first:** connect over ADB (USB debugging is usually in the head unit's developer or factory settings, and may need a vendor code) and record the screen size, density (`wm density`), SDK level and the package names of the vendor apps (`pm list packages`). Several features below depend on these.

---

## 2. Tech stack (recommended)

- **Kotlin + Jetpack Compose**, single-activity app, landscape only.
- `minSdk` = what the unit reports (assume 26 until checked). `targetSdk` = the unit's SDK level.
- DataStore (Preferences) for settings, which persist across reboots.
- No network libraries needed beyond `HttpURLConnection`/OkHttp for weather.
- Package name suggestion: `com.adnan.glasslauncher`.

### Make it a launcher
```xml
<activity android:name=".MainActivity"
    android:launchMode="singleTask"
    android:screenOrientation="sensorLandscape"
    android:stateNotNeeded="true"
    android:resumeWhilePausing="true"
    android:excludeFromRecents="true">
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.HOME"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.LAUNCHER"/>
  </intent-filter>
</activity>
```
Pressing Home shows the chooser, and the user picks this launcher as default. **Keep a normal launcher icon too**, so the stock launcher can be restored from Settings › Apps › Default apps if anything goes wrong.

---

## 3. Screens (map to `design/`)

| Screen | Prototype file | Notes |
|---|---|---|
| Home: **Dashboard** (default) | `Main.dc.html` | Car still, map card, weather card, full-width music bar with volume |
| Home: **Car showcase** | `HomeShowcase.dc.html` | 360° spinning car (see §6). Tap to pause/resume |
| Home: **Minimal** | `HomeMinimal.dc.html` | Huge 12-hour clock with small AM/PM, date and greeting on the theme wallpaper |
| Home: **Drive focus** | `HomeDrive.dc.html` | Big map card, compact weather, music bar. No car |
| Music / Now Playing | `Media.dc.html` | Turntable record, controls, seek, mood tabs, searchable track list, row "⋯" actions, top-right column: Back / Lyrics / Volume pop-out |
| App drawer | `AppDrawer.dc.html` | Section tabs, search, 2-row **horizontal** scrolling grid, custom scrollbar + ‹ › buttons at bottom-right |
| Settings (Android) | `Settings.dc.html` | Sections: Home screen picker, Network, Bluetooth, Display (incl. Theme), Sound, Storage & apps, About device |
| Car settings | `CarSettings.dc.html` | Sound & EQ, Reverse camera, Steering wheel, Lights, Navigation & startup, System + reset confirm dialog |

**Home layout choice:** Settings › Home screen offers the four layouts as preview cards. Save the choice; the Home button and launcher start always open the chosen layout. The prototype can't share state between screens, but the app must.

### Layout rules shared by every screen
- **Side menu (rail) on the RIGHT edge**, 88 dp wide: Maps, Music, Apps, Car, Settings, then Home pinned at the bottom (highlighted). Glass icons with labels under them.
- Header: greeting (left), phone-status chip, date, **12-hour clock** (right).
- In menus, the section list / sidebar sits on the right and content on the left; back buttons are top-right.
- Touch targets ≥ 48 dp; primary playback buttons are 64–88 dp.

---

## 4. Design tokens

See `tokens.json` for machine-readable values. In summary:

- **Background:** near-black `#0E0F12` with three radial glows: one theme-tinted (top-left) and two neutral greys (`#2A2C33`, `#1D1E24`).
- **Glass panel:** fill `rgba(255,255,255,0.06)`, 1 dp border `rgba(255,255,255,0.14)`, inner top highlight `rgba(255,255,255,0.16)`, shadow `0 16 48 rgba(4,4,8,0.45)`, radius 28 dp, background blur 28.
- **Tiles inside panels:** fill `rgba(255,255,255,0.07)`, border `rgba(255,255,255,0.10)`, radius 16 dp.
- **Text:** primary `#F2F3F5`, secondary `#C8CAD1`, muted `#B3B5BD`, faint `#9A9CA5`.
- **Theme presets** (accent / tinted glow): Violet `#7C5CFF` / `#3B3368` (default), Ocean `#2563EB` / `#1F3D73`, Emerald `#047857` / `#0F4A3F`, Rose `#BE185D` / `#5A1F45`. White text on every accent passes 4.5:1.
- **Fonts:** display "Barlow Semi Condensed" (500/600), body "Manrope" (400–600). Both are on Google Fonts under the Open Font License; bundle them in `res/font`.

### Blur and performance
Real background blur (`RenderEffect.createBlurEffect`) only exists on API 31+. On older Android, or if frame rate drops, fall back to the same translucent fill **without** blur; it still reads as glass on the dark background. Test on the unit and keep 60 fps scrolling as the priority.

---

## 5. Motion spec (keep it subtle and fast)

| Interaction | Behaviour |
|---|---|
| Press any button/tile | scale → 0.95 and brighten, spring back (~220 ms, ease-out) |
| Screen open | panels fade + rise 14 dp, staggered 80 ms; grids cascade 30 ms per item |
| Opening Music / Apps / Settings / Car settings | **Wheel loader**: a small car wheel rolls in and spins over a light frosted blur for ~0.6 s, then fades out. `assets/icons` has no wheel, so draw it from `Media.dc.html` (`.loader` markup) |
| Toggle switch | knob slides with overshoot (~380 ms); track glows in the accent colour |
| Tabs / section pills | indicator slides with slight overshoot (~450 ms) |
| Music record | spins while playing (7 s per turn); tonearm swings off when paused |
| Playing track row | three bouncing equaliser bars |
| EQ preset change | band knobs glide to new positions |
| Toasts | rise from the bottom, auto-hide after ~2.2 s |
| Reduce motion | if the system's animation scale is 0, disable all of the above |

---

## 6. Assets (`assets/`)

| Path | What | Use |
|---|---|---|
| `car/noah-still.webp` | Grey Toyota Noah render, transparent background, soft floor shadow | Dashboard car card |
| `car/spin-frames/frame_00…71.webp` | 72 turntable frames, 5° apart, 774×500 | **Car showcase 360° spin.** Play at 12 fps (6 s per turn) with an `AnimationDrawable`/frame loop. Tap pauses |
| `car/noah-spin-sheet.webp` | Same 72 frames as a 9×8 sprite sheet (560×362 each) | Alternative to the frames |
| `car/noah-grey-optimized.glb` | Grey-painted, simplified glTF model (~120k triangles) | Optional: a real interactive 3D view later (e.g. Filament / SceneView). Not required for v1 |
| `wallpapers/wall-{Violet,Ocean,Emerald,Rose}.webp` | 1280×720 fluted-glass wallpapers, one per theme | Minimal home background; switch with the theme |
| `icons/*.svg` | 20 layered "glass" icons (maps, music, phone, radio, camera, link, bt, video, gallery, files, browser, store, eq, weather, calc, clock, settings, car, apps, home) | Side rail + app drawer. Convert to VectorDrawables (Android Studio › Vector Asset). Real third-party apps use their own icons; these glass icons are for the launcher's built-in entries and favourites |

**Licence/credit (required):** the car renders come from the Sketchfab model **"Toyota Noah" by Nieve5677, licensed CC Attribution**. Keep the credit line in **Settings › About device** ("Car 3D model — 'Toyota Noah' by Nieve5677 · CC BY"). Fonts are OFL.

---

## 7. Features: what is real vs. placeholder

Everything in brackets in the prototypes (e.g. `[Track title]`, `[City]`, `[--] km`) is a placeholder and must come from real data or be hidden.

| Feature | How to make it real | Notes |
|---|---|---|
| **App list** | `PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`; declare `<queries>` (or `QUERY_ALL_PACKAGES` for a personal sideloaded build) | Sort A–Z; let the user assign apps to the sections (Drive/Media/Connect/Tools/System) and pick favourites |
| **Now playing** (music bar + Music screen) | `MediaSessionManager.getActiveSessions()` with a `NotificationListenerService` (user grants notification access once) | Gives title/artist/artwork/position and play/pause/next/prev for any music app. Hide the bar when nothing is playing |
| **Volume** | `AudioManager` `STREAM_MUSIC` | Head units sometimes route volume through the MCU; verify it changes the real output |
| **Track list / moods / lyrics** | Only possible for local files or a specific app's API | v1: list local music (MediaStore) or hide the list and show the active session only. Lyrics: hide unless a source is chosen |
| **Weather** | Online weather API using the head unit's location (GPS) over the phone hotspot/Bluetooth tethering | e.g. Open-Meteo (no key needed; **check its current terms before use**). Show "Connect to the internet for weather" when offline |
| **Phone status chip** | Bluetooth connection state (`BluetoothAdapter` / profile proxy) plus network connectivity | Needs Bluetooth permissions for the unit's API level |
| **Maps card / Navigate** | Launch the chosen maps app (Settings › Navigation & startup) with `ACTION_VIEW geo:` | A launcher **cannot read** turn-by-turn directions from another app. Showing live next-turn info needs a phone companion app that reads the Google Maps notification. That is a separate project; for v1 show "Open navigation" only |
| **12-hour clock, date, greeting** | System time, `h:mm a` format | Update every minute |
| **Home layout, theme, font size** | DataStore | Applies instantly |
| **Car settings** (EQ, camera format, steering keys, backlight, radio region, factory reset) | **Vendor-controlled.** No public Android API exists for these | Have each row deep-link to the matching screen in the vendor's settings app (find package/activity names via `adb shell dumpsys package` / `pm list packages`). **Never implement factory reset yourself**; link to the vendor screen behind its own confirmation |
| **Android settings rows** (Wi-Fi, Bluetooth, Display, Sound, Storage, Apps) | Open the system screens: `Settings.ACTION_WIFI_SETTINGS`, `ACTION_BLUETOOTH_SETTINGS`, `ACTION_DISPLAY_SETTINGS`, `ACTION_SOUND_SETTINGS`, `ACTION_INTERNAL_STORAGE_SETTINGS`, `ACTION_APPLICATION_SETTINGS` | A normal app can't change most of these directly. The in-launcher toggles in the prototype become shortcuts |
| **About device** | `Build.MODEL`, `Build.VERSION.RELEASE`, `Build.DISPLAY` | The MCU version is vendor-only; omit it or deep-link to the vendor screen |

---

## 8. Suggested build order

1. **Skeleton:** Compose project, HOME intent filter, theme tokens, fonts, right-hand rail, navigation between screens. Install on the unit and set as default. Confirm you can switch back to the stock launcher.
2. **Home: Dashboard** with clock, car still and theme; then the other three layouts and the Settings › Home screen picker (persisted).
3. **App drawer** with real installed apps, sections, search, horizontal 2-row scrolling, ‹ › buttons and scrollbar.
4. **Media:** notification-listener permission flow, music bar, Music screen controls, volume pop-out.
5. **Weather** and the phone-status chip.
6. **Settings / Car settings** as shortcut screens to system/vendor settings; theme picker; About (with model credit).
7. **Polish:** animations per §5, wheel loader, reduce-motion, blur fallback, performance pass on the unit.

## 9. Acceptance checklist
- [ ] Installs on the NAM5240T, can be set as the default Home, and the stock launcher can be restored.
- [ ] All four home layouts work; the choice survives a reboot.
- [ ] The rail is on the right; every screen's back/primary controls are reachable from the driver's side.
- [ ] Clock is 12-hour everywhere.
- [ ] Music bar reflects whichever app is playing; play/pause/next/prev and volume work.
- [ ] App drawer lists real apps and launches them.
- [ ] Theme switch changes accent, glows and the Minimal wallpaper.
- [ ] Scrolling and animations stay smooth on the unit (target 60 fps); blur falls back gracefully.
- [ ] No placeholder text (`[...]`) is visible in the shipped app.
- [ ] Model credit present in About.

## 10. Open questions for the owner
1. Exact Android/SDK version and screen size of the unit (check via ADB).
2. Which music apps are used (Spotify, YouTube Music, local files)? This decides what the Music screen's track list can show.
3. Which maps app should "Navigate" open?
4. Is the phone connected by Bluetooth tethering or hotspot (for weather/internet)?
5. Is a phone companion app for live directions wanted later?
