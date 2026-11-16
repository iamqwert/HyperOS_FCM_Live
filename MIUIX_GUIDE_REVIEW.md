# Miuix 四篇文档对照审查（2026-11-04）

对照对象：

- 模糊 `/guide/blur`
- 导航 `/guide/miuix-nav`
- 工具 `/guide/utils`
- 文本样式 `/guide/textstyles`
- 颜色 `/guide/colors`

本文件是**给人看的工作记录**，不是仓库文档（与 `OTA_CHECKLIST.md` 同级，不入库）。

---

## 一、这轮已改（可以直接编译验收，无需目视）

### 1. 折射链路的 dp / px 单位（`ui/softglass/SoftGlassModifier.kt`）

`SoftGlassSpec.blurRadius` 一直是 **dp**（`textureBlur` 的读取口径，`BlurDefaults.MaxBlurRadius = 150f` 也是 dp 上限）。但折射链路把它原样喂给了 `BlurEffect(radiusX = …)`，那个参数是**像素**。结果同一条玻璃：

- 走着色器光路：23dp 被当成 23px ≈ 7~8dp 的模糊；
- 掉到 `textureBlur` fallback：23dp 真的 23dp。

现在 `effects` 块里先 `.dp.toPx()` 再交给库的 `blur()`，两条路径终于在同一个物理强度上。

**这一项会改变观感**：光路的玻璃明显更糊（约 density 倍）。原来偏锐可能正是你看着调出来的，所以如果打开发现柔光玻璃"糊过头"，把 `SoftGlassFineTuning.Default` 的 `glassBlurRadiusMultiplier`（0.25）往下调，或把 `SoftGlassSpec.blurRadius` 的基准降低，不用回退代码。

### 2. 搜索结果的越界回弹（`ui/MiuixSearchPager.kt`）

`overScrollVertical()` 与主题自带的 `MiuixOverscrollFactory` 同时生效 = 一次拖拽两套弹簧。按文档要求补了 `overscrollEffect = null`（保留 modifier，因为搜索结果常常不足一屏，而 factory 在内容不溢出时**不会**触发）。

同时补上 `.listScrollEndHaptic()` —— 全项目其它 Miuix 列表都有，只有这张漏了。

### 3. 组玻璃只留一次模糊（`ui/ScreenParts.kt`，上一轮 P0）

`SettingsGroup` 提供 `LocalGroupGlassPlane`，`MiuixCardSurface` 读到就不再自己 `textureBlur`。关于页 5~6 次 60dp 全分辨率高斯 → 1 次。

### 4. 卡片边缘高光（第三节 #3）——已撤销

试过 `Highlight.GlassStrokeBig*`（`SettingsGroup` 的平面 + 单独的 `MiuixCardSurface`），**关于页观感错乱，已整块回退**。原因推测：整组平面是 `matchParentSize` 的独立层，bloom 描边沿它的外轮廓走，与行内容/相邻卡片对不齐。

结论：这条保持原样（纯磨砂）。要再试只能按单卡描边（`drawWithContent` + `outline`），不要走 backdrop 层的 highlight。

### 5. 一屏装得下的页面补回弹（第三节 #4）

`ui/ScreenParts.kt` 新增成对的两个口子：`Modifier.pageOverscroll()` + `rememberPageOverscrollEffect()`，Miuix 系走库的 `overScrollVertical()` 且 `overscrollEffect = null`，Material 侧保持平台 stretch。已挂到**模块页**与**实验页**——这两页的常态就是内容不满一屏，主题自带的 `MiuixOverscrollFactory` 等不到边界，拉不动。

其它页没动：内容必然溢出，factory 本来就会响。

### 6. 转场下层压暗 0.5 → 0.32（第三节 #5）

`MainActivity.AppPages` 的 `NavDisplayEffects.dimAmount`。与文档示例一致，是纯观感调整。

### 7. 「预测性返回」开关（设置页 · 显示区，全观感）

默认**开启**，只关一件事：系统的返回手势还要不要**跟手预览**。

