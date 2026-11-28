package io.github.iamqwert.hypergreeze

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM behavior tests for high-risk cross-generation, polarity, and
 * hot-reload bookkeeping paths using [FakeXposedFramework].
 */
class CoreHookBehaviorTest {

    private val gmsUid = 10135
    private val otherUid = 10136
    private val isGms: (Int) -> Boolean = { it == gmsUid }

    // ---------- GmsSupport.hookGmsObserver: OS4 vs OS3 firmware matrix ----------

    class FakeOs4NetdExecutor {
        fun execute(timeout: Int, type: String, cmd: String, args: Array<Any>): Int = 0
    }

    class FakeOs4GmsObserver {
        fun onGoogleReachabilityChanged(reachable: Boolean) {}
    }

    class FakeOs3NetdExecutor {
        fun initGmsChain(chain: String, uid: Int, rule: String) {}
        fun setGmsChainState(chain: String, enable: Boolean) {}
        fun execute(timeout: Int, type: String, cmd: String, args: Array<Any>): Int = 0
    }

    class FakeOs3GmsObserver {
        fun updateGmsAlarm(limit: Boolean) {}
        fun updateGmsNetWork(limit: Boolean) {}
        fun updateGoogleReletivesWakelock(limit: Boolean) {}
        fun disableGms() {}
        fun updateGmsEnabled(limit: Boolean) {}
        fun updateGmsState(limit: Boolean) {}
        fun updateGmsInstalled(limit: Boolean) {}
        fun onGoogleReachabilityChanged(reachable: Boolean) {}
    }

