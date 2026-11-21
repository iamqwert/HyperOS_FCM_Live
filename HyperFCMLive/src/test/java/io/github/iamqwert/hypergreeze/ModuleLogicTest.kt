package io.github.iamqwert.hypergreeze

import io.github.iamqwert.hypergreeze.AllowlistGate.allowlistSenderIsForeign
import io.github.iamqwert.hypergreeze.ui.licenseRawFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for two decision matrices that live deep inside the module:
 *
 * - [licenseRawFor] — which licence body a row shows. The branch order has
 *   bitten once (EPL showed the Apache fallback under a correct-looking label),
 *   so the Eclipse branch and the name-precedence rules are pinned here.
 *
 * - [allowlistSenderIsForeign] — who may drive the FCM allowlist over
 *   broadcast. A wrong "false" here lets a foreign package rewrite the
 *   allowlist; a wrong "true" breaks adb debugging. Both directions are
 *   pinned.
 *
 * No Android runtime required.
 */
class ModuleLogicTest {

    // Mirrors of android.os.Process constants — the unit-test classpath's
    // mockable android.jar does not resolve them, and these values are stable
    // public API.
    private val systemUid = 1000
    private val rootUid = 0
    private val shellUid = 2000
    private val invalidUid = -1

    // ---------- licenseRawFor ----------

    @Test
    fun dedicatedReposWinOverTheirLabel() {
        // The three reference projects ship their own copyright line; even if
        // their SPDX label ever reads Apache, the dedicated copy must win.
        assertEquals(
            R.raw.license_mit_hyperosfcmfix,
            licenseRawFor("Kr328/HyperOSFCMFix", "Apache-2.0")
        )
        assertEquals(
            R.raw.license_mit_fcmguard,
            licenseRawFor("ReedGAOOO/FCMGuard-HyperOS", "MIT")
        )
        assertEquals(
            R.raw.license_mit_hyperisland,
            licenseRawFor("1812z/HyperIsland", "Unknown")
        )
        assertEquals(
            R.raw.license_mit_fcmfix_coloros,
            licenseRawFor("ByteJuno/fcmfix-coloros", "MIT License")
        )
        assertEquals(
            R.raw.license_mit_fcmfix,
            licenseRawFor("kooritea/fcmfix", "MIT License")
        )
    }

    @Test
    fun spdxLabelsMapToTheirOwnText() {
        assertEquals(R.raw.license_apache2, licenseRawFor("x", "Apache-2.0"))
        assertEquals(R.raw.license_gpl3, licenseRawFor("x", "GPL-3.0"))
        assertEquals(R.raw.license_mit, licenseRawFor("x", "MIT License"))
    }

    @Test
    fun eclipseLabelBeatsTheApacheFallback() {
        // Without this branch JUnit showed the Apache text — the mapping must
        // keep recognising Eclipse before falling back.
        assertEquals(R.raw.license_epl1, licenseRawFor("x", "EPL-1.0"))
        assertEquals(
            R.raw.license_epl1,
            licenseRawFor("x", "Eclipse Public License 1.0")
        )
        // Non-empty repos without a LICENSE are filtered before this function,
        // so an unmatched label here means the fallback, not a regression.
        assertEquals(R.raw.license_apache2, licenseRawFor("x", "Unrecognised"))
    }

    // ---------- allowlistSenderIsForeign ----------

    @Test
    fun theModuleItselfIsAlwaysTrusted() {
        assertFalse(allowlistSenderIsForeign(Prefs.MODULE_PKG, 10123))
    }

    @Test
    fun anyOtherNamedPackageIsForeign() {
        assertTrue(allowlistSenderIsForeign("com.evil.app", 10123))
        assertTrue(allowlistSenderIsForeign("com.android.systemui", systemUid))
        // Package identity beats uid: a package name that is not the module's
        // is rejected even when the uid happens to be privileged.
        assertTrue(allowlistSenderIsForeign("com.some.app", rootUid))
    }

    @Test
    fun unstampedSendersAreNeverRejected() {
        // Fail-open by construction: null package + INVALID_UID is processed
        // exactly as before the check existed.
        assertFalse(allowlistSenderIsForeign(null, invalidUid))
    }

    @Test
    fun unnamedSendersPassOnlyForSystemRootShell() {
        assertFalse(allowlistSenderIsForeign(null, systemUid))
        assertFalse(allowlistSenderIsForeign(null, rootUid))
        assertFalse(allowlistSenderIsForeign(null, shellUid))
        // Any other unnamed uid is foreign.
        assertTrue(allowlistSenderIsForeign(null, 10123))
    }