库里 `PredictiveBackHandlerWithSessions(enabled = backStack.size > 1)` 是写死的，没有开关参数。改的是**谁先拿到手势**——navigationevent 的仲裁是「最后 compose 的启用 handler 优先」，所以在 `NavDisplay` 之后挂一个普通 `NavigationBackHandler`：关闭时它站最上面，库收不到 started/progressed ⇒ 画面不跟手，松手 pop / 退出，转场照常。

根层也一并接管——不接管就会出现「关了开关、在主页面按返回仍在跟手」（那里跟手的是系统退到桌面的预览）。

> 🔴 与本应用的「中间区域横向拖拽返回」**无关**：那个手势 4.0.0 已取消，本应用没有 `swipeDismiss`。第一版误把它当成开关的对象，已撤。

---

## 二、逐篇核对：没问题的地方（附证据，免得再查一遍）

### 导航

| 文档要求 | 现状 |
|---|---|
| `rememberNavBackStack<Route>(…)` 显式写超类型 | ✅ `MainActivity.kt:440` |
| key 必须 `@Serializable` 且是 `data object` / `data class`（toString 由值派生） | ✅ `nav/Route.kt`，四个 `data object`，注释写明进程死亡后的 contentKey 稳定性 |
| push 要幂等（重复 key 会被 runtime 拒绝） | ✅ `Navigator.push` 里 `if (route !in backStack)` |
| 滑动关闭默认 `None`，不静默开启 | ✅ 没有 `swipeDismiss` 参数，全部走返回键/手势 pop |
| 返回手势在根层应交给系统（否则退不出应用） | ✅ 库内 `PredictiveBackHandlerWithSessions(enabled = backStack.size > 1)`，根层自动放行 |
| `NavDisplayEffects` | ✅ `cornerClipRadius = rememberNavSystemCornerRadius()`，`cornerClipMode` 保持默认 `Leading`（滑动类），`backdropColor` 取当观感背景色 |
| 多窗格 / 嵌套 display | 未使用，无需处理 |

**唯一冗余**：`transition = NavTransitions.MiuixDefault` 就是默认值，写出来是"说明"不是"覆盖"，保留。

### utils

- `scrollEndHaptic`：统一封装成 `listScrollEndHaptic()`，Miuix 系才挂 —— ✅
- `springAnimateToPage` + `PagerNavigationSpringSpec`：✅ 记录页与首页都走库的弹簧，没有自己写 tween
- `pressable` / `PressFeedbackType`：只用组件参数（`pressFeedbackType = Tilt`），没有手工 `pressable()`，也就没有 `interactionSource` 惰性创建的坑 —— ✅
- `pagerGestureOverride`：**没有使用**。记录页的 pager 是 `userScrollEnabled = false` + 点击/程序化切页，横向手势归首页那条；文档里"必须同时配 `pageNestedScrollConnection`"的约束只在用了这个 modifier 时成立 —— ✅
- `DialogLayout` / `PopupLayout`：没有直接用，全部走 `OverlayDialog` / `OverlayListPopup` / `OverlayDropdownPreference`，宿主是 `MiuixScaffold`（`PageScaffold`、`HomeShell`、`MainScreen` 三处）—— ✅

### textstyles

14 种样式全部是"只取 `fontSize`"，颜色/字重各自按场景给，这与文档"统一走 `MiuixTheme.textStyles`"的方向一致（项目里 9 处 `MiuixTheme.textStyles.X.fontSize`）。

刻意偏离的有三簇，都是设计值而非疏忽：

- 关于页 hero：35sp Bold（参照应用的原尺寸）
- 模块状态页数字簇：22sp / 16sp / 15sp SemiBold
- 浮动底栏标签：10sp SemiBold/Medium

### colors

桥接表 `Colors.toMaterialColorScheme` 逐槽核对无误：页面色 = Miuix `surface`，卡片 = `surfaceVariant`，弹层 = `surfaceContainer( Highest)`，`onSurfaceVariant` ← `onSurfaceVariantSummary`（60%），`outlineVariant` ← `dividerLine`，`scrim` ← `windowDimming`。这与 Miuix 自己的作用分工一致，不是"背景用错槽"。

