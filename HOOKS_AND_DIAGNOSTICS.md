# HyperFCMLive：Hook 实现与诊断方法技术文档

> 面向维护者与二次开发者。本文描述模块**当前实际存在**的钩子、其判据来源，以及配套的观测与取证体系。
> 
> 本文于 2026-10-04 依据既有验证记录重整：统一章节结构与术语，删除已被推翻的假设（另立"已排除的方案"存档），并为每条结论标注验证来源（见附录 D）。

---

## 0. 文档定位

| 维度   | 说明                                                                            |
| ---- | ----------------------------------------------------------------------------- |
| 描述对象 | `Hooker.kt`（模块唯一 Xposed 入口）及其依赖 `Prefs.kt`、`UnsafeUtils.kt`、`hiddenapi:stubs` |
| 描述层级 | 方法级钩点、判定极性、数据来源、失效模式                                                          |
| 不覆盖  | Compose UI、主题引擎、更新检查、licensing                                                |
| 证据来源 | 源码注释中的字节码偏移结论、ROM 静态取证、运行时 `dumpsys`、LSPosed 导出的 `modules_*.log`              |

阅读前须接受一条贯穿全文的原则：**本模块大量钩点在验证机上是零触发的**。它们按"防御位"安装——ROM 尚未真正做出限制动作时不改变任何行为。因此本文对每个钩点标注其性质，三者不可混为一谈，也不允许由零触发反推出"无用"：

| 性质       | 定义                                                    | 判读方式                       |
| -------- | ----------------------------------------------------- | -------------------------- |
| **活跃路径** | 该门在验证机上确实对 GMS 或目标应用判"否"，钩子改变了结果                      | 有对应的运行时命中日志                |
| **防御位**  | 该门在本代 ROM 上未对本模块关心的对象判"否"，或整条路径未被执行；钩子仅在 ROM 行为改变时起作用 | 装机日志确认已挂载；运行时零命中属预期        |
| **只读探针** | 只观测、不改写返回值                                            | 计数 / 快照行；用于决定该门是否需要晋升为行为钩子 |

> **本文的脱敏约定**：涉及具体设备、网络环境与使用者的部分一律改写为类别化描述或中性占位，不影响判读，也请补充新素材时沿用：
> 
> - 取样设备只标机型代号与 OS 代次。
> - **第三方应用一概不写真名与真包名**，改用类别（如"某社交应用"）或占位包名（如 `<示例包名>`）。
> - 例外只有三类，它们属于模块自身的公共接口而非使用者信息：Google FCM/GMS 组件（如 `com.google.android.gms`）、系统与 ROM 服务（如 `com.miui.powerkeeper`、`com.xiaomi.xmsf`）、以及已随 UI 文案对外公开的实验对象（微信）。
> - 本地取证素材目录写占位路径 `<evidence>/…`；改写不得破坏技术结论，故原始行号/偏移/参数方向一律原样保留。

---

## 1. 概述与目标

### 1.1 问题域：FCM 的投递链

FCM 在设备上不是一条长连接直达应用的链路，而是一次**跨进程的广播唤醒**：

```
云端 → GMS 长连接(MCS) → GMS 进程 → c2dm 广播(ACTION_REMOTE_INTENT)
     → system_server AMS 广播队列 → 目标应用 receiver → 应用自行拉取/展示
```

关键在于：**GMS 只负责把广播发出去**。能否叫醒目标应用、广播会不会被排队延后、目标进程是否处于冻结态，全部由 system_server 与 MIUI 私有服务决定。因此"推送收不到"在绝大多数情况下不是连接问题，而是**投递链上某个判定点返回了否**。

### 1.2 ROM 侧的四组拦截面

HyperOS 在这条链路上叠加了四组彼此独立的策略，模块的设计正是按这四组划分的：

| 面              | 归属                                                                                                      | 典型表现                                             |
| -------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| **A. 广播投递**    | `ActivityManagerService` / `BroadcastQueueModernStubImpl` / greeze 广播门控                                 | 广播被延迟（defer）、被判为不允许、被缓存到解冻后再投递；目标处于 stopped 态收不到 |
| **B. 冻结与网络策略** | `GreezeManagerService` / `AurogonImmobulusMode` / `PolicyMaker` / `DomesticPolicyManager`               | uid 被冻结、socket 被销毁、UDP 包过滤被下发、网络限制标记被置位          |
| **C. 清理与自启动**  | `ProcessCleanerBase` / `ProcessPolicy` / `ListAppsManager` / `AwareResourceControl`                     | 进程被强制停止、进入黑名单、被剥夺数据网络                            |
| **D. 省电与网络阻断** | `com.miui.powerkeeper`（`NetdExecutor` / `GmsObserver` / `AppStandbyController` / `UserConfigureHelper`） | GMS 专属防火墙链、DNS 拦截、待机限制、闹钟门控、场景编译结果               |

其中 D 面在**独立进程** `com.miui.powerkeeper` 中，其余三面在 `system_server`。这直接决定了模块的作用域划分（见 2.2）。

### 1.3 为什么必须在框架层做

- 拦截判定发生在被拦截方**无法观测**的位置：GMS 不知道自己的广播被 defer 了，应用也不知道自己被判为 stopped。
- 所有判定点都是服务端私有方法，没有公开 API 可以撤销。
- 修改 GMS 自身无效且不可行：判定发生在系统侧。

### 1.4 模块目标与非目标

**目标**

1. 让 GMS 自身在 ROM 各策略面上恒定豁免：冻结、网络限制、强停、清理、待机限制。
2. 让 c2dm 投递广播跨过 ROM 的广播门控：自启动判定、冻结 receiver、缓存延后、stopped 包投递。
3. 为白名单内的目标应用提供投递瞬时的豁免：`FLAG_INCLUDE_STOPPED_PACKAGES` 与约 2 s 的临时省电豁免。

**非目标（明确不做）**

| 项              | 说明                                                                                     |
| -------------- | -------------------------------------------------------------------------------------- |
| 不 hook GMS 自身  | 判定发生在系统侧；不注入 GMS、不修改 GMS 数据、不做应用保活                                                     |
| 不提供非 GMS 应用的免冻 | 代价远超模块语义范围                                                                             |
| 不自建 netd 防火墙链  | 系统本就有两层 uid 收口，实测 GMS 在两层均已放行（见 6.6）                                                   |
| 不治理国产推送栈       | Mi Push / 个推 / HMS / 荣耀由 `com.xiaomi.xmsf`承载，与 c2dm 不同构，四项 Firebase 检测不匹配——这是设计如此，不是漏检 |

---

## 2. 适用环境

### 2.1 验证平台

| 项                                     | 值                                                     |
| ------------------------------------- | ----------------------------------------------------- |
| 设备                                    | 小米机型（代号 myron），已 root，ADB 连接                          |
| Android                               | 17（API 37）                                            |
| ROM                                   | HyperOS OS4.0.0.33.XPMCNXM（本文下称 **V816**），region = CN |
| 注入框架                                  | LSPosed（lspd + zygisk），libxposed API 102              |
| GMS uid                               | 10133（该机读数，随设备而变）                                     |
| `minSdk` / `targetSdk` / `compileSdk` | 35 / 37 / 37                                          |
| ABI                                   | 仅 `arm64-v8a`                                         |
| Java / Kotlin                         | Java 21 / Kotlin 2.2.10                               |

**对照机**：一台 OS3 设备，仅用于跨代核对（见 2.3）。其代次的硬判据是 `AurogonImmobulusMode#isNoRestrictFreezeable` absent（该方法仅 OS4 存在）。

### 2.2 注入域

`META-INF/xposed/scope.list` 声明两个域，入口类由 `java_init.list` 指定为 `Hooker`：

| 域                      | 回调                                   | 承载的钩点组           |
| ---------------------- | ------------------------------------ | ---------------- |
| `system`               | `onSystemServerStarting`             | A、B、C 面 + 全部只读探针 |
| `com.miui.powerkeeper` | `onPackageReady`（仅 `isFirstPackage`） | D 面              |

划分依据是类可见性：`NetdExecutor` 与 `GmsObserver` 只在 PowerKeeper 进程内被实例化，在 system_server 里解析不到类；greeze 系列则只在 system_server。强行合并会产生大量 `ClassNotFoundException`，也会让"缺失目标"计数失去意义。

### 2.3 ROM 代次差异（OS3 / OS4）

| 差异点                                                                                                  | 处理方式                                         |
| ---------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `PowerKeeperAppConfigure#fillScenarioContent` 7 参（OS3）/ 8 参（OS4，尾部多一个 `Map`）                         | 按参数个数两组都钩，`PowerKeeperAppConfigure` 始终是第 2 参 |
| `AurogonImmobulusMode#isNoRestrictFreezeable(String,int)` 仅 OS4                                      | 缺失走 `logSkipOtherGeneration`（DEBUG）          |
| `AppStandbyController` 下游混淆字母（OS3 `s:(IZ)V`，OS4 `r:(IZ)V`）                                           | 不硬编码，只钩稳定的 `setUidState(IZ)V`                |
| `GmsObserver$i` 内部类序号漂移                                                                              | 遍历 1..8，按"声明了 `googleNetworkDisconnect`"定位   |
| `AMS#broadcastIntentWithFeature` 多组签名                                                                | 三组候选依次尝试 + `broadcastIntent` 回退              |
| `getRecordForAppLOSP` / `getRecordForAppLocked`                                                      | 前者优先，后者回退，都没有则降级为 binder uid 反查包名            |
| `ListAppsManager.mSystemBlackList` / `SYSTEM_BLACK_LIST`、`mUseDataWhiteList` / `USE_DATA_WHITE_LIST` | 字段名两个候选都试                                    |
| `PolicyManager` 实现二选一（Domestic / International）                                                      | **按代次分别覆盖**，不假设只有一个生效                        |

最后一条是最重要的一条：**CN ROM 走 Domestic 实现，`InternationalPolicyManager` 从不实例化**。挂在它上面的 `isPushApp` 钩子在本类 ROM 上方法体永不执行，因此网络限制必须补 `DomesticPolicyManager#isRestrictNet`，而不能只在 International 侧做——历史上正是漏了这一侧，导致帮助页承诺的"推送免网络限制"在 CN ROM 上从未落地。

#### 2.3.1 策略实现的分派链：字节码确证与「不改写」的依据

实现选择发生在 `AurogonImmobulusMode` 内部，选出的是**全进程唯一一个** `PolicyManager` 实例，没有 per-app 维度。`restorePolicyManager`（`@170404`，`PUBLIC`）的形态：

```text
0000: iget-boolean v0, mSetForceCnGlobal
0002: if-nez v0, 0011                 ; false ⇒ 跳过重算，沿用现有 mCurrentCNPolicy
0004: PolicyManager.isCnModel()
0008: if-eqz → STATE_INTERNATIONAL else STATE_DOMESTIC
000f: iput mCurrentCNPolicy
002b: mCurrentCNPolicy != STATE_DOMESTIC → 0043
0031: mDomesticPolicy == null ⇒ new DomesticPolicyManager(ctx, this)
003e: mPolicyManager = mDomesticPolicy
0043: mInternationalPolicy == null ⇒ new InternationalPolicyManager(ctx, this)
0050: mPolicyManager = mInternationalPolicy
```

三个要点：

1. `mSetForceCnGlobal` 门控的是"**是否按 `isCnModel()` 重算 `mCurrentCNPolicy`**"，不是"强制使用 CN 实现"。该标志为 false 时，`restorePolicyManager` 只按现有取值重设 `mPolicyManager`，不重新判定。
2. 实例带缓存（`mDomesticPolicy` / `mInternationalPolicy` 非 null 则复用），换实现只在 `mCurrentCNPolicy` 取值变化之后发生。
3. `isCnModel()` 由 `PolicyManager#isCnModel`（`@1939b8`）转发到 `PolicyManagerConfig#isCnModel`（`@193954`），读的是 `sCnModel`。

**`sCnModel` 的来源是 region**，既不是 build flag 也不是云控：

```text
PolicyManagerConfig.<clinit>
  0000: const-string v0, "CN"
  0002: miui.os.Build.getRegion()
  0006: "CN".equals(region)
  000a: sput-boolean v0, sCnModel
```

字段声明为 `PRIVATE STATIC boolean`（**无 `FINAL`**，故普通反射即可写）。本机 `ro.miui.region` 读数为 `CN` ⇒ `sCnModel = true` ⇒ `mCurrentCNPolicy = STATE_DOMESTIC`（`dumpsys greezer` 呈现为 `mCurrentCNPolicy: 1`）。

**ROM 自带的两条通道都走不通**，这是维持现状的直接依据：

| 通道                                                             | 判定               | 依据                                                                                                                                                                                                                                                 |
| -------------------------------------------------------------- | ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PolicyManagerConfig#setCnModel(Z)`（`PUBLIC STATIC`，ROM 自身即调用） | 不采用              | 该方法在写 `sCnModel` 的同时翻转 `greeze.power.ConfigManager.isGlobal`（`@1939a8`：`0000 sput sCnModel` → `0002 xor-int/lit8 v0, v1, 1` → `0004 sput ConfigManager.isGlobal`）。这不是 greeze 内部状态，而是整机"中国/国际模式"的一部分，影响面远超本模块的目标                                    |
| `dumpsys greezer force_cn_global set <N>`                      | 本机不可达；且语义上指定不了实现 | ① 分支首条即 `sget-boolean Build.IS_DEBUGGABLE` + `if-eqz → 跳过`（`@16ec46`），本机 `ro.debuggable=0`、`ro.build.type=user`；② 即便可达，该分支写 `mSetForceCnGlobal = true` 后立刻调 `restorePolicyManager()`，而后者在该标志为真时按 `isCnModel()` 重算，把刚写入的 N 覆盖掉。该命令只能打开"按 region 重算" |
| 自行构造 `InternationalPolicyManager` 实例注入                         | 不可行              | `PolicyManager` 接口未声明 `isPushApp`，且 `isPushApp` 的全部调用者都在 `InternationalPolicyManager` **类内**以 `invoke-direct` 自调用。换对象不会让这些调用改道                                                                                                                     |

**重算入口会把 `sCnModel` 打回 region 值**：`GreezeManagerService#onDeviceProvisionedChanged`（`@189360`）的第一条动作就是 `setCnModel("CN".equals(Build.getRegion()))`（`@189384`），随后才调 `restorePolicyManager()`。`restorePolicyManager` 在本 dex 内共 5 处调用点（`AurogonImmobulusMode#init`、`ImmobulusBroadcastReceiver#onReceive` ×2、dump 命令分支、`onDeviceProvisionedChanged`）——实现选择确实可在运行时被再次触发，但触发的结果是回到 region 对应的那一个。

**结论：不改写实现选择，维持上表的「按代次分别覆盖」。** 理由是收益为零而代价确定：GMS 免网络限制的目标已由 `DomesticPolicyManager#isRestrictNet → false` 达成（3.5，极性经字节码核对）；若强行改道 International，等于把一条已在本机验证过的链路换成一条在此机上从未执行过的链路，同时翻转整机的 `ConfigManager.isGlobal`。`InternationalPolicyManager#isPushApp` 钩子保留，服务的是国际版 ROM。

### 2.4 缺失目标的降级

每一个钩点组独立 `try/catch`，缺失按性质分两级：

- **`NoSuchMethodException` / `NoSuchFieldException`** → `logSkip`（INFO，计入 `hookTargetsAbsent`）或 `logSkipOtherGeneration`（DEBUG，计入 `hookTargetsAbsentOtherGeneration`），继续装下一个；
- **`ClassNotFoundException`** → 通常 ERROR（说明域选错了或代次跳变太大）。

**任何一个钩点失败都不影响其余钩点**。装机摘要行里的 `M target(s) absent on this ROM` 即该降级计数的呈现，它本身是兼容性健康度指标：`M` 突然增大意味着 ROM 代次变了。**跨代预期缺失不计入 `M`**，而是在同一行尾单列 `, K cross-generation (expected)`——否则这台机器上一次完全健康的安装会显示成 `10 target(s) absent`，把唯一要看的读数（"这次 OTA 真丢了本代该有的东西"）埋掉。

### 2.5 构建与工具链

| 项                 | 值                                                                               | 说明                                                                |
| ----------------- | ------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| libxposed         | `api:102` compileOnly + `service:102`                                           | `setId` 与热重载能力依赖 API ≥ 102，代码里做 `apiVersion >= 102` 判断            |
| `minifyEnabled`   | release 为 true                                                                  | 入口类必须保持 public + 无参构造（proguard 规则）                                |
| `static final` 写入 | Android 新版本限制                                                                   | `UnsafeUtils` 检测 `CINNAMON_BUN` / `BAKLAVA` preview 并切换 Unsafe 路径 |
| `/proc/net/tcp`   | 不可用                                                                             | system_server SELinux 域无读权限；且答非所问（见 6.2）                          |
| 构建命令              | `./gradlew --no-daemon -PallowDebugSignedRelease :HyperFCMLive:assembleRelease` | 产物 `HyperFCMLive-<versionName>.<versionCode>.apk`，arm64-only      |
| 单元测试              | `McuColorTest`、`UpdateCheckerVersionTest`                                       | JVM 层，不需要设备                                                       |

---

## 3. Hook 点与注入机制

### 3.1 通用机制

| 机制                            | 作用                                                                                                                          |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `hookE(Executable)`           | 所有钩子的唯一入口，递增 `hooksInstalled`；API ≥ 102 时调用 `setId(toGenericString())`，使同一目标在热重载时收敛为一条活钩子而非多条                               |
| `deoptimize(method)`          | 安装后去优化，避免被内联后钩子不进                                                                                                           |
| `skipValueFor(returnType)`    | 按返回类型给出安全零值：基本类型给 `false/0/0L/…`，`void` 与对象类型给 `null`                                                                       |
| `getInvoker(method)`          | 在钩子内调用原方法时使用，避免递归回自己的回调                                                                                                     |
| `UnsafeUtils.setBooleanField` | 绕过 `static final` 写入限制（Android 新版本对 `Field.set` 加了限制）。先尝试常规 `setBoolean`，失败再用 `Unsafe` 按 ART 字段偏移写入；`volatile` 字段走 CAS 字节写入 |
| `isGmsUid(uid)`               | `uid % 100000` 与缓存的 GMS appId 比较；首次调用经 PackageManager 解析并缓存                                                                 |

**回调内的硬约束**（源码注释明确要求，改动时不得违反）：

1. 绝不把异常抛回 system_server / PowerKeeper；
2. 不阻塞主线程（耗时动作全部投递到 `HandlerThread`）；
3. 不用协程；
4. 探针类辅助方法必须自行吞掉所有异常。

### 3.2 安装生命周期与热重载

```
onSystemServerStarting ──▶ hookSystemServer(classLoader) ──▶ logSummary("system_server")
onPackageReady         ──▶ hookPackage(pkg, classLoader)  ──▶ logSummary(pkg)
onHotReloading         ──▶ 保存 (pkg, classLoader) 到 savedInstanceState，返回 true
onHotReloaded          ──▶ 逐个 unhook 旧 handle ──▶ 重跑完整安装序列
```

热重载是**完整支持**的（libxposed API 102）：`onHotReloaded` 会显式 unhook 所有旧 handle 再重新安装，因此不需要重启。除非用户所用 LSPosed 管理器还没升级到支持libxposed API 102 的版本。

### 3.3 配置作用域

"作用域"有三个容易混淆的层次：

1. **进程作用域**：钩子装在哪个进程（见 2.2）。
2. **配置作用域**：钩子是否受用户白名单 / 严格模式约束（本节）。
3. **生效面作用域**：钩子作用于 GMS 自身、目标应用，还是整机策略表。

第三层常被忽略：部分保护是以**改写整机策略表**的形态落地的（睡眠网络白名单、`ProcessPolicy#getWhiteList`、`MILLET_NO_RESTRICT_APP`），它们天然不受白名单约束，也不应该受约束——否则就违反了"GMS 恒定受保护"这条基线。

#### 3.3.1 跨进程配置通路

system_server 无法读取模块私有文件（SELinux MLS 类别限制），按需 ContentProvider 查询又不可靠，因此使用 libxposed 的**远程偏好**作为唯一真相源：

```
UI 编辑
  → 本地镜像 (fcmlive_allowlist_cache)        // 仅用于列表秒开与"待推送"标记
  → 单线程 executor 同步 commit 到远程 prefs   // Prefs.GROUP_CONFIG
  → 广播 ACTION_ALLOWLIST_CHANGED ×3 (0 / 400ms / 1500ms)
  → system_server 的 BroadcastReceiver（独立 HandlerThread "fcmlive-allowlist"）
  → requestAllowlistReload → 重装 sAllowlist + sStrictMode
```

三处细节：

- **广播发三次**：接收器在开机早期由重试循环安装（`installAllowlistReceiverAsync`，最多 120 次 × 1 s）。用户在该窗口内改配置，前两次会丢，第三次补上。
- **写失败保留 pending 标记**（`hasPendingPush`），下一次绑定时把镜像推上去，而不是被旧的远程值覆盖。
- **写入用 `commit()` 而非 `apply()`**：广播不能跑在它所宣布的值之前。

#### 3.3.2 生效范围谓词 `moduleAppliesTo(packageName, tier)`

```kotlin
moduleAppliesTo(pkg, tier) =
    allowlist.isEmpty() || allowlist.contains(pkg) || pkg ∈ {GMS, GMS.persistent}
    || (tier == STRICT && !strictMode)
```

两层 tier 的差异是**刻意的**，对应 HELP 第四章「白名单机制」与第五章「严格模式」：

| tier          | 覆盖的门                                                                                            | 白名单何时生效                           |
| ------------- | ----------------------------------------------------------------------------------------------- | --------------------------------- |
| `Tier.WAKE`   | `checkApplicationAutoStart`、`isRestrictReceiver`、`isNeedCachedBroadcast`、`AMS#broadcastIntent*` | **始终**：名单非空时未勾选应用就拿不到这几项          |
| `Tier.STRICT` | `isAllowBroadcast`、`isPushApp`、`isForceStopEnable`                                              | **仅严格模式**：默认对全部应用放行，严格模式才收回到勾选的应用 |

两条不变式：

- 两层都是 fail-open（空名单全放行），与"一个都不勾选 = 全部放行"一致；
- 两层都有 GMS 恒定豁免，保证 GMS 自身永远不受白名单影响（否则非空名单会让唯一没有 caller 校验的调用点把 GMS 自己挡在外面）。

当前实现中，原先的 `shouldApply`（≡ `Tier.STRICT`）与 `shouldWake`（≡ `Tier.WAKE`）合并为这一个带 `tier` 参数的函数，**行为未变**：两者原本长得几乎一样，真实差异（哪一层忽略 `strictMode`）只存在于调用点；现在差异写在调用点上，实现只有一份。

严格模式的**实际收权面比名义上小**：`Tier.STRICT` 名义上有 3 个调用点，但在 CN ROM 上 `InternationalPolicyManager` 从不实例化（见 2.3），真实收权面只有 `isAllowBroadcast` 与 `isForceStopEnable` 两处。

#### 3.3.3 开局 fail-open 窗口

