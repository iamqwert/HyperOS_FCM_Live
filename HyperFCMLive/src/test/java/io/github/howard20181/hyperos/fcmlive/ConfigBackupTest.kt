package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the config file's shape — the one part of the backup that has to stay
 * readable by builds other than the one that wrote it.
 *
 * Two contracts are load-bearing here and neither is visible at the call site:
 *
 *  - settings ride `#` comment lines, so a build from before this format skips
 *    them and imports the package list alone. Written any other way those lines
 *    would arrive as package names and be pushed to system_server as
 *    allowlist entries;
 *  - a bare line is a package only if it *is* a package name, because the file
 *    is meant to be hand-edited.
 *
 * [ConfigBackup.parse] takes no context for this reason: the format can be
 * checked on the JVM, without an emulator.
 */
class ConfigBackupTest {

    @Test
    fun `a pre-format allowlist still reads as packages`() {
        val parsed = ConfigBackup.parse(
            """
            com.tencent.mm
            com.google.android.gms
            """.trimIndent()
        )
        assertEquals(setOf("com.tencent.mm", "com.google.android.gms"), parsed.packages)
        assertTrue(parsed.values.isEmpty())
    }

    @Test
    fun `settings and packages are separated`() {
        val parsed = ConfigBackup.parse(
            """
            # fcmlive-backup 2
            # Module switches
            # set sleep_keepalive=true
            # set wifi_weak_signal_floor=40
            # Apps
            com.tencent.mm
            """.trimIndent()
        )
        assertEquals(
            mapOf("sleep_keepalive" to "true", "wifi_weak_signal_floor" to "40"),
            parsed.values
        )
        assertEquals(setOf("com.tencent.mm"), parsed.packages)
    }

    @Test
    fun `a bare key-value line is not a package`() {
        // The failure this prevents: a build that predates the format reads the
        // package list line by line, so anything uncommented here would become
        // an allowlist entry. `sleep_keepalive=true` is not a package name.
        val parsed = ConfigBackup.parse("sleep_keepalive=true\ncom.tencent.mm")
        assertTrue(parsed.values.isEmpty())
        assertEquals(setOf("com.tencent.mm"), parsed.packages)
    }

    @Test
    fun `prose and stray text are not packages`() {
        val parsed = ConfigBackup.parse(
            """
            # this is a note
            com.tencent.mm
            some free text
            com.google.android.gms!
            """.trimIndent()
        )
        assertEquals(setOf("com.tencent.mm"), parsed.packages)
    }

    @Test
    fun `a byte-order mark does not eat the first line`() {
        val parsed = ConfigBackup.parse("\uFEFFcom.tencent.mm\ncom.google.android.gms")
        assertEquals(setOf("com.tencent.mm", "com.google.android.gms"), parsed.packages)
    }

    @Test
    fun `values keep everything after the first equals sign`() {
        val parsed = ConfigBackup.parse("# set seed_color=-16777216")
        assertEquals("-16777216", parsed.values["seed_color"])
    }

    @Test
    fun `a setting without a key or a value is dropped`() {
        val parsed = ConfigBackup.parse(
            """
            # set =true
            # set novalue
            # set empty=
            """.trimIndent()
        )
        assertFalse(parsed.values.containsKey(""))
        assertFalse(parsed.values.containsKey("novalue"))
        assertEquals("", parsed.values["empty"])
    }

    @Test
    fun `an empty file holds nothing`() {
        val parsed = ConfigBackup.parse("")
        assertTrue(parsed.packages.isEmpty())
        assertTrue(parsed.values.isEmpty())
    }
}
