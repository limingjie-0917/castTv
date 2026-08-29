# CastTV Receiver · 投屏TV接收器

> 一款面向 Android TV / 电视盒子的全栈投屏与内容中台应用：AirPlay + DLNA + 抖音投屏接收、直播源/网页解析观影、收藏云同步、稍后播放队列、自定义 Tab 频道、动画城、手机端局域网控制台……全部开箱即用的"一台机顶盒就是一个家庭媒体中心"方案。
>
> 当前版本：`1.2.186` (versionCode 461) · 包名 `com.bd.casttv` · 最低支持 Android 5.0 (API 21)

---

## ✨ 功能一览

| 模块 | 能力 |
|---|---|
| 📺 **投屏接收** | AirPlay (视频/镜像/音频)、DLNA MediaRenderer、抖音投屏专属页；前台保活 + 全屏拉起锁屏 |
| 🎬 **在线观影** | 网页 JSON 适配器解析 (MacCms / 蜗牛 / 海王 / ZyPlayer / 通用规则)，自动嗅探源，15s 超时切备源 |
| 📡 **直播与自定义 Tab** | 直播源导入 / 频道归一聚合 / 源健康评分滑动累计；多页面容器 `‹ ›` 翻页 + 底部指示 |
| 🎞 **动画城** | 我的动画收藏云端同步、4 列 Premium Media 风格卡片、详情页简介折叠 + 多源多集选择 |
| 🔖 **收藏 / 稍后 / 队列** | 本地合集、Gitee 云端共享合集、推荐流可见用户、稍后队列冷启动归一；多设备同步 |
| 📱 **手机端控制台** | 局域网 HTTP 服务 `PhoneHubServer`，在手机浏览器完成收藏传书 / 云同步 / 云源下载 |
| 🧰 **诊断与工具** | SSDP / DLNA 网络诊断面板、主题壁纸屏保设置、设备命名、定时提醒与使用时长 |

---

## 🏗 工程结构

```
casttv/
├── app/
│   ├── build.gradle.kts                 # 版本号自动递增 bumpVersion + APK 文件树输出
│   ├── casttv.keystore                  # 调试/发布签名密钥（仓库内自带 debug keystore）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/json_adapter_spec.md  # 网页解析 JSON 规则文档（AI 生成规则参考）
│       ├── java/com/bd/casttv/
│       │   ├── airplay/                 # AirPlay RTSP / FairPlay / jap2lib
│       │   ├── dlna/                    # SSDP + UPnP MediaRenderer + 手机端 HTTP 页
│       │   ├── douyin/                  # 抖音投屏命中 + 历史记录
│       │   ├── favorites/               # 本地/共享合集、Tab 频道、直播源导入
│       │   ├── health/                  # 源健康滑动评分检测器
│       │   ├── cartoon/ CartoonStore    # 动画城本地缓存
│       │   ├── queue/                   # 稍后播放队列
│       │   ├── sync/                    # Gitee 云同步 API / ShareStore / 推荐流
│       │   ├── routine/ screensaver/    # 定时提醒、海报屏保、使用时长
│       │   ├── settings/                # SharedPreferences + 主题壁纸/Dock/设备名
│       │   ├── ui/
│       │   │   ├── framework/           # 新框架：NewMainActivity / BasePage / PageContainer
│       │   │   ├── theme/CartoonDesign  # 动画城 Taste 设计令牌（液态玻璃 + 琥珀强调色）
│       │   │   └── pages/               # CartoonCityPage / CartoonDetailPage / DouyinCastPage…
│       │   ├── update/                  # 应用内更新检查
│       │   ├── util/                    # ThemeManager / Thumbnails / QR / 网络监控 …
│       │   ├── webparse/                # RuleBasedAdapter / JsonAdapterSpec / WebParseExtractor
│       │   └── CastApp.kt
│       └── res/                         # drawable / layout / values / xml (FileProvider 路径)
├── design/                              # 架构 SVG 预览 + gen 工具
├── conventions.md                       # 派发任务规范（构建/工程/TV 交互/错误处理/硬约束）
├── AGENT.md                             # 开发 Agent 必须遵守的长期生效约束清单
├── DIALOG_SPEC.md                       # 自定义深色弹窗规范
└── build.gradle.kts  settings.gradle.kts
```

---

## 🛠 环境与构建

### 工具链

