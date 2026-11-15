# Droid Desk Companion

A small Android helper app (about 570 KB; one library, [Shizuku-API](https://github.com/RikkaApps/Shizuku-API), MIT) that lets Droid Desk on the PC reach the data Android only shares with apps on the phone: **SMS and MMS, contacts, call log and calendar**.

It can also **receive files over Wi‑Fi** (see [Wi‑Fi receive](#wi-fi-receive)) and **send them** (see [Wi‑Fi send](#wi-fi-send)), so files move between the phone and Droid Desk without a cable.

It also has phone-care tools people use on the phone itself (see [Phone-care screens](#phone-care-screens)): **Memory** (RAM use and Boost), **Network** (connection details, speed test, ping, DNS lookup), **Cleaner** (app caches, junk files, duplicates, large files, on internal storage and SD cards, plus a storage breakdown), **Battery** (level, condition, details, history charts, and a [Battery saver](#battery-saver)), **Freezer** ([stop apps you aren't using](#freezer)), **Apps** (installed apps by size, uninstall), **Storage analyzer** (folder sizes), **Permission audit** (which apps hold sensitive permissions) and **Hardware test** (guided checks of screen, sound, sensors and more).

Users don't install it themselves. Droid Desk pushes it over USB the first time someone opens Messages, Contacts, Call log, Photos or Music. It also gives Droid Desk's Photos and Music tabs the phone's media index and thumbnails (`media.*` below; needs the photos/videos/music permissions, which Droid Desk grants at install).

**Using the app?** The [user guide](docs/USER_GUIDE.md) shows every screen with screenshots and explains each option.

## Download

- **Droid Desk Companion (APK):** [Releases](https://github.com/Deshan555/Andromon-android/releases). Each release has `droid-desk-companion-<version>.apk` and its SHA-256 under **Assets**.
- **Droid Desk for macOS, Windows and Linux:** [Releases of the desktop app](https://github.com/Deshan555/Andromon_PC/releases). It installs the companion on the phone by itself, so most people only need this.
- **Builds that are not released yet:** open the newest green run of the `release` branch under [Actions](https://github.com/Deshan555/Andromon-android/actions/workflows/android.yml) and download **droid-desk-companion-release** from its **Artifacts** (needs a GitHub sign-in; kept for 90 days). Until the signing secrets are set, that APK is signed with a throw-away key and is named `…-test-key.apk`.

Releases are made by the build as **drafts**: a release shows on the Releases page once it has been published (see [Builds and releases](#builds-and-releases)).

## How it works

```
PC (Droid Desk)                 USB / ADB                    Phone
───────────────                 ─────────                    ─────
adb.createSocket("localabstract:droiddesk_companion") ──►  CompanionService (foreground)
   {"id":1,"method":"sms.list"}\n  ─────────────────────►   checks peer uid == shell (adbd)
   ◄────────────────────────  {"id":1,"result":[...]}\n     queries content providers, closes
```

- **Install:** Droid Desk pushes the APK, then runs `pm install -r -g`. `-g` grants the runtime permissions. Installing via adb also allow-lists SMS and call log, which are "restricted" permissions that Android refuses for APKs installed by tapping them. Droid Desk then runs `appops set` for `MANAGE_EXTERNAL_STORAGE` and `GET_USAGE_STATS`, which the Cleaner needs and `-g` doesn't grant, and for `WRITE_SMS`, which restoring text messages needs (see `sms.restore`).
- **Start:** `am start-foreground-service`. If that's blocked, Droid Desk opens the app's screen with `--ez autostart true`, which starts the service and moves the app to the background.
- **Transport:** an abstract Unix socket, reached only through the ADB USB connection. The PC link never uses TCP or Wi‑Fi. The app uses the network only for the Network screen's tests and for Wi‑Fi receive while the person has it turned on.
- **Auth:** each connection's peer UID must be `2000` (shell/adbd) or `0` (root), so other apps on the phone can't talk to it. The service is exported only behind `android.permission.DUMP`, which only the shell has.
- **Lifetime:** a foreground service of type `connectedDevice`, which shows a "Connected to Droid Desk" notification. It stops on the `stop` call, from the notification's Stop button, or after 15 idle minutes.

## Phone-care screens

A bottom bar docked to the screen edge switches between Home, PC link, Cleaner, Network and Receive; Memory, Battery, Freezer, Apps, Storage analyzer, Permission audit, Hardware test and Settings open from Home; the Battery saver opens from Battery. All of them are plain framework views (`Ui.kt`: a light grey page with off-white cards, one lime-to-green gradient hero card per screen, big headings and figures, dark pill buttons, and a dark bottom bar docked to the screen edge whose open item sits on a white rounded square; brand colours `#E3F786`, `#9CD237`, `#292A2C` and white; light and dark themes), one `Screen` subclass each, hosted by `MainActivity`. Text uses Poppins (`res/font`, subset to Latin with `pyftsubset` to keep the APK small; licence in `res/raw/poppins_ofl.txt`), set app-wide through the theme's `android:fontFamily`.

| Screen | What it does | Limits |
|---|---|---|
| **Home** | A 0–100 health score from free storage, free memory and battery condition (`Phone.health`). Each problem costs points and is listed under the ring with a link to the screen that helps. Storage at a glance and tiles for PC link and every tool. | The score only uses what Android reports; it isn't a security or malware check. |
| **PC link** | The companion itself: the Droid Desk link switch with the message/contact/call counts the PC can read, how to connect, the data permissions and where the data goes. | — |
| **Memory** | RAM used/available, swap (zRAM), a live one-minute chart. **RAM manager**: the apps in memory with icon, memory use and state (In use, Visible, Running, Recent, Cached), **Stop** per app (force stop; asks first for system apps and apps on screen) and **Free memory** (`am kill-all`: every background app). Works through [privileged access](#privileged-access-shizuku-helper-root): Shizuku, or `ShellHelper`, a small helper Droid Desk starts with ADB rights (Shizuku › Phone RAM manager), or `su` on rooted phones; the helper keeps running until the phone restarts. Without it, **Boost** (`killBackgroundProcesses`) on Android 13 and older. | Without the helper, other apps' processes can't be seen, and Android 14+ disables Boost. The helper only lists processes, force-stops and kills apps, and reads Android's battery statistics and app-ops for the Battery and Permission audit screens (read-only): fixed commands, no shell. SELinux blocks app↔shell Unix sockets, so it listens on loopback TCP and every request needs a random token, delivered to `HelperReceiver` (guarded by `android.permission.DUMP`). Never stops the home screen, keyboard, System UI, phone or the companion. |
| **Network** | Connection type, Wi‑Fi band, link speed and signal, IP addresses, gateway, DNS, Private DNS, metered. Speed test (ping, download, upload) against `speed.cloudflare.com`, at most 8 s per phase. Ping (system `ping`, falling back to timing TCP connections) and DNS lookup for any host. | The speed test uses up to ~125 MB on Wi‑Fi and ~35 MB on mobile data. DNS lookups time out after 8 s and pings after 20 s. |
| **Cleaner** | Storage use for internal storage and each SD card, with a breakdown. A scan runs **SD Maid SE's cleaning rules** (ported, see below): the 13 System Cleaner filters that work without root (log files, ad caches, tracking/analytics files, LOST.DIR, Linux trash, macOS and Windows files, temp files, empty folders, thumbnail caches, superfluous APKs, old screenshots, Android trash), and CorpseFinder, which finds leftover folders of uninstalled apps with SD Maid SE's marker database (shared storage) or by package name (Android/media). Duplicates (same size, then first/last 64 KB, then SHA‑1; the oldest copy is kept) and large files come on top. Groups SD Maid SE leaves off by default, leftovers, duplicates and large files start unticked. Every clean asks for confirmation first. | "App caches on shared storage" needs Android 11+ and goes through Android's own confirmation (`ACTION_CLEAR_APP_CACHE`), which only empties apps' caches on shared storage (`Android/data/<app>/cache`); the size shown is just that part (`StorageStats.getExternalCacheBytes`, Android 12+), and the group starts unticked. Apps' private caches, usually most of the total, can't be cleared by an app: Droid Desk's **Apps › Clear app caches** does that over USB with `pm trim-caches`. Every rule can be switched off under Settings › Cleaning rules. Android/data and Android/obb can only be read up to Android 10. Leftover search needs `QUERY_ALL_PACKAGES` (an app missing from the list would make its folders look abandoned) and is skipped without it. SD Maid SE's root-only filters and its per-app cache cleaner are not included. Empty folders are only removed while still empty; matched folders are deleted with their contents, never following symlinks. |
| **Battery** | Level, charging state and time to full (Android 9+), condition, temperature, voltage, technology, charge cycles (Android 14+). **Battery Saver** is a real switch (see [Battery saver](#battery-saver)); the Dark theme row shows the current setting and opens it; rows open the Battery saver and the Freezer. **History** (`BatteryLog.kt`): level, charging state, temperature and voltage are sampled about every 15 minutes by a `JobScheduler` job (`BatteryJob`), also while the app is closed, and every minute while the Battery screen is open; 7 days are kept in an app-private file (10 bytes per sample). Charts of level (charging stretches shaded, stretches without samples dashed) and temperature over 24 hours or 7 days, average drain per hour on battery, when the phone was last seen at 100%, and a time-left estimate from the drain of the last 3 hours (or 24 hours). **App battery use**: Android's own per-app estimate since the last full charge (`dumpsys batterystats`), only with privileged access (Shizuku or `ShellHelper`). | Apps can't turn Dark theme on or off, so that row opens Settings; Battery Saver can only be switched with `WRITE_SECURE_SETTINGS` or privileged access (otherwise the Saver screen opens and says so). Android gives apps no time-left figure on battery: the one shown is this app's own estimate from recent drain and is labelled as such. Android pauses the sampling job while the phone dozes, so there are gaps, and history only starts once the app has been opened. Figures that need more samples say "Not enough data yet". Without the helper there are no per-app figures, and the screen says so. |
| **Freezer** | Opened from Home and from Battery. Stops apps that run without being used, by hand, with a shortcut or tile, when the screen turns off or the battery runs low. See [Freezer](#freezer). | Needs privileged access to stop apps directly; otherwise an accessibility method or App info per app. Usage access for “When unused”. |
| **Saver** | Opened from Battery. Battery Saver switch, Deep sleep, charging helper, apps that are awake, per-app limits. See [Battery saver](#battery-saver). | Most parts need privileged access or `WRITE_SECURE_SETTINGS`; each says which. |
| **Settings** | Opened from the gear on Home. Theme (same as phone / light / dark), **Accessibility** (text size: same as phone / large / larger / largest, on top of the phone's font size; high contrast, which draws grey text and thin lines in the next stronger colour; bold text, one weight heavier everywhere; reduce motion, also followed when the phone has "Remove animations" on; and a shortcut to the phone's accessibility settings), whether opening the app starts the PC link, the Junk Cleaner's large-file size (50 MB–1 GB), always using the shorter speed test, the state of each access with a link to the Android setting, **Privileged access** (Shizuku, see below), privacy notes and the licences. | Stored in the app's private preferences on the phone. Droid Desk can always start the PC link over USB, even when "Start when the app opens" is off. |
| **Apps** | Every installed app (user, system, disabled), sorted by size, name, package, install date, last update, screen time or least recently used, with search. Details per app: version, status, install/update dates, installer, target and minimum Android, app/data/cache size, screen time and last use (30 days). Actions: open, App info, Play Store, **export the APK** to `Download/Droid Desk/APKs` (split apps as a `.apks` bundle of base + splits), and uninstall (one or many, or "uninstall updates" for updated system apps), each through Android's own confirmation. | Sizes and usage need usage access. Force stop and disabling apps need root or a USB shell, so they aren't offered. |
| **Storage analyzer** | Opened from Home or the Cleaner. A folder-size browser for internal storage and each SD card: sizes are added up in the background, shown largest first with bars for each item's share of the folder, with a breadcrumb; Back goes up one folder. Tap a file to open it in another app (handed over through Android's media index); the three dots delete a file or folder after a confirmation that names the path and size. | Needs All files access. Android/data and Android/obb are closed to apps since Android 11, and installed apps, their private data and Android itself aren't visible to a file browser, so the folders add up to less than "used". Symlinks are not followed. Deleted items don't go to a recycle bin; the top-level `Android` folder can't be deleted as a whole. At most 150 items are listed per folder. |
| **Permission audit** | Opened from Home. Per sensitive permission group (Camera, Microphone, Location, Contacts, SMS, Call log, Phone, Files and media, Notification access, Accessibility services, Device admin, Display over other apps, Install unknown apps, All files access on Android 11+): the apps that are allowed and the apps that only asked, user or system apps, each opening Android's App info page. Read from the package manager's requested-permission flags, `enabled_notification_listeners`, `AccessibilityManager`, `DevicePolicyManager` and, for the three app-op switches, `AppOpsManager` read the way Android's Settings does (op allowed, or default with the permission granted). With privileged access (Shizuku or `ShellHelper`), Camera, Microphone, Location, Contacts, SMS and Call log also show when each allowed app last used it (`appops get`). | Lists what Android reports; it can't tell whether an app misuses a permission. Needs `QUERY_ALL_PACKAGES` to see every app (it says so when the list may be incomplete). When Android won't tell an app-op's state, the app is listed as "state not readable". Last-use times are left out without the helper; Android keeps only the most recent use, for up to 80 apps per group. |
| **Hardware test** | Opened from Home. A checklist of 13 guided checks: display colours (full-screen white, black, red, green, blue), touch grid (paint every square), multi-touch count, speaker tone, earpiece tone, microphone (records 4 s with a level bar, plays it back, never saved), vibration, volume buttons, sensors (live accelerometer, gyroscope, proximity, light, magnetometer, barometer), flashlight, cameras Android lists, Wi‑Fi/Bluetooth/GPS/NFC presence and switch state, fingerprint sensor presence. Automatic results where the phone can tell (touch grid, multi-touch, buttons, sensors, lists), otherwise the person taps Passed or Failed. The summary can be shared as text. | Quick checks, not a workshop diagnosis. The power button can't be tested by an app. Cameras, radios and fingerprint are presence checks from what Android reports: no photo is taken, no signal is measured, no finger is scanned. The microphone test asks for the Microphone permission when started. Results are kept only until the app closes. |

## Privileged access (Shizuku, helper, root)

Stopping and freezing apps, the RAM manager's process list, per-app battery use, app-op records, Deep sleep and app limits need the rights of adb (uid `shell`) or root. `Privileged.kt` is the one door to them, with three ways through it:

| Backend | How it starts | Lasts |
|---|---|---|
| **Shizuku** (`ShizukuAccess.kt`, `PrivilegedService.kt`) | The person starts the [Shizuku](https://shizuku.rikka.app) app (Wireless debugging pairing on Android 11+, or from a computer: Droid Desk's Shizuku page has **Start Shizuku**), then allows the companion in Shizuku's own dialog. No computer needed afterwards | Until the phone restarts (Shizuku started over adb), or always (rooted) |
| **Droid Desk helper** (`ShellHelper.kt`) | Droid Desk on the PC, over adb (Shizuku › Phone RAM manager) | Until the phone restarts |
| **Root** | The same helper, started with `su` (Memory › Use root instead) | Until the phone restarts |

- **Library:** [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) **13.1.5** (`dev.rikka.shizuku:api` and `:provider`, MIT, from Maven Central). It is the app's only dependency. Its `androidx.annotation` dependency (annotations only) is excluded, so no AndroidX code is shipped.
- **How:** a Shizuku *UserService*, the way Shizuku documents for running an app's own code with its rights (its `newProcess` is being removed). Shizuku starts `PrivilegedService` from the companion's APK as shell or root and hands its binder to the companion. The interface (`IPrivilegedService.aidl`) has one call: a method name and JSON parameters. It runs in daemon mode, so a forced Doze is still lifted after Android closes the app (see Battery saver); a new app version replaces a running copy.
- **Same commands for every backend:** `PrivilegedCommands.kt` holds the fixed list both the helper and the UserService serve. Each command has a fixed argument list and is started without a shell. Package names must match a strict pattern; every other argument must be one of a fixed set (bucket names, two settings keys with value ranges, the companion's own permissions and app-ops). `android`, System UI, the phone app, Shizuku and the companion can't be stopped. No text from the screen ever becomes a command line.
- **Who can reach it:** Shizuku gives the UserService binder only to the app that asked; the service also checks the caller's uid. The helper keeps its random token (see Memory above). `ShizukuProvider` is exported for Shizuku and guarded by `INTERACT_ACROSS_USERS_FULL`, which normal apps can't hold.
- **States handled:** Shizuku not installed, installed but not running, older than v11 (not supported), running without permission (asked with `Shizuku.requestPermission`, answered through its result listener), "Deny and don't ask again", the binder dying mid-call (one retry, then "not available"), and adb or root (`Shizuku.getUid()`, shown in Settings).
- **R8:** `proguard-rules.pro` keeps `PrivilegedService` and its two constructors (Shizuku creates it by name) and silences the excluded `androidx.annotation`. The library brings its own rule for `BinderContainer`. The service is named by a fixed `tag`, as its class name isn't relied on.
- **Settings › Privileged access:** the backend in use and why, Shizuku's state (version, adb or root), **Grant permission**, **Open Shizuku** (or a download link), **How to start Shizuku**, **Prefer** (Automatic = Shizuku first, Shizuku, or helper/root), and **Finish setup without a computer**: with a backend ready, the companion grants itself what Droid Desk grants over adb, each item listed with its state, tickable, and reported afterwards: runtime permissions and `WRITE_SECURE_SETTINGS` (`pm grant`), the app-ops `GET_USAGE_STATS`, `MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `WRITE_SMS` (`appops set`), and notification access (`cmd notification allow_listener`).
- **Limits:** on phones that aren't rooted Shizuku must be started again after every restart. Android refuses SMS and call-log permissions for apps that weren't installed over adb, also through `pm grant`; the result says so. With adb rights (not root) nothing can touch other apps' private data.

## Freezer

Stops apps that run in the background without being used. "Freezing" is a force stop, as in Android's App info: the app stays stopped until it is opened again (or another app calls it), gets no notifications while frozen, and nothing is uninstalled or disabled. The logic is ported from [SuperFreezZ](https://gitlab.com/SuperFreezZ/SuperFreezZ) (GPL-3.0-or-later, commit `267d0f7`).

| Part | How |
|---|---|
| Modes (ported) | Per app: **Always**, **When unused**, **Never**. The default for downloaded apps is "When unused", as in SuperFreezZ |
| What is pending (ported) | An app is running when Android's `FLAG_STOPPED` is clear. "Always" apps are pending whenever they run. "When unused" apps are pending when they run and were on screen for less than 2 seconds in the last 7 days (`UsageStatsManager`, needs usage access; 1 to 30 days can be chosen). Without usage access "When unused" freezes nothing. Apps installed from F-Droid are left alone unless switched on (SuperFreezZ ships a list of F-Droid package names; here the installer is checked instead) |
| Protected (new) | System apps, the home screen, keyboard, phone and SMS apps, enabled accessibility services, device admin apps, Shizuku and the companion start as "Never"; choosing another mode for them asks first. Apps in use right now (on screen, or with a foreground service: music, navigation) are skipped unless allowed per app. "In use" comes from Android's process ranking with privileged access, otherwise from usage events (Android 10+) |
| Stopping | With privileged access: `am set-inactive`, `am force-stop`, `am kill` (SuperFreezZ's root method), no screens. Without: the **accessibility method** if the person turned it on (ported: opens App info per app and presses Force stop, OK, Back; 4-second timeout per step, stops on an unexpected screen, treats a disabled button as already stopped, the power button aborts). With neither: App info opens per app and the person presses the buttons |
| Screen | Hero card with the number of pending apps and **Freeze now**; the method in use; app list with search, pending apps first and a line per app saying why it will or won't be frozen; tap an app for its mode, **Freeze now** for that app, and the overrides |
| Automatic | **When the screen turns off** (at once, or after 30 s to 15 min; turning the screen on cancels; at most once a minute) and **when the battery drops below** 15 to 50% (once per discharge). Both need privileged access to act; without it the battery trigger posts a notification to tap |
| Shortcuts | A pinned home-screen shortcut and a quick-settings tile, both **Freeze now** (`FreezeActivity`, not exported) |

**Background mechanism.** Android delivers "screen off/on" and battery changes only to running apps that registered for them in code. While at least one automatic option is on (here or in the Battery saver), `PowerWatchService` runs: a foreground service of type `specialUse` with a visible "Battery watch" notification that lists what it waits for. It starts when an option is turned on (the app is on screen then), at app start, and after a phone restart or app update (`PowerReceiver`: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`), and stops with the last option. Nothing runs when all options are off. The delayed freeze uses an inexact `AlarmManager` alarm, so it can run late. If Android stops the service, the 15-minute battery job still checks the battery level and charging alerts; screen-off triggers wait until the app is opened or the phone restarts. Android 12+ doesn't let a job restart a foreground service.

**The accessibility service** (`FreezeAccessibilityService`) can only be bound by Android, only after the person switches it on in Accessibility settings, is limited to windows of the Settings app, and does nothing unless the method is also on in the Freezer and a freeze is running. Differences from SuperFreezZ: it never switches itself on; the guess between two unnamed buttons is left out (a wrong guess could press "Uninstall"); a failed app is skipped and three failures end the run; it isn't used with the screen off (SuperFreezZ wakes the screen and suspends the lock screen for that).

**Limits:** apps restart when something calls them (a push message for another app won't, a share or a sync adapter may). Freezing apps opened many times a day costs more battery than it saves. The accessibility method depends on how the phone's App info screen is built and may not find the button on every phone.

## Battery saver

Opened from Battery (**Saver**). Each part names what it needs and is greyed out with the reason when that is missing.

| Part | What it does | Needs |
|---|---|---|
| **Battery Saver** switch | Writes `Settings.Global` `low_power`; "turn on automatically below X%" writes `low_power_trigger_level` | `WRITE_SECURE_SETTINGS`, or privileged access (`settings put`) |
| **Deep sleep now** | `dumpsys deviceidle force-idle`: Doze at once. Ends when the screen turns on (`unforce`), or after 10 minutes if the screen never goes off. Option: every time the screen turns off (not while charging) | Privileged access; the option also needs the Battery watch |
| **Charging** | Battery current and estimated power (current × voltage) every 2 s, from `BATTERY_PROPERTY_CURRENT_NOW`. Reminder at a chosen charge level (70 to 95%), fully-charged alert, overheating alert (45 °C, at most every 30 min) | Nothing; alerts need notifications and the Battery watch (15-minute checks without it) |
| **Awake now** | Apps holding wakelocks (`dumpsys power`) first, then apps running in the background, with **Stop**; tap for "Always freeze" | Privileged access |
| **App limits** | Per downloaded app: App Standby bucket (`am get-standby-bucket`; set Active, Rare, or Restricted on Android 12+) and background restriction (`RUN_ANY_IN_BACKGROUND` app-op, read without special rights). Biggest battery users first when Android reports per-app figures | Privileged access to see buckets and change either; Android 9+ for buckets |

**Limits:** Android turns Battery Saver off while charging, and phones with their own power saving mode (Samsung and others) may ignore both settings; the screen says so when the switch doesn't take. In Doze, notifications and syncing are delayed; calls, SMS and alarms still arrive. A forced Doze doesn't end by itself, so the privileged process also watches the screen and lifts it, in case Android has closed the app. The app can remind at a charge level but can't stop charging. Phones report battery current in different units and signs: the unit is told apart by size and the sign taken from the charging state, and phones that report nothing show "Not reported". Android moves apps between standby buckets by itself, so a bucket set here doesn't stay forever.

**Permissions added for these features:** `FOREGROUND_SERVICE_SPECIAL_USE` (the Battery watch) and Shizuku's `moe.shizuku.manager.permission.API_V23` (merged from the library). New components: `ShizukuProvider` (exported, guarded), `FreezeActivity` (not exported), `FreezeAccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`), `PowerWatchService` and `PowerReceiver` (not exported), `FreezeTile` (`BIND_QUICK_SETTINGS_TILE`).

## Wi‑Fi receive

The **Receive** screen (last button in the bottom bar) makes the phone visible to computers on the same Wi‑Fi and saves what they send to `Download/Droid Desk`. Droid Desk's **Wi‑Fi transfer** tab sends to it, and so does any app that speaks the same open file-sharing protocol (v2).

| Part | How |
|---|---|
| Discovery | JSON announcements on UDP multicast `224.0.0.167:53317`. When a computer announces itself, the phone answers with its name, port and certificate fingerprint. A `MulticastLock` is held while receiving is on, because many phones drop multicast otherwise. |
| Transfer | HTTPS on TCP `53317` (or the next free port, which is announced). `POST …/prepare-upload` lists the files; the person accepts or declines; each file then goes to `POST …/upload` with its own token. Parallel uploads and keep-alive connections are supported. |
| Saving | Android 10+: MediaStore `Downloads` (no storage permission; files are hidden until complete and removed if a transfer fails). Android 8–9: written directly, then indexed (needs the storage permission). Name clashes get ` (1)` added. Sub-folders the sender includes are kept, up to 6 levels. |
| Running | `ReceiveService`, a `connectedDevice` foreground service, not exported. A notification shows the status and progress, plus Accept/Decline for each transfer while the app is in the background. Turns off after 30 minutes without transfers. |

Code: `WifiReceive.kt` (protocol and saving), `MiniHttp.kt` (small HTTP/1.1 server), `TlsIdentity.kt` (key and self-signed certificate), `ReceiveService.kt`, `ReceiveScreen.kt`. This code uses no library.

**Security**
- Nothing listens on the network until receiving is turned on, and everything closes when it's turned off.
- HTTPS only. The phone makes its own RSA-2048 key and self-signed certificate (app-private storage, kept across restarts). Senders pin the certificate by its SHA-256 fingerprint.
- Nothing is saved without the person's consent, given on the screen or in the notification. Unanswered requests are declined after 60 seconds. One transfer at a time; others get `409`.
- Each upload must carry the session ID, its file's random token, and come from the IP address that asked. It may not exceed the announced size, and is checked against the sender's SHA-256 when one is given (`422` on a mismatch).
- File names from the network are sanitised. `..`, control and reserved characters are removed, so files can't land outside `Download/Droid Desk`.
- Transfers that need more space than is free are declined.

**Direct link** (`DirectLink.kt`): when Droid Desk asks for it (an extra `droiddesk` field that other apps ignore), the phone starts a temporary private hotspot after the person accepts. It uses Android's LocalOnlyHotspot, so Android picks a random name and password and the network has no internet. The details go back only inside the encrypted reply to the computer that asked. Uploads are then also accepted from clients of that hotspot, still with the per-file tokens. The hotspot closes when the transfer ends, when it's cancelled, or when receiving is turned off.
- **Foreground rule:** Android only allows this while the app is on screen. That's why the notification's Accept opens the app for Direct link requests.
- **Permissions:** needs "Nearby devices" (Android 13+), or location permission with Location turned on (Android 8–12; location is never read). Droid Desk grants the permission when it installs the app.
- **Phones on Wi‑Fi:** these also need Wi‑Fi + hotspot concurrency (`isStaApConcurrencySupported`, Android 11+). Otherwise the phone would drop off the router. Phones without it show Direct link as "Not available".
- **Off switch:** the Receive screen has an on/off switch for Direct link, and shows the phone's own Wi‑Fi band, rate and signal.

**Limits:** no PIN option for receiving. Discovery needs multicast, so some guest or office networks hide the phone. In that case use the phone's hotspot. If another app on the phone already uses port 53317, the phone takes the next free port, which only discovery can find.

## Wi‑Fi send

The same screen has a **Receive | Send** switch. The Send side sends files, photos, text and links from the phone to a computer running Droid Desk's **Wi‑Fi transfer** tab, or to any app that speaks the same open file-sharing protocol (v2). Receiving doesn't have to be on.

| Part | How |
|---|---|
| Share target | **Share › Droid Desk** in Gallery, Files and other apps (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`, any type) queues the shared items and opens the Send side. Shared text and links are sent as a small `.txt` file with the text also given as the protocol's `preview`, so apps that show messages display it. The Send side also has **Files** (Android's document picker) and **Photos** (the photo picker on Android 13+) buttons. Names and sizes come from `OpenableColumns`. |
| Discovery | Opening the Send side announces the phone on UDP multicast `224.0.0.167:53317` and lists the devices that answer over multicast, or by calling `register` on the phone while receiving is on. If nothing answers within 1.5 s (multicast is filtered on some networks), and whenever **Refresh** is tapped, it asks each address of the phone's own /24 directly (`POST …/register`, 48 at a time, 0.7 s connect timeout, private address ranges only). **Enter address** adds a device by IP address (`192.168.1.20` or `192.168.1.20:53317`). Each device shows its name, type, address and the first 8 characters of its fingerprint. |
| Transfer | `POST …/prepare-upload` with each item's id, plain file name, size, type and (files up to 32 MB, 128 MB in total) SHA-256. Then the other device accepts. `204` means it needs nothing more, `403` declined, `409` busy; `401` asks for the receiver's PIN on the phone and tries again with `?pin=`. The reply must hold a session ID and a token per file; files without a token are not sent. Files are then streamed from their content URIs to `POST …/upload`, two at a time on kept-alive connections. Cancel closes the connections and calls `…/cancel`. |
| Running | `SendService`, a `connectedDevice` foreground service, not exported, started by the Send button. Its notification shows the progress and a Cancel button, and a summary when the transfer ends. Wi‑Fi and wake locks are held during the transfer. Files stay open while queued, and their read grants are passed to the service, so a transfer keeps going after leaving the app. |

Code: `WifiSend.kt` (discovery, HTTP client, transfer), `SendService.kt`, `ShareActivity.kt` (share target and pickers; no screen of its own), `SendScreen.kt` (the Send side). It reuses `TlsIdentity.kt`, so the phone has one name and fingerprint for sending and receiving. This code uses no library.

**Security**
- Sending opens outbound connections only. Nothing listens because of it, and nothing is sent until the person picks a device and taps Send, and the other device accepts.
- HTTPS with the receiver's self-signed certificate. Its SHA-256 must match the fingerprint the device announced, or the one seen when it was found by address; otherwise the transfer stops before any request is sent. Devices that announce plain HTTP (encryption turned off in their app) can be sent to, with a warning on the screen.
- Only `content://` items are accepted from other apps, so a share can't point at the companion's own private files. File names are sent as bare names, without folders.
- The receiver's reply is checked (session ID, per-file tokens), and at most the announced size of each file is sent.
- Connections to a device on the phone's Wi‑Fi (or Ethernet) are bound to that network, so they don't leave over mobile data when the Wi‑Fi has no internet.

**Limits:** up to 500 items per transfer. One transfer at a time. Files only, no folders. If one file fails the others still go; what wasn't sent stays in the list to try again (there is no resume inside a file). The address sweep covers /24 networks and the default port 53317 only. While receiving is off, the phone still announces itself to be found by receivers that only answer announcements, so a computer may list the phone although it can't receive yet. Not yet tested on a real phone.

## API (one JSON request per connection)

| Method | Params | Result |
|---|---|---|
| `info` | – | `{version, versionName, sdk, permissions{READ_SMS,…}, defaultSmsApp}` |
| `sms.list` | `limit?` | `[{id, thread, address, body, date, dateSent, type, read}]`, newest first. Type: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued |
| `sms.send` | `to, body` | `{parts}`. Android saves it to Sent automatically |
| `sms.restore` | `messages[{address, body, date, dateSent?, type, read?}]` | `{added, skipped, failed}`. Skips messages already on the phone (same address, date, body and type). Only the default SMS app may write messages, unless the app-op `WRITE_SMS` is allowed, which Droid Desk does over adb (`appops set dev.droiddesk.companion WRITE_SMS allow`). Every insert is read back, so `added` counts what is really stored; an error says so when the phone stores nothing. Outbox/queued messages are stored as "failed" so the phone doesn't send them again |
| `mms.list` | `limit?` | `[{id, thread, date, dateSent, type, read, subject, from, to[], recipients[], text, parts[{partId, mime, name, size}]}]`, newest first. Dates in ms; `type` uses the SMS codes (1 inbox, 2 sent, 3 draft, 4 outbox). `recipients` are the conversation's other participants (group threads), `text` the text parts, `parts` the attachments |
| `mms.part` | `partId, maxBytes?, thumb?` | `{partId, mime, size, data}` (base64). With `thumb` (pixels) a picture comes back as a small JPEG (`thumb: true`). Attachments over `maxBytes` (default 20 MB, at most 22 MB) are refused |
| `contacts.list` | – | `[{id, name, starred, phones[{number,type}], emails[{address,type}], org?, note?, birthday?, hasPhoto, accountType, accountName, rawId, rawCount}]`. The account is that of the contact's first raw contact (`""` for the phone's own) |
| `contacts.add` | `contacts[]` (same shape, plus `photo?` as a base64 picture) | `{added}`. Adds to the local "Phone" account |
| `contacts.update` | `contact{id, name, phones[], emails[], org?, note?, birthday?, starred?}` | `{ok, id}`. Replaces name, phones, e-mails, organisation, note and birthday. Written to the contact's raw contacts of the phone or an account; entries that apps (WhatsApp…) or a SIM card added for themselves are left alone. `id` can change when Android regroups the contact |
| `contacts.delete` | `ids[]` | `{deleted, failed[]}` |
| `contacts.photo` | `id, size?` | Base64 JPEG, or `null`. `size` ≤ 96 gives the stored thumbnail |
| `contacts.setPhoto` | `id, data` (base64 picture or `null`) | `true`. Scaled to at most 720 px; `null` removes the photo |
| `calls.list` | `limit?` | `[{number, name, date, duration, type}]`. Type: 1 in, 2 out, 3 missed, 4 voicemail, 5 declined, 6 blocked |
| `calls.restore` | `calls[{number, date, duration, type, name?}]` | `{added, skipped, failed}`. Skips calls already in the log (same number, date, duration and type). Needs `WRITE_CALL_LOG`. Voicemail entries fail: only the phone app may write those |
| `calendar.list` | `from?, to?` (ms) | `{calendars[{id, name, account, accountType, color, visible, writable, primary}], events[{id, calendarId, title, location, description, start, end, allDay, timezone, rrule, rdate?, exdate?, exrule?, duration?, originalId?, originalInstanceTime?, status?, uid?, attendees?[{name, email, status, type}]}]}`. With both `from` and `to`: only events that occur in that range, plus `instances[{eventId, begin, end}]` with repeating events expanded by Android |
| `calendar.add` | `calendarId, events[]` (same shape) | `{added, skipped, failed}`. Skips events the calendar already has (same title, start and repeat rule). Attendees are never written, so no invitations are sent |
| `calendar.createLocal` | `name?` | `{id}` of a calendar kept only on the phone ("Droid Desk"), created if missing, for phones without a calendar that can be written to |
| `media.list` | `kind` (`image`, `video`, `audio`), `allAudio?` | `[{id, name, size, mime, path, date, album, width, height, duration, orientation}]`. Audio items have `title, artist, album, albumId, track, year, music` instead of width/height. `path` is what the PC copies over adb |
| `media.thumbs` | `kind, ids[] (≤60), size?` | `{id: base64 JPEG}` (no entry when there's no thumbnail or album art) |
| `media.delete` | `kind, ids[]` | `{deleted, failed:[{id, path}]}`. Uses MediaStore, falling back to deleting the file (All files access) and re-indexing. The PC removes anything left over adb |
| `media.scan` | `paths[]` | `{path: id or null}`. Indexes files the PC pushed (or forgets deleted ones) |
| `media.duplicates` | `kind` | `[{size, ids[]}]`, oldest first: same size, then first and last 64 KB, then SHA‑1 |
| `call.answer` | – | `true`. Picks up the ringing call (`TelecomManager.acceptRingingCall`, needs "Phone" › Answer calls) |
| `call.end` | – | `true` if a call was ended. Declines a ringing call or hangs up (`TelecomManager.endCall`, Android 9+) |
| `notif.status` | – | `{access, connected, component}`: whether "Notification access" is on and the listener is running |
| `notif.list` | – | The phone's current notifications, newest first (≤150), same shape as the `notif` event below |
| `notif.reply` | `key, action, text` | `{via:"app", opensScreen}` or `{via:"sms", to}`. Fills the app's own Reply (RemoteInput, as a smartwatch does). For the default SMS app the text is sent as an SMS to the sender's number instead, since many SMS apps reply by opening a screen, which Android blocks from the background |
| `notif.action` | `key, action` | `true`. Presses one of the notification's buttons ("Mark as read"…). `action` ≥ 100 = the notification's smartwatch buttons |
| `notif.dismiss` | `keys[]` | `{ok}`. Dismisses them on the phone (all clearable ones if `keys` is missing) |
| `notif.icon` | `pkg` | `{pkg, app, icon}`: the app's name and a 96 px PNG (base64) |
| `events` | `calls?` (default true), `notifications?` (default false) | **Stays open.** One JSON line per event: first `{type:"hello", permissions{READ_PHONE_STATE,…}, notifications{access,…}}`, then call alerts `{type:"call", state, id, number, name, time}` with `state` = `ringing` (sent again when the number arrives), `answered`, `ended`, `missed`, `declined` or `blocked` (told apart from the call log), and `{type:"ping"}` every 20 s. With `notifications`: `{type:"notif", op:"posted", key, pkg, app, title, text, lines[], messages[{sender,text,me,time}], actions[{i,title,reply}], clearable, alert, importance, largeIcon?}` and `{type:"notif", op:"removed", key}`. Ongoing notifications (music, downloads), group summaries, calls and the companion's own are skipped; `alert` is true only when a notification's content changed and it (or its group summary) made a sound. While a PC listens, the service doesn't stop for being idle. See `CallWatch.kt` and `NotifListener.kt` |
| `push.send` | `text` (≤ 20 000 characters) | `{link, opened, notified}`. Posts a "From your computer" notification: tapping it opens the link (or copies the text), and **Copy** copies it. An `http(s)` link is also opened in the browser straight away when the phone is unlocked and Android lets a background app do so (the companion is on screen or has "Display over other apps"); `opened` is the best guess at that |
| `clip.set` | `text` (≤ 200 000 characters) | `{length}`. Puts the text on the phone's clipboard |
| `phone.ring` | `on` (default true) | `{ringing, sound?, seconds?}`. Find my phone: see [Phone links](#phone-links) |
| `player.now` | – | `{app, pkg, title, artist, album, state, position, duration, canSeek, art?}` or `null` when nothing is playing. `state`: `playing`, `paused`, `buffering` or `stopped`; `position`/`duration` in ms; `art`: a small JPEG (≤ 160 px, base64). Needs "Notification access" (error otherwise). Named `player.*` because `media.*` is the photo and music index |
| `player.control` | `action` (`play`, `pause`, `toggle`, `next`, `prev`, `seek`), `position?` (ms, for `seek`), `pkg?` | `true`. Acts on the app that is playing (or `pkg`) |
| `events` (additions) | `media?` (default false) | Always: `{type:"clip", text, truncated}` when the person sends the phone's clipboard (or `{type:"clip", error:"locked"\|"empty"}` when it couldn't be read), and `{type:"ring", on:false, reason}` when Find my phone stops (`found`, `timeout`, `pc` or `link`). With `media`: `{type:"media", …}` in the shape of `player.now` when the track or playback state changes or the position jumps, once at the start, and `{type:"media", state:"none"}` when nothing plays any more. See `Links.kt`, `ClipSendActivity.kt` and `Player.kt` |
| `rec.session` | `steps?`, `last?` | **Stays open.** Tries to show the badge and replies `{overlay: true/false, canDraw}` (it doesn't rely on `Settings.canDrawOverlays()`, which was wrongly false on a Galaxy S20), then keeps the floating **Recording** badge over other apps (`RecOverlay.kt`) while Droid Desk records a test. The PC then sends one JSON line per change (`{"steps":3,"last":"Tap “Log in”"}`) and `{"ping":true}` every 20 s. The badge goes away when the connection closes, so it can't be left behind. Needs "Display over other apps" (`SYSTEM_ALERT_WINDOW`), which Droid Desk allows over adb (`appops set … SYSTEM_ALERT_WINDOW allow`). The badge can be dragged; Android hides it on its own Settings screens and on screens that block overlays |
| `stop` | – | `true`, then the service stops |

## Phone links

Small things that tie the phone to the computer (Droid Desk's Overview › **Phone links**). Code: `Links.kt`, `ClipSendActivity.kt`, `Player.kt`, `Tiles.kt`.

- **Send to phone** (`push.send`): a link or text from the computer arrives as a notification, and links open in the browser when Android allows it.
- **Shared clipboard:** `clip.set` writes the phone's clipboard. The other way round, Android 10+ only lets the app on screen read the clipboard, so `ClipSendActivity` (see-through, no history) opens, reads it once it has the focus, sends it as a `clip` event and closes. It is opened by **Send clipboard to PC** on the "Connected to Droid Desk" notification, by the quick-settings tile, or by Droid Desk over adb (`am start`); it is exported only behind `android.permission.DUMP`. The phone has to be unlocked. Clipboard contents are never logged or stored.
- **Find my phone** (`phone.ring`): plays the ringtone in a loop on the alarm stream at full volume (so it also sounds on silent, and in Do Not Disturb unless that is set to total silence or blocks alarms), vibrates, and shows a notification with **Found it** that opens a full-screen "Found it" screen over the lock screen. It stops on Found it, when the PC says so, when the PC link stops, or after 2 minutes, and puts the alarm volume back. Android 14+ only opens the screen over the lock screen with the "full-screen notifications" access, which Droid Desk allows at install (`appops set … USE_FULL_SCREEN_INTENT allow`); without it the notification still shows.
- **Media remote** (`player.*`): what any app is playing, through `MediaSessionManager`. Android only shows this to notification listeners, so it needs the same "Notification access" as phone notifications; nothing is watched unless a PC asks with `media`.
- **Quick-settings tiles** (add them by editing the quick-settings panel): **PC link** (starts/stops the link), **Receive files** (turns Wi‑Fi receive on/off; opens the Receive screen first when a permission is missing) and **Send clipboard to PC** (unavailable while no computer is listening). If Android refuses to start a service from a tile, the tile opens the app, which starts it.

## Build

```sh
cd android
./gradlew assembleRelease
# or, from pc/: npm run build:companion   (builds and copies to pc/renderer/companion.apk)
```

Needs JDK 17 and the Android SDK (platform 36). Built by hand, the release APK is signed with the local **debug key** unless a keystore is given (see Builds and releases). If a phone has a copy signed with a different key, Droid Desk uninstalls it and reinstalls. For public releases, create a real keystore and keep it safe.

When you change the app, bump `versionCode` in `app/build.gradle.kts` **and** `COMPANION_VERSION` in `pc/src/app.js`, so Droid Desk updates phones that have the old version.

## Builds and releases

GitHub Actions does the checking and building (`.github/workflows/android.yml`).

| When | What happens |
|---|---|
| Push to `main`, or a pull request | Lint and a debug build. The debug APK and the lint report are attached to the run. |
| Push to `release` | The same checks, then the signed release APK goes into a **draft** GitHub Release named after `versionName` (`v0.29.0`), with its SHA-256 file. You review and publish it. |
| A tag like `v0.29.0`, or **Run workflow** in the Actions tab | The release APK, attached to the run only. |

**To release:** raise `versionCode` and `versionName`, merge `main` into `release` and push. Pushing the same version again replaces the files on the draft; a version that is already published is never overwritten (the build fails and asks for a new version).

**Signing key.** Updates only install over a build signed with the same key, so the workflow needs one key that never changes. Add it as repository secrets (Settings › Secrets and variables › Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The keystore file, base64-encoded: `base64 -i release.keystore \| pbcopy` on macOS |
| `KEYSTORE_PASSWORD` | The keystore's password |
| `KEY_ALIAS` | The key's name in the keystore |
| `KEY_PASSWORD` | The key's password |

Create a keystore with `keytool -genkeypair -v -keystore release.keystore -alias droiddesk -keyalg RSA -keysize 4096 -validity 10000`, and keep a copy somewhere safe: a lost key means every phone has to uninstall and reinstall. Phones that already have a build signed with a local debug key need that one reinstall when the key changes (Droid Desk does it by itself, but the companion's settings on the phone are reset).

Without the secrets the workflow still builds, signs the APK with a throw-away key, names it `…-test-key.apk` and makes no draft release.

The same variables sign a build made by hand: `DD_KEYSTORE_FILE` (path to the keystore), `DD_KEYSTORE_PASSWORD`, `DD_KEY_ALIAS`, `DD_KEY_PASSWORD`.

**Lint.** A new lint error fails the build. The problems that existed when the check was added are listed in `app/lint-baseline.xml` and don't count. After fixing one, run `./gradlew updateLintBaseline` and commit the file.

## Tested

- **Android 12 emulator (API 31), Direct link:** tested with Droid Desk's real sending code; only the computer's Wi‑Fi switch was simulated. The hotspot started (random name, WPA2, 2.4 GHz), its details came back over HTTPS, the phone was verified by its certificate, the file uploaded, and the hotspot closed afterwards. Also tested: Accept from the notification while in the background (opens the app, then the transfer completes), and falling back to the normal Wi‑Fi when the computer doesn't join. Not yet tested on a real phone with a real computer joining.
- **Android 12 emulator (API 31), Wi‑Fi receive:** tested a 26-file send from Droid Desk (keep-alive, 3 parallel uploads, all SHA-256 checksums matched), Accept/Decline on screen and from the notification with the app in the background, timeouts, 409 while busy, bad token/session, replay, oversize upload, checksum mismatch (422), chunked upload, `Expect: 100-continue`, path traversal names, cancel, opening a received file, and turning off (port closed). Discovery over multicast needs a real Wi‑Fi network; it was not tested on the emulator.
- **Android 12 emulator (API 31), phone notifications:** turning on Notification access over adb, incoming SMS (AOSP Messaging, which keeps its Reply only in the smartwatch actions and replies by opening a screen: answered as an SMS instead, from the tab, the pop-up, quick replies and typed text), chat-style notifications with several senders, dismiss and Clear all on the phone, group summaries (no duplicates; only the new conversation pops up), and restarting the companion (old notifications don't pop up again). Not yet tested with WhatsApp or on a real phone.
- **Android 12 emulator (API 31), call alerts:** simulated calls (`adb emu gsm call`) with Droid Desk connected over Wireless debugging: ringing (with the contact's name), answer and hang up from the PC, decline from the PC (logged as declined, so no "missed" card), silence (the call keeps ringing), and a missed call. Not yet tested on a real phone or with two calls at once (call waiting).
- **Android 12 emulator (API 31):** tested install with `-g` (all permissions granted), starting the service from adb, `info`, `contacts.add`, `contacts.list`, `sms.list`, `calls.list`, `sms.send` (the message appears in Sent), `stop`, and unknown-method errors.
- **Not yet tested on any device: Shizuku, the Freezer and the Battery saver.** They were written and built (the minified release build succeeds) without a phone. To check: Shizuku's permission dialog and the UserService start (adb and root), every state of Settings › Privileged access, “Finish setup without a computer”, freezing through each method (the accessibility method especially, on Samsung and on Android 14+ where App info looks different), the “Battery watch” service surviving a restart, Deep sleep and its automatic end, standby buckets, the Battery Saver switch on Samsung, and the charging figures.
- **Not yet tested on Android 14+:** the stricter foreground-service rules there are handled in the manifest, but haven't been tested. Try it on a real phone.

## Not included yet

- **Sending MMS, and restoring MMS:** pictures and group texts can be read, not sent or put back.
- **Restoring SMS on every phone:** it depends on the phone honouring the `WRITE_SMS` app-op for an app that isn't the default SMS app. Not yet tested on real phones; where it doesn't work, `sms.restore` reports an error, and the exported XML can be restored with the "SMS Backup & Restore" app instead.
- **Notification mirroring.**
- **Moving the PC link to Wi‑Fi** (it stays on USB).
- **Boost on Android 14+:** not possible for apps without privileged access (see above).

## License

Droid Desk Companion is free software, licensed under the **GNU General Public License, version 3 only** (`GPL-3.0-only`). The full text is in [`LICENSE`](LICENSE). Every source file carries the header:

```
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors
```

The app shows the copyright, the no-warranty notice and the licence under **Settings › About › Licence**, as GPL-3.0 section 5(d) asks of programs with an interactive interface.

Anyone who distributes the APK, including inside Droid Desk's installer, must also offer the matching source code (GPL-3.0 section 6).

### Third-party material

| What | Where | License |
|---|---|---|
| System Cleaner filters, matching engine (`SdmSieve.kt`, `SystemCleaner.kt`) and CorpseFinder (`CorpseFinder.kt`), ported from [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) (commit `ecbe81b`) | `app/src/main/java/…/companion/` | GNU GPL v3, the SD Maid SE authors |
| SD Maid SE's clutter marker database | `app/src/main/assets/clutter/db_clutter_markers.json` | From SD Maid SE. Its README excludes "assets" (icons, logos, marketing) from the GPL; this data file is included on the understanding that it is covered by the GPL like the code that uses it. |
| The Freezer: freeze modes, the pending-freeze decision and its defaults, running/stopped detection, freeze on screen off, the list order, and the accessibility method (`Freezer.kt`, `FreezeAccessibility.kt`, `FreezerScreen.kt`, `PowerWatch.kt`, the `freeze` command in `PrivilegedCommands.kt`, `res/xml/freeze_accessibility.xml`), ported from [SuperFreezZ](https://gitlab.com/SuperFreezZ/SuperFreezZ) (commit `267d0f7`) | `app/src/main/java/…/companion/`, `app/src/main/res/xml/` | GNU GPL v3 or later, Copyright (c) 2018 Hocuri; used here under version 3. Each file keeps the original notice |
| [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 13.1.5 (`dev.rikka.shizuku:api`, `:provider`, with their `aidl` and `shared` parts), the app's only library, from Maven Central | compiled into the APK | MIT License, Copyright (c) 2021 RikkaW (notice under Settings › About › Third-party notices) |
| Poppins font, subset to Latin | `app/src/main/res/font/` | SIL Open Font License 1.1 (`res/raw/poppins_ofl.txt`) |
| [Lucide](https://lucide.dev) icons, converted to vector drawables | `app/src/main/res/drawable/ic_*.xml` | ISC License (`res/raw/lucide_isc.txt`); parts derived from Feather, MIT |
| Freeze shortcut icon, based on Material Icons (Google LLC) | `res/drawable/ic_freeze_shortcut.xml` | Apache License 2.0 (`res/raw/apache_2_0.txt`) |
| Gradle wrapper (build tool only, not in the APK) | `gradle/wrapper/` | Apache License 2.0 |

All of these are compatible with GPL-3.0. The licence texts ship inside the APK and can be read under **Settings › About › Third-party notices**.


## Wi‑Fi connection to Droid Desk (Wireless debugging)

The **PC link** screen has a **Connect over Wi‑Fi** card:
- **After Droid Desk's one-time USB setup** ("Use this phone without the cable"), the app holds `WRITE_SECURE_SETTINGS`. Droid Desk grants it over adb; apps can't get it otherwise. The card is then a single **Turn on / Turn off Wi‑Fi connection** button, which writes `Settings.Global` `adb_wifi_enabled`. On a network the person already allowed, Android turns it on silently; on a new network it asks once.
- **Without that setup**, the card explains the one-time USB setup, with a shortcut to Developer options for doing it by hand (Wireless debugging › Pair device with QR code, scanning the QR code Droid Desk shows).
- Below Android 11 it says Wi‑Fi isn't available.

The PC link itself is unchanged: Droid Desk reaches the companion through adb (`localabstract:droiddesk_companion`), whether adb runs over USB or Wireless debugging.