`hookAllowlist()` 在装载时**同步**读一次远程 prefs。成功路径在**内容变化**时打一条 INFO（`allowlist loaded: N pkg(s), strict=…`），因此日志里能区分"严格模式已生效"与"还没读到名单"。失败路径保留 ERROR 日志，并按**指数退避**重试（1 s 起步、×2、封顶 `ALLOWLIST_STALE_MS` = 10 s）——stale 判断读 `sAllowlistFreshMs`（上次成功时刻），`sAllowlistReadMs` 保持真实尝试时刻、只用于 `requestAllowlistReload()` 的 500 ms 节流，因此持续失败不会形成忙循环。窗口内仍表现为偏松（全放行），不会丢推送。

### 3.4 A 面：广播投递（system_server）

| 钩点                                                       | 判定与动作                                                                                                                                       | 守门                      | 性质  |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- | --- |
| `AMS#broadcastIntentWithFeature` / `#broadcastIntent`    | 命中 `ACTION_REMOTE_INTENT` 且 caller 是 GMS：补 `FLAG_INCLUDE_STOPPED_PACKAGES`，并为目标包申请 `addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000)` | `Tier.WAKE`             | 活跃  |
| `BroadcastQueueModernStubImpl#checkApplicationAutoStart` | 冷启动路径（有 `ResolveInfo`）：caller=GMS + c2dm → 返回 `true`                                                                                        | `Tier.WAKE`             | 活跃  |
| `GreezeManagerService#isRestrictReceiver`                | 温而冻的 receiver 路径：返回 `false`，并**主动复现原生解冻** `thawUidAsync(uid, 1000, "bc_action")`                                                            | `Tier.WAKE`             | 活跃  |
| `GreezeManagerService#isNeedCachedBroadcast`             | 命中 c2dm → 返回 `false`，避免广播被缓存到解冻后                                                                                                            | `Tier.WAKE`             | 活跃  |
| `GreezeManagerService#isAllowBroadcast`                  | GMS 的 c2dm / CN 重连动作 → `true`（caller 判定优先 callerPkg，回退 callerUid）                                                                           | `Tier.STRICT`           | 活跃  |
| `DomesticPolicyManager#deferBroadcast`                   | 4 个 CN 重连动作 → `false`；c2dm **不再豁免**（修正理由见 5.5）                                                                                              | 由 `isAllowBroadcast` 承担 | 活跃  |
| `GreezeManagerService#deferBroadcastForMiui`             | 4 个 CN 动作 → `false`                                                                                                                         | 无（CN 队列属 GMS 内部）        | 活跃  |

`isRestrictReceiver` 这一处有一段不可替代的历史：早期版本直接短路 `checkReceiverIfRestricted`，跳过了原生路径上的 `thawUidAsync("bc_action")`，结果是广播被投递到一个仍然冻结的进程、无人解冻，GMS 反复重试同一条消息（表现为 `No response to broadcast`）。现在的形态是**回答 false 并自己补上解冻**，reason 与调用方 uid 与原生路径一致，greeze 的记账才不会错位。

签名适配：`broadcastIntentWithFeature` 准备了三组候选签名（15/14/13 参）依次尝试，回退到 `broadcastIntent` 时 `intentArgIndex` 从 2 变为 1。caller 识别优先用 `getRecordForAppLOSP`，回退 `getRecordForAppLocked`，两者都没有时降级为 `Binder.getCallingUid()` 反查包名。

### 3.5 B 面：冻结与网络策略（system_server）

| 钩点                                                           | 判定与动作                                          | 性质                        |
| ------------------------------------------------------------ | ---------------------------------------------- | ------------------------- |
| `AurogonImmobulusMode#isNoRestrictApp(String)`               | GMS → `true`（"在免限集合里"）                         | 防御位                       |
| `AurogonImmobulusMode#isNoRestrictFreezeable(String,int)`    | GMS → `false`（"不要冻结"）；仅 OS4 存在                 | 防御位                       |
| `AurogonImmobulusMode#triggerQuickFreeze(I,I)`               | GMS uid → 跳过（返回类型安全零值）                         | 防御位                       |
| `PolicyMaker#isAllowFreeze(I)`                               | GMS uid → 跳过                                   | 防御位                       |
| `DomesticPolicyManager#isRestrictNet(I)`                     | GMS uid → `false`；已勾选应用 uid → `false`（实验开关 `greeze_net_release`，默认关） | 防御位 + 实验档              |
| `GreezeManagerService#udpPackageRestrict(I,boolean)`         | GMS uid 且 `allow==true` → 跳过                   | 防御位                       |
| `GreezeManagerService#triggerGMSLimitAction(Boolean)` / `()` | 有参版强制 `false`；无参版用 Unsafe 清 `mGmsLimitEnabled` | 防御位                       |
| `GreezeManagerService#updateGmsNetStatus(Boolean)`           | 强制 `false`                                     | 防御位                       |
| `InternationalPolicyManager#isPushApp(String)`               | 调用栈上出现 `isRestrictNet` 时 → `false`             | **CN ROM 上从不执行**（见 2.3.1） |

一处**必须每次补做**的状态，`mGmsLimitEnabled`：它是**纯运行时状态**——构造函数里初始化为 `true`，唯一的写入方是 `dump` 命令，没有任何持久化路径 ⇒ **system_server 每次起来（开机、软重启、以及模块热重载后的重新装载）都会把它打回 `true`**。所以上表第 7 行的清理是"每次调用都清"，不能做成"装钩子时清一次"：一次性的清理会在下一次 system_server 启动后被静默撤销，而且屏上不会有任何提示。该性质由本机取证与 `hyperos-fcm-fix` 的 greeze 取证文档互为独立佐证；代码侧以 `ponytail:` 注释标注在 `triggerGMSLimitAction` 钩子前。

两处**极性**问题必须牢记，历史上都判错过：

1. `isRestrictNet` 的语义是 `!mMessageApp.contains(pkg)`，即 `mMessageApp` 是**豁免名单**而非限制名单。返回 `true` 意味着"限制这个 uid 的网络"，模块对 GMS 强制 `false` 才是正确方向。该字段是 `PUBLIC STATIC` 且在 `<clinit>` 中写入，因此反射读一次即可（但冷启动时可能尚未初始化，见 4.8）。
2. `udpPackageRestrict(uid, allow)` 只在 `allow==true` 时跳过。若连同 `allow=false` 一起跳过，会把已下发的过滤规则留在原地，导致 GMS 被**永久**过滤。冻结路径传 `true`、解冻路径传 `false`，方向不能搞反。

`isPushApp` 的调用栈判定用 `StackWalker`（`RETAIN_CLASS_REFERENCE`，要求 API ≥ 34）：命中 `isRestrictNet` 帧且类由 system_server 的 ClassLoader 加载时才改写返回值。这是为了区分"`isRestrictNet` 问我是不是推送应用"与"别处问同一个问题"。

该钩子在 CN ROM 上不执行，并不意味着网络限制这一侧存在缺口：同一目标由 `DomesticPolicyManager#isRestrictNet` 承担（上表第 5 行）。两处并存是代次覆盖，不是冗余——实现选择由 region 一次性决定（2.3.1），模块无法也不应在运行时改写它。

实验档 `greeze_net_release`（默认关）复用 `isRestrictNet` 这条钩子：uid 反查包名（`getPackagesForUid`，**无缓存**——`isRestrictNet` 唯一调用方是 `freezeUids`，冷路径不值得为它引入失效复杂度），任一包名在 FCM 唤醒白名单内即答 `false`。空名单 = 不作用于任何应用；读取失败按关处理。实验分支排在 GMS 分支**之后**，GMS uid 永远不走实验分支，两个方向各自计数、互不混入。日志形状：每个 uid 首次放行打一条 `greeze net release: kept checked app <pkg> (uid N) unrestricted`，量级进摘要行 `net-released=`。

### 3.6 C 面：清理与自启动（system_server）

| 钩点                                                                              | 动作                                              | 守门                                     |
| ------------------------------------------------------------------------------- | ----------------------------------------------- | -------------------------------------- |
| `ProcessCleanerBase#isForceStopEnable(ProcessRecord,int,ProcessManagerService)` | 声明了 FCM 组件且 `policy != 13` → `false`            | `Tier.STRICT` + `declaresFcmComponent` |
| `ProcessPolicy#getWhiteList(int)`                                               | `flags & 1 != 0` 时把 GMS 两个名字追加进返回值（副本 + 原地各写一次） | 无                                      |
| `ListAppsManager` 构造器 ×N                                                        | 构造完成后从 `mSystemBlackList` 移除 GMS                | 无                                      |
| `ListAppsManager#isInWhiteList(String)`                                         | 每次查询前把 GMS 加入 `mUseDataWhiteList`               | 无                                      |
| `AwareResourceControl` 构造器 ×N                                                   | 构造完成后从 `mNoNetworkBlackUids` 移除 GMS（按包名或 uid）   | 无                                      |
| `GlobalFeatureConfigureHelper#getDozeWhiteListApps(Bundle/Context)`             | 返回值不含 GMS 时追加                                   | 无                                      |

`declaresFcmComponent` 是模块唯一的**内容判定**，四处提问：

```
queryIntentServices(ACTION_MESSAGING_EVENT)
queryBroadcastReceivers(ACTION_REMOTE_INTENT)
getServiceInfo(pkg, com.google.firebase.messaging.FirebaseMessagingService)
getReceiverInfo(pkg, com.google.firebase.iid.FirebaseInstanceIdReceiver)
```

前两问靠 intent-filter，后两问**直接解析组件**——这正是后两问存在的理由：有些应用的 Firebase 类不带 intent-filter，只靠 action 查询看不见它们。结果按包名缓存 5 分钟（上限 256 条，满了整体清空）。

`mNoNetworkBlackUids` 的清理带一条诊断日志：既没按包名也没按 uid 命中时，打一条一次性 INFO（`noNetworkBlacklistMismatchLogged`），用来暴露"字段名对了但集合内容不是预期类型"这类代次漂移。

"勾选了为什么还是被冻"是预期行为，不是模块失效：免冻是 GMS 专属的。

### 3.7 D 面：PowerKeeper 域（独立进程）

#### 3.7.1 网络阻断与待机（D1）

| 钩点                                                                                    | 动作                                                                                                                                                                                             |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `NetdExecutor#initGmsChain(String,int,String)`                                        | 第 3 个参数改写为 `"ACCEPT"`（原为 `REJECT`）                                                                                                                                                             |
| `NetdExecutor#setGmsDnsBlockerState(int,boolean)`                                     | 强制 `false`（不拦截 DNS）                                                                                                                                                                            |
| `NetdExecutor#setGmsChainState(String,boolean)`                                       | 强制 `false`（不开墙阻断）                                                                                                                                                                              |
| `NetdExecutor#execute(int,String,String,Object[])`                                    | `setuiddnsrule` → 仅当第 1 参 uid 为 GMS 时把第 2 参改 `"allow"`（无法解析的 uid 一律放行不改写，防未来 ROM 新增其他 uid 的调用方被静默翻面）；`enablemiuistandby enable` → 直接返回跳过值（第 1 次及之后每 10 次打 `standby-firewall: suppressed` INFO） |
| `GmsObserver#updateGmsAlarm` / `#updateGmsNetWork` / `#updateGoogleReletivesWakelock` | 强制 `false`                                                                                                                                                                                     |
| `GmsObserver#updateGmsEnabled` / `#updateGmsState` / `#updateGmsInstalled`            | 强制 `false`                                                                                                                                                                                     |
| `GmsObserver#disableGms` / `#disableGmsApps`                                          | 整个方法跳过（返回 `null`）                                                                                                                                                                              |
| `GmsObserver#updateFrameworkGmsNetStatus(boolean)`                                    | `true` → `false`                                                                                                                                                                               |
| `GmsObserver#onGoogleReachabilityChanged(boolean)`                                    | 强制 `true`（永远"可达"）                                                                                                                                                                              |
| `GmsObserver#c(GmsObserver,boolean)`                                                  | 混淆桥接方法，第 2 参强制 `true`；装钩成功打 `GmsObserver#c (obfuscated connected-bridge) hooked` 确认行，缺席打 skip 行——跨代漂移（方法改名）不再静默                                                                                |
| `GmsObserver$i#googleNetworkDisconnect`（i ∈ 1..8）                                     | 跳过                                                                                                                                                                                             |

`GmsObserver$i` 的扫描是**序号遍历**而非按名字定位：内部类序号在不同代次会漂移，代码遍历 1..8 并对声明了 `googleNetworkDisconnect` 的那个安装钩子。这是对混淆的唯一可行对策——名字不可依赖，结构可以。

`enablemiuistandby enable` 的跳过带节流日志（首次命中 + 之后每 10 次，计数器 `standbyFirewallSkipCount`），可以证实"断网链被挡"及其量级；此前为完全静默，该观测盲区已关闭。

**字节码确证**：该命令在 PowerKeeper 侧**端到端无 per-uid 通道**——`AppStandbyController.setMiuiStandby(Z)`（唯一门控是 `mMiuiStandby` 状态缓存防重复下发）→ `NetdExecutor.enableFirewallStandbyChain()V`（**无参**静态方法）→ netd `dnsproxyd enablemiuistandby enable`（无 uid 参数）。

**运行时确证（ADB socket 探针）**：对 `/dev/socket/dnsproxyd` 的实测（NUL 分帧 FrameworkListener 协议）显示，`getaddrinfo`（无参）返回 `501 GetAddrInfoCmd::runCommand: invalid number of arguments`（监听者 = tethering apex `libnetd_resolv.so` 的 DnsProxyListener，身份经错误字符串匹配确认）；而 `enablemiuistandby`（无论带不带参数）返回 **`500 Command not recognized`**，`setuiddnsrule` 同样未注册。即 **PowerKeeper 经 dnsproxyd socket 下发的全部 MIUI 命令在本代 ROM 上是死信**——"全局待机断网链"从未真正生效过。钩子处置：**保留**（拦截一个死命令零成本；若未来 OTA 重新注册处理器，它自动恢复保护价值）。

**`OemNetdListener` 引擎与 GMS 的关系已查清（静态 + 运行时双通道）——无关**：

- **唯一 Java 调用方** = `com.miui.server.RestrictAppNetManager`。数据源三路：① `init()` 硬编码出厂名单 `sRestrictedAppListBeforeRelease`——**全部是跑分软件**（antutu ×8、鲁大师 ×2+cooling、安兔兔视频、gamebench、3DMark 等 17 项）；② `MiuiSettings$SettingsCloudData` 云控观察者；③ 装包广播（经 `isAllowAccessInternet(pkg)` 门控）。
- **全类区域 0 个 GMS 字符串引用**（`com.google.android.gms` / `gsf` 均无）。
- **运行时**：本次开机至今 logd 主缓冲零条 `setMiuiFirewallRule` / `addMiuiFirewallSharedUid` / `notifyFirewallBlocked`；tag `RestrictAppNetManager` 静默；`dumpsys netpolicy` 中 UID=10133 `policy=4 (ALLOW_METERED_BACKGROUND)`、`hasNetworkAccess=true`、在默认 restrict-background allowlist；`oemnetd` 独立守护进程不存在、`service list` 无对应 Binder 服务。
- **残余风险与监控点**：唯一理论通道是云控未来把 GMS 加入 restrict 名单——事后可见（无需 root）：`adb shell logcat -d -s RestrictAppNetManager`（`updateFirewallRule : N`）与 netd 侧 `setMiuiFirewallRule: packageName=%s, uid=%d` 日志行。不为此加钩子。

#### 3.7.2 免限名单与场景编译（P1–P3）

这组解决同一根因：GMS 在 PowerKeeper 的配置里被卡在 `miuiAuto`（scenario 0），因为策略 UI 对没有启动器图标的包隐藏了选择器，于是 `dealNoRestrictApp()` 永远不会把它纳入 `MILLET_NO_RESTRICT_APP`。三层各自修补一个环节：

| 层       | 钩点                                                                           | 动作                                                                |
| ------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| P1 源头   | `UserConfigureHelper#getNoRestrictApps(Context)`                             | 返回值追加 GMS                                                         |
| P1 兜底   | `UserConfigureHelper` 中所有名字含 `update/save/insert/modify/setBg` 且不含 `get` 的方法 | 调用后重新断言 userTable；`setBgControl` 且涉及 GMS 时把非保留列的值改写为 `noRestrict` |
| P1 终检   | `ActiveStateController#dealNoRestrictApp()`                                  | 执行后校验 `Settings.System.MILLET_NO_RESTRICT_APP`，缺失则追加并触发 P4 恢复     |
| P2 冻结侧  | 见 B 面 `AurogonImmobulusMode#isNoRestrictApp`                                 | 在 system_server 侧让 GMS 表现得像在免限集合里                                 |
| P3 编译结果 | `PowerKeeperAppConfigure#fillScenarioContent`（7 参 OS3 / 8 参 OS4）             | 执行后把 GMS 的 `scenario` 由 `0` 改写为 `8`                               |

P1 的"写回"部分（`ensureGmsUserTableBgControl`）直连 PowerKeeper 的 ContentProvider（`content://com.miui.powerkeeper.configure/userTable`），把 GMS 行的 `bgControl` 写成 `noRestrict`；查不到就 insert。由 `userTableReassertInFlight` 防止重入——写回本身会触发被钩住的 writer，形成环。

装在 PowerKeeper 进程内而不是用外部 watchdog 修 Settings，是为了消灭竞态：**每次投影重新生成都在源头带上 GMS**。

#### 3.7.3 待机限制（D3）

`AppStandbyController#setUidState(int, boolean)` 是 per-uid 待机限制的收敛点（方法自己就打印 `setUidState, uid = %d allow = %b`）。对 GMS 强制 `allow=true`。

三条约束写在源码注释里，改动前必读：

- **只改参数，不要预置 `mUidState` 为 true**：当传入值等于缓存值时方法会提前返回，true 的缓存会抑制恢复路径而不是触发它。
- 下游 helper 方法名被混淆且代次不同（OS3 `s:(IZ)V`，OS4 `r:(IZ)V`），**不得硬编码字母**，只钩 `setUidState` 本身。
- 已知残余缺口：GMS 被**带外**限制（不经过 `setUidState`）且 `mUidState` 仍为 true 时，即使传入 `allow=true` 也会短路，没有任何东西解除限制。P4 恢复不覆盖这种情况（见 6.3）。

**同名方法的排查（素材取自本机 ROM 的 PowerKeeper `classes.dex` dexdump，下称 `<PowerKeeper-dis>`，与从设备直接 pull 的同名文件类名/签名逐项一致，两份均含 OS4 专属 `PhoneSleepModeController`）**：PowerKeeper 里叫 `setUidState` 的方法共 **6 个**：

| 类                          | 签名                            | 下游动作                                                           | 是否待机网络      |
| -------------------------- | ----------------------------- | -------------------------------------------------------------- | ----------- |
| `ActiveStateController`    | `(IIZ)V`                      | 向 `AppActiveConfigure.CONTENT_URI` insert（uid/property/active） | 配置库         |
| `AppClusterController`     | `(ILClusterUtils$Cluster;Z)V` | `ClusterUtils.addAppToCluster` / `delAppFromCluster`           | 分组成员        |
| `DeviceIdleController`     | `(IZ)V`                       | `mTempWhitelistAppIds` / `mTempNonWhitelistAppIds`             | doze 临时白名单  |
| `KillProcessController`    | `(IZ)V`                       | `ProcessManager.killApplicationAlways`                         | 进程          |
| `SensorController`         | `(IZ)V`                       | `setAppSensorsControlPolicy`                                   | 传感器         |
| **`AppStandbyController`** | `(IZ)V`                       | 见下                                                             | **是（本钩目标）** |

即 `(IZ)V` 这一个签名在 **4 个类**里各有一份——按名字 grep 会命中错的类，必须按 `类名#签名` 定位。

真正的网络收敛链在全 dex 内唯一：

```
AppStandbyController#setUidState
  → DeviceIdlePolicyHelper.r(uid, !allow)
  → q(pkg, !allow, userId)
  → IUsageStatsManager.setAppInactive(pkg, !allow, userId)
```

后三级各只有 **1 个调用点**（`DeviceIdlePolicyHelper.r:(IZ)V` 与 `IUsageStatsManager.setAppInactive` 全 dex 唯一，且都在 `DeviceIdlePolicyHelper` 内；`.r` 的唯一调用点就在 `setUidState` 偏移 `0045`）。⇒ PowerKeeper 自身能改 GMS"待机/未激活"网络状态的路径 **100% 收敛在这一个钩子上**；其余 5 个同名方法改的是 doze 白名单 / 进程 / 传感器 / 配置库，不是待机网络状态。

因此"带外"的真实面只能来自 PowerKeeper 之外：system_server 的 `UsageStatsService#setAppInactive` 被别的调用方触发，或 netd 侧规则。免 root 可读的四项判据：

```sh
am get-standby-bucket com.google.android.gms     # 本机 5 = ACTIVE
adb shell dumpsys netpolicy                     # UID=10133：policy=4 ALLOW_METERED_BACKGROUND
adb shell dumpsys greezer                       # GMS：frozen=0s
adb shell dumpsys deviceidle whitelist          # user / system / system-excidle 三段
```

四项全绿 ⇒ 未发生。

### 3.8 睡眠模式断网链

#### 3.8.1 现役实现（OS4 / V816）

OS4/V816 的睡眠断网**不是**按 uid 掐网：`PhoneSleepModeController#applySleepConfig` 直接调用 `WifiManager#setWifiEnabled(false)`（偏移 `29e404`）与 `CommonAdapter#setDataEnabled(TM,false)`（偏移 `29e36a`），WiFi 与蜂窝一起关。实测一次完整 cutoff 的关网段共 5 h 30 min，期间 GCM `net=-1`，即蜂窝也不可达。

- `sleep_mode_network_white_apps` 全 ROM 仅 3 处引用（云控读、云控写、清应用观察者），**关网路径零读取点** ⇒ 按 uid 的机制残骸。
- `setRadioPower` / `setAirplaneMode` 零命中 ⇒ 蜂窝射频与信令全程在线，电话/短信/小区广播不受影响——是"不走 IP"，而非"IP 被豁免"。

#### 3.8.2 门控矩阵与两个对等的实验开关

现役钩点与**两个对等**的实验开关（全在 `Hooker.kt`，每次 cutoff 调用惰性读远端值）。2026-10-05 起两个开关不再分主副：各自对应 `applySleepConfig` 切断的一条无线电，hook 侧各读自己的键，界面两行常显、没有 `AnimatedVisibility`。

| 钩点                                                                        | 闸门                                         | 放行 / 拦截时的日志                                                                                                      |
| ------------------------------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `WifiManager#setWifiEnabled(Z)`，仅 `enable=false` 且在 `applySleepConfig` 栈内 | `sleep_keepalive`                          | `sleep-mode: kept WiFi on (sleep would have turned it off)` / `sleep-mode: WiFi left to the ROM policy (…)`      |
| `CommonAdapter#setDataEnabled(TM,Z)`，同上                                   | `sleep_keepalive_data` | `sleep-mode: kept mobile data on (keepalive data switch is on)` / `sleep-mode: mobile data left to the ROM policy (…)` |

**两个开关互不依赖**（2026-10-05 改；改之前是主 + 副，现在是「一条无线电一个开关」）：

