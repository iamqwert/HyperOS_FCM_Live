# FCM Diagnostics 界面与 GCM dump 全量格式取证（GMS 26.41.62）

> 取证对象：`com.google.android.gms` base.apk，版本 `26.41.62 (260400-1002810655)`，
> 拉取自 REDMI K90 Pro Max（HyperOS 4.0.4.0 / Android 17），2026-10-23。
> 方法：dexsql 静态字节码取证（`decode` / SQL on strings）。
> 产物：`rom-dump/gms-diagnostics-26.41.62/`（已 gitignore，目录内有 `MANIFEST.txt` 登记输入 SHA256
> 与 12 个 smali 的来源方法）——`gms-base.apk` 与各解码 smali：
> `chimera.smali`（UI）、`cnzw.smali`（binder 代理）、`coaa.smali`（服务端 binder 实现）、
> `i.smali`（Advanced 状态文本）、`jjap.smali`（close code 官方释义表）、`coeu.smali`（MCS 控制器）、
> `cogi.smali` / `codx.smali`（dump 打印链）、`svc.smali`（普通状态文本）、`cnzv.smali`。
>
> **有效性**：仅对该 GMS 版本有效；GMS 更新后需重新取证。所有"字面量"均为二进制内 const-string 原文。

## 1. UI 结构

| 组件 | 类 | 事实 |
| --- | --- | --- |
| 诊断 Activity | `com.google.android.gms.gcm.GcmChimeraDiagnostics`（入口壳 `GcmDiagnostics` extends Chimera 基类） | 单 Activity：一个 Button 在「Events」「Status」间切换（`f()`），右上角菜单项 **"Advanced view"** 可勾选（`onCreateOptionsMenu`），只影响 Status 文本，**Events 两种模式恒同**（`d()`） |
| 数据通道 | `cnzw` → binder `coab` → 服务端 `coaa` | Activity 发有序广播 `com.google.android.gms.gcm.GCM_SERVICE_DIAGNOSTICS`（package=`com.google.android.gms`，extras 带 callback binder），`GcmChimeraService` 侧 `coaa` 应答 |
| Status 普通视图 | `coaa.b()` → `GcmChimeraService.c(PrintWriter)` | 与 `dumpsys activity service com.google.android.gms/.gcm.GcmService` 上半段同源 |
| Status Advanced 视图 | `coaa.a()` → `coeu.i()`；连接层禁用时打印字面量 `Disabled` | 见 §3 |
| Events 视图 | `coaa.c()` → 事件环字符串列表，UI 端按搜索框过滤 | 每行即 dump 事件环一行去掉 `MM-dd HH:mm:ss.SSS net=N:` 前缀 |

## 2. 状态页 · 普通视图（`GcmChimeraService.c(PrintWriter)`，即 dump 上半段）

按打印顺序（`svc.smali`）：

| 字面量 / 格式 | 内容 | 解析器覆盖 |
| --- | --- | --- |
| `DeviceID: <id>` | 直接打印 `coeu.D`；Direct boot 时为 `Direct boot mode (DeviceID not available`（注意：原文缺右括号）；否则打印 `cnzv.a()`（androidId） | 刻意不解析（隐私） |
| `Disabled` | 连接层状态 ordinal = -1 时整页只有这一行 | 未解析（正常 ROM 不可见） |
| （连接时）`<Client 块 toString>` | `coeu.toString()`，字段见 §4 | `GcmSnapshot` 大部分字段 |
| `Heartbeat waiting ack <cohb>` | 心跳已发出未收到 ACK 时取代下一行 | **未解析** |
| `Heartbeat: <cohb>` | `cohb.toString()`（`Heartbeat:` + 间隔） | 部分（HB algorithm 块覆盖间隔） |
| `Last heartbeat reset connection <elapsed> ago` | 上次因心跳超时重置连接距今（`DateUtils.formatElapsedTime`） | **未解析** |
| `Last ping: <time>` | 上次 ping 时刻（`La;->E(J)` 格式化） | **未解析** |
| `Adaptive Heartbeat type <N>: <bool>` | 每种传输类型一行（cellular/wifi 等） | `adaptiveHeartbeat`（取第一处） |
| `connectionsLimit: <N>` | 跟在 Adaptive 行后 | `connectionsLimit` |
| `All stored connections: ` | 其后每行一个已存连接的 toString（即 `HB algorithm:` 块） | `heartbeat`（取最后一处） |
| `Last connected: <codi>` | 上一次连接的 HB 算法块 toString | 同上 |
| `Seen good heartbeat in last connection? <bool>` | 上条连接是否收到过正常心跳 | **未解析** |
| `Connecting` / `Not connected` | 未建立/未连接分支 | `connected` 为布尔（见 §9 缺口） |
| （随后）`cogi.d(PrintWriter)` | 重连闹钟、网络状态、事件环与各队列块 | 见 §5、§6 |

