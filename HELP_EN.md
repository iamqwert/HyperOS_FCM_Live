# HyperGreeze User Guide

**English** · [简体中文](HELP.md)

This guide covers setup, key options, and fast troubleshooting. For framework hook internals, see the [Technical Docs (Chinese)](FCM_HOOK_TECH_DOC.md).

---

## 1. Quick Start

1. **Install the module**: download and install the APK from [Releases](https://github.com/iamqwert/HyperGreeze/releases).
2. **Enable and select scope**: in LSPosed Manager, enable the module and select **System Framework (`system`)** and **Battery and Performance (`com.miui.powerkeeper`)**.
3. **Apply**: LSPosed builds supporting API 102 hot-reload automatically; older builds require a manual reboot.
4. **Select apps**: open the module and select the apps that need timely FCM push notifications.
5. **Verify**: switch to the **Records** tab and confirm GMS shows Connected and hooks are installed in the exported summary.

---

## 2. FCM Wake-up Allowlist

The allowlist is the module's **only** narrowing control — there is no "allow all" master switch. After a fresh install, the allowlist is empty and only Google Play services (GMS) itself is protected.

### What selected apps get

Only **selected** apps have these restrictions lifted:

- FCM push broadcasts pass the system autostart check
- Apps in the "stopped" state can still be woken by push broadcasts
- Frozen apps receive push broadcasts directly with stock unfreeze behavior
- Swiping away from Recents does not force-stop them (apps declaring FCM components only)

Unselected apps keep stock system behavior: swiping them from Recents force-stops them, and they generally need to be reopened once for pushes to resume.

### Why is Google Play services greyed out with a lock icon?

GMS and push reconnects are **always protected**, regardless of allowlist selection. The card is locked and excluded from Select All / batch actions because toggling it would make no difference.

### Why does the list show only some apps?

- The module uses Android 11+ package visibility (`<queries>`) to see only push-capable apps — **no installed-apps permission is required**.
- **Exclude MiPush apps** is on by default (apps using Xiaomi's system push usually do not rely on FCM). You can change filters or show system apps from the top-right menu.
- Long-press an app card (or tap **Batch Actions** in the MIUIX style) to add or remove multiple apps at once.

---

## 3. Records & Diagnostics

The **Records** tab reads GMS's own FCM diagnostics directly from the system process — **no root or shell needed** (the module must be active in LSPosed):

- **Status tab**: displays live Connection / Network / Heartbeat / Push / Errors rows. Rows appear only when data exists, so a missing row is normal.
- **Events tab**: filters event history into All / Connection / Heartbeat / Push / Sleep / Errors. Keeps 500 rows or 7 days by default (configurable in Settings).
- **Export diagnostic text**: tap the upload icon in the top bar to save a `.txt` report containing device info, OS/module versions, **hook-install summary**, current status, and event history.

---

## 4. Settings & Experimental Features

> Three lists are distinct throughout this guide: **this app's "FCM wake-up allowlist"**, the Security app's **"battery saver policy"**, and AOSP **"battery optimization"**.

### Everyday settings

- **Sleep mode (Keep Wi-Fi / Keep mobile data)**: off by default. HyperOS cuts Wi-Fi and mobile data device-wide when sleep mode is entered or exited, without per-app control. Enable Wi-Fi or both if you need overnight push delivery (increases overnight battery use).
- **Stop the system from putting WeChat back on the battery-unoptimized list**: HyperOS repeatedly resets WeChat to "Not optimized" on power-mode changes, bypassing the normal cached-process FCM path. Turn this switch on first, then tap **AOSP App Battery Usage** below to set WeChat to **Optimized** manually.
- **Backup & restore**: export or import your allowlist and switch settings as a plain text file.
- **Hide launcher icon**: once hidden, open the module from LSPosed Manager. When visible, long-press the icon for shortcuts (Settings, Help, FCM diagnostics).

### Experimental features (Settings → Experiments & Debugging)

All switches are **off by default** and take effect immediately without rebooting or reloading:

| Switch | What it does | Notes |
| --- | --- | --- |
| **Allow apps to be started** | Lifts runtime service-start autostart checks for selected apps | Writes nothing to system settings; for permanent autostart, use **Autostart manager** directly |
| **Stay connected on weak Wi-Fi** | Reduces automatic switching to mobile data on weak Wi-Fi (thresholds: `45 / 40 / 35 / 30`) | Keeps you on poor Wi-Fi; **does not improve Wi-Fi quality** |
| **Keep network access while frozen** | Keeps selected apps' network open while frozen | Preserves the app's own connections; plays no role in FCM delivery |
| **Bypass Smart Power blocking** | Allows FCM broadcasts and messaging service starts | **HyperOS 3 only** (removed in HyperOS 4) |

---

## 5. Troubleshooting & FAQ

### Selected the app, still no push?

Check these four items in order:

1. **Check the Records tab**: confirm the module is active, hooks are installed, and GMS shows **Connected**.
2. **Check VPN / proxy rules**: intercepting FCM domains can drop or delay the long-lived connection, while blindly bypassing all of `com.google.android.gms` or entire port ranges can break other Google services (such as Google Search) in Mainland China. Follow [Firebase's network configuration guide](https://firebase.google.com/docs/cloud-messaging/network-configuration) and route these domains **direct** in your proxy client (toggle Airplane mode once afterward so the socket reconnects):
   - **Mainland China**:
     - `mtalk.google.com`, `mtalk4.google.com`, `mtalk-staging.google.com`, `mtalk-dev.google.com`
     - `alt1-mtalk.google.com` through `alt8-mtalk.google.com`
   - **Other regions with unrestricted internet**: ensuring direct access on TCP ports `5228`, `5229`, `5230`, and `443` is sufficient.
   - The module has no root or `iptables` access and cannot see or change proxy routing.
3. **Verify the app actually uses FCM**: declaring FCM components does not guarantee the server sends notifications via FCM.
4. **Confirm the app is selected**: unselected apps swiped from Recents are force-stopped until reopened.

### Why are there more disconnect entries in Records / FCM Diagnostics?

When the system restricts networking, the underlying socket can die silently until GMS's next heartbeat times out. When rewriting GMS into the no-restrict list, the module nudges GMS to verify and rebuild the connection early. More entries are **expected** — what matters is returning to **Connected** within seconds. Only prolonged disconnection indicates a network or proxy issue.

### Do apps stay alive after a push? How much extra battery is used?

The module **never keeps apps alive**. After delivery, the app becomes a cached process and is reclaimed or frozen normally without hurting the next push (cold-start delivery takes ~0.5 s in testing). Extra battery drain comes mainly from push wake-ups, so select only apps that genuinely need instant notifications.

---

## 6. Known Boundaries & Privacy

### Known boundaries

- **Doze batching is untouched**: during Doze, non-high-priority pushes are held on Google's servers and delivered in batches afterward — stock Android behavior preserved intentionally.
- **Brief drops under heavy memory pressure**: aggressive memory cleanup may briefly disconnect GMS (~3 s) before it reconnects automatically.
- **No keep-alive or proxy control**: no persistent background service, no `su`, and no `iptables` access.

### Privacy & permissions

The module requests **no sensitive permissions**, collects or uploads no personal data, stores everything locally, and includes no analytics or ad SDKs:

| Declaration | Purpose |
| --- | --- |
| `<queries>` (manifest declaration, not a permission) | Android 11+ package visibility — exposes only push-capable apps |
| `android.permission.INTERNET` | Used solely to check for module updates on GitHub |

---

## 7. Reporting Issues

Grab a **diagnostic report** before opening an issue:

- **Preferred**: open the **Records** tab → tap the top-right upload icon to export the diagnostic text (includes the `HyperGreeze active in ...` hook summary and event history).
- **Fallback (if the module is not responding)**: enable **Show debug logs** under Settings → Experiments & Debugging, wait for boot to finish, and run:
  ```bash
  adb logcat -d | grep "HyperGreeze active in"
  ```
  Or attach `modules_*.log` exported from LSPosed Manager.

Open an issue using the template on [GitHub Issues](https://github.com/iamqwert/HyperGreeze/issues) and attach the exported report.