- 服务端：`isSleepKeepaliveDataEnabled()` **只读自己的键**（`Hooker.kt:2251`），不再 AND 主开关。⇒「只保移动数据、让 WiFi 照 ROM 的关」是可达配置，两行各自独立。
- 界面：两行**常显**、各自独立，没有 `AnimatedVisibility`；同标题、同连通组，`first` / `last` 只决定两端圆角。
- 降级路径（3.8.3）是唯一无法只保一条无线电的场合：它整段跳过关网调用，因此布防条件改为「任一开关开」，安装期日志会明说走的是这条。
- 离线镜像：`MainActivity#reloadAllowlist()` 对两个键都做 pending 修复，绑定后把界面值推上行，而不是被旧的远端值覆盖。

两个开关均默认关闭，读取失败一律落"关"（fail-closed）：这是一个覆盖用户主动省电决策的实验选项，读不出来时必须让系统自己的策略生效。

#### 3.8.3 降级路径

`applySleepConfig` 的实际极性门控是 `Settings.Secure.getIntForUser("key_open_earthquake_warning")`：该值 1 时整段关网（连同 `SleepState` 记账）被跳过。模块的降级路径（`hookSleepModeEarthquakeFlag`）只在两个 cutoff 调用**有一个钩不住**时启用——否则关掉一个电台而 `SleepState` 未记账，会让 `restoreSleepConfig` 没有可恢复的对象。正常路径下模块让 ROM 走完整流程，只在两个关网调用处拦。

降级路径**既不单独拦 WiFi 也不单独拦数据**：它整段跳过关网调用，没有 per-电台的决定可窄化，因此对两个开关的行为是一致的，不存在"降级下某一个开关失效"的不对称。安装期日志会明说走的是这条（`cutoff hooks incomplete, degraded`）。

#### 3.8.4 "其余动作保持原生"指的是什么

`applySleepConfig` 的关网段不止那两个开关调用，同一段里还跑着按位处理，且每一"位"都是一次真实的系统行为改写：

| 偏移                  | 位                         | 动作                                                                                                                                           | 入睡           | 出睡恢复                                        |
| ------------------- | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------ | ------------------------------------------- |
| `29e33e` / `29e36a` | 1 = data                  | `SleepState.setPreviousEnable(1,…)` → `CommonAdapter.setDataEnabled(TM,false)`                                                               | 关移动数据        | `restoreSleepConfig` 按记录开回                  |
| `29e3d8` / `29e404` | 2 = WiFi                  | `setPreviousEnable(2,…)` → `WifiManager.setWifiEnabled(false)`                                                                               | 关 WiFi       | 同上                                          |
| `29e42e`~`29e4a0`   | 16 = keyguardNotification | 读 `Settings.System wakeup_for_keyguard_notification`（默认 -1）→ 存 `SleepState.previousNotification` → `setRestore(16, 原值>0)` → `putInt(..., 0)` | 关掉"锁屏通知点亮屏幕" | `29feb2` `putInt` 写回 `previousNotification` |
| `29e4a6`~           | 32 = FOD                  | `ro.hardware.fp.fod` 为真时 `isFodAodShowEnable()` → `setPreviousEnable(32,…)` → `setFodAodShowEnable(false)`                                   | 关屏下指纹 AOD 常显 | 按记录开回                                       |
| `29e4f4`~`29e52c`   | 128 = pickup              | `isPickupWakeupEnable()` → `setPreviousEnable(128,…)` → `setPickupWakeupEnable(false)`                                                       | 关抬手亮屏        | 按记录开回                                       |

模块只拦前两行；**16/32/128 三行全部按 ROM 本意执行**。其中 16 位最容易被忽略也最实际：睡眠期间锁屏通知**不再点亮屏幕**。旧的 flag 捷径会连它一起跳过 ⇒ 夜里每条推送都把屏幕点亮一次。反过来，若刻意阻止它执行（为了"通知照常亮屏"），失去的正是 ROM 这一项省电与免打扰。

#### 3.8.5 旧 per-uid 链：防御位与哨兵

OS3 及更早的代次里，睡眠断网由 system_server 的 `MiuiNetworkPolicyManagerService` 承担：入睡后只放行 `mSleepModeWhitelistUids` 中的 uid，其余整夜掐网；GMS 默认不在集合里。进入睡眠的广播顺序是 `setSleepModeWhitelistUidRules()` → `enableSleepModeChain(true)`，因此只需在下发之前把 GMS uid 塞进集合；退出时 `clearSleepModeWhitelistUidRules()` 会对称撤销，不会残留规则。

在 V816 上这两条路径**整夜零触发**（对应的 `Sleep mode entering` 行从未出现），钩子保留为 **OTA 防御位**。判定与观测：

- **两条臂独立挂载**：`armSleepModeWhitelist` 与 `armSleepModeChain` 各自解析目标、各自 `deoptimize`，互不短路。安装期一行总结：
  
  `Sleep-mode legacy per-uid chain armed: whitelist=<bool>, chain=<bool> (sentinel: …)`

- **触发哨兵**：两条臂各自在**首次真正被 ROM 调用**时打一行（一次性，由 `sSleepWhitelistPathFired` / `sSleepChainPathFired` 控制）：
  
  ```
  sleep-mode sentinel: legacy path FIRED — #setSleepModeWhitelistUidRules ran on this ROM, …
  sleep-mode sentinel: legacy path FIRED — #enableSleepModeChain ran on this ROM; …
  ```
  
  本代 ROM 上这两行都不应出现；一旦出现即说明机型已离开"睡眠断网在 PowerKeeper、不按 uid 过滤"的结论，后续判断必须以该行日志为起点重建。

- 四个静默出口（字段类型不是可变集合、uid 解析失败、GMS 已在集合内、链开启本身）都已补日志——此前"回调压根没跑"和"跑了但集合是空的"在日志里完全一样。

判定表（供 OS3 或 OTA 恢复旧链时使用；一个晚上的阴性不足以判定该链永久缺席）：

| 观测到的日志组合                                          | 判定                                                           |
| ------------------------------------------------- | ------------------------------------------------------------ |
| 无任何 `Sleep mode entering` 行                       | 睡眠链整夜未进入（广播未发，或时间窗/静止判定未满足）                                  |
| `size 0` 且无 `kept GMS` 行                          | 进入过但 `setSleepModeWhitelistUidRules()` 未在开链前调用 ⇒ 注入未生效，查调用顺序 |
| `size ≥1` 且有 `kept GMS (uid …)`                   | 注入成功，uid 级放行已下发                                              |
| `already whitelisted, size N`                     | 集合非空 ⇒ ROM 侧确实填过（推翻"恒为空"的静态结论）或上次残留未清                        |
| `not a mutable collection` / `GMS uid unresolved` | 字段类型或 uid 解析异常 ⇒ 注入失效，需改实现                                   |

出现与静态结论冲突时，**以运行时为准**：静态取证只证明常规路径，排除不了云控等特殊路径的写入。

#### 3.8.6 跨代核对

本机是 OS4，而模块对 OS3 保留了一批目标，因此逐个核过 OS3（素材：`<PowerKeeper-OS3-dis>` = OS3 PowerKeeper 带指令体 dexdump、`<OS3 miui-services.jar>` = OS3 system_server）：

| 目标                                                                                                                | OS3 偏移                             | OS4/V816 偏移 | 结论                                      |
| ----------------------------------------------------------------------------------------------------------------- | ---------------------------------- | ----------- | --------------------------------------- |
| `PhoneSleepModeController#applySleepConfig`                                                                       | `1a1eac`                           | `29e120`    | 同名同签名；栈帧闸门 `calledFromSleepApply` 两代都命中 |
| `CommonAdapter#setDataEnabled(TM,Z)`（在 `applySleepConfig` 体内）                                                     | `1a2106`                           | `29e36a`    | 静态方法、签名一致                               |
| `WifiManager#setWifiEnabled(Z)`（同上）                                                                               | `1a218c`                           | `29e404`    | framework 目标，一致                         |
| `PhoneSleepModeController#restoreSleepConfig`                                                                     | `1a3890`                           | 有           | 降级路径的 `calledFromSleepConfig` 依赖它       |
| `Settings.Secure` 读 `key_open_earthquake_warning`                                                                 | `1a1f82`                           | `29e31e`    | 两代都靠它跳过整段关网，降级路径同形                      |
| `MiuiNetworkPolicyManagerService#{setSleepModeWhitelistUidRules, enableSleepModeChain}`、`mSleepModeWhitelistUids` | 均在                                 | 均在          | 两代都"存在"；差别是本代不调用——这正是哨兵要观测的             |
| `ProcessCleanerBase#isForceStopEnable(ProcessRecord,int,ProcessManagerService)`                                   | 同签名（另有两个 `(ProcessRecord,int)` 重载） | 同签名         | 强停路径两代同形，严格模式口径对 OS3 同样成立               |
| `ProcessSceneCleaner` / `killOnce` / `handleSwipeKill`                                                            | 均在                                 | 均在          | 上滑清理链两代同形                               |
| `CommonAdapter#addPowerSaveWhitelistApps`                                                                         | 有                                  | 有           | 微信免冻剔除两代可挂                              |

**一处仍未定的跨代假设（观察项）**：出睡 nudge 的闸门 `sGmsKeptOnSleepWhitelist` 只证明"uid 规则已下发"，由此推出"链路整夜通畅"是**代次假设**——在 V816 成立（该路径根本不跑），但一个"既按 uid 白名单、又在 PowerKeeper 关电台"的 ROM 会让标志为真而链路已断。该分支在本机不可达、**无法运行时取证**，故不改行为，只在跳过时的日志里写明假设。若某天真出现 `sleep-mode sentinel: legacy path FIRED`，先看当晚有没有 `sleep-mode: kept WiFi on` 一类行，再决定是否把 nudge 闸门改成"按实际是否断网"判定。

### 3.9 实验开关：放宽 WiFi 弱信号切换

**真义**：该开关的作用是**让用户留在一条确实很差的 WiFi 上**，而不是让 WiFi 变好。

**背景链路**：WiFi↔蜂窝抖动的执行者是 system_server 的 `AmlWifiScoreReportInjector`，它把 `rssiScore < 50`（≈ RSSI −78…−82 dBm）判给 `AmlMiuiThirdPartScorer`，后者输出 `isUsable=false`，进而触发 `ConnectivityService: +EXITING` 与 `Setting inactive [107 WIFI] for 30000ms`。切回条件是 `averageRssi = -72`，滞回带约 6 dBm。

**关键结构事实**：ROM 至少两条独立的切换理由，且**共用同一个出口**：

| 理由码                                  | 触发条件（实测日志）                                                                                                               |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| `SWITCH_TO_CELLULAR_BY_LOW_RSSI`     | `writeNetworkRatingData` 里 `rssiScore < 50`                                                                              |
| `SWITCH_TO_CELLULAR_BY_POOR_QUALITY` | `linkLayerFailpercent >= 85` → `linkLayerFadingScore = 11`，伴 `weightedAverageTxFailPercent = 96 successfulLinkSpeed = 3` |

两条理由都会把 `mLegacyIntScore` 压到 50 以下，而 `AmlMiuiThirdPartScorer` 只认分数。⇒ **在出口抬分数即可让两条理由一起失效**；逐条拦理由码会漏（见 5.5）。

**实现**：

| 项        | 说明                                                                  |
| -------- | ------------------------------------------------------------------- |
| 钩点       | `AmlMiuiThirdPartScorer#notifyScoreAndIsUsable`                     |
| 改写字段     | `mLegacyIntScore`                                                   |
| 主开关      | `Prefs.KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED`（默认关）                    |
| 子选项      | `Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR`，可选 `45 / 40 / 35 / 30`，默认 **45** |
| ROM 判据常量 | `WIFI_SCORE_USABLE_MIN = 50`（**ROM 自己的值，不是本模块的可调旋钮**）               |
| 写入目标     | `WIFI_SCORE_CLAMP_TARGET = WIFI_SCORE_USABLE_MIN + 1` = **51**      |
| 日志节流     | `WIFI_SCORE_CLAMP_LOG_INTERVAL_MS = 30_000`（两个分支各自独立计数）             |

| ROM 原始分       | 行为                                                                      |
| ------------- | ----------------------------------------------------------------------- |
| ≥ 50          | 不介入——本来就是 ROM 的可用判决，改写它等于替 ROM 造一个它没有做出的决定                              |
| `[floor, 50)` | 钳到 `WIFI_SCORE_CLAMP_TARGET`（51），`chain.proceed()` 返回后在 `finally` 里还原原值 |
| `< floor`     | 原样放行，`noteWifiScoreSkip` 记一行                                            |

读取失败落在默认 45，也就是**最窄**那一档：一个读不出来的值和一个已被退役选项表移除的值在这里长得很像，只有前者是重读一次就能恢复的，所以两者都按默认处理，且不可能往"更深"的方向猜。

钳位目标取 51 而非恰好 50，是为了让 `< 50` 与 `<= 50` 两种比较都成立——若某代 ROM 改成 `<= 50`，钳到 50 会当场失效且不报错（日志照打 `reported as usable instead`，但 ROM 已判 unusable）。

**两个分支都留正记录**（各带 30 s 节流且**各自独立计数**；共用一个窗口会让更吵的那一路把另一路饿死）：

```
wifi-weak-signal: reported 49 met the chosen floor 45 but would have failed the ROM's 50; reported as usable instead
wifi-weak-signal: reported 38 is below the chosen floor 45; left to the ROM's own policy
```

第二条的存在是刻意的，否则会退化成"日志没出现 ⇒ 钩子没生效"这类误读。**它的上界同样重要**：分数本就 ≥50 时什么都不写，否则一条安静的链路也会每 30 秒产出一条伪装成"干预"的日志。

**类加载**：`AmlMiuiThirdPartScorer` 由 `/system_ext/framework/miui-wifi-service.jar` 提供，在 system_server 里可见但**不在它的 classpath 上**，`classLoader.loadClass` 必定 `ClassNotFoundException` → 会误报 absent。取法：借已注册 binder 的类加载器，`ServiceManager.getService(...)` 候选服务名为 `MiuiWifiService` / `AmlConnectivityService` / `MiuiNetPathOptimizerService`；**必须加退避重试**（开机早期 wifi 服务尚未发布，实测 3 s × 40 次足够）。absent 时要分清是"这台 ROM 没有"还是"服务还没起来"——日志措辞必须区分，否则会把时序问题误记成代次差异。

### 3.10 恢复动作（P4，非 hook）

`recoverGmsConnection(Context)` 是**出境 IPC**，不是钩子：向 GMS 与 GSF 各发三条广播（`GCM_RECONNECT` / `GTALK_HEARTBEAT` / `MCS_HEARTBEAT`），再查询一次 Chimera provider。三条一起发是因为 `GCM_RECONNECT` 在部分版本上覆盖不到 MCS/GTalk 的重连路径。

当前有两个触发点，均为条件性：

1. 睡眠模式退出——仅当 `sGmsKeptOnSleepWhitelist` 为 false（白名单注入未生效）时才 nudge，触发前后各采样一次流量使效果可证伪；注入成功则跳过并留日志，避免拆掉整夜健康的 MCS。
2. `MILLET_NO_RESTRICT_APP` 修复——仅在实际发生追加修复时。

**为什么不扩触发面**：三类诱因都落在 GMS 自身的重连能力内，而 P4 的广播是**破坏性**的（会让 GMS 主动拆掉当前 MCS）。本机 `dumpsys activity service com.google.android.gms/.gcm.GcmService` 实测：`connected=mtalk.google.com:5228`（TCP 5228）、`connects=16`、`failedLogins=0`、`Seen good heartbeat in last connection? true`，各网络类型的 `FastSlowHeartbeatAlgorithm` 全为 `bad_heartbeat_count: 0`，`interval_range=[110s,1730s]`、`heartbeat_interval=230s`。

| 诱因        | 自愈机制                                                        | 结论     |
| --------- | ----------------------------------------------------------- | ------ |
| 网络切换      | GMS 注册 ConnectivityManager 回调，网络变化自行重建 MCS                  | 不需要 P4 |
| GMS 被杀重建  | 进程重启即重连；模块 `isForceStopEnable` 另挡住强停                        | 不需要 P4 |
| NAT/FW 老化 | MCS 走 **TCP**，NAT/FW 状态超时（≥1 h）远大于心跳上限 1730 s ⇒ 心跳本身就是为它设计的 | 不需要 P4 |

⇒ 触发面窄是**设计选择而非缺口**：唯一"连接已死且 GMS 未必自愈"的时刻，就是 ROM 整夜物理掐网后退出（重连退避可能已耗尽）。重启条件不变：整宿观测出现"MCS 死亡 + GMS 未自愈 + 未进睡眠模式"的证据后再设计带门控的触发。

### 3.11 失效处理：分层隔离、fail-open 与安全零值

**分层隔离**：挂载点组收在两张清单里——`systemServerGroups`（system_server，23 组）与 `powerKeeperGroups`（PowerKeeper，7 组），**一组一行**；由 `installGroup` 逐组执行、每组一个独立 `try/catch(Throwable)`，外层再包一层，确保**装载失败不会导致 system_server 崩溃**——这是 Xposed 模块最基本的安全边界。新增一组 = 往清单加一行，不再复制一段 try/catch。

```
hookSystemServer
  └─ for (group in systemServerGroups(classLoader)) installGroup(group)
       ├─ Group("hook",    "allowlist receiver")       失败 → ERROR "Failed to hook allowlist receiver"
       ├─ Group("hook",    "GreezeManagerService")     失败 → ERROR "Failed to hook GreezeManagerService"
       ├─ Group("install", "wake-path probe")          失败 → ERROR "Failed to install wake-path probe"
       ├─ Group("start",   "GMS traffic probe")        失败 → ERROR "Failed to start GMS traffic probe"
       ├─ …（逐组独立 try/catch(Throwable)，一组炸了不影响后面的组）
       └─ logSummary("system_server")   ← 在 onSystemServerStarting 末尾，全部组跑完才打一次
```

每组带一个**动词**（`hook` / `install` / `probe` / `start`），因为它就是失败文案 `Failed to <verb> <name>` 的组成部分，而不是装饰：这些字符串是装机验证与整夜日志 grep 的锚点，把动词统一成 `hook` 等于改掉日志契约。清单化之后保持不变的三件事：`hookE` 的 `setId()` 去重与 `hooksInstalled` / `hookTargetsAbsent` 计数（摘要行的 `N hook(s) installed, M target(s) absent` 由它而来），以及 `logSkip`（INFO，本代次该有的目标缺失）与 `logSkipOtherGeneration`（DEBUG，别的代次才有的目标缺失）两级 absent 之分——合成一条"skip"就再也分不出"这一代从来没有"与"这次 OTA 丢了"。

**两级 absent 的成行粒度不同，这是刻意的**：同代缺失（INFO）逐条立即打；跨代缺失（DEBUG）**先入缓冲，由安装面出口并成一行** `cross-generation target(s) absent (K), skip: <符号…>`——符号一个不丢（将来某代开始带这个符号，它会从这行里消失，diff 一行即可看出），行数从 10 降到 1。同理，只读存在性探针的**正常读数**也并成一行 `probe: <键=值…>`；**异常读数（某层不再解析、某半边消失）仍各自单独成行**，因为那才是要看见的东西。

⚠ **缓冲的 flush 必须挂在安装面出口 `hookSystemServer` / `hookPackage` 的 `finally` 里，不能挂 `logSummary`**：热重载由 `onHotReloaded` **直接**调用这两个函数，从不经过 `onSystemServerStarting` / `onPackageReady` ⇒ 热重载路径上 `logSummary` 根本不执行（实证：热重载日志里**没有**摘要行）。挂在 `logSummary` 会让这些行在每次热重载被静默吞掉——恰好是这两条合并行本要消除的失效形态。另外 wifi 弱信号重试跑在自己的线程里、不被所在组 join，其结论可能晚于 flush 到达；那种迟到项由 `installPassCollecting` 判定，单独成行而不是留在缓冲里等下一次（下一次是热重载或重启之后）。

**回调内标准形态**：

```kotlin
try {
    // 判定 + 改写
} catch (t: Throwable) {
    log(Log.ERROR, TAG, "…failed", t)
}
chain.proceed()      // 或返回已计算的结果
```

三条纪律：① 异常**不得**穿透回调；② `chain.proceed()` 必须被调用（或用改写后的参数调用），不允许因为判定失败而吞掉原调用；③ 构造后清理类钩子用 `try { proceed() } finally { 清理 }`，保证即使原构造抛异常，清理仍然执行。

**fail-open 与 fail-closed**：

| 位置                                     | 方向                                      | 理由                                    |
| -------------------------------------- | --------------------------------------- | ------------------------------------- |
| 白名单为空                                  | **fail-open**（全放行）                      | 契约承诺"一个都不勾选 = 全部放行"                   |
| 远程 prefs 读取异常                          | 保留旧值，不清空                                | 宁可偏松不可丢推送                             |
| `gmsUid()` 解析失败                        | 跳过本次注入并 WARN                            | 注入集合需要 uid，降级为空操作好过注入错误 uid           |
| `getSystemContext()` 失败                | 回退 `getPowerKeeperContext()`，都没有则放弃本次动作 | 两个进程的 Context 不同源，都要缓存                |
| 睡眠保活 / 微信 keepout / WiFi 弱信号 / 唤醒四个实验开关读取失败 | **fail-closed**（按关处理）                   | 这四项覆盖用户主动做出的省电、网络或投递决策，读不出来时必须让系统自己的策略生效 |

偏松的代价是额外耗电，偏紧的代价是丢推送；**保护类判定无法确定时一律偏松，实验类开关读取失败一律按关**。

**返回值安全零值**：`skipValueFor` 按返回类型生成 `boolean→false`、`int→0`、`long→0L`，其它基本类型类推；`void` 与引用类型→`null`。注意 `PolicyMaker#isAllowFreeze` 返回 `int`（`CANNOT_FREEZE` 常量）、`triggerQuickFreeze` 返回类型不确定——两者都用 `skipValueFor(method.returnType)` 而不是硬编码，这样代次漂移改变返回类型时不会返回错误的零值。

### 3.12 关键数据流

**配置数据流**

```
[app 进程]  UI 勾选
   → Prefs.writeAllowlist / writeStrictMode
   → 本地镜像（同步）+ 远程 prefs（单线程 executor，commit）
   → 广播 ×3（0 / 400ms / 1500ms）
        ↓
[system_server] BroadcastReceiver（HandlerThread "fcmlive-allowlist"）
   → requestAllowlistReload（距上次读取 < 500ms 则合流延后）
   → loadAllowlistFromRemotePrefs → sAllowlist / sStrictMode / sAllowlistReadMs
        ↓
   钩点查询：moduleAppliesTo(pkg, Tier.WAKE | Tier.STRICT)
```

反向：写入失败 → 保留 pending 标记 → 下次绑定时把镜像推上去（防止被旧远程值覆盖）。

**推送投递数据流**

