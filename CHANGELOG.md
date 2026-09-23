# Changelog

## 2.0.0 (versionCode 19)

- 新增**应用内语言切换**（简体中文 / English）
- 新增**主题**切换，包括主题模式、动态颜色、调色风格和颜色规格
- 新增**备份与恢复**
- 新增**帮助页**
- 优化下拉菜单动效
- 修复勾选应用后需手动刷新才生效的问题
- 修复未授权时过早提示无FCM应用的问题
- 加固稳定性与安全性：钩子异常隔离、清理器判定防崩溃、白名单广播改后台线程

## 1.8.0 (versionCode 18)

- 修复 HyperOS 3 上误报的报错
- 修复 HyperOS 3 上 `googleNetworkDisconnect` 从未生效的问题

## 1.7.0 (versionCode 17)

- 新增关于页，完善开放源代码许可清单
- 启动时自动检查更新（每 24 小时一次，亦可手动检查）
- 尝试兼容 HyperOS 3「电量和性能」Hook 策略，参考 [zuohl/HyperOS_FCM_Live](https://github.com/zuohl/HyperOS_FCM_Live)
- 完善应用列表权限获取，参考 [250king/HyperOS_FCM_Live#1](https://github.com/250king/HyperOS_FCM_Live/pull/1)
- 优化更多选项菜单配色
- 桌面图标更名为「FCM 唤醒名单」
- 未找到支持 FCM 的应用时显示 Toast
- 改进项目代码质量

## 1.6.0 (versionCode 16)

- 长按应用卡片进入多选，顶栏批量加入/移出白名单，支持全选/取消全选
- 更多选项新增「展示支持应用」（参考 FCMPushViewer 的 Receiver 检测）；首次启动默认开启，用户更改后持久化
- 长按图标 Tooltip 自定义定位，避免遮挡控件
- 修复多选点击时整屏涟漪异常
- 接入 HyperOS `GET_INSTALLED_APPS` 运行时权限申请（思路参考 [250king/HyperOS_FCM_Live#1](https://github.com/250king/HyperOS_FCM_Live/pull/1)），避免应用列表被过滤得不全

## 1.5.1 (versionCode 15)

###紧急修复，建议更新至本版本
-修复'1.5.0.14'无法隐藏桌面图标的问题
-界面深浅取色跟随系统
