# 修复澎湃系统谷歌推送重连

去除澎湃系统对谷歌推送连接相关广播的限制。启用模块后，系统耗电可能会增加。

感谢Howard20181的https://github.com/Howard20181/HyperOS_FCM_Live 、Bill Xi的https://github.com/billtv/HyperOS_FCM_Live 以及HappyMax0的https://github.com/HappyMax0/FCMPushViewer 做出的贡献，本修改版参考自上述仓库项目

`GET_INSTALLED_APPS` 运行时权限申请思路参考自 250king 的 [PR #1](https://github.com/250king/HyperOS_FCM_Live/pull/1)（HyperOS 应用列表权限策略）。

## 兼容性说明

两种 PowerKeeper 设计均已适配，模块会在运行时按方法是否存在自动选择，无需手动切换：

- **HyperOS 3** —— 沿用旧设计（`initGmsChain` / `updateGmsAlarm` / `updateGmsNetWork` / `updateGoogleReletivesWakelock` 等）
- **HyperOS 4** —— 新设计（`updateFrameworkGmsNetStatus`）

两者 `versionName` 同为 4.2.00，但实际实现完全不同，因此模块不做版本号判断，只按方法存在性探测。

> 已验证机型：Xiaomi 17 Pro Max（popsicle），OS3.0.319.0.WPBCNXM / Android 16。

本项目基于 GPL-3.0
