# Neverhide Empire

A private, all-in-one native Android app (Kotlin, min SDK 26 / target SDK 34)
bundling four features:

1. **Screenshot Tool** - MediaProjection capture (no root), Quick Settings tile,
   and a draggable floating bubble. Saves PNGs to `Pictures/NeverhideEmpire`.
2. **3D Rotating App Launcher** - a real HOME launcher rendering installed apps
   with OpenGL ES 2.0 in three modes: **Box** (cube faces), **Circle** (spinning
   ring), **Sphere** (Fibonacci distribution). Drag to rotate, tap to launch.
3. **3D Live Wallpaper Engine** - four GL effects: Fire particles, Water waves,
   Galaxy stars, Hologram grid. Rendering pauses when hidden (battery-friendly).
4. **Adrenaline Bundle Updater** - checks a JSON manifest on your server,
   downloads the new APK, and prompts to install (REQUEST_INSTALL_PACKAGES).

## Build

### Option A - Android Studio (recommended)
1. Open Android Studio (Hedgehog or newer).
2. `File > Open` this folder.
3. Let it sync, then `Run` on a device (min Android 8.0).

### Option B - command line
Requires JDK 17 and the Android SDK (set `ANDROID_HOME` or add a
`local.properties` with `sdk.dir=/path/to/Android/sdk`).

```bash
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

Install:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup after install
- **Launcher**: press Home and pick "Neverhide Empire" (set as default to use it).
- **Wallpaper**: Settings > Wallpaper > Live > Neverhide Empire. Change effect by
  setting the int `wallpaper_effect` (0 Fire, 1 Water, 2 Galaxy, 3 Hologram) in
  SharedPreferences `empire_prefs`.
- **Screenshot bubble**: grant "Display over other apps", then tap the bubble.
- **Updater**: edit `MANIFEST_URL` in `AdrenalineUpdater.kt` to your server, and
  enable "Install unknown apps" for the app.

## Permission note
The app requests READ_CONTACTS, READ_CALL_LOG and ACCESS_FINE_LOCATION per the
original spec even though no feature uses them. They are safe to remove from
`AndroidManifest.xml` and `MainActivity.kt` and are not required for any feature.
This build is intended for private/sideload use, not the Play Store.
