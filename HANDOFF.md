# HANDOFF.md — 接力记录（新记录追加在最上面）

模板见 AGENTS.md 第 6 节。

## 2026-10-17 01:15 CST · 系统架构师 · A-002
- 分支 / 提交：main（本提交，仅 AGENTS.md / TASKS.md / HANDOFF.md / docs/ARCHITECTURE.md）
- 完成：
  - 复核 A-001：既有规则与 T-001～T-014 保留，只做增补
  - AGENTS.md：新增「仅 API 提交」规则（parent=开工 head、force=false、未编译须如实写）与分叉时用 merge commit；新增「网页注入与 JS 桥红线」
  - TASKS.md：基线更新为 main @ 09f6d04 / integrate @ 7288fb1；T-001 验收补覆盖安装与 merge 方式；新增 T-015（收紧 CookieBoxDl 桥）、T-016（统一敏感页注入策略）、T-017（明文流量）、T-018（电脑版视口去闪烁）
  - docs/ARCHITECTURE.md：标注 3.1 节描述的是 7288fb1 行为，与 main 现状不同；补网页边界与明文流量说明
- 未完成 / 风险：
  - 【高】main 上 `SecureStore.key()` 仍会在取不到条目时重建密钥，7288fb1 已修但未进 main → T-001 最先做
  - 【高】签名密钥公开 → T-003 仍待主人决策
  - 【中】`CookieBoxDl` 对所有网页全局暴露 → T-015
  - A-001 记录时间（01:20）晚于其提交时间（01:13），以提交时间为准，未改动该记录
- 兼容性验证：不涉及存储（未改代码、构建脚本、CI）
- 泄露自查：无新增密钥、口令或真实数据；文档未引用签名口令
- 下一棒建议：主人批准 T-001（合入 7288fb1 并编译）；开发者并行认领 T-002、T-015

## 2026-10-17 01:20 CST · 系统架构师 · A-001
- 分支 / 提交：main（本提交，仅文档与空目录骨架）
- 完成：
  - 新增 AGENTS.md（角色权限、分支提交规范、数据兼容红线清单、零泄露红线、交接模板）
  - 新增 TASKS.md（P0/P1/P2 共 17 个半天量任务，含范围、验收、依赖）
  - 新增 docs/ARCHITECTURE.md（1.7.0 现状架构、存储与加密设计）、docs/adr/0001-cookie-vault-encryption.md
  - 新增测试目录骨架 app/src/test/java/com/cookiebox/app/.gitkeep
  - 未改动任何功能代码、构建脚本、CI
- 未完成 / 风险：
  - 【高】cookiebox.jks 及签名口令明文公开在仓库，任何人可签出能覆盖安装的 APK 读取用户本地数据 → T-003，需主人决策
  - 【中】AccountVault.key() 在取不到密钥时会重新生成并覆盖旧密钥 → T-002
  - 【中】integrate/1.7 上的加固提交 7288fb1 尚未进 main，也未编译验证 → T-001
  - 根目录 ARCHITECTURE.md 与 docs/ARCHITECTURE.md 暂时并存 → T-010
- 兼容性验证：不涉及存储（未改代码）
- 泄露自查：本次提交无密钥、无真实数据；文档只引用文件名，未复制口令
- 下一棒建议：主人先批 T-001 与 T-003；开发者可并行认领 T-002、T-006、T-007
