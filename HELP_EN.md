# HyperGreeze User Guide

**English** · [简体中文](HELP.md)

Removes HyperOS restrictions on broadcasts related to Google push connections.

> The module targets LSPosed API 102.
> This guide matches the module's current behavior one to one; if anything here disagrees with what the module actually does, please open an issue.

---

## Contents

- [1. What it solves](#1-what-it-solves)
- [2. Quick start](#2-quick-start)
- [3. How it works](#3-how-it-works)
- [4. The allowlist mechanism](#4-the-allowlist-mechanism)
- [5. Power saving and freezing](#5-power-saving-and-freezing)
- [6. UI and features](#6-ui-and-features)
- [7. Experimental features](#7-experimental-features)
- [8. FAQ](#8-faq)
- [9. Known limitations](#9-known-limitations)
- [10. Privacy and permissions](#10-privacy-and-permissions)
- [11. Troubleshooting and feedback](#11-troubleshooting-and-feedback)

---

## 1. What it solves

HyperOS's power-saving policies restrict broadcasts and networking for background apps. Google push (FCM) relies on Google Play services to broadcast a wake-up call to the target app; once that broadcast is deferred or blocked, notifications stop arriving.

This module rewrites those checks inside the framework: it lets Google push and reconnect broadcasts through, keeps GMS from being cut off from the network, frozen or blacklisted, grants the target app a roughly 2-second temporary battery exemption on each push (allowlist-based), and lets the app receive the broadcast even in the "stopped" state.

> The trade-off: allowing push reconnects means apps can be woken by pushes, and battery use may go up with the module enabled. That is inherent to what the module does.

---

## 2. Quick start

1. LSPosed (or another compatible Xposed framework) is installed on the device
2. Install this module's APK
3. Enable the module in LSPosed's module list and check its scope; the experimental "GCM reconnect repair" additionally needs Google Play services checked
4. An LSPosed manager that supports API 102 hot-reloads the scope automatically when the module is enabled or updated, and the change takes effect immediately; on older LSPosed builds, both the first install and later updates require a manual reboot
5. Open the module and configure the allowlist as needed

It's a good idea to confirm the version under "Settings → Check for updates" and use "FCM diagnostics" to check GMS's connection state.

---

## 3. How it works

The delivery chain: GMS receives the FCM message → sends the `com.google.android.c2dm.intent.RECEIVE` broadcast → the target app's receiver runs.

HyperOS layers several gates on this chain; the module handles them gate by gate:

| Stage | System-side gate | What the module does |
|---|---|---|
| Broadcast delivery | `isAllowBroadcast` (the c2dm delivery check) | Allowed per the allowlist |
| App is "stopped" | stopped-packages flag | Re-adds `FLAG_INCLUDE_STOPPED_PACKAGES` |
| Autostart check | `checkApplicationAutoStart` | Allowed per the allowlist |
| App is frozen | `isRestrictReceiver` (greeze broadcast gate) | Let through, replaying the stock unfreeze |
| Broadcast deferred | `isNeedCachedBroadcast`, `deferBroadcast` | See [Known limitations](#9-known-limitations) |
| Alarm blocked | `checkAlarmIsAllowedSend` (the MIUI alarm delivery gate) | If an alarm belonging to GMS is judged blocked, it is let through instead |
| Power/network limits | PowerKeeper profiles, AppStandby, greeze's `isRestrictNet`, network restrictions | GMS is written into the no-restrict list; on delivery, the target app gets a ~2 s exemption |
| Force stop | `isForceStopEnable` | Applies to selected apps (that declare FCM components) only |

The hooked system services include `ActivityManagerService`, the alarm delivery gate (`AlarmManagerServiceStubImpl`), greeze (`GreezeManagerService`, `AurogonImmobulusMode`, the policy managers `DomesticPolicyManager` / `InternationalPolicyManager`), PowerKeeper's `AppStandbyController`, the process policy tables, and more.

**Compatibility**: both PowerKeeper designs are supported. The module picks at runtime based on whether the method exists — no manual switching.

- HyperOS 3 — the old design (`initGmsChain` / `updateGmsAlarm` / `updateGmsNetWork` and friends)
- HyperOS 4 — the new design (`updateFrameworkGmsNetStatus`)

---

## 4. The allowlist mechanism

The allowlist is the module's **only** narrowing mechanism: every restriction is lifted for selected apps only, and unselected apps keep the system's own judgment in full. There is no master switch and no "allow everything" entry — to lift a restriction for an app, select that app.

### Nothing selected

Everything stays tightened: all apps keep the system's default judgment, and behavior is essentially the same as without the module. In this state the module does exactly one thing — protect Google Play services itself (see below).

This is also the state right after a fresh install. To enable push wake-ups for an app, just select it.

### Some apps selected

Selected apps gain extra privileges:

- The autostart check lets them through directly
- They can be woken by this push even in the "stopped" state
- Each push earns them a ~2-second battery exemption
- When the app is frozen, the push broadcast is delivered directly instead of being cached
- Google push broadcasts are allowed through (`isAllowBroadcast`)
- Swiping them away from Recents does not force-stop them (`isForceStopEnable`; only apps that declare Google push components)

Unselected apps get none of these. Pushes for them usually arrive only after the app has been opened; swiping them away really does force-stop them and mark them "stopped", after which a push may fail to deliver (the system log shows `Failed to broadcast to stopped app`) — the app generally has to be reopened once to recover.

### What is always protected

GMS and push reconnects are protected **always**, regardless of selection: whether the list is empty or a page long, Google Play services itself is never narrowed. On top of that, part of the protection is written as device-wide policy tables (power-saving profiles, process allowlist, no-restrict list), which apply to the whole device as well.

That is why, in the app list, the Google Play services (GMS) card is **grey and unselectable**, showing a lock icon on the right instead of a checkbox: there is no "selected vs unselected" for it — behavior is identical either way. The card is also excluded from multi-select; select all never picks it up.

---

## 5. Power saving and freezing

### What the module does

It never modifies GMS itself — only the system-side checks:

- Writes GMS into the no-restrict list, flips its power-saving profile from "Smart restrict" to "Unrestricted", and writes "Unrestricted" back into the background control of the configuration table
- Fast freezing and the freeze policy skip GMS outright
- Turns off the restriction switch and network restrictions aimed at GMS
- Lets push reconnect and delivery broadcasts through
- Whether the network is cut during sleep mode is up to the system — the module stays out by default; the "Sleep mode" switches on the Settings page can keep the connection alive overnight
- If GMS is found frozen, it also sends heartbeat/reconnect requests so GMS dials back in

Summarized by dimension ("AOSP equivalent" = what stock Android does in that dimension):

| Dimension | Means | Nature | AOSP equivalent |
|---|---|---|---|
| Delivery to stopped apps | Adds the "include stopped packages" flag to Google push broadcasts | In-memory, per-broadcast, reversible | Restores: stock push broadcasts carry the flag natively |
| Temporary push-time exemption | Requests a ~2 s temporary battery exemption for the target app | In-memory, short window | Restores: the equivalent window stock Android gives a push cold start |
| Broadcast autostart gate | Push broadcasts pass the system's autostart entry directly | In-memory | Neutralizes: a broadcast gate HyperOS added |
| Power-saving policy | No-restrict list, profile forced to "Unrestricted", background control write-back | In-memory + config write-back | Neutralizes: no such layer in stock |
| Freezing and network limits | Fast freeze skips Google services, network restrictions turned off | In-memory | Neutralizes: no such layer in stock |
| Process retention | Three places (policy tables, process allowlist, etc.) written persistently in the lenient direction | Persistent | Restores AOSP-level survival surface |

### How it relates to the allowlist

The GMS protections above are always on; they do not depend on selection.

The allowlist decides whether the target app gets the wake-up privileges — autostart wake-ups, "stopped" wake-ups and the ~2 s battery exemption — and whether "allow Google push broadcasts" and "protection from force-stop" apply to it. Force-stop protection only covers apps that declare Google push components.

---

## 6. UI and features

### Main page (the FCM wake-up allowlist)

| Feature | Description |
|---|---|
| Search | Filter by app name or package name |
| Long-press a card | Enter multi-select to batch add/remove allowlist entries |
| Select all / deselect all | Applies to the current list in multi-select mode |
| More options (top right) | Show supported apps, exclude MiPush apps, show system apps, Settings |
| FCM diagnostics | Opens GMS's built-in diagnostics screen; falls back to GMS's app details page when unavailable |

### Settings page

| Section | Items |
|---|---|
| Display | Language (Simplified Chinese / English), hide launcher icon (once hidden, open the module from LSPosed) |
| Theme | Theme mode, dynamic color, palette style, color spec |
| Battery optimization | WeChat battery optimization switch, AOSP app battery usage entry |
| Sleep mode | Keep Wi-Fi, keep mobile data (off by default, see below) |
| Backup & restore | Export settings, import settings (allowlist as a text file) |
| Privacy & permissions | What each permission is for, and the privacy commitment |
| Experiments & debugging | Debug logging, experimental features entry |
| About | View source, check for updates, open-source licenses |

### Battery optimization (Settings page)

**Stop the system from putting WeChat back on the battery-unoptimized list**

Only blocks subsequent writes of WeChat into AOSP battery optimization's "Not optimized" list.

- Only blocks future writes; existing entries are not withdrawn
- Entries already on the list must be changed to "Optimized" yourself in battery optimization

The "AOSP app battery usage" row next to it is a direct entry into the system's battery usage page.

### Sleep mode (Settings page)

**Keep Wi-Fi connected**

Wi-Fi stays on when sleep mode is entered or exited, so the Google push channel is not cut all night; overnight battery use goes up.

**Keep mobile data connected**

Both mobile data and Wi-Fi stay connected all night; overnight battery use goes up further.

> Sleep mode cuts the network device-wide: the system turns off Wi-Fi and mobile data outright, without per-app handling. The only choice is keeping Wi-Fi, or keeping mobile data along with it.

### Launcher shortcuts

Long-press the launcher icon for direct access to: Settings, Help (opens this guide in a browser), FCM diagnostics.

### Horizontal swipe-back (subpages)

On the **subpages** — Settings, Experimental features, Privacy & permissions, Open-source licenses — swipe right from the **middle of the screen** to go back. The page follows your finger and gradually reveals the page beneath; the back action completes past a certain distance, otherwise the page springs back. A quick flick works too.

This gesture is not the same thing as the system's predictive back:

| | Horizontal swipe-back (this module) | System predictive back |
|---|---|---|
| Start position | The middle of the screen | The left/right **edges** |
| Destination | The **previous page** | Exits the **current app** |

The main page does not enable the gesture — its previous page is the launcher, which matches the system back action, so the system handles it.

---

## 7. Experimental features

These switches are reached from the "Experiments & debugging" section of the Settings page. They are all off by default, and each one makes the module intervene in behavior it otherwise leaves alone — which is why they are hidden one level deeper.

Flipping a switch takes effect immediately: no reboot, no module reload.

> Three kinds of lists are not the same thing, here and throughout: **this app's "FCM wake-up allowlist"**, the Security app's "battery saver policy", and AOSP "battery optimization".

For exactly where these switches intervene in the system, which behaviors the module deliberately leaves alone, and which conclusions are not yet backed by real-device measurements, see [the technical docs, §6.3 (Chinese)](HOOKS_AND_DIAGNOSTICS.md).

### Autostart

**Allow apps to be started**

When the system judges an autostart, selected apps' service starts and process starts are allowed through. Nothing is written to any setting; turning it off restores stock behavior immediately.

Two sub-switches live under it, each widening coverage; a sub-switch only shows once the one above it is on, and the chain collapses as you go up:

| Tier | Intervention point | Nature | Description |
|---|---|---|---|
| Turn off app freezing | The system's restart gate at freeze time | In-memory, off restores stock | Reduces freezing of selected apps; once on, whether processes take the cold or warm path changes wholesale, so cross-tier counters are not directly comparable |
| Allow all autostart checks | The unified query entry for the autostart switches | In-memory, off restores stock | The widest tier. Behavior is equivalent to having autostart enabled but nothing is written to disk, and the Security app's display is unchanged; it also lifts the "pre-start penalty", so the app's own autostart queries stop being refused |

A hidden sub-switch is just as inert: each tier only takes effect when it and every tier above it are on. Turn any tier off and the hidden tiers chained below it stop working along with it (their counters dropping to zero is expected, not a failure).

> These switches only affect selected apps — add apps to the allowlist first, otherwise the switches have nothing to act on.

The last row, "Autostart manager", is an entry that opens the Security app's autostart management page. Apps that earlier versions only wrote the autostart behavior bit for are reconciled automatically at every boot.

### Network

**Stay connected on weak Wi-Fi**

When the Wi-Fi signal is weak, the system no longer switches to mobile data based on its score, reducing back-and-forth switching.

The honest description: it keeps you on a Wi-Fi network that really is bad, and **does not improve Wi-Fi quality**.

**Adjust the weak Wi-Fi threshold**

Choose 45 / 40 / 35 / 30; the default 45 is the most conservative. When the score falls below the chosen value, switching is handed back to the system's default policy.

This change only applies at the moment the system reports the score; nothing is written to any configuration, and uninstalling leaves no residue.

> Boundary: more than one reason can trigger a switch, and they share a single exit — the module works on that exit rather than on each reason. There is also one switching path in the system that bypasses the score and is not covered. No switch has ever been observed on that path, **but absence of evidence is not evidence of absence**.

### Background network

**Keep network access while frozen**

When the system freezes a background app, it cuts that app's network too. With this on, selected apps keep network access while frozen; the freezing itself happens as usual.

- Only affects selected apps; with an empty allowlist, the switch affects no app
- Writes nothing to any setting; turning it off restores stock behavior immediately
- This switch plays no part in Google push delivery; what it preserves is the app's own persistent connections surviving a freeze

### Smart Power

**Bypass Smart Power blocking**

Allows the app's FCM broadcasts and messaging service to start.

- Visible on OS3 devices only: OS4 removed SmartPower's blocking decisions, so there is nothing to hook and the section is not rendered on OS4
- Off by default; writes nothing to any setting

### GMS

**GCM reconnect repair**

Triggers a reconnect when the GMS connection has dropped and the next reconnect alarm is overdue.

- Needs Google Play services checked in LSPosed; without that scope the switch has no effect
- Use it together with "Debug logging" on the Settings page when troubleshooting: normal operation logs GCM reconnect repair, and a negative heartbeat logs connect missed
- The hook is installed inside the Google Play services process — the only experimental switch that does not work on the system side

---

## 8. FAQ

### Selected the app, still no push?

Check, in order:

① The module is enabled in LSPosed
② The app actually supports FCM (declaring FCM components does not mean the app sends its notifications through FCM)
③ Use "FCM diagnostics" to check GMS's connection state

### Battery drain went up?

That is the module's inherent cost — it lets push reconnects through and allows apps to be woken.

Selecting only the apps that genuinely need instant push reduces how often apps are woken from the "stopped" state, which is where most of the extra drain lives.

But this is not about turning the module off for other apps: GMS and push broadcasts are protected regardless.

### How is "show supported apps" determined?

Four push-related declarations in an app's manifest; the judgment is based on them:

- ② declares the receiver class `com.google.firebase.iid.FirebaseInstanceIdReceiver`
- ③ declares the broadcast action `com.google.firebase.MESSAGING_EVENT`
- ④ declares the broadcast action `com.google.android.c2dm.intent.RECEIVE`
- ① declares the service class `com.google.firebase.messaging.FirebaseMessagingService`

The rule: **any two of ②③④ qualify the app; only ② or only ③ also qualifies; only ① or only ④ does not.**

Why ② is not required: push SDKs let apps declare their own receiver to catch the `RECEIVE` broadcast without subclassing the SDK's receiver class — Google Play services itself does exactly that. So "no ②" is not an exclusion criterion. ① alone does not qualify because it is the SDK's public base class, carried even by apps that only integrated the library without wiring up push. ④ alone does not qualify because components from the early C2DM era still carry that action.

### What counts as a MiPush app?

The app's manifest declares `com.xiaomi.push.service.XMPushService` (disabled components included).

> MiPush is a system-level push: once an app registers, the system takes over delivery and disables the app's own `XMPushService`. So **that component being disabled is precisely the sign MiPush is working**.

### After a push wakes the app, does it stay alive?

The module only handles delivery and does nothing after the fact. A woken app usually becomes a cached process, later reclaimed or frozen by greeze.

That does not affect the next push — the next one cold-starts the app again; measured cold start to receiver execution is about 0.5 seconds.

### Why does FCM diagnostics always show disconnects?

When GMS is (re)written into the no-restrict list, the module proactively asks it to re-establish the push connection; the experimental "GCM reconnect repair" (off by default), once enabled, also nudges a reconnect when the connection has dropped and the next reconnect alarm is overdue.

The reason: a connection can be dead without anyone knowing. When the system restricts networking it cuts the underlying socket directly, and GMS only notices at the next heartbeat timeout — during that window pushes are not actually arriving, yet diagnostics still show "Connected".

Nudging it once moves the discovery from "whenever the next heartbeat fires" to "right now", and the dead connection is replaced immediately.

So with the module enabled, the connection log will be more fragmented and longer than before — expected. What to actually watch: does it reconnect promptly after a drop? Back to Connected within seconds is normal; stuck disconnected for a long time is what deserves investigation.

---

## 9. Known limitations

These are the module's real behaviors, listed to prevent misreading:

- **Whether a Google push broadcast gets deferred and whether it can be delivered are decided by the same check.** That check reads the allowlist, so for unselected apps the decision is handed back to the system — behavior identical to running without the module.
- **"No network restrictions" depends on the international policy implementation.** Real-world testing on HyperOS 4 China builds shows the domestic policy runs (that branch never participates), so on such ROMs the narrowing happens in two places: allowing push broadcasts through, and protection from force-stop.
- **Unselected apps are exposed to being force-stopped.** Force-stop protection only applies to selected apps: swiping an unselected app away from Recents really does force-stop it and mark it "stopped"; the app has to be reopened once for push to resume.
- **Part of the GMS protection is written as device-wide policy tables** (power-saving profiles, process allowlist, no-restrict list). They apply to the whole device and are never narrowed by selection.
- **Sleep mode cuts the network device-wide.** On entering sleep the system turns off Wi-Fi and mobile data outright, without per-app handling — there is no "let only Google push through" option; the Sleep mode keep-alive switches on the Settings page can only keep Wi-Fi, or mobile data along with it, and are off by default.
- **Push delays during Doze are not touched.** During doze, non-high-priority pushes are held on Google's servers and delivered in a batch when doze ends, and apps are not added to battery optimization's allowlist automatically — stock Android does the same, and the module deliberately preserves it. The module's only doze-layer action is making sure vendor policy does not cut Google services' network on top.
- **Target apps are not added to the freeze-exempt set.** After being woken by a push they become cached processes, later reclaimed or frozen — that is normal and does not affect the next push.
- **The alarm gate only intervenes when the ROM judges "blocked".** Real-device testing shows the gate does see GMS's alarms, but no case of the ROM refusing a GMS alarm was ever observed — in normal states this change alters nothing and only matters if the ROM blocks GMS's alarms (reconnects, heartbeats).
- **The list write-back proactively asks GMS to reconnect.** It sends reconnect and heartbeat broadcasts to GMS and GSF, prompting GMS to verify the connection and rebuild it if needed — hence more connection entries in FCM diagnostics than with the module off. More entries does not mean a worse connection; the criterion is whether it returns to Connected quickly after a drop.
- **The module has no keep-alive capability.** It does not stop the system from reclaiming app processes, and it holds no persistent background work of its own.

---

## 10. Privacy and permissions

| Permission | What it is for |
|---|---|
| `android.permission.QUERY_ALL_PACKAGES` | Reads installed apps locally |
| `com.android.permission.GET_INSTALLED_APPS` | Needed for a full app list on HyperOS; without it the list may be incomplete |
| `android.permission.INTERNET` | Used only to check for updates on GitHub |

Privacy commitment: no personal data is collected or uploaded; the allowlist and settings stay on this device; no analytics, ads or tracking SDKs; the code is open source and auditable.

---

## 11. Troubleshooting and feedback

### Confirm the module is loaded

On every successful load the module leaves one summary line in the log:

```
HyperFCMLive active in <host>: N hook(s) installed, M target(s) absent
```

View it with (`N` = hooks installed, `M` = targets absent on the current ROM):

```bash
adb logcat -d | grep "HyperFCMLive active in"
```

> Enlarge the log buffer in Developer options, or early-boot entries may be missing. Wait for `sys.boot_completed=1` before capturing.
> The `/sdcard/Download/.../log/modules_*.log` files LSPosed exports are more complete than a logcat snapshot — prefer them.

### Feedback

- Source: <https://github.com/iamqwert/HyperOS_FCM_Live>
- When filing an issue, please attach: device model, system version (Android / HyperOS), module version, and the summary line above
- Released under the GNU GPL-3.0 license