未直接使用的槽位：`secondary*`、`primaryVariant`、`disabledPrimary*`、`onBackgroundVariant`、`onSurfaceSecondary`、`disabledOnSurface`、`sliderKeyPoint*`。属于"这套界面没有对应语义"，不是缺口（例：禁用行在 Miuix 系下根本不出现，所以用不到 `disabledOnSurface`）。

---

## 三、需要你过目的（我没动）

| # | 事项 | 现状 | 建议 | 代价 |
|---|---|---|---|---|
| 1 | **光路模糊变强了**（见上） | dp→px 后玻璃比原来糊约 3 倍 | 若糊过头，调 `glassBlurRadiusMultiplier` | 一个常数，改完需目视 |
| 2 | 非标字号 | 10sp（底栏标签）、15sp（推送行应用名）、22sp（状态页数字）没有对应样式 | 10→`footnote2`(11sp)、15→`body1`(16sp)？22 无解 | 会改变观感与行高，需目视 |
| 3 | ~~卡片没有边缘高光~~ | **试过，观感错乱已回退**，见第一节 #4 | 要再试只能单卡描边，别走 backdrop 层 | — |
| 4 | ~~一屏装得下的页面没有回弹~~ | **已改**，见第一节 #5（模块页 + 实验页） | 其它页若也发现不满一屏，照同一对 API 挂 | 手感变化，需目视 |
| 5 | ~~`dimAmount` 保持默认 0.5~~ | **已改为 0.32**，见第一节 #6 | — | — |
| 6 | 标题条两套引擎（P2） | plain Miuix 走 Haze，柔光玻璃走 miuix `progressiveTextureBlur`，参数两处维护 | 收敛到一套 | 改动面广，你说过不动 |
| 7 | 覆盖型目的地（bottom sheet / dialog 作为路由） | miuix-nav v1 明确不支持 | 将来要的话走 `OverlayBottomSheet`，别塞进返回栈 | 设计决定 |

---

## 四、四篇文档中和这个项目相关、值得记住的点

- **模糊**：`textureBlur` 的 `blurRadius` 上限 150dp；`progressiveTextureBlur` 的 `noiseCoefficient` 默认 0（噪点叠渐变更显眼）；`Highlight` 不支持时静默跳过；`layerBackdrop` 不捕获父级背景，透明区会被糊成色块（本项目已在 `rememberPageLayerBackdrop` 里先 `drawRect(surface)` 堵住）。
- **导航**：`rememberNavBackStack` 不写超类型 → 存其它子类型时序列化失败；contentKey 用 `toString()` 做槽位，非 data 类的默认恒等串在进程死亡后会重置状态；v1 没有结果回传通道（要自己挂 requestKey 总线）。
- **utils**：`overScrollVertical()` 必须写在 `nestedScroll` **之前**（同一节点上）；`MiuixOverscrollFactory` 内容不足一屏不触发；`pagerGestureOverride` 的 `CrossAxisInterceptor` 模式必须同时 `userScrollEnabled=false` + `pageNestedScrollConnection`。
- **textstyles**：只有 `paragraph` 有行高（1.2em），只有 `subtitle` 是 Bold；样式不带颜色，颜色统一来自 `onBackground`。
- **colors**：文本层级靠透明度分层（`onSurfaceSecondary` 80% → `onSurfaceVariantSummary` 60% → `onSurfaceVariantActions` 40%）；`windowDimming` 专给弹层遮罩。

---

## 五、2026-11-09：柔光玻璃底栏改接 MIUIX Glass 风格 + 过时项盘点

对照对象：`C:/Dev/miuix-main` 的 `miuix-glass`（`GlassNavigationBar.kt`、`GlassMotion.kt`、`GlassMaterials.kt`、`GlassStrokes`、`GlassShadows`、`GlassDefaults`）、`miuix-ui` 的 `TabRow.kt`/`NavigationBar.kt`/`TextStyles.kt`/`SmallTitle.kt`、`docs/zh_CN/guide/glass.md`，以及 `example/shared/AppContent.kt` 里三种悬浮底栏的写法；`miuix-glass` 在 Maven Central **没有 0.9.4 制品**（`miuix-glass-android/0.9.4/*.pom` → 404，`miuix-ui-android` → 200），所以只能抄规范，不能加依赖。

