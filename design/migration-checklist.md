# CastTV 架构重构 · 模块功能迁移清单

> 版本：v2.0（含用户确认调整）
> 用途：8 大页面 + 1 全局顶部状态栏 + 1 组公共能力的完整功能盘点，迁移到新 PageContainer 架构时**逐条确认**，避免漏迁。

---

## 🌐 全局顶部状态栏 GlobalTopStatusBar（新增，撑满宽度）

> 从主页抽出到全局层，位于 WallpaperLayer 之上、PageContainer 之上，所有页面都展示，不参与焦点

| # | 功能 | 描述 | 迁移状态 |
|---|---|---|---|
| 0.1 | 当前时间 | HH:mm，每分钟刷新 | ✅ ➕ |
| 0.2 | 设备名称 | 可点击跳设置 · 设备名（不参与焦点，仅展示） | ✅ ➕ |
| 0.3 | DLNA / AirPlay 状态 | 服务在线指示灯 | ✅ ➕ |
| 0.4 | 投屏中态提示 | 收到投屏请求 → 顶部状态栏红点闪烁 + 文字提示 | ✅ ➕ |
| 0.5 | 网络状态 | WiFi / 有线 / 断网 图标 | ✅ ➕ |

---

## ① 主页 Home（主页 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 1.1 | ~~状态栏~~ | MainActivity 顶部 | **迁到全局顶部状态栏** | 🔄 迁至 GlobalTopStatusBar |
| 1.2 | 投屏引导轮播 | tutorialRunnable | 各主流 App 投屏引导循环展示 | ☐ |
| 1.3 | 投屏状态卡片 | castStatusCard | 16:9 三态：待机 / 接收中 / 播放中 | ☐ |
| 1.4 | OK 键续播 | resumeCurrentCastFromHome | 播放中态按 OK 直接进播放器 | ☐ |
| 1.5 | ~~投屏横幅提示~~ | showCastReceivingBanner | **取消** · 用全局顶部状态栏投屏红点替代 | ✂️ 取消 |
| 1.6 | 退出确认弹窗 | showExitConfirmDialog | 蜡笔风格暖色弹窗 | ☐ |
| 1.7 | ~~一级 Dock 导航~~ | setupDock | **确认下线**，被 pageRootFocus + BottomIndicatorBar 替代 | ✂️ 下线 |

---

## ② 收藏 Favorites（收藏 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 2.1 | 合集侧边栏 | CollectionSidebarAdapter | 左侧合集列表，焦点即刷新 | ☐ |
| 2.2 | 视频网格 | FavoriteAdapter | 标题/分辨率/时长/缩略图 | ☐ |
| 2.3 | 视频卡片操作 | onBindViewHolder | 播放/改名/移动/删除/稍后观看 | ☐ |
| 2.4 | 新建合集 | switchToCreateMode | 表单输入合集名 | ☐ |
| 2.5 | 合集重命名/删除 | showCollectionRenameDialog | 非预置合集可改名/删除 | ☐ |
| 2.6 | 批量管理合集 | showCollectionManageDialog | 勾选+排序+批量删 | ☐ |
| 2.7 | 视频多选模式 | btnCollectionSelect | 批量删/移到其它合集 | ☐ |
| 2.8 | 局域网收藏导入/导出 | transferServer | 手机端上传/下载 CSV | ☐ |
| 2.9 | 导入直播源(M3U) | showImportLiveSourceInputDialog | 解析 M3U/JSON 并分组 | ☐ |
| 2.10 | 云端共享直播源浏览 | renderCloudShareList | 热度/点赞/一键导入 | ☐ |
| 2.11 | 云端共享分享 | showSharePromptDialog | 导入成功后上传云端 | ☐ |
| 2.12 | 合集加密/解密 | verifyPasswordsThenDownload | SHA-256 密码，关键词找回 | ☐ |
| 2.13 | 合集 Shared/Private 切换 | updateCollectionType | 管理员专属 | ☐ 🔒 |

---

## ③ 自定义 Tab CustomTab（每个自定义 Tab 独立 Page）

