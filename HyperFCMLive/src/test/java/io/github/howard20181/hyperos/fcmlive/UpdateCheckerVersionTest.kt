package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for update version comparison. No Android runtime required.
 */
class UpdateCheckerVersionTest {

    @Test
    fun equalVersionsCompareZero() {
        assertEquals(0, UpdateChecker.compareVersions("2.3.0.23", "2.3.0.23"))
        assertEquals(0, UpdateChecker.compareVersions("v2.3.0.23", "2.3.0.23"))
    }

    @Test
    fun newerVersionIsGreater() {
        assertTrue(UpdateChecker.compareVersions("2.3.0.24", "2.3.0.23") > 0)
        assertTrue(UpdateChecker.compareVersions("2.4.0.23", "2.3.0.23") > 0)
        assertTrue(UpdateChecker.compareVersions("3.0.0.1", "2.9.9.99") > 0)
    }

    @Test
    fun olderVersionIsLess() {
        assertTrue(UpdateChecker.compareVersions("2.3.0.22", "2.3.0.23") < 0)
        assertTrue(UpdateChecker.compareVersions("2.2.9.99", "2.3.0.1") < 0)
    }

    @Test
    fun missingSegmentsTreatedAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("2.3", "2.3.0.0"))
        assertTrue(UpdateChecker.compareVersions("2.3.1", "2.3") > 0)
    }

    @Test
    fun prereleaseRanksLowerThanRelease() {
        assertTrue(UpdateChecker.compareVersions("1.6.0-rc1", "1.6.0") < 0)
        assertTrue(UpdateChecker.compareVersions("1.6.0", "1.6.0-rc1") > 0)
    }

    @Test
    fun nonNumericSegmentsCompareAsZero() {
        // "abc" parses as 0, so 0.0.0 == 0.abc
        assertEquals(0, UpdateChecker.compareVersions("1.abc.0", "1.0.0"))
    }
}
