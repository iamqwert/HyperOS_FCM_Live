# HyperFCMLive：Hook 实现与诊断方法技术文档

> 面向维护者与二次开发者。本文描述模块**当前实际存在**的钩子、它们的判据来源，以及配套的观测/取证体系。

---

## 0. 文档定位

| 维度   | 说明                                                                      |
| ---- | ----------------------------------------------------------------------- |
| 描述对象 | `Hooker.kt`（模块唯一 Xposed 入口）及其依赖：`Prefs`、`UnsafeUtils`、`hiddenapi:stubs` |
| 描述层级 | 方法级钩子、判定极性、数据来源、失效模式                                                    |
| 不覆盖  | Compose UI、主题引擎、更新检查、licensing                                          |
| 证据来源 | 源码注释中的字节码偏移结论、ROM 静态取证、运行时 `dumpsys`、LSPosed 导出的 `modules_*.log`        |

阅读前需要接受一条贯穿全文的原则：**本模块大量钩子在测试机上是"零触发"的**。它们按"防御位"安装——ROM 尚未真正做出限制动作时它们不改变任何行为。因此本文对每个钩子都会标注它是**活跃路径**、**防御位**还是**只读探针**，这三者不能混为一谈，也不允许从零触发反推出"无用"。

> **本文的脱敏约定**：涉及具体设备、网络环境与使用者的部分一律改写为类别化描述或中性占位，不影响判读，也请补充新素材时沿用：
>
> - 取样设备只标机型代号与 OS 代次；不写序列号、`android_id`、BSSID 与接入点名称（接入点名称会暴露地理位置）。
> - **第三方应用一概不写真名与真包名**，改用类别（如"某社交应用"）或占位包名（如 `<示例包名>`），因为应用组合本身即是使用者画像。
> - 例外只有三类，它们属于模块自身的公共接口而非使用者信息：Google FCM/GMS 组件（如 `com.google.android.gms`）、系统与 ROM 服务（如 `com.miui.powerkeeper`、`com.xiaomi.xmsf`）、以及已随 UI 文案对外公开的实验对象（微信）。
> - 本地取证素材目录写占位路径 `<evidence>/…`；改写不得破坏技术结论，故原始行号/偏移/参数方向一律原样保留。

---

## 1. 背景：问题域建模

### 1.1 FCM 的投递链

FCM 在设备上不是一条长连接直达应用的链路，而是一次**跨进程的广播唤醒**：

```
云端 → GMS 长连接(MCS) → GMS 进程 → c2dm 广播(ACTION_REMOTE_INTENT)
     → system_server AMS 广播队列 → 目标应用 receiver → 应用自行拉取/展示
```

关键点在于：**GMS 只负责把广播发出去**，能不能叫醒目标应用、广播会不会被排队延后、目标进程是否处于冻结态，全部由 system_server 与 MIUI 私有服务决定。因此"推送收不到"在绝大多数情况下不是连接问题，而是**投递链上某个判定点返回了否**。

### 1.2 ROM 侧的四个拦截面

HyperOS 在这条链路上叠加了四组彼此独立的策略，模块的设计正是按这四组划分的：

| 面              | 归属                                                                                                      | 典型表现                                             |
| -------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| **A. 广播投递**    | `ActivityManagerService` / `BroadcastQueueModernStubImpl` / greeze 广播门控                                 | 广播被延迟（defer）、被判为不允许、被缓存到解冻后再投递；目标处于 stopped 态收不到 |
| **B. 冻结与网络策略** | `GreezeManagerService` / `AurogonImmobulusMode` / `PolicyMaker` / `DomesticPolicyManager`               | uid 被冻结、socket 被销毁、UDP 包过滤被下发、网络限制标记被置位          |
| **C. 清理与自启动**  | `ProcessCleanerBase` / `ProcessPolicy` / `ListAppsManager` / `AwareResourceControl`                     | 进程被强制停止、进入黑名单、被剥夺数据网络                            |
| **D. 省电与网络阻断** | `com.miui.powerkeeper`（`NetdExecutor` / `GmsObserver` / `AppStandbyController` / `UserConfigureHelper`） | GMS 专属防火墙链、DNS 拦截、待机限制、闹钟门控、场景编译结果               |

其中 D 面在**独立进程** `com.miui.powerkeeper` 中，其余三面在 `system_server`。这直接决定了模块的作用域（第 2 章）。

### 1.3 为什么必须在框架层做

- 拦截判定发生在被拦截方**无法观测**的位置：GMS 不知道自己的广播被 defer 了，应用也不知道自己被判为 stopped。

- 所有判定点都是服务端私有方法，没有公开 API 可以撤销。

- 修改 GMS 自身无效且不可行：判定发生在系统侧。模块明确**不 hook GMS、不保活应用进程、不修改 GMS 数据**。

---

## 2. 模块概述与作用域

### 2.1 注入域

`META-INF/xposed/scope.list` 声明两个域，入口类由 `java_init.list` 指定为 `Hooker`：

| 域                      | 回调                                   | 承载的钩子组           |
| ---------------------- | ------------------------------------ | ---------------- |
| `system`               | `onSystemServerStarting`             | A、B、C 面 + 全部只读探针 |
| `com.miui.powerkeeper` | `onPackageReady`（仅 `isFirstPackage`） | D 面              |

这个划分不是随意的：`NetdExecutor` 与 `GmsObserver` 只在 PowerKeeper 进程内被实例化，在 system_server 里根本解析不到类，反过来 greeze 系列只在 system_server。强行合并会导致大量 `ClassNotFoundException`，也会让"缺失目标"计数失去意义。

### 2.2 生命周期

```
onSystemServerStarting ──▶ hookSystemServer(classLoader) ──▶ logSummary("system_server")
onPackageReady         ──▶ hookPackage(pkg, classLoader)  ──▶ logSummary(pkg)
onHotReloading         ──▶ 保存 (pkg, classLoader) 到 savedInstanceState，返回 true
onHotReloaded          ──▶ 逐个 unhook 旧 handle ──▶ 重跑完整安装序列
```

热重载是**完整支持**的（libxposed API 102）：`onHotReloaded` 会显式 unhook 所有旧 handle 再重新安装，因此不需要重启。

### 2.3 作用域的三层含义

"作用域"在本模块里有三个容易混淆的层次，必须分开讨论：

1. **进程作用域**：钩子装在哪个进程（2.1）。
2. **配置作用域**：钩子是否受用户白名单/严格模式约束（第 3 章）。
3. **生效面作用域**：钩子作用于 GMS 自身、目标应用，还是整机策略表（第 4 章 P4 类副作用）。

第三层常被忽略：部分保护是以**改写整机策略表**的形态落地的（睡眠网络白名单、`ProcessPolicy#getWhiteList`、`MILLET_NO_RESTRICT_APP`），它们天然不受白名单约束，也不应该受约束——否则就违反了"GMS 恒定受保护"这条基线。

---

## 3. 配置作用域：两个谓词与它们的差异

### 3.1 跨进程配置通路

system_server 无法读取模块私有文件（SELinux MLS 类别限制），按需 ContentProvider 查询又不可靠，因此使用 libxposed 的**远程偏好**作为唯一真相源：

```
UI 编辑
  → 本地镜像 (fcmlive_allowlist_cache)        // 仅用于列表秒开与"待推送"标记
  → 单线程 executor 同步 commit 到远程 prefs   // GROUP_CONFIG
  → 广播 ACTION_ALLOWLIST_CHANGED ×3 (0 / 400ms / 1500ms)
  → system_server 的 BroadcastReceiver（独立 HandlerThread "fcmlive-allowlist"）
  → 重装 sAllowlist + sStrictMode
```

三处细节值得记住：

- **广播发三次**：接收器在开机早期由重试循环安装（`installAllowlistReceiverAsync`，最多 120 次 × 1s）。用户在那个窗口内改配置，前两次会丢，第三次补上。这解释了"改了没生效要重开一次"这类历史问题的成因。
- **写失败会保留 pending 标记**（`hasPendingPush`），下一次绑定时把镜像推上去，而不是反过来被旧的远程值覆盖。历史上曾发生过"改了又被静默回滚"。
- **写入用 `commit()` 而非 `apply()`**：广播不能跑在它所宣布的值之前。

### 3.2 生效范围谓词：`moduleAppliesTo(pkg, tier)`

```kotlin
moduleAppliesTo(pkg, tier) =
    allowlist.isEmpty() || allowlist.contains(pkg) || pkg ∈ {GMS, GMS.persistent}
    || (tier == STRICT && !strictMode)
```

两层 tier 的差异是**刻意的**，对应 HELP §4 / §5：

| tier              | 覆盖的门                                                                              | 白名单何时生效                          |
| ----------------- | --------------------------------------------------------------------------------- | -------------------------------- |
| `Tier.WAKE`       | `checkApplicationAutoStart`、`isRestrictReceiver`、`isNeedCachedBroadcast`、`AMS#broadcastIntent*` | **始终**：名单非空时未勾选应用就拿不到这几项           |
| `Tier.STRICT`     | `isAllowBroadcast`、`isPushApp`、`isForceStopEnable`                                 | **仅严格模式**：默认对全部应用放行，严格模式才收回到勾选的应用 |

两条不变式：

- 两层都是 fail-open（空名单全放行），与"一个都不勾选 = 全部放行"一致；
- 两层都有 GMS 恒定豁免，保证 GMS 自身永远不受白名单影响（否则非空名单会让唯一没有 caller 校验的调用点把 GMS 自己挡在外面）。

2026-10-02 起，原先的 `shouldApply`（≡ `Tier.STRICT`）与 `shouldWake`（≡ `Tier.WAKE`）合并为这一个带 `tier` 参数的函数，**行为未变**：两者原本长得几乎一样，真实差异（哪一层忽略 `strictMode`）只存在于调用点，靠读函数名极易记错；现在差异写在调用点上（`Tier.WAKE` / `Tier.STRICT`），且实现只有一份。

严格模式的**实际收权面比名义上小**：`Tier.STRICT` 名义上有 3 个调用点，但在 CN ROM 上 `InternationalPolicyManager` 从不实例化（见 6.1），真实收权面只有 `isAllowBroadcast` 与 `isForceStopEnable` 两处。这一点在排查"开了严格模式为什么还有干预"时必须先说清。

### 3.3 开局 fail-open 窗口

`hookAllowlist()` 在装载时**同步**读一次远程 prefs。成功路径在**内容变化**时打一条 INFO（`allowlist loaded: N pkg(s), strict=…`），因此日志里能区分"严格模式已生效"与"还没读到名单"。失败路径保留 ERROR 日志，并按**指数退避**重试（1s 起步、×2、封顶 `ALLOWLIST_STALE_MS` 10s）——stale 判断读 `sAllowlistFreshMs`（上次成功时刻），`sAllowlistReadMs` 保持真实尝试时刻、只用于 `requestAllowlistReload()` 的 500ms 节流，因此持续失败不会形成忙循环。窗口内仍表现为偏松（全放行），不会丢推送。

---

## 4. 实现机制

### 4.1 通用机制

| 机制                            | 作用                                                                                                                          |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `hookE(Executable)`           | 所有钩子的唯一入口，递增 `hooksInstalled`；API ≥ 102 时调用 `setId(toGenericString())`，使同一目标在热重载时收敛为一条活钩子而非多条                               |
| `deoptimize(method)`          | 安装后去优化，避免被内联后钩子不进                                                                                                           |
| `skipValueFor(returnType)`    | 按返回类型给出安全零值：基本类型给 `false/0/0L/...`，`void` 与对象类型给 `null`                                                                     |
| `getInvoker(method)`          | 在钩子内调用原方法时使用，避免递归回自己的回调                                                                                                     |
| `UnsafeUtils.setBooleanField` | 绕过 `static final` 写入限制（Android 新版本对 `Field.set` 加了限制）。先尝试常规 `setBoolean`，失败再用 `Unsafe` 按 ART 字段偏移写入；`volatile` 字段走 CAS 字节写入 |
| `isGmsUid(uid)`               | `uid % 100000` 与缓存的 GMS appId 比较；首次调用经 PackageManager 解析并缓存                                                                 |

**回调内的硬约束**（源码注释明确要求，改动时不得违反）：

- 绝不把异常抛回 system_server / PowerKeeper；
- 不阻塞主线程（耗时动作全部投递到 `HandlerThread`）；
- 不用协程；
- 探针类辅助方法必须自行吞掉所有异常。

### 4.2 A 面：广播投递（system_server）

| 钩子                                                       | 判定与动作                                                                                                                                       | 守门               | 性质              |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- | --------------- |
| `AMS#broadcastIntentWithFeature` / `#broadcastIntent`    | 命中 `ACTION_REMOTE_INTENT` 且 caller 是 GMS：补 `FLAG_INCLUDE_STOPPED_PACKAGES`，并为目标包申请 `addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000)` | `Tier.WAKE`     | 活跃              |
| `BroadcastQueueModernStubImpl#checkApplicationAutoStart` | 冷启动路径（有 `ResolveInfo`）：caller=GMS + c2dm → 返回 `true`                                                                                        | `Tier.WAKE`     | 活跃              |
| `GreezeManagerService#isRestrictReceiver`                | 温而冻的 receiver 路径：返回 `false`，并**主动复现原生解冻** `thawUidAsync(uid, 1000, "bc_action")`                                                            | `Tier.WAKE`     | 活跃              |
| `GreezeManagerService#isNeedCachedBroadcast`             | 命中 c2dm → 返回 `false`，避免广播被缓存到解冻后                                                                                                            | `Tier.WAKE`     | 活跃              |
| `GreezeManagerService#isAllowBroadcast`                  | GMS 的 c2dm / CN 重连动作 → `true`（caller 判定优先 callerPkg，回退 callerUid）                                                                                  | `Tier.STRICT`    | 活跃              |
| `DomesticPolicyManager#deferBroadcast`                   | 4 个 CN 重连动作 → `false`；c2dm 不再豁免（2026-10-02，P0）                                                                                                    | 由 `isAllowBroadcast` 承担（见 10.3） | 活跃              |
| `GreezeManagerService#deferBroadcastForMiui`             | 4 个 CN 动作 → `false`                                                                                                                         | 无（CN 队列属 GMS 内部） | 活跃              |

`isRestrictReceiver` 这一处有一段不可替代的历史：早期版本直接短路 `checkReceiverIfRestricted`，跳过了原生路径上的 `thawUidAsync("bc_action")`，结果是广播被投递到一个仍然冻结的进程，无人解冻，GMS 反复重试同一条消息（表现为"No response to broadcast"）。现在的形态是**回答 false 并自己补上解冻**，reason 与调用方 uid 与原生路径一致，greeze 的记账才不会错位。

签名适配：`broadcastIntentWithFeature` 准备了三组候选签名（15/14/13 参）依次尝试，回退到 `broadcastIntent` 时 `intentArgIndex` 从 2 变为 1。caller 识别优先用 `getRecordForAppLOSP`，回退 `getRecordForAppLocked`，两者都没有时降级为 `Binder.getCallingUid()` 反查包名。

### 4.3 B 面：冻结与网络策略（system_server）

| 钩子                                                           | 判定与动作                                          | 性质               |
| ------------------------------------------------------------ | ---------------------------------------------- | ---------------- |
| `AurogonImmobulusMode#isNoRestrictApp(String)`               | GMS → `true`（"在免限集合里"）                         | 防御位              |
| `AurogonImmobulusMode#isNoRestrictFreezeable(String,int)`    | GMS → `false`（"不要冻结"）；仅 OS4 存在                 | 防御位              |
| `AurogonImmobulusMode#triggerQuickFreeze(I,I)`               | GMS uid → 跳过（返回类型安全零值）                         | 防御位              |
| `PolicyMaker#isAllowFreeze(I)`                               | GMS uid → 跳过                                   | 防御位              |
| `DomesticPolicyManager#isRestrictNet(I)`                     | GMS uid → `false`                              | 防御位              |
| `GreezeManagerService#udpPackageRestrict(I,boolean)`         | GMS uid 且 `allow==true` → 跳过                   | 防御位              |
| `GreezeManagerService#triggerGMSLimitAction(Boolean)` / `()` | 有参版强制 `false`；无参版用 Unsafe 清 `mGmsLimitEnabled` | 防御位              |
| `GreezeManagerService#updateGmsNetStatus(Boolean)`           | 强制 `false`                                     | 防御位              |
| `InternationalPolicyManager#isPushApp(String)`               | 调用栈上出现 `isRestrictNet` 时 → `false`             | **CN ROM 上从不执行** |

两处必须记住的**极性**问题，历史上都判错过：

1. `isRestrictNet` 的语义是 `!mMessageApp.contains(pkg)`，即 `mMessageApp` 是**豁免名单**而非限制名单。返回 `true` 意味着"限制这个 uid 的网络"。模块对 GMS 强制 `false` 才是正确的方向。该字段是 `PUBLIC STATIC` 且在 `<clinit>` 中写入，因此反射读一次即可（但冷启动时可能尚未初始化，见 5.6）。
2. `udpPackageRestrict(uid, allow)` 只在 `allow==true` 时跳过。若连同 `allow=false` 一起跳过，会把已下发的过滤规则留在原地，导致 GMS 被**永久**过滤。冻结路径传 `true`、解冻路径传 `false`，方向不能搞反。

`isPushApp` 的调用栈判定用 `StackWalker`（`RETAIN_CLASS_REFERENCE`，要求 API ≥ 34）：命中 `isRestrictNet` 帧且类由 system_server 的 ClassLoader 加载时才改写返回值。这是为了区分"`isRestrictNet` 问我是不是推送应用"与"别处问同一个问题"。

### 4.4 C 面：清理与自启动（system_server）

| 钩子                                                                              | 动作                                              | 守门                                     |
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

`mNoNetworkBlackUids` 的清理带一条诊断日志：既没按包名也没按 uid 命中时，打一条一次性 INFO（`noNetworkBlacklistMismatchLogged`），用来暴露"字段名对了但集合内容不是我们预期的类型"这类代次漂移。

### 4.5 D 面：PowerKeeper 域（独立进程）

#### D1. 网络阻断与待机

| 钩子                                                                                    | 动作                                                                                  |
| ------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `NetdExecutor#initGmsChain(String,int,String)`                                        | 第 3 个参数改写为 `"ACCEPT"`（原为 `REJECT`）                                                  |
| `NetdExecutor#setGmsDnsBlockerState(int,boolean)`                                     | 强制 `false`（不拦截 DNS）                                                                 |
| `NetdExecutor#setGmsChainState(String,boolean)`                                       | 强制 `false`（不开墙阻断）                                                                   |
| `NetdExecutor#execute(int,String,String,Object[])`                                    | `setuiddnsrule` → 仅当第 1 参 uid 为 GMS 时把第 2 参改 `"allow"`（无法解析的 uid 一律放行不改写，防未来 ROM 新增其他 uid 的调用方被静默翻面）；`enablemiuistandby enable` → 直接返回跳过值（第 1 次及之后每 10 次打 `standby-firewall: suppressed` INFO）。另：经 socket 实证本代 ROM 上这两条命令都是死信（见下），钩子为 OTA 防御位 |
| `GmsObserver#updateGmsAlarm` / `#updateGmsNetWork` / `#updateGoogleReletivesWakelock` | 强制 `false`                                                                          |
| `GmsObserver#updateGmsEnabled` / `#updateGmsState` / `#updateGmsInstalled`            | 强制 `false`                                                                          |
| `GmsObserver#disableGms` / `#disableGmsApps`                                          | 整个方法跳过（返回 `null`）                                                                   |
| `GmsObserver#updateFrameworkGmsNetStatus(boolean)`                                    | `true` → `false`                                                                    |
| `GmsObserver#onGoogleReachabilityChanged(boolean)`                                    | 强制 `true`（永远"可达"）                                                                   |
| `GmsObserver#c(GmsObserver,boolean)`                                                  | 混淆桥接方法，第 2 参强制 `true`；装钩成功打 `GmsObserver#c (obfuscated connected-bridge) hooked` 确认行，缺席打 skip 行——跨代漂移（方法改名）不再静默 |
| `GmsObserver$i#googleNetworkDisconnect`（i ∈ 1..8）                                     | 跳过                                                                                  |