> 最新确认：自定义 Tab（如体育直播、电影等）是 `PageContainer` 里的独立 Page，通过屏幕左右两侧常驻翻页箭头（‹ ›）与底部 `BottomIndicatorBar` 切换进入；**不是左侧 Dock/侧边栏入口**。页面内部采用「左频道/资源列表 + 右 16:9 预览播放器区」双栏结构：左侧列表是主焦点，`onFocus` 切换频道后右侧 200~300ms 防抖刷新预览；右侧预览区是次焦点，OK 全屏播放。非全屏 BACK 先回当前 Page 根焦点并展示 `BottomIndicatorBar`，当前 Page 根焦点再次 BACK 回首页根节点；全屏 BACK 退出全屏并回到当前自定义 Tab Page。

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 3.1 | 频道自动聚合 | TabChannelAggregator | 同名条目合并+备源；自定义 Tab 作为独立 Page 展示聚合频道 | ☐ |
| 3.2 | 预览播放器 | startPreview | 右侧 16:9 静音循环预览主源；播放器无边框、无圆角，水平方向充满预览区域 | ☐ |
| 3.3 | 焦点切换预览 | 频道列表 onFocus | 左侧主焦点移动即触发右侧 200~300ms 防抖 Loading 与预览刷新，不依赖 OK 点击 | ☐ ➕ |
| 3.4 | 预览失败回退 UI | startPreview | 错误文案 + 全屏尝试引导 | ☐ |
| 3.5 | 源管理弹窗 | ChannelSourceManageDialog | MENU 或源管理按钮打开；设主源/标记异常/强制健康检测 | ☐ |
| 3.6 | 健康状态展示 | rowLabel | 稳定/无声/卡顿/无法连接；异常源仅打标，不删除，不参与预览/播放 | ☐ |
| 3.7 | 滑动 ANR 优化 | 已在 1.1.144 版实现 | 滚动中不预览，停后再启 | ☐ |
| 3.8 | 自定义 Tab 管理入口 | showDockManageContent | 从设置进入（改名）；只负责增删改查/排序/图标，不作为运行时左侧 Dock | 🔄 改名为「自定义 Tab 管理」 |
| 3.9 | 自定义 Tab 图标 | CustomDockIconPresets | 20 个预置矢量图，用于 BottomIndicatorBar / 页面管理展示 | ☐ |
| 3.10 | 列表标题跑马灯 | 频道/资源列表项 | 左侧列表宽度收窄到约 13 个字符；超长标题不截断，焦点项横向跑马灯展示完整标题 | ☐ ➕ |

---

## ④ 连接手机 PhoneHub（连接手机 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 4.1 | HTTP 服务开关 | serviceSwitch | 端口 8899/8090 候选 | ☐ |
| 4.2 | 扫码连接 | QrCodeGenerator | 二维码含局域网 IP + 端口 | ☐ |
| 4.3 | 局域网地址显示 | urlText | 手动输入用 | ☐ |
| 4.4 | 手机端 HTTP 页面路由 | PhoneHubServer / HtmlPages | 收藏/历史/云同步 Tab AJAX 刷新 | ☐ |
| 4.5 | 手机端功能与 TV 端对齐 | — | 云同步等 HTTP 页面同步实现 | ☐ |
| 4.6 | 已连接设备列表 | — | **新增** · 展示当前接入手机（IP / 昵称 / 连接时长 / 断开按钮） | ☐ ➕ |

---

## ⑤ 历史记录 History（历史 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 5.1 | 历史列表展示 | showHistoryContent | 缩略图 + 时长 + 最后播放时间 | ☐ |
| 5.2 | 续播 | historyAdapter | 从上次位置续播 | ☐ |
| 5.3 | 清空历史 | 设置对话框 clearHistoryCheck | 现在在设置勾选后清理 · **建议保留在设置**，历史页顶部**新增独立按钮** | ☐ + ➕ 页顶按钮 |
| 5.4 | 加入收藏 | — | **新增** · 每条一键加入收藏 | ☐ ➕ |
| 5.5 | 观看进度条 | — | **新增** · 卡片带进度条 | ☐ ➕ |
| ~~5.6~~ | ~~导出历史~~ | — | 用户未采纳 | ✂️ |

---

