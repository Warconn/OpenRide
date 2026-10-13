# Entertainment Ride prototype (Warconn fork)

**Status:** experimental, Android 10 Bike+ hardware validation and APK build pending.

This feature runs **alongside** the stock Peloton application. It does not remove or
disable stock apps, install a HOME launcher, modify the bike's firmware, or change
OpenRide's sensor service. The debugReal variant in this fork uses the separate package
name `dev.digitalducktape.openride.entertainment` so it can coexist with stock OpenRide.

## What it does

1. Create/select an OpenRide rider profile and start a **Quick Start** ride.
2. Tap **Entertainment** in the upper-right corner of the active ride.
3. **Watch Plex** launches the independently installed Plex Android app and a small
   floating metrics HUD. **Read Kindle** launches Kindle with **no on-screen overlay**.
   **YouTube TV (test)** attempts its normal launch, but actual availability and DRM
   playback on the bike are unverified.
4. The foreground service keeps the process-wide ride session active. It **does not save**
   the ride itself. Return to OpenRide and tap **End Ride** to save.
5. In the overlay: drag the **RIDE / LIVE** handle to reposition; tap **−** to collapse
   to a small pill; tap **⚙** to choose individual displayed metrics; tap **×** to
   completely hide the overlay. Hiding does **not** stop the foreground service or recording.
6. Tap **Show panel** inside OpenRide's Entertainment dialog to restore a hidden overlay.
   You can also return to OpenRide via the ongoing ride notification.

## Important limitations

- Plex/Kindle/YouTube TV must be installed **separately** (for example using OpenPelo).
  No streaming providers or content are bundled or modified.
- Android's **Display over other apps** permission is required when displaying the HUD.
  Grant it through the system permission screen shown on first Watch Plex attempt,
  then return to the OpenRide ride screen and tap again.
- The Android foreground service keeps the *existing in-memory* ride session alive
  through a normal app switch. **Force-stop, a process crash, reboot, or low-memory kill
  can lose the unsaved ride**. This is not yet a durable background recording solution.
- Third-party apps may block screen overlays, present a DRM playback error, require
  services absent from the Peloton tablet, or be incompatible with landscape layout.
  YouTube TV is especially unverified.
- Automatic Peloton firmware updates may break unsupported sensor integrations.
- GymKit and Apple Watch recording are **not** part of this first prototype.
- This prototype does not manipulate physical resistance or safety functions.
- The notification reopens OpenRide; it may not always restore the exact ride route
  after an Android process restart.

## Mac development / verification

Prerequisites: JDK 21, Android SDK for API 34 (and build-tools), and
Godot **4.7.2** for full APK packaging (matching upstream's exact version).

```sh
git clone https://github.com/Warconn/OpenRide.git
cd OpenRide
git checkout feature/entertainment-ride-overlay
./gradlew :app:compileDebugRealKotlin
```

The compile task checks Kotlin without exporting bundled Godot mini-games. A full
test APK requires the Godot editor path:

```sh
export GODOT_BIN=/Applications/Godot.app/Contents/MacOS/Godot
./gradlew :app:assembleDebugReal
adb install -r app/build/outputs/apk/debugReal/app-debugReal.apk
```

Start the fork build via the app icon or:

```sh
adb shell am start -n dev.digitalducktape.openride.entertainment/dev.digitalducktape.openride.MainActivity
```

Confirm live power/cadence on the bike **before** leaving the ride for an entertainment app.
Then test Plex, hide/collapse, return and end ride, and verify the ride history contains
all elapsed seconds. Repeat with Kindle and no overlay.

To uninstall *only this prototype* (note: this deletes its local ride history):

```sh
adb uninstall dev.digitalducktape.openride.entertainment
```

Do not follow the upstream optional HOME launcher or disable-stock-Peloton steps
during the coexistence trial.

## Next engineering steps

- Persist and recover active rides across process death, plus test lifecycle resilience.
- Tests for metrics preferences, overlay mode transitions, and service shutdown.
- Confirm Plex, Kindle, and YouTube TV package/DRM behavior on the actual Bike+.
- Improve touch layout and replace programmatic Android Views with the final Compose-based design.
- Optional BLE cycling sensor advertisement for Apple Watch, separately scoped.
