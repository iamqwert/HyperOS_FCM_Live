package io.github.howard20181.hyperos.fcmlive;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
    private static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent";
    private Pair<String, ClassLoader> param;
    private final Set<String> hookedIds = new HashSet<>();
    private Context systemContext;

    private HookBuilder hookE(Executable executable) {
        var builder = hook(executable);

        if (getApiVersion() >= 102) {
            var id = executable.toGenericString();
            builder.setId(id);
            hookedIds.add(id);
        }

        return builder;
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
    }

    private void hookSystemServer(ClassLoader classLoader) {
        try {
            hookAllowlist();
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook allowlist receiver", t);
        }
        try {
            hookGreezeManagerService(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService", t);
        }
        try {
            hookDomesticPolicyManager(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook DomesticPolicyManager", t);
        }
        try {
            hookListAppsManager(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager", t);
        }
        try {
            hookBroadcastQueueModernStubImpl(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook BroadcastQueueModernStubImpl", e);
        }
        try {
            hookProcessPolicy(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ProcessPolicy", e);
        }
        try {
            hookAwareResourceControl(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook AwareResourceControl", e);
        }
        try {
            hookActivityManagerService(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ActivityManagerService", e);
        }
        try {
            hookInternationalPolicyManager(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook InternationalPolicyManager", e);
        }
        try {
            hookProcessCleanerBase(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ProcessCleanerBase", e);
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
    }

    private void hookPackage(String packageName, ClassLoader classLoader) {
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                hookGmsObserver(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GmsObserver", e);
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", e);
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
        // Clean reload: reset id bookkeeping and remove every previous hook so the
        // re-setup below starts fresh. Without this, old handles were never unhooked
        // (hookedIds accumulated across passes) and stacked duplicate hooks made the
        // reload appear ineffective.
        hookedIds.clear();
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
            var isAllowBroadcastMethod = GreezeManagerServiceClass.getDeclaredMethod("isAllowBroadcast", int.class, String.class, int.class, String.class, String.class);
            var getPackageNameFromUidMethod = GreezeManagerServiceClass.getDeclaredMethod("getPackageNameFromUid", int.class);
            getPackageNameFromUidMethod.setAccessible(true);
            hookE(isAllowBroadcastMethod).intercept(chain -> {
                String calleePkgName = chain.getArg(3) instanceof String calleeProcessName ? calleeProcessName : null;
                try {
                    if (chain.getArg(2) instanceof Integer calleeUid
                            && getInvoker(getPackageNameFromUidMethod).invoke(chain.getThisObject(), calleeUid) instanceof String calleePackageName) {
                        calleePkgName = calleePackageName;
                    }
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to get callee package name", e);
                }
                if (chain.getArg(4) instanceof String action
                        && ((chain.getArg(1) instanceof String callerPkgName
                        // callerPkgName get from intent or BroadcastRecord.callerPackage,
                        // both are nullable, but they won't become null in FCM broadcasts.
                        && GMS_PACKAGE_NAME.equals(callerPkgName)
                        && ACTION_REMOTE_INTENT.equals(action))
                        || ((GMS_PACKAGE_NAME.equals(calleePkgName)
                        || GMS_PERSISTENT_PROCESS_NAME.equals(calleePkgName))
                        && CN_DEFER_BROADCAST.contains(action)))) {
                    return true;
                }
                return chain.proceed();
            });
            deoptimize(isAllowBroadcastMethod);
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
            triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction", boolean.class);
        } catch (NoSuchMethodException ignored) {
            triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction");
        }
        hookE(triggerGMSLimitActionMethod).intercept(chain -> {
            if (!chain.getArgs().isEmpty()) {
                var args = chain.getArgs().toArray();
                args[0] = false;
                return chain.proceed(args);
            } else {
                var mGmsLimitEnabled = GreezeManagerServiceClass.getDeclaredField("mGmsLimitEnabled");
                UnsafeUtils.INSTANCE.setBooleanField(mGmsLimitEnabled, chain.getThisObject(), false);
                return chain.proceed();
            }
        });
        deoptimize(triggerGMSLimitActionMethod);
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
            log(Log.INFO, TAG, "GreezeManagerService#updateGmsNetStatus absent, skip");
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
                        && intent.getPackage() instanceof String targetPackage
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
            // Never add to the collection the framework hands back: it may be
            // immutable (the UnsupportedOperationException would escape into
            // ActivityManagerService) and it may be a shared, cached instance
            // (then the entries accumulate on every single call). A private copy
            // keeps the same order and element types while leaving the original
            // untouched. On any failure the original result is returned as-is.
            try {
                if (chain.getArg(0) instanceof Integer flags && (flags & 1) != 0
                        && result instanceof List<?> source) {
                    var whiteList = new ArrayList<Object>(source);
                    addIfAbsent(whiteList, GMS_PACKAGE_NAME);
                    addIfAbsent(whiteList, GMS_PERSISTENT_PROCESS_NAME);
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
                log(Log.INFO, TAG, "NetdExecutor#initGmsChain absent, skip");
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
                log(Log.INFO, TAG, "NetdExecutor#setGmsDnsBlockerState absent, skip");
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
                log(Log.INFO, TAG, "NetdExecutor#setGmsChainState absent, skip");
            }
            // Defense in depth: force setuiddnsrule → allow; skip enabling standby firewall.
            try {
                var executeMethod = NetdExecutorClass.getDeclaredMethod("execute", int.class, String.class, String.class, Object[].class);
                // Skipping a call by returning null is only safe when the method
                // returns void or a reference type: for a primitive return the caller
                // would unbox null and crash inside PowerKeeper, so in that case the
                // standby-firewall skip is dropped rather than risked.
                Class<?> executeReturn = executeMethod.getReturnType();
                boolean skippable = executeReturn == void.class || !executeReturn.isPrimitive();
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
                            return skippable ? null : chain.proceed();
                        }
                    }
                    return chain.proceed();
                });
                deoptimize(executeMethod);
                if (!skippable) {
                    log(Log.WARN, TAG, "NetdExecutor#execute returns " + executeReturn.getName()
                            + "; cannot skip the standby-firewall call safely");
                }
            } catch (NoSuchMethodException e) {
                log(Log.INFO, TAG, "NetdExecutor#execute not found, skip command-level GMS net hooks");
            }
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "Failed to hook NetdExecutor", e);
        }
        try {
            var GmsObserverClass = classLoader.loadClass("com.miui.powerkeeper.utils.GmsObserver");
            // Legacy method names — present on older PowerKeeper only.
            for (String legacyName : new String[]{"updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock"}) {
                try {
                    var legacyMethod = GmsObserverClass.getDeclaredMethod(legacyName, boolean.class);
                    hookE(legacyMethod).intercept(chain -> {
                        var args = chain.getArgs().toArray();
                        args[0] = false;
                        return chain.proceed(args);
                    });
                    deoptimize(legacyMethod);
                } catch (NoSuchMethodException e) {
                    log(Log.INFO, TAG, "GmsObserver#" + legacyName + " absent, skip");
                }
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
                    log(Log.INFO, TAG, "GmsObserver#" + alwaysSkip + " absent, skip");
                }
            }
            // HyperOS 3: updateGmsEnabled(true) / updateGmsState(true) apply limits.
            for (String limitFlag : new String[]{"updateGmsEnabled", "updateGmsState", "updateGmsInstalled"}) {
                try {
                    var limitMethod = GmsObserverClass.getDeclaredMethod(limitFlag, boolean.class);
                    hookE(limitMethod).intercept(chain -> {
                        var args = chain.getArgs().toArray();
                        args[0] = false;
                        return chain.proceed(args);
                    });
                    deoptimize(limitMethod);
                } catch (NoSuchMethodException e) {
                    log(Log.INFO, TAG, "GmsObserver#" + limitFlag + " absent, skip");
                }
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
                log(Log.INFO, TAG, "GmsObserver#updateFrameworkGmsNetStatus absent, skip");
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
                log(Log.INFO, TAG, "GmsObserver#onGoogleReachabilityChanged absent, skip");
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
            log(Log.INFO, TAG, "GmsObserver$*#googleNetworkDisconnect absent, skip disconnect rewrite");
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
                        // rather than mutating the framework's own list, which may
                        // be immutable or shared between calls.
                        try {
                            if (result instanceof List<?> source
                                    && !source.contains(GMS_PACKAGE_NAME)) {
                                var whiteList = new ArrayList<Object>(source);
                                whiteList.add(GMS_PACKAGE_NAME);
                                return whiteList;
                            }
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "Failed to extend doze white list", t);
                        }
                        return result;
                    });
                } catch (NoSuchMethodException e) {
                    log(Log.INFO, TAG, "GlobalFeatureConfigureHelper#getDozeWhiteListApps(" + argType.getSimpleName() + ") absent, skip");
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

    /** Re-read the allowlist from the shared remote preferences. */
    private void loadAllowlistFromRemotePrefs() {
        try {
            Set<String> set = getRemotePreferences(Prefs.GROUP_CONFIG)
                    .getStringSet(Prefs.KEY_ALLOWLIST, Collections.emptySet());
            sAllowlist = set != null ? new HashSet<>(set) : new HashSet<>();
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
     * Whether a target package should be woken / auto-started by FCM.
     * An empty allowlist keeps the legacy behaviour (wake everything); once the
     * user checks at least one app it becomes whitelist mode (only checked apps).
     */
    private boolean shouldWake(String targetPackage) {
        Set<String> allowlist = getFcmAllowlist();
        return allowlist.isEmpty() || allowlist.contains(targetPackage);
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

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException, NoSuchFieldException {
        var ActivityManagerServiceClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var mContextField = ActivityManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        var IApplicationThreadClass = classLoader.loadClass("android.app.IApplicationThread");
        var IIntentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var infoField = ProcessRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);
        Method getRecordMethod;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12~16
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLOSP", IApplicationThreadClass);
        } else {
            // Android 8~11
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLocked", IApplicationThreadClass);
        }
        Method broadcastMethod;
        int intentArgIndex;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions,
            //    String[] excludedPackages, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else {
            // int broadcastIntent(IApplicationThread caller,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 1;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntent",
                    IApplicationThreadClass,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        }
        hookE(broadcastMethod).intercept(chain -> {
            // This runs for every broadcast in the system, so reject on the
            // action before touching anything reflective.
            if (chain.getArg(intentArgIndex) instanceof Intent intent
                    && ACTION_REMOTE_INTENT.equals(intent.getAction())) {
                try {
                    Object app = getInvoker(getRecordMethod)
                            .invoke(chain.getThisObject(), chain.getArg(0));
                    if (app != null && infoField.get(app) instanceof ApplicationInfo info
                            && GMS_PACKAGE_NAME.equals(info.packageName)
                            // Wake / auto-start only apps the user whitelisted; empty list = all.
                            && intent.getPackage() instanceof String targetPackage
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

    /** Reused walker: building one per call would allocate on every isPushApp(). */
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private void hookInternationalPolicyManager(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var InternationalPolicyManagerClass = classLoader.loadClass("com.miui.server.greeze.InternationalPolicyManager");
        var isPushAppMethod = InternationalPolicyManagerClass.getDeclaredMethod("isPushApp", String.class);
        // Match the caller by class name rather than by ClassLoader identity:
        // RETAIN_CLASS_REFERENCE is what forces the walker to resolve and retain the
        // declaring classes, and it buys nothing here — this hook runs inside
        // system_server, so no app-supplied frame can ever appear on that stack.
        final String restrictNetOwner = InternationalPolicyManagerClass.getName();
        hookE(isPushAppMethod).intercept(chain -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    boolean fromRestrictNet = STACK_WALKER.walk(frames -> frames.anyMatch(frame ->
                            "isRestrictNet".equals(frame.getMethodName())
                                    && restrictNetOwner.equals(frame.getClassName())));
                    if (fromRestrictNet) {
                        return false;
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Stack inspection failed", t);
                }
            }
            return chain.proceed();
        });
    }

    /** How long a "declares a C2DM receiver" answer is reused. */
    private static final long C2DM_CACHE_TTL_MS = 5L * 60L * 1000L;
    /** Hard cap on the cache; cleared wholesale when exceeded. */
    private static final int C2DM_CACHE_MAX = 256;

    /** One cached C2DM-receiver answer plus when it was resolved. */
    private static final class C2dmQuery {
        final boolean declaresReceiver;
        final long checkedAtMs;

        C2dmQuery(boolean declaresReceiver, long checkedAtMs) {
            this.declaresReceiver = declaresReceiver;
            this.checkedAtMs = checkedAtMs;
        }
    }

    private final Map<String, C2dmQuery> c2dmReceiverCache = new HashMap<>();

    /**
     * Whether {@code packageName} declares a C2DM receiver.
     *
     * <p>This is what grants the cleaner exemption, and it is deliberately
     * <em>not</em> narrowed to the user's allowlist: every app that genuinely
     * ships a push receiver stays protected, which is the historical behaviour and
     * what a "keep push alive" module is expected to do. The trade-off is that the
     * action can also be declared purely to opt out of the cleaner; that was
     * reviewed and accepted on 2026-09-23 — do not "fix" it again without asking,
     * since tightening it changes behaviour for people who never opened the app.
     *
     * <p>Answering it needs a PackageManager query, and the cleaner asks far more
     * often than apps get installed, so answers are reused for a few minutes
     * instead of querying the package manager on every call. An app that only
     * starts declaring the receiver in an update keeps its old answer for at most
     * {@link #C2DM_CACHE_TTL_MS}.
     */
    private boolean declaresC2dmReceiver(PackageManager pm, String packageName) {
        long now = SystemClock.uptimeMillis();
        synchronized (c2dmReceiverCache) {
            C2dmQuery cached = c2dmReceiverCache.get(packageName);
            if (cached != null && now - cached.checkedAtMs < C2DM_CACHE_TTL_MS) {
                return cached.declaresReceiver;
            }
        }
        final boolean declares;
        try {
            var intent = new Intent(ACTION_REMOTE_INTENT);
            intent.setPackage(packageName);
            declares = !pm.queryBroadcastReceivers(intent, 0).isEmpty();
        } catch (Throwable t) {
            // Fail safe: when in doubt, let the cleaner keep its own decision.
            log(Log.ERROR, TAG, "queryBroadcastReceivers failed for " + packageName, t);
            return false;
        }
        synchronized (c2dmReceiverCache) {
            if (c2dmReceiverCache.size() >= C2DM_CACHE_MAX) {
                c2dmReceiverCache.clear();
            }
            c2dmReceiverCache.put(packageName, new C2dmQuery(declares, now));
        }
        return declares;
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
                        && declaresC2dmReceiver(pm, info.packageName)) {
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
