# JSON 查看器 · JSON Viewer

<p align="center">
  <img src="docs/screenshots/app-icon.png" width="110" alt="JSON Viewer">
</p>

<p align="center">
  本地优先、最小权限的 Android JSON 查看与编辑工具<br/>
  <sub>View, query and edit JSON on Android — offline by default, one permission only.</sub>
</p>

<p align="center">
  <a href="https://github.com/zmn001125/JsonViewer/releases/latest"><img src="https://img.shields.io/github/v/release/zmn001125/JsonViewer?color=607D8B&label=release" alt="Release"></a>
  <a href="https://github.com/zmn001125/JsonViewer/actions/workflows/android.yml"><img src="https://github.com/zmn001125/JsonViewer/actions/workflows/android.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF.svg" alt="Kotlin">
</p>

---

## 这是什么

一个装在手机上的 JSON 工作台：粘贴或导入 JSON，在**代码模式**与**表单模式**之间随时切换，查路径、改值、看错误、调接口。解析、着色、搜索、编辑全部在本地完成，没有任何统计、账号或云同步；整个应用只申请 `INTERNET` 一个权限，且仅用于「网络请求」功能。

| 代码模式 | 表单模式 | 结构大纲 | 完整值 / 编辑 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/01-code.jpg" width="190"> | <img src="docs/screenshots/02-form.jpg" width="190"> | <img src="docs/screenshots/03-outline.jpg" width="190"> | <img src="docs/screenshots/04-value.jpg" width="190"> |

| 导入历史 | 网络请求 | 常规设置 | 主题定制 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/05-history.jpg" width="190"> | <img src="docs/screenshots/06-network.jpg" width="190"> | <img src="docs/screenshots/07-settings.jpg" width="190"> | <img src="docs/screenshots/08-theme.jpg" width="190"> |

## 功能

### 查看与定位