`GmsObserver$i` 的扫描是**序号遍历**而非按名字定位：内部类序号在不同代次会漂移，代码遍历 1..8 并对声明了 `googleNetworkDisconnect` 的那个安装钩子。这是对混淆的唯一可行对策——名字不可依赖，结构可以。

`enablemiuistandby enable` 的跳过带节流日志（首次命中 + 之后每 10 次，计数器 `standbyFirewallSkipCount`），可以证实"断网链被挡"及其量级；此前为完全静默，该观测盲区已关闭。

**全局性的字节码确证（2026-10-01）**：该命令在 PowerKeeper 侧**端到端无 per-uid 通道**——`AppStandbyController.setMiuiStandby(Z)`（唯一门控是 `mMiuiStandby` 状态缓存防重复下发）→ `NetdExecutor.enableFirewallStandbyChain()V`（**无参**静态方法）→ netd `dnsproxyd enablemiuistandby enable`（无 uid 参数）。触发入口包括 `handleScreenOffTimeout` 等多个状态机方法，不限于睡眠模式夜。

**运行时确证（2026-10-01，ADB socket 探针）——上面的担心在本 ROM 不成立**：对 `/dev/socket/dnsproxyd` 的实测（NUL 分帧 FrameworkListener 协议）：`getaddrinfo`（无参）→ `501 GetAddrInfoCmd::runCommand: invalid number of arguments`（监听者 = tethering apex `libnetd_resolv.so` 的 DnsProxyListener，身份经错误字符串匹配确认）；而 `enablemiuistandby`（无论带不带参数）→ **`500 Command not recognized`**，`setuiddnsrule` 同样未注册。即 **PowerKeeper 经 dnsproxyd socket 下发的全部 MIUI 命令在本代 ROM 上是死信**——"全局待机断网链"从未真正生效过，拦截它的"全系统代价"不存在。netd 内真正的 MIUI 控制引擎是 `OemNetdListener.setMiuiFirewallRule(包名, uid, rule, type)` / `checkMiuiNetworkFirewall`（经 oemnetd Binder，per-uid/per-IP），与本命令无关。钩子处置：**保留**（拦截一个死命令零成本；若未来 OTA 重新注册处理器，它自动恢复保护价值）。

**OemNetdListener 引擎与 GMS 的关系已查清（2026-10-01，静态 + 运行时双通道）——无关，关闭**：
- **唯一 Java 调用方** = `com.miui.server.RestrictAppNetManager`（限制应用联网管理器，miui-services）。数据源三路：① `init()` 硬编码出厂名单 `sRestrictedAppListBeforeRelease`——**全部是跑分软件**（antutu ×8、鲁大师 ×2+cooling、安兔兔视频、gamebench、3DMark 等 17 项）；② `MiuiSettings$SettingsCloudData` 云控观察者（`registerCloudDataObserver`/`1`）；③ 装包广播 `$3.onReceive`（经 `isAllowAccessInternet(pkg)` 门控）。
- **全类区域 0 个 GMS 字符串引用**（com.google.android.gms/gsf 均无）。
- **运行时**：本次开机至今 logd 主缓冲零条 `setMiuiFirewallRule` / `addMiuiFirewallSharedUid` / `notifyFirewallBlocked`（缓冲回溯到开机，时钟校准前时间戳例外）；tag `RestrictAppNetManager` 静默；`dumpsys netpolicy` 中 UID=10133 `policy=4 (ALLOW_METERED_BACKGROUND)`、`hasNetworkAccess=true`、在默认 restrict-background allowlist；`oemnetd` 独立守护进程不存在、`service list` 无对应 Binder 服务。
- **残余风险与监控点**：唯一理论通道是云控未来把 GMS 加入 restrict 名单——事后可见（无需 root）：`adb shell logcat -d -s RestrictAppNetManager`（`updateFirewallRule : N`）与 netd 侧 `setMiuiFirewallRule: packageName=%s, uid=%d` 日志行；症状层面流量探针会先报警。不为此加钩子。

#### D2. 免限名单与场景编译（P1–P3）

这组解决的是同一根因：GMS 在 PowerKeeper 的配置里被卡在 `miuiAuto`（scenario 0），因为策略 UI 对没有启动器图标的包隐藏了选择器，于是 `dealNoRestrictApp()` 永远不会把它纳入 `MILLET_NO_RESTRICT_APP`。三层各自修补一个环节：

| 层       | 钩子                                                                           | 动作                                                                |
| ------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| P1 源头   | `UserConfigureHelper#getNoRestrictApps(Context)`                             | 返回值追加 GMS                                                         |
| P1 兜底   | `UserConfigureHelper` 中所有名字含 `update/save/insert/modify/setBg` 且不含 `get` 的方法 | 调用后重新断言 userTable；`setBgControl` 且涉及 GMS 时把非保留列的值改写为 `noRestrict` |
| P1 终检   | `ActiveStateController#dealNoRestrictApp()`                                  | 执行后校验 `Settings.System.MILLET_NO_RESTRICT_APP`，缺失则追加并触发 P4 恢复     |
| P2 冻结侧  | 见 B 面 `AurogonImmobulusMode#isNoRestrictApp`                                 | 在 system_server 侧让 GMS 表现得像在免限集合里                                 |
| P3 编译结果 | `PowerKeeperAppConfigure#fillScenarioContent`（7 参 OS3 / 8 参 OS4）             | 执行后把 GMS 的 `scenario` 由 `0` 改写为 `8`                               |

P1 的"写回"部分（`ensureGmsUserTableBgControl`）直连 PowerKeeper 的 ContentProvider（`content://com.miui.powerkeeper.configure/userTable`），把 GMS 行的 `bgControl` 写成 `noRestrict`；查不到就 insert。由 `userTableReassertInFlight` 防止重入——写回本身会触发被钩住的 writer，形成环。

装在 PowerKeeper 进程内而不是用外部 watchdog 修 Settings，是为了消灭竞态：**每次投影重新生成都在源头带上 GMS**。

#### D3. 待机限制

`AppStandbyController#setUidState(int, boolean)` 是 per-uid 待机限制的收敛点（方法自己就打印 `setUidState, uid = %d allow = %b`）。对 GMS 强制 `allow=true`。

三条约束写在源码注释里，改动前必读：

- **只改参数，不要预置 `mUidState` 为 true**：当传入值等于缓存值时方法会提前返回，true 的缓存会抑制恢复路径而不是触发它。
- 下游 helper 方法名被混淆且代次不同（OS3 `s:(IZ)V`，OS4 `r:(IZ)V`），**不得硬编码字母**，只钩 `setUidState` 本身。
- 已知残余缺口：GMS 被**带外**限制（不经过 `setUidState`）且 `mUidState` 仍为 true 时，即使传入 `allow=true` 也会短路，没有任何东西解除限制。P4 恢复不覆盖这种情况。

**同名方法的排查（2026-10-03；素材取自本机 ROM 的 PowerKeeper `classes.dex` dexdump，下称 `<PowerKeeper-dis>`，与从设备直接 pull 的同名文件类名/签名逐项一致，两份均含 OS4 专属 `PhoneSleepModeController`）**：PowerKeeper 里叫 `setUidState` 的方法共 **6 个**，分布在 6 个 controller——

| 类 | 签名 | 下游动作 | 是否待机网络 |
| --- | --- | --- | --- |
| `ActiveStateController` | `(IIZ)V` | 向 `AppActiveConfigure.CONTENT_URI` insert（uid/property/active） | 配置库 |
| `AppClusterController` | `(ILClusterUtils$Cluster;Z)V` | `ClusterUtils.addAppToCluster` / `delAppFromCluster` | 分组成员 |
| `DeviceIdleController` | `(IZ)V` | `mTempWhitelistAppIds` / `mTempNonWhitelistAppIds` | doze 临时白名单 |
| `KillProcessController` | `(IZ)V` | `ProcessManager.killApplicationAlways` | 进程 |
| `SensorController` | `(IZ)V` | `setAppSensorsControlPolicy` | 传感器 |
| **`AppStandbyController`** | `(IZ)V` | 见下 | **是（本钩目标）** |

即 `(IZ)V` 这一个签名在 **4 个类**里各有一份——按名字 grep 会命中错的类，必须按 `类名#签名` 定位。

真正的网络收敛链在全 dex 内唯一：`AppStandbyController#setUidState` → `DeviceIdlePolicyHelper.r(uid, !allow)` → `q(pkg, !allow, userId)` → `IUsageStatsManager.setAppInactive(pkg, !allow, userId)`。后三级各只有 **1 个调用点**（`DeviceIdlePolicyHelper.r:(IZ)V` 与 `IUsageStatsManager.setAppInactive` 全 dex 唯一，且都在 `DeviceIdlePolicyHelper` 内；`.r` 的唯一调用点就在 `setUidState` 偏移 `0045`）。⇒ PowerKeeper 自身能改 GMS「待机/未激活」网络状态的路径 **100% 收敛在这一个钩子上**；其余 5 个同名方法改的是 doze 白名单 / 进程 / 传感器 / 配置库，不是待机网络状态。

因此「带外」的真实面只能来自 PowerKeeper 之外：system_server 的 `UsageStatsService#setAppInactive` 被别的调用方触发，或 netd 侧规则。免 root 可读的四项判据：`am get-standby-bucket com.google.android.gms`（本机 5=ACTIVE）、`dumpsys netpolicy` 的 `UID=10133`（本机 `policy=4 ALLOW_METERED_BACKGROUND`）、`dumpsys greezer` 的 `frozen=0s`、`dumpsys deviceidle whitelist` 三段。四项全绿 ⇒ 未发生。

### 4.6 睡眠模式断网链（system_server）

`PhoneSleepModeController` 入睡后会打开一条断网链，只放行 `mSleepModeWhitelistUids` 中的 uid，其余整夜掐网。GMS 默认不在集合里，于是 FCM 长连接被静默切断——表现为"FCM 以为只是网络断了"，直到心跳超时才重连。

进入睡眠的广播顺序是 `setSleepModeWhitelistUidRules()` → `enableSleepModeChain(true)`，因此只需要在**下发之前**把 GMS uid 塞进集合，不必改动链开关语义；退出时 `clearSleepModeWhitelistUidRules()` 会对称撤销，不会残留规则。

```
hook: MiuiNetworkPolicyManagerService#setSleepModeWhitelistUidRules()   ← arm 1
        → addGmsToSleepModeWhitelist(field, thisObject) → 原方法
hook: MiuiNetworkPolicyManagerService#enableSleepModeChain(boolean)     ← arm 2
        enabling=true  → 记录 "chain enabled, whitelist size N"
        enabling=false → 采样流量 → 异步 nudge → 15s 后再采样一次
        （2026-10-01 起改为**条件触发**：`sGmsKeptOnSleepWhitelist` 为 true 时跳过 nudge，改打 `skipping recovery nudge (MCS untouched)`；为 false 才走原 nudge 路径。标志由白名单注入钩子在本会话内设置，出睡决策后复位）
```

**两条臂独立挂载（2026-10-03）**：`armSleepModeWhitelist` 与 `armSleepModeChain` 各自解析目标、各自 `deoptimize`，互不短路。此前白名单方法缺失会 `return` 掉整个函数，顺带把链钩也跳过——那种 ROM 上"出睡重连"会**既未挂载也无 INFO 提示**（只有 DEBUG 的 `logSkipOtherGeneration`）。安装期一行总结两条臂的挂载结果：`Sleep-mode legacy per-uid chain armed: whitelist=<bool>, chain=<bool> (sentinel: …)`。

**触发哨兵**：两条臂各自在**首次真正被 ROM 调用**时打一行

```
sleep-mode sentinel: legacy path FIRED — #setSleepModeWhitelistUidRules ran on this ROM, …
sleep-mode sentinel: legacy path FIRED — #enableSleepModeChain ran on this ROM; …
```

一次性（`sSleepWhitelistPathFired` / `sSleepChainPathFired`）。本代 ROM 上**这两行都不应出现**；一旦出现即说明机型已离开"睡眠断网在 PowerKeeper、不按 uid 过滤"的结论，后续判断必须以该行日志为起点重建，而不是继续引用本文的 V816 结论。

四个静默出口（字段类型不是可变集合、uid 解析失败、GMS 已在集合内、链开启本身）都已补日志——此前"回调压根没跑"和"跑了但集合是空的"在日志里完全一样，无法区分（见 5.7 判定表）。

#### 4.6.1 现役实现与门控矩阵（2026-10-02 更正）

**上面这段描述在本代 ROM 上已失效，先读这里。** OS4/V816 的睡眠断网**不是**按 uid 掐网：`PhoneSleepModeController#applySleepConfig` 直接调用 `WifiManager#setWifiEnabled(false)`（偏移 `29e404`）与 `CommonAdapter#setDataEnabled(TM,false)`（偏移 `29e36a`），WiFi 与蜂窝一起关（实测 `01:38:00→07:08:57` 共 5h30m，GCM `net=-1`，即蜂窝也不可达）。

- `sleep_mode_network_white_apps` 全 ROM 仅 3 处引用（云控读、云控写、清应用观察者），**关网路径零读取点** ⇒ 按 uid 的机制残骸。
- `setSleepModeWhitelistUidRules()` / `enableSleepModeChain(true)` 整夜零触发（`Sleep mode entering` 从未出现）。旧钩保留作 OTA 防御位，但**在拿到新的运行时命中证据前，不得把它当作有效保护来写文案或下结论**。
- `setRadioPower` / `setAirplaneMode` 零命中 ⇒ 蜂窝射频与信令全程在线，电话/短信/小区广播不受影响——是"不走 IP"，而非"IP 被豁免"。

`applySleepConfig` 的实际极性门控是 `Settings.Secure.getIntForUser("key_open_earthquake_warning")`：该值 1 时整段关网（连同 `SleepState` 记账）被跳过。这是模块的**降级路径**（`hookSleepModeEarthquakeFlag`），只在两个 cutoff 调用有一个钩不住时启用；正常路径下模块让 ROM 走完整流程，只在两个关网调用处拦。

现役钩点与**两个**实验开关的门控关系（全在 `Hooker.kt`，每次 cutoff 调用惰性读远端值）：

| 钩点 | 闸门 | 放行/拦截时的日志 |
| --- | --- | --- |
| `WifiManager#setWifiEnabled(Z)`，仅 `enable=false` 且在 `applySleepConfig` 栈内 | `sleep_keepalive` | `sleep-mode: kept WiFi on (sleep would have turned it off)` / `sleep-mode: WiFi left to the ROM policy (…)` |
| `CommonAdapter#setDataEnabled(TM,Z)`，同上 | `sleep_keepalive` ∧ `sleep_keepalive_data` | `sleep-mode: kept mobile data on (data sub-switch is on)` / `sleep-mode: mobile data left to the ROM policy (…)` |

**门控闭合（2026-10-03 核对，防"主开关关了副开关还在生效"；2026-10-04 去掉充电子开关后重核）**：

- 服务端：`isSleepKeepaliveDataEnabled()` = master ∧ data。主开关关闭后，副开关即使存值仍为 true 也不进入任何分支。（原 `isSleepKeepaliveChargingOnlyEnabled()` / `chargingGateBlocks(radio)` 随充电子开关一并删除，见下。）
- 界面：**一个**副开关处在 `AnimatedVisibility(visible = sleepKeepalive)` 里，主开关一关整块收起；副开关的 `checked` 值**有意保留**，重新打开主开关时恢复上次选择——这是"记住选择"，不是残留生效。
- 离线镜像：`MainActivity#reloadAllowlist()` 对两个键都做 pending 修复，绑定后把界面值推上行，而不是被旧的远端值覆盖。

**一条已知限制（不改，备查）**：

1. 降级路径（`hookSleepModeEarthquakeFlag`）**既不拦 WiFi 也不拦数据**——它整段跳过关网调用，没有 per-电台 的决定可窄化（因此对两个开关的行为是一致的，不存在"降级下某个子开关失效"的不对称）。安装期日志已明说走的是这条。

**"其余动作保持原生"指的是什么（2026-10-03 取证补齐）**：`applySleepConfig` 的关网段不止那两个开关调用，同一段里还跑着按位处理，且每一"位"都是一次真实的系统行为改写——

| 偏移 | 位 | 动作 | 入睡 | 出睡恢复 |
| --- | --- | --- | --- | --- |
| `29e33e` / `29e36a` | 1 = data | `SleepState.setPreviousEnable(1,…)` → `CommonAdapter.setDataEnabled(TM,false)` | 关移动数据 | `restoreSleepConfig` 按记录开回 |
| `29e3d8` / `29e404` | 2 = WiFi | `setPreviousEnable(2,…)` → `WifiManager.setWifiEnabled(false)` | 关 WiFi | 同上 |
| `29e42e`~`29e4a0` | 16 = keyguardNotification | 读 `Settings.System wakeup_for_keyguard_notification`（默认 -1）→ 存 `SleepState.previousNotification` → `setRestore(16, 原值>0)` → `putInt(..., 0)` | **关掉"锁屏通知点亮屏幕"** | `29feb2` `putInt` 写回 `previousNotification` |
| `29e4a6`~ | 32 = FOD | `ro.hardware.fp.fod` 为真时 `isFodAodShowEnable()` → `setPreviousEnable(32,…)` → `setFodAodShowEnable(false)` | 关屏下指纹 AOD 常显 | 按记录开回 |
| `29e4f4`~`29e52c` | 128 = pickup | `isPickupWakeupEnable()` → `setPreviousEnable(128,…)` → `setPickupWakeupEnable(false)` | 关抬手亮屏 | 按记录开回 |

模块只拦前两行；**16/32/128 三行全部按 ROM 本意执行**。其中 16 位最容易被忽略也最实际：睡眠期间锁屏通知**不再点亮屏幕**。旧的 flag 捷径会连它一起跳过 ⇒ 夜里每条推送都把屏幕点亮一次。反过来，若刻意阻止它执行（为了"通知照常亮屏"），失去的正是 ROM 这一项省电与免打扰。

~~充电读取本身返回三态（`readChargingState(): Boolean?`，null = 读不到）：读不到与"未充电"都会断网（fail-closed），但**日志分行报告**，否则一个不可读的电池服务会伪装成"闸门正常工作"。~~ **2026-10-04 作废：充电子开关连同 `readChargingState()` / `chargingGateBlocks()` 一并删除，见下。**

**充电整夜 ≠ 一次检验（2026-10-04，见 §5.9.8(d)）**：实测一夜充电息屏，模块侧 **0 条 `sleep-mode:` 行**，而这条支路每次 cutoff 必打一行，结合 GCM 全程在线可反向确证 —— **ROM 那一夜压根没走进 `applySleepConfig` 的关网段**。所以"挂一夜但插着电"得到的是**零样本**，既不能写成"保活有效"，也不能写成"保活空转"；要检验就拔掉充电器。（`charging-only` 子开关只在 cutoff 已经发生时窄化放行条件，它造不出触发。）

