
## 安装包说明
| 文件 | 用途 |
|------|------|
| `*-release.apk` | 日常使用（推荐） |
| `*-debug.apk` | 排查问题（未混淆，debug 签名） |

## 安装注意
- 作用域：`system` + `com.miui.powerkeeper`
- 安装或更新后请**重启**手机（system_server hook 需完整加载）
- 若签名变更（例如从 debug 签名换成发布签名），需先**卸载**旧模块再安装，并在 LSPosed 中重新启用模块

## 环境（本版验证）
- HyperOS 4.0.0.31 beta · Android 17
- PowerKeeper 4.2.00
- LSPosed 2.2.0-it(7891)
