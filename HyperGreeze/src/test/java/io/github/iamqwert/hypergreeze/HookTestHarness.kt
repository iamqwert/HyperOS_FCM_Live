package io.github.iamqwert.hypergreeze

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Shared pure-JVM test harness for hooking subsystems without an Android
 * runtime or LSPosed framework. Mirrors the mock-ClassLoader + dynamic-proxy
 * approach so firmware differences (OS3 vs OS4), polarity, and hot-reload
 * bookkeeping can be verified directly in `:HyperGreeze:testDebugUnitTest`.
 */
internal class FakeXposedFramework(
    apiVersion: Int = 102,
    private val remotePrefsProvider: ((String) -> android.content.SharedPreferences?)? = null
) {

    val hooks: MutableMap<Executable, XposedInterface.Hooker> = LinkedHashMap()
    val hookIds: MutableMap<Executable, String> = LinkedHashMap()
    val deoptimized: MutableSet<Executable> = LinkedHashSet()

    val xposedInterface: XposedInterface = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(XposedInterface::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getApiVersion" -> apiVersion
            "getRemotePreferences" -> remotePrefsProvider?.invoke(args[0] as String)
            "deoptimize" -> {
                deoptimized.add(args[0] as Executable)
                true
            }
            "hook" -> {
                val target = args[0] as Executable
                Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(XposedInterface.HookBuilder::class.java)
                ) { self, bMethod, bArgs ->
                    when (bMethod.name) {
                        "setId" -> {
                            hookIds[target] = bArgs[0] as String
                            self
                        }
                        "intercept" -> {
                            hooks[target] = bArgs[0] as XposedInterface.Hooker
                            null
                        }
                        else -> self
                    }
                }
            }
            "getInvoker" -> {
                val target = args[0] as Method
                Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(XposedInterface.Invoker::class.java)
                ) { invoker, iMethod, iArgs ->
                    if (iMethod.name == "invoke") {
                        target.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val callArgs = iArgs[1] as Array<Any?>
                        target.invoke(iArgs[0], *callArgs)
                    } else {
                        invoker
                    }
                }
            }
            else -> null
        }
    } as XposedInterface

    val module: XposedModule = object : XposedModule() {}.apply {
        attachFramework(xposedInterface) {}
    }

    val installBook: InstallBookkeeping = InstallBookkeeping(module)

    fun env(
        classLoader: ClassLoader,
        gmsUid: Int? = 10135,
        isGmsUid: (Int) -> Boolean = { it == gmsUid },
        allowlistPackageForUid: (Int) -> String? = { null }
    ): HookEnv = HookEnv(
        module = xposedInterface,
        classLoader = classLoader,
        installBook = installBook,
        systemContextProvider = { null },
        gmsUidProvider = { gmsUid },
        isGmsUidCheck = isGmsUid,
        allowlistPackageLookup = allowlistPackageForUid
    )

    fun hookFor(owner: Class<*>, name: String, vararg paramTypes: Class<*>): XposedInterface.Hooker {
        val method = owner.getDeclaredMethod(name, *paramTypes)
        return hooks[method]
            ?: throw AssertionError("No hook registered for ${owner.simpleName}#$name")
    }

    fun constructorHookFor(owner: Class<*>, vararg paramTypes: Class<*>): XposedInterface.Hooker {
        val ctor: Constructor<*> = owner.getDeclaredConstructor(*paramTypes)
        return hooks[ctor]
            ?: throw AssertionError("No hook registered for ${owner.simpleName}.<init>")
    }

    fun isDeoptimized(owner: Class<*>, name: String): Boolean =
        deoptimized.any { it.declaringClass == owner && it.name == name }
}

internal class FakeChain(
    private val executable: Executable,
    private val receiver: Any?,
    private val defaultResult: Any?,
    vararg initialArgs: Any?
) : XposedInterface.Chain {

    private val argList: MutableList<Any?> = initialArgs.toMutableList()

    var proceedCount: Int = 0
        private set

    var lastProceedArgs: Array<out Any?>? = null
        private set

    var throwOnProceed: Throwable? = null

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = receiver

    override fun getArgs(): MutableList<Any?> = argList

    override fun getArg(index: Int): Any? = argList[index]

    override fun proceed(): Any? = proceed(argList.toTypedArray())

    override fun proceed(args: Array<out Any?>): Any? {
        proceedCount++
        lastProceedArgs = args.copyOf()
        throwOnProceed?.let { throw it }
        return defaultResult
    }

    override fun proceedWith(thisObject: Any): Any? = proceed()

    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? = proceed(args)
}

/**
 * ClassLoader that resolves explicit virtual firmware class names to test
 * stub classes, and throws [ClassNotFoundException] for names mapped to `null`.
 */
internal class MapClassLoader(
    private val mappings: Map<String, Class<*>?>,
    parent: ClassLoader = MapClassLoader::class.java.classLoader!!
) : ClassLoader(parent) {

    override fun loadClass(name: String): Class<*> {
        if (mappings.containsKey(name)) {
            return mappings[name] ?: throw ClassNotFoundException(name)
        }
        return super.loadClass(name)
    }
}
