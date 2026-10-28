package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cross-check between the manifest scan and GMS's own client-queue
 * list.
 *
 * The value of this comparison is entirely in **which side** a package falls
 * on — "declared but never registered" is a push that cannot arrive, while
 * "registered but no longer declared" is usually uninstall residue. A test that
 * only checked the count would let a later refactor swap the two lists without
 * failing, so each direction is asserted by name.
 */
class PushHealthTest {

    @Test
    fun `agreement produces no rows`() {
        val diff = PushHealth.compare(
            setOf("com.a", "com.b"),
            listOf("com.b", "com.a")
        )
        assertFalse(diff.hasDrift)
        assertEquals(2, diff.agreed)
        assertEquals(emptyList<String>(), diff.declaredOnly)
        assertEquals(emptyList<String>(), diff.registeredOnly)
    }

    @Test
    fun `declared but not registered is reported on the declared side`() {
        val diff = PushHealth.compare(
            setOf("com.a", "com.b"),
            listOf("com.a")
        )
        assertEquals(listOf("com.b"), diff.declaredOnly)
        assertEquals(emptyList<String>(), diff.registeredOnly)
        assertEquals(1, diff.agreed)
        assertTrue(diff.hasDrift)
    }

    @Test
    fun `registered but not declared is reported on the registered side`() {
        val diff = PushHealth.compare(
            setOf("com.a"),
            listOf("com.a", "com.gone")
        )
        assertEquals(emptyList<String>(), diff.declaredOnly)
        assertEquals(listOf("com.gone"), diff.registeredOnly)
        assertTrue(diff.hasDrift)
    }

    @Test
    fun `both directions at once`() {
        val diff = PushHealth.compare(
            setOf("com.a", "com.declared"),
            listOf("com.a", "com.residue")
        )
        assertEquals(listOf("com.declared"), diff.declaredOnly)
        assertEquals(listOf("com.residue"), diff.registeredOnly)
        assertEquals(1, diff.agreed)
    }

    @Test
    fun `an empty registered list means everything declared is unregistered`() {
        // GMS naming nobody is the honest reading of that pair; the caller
        // decides not to render it, but the arithmetic must not pretend
        // agreement.
        val diff = PushHealth.compare(setOf("com.a", "com.b"), emptyList())
        assertEquals(listOf("com.a", "com.b"), diff.declaredOnly)
        assertEquals(0, diff.agreed)
    }

    @Test
    fun `empty on both sides is not drift`() {
        val diff = PushHealth.compare(emptySet(), emptyList())
        assertFalse(diff.hasDrift)
        assertEquals(0, diff.agreed)
    }

    @Test
    fun `output is sorted so the row does not reshuffle between polls`() {
        val diff = PushHealth.compare(
            setOf("com.z", "com.a", "com.m"),
            emptyList()
        )
        assertEquals(listOf("com.a", "com.m", "com.z"), diff.declaredOnly)
    }

    @Test
    fun `a duplicated registered entry is counted once`() {
        val diff = PushHealth.compare(
            setOf("com.a"),
            listOf("com.a", "com.a")
        )
        assertFalse(diff.hasDrift)
        assertEquals(1, diff.agreed)
    }
}
