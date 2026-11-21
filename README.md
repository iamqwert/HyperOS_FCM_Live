# HyperGreeze（HyperOS FCM Live）

**简体中文** · [English](README_EN.md)

解除小米澎湃系统（HyperOS 3 / 4）对谷歌推送（FCM）广播与 GMS 的后台限制，让推送在后台可靠送达。

> 本模块使用 AI 辅助开发。

[使用帮助](HELP.md) · [技术文档](FCM_HOOK_TECH_DOC.md) · [更新日志](CHANGELOG.md) · [下载](https://github.com/iamqwert/HyperOS_FCM_Live/releases)

---

## 这是什么

澎湃 OS 的省电与冻结策略会延后或拦截后台广播。FCM 依赖 Google Play 服务（GMS）发广播唤醒目标应用，一旦这条广播被拦下，通知就会延迟或丢失。

本模块在系统框架层（`system` 与 `com.miui.powerkeeper`）放行 FCM 广播并保护 GMS 不断网、不被冻结，让已勾选应用即使处于「已停止」或冻结状态也能被推送唤醒。

- **不保活应用**：投递完成后交回系统正常回收或冻结
- **不注入 GMS**：仅在系统侧生效，避免触发 GMS 完整性检测
- **固有代价**：允许推送唤醒会略微增加系统耗电

---

## 快速上手

### 环境要求

- **系统**：HyperOS 3 / 4（Android 15+）
- **框架**：LSPosed（API ≥ 101，建议 API ≥ 102 以支持自动热重载）
- **服务**：设备已启用 Google Play 服务（GMS）

### 安装步骤

1. 从 [Releases](https://github.com/iamqwert/HyperOS_FCM_Live/releases) 下载并安装 APK
2. 在 LSPosed 管理器中启用模块，勾选作用域：**系统框架（`system`）** 与 **电量和性能（`com.miui.powerkeeper`）**
3. 等待作用域自动热重载（API 102+）；若未自动生效，手动重启设备
4. 打开模块，勾选需要接收 FCM 推送的应用（GMS 始终自动受保护，无需勾选）
5. 切到底栏 **「记录」** 页，确认连接状态为「已连接」且钩子装载正常

更多配置与排查详见 [使用帮助（HELP.md）](HELP.md)。

---

## 核心功能

| 模块               | 说明                                                                                              |
| ------------------ | ------------------------------------------------------------------------------------------------- |
| **FCM 唤醒白名单** | 仅对勾选应用解除推送限制                                                                          |
| **GMS 系统层保护** | 始终生效；写入不限制名单、省电策略设为无限制、跳过快速冻结并放行重连广播与闹钟                    |
| **记录与诊断**     | 直接查看 FCM 状态与六类事件历史，一键导出含钩子装载摘要的诊断文本                                 |
| **常用选项**       | 睡眠模式保留 WiFi / 移动数据、禁止恢复微信电池未优化状态、配置导出/导入、个性化主题、隐藏桌面图标 |
| **实验功能**       | 默认全关，即时生效：放行应用拉起、弱信号切换放宽、冻结期联网、Smart Power（仅 OS3）               |
| **隐私与权限**     | 仅申请 `INTERNET` 用于检查更新                                                                    |

---

## 兼容性与关键边界

已实机验证：

| 机型              | 系统                         |
| ----------------- | ---------------------------- |
| Xiaomi 17 Pro Max | HyperOS 3.0.319 / Android 16 |
| REDMI K90 Pro Max | HyperOS 4.0.6.0 / Android 17 |

使用前需知：

- **未勾选的应用不放行**：未勾选应用维持系统原判，划掉最近任务会被强停
- **不保活目标应用**：被推送唤醒后进入缓存进程、随后被冻结或回收均属正常，不影响下次推送
- **睡眠模式默认不介入**：澎湃睡眠模式为整机断网，如需整夜不断连请在设置页开启「睡眠模式」选项
- **代理不在模块管辖内**：使用 VPN / 代理时请按 [FCM 官方网络配置](https://firebase.google.com/docs/cloud-messaging/network-configuration) 对 `mtalk` 相关域名配置直连（详见 [使用帮助](HELP.md)），详见[为 FCM 配置网络  |  Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging/network-configuration?hl=zh-cn)

具体实现与钩点清单见 [技术文档（FCM_HOOK_TECH_DOC.md）](FCM_HOOK_TECH_DOC.md)。

---

## 致谢

本项目最初基于以下开源项目与贡献者：

- [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)
- [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live)
- [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer)
- [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- [Kr328/HyperOSFCMFix](https://github.com/Kr328/HyperOSFCMFix)
- [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)
- [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- [zuohl/HyperOS_FCM_Live](https://github.com/zuohl/HyperOS_FCM_Live)
- 250king（[PR #1](https://github.com/250king/HyperOS_FCM_Live/pull/1)，早期应用列表权限思路；现已替换为包可见性方案）

---

## 许可

本项目采用 [GNU GPL-3.0](LICENSE) 协议开源。
