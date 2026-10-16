# AGENTS.md — CookieBox 多 AI 接力开发规范

> 每一棒开工前必读：本文件 → TASKS.md → HANDOFF.md（最新一条）→ docs/ARCHITECTURE.md。
> 本文件的「红线」优先于任何单次指令。冲突时停工，在 HANDOFF.md 记录并交给仓库主人裁决。

## 1. 角色与权限

| 角色 | 可以做 | 不可以做 |
|---|---|---|
| 系统架构师 | 维护 docs/、AGENTS.md、TASKS.md、目录骨架（空目录 / .gitkeep）、拆分任务 | 写或改任何功能代码（`app/src/main/**` 下的 .kt / .xml） |
| 开发者 | 认领 TASKS.md 中的一个任务，只改该任务「范围」列出的文件 | 越出任务范围；顺手重构；改构建签名 / CI 发布逻辑（除非任务就是这个） |
| 审查者 | 读代码、跑构建、在 PR / HANDOFF 写结论，可提交测试 | 直接改功能代码修 bug（另开任务） |
| 仓库主人（人类） | 合并到 main、批准发布、批准任何红线例外 | — |

一棒只做一个角色、一个任务。做不完就在 HANDOFF.md 写清进度交给下一棒，不要把半成品混进别的任务。

## 2. 分支与提交

- `main`：只接受已审查的合并；功能代码不直接推到 main。架构师的纯文档提交例外。
- `integrate/<x.y>`：版本集成分支（当前 `integrate/1.7`）。
- `task/<任务ID>-<短名>`：每个任务一个分支，从当前集成分支切出，完成后合回集成分支。
- **禁止 force push、禁止改写历史、禁止删分支**，除非仓库主人在本次指令里明确批准。
- 提交信息：Conventional Commits（`feat|fix|docs|refactor|test|ci|chore(scope): 摘要`），正文写「为什么」和「兼容性影响」，末行写 `Task: T-xxx`。
- 推送前先拉取分支最新 head；有人刚提交就先读他的 HANDOFF 再动手。
- **仅通过 GitHub API 提交的一棒**（无本地 Git / 无编译环境）：每次提交的 parent 必须等于开工时读到的分支 head，更新 ref 时 `force=false`；HANDOFF 的「兼容性验证」必须如实写「未编译、未真机验证」，不得写「通过」。
- 集成分支与 main 分叉时（例如 main 有纯文档提交、integrate 有代码提交），合并用 merge commit，不用 rebase（rebase 会改写他人已推送的 sha）。

## 3. 数据兼容红线（不破坏已有数据）

以下名字已在用户手机上落盘，**任何版本都不得改名、删除或改变含义**。要换格式只能「新增键 + 兼容读取旧键 + 校验后迁移」：

| SharedPreferences 文件 | 键 | 说明 |
|---|---|---|
| `cookie_vault_storage` | `key_saved_cookie_records` | v1.6.x 明文（只读兼容 + 迁移） |
| | `key_saved_cookie_records_enc` | v1.7 密文 `v1:` + Base64(IV‖CT‖TAG) |
| | `key_saved_cookie_records_enc_unreadable[_<ms>]` | 解不开的密文备份，只能在成功恢复后删除 |
| | `key_saved_cookie_records_corrupt` | 损坏 JSON 备份 |
| `account_vault` | `accounts` / `ask_to_save` / `never_sites` | 账号库 |
| `browser_prefs` | （见 BrowserPrefs.kt） | 标签会话、浏览设置 |
| `cookiebox_download` | （见 MainActivity.kt） | 下载设置 |

| Keystore 别名 | 用途 |
|---|---|
| `cookiebox_cookie_vault_key` | Cookie 储存库 |
| `cookiebox_account_key` | 账号库密码 |

迁移铁律：
1. 先校验旧数据 → 写新数据 → 回读校验 → 同一次 `commit()` 里删除旧数据；任一步失败保留旧数据。
2. 已存在的 Keystore 别名**绝不重新生成**（`generateKey()` 会覆盖旧密钥，密文永久作废）。
3. 解不开、解析不了的数据一律先备份再继续，宁可多留不可少留。
4. 只有用户主动点「清空」才允许删除用户数据。
5. `versionCode` 只增不减；签名证书不变（变更见 T-003，需主人批准）。
6. 每个碰到存储的任务，HANDOFF 里必须写「从 1.6.x 覆盖安装」「从 1.7.0 覆盖安装」两条验证结果。

## 4. 零泄露红线

- 仓库里不得新增任何密钥、口令、令牌、真实 Cookie、真实账号、抓包样本。测试数据一律用明显假的值（`test_token_xxx`）。
- 日志、Toast、异常信息、崩溃上报里不得出现 Cookie 值、密码、Token；最多打印键名和长度。
- 应用不得新增任何联网上报、统计 SDK、远程配置；除 WebView 访问用户输入的网址外不发任何请求。
- 本地数据保持 `allowBackup=false` 与 data_extraction_rules 全排除，不得放开。
- 复制到剪贴板的凭据应标记为敏感（T-007）。
- **网页注入与 JS 桥红线**：
  - 不得新增全局 `addJavascriptInterface`；新桥一律用 `WebViewCompat.addWebMessageListener` 并限定 origin，或只在单次操作期间存在（现存 `CookieBoxDl` 见 T-015）。
  - 所有 `evaluateJavascript` 注入（视口、缩放、翻译、自动填充、下载）必须经过同一个敏感页判定，不得各自绕过；判定规则统一后见 T-016。
  - 注入脚本只读或只改展示，不得把页面数据（Cookie、表单值、Storage）回传给除本机 UI 以外的任何地方。
- 已知遗留风险：`cookiebox.jks` 与签名口令目前公开在仓库里（见 T-003）。在主人决定前不得再扩散（不得复制到别处、不得写进文档示例）。

## 5. 保持项目干净

- 不提交构建产物、APK、IDE 文件、本地日志、临时脚本；`.gitignore` 只增不减。
- 不引入新依赖，除非任务写明；引入时写清用途和体积。
- 一个任务一个 PR / 一组提交，不夹带无关格式化。
- 文档以 `docs/` 为准；根目录 ARCHITECTURE.md 保留到 T-010 合并为止。

## 6. 交接（HANDOFF.md）

每一棒结束时在 HANDOFF.md **顶部**追加一条，模板：

```
## <YYYY-MM-DD HH:mm 时区> · <角色> · <任务ID>
- 分支 / 提交：<branch> @ <短 sha>
- 完成：<做了什么，1–5 行>
- 未完成 / 风险：<没做完的、发现的问题>
- 兼容性验证：<1.6.x 覆盖安装 / 1.7.0 覆盖安装 / 不涉及存储>
- 泄露自查：<无新增密钥与敏感日志 / 说明>
- 下一棒建议：<任务ID + 一句话>
```

同时把 TASKS.md 里对应任务的状态改掉。