```
GMS 发出 c2dm 广播
  → AMS#broadcastIntentWithFeature
      ├─ caller 识别（getRecordForApp* → ProcessRecord.info.packageName，失败降级 binder uid）
      ├─ caller=GMS + moduleAppliesTo(target, Tier.WAKE) → 补 FLAG_INCLUDE_STOPPED_PACKAGES
      │                                   → addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000)
      ├─ [实验] 唤醒 开 + moduleAppliesTo(target, Tier.WAKE) → 同一出口补同一标记（不限 caller/action）
      │            └─ 副开关「复位停止状态」开 → setPackageStoppedState(pkg, false, userId)（同包 60 s 节流）
  → BroadcastQueueModernStubImpl#checkApplicationAutoStart   （冷启动路径）
  → GreezeManagerService#isRestrictReceiver                  （温而冻路径，附带 thawUidAsync）
  → GreezeManagerService#isAllowBroadcast                    （Tier.STRICT）
  → GreezeManagerService#isNeedCachedBroadcast               （冻结缓存路径）
  → DomesticPolicyManager#deferBroadcast                     （只剩 4 个 CN 重连动作）
  → 目标应用 receiver
```

每层拦截点都会**读** `sAllowlist`（经 `getFcmAllowlist()`，带 10 s 陈旧度检查与异步重载触发）。

**冻结 / 解冻与网络策略数据流**

```
freezeUids(uid)
  ├─ AurogonImmobulusMode#isNoRestrictApp(pkg)        → GMS: true  ⇒ 跳过
  ├─ AurogonImmobulusMode#isNoRestrictFreezeable      → GMS: false ⇒ 跳过（OS4）
  ├─ AurogonImmobulusMode#triggerQuickFreeze(uid,…)   → GMS: 跳过
  ├─ PolicyMaker#isAllowFreeze(uid)                   → GMS: 跳过
  ├─ DomesticPolicyManager#isRestrictNet(uid)         → GMS: false ⇒ 不置 0x0C00、不销毁 socket
  │                                                     （已勾选应用: false，实验开关 greeze_net_release，默认关）
  └─ GreezeManagerService#udpPackageRestrict(uid,true)→ GMS: 跳过 ⇒ 不下发 UDP 过滤
                                                          （allow=false 方向必须放行）
```

**睡眠模式数据流（OS4/V816 现役路径，PowerKeeper 进程）**

```
PhoneSleepModeController#applySleepConfig
  ├─ 读 Settings.Secure key_open_earthquake_warning   ← 降级路径钩点（仅在 cutoff 钩不全时启用）
  ├─ 位 1：SleepState.setPreviousEnable(1,…) → CommonAdapter#setDataEnabled(TM,false)  ← 钩点
  ├─ 位 2：setPreviousEnable(2,…) → WifiManager#setWifiEnabled(false)                 ← 钩点
  └─ 位 16/32/128：锁屏通知亮屏 / FOD AOD / 抬手亮屏 → 按 ROM 本意执行，模块不介入
退出：restoreSleepConfig → 按 SleepState 记录恢复
```

**睡眠模式数据流（旧 per-uid 路径，system_server，V816 上为防御位）**

```
PhoneSleepModeController#broadcastSleepState(state=1)
  → 广播 com.miui.powerkeeper_sleep_changed
      → MiuiNetworkPolicyManagerService$45.onReceive
          → setSleepModeWhitelistUidRules()   ← 钩点：注入 GMS uid
          → enableSleepModeChain(true)        ← 钩点：记录 size
退出（state≠1 / ACTION_SCREEN_ON）
      → clearSleepModeWhitelistUidRules()     （ROM 自行对称撤销）
      → enableSleepModeChain(false)           ← 钩点：白名单标志判定 → 跳过或（采样 → nudge → 15s 后再采样）
```

`ContentObserver`（$46）另有一条路径：开关变假时也会 clear + `enableSleepModeChain(false)`。

**诊断数据回流**

```
钩子内判定 → log(INFO/WARN/ERROR)
          → LSPosed 日志守护 → modules_*.log
                                    ↓
                    人工/脚本分析：摘要行、一次性证据行、计数心跳、判定表
                                    ↓
              决策：探针退役 / 晋升为行为钩子 / 保持观察
```

**晋升判据**（写死在方法论里）：只有当运行时证据表明某门确实对 GMS 判"否"时，才允许把只读探针改写为行为钩子。反之，**退役判据**绝不能是 `invoke-*` 计数，只能是长窗口持续零流量叠加结构可达性复核。

---

## 4. 诊断方法与观测指标

### 4.1 三条设计原则

1. **只读优先**。能观察就先观察，只有在运行时证据表明确实需要改写时才落地行为钩子。现有 7 组探针全部只读，不修改任何返回值。
2. **可证伪**。每条"成功路径"日志都必须有一个"到达但未命中"的对应日志。只有命中日志的探针，静默时无法区分"从未被拒绝"与"从未被调用"。
3. **探针不得抛出**。所有只读辅助方法内部全包 `try/catch`，返回可读的占位字符串（如 `<unreadable>`、`<not a collection>`）。

### 4.2 五类诊断手段

| 类别            | 代表                                                                          | 回答的问题                 | 观测方式                   | 判据                                                                                        |
| ------------- | --------------------------------------------------------------------------- | --------------------- | ---------------------- | ----------------------------------------------------------------------------------------- |
| **存在性探针**     | `probeReflectiveMethod`、`reportWhetstoneClasses`、`probePacketFilterSupport` | 这个隐藏符号在这台 ROM 上存不存在   | 装机期一次性 INFO            | 出现即存在；缺席对应 `logSkip`/`logSkipOtherGeneration` 行                                           |
| **只读字段快照**    | `mMessageApp` 探针、`sleepModeWhitelistSize`、`NoNetworkBlackUids` mismatch     | 这个集合现在是什么内容、GMS 在不在里面 | 装机期 + 事件触发             | 只用 `containsGms`，不把 size 当判据（见 4.8）                                                       |
| **计数 + 心跳节流** | `checkWakePath` 探针、`checkBroadcastWakePath` 探针、`doDesSocketForUid` 三层探针     | 这个门被进入多少次、拒绝了多少次      | 每 30 min 心跳行 + 明细前 N 次 | `reached` 显著大于 0 且 `denied` 可归因；`reached=0, denied=0` 与 `reached=9951, denied=0` 是完全不同的结论 |
| **一次性证据日志**   | 8 个 `@Volatile Boolean` 标志位                                                 | 这条路径到底有没有真实发生过一次      | INFO，每条路径只打一次          | 出现即该路径至少跑过一次                                                                              |
| **流量采样**      | `TrafficStats` per-uid 增量                                                   | 结果层面：GMS 现在还在不在交换数据   | 每 30 min + nudge 前后    | 增量非零；nudge 前后各一条采样                                                                        |

### 4.3 存在性探针：为什么必须运行时问

有一类符号**静态取证永远查不到**，因为它们是反射跳板：

```
MiuiNetworkPolicyManagerService#updateSleepModeWhitelistUidRules
  → Class.forName("android.net.ConnectivityManager")
      .getDeclaredMethod("updateSleepModeUidRule", int, boolean)
```

目标方法在 `framework.jar`，而静态取证扫的是 `services.jar` 的 dex。反射调用在 dex 层面只是一个字符串常量，`invoke-*` 计数看不到它。同理 `WhetstoneActivityManager` 位于 `/system_ext/framework/miui-framework.jar`（不是 `/system/framework`），客户端一半根本不在被 grep 的语料里。

`probeSocketTeardown` 因此同时监控三层：

```
WhetstoneActivityManager (client, static) ──AIDL "whetstone.activity"──▶
   WhetstoneActivityManagerService (server) ──▶ MiuiNetworkManagementService#doDesSocketForUid (impl)
```

跨两次传输，单层探针可能整个漏掉。**binder 传输对 `invoke-*` 计数不可见**，这是"静态零调用者 ≠ 死代码"的核心理由。当前读数：结构可达、观测窗内零调用 ⇒ 判定为"结构可达但从未触发"，**不是**死代码；只有更长窗口的持续零流量才允许降级。

### 4.4 计数与心跳的节流策略

| 探针                               | 节流方式                                                                                                                                                                       | 理由                                                                                 |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `checkWakePath`（Gate-W）          | 拒绝：前 10 次逐条 + 按调用方聚合（30 min 节流的 denied summary 摘要行，心跳行附 `top=`）；放行：按时间节流（`WAKE_PATH_HEARTBEAT_MIN_MS` = 30 min）                                                            | 一夜进入近万次，每次都打日志会把 `modules_*.log` 淹没                                                |
| `checkBroadcastWakePath`（Gate-B） | 首次到达打一条即时 `… broadcast gate first reach …`（仅此一条）；明细行前 `WAKE_PATH_DETAIL_LIMIT`（10）次（c2dm 到达、以及任何拒绝各计一份）；计数每次 traffic-probe tick（30 min）随该 tick 的 `gms probe …` 行尾输出 | Gate-W 的首次心跳即时可见，广播路径若只靠 30 min 摘要行，"已挂钩但从未执行"要等半小时才能与"到达但从不拒绝"区分，故补一条**只打一次**的到达行 |
| `doDesSocketForUid`              | 前 10 次，或任何命中 GMS uid 的调用                                                                                                                                                   | GMS 命中无论第几次都必须记录                                                                   |

### 4.5 日志契约

| 约定                            | 含义                                                                                      |
| ----------------------------- | --------------------------------------------------------------------------------------- |
| `TAG = "HyperGreeze"`         | 全部日志统一前缀                                                                                |
| `logSkip(msg)`                | 目标缺失，INFO，**递增 `hookTargetsAbsent`**                                                    |
| `logSkipOtherGeneration(symbol)` | 目标缺失但属于"另一代次 ROM 的符号"，DEBUG——不该在当前代次出现，不是异常。**不立即成行**：入缓冲，由安装面出口并成一条 `cross-generation target(s) absent (K), skip: …`，且不计入摘要行的 `M` |
| `ClassNotFoundException`      | 通常 ERROR（类应当存在）                                                                         |
| 装机摘要行                         | `HyperFCMLive active in <process>: N hook(s) installed, M target(s) absent on this ROM[, K cross-generation (expected)]` |
| 探针合并行                         | `probe: <键=值…>`（只并"正常读数"；异常读数各自成行）。**powerkeeper 域同样有一行**——`NetdExecutor#execute->…` 原先是自成一行的签名探测，现收进这里，两个域的 `probe:` 键名一律用 `<SimpleClass>#<method>` |
| 白名单行                          | `allowlist loaded: selected=<bool>, strict=<bool>`——**不打印包数**：在看不到包名的前提下，计数只等价于"非空"，而 9→5 的编辑在新旧两版都读作同一行 |
| 30 min 心跳行                     | `gms probe [<reason>]: <uid/rx/tx>; broadcast gate …`——**同一 tick 的计数字段与流量增量合成一行**（原先是背靠背两条：`gms traffic probe [periodic]` + `broadcast gate:`；非全零时更是三条）。`reason` 里带 `gen N`，热重载判据看它递增 |
| 安装面成组行                       | 同一类、同一用途的多个钩子并成一行：`UserConfigureHelper#{a/b/c} hooked for userTable re-assert` |

摘要行是**装机验证的第一判据**：两个域各应出现一次，`M` 的取值应与该 ROM 的代次预期相符（本机 powerkeeper 为 `18 hook(s) installed, 0 target(s) absent on this ROM, 10 cross-generation (expected)`——那 10 个是 OS3-only 符号，不是回归）。

**摘要行只在开机那一次出现，热重载不会重打**：热重载入口是 `onHotReloaded`，它直接调 `hookSystemServer` / `hookPackage`，不经 `onSystemServerStarting` / `onPackageReady`（实证：`modules_2026-10-05T23_15_51.594188.log` 是装新版后的热重载，全文 54 行里没有任何 `hook(s) installed` 行）。热重载是否生效改看探针启动行的 `gen N` 递增与旧链 `superseded, retire` 行（那条独立的 `gms traffic probe: scheduled every 30 min … generation N` 已并入启动行，`generation` 不再单独成行）。

一次性证据标志位清单（每个都对应一条"这条路径真的跑过一次"的 INFO）：

`gmsRestrictNetLogged` · `c2dmDeferBypassLogged` · `alarmGateBypassLogged` / `alarmGateSeenLogged`（成对） · `gmsUdpFilterLogged` · `restrictNetMatchLogged` · `noNetworkBlacklistMismatchLogged`

`alarmGateBypassLogged` / `alarmGateSeenLogged` 的成对设计是可证伪原则的样板：只有"模块改写了一次拒绝"的记录而没有"ROM 放行过一次"的记录时，静默的日志无法区分"从未拒绝"与"从未到达"。

### 4.6 外部取证

| 手段                                                                          | 用途                                                                                                   | 判读要点                                                                                                    |
| --------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| LSPosed 导出的 `modules_*.log`                                                 | **夜间取证的唯一起点**                                                                                        | logcat 主缓冲会被白天日志冲掉，夜间往往只剩几十行                                                                            |
| `adb shell dumpsys greezer`                                                 | 当前生效的策略实现（`mCurrentCNPolicy`）、是否开着按 region 重算的门（`mSetForceCnGlobal`）、冻结进程列表、GMS 是否在内                 | `mCurrentCNPolicy: 1` ⇒ Domestic 生效；GMS per-uid 记账 `frozen=0s`；`mSetForceCnGlobal=false` ⇒ 不重算（见 2.3.1） |
| `adb shell getprop ro.miui.region` / `ro.debuggable` / `ro.build.type`      | region 决定 `sCnModel`；后两者决定 `dumpsys greezer force_cn_global` 分支是否可达                                  | 本机 `CN` / `0` / `user` ⇒ Domestic 生效且该命令被 `IS_DEBUGGABLE` 门挡掉（见 2.3.1）                                  |
| `adb shell dumpsys netpolicy`                                               | 睡眠白名单集合大小、GMS 的当前网络策略                                                                                | `policy=4` = ALLOW_METERED_BACKGROUND                                                                   |
| `adb shell dumpsys deviceidle`                                              | doze 白名单各段是否含 GMS                                                                                    | user / system / system-excidle 三段                                                                       |
| `adb shell dumpsys network_management`                                      | netd 的 `UID firewall dozable rule`（`uid:1` = ALLOW / `2` = DENY），夜间真正生效的那一层                          | 只在 Doze idle 期间 enabled                                                                                 |
| `adb shell settings get system MILLET_NO_RESTRICT_APP`                      | 验证 P1 的写入是否落地                                                                                        | 应含 GMS                                                                                                  |
| PowerKeeper 私有 ContentProvider `.../SimpleSettings/misc`                    | 睡眠开关的真实存储位置（不在 Settings 三个命名空间里）                                                                     | —                                                                                                       |
| `adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService` | **FCM 链路的首选诊断源**                                                                                     | 见 4.7                                                                                                   |
| `adb logcat -s MIPOWERHALSERVICE-NETLINK`                                   | 小时级流量采样（`uid=10133 … dev=wlan0`），补流量探针夜间被 suspend 推迟的盲区                                              | —                                                                                                       |
| `adb shell cmd wifi status`                                                 | 秒级链路指标：`RSSI` / `score` / `isUsable` / `Link speed` / `Calculated Tx\|Rx` / `lostTxPacketsPerSecond` | 比 `dumpsys wifi` 轻得多，适合前台活跃窗口采样                                                                         |

**长窗取证必须逐行刷盘**：`logcat -f` 走的是块缓冲，小流量下可能整夜不 flush，次日取回时仍是一个空文件。必须：

```sh
adb shell "logcat -s LSPosedLogDaemon | awk '/fcmlive,HyperGreeze/ {print; fflush()}' >> /data/local/tmp/fcmlive-night.log"
```

- toybox 的 awk **支持 `fflush()`**（无参即 flush 全部输出流），这是本机唯一可用的逐行刷盘手段；`--line-buffered` / `sed -u` / `stdbuf` 在本机均不存在。
- 想让它熬过物理拔线：`setsid nohup <script> </dev/null >/dev/null 2>&1 &` 已实测有效。断线后无法验证它是否仍在跑（adbd 退出时可能清理子进程），这是残余风险，不是可消除项。
- **c2dm 不可伪造**：receiver 声明 `com.google.android.c2dm.permission.SEND`，AMS 在 enqueue 阶段就对非 GMS 发送方抛 Permission Denial（shell uid 2000 一样被踢）⇒ 只能等真实投递。自然样本首选 Play Store；已被勾进自己唤醒名单的那款即时通讯应用也同理——名单里的包名触发不到 Gate-B 的拒绝分支，不宜用作阴性证据。

**取证规则**：

1. `invoke-*` 计数看不到：反射跳板、binder 跨进程、跨 jar 的类。
2. **被覆写的方法要按基类型计数**。曾因只数 `AlarmManagerServiceStubImpl` 的调用点而把 `checkAlarmIsAllowedSend` 误判为死代码——实际调用点在 `AlarmManagerService.triggerAlarmsLocked`，走的是基类虚派发。
3. ROM 取证与运行时 `dumpsys` **两端闭合**才能下"哪个实现生效"的结论。只看一边会把"存在"当成"生效"。
4. 判极性要从字节码读，不要从方法名猜（`isNoRestrictApp` / `isRestrictNet` 都判错过）。

### 4.6.1 分析对象清单：本模块触及的 ROM 目录与文件

按「静态字节码源 → 运行时状态源 → 内核/持久态」三层归类。**静态层决定「有没有、怎么判」，运行时层决定「此刻是不是」，内核层决定「真正落点在哪」**——三者缺一不可（见取证规则 3）。

**A. 静态字节码源（`adb exec-out "cat"` 拉取 → 本地 dexdump/反汇编）**

| 路径 | 承载 | 本项目用途 |
| --- | --- | --- |
| `/system/framework/framework.jar` | AOSP 框架类 | `Intent.isExcludingStopped()`（stopped 判定掩码）、`BroadcastSkipPolicy`、AOSP 侧 AMS 调用点 |
| `/system/framework/services.jar` | AOSP system_server | `ActivityManagerService`、`ActiveServices`、`ContentProviderHelper`、`IntentResolver`、`ProcessRecord` |
| `/system_ext/framework/miui-services.jar` | **MIUI system_server 扩展（最大落点）** | `GreezeManagerService` / `AurogonImmobulusMode` / `PolicyMaker` / `DomesticPolicyManager` / `InternationalPolicyManager` / `PowerStrategyMode` / `BroadcastQueueModernStubImpl` / `ProcessCleanerBase` / `ProcessManagerService` / `AppStandbyController` |
| `/system_ext/framework/miui-framework.jar` | MIUI 框架扩展 | `WhetstoneActivityManager`（客户端）+ `WhetstoneActivityManagerService`（服务端同 jar）、`MiuiNetworkManager` |
| `/system_ext/framework/miui-wifi-service.jar` | MIUI WiFi 服务 | `AmlMiuiThirdPartScorer#notifyScoreAndIsUsable`（WiFi 弱信号实验） |
| `/system_ext/app/PowerKeeper/PowerKeeper.apk` | **PowerKeeper 独立进程（非 system_server）** | `PhoneSleepModeController#applySleepConfig`、`UserConfigureHelper`、`ActiveStateController`、`PowerKeeperAppConfigure` |
| `/system/bin/netd` | netd 守护进程 | UDP 包过滤 / `setMiuiFirewallRule` / `MiUiFirewallSharedUid`（`oemnetd` 独立进程不存在） |

**取证注意**：dex 里查类/成员**必须用描述符形式** `Lcom/x/Y;`，点分形式在字符串池里查不到；`miui-services.jar` 可能位于 `/system/system_ext/...` 或 `/system_ext/...`，以 `pm path` / `ls` 实测为准。

**B. 运行时状态源（`dumpsys` / `settings` / `logcat`）**

| 入口 | 读出什么 | 判读要点 |
| --- | --- | --- |
| `dumpsys greezer` | 当前策略实现、冻结列表、per-uid 记账、`resCtrl:` | `mCurrentCNPolicy`；`frozen=0s`；`FZ`/`THAW` 事件 |
| `dumpsys activity [service\|broadcasts\|processes\|recents]` | 广播史、进程记录、服务绑定、provider binding | `~3m17s` 反推进程启动（**优于 `/proc` starttime**） |
| `dumpsys deviceidle` | doze 三段白名单 | user / system / system-excidle |
| `dumpsys netpolicy` | 网络策略、睡眠白名单 size | `policy=4` = ALLOW_METERED_BACKGROUND |
| `dumpsys powerkeeper` / `smartpower` | 省电场景、`userTable` | `bgControl`、`scenario` |
| `dumpsys package <pkg>` | `versionName` / `lastUpdateTime` / `firstInstallTime` | 版本与更新时刻核对 |
| `dumpsys activity service com.google.android.gms/.gcm.GcmService` | **FCM 台账（权威）** | `mCreationTimeMs` 判投递；见 §4.7 |
| `cmd appops get <pkg> [10008\|10053]` | 自启动 AppOp | `rejectTime` 是硬判据 |
| `settings get system MILLET_NO_RESTRICT_APP` | PowerKeeper 免限名单 | 应含 GMS |
| `getprop ro.miui.region` / `ro.debuggable` / `ro.build.type` | 代次与可达性判定 | 决定 Domestic 是否生效 |
| PowerKeeper 私有 CP `content://com.miui.powerkeeper.configure/userTable` | 省电策略表（**不在 Settings 三命名空间**） | 写入面之一 |
| `logcat`（`LSPosedLogDaemon` tag）/ `events` 缓冲 | 钩子输出；`am_proc_start` / `am_kill` | **判 app 启动只用 am 事件，勿用 `/proc` starttime**（USAP 池化陷阱） |

**C. 内核 / 持久态（真正落点）**

| 路径 | 内容 | 用途 |
| --- | --- | --- |
| `/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze` | **greeze 冻结的真正落点（cgroup v2）** | 写 1 = 冻结；v1 `freezer/perf/*` 与 `/proc/sys/millet/freeze_pid` 在 A17 已不存在 |
| `/proc/<pid>/oom_score_adj` | 缓存优先级（adj） | 与冻结**正交**，勿混（`cached_apps_freezer=disabled` 时 adj 只是优先级概念） |
| `/proc/net/tcp`、`/proc/net/tcp6` | 连接表（socket 状态） | 自有长连接存活判定；`SYN_SENT` / `CLOSE_WAIT` |
| `/proc/uptime`、`/proc/stat`（`btime`） | 时间基准 | starttime 换算，**仅作参照** |
| `/data/system/deviceidle.xml` | doze 白名单持久文件 | 「禁止写回未优化名单」的写盘面 |
| `/data/adb/lspd/log/` | LSPosed 管理器日志导出 | 夜间取证的唯一起点 |
| `/data/local/tmp/*.{sh,log,txt}` | 自建取证脚本与产物 | 长窗刷盘、dexdump 中转 |

**D. 三层的组合纪律**

- **能不能钩** → 静态层（类/方法/签名是否存在，代次差异见 §2.3）。
- **钩了有没有生效** → 运行时层（`allowlist loaded:` / `net release:` 等钩子自证据）。
- **真正改变了什么** → 内核/持久层（`cgroup.freeze`、socket 表、`deviceidle.xml`）。
- **只用一层必出错**：只查静态 = 把"存在"当"生效"；只看运行时日志 = "没出现"当"没发生"（弱证据，见 §6.4）；只读内核 = 不知道是谁写的。

### 4.7 GcmService dump 判读

抓法（约 300 行，建议重定向到文件再 grep）：

```sh
adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService > gcm.txt
```

