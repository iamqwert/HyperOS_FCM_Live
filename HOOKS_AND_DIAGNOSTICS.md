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

### 3.2 `shouldWake` 与 `shouldApply`

```kotlin
shouldWake(pkg)   = allowlist.isEmpty() || allowlist.contains(pkg) || pkg ∈ {GMS, GMS.persistent}
shouldApply(pkg)  = !strictMode || allowlist.isEmpty() || allowlist.contains(pkg) || pkg ∈ {GMS, GMS.persistent}
```

差异有三条，且都是**刻意的**：

| 差异                           | 后果                                                           |
| ---------------------------- | ------------------------------------------------------------ |
| `shouldWake` 不读 `strictMode` | 白名单非空时投递链路即已收窄，与"严格模式是追加收窄"的契约一致                             |
| 两者都是 fail-open（空名单全放行）       | 与"一个都不勾选 = 全部放行"一致                                           |
| 两者都有 GMS 恒定豁免分支              | 保证 GMS 自身永远不受白名单影响（否则非空的白名单会让唯一没有 caller 校验的调用点把 GMS 自己挡在外面） |

严格模式的**实际收权面比名义上小**：`shouldApply` 名义上有 3 个调用点，但在 CN ROM 上 `InternationalPolicyManager` 从不实例化（见 6.1），真实收权面只有 `isAllowBroadcast` 与 `isForceStopEnable` 两处。这一点在排查"开了严格模式为什么还有干预"时必须先说清。

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
| `AMS#broadcastIntentWithFeature` / `#broadcastIntent`    | 命中 `ACTION_REMOTE_INTENT` 且 caller 是 GMS：补 `FLAG_INCLUDE_STOPPED_PACKAGES`，并为目标包申请 `addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000)` | `shouldWake`     | 活跃              |
| `BroadcastQueueModernStubImpl#checkApplicationAutoStart` | 冷启动路径（有 `ResolveInfo`）：caller=GMS + c2dm → 返回 `true`                                                                                        | `shouldWake`     | 活跃              |
| `GreezeManagerService#isRestrictReceiver`                | 温而冻的 receiver 路径：返回 `false`，并**主动复现原生解冻** `thawUidAsync(uid, 1000, "bc_action")`                                                            | `shouldWake`     | 活跃              |
| `GreezeManagerService#isNeedCachedBroadcast`             | 命中 c2dm → 返回 `false`，避免广播被缓存到解冻后                                                                                                            | `shouldWake`     | 活跃              |
| `GreezeManagerService#isAllowBroadcast`                  | GMS 的 c2dm / CN 重连动作 → `true`                                                                                                               | `shouldApply`    | 活跃              |
| `DomesticPolicyManager#deferBroadcast`                   | c2dm 与 4 个 CN 重连动作 → `false`                                                                                                                | **无**            | 活跃（已知缺口，见 10.3） |
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
| `ProcessCleanerBase#isForceStopEnable(ProcessRecord,int,ProcessManagerService)` | 声明了 FCM 组件且 `policy != 13` → `false`            | `shouldApply` + `declaresFcmComponent` |
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

### 4.6 睡眠模式断网链（system_server）

`PhoneSleepModeController` 入睡后会打开一条断网链，只放行 `mSleepModeWhitelistUids` 中的 uid，其余整夜掐网。GMS 默认不在集合里，于是 FCM 长连接被静默切断——表现为"FCM 以为只是网络断了"，直到心跳超时才重连。

进入睡眠的广播顺序是 `setSleepModeWhitelistUidRules()` → `enableSleepModeChain(true)`，因此只需要在**下发之前**把 GMS uid 塞进集合，不必改动链开关语义；退出时 `clearSleepModeWhitelistUidRules()` 会对称撤销，不会残留规则。