**「仅在充电时才不断网」为什么被删（2026-10-04 上午提问，同日删除）**：既然上面那句成立——充电整夜里 ROM 没执行 cutoff——那么把这个子开关打开的收益是什么？按现有证据逐层过：

1. **它是纯收窄，不可能带来正向收益。** `chargingGateBlocks(radio)` 为真就 `chain.proceed()` 放行原调用。它永远不能把"本来会断的网"变成"不断"，效果的上限就是主开关单独开启时的样子。
2. **充电夜：开与关不可区分。** §5.9.8(d) 那一夜没有任何 cutoff 发生，因此该夜两种取值的行为完全相同——它不是"省了电"，而是"把本来就没发生的事继续不发生"。
3. **这条路确实是活的。** 10-02 凌晨实测过一次完整的 `applySleepConfig`：`01:38:00.168 … setDataEnabled false setWifiEnabled false` → `07:08:55.991 restoreSleepConfig`，中间 GCM `net=-1` 断 **5h30m57s**（见当日工作日志）。所以确有会断网的夜，只是那夜是否充电没有留下记录。
4. ⇒ **唯一能让它产生差异的场景，恰恰是最需要保活的那一夜**：未充电、且 cutoff 真的发生。在那个场景里它做的事是主动放弃保活。
5. **附加失败模式**：`readChargingState()` 是三态，读不到时按"未充电"处理（fail-closed）。开启后，一个不可读的电池服务会让用户**静默失去保活**，且日志只会写"unreadable"，不写"保护没生效"。
6. **它当初成立的前提已被推翻**：立项时的假设是"充电 ⇒ PowerKeeper 否决入睡"（2026-10-01 记录），但 2026-10-03 更正为——**真正让设备不睡的是 USB/adb 活动使其保持 Awake，不是充电**。前提塌了，开关的收益论证随之失效。

⇒ 处置结论：**在本机当前 ROM 上没有正向收益，只有负向代价**，用户据此决定删除。

**删除范围（2026-10-04 上午执行，未改动主开关与 `…_data` 子开关）**：`Prefs.KEY_SLEEP_KEEPALIVE_CHARGING` 与其 pending 键、`readLocalSleepKeepaliveCharging` / `hasPendingSleepKeepaliveChargingPush` / `writeSleepKeepaliveCharging`、`MainActivity#reloadAllowlist` 里对应的 pending 修复、`ExperimentScreen` 里的 UI 卡片、`strings.xml` 两条文案、图标 `ic_battery_charging_full`；钩子侧删除 `isSleepKeepaliveChargingOnlyEnabled()` / `readChargingState()` / `chargingGateBlocks()` / 缓存 `hostAppContext` 与 `import android.os.BatteryManager`，两个 cutoff 拦截器里各去掉一道充电闸，`hookSleepModeEarthquakeFlag` 的安装日志去掉"此路径无法应用充电闸"那半句。存留在用户设备上的旧远端/local 键不再被任何代码读取，**不清理也不会生效**。

**删除后仍然成立的一条**：从来没有人见过 cutoff 真的发生时这个开关会有何表现，因为缺乏拔电对照夜。上面的论证推翻的是"这个开关有价值"，不是"ROM 会/不会在某夜关网"——后者仍然只靠 10-02 那一次样本。⇒ 睡眠保活本体是否真的有用，**依旧没有证据**，验证方法不变：拔掉充电器、断开 USB，挂一整夜。

**跨代核对（2026-10-03，为"睡眠保活与强停口径是否照顾 OS3"补）**：

本机是 OS4，而模块对 OS3 保留了一批目标，所以上面这些都逐个核过 OS3（素材：`<PowerKeeper-OS3-dis>` = OS3 PowerKeeper 带指令体 dexdump、`<OS3 miui-services.jar>` = OS3 system_server）。

| 目标 | OS3 偏移 | OS4/V816 偏移 | 结论 |
| --- | --- | --- | --- |
| `PhoneSleepModeController#applySleepConfig` | `1a1eac` | `29e120` | 同名同签名；栈帧闸门 `calledFromSleepApply` 两代都命中 |
| `CommonAdapter#setDataEnabled(TM,Z)`（在 `applySleepConfig` 体内） | `1a2106` | `29e36a` | 静态方法、签名一致 |
| `WifiManager#setWifiEnabled(Z)`（同上） | `1a218c` | `29e404` | framework 目标，一致 |
| `PhoneSleepModeController#restoreSleepConfig` | `1a3890` | 有 | 降级路径的 `calledFromSleepConfig` 依赖它 |
| `Settings.Secure` 读 `key_open_earthquake_warning` | `1a1f82` | `29e31e` | 两代都靠它跳过整段关网，降级路径同形 |
| `MiuiNetworkPolicyManagerService#{setSleepModeWhitelistUidRules, enableSleepModeChain}`、`mSleepModeWhitelistUids` | 均在 | 均在 | 两代都"存在"；差别是本代不调用——这正是哨兵要观测的 |
| `ProcessCleanerBase#isForceStopEnable(ProcessRecord,int,ProcessManagerService)` | 同签名（另有两个 `(ProcessRecord,int)` 重载） | 同签名 | 强停路径两代同形，§四 的严格模式口径对 OS3 同样成立 |
| `ProcessSceneCleaner` / `killOnce` / `handleSwipeKill` | 均在 | 均在 | 上滑清理链两代同形 |
| `CommonAdapter#addPowerSaveWhitelistApps` | 有 | 有 | 微信免冻剔除两代可挂 |
| `PowerSaveConfigureManager#setPowerSaveAppConfigure` | 有 | 有 | 微信盾的拦截点两代都在；OS3 是否也存在"getter 内嵌升格写"未取证，开关在不符代次时静默不触发 |

**一处仍未定的跨代假设（watchlist）**：出睡 nudge 的闸门 `sGmsKeptOnSleepWhitelist` 只证明"uid 规则已下发"，由此推出"链路整夜通畅"是**代次假设**——在 V816 成立（该路径根本不跑），但一个"既按 uid 白名单、又在 PowerKeeper 关电台"的 ROM 会让标志为真而链路已断。该分支在本机不可达、**无法运行时取证**，故不改行为，只在跳过时的日志里写明假设。若某天真出现 `sleep-mode sentinel: legacy path FIRED`，先看当晚有没有 `sleep-mode: kept WiFi on` 一类行，再决定是否把 nudge 闸门改成"按实际是否断网"判定。

### 4.7 恢复动作（P4，非 hook）

`recoverGmsConnection(Context)` 是**出境 IPC**，不是钩子：向 GMS 与 GSF 各发三条广播（`GCM_RECONNECT` / `GTALK_HEARTBEAT` / `MCS_HEARTBEAT`），再查询一次 Chimera provider。三条一起发是因为 `GCM_RECONNECT` 在部分版本上覆盖不到 MCS/GTalk 的重连路径。

当前有两个触发点：睡眠模式退出（**条件性**——`sGmsKeptOnSleepWhitelist` 为 false，即白名单注入未生效时才 nudge，触发前后各采样一次流量使效果可证伪；注入成功则跳过并留日志，避免拆掉整夜健康的 MCS）与 `MILLET_NO_RESTRICT_APP` 修复（条件性——仅在实际发生追加修复时）。这是一个覆盖面问题，不是需求问题（见第 10 章）。

**为什么不扩触发面（2026-10-03 补证）**：三类诱因都落在 GMS 自身的重连能力内，而 P4 的广播是**破坏性**的（会让 GMS 主动拆掉当前 MCS）。本机 `dumpsys activity service com.google.android.gms/.gcm.GcmService` 实测：`connected=mtalk.google.com:5228`（**TCP 5228**）、`connects=16`、`failedLogins=0`、`Seen good heartbeat in last connection? true`，各网络类型的 `FastSlowHeartbeatAlgorithm` 全为 `bad_heartbeat_count: 0`，`interval_range=[110s,1730s]`、`heartbeat_interval=230s`。

| 诱因 | 自愈机制 | 结论 |
| --- | --- | --- |
| 网络切换 | GMS 注册 ConnectivityManager 回调，网络变化自行重建 MCS | 不需要 P4 |
| GMS 被杀重建 | 进程重启即重连；模块 `isForceStopEnable` 另挡住强停 | 不需要 P4 |
| NAT/FW 老化 | MCS 走 **TCP**，NAT/FW 状态超时（≥1h）远大于心跳上限 1730s ⇒ 心跳本身就是为它设计的 | 不需要 P4 |

⇒ 触发面窄是**设计选择而非缺口**：唯一「连接已死且 GMS 未必自愈」的时刻，就是 ROM 整夜物理掐网后退出（重连退避可能已耗尽）。重启条件不变：整宿观测出现「MCS 死亡 + GMS 未自愈 + 未进睡眠模式」的证据后再设计带门控的触发。

---

### 4.8 「电量与性能」省电策略的自动升格（ROM 原生，2026-10-03 取证）

这一节与模块无关，记录的是 PowerKeeper 自己的行为：一个应用在省电策略里被设成「智能限制」（`miuiAuto`）后，**会不会自己跳到「无限制」（`noRestrict`）**。素材为 §4.5 同源的 `<PowerKeeper-dis>`。

**结论：会跳，但只在「这个 userId+pkg 第一次被读取」时发生一次。**之后永久保持当前值，用户手动改的选择不会被覆盖，除非这个组合退出 visited 集合。

#### 存储与默认值

`userTable` 建表 SQL 明写默认值，且安装应用时 `PowerKeeperConfigureManager$5.onPackageAdded` 会立即 [`2675d8`] 以 `bgControl="miuiAuto"` 建行（经 `UserConfigure.CONTENT_URI` insert）：

```sql
CREATE TABLE IF NOT EXISTS userTable (
  _id INTEGER PRIMARY KEY AUTOINCREMENT, userId INTEGER NOT NULL DEFAULT 0,
  pkgName TEXT NOT NULL, lastConfigured INTEGER,
  bgControl TEXT NOT NULL DEFAULT 'miuiAuto', bgLocation TEXT,
  UNIQUE (userId, pkgName) ON CONFLICT REPLACE );
```

四档取值与对外名由 `getInterfaceConfigureValue` [`26dca8`] / `getUserConfigureValue` [`26dd18`] 成对映射：`miuiAuto↔miui_auto`、`noRestrict↔no_restrict`、`restrictBg↔restrict_bg`、`noBg↔no_bg`。**所以 ROM 侧「智能限制」确实就是 `miuiAuto`**，并且是所有已安装应用的出厂默认值。

#### 唯一的自写点

`setPowerSaveAppConfigure` 全 ROM 仅 2 个调用点：`200080`（Binder 外部入口 `PowerKeeperManager`）与 `26da36`——后者位于 **`getPowerSaveAppConfigure` 内部** [`26d8ac`]，即"读"的时候偷偷写。 polarity 三次反闸门串起来才是条件：

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

#### visited 的持久化与唯一的重置路径

`sHasVisited` 不是内存缓存：`<clinit>` [`26dd88`] 从 `SimpleSettings$Misc.getStringForUser(ctx, "s_has_visited", …)` 用 Gson 反序列化读入（日志 `init from database => …`），`storeList()` [`26de4c`] 再 JSON 存回（日志 `dump to database -> …`）。⇒ **跨进程重启、跨 reboot 都保留。**

唯一把条目移出集合的地方是 `PowerKeeperConfigureManager$5.onPackageRemoved` [`2676ba` → `2676dc` remove → `storeList`]。也就是说：**卸载并重装该应用，会让这次自动升级重新获得一次机会**（升级写下的 `no_restrict` 会在重装后被再次安排上）。

#### 谁会触发这次读取（未闭环）

整套符号在 `miui-services.jar`(2 dex) / `services.jar`(4 dex) / `miui-framework.jar` 的 dex 字符串池里**零命中** `getPowerSaveAppConfigure`；`PowerKeeper.apk` 内部除 AIDL 桩（`IPowerKeeper$Proxy` `1fdd38`）与 `PowerKeeperManager` 转发外也无调用者。⇒ **调用方不在系统层，是上层 App（设置 / 手机管家的省电详情页之类）**。手上无这两个 APK，未做闭环；待设备上 `logcat -s PowerSaveConfigureManager` 抓一次实际调用以确认触发时机。

#### 判定表

| 场景 | 是否自动升到「无限制」 | 依据 |
| --- | --- | --- |
| 新装应用，从未被任何客户端读过配置 | **会**，在第一次被读的瞬间 | 三个条件齐备（默认值 `miuiAuto` + 若在白名单） |
| 已读过一次（无论那次是否真的升级） | 不会，`00c2` 处直接跳过 | visited 已持久化，无法再用"没跳"反推条件不成立 |
| 用户在 UI 手动改回「智能限制」后 | 不会自动改回 | 手动改走 Binder 的 `set`，**不 touch `sHasVisited`** [`26daac`] |
| 卸载微信后重装 | **会**，`onPackageRemoved` 摘掉标记出 recharge | `2676ba` |
| **应用内版本更新**（覆盖安装） | **不会**，不摘标记 | 见下 |
| 应用不在电池优化豁免名单 | 永远不跳 | 条件 ③ 是硬门槛 |

#### 版本更新不摘标记（2026-10-03 补）

链路：`PowerKeeperPackageManager$MyPackageMonitor extends com.android.internal.content.PackageMonitor`，覆写 `onPackageAdded` [`2e201c`]→合成桥 `d` [`2e2548`]→`addPackage`、`onPackageRemoved` [`2e206c`]→合成桥 `g` [`2e2578`]→`removePackage`、`onPackageRemovedAllUsers` [`2e2088`]→`j`+`g`、`onPackageUpdateFinished` [`2e20b0`]→`d`→`addPackage`。**未覆写 `onPackageUpdateStarted`**，而 `onPackageUpdateFinished` 被覆写成 addPackage ⇒ 基类在 `EXTRA_REPLACING=true` 时走的是 update 分支（否则覆写 finished 无意义），即覆盖安装不落到 `onPackageRemoved`。

`addPackage` 内部 [`2e2870`~`2e28e8`] 也有一处 `notifyPackageRemoved`，但条件是**该 uid 下 uid 值发生变化**（`00bd` 取旧 uid 比较），覆盖安装 uid 不变 ⇒ 不触发。⇒ **微信版本更新不会重置熔断，只有卸载重装会。**

#### 模块决策：`wechat_battery_shield` 已移除（3.5.3 之后）

该实验开关曾拦 `PowerSaveConfigureManager.setPowerSaveAppConfigure` 里那次自动升格写。因熔断持久（跨重启）、手动改回走 `set` 不 touch 集合、覆盖安装又不摘标记 ⇒ **对已装微信而言升格只发生一次，开关挡的是"一次已被消耗的机会"**，故删除，仅保留 AOSP 侧 `wechat_doze_keepout`（拦 `addPowerSaveWhitelistApps`，那一条是每次电源模式变化都重写的，无熔断）。残留风险只有"卸载重装"与"`s_has_visited` 丢失"两条，均不值得保留一个默认关闭、命中率极低的钩子。

#### 与另一套机制的区别（勿混）

这里的 `userTable.bgControl` 是**电量与性能里的省电策略档位**；记忆里 `DeviceIdleController$1` 的 `sAlwaysWhiteApps` 写的是** deviceidle.xml（AOSP「电池优化」未优化名单）**。两套互不相干，但条件 ③ 读的正是后者——**这也是为什么"ROM 自己把某应用加进电池优化白名单"会连带把它的省电策略顶到无限制**。

#### 待补的设备侧验证（设备离线，未做）

```
adb logcat -v time -s PowerSaveConfigureManager        # 抓 init from database / return configure / setPowerSaveAppConfigure success pkg=…
adb shell settings get system s_has_visited            # 看 "0com.tencent.mm" 是否已在集合里
adb shell dumpsys deviceidle whitelist | grep tencent  # 条件 ③ 是否成立
```

---

## 5. 诊断体系

### 5.1 三条设计原则

1. **只读优先**。能观察就先观察，只有在运行时证据表明确实需要改写时才落地行为钩子。现有 7 组探针全部只读，不修改任何返回值。
2. **可证伪**。每条"成功路径"日志都必须有一个"到达但未命中"的对应日志。只有命中日志的探针，静默时无法区分"从未被拒绝"与"从未被调用"——这个教训直接来自对 `checkWakePath` 与 `isPushApp` 的取证。
3. **探针不得抛出**。所有只读辅助方法内部全包 `try/catch`，返回可读的占位字符串（如 `<unreadable>`、`<not a collection>`）。

### 5.2 五类诊断手段

| 类别            | 代表                                                                          | 回答的问题                 |
| ------------- | --------------------------------------------------------------------------- | --------------------- |
| **存在性探针**     | `probeReflectiveMethod`、`reportWhetstoneClasses`、`probePacketFilterSupport` | 这个隐藏符号在这台 ROM 上存不存在   |
| **只读字段快照**    | `mMessageApp` 探针、`sleepModeWhitelistSize`、`NoNetworkBlackUids` mismatch     | 这个集合现在是什么内容、GMS 在不在里面 |
| **计数 + 心跳节流** | `checkWakePath` 探针、`checkBroadcastWakePath` 探针、`doDesSocketForUid` 三层探针              | 这个门被进入多少次、拒绝了多少次      |
| **一次性证据日志**   | 8 个 `@Volatile Boolean` 标志位                                                 | 这条路径到底有没有真实发生过一次      |
| **流量采样**      | `TrafficStats` per-uid 增量                                                   | 结果层面：GMS 现在还在不在交换数据   |

**Gate-W 两轮长窗实测（结论已定）**：首轮 9h23m reached=9951 / denied=22（可归因拒绝全部落在单一第三方应用包名上，GMS 作为 caller 的 57 次采样全放行）；第二轮 13h49m 整宿（2026-09-30 19:57 → 10-01 09:46）reached=15073 / denied=0。两轮合计 ~25k 样本，GMS 零拒绝 ⇒ "行为钩不落地、维持只读"正式落档；退役按判据 17 仍需更多窗口，但晋升评估已完结。

### 5.3 存在性探针：为什么必须运行时问

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

跨两次传输，单层探针可能整个漏掉。**binder 传输对 `invoke-*` 计数不可见**，这是"静态零调用者 ≠ 死代码"的核心理由。当前读数：结构可达、观测窗内零调用 ⇒ 判定为"结构可达但从未触发"，**不是**死代码；只有更长窗口的持续零流量才允许降级，绝不能靠 `invoke-*` 计数降级。

### 5.4 计数与心跳的节流策略

| 探针                  | 节流方式                                                        | 理由                            |
| ------------------- | ----------------------------------------------------------- | ----------------------------- |
| `checkWakePath`     | 拒绝：前 10 次逐条 + 按调用方聚合（30 min 节流的 denied summary 摘要行，心跳行附 `top=`）；放行：按时间节流（`WAKE_PATH_HEARTBEAT_MIN_MS` = 30 min） | 一夜进入近万次，每次都打日志会把 modules 日志淹没 |
| `checkBroadcastWakePath`（P2，2026-10-02） | 首次到达打一条即时 `… broadcast gate first reach …`（仅此一条）；明细行前 `WAKE_PATH_DETAIL_LIMIT`（10）次（c2dm 到达、以及任何拒绝各计一份）；计数每次 traffic-probe tick（30 min）随 `broadcast gate: wake-path …` 行输出 | 服务/活动唤醒路径（Gate-W）的对照物。Gate-W 的首次心跳即时可见，广播路径若只靠 30 min 摘要行，"已挂钩但从未执行"要等半小时才能与"到达但从不拒绝"区分，故补一条**只打一次**的到达行 |
| `doDesSocketForUid` | 前 10 次，或任何命中 GMS uid 的调用                                    | GMS 命中无论第几次都必须记录              |