#### 4.7.1 `Close err:N` 的共现归因

**单个 `Close err:N` 不能定性，必须连前后各一行一起看**。`time:S` 是被关掉的那条连接已存活的秒数，用它可以反推关的是哪一条：

```sh
awk '/Close err:/{print "CLOSE: "$0}
     /Active network|Starting parallel|Heartbeat alarm|went away/{print "      : "$0}' gcm.txt
```

| err  | 可靠的共现上下文                                                                                                               | 反推的类别                         |
| ---- | ---------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| `27` | 上一行 `Active network switched to X, was Y` + `Starting parallel McsConnection{X} in place of existing McsConnection{Y}` | **默认网络切换**，旧连接被并行新连接取代        |
| `20` | 伴随 `Active network went away` 或一个新网络对象（VPN）出现                                                                          | **旧网络对象失效**（不是"切换"，是"没了"）     |
| `25` | **与 `20` 成对、且 `time:S` 完全相同**                                                                                          | 与 `20` 是**同一事件的读／写两侧**，不是两次故障 |
| `6`  | 上一行 `Heartbeat alarm delay: N ms`（闹钟响了却没等到 Ack）                                                                        | **心跳超时**                      |

> 措辞纪律：这四个数字是 GMS 内部的 close reason，**公开渠道查不到逐条释义**。上表是从本代 ROM 的共现关系**反推**的分类，对外表述要带"反推"，不能当成官方语义。
> 
> `err:1` 目前只在对照机上出现过两条（`net=-1` 状态下，`time:14` / `time:797`），样本太少，未定性。

#### 4.7.2 用网络 ID 区分「WiFi 断了」与「只是默认网络被切走」

`GcmNetwork{N X(1)}` 里的 `N` 是 ConnectivityManager 的 netId，**一次开机内单调递增、不复用**，因此它是硬证据：

| 观测                                                                     | 含义                                                 |
| ---------------------------------------------------------------------- | -------------------------------------------------- |
| `Active network went away, was GcmNetwork{N WiFi(1)}` 之后回来的**还是同一个 N** | WiFi 链路没断，只是默认网络被临时判给了蜂窝（本机 WiFi 恒为 `102`，掉了四次都是它） |
| 回来的**是另一个 N**（对照机实测 `111` → `113`）                                     | WiFi 网络对象被销毁并重建 ⇒ **WiFi 真的断过一次**                  |

再配合 `net=-1` 行（连蜂窝也没有）区分「只掉 WiFi」与「两个射频一起掉」。

**典型成对签名**：`err:20`（WiFi 网络消失）→ 约 3 s 后 `err:27` 且 `time:1`（刚在蜂窝上建好 1 s 的连接又被回来的 WiFi 顶掉）。

#### 4.7.3 四条排除判据

| 判据                                              | 怎么读                                                             | 对照机实测                                                               |
| ----------------------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------- |
| **整机断网 vs 只掉 WiFi**：看蜂窝网络对象是否还在                 | `available:` 列表里 `Cell` 全程在 ⇒ 只有 WiFi 掉；Cell 也一起消失 ⇒ 睡眠模式/射频级关断 | `Cell{106}` 全程挂在 available ⇒ **排除睡眠模式**（它连数据一起关）                    |
| **Doze 主动关 WiFi vs WiFi 自己掉**：数 Doze 进出与断线次数的比例 | Doze 每次进出都断才可能是 Doze 关的；次数不成对即无关                                | Doze `Entering` 9 次 + `Exiting` 19 次，WiFi 只断了 3 次 ⇒ **不成对，排除 Doze** |
| **WiFi 真断 vs 选择抖动**：netId 是否换号                  | 换号 = 网络对象被销毁重建                                                  | WiFi 依次 `109 → 110 → 111 → 113`，**每次都换号** ⇒ WiFi 链路真的断过             |
| **定时器 vs 外部事件**：存活时长是否规则                        | 定时器机制（休眠策略 / `wifi_idle_ms`）会让每次存活时长相对固定；外部事件驱动则大幅抖动            | 三次存活 `100 s / 797 s / 3121 s`，相差 8 倍与 4 倍 ⇒ **不是任何定时器，是外部事件**       |

四条都过完仍指向"WiFi 自己掉"，就该去查链路本体，而不是继续在模块或省电策略里找原因：

```sh
adb shell dumpsys wifi > wifi.txt   # 看 disconnect reason / 断线计数
adb shell logcat -d -v time -s ClientModeImpl:* WifiClientModeImpl:* WifiNetworkAgent:* > wifilog.txt
# 注意：logcat 要在断线后尽快抓，ring buffer 有限，隔久了就被冲掉
```

#### 4.7.4 `Close err` 的重连代价分级

同一份 44 min 本机样本里的 25 条 `Close err`，按 code 拆开后**代价差两个数量级**：

| code        | 条数     | 共现签名                                                                                                                                              | 重建耗时（Close → Connected）              | 定性                                    |
| ----------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ------------------------------------- |
| `27`        | 7      | 上一行 `Starting parallel McsConnection{X} in place of existing McsConnection{Y}`                                                                    | 0.40 – 1.68 s（均值 **0.88 s**）         | **优雅替换**：新连接先建好再拆旧的，几乎无缝              |
| `20` / `25` | 11 / 6 | **成对出现、`time:S` 相同**（同一条连接的读／写两侧各报一次）；后续是 `Connecting using …` 而非 parallel，常伴 `Reconnect alarm delay: 5 ms`，重时升级到 `FALLBACK_ALTERNATIVE_HOSTPORT` | 0.91 – 22.02 s（均值 **6.79 / 8.72 s**） | **非计划中断**：旧连接没了，要排队重连并重跑 LoginRequest |
| `26`        | 1      | 无 switch 前因                                                                                                                                       | **148.09 s**                         | 最贵；样本太少，只登记不定性                        |

- ⇒ 这份 dump 里**累计连接不可用 281 s / 44 min = 10.8%**。因此「抖动只是 Close 多几条、不丢推送」这种概括**不成立**：每次 `err:20/25` 都有 7–9 s 的下行空窗，这段时间内 FCM 消息到不了 GMS。
- 计数陷阱：`20` + `25` 是**同一事件的两行**，统计故障次数要成对折算，否则次数翻倍。
- **唯一的干净对照**：同一晚 VPN 在线的 415 s 内 `Close err` **0 条**，而它前后的抖动期分别是 **0.63 / 1.20 条每分钟**。同机、同 AP、同时段，唯一变量是"默认网络还抖不抖"⇒ `Close err` 与默认网络抖动是**因果，不是伴随**（VPN 把底层 WiFi↔Cell 互换挡在了 GMS 视野外）。
- ⇒ **减少默认网络抖动会直接、显著地减少 `Close err`**；反向也成立——看到 `Close err` 密集，第一步该去查默认网络的判决链（`AmlWifiScoreReportInjector`），而不是先在 GMS 或模块侧找原因。

**本机已确证可写的旋钮**：

```sh
adb shell settings get system cloud_min_rssi_for_data_switch_5GHzwifi   # 本机实测 -72
adb shell settings get system cloud_min_rssi_for_data_switch_24GHzwifi  # 本机实测 -73
```

`AmlWifiScoreReportInjector` 就是拿这两个阈值判 `SWITCH_TO_CELLULAR_BY_LOW_RSSI`（实测触发时 `rssiScore = 47` < 50，瞬时 RSSI 约 −78/−82 dBm），切回条件则是 `isSwitchBackToMasterWifi averageRssi = -72`。滞回带只有约 6 dBm，环境 RSSI 只要在 −72 附近摆荡就会反复触发。`cloud_*` 前缀意味着可能被云端下发覆盖回原值，改完要复查。

#### 4.7.5 `Received <pkg>` 不等于投递成功

`Received com.xxx <msgid>` **只表示 GMS 收到了下行 stanza**；投递有没有到达 app，要看紧接着的广播结果。同一份样本里的实际序列（时间已换算为相对第一条的偏移）：

```text
T+0.000 s   net=1: Received <示例包名> 0:1791035808234013%d88aa106f9fd7ecd
T+0.021 s   net=1: No response to broadcast from <示例包名> (id=… time=5ms priority=NORMAL)
T+120.026 s net=1: Failed to broadcast to stopped app <示例包名> (id=… time=3ms priority=NORMAL)
T+360.036 s net=1: Failed to broadcast to stopped app <示例包名> (id=… time=3ms priority=NORMAL)
```

dump 末尾 `Queued messages:` 里它还挂着 `reason=1 retries=2` ⇒ **直到抓日志那一刻，这条消息从未送达 app**。

- `Received` **不能**当作"推送正常抵达"的证据。判投递成功要看正向的完成行（正常完成的样本带耗时，实测 199 / 162 / 122 / 16 ms）。
- `Failed to broadcast to stopped app` 是 **framework 原生行为**（Android 3.1+ 起 STOPPED 状态的包不接收广播），**既不等于模块拦的，也不等于 ROM 冻的**。归因前先查 `dumpsys usagestats` 里该包的 `lastTimeUsed`。
- ⇒ 这条记录对"网络抖动会不会丢推送"是零信息量的：它证明不了"能收到"，也证明不了"收不到"。要回答那个问题，看 4.7.4 的重连空窗。

#### 4.7.6 `HB interval sent` 由传输类型决定，不是质量信号

`Sent LoginRequest; HB interval sent: N` 里的 N **按当前网络类型取值**：

| 当前网络                            | `HB interval sent` | 折算      |
| ------------------------------- | ------------------ | ------- |
| `WiFi(1)` / `VPN(17) [WiFi(1)]` | `230000`           | 3.8 min |
| `Cell(0)`                       | `1680000`          | 28 min  |

⇒ **看到 `230000` 不要读成"GMS 检测到不稳所以缩短心跳"**。它只说明这次 login 走在 WiFi 上。实测一晚 19 次 login 在 230 s 与 1680 s 之间来回跳，形状极像自适应降级，实际是纯 transport 映射（蜂窝按流量与 NAT 超时更长，WiFi 走密集心跳）。同理，`heartbeat_interval` 稳定在 `230000` 是**健康**读数，不代表链路劣化。

#### 4.7.7 判「息屏切流量」的正确顺序

1. 先划出 doze 区间：把 `Client Entering doze` / `Client Exiting doze` 两两配对；
2. 再看 `Active network switched` 落在区间内还是外。**落在区间外就不该往"息屏"上归因**——实测全部 WiFi→Cell 切换都在 doze 之外，其中一组是 58 s 内来回切 4 次，任何屏幕状态策略都做不出这个节奏；
3. 用 `settings get global wifi_sleep_policy` 直接排除 WLAN 休眠策略：`2` = 息屏也保持（本机与对照机实测值），`0` = 息屏后可断开。值为 2 时这条解释**直接出局**；
4. 剩下的嫌疑人按贡献排序查：**VPN 通道上下线 > WiFi 链路本身抖动 > WLAN 助理**。
- **VPN 是隐形大头**：本机 12 条 Close 里 4 条来自 `GcmNetwork{103/104 VPN(17) [WiFi(1)]}` 的出现与消失。注意它是**间歇**的——事后 `ip -o addr | grep tun` / `ps -A | grep vpn` 查不到，不等于当时没跑过。
- **WLAN 助理别乱当证据**：global 里的 `wifi_assistant=1` 只是主开关，子项（智能切换等）**没有稳定的 global 键可查**，另有 `wifi_assistant_full_signal_switch` 之类散键。因此 `wifi_assistant=1` **不能**当作"智能切换已开启"的证据，更不能用它反推某次切换的成因。

**AOSP 侧补充读数**：

```sh
adb shell settings get global wifi_sleep_policy   # ⇒ 2（WIFI_SLEEP_POLICY_NEVER：息屏也保持 WLAN）
adb shell settings get global wifi_idle_ms        # ⇒ null
```

`null` **不等于"这个机制不存在"**——它只说明该 Settings 键从未被显式写入。读取侧是 `getLong(resolver, WIFI_IDLE_MS, DEFAULT_IDLE_MS)`，拿不到键时用**代码内默认值**；只不过 policy=NEVER 时 idle_ms 根本不进入决策，两者合起来的结论是：**AOSP 这条链路上，息屏不会因为超时断 WiFi**。手机端自查路径：设置 → WLAN → 高级设置 →「休眠时保持 WLAN 连接」，应为**始终**。

但该读数**不能结案**：它只排除 AOSP 路径。logcat 里 `WifiOptimizationImpl`、`AmlWifiScoreReportInjector`、`PowerInsight_WifiCollector` 这几个 tag 说明 MIUI 有自己的 WiFi 评分／优化层在跑，它未必读这个 global 键——那一条链的运行时判决见 3.9 与 4.7.4。

### 4.8 取样陷阱清单

| 陷阱                                | 后果                                                                                                | 对策                                                                                                          |
| --------------------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| **冷启动阶段读静态字段**                    | `<clinit>` 尚未执行，读到空集合，得到假阴性。曾读到 `mMessageApp size=0`，而热重载时是 544 / 576 / 64 —— **该集合大小随云控与加载阶段浮动** | 只用 `containsGms`，不把 size 当判据                                                                                |
| **探针只记前 N 次**                     | 长窗观测留下不可恢复的归因盲区                                                                                   | `checkWakePath` 已改为 per-caller 聚合 + 30 min denied summary；其余前 N 次探针保留（GMS 命中本就必记）                           |
| **静默出口无日志**                       | "没跑"与"跑了但没结果"在日志里同形                                                                               | 逐条补日志（3.5.0 已补 4 处）                                                                                         |
| `adb logcat -G`                   | 调整缓冲大小会丢掉原有内容，开机时段全部丢失                                                                            | 抓开机记录时不要动 `-G`，改用 `modules_*.log`                                                                           |
| `dexdump … \| awk … \| head -N`   | `head` 到量后 SIGPIPE 终止上游，"扫描完整个 dex 没找到"是假结论                                                       | 涉及"没找到"的取证禁止用 `head` 截断管道                                                                                   |
| Git Bash 路径转换                     | `adb shell ls /system/...` 被静默转成本机路径，输出为空                                                         | `export MSYS_NO_PATHCONV=1`                                                                                 |
| 零触发 ≠ 无用                          | 一个晚上的阴性只能证明"本轮未观测到触发"；触发面为 0 样本时否定兜底逻辑是循环论证                                                       | 显式写明"未验证"而非"不需要"（见 6.4）                                                                                     |
| `/proc/net/tcp` 的 uid 列           | uid 在第 **8** 列且是**十进制**（不是常见的十六进制写法），按十六进制读会得到完全不同的 uid                                           | 按列号 8、十进制解析                                                                                                 |
| Doze `IDLE_MAINTENANCE`           | 每次进入维护窗都会调 `NetworkPolicyManager.setDeviceIdleMode(false)` ⇒ dozable 链临时停用、全网临时可联网，看起来像"策略失效"     | 判"某应用为何能联网"先看 `dumpsys deviceidle` 的 `Idling history`                                                       |
| 自研流量探针夜间读数                        | 靠 system_server 的定时器，夜间被 suspend 大幅推迟 ⇒ 时间戳不可信，也不能只凭它下结论                                          | 用 GMS 心跳行连续性交叉验证：约 **3 min 51 s** 一条，看有无 **>8 分钟** 断档                                                       |
| `am start` / `cmd activity start` | 本 ROM 抛 `IllegalStateException: Already in the pool!`，shell 拉不起 Activity，容易误判为"模块坏了"              | 改用 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`（有时可用）                                            |
| 两条 wake-path 探针混淆                 | 广播闸门与 service/activity 闸门不是一条路，混着读会把结论张冠李戴                                                        | 广播 = `WakePathChecker#checkBroadcastWakePath`；service/activity = `ActivityManagerServiceImpl#checkWakePath` |
| 热重载后的首条日志                         | 更新后第一次加载会打**旧实例**的文案（`onHotReloading`），据此判断改动没生效是错的                                               | 以 run-id / 安装时间为准，不以首条文案为准                                                                                  |
| **`python -c` 里的 `:\d` 正则**       | 传参层会把冒号后的反斜杠改写成 `/d`——`r'\d\d:\d\d'` 实到 Python 手上已是 `\d\d:/d/d`，正则**静默返回 None**（伪阴性，不报错）          | 正则里用 `[0-9]` 代替 `\d`；或把脚本**写成 `.py` 文件再执行**（文件内容不经 shell 转义）                                                |
| **判资源孤儿时排除 `res/values*`**        | `themes.xml` 的 `@color/` / `@style/` 间接引用全部被漏掉，`md_*` 色板被误判成 31 个未引用孤儿                            | 引用池必须包含 `res/values*`（含 `-night`），只排掉**被判定资源自身所在的文件**                                                       |
| **Compose 委托 import 算作孤儿**        | `androidx.compose.runtime.getValue/setValue` 源码里不出现字面名，脚本判为未使用；删掉则 `by remember{}` 编译失败           | 白名单保留；同理 `@Preview` 函数无调用者也不是死代码                                                                            |
| **`NetReassign` 当切网判据**           | `[no changes]` 每次能力重评判都刷；`[reqId : null → 101]` 是 NetworkRequest 级重分配。一局能刷上千条，误算成"1270 次切换"       | 只认四条：`Setting inactive [N WIFI]` / `Switching to new default` / `+EXITING` / `Wifi is set to exiting`       |
| **游戏中 `adb install -r`**          | LSPosed 会重载 system_server 与 powerkeeper，正在采集的那局数据直接被搅掉                                            | 采集进行中禁止安装；编译好等采集结束再装                                                                                        |
| **设备端 logcat 过滤串被 `adbd` 回显**     | `grep -c "Setting inactive"` 报 4 条，全是 `adbd: adbd service requested 'shell,…'` 把自己的过滤表达式打了一遍      | 统计前必须排除 `adbd` TAG                                                                                          |
| **logcat 缓冲区在前台活跃场景撑不住**          | 开始前 `logcat -d` 有 15.8 万行，但 main buffer 只覆盖到最近 **3 分钟**（被游戏日志冲掉）                                  | 想追历史事件必须实时落盘，事后 `-d` 拿不到东西                                                                                  |

### 4.9 向终端用户采集 FCM 诊断文本

**先确认拿到的是不是诊断本体**：用户回传的"FCM 日志"常常是 `logcat` 全量 dump（几十 MB、几万行），里面**没有** `Close err:` 与 `GcmNetwork{`，而判"WiFi 真断 vs 只是默认网络被切走"全靠这两个字段。收到文件先 grep 一下，缺了就按方式 B 重抓：

```sh
grep -c "Close err:" gcm.txt   # 0 ⇒ 这不是诊断本体
```

**采集时机（比命令本身更要紧）**：

1. 抓之前**不要重启**——`dumpsys` 读的是 GMS 进程内存里的环形事件历史，重启即清空；
2. 先**复现一次**（息屏放几分钟 → 亮屏）再抓，否则抓到的窗口里没有那次切换；
3. 顺手记下**息屏与亮屏的大致时刻**，否则拿到文本也无法与 Close 时间戳对齐。

**三条采集路径**：

| 路径         | 前提                 | 操作                                                                                                                                           | 覆盖面                                                              |
| ---------- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| A ~~界面分享~~ | 无                  | ~~诊断页右上角菜单 → 分享 / 复制到剪贴板 → 粘到备忘录导出 `.txt`~~                                                                                                  | ~~只含界面显示的条目；菜单项名称随 GMS 版本变（Share / Copy to clipboard）~~，**基本失效** |
| B adb      | 电脑 + USB 调试        | `adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService > gcm.txt`                                                        | 与本机取证同源，含 netId、`Close err:N`、心跳延迟，比界面全                          |
| C 手机端自抓    | 已 root，或装了 Shizuku | `su -c 'dumpsys activity service com.google.android.gms/.gcm.GcmService > /sdcard/Download/gcm.txt'`；Shizuku + Termux 用 `rish` 起 shell 后同样执行 | 同 B，免去电脑                                                         |

**判读所需的最小集合**（缺一项就只能停在"无法定性"）：

1. 上面那份 `gcm.txt`（或界面全文）；
2. **系统版本**：设置 → 我的设备 → Android 版本与 HyperOS 版本（决定睡眠保活是不是静默空转，见 6.3）；
3. **两个开关的截图**：睡眠保活 WiFi / 移动数据两个开关；
4. **LSPosed 导出的 `modules_*.log`**（`/sdcard/Download/…/log/`），重点三行：`Sleep-mode legacy per-uid chain armed`、`cutoff hooks incomplete, degraded`、`sleep-mode: kept WiFi on`；
5. `adb shell settings get global wifi_sleep_policy`（`2` = 息屏也保持 WLAN，可直接排除休眠策略这条解释）。

**隐私提醒**：dump 里含 android_id / 设备 ID、包名列表与服务端地址。转发前删掉 Device Id、android_id 这两行不影响判读，其余（netId、err 码、时间戳）才是要紧的。

---

## 5. 验证过程与最终结论

### 5.1 构建与装机验证（每次改动后必做）

1. 构建 → `adb install -r`；
   - 快速查看：`adb logcat -d -s LSPosedLogDaemon | grep HyperGreeze`
   - **整宿留存源**是 LSPosed 管理器导出的 `modules_*.log`（主 logcat 缓冲会被白天日志冲掉）
2. 触发热重载（不需要重启）；
3. 在 `modules_*.log` 中确认两个域的摘要行：
   - `HyperFCMLive active in system_server: N hook(s) installed, M target(s) absent`
   - `HyperFCMLive active in com.miui.powerkeeper: …`
4. 核对 `M` 与该 ROM 代次的预期一致（突增 = 代次漂移）；
5. 确认此次改动对应的钩子/日志行**在列**。

三条避免误读的注记：

- `AppStandbyController#setUidState hooked` 这类行会**每次 package-ready 重复一次**（热重载会重跑），看起来像装了多个钩子，实际 `setId()` 已把它们收敛为一条活钩子。行尾带 `pkg=` 与 `userId=` 就是为了消除这个误读。
- **摘要行只在开机那一次出现**（日志核对：开机时两个注入域各打一条 `HyperFCMLive active in …`，之后的两次热重载只重打了各钩子的安装行与探针行，**没有**再打摘要行）。所以"日志里只有一组 `N hook(s) installed`"不能读作"后续热重载没生效"——热重载是否生效看探针的 `generation` 递增（`gms traffic probe: … generation 2/3`）与旧链 `superseded, retire` 行。
- 实测一次 framework 软重启后的自检：system_server **29 hooks / 0 absent**，powerkeeper **18 hooks / 0 absent on this ROM, 10 cross-generation (expected)**（那 10 个全是 `NetdExecutor` ×2 + `GmsObserver` ×8，本 ROM 已知缺失，非回归）。跨版本比较钩子数时须先确认被比较的钩点在当前 HEAD 是否还存在（`git grep` 可核对）。

### 5.2 长窗观测与真机对照

一次完整的整宿观测（开机 → 次日）应产出：

