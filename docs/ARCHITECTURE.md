# CookieBox 架构（v1.7.0）

> 根目录 ARCHITECTURE.md 是早期版本说明，T-010 完成后以本文件为准。

## 1. 定位
纯本地的 Android Cookie 提取与调试浏览器：WebView 登录 → CookieManager 读取（含 HttpOnly）→ 多格式导出 / 储存库保存 / 一键带 Cookie 进入网站。不含任何后端、统计或上报。

## 2. 模块

```
app/src/main/java/com/cookiebox/app/
├── MainActivity.kt          浏览器外壳：标签、地址栏、下载、自动填充、各抽屉入口（约 1600 行，T-009 拆分）
├── ui/                      底部抽屉与列表适配器
│   ├── CookieBottomSheetDialog    当前页 Cookie 提取与导出
│   ├── StorageBottomSheetDialog   Cookie 储存库
│   ├── AccountsBottomSheetDialog  账号库
│   ├── TabsBottomSheetDialog      标签管理
│   ├── WebToolsBottomSheetDialog  翻译 / 缩放等工具
│   └── browser/                   （规划）T-009 拆出的控制器
├── engine/                  无界面逻辑
│   ├── CookieExtractor / CookieFormatter / CookieDictionary   提取、格式化、释义
│   ├── CookieStorageRepository    Cookie 储存库（加密存储 + 迁移）
│   ├── SecureStore                Keystore AES-256-GCM 通用加解密
│   ├── AccountVault / AccountAutofill   账号库与自动填充（自带一份 Keystore 代码，T-002）
│   ├── CookieKeepAliveEngine      用 WebView 低频访问首页为 Cookie 保鲜
│   ├── BrowserPrefs               浏览设置与标签会话
│   ├── FileDownloader / WebTranslator / DomainUtils
├── mcp/                     本机 MCP 服务（ADR-0004，默认关闭）：前台服务 + Streamable HTTP + 工具
└── model/                   数据类（SavedCookieRecord、AccountCredential、TabItem…）
app/src/test/                （规划）JVM 单元测试，T-005b
docs/adr/                    架构决策记录
```

依赖方向：`ui` / `MainActivity` → `engine` → `model`。`engine` 不得引用 `ui`。

## 3. 本地存储

全部为 `MODE_PRIVATE` 的 SharedPreferences，`allowBackup=false`、云备份与换机迁移全部排除。键名清单与冻结规则见 AGENTS.md 第 3 节。

### 3.1 Cookie 储存库加密（v1.7.0）
- 密钥：Android Keystore，别名 `cookiebox_cookie_vault_key`，AES-256，不可导出。
- 密文：`"v1:" + Base64(12B IV ‖ 密文 ‖ 16B Tag)`；前缀用于未来升级格式（`v2:`…）。
- 写入：`encryptVerified()` 加密后立刻回读解密比对，一致才落盘；`commit()` 同步写。
- 读取与迁移：密文、旧明文、历史不可读备份三路读取，按记录 id 合并（密文优先），加密写入成功后才删除旧来源。
  - ⚠️ 上一条及「别名存在绝不重建密钥」描述的是 integrate/1.7 @ 7288fb1。main @ 09f6d04 目前仍是 c7bcce2 的行为：只保留第一份不可读备份、明文与密文并存时直接删明文、`SecureStore.key()` 取不到条目时会重新生成。T-001 合入后删除本注释。
- 降级：Keystore 不可用时写明文，保证新数据不丢；下次可用时自动迁回密文。
- 详见 docs/adr/0001-cookie-vault-encryption.md。

### 3.2 账号库
密码字段单独加密（别名 `cookiebox_account_key`），其余字段明文 JSON。

## 4. 安全边界
- 进程内：Cookie 明文只在内存中存在；日志不得输出值（T-006）。
- 设备上：依赖应用沙箱 + Keystore；root 设备不在防护范围内。
- 网页边界：`CookieBoxDl` 下载桥当前对所有页面全局可见（一次性 token 防滥用，T-015 收紧）；视口 / 缩放 / 翻译 / 自动填充 / 下载五类注入各自判断敏感页，T-016 统一。
- 网络：`usesCleartextTraffic=true`，T-017 评估收紧。
- 分发：签名证书决定谁能覆盖安装并读取本地数据，当前证书已公开（T-003）。
- 本机服务（ADR-0004）：MCP 默认关闭；开启后前台服务监听 `127.0.0.1`（局域网需另开），Bearer Token + Host/Origin 校验 + 失败限流；AI 只能看到用户授权的「身份 × 域名」；日志与调用记录不含 Cookie、Token、请求头值和正文。

## 5. 构建与发布
GitHub Actions `build-apk.yml`：JDK 17，`assembleDebug`，产物上传 Artifact 并发布到 Releases（非 main 为预发布）。T-004 将改为任务 / 集成分支只编译、main 和 tag 才发布，并加入密钥扫描。