`cogi.d(PrintWriter)`（`cogi.smali`）：

| 字面量 / 格式 | 内容 | 解析器覆盖 |
| --- | --- | --- |
| `Reconnect Scheduler Alarm: in <Duration>` / `Reconnect Scheduler Alarm: OFF` | 下次重连调度（`java.time.Duration.toString()`，如 `PT8.388S`） | `reconnectAlarm` |
| `Fallback mode: <hzln.a(I)>` | 回退主机策略枚举名 | **未解析** |
| `Supports restricted networks: true` | 固定行 | 未解析（常量） |
| `Is client connected: <bool>` | **判断连接态的权威行** | `connected` |
| `Network Status:` + `  Active:           <coew>` + `  Preferred:        <coew>` + `  In-use by client: <coew>` | 网络三态（`coew.toString()` = `GcmNetwork{netId 类型(type)}`） | `network` |
| （随后）`cods.d(PrintWriter)` → `codx.d()`：`ConnectivityManagerVariantImplAsync`、`<monitor> active: <coew>`、` available: `、每行 `  <coew>` | 可用网络列表 | `network.available` |

## 3. 状态页 · Advanced 视图（`coeu.i()`，`i.smali`）

纯文本拼接，`\n` 分隔：

| 分支 | 输出 |
| --- | --- |
| 已连接 | `Server: Connected\nHost: <InetAddress>\nPort: <port>\nTime connected: <formatElapsedTime(存活秒)>` |
| 连接中 | `Server: Connecting\nHost: <主机名>\nPort: <port>` + （有过断开史时）`\nTime disconnected: <距今秒>` |
| 未连接 | `Server: Not connected\nHost: <主机名>\nPort: <port>` + 同上或 `\nNo previous connection` |
| 尾行 | 最近一次 close code ≠ 0 时：`\nLast close code: <jjap.a(code)>`（官方名，见 §7） |
| 连接层禁用 | 整页 `Disabled` |

要点：Advanced 视图**会打印 IP**（已连接时 `InetAddress`），普通视图与 dump 都只打主机名；`Time disconnected` = `断开时刻 + 重试间隔×N - now` 推导，等价于"距上次断开"。

## 4. Client 块（`coeu.toString()`）

格式字面量（顺序）：
`connected=<host/ip>` `,port=<port>` `connecting=<bool>` `pending=<bool>` `,host=<host>` `\nstreamId=<id>` `,connects=<n>` `,failedLogins=<n>` `,lastConnectionDurationS=<s>` `,lastConnectionErrorCode=<code>` `\nconnectTime=<time/host>` `\ndisconnectTime=<time>` `\nlostConnection=<bool>` `Reads within the wakelock budget: <n>` `\nReads that exceeded the wakelock budget: <n>`

解析器覆盖：`connected/port/host` → `endpoint`；`streamId`、`connects`、`failedLogins`、`connectTime` ✓。
未覆盖：`connecting`/`pending`（三态来源）、`lastConnectionDurationS`（上条连接存活秒——事件 `time:S` 的同源字段）、`lastConnectionErrorCode`（同 §7 code space）、`disconnectTime`、`lostConnection`、wakelock 预算读写计数（Doze 保活是否掐读的证据）。