    @Test
    fun gmsObserverOnOs4CountsMissingLegacyMethodsAsCrossGenerationAndGuardsNetd() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf(
                "com.miui.powerkeeper.utils.NetdExecutor" to FakeOs4NetdExecutor::class.java,
                "com.miui.powerkeeper.utils.GmsObserver" to FakeOs4GmsObserver::class.java
            )
        )
        fw.installBook.installPassCollecting = true
        GmsSupport.hookGmsObserver(fw.env(loader, gmsUid = gmsUid, isGmsUid = isGms))
        fw.installBook.installPassCollecting = false
        fw.installBook.logSummary("com.miui.powerkeeper")

        val summary = fw.installBook.lastSummary!!
        assertTrue("OS4 should install 2 hooks: $summary", summary.contains("2 hook(s) installed"))
        assertTrue("OS4 should have 0 same-generation absences: $summary", summary.contains("0 target(s) absent on this ROM"))
        assertTrue("OS4 should report 9 cross-generation absences: $summary", summary.contains("9 cross-generation (expected)"))

        // onGoogleReachabilityChanged forces true.
        val reachMethod = FakeOs4GmsObserver::class.java.getDeclaredMethod(
            "onGoogleReachabilityChanged", Boolean::class.javaPrimitiveType
        )
        val reachHook = fw.hookFor(FakeOs4GmsObserver::class.java, "onGoogleReachabilityChanged", Boolean::class.javaPrimitiveType!!)
        val reachChain = FakeChain(reachMethod, FakeOs4GmsObserver(), null, false)
        reachHook.intercept(reachChain)
        assertEquals(1, reachChain.proceedCount)
        assertEquals(true, reachChain.lastProceedArgs!![0])

        // NetdExecutor#execute rewrites setuiddnsrule ONLY for GMS uid.
        val execMethod = FakeOs4NetdExecutor::class.java.getDeclaredMethod(
            "execute", Int::class.javaPrimitiveType, String::class.java, String::class.java, Array<Any>::class.java
        )
        val execHook = fw.hooks.getValue(execMethod)

        val gmsDnsChain = FakeChain(
            execMethod, FakeOs4NetdExecutor(), 0,
            1000, "netd", "setuiddnsrule", arrayOf<Any>(gmsUid.toString(), "deny")
        )
        execHook.intercept(gmsDnsChain)
        assertEquals(1, gmsDnsChain.proceedCount)
        @Suppress("UNCHECKED_CAST")
        val rewrittenGmsArgs = gmsDnsChain.lastProceedArgs!![3] as Array<Any?>
        assertArrayEquals(arrayOf<Any?>(gmsUid.toString(), "allow"), rewrittenGmsArgs)

        val otherDnsChain = FakeChain(
            execMethod, FakeOs4NetdExecutor(), 0,
            1000, "netd", "setuiddnsrule", arrayOf<Any>(otherUid.toString(), "deny")
        )
        execHook.intercept(otherDnsChain)
        assertEquals(1, otherDnsChain.proceedCount)
        @Suppress("UNCHECKED_CAST")
        val untouchedOtherArgs = otherDnsChain.lastProceedArgs!![3] as Array<Any?>
        assertArrayEquals(arrayOf<Any?>(otherUid.toString(), "deny"), untouchedOtherArgs)

        // enablemiuistandby enable is suppressed without calling proceed.
        val standbyChain = FakeChain(
            execMethod, FakeOs4NetdExecutor(), 0,
            1000, "netd", "enablemiuistandby", arrayOf<Any>("enable")
        )
        assertEquals(0, execHook.intercept(standbyChain))
        assertEquals(0, standbyChain.proceedCount)
    }

    @Test
    fun gmsObserverOnOs3InstallsAllLegacyHooksAndNeutralizesLimits() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf(
                "com.miui.powerkeeper.utils.NetdExecutor" to FakeOs3NetdExecutor::class.java,
                "com.miui.powerkeeper.utils.GmsObserver" to FakeOs3GmsObserver::class.java
            )
        )
        GmsSupport.hookGmsObserver(fw.env(loader, gmsUid = gmsUid, isGmsUid = isGms))
        fw.installBook.logSummary("com.miui.powerkeeper")

        val summary = fw.installBook.lastSummary!!
        assertTrue("OS3 should install all 11 hooks: $summary", summary.contains("11 hook(s) installed"))
        assertTrue("OS3 should have 0 absences: $summary", summary.contains("0 target(s) absent on this ROM"))

        // initGmsChain forces "ACCEPT"
        val initMethod = FakeOs3NetdExecutor::class.java.getDeclaredMethod(
            "initGmsChain", String::class.java, Int::class.javaPrimitiveType, String::class.java
        )
        val initChain = FakeChain(initMethod, FakeOs3NetdExecutor(), null, "gms_wall", gmsUid, "REJECT")
        fw.hooks.getValue(initMethod).intercept(initChain)
        assertEquals("ACCEPT", initChain.lastProceedArgs!![2])

        // setGmsChainState forces false
        val stateMethod = FakeOs3NetdExecutor::class.java.getDeclaredMethod(
            "setGmsChainState", String::class.java, Boolean::class.javaPrimitiveType
        )
        val stateChain = FakeChain(stateMethod, FakeOs3NetdExecutor(), null, "gms_wall", true)
        fw.hooks.getValue(stateMethod).intercept(stateChain)
        assertEquals(false, stateChain.lastProceedArgs!![1])

        // disableGms is skipped completely
        val disableMethod = FakeOs3GmsObserver::class.java.getDeclaredMethod("disableGms")
        val disableChain = FakeChain(disableMethod, FakeOs3GmsObserver(), null)
        assertNull(fw.hooks.getValue(disableMethod).intercept(disableChain))
        assertEquals(0, disableChain.proceedCount)
    }

    // ---------- GreezeHooks: DomesticPolicyManager#isRestrictNet polarity ----------

    class FakeDomesticPolicyManager {
        fun isRestrictNet(uid: Int): Boolean = true
    }

    @Test
    fun domesticRestrictNetPolarityProtectsGmsAndRespectsExperimentSwitch() {
        val fw = FakeXposedFramework()
        val loader = MapClassLoader(
            mapOf("com.miui.server.greeze.DomesticPolicyManager" to FakeDomesticPolicyManager::class.java)
        )
        var netRelease = false
        val env = fw.env(
            classLoader = loader,
            gmsUid = gmsUid,
            isGmsUid = isGms,
            allowlistPackageForUid = { uid -> if (uid == otherUid) "com.example.chat" else null }
        )
        GreezeHooks.hookDomesticPolicyManager(env) { netRelease }

        val method = FakeDomesticPolicyManager::class.java.getDeclaredMethod("isRestrictNet", Int::class.javaPrimitiveType)
        val hook = fw.hooks.getValue(method)

        // GMS is always unrestricted (returns false, skips ROM).
        val gmsChain = FakeChain(method, FakeDomesticPolicyManager(), true, gmsUid)
        assertEquals(false, hook.intercept(gmsChain))
        assertEquals(0, gmsChain.proceedCount)

        // Allowlisted app with experiment OFF -> proceeds to ROM.
        val offChain = FakeChain(method, FakeDomesticPolicyManager(), true, otherUid)
        assertEquals(true, hook.intercept(offChain))
        assertEquals(1, offChain.proceedCount)

        // Allowlisted app with experiment ON -> returns false.
        netRelease = true
        val onChain = FakeChain(method, FakeDomesticPolicyManager(), true, otherUid)
        assertEquals(false, hook.intercept(onChain))
        assertEquals(0, onChain.proceedCount)
    }

    // ---------- GreezeHooks: ListAppsManager static vs instance field ----------

    class FakeOs4ListAppsManager {
        companion object {
            @JvmField
            val USE_DATA_WHITE_LIST: MutableSet<String> = HashSet()
        }
    }

    class FakeLegacyListAppsManager {
        @JvmField
        val mUseDataWhiteList: MutableSet<String> = HashSet()
    }

    @Test
    fun listAppsManagerInjectsGmsIntoBothStaticAndInstanceWhitelistFields() {
        // OS4 static USE_DATA_WHITE_LIST
        FakeOs4ListAppsManager.USE_DATA_WHITE_LIST.clear()
        val fw4 = FakeXposedFramework()
        val loader4 = MapClassLoader(
            mapOf("com.miui.server.greeze.power.ListAppsManager" to FakeOs4ListAppsManager::class.java)
        )
        GreezeHooks.hookListAppsManager(fw4.env(loader4))
        val ctor4 = FakeOs4ListAppsManager::class.java.getDeclaredConstructor()
        val instance4 = FakeOs4ListAppsManager()
        fw4.constructorHookFor(FakeOs4ListAppsManager::class.java).intercept(FakeChain(ctor4, instance4, null))
        assertTrue(FakeOs4ListAppsManager.USE_DATA_WHITE_LIST.contains(HookerSupport.GMS_PACKAGE_NAME))

        // Legacy instance mUseDataWhiteList
        val fw3 = FakeXposedFramework()
        val loader3 = MapClassLoader(
            mapOf("com.miui.server.greeze.power.ListAppsManager" to FakeLegacyListAppsManager::class.java)
        )
        GreezeHooks.hookListAppsManager(fw3.env(loader3))
        val ctor3 = FakeLegacyListAppsManager::class.java.getDeclaredConstructor()
        val instance3 = FakeLegacyListAppsManager()
        fw3.constructorHookFor(FakeLegacyListAppsManager::class.java).intercept(FakeChain(ctor3, instance3, null))
        assertTrue(instance3.mUseDataWhiteList.contains(HookerSupport.GMS_PACKAGE_NAME))
    }

    // ---------- InstallBookkeeping: hot-reload differential unhook ----------

    @Test
    fun hotReloadBookkeepingRetainsReplacedIdsAndMarksStaleIdsForUnhook() {
        val fw = FakeXposedFramework()
        val methodA = FakeOs4GmsObserver::class.java.getDeclaredMethod(
            "onGoogleReachabilityChanged", Boolean::class.javaPrimitiveType
        )
        val methodB = FakeDomesticPolicyManager::class.java.getDeclaredMethod(
            "isRestrictNet", Int::class.javaPrimitiveType
        )

        // Initial pass installs A and B.
        fw.installBook.hookE(methodA).intercept { it.proceed() }
        fw.installBook.hookE(methodB).intercept { it.proceed() }
        val idA = fw.hookIds.getValue(methodA)
        val idB = fw.hookIds.getValue(methodB)
        assertTrue(fw.installBook.isHookedId(idA))
        assertTrue(fw.installBook.isHookedId(idB))

        // Hot reload pass resets bookkeeping and only re-installs A.
        fw.installBook.resetForHotReload()
        fw.installBook.hookE(methodA).intercept { it.proceed() }

        assertTrue("Re-installed hook ID should be kept", fw.installBook.isHookedId(idA))
        assertFalse("Removed hook ID should be marked stale for unhook", fw.installBook.isHookedId(idB))
        assertFalse(fw.installBook.isHookedId(null))
    }

    // ---------- RemotePrefsObserver: attach / reattach / detach ----------

    @Test
    fun remotePrefsObserverUnregistersPreviousListenerOnReattachAndDetach() {
        val listeners = LinkedHashSet<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
        val fakePrefs = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> args[1] as Boolean
                "registerOnSharedPreferenceChangeListener" -> {
                    listeners.add(args[0] as android.content.SharedPreferences.OnSharedPreferenceChangeListener)
                    null
                }
                "unregisterOnSharedPreferenceChangeListener" -> {
                    listeners.remove(args[0] as android.content.SharedPreferences.OnSharedPreferenceChangeListener)
                    null
                }
                else -> null
            }
        } as android.content.SharedPreferences

        val fw = FakeXposedFramework(remotePrefsProvider = { fakePrefs })
        val observer = RemotePrefsObserver()
        val seenKeys = ArrayList<String?>()

        observer.attach(fw.xposedInterface, "system_server") { _, key -> seenKeys.add(key) }
        assertEquals(1, listeners.size)

        // Fire once -> seen once.
        listeners.toList().forEach { it.onSharedPreferenceChanged(fakePrefs, Prefs.KEY_ALLOWLIST) }
        assertEquals(listOf(Prefs.KEY_ALLOWLIST), seenKeys)

        // Re-attach (e.g. hot reload): old listener unregistered, still exactly 1 active.
        observer.attach(fw.xposedInterface, "system_server") { _, key -> seenKeys.add(key) }
        assertEquals(1, listeners.size)

        listeners.toList().forEach { it.onSharedPreferenceChanged(fakePrefs, Prefs.KEY_DEBUG_LOG) }
        assertEquals(listOf(Prefs.KEY_ALLOWLIST, Prefs.KEY_DEBUG_LOG), seenKeys)

        // Detach removes listener cleanly.
        observer.detach()
        assertEquals(0, listeners.size)
    }
}
