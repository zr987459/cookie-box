# T-021 高对比度配色重制（WCAG AAA）

- 基线：main @ b2d0fd5（v1.8.0）
- 范围：只改资源（colors / colors_ui / themes 浅深两套、6 处 9–10sp 角标字号）+ 新增对比度门禁脚本。**未改 Kotlin、未改任何 view id、未改 CI 与版本号。**

## 1. 看不清的根因

v1.8.0 的浅色模式是两套调色板拼起来的：`values/colors.xml` 是深色 Slate（文字 `#F8FAFC` 近白），`values/colors_ui.xml` 却是浅米色（浮动地址栏、底栏 `#FFFFFF`）。结果：

| 位置 | 组合 | 对比度 |
|---|---|---|
| 浮动地址栏 / 胶囊底栏文字 | 近白字 on 白底 | **1.05 : 1**（等于看不见） |
| 挂锁底座上的文字 | 近白字 on 米色 | 1.12 : 1 |
| 底栏图标 | 棕灰 on 深色面板 | 1.89 : 1 |
| 主按钮白字 | on `#3B82F6` | 3.68 : 1 |
| 副文字 `#94A3B8` | on 深色卡片 | 5.7 : 1 |

深色模式则是 Slate 底 + 琥珀品牌色 + 蓝色残留混用，风格不统一，副文字与危险色也不到 7 : 1。门禁脚本在 main 上报 **30 项不达标**。

## 2. 新配色

风格：Material 3 中性色阶 + 靛蓝强调，参考 Arc Search 的「纸白 + 深墨 + 一抹品牌色」。层次靠明度分三级（底 → 卡片 → 输入框），不靠描边和阴影堆叠。

| 语义 | 浅色 | 深色 |
|---|---|---|
| background | `#F5F6F9` 纸白 | `#0C0D11` 蓝调近黑 |
| surface / 浮层 | `#FFFFFF` | `#14161C` / `#1A1D24` |
| surface_variant（输入框、角标） | `#ECEEF3` | `#1F222B` |
| on_surface 主文字 | `#0E1117` | `#F2F3F7` |
| on_surface_variant 副文字 | `#3A404D` 深石墨 | `#C6CAD6` |
| primary 品牌 | `#3730A3` 靛蓝 800 | `#BEC6FF` 靛蓝 200 |
| success / danger | `#04512F` / `#8E0F29` | `#7BE3B0` / `#FFB3BE` |

文字层级：主文字近黑/暖白；副文字是**深石墨/浅银**而不是发灰的中灰，任何背景上 ≥ 8 : 1；提示文字与副文字同色，靠字号区分。9sp 角标统一提到 11sp（底栏标签数角标 10sp，受 16dp 圆形限制）。

## 3. 门禁结果

`python3 scripts/check_contrast.py` 校验 33 组「前景 × 背景」（覆盖布局和 Kotlin `setTextColor` / `setColorFilter` 实际用到的所有组合），浅深各一遍：

- 文字阈值 7.0（AAA），图标阈值 4.5
- 结果：**全部通过，最低 8.04 : 1**

改色流程：先改 `values*/colors*.xml` → 跑脚本 → 通过再提交。新增颜色必须在脚本 `PAIRS` 里补上它的组合。

## 4. 主题层面的补强
- 补 `textColorPrimary / Secondary / Hint`、`colorControlNormal`：没显式写 textColor 的控件（对话框、菜单、输入框提示、Snackbar）也走高对比度。
- 浅色模式状态栏 / 导航栏改为浅底深图标（以前是深底配浅色主题，割裂）。
- 底部弹窗统一为 Material 3（深色模式原来误用了 `Theme.Design.BottomSheetDialog`），并通过 `bottomSheetDialogTheme` 全局生效。
- 新增 `ThemeOverlay.CookieBox.Dialog`，确认框 / 提示框与主界面同色。

## 5. 未验证 / 待办
- 沙盒无 Android SDK，**未编译、未真机验证**；需要在本地或 CI `assembleDebug` 后浅色 / 深色各看一遍主界面和 5 个底部弹窗。
- WebView 网页内容本身的颜色不在本任务范围。