### 这轮已改

**1. 新增 `ui/softglass/SoftGlassNavigationBar.kt`，按 `GlassNavigationBar` 逐条移植**

| 维度 | 库（miuix-glass） | 本项目实现 |
|---|---|---|
| 高度 / 图标 / 字号 | 54dp、**25dp**、11dp→sp（字倍 ≥1.6 用 16dp） | 同值（2026-11-10 随 HEAD 把图标 28dp 改为 25dp），字号走 `textStyles.footnote2.copy(fontSize = …)` |
| 内容内边距 | 竖 6dp、图标与标签 0dp、横向 8dp | 同值 |
| 指示器 chip | 竖向缩 3dp、横向超出每格 5dp | 同值 |
| 指示器运动 | 左右两条 `Animatable`：leading `folmeSpring(0.7, 0.4)`、trailing `(0.75, 0.5)`；同格拖拽换 `(1, 0.15)` 跟手；跨格才走边缘弹簧 | 同值，直接用库已发布的 `top.yukonga.miuix.kmp.anim.folmeSpring`（0.9.4 的 `MiuixEasingKt` 在 classes.jar 里），没有自己换算成 stiffness |
| 拖拽 | 任意位置按下即选中；过 touch slop 才拖 chip；同格内 4× 位移（≤60px）拖尾；两端渲染裁剪但弹簧不停 | 同值（`navigationDragTarget` / `navigationIndicatorBounds` 两个纯函数照抄） |
| 按下反馈 | chip 缩到 max((48-10)/48, 0.9) = 0.9；目标内容 alpha 0.6；chip 颜色 `navPressEnter(1,0.2)` / `navPressExit(0.95,0.35)` | 同值（`glassPressScale` 私有实现） |
| 文字/图标换色 | `navContent()` = tween 150ms + (0.33,0,0.67,1) | 同值 |
| 选中色 | 动态取色 → primary，否则 onBackground；未选中不加暗化 | 同值（`MiuixTheme.isDynamicColor` 判断，对应本项目的「莫奈颜色」） |
| 底边距 | `defaultWindowInsetsPadding`：无 inset 32dp，有 inset 16dp + inset，再叠 caption bar（300ms tween） | 同值，且抽成 `softGlassNavigationBarBottomMargin(inset)` 供页面算让位 |
| 材质 | `GlassMaterials.puredThinGlass`（20dp 模糊 + 三层颜色） | `SoftGlassRecipes.floatingNavigation`（92 × 0.217 ≈ 20dp，含折射/边缘光）——已对齐；记录页两条页签条单独用 92 × 0.8（`MiuixCapsuleTabRowDefaults.BlurRadiusMultiplier`） |
| 描边 / 阴影 | `GlassStrokes.MiddleLight/Dark`（bloom）、`GlassShadows.Float`（0.05 alpha / 24） | 本项目的 edgeOptics 高光 + `softGlassShadow` 三层 dropShadow |

刻意没搬：`visible` 显隐编排（缩放 0.6 + 18dp 模糊 + 100ms 延迟）、`trailingAction`、`alpha` —— 这个底栏常驻、旁边没有独立操作。

周边改动：
- `HomeShell` 的柔光玻璃分支改用新条，两侧 24dp（库文档与官方示例的口径，旧的是 16dp），底边距交给条自己；页面让位重算为 `54 + bottomMargin + 16`，与旧值 `54 + 12 + 4 + inset + 16` 完全相等 ⇒ 列表底部空档不变。
- 删除 Deadliner 三件套 `MiuixFloatingTabBar.kt` / `FloatingTabMotion.kt` / `FloatingNavigationShadow.kt`（备份在 `.workbuddy/backup/2026-11-09-softglass-floating-bar/`）。
- 顺带解决了第三节 #2 里「浮动底栏标签 10sp」那条：现在 11sp，且有对应样式。