## 5. dump 下半段各块（`\n` 前缀字面量，pool 9）

| 字面量 | 内容 | 解析器 |
| --- | --- | --- |
| `\nAffinity Scores:` | 每行 `<pkg>:<uid> <score>` | `affinityClients`（计数） |
| `\nApps supporting client queue:` | 每行 `<pkg>:<uid> v<n>` | `queuedClients`（计数） |
| `\nHB algorithm:` | `…, heartbeat_interval: <ms>, phase: <p>, good_heartbeat_count: <g>, bad_heartbeat_count: <b>`；实现类有 `FastSlowHeartbeatAlgorithm{fast_upward_trigger: …}` | `heartbeat` |
| `\nHigh priority quota usage:` | 高优先级配额明细 | `highPriorityQuota` |
| `\nQueued messages:` | 排队消息，每行含 `reason=<n> retries=<n>`；reason 取值即 `CLIENT_QUEUE_*` 枚举（见 §8） | `pendingMessages`（计数） |

## 6. 事件环格式全表

环行前缀：`MM-dd HH:mm:ss.SSS` + ` net=<netId>: <text>`（` net=` 为 pool 9 idx 1040 字面量）。
下表 `<text>` 的全部字面量/printf 模板（按类别）：

| 类别 | 字面量 / 模板 | 解析器 EventKind |
| --- | --- | --- |
| 连接建立 | `Connected` | Connected ✓ |
| 登录 | `Sent LoginRequest; HB interval sent: %d` | Login ✓ |
| 心跳收发 | `Received Server HB, interval received: %d` / `Sent Server HB Ack; interval sent: %d` / `Sent Client HB; interval sent: %d` / `Received Client HB Ack, interval received: %d` | Heartbeat ✓ |
| 心跳闹钟 | `Heartbeat alarm delay: %d ms` / `Heartbeat alarm fired early: %s` | Heartbeat ✓（fired early 未单列） |
| 心跳超时 | `Heartbeat timeout, GCM connection reset` / `Heartbeat timeout, GCM connection reset %s` | Heartbeat ＋异常（心跳阶段，`Close err:6` 的前奏） |
| 关闭 | `Close err:%s time:%d`（code 见 §7） | Close ✓ |
| 失败连接 | `Failed connection err:%d`（pool 15）/ `Failed to connect to %s, trying %s`（主机回退，pool 9） | Reconnect ＋异常（重连阶段） |
| 主动断开 | `Disconnect since no networks are available` / `Disconnect since GCM is no longer enabled` | ServerClose（仅连接类，无码不定性） |
| 重连 | `Reconnect alarm delay: %d ms` / `Global backoff: <Duration>` / `Starting parallel %s in place of existing %s` / `Connecting using %s` | Reconnect ✓ |
| 网络切换 | `Active network switched to %s, was %s` / `Active network went away, was %s` / `Active network is now %s`（第三种 pool 9 idx 未知，代码内存在）/ `onGcmNetworkChanged: %s` | Network ✓ |
| Doze | `Client Entering doze` / `Client Exiting doze` | Doze ✓ |
| 协议报文 | `Sent IqStanza %s` / `Received IqStanza %s` | IqStanza ✓ |
| 消息接收 | `Received %s %s`（pkg、msgid） | MessageReceived ✓ |
| 消息回执 | `Acked %s %s`（pkg:uid、msgid） | MessageAcked ✓ |
| 广播完成 | `Successful broadcast to %s (id=%s time=%dms priority=%s)` | BroadcastCompleted ✓ |
| 广播失败 | `Failed to broadcast to stopped app %s (id=%s time=%dms priority=%s)` | BroadcastFailed ✓ |
| 广播无响应 | `No response to broadcast from %s (id=%s time=%dms priority=%s)` | BroadcastNoResponse ✓ |
| 其它代码内存在 | `Received Close`、`Sent Close`、`Disconnected`、`Closing because of a remote restart` | ServerClose（仅连接类，无码不定性） |

