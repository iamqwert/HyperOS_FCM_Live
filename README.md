# HyperOS FCM Live（HyperGreeze）

去除小米澎湃系统（HyperOS）对谷歌推送连接相关广播的限制，让 FCM 推送在小米设备上保持可用

**本模块使用 AI 辅助**

[使用帮助](HELP.md) · [技术文档](HOOKS_AND_DIAGNOSTICS.md) · [更新日志](CHANGELOG.md) · [下载](https://github.com/iamqwert/HyperOS_FCM_Live/releases)

---

## 简介

澎湃 OS 的省电策略会限制后台应用的广播与网络。谷歌推送（FCM）靠 GMS 发一条广播把应用叫醒，这条广播一旦被延后或拦下，应用就收不到推送

本模块在系统框架层（`system_server` 与 `com.miui.powerkeeper`）放行谷歌推送与重连相关的广播，不让 GMS 断网、被冻结或被拉黑；收到推送时按白名单为应用申请约 2 秒的临时省电豁免，并让应用即使在「已停止」状态下也能收到这条广播

它**不保活 FCM 应用、不阻止系统回收 FCM 应用进程、不修改 GMS 本身** 

> 代价：放行推送重连会允许应用被推送唤醒，启用后系统耗电可能上升

---

## 功能

| 分类 | 项目 |
|---|---|
| 推送修复 | 放行 c2dm 投递与重连广播、补 `FLAG_INCLUDE_STOPPED_PACKAGES`、过 greeze 自启动与冻结门、投递时约 2 秒省电豁免 |
| GMS 保护 | 写入不限制名单、省电场景改为「无限制」、跳过快速冻结、关闭网络限制；睡眠模式期间是否断网由系统决定，默认不介入 |
| 白名单 | 决定谁拿到「被推送唤醒」特权；支持多选批量加入/移出、搜索、全选 |
| 严格模式 | 在白名单之上追加收窄：把「允许推送广播送达」与「防止被强停」也收回到勾选的应用 |
| 列表过滤 | 显示系统应用、展示支持 FCM 的应用、排除 MiPush 应用 |
| 工具 | 打开 GMS 自带诊断界面、白名单导出/导入、检查更新 |
| 外观 | 主题模式、动态颜色、调色风格、颜色规格、隐藏桌面图标 |
| 入口 | Shortcuts 直达设置、帮助、FCM 诊断 |

> 「帮助」入口用浏览器打开在线文档 [HELP.md](HELP.md)，应用内不再内置帮助页

---

## 工作原理

一次推送的链路是：GMS 收到 FCM 消息 → 发出 `com.google.android.c2dm.intent.RECEIVE` 广播 → 目标应用的接收器被执行

澎湃 OS 在这条链路上叠加了多层门控，模块按门控逐层处理：

| 环节 | 系统侧门控 | 模块的处理 |
|---|---|---|
| 广播能否送达 | `GreezeManagerService#isAllowBroadcast` | 严格模式下按白名单，否则一律放行 |
| 应用处于「已停止」 | stopped-packages 标记 | 补 `FLAG_INCLUDE_STOPPED_PACKAGES` |
| 自启动判定 | `BroadcastQueueModernStubImpl#checkApplicationAutoStart` | 按白名单放行 |
| 应用已被冻结 | `isRestrictReceiver`（greeze 广播门） | 放行并复现原生解冻 |
| 省电场景 / 待机 | `AppStandbyController#setUidState` | 改写为 allow |
| 冻结策略 | `AurogonImmobulusMode#isNoRestrictApp` 等 | 对 GMS 返回豁免 |
| 广播被延后 | `isNeedCachedBroadcast`（greeze 广播缓存） | 按白名单放行 |
| 重连/心跳被延后 | `deferBroadcastForMiui` | 仅 GMS 重连类动作返回不放行 |
| 强停 | `isForceStopEnable` | 严格模式下只对勾选的应用生效 |

被 hook 的宿主只有两个：`system`（系统框架）与 `com.miui.powerkeeper`

GMS 侧的四项保护：

- **P1** —— 把 GMS 保持在 `Settings.System.MILLET_NO_RESTRICT_APP` 名单里
- **P2** —— 在 system_server 中为 greeze 冻结路径兜底
- **P3** —— 把 GMS 的编译场景强制为「无限制」（8）而不是 0
- **P4** —— 发现 GMS 已被冻住时，请求 GMS / GSF 重新建立连接并解冻

---

## 安装与启用

### 要求

- HyperOS 3 / 4
- LSPosed（API ≥ 101）
- Google Play 服务可用

### 步骤

1. 从 [Releases](https://github.com/iamqwert/HyperOS_FCM_Live/releases) 下载 APK 并安装
2. 在 LSPosed 管理器启用模块及勾选作用域
3. 等待作用域自动热重载（LSPosed 需支持 API 102 +）；若失败或没通知，需手动重启设备以应用
4. 打开模块配置列表

详细的白名单与严格模式说明见 [HELP.md](HELP.md)

---

## 兼容性
已验证机型：

| 机型 | 系统 |
|---|---|
| Xiaomi 17 Pro Max | OS3.0.319 / Android 16 |
| REDMI K90 Pro Max | OS4.0.33  / Android 17 |

---

## 已知边界

- **c2dm 是否被延后与能否送达由同一处判定决定**。该判定读白名单与严格模式，因此严格模式下未勾选应用的延后与否交回系统判断，表现与未装模块时一致
- **严格模式名义收权三处，其中「免网络限制」依赖国际版策略实现**。实测 HyperOS 4 国内版走国内策略，该项不参与，实际收权两处
- **严格模式开启后，未勾选的应用会重新暴露在被强停的风险中**。防止被强停默认对全部声明了谷歌推送组件的应用生效，开启严格模式后收回到勾选的应用：未勾选的应用从最近任务划掉会被真正强停、标记为「已停止」，之后要重新打开一次应用才能恢复推送
- **GMS 保护有一部分以整机策略表的形式写入**（省电场景、进程白名单、不限制名单），对整台设备生效，勾选与严格模式都不会把它们收窄
- **睡眠模式是整机断网**。进入睡眠时系统直接关闭 WiFi 与移动数据，不按应用区分；需要整夜保持联网可在实验功能里开启，可选保留 WiFi、保留移动数据，或两者都保留
- **目标应用不在免冻集合内**。被推送拉起后进入缓存进程、之后被内存回收或 greeze 冻结都属正常，不影响下一次推送
- **高内存负载时清理内存 GMS 可能短暂掉线**，约 3 秒，会立即恢复

---

## 致谢

本项目是修改版，参考并致谢以下项目与贡献者：

- [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)
- [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live)
- [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer)
- [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- [Kr328/HyperOSFCMFix](https://github.com/Kr328/HyperOSFCMFix)
- [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- [zuohl/HyperOS_FCM_Live](https://github.com/zuohl/HyperOS_FCM_Live)
- `GET_INSTALLED_APPS` 运行时权限申请思路参考自 250king 的 [PR #1](https://github.com/250king/HyperOS_FCM_Live/pull/1)

---

## 许可

本项目采用 [GNU GPL-3.0](LICENSE) 协议