**2. 主页「钩子装载自检」整条删除**：入口、`Route.SelfCheck`、`SelfCheckScreen.kt`、`selfcheck_*` 字符串（中英各 10 条）。`ic_checklist` 保留（`MainScreen` 还在用）。

### 过时项：柔光玻璃

| # | 项 | 现状 | MIUIX 最新 | 结论 |
|---|---|---|---|---|
| 1 | 悬浮底栏 | Deadliner 条形：10sp 标签、26dp 图标、只有按在 chip 内才能拖、整块面板随拖拽位移 | `GlassNavigationBar` | **已改**（见上） |
| 2 | 弹层 / 页签条 / 顶栏 | 全部手写 `SoftGlass*` | `miuix-glass` 有 `GlassDialog` / `GlassBottomSheet` / `GlassTabRow` / `GlassTopAppBar` / `GlassIconButton` / `GlassPopup` 系列 | 未发布，只能抄规范；一旦 0.9.x 发布可整块替换（`minSdk 33` 我们是 35，不挡） |
| 3 | 玻璃配方表 | 5 条 recipe 里 4 条没人调用：`BottomSheet` / `Dialog` / `quickAdd` / `tabletNavigation`，`immersiveDialogAdvancedHighlight` 只有定义 | — | 死代码，建议删；这轮没动（怕和工作区里未提交的改动撞） |
| 4 | `SoftGlassSpec.blurRadiusX/Y` | 没有任何调用方赋过值 ⇒ `usesIndependentBlurRadii` 恒 false，对应分支全死 | — | 删要动 `softGlassMaterial` 的 render 链，未动 |
| 5 | 描边 | refraction 里出高光 + 一条渐变 `border` | `GlassStrokes`：0.8dp 平带 + 1.2dp 斜切 + 两盏方向灯 | 等价不同源；换真 bloom 需 miuix-glass |
| 6 | 模糊强度 | 底栏 92 × 0.217 ≈ 20dp；记录页页签条 92 × 0.8 = 73.6dp | `PuredThinGlass` 20dp | 底栏对齐规范；页签条按目视单独加强（2026-11-10） |

### 过时项：MIUIX（纯 Miuix 风格，**一律未动**——按 AGENTS.md「处理某一风格时不得连着改其他风格」）

| # | 项 | 现状 | 最新规范 | 影响 |
|---|---|---|---|---|
| 1 | 页签条 | 自定义 `MiuixCapsuleTabRow`：胶囊形、3dp 内边距、按标签实测宽度、选中 Medium | `TabRow`（42dp / 12dp 圆角 / 9dp 间距 / 76–98dp 夹取）与新增的 `TabRowWithContour`（45dp / 8dp 圆角 / 5dp 外圈），选中 **Bold**；两者 0.9.4 都已发布 | 可整块替换；观感会从胶囊变回系统页签 |
| 2 | 悬浮胶囊 item | 手写 `PillTabItem`：22dp 图标、11sp、未选中 0.6、48dp 板 | `FloatingNavigationBarItem` **仍然只画图标**（`label` 只进 contentDescription，源码确认）⇒ 手写仍必要；但库值是 28dp 图标、未选中 alpha 0.4 | 纯数值，改完观感会变 |
| 3 | 悬浮胶囊阴影 | 库默认 `shadowElevation = 1dp` ⇒ 0.2 alpha / 10dp 阴影压在透明玻璃下 | Glass 风格对应物是 `GlassShadows.Float`（0.05 alpha / 24dp / 无偏移） | 我们的玻璃半透明，这层偏重 |
| 4 | 停靠底栏 | `MiuixNavigationBar` 默认 `showDivider = true`；item 64dp / 26dp / 12sp | 一致；新增的 `mode = NavigationBarDisplayMode` 默认 `IconAndText` 正是我们要的 | 无需动 |
| 5 | 小节标题 | `SmallTitle` + 自给 insideMargin(16/20/8/8) | 库默认 `InsideMargin(28, 8)` | 左侧 12+16=28 已对齐；右侧 8 与库不同但无视觉差 |
| 6 | 连续曲率 | 手写 `MiuixGroupShape`（handle 1-0.643、角块 1.1r） | `SquircleDefaults.Extension = 1.1f`、`SQUIRCLE_CONTROL = 0.643f` | ✅ 数值本就对齐；库里只给 modifier 不给 `Shape`，手写仍必要 |
| 7 | 搜索 | `InputField` 自理 + 自写取消键 + 结果 pager | 库有 `SearchBar`（`inputField` + 展开/收起 + 建议区） | 语义接近但结构不同，换要重排整条搜索链，未动 |
| 8 | 没采用的新组件 | `BreadcrumbBar`、`Tooltip`、`FloatingToolbar`、`ColorPicker`、`NumberPicker`、`NavigationRail`、`NavigationBarItem.badge` | — | 界面没有对应语义，不为用而用 |