> 归属模型：**竖切 6 类生命周期**（网络 / 连接 / 登录会话 / 心跳 / 投递 / 休眠）＋**横切 1 个异常维度**（Close、心跳超时、连接失败、退避、广播失败按 ErrorStage 二次标注），一条事件可同时带竖切与横切标签，见 `EventTaxonomy.kt`。

### 6.0 事件格式的完整枚举（2026-10-23 补，之前此表并不完整）

上表原是「实机观测 + 部分 smali 解码」的产物，**漏了十余种真实存在的事件行**（例：用户实机看到的 `Sending enter doze bit.` 就被归入「其他」）。补全方法：事件环的唯一写入口是
`Lcom/google/android/gms/gcm/GcmChimeraService;->b(Ljava/lang/String;[Ljava/lang/Object;)V`（见 `coeu.smali` 的调用点），
用 DexSQL 对 `gms-base.apk` 查 `method_calls` 取该方法的 22 个调用方方法，再取这些方法的 `method_strings` 字面量，
得到候选集（去重后全量 102 条，套过滤后 65 条，见本节末「复现口径」），
人工剔除与事件无关者后即为事件格式全集（GMS 26.41.62）。此前缺失、现已覆盖的：

| 类别 | 字面量 | 归属 |
| --- | --- | --- |
| 休眠 | `Sending enter doze bit.` | Doze |
| 休眠 | `Force release of GOOGLE_C2DM lock` / `Force releasing GOOGLE_C2DM lock after %d ms` | Doze |
| 登录 | `Login error %s %s` / `Login failed on server` / `Missing Android ID or token` / `Invalid appId` / `Got new GCM ID/token` / `Received message before LoginResponse` | Login |
| 心跳 | `Alarm %s fired %d ms late` | Heartbeat |
| 关闭 | `Sent Close` / `Closing because of a remote restart` | ServerClose |
| 重连 | `Skipping connect attempt since the network is suspended. Network: %s` / `Skipping connect attempt since there is no active network.` / `No addresses for host` | Reconnect |
| 网络 | `Active network is now cellular, but already on cellular fallback.` | Network |
| 广播 | `Failed to broadcast to uninstalled app %s (…)` / `Failed to broadcast to %s, it may be updating (…)` / `%s returned error code=%d to broadcast (…)` / `Broadcast sent to %s missing Intent package (…)` | BroadcastFailed |
| 传输 | `IOException in writerThread` / `IOException while handling message` / `Unexpected error in writerThread` / `Read took too long, took %d ms to read and parse %d bytes` | Transport（新 kind） |

**仍未归类（刻意，fail-open 到「其他」）**：`Sent %s`（GMS 的通用占位，无固定语义）、
`Sending IID %s to all apps` / `Sending IID SYNC to all apps`（GCM ID 分发，与推送链路判断无关）、
`Proxied notification for %s (id=%s)`（通知代理到其他设备，尚未取证语义）、`GSync (p=`（拼接片段）、
`broadcast intent callback: result=CANCELLED for%s`（拼接片段）、`%s returned error code=…` 之外的写线程杂项。

> 复现口径（2026-10-26 固化）：`rom-dump/queries/gms-base.apk/05_format_enumeration.sql`。按该脚本实测：
> 调用方方法 **22 个**（classes2.dex 17 个 / classes8.dex 5 个——`b(...)` 在 multidex 里有两份，两份都要收）；
> 字面量**去重后全量 102 条**，套上「含 `%` 或 大写开头且含空格」的过滤即为 **65 条**，
> 也就是本章上一段原先说的「65 条候选」——**这是同一个查询的两个阶段，不是矛盾**。
> 过滤只为滤掉纯常量/包名/字段名；拼接片段（`GSync (p=` 等）过不了任何静态筛选，见下条结论。

