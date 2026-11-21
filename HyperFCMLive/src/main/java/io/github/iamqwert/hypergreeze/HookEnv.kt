package io.github.iamqwert.hypergreeze

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Shared hook-installation environment passed from [Hooker] to every
 * subsystem, replacing the 4–8 individual lambda parameters that used to be
 * threaded through each entry point.
 */
internal class HookEnv(
    val module: XposedInterface,
    val classLoader: ClassLoader,
    private val installBook: InstallBookkeeping,
    private val systemContextProvider: () -> Context?,
    private val gmsUidProvider: () -> Int?,
    private val isGmsUidCheck: (Int) -> Boolean,
    private val allowlistPackageLookup: (Int) -> String?
) {

    fun hookE(executable: Executable): XposedInterface.HookBuilder =
        installBook.hookE(executable)

    fun logSkip(message: String) = installBook.logSkip(message)

    fun logSkipOtherGeneration(symbol: String) = installBook.logSkipOtherGeneration(symbol)

    fun recordProbe(finding: String) = installBook.recordProbe(finding)

    fun systemContext(): Context? = systemContextProvider()

    fun gmsUid(): Int? = gmsUidProvider()

    fun isGmsUid(uid: Int): Boolean = isGmsUidCheck(uid)

    fun allowlistPackageForUid(uid: Int): String? = allowlistPackageLookup(uid)

    fun findMethod(
        owner: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>?
    ): Method? {
        return try {
            owner.getDeclaredMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Pin the single boolean argument of [name] to [value] and let the call
     * through. Callers pass [Log.DEBUG] for cross-generation targets (routed
     * to [logSkipOtherGeneration]) and [Log.INFO] for same-generation targets
     * (routed to [logSkip]).
     */
    fun forceBooleanArg(
        owner: Class<*>,
        name: String,
        value: Boolean,
        label: String,
        level: Int = Log.DEBUG
    ) {
        try {
            val method = owner.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) {
                    args[0] = value
                }
                chain.proceed(args)
            }
            module.deoptimize(method)
        } catch (e: NoSuchMethodException) {
            if (level == Log.DEBUG) {
                logSkipOtherGeneration("$label#$name")
            } else {
                logSkip("$label#$name absent, skip")
            }
        }
    }
}