同时保留 `reached` 计数，使"静默"具备量的含义：一条 `reached=0, denied=0` 与 `reached=9951, denied=0` 是完全不同的结论。这是所有计数探针的硬性要求。

### 5.5 日志契约

| 约定                            | 含义                                                                                      |
| ----------------------------- | --------------------------------------------------------------------------------------- |
| `TAG = "HyperGreeze"`         | 全部日志统一前缀                                                                                |
| `logSkip(msg)`                | 目标缺失，INFO，**递增 `hookTargetsAbsent`**                                                    |
| `logSkipOtherGeneration(msg)` | 目标缺失但属于"另一代次 ROM 的符号"，DEBUG——不该在当前代次出现，不是异常                                             |
| `ClassNotFoundException`      | 通常 ERROR（类应当存在）                                                                         |
| 装机摘要行                         | `HyperFCMLive active in <process>: N hook(s) installed, M target(s) absent on this ROM` |

摘要行是**装机验证的第一判据**：两个域各应出现一次，`M` 的取值应与该 ROM 的代次预期相符。热重载会重新打印一次，此时 `N` 是重挂的数量而非累计值（`setId` 保证同一目标收敛为一条）。

一次性证据标志位清单（每个都对应一条"这条路径真的跑过一次"的 INFO）：

`gmsRestrictNetLogged` · `c2dmDeferBypassLogged` · `alarmGateBypassLogged` / `alarmGateSeenLogged`（成对） · `gmsUdpFilterLogged` · `restrictNetMatchLogged` · `noNetworkBlacklistMismatchLogged`

`alarmGateBypassLogged` / `alarmGateSeenLogged` 的成对设计是可证伪原则的样板：只有"我改写了一次拒绝"而没有"ROM 放行过一次"，静默的日志无法区分"从未拒绝"与"从未到达"。

### 5.6 外部取证

| 手段                                                       | 用途                                            |
| -------------------------------------------------------- | --------------------------------------------- |
| LSPosed 导出的 `modules_*.log`                              | **夜间取证的唯一起点**。logcat 主缓冲会被白天日志冲掉，夜间往往只剩几十行    |
| `adb shell dumpsys greezer`                              | 当前生效的策略实现（`mCurrentCNPolicy`）、冻结进程列表、GMS 是否在内 |
| `adb shell dumpsys netpolicy`                            | 睡眠白名单集合大小、GMS 的当前网络策略                         |
| `adb shell dumpsys deviceidle`                           | doze 白名单各段是否含 GMS                             |
| `settings system MILLET_NO_RESTRICT_APP`                 | 验证 P1 的写入是否落地                                 |
| PowerKeeper 私有 ContentProvider `.../SimpleSettings/misc` | 睡眠开关的真实存储位置（不在 Settings 三个命名空间里）              |
| `adb shell dumpsys network_management`                 | netd 的 `UID firewall dozable rule`（`uid:1`=ALLOW / `2`=DENY），夜间真正生效的那一层              |
| `adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService` | **FCM 链路的首选诊断源**：connects / failedLogins / bad_heartbeat_count、`Failed to broadcast to stopped app`（=stopped）、`No response to broadcast … time=Nms priority=NORMAL`（广播没把目标进程拉起来） |
| `adb logcat -s MIPOWERHALSERVICE-NETLINK`              | 小时级流量采样（`uid=10133 … dev=wlan0`），补流量探针夜间被 suspend 推迟的盲区 |

**长时取证必须逐行刷盘（2026-10-03 实测）**：`logcat -f` 走的是块缓冲，小流量下可能整夜不 flush，第二天拿到的是空文件。必须：

```sh
adb shell "logcat -s LSPosedLogDaemon | awk '/fcmlive,HyperGreeze/ {print; fflush()}' >> /data/local/tmp/fcmlive-night.log"
```

- toybox 的 awk **支持 `fflush()`**（无参即 flush 全部输出流），这是本机唯一可用的逐行刷盘手段；`--line-buffered` / `sed -u` / `stdbuf` 在本机均不存在。
- 想让它熬过物理拔线：`setsid nohup <script> </dev/null >/dev/null 2>&1 &` 已实测有效。断线后无法验证它是否仍在跑（adbd 退出时可能清理子进程），这是残余风险，不是可消除项。
- **c2dm 不可伪造**：receiver 声明 `com.google.android.c2dm.permission.SEND`，AMS 在 enqueue 阶段就对非 GMS 发送方抛 Permission Denial（shell uid 2000 一样被踢）⇒ 只能等真实投递。自然样本首选 Play Store；已被勾进自己唤醒名单的那款即时通讯应用也同理——名单里的包名触发不到 P2 分支，别拿它当阴性证据。

**取证规则（踩过坑换来的）**：

1. `invoke-*` 计数看不到：反射跳板、binder 跨进程、跨 jar 的类。
2. **被覆写的方法要按基类型计数**。曾因只数 `AlarmManagerServiceStubImpl` 的调用点而把 `checkAlarmIsAllowedSend` 误判为死代码——实际调用点在 `AlarmManagerService.triggerAlarmsLocked`，走的是基类虚派发。
3. ROM 取证与运行时 `dumpsys` **两端闭合**才能下"哪个实现生效"的结论。只看一边会把"存在"当成"生效"。
4. 判极性要从字节码读，不要从方法名猜（`isNoRestrictApp` / `isRestrictNet` 都判错过）。

### 5.7 取样陷阱清单

| 陷阱                              | 后果                                                                                                | 对策                                |
| ------------------------------- | ------------------------------------------------------------------------------------------------- | --------------------------------- |
| **冷启动阶段读静态字段**                  | `<clinit>` 尚未执行，读到空集合，得到假阴性。曾读到 `mMessageApp size=0`，而热重载时是 544 / 576 / 64 —— **该集合大小随云控与加载阶段浮动** | 只用 `containsGms`，不把 size 当判据      |
| **探针只记前 N 次**                   | 长窗观测留下不可恢复的归因盲区                                                                                   | `checkWakePath` 已改为 per-caller 聚合 + 30min denied summary；其余前 N 次探针保留（GMS 命中本就必记）               |
| **静默出口无日志**                     | "没跑"与"跑了但没结果"在日志里同形                                                                               | 逐条补日志（3.5.0 已补 4 处）               |
| `adb logcat -G`                 | 调整缓冲大小会丢掉原有内容，开机时段全部丢失                                                                            | 抓开机记录时不要动 `-G`，改用 `modules_*.log` |
| `dexdump … \| awk … \| head -N` | `head` 到量后 SIGPIPE 终止上游，"扫描完整个 dex 没找到"是假结论                                                       | 涉及"没找到"的取证禁止用 `head` 截断管道         |
| Git Bash 路径转换                   | `adb shell ls /system/...` 被静默转成本机路径，输出为空                                                         | `export MSYS_NO_PATHCONV=1`       |
| 零触发 ≠ 无用                        | 一个晚上的阴性只能证明"本轮未观测到触发"；触发面为 0 样本时否定兜底逻辑是循环论证                                                       | 显式写明"未验证"而非"不需要"                  |
| `/proc/net/tcp` 的 uid 列           | uid 在第 **8** 列且是**十进制**（不是常见的十六进制写法），按十六进制读会得到完全不同的 uid                                          | 按列号 8、十进制解析                       |
| Doze `IDLE_MAINTENANCE`           | 每次进入维护窗都会调 `NetworkPolicyManager.setDeviceIdleMode(false)` ⇒ dozable 链临时停用、全网临时可联网，看起来像"策略失效" | 判"某应用为何能联网"先看 `dumpsys deviceidle` 的 `Idling history` |
| 自研流量探针夜间读数                    | 靠 system_server 的定时器，夜间被 suspend 大幅推迟 ⇒ 时间戳不可信，也不能只凭它下结论                                       | 用 GMS 心跳行连续性交叉验证：约 **3m51s** 一条，看有无 **>8 分钟** 断档 |
| `am start` / `cmd activity start` | 本 ROM 抛 `IllegalStateException: Already in the pool!`，shell 拉不起 Activity，容易误判为"模块坏了"          | 改用 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`（有时可用） |
| 两条 wake-path 探针混淆               | 广播闸门与 service/activity 闸门不是一条路，混着读会把结论张冠李戴                                                       | 广播=`WakePathChecker#checkBroadcastWakePath`；service/activity=`ActivityManagerServiceImpl#checkWakePath` |
| 热重载后的首条日志                     | 更新后第一次加载会打**旧实例**的文案（`onHotReloading`），据此判断改动没生效是错的                                          | 以 run-id / 安装时间为准，不以首条文案为准     |
| **`python -c` 里的 `:\d` 正则（2026-10-04）** | 传参层会把冒号后的反斜杠改写成 `/d`——`r'\d\d:\d\d'` 实到 Python 手上已是 `\d\d:/d/d`，正则**静默返回 None**（伪阴性，不报错）。曾据此以为某段 GCM 日志没有心跳 | 正则里用 `[0-9]` 代替 `\d`；或把脚本**写成 `.py` 文件再执行**（文件内容不经 shell 转义） |
| **判资源孤儿时排除 `res/values*`**       | `themes.xml` 的 `@color/` / `@style/` 间接引用全部被漏掉，`md_*` 色板被误判成 31 个未引用孤儿                          | 引用池必须包含 `res/values*`（含 `-night`），只排掉**被判定资源自身所在的文件** |
| **Compose 委托 import 算作孤儿**       | `androidx.compose.runtime.getValue/setValue` 源码里不出现字面名，脚本判为未使用；删掉则 `by remember{}` 编译失败      | 白名单保留；同理 `@Preview` 函数无调用者也不是死代码 |
| **`NetReassign` 当切网判据（2026-10-04）** | `[no changes]` 每次能力重评判都刷；`[reqId : null → 101]` 是 NetworkRequest 级重分配。一局能刷上千条，误算成"1270 次切换" | 只认四条：`Setting inactive [N WIFI]` / `Switching to new default` / `+EXITING` / `Wifi is set to exiting`。详见 §5.9.10 |
| **游戏中 `adb install -r`**           | LSPosed 会重载 system_server 与 powerkeeper，正在采集的那局数据直接被搅掉                                      | 游戏进行中禁止安装；编译好等采集结束再装 |

### 5.8 睡眠链判定表

> **2026-10-02 更正**：本代 ROM 的睡眠断网不走 uid 链，下表对应的日志在新构建里不再产生（整夜零触发）。现役判据改为 `sleep-mode: kept WiFi on` / `mobile data left to the ROM policy (…)`，见 4.6.1。下表保留，供 OS3 或 OTA 恢复旧链时使用——**一个晚上的阴性不足以判定该链永久缺席**。

`Sleep mode entering: chain enabled, whitelist size N` 是分水岭——出现即证明 `enableSleepModeChain(true)` 被调用过。

| 观测到的日志组合                                          | 判定                                                           |
| ------------------------------------------------- | ------------------------------------------------------------ |
| 无任何 `Sleep mode entering` 行                       | 睡眠链整夜未进入（广播未发，或时间窗/静止判定未满足）。2026-10-01 复核：用户确认手机管家"夜间休眠省电"开关为开 ⇒ "开关未开"已排除，指向 **PowerKeeper 入睡决策被运行时条件否决**——字节码可见的门控候选：`checkSleepModeSwitch`、`power.sleep.time` 时间窗、`DynamicTurboPowerHandler` 跟踪的 `isCharging`/`mOnBattery`/`mCurrentLevel`（**充电很可能是门控之一**）。入睡决策只有 `Log.d (tag=power.sleep)` 与 `writeLocalLog`，夜间均不可观测；验证需 cp 出 DB 读 `key_settings_sleep_mode`（开关）/`key_sleep_state`（状态），或下一窗口用新构建的 `standby-firewall` 计数反证 |
| `size 0` 且无 `kept GMS` 行                          | 进入过但 `setSleepModeWhitelistUidRules()` 未在开链前调用 ⇒ 注入未生效，查调用顺序 |
| `size ≥1` 且有 `kept GMS (uid …)`                   | 注入成功，uid 级放行已下发                                              |
| `already whitelisted, size N`                     | 集合非空 ⇒ ROM 侧确实填过（推翻"恒为空"的静态结论）或上次残留未清                        |
| `not a mutable collection` / `GMS uid unresolved` | 字段类型或 uid 解析异常 ⇒ 注入失效，需改实现                                   |

出现与静态结论冲突时，**以运行时为准**：静态取证只证明常规路径，排除不了云控等特殊路径的写入。

### 5.9 GcmService dump 的 Close 归因（2026-10-03 实测）

抓法（约 300 行，建议重定向到文件再 grep）：

```sh
adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService > gcm.txt
```

**单个 `Close err:N` 不能定性，必须连前后各一行一起看**。`time:S` 是被关掉的那条连接已存活的秒数，用它可以反推关的是哪一条：

```sh
awk '/Close err:/{print "CLOSE: "$0}
     /Active network|Starting parallel|Heartbeat alarm|went away/{print "      : "$0}' gcm.txt
```

| err  | 可靠的共现上下文                                                                                     | 反推的类别                       |
| ---- | -------------------------------------------------------------------------------------------- | --------------------------- |
| `27` | 上一行 `Active network switched to X, was Y` + `Starting parallel McsConnection{X} in place of existing McsConnection{Y}` | **默认网络切换**，旧连接被并行新连接取代      |
| `20` | 伴随 `Active network went away` 或一个新网络对象（VPN）出现                                                 | **旧网络对象失效**（不是"切换"，是"没了"） |
| `25` | **与 `20` 成对、且 `time:S` 完全相同**                                                                    | 与 `20` 是**同一事件的读／写两侧**，不是两次故障 |
| `6`  | 上一行 `Heartbeat alarm delay: N ms`（闹钟响了却没等到 Ack）                                              | **心跳超时**                    |

> 措辞纪律：这三个数字是 GMS 内部的 close reason，**公开渠道查不到逐条释义**。上表是从本代 ROM 的共现关系**反推**的分类，对外表述要带"反推"，不能当成官方语义。

2026-10-03 本机 2.5 小时窗口（16:27–18:54）：`err:27` ×5、`err:20` ×5、`err:6` ×2；每次重建耗时 **0.4–1.3 s**；期间真实投递全部成功（199 / 162 / 122 / 16 ms）。`bad_heartbeat_count` 全程 0、`good_heartbeat_count` 持续上升。

> **这句话只能覆盖 `err:27` 那一类，不能拿去概括所有 `Close err`。** 见 5.9.3：不同 code 的重连代价差一个数量级，`err:20/25` 的平均重建是 7–9 s、单次实测最长 148 s。判“会不会丢推送”必须先分 code，用最好的那一类的 0.4–1.3 s 去代表全部，是 2026-10-03 晚上实际踩过的误判。

**判"息屏切流量"的正确顺序**（不要凭感觉归因）：

1. 先划出 doze 区间：把 `Client Entering doze` / `Client Exiting doze` 两两配对；
2. 再看 `Active network switched` 落在区间内还是外。**落在区间外就不该往"息屏"上归因**——2026-10-03 全部 WiFi→Cell 切换都在 doze 之外，其中 16:27 一组是 58 秒内来回切 4 次，任何屏幕状态策略都做不出这个节奏；
3. 用 `settings get global wifi_sleep_policy` 直接排除 WLAN 休眠策略：`2` = 息屏也保持（本机实测值），`0` = 息屏后可断开。值为 2 时这条解释**直接出局**；
4. 剩下的嫌疑人按贡献排序查：**VPN 通道上下线 > WiFi 链路本身抖动 > WLAN 助理**。

- **VPN 是隐形大头**：本机 12 条 Close 里 4 条来自 `GcmNetwork{103/104 VPN(17) [WiFi(1)]}` 的出现与消失。注意它是**间歇**的——事后 `ip -o addr | grep tun` / `ps -A | grep vpn` 查不到，不等于当时没跑过。
- **WLAN 助理别乱当证据**：global 里的 `wifi_assistant=1` 只是主开关，子项（智能切换等）**没有稳定的 global 键可查**，另有 `wifi_assistant_full_signal_switch` 之类散键。因此 `wifi_assistant=1` **不能**当作"智能切换已开启"的证据，更不能用它反推某次切换的成因——本机用户确认智能切换是 18:30 之后才开的，而 16:27 那组抖动更早。

#### 5.9.1 用网络 ID 区分「WiFi 断了」与「只是默认网络被切走」

`GcmNetwork{N X(1)}` 里的 `N` 是 ConnectivityManager 的 netId，**一次开机内单调递增、不复用**，因此它是硬证据（2026-10-03 两台机对照）：

| 观测                                                          | 含义                                                        |
| ----------------------------------------------------------- | --------------------------------------------------------- |
| `Active network went away, was GcmNetwork{N WiFi(1)}` 之后回来的**还是同一个 N** | WiFi 链路没断，只是默认网络被临时判给了蜂窝（本机 WiFi 恒为 `102`，掉了四次都是它） |
| 回来的**是另一个 N**（实测 `111` → `113`）                             | WiFi 网络对象被销毁并重建 ⇒ **WiFi 真的断过一次**（对方机型）     |

再配合 `net=-1` 行（连蜂窝也没有）区分「只掉 WiFi」与「两个射频一起掉」。

**典型成对签名**：`err:20`（WiFi 网络消失）→ 约 3 秒后 `err:27` 且 `time:1`（刚在蜂窝上建好 1 秒的连接又被回来的 WiFi 顶掉）。对方机型 17:19 / 17:21 / 17:23 连续三组同形，18:28:58 那组是 `WiFi(111)` 消失 → 切 `Cell(112)` → 21 秒后 `WiFi(113)` 回来 → `Close err:27 time:21`。

**两个尚未定性的点，只登记不解释**：

- `err:1`：对方机型在 `net=-1` 状态下有两条（`time:14` / `time:797`），本机 2.5 小时窗口内 0 条。样本太少，别急着给它安语义。
- **恢复时刻与出睡不同步**：那台机 WiFi 在 `18:29:19` 就回来了，`Client Exiting doze` 是 `18:29:36`——**WiFi 比出睡早 17 秒**。若 WiFi 是被睡眠模式关掉、出睡再打开，恢复应与出睡同步或更晚。所以“睡眠模式干的”这件事，在拿到对方机型 LSPosed 日志之前**不能定**。

#### 5.9.2 三条排除判据（2026-10-03 拿到对方真机 dump 后补）

拿到完整 `gcm.txt` 后，“息屏切流量”能按下面三条逐层排除，每条都只看 dump 内部证据，不需要问用户：

