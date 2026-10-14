# 🍪 CookieBox (曲奇盒) - 手机端轻量安全 Cookie 浏览器

<p align="center">
  <b>一款专为 Android 移动端设计的轻量、极简且专业的 Cookie 提取与安全调试浏览器</b><br/>
  解决手机上抓取 <code>HttpOnly</code> 关键登录凭据繁琐、抓包证书配置复杂等痛点。
</p>

---

## ✨ 核心特性

- 🚀 **极简操作流程**：内置常用平台快捷入口（B站、微博、GitHub、知乎、V2EX 等），登录账号后点击底部「提取」胶囊按钮，**0.1 秒展开全部凭据**。
- 🗂️ **丝滑多窗口系统 (Multi-Window Tabs)**：支持像主流浏览器一样随时打开多个网页标签，各标签独立运行；原生适配网页内部 `target="_blank"` 与 `window.open`，开新窗口绝不闪退。
- 🗄️ **Cookie 本地储存库与「一键直达」**：
  - 提取的凭据可一键永久存入本地保险库，随时调出查看、按域名搜索过滤；
  - **一键免密直达网站**：在储存库点击「进入网站」，自动将保存的全部 Cookie 深度注入系统底层内核，直接免密登录进入目标站点；
  - 支持快捷复制 Header、查看卡片明细或二次导出 JSON/Netscape/Python/cURL。
- 🔓 **穿透 `HttpOnly` 限制**：依托 Android 原生内核协议栈 `CookieManager`，通杀常规网页 JavaScript 无法触及的 HttpOnly / Secure 敏感凭据（如 B站 `SESSDATA`、百度 `BDUSS` 等）。
- 📦 **全能导出格式一键复制**：
  - **Header 字符串**：`name1=value1; name2=value2`（直拷放入爬虫请求头）。
  - **结构化 JSON**：包含 `name`, `value`, `domain`, `path` 等规范字段。
  - **Netscape cookies.txt**：无缝导入 `yt-dlp`、`curl`、`wget`。
  - **Python Requests 代码**：自动生成开箱即用的完整 Python 爬虫请求脚本。
  - **cURL 命令**：自动生成终端一行可执行的 cURL 测试命令。
- 🔍 **搜索与过滤**：支持对庞大的 Cookie 列表及储存库记录进行毫秒级实时关键词搜索。
- 🛡️ **抗风控真实指纹**：采用系统原生 Chromium WebView 内核，具备真实硬件 GPU 渲染、Canvas/WebGL 指纹与真实触摸轨迹，过滑块验证、人机验证码如真人普通上网。
- 💻 **多端 UA 智能切换**：一键切换 **Android 移动端** 与 **Windows 桌面端 (PC)**，轻松抓取不同终端特有的接口凭据。
- 📱 **单手沉浸式交互优化**：底部操控栏贴合手机握持习惯，前进、后退、储存库、多标签与提取按钮触手可及。
- 🔒 **100% 隐私零泄露 (Zero-Trust)**：
  - **纯本地运行**：绝无任何数据分析上报、第三方 SDK 或外部服务器，所有凭据仅留存于本地内存与私有存储。
  - **会话无痕清理**：提供一键彻底抹除 Cookie 与浏览器缓存功能，防止多账号切换被风控标记。

---

## 📱 界面预览与工作流程

1. **进入目标网站**：在地址栏输入网址或点击首页快捷卡片。
2. **正常登录**：通过账号密码、短信验证码或扫码等官方常规方式登录。
3. **点击「曲奇」提取**：弹出底部抽屉，自由选择所需导出格式或单项复制。
4. **清理下线**：提取完毕后，可点击右上角清理图标一键销毁当前所有会话缓存。

---

## 🛠️ 技术栈与架构

- **开发语言**：Kotlin
- **界面体系**：Material 3 + Jetpack ViewBinding
- **核心组件**：Android WebKit (`WebView`, `CookieManager`, `WebChromeClient`)
- **构建环境**：Gradle 8.7 + Android Gradle Plugin 8.3.2 (Target SDK 34, Min SDK 24)
- **云端编译**：GitHub Actions CI/CD 流水线自动化打包

详见 [架构设计文档 (ARCHITECTURE.md)](ARCHITECTURE.md) 与 [功能路线图 (ROADMAP.md)](ROADMAP.md)。

---

## 📥 下载安装

本项目已配置 **GitHub Actions 自动化流水线**：
1. 访问仓库顶部的 **Actions** 标签页。
2. 点击最近一次的 **Build CookieBox APK** 工作流运行记录。
3. 在页面底部的 **Artifacts** 区域直接下载 `CookieBox-debug-apk.zip`。
4. 解压后将 `app-debug.apk` 安装到手机上即可立刻使用！

---

## 📄 开源许可证

本项目采用 [MIT License](LICENSE) 授权开源。
