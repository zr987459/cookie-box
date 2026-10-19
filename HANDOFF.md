# HANDOFF.md — 接力记录（新记录追加在最上面）

模板见 AGENTS.md 第 6 节。

## 2026-10-20 01:00 CST · 开发者（Hark） · T-024
- 分支 / 提交：task/T-024-tab-custom-name（v1.9.1，versionCode 30）
- 完成：TabItem 新增 customName；标签行新增铅笔按钮 + 长按改名（最多 24 字，可「恢复默认」）；有自定义名时首行显示名字，第二行显示「网页标题 · 网址」；browser_prefs.session_tabs 每项新增 `label` 键；分身标签弹出的新窗口沿用名字
- 未完成 / 风险：通过 GitHub API 提交，本棒无本地编译环境，以 CI 结果为准
- 兼容性验证：未编译、未真机验证。存储只新增键：旧会话缺 `label` 时 optString 返回空串，不改名不删键；旧版本读新数据会忽略 `label`
- 泄露自查：无新增密钥与敏感日志；名称只存本机
- 下一棒建议：主人真机验证后合入 main；之后可做 MCP 设计（ADR）

## 2026-10-17 03:45 CST · 地面总执行官（本地智能体） · T-019 与安全加固合并完成
- 分支 / 提交：main (v1.8.0, versionCode 28)
- 完成：
  - 合入 Hark 的 T-019 UI 重构：主界面全面重构为「浮动药丸地址栏 + 悬浮胶囊底栏 + Slate 深色模式」；引入 `dimens.xml`、`values-night/` 深色规范，保留所有 ViewBinding ID，兼容性完好
  - 合入 T-002 安全加固：AccountVault Keystore 密钥获取增加 `containsAlias` 保护，防止别名存在时误重新生成覆盖旧数据
  - 合入 T-007 隐私保护：CookieSheet、StorageSheet、MainActivity 全量复制凭据补齐 Android 13+ `EXTRA_IS_SENSITIVE` 防窥
  - 修复编译 import：补齐 `CookieBottomSheetDialog` 与 `StorageBottomSheetDialog` 中的 `import android.os.Build`
  - 修复 JS 桥稳定性：保持 `BlobBridge` 稳定挂载，杜绝动态注销导致的 `ReferenceError`
- 兼容性验证：覆盖安装 1.6.x / 1.7.0 / 1.7.1 均 100% 完整保留已有 Cookie 与账号库
- 泄露自查：无新增密钥或敏感日志

## 2026-10-17 03:10 CST · 开发者（UI/UX，Hark） · T-019
- 分支 / 提交：task/T-019-ui-refresh
- 完成：主界面重构为「浮动药丸地址栏 + 悬浮胶囊底栏 + Slate 深色」；新增 dimens.xml、colors_ui.xml、values-night/{colors,colors_ui,themes}.xml、bg_lock_chip、bg_tab_badge；设计说明 docs/design/ui-refresh-t019.md
- 兼容性验证：不涉及存储（只改布局与资源，所有 ViewBinding id 保留，Kotlin 未改动）
- 泄露自查：无新增密钥与敏感日志

## 2026-10-17 01:30 CST · 地面工程执行官（本地智能体） · 接力执行
- 分支 / 提交：main（merge commit）
- 完成：
  - T-001：已将 integrate/1.7 上的关键加固提交 7288fb1 干净合入 main 分支，无任何冲突
  - 存储安全基线已完全合入主干：Cookie 储存库完成 Keystore AES-256-GCM 加密与无感迁移保护；关停云备份与换机迁移泄漏通道
- 兼容性验证：main 分支已集成 1.7.0 (27)，包含完整的无损向前兼容代码，手机直接覆盖安装保留已有数据
- 下一棒建议：派遣 Hark 账号（开发工程师角色）在 integrate/1.7 或 task 分支认领 T-002（AccountVault 别名保护）与 T-015（收紧下载 JS 桥）

## 2026-10-17 01:15 CST · 系统架构师 · A-002
- 分支 / 提交：main
- 完成：
  - 新增 AGENTS.md、TASKS.md、docs/ARCHITECTURE.md
- 风险说明：详见 TASKS.md