| 判据                                                    | 怎么读                                                                                   | 该机的实测                                                                             |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| **整机断网 vs 只掉 WiFi**：看蜂窝网络对象是否还在          | `available:` 列表里 `Cell` 全程在 ⇒ 只有 WiFi 掉；Cell 也一起消失 ⇒ 睡眠模式/射频级关断 | `Cell{106}` 从 17:21 到 18:28 一直挂在 available 列表 ⇒ **排除睡眠模式**（它连数据一起关） |
| **Doze 主动关 WiFi vs WiFi 自己掉**：数 Doze 进出与断线次数的比例 | Doze 每次进出都断才可能是 Doze 关的；次数不成对即无关                                | Doze `Entering` 9 次 + `Exiting` 19 次，WiFi 只断了 3 次 ⇒ **不成对，排除 Doze**        |
| **WiFi 真断 vs 选择抖动**：netId 是否换号（见 5.9.1）        | 换号 = 网络对象被销毁重建                                                                | WiFi 依次 `109 → 110 → 111 → 113`，**每次都换号** ⇒ WiFi 链路真的断过                |

三条都过完仍指向“WiFi 自己掉”，就该去查链路本体，而不是继续在模块或省电策略里找原因。

**该机型已知条件（用户已确认，不要再重复索取）**：

- **OS3**。硬证据是 `AurogonImmobulusMode#isNoRestrictFreezeable absent`（该方法仅 OS4 存在），用户亦口头确认过两次。
- **从未开启「智能切换」（WLAN 助理的 WiFi↔数据互切）**。因此排查清单里不能再留 WLAN 助理这一项，也不能用 `wifi_assistant=1` 反推成因（见 §5.9 末）。

排除这两条之后，剩下唯一与“息屏后断 WiFi、亮屏即回”形状吻合的候选是 **WLAN 休眠策略**（`wifi_sleep_policy` + `wifi_idle_ms`）：它只在息屏后计时断开、亮屏立刻重连，与智能切换是两套彼此无关的机制。

**该值已取得（2026-10-03）**：

```sh
adb shell settings get global wifi_sleep_policy   # ⇒ 2
adb shell settings get global wifi_idle_ms        # ⇒ null
```

- `2` = `WIFI_SLEEP_POLICY_NEVER`：**息屏也保持 WLAN**。
- `null` **不等于“这个机制不存在”**——它只说明该 Settings 键从未被显式写入。读取侧是 `getLong(resolver, WIFI_IDLE_MS, DEFAULT_IDLE_MS)`，拿不到键时用**代码内默认值**，所以 null 走的是“默认时长”而不是“永不超时”。只不过 policy=NEVER 时 idle_ms 根本不进入决策，两者合起来的结论是：**AOSP 这条链路上，息屏不会因为超时断 WiFi**。

手机端自查路径：设置 → WLAN → 高级设置 →「休眠时保持 WLAN 连接」，应为**始终**。

**但这个读数不能结案**：`=2` 只排除 AOSP 路径。上一份 logcat 里 `WifiOptimizationImpl`、`AmlWifiScoreReportInjector`、`PowerInsight_WifiCollector` 这几个 tag 说明 MIUI 有自己的 WiFi 评分／优化层在跑，它未必读这个 global 键。2026-10-03 晚间已追到这条链的运行时判决，见 §5.9.3。

**第四条判据（纯 dump 内部，不必问用户）：存活时长是否规则。** 定时器机制（休眠策略 / `wifi_idle_ms`）会让 WiFi 每次存活时长相对固定；外部事件驱动（链路、路由器、信号）则大幅抖动。该机三次存活 `100 s / 797 s / 3121 s`，相差 8 倍与 4 倍 ⇒ **不是任何定时器，是外部事件**。

四条全部指向“WiFi 链路本体”之后，要抓的就是 WiFi 侧自己的断线原因，而不是继续在省电策略里找：

```sh
adb shell dumpsys wifi > wifi.txt   # 看 disconnect reason / 断线计数
adb shell logcat -d -v time -s ClientModeImpl:* WifiClientModeImpl:* WifiNetworkAgent:* > wifilog.txt
# 注意：logcat 要在断线后尽快抓，ring buffer 有限，隔久了就被冲掉
```

**该机 2.4 小时窗口的完整读数**（17:21–19:44，8 条 Close）：

| 时刻            | 事件                                                        | 重建耗时        |
| --------------- | ----------------------------------------------------------- | --------------- |
| 17:21:18        | `err:27 time:1` 切回 WiFi                                    | 0.6 s           |
| 17:22:59        | `err:20 time:100` WiFi `109` 消失 → 落 `Cell{106}`          | 1.3 s           |
| 17:23:03        | `err:27 time:1` WiFi `110` 回来                              | 0.7 s           |
| 17:36:20        | `err:1 time:797` `Disconnect since no networks are available` | 1.5 s           |
| 17:36:56        | `err:27 time:33` WiFi `111` 回来                             | 0.6 s           |
| 18:28:58        | `err:20 time:3121` WiFi `111` 消失 → 落 `Cell{112}`         | 1.5 s           |
| 18:29:20        | `err:27 time:21` WiFi `113` 回来                             | 0.5 s           |
| 19:10:05        | `err:6 time:2444` 前一行 `Heartbeat alarm delay: 2 ms`       | 0.9 s           |

`bad_heartbeat_count` 四组里三组为 0（唯一非 0 那组 `good=1, bad=2`），`heartbeat_interval` 稳定在 `230000`（实测服务端心跳间隔 229–231 s；这个值的含义见 §5.9.5，**它不代表链路劣化**），期间两次真实投递都成功（分别是 23 ms 与 58 ms，均省略包名）。**连接是健康的，这 8 条全是切换代价。**

> 这句结论同样**只对 `err:27` 那一类成立**（见 §5.9.3）：这 8 条实测重建 0.5–1.5 s，但另一份样本里 `err:20/25` 的均值是 7–9 s、单次最长 148 s。

#### 5.9.3 `Close err` 的重连代价分级（2026-10-03 晚间，同日结论的自我修正）

同一份 44 分钟本机样本（`21:21:17`–`22:04:52`，299 行）里的 25 条 `Close err`，按 code 拆开后**代价差两个数量级**：

| code       | 条数    | 共现签名 | 重建耗时（Close → Connected） | 定性 |
| ---------- | ------- | -------- | ----------------------------- | ---- |
| `27`       | 7       | 上一行 `Starting parallel McsConnection{X} in place of existing McsConnection{Y}` | 0.40 – 1.68 s（均值 **0.88 s**） | **优雅替换**：新连接先建好再拆旧的，几乎无缝 |
| `20` / `25` | 11 / 6 | **成对出现、`time:S` 相同**（同一条连接的读／写两侧各报一次）；后续是 `Connecting using …` 而非 parallel，常伴 `Reconnect alarm delay: 5 ms`，重时升级到 `FALLBACK_ALTERNATIVE_HOSTPORT` | 0.91 – 22.02 s（均值 **6.79 / 8.72 s**） | **非计划中断**：旧连接没了，要排队重连并重跑 LoginRequest |
| `26`       | 1       | 无 switch 前因 | **148.09 s** | 最贵；样本太少，只登记不定性 |

- ⇒ 这份 dump 里**累计连接不可用 281 s / 44 min = 10.8%**。所以「抖动只是 Close 多几条、不丢推送」这种概括**不成立**：每次 `err:20/25` 都有 7–9 s 的下行空窗，这段时间内 FCM 消息到不了 GMS。2026-10-03 我先在口头结论里用了“不丢推送”，当晚即被这条数据推翻，**以本节为准**。
- 计数陷阱：`20` + `25` 是**同一事件的两行**，统计故障次数要成对折算，否则次数翻倍。
- **唯一的干净对照**：同一晚 `21:40:13`–`21:47:08` VPN 在线的 415 秒内 `Close err` **0 条**，而它前后的抖动期分别是 **0.63 / 1.20 条每分钟**。同机、同 AP、同时段，唯一变量是「默认网络还抖不抖」⇒ `Close err` 与默认网络抖动是**因果，不是伴随**（VPN 把底层 WiFi↔Cell 互换挡在了 GMS 视野外）。

**由此得到的可执行结论：减少默认网络抖动会直接、显著地减少 `Close err`。** 反向也成立——看到 `Close err` 密集，第一步该去查默认网络的判决链（`AmlWifiScoreReportInjector`，即 §5.9.2 末尾提到的那条 MIUI WiFi 评分层），而不是先在 GMS 或模块侧找原因。

**本机已确证可写的旋钮**：

```sh
adb shell settings get system cloud_min_rssi_for_data_switch_5GHzwifi   # 本机实测 -72
adb shell settings get system cloud_min_rssi_for_data_switch_24GHzwifi  # 本机实测 -73
```

`AmlWifiScoreReportInjector` 就是拿这两个阈值判 `SWITCH_TO_CELLULAR_BY_LOW_RSSI`（实测触发时 `rssiScore = 47` < 50，瞬时 RSSI 约 -78/-82），切回条件则是 `isSwitchBackToMasterWifi averageRssi = -72`。滞回带只有约 6 dBm，环境 RSSI 只要在 -72 附近摆荡就会反复触发。**把 5G 那个放到 -85 即可止血**，代价是弱信号时不再自动走蜂窝；`cloud_*` 前缀意味着可能被云端下发覆盖回原值，改完要复查。

#### 5.9.4 `Received <pkg>` 不等于投递成功（2026-10-03 误判登记）

我自己在这条上判错过一次，登记在此避免重犯。

`Received com.xxx <msgid>` **只表示 GMS 收到了下行 stanza**；投递有没有到达 app，要看紧接着的广播结果。同一份样本里的实际序列：

```text
10-03 21:56:47.431 net=1: Received <示例包名> 0:1791035808234013%d88aa106f9fd7ecd
10-03 21:56:47.452 net=1: No response to broadcast from <示例包名> (id=… time=5ms priority=NORMAL)
10-03 21:58:47.457 net=1: Failed to broadcast to stopped app <示例包名> (id=… time=3ms priority=NORMAL)
10-03 22:02:47.467 net=1: Failed to broadcast to stopped app <示例包名> (id=… time=3ms priority=NORMAL)
```

dump 末尾 `Queued messages:` 里它还挂着 `reason=1 retries=2` ⇒ **直到抓日志那一刻，这条消息从未送达 app**。

- 因此 `Received` **不能**当作「推送正常抵达」的证据。判投递成功要看正向的完成行（§5.9 开头那批成功样本是带耗时的正常完成：`199 / 162 / 122 / 16 ms`）。
- `Failed to broadcast to stopped app` 是 **framework 原生行为**（Android 3.1+ 起 STOPPED 状态的包不接收广播），**既不等于模块拦的，也不等于 ROM 冻的**。归因前先查 `dumpsys usagestats` 里该包的 `lastTimeUsed`：这次涉及的是一款社交类应用，`lastTimeUsed` 落在样本日之前第 5 天（间隔 5 天以上，且期间无前台/后台任何事件），属自然闲置。
- ⇒ **这条记录对「网络抖动会不会丢推送」是零信息量的**：它证明不了“能收到”，也证明不了“收不到”。要回答那个问题，看 §5.9.3 的重连空窗。

#### 5.9.5 `HB interval sent` 由传输类型决定，不是质量信号

`Sent LoginRequest; HB interval sent: N` 里的 N **按当前网络类型取值**，同一晚 19 次 login 无一例外：

| 当前网络 | `HB interval sent` | 折算 |
| -------- | ------------------ | ---- |
| `WiFi(1)` / `VPN(17) [WiFi(1)]` | `230000` | 3.8 分钟 |
| `Cell(0)` | `1680000` | 28 分钟 |

⇒ **看到 `230000` 不要读成“GMS 检测到不稳所以缩短心跳”**。它只说明这次 login 走在 WiFi 上。这条是当晚差点犯的误判：HB 在 230 s 与 1680 s 之间来回跳了 19 次，形状极像自适应降级，实际是纯 transport 映射（蜂窝按流量与 NAT 超时更长，WiFi 走密集心跳）。

#### 5.9.6 多条切换理由共用同一个出口，掐住出口即可（2026-10-04 实测）

上一节只讲了"`AmlWifiScoreReportInjector` 按 RSSI 判"，那是**不完整的**。真机验证后发现它至少两条独立的切换理由，且都汇到同一个出口：

| 理由码 | 触发条件（实测日志） | 备注 |
| ------ | -------------------- | ---- |
| `SWITCH_TO_CELLULAR_BY_LOW_RSSI` | `writeNetworkRatingData` 里 rssiScore < 50 | 昨晚记录到的那种 |
| `SWITCH_TO_CELLULAR_BY_POOR_QUALITY` | `linkLayerFailpercent >= 85` → `linkLayerFadingScore = 11`，伴 `weightedAverageTxFailPercent = 96 successfulLinkSpeed = 3` | **同一台机、同一局游戏换挂载后出现，基线日志里 0 次** |

两条理由都会把 `mLegacyIntScore` 压到 50 以下，而 **`AmlMiuiThirdPartScorer` 只认分数**——`score < 50` 才走 `report new wifi score < 50 isUsable = false`。所以：

- ⇒ **不要按理由码逐个拦**。在出口（§5.9.3 末尾那条 hook）把分数抬过 50，两条理由一起失效。
- ⇒ **看到 `SWITCH_TO_CELLULAR_BY_POOR_QUALITY` 说明这条链路的质量是真的差（tx 失败率 96%、有效速率 3 Mbps），不是误判。模块此时是"让设备留在一条确实很烂的 WiFi 上"，是否符合预期由使用者判断。
- ⚠️ **未铰住的旁路**：ROM 还会调 `notifySwitchNetworkByOtherStrategies type = 1003`（实测 27 次）。它绕过了分数通道，本次没造成切换，但**不能保证永远无害**。已作为长期观察项登记在 §7.5，**排查触发规则也写在那儿**——将来遇到「分数被压住了却仍切走」，先查它。

**验证结果（2026-10-04 00:31–00:47，同一台机、同一 SSID、同一款前台游戏）**：ROM 侧产生 **33 个 <50 的低分样本（最低 38）**，而 GMS dump 里 `Active network switched` **0 次**、`Close err` **0 次**、`connects=1 failedLogins=0`，心跳按 230 s 稳定走完全程。**对照组是同一晚更早的基线：12 个低分样本 12 次全部触发切换**（每个 `updatedScore<50` 的后两行里必有 `isUsable = false`）。

`AmlMiuiThirdPartScorer` 住在 `/system_ext/framework/miui-wifi-service.jar`，**不在 system_server 的 classpath 上**。`classLoader.loadClass` 必定 ClassNotFound，会误报 absent —— 实际类正在跑。取它的正确姿势见 §5.9.7。

#### 5.9.7 hook 不在 classpath 上的 ROM 类（2026-10-04）

`AmlMiuiThirdPartScorer` 由 `miui-wifi-service.jar` 提供，该类在 system_server 里可见，但**不在它的 classpath 上**，所以：

1. 不能用 `classLoader.loadClass`，要借已注册 binder 的类加载器：`ServiceManager.getService("MiuiWifiService")` → `binder.javaInterface` 或直接取 `(Binder)` 侧的 Loader；候选服务名有 `MiuiWifiService` / `AmlConnectivityService` / `MiuiNetPathOptimizerService`。
2. **必须加退避重试**：开机早期 wifi 服务还没发布，`getService` 返回 null。实测 3 s × 40 次足够。
3. absent 时要分清是"这台 ROM 没有"还是"服务还没起来"——日志措辞必须区分，否则会把时序问题误记成代次差异。

#### 5.9.8 充电整夜样本：零切换、4 次 `err:6`，两个实验开关都没被检验到（2026-10-04 凌晨）

数据源：`gcm20261004.txt`（`dumpsys activity service com.google.android.gms/.gcm.GcmService`，覆盖 `03:47:10–09:05:25`，200 行历史）+ `modules_2026-10-03T14_24_24.log`。这一夜**充电、息屏到天亮**。

**(a) 网络侧：观测窗口内零切换。** 历史里只出现过一个网络对象 `GcmNetwork{101 WiFi(1)}`；`onGcmNetworkChanged` 共 4 次，全是建连后自报的同一个 WiFi；`Cell(0)` 一直在 `available` 但从未成为默认。按 §5.9.2 的四件套：Cell 常在 ⇒ 排除睡眠关网；netId 未换号 ⇒ WiFi 没真断。

**(b) 但仍有 4 次断连，全是 `Close err:6`**，即 §5.9.3 里"心跳没等到 Ack"那一类：每条前一行都是 `Heartbeat alarm delay: -N ms` 之后发出 `Sent Client HB`，此后服务端心跳也不再来，客户端在下一次 alarm tick（约 60 s 后）才发现。`time:S` 是被关掉那条连接的寿命（用它反推的建连时刻与实际 `Connected` 行完全吻合）：

| 关闭时刻 | `time:`（连接寿命） | 上一条入向 | 重建完成 | 下行不可用区间 |
| -------- | ------------------- | ---------- | -------- | -------------- |
| 04:15:34 | `6343` | 04:10:13 | 04:15:35 | 61 – 322 s |
| 04:46:54 | `1878` | 04:41:32 | 04:46:55 | 61 – 323 s |
| 06:56:09 | `7754` | 06:50:02 | 06:56:10 | 61 – 367 s |
| 09:05:24 | `7753` | 09:00:02 | 09:05:25 | 61 – 323 s |

- ⇒ **5h18m 内累计下行不可用 244 – 1335 s，占 1.28% – 6.99%**（每次 1–6 分钟）。区间而非定值的原因：只能确证链路在上一条入向时还活着、且最迟活到客户端心跳发出前，实际的断裂时刻落在这两者之间。
- 故障间隔 **1880 / 7755 / 7755 s**：后两次只差 0.1 s，形状像某种约 2 小时 9 分的周期，但**只有两个周期、三组样本，不足以定性**，登记为待复采的观察项，暂不动代码。
- **这一夜在"投递成功率"上是零信息**：整份 dump 里非心跳的 `Received` 只有 4 条 `IqStanza`（每次建连后各一条），**没有任何一次真实投递**发生过——既不能证明"能收到"，也不能证明"收不到"。

**(c) 「放宽 WiFi 弱信号切换」这一夜没有触发，因而没有被检验。** 模块侧 25 次 `wifi-weak-signal: reported N would have failed…`（N = 41–49）**全部落在 00:28:51–00:59:29**；`01:00–09:05` 一条都没有。该行按 30 s 节流（`WIFI_SCORE_CLAMP_LOG_INTERVAL_MS`），所以"零行"可反推出**这 8 小时 ROM 从未上报过 <50 的分数**。⇒ 此刻可引用的实证仍是 §5.9.6 那局（33 个低分样本零翻转，对照同晚基线 12/12 全切）；本夜只能写"无可用样本"，不能写成"又一次验证通过"。

**(d) 睡眠保活（主开关 + 移动数据子开关）同样没被检验**——而且这次能定死，不像 (c) 只是"没触发"。整夜 **0 条 `sleep-mode:` 运行时行**；这条支路在 §4.6.1 的表格里设计成"每次 cutoff 调用必打印一行"（放行与拦截各有自己的措辞），且 00:31:31 的安装行走的是**正常路径**（不是 `cutoff hooks incomplete, degraded`），因此唯一与日志自洽的解释是：**ROM 这一夜根本没有执行 `applySleepConfig` 的关网段**，不是"钩子把网悄悄保住了"。反向可确证：若是 §4.6.1 记录过的那种整机关网，`03:47–07:08` 这段 GCM 会是 `net=-1` 空白，实际是 230 s 心跳连续不断。

