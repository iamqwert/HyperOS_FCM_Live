package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether the classes and members the module hooks still exist on this ROM.
 *
 * <p>The module runs inside system_server and PowerKeeper, and neither of those
 * processes has a channel back to this screen: no shared preference, no binder,
 * no file both sides may touch. So this does not report what the hooks
 * <em>did</em>. It reports what they <em>can</em> do — every target is loaded
 * here, in this process, and asked whether it is still there. That answers the
 * question the screen exists for: "does this ROM still have the thing my module
 * hooks?", which is the usual reason a module looks fine and does nothing.
 *
 * <p>Both processes are loadable from here, which is why no target has to be
 * left unchecked:
 * <ul>
 *   <li>PowerKeeper is an installed package, so its APK can be handed to a
 *       class loader — provided the context is created with
 *       {@code CONTEXT_INCLUDE_CODE}, which is the flag that actually supplies
 *       the other package's loader. Without it {@code getClassLoader()} returns
 *       <em>ours</em>, every PowerKeeper class comes back not-found, and the
 *       whole screen silently degrades to "not checkable".</li>
 *   <li>system_server's classes are not on an app's boot classpath, but they
 *       ship as plain readable jars under {@code /system/framework}, so those
 *       jars can be handed to a class loader too.</li>
 * </ul>
 *
 * <p>What this still cannot do is prove a hook is <em>installed</em> — that
 * needs the module's own log line, which is why the screen carries the command
 * for it. And a target can come back {@link State#UNKNOWN} when the ROM hides
 * its members from reflection; that is reported as "not checkable" rather than
 * guessed as "missing", because a false alarm is worse than a shrug.
 *
 * <p>An {@link State#ABSENT} is usually not a fault: the module carries hooks
 * for several HyperOS generations and probes them independently, so a missing
 * target normally means "written for another version". {@link Expect} says
 * which, and the current generation decides whether that absence is expected.
 */
public final class HookStatus {

    private static final String PK = "com.miui.powerkeeper";

    /**
     * Directories where system_server's classes ship on a normal build.
     *
     * <p>Looked up by listing these rather than by naming files, because which
     * jar carries what is not fixed: on HyperOS 4 the MIUI classes the module
     * hooks — everything under {@code com.miui.server} — are absent from
     * {@code services.jar} entirely (zero matches across all four of its dex
     * files) and live in {@code miui-services.jar}. A hardcoded list that gets
     * that name wrong leaves two thirds of the system_server targets silently
     * "not checkable", which looks exactly like a ROM that hides its members.
     */
    private static final String[] FRAMEWORK_DIRS = {
            "/system/framework",
            "/system/system_ext/framework",
            "/system/product/framework",
            "/system/vendor/framework",
    };

    /** Jar names to prefer, and the fallback when a listing is not possible. */
    private static final String[] FRAMEWORK_JAR_NAMES = {
            "services.jar", "miui-services.jar", "miui-framework.jar", "framework.jar",
    };

    /**
     * Upper bound on jars handed to one class loader: every extra jar is a dex
     * the loader has to open, and ten is far more than a build ships.
     */
    private static final int MAX_FRAMEWORK_JARS = 10;

    /** Which side of the module a target belongs to; also how rows are grouped. */
    public enum Side {
        POWERKEEPER(R.string.status_group_powerkeeper),
        SYSTEM_SERVER(R.string.status_group_system_server);

        final int labelRes;

        Side(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** How a target turned out. */
    public enum State { PRESENT, ABSENT, UNKNOWN }

    /**
     * Which HyperOS generation a target belongs to — the difference between
     * "this ROM moved on" and "something is wrong".
     */
    public enum Expect {
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
    public static final class Item {
        public final String target;
        public final Side side;
        public final State state;
        public final Expect expect;

        Item(String target, Side side, State state, Expect expect) {
            this.target = target;
            this.side = side;
            this.state = state;
            this.expect = expect;
        }

        /** True when this absence is worth flagging rather than explaining. */
        public boolean isUnexpectedAbsence() {
            if (state != State.ABSENT) {
                return false;
            }
            if (expect == Expect.ALL) {
                return true;
            }
            // OPTIONAL and NONE both fall through: neither is a fault.
            if (expect == Expect.NONE || expect == Expect.OPTIONAL) {
                return false;
            }
            int gen = currentGeneration();
            if (gen == 0) {
                // Unknown ROM: only claim a miss for the generation-agnostic
                // targets, otherwise every cross-version hook looks broken.
                return false;
            }
            return (gen == 3 && expect == Expect.HYPEROS_3)
                    || (gen == 4 && expect == Expect.HYPEROS_4);
        }
    }

    private HookStatus() {
    }

    /**
     * Probe every target. Cheap enough for a background thread, which is what
     * the caller uses: defining classes from another package's dex is the
     * expensive part and must not run on the main thread.
     */
    public static List<Item> probe(Context context) {
        ClassLoader powerKeeper = powerKeeperLoader(context);
        ClassLoader framework = frameworkLoader();
        List<Item> items = new ArrayList<>();

        // ---- 电量和性能 / PowerKeeper ------------------------------------
        method(items, powerKeeper, Side.POWERKEEPER,
                "NetdExecutor#initGmsChain",
                "com.miui.powerkeeper.utils.NetdExecutor", "initGmsChain",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "NetdExecutor#setGmsDnsBlockerState",
                "com.miui.powerkeeper.utils.NetdExecutor", "setGmsDnsBlockerState",
                Expect.ALL);
        method(items, powerKeeper, Side.POWERKEEPER,
                "NetdExecutor#setGmsChainState",
                "com.miui.powerkeeper.utils.NetdExecutor", "setGmsChainState",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "NetdExecutor#execute",
                "com.miui.powerkeeper.utils.NetdExecutor", "execute",
                Expect.ALL);

        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateGmsAlarm",
                "com.miui.powerkeeper.utils.GmsObserver", "updateGmsAlarm",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateGmsNetWork",
                "com.miui.powerkeeper.utils.GmsObserver", "updateGmsNetWork",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateGoogleReletivesWakelock",
                "com.miui.powerkeeper.utils.GmsObserver",
                "updateGoogleReletivesWakelock", Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#disableGms",
                "com.miui.powerkeeper.utils.GmsObserver", "disableGms",
                Expect.HYPEROS_3);
        // A HyperOS 4 name that the OS 3 build does not ship either — verified
        // against both 4.2.00 builds, so absence is the normal outcome.
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#disableGmsApps",
                "com.miui.powerkeeper.utils.GmsObserver", "disableGmsApps",
                Expect.NONE);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateGmsEnabled",
                "com.miui.powerkeeper.utils.GmsObserver", "updateGmsEnabled",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateGmsState",
                "com.miui.powerkeeper.utils.GmsObserver", "updateGmsState",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateGmsInstalled",
                "com.miui.powerkeeper.utils.GmsObserver", "updateGmsInstalled",
                Expect.HYPEROS_3);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#updateFrameworkGmsNetStatus",
                "com.miui.powerkeeper.utils.GmsObserver",
                "updateFrameworkGmsNetStatus", Expect.HYPEROS_4);
        method(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#onGoogleReachabilityChanged",
                "com.miui.powerkeeper.utils.GmsObserver",
                "onGoogleReachabilityChanged", Expect.ALL);
        // Matched by signature, not name: "c" is a compiler-generated name and
        // OS 3 has an unrelated c(GmsObserver) returning a listener. Matching on
        // the name alone would report this as present on OS 3 when the overload
        // the module needs is not there.
        signature(items, powerKeeper, Side.POWERKEEPER,
                "GmsObserver#c(GmsObserver, boolean)",
                "com.miui.powerkeeper.utils.GmsObserver", "c",
                "(Lcom/miui/powerkeeper/utils/GmsObserver;Z)V", Expect.HYPEROS_4);
        // The disconnect listener is an anonymous inner class whose index drifts
        // between builds (GmsObserver$5 on HyperOS 3, $2 on HyperOS 4), so scan
        // for it instead of hard-coding one name.
        items.add(new Item("GmsObserver$*#googleNetworkDisconnect", Side.POWERKEEPER,
                probeInnerMethod(powerKeeper, "com.miui.powerkeeper.utils.GmsObserver",
                        "googleNetworkDisconnect"), Expect.ALL));
        // getDozeWhiteListApps ships with more than one signature, so the module
        // matches it by name; do the same here.
        method(items, powerKeeper, Side.POWERKEEPER,
                "GlobalFeatureConfigureHelper#getDozeWhiteListApps",
                "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper",
                "getDozeWhiteListApps", Expect.ALL);

        // ---- system_server ------------------------------------------------
        method(items, framework, Side.SYSTEM_SERVER,
                "GreezeManagerService#isAllowBroadcast",
                "com.miui.server.greeze.GreezeManagerService", "isAllowBroadcast",
                Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "GreezeManagerService#getPackageNameFromUid",
                "com.miui.server.greeze.GreezeManagerService", "getPackageNameFromUid",
                Expect.OPTIONAL);
        method(items, framework, Side.SYSTEM_SERVER,
                "GreezeManagerService#deferBroadcastForMiui",
                "com.miui.server.greeze.GreezeManagerService", "deferBroadcastForMiui",
                Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "GreezeManagerService#triggerGMSLimitAction",
                "com.miui.server.greeze.GreezeManagerService", "triggerGMSLimitAction",
                Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "GreezeManagerService#updateGmsNetStatus",
                "com.miui.server.greeze.GreezeManagerService", "updateGmsNetStatus",
                Expect.HYPEROS_4);
        // Only read on the no-arg overload path, and the hook still installs
        // without it — it just cannot clear the flag.
        field(items, framework, Side.SYSTEM_SERVER,
                "GreezeManagerService#mGmsLimitEnabled",
                "com.miui.server.greeze.GreezeManagerService", "mGmsLimitEnabled",
                Expect.OPTIONAL);
        method(items, framework, Side.SYSTEM_SERVER,
                "DomesticPolicyManager#deferBroadcast",
                "com.miui.server.greeze.DomesticPolicyManager", "deferBroadcast",
                Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "ListAppsManager#isInWhiteList",
                "com.miui.server.greeze.power.ListAppsManager", "isInWhiteList",
                Expect.ALL);
        // Both fields are looked up under a name and then a constant-style
        // fallback, so either spelling existing counts as present.
        memberAnyOf(items, framework, Side.SYSTEM_SERVER,
                "ListAppsManager#mSystemBlackList",
                "com.miui.server.greeze.power.ListAppsManager", true,
                new String[]{"mSystemBlackList", "SYSTEM_BLACK_LIST"}, Expect.ALL);
        memberAnyOf(items, framework, Side.SYSTEM_SERVER,
                "ListAppsManager#mUseDataWhiteList",
                "com.miui.server.greeze.power.ListAppsManager", true,
                new String[]{"mUseDataWhiteList", "USE_DATA_WHITE_LIST"}, Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "BroadcastQueueModernStubImpl#checkApplicationAutoStart",
                "com.android.server.am.BroadcastQueueModernStubImpl",
                "checkApplicationAutoStart", Expect.ALL);
        field(items, framework, Side.SYSTEM_SERVER,
                "BroadcastRecord#callerPackage",
                "com.android.server.am.BroadcastRecord", "callerPackage", Expect.ALL);
        field(items, framework, Side.SYSTEM_SERVER,
                "BroadcastRecord#intent",
                "com.android.server.am.BroadcastRecord", "intent", Expect.ALL);
        field(items, framework, Side.SYSTEM_SERVER,
                "ProcessRecord#info",
                "com.android.server.am.ProcessRecord", "info", Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "ProcessRecord#getApplicationInfo",
                "com.android.server.am.ProcessRecord", "getApplicationInfo", Expect.ALL);
        field(items, framework, Side.SYSTEM_SERVER,
                "ProcessManagerService#mPkms",
                "com.android.server.am.ProcessManagerService", "mPkms", Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "ProcessPolicy#getWhiteList",
                "com.android.server.am.ProcessPolicy", "getWhiteList", Expect.ALL);
        field(items, framework, Side.SYSTEM_SERVER,
                "AwareResourceControl#mNoNetworkBlackUids",
                "com.miui.server.greeze.power.AwareResourceControl",
                "mNoNetworkBlackUids", Expect.ALL);
        method(items, framework, Side.SYSTEM_SERVER,
                "InternationalPolicyManager#isPushApp",
                "com.miui.server.greeze.InternationalPolicyManager", "isPushApp",
                Expect.ALL);
        // Not hooked — the isPushApp hook recognises its caller by this method
        // name on the stack. If a build renames it the branch stops matching
        // without any error, and GMS then gets network-restricted as designed,
        // which is the overnight "push stopped while the screen was off" case.
        // OPTIONAL because the caller test also accepts any class system_server
        // loaded, so the name may legitimately live elsewhere.
        method(items, framework, Side.SYSTEM_SERVER,
                "DomesticPolicyManager#isRestrictNet",
                "com.miui.server.greeze.DomesticPolicyManager", "isRestrictNet",
                Expect.OPTIONAL);
        method(items, framework, Side.SYSTEM_SERVER,
                "InternationalPolicyManager#isRestrictNet",
                "com.miui.server.greeze.InternationalPolicyManager", "isRestrictNet",
                Expect.OPTIONAL);
        method(items, framework, Side.SYSTEM_SERVER,
                "ProcessCleanerBase#isForceStopEnable",
                "com.android.server.am.ProcessCleanerBase", "isForceStopEnable",
                Expect.ALL);
        field(items, framework, Side.SYSTEM_SERVER,
                "ActivityManagerService#mContext",
                "com.android.server.am.ActivityManagerService", "mContext", Expect.ALL);
        // Stopped-package delivery and the power exemption hang off whichever
        // broadcast entry point this release has; losing both is the one miss
        // that takes those two features with it, which is why it is not OPTIONAL.
        memberAnyOf(items, framework, Side.SYSTEM_SERVER,
                "ActivityManagerService#broadcastIntent*",
                "com.android.server.am.ActivityManagerService", false,
                new String[]{"broadcastIntentWithFeature", "broadcastIntent"}, Expect.ALL);
        // Renamed between releases; the binder uid is the fallback, so this one
        // only costs the uid lookup.
        memberAnyOf(items, framework, Side.SYSTEM_SERVER,
                "ActivityManagerService#getRecordForApp*",
                "com.android.server.am.ActivityManagerService", false,
                new String[]{"getRecordForAppLOSP", "getRecordForAppLocked"},
                Expect.OPTIONAL);

        return items;
    }

    // ---- target helpers ------------------------------------------------

    private static void method(List<Item> items, ClassLoader loader, Side side,
                               String target, String className, String member,
                               Expect expect) {
        items.add(new Item(target, side,
                probeMember(loader, className, member, false), expect));
    }

    private static void field(List<Item> items, ClassLoader loader, Side side,
                              String target, String className, String member,
                              Expect expect) {
        items.add(new Item(target, side,
                probeMember(loader, className, member, true), expect));
    }

    /**
     * For names that are not unique enough to match on alone — compiler
     * generated ones above all, where a same-named overload with a different
     * signature is a different method entirely.
     */
    private static void signature(List<Item> items, ClassLoader loader, Side side,
                                  String target, String className, String member,
                                  String descriptor, Expect expect) {
        items.add(new Item(target, side,
                probeSignature(loader, className, member, descriptor), expect));
    }

    /**
     * For members the module looks up under a name and then a fallback —
     * present if either spelling exists, so the fallback is not read as a miss.
     */
    private static void memberAnyOf(List<Item> items, ClassLoader loader, Side side,
                                    String target, String className, boolean isField,
                                    String[] members, Expect expect) {
        State state = State.UNKNOWN;
        for (String member : members) {
            State s = probeMember(loader, className, member, isField);
            if (s == State.PRESENT) {
                state = State.PRESENT;
                break;
            }
            if (s == State.ABSENT) {
                state = State.ABSENT;
            }
        }
        items.add(new Item(target, side, state, expect));
    }

    /**
     * Is {@code member} still declared on {@code className}?
     *
     * <p>Matched by name, not signature: signatures drift between builds, and
     * the module itself falls back to name matching for the same reason.
     *
     * <p>A class that loads but declares nothing is {@link State#UNKNOWN}
     * rather than {@link State#ABSENT} — that is the signature of a ROM hiding
     * its members from reflection, and reporting it as "missing" would raise an
     * alarm over something that is merely invisible.
     */
    private static State probeMember(ClassLoader loader, String className,
                                     String member, boolean isField) {
        if (loader == null) {
            return State.UNKNOWN;
        }
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            if (isField) {
                Field[] fields = clazz.getDeclaredFields();
                if (fields.length == 0) {
                    return State.UNKNOWN;
                }
                for (Field f : fields) {
                    if (f.getName().equals(member)) {
                        return State.PRESENT;
                    }
                }
                return State.ABSENT;
            }
            Method[] methods = clazz.getDeclaredMethods();
            if (methods.length == 0) {
                return State.UNKNOWN;
            }
            for (Method m : methods) {
                if (m.getName().equals(member)) {
                    return State.PRESENT;
                }
            }
            return State.ABSENT;
        } catch (Throwable t) {
            // Not found, unreadable, or fails to link: none of those mean
            // "the member is gone", so none of them may read as ABSENT.
            return State.UNKNOWN;
        }
    }

    /**
     * Is there a method named {@code member} with exactly this descriptor?
     *
     * <p>Same {@link State#UNKNOWN} guard as {@link #probeMember}: a class that
     * shows no members at all is invisible here, not empty.
     */
    private static State probeSignature(ClassLoader loader, String className,
                                        String member, String descriptor) {
        if (loader == null) {
            return State.UNKNOWN;
        }
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            Method[] methods = clazz.getDeclaredMethods();
            if (methods.length == 0) {
                return State.UNKNOWN;
            }
            for (Method m : methods) {
                if (m.getName().equals(member) && descriptorOf(m).equals(descriptor)) {
                    return State.PRESENT;
                }
            }
            return State.ABSENT;
        } catch (Throwable t) {
            return State.UNKNOWN;
        }
    }

    /** JVM type descriptor of a method, in the same shape as the dex uses. */
    private static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(descriptorOf(p));
        }
        sb.append(')').append(descriptorOf(m.getReturnType()));
        return sb.toString();
    }

    private static String descriptorOf(Class<?> type) {
        if (type == boolean.class) {
            return "Z";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == void.class) {
            return "V";
        }
        if (type.isArray()) {
            return "[" + descriptorOf(type.getComponentType());
        }
        // Descriptors use slashes where Class#getName uses dots.
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /** Look for {@code method} among an outer class' numbered inner classes. */
    private static State probeInnerMethod(ClassLoader loader, String outer,
                                          String method) {
        if (loader == null) {
            return State.UNKNOWN;
        }
        boolean sawAny = false;
        for (int i = 1; i <= 12; i++) {
            try {
                Class<?> clazz = Class.forName(outer + "$" + i, false, loader);
                Method[] methods = clazz.getDeclaredMethods();
                if (methods.length == 0) {
                    continue;
                }
                sawAny = true;
                for (Method m : methods) {
                    if (m.getName().equals(method)) {
                        return State.PRESENT;
                    }
                }
            } catch (Throwable ignored) {
                // Inner class indices are sparse; keep walking.
            }
        }
        return sawAny ? State.ABSENT : State.UNKNOWN;
    }

    // ---- class loaders ------------------------------------------------

    /**
     * A loader for PowerKeeper's code, or {@code null}.
     *
     * <p>{@code CONTEXT_INCLUDE_CODE} is the flag that actually hands back the
     * other package's loader; the context is useless for this without it. The
     * APK path is the fallback, for builds where the context route is blocked.
     */
    private static ClassLoader powerKeeperLoader(Context context) {
        try {
            Context other = context.createPackageContext(PK,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader cl = other != null ? other.getClassLoader() : null;
            if (reaches(cl, "com.miui.powerkeeper.utils.NetdExecutor")) {
                return cl;
            }
        } catch (Throwable ignored) {
            // Fall through to the APK.
        }
        return loaderForInstalledPackage(context, PK);
    }

    private static ClassLoader loaderForInstalledPackage(Context context,
                                                         String packageName) {
        try {
            ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            if (ai == null || ai.sourceDir == null) {
                return null;
            }
            StringBuilder dexPath = new StringBuilder(ai.sourceDir);
            if (ai.splitSourceDirs != null) {
                for (String split : ai.splitSourceDirs) {
                    if (split != null) {
                        dexPath.append(':').append(split);
                    }
                }
            }
            ClassLoader cl = new dalvik.system.PathClassLoader(dexPath.toString(),
                    ClassLoader.getSystemClassLoader());
            return reaches(cl, "com.miui.powerkeeper.utils.NetdExecutor") ? cl : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A loader for system_server's classes, or {@code null}.
     *
     * <p>Those classes are not on an app's boot classpath, but they ship as
     * ordinary world-readable jars, so the jars are enough. Read-only, and no
     * behaviour in either process is touched.
     */
    /**
     * Every readable framework jar, preferred names first.
     *
     * <p>The directories are world-readable, so listing them needs no
     * permission; a build that refuses the listing still gets the named jars.
     */
    private static List<String> frameworkJars() {
        Set<String> preferred = new HashSet<>(Arrays.asList(FRAMEWORK_JAR_NAMES));
        List<String> named = new ArrayList<>();
        List<String> others = new ArrayList<>();

        for (String dir : FRAMEWORK_DIRS) {
            File[] files = null;
            try {
                files = new File(dir).listFiles();
            } catch (Throwable ignored) {
                // Unreadable directory: the named fallback below covers it.
            }
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile() || !f.canRead() || f.length() == 0) {
                    continue;
                }
                String name = f.getName();
                if (!name.endsWith(".jar")) {
                    continue;
                }
                String path = f.getAbsolutePath();
                if (preferred.contains(name)) {
                    if (!named.contains(path)) {
                        named.add(path);
                    }
                    continue;
                }
                // MIUI classes are not always in a jar named after them.
                String lower = name.toLowerCase(Locale.ROOT);
                if ((lower.contains("service") || lower.contains("miui"))
                        && !others.contains(path)) {
                    others.add(path);
                }
            }
        }

        if (named.isEmpty() && others.isEmpty()) {
            for (String dir : FRAMEWORK_DIRS) {
                for (String name : FRAMEWORK_JAR_NAMES) {
                    File f = new File(dir, name);
                    if (f.canRead() && f.length() > 0) {
                        named.add(f.getAbsolutePath());
                    }
                }
            }
        }

        List<String> jars = new ArrayList<>(named);
        for (String path : others) {
            if (jars.size() >= MAX_FRAMEWORK_JARS) {
                break;
            }
            jars.add(path);
        }
        return jars;
    }

    private static ClassLoader frameworkLoader() {
        StringBuilder dexPath = new StringBuilder();
        for (String path : frameworkJars()) {
            if (dexPath.length() > 0) {
                dexPath.append(':');
            }
            dexPath.append(path);
        }
        if (dexPath.length() == 0) {
            return null;
        }
        try {
            ClassLoader cl = new dalvik.system.PathClassLoader(dexPath.toString(),
                    ClassLoader.getSystemClassLoader());
            // Sanity check before trusting it: on a ROM that filters these
            // members out of reflection every class comes back empty, and each
            // empty one would otherwise be reported as "missing". Only trust the
            // loader if at least one known class actually shows its members —
            // checked across several, since which jar carries what varies.
            for (String probe : new String[]{
                    "com.android.server.am.ProcessRecord",
                    "com.android.server.am.ActivityManagerService",
                    "com.android.server.am.ProcessPolicy",
                    "com.miui.server.greeze.GreezeManagerService"}) {
                if (declaresAnything(cl, probe)) {
                    return cl;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean reaches(ClassLoader loader, String className) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean declaresAnything(ClassLoader loader, String className) {
        if (loader == null) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            return clazz.getDeclaredMethods().length > 0
                    || clazz.getDeclaredFields().length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Which HyperOS generation this ROM is, or 0 when it cannot be told.
     *
     * <p>{@code ro.build.version.incremental} reads as {@code OS4.0.0.33…} on
     * these builds, which is enough to tell the generations apart — and knowing
     * it is what separates "written for the other version" from "gone".
     */
    static int currentGeneration() {
        String incremental = Build.VERSION.INCREMENTAL;
        if (incremental == null) {
            return 0;
        }
        String upper = incremental.toUpperCase(Locale.ROOT);
        int os = upper.indexOf("OS");
        if (os < 0) {
            return 0;
        }
        int i = os + 2;
        while (i < upper.length() && !Character.isDigit(upper.charAt(i))) {
            i++;
        }
        if (i >= upper.length()) {
            return 0;
        }
        char major = upper.charAt(i);
        if (major == '3') {
            return 3;
        }
        if (major == '4') {
            return 4;
        }
        return 0;
    }
}
