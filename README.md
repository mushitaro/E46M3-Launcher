# E46M3 ///M Launcher

*[日本語版はこちら / Japanese version](README.ja.md)*

A replacement home screen for the Android head unit fitted to a BMW E46 M3.

It replaces the OEM launcher with a console modelled on the car's own switchgear:
a display window, a key grid, and an "M" mode that turns the window into a
tachometer fed live from the engine ECU over a K+DCAN cable.

**Cold start is 790–1780 ms against the stock launcher's 5966 ms.**

---

## Read this before installing anything

**This is not a general-purpose Android launcher.** It was written for exactly
one head unit, and several things about it are hard-wired to that hardware
rather than adapted at runtime. It will install on other devices, and on most of
them it will look wrong and do less.

Two specific risks, stated up front:

> ### ⚠️ Do not uninstall this app while it is set as HOME
>
> Android falls back to `com.android.settings/.FallbackHome`, which is a blank
> screen with no way out from the unit itself. Recovering needs the vehicle
> powered, your PC on the same WiFi, and ADB. **Rehearse the restore command
> below before you install, not after.**
>
> The app has a guided removal flow that hands HOME back first, and it refuses
> to uninstall until the platform confirms another launcher has taken over.
> Use it rather than the Settings app.

> ### ⚠️ The prebuilt APK updates itself from *this* repository
>
> The release URL is compiled into the binary:
> `https://github.com/mushitaro/E46M3-Launcher/releases/latest/download/ota-manifest.json`
>
> If you install the published APK, your head unit will check this repository for
> updates and offer to install what it finds, signed by this project's key. That
> is the intended behaviour for a public project, but it is a trust relationship
> you should agree to knowingly. To control your own updates, [build it
> yourself](#building-it-yourself).

---

## Which head units this runs on

**Check the unit, not the brand on the box.**

These are white-label units: one ODM builds the hardware and many sellers
rebrand it. The unit this was developed on was bought in Japan under the
**ENEON** brand — but nothing in the firmware says so, and searching the whole
device dump (system properties, `/sdcard`, all 23 vendor APKs) finds no trace of
that name. The retail brand is a sticker. What the device reports about itself
is the ODM identity, and that is the thing worth matching.

So identify yours this way:

```bash
adb shell getprop ro.product.model         # FF-5000
adb shell getprop ro.product.brand         # FFKJ
adb shell getprop ro.product.manufacturer  # alps     (MediaTek's reference name)
adb shell getprop ro.build.version.sdk     # 27       <- the real Android version
adb shell getprop ro.build.display.id      # FF_8227L_10
adb shell wm size                          # Physical size: 1024x600
```

> **`ro.build.version.sdk` is the one to trust.** On this unit the Settings app
> reports "Android 10" and `ro.build.version.release` says `10`, both of which
> are cosmetic — the vendor edited the strings. It is Android **8.1, API 27**.
> Everything in this project is reasoned at 27.

### Verified reference unit

| | |
|---|---|
| Model / device / name | **`FF-5000`** |
| Brand | **`FFKJ`** (the ODM — sold under various retail brands, e.g. ENEON in Japan) |
| Manufacturer | `alps` (MediaTek reference) |
| Build ID | `FF_8227L_10` |
| Fingerprint | `alps/full_8227L_demo/8227L_demo:8.1.0/O11019/1571038753:userdebug/test-keys` |
| HMI version | `XRCH.D.Q.F.3.04_1.2019.11.29.16.00` — from the vendor UI crash log, **not** readable with `getprop` |
| SoC | MediaTek **MT8227L** / AutoChips AC8227L, 4× Cortex-A7, **32-bit only** (`armeabi-v7a`) |
| Android | **8.1 Oreo, API 27** |
| Screen | **1024 × 600**, 240 dpi |
| RAM | 2 GB (~1.1 GB free) |
| Build type | `userdebug` / `test-keys`; network ADB on by default at port 5555 |
| Vendor UI | `com.ts.MainUI` (the "TS" family of MTK head-unit firmware) |

### How close is yours?

| Your unit | What to expect |
|---|---|
| `FF-5000` / `FFKJ`, API 27, 1024×600 | **The reference.** Everything described here applies |
| Another MTK **8227L** unit with `com.ts.MainUI`, API 27, **1024×600** | Very likely fine. The console, the app list, the updates and the vehicle keys all key off things this family shares |
| Same family but a **different resolution** (800×480, 1280×720…) | **The console will be laid out wrong.** Positions are in **px** on purpose — see `res/values/design.xml` for why — and there is no density scaling. It runs; it does not look right |
| API 23–26 | Installs and runs, updates work. `targetSdk 27` means the framework will apply compatibility shims that were never tested |
| **Below API 23** | The update subsystem does not run at all — it needs `NetworkCapabilities` to tell a captive portal from a working link. `minSdk` is 21, so the rest still installs |
| Not an 8227L / no `com.ts.MainUI` | A working home screen, clock, app list and updates. RADIO / BT / VIDEO / EQ / CARPLAY / DROID and the outside temperature will be dead |

### What depends on this exact hardware

| Feature | Requires | Without it |
|---|---|---|
| Console layout | **1024 × 600** panel | Keys land in the wrong places. Positions are in **px**, deliberately (see `res/values/design.xml`) — there is no density scaling |
| M key, tachometer, engine temps | An **FTDI (`0x0403`) or CH340 (`0x1A86`) K+DCAN cable** on the USB host bus | **The M key is not fitted at all.** Its socket stays blanked, and TUNER is unreachable — see [troubleshooting](#the-m-key-never-appears) |
| RADIO, BT, VIDEO, EQ, outside temperature | `com.ts.MainUI` (the vendor's CAN/radio backend) | Those keys dim and do nothing |
| CARPLAY / DROID | `com.ts.carplayapp`, `net.easyconn` | Same |
| TUNER (web tool) | **Google Chrome** installed | The key fails; the launcher tells you so |
| TUNER full-screen | Chrome **and** a signature match (see below) | Opens with a browser toolbar, silently |
| App list | Standard `CATEGORY_LAUNCHER`, plus the vendor-private `MYLAUNCHER` category | Works anywhere; vendor apps simply will not be listed |

Everything degrades without crashing. A unit with none of the above still gets a
working home screen, an app list, a clock and the update system.

---

## Installing

You need `adb` on your PC and the head unit powered and on the same WiFi subnet
as your PC. **The ADB link only exists while the vehicle is powered** — turn the
key off and the connection drops immediately.

### 1. Write down the way back, first

```bash
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
```

That is the stock launcher on an FF-5000. On another unit, find yours with:

```bash
adb shell "dumpsys package > /sdcard/p.txt"
adb shell "grep -B 2 -A 6 'android.intent.category.HOME' /sdcard/p.txt"
```

Keep it in a second terminal window while you do everything below.

> Piping `dumpsys` straight into `grep` **on the device** gives `Broken pipe`, so
> it goes via a file. This is not optional; it is how the tool behaves there.

### 2. Download the APK

From [Releases](https://github.com/mushitaro/E46M3-Launcher/releases/latest) —
the asset named `app-launcher-<version>-<code>.apk`.

Verify it before installing:

```bash
apksigner verify --min-sdk-version 21 --print-certs app-launcher-0.2.0-2.apk
```

The certificate SHA-256 must be:

```
8e529141ef09abdb50d95930a25263153834ac3b385c9f7603b15fe8bcfdb580
```

It must also report **v1 scheme: true** and **v2 scheme: true** — API 27 does not
understand v3 signatures.

### 3. Connect and install

```bash
adb connect <head-unit-ip>:5555
adb install -r app-launcher-0.2.0-2.apk
```

Installing does **not** make it the home screen. The existing preference is
pinned with `mAlways=true`, so it has to be replaced explicitly.

### 4. Make it HOME

```bash
adb shell cmd package set-home-activity app.tsunagi.e46m3.launcher/.HomeActivity
```

Verify it took — and **not** with `cmd package resolve-activity`, which answers
from a stale cache:

```bash
adb shell "dumpsys package app.tsunagi.e46m3.launcher > /sdcard/p.txt"
adb shell "grep -A 4 'Preferred Activities' /sdcard/p.txt"
```

You should see this app's `HomeActivity` in the output.

### 5. Allow it to install updates

```bash
adb shell appops set app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES allow
adb shell appops get app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES
```

**Do not skip this.** `REQUEST_INSTALL_PACKAGES` is declared in the manifest but
is backed by an app-op that starts un-granted on API 26+. Without it the
launcher will find updates, download them and verify them — and then be unable
to apply them. It says so on screen, but the fix is this command.

The equivalent on the unit itself is Settings → Apps → Special access → Install
unknown apps, if your vendor's Settings carries that screen.

### 6. Restart the home screen

```bash
adb shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
```

---

## Updates

After the first install, the head unit maintains itself.

| Step | Who | Automatic? |
|---|---|---|
| Check for a new version | The head unit | **Yes** — a few seconds after the home screen appears |
| Download and verify | The head unit | **Yes** on an unmetered link. On a metered one it waits for you to press DOWNLOAD |
| Apply | You | **One tap**, then the system's confirmation dialog |

**Tap the display window** (the LCD panel at the top) to open the update pane. When
an update is waiting, the bottom-right line of the LCD reads `UPDATE <version>`.

### What is checked before anything is installed

1. The update manifest carries an **RSA-2048/SHA-256 signature** made with a key
   that is not in this repository. The public half is compiled into the APK. The
   bytes are verified *before* they are parsed.
2. The APK's **SHA-256** must match the signed manifest.
3. The APK's **signing certificate** must match the manifest *and* must be
   identical to the certificate the running app was installed with.
4. The manifest's `serial` may never go backwards, so an old signed manifest
   cannot be replayed at your unit.

This is deliberately independent of HTTPS. These head units frequently boot with
the real-time clock reading **2006**, which makes every certificate "not yet
valid" and fails the TLS handshake before any payload arrives. When that happens
the launcher says `CLOCK WRONG`, does not connect, and retries by itself once the
clock is corrected.

### When an update will be refused

A self-update restarts the home screen, so it is declined while any of these is
true — each with its own message on screen:

- the M console (tachometer) is open
- the app list is open
- a resume countdown is running
- **a K+DCAN cable is plugged in** — someone is working on the car
- the ECU link is live
- **the engine is running**

Close the console, unplug, or switch off, and press INSTALL again.

---

## Removing it

**Use the built-in flow.** Open the update pane (tap the display window), then
**long-press the version line at the bottom**. It walks four steps, each gating
the next, with the recovery commands on screen the whole time:

1. Release this app's HOME assignment
2. Choose the original launcher, with **Always**
3. Verify the platform actually moved HOME
4. Uninstall

Step 4 is not offered until step 3 confirms another launcher holds HOME.

If anything goes wrong, or you would rather do it from a PC:

```bash
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
adb uninstall app.tsunagi.e46m3.launcher
```

Both lines, in that order. The second without the first is the failure this
whole procedure exists to prevent.

---

## Building it yourself

Do this if you want your own updates, your own signing key, or changes.

**Requirements:** JDK 17, Android SDK with build-tools 35.0.0. Gradle 8.9 and
AGP 8.7.3 come with the wrapper.

```bash
git clone https://github.com/mushitaro/E46M3-Launcher
cd E46M3-Launcher/app-launcher
```

### 1. Your own signing key

Create a keystore, then `app-launcher/keystore.properties` (gitignored):

```properties
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The same certificate is applied to **every** build type on purpose. A debug build
signed with the usual auto-generated debug key would fail Digital Asset Links
verification and silently degrade TUNER to a Custom Tab with a toolbar, with no
error anywhere.

### 2. Your own OTA signing key

```bash
tools/ota-keygen.sh
```

Writes the private key to `~/.e46m3/ota-rsa2048-private.pem` (never in the repo)
and two public copies that **must** be committed:
`tools/ota_public_key.pem` and `app/src/main/res/raw/ota_public_key.der`.

> Losing the private key means every unit in the field stops accepting updates
> until it is re-flashed over ADB. Back it up somewhere other than the machine
> that made it.

### 3. Point updates at your own repository

Either at build time:

```bash
./gradlew assembleRelease -PotaManifestUrl=https://github.com/<you>/<repo>/releases/latest/download/ota-manifest.json
```

…or change the default in `app/build.gradle.kts`.

### 4. TUNER full-screen (optional)

TUNER is a Trusted Web Activity, not an APK. Full-screen requires a two-sided
Digital Asset Links statement: this app names the origin in
`res/values/strings.xml`, and the site must serve
`/.well-known/assetlinks.json` naming your package and **your** certificate's
SHA-256. Get either side wrong and Chrome reports nothing — it just shows the
toolbar.

### 5. Publish a release

```bash
tools/release.sh --bump patch
```

Builds, verifies, publishes as a **prerelease**, re-downloads everything from the
public URL, checks the bytes that came back, and only then promotes it to
`latest`. `--dry-run` does everything except publish.

---

## Troubleshooting

### The home screen is gone / the unit shows a blank screen

HOME was lost. Power the vehicle, join the same WiFi, and run:

```bash
adb connect <ip>:5555
adb shell cmd package set-home-activity app.tsunagi.e46m3.launcher/.HomeActivity
```

The launcher also posts this command as a notification and logs it with the tag
`OTA_LOST_HOME`:

```bash
adb logcat -d | grep OTA_LOST_HOME
```

### The M key never appears

Expected without a diagnostic cable. The M key is only fitted while an **FTDI
(`0x0403`) or CH340 (`0x1A86`)** device is on the USB host bus — that is how the
launcher knows the K+DCAN cable is in. The whole vendor ID is matched, not a
specific product ID.

TUNER lives on the M console, so it is unreachable without the cable. The update
pane is not: it opens from the home screen by tapping the display window.

### TUNER opens with a browser toolbar

Digital Asset Links verification failed. Causes, all silent: Chrome not
installed, no network on first launch, the site not serving
`/.well-known/assetlinks.json`, or the APK signed with a different key than the
one that file names.

### Updates are found but INSTALL does nothing

The `REQUEST_INSTALL_PACKAGES` app-op is not granted. See [step 5](#5-allow-it-to-install-updates).

### The update pane says CLOCK WRONG

The head unit's clock is implausible, so no connection is attempted — a
certificate that is not yet valid would fail the handshake anyway. It resolves
itself when the clock syncs; nothing to do.

### Keys are dim and do nothing

The app they point at is not installed on your unit. Dimming means "resolved as
absent", which is normal on hardware other than an FF-5000.

---

## Documentation

All under [`docs/`](docs/), in Japanese, with claims marked
**[V]** verified on the vehicle / **[I]** established by static investigation /
**[U]** unverified.

| | |
|---|---|
| [`01-device-investigation.md`](docs/01-device-investigation.md) | The head unit: what it actually is, why root is unavailable, why the Android version is faked, why WebView cannot be used |
| [`03-mainui-recon.md`](docs/03-mainui-recon.md) | Reverse engineering of `com.ts.MainUI` — the vehicle binder API |
| [`04-launcher-design.md`](docs/04-launcher-design.md) | The launcher itself: architecture, the console, the tachometer, real bugs found on the car and their causes |
| [`05-tuner-resume-spec.md`](docs/05-tuner-resume-spec.md) | Reopening a web tool after the vehicle loses power |
| [`06-tuner-webgl-fallback-spec.md`](docs/06-tuner-webgl-fallback-spec.md) | Working around the absence of WebGL on this unit |
| [`07-ota-design.md`](docs/07-ota-design.md) | The update system: threat model, publish gates, what is still unverified |

---

## Third-party components

| | |
|---|---|
| `androidx.constraintlayout` 2.1.4, `androidx.recyclerview` 1.3.2, `androidx.browser` 1.8.0 | Apache-2.0 |
| [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) 3.9.0 | MIT |

Deliberately absent: Compose, AppCompat, Material Components. This screen cold
starts on every boot, every `KILL_APPS` and every low-memory kill, on in-order
Cortex-A7 cores where `speed-profile` dexopt (API 28+) is unavailable — so
roughly ten thousand extra methods would be JIT-compiled every single time.

No licence has been declared for this repository yet. If you want to reuse the
code, please open an issue and ask.