> 结论：**无法证明"绝对完整"**——GMS 可能拼接字符串（`GSync (p=` 就是半句），且新版 GMS 会增删格式；
> 但"所有静态字面量"这一层已穷举，再出新格式就是动态拼接或版本更新，属 fail-open 范畴。

### 6.1 `net=N` 与 `GcmNetwork{N X(t)}` 的编号语义

`net=N` 前缀与 `GcmNetwork{N X(t)}` 里的 `N` 是**同一个东西**：ConnectivityManager 的 netId（netd 路由/规则表的键）。

| 取值 | 含义 | 证据 |
| --- | --- | --- |
| `-1` | GCM 侧无活动网络（连蜂窝也没有的整机断网时刻） | 本机观测：`net=-1` 行只伴随蜂窝也消失（§4.7.2） |
| `51` / `52` / `99` | netd 保留段：`DUMMY_NET_ID` / `UNREACHABLE_NET_ID` / `LOCAL_NET_ID`，不会出现在事件环里 | 本 ROM `services.jar` 的 `Landroid/net/INetd;` 静态常量（2026-10-23 取证） |
| `100+` | **物理网络**（WiFi / 蜂窝 / VPN 等 NetworkAgent），开机后从 100 起单调递增分配，**不复用** | 保留段到 99 ⇒ 物理段从 100 起；实机 `102/103/104/106` 完全吻合 |

判读规则（引自 FCM_HOOK_TECH_DOC.md §5.7.2，实机 44min 样本验证）：

- **同一个 N 回来** = 网络对象没销毁，只是默认网络被切走（WiFi 链路没断）；
- **换了一个更大的 N** = 网络对象被销毁重建 ⇒ 该链路**真的断过一次**；
- 一次开机内单调递增、不复用，因此号差值没有绝对含义，只有"换没换号"有含义；
- 分配循环本体在 Connectivity APEX（`ConnectivityService`，不在 rom-dump 取证范围），"从 100 起单调递增"由保留段常量与实机序列交叉限定。

`X(t)` 的 `t` 是 framework 网络类型常量：`0` = `TYPE_MOBILE`（蜂窝），`1` = `TYPE_WIFI`。实机互证：`Starting parallel McsConnection{WiFi · 104, type=1} in place of existing McsConnection{Cell · 103, type=0}`——一条事件同时给出新旧两条网络的 netId 与类型。

## 7. Close code 官方全表（`Ljjap;->a(I)`，`jjap.smali`）

这是 GMS **要显示给用户的**枚举名（Advanced 视图 `Last close code:`），即官方释义，不再是"反推"：

| code | 官方名 | code | 官方名 |
| --- | --- | --- | --- |
| 1 | `ERR_NET_DOWN` | 23 | `ERR_UNREACHABLE_PORT` |
| 3 | `ERR_UNKNOWN_HOST` | 24 | `ERR_CONNECTION_TIMEOUT` |
| 4 | `ERR_AUTH` | 25 | `ERR_CONNECTION_REFUSED_REMOTELY` |
| 6 | `ERR_HEARTBEAT_ACK_TIMEOUT` | 26 | `ERR_SSL_EXCEPTION` |
| 10 | `ERR_UNKNOWN` | 27 | `ERR_CLOSE_BY_NEW_CONNECTION` |
| 11 | `ERR_DISABLED` | 28 | `ERR_IO_RST_HB` |
| 14 | `ERR_PROTOCOL` | 29 | `ERR_MCS_RECONNECT_REQUEST` |
| 15 | `ERR_SERVICE_END` | 30 | `ERR_CLOSE_BY_USER_UNLOCKED` |
| 16 | `ERR_IO_CONNECTING` | 31 | `ERR_MISSING_CHECKIN_CREDENTIALS` |
| 17 | `ERR_MANUAL` | 32 | `ERR_SERVER_NOT_AUTHORIZED` |
| 18 | `ERR_REMOTE_CLOSE` | 33 | `ERR_SERVER_NOT_ALLOWED` |
| 19 | `ERR_IO_RST` | 34 | `ERR_SERVER_INTERNAL_ERROR` |
| 20 | `ERR_IO_FIN` | 35 | `ERR_FAILED_PROBE` |
| 21 | `ERR_IO_WRITE` | 36 | `ERR_IO_WHILE_HANDLING_MESSAGE` |
| 22 | `ERR_UNREACHABLE_HOST` | 其余 | （映射外，`null`） |