| 观测项       | 期望                                                                                  |
| --------- | ----------------------------------------------------------------------------------- |
| 摘要行出现次数   | 2（两域各一次）；多次说明发生了重启/热重载                                                              |
| 冻结防御位命中   | 正常为 0；非 0 说明 ROM 真的对 GMS 动了手，需复核极性                                                  |
| Alarm 门   | 至少应有 `GMS alarm allowed by ROM` 一条（证明门被到达）；出现 `re-allowed denied GMS alarm` 说明门真的拒过 |
| userTable | `bgControl` 保持 `noRestrict`，无被改回 `miuiAuto`                                         |
| Gate-W    | `reached` 显著大于 0，`denied` 可归因；GMS caller 应全部放行                                      |
| 睡眠链       | 按 3.8.5 判定表逐条对照                                                                     |
| 流量探针      | 夜间增量非零；nudge 前后各一条采样                                                                |

用 `dumpsys` 做**对照实验**是无需等待推送即可验证 GMS 侧保护的手段：

| 观测                                       | 期望                                            |
| ---------------------------------------- | --------------------------------------------- |
| `dumpsys greezer` 冻结进程列表                 | GMS 各进程**不在**列表内；被勾选的目标应用**可以**在列表内           |
| `dumpsys greezer` 的 `mCurrentCNPolicy`   | 决定 Domestic / International 哪一侧生效，据此判断哪些钩子是活的 |
| `dumpsys netpolicy`                      | 睡眠结束后白名单应被 clear（集合为空符合预期），GMS 策略无阻塞          |
| `dumpsys deviceidle`                     | GMS 在 doze 白名单各段内                             |
| `settings system MILLET_NO_RESTRICT_APP` | 含 GMS（P1 已落地）                                 |

端到端验证用已知通过 FCM 收推送的应用进行，但要注意它的局限：一次成功只能证明**本次**链路通畅，不能证明防御位有效；反之一次失败也无法定位到具体门——需要结合 5.2 的日志。

### 5.3 真机取证样本

按取证先后编号；窗口列给出**相对次序**（是否同一天、日间还是夜间），具体日历日期对判据没有意义，本文不记录。

| 编号  | 窗口      | 时长          | 条件                |
| --- | ------- | ----------- | ----------------- |
| S1  | 第 1 夜   | 13 h 49 min | 本机，整宿             |
| S2  | 第 2 昼   | 9 h 23 min  | 本机，Gate-W 首轮      |
| S3  | 第 3 夜   | 5 h 30 min  | 本机，睡眠 cutoff 完整发生 |
| S4  | 第 4 昼   | 2 h 27 min  | 本机，息屏/亮屏          |
| S5  | 同日夜间    | 44 min      | 本机，含 VPN 在线对照段    |
| S6  | 第 5 日凌晨 | 5 h 18 min  | 本机，**充电**息屏整夜     |
| S7  | 同日上午    | 18 min      | 本机，前台游戏，三路采集      |
| S8  | 紧接 S7   | 31 min      | 本机，删除充电子开关后第二轮游戏  |
| S9  | 对照机单独一次 | 2 h 24 min  | OS3 设备            |

**S1 / S2（Gate-W）**：首轮 reached=9951 / denied=22（可归因拒绝全部落在单一第三方应用包名上，GMS 作为 caller 的 57 次采样全放行）；第二轮 reached=15073 / denied=0。两轮合计约 25 k 样本，GMS 零拒绝 ⇒ "行为钩不落地、维持只读"正式落档。

**S3（睡眠 cutoff）**：窗口内出现一次完整的 `applySleepConfig`——`… setDataEnabled false setWifiEnabled false` → `restoreSleepConfig`，中间 GCM `net=-1` 断 **5 h 30 min 57 s**。这是"确有会断网的夜"的唯一直接样本；该夜是否充电没有留下记录。

**S4 / S5（Close err 归因）**：S4 窗口内 `err:27` ×5、`err:20` ×5、`err:6` ×2，每次重建耗时 0.4–1.3 s，期间真实投递全部成功（199 / 162 / 122 / 16 ms），`bad_heartbeat_count` 全程 0。S5 窗口给出代价分级与 VPN 对照（见 4.7.4）。

**S6（充电整夜）**：网络侧零切换（全程只有一个 `GcmNetwork{101 WiFi(1)}`，netId 未换号）；但有 **4 次 `Close err:6`**（心跳没等到 Ack）：

（时刻一律换算为窗口起点之后的相对偏移）

| 关闭           | `time:`（连接寿命） | 上一条入向        | 重建完成    | 下行不可用区间    |
| ------------ | ------------- | ------------ | ------- | ---------- |
| T+28 min     | `6343`        | T+23 min     | 关闭后 1 s | 61 – 322 s |
| T+1 h 00 min | `1878`        | T+55 min     | 关闭后 1 s | 61 – 323 s |
| T+3 h 09 min | `7754`        | T+3 h 03 min | 关闭后 1 s | 61 – 367 s |
| T+5 h 18 min | `7753`        | T+5 h 13 min | 关闭后 1 s | 61 – 323 s |

- ⇒ 5 h 18 min 内累计下行不可用 244 – 1335 s，占 **1.28% – 6.99%**（每次 1–6 min）。区间而非定值的原因：只能确证链路在上一条入向时还活着、且最迟活到客户端心跳发出前，实际的断裂时刻落在这两者之间。
- 故障间隔 1880 / 7755 / 7755 s：后两次只差 0.1 s，形状像约 2 h 9 min 的周期，但**只有两个周期、三组样本，不足以定性**，登记为待复采的观察项。
- **这一夜在"投递成功率"上是零信息**：整份 dump 里非心跳的 `Received` 只有 4 条 `IqStanza`，**没有任何一次真实投递**发生过。
- **睡眠保活未被检验**：整夜 **0 条 `sleep-mode:` 运行时行**，而该支路设计成"每次 cutoff 调用必打印一行"（放行与拦截各有自己的措辞），且安装行走的是正常路径（不是 `cutoff hooks incomplete, degraded`）。唯一与日志自洽的解释是：**ROM 这一夜根本没有执行 `applySleepConfig` 的关网段**。反向可确证：若是 S3 记录过的那种整机关网，窗口前段那几个小时 GCM 应是 `net=-1` 空白，实际是 230 s 心跳连续不断。
- ⇒ **充电整夜不构成对睡眠保活的检验**。这是"整夜验证必须断 USB、不充电"那条规矩的实证依据。
- **WiFi 弱信号放宽同样未被检验**：模块侧 25 次 `wifi-weak-signal: reported N …`（N = 41–49）全部集中在窗口最初约 30 min 内，此后 8 h 一条都没有；该行按 30 s 节流，故"零行"可反推这 8 h 内 ROM 从未上报过 <50 的分数。⇒ 此刻可引用的实证仍是 S7 / S8。

**S7（前台游戏，三路采集）**：ROM `rssiScore` 在 36–60 之间剧烈振荡，971 个样本里 **169 个低于 50**，最低 36；模块共 14 次打印钳位行；`notifySwitchNetworkByOtherStrategies` `type=1001` ×4、`type=1003` ×44；**切换次数为 0**，FCM 零 `Close err`，20 条入向心跳间隔恒定 230/231 s。

**S8（第二轮游戏）**：ROM `rssiScore` 44–60，1245 个样本中 **50 个低于 50**（最低 44）；链路实测 RSSI −86 ~ −66 dBm（中位 −74）；**ROM 算出的切换理由 `SWITCH_TO_CELLULAR_BY_LOW_RSSI` × 50 + `SWITCH_TO_CELLULAR_BY_POOR_QUALITY` × 6 = 56 次，实际切换 0 次**；模块 24 行钳位日志；FCM 27 条入向心跳间隔恒定 230/231 s，`Close err` 0 条。

- 这局比 S7 强的那一点：S7 只看到"评分低但没切"，本局直接读到了 ROM 的**意图**——它算出了 56 次切换理由，**每一次都没走到 `Setting inactive`**，正面确认了"两条理由共用同一个出口"的判断。
- 局限照写：仍是**开关开启**的样本，不能反推"不开也一样"。基线对照仍只有单独一晚（12 个低分样本 12 次全切）。
- `type=1001` 与 `BY_POOR_QUALITY` 的时刻**并不吻合**，故不能写成"1001 是 POOR_QUALITY 的专用出口"。

**S9（对照机，OS3）**：2.4 小时窗口 8 条 Close（见 4.7.3 四条判据的实测列）。`bad_heartbeat_count` 四组里三组为 0（唯一非 0 那组 `good=1, bad=2`），`heartbeat_interval` 稳定在 `230000`，期间两次真实投递都成功（23 ms 与 58 ms）。**连接是健康的，这 8 条全是切换代价**——但这句结论**只对 `err:27` 那一类成立**（见 4.7.4）。

**该机型已确认的前置条件**（不必重复索取）：OS3；从未开启"智能切换"；`wifi_sleep_policy=2`、`wifi_idle_ms=null` ⇒ AOSP 侧排除 WLAN 休眠策略。

### 5.4 结论汇总

| #   | 结论                                                                              | 状态            | 主要证据                                                                                         |
| --- | ------------------------------------------------------------------------------- | ------------- | -------------------------------------------------------------------------------------------- |
| 1   | V816 的睡眠断网是整机物理关网（`setWifiEnabled(false)` + `setDataEnabled(false)`），不是按 uid 掐网 | 确证            | S3 实测 5 h 30 min、`net=-1`；字节码偏移 `29e36a` / `29e404`；`sleep_mode_network_white_apps` 关网路径零读取点 |
| 2   | 旧 per-uid 睡眠链在 V816 上整夜零触发                                                      | 确证（本代）        | 无 `Sleep mode entering` 行、哨兵未 FIRED；转为防御位并保留哨兵                                               |
| 3   | `enablemiuistandby enable` / `setuiddnsrule` 在本代 ROM 是死信                        | 确证            | dnsproxyd socket 实测 `500 Command not recognized`                                             |
| 4   | `OemNetdListener` 与 GMS 无关                                                      | 确证            | 静态 0 个 GMS 字符串引用 + 运行时零命中 + `netpolicy policy=4`                                             |
| 5   | GMS 不进 greeze 冻结路径                                                              | 确证            | `dumpsys greezer` per-uid `frozen=0s`、`noControl` 报 `no_freeze:invisible`                    |
| 6   | c2dm **确实**能走到 `deferBroadcast`                                                 | 确证            | 自设反证哨兵命中（时间戳不记录）                                                                             |
| 7   | Gate-W 对 GMS 零拒绝（约 25 k 样本）                                                     | 确证            | S1 / S2                                                                                      |
| 8   | PowerKeeper 自身能改 GMS 待机网络状态的路径 100% 收敛在 `AppStandbyController#setUidState`      | 确证（静态）        | 全 dex 唯一收敛链，后三级各 1 个调用点                                                                      |
| 9   | 「放宽 WiFi 弱信号切换」能掐住 ROM 的切换出口                                                    | 确证（开关开启样本）    | S8：56 次理由、0 次执行；基线对照 12/12 全切                                                                |
| 10  | 睡眠保活（WiFi / 移动数据两个开关）**有效**（原判 ~~有效性未验证~~，2026-10-05 改判） | **有效**（用户实机确认） | 用户实机确认（2026-10-05）：开启的那条无线电在睡眠期间保持开启。S6 充电夜不构成检验（ROM 未执行 cutoff，零样本），唯一 cutoff 样本是 S3 |
| 11  | 子选项 `wifi_weak_signal_floor` 各档位的实际效果                                           | **未验证**       | 只有推演，无实测对照；30 档零样本                                                                           |
| 12  | `notifySwitchNetworkByOtherStrategies type = 1003` 是否会导致切换                      | **未观察到（观察项）** | S7 44 次、S8 168 次出场，均零切换                                                                      |
| 13  | 充电夜 WiFi 评分与 FCM 报错的关联                                                          | **未验证**       | S6 后 8 小时零低分上报                                                                               |

> **第 10 行的改判（2026-10-05）**：原判的依据是"本轮观测窗口内零样本"，而阴性推理本身不能否定一条功能；用户随后在真机上确认该功能生效，正向样本成立，故改判为**有效**。S6 的零样本记录仍然成立（它说明充电夜不构成对睡眠保活的检验），只是不再被读成"功能无效"。表中其余各行未动。

### 5.5 已排除的方案

以下方案曾实施或曾考虑，现已删除或否决。保留记录是为了避免重复论证，也避免后来者把它们当作既有能力。

| 方案                                                                                                                          | 处置                                   | 排除依据                                                                                                                                                                                                                                                                                                                                                                   |
| --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **按 uid 把 GMS 注入睡眠白名单作为现役保护**                                                                                               | 降级为 OTA 防御位 + 哨兵                     | 结论 1、2：V816 不走这条链；在无新的运行时命中证据前不得当作有效保护                                                                                                                                                                                                                                                                                                                                 |
| **「仅在充电时才不断网」子开关**（`sleep_keepalive_charging`）                                                                              | 已删除                                  | ① 纯收窄，上限就是主开关单独开启的样子，不可能带来正向收益；② 充电夜开与关不可区分（S6 无 cutoff）；③ 唯一能产生差异的场景恰是最需要保活的一夜，而它在那里主动放弃保活；④ `readChargingState()` 三态读不到时 fail-closed，会让用户静默失去保活；⑤ 立项前提"充电 ⇒ PowerKeeper 否决入睡"已被推翻（真正让设备不睡的是 USB/adb 活动使其保持 Awake）                                                                                                                                                    |
| **`wechat_battery_shield`**（拦 ROM 的省电策略自动升格写）                                                                               | 3.5.3 之后移除                           | 该升格的熔断跨重启持久、手动改回走 `set` 不 touch 集合、覆盖安装不摘标记 ⇒ 对已装微信而言升格只发生一次，开关挡的是"一次已被消耗的机会"。保留 AOSP 侧 `wechat_doze_keepout`                                                                                                                                                                                                                                                          |
| **自建 netd 链 + 仅 allow GMS**                                                                                                 | 否决，不实施                               | 系统本就有两层 uid 收口（netd dozable 链、netpolicy 策略），实测 GMS 在两层均已放行（见 6.6）                                                                                                                                                                                                                                                                                                      |
| **在 `deferBroadcast` 上给 c2dm 无条件豁免**                                                                                        | 已移除（P0 修正）                           | 该签名只有 action 没有包名，无法按目标应用守门，与"未勾选应用应与未装模块时一致"的承诺有偏差。守门职责整体上移到 `isAllowBroadcast`（同时持有 callerUid / callerPkgName / calleeUid / calleePkgName）                                                                                                                                                                                                                           |
| **逐条拦截切换理由码**（`BY_LOW_RSSI` / `BY_POOR_QUALITY`）                                                                            | 否决，改为掐出口                             | 两条理由共用 `mLegacyIntScore < 50` 同一出口；逐条拦会漏。S8 正面确认                                                                                                                                                                                                                                                                                                                       |
| **把 WiFi 分数钳到恰好 50**                                                                                                        | 已修，改为 `WIFI_SCORE_CLAMP_TARGET = 51` | 钳到 50 依赖 ROM 用严格 `<`；若某代改成 `<= 50` 会当场失效且不报错（日志照打"reported as usable instead"）                                                                                                                                                                                                                                                                                         |
| **扩展 P4 恢复的触发面**（模块装载 / 亮屏 / c2dm 投递时）                                                                                      | 评估后不实施                               | ① P4 广播会让 GMS **主动断开当前 MCS**，属破坏性修复，只在"连接已死且 GMS 不会自愈"有先验证据时才正当；② 三类诱因均可由 GMS 自愈（见 3.10）；③ c2dm 正在投递恰好**证明** MCS 活着，投递前预检在语义上是反的                                                                                                                                                                                                                                       |
| **`/proc/net/tcp` 扫描 MCS socket**                                                                                           | 已移除                                  | ① 读不到（该文件被标 `proc_net_tcp_udp`，Enforcing 下 system_server 无读权限；放宽 SELinux 不在考虑范围内）；② 答错问题：DNS 拦截与防火墙 DROP 都不通知端点，socket 保持 ESTABLISHED，会把死连接报成健康连接                                                                                                                                                                                                                      |
| **预置 `mUidState` 为 true 以绕过 `setUidState` 短路**                                                                              | 否决                                   | 传入值等于缓存值时方法提前返回，true 的缓存会抑制恢复路径而非触发它                                                                                                                                                                                                                                                                                                                                   |
| **硬编码下游混淆字母**（OS3 `s:` / OS4 `r:`）                                                                                          | 否决                                   | 代次漂移；只钩稳定的 `setUidState(IZ)V`                                                                                                                                                                                                                                                                                                                                          |
| **`NetReassign` 当作切网判据**                                                                                                    | 否决                                   | `[no changes]` 每次能力重评判都刷，`[reqId : null → 101]` 是 NetworkRequest 级重分配；会得出 1270 次"切换"的荒谬结论                                                                                                                                                                                                                                                                              |
| **清除 stopped 标记的几条路**（主动写 false、钩 `isPackageStoppedForUser` 返 false、跳过 `forceStopPackage`、拒 `setPackageStoppedState(true)`） | 不建议                                  | 只读欺骗会牵连 `AppWidgetServiceImpl`；根因在严格模式收窄。正规清除点是 `AMS#addAppLocked` 与 `ActiveServices#bringUpServiceInnerLocked`                                                                                                                                                                                                                                                        |
| **覆盖安装会重置 ROM 的省电策略熔断**                                                                                                     | 排除                                   | `onPackageUpdateStarted` 未覆写、`onPackageUpdateFinished` 被覆写成 `addPackage`；`addPackage` 内的 `notifyPackageRemoved` 条件是 uid 发生变化，覆盖安装 uid 不变                                                                                                                                                                                                                               |
| **用 `wifi_assistant=1` 反推"智能切换已开启"**                                                                                        | 排除                                   | 该键只是主开关，子项没有稳定的 global 键可查                                                                                                                                                                                                                                                                                                                                             |
| **`wechat_battery_shield` 之外的 PowerKeeper `PowerSaveConfigureManager#setPowerSaveAppConfigure` 钩点**                         | 已随该开关移除                              | 源码里已无此字符串（`git grep PowerSaveConfigureManager HEAD` 为空）                                                                                                                                                                                                                                                                                                                |
| **把 greeze 策略实现从 Domestic 改写为 International**（反射写 `sCnModel` + 触发重算 / `dumpsys greezer force_cn_global set` / 自行构造实例注入）     | 否决，不实施                               | ① 目标已由 `DomesticPolicyManager#isRestrictNet → false` 达成（极性经字节码核对），改道没有新增收益；② `setCnModel` 连带翻转 `greeze.power.ConfigManager.isGlobal`，影响面是整机"中国/国际模式"；③ `force_cn_global` 被 `Build.IS_DEBUGGABLE` 门控（本机 `ro.debuggable=0`）且语义上只能打开"按 region 重算"；④ `onDeviceProvisionedChanged` 首条动作即把 `sCnModel` 打回 region 值；⑤ `isPushApp` 的调用者全是类内 `invoke-direct` 自调用，换对象不改道。详见 2.3.1 |

### 5.6 验证的边界

- "整夜没有任何坏事发生" ≠ "某条链被挡住了"；
- 一个晚上的阴性只支持"本轮未观测到触发"，不支持"该机制无用"；
- 触发面为 0 样本时，否定兜底逻辑是循环论证；
- 静态结论与运行时冲突时，**以运行时为准**。

---

## 6. 已知限制与风险

### 6.1 观测手段的物理边界

- **静态取证看不到**：反射跳板、binder 跨进程、跨 jar 的类。因此"零调用者"永远只是弱证据。
- **冷启动阶段**：静态字段可能尚未初始化，探针读数不可信。
- **日志留痕不完整**：仍保留前 N 次节流的探针（如 `doDesSocketForUid` 的非 GMS caller）在长窗下存在归因盲区；其 GMS 命中本就必记。

### 6.2 流量探针能回答什么、不能回答什么

`TrafficStats` 的 per-uid 字节增量是 system_server 域内**唯一可达**的连接可观测量。它回答"GMS 还在不在交换数据"，**不回答"连接是不是健康的"**。

**探针链代际去重**：热重载不取消旧的 30 min 定时链——每次 `hookPackage` 重跑都会新增一条并行链（实测一夜两条重载后三条交错，且同计数器同相位基线导致增量重复上报）。修复：代际计数器存于 `System.getProperties()`（boot classloader 对象，跨模块 classloader 共享、进程内全局；companion 字段因热重载换新 classloader 而不可见，不可用）。每条链在 `run()` 时校验自己是否仍为最新代，否则打 `chain generation N superseded, retire without rescheduling` 后退役。调度日志同步带上 `generation N`。

### 6.3 残余缺口

| 缺口                                                     | 影响                                                                                                                                                                     | 现状                                                                                                           |
| ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `setUidState` 带外限制 + 缓存为 true                          | 即使传入 `allow=true` 也会短路，无任何机制解除（P4 不覆盖）。PowerKeeper 内部已证唯一收敛（3.7.3），带外面只能来自 system_server / netd                                                                        | 四项免 root 判据可查（见 3.7.3），当前全绿                                                                                  |
| P4 触发面只有睡眠退出 + MILLET 修复                               | 其他诱因（网络切换、GMS 重建、NAT/FW 老化）无触发                                                                                                                                         | **设计选择而非缺口**，理由与重启条件见 3.10                                                                                   |
| 冷启动读静态字段假阴性                                            | 开机早期的 `mMessageApp` 读数不可信                                                                                                                                              | 只用 `containsGms`                                                                                             |
| 睡眠保活的栈谓词是 OS4 专用                                       | 两个拦截点都要求栈上存在 `PhoneSleepModeController#applySleepConfig` / `#restoreSleepConfig`。睡眠断网若长在别处（OS3 的 system_server per-uid 链），谓词永不命中 ⇒ 「睡眠不断网」开关**静默空转**：安装日志照样报已挂载，射频照旧被关 | 判分叉在哪一侧看 `Sleep-mode legacy per-uid chain armed: whitelist=…, chain=…` 与两条 `legacy path FIRED` 哨兵，**不看开关状态** |
| `notifySwitchNetworkByOtherStrategies type = 1003` 未铰住 | 「放宽 WiFi 弱信号切换」掐住的**只是分数出口**。ROM 另有绕过分数通道的旁路出口，S7 实测调用 44 次、S8 168 次，均未造成切换                                                                                            | **长期观察项，不是已关闭项**                                                                                             |
| 非 GMS 应用免冻                                             | 不提供                                                                                                                                                                    | 不在计划内                                                                                                        |
| 归因盲区                                                   | 探针前 N 次上限导致长窗观测不可完全归因                                                                                                                                                  | 已对 Gate-W 修补，其余保留                                                                                            |

**旁路出口的排查触发规则（长期有效）**：

> **只要出现「低分样本零翻转、分数出口确认被压住了，却仍然被切走蜂窝」这个矛盾组合，第一步就是查旁路出口，不要先怀疑分数 hook 失效。**

```sh
adb logcat -d -v time | grep -a "notifySwitchNetworkByOtherStrategies"
# 命中后取 type 值，再看同一秒前后是否有 Setting inactive / Switching to new default
```

判定与处置：

- `type = 1003` 出现**但没有**跟随 `Setting inactive` / `Switching to new default` ⇒ 本轮无害，继续观察，不动代码。
- 一旦出现"旁路调用后真的切走了" ⇒ 说明分数出口已经不是唯一切换路径，此时**必须重新评估**「放宽 WiFi 弱信号切换」的覆盖面：选项是把钩点上移（拦 `notifySwitchNetworkByOtherStrategies` 本身），或明确告知使用者该开关只覆盖弱信号这一条理由。**在此之前不要声称该开关能止住所有抖动。**