- **双模式**：代码模式基于自绘编辑器 [Sora Editor](#致谢)，带行号、增量语法高亮、软换行、双指缩放，**几 MB 文本下输入/删除同样跟手**；表单模式**逐层进入**，每次只渲染当前层，节点多也不卡。
- **面包屑导航**：`$ › store › book[2] › title` 每一段可点击回退；右侧显示本层节点数，一键复制当前 JSONPath。
- **结构大纲**：边缘滑动或点按钮呼出，全树展开/折叠、只看容器、按名称或值过滤，点击即跳转并闪烁定位。
- **JSONPath 查询**：支持 `$.a.b`、`$[0]`、`$..x`、`[*]`、切片、过滤表达式；结果可逐条定位或整体复制。
- **双模式搜索**：代码页高亮命中并上下跳转；表单页跨层搜索，命中后自动进入所在层。

### 编辑与转换

- **值详情**：完整内容可滚动、可选中，支持值内搜索、复制值、复制路径。
- **就地编辑**：修改后只替换原文对应片段，代码页与表单页同步；可把值改成字符串 / 数字 / 布尔 / null / 对象 / 数组，保存前按类型校验。
- **大文本编辑**：超过 4 万字符时打开临时代码编辑器（同一套高亮、行号、搜索），直接敲回车即可换行，保存时自动转义为 `\n`。
- **格式化**：一键美化（2 空格缩进）、压缩为单行、中文 ↔ `\uXXXX` 双向转义。

### 网络调试

- GET / POST，请求体原样发送，自定义任意请求头，支持粘贴 JSON 批量填头。
- **一行拆包**：直接粘贴 `https://…,{"method":"POST","body":"a=1","headers":{…},"charset":"gbk"}` 这种抓包格式，自动拆出链接、方法、请求头、请求体与编码；URL 查询参数里的逗号不会被误拆。
- **编码与转义**：文本编码可选 自动 / UTF-8 / GBK / GB18030 / 自定义，同时作用于响应解码、请求体字节和 URL 转义；URL 转义可选 自动（只转中文、空格等，保留已有 `%XX`）、不转义、`%` 也转义。
- **UA 策略**：手机 UA / 电脑 UA / 多条自定义 / 不携带；**Referer** 可关闭自动补全；**Content-Type** 可选自动、JSON、表单、纯文本、自定义或不发送。手填的请求头永远优先。
- 先看状态码与响应体，再决定是否导入到代码页；成功的请求连同方法、头、正文与策略一起进「链接历史」，点一下还原整次请求。

### 个性化与稳定性

- 预设主题 + 自定义主色 / 背景 / 强调色，代码与表单字号独立调节，浅色深色自动适配。
- 底部按钮可排序，每个按钮支持**三态**：底栏 / 更多 / 隐藏；「撤销 / 重做」也在其中，无操作可退时自动压暗。
- 导入历史、链接历史各 1–100 条，可多选删除。
- 崩溃日志默认开启，记录版本、机型、堆栈与崩溃前页面，可在设置里查看、复制、分享或关闭。
- 大文件防护：16MB 文件上限、200 万字符编辑器上限、25 万节点、400 层嵌套、5000 条命中，超限给出明确提示而不是闪退。

## 下载

前往 [**Releases**](https://github.com/zmn001125/JsonViewer/releases/latest) 下载最新 APK：

- `json-viewer-1.4.4.apk` — 正式版，release 签名，可直接安装（Android 7.0+，约 2.5 MB）
- 安装时如提示「未知来源」，在系统设置里允许对应应用安装即可

> 从旧版本（`com.jsonviewer`）升级的用户请注意：包名已改为 `com.guaner.jsonviewer`，新旧版本可以共存，但本地历史与设置不会自动迁移。

## 构建

环境：JDK 17、Android SDK 34（build-tools 34.0.0）、Gradle 8.2（随工程提供 wrapper）。

```bash
git clone https://github.com/zmn001125/JsonViewer.git
cd JsonViewer

./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk（R8 + 资源压缩）
./gradlew testDebugUnitTest    # 单元测试
```

也可以直接用 Android Studio 打开工程，等待 Sync 完成后运行。

### 签名

仓库**不包含**任何密钥。`assembleRelease` 按以下顺序取签名：

1. 环境变量 `JSONVIEWER_KEYSTORE` / `JSONVIEWER_STORE_PASSWORD` / `JSONVIEWER_KEY_ALIAS` / `JSONVIEWER_KEY_PASSWORD`；
2. 工程根目录的 `release.jks`（已在 `.gitignore` 中排除）；
3. 都没有时退回 debug 签名，保证产物仍可安装。

### GitHub Actions

`.github/workflows/android.yml` 在 push / PR 时运行单元测试并构建 debug 与 release 包，产物上传为 artifact；推送 `v*` 标签时会自动创建 GitHub Release 并附上 APK。

## 工程结构

```
app/src/main/java/com/guaner/jsonviewer/
├── MainActivity.kt              # 主界面：模式切换、解析调度、搜索定位、导入分享
├── JsonViewerApp.kt             # 全局初始化（设置、主题、历史、崩溃日志）
├── json/                        # 解析、建树、序列化、JSONPath、错误定位、限额
├── net/                         # HttpURLConnection 引擎、请求头 JSON 解析
├── data/                        # 设置、导入历史、链接历史、UA 配置、会话存储
├── theme/                       # 主题模型、配色推导、预设与自定义主题
├── editor/                      # 代码编辑器：JSON 增量着色语言、主题→配色映射
├── crash/                       # 崩溃日志落盘与解析
└── ui/                          # 编辑器、树、大纲、值面板、网络面板、设置页
app/src/test/                    # 纯 JVM 单元测试（解析 / 路径 / 序列化 / 设置）
docs/screenshots/                # README 截图
```

技术栈：Kotlin + ViewBinding + Material 3，代码编辑用 [Sora Editor](https://github.com/Rosemoe/sora-editor)，解析用 Jackson Core 流式 API（异常自带字符偏移，便于换算行列），查询用 Jayway JsonPath。没有引入图片加载、数据库或网络库，网络请求直接用 `HttpURLConnection`。

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 仅「网络请求」面板发起 HTTP/HTTPS 请求 |

- 导入文件走系统文档选择器（SAF），保存与分享走 `FileProvider`，都不需要存储权限。
- 历史、设置、崩溃日志只存在应用私有目录，卸载即清除。
- 无广告、无统计 SDK、无账号体系，不会上传任何内容。

## 说明

- **明文 HTTP**：网络面板允许 `http://`（`network_security_config.xml` 中放开 `cleartextTrafficPermitted`），方便调试内网与老接口；HTTPS 仍走系统 CA 校验。
- **混淆**：release 开启 R8 与资源压缩，json-path / Jackson 已配置 keep 规则。
- **测试**：单元测试覆盖解析器、路径引擎、序列化、限额、设置与主题编解码等纯逻辑模块，不依赖设备与网络。

## 更新日志

- **1.4.4** — 大纲跳转改为真正的**双层色块**：整行铺满行高铺一条浅色带，字段名压在带上一块深色圆角块。旧版走 Sora 诊断通道，那东西只会画贴近基线的细下划线，看起来几乎没变化；这版两层都改由 `onDraw` 按行高自绘
- **1.4.3** — 修复大纲跳转后字段名没有深色高亮：Sora 的诊断通道对 `SEVERITY_NONE` 直接跳过、且只画波浪线/下划线，画不出背景块，改为在编辑器自绘阶段按 `Layout` 的字符坐标画圆角实色块；整行浅底保留
- **1.4.2** — 底栏新增「撤销 / 重做」，与其他按钮一样可隐藏、可排序，不可用时自动压暗；值编辑弹窗也能搜索/正则/替换；大纲跳转改为「整行浅底 + 字段名实底」双层高亮（不再是波浪线）；设置里的开关按开/关/禁用三态分别配色，关闭态明度明显更低
- **1.4.1** — 代码搜索接入 Sora 的 `EditorSearcher`：支持正则表达式、区分大小写、单个/全部替换，搜索在编辑器内部线程池执行，超大文本也不卡主线程；「设置 → 主题 → 编辑器」新增 4 个搜索相关开关
- **1.4** — 代码编辑改用 Sora Editor：几 MB 文本下打字、删除、滚动全部跟手；代码页、临时编辑器、值编辑框三处统一为同一套编辑器，配色 / 字号 / 字体 / 换行全部跟随主题
- **1.3.1** — 修复「提示已恢复但内容为空」；恢复大文本时不再强制滚动与立即着色
- **1.3** — 优化超大文本的代码编辑手感；新增启动保护：异常退出后本次不再自动恢复上次内容，避免「一打开就崩、进不去」
- **1.2** — 许可证由 MIT 变更为 GNU GPL v3.0：修改或分发须同样开源
- **1.1** — 网络面板支持粘贴「链接,{配置}」自动拆分；新增文本编码（UTF-8 / GBK / GB18030 / 自定义）与 URL 转义三态（自动 / 不转义 / `%` 也转义）
- **1.0** — 首个开源版本

## 致谢

- [Sora Editor](https://github.com/Rosemoe/sora-editor) — 代码编辑器的排版、行号、选区、撤销栈与自绘渲染（LGPL-2.1）
- [Jackson Core](https://github.com/FasterXML/jackson-core) — 流式 JSON 解析
- [Jayway JsonPath](https://github.com/json-path/JsonPath) — JSONPath 查询
- [Material Components for Android](https://github.com/material-components/material-components-android)

## License

[GNU General Public License v3.0](LICENSE) © 2026 zmn001125

这是一份**强 copyleft** 协议，用一句话说清：

| 你的用法 | 是否需要开源 |
| --- | --- |
| 自己用、内部用，不对外分发 | 不需要，随便改 |
| 改了之后分发 APK、上架应用商店、随产品交付给客户 | **必须**以同样协议开放修改后的完整源码 |
| 原样分发（不改） | 需要保留本协议与版权声明，并附带源码获取方式 |

换言之：**可以免费用、免费改、免费商用，但不能拿去做闭源分发。** 分发时需提供对应源码（本仓库地址即可），并保留原作者版权声明。

> **关于历史版本**：v1.0 与 v1.1 曾以 MIT 协议发布，已获得的授权不可撤销，仍按 MIT 处理。GPL-3.0 自 v1.2 起生效。

第三方依赖仍遵循各自协议：Sora Editor（**LGPL-2.1**）、Jackson Core（Apache-2.0）、Jayway JsonPath（Apache-2.0）、AndroidX / Material Components（Apache-2.0），均与 GPL-3.0 兼容。

> **关于 Sora Editor**：它以 LGPL-2.1 发布并以未修改的二进制形式（Maven 依赖）链接进本应用。GPL-3.0 允许这种链接方式；如果你 fork 本仓库并修改了 Sora Editor 自身源码，需要按 LGPL-2.1 开放那部分改动。

