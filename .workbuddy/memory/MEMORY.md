# HyperFCMLive 项目长期备忘

## 构建环境（本机）
- **必须先设 `JAVA_HOME`**：`C:\Users\视窗\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`（PATH 里没有 java）。
- Gradle 需写 `~/.gradle/caches/journal-1`，**沙箱内会「拒绝访问」**，构建命令要放行沙箱。
- 常用命令（离线可用）：
  - `./gradlew --offline :HyperFCMLive:assembleRelease :HyperFCMLive:assembleDebug`
  - `./gradlew :HyperFCMLive:dependencies --configuration releaseRuntimeClasspath --offline`
- 判定死代码的权威证据：`HyperFCMLive/build/outputs/mapping/release/usage.txt`（R8 移除清单）。

## 模块结构
- `:HyperFCMLive`（Android 应用 + `Hooker` Xposed 模块，入口见 `META-INF/xposed/java_init.list`）。
- `:hiddenapi:stubs`：**纯 `java-library`，没有 android.jar 依赖**，因此模块内被引用的 Android 类必须自包含
  （例：`PowerExemptionManager` → `android.content.Context` → `android.content.pm.ApplicationInfo`；
  `androidx.annotation.RequiresApi` 也是模块内自带，不能删）。改这里前务必核对模块内 import 链。
- `:hiddenapi:bridge` 曾是未使用的上游遗留，已删除（不要恢复）。

## 依赖与许可证约定
- 构建依赖只看两处：`HyperFCMLive/build.gradle`（compileOnly/implementation）+ Gradle 依赖树。
- 许可证页面 `LicensesActivity.DEPS` = {名称, 版本("" 表示无), 许可证, 项目 URL}，按字母序排列；
  `REFERENCES` = {名称, 许可证, URL}。依赖列表须与 Gradle 解析结果保持一致。
- `res/raw/license_apache2.txt` / `license_gpl3.txt` 是 Licenses 分区展示的全文；新增许可证需同步补 raw 文本。
- 本项目 GPL-3.0；APK 不进任何 hiddenapi/OpenJDK stub 字节码（compileOnly）。

## 约定
- `shrinkResources false`（见模块 build.gradle），所以 `res/raw/keep.xml` 的 tools:keep/discard 实际不生效。
- `proguard-rules.pro` 里 `-keep class io.github.howard20181.hyperos.fcmlive.** { *; }` 会让 R8 **无法精简应用自身代码**；
  如需进一步瘦身需收窄此规则（有反射/资源引用风险，需逐项验证）。
- 版本号：`versionCode` 被刻意固定（避免 LSPosed 把本包判为旧版），勿改回 jgit 提交数派生。
