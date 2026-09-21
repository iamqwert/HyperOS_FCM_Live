# 修复澎湃系统谷歌推送重连

去除澎湃系统对谷歌推送连接相关广播的限制。启用模块后，系统耗电可能会增加。

感谢 [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)、[billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live) 以及 [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer) 做出的贡献。本修改版参考自上述仓库项目（其中「支持 FCM 应用」检测思路参考 FCMPushViewer）。

本项目基于 **GPL-3.0**。

## 运行条件

| 项目 | 要求 |
|------|------|
| 系统 | HyperOS（澎湃） |
| 框架 | LSPosed（或兼容的 Xposed 实现） |
| 依赖 | 已安装 Google Play 服务（GMS） |
| 作用域 | 一般勾选系统框架（`system_server`） |

## 功能概览

- 解除系统对谷歌推送**连接相关广播**的限制，便于 FCM 重连与投递
- **FCM 唤醒白名单**：控制哪些应用可被推送唤醒 / 自动拉起
- **长按卡片多选**：顶栏批量「加入 / 移出」白名单，支持全选、取消全选
- **展示支持应用**：按 Manifest 中的 FCM 类 Receiver 过滤列表（首次启动默认开启，可手动改并记住）
- 长按图标显示 Tooltip（自定义定位，避免遮挡图标）
- FAB 可打开 GMS 的 FCM 诊断界面
- 支持隐藏桌面图标（可从 LSPosed 进入模块设置）

## 白名单说明

- **列表为空**：偏宽松，FCM 相关唤醒大致放行（兼容旧版行为）
- **一旦勾选任意应用**：变为白名单模式，**仅勾中的应用**会被 FCM 拉起
- **Google Play 服务**由模块单独处理连接与网络限制，**不必**在列表中勾选
- 需要接收推送的应用请自行勾选；可用「展示支持应用」缩小范围

### 多选操作

1. **长按**任意应用卡片进入多选
2. 顶栏左侧为返回；标题显示「已选择 N 项」
3. 右侧图标：**加入白名单** · **移出白名单** · **全选 / 取消全选**（同一按钮切换图标）
4. 点批量按钮写入白名单后退出多选

## 效果与限制

- 只针对「谷歌推送连接 / 唤醒」相关限制，**不是**通用后台保活工具
- 启用后系统耗电可能增加
- 「支持 FCM」依据应用 Manifest 中的 Receiver 判断，**不保证**在每台设备上一定推达；仍受后台策略、应用自身逻辑影响

## 安装与设置

1. 安装本模块 APK，在 LSPosed 中启用并勾选作用域（系统框架）
2. 重启设备（或按 LSPosed 提示重载）
3. 打开模块设置，在列表中勾选需要 FCM 唤醒的应用
4. 若隐藏了桌面图标：LSPosed → 模块 → 本模块 → 启动 / 设置

## 反馈问题

提交 Issue 时请尽量提供：

- 机型与 HyperOS 版本
- 模块版本（当前：**1.6.0** / versionCode **16**）
- LSPosed 版本
- 现象与复现步骤

更新记录见仓库内 [`CHANGELOG.md`](CHANGELOG.md)。

## 致谢

| 项目 | 说明 |
|------|------|
| [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live) | 原始工作 |
| [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live) | 相关改进 |
| [HappyMax0/FCMPushViewer](https://github.com/HappyMax0/FCMPushViewer) | FCM 应用检测思路参考 |

本项目基于 **GPL-3.0** 发布。
