# CookieBox 1.9 · 高对比度调色（Glacier / Midnight）

## 问题根因
1. `values/colors.xml`（浅色）里存的是深色值：`on_surface #F8FAFC`、`surface #111827`；而 `colors_ui.xml` 浅色浮层是 `#FFFFFF`。系统浅色模式下地址栏、底栏、卡片出现**白字白底 1.05:1**。
2. 次级文字 `#94A3B8` 在白底仅 2.56:1，在深底 5.7–6.5:1，都达不到 AAA。
3. 深色模式品牌色是琥珀 `#F5B971`，底色却是 Slate 冷蓝黑，冷暖混搭显脏。

## 方案
- 色名全部保留，只换值：Kotlin / ViewBinding 零改动。
- 颜色角色只在 `Base.Theme.CookieBox` 声明一次，浅深差异全部来自 `values(-night)/colors*.xml`。
- `scripts/check_contrast.py` 在 CI 中校验，任何文字色 × 任何面色 < 7:1 即阻断构建。

| 角色 | 浅色 Glacier | 深色 Midnight |
|---|---|---|
| 页面底 background | `#F4F6FB` | `#0B0E14` |
| 弹窗/卡片 surface | `#FFFFFF` | `#12161F` |
| 浮动药丸 cb_floating_surface | `#FFFFFF` | `#181D28` |
| 输入区/卡片 surface_variant | `#EDF0F7` | `#1F2533` |
| 发丝边 cb_hairline | `#D3D9E6` | `#2A3141` |
| 主文字 on_surface | `#0E1525` | `#F3F5F9` |
| 次文字/提示 on_surface_variant | `#3D4659` | `#C4CBD8` |
| 图标 cb_icon | `#222B3D` | `#DDE2EC` |
| 品牌 primary / on_primary | `#1A3AAE` / `#FFFFFF` | `#A9C2FF` / `#0A1E52` |
| 成功 / 危险 | `#08532D` / `#8C1913` | `#7FDBA2` / `#FFB4AB` |

最差文字对比度：浅色 7.69:1，深色 7.88:1；按钮文字 9.3:1 / 9.0:1。

## 字号
最小 11sp（状态标签），副文字 12sp，正文 13–15sp，域名 15sp 中粗。状态标签改用 `bg_tag_chip`（surface 底 + 发丝边），在 surface_variant 卡片上清晰分层。