与本机 §4.7.1 共现反推的对照：

- `27 = ERR_CLOSE_BY_NEW_CONNECTION`：与"并行新连接顶掉旧连接"**完全吻合**，反推成立。
- `6 = ERR_HEARTBEAT_ACK_TIMEOUT`：吻合。
- `20 = ERR_IO_FIN`（读侧收到 FIN）、`25 = ERR_CONNECTION_REFUSED_REMOTELY`：**官方语义比反推更精确**。20/25 成对、`time:S` 相同 ⇒ 同一连接读/写两侧各报一次；"网络对象失效"是行为学描述，官方语义是 TCP 层读 FIN + 远端拒绝。
- 实机曾见 `err:1`（`net=-1`）→ `ERR_NET_DOWN`，与"net=-1 连蜂窝也没有"的观测吻合，**可定性**。

## 8. 队列 reason 枚举（`Queued messages:` 的 `reason=` 取值）

pool 9 的 `CLIENT_QUEUE_*` 常量：`NEW_MESSAGE`、`DUPLICATE_MESSAGE`、`APP_UNINSTALLED`、`APP_DOES_NOT_SUPPORT_RETRIES`、`ATTEMPTING_RETRY_ON_RESTART`、`ATTEMPTING_RETRY_WITH_BACKOFF`、`DROPPED_APP_RESTRICTED`、`DROPPED_EXCEEDED_MAX_RETRIES`、`DROPPED_EXCEEDED_STORAGE_LIMIT`、`DROPPED_EXPIRED`、`ERROR_DIFFERENT_MESSAGE_IN_DB`、`ERROR_MISSING_FROM_DB`、`EXCEEDED_MAX_RETRIES`、`EXCEEDED_STORAGE_LIMIT`。
（数字 → 枚举的映射未逐个取证；判读时以"挂起计数 + 共现事件"为主。）

## 9. 解析器缺口清单（后续可选改进）

按价值排序：

1. **`Total Delay` 行**：`  Total Delay: %dms (Server-to-Client: %dms, Pre-Broadcast Queue: %dms, App Broadcast: %dms, Result: %d)%n` —— 投递延迟分段测量，是"推送慢"的第一手证据。
2. **`lastConnectionErrorCode`**（Client 块）：状态页可直接显示官方 close code 名，不必等事件环翻页。
3. **`connecting`/`pending`**：把 `connected` 布尔升级为三态（Advanced 视图就是这么做的）。
4. **`Heartbeat waiting ack` / `Last ping:` / `Last heartbeat reset connection … ago`**：心跳健康度直接读数。
5. **`Reads within/that exceeded the wakelock budget`**：Doze 期间读被掐的计数。
6. ~~`Failed connection err:%d` / `Heartbeat timeout, GCM connection reset`：事件分类补两类。~~ **已完成**（2026-10-23，`EventKind.ServerClose` 与横切归属落地）。
7. `--- High Latency / Delayed Messages (Last 10) ---` 与 `  Impact: %d messages queued during outage%n`、`  (No delayed messages recorded)`、`%s [MSG_DELAYED] id=%s pkg=%s pri=%s%n`、`Hour (UTC) | Conns | Drops | …` 统计表：GMS 内建的延迟/丢包统计，实机 dump 未见（可能需特定 flag），取证到格式先行记录。

## 10. 状态页覆盖度评估（本 app「记录」页 状态 tab）

