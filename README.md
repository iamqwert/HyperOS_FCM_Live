# 修复澎湃系统谷歌推送重连

去除澎湃系统对谷歌推送连接相关广播的限制。启用模块后，系统耗电可能会增加。

感谢 [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)、[billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live) 以及 [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer) 做出的贡献。本修改版参考自上述仓库项目（其中「支持 FCM 应用」检测思路参考 FCMPushViewer）。

## 运行条件

| 项目 | 要求 |
|------|------|
| 系统 | HyperOS 4 |
| 框架 | LSPosed |
| 依赖 | 谷歌基础服务 |
| 作用域 | 系统框架 + 电量和性能 |

## 功能概览

- 解除HyperOS 4对谷歌推送**连接相关广播**的限制
- **FCM 唤醒白名单**：控制哪些应用可被推送唤醒
- **长按卡片多选**：支持批量操作
- **展示 FCM 支持应用**：按 Manifest 中的 FCM 类 Receiver 过滤列表
- 快捷打开 FCM 诊断界面
- 隐藏桌面图标（可从 LSPosed 进入模块设置）

## 效果与限制

- 只针对「谷歌推送连接 / 唤醒」相关限制，**不是**通用后台保活工具
- 启用后系统耗电可能增加
- 「应用支持 FCM」依据应用 Manifest 中的 Receiver 判断，并**不保证**在每台设备上一定推达；仍受后台策略、应用自身逻辑影响

## 安装与设置

1. 安装本模块，在 LSPosed 中启用并勾选作用域
2. 重启设备（若 LSPosed 管理器版本支持 API 102， 会自动热重载，无需重启）
3. 打开模块设置，勾选需要 FCM 唤醒的应用
4. 若隐藏了桌面图标，则从 LSPosed 进入模块设置

## 更新记录

更新记录见仓库内 [`CHANGELOG.md`](CHANGELOG.md)。

## 致谢与许可

| 项目 | 说明 |
|------|------|
| [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live) | 原始工作 |
| [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live) | 相关改进 |
| [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer) | FCM 应用检测思路参考 |

本项目基于 **GPL-3.0** 发布
