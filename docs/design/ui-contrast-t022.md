# CookieBox 高对比度配色重构 · 设计说明（T-022）

> 范围：只改颜色 / 主题 / 一个 drawable 资源。**色名全部保留、布局 id 全部保留、未改 Kotlin**，存量代码零改动即可编译。

## 1. 文字看不清的根因

| 问题 | 位置 | 后果 |
|---|---|---|
| 浅色模式沿用了深色色板 | `values/colors.xml`：`on_surface #F8FAFC`、`background #0B0F17` | T-019 的浮层药丸在浅色下是白色（`cb_floating_surface #FFFFFF`），上面的地址、底栏文字是近白色 → **白字压白底，约 1.05:1** |
| 浅色主题强制深色系统栏 | `values/themes.xml`：`windowLightStatusBar=false` | 浅色界面顶着一条黑色状态栏，割裂 |
| 次级文字偏灰 | 深色 `on_surface_variant #94A3B8` 在卡片 `#1E293B` 上约 5.7:1 | 只到 AA，提示文字、副标题发灰 |
| 品牌色当文字用但太浅 | 浅色 `primary #3B82F6` 在白底约 3.7:1 | 标签、链接、文字按钮不达标 |
| 深色模式蓝灰底 + 琥珀品牌色 | `#0F172A` + `#F5B971` | 冷暖打架，显脏 |
| Material 3 角色缺失 | 未定义 `colorOutline`、`colorSurfaceContainer*` 等 | Chip、描边按钮、输入框、对话框回落到 M3 默认紫色系 |

## 2. 新配色

**浅色「暖纸」**：底色 `#F6F3EE` → 卡片 `#F2EDE6` → 浮层 / 弹窗 `#FFFFFF`；主文字 `#1B1612`，次文字 `#4A4036`，品牌焦糖可可 `#6E3800`。

**深色「石墨琥珀」**：底色 `#0D0E11` → 弹窗 `#15171C` → 浮层药丸 `#1A1D23` → 卡片 `#20242B`；主文字 `#F3F4F6`，次文字 `#C9CED6`，品牌琥珀 `#FFC47E`。中性石墨替代偏蓝 Slate，琥珀不再显脏。

文字分层：主文字（≥14:1）> 次文字 / 提示（≥8:1）> 品牌强调（≥8:1）。不再使用任何低于 7:1 的文字色。

## 3. 对比度实测（WCAG 2.x 公式）

```
== light
on_surface on background 16.22 AAA
on_surface on surface    17.95 AAA
on_surface on float      17.95 AAA
on_surface on sv         15.41 AAA
       osv on background  9.13 AAA
       osv on surface    10.11 AAA
       osv on float      10.11 AAA
       osv on sv          8.68 AAA
       osv on chip        8.25 AAA
   primary on surface     9.42 AAA
   primary on float       9.42 AAA
   primary on sv          8.09 AAA
   primary on background  8.51 AAA
   primary on chip        7.69 AAA
on_primary on primary     9.42 AAA
      icon on float      15.29 AAA
      icon on sv         13.13 AAA
    danger on surface     9.48 AAA
    danger on sv          8.14 AAA
   success on surface     8.36 AAA
   success on sv          7.18 AAA
       opc on pc         13.74 AAA
       osc on sc         13.47 AAA
  cookie_t on cookie_bg   9.34 AAA
    code_t on code_bg    15.12 AAA
   outline on surface     5.92 AA
== dark
on_surface on background 17.54 AAA
on_surface on surface    16.29 AAA
on_surface on float      15.34 AAA
on_surface on sv         14.15 AAA
       osv on background 12.21 AAA
       osv on surface    11.34 AAA
       osv on float      10.68 AAA
       osv on sv          9.85 AAA
       osv on chip        8.50 AAA
   primary on surface    11.48 AAA
   primary on float      10.81 AAA
   primary on sv          9.97 AAA
   primary on background 12.36 AAA
   primary on chip        8.61 AAA
on_primary on primary    10.98 AAA
      icon on float      13.99 AAA
      icon on sv         12.90 AAA
    danger on surface     9.37 AAA
    danger on sv          8.14 AAA
   success on surface    11.96 AAA
   success on sv         10.39 AAA
       opc on pc          9.62 AAA
       osc on sc         11.77 AAA
  cookie_t on cookie_bg   9.75 AAA
    code_t on code_bg    16.04 AAA
   outline on surface     6.89 AA
```

说明：`osv` = on_surface_variant，`sv` = surface_variant，`float` = cb_floating_surface，`chip` = cb_lock_chip。`outline` 只用于描边 / 非文字图形，WCAG 要求 3:1，已满足。

## 4. 主题改动

- 浅色 / 深色合并为一份 `Theme.CookieBox`（`values/themes.xml`），颜色由 `values` / `values-night` 同名资源切换；系统栏图标深浅由新增 `@bool/cb_light_system_bars` 控制。避免两份主题再次漂移（T-019 前后两份已出现不一致）。
- 补全 M3 角色：`colorOutline(Variant)`、`colorError*`、`colorSurfaceContainer{Lowest..Highest}`、`colorSurfaceInverse` 等，并设置 `textColorPrimary/Secondary/Hint`，让没显式写色的控件同样高对比。
- 底部弹窗：`bottomSheetDialogTheme` 改为继承 `ThemeOverlay.Material3.BottomSheetDialog` 的 overlay（保留本主题颜色），容器背景透明，只显示布局自带的 `bg_bottom_sheet`（新增 1dp 发丝边），消除双层底色。旧样式名 `BottomSheetDialogTheme` 保留。

## 5. 验证状态

- 仅通过 GitHub API 提交，**未编译、未真机验证**。需要下一棒 `assembleDebug` + 浅色 / 深色各过一遍主界面、5 个底部弹窗和 AlertDialog。
- 重点看：底部弹窗是否出现双背景或圆角被裁、M3 BottomSheet overlay 在 API 24–26 的导航栏表现、`colorSurfaceContainer*` 属性在 Material 1.11.0 下解析正常。
