package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * Whether the classes and members the module hooks still exist on this ROM.
 *
 * The module runs inside system_server and PowerKeeper, and neither of those
 * processes has a channel back to this screen: no shared preference, no binder,
 * no file both sides may touch. So this does not report what the hooks
 * *did*. It reports what they *can* do — every target is loaded
 * here, in this process, and asked whether it is still there. That answers the
 * question the screen exists for: "does this ROM still have the thing my module
 * hooks?", which is the usual reason a module looks fine and does nothing.
 *
 * Both processes are loadable from here, which is why no target has to be
 * left unchecked:
 * - PowerKeeper is an installed package, so its APK can be handed to a
 *   class loader — provided the context is created with
 *   `CONTEXT_INCLUDE_CODE`, which is the flag that actually supplies
 *   the other package's loader. Without it `getClassLoader()` returns
 *   *ours*, every PowerKeeper class comes back not-found, and the
 *   whole screen silently degrades to "not checkable".
 * - system_server's classes are not on an app's boot classpath, but they
 *   ship as plain readable jars under `/system/framework`, so those
 *   jars can be handed to a class loader too.
 *
 * What this still cannot do is prove a hook is *installed* — that
 * needs the module's own log line, which is why the screen carries the command
 * for it. And a target can come back [State.UNKNOWN] when the ROM hides
 * its members from reflection; that is reported as "not checkable" rather than
 * guessed as "missing", because a false alarm is worse than a shrug.
 *
 * An [State.ABSENT] is usually not a fault: the module carries hooks
 * for several HyperOS generations and probes them independently, so a missing
 * target normally means "written for another version". [Expect] says
 * which, and the current generation decides whether that absence is expected.
 */
object HookStatus {

    private const val PK = "com.miui.powerkeeper"

    /**
     * Directories where system_server's classes ship on a normal build.
     *
     * Looked up by listing these rather than by naming files, because which
     * jar carries what is not fixed: on HyperOS 4 the MIUI classes the module
     * hooks — everything under `com.miui.server` — are absent from
     * `services.jar` entirely (zero matches across all four of its dex
     * files) and live in `miui-services.jar`. A hardcoded list that gets
     * that name wrong leaves two thirds of the system_server targets silently
     * "not checkable", which looks exactly like a ROM that hides its members.
     */
    private val FRAMEWORK_DIRS = arrayOf(
        "/system/framework",
        "/system/system_ext/framework",
        "/system/product/framework",
        "/system/vendor/framework",
    )

    /** Jar names to prefer, and the fallback when a listing is not possible. */
    private val FRAMEWORK_JAR_NAMES = arrayOf(
        "services.jar", "miui-services.jar", "miui-framework.jar", "framework.jar",
    )

    /**
     * Upper bound on jars handed to one class loader: every extra jar is a dex
     * the loader has to open, and ten is far more than a build ships.
     */
    private const val MAX_FRAMEWORK_JARS = 10

    /** Which side of the module a target belongs to; also how rows are grouped. */
    enum class Side(@JvmField val labelRes: Int) {
        POWERKEEPER(R.string.status_group_powerkeeper),
        SYSTEM_SERVER(R.string.status_group_system_server),
    }

    /** How a target turned out. */
    enum class State { PRESENT, ABSENT, UNKNOWN }

    /**
     * Which HyperOS generation a target belongs to — the difference between
     * "this ROM moved on" and "something is wrong".
     */
    enum class Expect {
        /** Present on every supported generation. Absent anywhere is a real miss. */
        ALL,

        /** HyperOS 3 only; HyperOS 4 dropped it. */
        HYPEROS_3,

        /** The HyperOS 4 replacement path. */
        HYPEROS_4,

        /**
         * Never shipped on any known build — carried as a guess at a future
         * name. Absence is the expected outcome, not a regression.
         */
        NONE,

        /**
         * Best effort: the hook carries on without it and loses only one path
         * (a uid lookup, a flag it would have cleared). Absence is a downgrade,
         * not a fault, so it is not flagged alongside the real misses.
         */
        OPTIONAL
    }

