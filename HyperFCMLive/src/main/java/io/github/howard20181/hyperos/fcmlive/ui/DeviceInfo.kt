package io.github.howard20181.hyperos.fcmlive.ui

import android.os.Build

/**
 * The environment rows the app can state about itself without any permission.
 *
 * These moved out of `ModuleStatusScreen` (which still shows them) so the
 * diagnostics export can carry the same three strings: a report without the
 * device and the build answers "push stopped working" with nothing to compare
 * against, and re-deriving them at the export site would let the two surfaces
 * drift (a prop fallback fixed in one place but not the other).
 *
 * Everything here is a system property read through reflection, which is open
 * to every process — no root, no shell, no permission.
 */

/**
 * The device's market name — "REDMI K90 Pro Max", not the codename-model
 * `Build.MODEL` carries on many devices. The market name lives in a prop only
 * vendor builds set; a device without it falls back to `Build.MODEL` rather
 * than an empty row.
 */
internal fun deviceName(): String =
    systemProperty("ro.product.marketname")
        .ifEmpty { systemProperty("ro.vendor.product.marketname") }
        .ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

/**
 * The row states the build, not the generation: `4.0.2.0.XPMCNXM`, the same
 * string Settings shows under 系统版本.
 *
 * `ro.mi.os.version.name` is only the generation — a real device answers
 * `OS4.0` — so the full version lives in the incremental props, which carry the
 * `OS` prefix that the row drops (`ro.mi.os.version.incremental` and
 * `ro.build.version.incremental` agree on HyperOS; the MIUI-era name is the
 * last stand for a device that has neither). The `OS` strip happens once, on
 * whichever property answered, so every fallback lands in the same shape.
 */
internal fun osVersion(): String =
    systemProperty("ro.mi.os.version.incremental")
        .ifEmpty { systemProperty("ro.build.version.incremental") }
        .ifEmpty { systemProperty("ro.mi.os.version.name") }
        .ifEmpty { systemProperty("ro.miui.ui.version.name") }
        .removePrefix("OS")
        .ifEmpty { "Android ${Build.VERSION.RELEASE}" }

internal fun androidVersion(): String =
    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

/** SystemProperties.get via reflection: hidden from the SDK, open to every process. */
internal fun systemProperty(key: String): String = try {
    val get = Class.forName("android.os.SystemProperties")
        .getDeclaredMethod("get", String::class.java)
    (get.invoke(null, key) as? String)?.trim().orEmpty()
} catch (ignored: Throwable) {
    ""
}

/**
 * 本机是否为 OS3 世代。
 *
 * 读 `ro.mi.os.version.name`（真机 OS4 实测返回 `OS4.0.0.33.XPMCNXM`），
 * `OS3` 前缀才算命中；MIUI 时代该属性不存在，同样视为非 OS3。反射读
 * SystemProperties：该类在 SDK 里不可见，但 `get(String)` 对所有进程开放。
 *
 * 这个判断只管 UI 显隐与配置备份的可用性，hook 侧从不做世代假设——目标方法
 * 在不在决定装不装（见 SmartPowerHooks 的文档）。两个调用方共用这一份，
 * 避免"页面隐藏了开关、备份却仍在恢复它"这类漂移。
 */
internal fun isOs3Generation(): Boolean =
    systemProperty("ro.mi.os.version.name").startsWith("OS3")
