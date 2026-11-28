package io.github.iamqwert.hypergreeze

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [WakeLockGate] and [AlarmGate] using [FakeXposedFramework].
 */
class WakeLockAndAlarmGateTest {

    private val gmsUid = 10135
    private val gmsWorkProfileUid = 1010135
    private val otherUid = 10136
    private val isGms: (Int) -> Boolean = { it == gmsUid || it == gmsWorkProfileUid }

    // ---------- WakeLockGate.shouldExemptPartialWakeLock ----------

    @Test
    fun exemptsPartialWakeLockWhenAllOwnersAreGms() {
        assertTrue(WakeLockGate.shouldExemptPartialWakeLock(1, intArrayOf(gmsUid), isGms))
        assertTrue(
            WakeLockGate.shouldExemptPartialWakeLock(
                0x40000001,
                intArrayOf(gmsUid, gmsWorkProfileUid),
                isGms
            )
        )
    }

    @Test
    fun preservesVendorPolicyForMixedOrNonPartialWakeLocks() {
        assertFalse(WakeLockGate.shouldExemptPartialWakeLock(1, intArrayOf(gmsUid, otherUid), isGms))
        assertFalse(WakeLockGate.shouldExemptPartialWakeLock(6, intArrayOf(gmsUid), isGms))
        assertFalse(WakeLockGate.shouldExemptPartialWakeLock(32, intArrayOf(gmsUid), isGms))
        assertFalse(WakeLockGate.shouldExemptPartialWakeLock(1, intArrayOf(), isGms))
        assertFalse(WakeLockGate.shouldExemptPartialWakeLock(1, null, isGms))
    }

    // ---------- WakeLockGate.hookWakeLockPolicy end-to-end ----------

    class FakeWakeLock(
        @JvmField val mFlags: Int,
        val owners: IntArray
    )

    class FakePowerStub {
        var getRealOwnersCalls: Int = 0
        private fun getRealOwners(lock: FakeWakeLock): IntArray {
            getRealOwnersCalls++
            return lock.owners
        }
        fun isWakelockDisabledByPolicy(lock: FakeWakeLock): Boolean = true
        fun setUidPartialWakeLockDisabledState(uid: Int, tag: String?, disabled: Boolean) {}
        fun setUidPartialWakeLockDisabledState(uid: Int, tag: String?, disabled: Boolean, reason: String?) {}
    }