| 组件 | 版本 | 本机路径 |
|---|---|---|
| Gradle | 8.9 | `~/gradle-install/gradle-8.9/` |
| AGP | 8.5.2 | (由 `plugins` 块声明) |
| Kotlin | 1.9.24 | (由 `plugins` 块声明) |
| JDK | Temurin 17.0.20 | `~/gradle-install/jdk-17.0.20.1+1/Contents/Home` |
| Android SDK | compileSdk 34, targetSdk 34, minSdk 21 | `~/Library/Android/sdk` (见 `local.properties`) |

> ⚠️ **禁止使用 JBR 25**：AGP 8.5.2 与 JBR 25 不兼容，会在编译期抛错。

### 一键命令

```bash
# Debug 构建（含版本号自动递增）
cd casttv
JAVA_HOME=~/gradle-install/jdk-17.0.20.1+1/Contents/Home \
  ~/gradle-install/gradle-8.9/bin/gradle assembleDebug -x lint --no-daemon

# 只做 Kotlin 编译验证（无 APK 产出，最快迭代）
JAVA_HOME=~/gradle-install/jdk-17.0.20.1+1/Contents/Home \
  ~/gradle-install/gradle-8.9/bin/gradle compileDebugKotlin --offline

# Release 构建（会先递增版本号，使用 app/casttv.keystore 签名）
JAVA_HOME=~/gradle-install/jdk-17.0.20.1+1/Contents/Home \
  ~/gradle-install/gradle-8.9/bin/gradle assembleRelease -x lint --no-daemon
```

### 版本号与 APK 输出（强约束）

`bumpVersion` 任务挂在 `preBuild` 之前，**每次 assemble\* 都会自动写回版本号**：

- `BASE_VERSION_CODE += 1`
- `BASE_VERSION_NAME 末位 += 1`（格式 `1.2.XXX`，工程实际为三位末位）
- 写回 `app/build.gradle.kts` 顶部字面量，禁止人工只改其中一项。

构建完成后 APK 同时出现在两个位置：

```
builds/
└── 1.2.186/
    └── casttv-receiver-v1.2.186-debug.apk        ← 便于取用
app/build/outputs/apk/debug/
    └── casttv-receiver-v1.2.186-debug.apk        ← Gradle 标准目录
```

签名信息：`app/casttv.keystore`（仓库内携带，供 debug/release 共用，构建文件已配置 `signingConfigs`）。

---

## ☁️ 云同步 (Gitee)

- **远端仓库**：`https://gitee.com/bdCasttv/video-source`
- **主数据路径**：`shared_data/cartoons/_index.json`（动画城）、`favorites/*.json`（共享合集）、`recommendations.json`（推荐流）、`visible_users.json`（可见用户清单）
- **认证**：由 `GiteeApi` 携带 accessToken 提交；读取失败统一由 `CoroutineExceptionHandler` 落到空态文案，不静默吞。
- **脏数据策略**：
  - `globalAdapterId: "null"` 字符串脏值 → `CartoonStore.decodeSharedCartoon()` 按 null 识别并在 UI 侧兜底；云端可通过 `cleanupCloudDirtyCartoons()` 一次性重写。
  - 本地私有合集仍只在上传弹窗中提示 `private` 禁用，但**推荐流程不受限制**（推荐只写 `recommendations.json`，不改合集本体）。

---

## 🎨 UI / Taste 设计系统（动画城子主题）

动画城页面与详情页采用 **Premium Media · Apple TV 级电影质感** 风格，设计令牌集中在
[ui/theme/CartoonDesign.kt](app/src/main/java/com/bd/casttv/ui/theme/CartoonDesign.kt)：

| 令牌类别 | 尺度/取值 |
|---|---|
| **圆角** (Radius) | `SM=12 / MD=16 / LG=20 / XL=24` |
| **主色** | 单一琥珀金 `ACCENT = #F5C451`；语义色 `SUCCESS/WARN/INFO` 仅做胶囊徽 |
| **底板** | 三档冷灰 `SURFACE_0(#0C1020) / 1(#121626) / 2(#1C223C)` |
| **文字** | `PRIMARY / SECONDARY / MUTED` 三档，与底板对比度均超 WCAG AAA |
| **Typography** | `DISPLAY=32 / TITLE_LG=24 / TITLE_MD=18 / TITLE_SM=15 / BODY=13 / BADGE=12 / STATUS=11` |
| **材质** | 液态玻璃 `liquidGlassDrawable()`（LayerDrawable 叠加渐变底 + 2px 内高光 + 内阴影 + 软描边） |
| **焦点态** | `liquidGlassToFocused()` → 2px 琥珀描边 + 高光加亮 + `FocusFxHelper` 光边缩放 |
| **徽章** | 全圆角胶囊 `capsuleBadge()`，三态语义：更新中 / 更新至第N集 / 全N集已完结 / 待解析 |