⇒ 结论写给后来人：**充电整夜不构成对睡眠保活的检验**。这也是"整夜验证必须断 USB、不充电"那条老规矩的实证依据（此前只有规矩没有数据）。下一轮请拔掉充电器再挂一夜。

**附带记录——00:31:11 有一次 framework 软重启**（新 `system_server` pid 31556 `uptimeMs=364`，systemui / powerkeeper / health / settings 一并重载）。重启后自检正常：system_server **29 hooks / 0 absent**，powerkeeper **18 hooks / 10 absent**（10 个 absent 全是 `NetdExecutor` ×2 + `GmsObserver` ×8，本 ROM 已知缺失，非回归）。**19 → 18 的差值不是丢钩**：14:25 那条 `PowerSaveConfigureManager#setPowerSaveAppConfigure hooked (wechat-shield, …)` 来自彼时还在的旧 APK，该钩在 HEAD 里已被 `CommonAdapter#addPowerSaveWhitelistApps`（doze-keepout）取代，源码里已无此字符串（`git grep PowerSaveConfigureManager HEAD` 为空）。

#### 5.9.9 游戏时段实时取证：WiFi 评分劣化没有传导到 FCM（2026-10-04 上午）

这是第一次在**前台活跃（用户在打游戏）**的窗口里同时挂三路采集，目的就是回答"WiFi 质量变化会不会引发 FCM 报错"。18 分钟窗口 `09:39:51–09:57:39`，三条数据源互相对齐：

| 数据源 | 命令 | 开销 |
| --- | --- | --- |
| ROM 评分流 | `adb shell "logcat -v threadtime | grep -E 'AmlWifiScoreReportInjector\|AmlMiuiThirdPartScorer\|WifiScoreReport\|notifySwitchNetworkByOtherStrategies\|Setting inactive\|Switching to new default\|NetReassign'"` | 设备端过滤，很轻 |
| 链路指标 | `adb shell cmd wifi status`，每 5 s 一次 | **极轻**，见下 |
| FCM | `adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService`，每 60 s | 重，勿加密采集频率 |

**新发现的数据源**：`cmd wifi status` 一条命令就能拿到 `RSSI` / `score` / `isUsable` / `Link speed` / `Calculated Tx|Rx` / `lostTxPacketsPerSecond` / `retriedTxPacketsPerSecond`，**比 `dumpsys wifi` 轻得多**，适合在前台活跃窗口里做秒级采样（`dumpsys wifi` 在这个场景下太重）。

结果：

- **ROM `rssiScore` 在 36–60 之间剧烈振荡**，971 个样本里 **169 个低于 50**，最低 `09:53:50 = 36`。`writeNetworkRatingData` 的 RSSI 字段对应 -87 ~ -65 dBm。
- 模块共 **14 次**打印 `wifi-weak-signal: reported 48/49 would have failed the ROM's own usability floor of 50; reported as usable instead`（30 s 节流后的行数）。注意这句措辞的读法：**48/49 是原始分数**，钩子（§源码 `hookE(notifyMethod)`）在 `notifyScoreAndIsUsable()` 前把 `mLegacyIntScore` 钳到 `WIFI_SCORE_USABLE_MIN`（= 50）、`finally` 里还原原值——**不是"把分数抬过去"，是"让 ROM 在这一次上报里看到 50"**。
- **`notifySwitchNetworkByOtherStrategies`：type=1001 ×4、type=1003 ×44**，全部集中在低分时段。
- **切换次数为 0**：没有任何 `Wifi is set to exiting` / `+EXITING` / `Setting inactive` / `Switching to new default`。整个过程 `isUsable` 恒 `true`，`GcmNetwork{101 WiFi(1)}` 从头到尾没变过。
- **FCM 零报错**：20 条入向心跳间隔**恒定 230/231 s**，全程零 `Close err`。唯一的 553 s 缺口是 `09:05:24` 那次已知的 `Close err:6`（§5.9.8(b)），**发生在本窗口开始之前**。

⇒ **在本窗口内，WiFi 评分反复穿越并远低于可用性下限（最低 36），没有产生任何一次默认网络切换，也没有产生任何一次 FCM 连接层事件。** 这是一条能写的正向结论，但它是"开关开启"的样本——**不能用它反推"不开也会这样"**，对照组仍然缺失（同一台 ROM 上 §5.9.6 记录过基线：12 个低分样本 12 次全切）。

**给 §5.9.6 遗留项的补充数据**：`notifySwitchNetworkByOtherStrategies type = 1003` 这条"绕过分数通道的第二出口"本窗口出场 **44 次却零切换**，是迄今最强的一次"未观察到切换"证据。但定性不变：**仍是观察项，不是已关闭项**——44 次零切换只能说明"这条通知本身不等于切换"，不能证明"它永远不会导致切换"。继续保持 §7.5 末尾那条排障硬规则的第一步检查。

**两个取证陷阱，都踩到了**：

1. **设备端 logcat 的过滤串会被 `adbd` 回显**：`grep -c "Setting inactive"` 报 4 条，全是 `adbd: adbd service requested 'shell,…'` 把我自己的过滤表达式打了一遍。统计前必须排除 `adbd` TAG，否则会把"我的命令"当成"ROM 的事件"。
2. **logcat 缓冲区在前台活跃场景撑不住**：开始前 `logcat -d` 有 15.8 万行，但 main buffer 只覆盖到最近 **3 分钟**（被游戏日志冲掉）。想追历史事件必须实时落盘，事后 `-d` 拿不到东西。

~~**边界脆弱点（登记，未修）**：钩子把分数钳成 **恰好 50**，而 ROM 的出口条件是 `mLegacyIntScore < 50` ⇒ 依赖"严格小于"。若某代 ROM 改成 `<= 50`，这个干预会当场失效且不报错（日志照打 `reported as usable instead`，但实际已被判 unusable）。要加固的话，钳到 `50 + 1` 或改成直接改写 `isUsable` 形参，两者都是行为变更，需先拿到用户同意。~~

**边界脆弱点已修（2026-10-04，用户拍板）**：钳位目标从恰好 50 改为新常量 `WIFI_SCORE_CLAMP_TARGET = WIFI_SCORE_USABLE_MIN + 1`（= 51）。分开这两个值是这次的要点——原来 `WIFI_SCORE_USABLE_MIN` 一身兼二职：既是 ROM 的判据值（不该由本模块拥有），又是我们写入的目标。现在前者仍是 50 并且注释写明"不是本模块的可调旋钮"，后者是 51，两种比较（`< 50` 和 `<= 50`）都能过。对当前 ROM 的最终结果没有差别。**注意日志措辞随之改变**：旧文案是 `reported 49 would have failed the ROM's own usability floor of 50`（那句话里的 49 是原始分，曾让我自己误读成"抬到 49"），新文案为 `reported 49 met the chosen floor 45 but would have failed the ROM's 50; reported as usable instead`。

#### 5.9.10 第二轮游戏局：ROM 算出 56 次切换理由，一次都没执行（2026-10-04）

在 §5.9.9 那局之后，删除充电子开关并重装（`generation 2`）后又跑了一局真实网络游戏。开局锚点 **10:25:28**，四路采集窗口 **10:20:00–10:51:17（31.3 分钟）**，数据分析脚本在 `.workbuddy/tmp/game20261004-run2/`。

| 指标 | 结果 |
| --- | --- |
| ROM `rssiScore` | **44–60**，1245 个样本，**50 个低于 50**（44×3 / 45×3 / 46×2 / 47×9 / 48×6 / 49×27），最低分 `10:25:29` |
| 链路实测 | RSSI **-86 ~ -66 dBm**（中位 -74），Link speed 144–864 Mbps |
| **ROM 算出的切换理由** | **`SWITCH_TO_CELLULAR_BY_LOW_RSSI` × 50 + `SWITCH_TO_CELLULAR_BY_POOR_QUALITY` × 6** |
| **实际切换** | **0 次** |
| 模块干预 | 24 行 `wifi-weak-signal: reported 43/47/49 … reported as usable instead`（30 s 节流后） |
| `notifySwitchNetworkByOtherStrategies` | `type=1001` ×6、`type=1003` ×168 |
| FCM | 27 条入向心跳，间隔**恒定 230/231 s**，超周期 0 次，`Close err` 0 条（窗口前那条既知故障不算） |

**这局比 §5.9.9 强的那一点**：§5.9.9 只看到"评分低但没切"，本局直接读到了 ROM 的**意图**——它老老实实算出了 56 次 `SWITCH_TO_CELLULAR_BY_*` 理由，**每一次都没走到 `Setting inactive`**。也就是说 §5.9.6 那条"两条理由共用同一个出口"的判断在这里被正面确认：`BY_LOW_RSSI` 和 2026-10-03 夜间才首次现身的 `BY_POOR_QUALITY`（linkLayerFailpercent ≥ 85，见 §5.9.6）**两条路同时撞在同一个出口上，同一个钩子把两条一起掐住了**——这正好是当初选择"抬分数掐出口"而不是"逐条拦理由码"的理由。

**局限照写**：仍是**开关开启**的样本，不能反推"不开也一样"。基线对照仍然只有 §5.9.6 那一晚（12 个低分样本 12 次全切）。

**第二个出口的两个 type**：`type=1001` 在上一局就出现过 4 次（见 §5.9.9），本局 6 次，同样来自 `AmlMiuiThirdPartScorer`（与 1003 同一个 TAG）：（和 1003 同一个 TAG），本局 6 次。核对过它与 `BY_POOR_QUALITY` 的时刻**并不吻合**（6 次 1001 落在 10:20:28 / 10:25:11 / 10:26:07 / 10:35:16 / 10:38:36 / 10:41:55，而 6 次 POOR_QUALITY 是 10:20:33 / 10:20:34 / 10:41:57 / 10:41:59 / 10:42:03 / 10:49:20）⇒ **不能写成"1001 是 POOR_QUALITY 的专用出口"**。定性依旧：观察项，不是已关闭项。

**第三个取证陷阱（本局新增）**：`ConnectivityService` 的 **`NetReassign` 不能当切网判据**，一局能刷出上千条：

- `NetReassign [no changes]`（本局 1211 条）— 每次网络能力重评判都打，**字面意思就是"没动"**；
- `NetReassign [reqId : null → 101]`（本局 59 条，其中 →WiFi 12 / →Cell 47）— 这是 **NetworkRequest 级别的重分配**（reqId 一路递增到 2600+），不等于默认网络切换；本机 Cell(0) 一直 available，单个 request 被分到 Cell 是常态。

⇒ **判"是否被切走蜂窝"只认这四条**：`Setting inactive [NNN WIFI]` / `Switching to new default` / `+EXITING` / `Wifi is set to exiting`。把 `NetReassign` 算进去会得到 1270 次"切换"的荒谬结论。

**操作禁忌（本局确认）**：**游戏进行中禁止 `adb install -r`**。装 APK 会让 LSPosed 重载 system_server 与 powerkeeper，正好把整局数据搅掉。本局的 APK 就是先编译好、等用户退出后才安装的（安装后 `generation 4`）。

#### 5.9.11 子选项「保留 WiFi 的最低评分」：权衡范围与带来的行为收窄（2026-10-04）

截至 §5.9.10，该开关的行为是**二值的**：ROM 给分低于 50 就一律改判可用，49 与 33 得到完全相同的处理，没有"救到多深为止"这一概念。本日为它加了唯一一个梯度的旋钮 `wifi_weak_signal_floor`。

**语义**（`Hooker.kt` 的 interceptor）：

| ROM 原始分 | 行为 |
| --- | --- |
| ≥ 50 | 不介入——本来就是 ROM 的可用判决，改写它等于替 ROM 造一个它没有做出的决定 |
| `[floor, 50)` | 钳到 `WIFI_SCORE_CLAMP_TARGET`（= 51），调用返回后 `finally` 还原 |
| `< floor` | 原样放行，`noteWifiScoreSkip` 记一行 |

可选 `45 / 40 / 35 / 30`，**默认 45**。读取失败同样落到这里——也就是**最窄**那一档，而不是最宽：一个读不出来的值和一个已被退役选项表移除的值，在这里长得很像，只有前者是重读一次就能恢复的，所以两者都按默认处理，且不可能往"更深"的方向猜。

**这是一次行为收窄，必须写进 release notes**：升级前"一律保"，升级后默认"≥45 才保"。用已有两局样本量化它的落差：

| 样本 | 总数 | <50 | <45 | <40 | <35 | <30 |
| --- | --- | --- | --- | --- | --- | --- |
| §5.9.9 那局 | 1409 | 188 | **67** | 25 | 4 | 0 |
| §5.9.10 那局 | 1245 | 50 | **3**（全是 44） | 0 | 0 | 0 |

⇒ 默认档在 §5.9.10 那局只放弃 3/1245 个采样时刻，但在 §5.9.9 那局会放弃 67/188，也就是**三分之一的低分事件**。"轻微下调"是相对于完全不加限制而言，不是在所有场景都轻微。**30 这一档目前零实测样本**（两局最低分别是 33 与 44），它作为刻度末端提供，并未被测量证明过。

**两个分支都留了正记录**，各带 30 s 节流且**各自独立计数**：共用一个窗口会让更吵的那一路把另一路饿死，稀有的那一路就会看起来"从没发生过"。

- `wifi-weak-signal: reported 49 met the chosen floor 45 but would have failed the ROM's 50; reported as usable instead`
- `wifi-weak-signal: reported 38 is below the chosen floor 45; left to the ROM's own policy`

第二条的存在是刻意的，否则就会退化成"日志没出现 ⇒ 钩子没生效"这类误读。**它的上界和下界同等重要**：分数本就 ≥50 时什么都不写，否则一条安静的链路也会每 30 秒产出一条伪装成"干预"的日志。

**仍未取证**：默认 45 乃至任一档位的实际效果目前只有推演，没有实测对照。最低限度的验证是等 WiFi 跌到 50 以下时看两类日志出现的是哪一类，口诀：

```
adb logcat -s LSPosedLogDaemon | grep -a wifi-weak-signal
```。

#### 5.10 向终端用户采集 FCM 诊断文本（2026-10-03）

**先确认拿到的是不是诊断本体**（2026-10-03 实测踩坑）：用户回传的“FCM 日志”常常是 `logcat` 全量 dump（几十 MB、几万行），里面**没有** `Close err:` 与 `GcmNetwork{`，而判“WiFi 真断 vs 只是默认网络被切走”全靠这两个字段。收到文件先 grep 一下，缺了就按方式 B 重抓，别在 logcat 里翻半天。

```sh
grep -c "Close err:" gcm.txt   # 0 ⇒ 这不是诊断本体
```

**采集时机（比命令本身更要紧）**：

1. 抓之前**不要重启**——`dumpsys` 读的是 GMS 进程内存里的环形事件历史，重启即清空；
2. 先**复现一次**（息屏放几分钟 → 亮屏）再抓，否则抓到的窗口里没有那次切换；
3. 让他顺手记下**息屏与亮屏的大致时刻**，否则拿到文本也无法与 Close 时间戳对齐。

**三条采集路径**：

| 路径          | 前提                  | 操作                                                                                                | 产出          | 覆盖面                                                                              |
| ------------- | --------------------- | --------------------------------------------------------------------------------------------------- | ------------- | ----------------------------------------------------------------------------------- |
| A 界面分享     | 无                    | 诊断页右上角菜单 → 分享 / 复制到剪贴板 → 粘到备忘录导出 `.txt`                                     | 纯文本        | 只含界面显示的条目；菜单项名称随 GMS 版本变（Share / Copy to clipboard），**并非每个版本都有** |
| B adb（推荐）  | 电脑 + USB 调试       | `adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService > gcm.txt`                | 纯文本        | 与本机取证同源，含 netId、`Close err:N`、心跳延迟，比界面全                        |
| C 手机端自抓   | 已 root，或装了 Shizuku | `su -c 'dumpsys activity service com.google.android.gms/.gcm.GcmService > /sdcard/Download/gcm.txt'`；Shizuku + Termux 用 `rish` 起 shell 后同样执行 | 纯文本        | 同 B，免去电脑                                                                      |

**判读所需的最小集合**（缺一项就只能停在“无法定性”）：

1. 上面那份 `gcm.txt`（或界面全文）；
2. **系统版本**：设置 → 我的设备 → Android 版本与 HyperOS 版本（决定睡眠保活是不是静默空转，见 §7.5）；
3. **两个开关的截图**（原第三项"仅在充电时"已于 2026-10-04 删除）：睡眠保活主开关 / 移动数据子项；
4. **LSPosed 导出的 `modules_*.log`**（`/sdcard/Download/…/log/`），重点三行：`Sleep-mode legacy per-uid chain armed`、`cutoff hooks incomplete, degraded`、`sleep-mode: kept WiFi on`；
5. `adb shell settings get global wifi_sleep_policy`（`2` = 息屏也保持 WLAN，可直接排除休眠策略这条解释）。

**隐私提醒**：dump 里含 android_id / 设备 ID、包名列表与服务端地址。转发前删掉 Device Id、android_id 这两行不影响判读，其余（netId、err 码、时间戳）才是要紧的。

---

## 6. 兼容性

### 6.1 ROM 代次差异（OS3 / OS4）

| 差异点                                                                                                  | 处理方式                                         |
| ---------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `PowerKeeperAppConfigure#fillScenarioContent` 7 参（OS3）/ 8 参（OS4，尾部多一个 `Map`）                         | 按参数个数两组都钩，`PowerKeeperAppConfigure` 始终是第 2 参 |
| `AurogonImmobulusMode#isNoRestrictFreezeable(String,int)` 仅 OS4                                      | 缺失走 `logSkipOtherGeneration`（DEBUG）          |
| `AppStandbyController` 下游混淆 letter（OS3 `s:`，OS4 `r:`）                                                | 不硬编码，只钩稳定的 `setUidState(IZ)V`                |
| `GmsObserver$i` 内部类序号漂移                                                                              | 遍历 1..8，按"声明了 `googleNetworkDisconnect`"定位   |
| `AMS#broadcastIntentWithFeature` 多组签名                                                                | 三组候选依次尝试 + `broadcastIntent` 回退              |
| `getRecordForAppLOSP` / `getRecordForAppLocked`                                                      | 前者优先，后者回退，都没有则降级 binder uid                  |
| `ListAppsManager.mSystemBlackList` / `SYSTEM_BLACK_LIST`、`mUseDataWhiteList` / `USE_DATA_WHITE_LIST` | 字段名两个候选都试                                    |
| `PolicyManager` 实现二选一（Domestic / International）                                                      | **按代次分别覆盖**，不假设只有一个生效                        |

最后一条是最重要的一条：**CN ROM 走 Domestic 实现，`InternationalPolicyManager` 从不实例化**，挂在它上面的 `isPushApp` 钩子在本类 ROM 上方法体永不执行。因此 P0 网络限制必须补 `DomesticPolicyManager#isRestrictNet`，而不能只在 International 侧做——历史上正是漏了这一侧，导致帮助页承诺的"推送免网络限制"在 CN ROM 上从未落地。

### 6.2 缺失目标的降级

每一个钩点组独立 `try/catch`，缺失按性质分两级：

- **`NoSuchMethodException` / `NoSuchFieldException`** → `logSkip` 或 `logSkipOtherGeneration`，计入 `hookTargetsAbsent`，继续装下一个；
- **`ClassNotFoundException`** → 通常 ERROR（说明域选错了或代次跳变太大）；

**任何一个钩点失败都不影响其余钩点**。装机摘要行里的 `M target(s) absent` 就是这个降级计数的呈现，它本身是兼容性健康度指标：`M` 突然增大意味着 ROM 代次变了。