    @Test
    fun wakeLockPolicyDecisionZeroReflectionOnAllowedAndExemptsOnlyPureGmsPartial() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf(
                "com.android.server.power.PowerManagerServiceImpl" to FakePowerStub::class.java,
                "com.android.server.power.PowerManagerService\$WakeLock" to FakeWakeLock::class.java
            )
        )
        WakeLockGate.hookWakeLockPolicy(fw.env(loader, gmsUid = gmsUid, isGmsUid = isGms))

        val method = FakePowerStub::class.java.getDeclaredMethod(
            "isWakelockDisabledByPolicy", FakeWakeLock::class.java
        )
        val hook = fw.hookFor(FakePowerStub::class.java, "isWakelockDisabledByPolicy", FakeWakeLock::class.java)
        val stub = FakePowerStub()

        // Allowed by ROM (returns false): must NOT invoke getRealOwners (zero reflection cost).
        val allowedChain = FakeChain(method, stub, false, FakeWakeLock(1, intArrayOf(gmsUid)))
        assertEquals(false, hook.intercept(allowedChain))
        assertEquals(1, allowedChain.proceedCount)
        assertEquals(0, stub.getRealOwnersCalls)

        // Denied by ROM, pure GMS partial wakelock -> re-allowed (false).
        val gmsChain = FakeChain(method, stub, true, FakeWakeLock(1, intArrayOf(gmsUid, gmsWorkProfileUid)))
        assertEquals(false, hook.intercept(gmsChain))
        assertEquals(1, gmsChain.proceedCount)
        assertEquals(1, stub.getRealOwnersCalls)

        // Denied by ROM, mixed WorkSource -> stays denied (true).
        val mixedChain = FakeChain(method, stub, true, FakeWakeLock(1, intArrayOf(gmsUid, otherUid)))
        assertEquals(true, hook.intercept(mixedChain))
        assertEquals(2, stub.getRealOwnersCalls)

        // Denied by ROM, non-partial wakelock -> stays denied without calling getRealOwners.
        val screenChain = FakeChain(method, stub, true, FakeWakeLock(26, intArrayOf(gmsUid)))
        assertEquals(true, hook.intercept(screenChain))
        assertEquals(2, stub.getRealOwnersCalls)
    }

    @Test
    fun wakeLockDisableRequestIgnoresGmsDisableAndNeverFlipsToEnable() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf(
                "com.android.server.power.PowerManagerServiceImpl" to FakePowerStub::class.java,
                "com.android.server.power.PowerManagerService\$WakeLock" to FakeWakeLock::class.java
            )
        )
        WakeLockGate.hookWakeLockPolicy(fw.env(loader, gmsUid = gmsUid, isGmsUid = isGms))

        val threeArg = FakePowerStub::class.java.getDeclaredMethod(
            "setUidPartialWakeLockDisabledState",
            Int::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType
        )
        val fourArg = FakePowerStub::class.java.getDeclaredMethod(
            "setUidPartialWakeLockDisabledState",
            Int::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType, String::class.java
        )
        val stub = FakePowerStub()

        for (method in listOf(threeArg, fourArg)) {
            val hook = fw.hooks.getValue(method)
            val isFour = method.parameterCount == 4

            // GMS + disabled=true -> ignored (proceedCount == 0).
            val gmsDisableArgs = if (isFour) arrayOf<Any?>(gmsUid, "GCM", true, "vendor") else arrayOf<Any?>(gmsUid, "GCM", true)
            val gmsDisableChain = FakeChain(method, stub, null, *gmsDisableArgs)
            assertNull(hook.intercept(gmsDisableChain))
            assertEquals(0, gmsDisableChain.proceedCount)

            // GMS + disabled=false -> passes through untouched.
            val gmsEnableArgs = if (isFour) arrayOf<Any?>(gmsUid, "GCM", false, "vendor") else arrayOf<Any?>(gmsUid, "GCM", false)
            val gmsEnableChain = FakeChain(method, stub, null, *gmsEnableArgs)
            hook.intercept(gmsEnableChain)
            assertEquals(1, gmsEnableChain.proceedCount)
            assertEquals(false, gmsEnableChain.lastProceedArgs!![2])

            // Other UID + disabled=true -> passes through untouched.
            val otherDisableArgs = if (isFour) arrayOf<Any?>(otherUid, "TAG", true, "vendor") else arrayOf<Any?>(otherUid, "TAG", true)
            val otherDisableChain = FakeChain(method, stub, null, *otherDisableArgs)
            hook.intercept(otherDisableChain)
            assertEquals(1, otherDisableChain.proceedCount)
        }
    }

    // ---------- AlarmGate adjustAlarmLocked pending delay cleanup ----------

    class FakeAlarm(
        @JvmField val creatorUid: Int
    ) {
        @JvmField
        val policyWhen = LongArray(NUM_POLICIES)

        fun maxWhen(): Long = policyWhen.maxOrNull() ?: 0L

        fun setPolicyElapsed(index: Int, elapsed: Long): Boolean {
            val before = maxWhen()
            policyWhen[index] = elapsed
            return before != maxWhen()
        }

        companion object {
            @JvmField
            val POWER_PENDING_POLICY_INDEX = 5

            @JvmField
            val NUM_POLICIES = 6
        }
    }

    class FakeAlarmWithoutPolicyApi(
        @JvmField val creatorUid: Int
    )

    class FakeAlarmStub {
        private fun adjustAlarmLocked(alarm: FakeAlarm): Boolean = true
        fun proxyAlarm(alarm: FakeAlarm): Boolean = adjustAlarmLocked(alarm)
    }

    class FakeAlarmStubLegacy {
        private fun adjustAlarmLocked(alarm: FakeAlarmWithoutPolicyApi): Boolean = true
    }

    @Test
    fun adjustAlarmLockedClearsExistingVendorPendingDelayAndDeoptimizesCaller() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf(
                "com.android.server.alarm.Alarm" to FakeAlarm::class.java,
                "com.android.server.alarm.AlarmManagerServiceStubImpl" to FakeAlarmStub::class.java
            )
        )
        AlarmGate.hookAlarmGate(fw.env(loader, gmsUid = gmsUid, isGmsUid = isGms))

        val stubMethod = FakeAlarmStub::class.java.getDeclaredMethod("adjustAlarmLocked", FakeAlarm::class.java)
        val hook = fw.hookFor(FakeAlarmStub::class.java, "adjustAlarmLocked", FakeAlarm::class.java)

        assertTrue(fw.isDeoptimized(FakeAlarmStub::class.java, "adjustAlarmLocked"))
        assertTrue(fw.isDeoptimized(FakeAlarmStub::class.java, "proxyAlarm"))

        val gmsAlarm = FakeAlarm(gmsUid)
        gmsAlarm.policyWhen[0] = 1000L
        gmsAlarm.policyWhen[2] = 7000L
        gmsAlarm.policyWhen[FakeAlarm.POWER_PENDING_POLICY_INDEX] = 259_201_000L

        val chain = FakeChain(stubMethod, FakeAlarmStub(), true, gmsAlarm)
        assertEquals(true, hook.intercept(chain))
        assertEquals(0, chain.proceedCount)
        assertArrayEquals(longArrayOf(1000L, 0L, 7000L, 0L, 0L, 0L), gmsAlarm.policyWhen)

        // Second pass when slot 5 is already 0: returns false (no further change).
        assertEquals(false, hook.intercept(chain))
        assertEquals(0, chain.proceedCount)

        // Non-GMS alarm: must proceed untouched.
        val otherAlarm = FakeAlarm(otherUid)
        otherAlarm.policyWhen[FakeAlarm.POWER_PENDING_POLICY_INDEX] = 259_201_000L
        val otherChain = FakeChain(stubMethod, FakeAlarmStub(), true, otherAlarm)
        assertEquals(true, hook.intercept(otherChain))
        assertEquals(1, otherChain.proceedCount)
        assertEquals(259_201_000L, otherAlarm.policyWhen[FakeAlarm.POWER_PENDING_POLICY_INDEX])
    }

    @Test
    fun adjustAlarmLockedFallsBackToFalseWhenPolicyApiIsAbsent() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf(
                "com.android.server.alarm.Alarm" to FakeAlarmWithoutPolicyApi::class.java,
                "com.android.server.alarm.AlarmManagerServiceStubImpl" to FakeAlarmStubLegacy::class.java
            )
        )
        AlarmGate.hookAlarmGate(fw.env(loader, gmsUid = gmsUid, isGmsUid = isGms))

        val stubMethod = FakeAlarmStubLegacy::class.java.getDeclaredMethod(
            "adjustAlarmLocked", FakeAlarmWithoutPolicyApi::class.java
        )
        val hook = fw.hookFor(FakeAlarmStubLegacy::class.java, "adjustAlarmLocked", FakeAlarmWithoutPolicyApi::class.java)

        val gmsChain = FakeChain(stubMethod, FakeAlarmStubLegacy(), true, FakeAlarmWithoutPolicyApi(gmsUid))
        assertEquals(false, hook.intercept(gmsChain))
        assertEquals(0, gmsChain.proceedCount)
    }
}