列表页与详情页要点：
- **CartoonCityPage**：4 列网格（`CartoonItemDecoration` 对称间距）、无底板浮层 Loading（琥珀圆环 + 胶囊状态徽）、管理卡使用琥珀强调卡。
- **CartoonDetailPage**：头图液态玻璃 + 左下角语义徽章 → eyebrow 小字 + DISPLAY 标题 + 元信息行 → 简介 4 行折叠/展开 → 琥珀主 CTA + 胶囊 spinner 状态行 → 播放源 Tab / 选集胶囊（8 列、三态切换、点击记录本源 lastPlayedIndex）。

---

## 🕹 TV 遥控器交互约定（TV 端第一公民）

- **左右键**：只做焦点移动，`onKey` 返回 `false`，永不绑定业务逻辑。
- **OK / Enter**：唯一触发操作的按键。
- **上下键**：列表导航；末项再下按触发 `BoundaryFocusHandler` 边界抖动（不可切换焦点）。
- **BACK 两级逻辑**：当前页内有子焦点 → 先回到 Page 根并显示 BottomIndicatorBar；根已获焦 → 再 BACK 回首页根节点；首页根再 BACK 走退出确认。
- **PlayerActivity** 退出统一回 `NewMainActivity`（`FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP | NEW_TASK`），绝不回老 `MainActivity`。
- **按钮三态颜色、收藏菜单三态、Dock 管理按钮、侧边栏图标按钮** 等视觉规则见 `AGENT.md` 顶部，已全面收紧为「只有焦点换边框、只有选中换字色」。

---

## 📋 开发者必读清单

> 详见项目根 `conventions.md`（派发任务规范）与 `AGENT.md`（Agent 长期生效约束）。
> 以下是最常踩坑的 TOP 5：

1. **RecyclerView 刷新必须 DiffUtil**，严禁 `notifyDataSetChanged()`；选中态切换只用 `notifyItemChanged(pos)`。
2. **所有** `findViewById / getString / list[pos] / AlertDialog.show / runOnUiThread` 前必判空或判 `isFinishing||isDestroyed`，网络请求必须超时 + Toast。
3. **HTTP 请求 UA 固定**：`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36`。
4. **JSON 适配器选择器基于 Jsoup，CSS Selector 全量支持**（后代/子/伪类/属性前缀），不要再写正则 HTML 解析。
5. **禁止引入 Material**，本项目 TV UI 为自定义深色+琥珀主题体系，一切 Drawable / 颜色均从 `ThemeManager` / `CartoonDesign` / `res/drawable/bg_*.xml` 取。

---

## 🚢 CI / 发布

`.github/workflows/build-and-release.yml` 会在 push tag 时：
1. 使用 Temurin 17 + Gradle 8.9 跑 `assembleRelease`；
2. 上传 `casttv-receiver-v<ver>-release.apk` 作为 GitHub Release 附件；
3. 同时拷贝产物到 `builds/<ver>/` 保留版本快照。

本地发布走同样的 Gradle 命令，版本号由 `bumpVersion` 自动维护，无须手工。

---

## 📮 FAQ

**Q: 首次启动没看到动画城入口？**
A: 进入「更多功能」页面，卡片网格第一屏即有「动画城」，首次进入会自动从 Gitee `shared_data/cartoons/_index.json` 拉取，失败会显示琥珀胶囊「加载失败」+ 具体错误，空态为琥珀圆环 loading。

**Q: 无法被 AirPlay / DLNA 搜到？**
A: 进入「更多功能 → 网络诊断」面板，节点按顺序检查 SSDP M-SEARCH / NOTIFY / description.xml / SCPD / SOAP Action / SetAVTransportURI，确认局域网 IP 不在 VPN / 多网卡；如有多网卡需在设置里指定 DLNA 绑定网卡。

**Q: 网页解析源只能翻第一页？**
A: 规则文档见 `app/src/main/assets/json_adapter_spec.md` 3.2 节下一页寻址；App 内已在 `WebParseListExtractor` 对 AI 生成的「下一页选择器为空」做兜底：`当前URL末尾数字` +1、`?page=参数` +1、列表最后一项 `nextL` 按钮相对链接三种顺序探测。

---

<div align="center">
  <sub>Build with ❤ for the family TV box. · 琥珀金 · 液态玻璃 · Premium Media™</sub>
</div>