```
hook: MiuiNetworkPolicyManagerService#setSleepModeWhitelistUidRules()
        → addGmsToSleepModeWhitelist(field, thisObject) → 原方法
hook: MiuiNetworkPolicyManagerService#enableSleepModeChain(boolean)
        enabling=true  → 记录 "chain enabled, whitelist size N"
        enabling=false → 采样流量 → 异步 nudge → 15s 后再采样一次
        （2026-10-01 起改为**条件触发**：`sGmsKeptOnSleepWhitelist` 为 true 时跳过 nudge，改打 `skipping recovery nudge (MCS untouched)`；为 false 才走原 nudge 路径。标志由白名单注入钩子在本会话内设置，出睡决策后复位）
```

四个静默出口（字段类型不是可变集合、uid 解析失败、GMS 已在集合内、链开启本身）都已补日志——此前"回调压根没跑"和"跑了但集合是空的"在日志里完全一样，无法区分（见 5.7 判定表）。

### 4.7 恢复动作（P4，非 hook）

`recoverGmsConnection(Context)` 是**出境 IPC**，不是钩子：向 GMS 与 GSF 各发三条广播（`GCM_RECONNECT` / `GTALK_HEARTBEAT` / `MCS_HEARTBEAT`），再查询一次 Chimera provider。三条一起发是因为 `GCM_RECONNECT` 在部分版本上覆盖不到 MCS/GTalk 的重连路径。

当前有两个触发点：睡眠模式退出（**条件性**——`sGmsKeptOnSleepWhitelist` 为 false，即白名单注入未生效时才 nudge，触发前后各采样一次流量使效果可证伪；注入成功则跳过并留日志，避免拆掉整夜健康的 MCS）与 `MILLET_NO_RESTRICT_APP` 修复（条件性——仅在实际发生追加修复时）。这是一个覆盖面问题，不是需求问题（见第 10 章）。

---

## 5. 诊断体系

### 5.1 三条设计原则

1. **只读优先**。能观察就先观察，只有在运行时证据表明确实需要改写时才落地行为钩子。现有 6 组探针全部只读，不修改任何返回值。
2. **可证伪**。每条"成功路径"日志都必须有一个"到达但未命中"的对应日志。只有命中日志的探针，静默时无法区分"从未被拒绝"与"从未被调用"——这个教训直接来自对 `checkWakePath` 与 `isPushApp` 的取证。
3. **探针不得抛出**。所有只读辅助方法内部全包 `try/catch`，返回可读的占位字符串（如 `<unreadable>`、`<not a collection>`）。

### 5.2 五类诊断手段

| 类别            | 代表                                                                          | 回答的问题                 |
| ------------- | --------------------------------------------------------------------------- | --------------------- |
| **存在性探针**     | `probeReflectiveMethod`、`reportWhetstoneClasses`、`probePacketFilterSupport` | 这个隐藏符号在这台 ROM 上存不存在   |
| **只读字段快照**    | `mMessageApp` 探针、`sleepModeWhitelistSize`、`NoNetworkBlackUids` mismatch     | 这个集合现在是什么内容、GMS 在不在里面 |
| **计数 + 心跳节流** | `checkWakePath` 探针、`doDesSocketForUid` 三层探针                                 | 这个门被进入多少次、拒绝了多少次      |
| **一次性证据日志**   | 8 个 `@Volatile Boolean` 标志位                                                 | 这条路径到底有没有真实发生过一次      |
| **流量采样**      | `TrafficStats` per-uid 增量                                                   | 结果层面：GMS 现在还在不在交换数据   |

**Gate-W 两轮长窗实测（结论已定）**：首轮 9h23m reached=9951 / denied=22（可归因拒绝全部 `com.coolapk.market`，GMS 作为 caller 的 57 次采样全放行）；第二轮 13h49m 整宿（2026-09-30 19:57 → 10-01 09:46）reached=15073 / denied=0。两轮合计 ~25k 样本，GMS 零拒绝 ⇒ "行为钩不落地、维持只读"正式落档；退役按判据 17 仍需更多窗口，但晋升评估已完结。

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

### 5.8 睡眠链判定表

`Sleep mode entering: chain enabled, whitelist size N` 是分水岭——出现即证明 `enableSleepModeChain(true)` 被调用过。