**废弃 API**：0.9.4 里 `@Deprecated` 只有 `SpinnerItemImpl` / `SpinnerDefaults` / `SpinnerColors` / `SpinnerEntry` 四个别名，本项目一个都没用。

### 验证边界

- `./gradlew --no-daemon -PallowDebugSignedRelease :HyperFCMLive:assembleRelease` 通过；APK 已 `install -r` 到真机。
- 观感、拖拽手感、按下缩放**未做**真机目视（按约定不主动做 UI 测试）。可疑点：24dp 侧边比原来的 16dp 窄、chip 选中色走 `onBackground`（与页签条的 `onSurfaceContainer` 不同源，这是库自己的分工）。

---

## 六、2026-11-09（下半）：纯 MIUIX 顶栏换 GlassTopAppBar（progressive blur 关）+ 死代码清理

> 这一节的顶栏分工（应用页/记录页留在 Haze 页窗）已被第七节取代：现在是两风格共用一套引擎、两页都走 MIUIX 默认 bar。其余内容仍有效。

### 1. 顶栏：`ui/MiuixGlassTopAppBar.kt`

对照 `miuix-glass/GlassTopAppBar.kt`。库的 `progressiveBlur` 默认 false，那一支不模糊，画的是**渐变带**：

| 维度 | 库 | 本项目 |
|---|---|---|
| 背景 | `bandBrush(surface)`：0→0.45 实色，0.58/0.72/0.86 依次 0.82/0.58/0.34，到 1.0 透明；`scaleY = (h + BandOverhang 28dp) / h`，`transformOrigin` 顶边 | 同值 |
| 出现时机 | `isContentScrolled` → `updateTransition` → band `tween(100, Linear)`，控件材质 `tween(350, Linear)` | 同值（页面项用 `barScrollProgress`，其余用 `collapsedFraction`） |
| 控件 | 44dp 玻璃药丸，`HorizontalPadding = 12dp`（与卡片同轴）；无 backdrop 时用 `buttonFill()`（浅色白 / 深色 #2C2C2C） | 同值，阴影/描边按库的 `GlassShadows.Regular` + `SmallLight/Dark` 近似 |
| 按下 | 内容 alpha 0.6 | 同值 |
| 大标题 | `BlurTopAppBar` 折叠时标题虚化 12dp | **未搬**：0.9.4 没有 `BlurTopAppBar`，`TopAppBar` 也无 `largeTitleBlurRadius`/`titleAlpha`（jar 内只有 `TopAppBar`/`SmallTopAppBar`），标题仍按原样折叠 |
| 玻璃本体 | `glassPanel` + `PuredThinGlass` | 无 backdrop 可用，走库自己的 fallback 纯色药丸 |

接线：`PageScaffold` 里 `bandBar = !softGlassChrome() && barExtension == null`。
- **应用页**（`MainScreen` 自建 scaffold，带固定搜索胶囊）与**记录页**（`barExtension` 两排页签）保持原来的 Haze 页窗；
- 柔光玻璃整体不动（仍走 progressive 页窗 + Haze）；
- 其余（主页、设置、关于、许可、实验）换渐变带。关于页的 compact bar 保留：标题 alpha 仍按 hero 行程算。

