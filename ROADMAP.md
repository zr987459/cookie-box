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

## 阶段二：增强与进阶版 (已完成 - v1.3.0)
- [x] 多窗口标签页管理与多账号多开隔离 (Multi-Account Tabs Isolation)：
  - 借鉴开源优秀浏览器架构，实现各标签独立的会话快照与隔离机制
  - 新增「新建多开小号标签」功能，自动抹除旧会话以全新身份登录，切换标签自动重放对应 Cookie，彻底实现大号小号互不干扰
  - 完整实现 WebChromeClient `onCreateWindow` 与 `setSupportMultipleWindows`
- [x] Cookie 百科辞典与智能介绍 (Cookie Dictionary)：
  - 内置主流网站（B站、百度、微博、GitHub、知乎、Cloudflare 等）核心鉴权 Cookie 百科
  - 卡片顶部显示分类彩色角标（核心凭据、安全校验、用户标识、设备指纹、会话凭据）
  - 启发式智能识别未知 Cookie，为每一项提供人话解释
- [x] 复制与交互细节优化：
  - 移除 RecyclerView 的手势抢占，彻底解决点击复制失效问题
  - 新增独立的「复制值」与「复制键值对」按钮，支持一键单独提取 Value（爬虫脚本填参最方便）
- [x] 凭据一键直达免密网站优化：
  - 修复登录页死循环，智能将 `/login`、`passport.` 登录页链接自动降级重定向至主站
  - 增加全量异步 `setCookie` 回调与主线程下一帧延迟加载，确保内核协议栈 100% 写入成功，彻底消除“需要输密码”的异步竞态问题
- [x] 固化永久正式数字证书签名 (`cookiebox.jks`)，未来所有升级 100% 平滑覆盖，杜绝破坏性更新

---

## 阶段三：开发者极客特性 (长远规划)
- [ ] 请求头 Header 实时抓包嗅探监听
- [ ] 自定义 Cookie 编辑与修改后写回 WebView 测试
- [ ] 局域网 WebDAV / 本地加密导出备份