**应用侧判据面（供后续复核，不构成待办）**：

- 自启动 AppOps 10008 共四类检查点。**枚举口径：`#int 10008` 在 `miui-services` 两个 dex 合计 10 处**（dex1 七处 + dex2 三处）；只 dump 单个 dex 会漏掉下面第 ① 条里真正读 10008 的那个方法。①广播：**判据不在 `BroadcastQueueModernStubImpl#checkApplicationAutoStart` 里**——该方法只调 `android.app.AppOpsManagerInjector#isAutoStartRestriction(String)`（该类定义在 `miui-framework.jar`，判据是一张**硬编码的 MIUI 自带应用名单** `sAutoStartRestrictions` / `sAllowAutoStartPkgs`，与 AppOp 无关，且第三方应用恒为 false）。10008 是在它的下游读的：`checkApplicationAutoStart` 偏移 `0186` → `SecurityManagerInternal#checkBroadcastWakePath` → `com.miui.server.WakePathChecker#checkBroadcastWakePath` → `#isAllowedByWakePathRule`，由 `AppOpsManagerInternal.checkOperationWithoutVerify(10008, uid, pkg, bool)` 取值；非 ALLOWED 时交给链式启动规则引擎，规则不匹配才拒投（Slog 文案 `MIUILOG-AutoStart, Service/Provider/Broadcast Reject …`）。模块钩的是 `checkApplicationAutoStart`，且只对 caller=GMS + c2dm 在方法入口短路 ⇒ 其余 caller 会真的读到 10008。②服务绑定 `AutoStartManagerServiceStub#isAllowStartService` / `canRestartServiceLocked`（上游 SyncManager / AccountManager）+ `JobServiceContextImpl#checkIfCancelJob`；③进程重启 `ProcessManagerService#isAllowAutoStart`（上游 `ProcessStarter` 一族 + `ProcessPolicy`；同族另有 `PreStartFeedbackImpl#isAutoStart`、`greeze.InternationalPolicyManager#isAutoStartRestrict`）；④通用查询 `AppOpsServiceStubImpl#isOpAllowedForUid`——**在 `miui-services` 内没有调用者**，调用方在 `services.jar` 或上层应用。GMS 声明的 c2dm action 只有 RECEIVE（投递）/ REGISTER / UNREGISTER（应用→GMS）⇒ **投递面只需放行 RECEIVE**；②③是 Firebase token 维护走的路径，与投递无关，但**与「唤醒后活得下去」有关**（见 4.7.4）。
- 上述检查点的**模块覆盖现状（2026-10-07，v3.7 工作树）**——放行 2 处（受「允许已勾选应用自启动」=`autostart_gate_release` 控制）：`AutoStartManagerServiceStubImpl#isAllowStartService`（含 Intent 重载）+ `ProcessManagerService#isAllowAutoStart`；**只读探针 2 处**（计数 + 明细，**永不改写返回值**，不受任何开关影响）：`AutoStartManagerServiceStubImpl#canRestartServiceLocked` 的两个重载 + `PreStartFeedbackImpl#isAutoStart(String,int)`。未覆盖 6 处。三点需要更正/补充：
  - `canRestartServiceLocked` **形参 0 = callee 包名**（两个重载都是），三条独立证据：impl 的 `locals` 表直接命名 `packageName/uid/message`；调用点 `ActiveServiceManagementImpl@272df8` 从 `ServiceRecord.packageName`/`appInfo.uid` 取值；`signalStopProcessesLocked@28c668` 走 3 参桥接并把形参 0 喂给 `sendSignalToProcessLocked(list, pkg, 19, false)`。接口定义在 **services.jar**（`PUBLIC INTERFACE ABSTRACT`），impl 在 `miui-services.jar`。唯一入口 `ActiveServices.scheduleServiceRestartLocked(ServiceRecord,boolean)` = 服务被杀死后的重启调度。
  - **`canRestartServiceLocked` 不是「静默无日志」**（旧表述作废）：它有独立日志行 `MIUILOG- Reject RestartService service :`（tag `AutoStartManagerService`），全 dex 唯一归属该方法，仅在 5 参路径（ComponentName 非空 + boolean 真）才打。`MIUILOG- Reject service :`（string@9657）仍唯一属于 `isAllowStartService`。
  - `PreStartFeedbackImpl#isAutoStart` 归入 ③ 是**按调用族粗分**，其真实角色是**预启动惩罚**：调用者 `isProhibitShowWindow`（拦窗口）/ `isTempDeniedNotificationForPreStart`（临时拒通知）/ `reportProcStartedLocked`（标记进 `mActiveCachedProcs`，配 `ENABLE_PRESTART_CACHED_KILL`）。即"没自启动就后台起来"的应用不只被拒启，还会丢窗口与通知——**这是唯一一条能直接作用在通知投递面上的 10008 读取点**，故本轮以只读探针先行取证。
  - 日志形状：`autostart gate: <gate> [start ]DENIED #N -> probe|kept|released (callee=…[, 组件短名], caller-uid=…)`，`<gate>` ∈ {`service`, `process`, `restart`, `pre-start`}；后两个恒为 `probe`。明细预算已改为**按 callee 分桶 + 每小时重置**（每包 4 行、全组每小时 40 行）——旧口径"进程内累计 20"会把夜间静默期的明细整段挤掉。**安装行基线随之从 `3 checkpoint(s)` 变为 `5 checkpoint(s)`**，跨版本比对数时注意。
- stopped 标记的清除点：`AMS#addAppLocked`、`ActiveServices#bringUpServiceInnerLocked`（⇒ 点一次图标即恢复）。
- `immobulus_mode_switch_restrict` 实测值包含 `com.google.android.gms`。

### 6.4 结论措辞纪律

本模块的日志与文档在以下场合**只能**写"未验证"，不得写"不需要"：

- 触发面未覆盖（0 样本）；
- 只有一个窗口的阴性结果；
- 静态结论缺少运行时闭合。

一个已被撤回并写入记录的案例：曾据"一夜零触发"判定睡眠退出 nudge 冗余，该结论被撤回——因为连"睡眠模式是否进入过"都没证实，触发面为 0 样本，用它否定兜底逻辑是循环论证。可成立的表述只有一句：**"在本轮观测窗口内触发次数为 0，其触发面与有效性尚未验证。"**

反过来，这条纪律**不禁止**在拿到正向样本时写"有效"：判据是样本，不是措辞。曾被登记为"未验证"的项一旦出现正向观测（例如用户在真机上确认），就应改判，并把改判理由写在原处——见 5.4 第 10 行。

同理，`Close err` 的代价必须**按 code 分级**再下结论：用最优雅的那一类（`err:27`，0.4–1.3 s）去概括全部 `Close err` 是实测踩过的误判，因为 `err:20/25` 的均值是 7–9 s、单次实测最长 148 s。

### 6.5 后续方向

**观测侧**

| 方向       | 内容                            | 状态  |
| -------- | ----------------------------- | --- |
| 探针生命周期管理 | 固化"晋升 / 退役"判据与复核周期，避免观察位无限堆积  | 待办  |
| 结构化诊断导出  | 把摘要行、计数、判定表结果导成一份可机读报告，减少人工分析 | 待办  |

**行为侧**

| 方向                 | 内容与前提                                                                                                           |
| ------------------ | --------------------------------------------------------------------------------------------------------------- |
| P4 触发面扩展           | **评估后不实施**，理由见 5.5。重启条件：整宿观测出现"MCS 死亡 + GMS 未自愈 + 未进睡眠模式"的证据后再设计带门控的触发                                          |
| `setUidState` 缓存分歧 | 唯一可行形态是"强制缓存为 false，再调用 `setUidState(uid, true)` 让方法体完整执行"；只补写缓存无效（提前返回就在该方法内）。当前所有可观测量都表明分歧未发生，**保持不实现**是刻意的选择 |
| 非 GMS 免冻           | 代价远超严格模式语义范围，不在计划内                                                                                              |

**兼容性侧**

- 每次 ROM 大版本升级后，以 `M target(s) absent` 的变化为起点做一次全量复核；
- 新增钩子时同步补齐"到达但未命中"的对应日志，否则该钩子的静默不可解释；
- 对代次漂移的符号一律走"多候选 + `logSkipOtherGeneration`"，不硬编码名字。

**日志冗余审计**

全量清点（按"函数名紧跟左括号"计匹配行）：`log(…)` **146 处**、`logSkip` **43 处**（INFO，同代缺失）、`logSkipOtherGeneration` **15 处**（DEBUG，跨代缺失）、`recordProbe` **10 处**（存在性/签名探针）。合计 **214 处**。

**成本在行数，不在字数**（实测）：LSPosed 每行的固定前缀（时间戳 + bearer + 模块 tag + 序号）约 **161 B**，比多数消息正文还长——一次热重载日志 12,959 B 里，消息正文只占 4,235 B，其余 8,670 B 全是前缀。"把长解释缩短"几乎没有收益（四条最长的解释行合计才 900 B），**并线才是唯一有效的办法**。

| #   | 现象                                                                                                                                                                                                                                                      | 级   | 处置                                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --- | ---------------------------------------------- |
| 1   | **30 分钟多行叠加**：`GMS_TRAFFIC_PROBE_INTERVAL_MS` 与 `WAKE_PATH_HEARTBEAT_MIN_MS` 同为 30 min，同一 tick 上叠加 `gms traffic probe [periodic]` / `broadcast gate: c2dm …` / `broadcast gate: wake-path …` / `wake-path probe: heartbeat …` ≈ **192 行/天**，实机连续窗口内长期全零 | 中   | **已修（两轮）**：① 两条 `broadcast gate:` 在全零时并为一条（192 → 144 行/天）；② 再把 `broadcast gate:` 整体并到 `gms probe [<reason>]` 行尾 ⇒ tick 由 2 行 → **1 行**（≈144 → ≈96 行/天；非全零时也从 3 行降到 1 行）。未做：`wake-path probe: heartbeat` 仍单独成行——它由 `checkWakePath` 现场触发，与定时 tick 不同源；`top=[]` 在 `denied=0` 时恒空但仍打印 |
| 2   | **死标志**：`wakePathDeniedLogged` / `wakePathReachedLogged` 只声明、零读写                                                                                                                                                                                        | 低   | **已删**，原位留注释警告「勿再引入只置一次的布尔哨兵——它与『压根没到达』不可区分」   |
| 3   | `userTable: ensure …` + `userTable: GMS current bgControl=…` 每次调用必出 2 行，而 `current == noRestrict` 时静默 return ⇒ 「无需写入」与「准备写入」外观相同                                                                                                                        | 中   | 候选：无需写入也补一行（本项目口径：不能靠日志没出现反推）。该函数有 4 个调用点      |
| 4   | `userTableReassertInFlight` **非 volatile 且 check-then-set 非原子**                                                                                                                                                                                         | 低   | 候选：`AtomicBoolean.compareAndSet`               |
| 5   | **纯存在性 probe 占 9 行 INFO**：whetstone ×2、socket-teardown ×3、sleep-mode ×2、packet filter、mMessageApp——只答「ROM 有无此方法」，答过一次后不再变                                                                                                                               | 低   | **已修**：并为一行 `probe: <键=值…>`。whetstone 折算成 `res:n/2,decl:n/2`、socket-teardown 合成 `层1+层2+层3`，其余键值原样保留；键名改用 `<SimpleClass>#<method>` 以免并线后失去归属。唯一不再打印的是 `Method.toString()` 的完整签名——探针查找本就锁死参数表，`present` 已等价于「以该签名存在」。**异常读数不并入**（某层不再解析等各自成行） |
| 6   | `Failed to hook GmsObserver` / `Failed to hook GlobalFeatureConfigureHelper` 各有两处、文案完全相同（内层 CNFE 与 `hookPackage` 外层兜底）⇒ 无法区分「类不存在」与「桥接方法缺失」                                                                                                             | 低   | 候选：文案分层                                        |
| 7   | `logSkip(msg, level)` 无论 INFO/DEBUG 都 `hookTargetsAbsent++` ⇒ 安装期 `M target(s) absent` 把 10 条 OS3-only 预期缺失算进「本 ROM 缺失」                                                                                                                                 | 中   | **已修**：删掉双参 `logSkip(msg, level)`，拆成 `logSkip`（INFO，计入 `hookTargetsAbsent`）与 `logSkipOtherGeneration`（DEBUG，计入 `hookTargetsAbsentOtherGeneration`）；摘要行改为 `… M target(s) absent on this ROM, K cross-generation (expected)`。本机读数由 `18 installed / 10 absent` 变为 `18 installed / 0 absent on this ROM, 10 cross-generation (expected)` |
| 8   | 睡眠进入三行叠加：`chain enabled, whitelist size N` 与白名单臂的 `kept GMS …` / `already whitelisted …`（本代不可达，OS3 上会真叠加）                                                                                                                                               | 低   | 观察                                             |
| 9   | `gms traffic probe: chain generation N superseded …` 每次热重载出 1~2 条，是热重载的必然结果而非异常                                                                                                                                                                         | 低   | 保留（解释旧链为何消失）                                   |
| 10  | **安装确认行是最大的一块**：一次热重载 24~26 行 / ≈6 KB（system_server 23 组 + PowerKeeper 7 组，逐组一行）                                                                                                                                                                          | —   | **保留**：这是"哪个钩子是活的"唯一的逐符号证据。砍掉后某组静默失败只能从计数变化反推，与本项目"不能靠日志没出现反推"的口径冲突 |

**第一轮实测收益**（装新版后的热重载，`modules_2026-10-05T23_15_51.594188.log`）：**54 行 / 12,959 B → 36 行 / 9,287 B（−18 行 / −28%）**，合并后只剩一条 `probe:`、一条 `cross-generation …`、一条 `broadcast gate: idle …`。剩下的大头就是第 10 行那 24 条安装确认 + 3 条事件，已无可并之物。

**第二轮（A–E 五项）**：A `gms probe` 与 `broadcast gate` 并成一行、B `UserConfigureHelper#{a/b/c} hooked …` 三行并一行、C `NetdExecutor#execute->…` 收进 `probe:` 行、D `scheduled every 30 min … generation N` 并入探针启动行、E `allowlist loaded: N pkg(s)` 改为 `selected=<bool>`。**稳定态 tick 行 96 → 48 行/天**（含 heartbeat 后 ≈144 → ≈96）；安装面每次少 3 行（B −2 / D −1），E 换语义不省行，**C 净 0 行**——它的价值是语义归类（powerkeeper 域原先零 `probe:` 行，收进来后行数不变，但两个域的探针从此同前缀、同 `<SimpleClass>#<method>` 键名）。

**为什么 24 条安装确认行始终不动**：一次热重载 24~26 行 / ≈6 KB 是最大的一块，但它是"哪个钩子活着"的**唯一逐符号证据**。真正可压的只有"同类、同用途"的成组行（`UserConfigureHelper` 三方法即此例）；把不同用途的钩子也并成一行，等于用计数变化反推静默失败，与本项目"不能靠日志没出现反推"的口径冲突。

### 6.6 收口面与粒度：不自建防火墙

**UID 层收口天然存在**。实测 GMS 在系统自带的各层均已放行：

| 层              | 读法                                                                                                       | GMS 实测                                                        |
| -------------- | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| netd dozable 链 | `dumpsys network_management` 的 `UID firewall dozable rule`（1=ALLOW / 2=DENY），**只在 Doze idle 期间 enabled** | 放行                                                            |
| netpolicy 策略   | `dumpsys netpolicy` 的 `policy=`                                                                          | `4`（ALLOW_METERED_BACKGROUND）                                 |
| 待机桶            | `am get-standby-bucket`                                                                                  | `5 (ACTIVE)`                                                  |
| deviceidle 名单  | `dumpsys deviceidle whitelist`（user / system / system-excidle 三段）                                        | 三段全在                                                          |
| 自启动 AppOps     | `cmd appops get <pkg>` 的 `MIUIOP(10008)`                                                                 | `allow`                                                       |
| 免限名单           | `settings system MILLET_NO_RESTRICT_APP`                                                                 | 含 GMS                                                         |
| 冻结面            | `dumpsys greezer` 的 per-uid 记账                                                                           | `frozen=0s`、不进冻结路径                                            |
| 声明面            | `declaresFcmComponent(GMS)`                                                                              | `true`（声明 c2dm RECEIVE + RECEIVE_DIRECT_BOOT）⇒ 强停防护对 GMS 实际生效 |

**两份"白名单"实测不等价**：设置里看到的「电池优化」= `dumpsys deviceidle whitelist`，真正掐网的是 netd dozable 链，两者交集 41 项、dozable 独有 11 项、白名单独有 12 项。所以**判"某应用能否收到消息"不能只看名单**：某金融类应用在两份名单里都零命中、夜间无进程，仍照样收到推送。

四套互不相同的存储，勿混为一谈：自启动 = AppOps `10008`；电池策略 = powerkeeper `userTable.bgControl`；睡眠网络白名单 = `sleep_mode_network_white_apps`（**V816 上无效**，见 3.8.1）；GMS 限制 = powerkeeper `gms_control`。

**粒度判据（限定了开关能表达什么）**：睡眠断网是整机物理级（直接 `setWifiEnabled(false)` / `setDataEnabled(false)`），**没有应用维度** ⇒「只保 GMS 不保其他应用」在睡眠面上不可实现（按 uid 裁剪必须叠加自建 netd 链，已否决）。省电只能二选一：只保 WiFi，或完全不保、靠 FCM 重连补投。

**3.5.3 之后须重新实测**：现役实现是「ROM 走完整路径 + 下游精确拦截」，旧的「整夜 5 h 40 min 无断档」是 flag 捷径实现的成绩，不能直接沿用到新实现上。

---

## 7. 术语表

| 术语                                         | 含义                                                                                                                                          |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **c2dm**                                   | `com.google.android.c2dm.intent.RECEIVE`，GMS 向应用投递 FCM 消息所用的广播 action                                                                       |
| **GMS / GSF**                              | `com.google.android.gms`（Google 移动服务）/ `com.google.android.gsf`（Google 服务框架，P4 广播的另一个收件人）                                                   |
| **MCS**                                    | GMS 与云端之间的长连接（`mtalk.google.com:5228`，TCP）。心跳区间实测 `[110 s, 1730 s]`，WiFi 下 `230 s`、蜂窝下 `1680 s`                                             |
| **greeze**                                 | `com.miui.server.greeze.GreezeManagerService`，MIUI 的冻结 / 广播策略引擎，本模块在 system_server 的最大接口面                                                   |
| **AurogonImmobulusMode**                   | MIUI 的冻结模式管理器，提供免限集合与快速冻结入口                                                                                                                 |
| **Domestic / International**               | `PolicyManager` 的两个实现，由 region 一次性决定（分派链见 2.3.1）。CN ROM 走 Domestic，`InternationalPolicyManager` 从不实例化                                       |
| **`sCnModel`**                             | `PolicyManagerConfig` 的 `PRIVATE STATIC boolean`（**无 `FINAL`**），`<clinit>` 中由 `miui.os.Build.getRegion()` 判定；`PolicyManager#isCnModel()` 读它 |
| **`mSetForceCnGlobal`**                    | `AurogonImmobulusMode` 的标志，门控"是否在 `restorePolicyManager()` 里按 `isCnModel()` 重算 `mCurrentCNPolicy`"。它不是"强制 CN"开关                             |
| **`mCurrentCNPolicy`**                     | 当前生效的实现编号（`STATE_DOMESTIC` / `STATE_INTERNATIONAL`），`dumpsys greezer` 会打印；本机读数 `1` 即 Domestic 生效                                            |
| **PowerKeeper**                            | `com.miui.powerkeeper`，MIUI 省电服务，独立进程；承载 GMS 网络阻断、待机限制、省电档位与睡眠模式                                                                            |
| **`MILLET_NO_RESTRICT_APP`**               | `Settings.System` 键，PowerKeeper 的"免限名单"；模块把 GMS 写入其中                                                                                        |
| **userTable / bgControl**                  | PowerKeeper 的省电策略表与档位列，取值 `miuiAuto` / `noRestrict` / `restrictBg` / `noBg`                                                                 |
| **scenario**                               | PowerKeeper 场景编译结果；`0` = `isGmsCoreApp + miuiAuto`，`8` = `bgControl = noRestrict`                                                           |
| **Tier.WAKE / Tier.STRICT**                | `moduleAppliesTo` 的两个层级。WAKE 层始终受白名单约束；STRICT 层仅在严格模式下收窄                                                                                    |
| **fail-open / fail-closed**                | 无法确定时放行 / 无法确定时按关闭处理。保护类判定取前者，实验类开关读取失败取后者                                                                                                  |
| **活跃路径 / 防御位 / 只读探针**                      | 钩子的三种性质，见第 0 章                                                                                                                              |
| **哨兵（sentinel）**                           | 一次性 INFO 日志，用于证明某条路径"至少真实跑过一次"；区别于持续计数                                                                                                      |
| **generation**                             | 流量探针链的代际计数（存于 `System.getProperties()`），用于热重载后让旧链自行退役                                                                                       |
| **nudge**                                  | P4 恢复动作的口语说法：向 GMS/GSF 发三条重连广播，会让 GMS 主动拆掉当前 MCS                                                                                            |
| **cutoff**                                 | 睡眠模式下 ROM 关闭 WiFi 与移动数据的那一次动作（`applySleepConfig` 的关网段）                                                                                      |
| **netId**                                  | `GcmNetwork{N X(1)}` 中的 N，ConnectivityManager 的网络 ID；一次开机内单调递增、不复用，是判断"网络对象是否被销毁重建"的硬证据                                                     |
| **`Close err:N`**                          | GMS 内部连接关闭原因码；`27` ≈ 默认网络切换的优雅替换，`20`/`25` 成对表示同一网络对象的读/写两侧失效，`6` = 心跳超时，`26` 最贵（实测 148 s）                                                  |
| **`AmlMiuiThirdPartScorer`**               | MIUI 的 WiFi 评分器，位于 `/system_ext/framework/miui-wifi-service.jar`（不在 system_server classpath 上）；以 `mLegacyIntScore < 50` 判不可用                |
| **`notifySwitchNetworkByOtherStrategies`** | ROM 中绕过分数通道的第二切换出口，模块未铰住；`type = 1003` 为长期观察项                                                                                               |
| **visited / `s_has_visited`**              | PowerKeeper 省电策略自动升格的熔断集合，跨重启持久化（见附录 C）                                                                                                     |

---

## 附录 A：钩子清点