| 观测到的日志组合                                          | 判定                                                           |
| ------------------------------------------------- | ------------------------------------------------------------ |
| 无任何 `Sleep mode entering` 行                       | 睡眠链整夜未进入（广播未发，或时间窗/静止判定未满足）。2026-10-01 复核：用户确认手机管家"夜间休眠省电"开关为开 ⇒ "开关未开"已排除，指向 **PowerKeeper 入睡决策被运行时条件否决**——字节码可见的门控候选：`checkSleepModeSwitch`、`power.sleep.time` 时间窗、`DynamicTurboPowerHandler` 跟踪的 `isCharging`/`mOnBattery`/`mCurrentLevel`（**充电很可能是门控之一**）。入睡决策只有 `Log.d (tag=power.sleep)` 与 `writeLocalLog`，夜间均不可观测；验证需 cp 出 DB 读 `key_settings_sleep_mode`（开关）/`key_sleep_state`（状态），或下一窗口用新构建的 `standby-firewall` 计数反证 |
| `size 0` 且无 `kept GMS` 行                          | 进入过但 `setSleepModeWhitelistUidRules()` 未在开链前调用 ⇒ 注入未生效，查调用顺序 |
| `size ≥1` 且有 `kept GMS (uid …)`                   | 注入成功，uid 级放行已下发                                              |
| `already whitelisted, size N`                     | 集合非空 ⇒ ROM 侧确实填过（推翻"恒为空"的静态结论）或上次残留未清                        |
| `not a mutable collection` / `GMS uid unresolved` | 字段类型或 uid 解析异常 ⇒ 注入失效，需改实现                                   |

出现与静态结论冲突时，**以运行时为准**：静态取证只证明常规路径，排除不了云控等特殊路径的写入。

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
| `setUidState` 带外限制 + 缓存为 true   | 即使传入 `allow=true` 也会短路，无任何机制解除（P4 不覆盖） |
| P4 触发面只有睡眠退出                    | MCS 僵死还可能来自网络切换、GMS 被杀后重建、NAT/FW 老化    |
| 冷启动读静态字段假阴性                     | 开机早期的 `mMessageApp` 读数不可信              |

历史缺口 `enablemiuistandby enable` 静默跳过、`checkWakePath` 拒绝只记前 10 次、allowlist 首读成功无日志三项已于观测侧修补关闭（见 11.1），不在上表重复列出。

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
   钩点查询：shouldWake(pkg) / shouldApply(pkg)
```

反向：写入失败 → 保留 pending 标记 → 下次绑定时把镜像推上去（防止被旧远程值覆盖）。

### 8.2 推送投递数据流

```
GMS 发出 c2dm 广播
  → AMS#broadcastIntentWithFeature
      ├─ caller 识别（getRecordForApp* → ProcessRecord.info.packageName，失败降级 binder uid）
      ├─ caller=GMS + shouldWake(target) → 补 FLAG_INCLUDE_STOPPED_PACKAGES
      │                                   → addToTemporaryAllowList(pkg, 102, "GOOGLE_C2DM", 2000)
  → BroadcastQueueModernStubImpl#checkApplicationAutoStart   （冷启动路径）
  → GreezeManagerService#isRestrictReceiver                  （温而冻路径，附带 thawUidAsync）
  → GreezeManagerService#isAllowBroadcast                    （shouldApply）
  → GreezeManagerService#isNeedCachedBroadcast               （冻结缓存路径）
  → DomesticPolicyManager#deferBroadcast                     （无守门，见 10.3）
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
2. 触发热重载（不需要重启）；
3. 在 `modules_*.log` 中确认两个域的摘要行：
   - `HyperFCMLive active in system_server: N hook(s) installed, M target(s) absent`
   - `HyperFCMLive active in com.miui.powerkeeper: …`
4. 核对 `M` 与该 ROM 代次的预期一致（突增 = 代次漂移）；
5. 确认此次改动对应的钩子/日志行**在列**（例如睡眠链那行 `Sleep-mode network whitelist hooked`）。