顺带删掉的东西：`PageScaffold` 里 `val pane = true` 及其三处死分支、`HazeGlassBar` 恒为 1 的 `frost` 参数。

### 2. 死代码 / 死资源

资源侧：**没有**。74 个 drawable、3 个 xml、8 个 raw、389 条字符串全部有引用（脚本按 `R.<type>.<name>` / `@<type>/<name>` 双向核对）。

Kotlin 侧删掉的：

| 位置 | 删了什么 | 依据 |
|---|---|---|
| `ui/softglass/SoftGlassRecipe.kt` | `BottomSheet` / `Dialog` / `quickAdd` / `tabletNavigation` / `immersiveDialogAdvancedHighlight` 五条 recipe、`QuickAddCornerRadius`、`GlassGeometrySpec`（唯一会设它的两条 recipe 已删，胶囊半径本就取渲染高度的一半） | 全 0 引用 |
| 同上 | `GlassTintStyle`（Neutral/Chromatic/SeedChromatic）、`resolveGlassTintComposite`、`blendGlassTintComposites`、`tint`/`tintStyle` 参数、`GlassMaterialInteraction.neutralTintProgress` | 没有任何调用方传过非 Neutral |
| `SoftGlassMaterial.kt` / `SoftGlassModifier.kt` | `blurRadiusX/Y`、`resolvedBlurRadiusX/Y`、`usesIndependentBlurRadii`、`softGlassTextureBlur` 的双半径分支、`blurRadiusOverride`/`tintAlphaOverride` | 没有任何调用方赋过值 |
| `SoftGlassSurface.kt` | `SoftGlassIconButton` 的 `enabled`/`size`/`tint`/`tintStyle`/`neutralTintProgress`，以及 `SoftGlassSurface` 的 `tint`/`tintStyle` | 两个真实调用点一个都没传 |
| `SoftGlassTuning.kt` | `topBarTintAlpha`（只写不读：`SoftGlassSpec` 里存了，但除 `navigationTintAlpha` 外没有任何读取点） | grep |
| `AllowlistGate.kt` | `postDelayedToAllowlistHandler` | 0 引用，且注释里写的调用方（autostart 回填）早已不存在 |

**刻意保留的"看起来像死代码"**：
- `mcu/` 里 `fromArgb`、`enableLightForeground`、`argbFromXyz`、`CAM16RGB_TO_XYZ`、`flRoot`、`sourceColorArgb` —— Material Color Utilities 的移植件，算法内部的备用函数，不跟着删（删了等于给 vendored 代码开刀，收益为零）。
- `GcmDumpParser.parseHeartbeatAlarmEarly` / `parseFailedConnectHosts` —— `EVENT_DISPLAY_MAP.md` 与 `FCM_DIAGNOSTICS_FORMATS.md` 把它们列在格式全集里（后者还注明 "fired early 未单列"），属于"格式覆盖但未接线"，不是死代码。
- 四个 `@Preview` composable —— 只在 IDE 里被用。

### 3. 验证边界

- `assembleRelease`（`-PallowDebugSignedRelease`）+ `testDebugUnitTest` 通过，APK 已 `install -r`。
- 未做真机目视。可疑点：渐变带在页面顶部完全透明（内容靠 `contentPadding` 让开，所以顶栏区是空的页面底色）；关于页的带从 hero 行程第一像素就淡入，比原来的连续 frost 更"早"。

---

## 七、2026-11-09（夜间）：顶栏两套引擎合一 + 过时项收口

### 1. 标题条：一套引擎，两种背景

`ui/MiuixGlassTopAppBar.kt` 现在是唯一的顶栏实现，等价于 MIUIX `GlassTopAppBar(progressiveBlur = …)` 的两个分支：

| | `progressiveBlur = false` | `progressiveBlur = true` |
|---|---|---|
| 谁用 | 纯 MIUIX 风格 | 柔光玻璃 |
| 背景 | `bandBrush`：上 45% 实色，0.58/0.72/0.86 → 0.82/0.58/0.34，底边透明；外溢 28dp | `progressiveTextureBlur`：10dp × 页面行程，2.2 曲线，页面色调 0.3 |
| 需要什么 | 什么都不需要 | 页面录制的 `LayerBackdrop` + RuntimeShader；缺失时自动退回 band |

