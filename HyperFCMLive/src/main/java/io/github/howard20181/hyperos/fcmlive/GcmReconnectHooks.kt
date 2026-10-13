package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GCM reconnect repair — runs inside the `com.google.android.gms` process
 * (scope entry: [Hooker]'s package branch, gated by [Prefs.KEY_GCM_RECONNECT_FIX]).
 *
 * The module's system-side hooks keep the *delivery* path open: FCM broadcasts
 * reach stopped apps, autostart gates answer, greeze leaves GMS's network
 * alone. What none of them controls is GMS's own long connection to the push
 * servers (mtalk): when that connection drops — NAT timeout on a quiet night,
 * a flaky handover — GMS reconnects on its own alarm schedule, and while it
 * is down every push queues server-side. This hook shortens that recovery:
 * every time GMS schedules its heartbeat/reconnect timer it also schedules a
 * one-shot check; if by check time the "next connection" deadline recorded in
 * the timer is already more than a minute in the past, the connection was
 * missed rather than scheduled, and a `GCM_RECONNECT` broadcast is sent to
 * make GMS dial out immediately. Sends are throttled to one per five minutes
 * (both alarm types share that deadline, and doze deferrals re-arm it
 * repeatedly — without the throttle one episode sends a burst of no-op
 * broadcasts; observed overnight 2026-10-12, seven sends 62s→91s).
 *
 * Hook-point discovery is the fcmfix/fcmself lineage's auto-discovery, kept
 * deliberately shape-based because GMS is an auto-updating app: start from
 * `HeartbeatChimeraAlarm`, take its primary constructor's 4th parameter as the
 * timer class, find its public final `(long)` setter and public final alarm
 * field, then hook the alarm class's `(Context, int, String)` constructor and
 * match the private final String field against the constructor's tag argument
 * to locate the alarm-type property. Everything is re-derived in memory at
 * every GMS process start — nothing about GMS's class layout is trusted, and
 * every discovery step logs its skip reason so a GMS update that broke a
 * shape assumption is diagnosable from the module log without a decompiler.
 *
 * The switch is read live on every qualifying timer write, so flipping it on
 * the experiment screen takes effect on the next heartbeat — no reboot, no
 * GMS restart. The feature only ever *sends a broadcast GMS listens for
 * itself*; it never changes GMS state directly, and when it is off the only
 * overhead is one alarm-type field read per heartbeat-timer write.
 */
internal object GcmReconnectHooks {

    private const val HEARTBEAT_ALARM_CLASS =
        "com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm"
    private const val ALARM_TYPE_HEARTBEAT = "GCM_HB_ALARM"
    private const val ALARM_TYPE_CONNECTION = "GCM_CONN_ALARM"
    private const val GCM_RECONNECT_ACTION =
        "com.google.android.intent.action.GCM_RECONNECT"

    /** setTimeout 之后多久复查倒计时（ms）。fcmself 血统里定死的 5 秒缓冲。 */
    private const val COUNTDOWN_CHECK_DELAY_MS = 5000L

    /** 「下次连接时刻」落后当下超过一分钟才算错过，而不是刚好到期。 */
    private const val STALE_CONNECTION_THRESHOLD_MS = -60_000L

    /**
     * 重连广播的发送节流。GMS 的心跳和重连两类 alarm 各持一个 timer，但共享
     * 同一个「下次连接时刻」；doze 推迟期间连环重排（实测一夜：04:05 一集内
     * 62s→91s 连续七次触发、双 alarm 各发一次），第一次补发之后的都是空转——
     * 连接活着时广播是无操作，连接死了时一次也够 GMS 拨号。5 分钟内只发一次，
     * 之后的触发只留一条节流日志。
     */
    private const val SEND_THROTTLE_MS = 5 * 60_000L

    /** 上次真实发送的 elapsedRealtime；复查全部跑在单线程调度器上，无并发。 */
    private var lastSendElapsed = Long.MIN_VALUE

    /** 负倒计时复查共用的单线程守护调度器，避免每次 setTimeout 都新建线程。 */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fcm-gcm-reconnect").apply { isDaemon = true }
        }

    /** setTimeout 钩子只在首次命中 alarm 类型后安装一次。 */
    private val timerHookInstalled = AtomicBoolean(false)

    /**
     * GMS 进程侧安装入口：发现钩点并挂上 alarm 构造监听。
     *
     * 与其它实验开关一致，这里**不**预读开关做早退——安装 unconditional，
     * 开关在每次 timer 写入时即时读取，翻转无需重启。发现链任何一环断了
     * 都只 logSkip，不影响 GMS 本体。
     */
    internal fun hookGcmReconnect(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
    ) {
        val prefs = try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
        } catch (ignored: Throwable) {
            null
        }
        // GMS 进程没有 allowlist 重载可搭，这次读取就是该进程调试日志门控的
        // 初始快照；后续随 timer 写入逐次刷新（见 [isRepairEnabled]）。
        HookLog.refresh(prefs)

        val heartbeat = try {
            classLoader.loadClass(HEARTBEAT_ALARM_CLASS)
        } catch (ignored: Throwable) {
            null
        }
        if (heartbeat == null) {
            logSkip("$HEARTBEAT_ALARM_CLASS absent; GCM reconnect repair not installed")
            return
        }

        val timer = discoverTimerClass(heartbeat) ?: run {
            logSkip("$HEARTBEAT_ALARM_CLASS has no 4-parameter primary constructor; shape changed upstream")
            return
        }
        val setTimeout = timer.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                Modifier.isFinal(it.modifiers) &&
                Modifier.isPublic(it.modifiers)
        } ?: run {
            logSkip("${timer.name} has no public final (long) setter; shape changed upstream")
            return
        }
        setTimeout.isAccessible = true
        val timerField = timer.declaredFields.firstOrNull {
            Modifier.isFinal(it.modifiers) && Modifier.isPublic(it.modifiers)
        } ?: run {
            logSkip("${timer.name} has no public final alarm field; shape changed upstream")
            return
        }
        timerField.isAccessible = true
        val alarmClass = timerField.type
        val alarmCtor = alarmClass.constructors.firstOrNull {
            it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == String::class.java
        } ?: run {
            logSkip("${alarmClass.name} has no (Context, int, String) constructor; shape changed upstream")
            return
        }

        // No install-time context check on purpose: a slow-starting GMS process
        // (e.g. :ui) loads the module before its Application exists, and bailing
        // here would only produce a misleading "target absent" summary — the
        // broadcast send re-fetches the context at fire time anyway, where a
        // started process always has one.

        // alarm 构造监听：构造时比对私有 final String 字段与第三个入参（alarm
        // 类型 tag），命中即定位 alarm 类型属性路径并安装 timer 钩子。
        alarmCtor.isAccessible = true
        hookE(alarmCtor).intercept { chain ->
            val result = chain.proceed()
            try {
                locateAlarmTypeAndArm(
                    module, hookE, alarmClass, timerField, timer, setTimeout,
                    chain.thisObject, chain.getArg(2),
                )
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "GCM reconnect: alarm inspection failed", t)
            }
            result
        }
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "GCM reconnect repair armed; waiting for ${alarmClass.name} construction to locate the alarm-type property"
        )
    }

    /** HeartbeatChimeraAlarm 主构造器的第 4 参即 timer 类；无声明方法时上溯父类。 */
    private fun discoverTimerClass(heartbeat: Class<*>): Class<*>? {
        val ctor = heartbeat.constructors.firstOrNull() ?: return null
        var timer = ctor.parameterTypes.getOrNull(3) ?: return null
        if (timer.declaredMethods.isEmpty()) {
            timer = timer.superclass ?: return null
        }
        return timer
    }

    /**
     * alarm 构造回调：找到持有 tag 的私有 final String 字段后安装 timer 钩子。
     * 一次 GMS 生命周期里可能构造多个 alarm 实例，未命中 tag 的实例静默放过。
     */
    private fun locateAlarmTypeAndArm(
        module: XposedInterface,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        alarmClass: Class<*>,
        timerField: Field,
        timer: Class<*>,
        setTimeout: Executable,
        alarm: Any?,
        alarmTag: Any?,
    ) {
        if (timerHookInstalled.get() || alarm == null || alarmTag == null) {
            return
        }
        val typeField = alarmClass.declaredFields.firstOrNull {
            it.type == String::class.java &&
                Modifier.isFinal(it.modifiers) &&
                Modifier.isPrivate(it.modifiers) &&
                run {
                    it.isAccessible = true
                    it.get(alarm) === alarmTag
                }
        } ?: return

        if (!timerHookInstalled.compareAndSet(false, true)) {
            return
        }
        val setTimeoutMethod = setTimeout as? java.lang.reflect.Method ?: return
        // 传已解析的 Field 对象而非名字路径：setTimeout 收到的 thisObject 运行时
        // 类可能是声明类的子类（R8 混淆下常见），按名字在 javaClass 上找声明于
        // 父类的字段会 NoSuchFieldException；Field 对象反射自声明类，对子类实例
        // 直接可读。alarm 构造与 timer 写入之间类不变，解析一次终身有效。
        val alarmTypeField = typeField
        val timerAlarmField = timerField
        hookE(setTimeoutMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                scheduleStaleCheck(
                    module, chain.thisObject, alarmTypeField, timerAlarmField,
                    timer, chain.getArg(0),
                )
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "GCM reconnect: check scheduling failed", t)
            }
            result
        }
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "GCM reconnect repair: hook point located (${timerAlarmField.name}.${alarmTypeField.name}" +
                " via ${timer.name}#${setTimeoutMethod.name})"
        )
    }

    /**
     * timer 写入回调（after）：只关心 GCM 心跳/重连两类 alarm；开关即时读取，
     * 关着就只有字段读取的开销。命中则按「本次设定的时长 + 缓冲」排一次复查，
     * 复查时若「下次连接时刻」已落在过去一分钟开外，就补发重连广播。
     */
    private fun scheduleStaleCheck(
        module: XposedInterface,
        timer: Any?,
        alarmTypeField: Field,
        timerAlarmField: Field,
        timerClass: Class<*>,
        timeoutArg: Any?,
    ) {
        if (timer == null || timeoutArg !is Long) {
            return
        }
        val alarmObject = timerAlarmField.get(timer)
        val alarmType = alarmObject?.let { alarmTypeField.get(it) } as? String
        if (alarmType != ALARM_TYPE_HEARTBEAT && alarmType != ALARM_TYPE_CONNECTION) {
            return
        }
        if (!isRepairEnabled(module)) {
            return
        }
        // 「下次连接时刻」= timer 的 long 字段里值最大的那个（并列取先声明者，
        // 与 fcmself 一致）；现在与复查时各读一次，避免缓存过期值。
        val maxField = timerClass.declaredFields
            .filter { it.type == Long::class.javaPrimitiveType }
            .map { field ->
                field.isAccessible = true
                field to (field.get(timer) as Long)
            }
            .maxByOrNull { it.second }
            ?.first ?: return
        val maxFieldName = maxField.name
        maxField.isAccessible = true
        scheduler.schedule(
            {
                try {
                    val nextConnectionTime = maxField.get(timer) as Long
                    val staleBy = nextConnectionTime - SystemClock.elapsedRealtime()
                    if (nextConnectionTime != 0L && staleBy < STALE_CONNECTION_THRESHOLD_MS) {
                        val appContext = AppContext.current()
                        if (appContext == null) {
                            // 不节流这条路径：上下文缺失时发送本就没发生，
                            // 留着下一次触发重试，WARN 只负责记录失效。
                            module.logGated(
                                Log.WARN, HookerSupport.TAG,
                                "GCM reconnect: connection missed by ${-staleBy / 1000}s but no application context to send from"
                            )
                            return@schedule
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSendElapsed < SEND_THROTTLE_MS) {
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "GCM reconnect: connection missed by ${-staleBy / 1000}s;" +
                                    " repair throttled, ${SEND_THROTTLE_MS / 1000}s window not elapsed"
                            )
                            return@schedule
                        }
                        lastSendElapsed = now
                        appContext.sendBroadcast(Intent(GCM_RECONNECT_ACTION))
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "GCM reconnect: connection missed by ${-staleBy / 1000}s; sent $GCM_RECONNECT_ACTION"
                        )
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "GCM reconnect: stale check failed", t)
                }
            },
            timeoutArg + COUNTDOWN_CHECK_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 开关即时读取；这次读取同时刷新调试日志门控快照。 */
    private fun isRepairEnabled(module: XposedInterface): Boolean {
        return try {
            val prefs = module.getRemotePreferences(Prefs.GROUP_CONFIG)
            HookLog.refresh(prefs)
            prefs.getBoolean(Prefs.KEY_GCM_RECONNECT_FIX, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /** GMS 进程侧的 Application 上下文，每次现取（进程内晚于 install 的调用必然拿得到）。 */
    private object AppContext {
        fun current(): Context? {
            return try {
                // ActivityThread 是 hidden API，编译期不可见，只能反射——
                // 与 GmsSupport.getPowerKeeperContext() 同一套取法。
                val currentApplication = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                currentApplication.invoke(null) as? Context
            } catch (ignored: Throwable) {
                null
            }
        }
    }
}
