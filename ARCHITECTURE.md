# CookieBox 架构设计文档 (ARCHITECTURE.md)

## 1. 项目定位与目标
**CookieBox** 是一款专为 Android 移动端打造的轻量、安全、专业的 Cookie 提取与调试浏览器应用。
主要解决移动端无法便捷获取网站 `HttpOnly` Cookie、Token、LocalStorage 数据的痛点，为自动化测试、爬虫脚本编写和 Web 开发者提供零门槛的一键提取方案，同时最大程度降低风控和隐私泄露风险。

---

## 2. 核心架构设计

系统整体采用层次清晰的模块化架构：

```
+-------------------------------------------------------------+
|                      UI 展现层 (View / M3)                   |
|  - MainActivity: 地址栏、导航栏、沉浸式 WebView 容器          |
|  - CookieBottomSheet: 抽屉式提取面板、搜索筛选、多格式切换     |
|  - QuickBookmarks: 预置常用平台快捷入口 (B站、微博、GitHub 等)  |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                     核心提取与处理层                         |
|  - CookieExtractor: Android 内核 CookieManager 深度穿透提取   |
|  - StorageExtractor: JS 注入获取 localStorage / sessionStorage|
|  - CookieFormatter: 多格式转换器 (Header, JSON, Netscape,    |
|                      cURL, Python Requests)                 |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                     底层浏览器引擎与安全                      |
|  - System WebView (Chromium 内核): 真实指纹、抗人机滑块拦截    |
|  - UA Manager: 移动端/桌面版 User-Agent 智能伪装与切换        |
|  - Security & Privacy: 纯本地运行、零上传、无痕沙箱、剪贴板保护|
+-------------------------------------------------------------+
```

---

## 3. 关键技术方案与原理

### 3.1 突破 `HttpOnly` 限制
- **背景**：现代网站的核心鉴权 Token（如 `SESSDATA`、`token`、`auth_session`）普遍带有 `HttpOnly` 标记，网页内的 JavaScript 无法通过 `document.cookie` 读取。
- **方案**：CookieBox 基于 Android 原生 `android.webkit.CookieManager`，该类工作在系统内核协议栈层面，能够直接无阻碍读取与当前 URL 及根域名关联的全部 Cookie（包括 `HttpOnly` 与 `Secure`）。

### 3.2 现代单页应用 (SPA) 本地存储兼容
- 针对部分现代 Web 采用 JWT 并保存在前端存储的问题，通过安全注入异步 JavaScript 脚本：
  ```javascript
  (function() {
      let ls = {};
      for (let i = 0; i < localStorage.length; i++) {
          let k = localStorage.key(i);
          ls[k] = localStorage.getItem(k);
      }
      return JSON.stringify(ls);
  })();
  ```
  结合 Cookie 统一呈现在“存储与 Token”面板中。

### 3.3 反爬虫与反风控机制 (Anti-Bot Defense)
- **真机指纹**：使用原生 Android WebView，拥有真实的 GPU Canvas 渲染、真实的 WebGL 上下文和真实的 Android 触控手势，天然通过 Cloudflare Turnstile、极验、腾讯防水墙等常见人机检测。
- **自定义 User-Agent**：支持无缝切换 Chrome Desktop (Windows/macOS) 与 Mobile (Android/iOS)，解决部分站点移动端与 PC 端接口及鉴权策略不一致的问题。

### 3.4 隐私与数据防泄露安全规范 (Zero-Trust Privacy)
1. **零外部网络权限**：除了 WebView 访问用户指定的网址外，应用本身不包含任何遥测、埋点、统计和后端服务器 API。
2. **多账号隔离清理**：支持一键清理 Cookie 缓存 (`CookieManager.removeAllCookies`) 与 Web 数据库，防止小号之间串号或被关联设备特征。
3. **数据复制保护**：提供导出面板本地直接复制，提供脱敏预览（隐藏 Token 敏感字符）。

---

## 4. 导出格式支持
1. **Standard Header String**: `key1=val1; key2=val2` (用于爬虫请求头 `Cookie`)
2. **Standard JSON**: 结构化对象，适合 API 请求体和各语言序列化
3. **Netscape / cookies.txt**: 兼容 `curl -b`、`yt-dlp --cookies`、`wget`
4. **Python Requests Snippet**: 直接生成可直接运行的 Python 爬虫代码模版
5. **cURL Command**: 直接生成终端可执行的完整请求命令

---

## 5. 云端流水线架构 (CI/CD)
遵循“手机端轻负荷，云端重型打包”原则：
- 使用 **GitHub Actions** 自动化编译。
- 每次推送代码或打标签时，云端 Ubuntu 节点自动拉取依赖、配置 JDK 17 与 Android SDK，编译出高效优化的 Debug 与 Release APK，并以 Release Artifacts 形式供用户直接下载安装。