两边的几何（44dp 控制盘、12dp 内边距、24dp 字形、大/紧凑两种标题）与两段动效（band 100ms / 材质 350ms）共用。控制盘是唯一按风格分叉的地方：柔光玻璃走自己的玻璃圆盘（见 `MiuixGlassTopBarButton`），其余走库的无 backdrop 兜底填充。旧的 `HazeGlassBar` 整段删除。

### 2. 应用页 / 记录页：两种风格都换 MIUIX 默认顶栏

- `PageScaffold` 里 `barExtension != null` ⇒ `plainBar`：页面用 `MiuixTopAppBar`（大标题，默认外观）＋条下直接的页签条，整列铺页面底色，无玻璃；记录页因此不再走 `barProgressive`（参数已删），`barScrollProgress` 只剩「玻璃圆盘的 reveal」一个用途。
- 应用页自建 scaffold，同样换默认顶栏：`MainTopBar` 的 `barColor` 从 `Transparent` 改页面底，搜索胶囊回落到纯 High 填充（不再有玻璃底可混），`hazeSource`/`rememberHazeState` 与整条 Haze 玻璃包装删除。

### 3. 「光路模糊」改回默认值

`softGlassMaterial` 的折射链路回到上游写法：`effect(BlurEffect(radius = material.blurRadius))`，不再 `.dp.toPx()`、不再走库的 `blur()` 自适应降采样，因此所有像素量（padding / size / 圆角 / 折射带 / 色散 / 后置模糊）都不再除 `downscaleFactor`。观感上光路更「轻」，这是交付默认值。

### 4. 底栏标签

纯 MIUIX 悬浮胶囊的标签 11sp → **12sp**（`NavigationBarDefaults.LabelFontSize`，与同一屏的停靠底栏一致），行高同步 12→13sp。图标 22dp、未选中 0.6、48dp 板、`showLabel = true` 一律没动 —— 标签名必须留着。

### 5. 其他过时项

- 胶囊页签选中字重 Medium → **Bold**（库 `TabRow` 的口径），测量宽度同步按 Bold 算，避免选中时被裁。
- **Haze 依赖整体移除**（`haze` + `haze-blur`，以及许可页那一行）：顶栏不再需要独立模糊引擎 —— band 自己画，柔光玻璃走已依赖的 `miuix-blur`。

### 6. 明确不改的（这轮判断）

| 项 | 理由 |
|---|---|
| 页签条换成库 `TabRow` / `TabRowWithContour` | 会把胶囊页签变回系统页签，是观感重做不是对齐；45dp/8dp 圆角也与本项目的两级 tab strip 不合 |
| 弹层 / 底栏玻璃换成 `miuix-glass` | Maven 无 0.9.4 制品（`miuix-glass-android/0.9.4` → 404），只能抄规范 |
| `FloatingNavigationBarItem` 替换手写 `PillTabItem` | 库项至今只画图标（label 只进 contentDescription），换过去标签就没了 |
| `BlurTopAppBar` 大标题虚化 | 0.9.4 的 jar 里没有这个函数（`javap` 只有 `TopAppBar`/`SmallTopAppBar`），源码里的 `largeTitleBlurRadius`/`titleAlpha` 用不了 |
| 悬浮胶囊 `shadowElevation = 1dp` | 观感项，风险大于收益；`PillTabItem` 本体不动 |
| `SmallTitle` 右边距 8 vs 库 28 | 右侧没有内容，改了没有可见差异 |

### 7. 验证边界

- `assembleRelease`（`-PallowDebugSignedRelease`）+ `testDebugUnitTest` 通过。
- **未安装到真机**：这一轮 adb 一直没发现设备（`adb mdns services` 为空），无法 `install -r`，也没做目视。可疑点：默认顶栏在应用页/记录页是完全实底的（内容滚到栏下会被遮住，这是设计），记录页两排页签现在直接坐在实底上；柔光玻璃的光路比上一版更轻。