注意日志中 `AppStandbyController#setUidState hooked` 这类行会**每次 package-ready 重复一次**（热重载会重跑），看起来像装了多个钩子，实际 `setId()` 已把它们收敛为一条活钩子。行尾带 `pkg=` 与 `userId=` 就是为了消除这个误读。

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

### 10.3 守门能力的结构性缺失（已确证为死路，见 11.2）

`DomesticPolicyManager#deferBroadcast(String)` 的签名**只有 action，没有包名**，因此无法按目标应用守门：任何 c2dm 都被无条件免延迟。这与"未勾选应用应与未装模块时一致"的承诺有偏差（方向是偏松，不丢推送，但会带来额外耗电）。在当前钩点上不可实现，需要换钩点或改用调用栈判定。

**2026-10-01 静态确证**：本 ROM 上该守门缺口对 c2dm 不成立——c2dm 在链路源头 `isAllowBroadcast`（偏移 00b3）就被提前放行，永远到不了 defer 调用，详见 11.2。动态佐证：2026-09-30 19:57 导出的 modules 日志（探针已在位、钩子已装配、窗口 50s）`deferBroadcast: c2dm delivery reached this hook` 零命中，与静态结论一致。

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
| `deferBroadcast` 守门 | **静态确证为结构性死路（2026-10-01），关闭**。全 ROM 素材（services dex1–4 全量 + miui-framework + PowerKeeper）唯一链路：`isAllowBroadcast` →（invoke-direct）→ `deferBroadcastForMiui` →（invoke-interface）→ `PolicyManager.deferBroadcast` → Domestic 实现，无第二条路；且 `isAllowBroadcast` 对 c2dm RECEIVE 在偏移 00b3 **提前 return true（允许，不延迟）**，走不到 defer 调用 ⇒ ACTION_REMOTE_INTENT 永远到不了 `DomesticPolicyManager#deferBroadcast`，c2dm 分支在本 ROM 是防御位而非行为路径。调用方 `isAllowBroadcast(I,String,I,String,String)` 手里有包名且已被钩（563–592 行已按 arg3/uid 解析目标包）——若未来真要守门，正确钩点是它而非 defer。保留 c2dm one-shot 日志作反证：若它命中，说明存在静态分析外的路径，届时重开 |
| `setUidState` 缓存分歧  | 唯一可行形态是"强制缓存为 false，再调用 `setUidState(uid, true)` 让方法体完整执行"；只补写缓存无效（提前返回就在该方法内）。当前所有可观测量都表明分歧未发生，**保持不实现**是刻意的选择 |
| 非 GMS 免冻            | 代价远超严格模式语义范围，不在计划内                                                                                              |

### 11.3 兼容性侧

- 每次 ROM 大版本升级后，以 `M target(s) absent` 的变化为起点做一次全量复核；
- 新增钩子时同步补齐"到达但未命中"的对应日志，否则该钩子的静默不可解释；
- 对代次漂移的符号一律走"多候选 + `logSkipOtherGeneration`"，不硬编码名字。

---

## 附录 A：钩子清点

| 域             | 组   | 目标                                                                                        | 方向                     |
| ------------- | --- | ----------------------------------------------------------------------------------------- | ---------------------- |
| system_server | 投递  | `AMS#broadcastIntentWithFeature`                                                          | 补 flag + 临时豁免          |
| system_server | 投递  | `BroadcastQueueModernStubImpl#checkApplicationAutoStart`                                  | → true                 |
| system_server | 投递  | `GreezeManagerService#isRestrictReceiver`                                                 | → false + thawUidAsync |
| system_server | 投递  | `GreezeManagerService#isNeedCachedBroadcast`                                              | → false                |
| system_server | 投递  | `GreezeManagerService#isAllowBroadcast`                                                   | → true                 |
| system_server | 投递  | `DomesticPolicyManager#deferBroadcast`                                                    | → false（无守门）           |
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
