package io.github.iamqwert.hypergreeze.mcu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the vendored Material Color Utilities math.
 * Golden-style checks: lock HCT round-trips and scheme roles so refactors
 * cannot silently drift the palette.
 */
class McuColorTest {

    @Test
    fun hctFromIntRoundTripsBaselinePurple() {
        val argb = 0xFF6750A4.toInt()
        val hct = Hct.fromInt(argb)
        // Material baseline purple: purple hue, mid tone
        assertTrue("hue≈299 was ${hct.hue}", hct.hue > 280 && hct.hue < 320)
        assertTrue("chroma>20 was ${hct.chroma}", hct.chroma > 20)
        assertTrue("tone≈40 was ${hct.tone}", hct.tone > 30 && hct.tone < 50)
        assertEquals(argb, hct.toInt())
    }

    @Test
    fun hctFromSolvesToStableArgb() {
        val hct = Hct.from(298.0, 48.0, 40.0)
        val again = Hct.from(298.0, 48.0, 40.0)
        assertEquals(hct.toInt(), again.toInt())
        assertTrue(hct.hue > 280 && hct.hue < 320)
    }

    @Test
    fun schemeLightPrimaryIsReadableOnSurface() {
        val scheme = Scheme.create(
            0xFF6750A4.toInt(),
            Scheme.Variant.TONAL_SPOT,
            false,
            Scheme.Spec.SPEC_2025
        )
        // Light theme: primary is a mid/dark accent, surface is near-white
        assertTrue("surface bright", (scheme.surface ushr 16 and 0xFF) > 200)
        assertTrue("primary not white", (scheme.primary ushr 16 and 0xFF) < 200)
        assertTrue("onPrimary defined", scheme.onPrimary != 0)
    }

    @Test
    fun schemeDarkSurfaceIsNearBlack() {
        val scheme = Scheme.create(
            0xFF6750A4.toInt(),
            Scheme.Variant.TONAL_SPOT,
            true,
            Scheme.Spec.SPEC_2021
        )
        val r = scheme.surface ushr 16 and 0xFF
        val g = scheme.surface ushr 8 and 0xFF
        val b = scheme.surface and 0xFF
        assertTrue("dark surface should be dim, was $scheme.surface", r < 80 && g < 80 && b < 80)
    }

    @Test
    fun tonalPaletteToneIsMonotonic() {
        val p = TonalPalette.fromHueAndChroma(280.0, 40.0)
        val t40 = p.tone(40.0)
        val t80 = p.tone(80.0)
        // Higher tone is lighter (higher average RGB)
        fun lum(c: Int) = ((c ushr 16 and 0xFF) + (c ushr 8 and 0xFF) + (c and 0xFF))
        assertTrue("t80 should be lighter than t40", lum(t80) > lum(t40))
    }

    @Test
    fun yellowTone99UsesAveragedArgb() {
        // Hue in yellow band (105..125): tone 99 special-cases to average of 98/100
        val p = TonalPalette.fromHueAndChroma(115.0, 20.0)
        val t99 = p.tone(99.0)
        val t98 = p.tone(98.0)
        val t100 = p.tone(100.0)
        fun ch(c: Int) = (c and 0xFF)
        // Blue channel of 99 is roughly the average of 98 and 100
        val expected = ((ch(t98) + ch(t100)) / 2f).toInt()
        assertTrue(
            "blue t99≈avg was ${ch(t99)} expected≈$expected",
            Math.abs(ch(t99) - expected) <= 1
        )
    }
}
