# HyperGreeze (HyperOS FCM Live)

**English** · [简体中文](README.md)

Removes HyperOS 3 / 4 restrictions on Firebase Cloud Messaging (FCM) broadcasts and GMS, keeping push notifications reliable in the background.

> Developed with AI assistance.

[User Guide](HELP_EN.md) · [Technical Docs (Chinese)](FCM_HOOK_TECH_DOC.md) · [Changelog](CHANGELOG.md) · [Downloads](https://github.com/iamqwert/HyperOS_FCM_Live/releases)

---

## What It Does

HyperOS power-saving and freezing policies defer or block background broadcasts. Because FCM relies on Google Play services (GMS) broadcasting a wake-up intent to the target app, blocking that broadcast delays or drops notifications.

This module lifts those gates at the framework level (`system` and `com.miui.powerkeeper`), keeping GMS online and unfrozen while allowing allowlisted apps to be woken by pushes even when stopped or frozen.

- **No app keep-alive**: after delivery, apps return to normal system reclamation and freezing
- **No GMS injection**: hooks only system hosts, avoiding GMS integrity checks
- **Trade-off**: allowing push wake-ups may slightly increase battery usage

---

## Quick Start

### Requirements

- **OS**: HyperOS 3 / 4 (Android 15+)
- **Framework**: LSPosed (API ≥ 101; API ≥ 102 recommended for automatic hot-reload)
- **Services**: Working Google Play services (GMS)

### Installation

1. Download and install the APK from [Releases](https://github.com/iamqwert/HyperOS_FCM_Live/releases)
2. Enable the module in LSPosed Manager and select scope: **System Framework (`system`)** and **Battery and Performance (`com.miui.powerkeeper`)**
3. Wait for automatic hot-reload (API 102+); if it does not trigger, reboot manually
4. Open the module and select the apps that need FCM push wake-ups (GMS is always protected automatically)
5. Open the **Records** tab to confirm GMS shows Connected and hooks are installed

See the [User Guide (HELP_EN.md)](HELP_EN.md) for detailed configuration and troubleshooting.

---

## Highlights

| Area                         | What it provides                                                                                                                                      |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| **FCM wake-up allowlist**    | Lifts push restrictions only for selected apps                                                                                                        |
| **Always-on GMS protection** | Keeps GMS in the no-restrict list, sets its battery saver policy to Unrestricted, skips fast freeze, and lets reconnect broadcasts/alarms through     |
| **Records & diagnostics**    | View live FCM status and six event categories without root or shell; export a full diagnostic report (including hook-install summary) in one tap      |
| **Everyday options**         | Sleep-mode Wi-Fi / mobile data keep-alive, block resetting WeChat to battery-unoptimized, config backup/restore, theme settings, hide launcher icon   |
| **Experimental switches**    | All off by default, take effect immediately: allow app starts, stay connected on weak Wi-Fi, keep network while frozen, Smart Power bypass (OS3 only) |
| **Privacy & permissions**    | `INTERNET` is used solely to check for updates                                                                                                        |

---

## Compatibility & Key Boundaries

Verified on real devices:

| Device            | System                       |
| ----------------- | ---------------------------- |
| Xiaomi 17 Pro Max | HyperOS 3.0.319 / Android 16 |
| REDMI K90 Pro Max | HyperOS 4.0.6.0 / Android 17 |

Good to know before using:

- **Unselected apps stay untouched**: unselected apps keep stock behavior and are force-stopped when swiped from Recents
- **No keep-alive**: apps woken by push may be frozen or reclaimed afterward; this is normal and does not affect the next push
- **Sleep mode is untouched by default**: HyperOS cuts networking device-wide during sleep; enable the Sleep mode options in Settings if you want overnight connectivity
- **Proxies are outside module control**: if you use a VPN or proxy, set the `mtalk` domains to direct per [FCM's network configuration docs](https://firebase.google.com/docs/cloud-messaging/network-configuration) (see the [User Guide](HELP_EN.md)). See [Configure your Network for FCM](https://firebase.google.com/docs/cloud-messaging/network-configuration)

For hook details and ROM internals, see [Technical Docs (FCM_HOOK_TECH_DOC.md)](FCM_HOOK_TECH_DOC.md).

---

## Acknowledgements

This fork builds on and references the work of:

- [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)
- [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live)
- [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer)
- [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- [Kr328/HyperOSFCMFix](https://github.com/Kr328/HyperOSFCMFix)
- [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)
- [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- [zuohl/HyperOS_FCM_Live](https://github.com/zuohl/HyperOS_FCM_Live)
- 250king ([PR #1](https://github.com/250king/HyperOS_FCM_Live/pull/1), original app-list permission approach, later replaced by package visibility)

---

## License

Released under the [GNU GPL-3.0](LICENSE) license.
