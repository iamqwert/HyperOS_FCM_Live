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