## ⑥ 网络诊断 Diagnostics（诊断 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 6.1 | SSDP 实时日志 | showDiagnosticsContent | M-SEARCH/NOTIFY/HTTP 流水 | ☐ |
| 6.2 | 日志实时搜索 | diagFilterKeyword | 关键词过滤 | ☐ |
| 6.3 | 日志加粗优化 | formatDiagnosticsBold | 时间戳加粗 | ☐ |
| 6.4 | 一键复制 | btnCopyDiagnostics | 复制过滤后日志 | ☐ |
| 6.5 | 清空日志 | — | 清空当前缓存 | ☐ |
| 6.6 | 一键结构化诊断 | — | **新增** · 8 项：WiFi / IP / DNS / 外网 / Gitee / 云端 / HTTP服务 / 直播源 | ☐ ➕ |
| 6.7 | 直播源可用性抽检 | — | **新增** · 全量 M3U 频道 ping 结果 | ☐ ➕ |
| 6.8 | 上报日志 | — | **新增** · 通过 HTTP 服务下载到手机 / 生成二维码 | ☐ ➕ |

---

## ⑦ 帮助 Help（帮助 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 7.1 | 投屏教程分步 | showHelpContent | 蜡笔风格图文教程 | ☐ |
| 7.2 | 边界抖动反馈 | handleScrollViewBoundaryShake | 顶/底抖动动画 | ☐ |
| 7.3 | 遥控器按键说明 | — | **新增** · 新架构焦点/按键规则专门讲 | ☐ ➕ |
| ~~7.4~~ | ~~FAQ~~ | — | 用户未采纳 | ✂️ |
| 7.5 | 更新日志 | — | **新增** · 展示历史版本 releaseNote | ☐ ➕ |
| 7.6 | 联系反馈 | — | **新增** · 一键上报日志 + 二维码联系方式 | ☐ ➕ |
| 7.7 | 关于 | — | **新增** · 版本 / 构建号 / 隐私 / 开源许可 | ☐ ➕ |

---

## ⑧ 设置 Settings（设置 Page）

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 8.1 | 设备名称修改 | showDeviceNameEditDialog | 支持随机生成 | ☐ |
| 8.2 | 投屏密码模式 | Settings.passwordMode | 4~8 位数字密码 | ☐ |
| 8.3 | 投屏静音启动 | Settings.muteOnCast | 开始时强制静音 | ☐ |
| 8.4 | 开机自启 | Settings.bootAutoStart | 注册 BootReceiver | ☐ |
| 8.5 | 播放内核切换 | Settings.useSurfaceView | TextureView / SurfaceView | ☐ |
| 8.6 | 屏保风格 | Settings.screensaverStyle | Track/Waterfall/Mosaic | ☐ |
| 8.7 | 主题风格切换 | ThemeManager | 经典蓝 / 深灰蜡笔 | ☐ |
| 8.8 | 缓存一键清理 | clearCacheBtn | 历史缩略图 / 收藏缩略图（同弹窗含清空历史勾选） | ☐ |
| 8.9 | 检查更新 | btnCheckUpdate | 手动 Gitee 检查 + 安装 | ☐ |
| 8.10 | 自定义 Tab 管理 | DockManagePage | **改名** · 自定义 Tab 增删改查 + 排序 + 图标 | 🔄 改名 |
| 8.11 | 云同步配置入口 | CloudSyncDialog | Gitee Token / 全量云端合集管理 | ☐ |
| 8.12 | 云端合集删除 | btnDeleteCloud | 管理员专属（继续留在设置） | ☐ 🔒 |
| 8.13 | 云端下载上限 | maxNonPresetDownload | 管理员专属（继续留在设置） | ☐ 🔒 |
| 8.14 | 云端 M3U 源管理 | showManageSharedLiveSourcesDialog | 管理员专属（继续留在设置） | ☐ 🔒 |
| 8.15 | **全局壁纸开关** | — | **新增** · WallpaperLayer 开/关 | ✅ ➕ |
| 8.16 | **壁纸来源** | — | **新增** · 预置 / 本地图 / 纯色 | ✅ ➕ |
| 8.17 | **壁纸遮罩浓度** | — | **新增** · 0%~80% 滑竿 | ✅ ➕ |
| 8.18 | **壁纸模糊度** | — | **新增** · 可选 0%~30% | ✅ ➕ |
| 8.19 | **页面排序管理** | — | **新增** · BottomIndicatorBar 顺序调整 + 显示/隐藏 | ✅ ➕ |
| 8.20 | **指示栏缩放** | — | **新增** · BottomIndicatorBar 缩放滑竿 0.7×~1.6×（默认 1.0×，步进 0.1×），实时预览 | ✅ ➕ |

---

## 🎬 播放器 PlayerActivity（跨页面公共能力，内部菜单也要迁）

> 补充！之前遗漏，用户明确追加

