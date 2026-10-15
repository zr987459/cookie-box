# CookieBox 功能路线图与任务清单 (ROADMAP.md)

## 阶段一：MVP 核心可用版 (已完成)
- [x] 系统架构设计 (ARCHITECTURE.md) 与功能路线图 (ROADMAP.md)
- [x] 标准 Android 项目工程结构搭建 (Kotlin + Gradle KTS)
- [x] 核心 WebView 容器与导航交互：
  - 地址栏实时输入与回车加载
  - 网页前进/后退/刷新/停止
  - 常用热门平台快捷导航卡片
- [x] 核心 Cookie 穿透提取引擎：
  - 调用系统 CookieManager 穿透抓取全部 Cookie (含 HttpOnly / Secure)
  - 自动解析键值对并计算统计信息
- [x] 抽屉式提取交互面板 (Cookie Bottom Sheet)：
  - 悬浮一键提取按钮（曲奇图标）
  - 键值列表视图展示与关键字实时过滤
  - 多格式导出（Header 字符串、JSON 格式、Netscape 格式、Python 代码、cURL 命令）
  - 一键复制到剪贴板与反馈提示
- [x] 安全与基础防护：
  - User-Agent 智能切换 (Android 手机 / Windows 桌面)
  - 一键清空全站 Cookie 与沙箱缓存 (防止多账号关联)
- [x] GitHub Actions 自动化云端编译流水线 (.github/workflows/build-apk.yml)
- [x] 发布首个正式 Release (v1.0.0) 并附带直链安装包 (CookieBox-v1.0.0.apk)

---

## 阶段二：增强与进阶版 (已完成 - v1.4.0)
- [x] 多窗口标签页管理与真正的引擎级多账号网页分身 (Multi-Account Multi-Profile)：
  - 接入 AndroidX WebKit `MULTI_PROFILE` 原生多容器支持，为每个「新建分身」标签开辟完全独立的底层 Cookie 与 WebStorage 沙盒
  - 大号、小号、多开分身在底层物理级隔离，同时多账号登录绝不互串、互不影响
- [x] 彻底解决「一键进入网站」免密失效与死锁 (RFC 6265bis 规范合规)：
  - 深度支持现代高安全网站（如 Hark、GitHub、Google 等）的 `__Secure-` 与 `__Host-` 严格前缀规则
  - 自动为带有 `__Secure-` 的关键鉴权 Token 补全 `; Secure; SameSite=Lax` 属性，彻底防止 Chromium 内核静默丢弃
  - 为 `__Host-` 严格去除 `Domain` 约束，双层向目标 URL 与根顶级域名注入，确保页面和异步 API 全量认证通过
- [x] 反剪贴板限制与全能导出方案：
  - 系统原生分享 (`ACTION_SEND`)，一键直达便签与聊天软件
  - 本地 TXT 文件直接导出至手机下载目录
  - 代码框全区域点击即复制
- [x] 固化永久正式数字证书签名 (`cookiebox.jks`)，未来所有版本无缝平滑覆盖更新

---

## 阶段三：开发者极客特性 (长远规划)
- [ ] 请求头 Header 实时抓包嗅探监听
- [ ] 自定义 Cookie 编辑与修改后写回 WebView 测试
- [ ] 局域网 WebDAV / 本地加密导出备份
