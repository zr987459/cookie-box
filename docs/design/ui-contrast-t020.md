# CookieBox 高对比度配色重构 · 设计说明（T-020）

> 范围：只改资源（colors / colors_ui / themes / drawable / 布局字号与角标底）。**未改 Kotlin，全部 ViewBinding id 保留，未新增依赖。**

## 0. 根因：为什么"很多字看不清"

| 问题 | 位置 | 实测对比度 |
|---|---|---|
| 浅色模式下 `values/colors.xml` 其实是**深色调色板**（`on_surface #F8FAFC`），而 `colors_ui.xml` 浅色给的浮层是 `#FFFFFF` → 地址栏、底栏、查找栏里的文字是**白字压白底** | `values/colors.xml` × `values/colors_ui.xml` | **1.05 : 1** |
| 浅色图标色 `#4F4539`（暖棕）放在深色 `surface #111827` 的弹窗上 | `cb_icon` | 1.9 : 1 |
| 深色模式品牌色是琥珀 `#F5B971`，浅色是蓝 `#3B82F6`，两套模式像两个 App；蓝色作文字在白底仅 3.7 : 1 | `primary` | 3.7 : 1 |
| 主题没设 `colorOutline / colorError / colorSurfaceContainer*`，Chip、输入框、底部弹窗、对话框回落到 M3 默认**紫色** | `themes.xml` | — |
| 9sp / 10sp 小字共 25 处，角标复用带描边的搜索框底，字更显糊 | 各 item 布局 | — |

## 1. 配色体系

一套色相、两种明度：**浅色 Porcelain & Cobalt / 深色 Ink & Periwinkle**。参考 Material 3 tonal palette 与 Arc Search 的"中性面 + 单一强调色"。

| 角色 | 浅色 | 深色 | 最差对比度（AAA ≥ 7） |
|---|---|---|---|
| 背景 background | `#F5F6FA` | `#0A0D14` | — |
| 面 surface / 弹窗 | `#FFFFFF` | `#11151E` / `#141923` | — |
| 浮层（两颗药丸） | `#FFFFFF` | `#171C27` | — |
| 次级面 surface_variant（卡片、输入框） | `#ECEFF5` | `#1E2432` | — |
| 主文字 on_surface | `#0B1220` | `#F2F4F8` | 15.7 / 13.5 |
| 副文字 & 提示 on_surface_variant | `#3B4558` | `#C2C9D6` | 8.1 / 8.9 |
| 品牌 primary（也作强调文字） | `#1C36B8` | `#AFC2FF` | 7.8 / 8.5 |
| 按钮字 on_primary | `#FFFFFF` | `#0A1966` | 8.5 / 8.9 |
| 图标 cb_icon | `#1E2636` | `#DCE1EA` | 12.7 / 11.3 |
| 成功 success | `#08592C` | `#86E3AE` | 7.1 / 9.6 |
| 危险 danger | `#8C1610` | `#FFB4AB` | 7.9 / 8.7 |
| 代码 code_text on code_bg | `#0F172A` | `#E6EAF1` | 15.0 / 12.3 |

"最差"= 该文字色在 background、surface、浮层、surface_variant、code_bg、cookie_chip_bg 六种底色上的最小值。禁用态（前进/后退 0.35 透明）属 WCAG 豁免。

## 2. 层级规则
- **三级文字**：主文字 `on_surface`（标题、正文）→ 副文字 `on_surface_variant`（副标题、时间、提示、hint）→ 强调 `primary`（链接、状态、计数）。不再有任何 < 7:1 的"灰字"。
- **分层靠明度 + 发丝线**，不靠阴影：背景 < 面 < 浮层 < 次级面，深色模式逐级提亮约 3%。
- 主题全局绑定 `textColorPrimary / Secondary / Hint / colorControlNormal`，系统控件（对话框、菜单、输入框）也自动高对比。

## 3. 组件
- 底部控制栏 / 顶部地址栏：浮层色 + `cb_hairline` 描边，与背景分得开；提取按钮是全屏唯一实心色块。
- 底部弹窗：28dp 顶圆角 + 顶部 1dp 发丝线，`bottomSheetDialogTheme` 统一接管，遮罩 50%。
- 对话框：`alertDialogTheme` / `materialAlertDialogTheme` 统一，按钮用加粗品牌色。
- 小角标：新增 `bg_tag`（无描边实心胶囊 6dp 圆角），替换原来复用的搜索框底。
- 字号下限 11sp（标签数量角标 10sp 加粗，受 16dp 胶囊限制）。
- 系统栏：浅色模式改为浅底深图标（原来浅色模式也是黑状态栏）。

## 4. 兼容性
- 色名全部保留并新增 `tertiary / outline* / error* / cb_sheet_surface / cb_tag_bg / cb_scrim`；Kotlin 里 `R.color.primary / danger / success / on_surface_variant` 引用照常生效。
- Material 1.11.0 已含 `colorSurfaceContainer*` 等属性（已核对其 attrs.xml）。
- 不涉及存储、JS 桥、CI、签名。
