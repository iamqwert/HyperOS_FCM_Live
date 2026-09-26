package io.github.howard20181.hyperos.fcmlive;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerExemptionManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final List<String> CN_DEFER_BROADCAST = Arrays.asList("com.google.android.intent.action.GCM_RECONNECT", "com.google.android.gcm.DISCONNECTED", "com.google.android.gcm.CONNECTED", "com.google.android.gms.gcm.HEARTBEAT_ALARM");
    // The four FCM markers, package-visible: the settings screen asks the same
    // question when it marks apps as FCM-supported, and the two must not drift
    // apart. Shipping any one of them is enough.
    static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    static final String ACTION_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    static final String FCM_MESSAGING_SERVICE_CLASS =
            "com.google.firebase.messaging.FirebaseMessagingService";
    static final String FCM_IID_RECEIVER_CLASS =
            "com.google.firebase.iid.FirebaseInstanceIdReceiver";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent";
    private Pair<String, ClassLoader> param;
    private Context systemContext;
    /**
     * Counts behind the end-of-install summary line.
     *
     * <p>Every hook goes through {@link #hookE}, so "installed" needs no
     * bookkeeping at the call sites. "Absent" is counted by {@link #logSkip},
     * which every "this ROM does not have it" path already calls. Both live on
     * the instance, and there is one instance per process, so system_server and
     * PowerKeeper each report their own numbers.
     */
    private int hooksInstalled;
    private int hookTargetsAbsent;

    private HookBuilder hookE(Executable executable) {
        var builder = hook(executable);
        hooksInstalled++;

        if (getApiVersion() >= 102) {
            builder.setId(executable.toGenericString());
        }

        return builder;
    }

    /**
     * Report a hook target this ROM does not have, and count it.
     *
     * <p>These are expected, not failures: the module carries hooks for several
     * PowerKeeper generations and probes each one, so an "absent" here usually
     * means "written for a different ROM version". Counting them turns that from
     * a wall of look-alike lines into one number the summary can report — and
     * the number is what tells a "nothing landed" install apart from a "two of
     * sixteen targets are for another ROM" one.
     */
    private void logSkip(String message) {
        logSkip(message, Log.INFO);
    }

    /**
     * Same as {@link #logSkip}, for targets that ship on another HyperOS
     * generation or on none at all.
     *
     * <p>Still counted, because the summary number is what separates "nothing
     * landed" from "several targets are for another ROM". Just logged at DEBUG:
     * on HyperOS 4 these account for ten of the absent targets, and at INFO they
     * bury the one line that would actually mean something.
     */
    private void logSkipOtherGeneration(String message) {
        logSkip(message, Log.DEBUG);
    }

    private void logSkip(String message, int level) {
        hookTargetsAbsent++;
        log(level, TAG, message);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        this.param = Pair.create("system", classLoader);
        try {
            hookSystemServer(classLoader);
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook SystemServer", tr);
        }
        logSummary("system_server");
    }

    /**
     * One line saying what landed, so "is the module running at all?" is
     * answerable without reading everything around it.
     *
     * <p>The point is the difference between the two numbers. A healthy install
     * reports a handful of hooks installed and a handful of targets absent — the
     * absent ones being hooks written for other ROM generations. Zero installed
     * means nothing landed and the module is not doing anything; that is the case
     * worth chasing, and before this line existed it looked exactly like a quiet
     * successful install, because success is otherwise silent.
     */
    private void logSummary(String process) {
        log(Log.INFO, TAG, "HyperFCMLive active in " + process + ": "
                + hooksInstalled + " hook(s) installed, "
                + hookTargetsAbsent + " target(s) absent on this ROM");
    }

    private void hookSystemServer(ClassLoader classLoader) {
        try {
            hookAllowlist();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook allowlist receiver", t);
        }
        try {
            hookGreezeManagerService(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService", t);
        }
        try {
            hookDomesticPolicyManager(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook DomesticPolicyManager", t);
        }
        try {
            hookListAppsManager(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager", t);
        }
        try {
            hookBroadcastQueueModernStubImpl(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook BroadcastQueueModernStubImpl", t);
        }
        try {
            hookProcessPolicy(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook ProcessPolicy", t);
        }
        try {
            hookAwareResourceControl(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook AwareResourceControl", t);
        }
        try {
            hookActivityManagerService(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook ActivityManagerService", t);
        }
        try {
            hookInternationalPolicyManager(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook InternationalPolicyManager", t);
        }
        try {
            hookProcessCleanerBase(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook ProcessCleanerBase", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var packageName = param.getPackageName();
        var classLoader = param.getClassLoader();
        this.param = Pair.create(packageName, classLoader);
        try {
            hookPackage(packageName, classLoader);
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook package", tr);
        }
        logSummary(packageName);
    }

    private void hookPackage(String packageName, ClassLoader classLoader) {
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                hookGmsObserver(classLoader);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Failed to hook GmsObserver", t);
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", t);
            }
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        // Hot reload of system_server hooks is unreliable; a full reboot is the
        // supported path. We still support reload below, but advise rebooting.
        log(Log.WARN, TAG, "Hot reload requested — a full reboot is recommended for reliability");
        param.setSavedInstanceState(this.param);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        // Clean reload: remove every previous hook so the re-setup below starts
        // fresh. Without this, old handles were never unhooked and stacked
        // duplicate hooks made the reload appear ineffective.
        param.getOldHookHandles().forEach(h -> {
            try {
                h.unhook();
            } catch (Throwable ignored) {
            }
        });
        if (param.getSavedInstanceState() instanceof Pair<?, ?> pair
                && pair.first instanceof String packageName
                && pair.second instanceof ClassLoader classLoader) {
            this.param = Pair.create(packageName, classLoader);
            try {
                if (param.isSystemServer()) {
                    hookSystemServer(classLoader);
                } else {
                    hookPackage(packageName, classLoader);
                }
            } catch (Throwable tr) {
                log(Log.ERROR, TAG, "Hot reload failed", tr);
            }
        }
    }

    private void hookGreezeManagerService(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        var GreezeManagerServiceClass = classLoader.loadClass("com.miui.server.greeze.GreezeManagerService");
        try {
            // am.ProcessRecord app = BroadcastProcessQueue.app, app nullable
            // but when app is null, this method will not call
            // calleePkgName = (app.info == null || app.info.packageName == null) ? app.processName : app.info.packageName
            // It could be the process name.
            // boolean isAllowBroadcast(int callerUid, String callerPkgName, int calleeUid, String calleePkgName, String action)
            var isAllowBroadcastMethod = findMethod(GreezeManagerServiceClass,
                    "isAllowBroadcast", int.class, String.class, int.class, String.class, String.class);
            // Optional helper, not a prerequisite: when a release drops it the
            // hook still works off the raw callee argument, which is what makes
            // the GMS reconnect/heartbeat branch match at all. Losing the uid
            // lookup used to take the whole isAllowBroadcast hook down with it.
            var getPackageNameFromUidMethod = findMethod(GreezeManagerServiceClass,
                    "getPackageNameFromUid", int.class);
            if (getPackageNameFromUidMethod != null) {
                getPackageNameFromUidMethod.setAccessible(true);
            } else {
                log(Log.INFO, TAG, "GreezeManagerService#getPackageNameFromUid absent;"
                        + " isAllowBroadcast falls back to the raw callee argument");
            }
            // Note: no early return here. A missing method must only cost its own
            // hook — the rest of this group (deferBroadcastForMiui, the GMS limit
            // hooks) still has to be given its chance.
            if (isAllowBroadcastMethod == null) {
                log(Log.ERROR, TAG, "GreezeManagerService#isAllowBroadcast absent, skip");
            } else {
                final Method uidLookup = getPackageNameFromUidMethod;
                hookE(isAllowBroadcastMethod).intercept(chain -> {
                    String calleePkgName = chain.getArg(3) instanceof String calleeProcessName ? calleeProcessName : null;
                    if (uidLookup != null) {
                        try {
                            if (chain.getArg(2) instanceof Integer calleeUid
                                    && getInvoker(uidLookup).invoke(chain.getThisObject(), calleeUid) instanceof String calleePackageName) {
                                calleePkgName = calleePackageName;
                            }
                        } catch (Exception e) {
                            log(Log.ERROR, TAG, "Failed to get callee package name", e);
                        }
                    }
                    if (chain.getArg(4) instanceof String action
                            && (((chain.getArg(1) instanceof String callerPkgName
                            // callerPkgName get from intent or BroadcastRecord.callerPackage,
                            // both are nullable, but they won't become null in FCM broadcasts.
                            && GMS_PACKAGE_NAME.equals(callerPkgName)
                            && ACTION_REMOTE_INTENT.equals(action))
                            // Strict mode: the callee is the app this broadcast is
                            // meant for, so it is what decides whether the module
                            // overrides Greeze here at all. The GMS-side branch
                            // below is about the transport and is never gated.
                            && shouldApply(calleePkgName))
                            || ((GMS_PACKAGE_NAME.equals(calleePkgName)
                            || GMS_PERSISTENT_PROCESS_NAME.equals(calleePkgName))
                            && CN_DEFER_BROADCAST.contains(action)))) {
                        return true;
                    }
                    return chain.proceed();
                });
                deoptimize(isAllowBroadcastMethod);
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#isAllowBroadcast", e);
        }
        try {
            // boolean deferBroadcastForMiui(String action)
            var deferBroadcastForMiuiMethod = GreezeManagerServiceClass.getDeclaredMethod("deferBroadcastForMiui", String.class);
            hookE(deferBroadcastForMiuiMethod).intercept(chain -> {
                if (chain.getArg(0) instanceof String action
                        && CN_DEFER_BROADCAST.contains(action)) {
                    return false;
                }
                return chain.proceed();
            });
            deoptimize(deferBroadcastForMiuiMethod);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#deferBroadcastForMiui", e);
        }
        Method triggerGMSLimitActionMethod;
        try {
            try {
                triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction", boolean.class);
            } catch (NoSuchMethodException ignored) {
                triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction");
            }
            hookE(triggerGMSLimitActionMethod).intercept(chain -> {
                if (!chain.getArgs().isEmpty()) {
                    var args = chain.getArgs().toArray();
                    args[0] = false;
                    return chain.proceed(args);
                }
                // No-arg overload: clear the flag the method reads, so the GMS
                // limit can never be switched on. Every step is isolated: this
                // runs inside GreezeManagerService, so an exception escaping it
                // would take the host service down with it. The module has to
                // degrade to "not applied", never crash the host — which is
                // what the other hooks in this method already do.
                try {
                    var mGmsLimitEnabled = GreezeManagerServiceClass.getDeclaredField("mGmsLimitEnabled");
                    UnsafeUtils.INSTANCE.setBooleanField(mGmsLimitEnabled, chain.getThisObject(), false);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to clear mGmsLimitEnabled", t);
                }
                return chain.proceed();
            });
            deoptimize(triggerGMSLimitActionMethod);
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#triggerGMSLimitAction", e);
        }
        // HyperOS 4 PowerKeeper uses IGreezeManager.updateGmsNetStatus(boolean limit).
        try {
            var updateGmsNetStatusMethod = GreezeManagerServiceClass.getDeclaredMethod("updateGmsNetStatus", boolean.class);
            hookE(updateGmsNetStatusMethod).intercept(chain -> {
                var args = chain.getArgs().toArray();
                if (args.length > 0) {
                    args[0] = false;
                }
                return chain.proceed(args);
            });
            deoptimize(updateGmsNetStatusMethod);
        } catch (NoSuchMethodException e) {
            logSkip("GreezeManagerService#updateGmsNetStatus absent, skip");
        }
    }

    private void hookDomesticPolicyManager(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var DomesticPolicyManagerClass = classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager");
        // boolean deferBroadcast(String action)
        var deferBroadcastMethod = DomesticPolicyManagerClass.getDeclaredMethod("deferBroadcast", String.class);
        hookE(deferBroadcastMethod).intercept(chain -> false);
        deoptimize(deferBroadcastMethod);
    }

    private void hookListAppsManager(ClassLoader classLoader) throws ClassNotFoundException {
        var ListAppsManagerClass = classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager");
        Field mSystemBlackListField = null;
        try {
            mSystemBlackListField = ListAppsManagerClass.getDeclaredField("mSystemBlackList");
        } catch (NoSuchFieldException e) {
            try {
                mSystemBlackListField = ListAppsManagerClass.getDeclaredField("SYSTEM_BLACK_LIST");
            } catch (NoSuchFieldException ex) {
                log(Log.ERROR, TAG, "Failed to find ListAppsManager.mSystemBlackList or ListAppsManager.SYSTEM_BLACK_LIST", e);
            }
        }
        if (mSystemBlackListField != null) {
            mSystemBlackListField.setAccessible(true);
            var PowerStrategyModeConstructors = ListAppsManagerClass.getDeclaredConstructors();
            for (var constructor : PowerStrategyModeConstructors) {
                Field finalMSystemBlackListField = mSystemBlackListField;
                hookE(constructor).intercept(chain -> {
                    try {
                        return chain.proceed();
                    } finally {
                        try {
                            var mSystemBlackList = (List<String>) finalMSystemBlackListField.get(chain.getThisObject());
                            if (mSystemBlackList != null) {
                                mSystemBlackList.remove(GMS_PACKAGE_NAME);
                            }
                        } catch (Exception e) {
                            log(Log.ERROR, TAG, "Failed to modify system blacklist", e);
                        }
                    }
                });
                deoptimize(constructor);
            }
        }
        try {
            var isInWhiteListMethod = ListAppsManagerClass.getDeclaredMethod("isInWhiteList", String.class);
            Field mUseDataWhiteListField = null;
            try {
                mUseDataWhiteListField = ListAppsManagerClass.getDeclaredField("mUseDataWhiteList");
            } catch (NoSuchFieldException e) {
                try {
                    mUseDataWhiteListField = ListAppsManagerClass.getDeclaredField("USE_DATA_WHITE_LIST");
                } catch (NoSuchFieldException ex) {
                    log(Log.ERROR, TAG, "Failed to find ListAppsManager.mUseDataWhiteList or ListAppsManager.USE_DATA_WHITE_LIST", e);
                }
            }
            if (mUseDataWhiteListField != null) {
                mUseDataWhiteListField.setAccessible(true);
                Field finalMUseDataWhiteListField = mUseDataWhiteListField;
                hookE(isInWhiteListMethod).intercept(chain -> {
                    try {
                        var mUseDataWhiteList = (Set<String>) finalMUseDataWhiteListField.get(chain.getThisObject());
                        if (mUseDataWhiteList != null) {
                            mUseDataWhiteList.add(GMS_PACKAGE_NAME);
                        }
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to modify use data whitelist", e);
                    }
                    return chain.proceed();
                });
            }
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager#isInWhiteList", e);
        }
    }

    private void hookBroadcastQueueModernStubImpl(ClassLoader classLoader) throws
            ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var BroadcastQueueModernStubImplClass = classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl");
        var BroadcastQueueClass = classLoader.loadClass("com.android.server.am.BroadcastQueue");
        var BroadcastRecordClass = classLoader.loadClass("com.android.server.am.BroadcastRecord");
        var callerPackageField = BroadcastRecordClass.getDeclaredField("callerPackage");
        callerPackageField.setAccessible(true);
        var intentField = BroadcastRecordClass.getDeclaredField("intent");
        intentField.setAccessible(true);
        var checkApplicationAutoStartMethod = BroadcastQueueModernStubImplClass.getDeclaredMethod("checkApplicationAutoStart", BroadcastQueueClass, BroadcastRecordClass, ResolveInfo.class);
        hookE(checkApplicationAutoStartMethod).intercept(chain -> {
            try {
                var broadcastRecord = chain.getArg(1);
                if (callerPackageField.get(broadcastRecord) instanceof String callerPackage
                        && GMS_PACKAGE_NAME.equals(callerPackage) // BroadcastRecord.callerPackage nullable
                        && intentField.get(broadcastRecord) instanceof Intent intent
                        && ACTION_REMOTE_INTENT.equals(intent.getAction())
                        // Auto-start only apps the user whitelisted; empty list = all.
                        && targetPackageOf(intent) instanceof String targetPackage
                        && shouldWake(targetPackage)) {
                    return true;
                }
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to modify BroadcastQueueModernStubImpl#checkApplicationAutoStart", e);
            }
            return chain.proceed();
        });
        deoptimize(checkApplicationAutoStartMethod);
    }

    private void hookProcessPolicy(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var ProcessPolicyClass = classLoader.loadClass("com.android.server.am.ProcessPolicy");
        var getWhiteListMethod = ProcessPolicyClass.getDeclaredMethod("getWhiteList", int.class);
        hookE(getWhiteListMethod).intercept(chain -> {
            var result = chain.proceed();
            // The copy is what guarantees *this* call sees GMS: the framework's
            // own list may be immutable (an UnsupportedOperationException here
            // would escape into ActivityManagerService).
            //
            // The in-place write is kept as well, best effort. PowerKeeper reads
            // this list through a cached field on some builds, and a caller that
            // reads the cache directly never sees a copy — 1.8.0 only ever wrote
            // in place, and dropping that is one of the differences between a
            // device that pushes overnight and one that does not.
            try {
                if (chain.getArg(0) instanceof Integer flags && (flags & 1) != 0
                        && result instanceof List<?> source) {
                    var whiteList = new ArrayList<Object>(source);
                    addIfAbsent(whiteList, GMS_PACKAGE_NAME);
                    addIfAbsent(whiteList, GMS_PERSISTENT_PROCESS_NAME);
                    addIfAbsentInPlace(source, GMS_PACKAGE_NAME);
                    addIfAbsentInPlace(source, GMS_PERSISTENT_PROCESS_NAME);
                    return whiteList;
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Failed to extend ProcessPolicy white list", t);
            }
            return result;
        });
    }

    /** Appends {@code value} unless an equal element is already present. */
    private static void addIfAbsent(List<Object> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    /**
     * Same, but on the framework's own list.
     *
     * <p>Only ever a bonus on top of the copy: an immutable or fixed-size list
     * throws, and that is answered by leaving it alone — which is where the copy
     * already carries the entry. Guarded by {@code contains} so a cached,
     * shared list cannot accumulate duplicates across calls.
     */
    @SuppressWarnings("unchecked")
    private static void addIfAbsentInPlace(List<?> target, String value) {
        if (target == null || target.contains(value)) {
            return;
        }
        try {
            ((List<Object>) target).add(value);
        } catch (Throwable ignored) {
            // Immutable list: the copy handed back is the real answer.
        }
    }

    private void hookAwareResourceControl(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var AwareResourceControlClass = classLoader.loadClass("com.miui.server.greeze.power.AwareResourceControl");
        var mNoNetworkBlackUidsField = AwareResourceControlClass.getDeclaredField("mNoNetworkBlackUids");
        mNoNetworkBlackUidsField.setAccessible(true);
        var AwareResourceControlConstructors = AwareResourceControlClass.getDeclaredConstructors();
        for (var constructor : AwareResourceControlConstructors) {
            hookE(constructor).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        pruneGmsFromNoNetworkBlacklist(mNoNetworkBlackUidsField, chain.getThisObject());
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "Failed to modify AwareResourceControl.mNoNetworkBlackUids", t);
                    }
                }
            });
            deoptimize(constructor);
        }
    }

    /** Set after the "nothing matched" case has been reported once, to keep logs quiet. */
    private volatile boolean noNetworkBlacklistMismatchLogged;

    /**
     * Drop GMS from {@code AwareResourceControl.mNoNetworkBlackUids}.
     *
     * <p>The field is named as a collection of <em>uids</em>, while the previous
     * code only ever removed the package name — a lookup that can never match a
     * uid, which would have left this hook silently doing nothing. Both forms are
     * tried now, and which one matched is logged: the field's real element type can
     * only be observed on a device, so the log is the verification.
     */
    private void pruneGmsFromNoNetworkBlacklist(Field blacklistField, Object awareResourceControl)
            throws IllegalAccessException {
        Object raw = blacklistField.get(awareResourceControl);
        if (!(raw instanceof Collection<?> blacklist)) {
            return;
        }
        boolean removedByName = blacklist.remove(GMS_PACKAGE_NAME);
        Integer uid = gmsUid();
        boolean removedByUid = uid != null && blacklist.remove(uid);
        if (removedByName || removedByUid) {
            log(Log.INFO, TAG, "Removed GMS from NoNetworkBlackUids (by "
                    + (removedByUid ? "uid " + uid : "package name") + ")");
        } else if (!noNetworkBlacklistMismatchLogged) {
            noNetworkBlacklistMismatchLogged = true;
            log(Log.INFO, TAG, "NoNetworkBlackUids (size=" + blacklist.size()
                    + ") matched neither the GMS package name nor its uid"
                    + (uid == null ? " (uid not resolvable yet)" : ""));
        }
    }

    /**
     * uid of the GMS package, or null when the package manager cannot answer yet.
     * {@link #getSystemContext()} is used from whichever process the module runs in
     * (here: PowerKeeper), so this does not require system_server.
     */
    private Integer gmsUid() {
        try {
            Context context = getSystemContext();
            if (context == null) {
                return null;
            }
            return context.getPackageManager().getApplicationInfo(GMS_PACKAGE_NAME, 0).uid;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * PowerKeeper hooks for GMS network / DNS / package control.
     * HyperOS 3 (PowerKeeper 3.x) keeps initGmsChain + updateGmsAlarm trio AND
     * also exposes setGmsDnsBlockerState / setGmsChainState / disableGms.
     * HyperOS 4 removed the legacy trio and routes limits through
     * updateFrameworkGmsNetStatus. Each hook is independent so a missing
     * method on one ROM does not abort the rest.
     */
    private void hookGmsObserver(ClassLoader classLoader) {
        try {
            var NetdExecutorClass = classLoader.loadClass("com.miui.powerkeeper.utils.NetdExecutor");
            // HyperOS 3: rewrite iptables action to ACCEPT.
            try {
                var initGmsChainMethod = NetdExecutorClass.getDeclaredMethod("initGmsChain", String.class, int.class, String.class);
                hookE(initGmsChainMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    args[2] = "ACCEPT";
                    return chain.proceed(args);
                });
                deoptimize(initGmsChainMethod);
            } catch (NoSuchMethodException e) {
                logSkipOtherGeneration("NetdExecutor#initGmsChain absent, skip");
            }
            // HyperOS 3+: never deny GMS DNS (present on 3 and 4).
            try {
                var setGmsDnsBlockerStateMethod = NetdExecutorClass.getDeclaredMethod("setGmsDnsBlockerState", int.class, boolean.class);
                hookE(setGmsDnsBlockerStateMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    if (args.length > 1) {
                        args[1] = false;
                    }
                    return chain.proceed(args);
                });
                deoptimize(setGmsDnsBlockerStateMethod);
            } catch (NoSuchMethodException e) {
                logSkip("NetdExecutor#setGmsDnsBlockerState absent, skip");
            }
            // HyperOS 3: keep the GMS firewall chain enabled (disable = block).
            try {
                var setGmsChainStateMethod =
                        NetdExecutorClass.getDeclaredMethod("setGmsChainState", String.class, boolean.class);
                hookE(setGmsChainStateMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    if (args.length > 1) {
                        args[1] = true;
                    }
                    return chain.proceed(args);
                });
                deoptimize(setGmsChainStateMethod);
            } catch (NoSuchMethodException e) {
                logSkipOtherGeneration("NetdExecutor#setGmsChainState absent, skip");
            }
            // Defense in depth: force setuiddnsrule → allow; skip enabling standby firewall.
            try {
                var executeMethod = NetdExecutorClass.getDeclaredMethod("execute", int.class, String.class, String.class, Object[].class);
                // A skipped call has to hand back something the caller can use.
                // null is right for void and for reference returns; for a
                // primitive return it would be unboxed and crash PowerKeeper.
                //
                // Refusing to skip in that case — an earlier fix — silently lets
                // "enablemiuistandby enable" through, and that chain is exactly
                // what cuts GMS off while the screen is off: pushes stop
                // overnight and come back on wake. So hand back the type's
                // "nothing happened" value instead, which skips the command and
                // still gives the caller a value it can read.
                Class<?> executeReturn = executeMethod.getReturnType();
                // Always skippable: skipValueFor covers all eight primitives, and
                // void / reference returns are satisfied with null. So the "let the
                // command through" branch that used to guard against a primitive
                // return can never be taken and is gone.
                final Object skipValue = skipValueFor(executeReturn);
                hookE(executeMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    if (args.length >= 4 && args[2] instanceof String cmd && args[3] instanceof Object[] cmdArgs) {
                        if ("setuiddnsrule".equals(cmd) && cmdArgs.length >= 2) {
                            var rewritten = cmdArgs.clone();
                            rewritten[1] = "allow";
                            args[3] = rewritten;
                            return chain.proceed(args);
                        }
                        if ("enablemiuistandby".equals(cmd) && cmdArgs.length >= 1
                                && "enable".equals(String.valueOf(cmdArgs[0]))) {
                            // Do not enable the standby firewall chain for GMS.
                            return skipValue;
                        }
                    }
                    return chain.proceed();
                });
                deoptimize(executeMethod);
                log(Log.INFO, TAG, "NetdExecutor#execute returns " + executeReturn.getName()
                        + "; standby-firewall skip returns "
                        + (skipValue == null ? "null" : skipValue));
            } catch (NoSuchMethodException e) {
                logSkip("NetdExecutor#execute not found, skip command-level GMS net hooks");
            }
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "Failed to hook NetdExecutor", e);
        }
        try {
            var GmsObserverClass = classLoader.loadClass("com.miui.powerkeeper.utils.GmsObserver");
            // Legacy method names — present on older PowerKeeper only.
            for (String legacyName : new String[]{"updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock"}) {
                hookForceFalse(GmsObserverClass, legacyName);
            }
            // GmsObserver can turn Google components off entirely.
            // Never execute the disable path (FCM needs GMS packages alive).
            // disableGms exists on HyperOS 3 only; disableGmsApps is a HyperOS 4 name
            // that is absent from both the OS3 and OS4 PowerKeeper 4.2.00 builds.
            for (String alwaysSkip : new String[]{"disableGms", "disableGmsApps"}) {
                try {
                    var disableMethod = GmsObserverClass.getDeclaredMethod(alwaysSkip);
                    hookE(disableMethod).intercept(chain -> null);
                    deoptimize(disableMethod);
                } catch (NoSuchMethodException e) {
                    logSkipOtherGeneration("GmsObserver#" + alwaysSkip + " absent, skip");
                }
            }
            // HyperOS 3: updateGmsEnabled(true) / updateGmsState(true) apply limits.
            for (String limitFlag : new String[]{"updateGmsEnabled", "updateGmsState", "updateGmsInstalled"}) {
                hookForceFalse(GmsObserverClass, limitFlag);
            }
            // HyperOS 3+: never apply the framework GMS network limit (4.x path).
            try {
                var updateFrameworkGmsNetStatusMethod =
                        GmsObserverClass.getDeclaredMethod("updateFrameworkGmsNetStatus", boolean.class);
                hookE(updateFrameworkGmsNetStatusMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    if (args.length > 0 && Boolean.TRUE.equals(args[0])) {
                        args[0] = false;
                    }
                    return chain.proceed(args);
                });
                deoptimize(updateFrameworkGmsNetStatusMethod);
            } catch (NoSuchMethodException e) {
                logSkip("GmsObserver#updateFrameworkGmsNetStatus absent, skip");
            }
            // Treat Google as always reachable so notifyFrameworkGmsNetworkChanged
            // computes limit = reachable ^ 1 == false.
            try {
                var onGoogleReachabilityChangedMethod =
                        GmsObserverClass.getDeclaredMethod("onGoogleReachabilityChanged", boolean.class);
                hookE(onGoogleReachabilityChangedMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    args[0] = true;
                    return chain.proceed(args);
                });
                deoptimize(onGoogleReachabilityChangedMethod);
            } catch (NoSuchMethodException e) {
                logSkip("GmsObserver#onGoogleReachabilityChanged absent, skip");
            }
            // Synthetic bridge c(GmsObserver, boolean) → onGoogleReachabilityChanged.
            try {
                var bridgeMethod = GmsObserverClass.getDeclaredMethod("c", GmsObserverClass, boolean.class);
                hookE(bridgeMethod).intercept(chain -> {
                    var args = chain.getArgs().toArray();
                    args[1] = true;
                    return chain.proceed(args);
                });
                deoptimize(bridgeMethod);
            } catch (NoSuchMethodException ignored) {
            }
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "Failed to hook GmsObserver", e);
        }
        // The disconnect listener is an anonymous inner class whose index drifts
        // between ROM builds: HyperOS 3 ships it as GmsObserver$5 while HyperOS 4
        // ships it as GmsObserver$2. Probe every candidate instead of hard-coding
        // a single index, otherwise OS3 silently loses this hook.
        boolean disconnectHooked = false;
        for (int i = 1; i <= 8 && !disconnectHooked; i++) {
            String listenerName = "com.miui.powerkeeper.utils.GmsObserver$" + i;
            try {
                var GmsObserverListenerClass = classLoader.loadClass(listenerName);
                // Drop disconnect events so PowerKeeper never learns "Google unreachable".
                try {
                    var disconnectMethod = GmsObserverListenerClass.getDeclaredMethod("googleNetworkDisconnect");
                    hookE(disconnectMethod).intercept(chain -> null);
                    deoptimize(disconnectMethod);
                    disconnectHooked = true;
                    log(Log.INFO, TAG, listenerName + "#googleNetworkDisconnect hooked");
                } catch (NoSuchMethodException ignored) {
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (!disconnectHooked) {
            logSkip("GmsObserver$*#googleNetworkDisconnect absent, skip disconnect rewrite");
        }
    }

    /**
     * Hooks {@code owner#name(boolean)} so the flag always reaches the method as
     * false: every one of these switches the GMS limit on, which is the one thing
     * the module never wants applied. An absent name just means it belongs to
     * another PowerKeeper generation — still counted, like any other skip.
     */
    private void hookForceFalse(Class<?> owner, String name) {
        try {
            var method = owner.getDeclaredMethod(name, boolean.class);
            hookE(method).intercept(chain -> {
                var args = chain.getArgs().toArray();
                args[0] = false;
                return chain.proceed(args);
            });
            deoptimize(method);
        } catch (NoSuchMethodException e) {
            logSkipOtherGeneration("GmsObserver#" + name + " absent, skip");
        }
    }

    private void hookGlobalFeatureConfigureHelper(ClassLoader classLoader) {
        try {
            var GlobalFeatureConfigureHelperClass = classLoader.loadClass("com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper");
            for (Class<?> argType : new Class<?>[]{Bundle.class, android.content.Context.class}) {
                try {
                    var getDozeWhiteListAppsMethod =
                            GlobalFeatureConfigureHelperClass.getDeclaredMethod("getDozeWhiteListApps", argType);
                    hookE(getDozeWhiteListAppsMethod).intercept(chain -> {
                        var result = chain.proceed();
                        // Same rule as the ProcessPolicy hook: hand back a copy
                        // (safe against an immutable list) *and* write through to
                        // the original, because PowerKeeper may hand out — and
                        // later read back — one cached instance.
                        try {
                            if (result instanceof List<?> source
                                    && !source.contains(GMS_PACKAGE_NAME)) {
                                var whiteList = new ArrayList<Object>(source);
                                whiteList.add(GMS_PACKAGE_NAME);
                                addIfAbsentInPlace(source, GMS_PACKAGE_NAME);
                                return whiteList;
                            }
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "Failed to extend doze white list", t);
                        }
                        return result;
                    });
                } catch (NoSuchMethodException e) {
                    logSkip("GlobalFeatureConfigureHelper#getDozeWhiteListApps(" + argType.getSimpleName() + ") absent, skip");
                }
            }
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", e);
        }
    }

    private static PowerExemptionManager powerExemptionManager = null;

    @RequiresApi(Build.VERSION_CODES.S)
    private static PowerExemptionManager getPowerExemptionManager(Context context) {
        if (powerExemptionManager == null) {
            // mContext.getSystemService("power_exemption")
            powerExemptionManager = new PowerExemptionManager(context);
        }
        return powerExemptionManager;
    }

    /**
     * A Context in system_server (ActivityThread.currentApplication()).
     */
    private Context getSystemContext() {
        if (systemContext == null) {
            try {
                // ActivityThread.currentApplication() is hidden; call via reflection.
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Method currentApplication = activityThreadClass.getMethod("currentApplication");
                if (currentApplication.invoke(null) instanceof Context ctx) {
                    systemContext = ctx;
                }
            } catch (Throwable ignored) {
            }
        }
        return systemContext;
    }

    /**
     * Package names the user allows FCM to wake / auto-launch, kept in memory in
     * system_server. Loaded from libxposed's cross-process remote preferences and
     * refreshed whenever the app broadcasts {@link Prefs#ACTION_ALLOWLIST_CHANGED}.
     */
    private static volatile Set<String> sAllowlist = Collections.emptySet();

    /**
     * Strict mode, from the overflow menu. Read in the same pass as the
     * allowlist: it only ever means anything together with it, and one
     * cross-process read then refreshes both.
     *
     * <p>Off by default, which keeps the historical behaviour for everyone who
     * never opens the menu — an upgrade must not start leaving unchecked apps to
     * the system on its own.
     */
    private static volatile boolean sStrictMode = false;

    /** Re-read the allowlist and strict mode from the shared remote preferences. */
    private void loadAllowlistFromRemotePrefs() {
        try {
            SharedPreferences prefs = getRemotePreferences(Prefs.GROUP_CONFIG);
            Set<String> set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, Collections.emptySet());
            sAllowlist = set != null ? new HashSet<>(set) : new HashSet<>();
            sStrictMode = prefs.getBoolean(Prefs.KEY_STRICT_MODE, false);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to read remote allowlist", e);
        }
        // Stamped even on failure, so the stale-copy fallback below cannot turn
        // into a read storm while the module's prefs are unreadable.
        sAllowlistReadMs = SystemClock.uptimeMillis();
    }

    /** Called from hookSystemServer: load the initial allowlist at boot. */
    private void hookAllowlist() {
        loadAllowlistFromRemotePrefs();
        installAllowlistReceiverAsync();
    }

    private volatile boolean allowlistReceiverRegistered = false;
    private final AtomicBoolean allowlistRegistering = new AtomicBoolean(false);
    /** True while a coalesced reload is already queued on the background thread. */
    private final AtomicBoolean allowlistReloadQueued = new AtomicBoolean(false);
    private volatile long sAllowlistReadMs = 0L;
    /** How often the stale-copy fallback may re-read the prefs (see getFcmAllowlist). */
    private static final long ALLOWLIST_STALE_MS = 10_000L;
    /**
     * Minimum spacing between two reloads. A burst of broadcasts (or a hostile app
     * spamming them) collapses into one queued read: the read always fetches the
     * whole current set, so coalescing cannot lose a change.
     */
    private static final long ALLOWLIST_RELOAD_MIN_MS = 500L;

    /** Handler running on a private background thread; null until first use. */
    private volatile Handler allowlistHandler;

    /**
     * A Handler on a dedicated background thread.
     *
     * <p>Everything that touches the shared prefs from the hook side runs here.
     * Reading them can be a cross-process call, and this used to happen on
     * system_server's main thread — once from the receiver, and once from the
     * stale-copy fallback inside a hooked ActivityManagerService call. Neither is
     * acceptable on the main thread of the system.
     */
    private Handler allowlistBackgroundHandler() {
        Handler handler = allowlistHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (this) {
            if (allowlistHandler == null) {
                HandlerThread thread = new HandlerThread("fcmlive-allowlist");
                thread.start();
                allowlistHandler = new Handler(thread.getLooper());
            }
            return allowlistHandler;
        }
    }

    /**
     * Ask for the allowlist to be re-read on the background thread.
     *
     * <p>Never blocks and never performs the read inline, so it is safe to call
     * from a hooked ActivityManagerService path. Requests inside
     * {@link #ALLOWLIST_RELOAD_MIN_MS} collapse into one pending read.
     */
    private void requestAllowlistReload() {
        long sinceLastRead = SystemClock.uptimeMillis() - sAllowlistReadMs;
        if (sinceLastRead >= ALLOWLIST_RELOAD_MIN_MS) {
            allowlistBackgroundHandler().post(this::loadAllowlistFromRemotePrefs);
            return;
        }
        if (!allowlistReloadQueued.compareAndSet(false, true)) {
            return;
        }
        allowlistBackgroundHandler().postDelayed(() -> {
            allowlistReloadQueued.set(false);
            loadAllowlistFromRemotePrefs();
        }, ALLOWLIST_RELOAD_MIN_MS - sinceLastRead);
    }
    /** Delay between two attempts to install the receiver during boot. */
    private static final long ALLOWLIST_REGISTER_RETRY_MS = 1_000L;
    /** Stop retrying after this many attempts (about two minutes). */
    private static final int ALLOWLIST_REGISTER_MAX_ATTEMPTS = 120;

    /**
     * Install the allowlist-change receiver as soon as the system can take one,
     * rather than waiting for the first C2DM broadcast to come through.
     *
     * <p>The receiver used to be registered lazily, from the first hooked C2DM
     * call. Until that happened the in-memory allowlist kept its boot-time value
     * and every broadcast the settings app sent after a toggle was dropped, so a
     * freshly checked app only started working once some *later* push happened to
     * install the receiver — the change appeared to need a refresh first.
     *
     * <p>Registering cannot be done synchronously here: IActivityManager does not
     * exist yet during early SystemServer startup, so registerReceiver NPEs. A
     * daemon thread does the retrying instead of a Handler because the main looper
     * is not guaranteed to be ready this early either.
     */
    private void installAllowlistReceiverAsync() {
        if (allowlistReceiverRegistered) {
            return;
        }
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < ALLOWLIST_REGISTER_MAX_ATTEMPTS; attempt++) {
                if (registerAllowlistReceiver()) {
                    return;
                }
                try {
                    Thread.sleep(ALLOWLIST_REGISTER_RETRY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            log(Log.WARN, TAG, "Allowlist receiver not installed during boot;"
                    + " falling back to lazy registration");
        }, "fcmlive-allowlist-register");
        t.setDaemon(true);
        t.start();
    }

    private Set<String> getFcmAllowlist() {
        // Fallback only: the receiver is normally installed at boot by
        // installAllowlistReceiverAsync(). If that has not happened yet, ask the
        // background thread for a refresh — this method runs inside a hooked
        // ActivityManagerService call, so it must never do the read itself.
        registerAllowlistReceiver();
        if (!allowlistReceiverRegistered
                && SystemClock.uptimeMillis() - sAllowlistReadMs >= ALLOWLIST_STALE_MS) {
            requestAllowlistReload();
        }
        return new HashSet<>(sAllowlist);
    }

    /**
     * Target package of a push broadcast, or null when neither form is present.
     *
     * <p>GMS normally sends these with an explicit package, but a build that
     * switches to {@code setComponent()} leaves {@code getPackage()} null — and
     * both call sites read it as a guard, so the whole enhancement (the
     * stopped-package flag and the power exemption) would then be skipped
     * silently, with no log line and nothing to fall back to.
     */
    private static String targetPackageOf(Intent intent) {
        String pkg = intent.getPackage();
        if (pkg != null) {
            return pkg;
        }
        ComponentName component = intent.getComponent();
        return component != null ? component.getPackageName() : null;
    }

    /**
     * Whether a target package should be woken / auto-started by FCM.
     * An empty allowlist keeps the legacy behaviour (wake everything); once the
     * user checks at least one app it becomes whitelist mode (only checked apps).
     */
    private boolean shouldWake(String targetPackage) {
        Set<String> allowlist = getFcmAllowlist();
        return allowlist.isEmpty() || allowlist.contains(targetPackage);
    }

    /**
     * Whether the module may second-guess the system for {@code packageName}.
     *
     * <p>This is the strict-mode gate, and it is deliberately narrower than
     * {@link #shouldWake}: that one decides who gets the *extras* (auto-start,
     * the stopped-package flag, the power exemption) and already excludes
     * unchecked apps whenever the list is non-empty. This one decides whether the
     * module answers at all for an app, and it is what the hooks that protect an
     * app *from the system* — the force-stop cleaner, the push-app network
     * restriction, the broadcast allowance — consult before replacing the
     * system's decision with their own.
     *
     * <p>Off (the default, and what every existing install keeps): those hooks
     * answer for every app that ships a push component, which is the historical
     * behaviour and stays untouched.
     *
     * <p>On, with at least one app checked: an app that is not on the list is
     * left to the system, so PowerKeeper and the framework decide exactly as they
     * would with no module installed.
     *
     * <p>An empty list stays wide open in both modes. Strict mode is a rule about
     * apps the user did *not* check, and with nothing checked there are none —
     * narrowing that case would turn the empty list from "let everything
     * through" into "let nothing through", which is the opposite of what the
     * help page promises for it.
     *
     * <p>GMS is exempt from the gate rather than left to the list: it carries the
     * push for the checked apps as well, so gating it would take those apps down
     * together with the unchecked ones and defeat the point of switching this on.
     */
    private boolean shouldApply(String packageName) {
        if (!sStrictMode) {
            return true;
        }
        Set<String> allowlist = getFcmAllowlist();
        if (allowlist.isEmpty()) {
            return true;
        }
        return allowlist.contains(packageName)
                || GMS_PACKAGE_NAME.equals(packageName)
                || GMS_PERSISTENT_PROCESS_NAME.equals(packageName);
    }

    /**
     * Register the receiver that re-reads the allowlist when the app updates it.
     *
     * <p>Guarded by {@link #allowlistRegistering} rather than {@code synchronized}
     * so the hooked path never waits on the boot-time retry thread: a broadcast
     * arriving mid-registration just proceeds with the current set.
     *
     * @return true once the receiver is installed (or was already); false when the
     *         system is not ready for it yet, in which case the caller retries.
     */
    private boolean registerAllowlistReceiver() {
        if (allowlistReceiverRegistered) {
            return true;
        }
        if (!allowlistRegistering.compareAndSet(false, true)) {
            return allowlistReceiverRegistered;
        }
        try {
            Context sys = getSystemContext();
            if (sys == null) {
                return false;
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Prefs.ACTION_ALLOWLIST_CHANGED.equals(intent.getAction())) {
                        // Runs on the background looper (see registerReceiver below)
                        // and coalesces bursts, so a flood of this (unauthenticated)
                        // broadcast cannot stall the system's main thread.
                        requestAllowlistReload();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED);
            Handler handler = allowlistBackgroundHandler();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                sys.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
            } else {
                sys.registerReceiver(receiver, filter, null, handler);
            }
            allowlistReceiverRegistered = true;
            log(Log.INFO, TAG, "Allowlist receiver installed");
            return true;
        } catch (Throwable e) {
            // Still booting (IActivityManager absent) — the caller retries.
            return false;
        } finally {
            allowlistRegistering.set(false);
        }
    }

    /**
     * {@code getDeclaredMethod} that reports a miss as {@code null} instead of
     * throwing.
     *
     * <p>Nearly every hook here is identified by name and exact parameter list, so
     * one renamed parameter is enough to make a hook disappear. The old code let
     * that exception escape and abort the whole group, which cost far more than
     * the single hook that had actually drifted; returning null lets each lookup
     * fail on its own and lets the caller fall back to another signature.
     */
    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /**
     * Whether the process sending the broadcast is GMS.
     *
     * <p>{@code getRecordForApp*} is an internal helper that a release can rename;
     * the binder calling uid cannot be renamed, so it is the durable answer and
     * the one used whenever the helper is missing. Both are best effort: answering
     * false only means this one broadcast misses the enhancement, which is exactly
     * what would have happened without the hook.
     */
    private boolean callerIsGms(Method getRecordMethod, Field infoField, Object ams,
                                Object callerThread) {
        if (getRecordMethod != null && callerThread != null) {
            try {
                Object app = getInvoker(getRecordMethod).invoke(ams, callerThread);
                if (app != null && infoField.get(app) instanceof ApplicationInfo info) {
                    return GMS_PACKAGE_NAME.equals(info.packageName);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Failed to resolve the broadcast caller", t);
            }
        }
        return binderCallerIsGms();
    }

    private boolean binderCallerIsGms() {
        try {
            Context context = getSystemContext();
            if (context == null) {
                return false;
            }
            String[] packages = context.getPackageManager()
                    .getPackagesForUid(Binder.getCallingUid());
            if (packages == null) {
                return false;
            }
            for (String pkg : packages) {
                if (GMS_PACKAGE_NAME.equals(pkg)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to resolve the binder caller uid", t);
        }
        return false;
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var ActivityManagerServiceClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var mContextField = ActivityManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        var IApplicationThreadClass = classLoader.loadClass("android.app.IApplicationThread");
        var IIntentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var infoField = ProcessRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);
        // Both names are tried rather than branching on SDK_INT: this helper has
        // been renamed before, and a miss used to abort the whole method — taking
        // the stopped-package flag and the power exemption down with it.
        Method getRecordMethod = findMethod(ActivityManagerServiceClass,
                "getRecordForAppLOSP", IApplicationThreadClass);
        if (getRecordMethod == null) {
            getRecordMethod = findMethod(ActivityManagerServiceClass,
                    "getRecordForAppLocked", IApplicationThreadClass);
        }
        if (getRecordMethod == null) {
            log(Log.WARN, TAG, "No ActivityManagerService#getRecordForApp*;"
                    + " the broadcast caller is identified by binder uid instead");
        }
        // Every known shape, newest first, instead of trusting SDK_INT: a release
        // that reshuffles a parameter would otherwise drop this hook silently,
        // which is the one failure nobody can see from the settings screen.
        // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
        //    Intent intent, String resolvedType, IIntentReceiver resultTo,
        //    int resultCode, String resultData, Bundle resultExtras,
        //    String[] requiredPermissions, String[] excludedPermissions,
        //    [String[] excludedPackages,] int appOp, Bundle bOptions,
        //    boolean serialized, boolean sticky, int userId)
        Method broadcastMethod = null;
        int intentArgIndex = 2;
        List<Class<?>[]> featureSignatures = Arrays.asList(
                // Android 13+: excludedPackages added.
                new Class<?>[]{IApplicationThreadClass, String.class, Intent.class, String.class,
                        IIntentReceiverClass, int.class, String.class, Bundle.class,
                        String[].class, String[].class, String[].class, int.class, Bundle.class,
                        boolean.class, boolean.class, int.class},
                // Android 12.
                new Class<?>[]{IApplicationThreadClass, String.class, Intent.class, String.class,
                        IIntentReceiverClass, int.class, String.class, Bundle.class,
                        String[].class, String[].class, int.class, Bundle.class,
                        boolean.class, boolean.class, int.class},
                // Android 11.
                new Class<?>[]{IApplicationThreadClass, String.class, Intent.class, String.class,
                        IIntentReceiverClass, int.class, String.class, Bundle.class,
                        String[].class, int.class, Bundle.class,
                        boolean.class, boolean.class, int.class});
        for (Class<?>[] signature : featureSignatures) {
            broadcastMethod = findMethod(ActivityManagerServiceClass,
                    "broadcastIntentWithFeature", signature);
            if (broadcastMethod != null) {
                break;
            }
        }
        if (broadcastMethod == null) {
            // Pre-feature-id name: one argument less, and the intent sits at 1.
            broadcastMethod = findMethod(ActivityManagerServiceClass, "broadcastIntent",
                    IApplicationThreadClass,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
            if (broadcastMethod != null) {
                intentArgIndex = 1;
            }
        }
        if (broadcastMethod == null) {
            log(Log.ERROR, TAG, "No broadcastIntent* in ActivityManagerService;"
                    + " stopped-package delivery and the power exemption are not installed");
            return;
        }
        final Method finalGetRecordMethod = getRecordMethod;
        final int finalIntentArgIndex = intentArgIndex;
        hookE(broadcastMethod).intercept(chain -> {
            // This runs for every broadcast in the system, so reject on the
            // action before touching anything reflective.
            if (chain.getArg(finalIntentArgIndex) instanceof Intent intent
                    && ACTION_REMOTE_INTENT.equals(intent.getAction())) {
                try {
                    if (callerIsGms(finalGetRecordMethod, infoField,
                            chain.getThisObject(), chain.getArg(0))
                            // Wake / auto-start only apps the user whitelisted; empty list = all.
                            && targetPackageOf(intent) instanceof String targetPackage
                            && shouldWake(targetPackage)) {
                        // The stopped-package flag is what this hook exists for, so
                        // it is applied first and protected on its own.
                        try {
                            if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            }
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "Failed to add FLAG_INCLUDE_STOPPED_PACKAGES", t);
                        }
                        // Optional extra: ~2s power exemption. A failure here (hidden
                        // API drift, SELinux) must not take the flag above with it.
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                    && mContextField.get(chain.getThisObject()) instanceof Context mContext) {
                                getPowerExemptionManager(mContext).addToTemporaryAllowList(
                                        targetPackage,
                                        102 /* PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA */,
                                        "GOOGLE_C2DM", 2000);
                            }
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "Failed to add temporary power exemption", t);
                        }
                    }
                } catch (Throwable t) {
                    // Never let a hook failure escape into ActivityManagerService:
                    // this is the broadcast path of every app on the device.
                    log(Log.ERROR, TAG, "C2DM broadcast hook failed", t);
                }
            }
            return chain.proceed();
        });
        deoptimize(broadcastMethod);
    }

    /**
     * What to return for a call that must be neutralised, or {@code null} — for
     * void and reference returns, where null is the correct "nothing happened".
     *
     * <p>A primitive return cannot take null: the caller unboxes it and crashes
     * inside PowerKeeper. Its zero value reads as "the command did nothing",
     * which is what a skipped command should look like, so the skip survives a
     * signature that returns {@code boolean} or {@code int}.
     */
    private static Object skipValueFor(Class<?> returnType) {
        if (returnType == void.class || !returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        return null;
    }

    /**
     * Reused walker: building one per call would allocate on every isPushApp().
     *
     * <p>{@code RETAIN_CLASS_REFERENCE} costs a class resolution per frame, which
     * is accepted here because the caller has to be identified by its
     * {@code ClassLoader} — see the hook below — and the alternative (matching
     * only the class name) silently loses the hook on builds where
     * {@code isRestrictNet} lives in another system_server class.
     */
    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** Logged once: proof on a device that the isRestrictNet branch is reached. */
    private volatile boolean restrictNetMatchLogged;

    private void hookInternationalPolicyManager(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var InternationalPolicyManagerClass = classLoader.loadClass("com.miui.server.greeze.InternationalPolicyManager");
        var isPushAppMethod = InternationalPolicyManagerClass.getDeclaredMethod("isPushApp", String.class);
        // The class name is the fast path; the ClassLoader test behind it is
        // what 1.8.0 used and what actually has to stay.
        //
        // isRestrictNet is not guaranteed to live in this class. Matching only
        // the name meant that on a build where it moved (another greeze /
        // system_server class that calls isPushApp) nothing matched and the hook
        // never fired: the network restriction then applies to GMS as designed,
        // the long-lived connection dies, and pushes stop arriving — which is
        // exactly what is seen overnight, when the restriction is in force and
        // the screen is off. The ClassLoader test covers every class system_server
        // loaded the same way, so a rename or a move costs nothing.
        final String restrictNetOwner = InternationalPolicyManagerClass.getName();
        final ClassLoader systemServerCl = InternationalPolicyManagerClass.getClassLoader();
        hookE(isPushAppMethod).intercept(chain -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    // Strict mode: an unchecked app keeps whatever network
                    // restriction the ROM decided on for it.
                    && shouldApply(chain.getArg(0) instanceof String pkg ? pkg : null)) {
                try {
                    boolean fromRestrictNet = STACK_WALKER.walk(frames -> frames.anyMatch(frame ->
                            "isRestrictNet".equals(frame.getMethodName())
                                    && (restrictNetOwner.equals(frame.getClassName())
                                        || (frame.getDeclaringClass() != null
                                            && frame.getDeclaringClass().getClassLoader()
                                                == systemServerCl))));
                    if (fromRestrictNet) {
                        if (!restrictNetMatchLogged) {
                            restrictNetMatchLogged = true;
                            log(Log.INFO, TAG, "isPushApp: caller isRestrictNet matched;"
                                    + " answering false");
                        }
                        return false;
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Stack inspection failed", t);
                }
            }
            return chain.proceed();
        });
    }

    /** How long a "ships FCM" answer is reused. */
    private static final long FCM_CACHE_TTL_MS = 5L * 60L * 1000L;
    /** Hard cap on the cache; cleared wholesale when exceeded. */
    private static final int FCM_CACHE_MAX = 256;

    /** One cached FCM answer plus when it was resolved. */
    private static final class FcmQuery {
        final boolean declares;
        final long checkedAtMs;

        FcmQuery(boolean declares, long checkedAtMs) {
            this.declares = declares;
            this.checkedAtMs = checkedAtMs;
        }
    }

    private final Map<String, FcmQuery> fcmCache = new HashMap<>();

    /**
     * Whether {@code packageName} ships any of the four FCM markers — the
     * Firebase messaging service or instance-id receiver class, or an
     * intent-filter for {@code MESSAGING_EVENT} / {@code c2dm.intent.RECEIVE}.
     *
     * <p>This is what grants the cleaner exemption, and it is deliberately
     * <em>not</em> narrowed to the user's allowlist: every app that genuinely
     * ships a push component stays protected, which is the historical behaviour
     * and what a "keep push alive" module is expected to do. The trade-off is
     * that a marker can also be declared purely to opt out of the cleaner; that
     * was reviewed and accepted on 2026-09-23 — do not tighten it again without
     * asking, since it changes behaviour for people who never opened the app.
     *
     * <p>Strict mode is the opt-in exception to that: it narrows the exemption to
     * the user's own list, but only for someone who switched it on, so the
     * accepted default above is not what changes.
     *
     * <p>Answering it needs PackageManager queries, and the cleaner asks far more
     * often than apps get installed, so answers are reused for a few minutes
     * instead of querying the package manager on every call. An app that only
     * starts shipping a marker in an update keeps its old answer for at most
     * {@link #FCM_CACHE_TTL_MS}.
     */
    private boolean declaresFcmComponent(PackageManager pm, String packageName) {
        long now = SystemClock.uptimeMillis();
        synchronized (fcmCache) {
            FcmQuery cached = fcmCache.get(packageName);
            if (cached != null && now - cached.checkedAtMs < FCM_CACHE_TTL_MS) {
                return cached.declares;
            }
        }
        final boolean declares;
        try {
            declares = declaresFcmUncached(pm, packageName);
        } catch (Throwable t) {
            // Fail safe: when in doubt, let the cleaner keep its own decision.
            log(Log.ERROR, TAG, "FCM lookup failed for " + packageName, t);
            return false;
        }
        synchronized (fcmCache) {
            if (fcmCache.size() >= FCM_CACHE_MAX) {
                fcmCache.clear();
            }
            fcmCache.put(packageName, new FcmQuery(declares, now));
        }
        return declares;
    }

    /**
     * The four markers, cheapest first: the two intent queries are what
     * Firebase's manifest merge actually writes, so they answer almost every
     * app; the two class lookups follow for the rest — an app that ships the
     * class with no matching intent-filter (stripped merge, hand-written entry,
     * old GCM build) is invisible to the queries above but still FCM-capable.
     */
    private static boolean declaresFcmUncached(PackageManager pm, String packageName) {
        var serviceIntent = new Intent(ACTION_MESSAGING_EVENT);
        serviceIntent.setPackage(packageName);
        if (!pm.queryIntentServices(serviceIntent, 0).isEmpty()) {
            return true;
        }
        var receiverIntent = new Intent(ACTION_REMOTE_INTENT);
        receiverIntent.setPackage(packageName);
        if (!pm.queryBroadcastReceivers(receiverIntent, 0).isEmpty()) {
            return true;
        }
        try {
            if (pm.getServiceInfo(
                    new ComponentName(packageName, FCM_MESSAGING_SERVICE_CLASS), 0) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // Not declared: fall through to the receiver class.
        }
        try {
            if (pm.getReceiverInfo(
                    new ComponentName(packageName, FCM_IID_RECEIVER_CLASS), 0) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // Not declared either: no FCM marker at all.
        }
        return false;
    }

    private void hookProcessCleanerBase(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var ProcessCleanerBaseClass = classLoader.loadClass("com.android.server.am.ProcessCleanerBase");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var mGetApplicationInfo = ProcessRecordClass.getDeclaredMethod("getApplicationInfo");
        var ProcessManagerServiceClass = classLoader.loadClass("com.android.server.am.ProcessManagerService");
        var mPkms = ProcessManagerServiceClass.getDeclaredField("mPkms");
        mPkms.setAccessible(true);
        // boolean isForceStopEnable(ProcessRecord app, int policy, ProcessManagerService pms)
        var isForceStopEnableMethod = ProcessCleanerBaseClass.getDeclaredMethod("isForceStopEnable", ProcessRecordClass, int.class, ProcessManagerServiceClass);
        hookE(isForceStopEnableMethod).intercept(chain -> {
            try {
                if (chain.getArg(1) instanceof Integer policy && policy != 13
                        && mPkms.get(chain.getArg(2)) instanceof PackageManager pm
                        // getApplicationInfo() is null for processes that never
                        // finished attaching; reading through it used to NPE.
                        && getInvoker(mGetApplicationInfo).invoke(chain.getArg(0)) instanceof ApplicationInfo info
                        && info.packageName != null
                        // Strict mode first: it is the cheap test and in the
                        // default (off) mode it answers without touching the
                        // package manager, so the exemption below is only
                        // looked up for apps that may actually get it.
                        && shouldApply(info.packageName)
                        && declaresFcmComponent(pm, info.packageName)) {
                    // Declares a push receiver: keep the cleaner's hands off it.
                    return false;
                }
            } catch (Throwable t) {
                // Fall through to the system's own decision — never crash the cleaner.
                log(Log.ERROR, TAG, "isForceStopEnable hook failed", t);
            }
            return chain.proceed();
        });
    }
}