> 与 §9 的分工：**§9 是解析层**缺口（dump 里有、解析器没读）；**本节是展示层**（解析了但不显示、显示成了别的形状、或根本无从取得）。
> 2026-10-24 完成一轮补齐，本节已按补齐后的状态重写。实机基准：真机 dump 一份（`dumpsys activity service com.google.android.gms/.gcm.GcmService`，GMS 26.41.62 / HyperOS 4.0.4.0）。

### 10.0 现状：5 段

| 段 | 行（按显示顺序） | 源 |
| --- | --- | --- |
| 连接 | 连接状态（三态）/ 服务端 / 已连接时长 / 上次连接时长 / 上次关闭 / 上次断开 / Stream ID / 累计连接 / 并行连接上限（分类型）/ 主机回退 / GMS 进程 | `Is client connected:`、`Connecting`、Client 块 `connected=…`、`connectTime`、`lastConnectionDurationS=`、事件环最新 `Close err:N`、`disconnectTime=`、`streamId`、`connects`、`connectionsLimit:`、`Fallback mode:`、header `pid=` |
| 网络 | 活动 / 首选 / 客户端使用中 / 可用列表 | `Network Status:` 三行 + `available:` 列表 |
| 心跳 | 自适应心跳（分类型）/ 心跳算法 / 心跳闹钟 / 心跳确认 / 上次 ping / 上次心跳重置 / 上次连接收到正常心跳 | `Adaptive Heartbeat type N:`、`HB algorithm:`、`Heartbeat: alarm(…)`、`Heartbeat waiting ack`、`Last ping:`、`Last heartbeat reset connection … ago`、`Seen good heartbeat in last connection?` |
| 推送 | 支持队列的应用（数 + **全部应用名**）/ 亲和评分（数 + 应用名与分值，取前 3）/ 高优先级配额（原块）/ 排队消息（数）/ 最近一次投递（延迟分段） | `Apps supporting client queue:`、`Affinity Scores:`、`High priority quota usage:`、`Queued messages:`、`Total Delay:` |
| 异常 | 登录失败 / 重连闹钟 / 全局退避 / 每网络退避 / Wakelock 读取 | `failedLogins`、`Reconnect Scheduler Alarm:`、`Global backoff:`、`Next backoff interval per network:`、`Reads within / that exceeded the wakelock budget` |

行数随 dump 内容浮动：**条件行只在 GMS 打印时出现**，缺失即无此行，不补零、不显示占位。

### 10.1 2026-10-24 补齐的具体项

| 曾经的样子 | 现在 |
| --- | --- |
| 连接态两态（已连接 / 未连接） | 三态，`Connecting` 单独成态（拨号窗口不再读成"断开"） |
| 只有"已连接时长" | 另有"上次连接时长"（`lastConnectionDurationS`，>0 才显示）与"上次关闭"（事件环最新 coded close + §7 官方 gloss + 时刻） |
| 亲和评分 / 客户端队列只有计数 | 计数 + **应用显示名**（PackageManager 解析，与事件行同一套；未安装的回落包名）。客户端队列**全量列出**，亲和评分取前 3 并折"等"，按分值降序 |
| 自适应心跳 / 并行连接上限只取第一处 | 按传输类型分别显示（`Cell 关闭，WiFi 开启`；上限 `Cell 5，WiFi 15`），类型名从 dump 自己的 `GcmNetwork{…}` 学到 |
| `Global backoff: PT4.026S` 原样显示 | 转成时间（`4.03 秒`）；另加"每网络退避"（`Next backoff interval per network:`） |
| 无 | 心跳健康三读数、Wakelock 读写计数、最近一次投递延迟分段、主机回退策略、GMS 进程号 |

顺手修掉一个真 bug：**排队消息**把 `Queued messages:` 块里的 `For scheduler stats see SchedulerService dump.` 这句话当成了一条消息，导致空闲队列永远显示"1 条"。现在只数带 `reason=` 的行（§5/§8 记录每个条目都带）。