    // ---------- FcmAppScan.isFcmClient ----------

    @Test
    fun fcmClientAcceptsIidOrMessagingOrC2dmSendOrGooglePushAlone() {
        assertTrue(FcmAppScan.isFcmClient(hasIidService = true, hasMessagingEvent = false, hasC2dmSendReceive = false, hasGooglePushReceiver = false))
        assertTrue(FcmAppScan.isFcmClient(hasIidService = false, hasMessagingEvent = true, hasC2dmSendReceive = false, hasGooglePushReceiver = false))
        assertTrue(FcmAppScan.isFcmClient(hasIidService = false, hasMessagingEvent = false, hasC2dmSendReceive = true, hasGooglePushReceiver = false))
        assertTrue(FcmAppScan.isFcmClient(hasIidService = false, hasMessagingEvent = false, hasC2dmSendReceive = false, hasGooglePushReceiver = true))
        assertTrue(FcmAppScan.isFcmClient(hasIidService = false, hasMessagingEvent = false, hasC2dmSendReceive = true, hasGooglePushReceiver = true))
    }

    @Test
    fun fcmClientRejectsBareReceiveOrNothing() {
        assertFalse(FcmAppScan.isFcmClient(hasIidService = false, hasMessagingEvent = false, hasC2dmSendReceive = false, hasGooglePushReceiver = false))
    }

    // ---------- HookerSupport.isGooglePushReceiverClass ----------

    @Test
    fun googlePushReceiverMatchesOnlyGnpCoreNamespaceAndSuffix() {
        assertTrue(
            HookerSupport.isGooglePushReceiverClass(
                "com.google.android.libraries.notifications.entrypoints.gcm.GcmBroadcastReceiverPushReceiver"
            )
        )
        assertTrue(
            HookerSupport.isGooglePushReceiverClass(
                "com.google.android.libraries.notifications.PushReceiver"
            )
        )
        assertFalse(HookerSupport.isGooglePushReceiverClass(null))
        assertFalse(HookerSupport.isGooglePushReceiverClass(""))
        assertFalse(HookerSupport.isGooglePushReceiverClass("com.example.PushReceiver"))
        assertFalse(HookerSupport.isGooglePushReceiverClass("com.google.android.libraries.notifications.OtherReceiver"))
    }

    // ---------- GmsSupport.skipValueFor ----------

    @Test
    fun skipValueForReturnsTypedZerosAndNullForVoidOrObjects() {
        assertEquals(null, GmsSupport.skipValueFor(Void.TYPE))
        assertEquals(null, GmsSupport.skipValueFor(String::class.java))
        assertEquals(false, GmsSupport.skipValueFor(Boolean::class.javaPrimitiveType!!))
        assertEquals(0, GmsSupport.skipValueFor(Int::class.javaPrimitiveType!!))
        assertEquals(0L, GmsSupport.skipValueFor(Long::class.javaPrimitiveType!!))
        assertEquals(0.toShort(), GmsSupport.skipValueFor(Short::class.javaPrimitiveType!!))
        assertEquals(0.toByte(), GmsSupport.skipValueFor(Byte::class.javaPrimitiveType!!))
        assertEquals(0.toChar(), GmsSupport.skipValueFor(Char::class.javaPrimitiveType!!))
        assertEquals(0f, GmsSupport.skipValueFor(Float::class.javaPrimitiveType!!))
        assertEquals(0.0, GmsSupport.skipValueFor(Double::class.javaPrimitiveType!!))
    }

    // ---------- InstallBookkeeping.formatSummary ----------

    @Test
    fun formatSummaryOmitsCrossGenerationClauseWhenZero() {
        assertEquals(
            "HyperFCMLive active in system_server: 12 hook(s) installed, 0 target(s) absent on this ROM",
            InstallBookkeeping.formatSummary("system_server", installed = 12, absent = 0, crossGeneration = 0)
        )
    }

    @Test
    fun formatSummaryIncludesCrossGenerationClauseWhenNonZero() {
        assertEquals(
            "HyperFCMLive active in com.miui.powerkeeper: 6 hook(s) installed, 1 target(s) absent on this ROM, 10 cross-generation (expected)",
            InstallBookkeeping.formatSummary("com.miui.powerkeeper", installed = 6, absent = 1, crossGeneration = 10)
        )
    }
}