    /** One probed target. */
    class Item internal constructor(
        @JvmField val target: String,
        @JvmField val side: Side,
        @JvmField val state: State,
        @JvmField val expect: Expect
    ) {
        /** True when this absence is worth flagging rather than explaining. */
        fun isUnexpectedAbsence(): Boolean {
            if (state != State.ABSENT) {
                return false
            }
            if (expect == Expect.ALL) {
                return true
            }
            // OPTIONAL and NONE both fall through: neither is a fault.
            if (expect == Expect.NONE || expect == Expect.OPTIONAL) {
                return false
            }
            val gen = currentGeneration()
            if (gen == 0) {
                // Unknown ROM: only claim a miss for the generation-agnostic
                // targets, otherwise every cross-version hook looks broken.
                return false
            }
            return (gen == 3 && expect == Expect.HYPEROS_3) ||
                (gen == 4 && expect == Expect.HYPEROS_4)
        }
    }

    /**
     * Probe every target. Cheap enough for a background thread, which is what
     * the caller uses: defining classes from another package's dex is the
     * expensive part and must not run on the main thread.
     */
    @JvmStatic
    fun probe(context: Context): List<Item> {
        val powerKeeper = powerKeeperLoader(context)
        val framework = frameworkLoader()
        val items = ArrayList<Item>()

        // ---- PowerKeeper ------------------------------------------------
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "NetdExecutor#initGmsChain",
            "com.miui.powerkeeper.utils.NetdExecutor", "initGmsChain",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "NetdExecutor#setGmsDnsBlockerState",
            "com.miui.powerkeeper.utils.NetdExecutor", "setGmsDnsBlockerState",
            Expect.ALL
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "NetdExecutor#setGmsChainState",
            "com.miui.powerkeeper.utils.NetdExecutor", "setGmsChainState",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "NetdExecutor#execute",
            "com.miui.powerkeeper.utils.NetdExecutor", "execute",
            Expect.ALL
        )

        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateGmsAlarm",
            "com.miui.powerkeeper.utils.GmsObserver", "updateGmsAlarm",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateGmsNetWork",
            "com.miui.powerkeeper.utils.GmsObserver", "updateGmsNetWork",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateGoogleReletivesWakelock",
            "com.miui.powerkeeper.utils.GmsObserver",
            "updateGoogleReletivesWakelock", Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#disableGms",
            "com.miui.powerkeeper.utils.GmsObserver", "disableGms",
            Expect.HYPEROS_3
        )
        // A HyperOS 4 name that the OS 3 build does not ship either — verified
        // against both 4.2.00 builds, so absence is the normal outcome.
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#disableGmsApps",
            "com.miui.powerkeeper.utils.GmsObserver", "disableGmsApps",
            Expect.NONE
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateGmsEnabled",
            "com.miui.powerkeeper.utils.GmsObserver", "updateGmsEnabled",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateGmsState",
            "com.miui.powerkeeper.utils.GmsObserver", "updateGmsState",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateGmsInstalled",
            "com.miui.powerkeeper.utils.GmsObserver", "updateGmsInstalled",
            Expect.HYPEROS_3
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#updateFrameworkGmsNetStatus",
            "com.miui.powerkeeper.utils.GmsObserver",
            "updateFrameworkGmsNetStatus", Expect.HYPEROS_4
        )
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#onGoogleReachabilityChanged",
            "com.miui.powerkeeper.utils.GmsObserver",
            "onGoogleReachabilityChanged", Expect.ALL
        )
        // Matched by signature, not name: "c" is a compiler-generated name and
        // OS 3 has an unrelated c(GmsObserver) returning a listener. Matching on
        // the name alone would report this as present on OS 3 when the overload
        // the module needs is not there.
        signature(
            items, powerKeeper, Side.POWERKEEPER,
            "GmsObserver#c(GmsObserver, boolean)",
            "com.miui.powerkeeper.utils.GmsObserver", "c",
            "(Lcom/miui/powerkeeper/utils/GmsObserver;Z)V", Expect.HYPEROS_4
        )
        // The disconnect listener is an anonymous inner class whose index drifts
        // between builds (GmsObserver$5 on HyperOS 3, $2 on HyperOS 4), so scan
        // for it instead of hard-coding one name.
        items.add(
            Item(
                "GmsObserver\$*#googleNetworkDisconnect", Side.POWERKEEPER,
                probeInnerMethod(
                    powerKeeper, "com.miui.powerkeeper.utils.GmsObserver",
                    "googleNetworkDisconnect"
                ), Expect.ALL
            )
        )
        // getDozeWhiteListApps ships with more than one signature, so the module
        // matches it by name; do the same here.
        method(
            items, powerKeeper, Side.POWERKEEPER,
            "GlobalFeatureConfigureHelper#getDozeWhiteListApps",
            "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper",
            "getDozeWhiteListApps", Expect.ALL
        )

        // ---- system_server ------------------------------------------------
        method(
            items, framework, Side.SYSTEM_SERVER,
            "GreezeManagerService#isAllowBroadcast",
            "com.miui.server.greeze.GreezeManagerService", "isAllowBroadcast",
            Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "GreezeManagerService#getPackageNameFromUid",
            "com.miui.server.greeze.GreezeManagerService", "getPackageNameFromUid",
            Expect.OPTIONAL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "GreezeManagerService#deferBroadcastForMiui",
            "com.miui.server.greeze.GreezeManagerService", "deferBroadcastForMiui",
            Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "GreezeManagerService#triggerGMSLimitAction",
            "com.miui.server.greeze.GreezeManagerService", "triggerGMSLimitAction",
            Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "GreezeManagerService#updateGmsNetStatus",
            "com.miui.server.greeze.GreezeManagerService", "updateGmsNetStatus",
            Expect.HYPEROS_4
        )
        // Only read on the no-arg overload path, and the hook still installs
        // without it — it just cannot clear the flag.
        field(
            items, framework, Side.SYSTEM_SERVER,
            "GreezeManagerService#mGmsLimitEnabled",
            "com.miui.server.greeze.GreezeManagerService", "mGmsLimitEnabled",
            Expect.OPTIONAL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "DomesticPolicyManager#deferBroadcast",
            "com.miui.server.greeze.DomesticPolicyManager", "deferBroadcast",
            Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "ListAppsManager#isInWhiteList",
            "com.miui.server.greeze.power.ListAppsManager", "isInWhiteList",
            Expect.ALL
        )
        // Both fields are looked up under a name and then a constant-style
        // fallback, so either spelling existing counts as present.
        memberAnyOf(
            items, framework, Side.SYSTEM_SERVER,
            "ListAppsManager#mSystemBlackList",
            "com.miui.server.greeze.power.ListAppsManager", true,
            arrayOf("mSystemBlackList", "SYSTEM_BLACK_LIST"), Expect.ALL
        )
        memberAnyOf(
            items, framework, Side.SYSTEM_SERVER,
            "ListAppsManager#mUseDataWhiteList",
            "com.miui.server.greeze.power.ListAppsManager", true,
            arrayOf("mUseDataWhiteList", "USE_DATA_WHITE_LIST"), Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "BroadcastQueueModernStubImpl#checkApplicationAutoStart",
            "com.android.server.am.BroadcastQueueModernStubImpl",
            "checkApplicationAutoStart", Expect.ALL
        )
        field(
            items, framework, Side.SYSTEM_SERVER,
            "BroadcastRecord#callerPackage",
            "com.android.server.am.BroadcastRecord", "callerPackage", Expect.ALL
        )
        field(
            items, framework, Side.SYSTEM_SERVER,
            "BroadcastRecord#intent",
            "com.android.server.am.BroadcastRecord", "intent", Expect.ALL
        )
        field(
            items, framework, Side.SYSTEM_SERVER,
            "ProcessRecord#info",
            "com.android.server.am.ProcessRecord", "info", Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "ProcessRecord#getApplicationInfo",
            "com.android.server.am.ProcessRecord", "getApplicationInfo", Expect.ALL
        )
        field(
            items, framework, Side.SYSTEM_SERVER,
            "ProcessManagerService#mPkms",
            "com.android.server.am.ProcessManagerService", "mPkms", Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "ProcessPolicy#getWhiteList",
            "com.android.server.am.ProcessPolicy", "getWhiteList", Expect.ALL
        )
        field(
            items, framework, Side.SYSTEM_SERVER,
            "AwareResourceControl#mNoNetworkBlackUids",
            "com.miui.server.greeze.power.AwareResourceControl",
            "mNoNetworkBlackUids", Expect.ALL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "InternationalPolicyManager#isPushApp",
            "com.miui.server.greeze.InternationalPolicyManager", "isPushApp",
            Expect.ALL
        )
        // Not hooked — the isPushApp hook recognises its caller by this method
        // name on the stack. If a build renames it the branch stops matching
        // without any error, and GMS then gets network-restricted as designed,
        // which is the overnight "push stopped while the screen was off" case.
        // OPTIONAL because the caller test also accepts any class system_server
        // loaded, so the name may legitimately live elsewhere.
        method(
            items, framework, Side.SYSTEM_SERVER,
            "DomesticPolicyManager#isRestrictNet",
            "com.miui.server.greeze.DomesticPolicyManager", "isRestrictNet",
            Expect.OPTIONAL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "InternationalPolicyManager#isRestrictNet",
            "com.miui.server.greeze.InternationalPolicyManager", "isRestrictNet",
            Expect.OPTIONAL
        )
        method(
            items, framework, Side.SYSTEM_SERVER,
            "ProcessCleanerBase#isForceStopEnable",
            "com.android.server.am.ProcessCleanerBase", "isForceStopEnable",
            Expect.ALL
        )
        field(
            items, framework, Side.SYSTEM_SERVER,
            "ActivityManagerService#mContext",
            "com.android.server.am.ActivityManagerService", "mContext", Expect.ALL
        )
        // Stopped-package delivery and the power exemption hang off whichever
        // broadcast entry point this release has; losing both is the one miss
        // that takes those two features with it, which is why it is not OPTIONAL.
        memberAnyOf(
            items, framework, Side.SYSTEM_SERVER,
            "ActivityManagerService#broadcastIntent*",
            "com.android.server.am.ActivityManagerService", false,
            arrayOf("broadcastIntentWithFeature", "broadcastIntent"), Expect.ALL
        )
        // Renamed between releases; the binder uid is the fallback, so this one
        // only costs the uid lookup.
        memberAnyOf(
            items, framework, Side.SYSTEM_SERVER,
            "ActivityManagerService#getRecordForApp*",
            "com.android.server.am.ActivityManagerService", false,
            arrayOf("getRecordForAppLOSP", "getRecordForAppLocked"),
            Expect.OPTIONAL
        )

        return items
    }

    // ---- target helpers ------------------------------------------------

    private fun method(
        items: MutableList<Item>, loader: ClassLoader?, side: Side,
        target: String, className: String, member: String,
        expect: Expect
    ) {
        items.add(Item(target, side, probeMember(loader, className, member, false), expect))
    }

    private fun field(
        items: MutableList<Item>, loader: ClassLoader?, side: Side,
        target: String, className: String, member: String,
        expect: Expect
    ) {
        items.add(Item(target, side, probeMember(loader, className, member, true), expect))
    }

    /**
     * For names that are not unique enough to match on alone — compiler
     * generated ones above all, where a same-named overload with a different
     * signature is a different method entirely.
     */
    private fun signature(
        items: MutableList<Item>, loader: ClassLoader?, side: Side,
        target: String, className: String, member: String,
        descriptor: String, expect: Expect
    ) {
        items.add(Item(target, side, probeSignature(loader, className, member, descriptor), expect))
    }

    /**
     * For members the module looks up under a name and then a fallback —
     * present if either spelling exists, so the fallback is not read as a miss.
     */
    private fun memberAnyOf(
        items: MutableList<Item>, loader: ClassLoader?, side: Side,
        target: String, className: String, isField: Boolean,
        members: Array<String>, expect: Expect
    ) {
        var state = State.UNKNOWN
        for (member in members) {
            val s = probeMember(loader, className, member, isField)
            if (s == State.PRESENT) {
                state = State.PRESENT
                break
            }
            if (s == State.ABSENT) {
                state = State.ABSENT
            }
        }
        items.add(Item(target, side, state, expect))
    }

    /**
     * Is `member` still declared on `className`?
     *
     * Matched by name, not signature: signatures drift between builds, and
     * the module itself falls back to name matching for the same reason.
     *
     * A class that loads but declares nothing is [State.UNKNOWN]
     * rather than [State.ABSENT] — that is the signature of a ROM hiding
     * its members from reflection, and reporting it as "missing" would raise an
     * alarm over something that is merely invisible.
     */
    private fun probeMember(
        loader: ClassLoader?,
        className: String,
        member: String,
        isField: Boolean
    ): State {
        if (loader == null) {
            return State.UNKNOWN
        }
        return try {
            val clazz = Class.forName(className, false, loader)
            if (isField) {
                val fields = clazz.declaredFields
                if (fields.isEmpty()) {
                    return State.UNKNOWN
                }
                for (f in fields) {
                    if (f.name == member) {
                        return State.PRESENT
                    }
                }
                return State.ABSENT
            }
            val methods = clazz.declaredMethods
            if (methods.isEmpty()) {
                return State.UNKNOWN
            }
            for (m in methods) {
                if (m.name == member) {
                    return State.PRESENT
                }
            }
            State.ABSENT
        } catch (t: Throwable) {
            // Not found, unreadable, or fails to link: none of those mean
            // "the member is gone", so none of them may read as ABSENT.
            State.UNKNOWN
        }
    }

    /**
     * Is there a method named `member` with exactly this descriptor?
     *
     * Same [State.UNKNOWN] guard as [probeMember]: a class that
     * shows no members at all is invisible here, not empty.
     */
    private fun probeSignature(
        loader: ClassLoader?,
        className: String,
        member: String,
        descriptor: String
    ): State {
        if (loader == null) {
            return State.UNKNOWN
        }
        return try {
            val clazz = Class.forName(className, false, loader)
            val methods = clazz.declaredMethods
            if (methods.isEmpty()) {
                return State.UNKNOWN
            }
            for (m in methods) {
                if (m.name == member && descriptorOf(m) == descriptor) {
                    return State.PRESENT
                }
            }
            State.ABSENT
        } catch (t: Throwable) {
            State.UNKNOWN
        }
    }

    /** JVM type descriptor of a method, in the same shape as the dex uses. */
    private fun descriptorOf(m: java.lang.reflect.Method): String {
        val sb = StringBuilder("(")
        for (p in m.parameterTypes) {
            sb.append(descriptorOf(p))
        }
        sb.append(')').append(descriptorOf(m.returnType))
        return sb.toString()
    }

    private fun descriptorOf(type: Class<*>): String {
        return when {
            type == Boolean::class.javaPrimitiveType -> "Z"
            type == Int::class.javaPrimitiveType -> "I"
            type == Long::class.javaPrimitiveType -> "J"
            type == Void.TYPE -> "V"
            type.isArray -> "[" + descriptorOf(type.componentType!!)
            // Descriptors use slashes where Class#getName uses dots.
            else -> "L" + type.name.replace('.', '/') + ";"
        }
    }

    /** Look for `method` among an outer class' numbered inner classes. */
    private fun probeInnerMethod(
        loader: ClassLoader?,
        outer: String,
        method: String
    ): State {
        if (loader == null) {
            return State.UNKNOWN
        }
        var sawAny = false
        for (i in 1..12) {
            try {
                val clazz = Class.forName(outer + "$" + i, false, loader)
                val methods = clazz.declaredMethods
                if (methods.isEmpty()) {
                    continue
                }
                sawAny = true
                for (m in methods) {
                    if (m.name == method) {
                        return State.PRESENT
                    }
                }
            } catch (ignored: Throwable) {
                // Inner class indices are sparse; keep walking.
            }
        }
        return if (sawAny) State.ABSENT else State.UNKNOWN
    }

    // ---- class loaders ------------------------------------------------

    /**
     * A loader for PowerKeeper's code, or `null`.
     *
     * `CONTEXT_INCLUDE_CODE` is the flag that actually hands back the
     * other package's loader; the context is useless for this without it. The
     * APK path is the fallback, for builds where the context route is blocked.
     */
    private fun powerKeeperLoader(context: Context): ClassLoader? {
        try {
            val other = context.createPackageContext(
                PK,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val cl = other?.classLoader
            if (reaches(cl, "com.miui.powerkeeper.utils.NetdExecutor")) {
                return cl
            }
        } catch (ignored: Throwable) {
            // Fall through to the APK.
        }
        return loaderForInstalledPackage(context, PK)
    }

    private fun loaderForInstalledPackage(
        context: Context,
        packageName: String
    ): ClassLoader? {
        return try {
            val ai = context.packageManager
                .getApplicationInfo(packageName, 0)
            if (ai.sourceDir == null) {
                return null
            }
            val dexPath = StringBuilder(ai.sourceDir)
            val splits = ai.splitSourceDirs
            if (splits != null) {
                for (split in splits) {
                    if (split != null) {
                        dexPath.append(':').append(split)
                    }
                }
            }
            val cl = dalvik.system.PathClassLoader(
                dexPath.toString(),
                ClassLoader.getSystemClassLoader()
            )
            if (reaches(cl, "com.miui.powerkeeper.utils.NetdExecutor")) cl else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Every readable framework jar, preferred names first.
     *
     * The directories are world-readable, so listing them needs no
     * permission; a build that refuses the listing still gets the named jars.
     */
    private fun frameworkJars(): List<String> {
        val preferred = HashSet(FRAMEWORK_JAR_NAMES.asList())
        val named = ArrayList<String>()
        val others = ArrayList<String>()

        for (dir in FRAMEWORK_DIRS) {
            val files = try {
                File(dir).listFiles()
            } catch (ignored: Throwable) {
                // Unreadable directory: the named fallback below covers it.
                null
            } ?: continue
            for (f in files) {
                if (!f.isFile || !f.canRead() || f.length() == 0L) {
                    continue
                }
                val name = f.name
                if (!name.endsWith(".jar")) {
                    continue
                }
                val path = f.absolutePath
                if (preferred.contains(name)) {
                    if (!named.contains(path)) {
                        named.add(path)
                    }
                    continue
                }
                // MIUI classes are not always in a jar named after them.
                val lower = name.lowercase(Locale.ROOT)
                if ((lower.contains("service") || lower.contains("miui")) &&
                    !others.contains(path)
                ) {
                    others.add(path)
                }
            }
        }

        if (named.isEmpty() && others.isEmpty()) {
            for (dir in FRAMEWORK_DIRS) {
                for (name in FRAMEWORK_JAR_NAMES) {
                    val f = File(dir, name)
                    if (f.canRead() && f.length() > 0) {
                        named.add(f.absolutePath)
                    }
                }
            }
        }

        val jars = ArrayList(named)
        for (path in others) {
            if (jars.size >= MAX_FRAMEWORK_JARS) {
                break
            }
            jars.add(path)
        }
        return jars
    }

    private fun frameworkLoader(): ClassLoader? {
        val dexPath = StringBuilder()
        for (path in frameworkJars()) {
            if (dexPath.isNotEmpty()) {
                dexPath.append(':')
            }
            dexPath.append(path)
        }
        if (dexPath.isEmpty()) {
            return null
        }
        return try {
            val cl = dalvik.system.PathClassLoader(
                dexPath.toString(),
                ClassLoader.getSystemClassLoader()
            )
            // Sanity check before trusting it: on a ROM that filters these
            // members out of reflection every class comes back empty, and each
            // empty one would otherwise be reported as "missing". Only trust the
            // loader if at least one known class actually shows its members —
            // checked across several, since which jar carries what varies.
            for (probe in arrayOf(
                "com.android.server.am.ProcessRecord",
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.ProcessPolicy",
                "com.miui.server.greeze.GreezeManagerService"
            )) {
                if (declaresAnything(cl, probe)) {
                    return cl
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun reaches(loader: ClassLoader?, className: String): Boolean {
        if (loader == null) {
            return false
        }
        return try {
            Class.forName(className, false, loader)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun declaresAnything(loader: ClassLoader?, className: String): Boolean {
        if (loader == null) {
            return false
        }
        return try {
            val clazz = Class.forName(className, false, loader)
            clazz.declaredMethods.isNotEmpty() || clazz.declaredFields.isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Which HyperOS generation this ROM is, or 0 when it cannot be told.
     *
     * `ro.build.version.incremental` reads as `OS4.0.0.33` on
     * these builds, which is enough to tell the generations apart — and knowing
     * it is what separates "written for the other version" from "gone".
     */
    internal fun currentGeneration(): Int {
        val incremental = Build.VERSION.INCREMENTAL ?: return 0
        val upper = incremental.uppercase(Locale.ROOT)
        val os = upper.indexOf("OS")
        if (os < 0) {
            return 0
        }
        var i = os + 2
        while (i < upper.length && !Character.isDigit(upper[i])) {
            i++
        }
        if (i >= upper.length) {
            return 0
        }
        return when (upper[i]) {
            '3' -> 3
            '4' -> 4
            else -> 0
        }
    }
}
