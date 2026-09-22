# 修复澎湃系统谷歌推送重连

去除澎湃系统对谷歌推送连接相关广播的限制。启用模块后，系统耗电可能会增加。

感谢Howard20181的https://github.com/Howard20181/HyperOS_FCM_Live 、Bill Xi的https://github.com/billtv/HyperOS_FCM_Live 以及HappyMax0的https://github.com/HappyMax0/FCMPushViewer 做出的贡献，本修改版参考自上述仓库项目

`GET_INSTALLED_APPS` 运行时权限申请思路参考自 250king 的 [PR #1](https://github.com/250king/HyperOS_FCM_Live/pull/1)（HyperOS 应用列表权限策略）。

本项目基于 GPL-3.0
