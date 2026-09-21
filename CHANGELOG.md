# Changelog

## 1.5.0-beta2 (versionCode 13)

### 适配 HyperOS 4 · PowerKeeper 4.2
- PowerKeeper 4.x 移除了 `NetdExecutor#initGmsChain` 与旧版 `GmsObserver#updateGmsAlarm` / `updateGmsNetWork` / `updateGoogleReletivesWakelock`，导致 hook 整段失败，FCM 仍可能需自启动才能收消息。
- 改为 hook 新 API：
  - `NetdExecutor#setGmsDnsBlockerState`（禁止 GMS DNS 拦截）
  - `NetdExecutor#execute`（`setuiddnsrule` 强制 allow；不启用 standby 防火墙链）
  - `GmsObserver#updateFrameworkGmsNetStatus`（禁止下发 GMS 网络限制）
  - `GmsObserver#onGoogleReachabilityChanged` / `GmsObserver$2#googleNetworkDisconnect`（按「Google 可达」处理）
- 旧方法名仍尝试 hook，找不到则跳过；每个 hook 独立 try/catch，避免单点失败拖垮整段。
- system_server：增加 `GreezeManagerService#updateGmsNetStatus` 防御性 hook。
- Doze 白名单：同时 hook `getDozeWhiteListApps` 的 `Bundle` 与 `Context` 重载。

### 发布与版本
- `versionCode` 固定为 13，避免 LSPosed 将本包识别为旧版而提示更新到未修改的上游包。
- 正式 GitHub Release（不再使用 nightly 标签）；同时提供 release 与 debug APK。
- Release APK：R8 混淆；配置 GitHub Secrets 后使用发布签名。

### 安装注意
- 作用域：`system` + `com.miui.powerkeeper`
- 安装或更新后请重启手机
- 签名变更时先卸载旧模块再安装，并在 LSPosed 中重新启用
