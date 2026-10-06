# Glass Launcher (NAM5240T)

An Android home-screen launcher for the Nakamichi NAM5240T car head unit, built from the
"NAM5240T Glass Launcher" design handoff (`design-source/`).

**Download:** [`dist/GlassLauncher.apk`](dist/GlassLauncher.apk) (about 3 MB).

![Dashboard](screenshots/01-home-dashboard.png)

| Car showcase | Minimal | Drive focus |
|---|---|---|
| ![](screenshots/02-home-showcase.png) | ![](screenshots/03-home-minimal.png) | ![](screenshots/04-home-drive.png) |

| Music | Apps | Settings | Car settings |
|---|---|---|---|
| ![](screenshots/14-music-playing.png) | ![](screenshots/06-apps.png) | ![](screenshots/07-settings-home.png) | ![](screenshots/09-car.png) |

The screenshots were rendered from the real app code with Robolectric (Android framework on
the JVM) at 1280×720. They have not been taken on the head unit itself.

## Install on the head unit

1. Copy `GlassLauncher.apk` to a USB stick, plug it into the head unit and open it with the
   file manager, or run `adb install GlassLauncher.apk` with USB debugging on.
2. Press **Home** and choose **Glass Launcher**, then **Always**.
3. **To go back to the stock launcher:** Settings › Storage & apps › *Default home app*
   (or Android Settings › Apps › Default apps › Home app). The launcher also keeps a normal
   app icon.

First-run permissions, each asked only when you use the feature:

- **Notification access:** shows the track, artist and artwork of whatever app is playing.
  Tap the music bar and turn on "Glass Launcher". Play/pause/next/previous work without it.
- **Location:** for local weather. You can instead set a city in Settings › Weather.

## What's in it

| Screen | Status |
|---|---|
| Right-hand rail (Maps, Music, Apps, Car, Settings, Home) | Real |
| Home: Dashboard, Car showcase (72-frame 360° spin, tap to pause), Minimal, Drive focus | Real; choice saved across reboots |
| Header: greeting, phone chip (connected Bluetooth device), date, 12-hour clock | Real |
| Music bar and Now Playing: title, artist, artwork, progress, seek, ±10 s, volume pop-out | Real, from the active media session of any app |
| Queue list | Shown when the music app shares its queue; otherwise a note |
| Music apps list | Real (apps that declare music playback) |
| Weather | Real, from Open-Meteo; needs internet (phone hotspot/tethering) |
| Navigation card | Opens your chosen maps app. It **cannot** show turn-by-turn directions; Android doesn't let a launcher read them |
| App drawer: sections, search, 2-row horizontal grid, scrollbar, ‹ › | Real apps; long-press an app to move it to another section, see App info or uninstall |
| Settings: home layout, theme (Violet/Ocean/Emerald/Rose), font size, car name, volume, weather, network, Bluetooth, storage, about | Real or shortcuts to the system screens |
| Car settings (EQ, camera, steering keys, lights, radio region, factory reset) | **Shortcuts only.** These are controlled by the head unit vendor and have no public Android API. You pick the vendor's settings app once and each row opens it. The launcher never resets anything itself |
| Motion: press feedback, staggered entrance, wheel loader, toggles, tab indicators, spinning record and tonearm, EQ bars, toasts | Real; off when Android's animation scale is 0 |

Changes from the prototypes:

- **No background blur.** Glass panels use the translucent fill only. The handoff allows this, and
  it keeps scrolling smooth on head-unit hardware.
- **Shuffle and repeat are replaced by −10 s / +10 s.** Android's framework media controls
  have no shuffle or repeat.
- **Lyrics is hidden.** There's no lyrics source.
- **The music bar stays visible when nothing is playing** ("Nothing playing"), so the Dashboard
  layout doesn't jump.

## Technical notes

- **Java on the plain Android framework. No AndroidX, Compose or Gradle.** The handoff
  suggested Kotlin + Jetpack Compose, but the environment this was built in could not reach
  Google's Maven repository. Everything is custom-drawn Views, and the APK has no
  dependencies.
- **SDK levels.** `minSdkVersion 24` (Android 7.0), `targetSdkVersion 29`. Targeting 29 keeps
  Bluetooth and package-visibility permissions simple for a sideloaded launcher. It installs
  and runs on newer Android versions too.
- **Screen sizes.** Every size is written in the prototype's 1280×720 units and scaled to the
  real screen, so it also fits the 9" model's 1360×800.
- **Signing.** The APK is signed with APK Signature Scheme v2. **The signing key was created in a
  temporary build environment and isn't saved.** If you rebuild, a new key is made, and Android
  will only install that build after you uninstall this one, which resets the launcher's settings.
  Keep `signing/release.p12` from your first own build to avoid this.

## Build it yourself (Linux x86-64)

Needs a JDK 17+, `curl`, `zip` and `unzip`. No Android Studio or SDK.

```sh
./build.sh          # writes dist/GlassLauncher.apk
```

`tools/fetch-tools.sh` downloads the toolchain from Maven Central:

- aapt2 and the framework resources, from apktool
- the Android 14 API classes, from Robolectric's `android-all`
- the `dx` dexer
- `apksig`

If you'd rather use Android Studio, the sources in `src/`, `res/` and `assets/` and
`AndroidManifest.xml` drop into a standard app module.

## Credits

- Car 3D model "Toyota Noah" by Nieve5677 (Sketchfab), licensed CC BY. Also credited in
  Settings › About device.
- Fonts: Barlow Semi Condensed and Manrope, SIL Open Font License.
- Weather data: Open-Meteo.com, CC BY 4.0. Check their terms before any commercial use.