### 6.3 平台与工具链

| 项                                     | 值                                     | 说明                                                                |
| ------------------------------------- | ------------------------------------- | ----------------------------------------------------------------- |
| `minSdk` / `targetSdk` / `compileSdk` | 35 / 37 / 37                          | HyperOS 3+ 至少是 Android 15，直接砍掉 pre-35 的边缘处理                       |
| ABI                                   | 仅 `arm64-v8a`                         | HyperOS 模块只需要 arm64                                               |
| Java / Kotlin                         | Java 21 / Kotlin 2.2.10               |                                                                   |
| libxposed                             | `api:102` compileOnly + `service:102` | `setId` 与热重载能力依赖 API ≥ 102，代码里做 `apiVersion >= 102` 判断            |
| `minifyEnabled`                       | release 为 true                        | 入口类必须保持 public + 无参构造（proguard 规则）                                |
| `static final` 写入                     | Android 新版本限制                         | `UnsafeUtils` 检测 `CINNAMON_BUN` / `BAKLAVA` preview 并切换 Unsafe 路径 |
| `/proc/net/tcp`                       | 不可用                                   | system_server SELinux 域无读权限；且答非所问（见 10.2）                         |

---

## 7. 异常处理与失效模式

### 7.1 分层隔离

```
hookSystemServer
  ├─ try { hookAllowlist()             } catch → ERROR "Failed to hook allowlist receiver"
  ├─ try { hookGreezeManagerService()  } catch → ERROR …
  ├─ …（每个组一个独立 try/catch(Throwable)）
  └─ logSummary("system_server")
```

`hookPackage` 与 `hookSystemServer` 内部同理。外层 `onSystemServerStarting` 再包一层，确保**装载失败不会导致 system_server 崩溃**——这是 Xposed 模块最基本的安全边界。

### 7.2 回调内约束

每个 `intercept` 内部的标准形态：

```kotlin
try {
    // 判定 + 改写
} catch (t: Throwable) {
    log(Log.ERROR, TAG, "…failed", t)
}
chain.proceed()      // 或返回已计算的结果
```

三条纪律：

1. 异常**不得**穿透回调；
2. `chain.proceed()` 必须被调用（或用改写后的参数调用），不允许因为判定失败而吞掉原调用；
3. 构造后清理类钩子用 `try { proceed() } finally { 清理 }`，保证即使原构造抛异常，清理仍然执行。

### 7.3 fail-open 与 fail-closed

| 位置                      | 方向                                      | 理由                          |
| ----------------------- | --------------------------------------- | --------------------------- |
| 白名单为空                   | **fail-open**（全放行）                      | 契约承诺"一个都不勾选 = 全部放行"         |
| 远程 prefs 读取异常           | 保留旧值，不清空                                | 宁可偏松不可丢推送                   |
| `gmsUid()` 解析失败         | 跳过本次注入并 WARN                            | 注入集合需要 uid，降级为空操作好过注入错误 uid |
| `getSystemContext()` 失败 | 回退 `getPowerKeeperContext()`，都没有则放弃本次动作 | 两个进程的 Context 不同源，都要缓存      |

偏松的代价是额外耗电，偏紧的代价是丢推送。**凡是无法确定的场合一律偏松**，这是本模块固定的取舍方向。

### 7.4 返回值安全零值

`skipValueFor` 按返回类型生成：`boolean→false`、`int→0`、`long→0L`、其它基本类型类推；`void` 与引用类型→`null`。

注意 `PolicyMaker#isAllowFreeze` 返回 `int`（`CANNOT_FREEZE` 常量），`triggerQuickFreeze` 返回类型不确定——两者都用 `skipValueFor(method.returnType)` 而不是硬编码，这样代次漂移改变返回类型时不会返回错误的零值。

### 7.5 已知残余缺口

| 缺口                              | 影响                                     |
| ------------------------------- | -------------------------------------- |
| `setUidState` 带外限制 + 缓存为 true   | 即使传入 `allow=true` 也会短路，无任何机制解除（P4 不覆盖）。PowerKeeper 内部已证唯一收敛（4.5 D3），带外面只能来自 system_server / netd，四项免 root 判据可查 |
| P4 触发面只有睡眠退出                    | **设计选择而非缺口**：网络切换 / GMS 重建 / NAT-FW 老化三类诱因均由 GMS 自身重连覆盖（4.7 实测心跳证据） |
| 冷启动读静态字段假阴性                     | 开机早期的 `mMessageApp` 读数不可信              |
| 睡眠保活的栈谓词是 OS4 专用                | 两个拦截点都要求栈上存在 `PhoneSleepModeController#applySleepConfig` / `#restoreSleepConfig`（OS4/V816 的睡眠实现，见 4.6.1）。睡眠断网若长在别处（OS3 的 system_server per-uid 链，见 4.6），谓词永不命中 ⇒ 「睡眠不断网」开关**静默空转**：安装日志照样报已挂载，射频照旧被关。判分叉哪一侧看 `Sleep-mode legacy per-uid chain armed: whitelist=…, chain=…` 与两条 `legacy path FIRED` 哨兵，**不看开关状态**（2026-10-03 由"开了睡眠不断网仍被切 WiFi"的用户报告引出，待对方机型 LSPosed 日志定性） |
| `notifySwitchNetworkByOtherStrategies type = 1003` 未铰住 | 「放宽 WiFi 弱信号切换」掐住的**只是分数出口**（§5.9.6）。ROM 另有绕过分数通道的旁路出口，同一局游戏实测调用 27 次，本轮未造成切换。**这是长期观察项，不是已关闭项**（2026-10-04 由用户指定登记，要求后续排障时优先想起） |

历史缺口 `enablemiuistandby enable` 静默跳过、`checkWakePath` 拒绝只记前 10 次、allowlist 首读成功无日志三项已于观测侧修补关闭（见 11.1），不在上表重复列出。

**应用侧判据面（2026-10-03 取证，供后续复核，不构成待办）**：

- 自启动 AppOps 10008 共四类检查点：①广播 `BroadcastQueueModernStubImpl#checkApplicationAutoStart`（模块已钩）；②服务绑定 `AutoStartManagerServiceStub#isAllowStartService`（上游 SyncManager / AccountManager）+ `JobServiceContextImpl#checkIfCancelJob`；③进程重启 `ProcessManagerService#isAllowAutoStart`（上游 `ProcessStarter` 一族 + `ProcessPolicy`）；④通用查询 `AppOpsServiceStubImpl#isOpAllowedForUid`。GMS 声明的 c2dm action 只有 RECEIVE（投递）/ REGISTER / UNREGISTER（应用→GMS）⇒ **投递面只需放行 RECEIVE**，②③是 Firebase token 维护走的路径，与投递无关。
- stopped 标记的清除点：`AMS#addAppLocked`、`ActiveServices#bringUpServiceInnerLocked`（⇒ 点一次图标即恢复）。**不建议走的路**：主动写 false、钩 `PackageManagerService#isPackageStoppedForUser` 返 false（只读欺骗，牵连 `AppWidgetServiceImpl`）、跳过 `AMS#forceStopPackage`、拒 `IPackageManager#setPackageStoppedState(true)`——根因在严格模式收窄（见 4.5 D1 与 §四 口径）。
- `immobulus_mode_switch_restrict` 实测值包含 `com.google.android.gms`。

**旁路出口的排查触发规则（2026-10-04 登记，长期有效）**：

`notifySwitchNetworkByOtherStrategies type = 1003` 是 ROM 里**绕过 `mLegacyIntScore` 分数通道**的第二切换出口，模块没有铰它。它至今一次都没真的造成切换，但那只是"没观察到"，不是"不会"。所以定下这条硬规则：

> **只要出现「低分样本零翻转、分数出口确认被压住了，却仍然被切走蜂窝」这个矛盾组合，第一步就是查旁路出口，不要先怀疑分数 hook 失效。**

```sh
adb logcat -d -v time | grep -a "notifySwitchNetworkByOtherStrategies"
# 命中后取 type 值，再看同一秒前后是否有 Setting inactive / Switching to new default
```

判定与处置：

- `type = 1003` 出现**但没有**跟随 `Setting inactive` / `Switching to new default` ⇒ 本轮无害，继续观察，不动代码。
- 一旦出现"旁路调用后真的切走了" ⇒ 说明分数出口已经不是唯一切换路径，此时**必须重新评估**「放宽 WiFi 弱信号切换」的覆盖面：选项是把 hook 上移（拦 `notifySwitchNetworkByOtherStrategies` 本身），或明确告知使用者该开关只覆盖弱信号这一条理由。**在此之前不要声称该开关能止住所有抖动。**

### 7.6 收口面与粒度（勿重复造轮子）

**UID 层收口天然存在，不要自建防火墙。** 已否决方案：自建 netd 链 + 仅 allow GMS。理由——系统本就有两个收口层，实测 GMS 在两层都已放行：

| 层                                        | 读法                                                     | GMS 实测             |
| ---------------------------------------- | ------------------------------------------------------ | ------------------ |
| netd dozable 链                           | `dumpsys network_management` 的 `UID firewall dozable rule`（1=ALLOW / 2=DENY），**只在 Doze idle 期间 enabled** | 放行                 |
| netpolicy 策略                             | `dumpsys netpolicy` 的 `policy=`                          | `4`                |
| 待机桶                                      | standby bucket                                          | `5 (ACTIVE)`       |
| deviceidle 名单                            | `dumpsys deviceidle whitelist`（user / system / system-excidle 三段） | 三段全在              |
| 自启动 AppOps                               | `cmd appops get <pkg>` 的 `MIUIOP(10008)`                | `allow`             |
| 免限名单                                    | `settings system MILLET_NO_RESTRICT_APP`                 | 含 GMS              |
| 冻结面                                      | `dumpsys greezer` 的 per-uid 记账                          | `frozen=0s`、不进冻结路径 |
| 声明面                                      | `declaresFcmComponent(GMS)`                              | `true`（声明 c2dm RECEIVE + RECEIVE_DIRECT_BOOT）⇒ 强停防护对 GMS 实际生效 |

**两份"白名单"实测不等价**（2026-10-03）：设置里看到的「电池优化」= `dumpsys deviceidle whitelist`，真正掐网的是 netd dozable 链，两者交集 41 项、dozable 独有 11 项、白名单独有 12 项。所以**判"某应用能否收到消息"不能只看名单**：某金融类应用（uid 10319，包名略）在两份名单里都零命中、夜间无进程，仍照样收到 4 点的推送。

四套互不相同的存储，勿混为一谈：自启动 = AppOps `10008`；电池策略 = powerkeeper `userTable.bgControl`；睡眠网络白名单 = `sleep_mode_network_white_apps`（**V816 上无效**，见 4.6）；GMS 限制 = powerkeeper `gms_control`。

**粒度判据（限定了开关能表达什么）**：睡眠断网是整机物理级（直接 `setWifiEnabled(false)` / `setDataEnabled(false)`），**没有应用维度** ⇒「只保 GMS 不保其他应用」在睡眠面上不可实现（按 uid 裁剪必须叠加自建 netd 链，已否决）。省电只能二选一：只保 WiFi，或完全不保、靠 FCM 重连补投。

**第三方推送不归本模块管**：国产推送栈（Mi Push / 个推 / HMS / 荣耀）由宿主 `com.xiaomi.xmsf`（uid 10206，已在 deviceidle user 白名单）承载，与 c2dm 不同构，四项 Firebase 检测不匹配——这是设计如此，不是漏检。

**3.5.3 之后须重新实测**：现役实现是「ROM 走完整路径 + 下游精确拦截」，旧的「整夜 5h40m 无断档」是 flag 捷径实现的成绩，不能直接沿用到新实现上。

---

## 8. 数据流转

### 8.1 配置数据流

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

### 8.2 推送投递数据流

```
GMS 发出 c2dm 广播
  → AMS#broadcastIntentWithFeature
      ├─ caller 识别（getRecordForApp* → ProcessRecord.info.packageName，失败降级 binder uid）
      ├─ caller=GMS + moduleAppliesTo(target, Tier.WAKE) → 补 FLAG_INCLUDE_STOPPED_PACKAGES
      │                                   → addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000)
  → BroadcastQueueModernStubImpl#checkApplicationAutoStart   （冷启动路径）
  → GreezeManagerService#isRestrictReceiver                  （温而冻路径，附带 thawUidAsync）
  → GreezeManagerService#isAllowBroadcast                    （Tier.STRICT）
  → GreezeManagerService#isNeedCachedBroadcast               （冻结缓存路径）
  → DomesticPolicyManager#deferBroadcast                     （只剩 4 个 CN 重连动作；c2dm 守门已上移到 isAllowBroadcast，见 10.3）
  → 目标应用 receiver
```

每层拦截点都会**读** `sAllowlist`（经 `getFcmAllowlist()`，带 10s 陈旧度检查与异步重载触发）。

### 8.3 冻结 / 解冻与网络策略数据流

```
freezeUids(uid)
  ├─ AurogonImmobulusMode#isNoRestrictApp(pkg)        → GMS: true  ⇒ 跳过
  ├─ AurogonImmobulusMode#isNoRestrictFreezeable      → GMS: false ⇒ 跳过（OS4）
  ├─ AurogonImmobulusMode#triggerQuickFreeze(uid,…)   → GMS: 跳过
  ├─ PolicyMaker#isAllowFreeze(uid)                   → GMS: 跳过
  ├─ DomesticPolicyManager#isRestrictNet(uid)         → GMS: false ⇒ 不置 0x0C00、不销毁 socket
  └─ GreezeManagerService#udpPackageRestrict(uid,true)→ GMS: 跳过 ⇒ 不下发 UDP 过滤
                                                          （allow=false 方向必须放行）
```

解冻/死亡路径传 `allow=false`，因此 `udpPackageRestrict` 只挡 `true` 方向——否则已下发的过滤规则会被永久留在原地。

### 8.4 睡眠模式数据流

```
PhoneSleepModeController#broadcastSleepState(state=1)
  → 广播 com.miui.powerkeeper_sleep_changed
      → MiuiNetworkPolicyManagerService$45.onReceive
          → setSleepModeWhitelistUidRules()   ← 钩子：注入 GMS uid
          → enableSleepModeChain(true)        ← 钩子：记录 size
退出（state≠1 / ACTION_SCREEN_ON）
      → clearSleepModeWhitelistUidRules()     （ROM 自行对称撤销）
      → enableSleepModeChain(false)           ← 钩子：白名单标志判定 → 跳过或（采样 → nudge → 15s 后再采样）
```

`ContentObserver`（$46）另有一条路径：开关变假时也会 clear + `enableSleepModeChain(false)`。

### 8.5 诊断数据回流

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

## 9. 测试验证

### 9.1 构建与单元

| 层      | 内容                                                                |
| ------ | ----------------------------------------------------------------- |
| JVM 单测 | 配色算法（`McuColorTest`）、版本号解析（`UpdateCheckerVersionTest`），不需要设备      |
| 构建     | AGP 9.4.1 + Java 21                                               |
| 产物     | `HyperFCMLive-<versionName>.<versionCode>[-debug].apk`，arm64-only |

### 9.2 装机验证（每次改动后必做）

1. 构建 → `adb install -r`；
   - 快速查看：`adb logcat -d -s LSPosedLogDaemon | grep HyperGreeze`
   - **整宿留存源**是 LSPosed 管理器导出的 `modules_*.log`（主 logcat 缓冲会被白天日志冲掉）
2. 触发热重载（不需要重启）；
3. 在 `modules_*.log` 中确认两个域的摘要行：
   - `HyperFCMLive active in system_server: N hook(s) installed, M target(s) absent`
   - `HyperFCMLive active in com.miui.powerkeeper: …`
4. 核对 `M` 与该 ROM 代次的预期一致（突增 = 代次漂移）；
5. 确认此次改动对应的钩子/日志行**在列**（例如睡眠链那行 `Sleep-mode legacy per-uid chain armed: whitelist=…, chain=…`）。

注意日志中 `AppStandbyController#setUidState hooked` 这类行会**每次 package-ready 重复一次**（热重载会重跑），看起来像装了多个钩子，实际 `setId()` 已把它们收敛为一条活钩子。行尾带 `pkg=` 与 `userId=` 就是为了消除这个误读。

**摘要行只在开机那一次出现**（2026-10-03 日志核对：14:24/14:25 的 `HyperFCMLive active in …` 各一条，随后 15:54、18:38 两次热重载只重打了各钩子的安装行与探针行，**没有**再打摘要行）。所以「日志里只有一组 `N hook(s) installed`」不能读作「后续热重载没生效」——热重载是否生效看探针的 `generation` 递增（`gms traffic probe: … generation 2/3`）与旧链 `superseded, retire` 行。

### 9.3 长窗观测

一次完整的整宿观测（开机 → 次日）应产出：

| 观测项       | 期望                                                                                  |
| --------- | ----------------------------------------------------------------------------------- |
| 摘要行出现次数   | 2（两域各一次）；多次说明发生了重启/热重载                                                              |
| 冻结防御位命中   | 正常为 0；非 0 说明 ROM 真的对 GMS 动了手，需复核极性                                                  |
| Alarm 门   | 至少应有 `GMS alarm allowed by ROM` 一条（证明门被到达）；出现 `re-allowed denied GMS alarm` 说明门真的拒过 |
| userTable | `bgControl` 保持 `noRestrict`，无被改回 `miuiAuto`                                         |
| Gate-W    | `reached` 显著大于 0，`denied` 可归因；GMS caller 应全部放行                                      |
| 睡眠链       | 按 5.8 判定表逐条对照                                                                       |
| 流量探针      | 夜间增量非零；nudge 前后各一条采样                                                                |

### 9.4 真机对照

用 `dumpsys` 做**对照实验**是无需等待推送即可验证 GMS 侧保护的手段：

| 观测                                       | 期望                                                 |
| ---------------------------------------- | -------------------------------------------------- |
| `dumpsys greezer` 冻结进程列表                 | GMS 各进程**不在**列表内；被勾选的目标应用**可以**在列表内（模块不提供非 GMS 免冻） |
| `dumpsys greezer` 的 `mCurrentCNPolicy`   | 决定 Domestic / International 哪一侧生效，据此判断哪些钩子是活的      |
| `dumpsys netpolicy`                      | 睡眠结束后白名单应被 clear（集合为空符合预期），GMS 策略无阻塞               |
| `dumpsys deviceidle`                     | GMS 在 doze 白名单各段内                                  |
| `settings system MILLET_NO_RESTRICT_APP` | 含 GMS（P1 已落地）                                      |

**"勾选了为什么还是被冻"是预期行为**，不是模块失效：免冻是 GMS 专属的。给非 GMS 应用也加免冻的代价远超模块语义范围。

### 9.5 端到端

用已知通过 FCM 收推送的应用做端到端验证，但要注意它的局限：一次成功只能证明**本次**链路通畅，不能证明防御位有效；反之一次失败也无法定位到具体门——需要结合 9.3 的日志。

### 9.6 验证的边界

- "整夜没有任何坏事发生" ≠ "某条链被挡住了"；
- 一个晚上的阴性只支持"本轮未观测到触发"，不支持"该机制无用"；
- 触发面为 0 样本时，否定兜底逻辑是循环论证；
- 静态结论与运行时冲突时，**以运行时为准**。

---

## 10. 局限性

### 10.1 观测手段的物理边界

