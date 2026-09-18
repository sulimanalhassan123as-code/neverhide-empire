# VAULT — The Aggressive Freeze & Silent Guardian Blueprint

**Status:** STORED, NOT ACTIVE. This technique is deliberately parked here.
We do NOT need it in the current app. Preserved for the future
"Silent Guardian" anti-theft build (owner's own device only).

**Why it's valuable:** the v2.6.1-v2.6.4 freeze engine proved a device-level
state that survived every app upgrade, every unfreeze attempt from later
builds, and held indefinitely. That persistence is the raw material an
un-uninstallable guardian needs.

---

## 1. The Freeze Engine (proven in v2.6.2 - v2.6.4)

Core principle: use Android's PACKAGE MANAGER device-level states, which are
owned by the OS, not by our app. The app that sets them can be uninstalled,
updated, or wiped — the state holds regardless.

### The freeze command (shell-permitted, no root)
```
pm disable-user --user 0 <package>
```
- Allowed for the plain shell identity (how Shizuku runs)
- The app vanishes from the launcher, cannot run at all, shows Android's own
  "managed by Shell" system dialog — the OS itself defends the state
- Survives reboots, app updates, and the freezing app being uninstalled

### The two blocking states (critical knowledge)
Android has TWO separate frozen states — an app can be in either, and the
wrong unfreeze command bounces off:
1. **disabled** — set by `pm disable-user`, reversed by `pm enable`
2. **suspended** — set by `pm suspend` (also used by Samsung Knox Guard
   itself), reversed ONLY by `pm unsuspend`

This is why the "old freeze held on": later builds unfroze with `pm enable`
only, which can NEVER release a suspended app.

### Never trust pm exit codes (Samsung lies)
Samsung One UI's `pm` prints `Error: ...` and still exits **0**. The v2.6.2
bug. The engine therefore verifies against the real state after EVERY
command:
```
pm list packages -d --user 0           # disabled list
pm list packages --suspended --user 0   # suspended list
```
Success = the package appears in NEITHER list.

### The release artillery (unfreeze)
Fire in order, stop the moment the device confirms freedom:
```
pm enable --user 0 <pkg>
pm enable <pkg>
pm unsuspend --user 0 <pkg>
pm unsuspend <pkg>
```

---

## 2. The Silent Guardian (future anti-theft build — design notes)

Goal: a guardian that works silently, alerts the owner, and cannot be
uninstalled by whoever holds the phone. Anti-theft for the owner's own
device — same category as Cerberus / Find My Device.

### The un-uninstallable core: Device Owner
```
adb shell dpm set-device-owner com.neverhide.empire/.guardian.GuardianAdminReceiver
```
- Android BLOCKS uninstalling a device-owner app through Settings — the
  Uninstall button is greyed out at the OS level
- Removal requires an ADB `dpm remove-active-admin` command (a thief has no
  PC pairing) or a factory reset
- Already wired in the app: `GuardianAdminReceiver` exists, device_admin.xml
  watch-login policy only (v2.5.1 fixed), reflective
  `setPackagesSuspended` path present and working

### Silent operation
- No launcher presence: disable the launcher alias (or `pm disable-user` our
  own launcher activity while keeping the service running)
- Capture: proven `GuardianCaptureService` pattern (v2.5.2) — genuine
  foreground service typed camera|location, works on Android 9+ without
  being blocked, 25s watchdog, 4s de-dup
- Alert network: SMS (offline, universal) + WhatsApp bridge leg (opt-in,
  verified live 2026-09-12) — wrong-password alerts with selfie + location

### If they try to remove it: freeze the removal paths
With Device Owner + the freeze engine, on uninstall/admin-removal attempt:
- Freeze Settings (`com.android.settings`) and the Package Installer UI
  with `pm disable-user` — the OS itself blocks the removal
- The DeviceAdminReceiver refuses admin removal; password-fail triggers
  (already built) fire the capture + alerts first
- A non-owner cannot disable a device owner at the Settings level

### Honest limits (documented, not hidden)
- Device Owner must be set once via ADB (one-time, already planned)
- Factory reset wipes everything (FRP is the only post-reset line of
  defense)
- This blueprint is for the owner's own device protection only

---

## 3. Where the live code lives

- Freeze/unfreeze engine + verification artillery:
  `app/src/main/java/com/neverhide/empire/cleaner/CleanerActivity.kt`
  (`setSuspended`, `suspendedPackages`) — v2.6.4
- Shizuku shell broker (exec/run/query in shell process):
  `app/src/main/java/com/neverhide/empire/cleaner/ShizukuShell.kt` +
  `ShizukuShellService.kt` (v2.4.0 UserService pattern)
- Foreground capture service (silent camera+location):
  `app/src/main/java/com/neverhide/empire/guardian/GuardianCaptureService.kt`
- Device admin: `app/src/main/java/com/neverhide/empire/guardian/GuardianAdminReceiver.kt`

Vault created 2026-09-18 by Lyra at the owner's request after the v2.6.4
"stuck freeze" lesson. Do not activate until the owner asks for the
Silent Guardian build.
