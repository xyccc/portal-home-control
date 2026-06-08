# Portal Home Control

A minimal native Android app to control **Philips Hue** lights, **Feit** bulbs, and
view a **Nest doorbell**, designed to be sideloaded onto a **Meta Portal** over ADB.

## Why it's built this way (read this first)

The original ask was "use Google Home APIs via OAuth, on a Portal, deployed via ADB."
That combination is not buildable as stated:

| Constraint | Reality |
|---|---|
| Google Home APIs (2024+) | Ship **inside Google Play Services** and need the Google Home app on-device. Portal runs Meta's AOSP fork with **no GMS** → the SDK can't run. |
| Google REST API for devices (SDM) | Covers **Nest devices only** (cameras/doorbells/thermostats). **No light support.** |
| Lights | Controlled directly: **Hue** via the bridge's local REST API; **Feit** via the **Tuya** cloud API (Feit is a Tuya OEM). Neither goes through Google. |

So this app talks to **three** backends directly over REST, no Google Home SDK:

- **Hue** → local CLIP v2 REST on the bridge (LAN, self-signed TLS).
- **Feit** → Tuya Cloud API (HMAC-SHA256 signed requests).
- **Nest doorbell** → Smart Device Management (SDM) REST + OAuth refresh token.
  Gives status + a live-stream URL. It does **not** receive button-press events
  (that needs a Pub/Sub subscription) and cannot "ring" the doorbell.

## Prerequisites (on your laptop, not a devserver)

- Android SDK (platform-tools for `adb`) + **JDK 17**
- Android Studio (easiest) **or** a system Gradle 8.2
- Portal connected over USB with **ADB debugging** enabled
  (Portal Settings → developer mode → ADB debugging → approve the on-device prompt)

> The Portal is plugged into your laptop, so build + deploy happen there. This
> repo was scaffolded on a remote box that has no Android SDK and no device.

## One-time credential setup

### Hue
1. Find the bridge IP (https://discovery.meethue.com/ or your router).
2. Press the bridge link button, then within 30s:
   ```
   tools/hue_pair.sh <bridge-ip>
   ```
3. Copy the `username` from the response → that's your **application key**.

### Feit (Tuya)
1. Create a project at https://iot.tuya.com (Cloud → Development).
2. Get **Access ID** and **Access Secret**.
3. Link your Feit/SmartLife account (Cloud project → Devices → Link App Account),
   and note the linked account **UID**.
4. Region host: US `openapi.tuyaus.com`, EU `openapi.tuyaeu.com`, etc.

### Nest doorbell
1. Enable Device Access + create a project (one-time $5):
   https://console.nest.google.com/device-access → note the **project ID**.
2. In Google Cloud: enable the **Smart Device Management API**, create an OAuth
   **Web application** client with redirect `http://localhost:8080/`.
3. Run the desktop OAuth helper to get a refresh token:
   ```
   python3 tools/get_nest_token.py --client-id XXX --client-secret YYY --project-id ZZZ
   ```
   Paste the printed values into the app.

## Configure the app

Two options:
- **In-app:** launch the app → **Settings** → fill in fields → Save.
- **Baked in:** copy `app/src/main/assets/config.example.json` →
  `app/src/main/assets/config.json`, fill it in (gitignored), rebuild. It seeds
  prefs on first launch.

## Build & deploy

```
./deploy.sh
```
Builds `:app:assembleDebug` and `adb install`s it. If the launcher hides the
sideloaded icon (Portal often does), relaunch with:
```
adb shell am start -n com.yvonna.portalhome/.MainActivity
```

## Known limitations / risks

- **Portal ADB**: not every Portal model/firmware allows sideload installs. If
  `adb install` is blocked, this app can't run on that unit — nothing in the code
  fixes that.
- **Hue TLS**: the app trusts the bridge's self-signed cert and skips hostname
  checks (`Http.insecureLocalClient`) because the cert never matches the LAN IP.
  Acceptable on your own LAN; do not reuse that client for internet calls.
- **Doorbell**: status + RTSP stream only. RTSP works for **wired** cameras;
  battery Nest doorbells are WebRTC-only and the "Live" button will error on them.
- Built and type-checked by hand on a box without the Android SDK — expect to fix
  minor issues on first real Gradle build.

## Layout

```
app/src/main/java/com/yvonna/portalhome/
  MainActivity.kt          UI: sections + light toggles + doorbell live button
  SettingsActivity.kt      credential entry
  Config.kt                SharedPreferences, seeded from assets/config.json
  net/Http.kt              OkHttp clients (default + insecure-LAN for Hue)
  net/HueClient.kt         Hue CLIP v2
  net/TuyaClient.kt        Tuya Cloud API + HMAC signing
  net/NestSdmClient.kt     SDM REST + OAuth refresh
  model/Device.kt          LightDevice / DoorbellDevice
tools/
  hue_pair.sh              get Hue app key
  get_nest_token.py        desktop OAuth → refresh token
deploy.sh                  build + adb install
```
