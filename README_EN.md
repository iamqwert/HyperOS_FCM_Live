# HyperOS FCM Live (HyperGreeze)

**English** · [简体中文](README.md)

Removes HyperOS restrictions on broadcasts related to Google push connections, keeping FCM push notifications working on Xiaomi devices.

**This module is built with AI assistance**

[User guide](HELP_EN.md) · [Technical docs (Chinese)](HOOKS_AND_DIAGNOSTICS.md) · [Changelog (Chinese)](CHANGELOG.md) · [Downloads](https://github.com/iamqwert/HyperOS_FCM_Live/releases)

---

## Overview

HyperOS's power-saving policies restrict broadcasts and networking for background apps. Google push (FCM) relies on Google Play services to broadcast a wake-up call to the target app; once that broadcast is deferred or blocked, notifications stop arriving.

This module rewrites those checks at the framework level (`system_server` and `com.miui.powerkeeper`): it lets Google push and reconnect broadcasts through, keeps GMS from being cut off from the network, frozen or blacklisted, grants the target app a roughly 2-second temporary battery exemption on each push (allowlist-based), and lets the app receive the broadcast even in the "stopped" state.

It does **not** keep FCM apps alive, does **not** stop the system from reclaiming their processes, and does **not** touch GMS's push-handling logic.

> The trade-off: allowing push reconnects means apps can be woken by pushes, and battery use may go up with the module enabled.

---

## Features

| Category | What it does |
|---|---|
| Push fix | Lets c2dm delivery and reconnect broadcasts through, re-adds `FLAG_INCLUDE_STOPPED_PACKAGES`, passes the greeze autostart and freeze gates, ~2 s battery exemption on delivery |
| GMS protection | Adds GMS to the no-restrict list, forces its power-saving profile to "Unrestricted", skips fast freezing, turns off network restrictions; whether the network is cut during sleep mode is left to the system by default |
| Allowlist | The module's only narrowing mechanism: decides which apps get the "woken by push" privileges; multi-select batch add/remove, search, select all |
| List filters | Only apps that declare push components (Android 11+ package visibility, no permission needed), exclude MiPush apps (on by default), show system apps |
| Tools | Opens GMS's built-in diagnostics screen, allowlist export/import, update check |
| Display & appearance | Language (Simplified Chinese / English), theme mode, dynamic color, palette style, color spec, hide launcher icon |
| Experimental | Three-tier chained autostart release, weak-signal switching relief, network while frozen, Smart Power (OS3 only); WeChat battery optimization and sleep keep-alive live on the Settings page |
| Entries | Shortcuts to Settings, Help and FCM diagnostics |

> The "Help" entry opens the online guide ([HELP_EN.md](HELP_EN.md)) in a browser; there is no in-app help page.

---

## How it works

The delivery chain: GMS receives the FCM message → sends the `com.google.android.c2dm.intent.RECEIVE` broadcast → the target app's receiver runs.

HyperOS layers several gates on this chain; the module handles them gate by gate:

| Stage | System-side gate | What the module does |
|---|---|---|
| Broadcast delivery | `GreezeManagerService#isAllowBroadcast` | Allowlist-based; unselected apps fall back to the system's own decision |
| App is "stopped" | "stopped" package filter | Re-adds `FLAG_INCLUDE_STOPPED_PACKAGES` |
| Autostart check | `BroadcastQueueModernStubImpl#checkApplicationAutoStart` | Allowed for allowlisted apps |
| App is frozen | `isRestrictReceiver` (greeze broadcast gate) | Let through, replaying the stock unfreeze |
| Power-saving profile / standby | `AppStandbyController#setUidState` | Rewritten to allow |
| Freeze policy | `AurogonImmobulusMode#isNoRestrictApp` and friends | Exemption returned for GMS |
| Broadcast deferred | `isNeedCachedBroadcast` (greeze broadcast cache) | Allowed for allowlisted apps |
| Reconnect/heartbeat deferred | `deferBroadcastForMiui` | Only GMS reconnect-type intents are answered; nothing else is let through |
| Force stop | `isForceStopEnable` | Applies to selected apps only |

The hooked hosts: the mainline hooks live in `system` (the framework) and `com.miui.powerkeeper`. The module does not inject into the `com.google.android.gms` process — the former "GCM reconnect repair" experiment was removed in 4.1.1 after GMS hot updates tightened their integrity detection.

The four GMS-side protections:

- **P1** — keeps GMS on the `Settings.System.MILLET_NO_RESTRICT_APP` list
- **P2** — backstops the greeze freeze path in system_server
- **P3** — forces GMS's compiled standby bucket to "Unrestricted" (8) instead of 0
- **P4** — when GMS is found frozen, asks GMS / GSF to rebuild the connection and unfreezes it

---

## Installation

### Requirements

- HyperOS 3 / 4
- LSPosed (API ≥ 101)
- Google Play services available

### Steps

1. Download the APK from [Releases](https://github.com/iamqwert/HyperOS_FCM_Live/releases) and install it
2. Enable the module in LSPosed and check its scope
3. Wait for the scope to hot-reload (requires an LSPosed build with API 102+); if that fails or no notification shows up, reboot manually to apply
4. Open the module's allowlist

See [HELP_EN.md](HELP_EN.md) for details.

---

## Compatibility

Verified devices:

| Device | System |
|---|---|
| Xiaomi 17 Pro Max | OS3.0.319 / Android 16 |
| REDMI K90 Pro Max | OS4.0.33 / Android 17 |

---

## Known limitations

- **Whether c2dm gets deferred and whether it can be delivered are decided by the same check.** That check reads the allowlist, so for unselected apps the decision is handed back to the system — behavior identical to running without the module.
- **The narrowing nominally covers three points, one of which ("no network restrictions") depends on the international policy implementation.** Real-world testing on HyperOS 4 China builds shows the domestic policy runs and that point never participates, so in practice the narrowing happens in two places.
- **Unselected apps are exposed to being force-stopped.** Force-stop protection only applies to selected apps: swiping an unselected app away from Recents really does force-stop it and mark it "stopped"; the app has to be reopened once for push to resume.
- **Part of the GMS protection is written as device-wide policy tables** (power-saving profiles, process allowlist, no-restrict list). They apply to the whole device and are never narrowed by selection.
- **Sleep mode cuts the network device-wide.** On entering sleep, the system turns off Wi-Fi and mobile data outright, without per-app handling; to stay connected overnight, enable the keep-alive options under "Sleep mode" on the Settings page — keep Wi-Fi, keep mobile data, or both.
- **Target apps are not added to the freeze-exempt set.** After being woken by a push they become cached processes and may later be reclaimed or frozen — that is normal and does not affect the next push.
- **Under heavy memory pressure, memory cleanup may drop GMS briefly** — about 3 seconds; it reconnects immediately.

---

## Acknowledgements

This project is a fork; thanks to these projects and contributors:

- [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)
- [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live)
- [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer)
- [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- [Kr328/HyperOSFCMFix](https://github.com/Kr328/HyperOSFCMFix)
- [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)
- [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- [zuohl/HyperOS_FCM_Live](https://github.com/zuohl/HyperOS_FCM_Live)
- The original idea for requesting the `GET_INSTALLED_APPS` runtime permission came from 250king's [PR #1](https://github.com/250king/HyperOS_FCM_Live/pull/1); that permission has since been removed when the app list moved to the package-visibility approach

---

## License

Released under the [GNU GPL-3.0](LICENSE) license.