| 域             | 组   | 目标                                                                                        | 方向                               |
| ------------- | --- | ----------------------------------------------------------------------------------------- | -------------------------------- |
| system_server | 投递  | `AMS#broadcastIntentWithFeature`                                                          | 补 flag + 临时豁免                    |
| system_server | 投递  | `BroadcastQueueModernStubImpl#checkApplicationAutoStart`                                  | → true                           |
| system_server | 投递  | `GreezeManagerService#isRestrictReceiver`                                                 | → false + thawUidAsync           |
| system_server | 投递  | `GreezeManagerService#isNeedCachedBroadcast`                                              | → false                          |
| system_server | 投递  | `GreezeManagerService#isAllowBroadcast`                                                   | → true                           |
| system_server | 投递  | `DomesticPolicyManager#deferBroadcast`                                                    | → false（仅 4 个 CN 重连动作；c2dm 不再豁免） |
| system_server | 投递  | `GreezeManagerService#deferBroadcastForMiui`                                              | → false                          |
| system_server | 冻结  | `AurogonImmobulusMode#isNoRestrictApp`                                                    | → true                           |
| system_server | 冻结  | `AurogonImmobulusMode#isNoRestrictFreezeable`                                             | → false                          |
| system_server | 冻结  | `AurogonImmobulusMode#triggerQuickFreeze`                                                 | 跳过                               |
| system_server | 冻结  | `PolicyMaker#isAllowFreeze`                                                               | 跳过                               |
| system_server | 网络  | `DomesticPolicyManager#isRestrictNet`                                                     | → false（GMS + 实验档已勾选应用）           |
| system_server | 网络  | `GreezeManagerService#udpPackageRestrict`（allow=true）                                     | 跳过                               |
| system_server | 网络  | `GreezeManagerService#triggerGMSLimitAction`                                              | → false / 清标志位                   |
| system_server | 网络  | `GreezeManagerService#updateGmsNetStatus`                                                 | → false                          |
| system_server | 网络  | `InternationalPolicyManager#isPushApp`                                                    | → false（CN ROM 不执行）              |
| system_server | 清理  | `ProcessCleanerBase#isForceStopEnable`                                                    | → false                          |
| system_server | 清理  | `ProcessPolicy#getWhiteList`                                                              | 追加 GMS                           |
| system_server | 清理  | `ListAppsManager` 构造器 / `#isInWhiteList`                                                  | 移除黑名单 / 加白名单                     |
| system_server | 清理  | `AwareResourceControl` 构造器                                                                | 移除 GMS                           |
| system_server | 清理  | `GlobalFeatureConfigureHelper#getDozeWhiteListApps`                                       | 追加 GMS                           |
| system_server | 睡眠  | `MiuiNetworkPolicyManagerService#setSleepModeWhitelistUidRules`                           | 注入 GMS uid（V816 上为防御位）           |
| system_server | 睡眠  | `MiuiNetworkPolicyManagerService#enableSleepModeChain`                                    | 日志 + 条件 nudge（V816 上为防御位）        |
| system_server | 网络  | `WifiManager#setWifiEnabled`（`applySleepConfig` 栈内）                                       | 实验开关：保留 WiFi                     |
| system_server | 网络  | `CommonAdapter#setDataEnabled`（`applySleepConfig` 栈内）                                     | 实验开关：保留移动数据                      |
| system_server | 网络  | `Settings$Secure#getIntForUser`（`key_open_earthquake_warning`）                            | 降级路径                             |
| system_server | 网络  | `AmlMiuiThirdPartScorer#notifyScoreAndIsUsable`                                           | 实验开关：抬 WiFi 分数                   |
| system_server | 网络  | `CommonAdapter#addPowerSaveWhitelistApps`                                                 | 微信 doze keepout                  |
| system_server | 闹钟  | `AlarmManagerServiceStubImpl#checkAlarmIsAllowedSend`                                     | 被拒的 GMS 闹钟 → true                |
| system_server | 探针  | `ActivityManagerServiceImpl#checkWakePath`（Gate-W）                                        | 只读计数                             |
| system_server | 探针  | `WakePathChecker#checkBroadcastWakePath`（Gate-B）                                          | 只读计数                             |
| system_server | 探针  | `AurogonImmobulusMode#mMessageApp`                                                        | 只读快照                             |
| system_server | 探针  | `ConnectivityManager#updateSleepModeUidRule` / `#enableSleepModeChain`                    | 存在性                              |
| system_server | 探针  | `FilterEnablePolicy#isSupportPacketFilter`                                                | 存在性 + 取值                         |
| system_server | 探针  | `doDesSocketForUid` ×3 层                                                                  | 只读计数                             |
| system_server | 探针  | `TrafficStats` per-uid                                                                    | 只读采样                             |
| powerkeeper   | 网络  | `NetdExecutor#initGmsChain` / `#setGmsDnsBlockerState` / `#setGmsChainState` / `#execute` | 改写参数 / 跳过                        |
| powerkeeper   | 网络  | `GmsObserver` 系列（11 个方法 + 内部类 `googleNetworkDisconnect`）                                  | 强制 false / true / 跳过             |
| powerkeeper   | 待机  | `AppStandbyController#setUidState`                                                        | GMS → allow=true                 |
| powerkeeper   | 配置  | `UserConfigureHelper#getNoRestrictApps` + 所有 writer                                       | 追加 GMS / 重断言                     |
| powerkeeper   | 配置  | `ActiveStateController#dealNoRestrictApp`                                                 | 校验并修复                            |
| powerkeeper   | 配置  | `PowerKeeperAppConfigure#fillScenarioContent`                                             | scenario 0 → 8                   |

## 附录 B：常量与阈值

| 常量                                                                | 值                                                                                                   | 用途                                    |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------- |
| `TAG`                                                             | `HyperGreeze`                                                                                       | 日志统一前缀                                |
| `ACTION_REMOTE_INTENT`                                            | `com.google.android.c2dm.intent.RECEIVE`                                                            | c2dm 投递                               |
| `ACTION_MESSAGING_EVENT`                                          | `com.google.firebase.MESSAGING_EVENT`                                                               | FCM 组件探测                              |
| `FCM_MESSAGING_SERVICE_CLASS`                                     | `com.google.firebase.messaging.FirebaseMessagingService`                                            | FCM 组件探测                              |
| `FCM_IID_RECEIVER_CLASS`                                          | `com.google.firebase.iid.FirebaseInstanceIdReceiver`                                                | FCM 组件探测                              |
| `GMS_PACKAGE_NAME` / `GMS_PERSISTENT_PROCESS_NAME`                | `com.google.android.gms` / `com.google.android.gms.persistent`                                      | GMS 判定                                |
| `CN_DEFER_BROADCAST`                                              | 4 个 GMS 重连/心跳/连接状态 action（`GCM_RECONNECT`、`gcm.DISCONNECTED`、`gcm.CONNECTED`、`gcm.HEARTBEAT_ALARM`） | 免延迟                                   |
| `RECOVERY_BROADCAST_ACTIONS`                                      | `GCM_RECONNECT` / `GTALK_HEARTBEAT` / `MCS_HEARTBEAT`                                               | P4 恢复                                 |
| `MILLET_NO_RESTRICT_APP_KEY`                                      | `MILLET_NO_RESTRICT_APP`                                                                            | 免限名单                                  |
| `USER_TABLE_URI`                                                  | `content://com.miui.powerkeeper.configure/userTable`                                                | PowerKeeper 省电策略表                     |
| `BG_CONTROL_NO_RESTRICT`                                          | `noRestrict`                                                                                        | 省电档位                                  |
| `SCENARIO_MUI_AUTO_GMS` / `SCENARIO_NO_RESTRICT`                  | 0 / 8                                                                                               | 场景改写                                  |
| `SLEEP_CONTROLLER_CLASS`                                          | `com.miui.powerkeeper.statemachine.PhoneSleepModeController`                                        | 睡眠控制器                                 |
| `SLEEP_APPLY_METHOD` / `SLEEP_RESTORE_METHOD`                     | `applySleepConfig` / `restoreSleepConfig`                                                           | 栈帧闸门                                  |
| `SLEEP_EARTHQUAKE_KEY`                                            | `key_open_earthquake_warning`                                                                       | 降级路径                                  |
| `WIFI_SCORER_CLASS`                                               | `com.android.server.wifi.global.global_scorer.AmlMiuiThirdPartScorer`                               | WiFi 评分器                              |
| `WIFI_SCORER_NOTIFY_METHOD` / `WIFI_SCORER_SCORE_FIELD`           | `notifyScoreAndIsUsable` / `mLegacyIntScore`                                                        | 钩点与字段                                 |
| `WIFI_SCORE_USABLE_MIN`                                           | 50                                                                                                  | ROM 自己的判据值（**不是本模块的可调旋钮**）            |
| `WIFI_SCORE_CLAMP_TARGET`                                         | 51 = `WIFI_SCORE_USABLE_MIN + 1`                                                                    | 钳位写入目标                                |
| `WIFI_SCORE_CLAMP_LOG_INTERVAL_MS`                                | 30 000 ms（30 s）                                                                                     | 钳位/放行两路日志节流（各自独立计数）                   |
| `WIFI_SCORER_CLASS_LOADER_SERVICES`                               | `MiuiWifiService` / `AmlConnectivityService` / `MiuiNetPathOptimizerService`                        | 借 binder 取 ClassLoader 的候选服务          |
| `Prefs.KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED`                       | `wifi_weak_signal_switch_relaxed`                                                                   | 主开关（默认关）                              |
| `Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR`                                | `wifi_weak_signal_floor`；`WIFI_WEAK_SIGNAL_FLOORS = {45, 40, 35, 30}`，默认 45                         | 子选项：保留 WiFi 的最低评分                     |
| `Prefs.KEY_SLEEP_KEEPALIVE`                                       | `sleep_keepalive`                                                                                   | 睡眠保活：保 WiFi（默认关）                          |
| `Prefs.KEY_SLEEP_KEEPALIVE_DATA`                                  | `sleep_keepalive_data`                                                                              | 睡眠保活：保移动数据（默认关，与上一个对等、互不依赖）                 |
| `Prefs.KEY_WECHAT_DOZE_KEEPOUT`                                   | `wechat_doze_keepout`                                                                               | 微信免冻剔除（拦 `addPowerSaveWhitelistApps`） |
| `FCM_CACHE_TTL_MS` / `FCM_CACHE_MAX`                              | 5 min / 256                                                                                         | FCM 探测缓存                              |
| `ALLOWLIST_STALE_MS`                                              | 10 000 ms（10 s）                                                                                     | 白名单陈旧阈值，也是失败退避封顶                      |
| `ALLOWLIST_FAILURE_BACKOFF_BASE_MS`                               | 1 000 ms（1 s）                                                                                       | 失败退避基数（1 s × 2^k）                     |
| `ALLOWLIST_RELOAD_MIN_MS`                                         | 500 ms                                                                                              | `requestAllowlistReload()` 的合流节流      |
| `ALLOWLIST_REGISTER_RETRY_MS` / `ALLOWLIST_REGISTER_MAX_ATTEMPTS` | 1 000 ms（1 s） / 120                                                                                 | 接收器安装重试                               |
| `GMS_TRAFFIC_PROBE_INTERVAL_MS`                                   | 30 min                                                                                              | 流量采样周期                                |
| `GMS_TRAFFIC_NUDGE_RESAMPLE_MS`                                   | 15 s                                                                                                | nudge 后复采样延迟                          |
| `WAKE_PATH_HEARTBEAT_MIN_MS`                                      | 30 min                                                                                              | Gate-W 心跳节流                           |
| `WAKE_PATH_DETAIL_LIMIT`                                          | 10                                                                                                  | Gate-W / Gate-B 明细行数上限                |
| 临时豁免参数                                                            | `(pkg, 102, "GOOGLE_C2DM", 2000)`                                                                   | 2 s 省电豁免                              |
| 本机 `cloud_min_rssi_for_data_switch_5GHzwifi`                      | −72（2.4 GHz 为 −73）                                                                                  | ROM 的弱信号切蜂窝阈值；`cloud_*` 前缀意味着可能被云端覆盖  |

## 附录 C：ROM 侧参考行为——「电量与性能」省电策略自动升格

本节与模块无关，记录 PowerKeeper 自己的行为：一个应用在省电策略里被设成「智能限制」（`miuiAuto`）后，**会不会自己跳到「无限制」（`noRestrict`）**。素材与 3.7 同源，即 `<PowerKeeper-dis>`。

**结论：会跳，但只在「这个 userId+pkg 第一次被读取」时发生一次。** 之后永久保持当前值，用户手动改的选择不会被覆盖，除非这个组合退出 visited 集合。

**存储与默认值**：`userTable` 建表 SQL 明写默认值，且安装应用时 `PowerKeeperConfigureManager$5.onPackageAdded` 会立即 [`2675d8`] 以 `bgControl="miuiAuto"` 建行：

```sql
CREATE TABLE IF NOT EXISTS userTable (
  _id INTEGER PRIMARY KEY AUTOINCREMENT, userId INTEGER NOT NULL DEFAULT 0,
  pkgName TEXT NOT NULL, lastConfigured INTEGER,
  bgControl TEXT NOT NULL DEFAULT 'miuiAuto', bgLocation TEXT,
  UNIQUE (userId, pkgName) ON CONFLICT REPLACE );
```

四档取值与对外名由 `getInterfaceConfigureValue` [`26dca8`] / `getUserConfigureValue` [`26dd18`] 成对映射：`miuiAuto↔miui_auto`、`noRestrict↔no_restrict`、`restrictBg↔restrict_bg`、`noBg↔no_bg`。**ROM 侧「智能限制」确实就是 `miuiAuto`**，并且是所有已安装应用的出厂默认值。

**唯一的自写点**：`setPowerSaveAppConfigure` 全 ROM 仅 2 个调用点：`200080`（Binder 外部入口 `PowerKeeperManager`）与 `26da36`——后者位于 **`getPowerSaveAppConfigure` 内部** [`26d8ac`]，即"读"的时候偷偷写。三次反闸门串起来才是条件：

```
0090  sHasVisited.contains(<userId><pkg>)   → 0096 if-nez  ⇒ 已访问过 ⇒ 跳 00c2，不升级
009a  getBgControl().equals("miuiAuto")     → 009e if-eqz  ⇒ 不是 miuiAuto ⇒ 跳 00c2
00aa  PowerManager.isIgnoringBatteryOptimizations(pkg)
                                            → 00ae if-eqz  ⇒ 未免电池优化 ⇒ 跳 00c2
00ba  bundle.putString("AppConfigure", "no_restrict")
00bd  setPowerSaveAppConfigure(bundle)      ← 写入 userTable，升级到无限制
00c2  sHasVisited.add(<userId><pkg>) → storeList()   ← 无论是否升级，读一次即打标
```

三个条件全部成立才会改写：**① 未 visited ∧ ② 当前是 `miuiAuto` ∧ ③ 该包处于电池优化豁免名单（deviceidle 白名单）**。

**visited 的持久化与唯一的重置路径**：`sHasVisited` 不是内存缓存——`<clinit>` [`26dd88`] 从 `SimpleSettings$Misc.getStringForUser(ctx, "s_has_visited", …)` 用 Gson 反序列化读入（日志 `init from database => …`），`storeList()` [`26de4c`] 再 JSON 存回（日志 `dump to database -> …`）⇒ **跨进程重启、跨 reboot 都保留**。唯一把条目移出集合的地方是 `PowerKeeperConfigureManager$5.onPackageRemoved` [`2676ba` → `2676dc` remove → `storeList`]，即**卸载并重装该应用会让这次自动升级重新获得一次机会**。

**版本更新不摘标记**：链路为 `PowerKeeperPackageManager$MyPackageMonitor extends com.android.internal.content.PackageMonitor`，覆写 `onPackageAdded` [`2e201c`]→合成桥 `d` [`2e2548`]→`addPackage`、`onPackageRemoved` [`2e206c`]→合成桥 `g` [`2e2578`]→`removePackage`、`onPackageRemovedAllUsers` [`2e2088`]→`j`+`g`、`onPackageUpdateFinished` [`2e20b0`]→`d`→`addPackage`。**未覆写 `onPackageUpdateStarted`**，而 `onPackageUpdateFinished` 被覆写成 `addPackage` ⇒ 基类在 `EXTRA_REPLACING=true` 时走的是 update 分支，覆盖安装不落到 `onPackageRemoved`。`addPackage` 内部 [`2e2870`~`2e28e8`] 也有一处 `notifyPackageRemoved`，但条件是**该包的 uid 值发生变化**（`00bd` 取旧 uid 比较），覆盖安装 uid 不变 ⇒ 不触发。

**判定表**

| 场景                 | 是否自动升到「无限制」                   | 依据                                                       |
| ------------------ | ----------------------------- | -------------------------------------------------------- |
| 新装应用，从未被任何客户端读过配置  | **会**，在第一次被读的瞬间               | 三个条件齐备                                                   |
| 已读过一次（无论那次是否真的升级）  | 不会，`00c2` 处直接跳过               | visited 已持久化，无法再用"没跳"反推条件不成立                             |
| 用户在 UI 手动改回「智能限制」后 | 不会自动改回                        | 手动改走 Binder 的 `set`，**不 touch `sHasVisited`** [`26daac`] |
| 卸载应用后重装            | **会**，`onPackageRemoved` 摘掉标记 | `2676ba`                                                 |
| **应用内版本更新**（覆盖安装）  | **不会**，不摘标记                   | 见上                                                       |
| 应用不在电池优化豁免名单       | 永远不跳                          | 条件 ③ 是硬门槛                                                |

**与另一套机制的区别（勿混）**：这里的 `userTable.bgControl` 是**电量与性能里的省电策略档位**；`DeviceIdleController$1` 的 `sAlwaysWhiteApps` 写的是 **deviceidle.xml（AOSP「电池优化」未优化名单）**。两套互不相干，但条件 ③ 读的正是后者——**这也是为什么"ROM 自己把某应用加进电池优化白名单"会连带把它的省电策略顶到无限制**。

**未闭环点**：整套符号在 `miui-services.jar`(2 dex) / `services.jar`(4 dex) / `miui-framework.jar` 的 dex 字符串池里**零命中** `getPowerSaveAppConfigure`；`PowerKeeper.apk` 内部除 AIDL 桩（`IPowerKeeper$Proxy` `1fdd38`）与 `PowerKeeperManager` 转发外也无调用者 ⇒ **调用方不在系统层，是上层 App（设置 / 手机管家的省电详情页之类）**。触发时机未做闭环，待补的设备侧验证：

```sh
adb logcat -v time -s PowerSaveConfigureManager        # 抓 init from database / return configure / setPowerSaveAppConfigure success pkg=…
adb shell settings get system s_has_visited            # 看目标包是否已在集合里
adb shell dumpsys deviceidle whitelist                 # 条件 ③ 是否成立
```

## 附录 D：本次整理所依据的验证来源

本文于 2026-10-04 对既有记录做了一次结构化重整。整理时逐项核对了源码标识符与常量，并按下述来源判定每条结论的存废。

**源码（本次逐项核对）**

- `HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/Hooker.kt`（4706 行，模块唯一 Xposed 入口）
- 同目录 `Prefs.kt`（配置键与 pending 机制）、`UnsafeUtils.kt`（`static final` 写入）
- `HyperFCMLive/src/main/AndroidManifest.xml`、`META-INF/xposed/scope.list` 与 `java_init.list`（注入域）

**ROM 静态取证**

| 素材                                                                                 | 用途                                              |
| ---------------------------------------------------------------------------------- | ----------------------------------------------- |
| `<PowerKeeper-dis>`（本机 PowerKeeper `classes.dex` dexdump）                          | 3.7.3 同名方法排查、附录 C 的字节码偏移                        |
| 从设备直接 pull 的同名 dex（与上者类名/签名逐项一致）                                                   | 交叉校验                                            |
| `<PowerKeeper-OS3-dis>`（OS3 PowerKeeper 带指令体 dexdump）                              | 3.8.6 跨代核对                                      |
| `<OS3 miui-services.jar>`                                                          | 3.8.6 跨代核对                                      |
| `miui-services.jar`(2 dex) / `services.jar`(4 dex) / `miui-framework.jar` dex 字符串池 | 附录 C 的调用方排查                                     |
| `/system_ext/framework/miui-wifi-service.jar`                                      | `AmlMiuiThirdPartScorer` 的归属确认                  |
| `/system/system_ext/framework/miui-services.jar` → `classes2.dex` 带指令体 dexdump     | 2.3.1 的策略分派链、`sCnModel` 来源、`force_cn_global` 门控 |

greeze 的全部类集中在 `miui-services.jar` 的 `classes2.dex` 中，取该 dex 即可，无需解其余 dex。

**运行时取证**

`dumpsys greezer` / `netpolicy` / `deviceidle` / `network_management` / `usagestats` / `wifi`；`dumpsys activity service com.google.android.gms/.gcm.GcmService`；`settings get`（global / system / secure）；`cmd wifi status`；`am get-standby-bucket`；`cmd appops get`；`logcat`（含 `MIPOWERHALSERVICE-NETLINK` 小时级流量采样）；对 `/dev/socket/dnsproxyd` 的 NUL 分帧 socket 探针。

**LSPosed 导出日志**

`modules_*.log`（`/sdcard/Download/…/log/`，文件名自带导出时刻）——夜间取证的唯一起点。

**样本窗口**

S1–S9，详见 5.3。S7 / S8 的分析脚本与 S6 的 GcmService dump 均为本地临时产物（未纳入版本库），本文不引用其路径与命名。

**时间信息的记录约定**

本文只保留"文档整理日期"这一处绝对日期，取证与验证的具体日历日期、钟点一律不记录：样本窗口按相对次序编号（是否同日、日间或夜间），日志片段里的绝对时间戳换算为窗口内相对偏移（`T+…`），时长则照实保留。后续补充素材时沿用同一约定。

**本次整理中删除或降级的内容**

- 删除：全部第一人称调试口吻、同日自我修正的过程记录、已被后续证据推翻的中间结论（如"抖动不丢推送""c2dm 走不到 defer""覆盖安装会重置熔断""充电 ⇒ 不入睡"）。
- 删除：已随代码删除的功能（`sleep_keepalive_charging` 及其 `readChargingState()` / `chargingGateBlocks()`、`wechat_battery_shield`、`PowerSaveConfigureManager#setPowerSaveAppConfigure` 钩点、`/proc/net/tcp` 扫描、两个只声明零读写的布尔哨兵）。
- 降级为防御位并附哨兵：旧 per-uid 睡眠链（3.8.5）。
- 移入"已排除的方案"（5.5）：上述删除项逐条给出排除依据，供后来者查阅，不作为既有能力。
- 标注为未验证：`wifi_weak_signal_floor` 各档位（结论 11）、`type = 1003` 旁路（结论 12）、充电夜的评分—报错关联（结论 13）。
- 改判为有效（2026-10-05，附录 D 之后补录）：睡眠保活本体（结论 10）——原判"有效性未验证"依据的是零样本的阴性推理，用户已在真机上确认其生效，改判理由写在 5.4 第 10 行的表后注。

**后续补录：2.3.1 策略分派链**

2.3.1 的结论来自对 `miui-services.jar` 中 `classes2.dex` 的 `dexdump -d` 静态取证，与 `dumpsys greezer` 的 `mCurrentCNPolicy` / `mSetForceCnGlobal` 读数、`getprop`（`ro.miui.region`、`ro.debuggable`、`ro.build.type`）交叉核对。取回的 jar 与 dexdump 产物为本地临时文件，未纳入版本库。

`dumpsys greezer force_cn_global set <N>` **仅在静态层面判读，未在设备上执行**：它会改动整机 greeze 的运行时策略状态，属于未确证的行为改动。本文对该命令的两条结论（本机被 `IS_DEBUGGABLE` 挡掉、即便可达也指定不了实现）均由字节码直接得出，不依赖执行结果。