### 10.2 解析器已备，但 OS 4 实机 dump 不打印

以下字段的解析都在（缺了就是 null，行不出现），但在这台设备上 **GMS 26.41.62 根本不打印**，所以真机上永远看不到：

| 字段 | 出处 | 说明 |
| --- | --- | --- |
| `lastConnectionErrorCode=` | §4 Client 块 | 实机 Client 块只到 `lastConnectionDurationS=`；"上次关闭"改由事件环提供 |
| `disconnectTime=` | §4 | 同上 |
| `Heartbeat waiting ack` | §2 | 仅"发了没收到"时打印 |
| `Last ping:` | §2 | 同上 |
| `Last heartbeat reset connection … ago` | §2 | 同上 |
| `Seen good heartbeat in last connection?` | §2 | 同上 |
| `Reads within / that exceeded the wakelock budget` | §4 | 实机无 |
| `Total Delay:` 分段 | §9 #1 | 实机无（推测与特定 flag 或调度器 dump 有关） |

> 判读：这些是"GMS 想说才说"的行。缺行 ≠ 正常。文档记录在此，将来换机型或 GMS 版本若打印出来，页面自动就有。

### 10.3 状态页无能力展示（并说明为什么）

| 想展示的 | 为什么做不到 |
| --- | --- |
| 服务端视角的一切：服务端队列长度、消息 TTL 过期时刻、服务端重试选择、实际配额判定 | dump 是**客户端**单视角的即时快照，服务端行为不在设备上。要服务端事实只能用 FCM 服务端 API / 控制台，属另一条取证路径 |
| 消息内容（payload / data / title） | dump 只留 `pkg` + `msgid`；载荷不落盘也不进事件环（这是设计，不是缺口） |
| 注册令牌、Android ID / DeviceID | dump 有 `DeviceID:` 一行，本 app **刻意不解析**（隐私）；令牌则根本不在 dump 里 |
| 其它用户 / 工作资料 / 多开环境里的 GMS | 抓取走的是当前进程的 `dumpsys …/.gcm.GcmService`，一次只有一个实例；跨 profile 要各自抓 |
| 通知代理的目标设备状态 | 事件里只有 `Proxied notification for %s (id=%s)`，对端不可见 |
| 内核 / netd / 无线层（丢包、RSSI、Doze 设备态、socket 统计） | 需要 root 才能取证与读取，本项目无 root 路径 |
| GMS 内部线程、内存、连接池状态 | dump 不含；`IOException in writerThread` 之类只在事件环留下后果，不留状态 |
| 历史趋势 / 曲线 | 只保留**最新一份快照** + 事件流（默认 500 行，可调 200/300/500/800/1200，且不早于 7 天）。没有时序落库，画不出"过去 24 小时连接次数" |
| 事件环被 GMS 覆盖掉的部分 | 事件环是内存环形缓冲，进程重启即清零；本 app 的存档是唯一副本。环容量由 Gservices flag `gcm_debug_log_size` 决定（dex 默认 50，2026-10-25 真机实测为 **200 条**），满了弹最老一条，无时间维度 |

### 10.4 判断

- **"这台设备此刻为什么收不到 / 收得慢"**：够用。连接、网络、心跳、推送队列、异常五段覆盖了链路上每一跳的当下状态，且列表类事实已能看到"是哪个 app"。
- **"这次连接是怎么断的"**：基本够用——上次关闭给了官方码义与时刻，上次连接时长给了上一段的存活时间；缺的是"断在哪一步"（需要 `lastConnectionErrorCode` 与心跳读数，实机不打印）。
- **"这条消息最后去哪了"**：永远不够，且不是本 app 的缺口（10.3 第一行）。

> 解析层回归：`HyperFCMLive/src/test/.../records/GcmDumpParserTest.kt` 用一份真机 dump 钉住上述字段，字段一丢就是红测试——因为这些行都是条件打印，静默失效与"一切正常"在界面上长得一样。