| # | 功能 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| P.1 | 收藏状态同步 | PlayerActivity | 播放中直接切换收藏状态 | ☐ |
| P.2 | 收藏动画反馈 | PlayerActivity | 心形粒子动画 | ☐ |
| P.3 | 播放队列管理 | PlayQueue | 队列显示 / 下一集 / 上一集 | ☐ |
| P.4 | 画面比例调整 | PlayerActivity | 16:9 / 4:3 / 铺满 / 原始 等 | ☐ |
| P.5 | 软硬解切换 | PlayerActivity | 硬件解码 / 软件解码 | ☐ |
| P.6 | 稍后观看快捷键 | PlayerActivity | 遥控器快捷键 | ☐ |
| P.7 | 播放器内菜单 | PlayerActivity | 长按 OK / 菜单键弹出菜单：收藏 / 队列 / 比例 / 解码 / 稍后 | ☐ |
| P.8 | 进度快进快退 | PlayerActivity | 方向键 / 数字键控制进度 | ☐ |
| P.9 | 播放器退出确认 | PlayerActivity | 直播源快速返回不确认，点播资源可选择续播位置 | ☐ |

---

## ⑨ 跨模块公共能力

| # | 能力 | 现状入口 | 描述 | 迁移状态 |
|---|---|---|---|---|
| 9.1 | Gitee 更新检查 & 安装 | UpdateChecker | 版本 JSON + APK 下载 + FileProvider 安装 | ☐ |
| 9.2 | 海报墙屏保（系统级） | PosterDreamService | 系统 Daydream | ☐ |
| 9.3 | 海报墙屏保（App 内） | PosterWallActivity | 前台闲置 5 分钟自动 | ☐ ⚠️ 需和 PageContainer 生命周期对齐 |
| 9.4 | DLNA 接收 | DlnaRendererService | SSDP + SOAP + Media3 | ☐ |
| 9.5 | AirPlay 1 接收 | AirPlayService | Netty + iOS 镜像 | ☐ |
| 9.6 | 万能播放器 | PlayerActivity | 见 🎬 播放器章节 | ☐ |
| 9.7 | 后台常驻服务 | CastReceiverService | 保证协议可被发现 | ☐ |
| 9.8 | 收藏存储 | FavoritesStore | 事务化 JSON + tmp 原子替换 + 备份 | ☐ |
| 9.9 | 设置存储 | Settings.kt | SharedPreferences | ☐ 新增壁纸相关 keys |
| 9.10 | 蜡笔风格通用面板 | 主题资源 | 焦点缩放/GlowUnderline/BoundaryShake | ☐ |
| 9.11 | **PageContainer** | — | **新增** · 单 Activity 页面容器 | ✅ ➕ |
| 9.12 | **BasePage / pageRootFocus** | — | **新增** · 生命周期 + 根焦点 | ✅ ➕ |
| 9.13 | **BottomIndicatorBar** | — | **新增** · 悬浮胶囊指示栏 | ✅ ➕ |
| 9.14 | **GlobalTopStatusBar** | — | **新增** · 全局顶部状态栏 | ✅ ➕ |
| 9.15 | **WallpaperLayer / WallpaperManager** | — | **新增** · 全局壁纸底层 | ✅ ➕ |
| 9.16 | **BoundaryFocusHandler** | — | **新增** · 边界抖动 / 回根焦点统一封装 | ✅ ➕ |

---

## 图例

- ☐ 待迁移　✅ 已迁移　➕ 新增　🔒 管理员专属　✂️ 废弃　🔄 迁移路径调整　⚠️ 需注意

## 全局层叠层次（z-index 从底到顶）

```
z-0  WallpaperLayer            全屏壁纸 + 遮罩
z-1  PageContainer             当前 Page（内容可延伸至顶栏和指示栏后方）
z-2  GlobalTopStatusBar        全局顶部状态栏（撑满宽，半透明背景）
z-3  BottomIndicatorBar        悬浮胶囊指示栏（居中，宽 50%，半透明背景）
z-4  Toast / Dialog / 投屏横幅  最上层浮层
```

## 使用方式

1. 逐条 review，标注哪里要改我直接更新
2. 全部锁定后正式启动阶段 1 骨架代码：`WallpaperLayer` → `PageContainer` + `BasePage` → `GlobalTopStatusBar` → `BottomIndicatorBar` → `BoundaryFocusHandler`
3. 每阶段结束同步更新表格勾选状态