- **静态取证看不到**：反射跳板、binder 跨进程、跨 jar 的类。因此"零调用者"永远只是弱证据。
- **冷启动阶段**：静态字段可能尚未初始化，探针读数不可信。
- **日志留痕不完整**：历史版本中部分出口（如 `enablemiuistandby enable`）为静默、部分探针只记前 N 次；前者与 `checkWakePath` 归因、allowlist 首读已于观测侧修补关闭，仍保留前 N 次节流的探针（如 `doDesSocketForUid` 的非 GMS caller）其 GMS 命中本就必记。

### 10.2 流量探针能回答什么、不能回答什么

`TrafficStats` 的 per-uid 字节增量是 system_server 域内**唯一可达**的连接可观测量。它回答"GMS 还在不在交换数据"，不回答"连接是不是健康的"。

**探针链代际去重（2026-10-01）**：热重载不取消旧的 30min 定时链——每次 `hookPackage` 重跑都会新增一条并行链（实测一夜两条重载后三条交错，且同计数器同相位基线导致增量重复上报）。修复：代际计数器存于 `System.getProperties()`（boot classloader 对象，跨模块 classloader 共享、进程内全局；companion 字段因热重载换新 classloader 而不可见，不可用）。每条链在 `run()` 时校验自己是否仍为最新代，否则打 `chain generation N superseded, retire without rescheduling` 后退役。调度日志同步带上 `generation N`，解析器可据此对账链的存活代数。

曾实现过一版 `/proc/net/tcp` 扫描（找 ESTABLISHED 的 MCS socket），已移除，两个理由各自致命：

1. **读不到**：该文件被标为 `proc_net_tcp_udp`，Enforcing 下 system_server 无读权限（adb 的 shell 域能读，这不证明钩子能读）；放宽 SELinux 不在考虑范围内；且 Android 10 起就在收紧 `/proc/net`。
2. **答错问题**：本 ROM 真正饿死 GMS 的两种方式是 **DNS 拦截**与**防火墙 DROP**，两者都不通知端点，socket 保持 ESTABLISHED，表会把一条死连接报成健康连接。唯一真正关闭 socket 的路径 `closeSocketForAurogon` 对 GMS 的实测命中率为零。

### 10.3 守门缺口的处置（2026-10-02 已实施，见 11.2）

`DomesticPolicyManager#deferBroadcast(String)` 的签名**只有 action，没有包名**，因此无法按目标应用守门：任何 c2dm 都被无条件免延迟。这与"未勾选应用应与未装模块时一致"的承诺有偏差（方向是偏松，不丢推送，但会带来额外耗电）。在当前钩点上不可实现，只能换钩点。

**已按此实施**：defer 层的 c2dm 豁免被移除（只留 4 个 CN 重连动作），守门职责整体交给 `isAllowBroadcast`——它同时持有 callerUid / callerPkgName / calleeUid / calleePkgName，并已加 callerUid 兜底。

**推翻 2026-10-01 结论的运行时证据**：模块自设的反证哨兵在 2026-10-02T07:55:54 命中（`deferBroadcast: c2dm delivery reached this hook`）⇒ c2dm **确实**能走到 defer。根因是把 `if-nez` 的极性读反，正确的链见 11.2。旧结论的另一半证据（09-30 19:57，50s 窗口零命中）按本文件 §10.5 的纪律只算触发面 0 样本，本就不构成否证。

### 10.4 覆盖面的已知不足

| 项                  | 现状                                            |
| ------------------ | --------------------------------------------- |
| P4 恢复触发点           | 睡眠退出 + MILLET 修复（条件性）；其他诱因（网络切换、GMS 重建、NAT/FW 老化）无触发——评估后**维持现状**，理由与重启条件见 11.2 |
| 非 GMS 应用免冻         | 不提供，也不打算提供                                    |
| `setUidState` 带外限制 | 无解除机制                                         |
| 归因盲区               | 探针前 N 次上限导致长窗观测不可完全归因                         |

### 10.5 结论措辞纪律

本模块的日志与文档在以下场合**只能**写"未验证"，不得写"不需要"：

- 触发面未覆盖（0 样本）；
- 只有一个窗口的阴性结果；
- 静态结论缺少运行时闭合。

一个已被撤回并写入记录的案例：曾据"一夜零触发"判定睡眠退出 nudge 冗余，该结论被撤回——因为连"睡眠模式是否进入过"都没证实，触发面为 0 样本，用它否定兜底逻辑是循环论证。可成立的表述只有一句：**"在本轮观测窗口内触发次数为 0，其触发面与有效性尚未验证。"**

---

## 11. 展望

### 11.1 观测侧

| 方向       | 内容                                                        |
| -------- | --------------------------------------------------------- |
| 消除归因盲区   | 已落地（2026-10-01）：`checkWakePath` 改为 per-caller 聚合（上限 32 个 caller）+ 30min 节流 denied summary + 心跳行 `top=`；其余探针保留前 N 次节流 |
| 首读可观测    | 已落地（2026-10-01）：`loadAllowlistFromRemotePrefs` 成功且内容变化时打 INFO；失败按指数退避（1s×2^k，封顶 10s）重试，stale 判断与节流时间戳分离避免忙循环 |
| 探针生命周期管理 | 固化"晋升 / 退役"判据与复核周期，避免观察位无限堆积                              |
| 结构化诊断导出  | 把摘要行、计数、判定表结果导成一份可机读报告，减少人工分析                             |

### 11.2 行为侧

| 方向                  | 内容与前提                                                                                                           |
| ------------------- | --------------------------------------------------------------------------------------------------------------- |
| P4 触发面扩展            | **评估后不实施**（2026-10-01）。① P4 的三条广播会让 GMS **主动断开当前 MCS**（破坏性修复），只在"连接已死且 GMS 不会自愈"有先验证据时才正当——睡眠退出正是这种时刻（ROM 强掐整夜 + GMS 退避可能耗尽），而候选事件都不携带这种证据：模块装载时连接通常健康；亮屏是高频事件，事件驱动的外形、定时驱动的实质；c2dm 正在投递恰好**证明** MCS 活着（投递前预检在语义上是反的）。② 文档所列诱因多可自愈：网络切换 / GMS 被杀重建走 GMS 原生重连，NAT/FW 老化正是 MCS/GTalk 心跳的设计场景（实测 HB 机制在工作）；唯一端点无感知的"socket 存活但数据路径死"（DNS 拦截 / 防火墙 DROP）已由上游 hook 事前拔源，且重连广播修不好仍然存在的拦截。③ 本行的事件清单原出自 `setUidState` 缓存修复的 KDoc——那里每次触发只是方法体内多发一次 `sendConnectivityActionToApp`（唤醒，不拆链），把同一清单搬到 P4 低估了代价。④ 重启条件：整宿观测出现"MCS 死亡 + GMS 未自愈 + 未进睡眠模式"的证据后再设计带门控的触发 |
| `deferBroadcast` 守门 | **2026-10-02 推翻旧结论并已实施（P0）**。旧结论（2026-10-01「结构性死路」）建立在一个读反的 `if-nez` 极性上；其动态佐证（09-30 19:57，50s 窗口零命中）按 §10.5 只算 0 样本，不构成否证。修正后的链（`GreezeManagerService#isAllowBroadcast` 偏移）：`009a isCnModel()` → `009e if-nez v0, 00bc`（**CN 时 v0≠0 ⇒ 跳 00bc，跳过整段提前放行**）→ `00a0 enableNewStrategy()` → `00a4 if-eqz v0, 00bc`（false 同样跳）→ `00a6..00bb` **只有非 CN + 新策略**才在此对 c2dm 提前 `return true` → `00d9 deferBroadcastForMiui`。`enableNewStrategy()` = `sget-boolean InternationalPolicyManager.mNewController`，`isCnModel()` = `PolicyManagerConfig.sCnModel` ⇒ 本机 region=CN 且 `dumpsys greezer` 报 `mCurrentCNPolicy:1`，009a/009e 已定局，c2dm 必走 00d9（`persist.sys.greeze.oversea` 不参与：它折进 mNewController，而 mNewController 只在 CN 判定之后才被读）。defer 侧 `deferBroadcastForMiui`：`mMiuiDeferBroadcast` 仅含 `android.intent.action.BATTERY_CHANGED` ⇒ c2dm 不在常延后表；`000c if-nez mScreenOn, 001b` ⇒ `PolicyManager.deferBroadcast` **仅亮屏时被调用**，故 07:55:54 那次命中蕴含当时屏幕是亮的。**已实施**：defer 层删掉 c2dm 无条件豁免（只留 CN 重连动作），并新增 `reached-defer` 计数做正向证据 |
| `setUidState` 缓存分歧  | 唯一可行形态是"强制缓存为 false，再调用 `setUidState(uid, true)` 让方法体完整执行"；只补写缓存无效（提前返回就在该方法内）。当前所有可观测量都表明分歧未发生，**保持不实现**是刻意的选择 |
| 非 GMS 免冻            | 代价远超严格模式语义范围，不在计划内                                                                                              |

### 11.3 兼容性侧

- 每次 ROM 大版本升级后，以 `M target(s) absent` 的变化为起点做一次全量复核；
- 新增钩子时同步补齐"到达但未命中"的对应日志，否则该钩子的静默不可解释；
- 对代次漂移的符号一律走"多候选 + `logSkipOtherGeneration`"，不硬编码名字。

### 11.4 日志冗余审计（2026-10-03）

全量清点：`Hooker.kt` 共 **237 处**日志调用点——42 条 `logSkip`（INFO，同代缺失）、14 条 `logSkipOtherGeneration`（DEBUG，跨代缺失）、22 条 `hooked/armed` 安装确认、42 条 probe。

| # | 现象                                                                                                                | 级 | 处置                                     |
| - | ----------------------------------------------------------------------------------------------------------------- | - | -------------------------------------- |
| 1 | **30 分钟四条叠加**：`GMS_TRAFFIC_PROBE_INTERVAL_MS` 与 `WAKE_PATH_HEARTBEAT_MIN_MS` 同为 30 min，同一 tick 上叠加 `gms traffic probe [periodic]` / `broadcast gate: c2dm …` / `broadcast gate: wake-path …` / `wake-path probe: heartbeat …` ≈ **192 行/天**，实机连续窗口内长期全零 | 中 | 候选：无事件时只留一条合并行；`top=[]` 在 `denied=0` 时恒空，属恒空字段       |
| 2 | **死标志**：`wakePathDeniedLogged` / `wakePathReachedLogged` 只声明、零读写（gate-W 早已改用 `reached == 1` + 计数器）    | 低 | **已删**，原位留注释警告「勿再引入只置一次的布尔哨兵——它与『压根没到达』不可区分」 |
| 3 | `userTable: ensure …` + `userTable: GMS current bgControl=…` 每次调用必出 2 行，而 `current == noRestrict` 时静默 return ⇒ 「无需写入」与「准备写入」外观相同 | 中 | 候选：无需写入也补一行（本项目口径：不能靠日志没出现反推）。该函数有 4 个调用点 |
| 4 | `userTableReassertInFlight` **非 volatile 且 check-then-set 非原子**                                                  | 低 | 候选：`AtomicBoolean.compareAndSet`                |
| 5 | **纯存在性 probe 占 9 行 INFO**：whetstone ×2、socket-teardown ×3、sleep-mode ×2、packet filter、mMessageApp——只答「ROM 有无此方法」，答过一次后不再变 | 低 | 候选：降 DEBUG 或并为一行                          |
| 6 | `Failed to hook GmsObserver` / `Failed to hook GlobalFeatureConfigureHelper` 各有两处、文案完全相同（内层 CNFE 与 `hookPackage` 外层兜底）⇒ 无法区分「类不存在」与「桥接方法缺失」 | 低 | 候选：文案分层                                 |
| 7 | `logSkip(msg, level)` 无论 INFO/DEBUG 都 `hookTargetsAbsent++` ⇒ 安装期 `M target(s) absent` 把 10 条 OS3-only 预期缺失算进「本 ROM 缺失」 | 中 | 候选：计数器按代次拆分，或汇总行括注「其中 N 为跨代预期」         |
| 8 | 睡眠进入三行叠加：`chain enabled, whitelist size N` 与白名单臂的 `kept GMS …` / `already whitelisted …`（本代不可达，OS3 上会真叠加）   | 低 | 观察                                      |
| 9 | `gms traffic probe: chain generation N superseded …` 每次热重载出 1~2 条，是热重载的必然结果而非异常                              | 低 | 保留（解释旧链为何消失）                            |

---

## 附录 A：钩子清点

| 域             | 组   | 目标                                                                                        | 方向                     |
| ------------- | --- | ----------------------------------------------------------------------------------------- | ---------------------- |
| system_server | 投递  | `AMS#broadcastIntentWithFeature`                                                          | 补 flag + 临时豁免          |
| system_server | 投递  | `BroadcastQueueModernStubImpl#checkApplicationAutoStart`                                  | → true                 |
| system_server | 投递  | `GreezeManagerService#isRestrictReceiver`                                                 | → false + thawUidAsync |
| system_server | 投递  | `GreezeManagerService#isNeedCachedBroadcast`                                              | → false                |
| system_server | 投递  | `GreezeManagerService#isAllowBroadcast`                                                   | → true                 |
| system_server | 投递  | `DomesticPolicyManager#deferBroadcast`                                                    | → false（仅 4 个 CN 重连动作；c2dm 不再豁免，P0） |
| system_server | 投递  | `GreezeManagerService#deferBroadcastForMiui`                                              | → false                |
| system_server | 冻结  | `AurogonImmobulusMode#isNoRestrictApp`                                                    | → true                 |
| system_server | 冻结  | `AurogonImmobulusMode#isNoRestrictFreezeable`                                             | → false                |
| system_server | 冻结  | `AurogonImmobulusMode#triggerQuickFreeze`                                                 | 跳过                     |
| system_server | 冻结  | `PolicyMaker#isAllowFreeze`                                                               | 跳过                     |
| system_server | 网络  | `DomesticPolicyManager#isRestrictNet`                                                     | → false                |
| system_server | 网络  | `GreezeManagerService#udpPackageRestrict`（allow=true）                                     | 跳过                     |
| system_server | 网络  | `GreezeManagerService#triggerGMSLimitAction`                                              | → false / 清标志位         |
| system_server | 网络  | `GreezeManagerService#updateGmsNetStatus`                                                 | → false                |
| system_server | 网络  | `InternationalPolicyManager#isPushApp`                                                    | → false（CN ROM 不执行）    |
| system_server | 清理  | `ProcessCleanerBase#isForceStopEnable`                                                    | → false                |
| system_server | 清理  | `ProcessPolicy#getWhiteList`                                                              | 追加 GMS                 |
| system_server | 清理  | `ListAppsManager` 构造器 / `#isInWhiteList`                                                  | 移除黑名单 / 加白名单           |
| system_server | 清理  | `AwareResourceControl` 构造器                                                                | 移除 GMS                 |
| system_server | 清理  | `GlobalFeatureConfigureHelper#getDozeWhiteListApps`                                       | 追加 GMS                 |
| system_server | 睡眠  | `MiuiNetworkPolicyManagerService#setSleepModeWhitelistUidRules`                           | 注入 GMS uid             |
| system_server | 睡眠  | `MiuiNetworkPolicyManagerService#enableSleepModeChain`                                    | 日志 + nudge             |
| system_server | 闹钟  | `AlarmManagerServiceStubImpl#checkAlarmIsAllowedSend`                                     | 被拒的 GMS 闹钟 → true      |
| system_server | 探针  | `ActivityManagerServiceImpl#checkWakePath`                                                | 只读计数                   |
| system_server | 探针  | `AurogonImmobulusMode#mMessageApp`                                                        | 只读快照                   |
| system_server | 探针  | `ConnectivityManager#updateSleepModeUidRule` / `#enableSleepModeChain`                    | 存在性                    |
| system_server | 探针  | `FilterEnablePolicy#isSupportPacketFilter`                                                | 存在性 + 取值               |
| system_server | 探针  | `doDesSocketForUid` ×3 层                                                                  | 只读计数                   |
| system_server | 探针  | `TrafficStats` per-uid                                                                    | 只读采样                   |
| powerkeeper   | 网络  | `NetdExecutor#initGmsChain` / `#setGmsDnsBlockerState` / `#setGmsChainState` / `#execute` | 改写参数 / 跳过              |
| powerkeeper   | 网络  | `GmsObserver` 系列（11 个方法 + 内部类 `googleNetworkDisconnect`）                                  | 强制 false / true / 跳过   |
| powerkeeper   | 待机  | `AppStandbyController#setUidState`                                                        | GMS → allow=true       |
| powerkeeper   | 配置  | `UserConfigureHelper#getNoRestrictApps` + 所有 writer                                       | 追加 GMS / 重断言           |
| powerkeeper   | 配置  | `ActiveStateController#dealNoRestrictApp`                                                 | 校验并修复                  |
| powerkeeper   | 配置  | `PowerKeeperAppConfigure#fillScenarioContent`                                             | scenario 0 → 8         |

## 附录 B：常量与阈值

| 常量                                                    | 值                                                        | 用途          |
| ----------------------------------------------------- | -------------------------------------------------------- | ----------- |
| `ACTION_REMOTE_INTENT`                                | `com.google.android.c2dm.intent.RECEIVE`                 | c2dm 投递     |
| `ACTION_MESSAGING_EVENT`                              | `com.google.firebase.MESSAGING_EVENT`                    | FCM 组件探测    |
| `FCM_MESSAGING_SERVICE_CLASS`                         | `com.google.firebase.messaging.FirebaseMessagingService` | FCM 组件探测    |
| `FCM_IID_RECEIVER_CLASS`                              | `com.google.firebase.iid.FirebaseInstanceIdReceiver`     | FCM 组件探测    |
| `GMS_PACKAGE_NAME` / `GMS_PERSISTENT_PROCESS_NAME`    | `com.google.android.gms` / `.persistent`                 | GMS 判定      |
| `CN_DEFER_BROADCAST`                                  | 4 个 GMS 重连/心跳/连接状态 action                                | 免延迟         |
| `RECOVERY_BROADCAST_ACTIONS`                          | `GCM_RECONNECT` / `GTALK_HEARTBEAT` / `MCS_HEARTBEAT`    | P4 恢复       |
| `MILLET_NO_RESTRICT_APP`                              | Settings.System 键                                        | 免限名单        |
| `SCENARIO_MUI_AUTO_GMS` / `SCENARIO_NO_RESTRICT`      | 0 / 8                                                    | 场景改写        |
| `FCM_CACHE_TTL_MS` / `FCM_CACHE_MAX`                  | 5 min / 256                                              | FCM 探测缓存    |
| `ALLOWLIST_STALE_MS` / `RELOAD_MIN_MS`                | 10 s / 500 ms                                            | 白名单陈旧与合流    |
| `ALLOWLIST_REGISTER_RETRY_MS` / `MAX_ATTEMPTS`        | 1 s / 120                                                | 接收器安装重试     |
| `GMS_TRAFFIC_PROBE_INTERVAL_MS` / `NUDGE_RESAMPLE_MS` | 30 min / 15 s                                            | 流量采样        |
| `WAKE_PATH_HEARTBEAT_MIN_MS`                          | 30 min                                                   | Gate-W 心跳节流 |
| 临时豁免参数                                                | `(pkg, 102, "GOOGLE_C2DM", 2000)`                        | 2 秒省电豁免     |
