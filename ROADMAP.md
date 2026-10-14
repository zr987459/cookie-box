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

## 阶段二：增强与进阶版 (规划中)
- [ ] LocalStorage 与 SessionStorage 深度提取支持
- [ ] Cookie 字段脱敏遮罩展示（保护录屏与截屏不泄密）
- [ ] 多账号 Profile 会话隔离（独立 Cookie 沙盒，同时登录多个账号互不影响）
- [ ] 预设特定常用网站的专项提取助手（如 B站 SESSDATA、百度 BDUSS、微博 SUB 专用提取卡片）

---

## 阶段三：开发者极客特性 (长远规划)
- [ ] 请求头 Header 实时抓包嗅探监听
- [ ] 自定义 Cookie 编辑与修改后写回 WebView 测试
- [ ] 局域网 WebDAV / 本地加密导出备份
