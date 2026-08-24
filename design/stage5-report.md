# CastTV 架构重构 · 阶段 5 验收报告

> 版本：v1.1.145 / versionCode 246  
> 产物：`casttv-receiver-v1.1.145.apk`  
> 生成时间：2026-08-05  

## 1. 各阶段总结

### 阶段 1：新架构骨架
- 新增 `BasePage`：统一页面生命周期与根焦点能力。
- 新增 `PageContainer`：承载单 Activity 多页面切换。
- 新增 `BoundaryFocusHandler`：封装边界抖动、回根焦点等遥控器焦点能力。
- 新增 `SettingsChangeBus`：用于设置项变更后通知新架构页面刷新。

### 阶段 2：全局层与底部指示栏
- 新增 `WallpaperLayer` / `WallpaperManager`：全局壁纸底层、遮罩、模糊、启停控制。
- 新增 `BottomIndicatorBar`：悬浮胶囊指示栏、页面索引与缩放能力。
- 新增 `GlobalTopStatusBar`：顶部状态栏，展示设备名、时间、DLNA/AirPlay、网络和投屏状态。

### 阶段 3：8 个业务页面迁移到新架构
- 新增 `HomePage`
- 新增 `FavoritesPage`
- 新增 `CustomTabPage`
- 新增 `PhoneHubPage`
- 新增 `HistoryPage`
- 新增 `DiagnosticsPage`
- 新增 `HelpPage`
- 新增 `SettingsPage`

### 阶段 4：NewMainActivity 组装与设置联动
- 新增/完善 `NewMainActivity`，完成 WallpaperLayer、PageContainer、GlobalTopStatusBar、BottomIndicatorBar 的层叠组装。
- `pageOrder` / `disabledPageIds` 通过 `SettingsChangeBus` 与 `onResume()` 触发重建页面列表。
- `indicatorScale`、壁纸配置变更后可刷新。
- 老 `MainActivity` 未修改，仍作为 launcher 保留回退路径。

### 阶段 5：全量回归与出包
- 补齐 `GlobalTopStatusBar` 关键接线：
  - 网络状态改为 `ConnectivityManager.NetworkCallback` 持续监听。
  - DLNA / AirPlay 通过 `DlnaRendererService.isRunning` / `AirPlayService.isRunning` 读取状态。
  - 投屏红点接入 `CastReceiverService.isRunning`。
- 为 `CastReceiverService` 增加静态 `@JvmStatic var isRunning`，并在 `onCreate/onDestroy` 中赋值。
- 更新版本号到 v1.1.145 / versionCode 246。
- 更新 `version.json` releaseNote 与 apkUrl。
- 更新迁移清单核心已完成项。

## 2. 静态代码回归

执行 TODO 检查后，遗留 TODO 均非本次出包阻塞项：

| 文件 | 遗留内容 | 阻塞结论 |
|---|---|---|
| `app/src/main/java/com/bd/casttv/ui/framework/pages/PhoneHubPage.kt` | PhoneHubServer 暂未暴露客户端 IP 列表，已连接设备列表展示为空 | 不阻塞，业务接口后续补齐 |
| `app/src/main/java/com/github/serezhka/jap2lib/OmgHax.java` | 第三方/协议栈内部注释 | 不阻塞 |
| `app/src/main/java/com/github/serezhka/jap2server/AirPlayServer.java` | 第三方/协议栈内部注释 | 不阻塞 |
| `app/src/main/java/com/github/serezhka/jap2server/internal/handler/mirroring/MirroringHandler.java` | 第三方/协议栈内部注释 | 不阻塞 |
| `app/src/main/java/com/github/serezhka/jap2server/internal/handler/session/Session.java` | 第三方/协议栈内部注释 | 不阻塞 |

## 3. 编译结果

### Debug 编译

命令：

```bash
cd casttv-receiver && /opt/gradle-8.9/bin/gradle compileDebugKotlin --offline
```

结果：`BUILD SUCCESSFUL in 16s`

### Release 打包

命令：

```bash
cd casttv-receiver && /opt/gradle-8.9/bin/gradle assembleRelease --offline
```

结果：`BUILD SUCCESSFUL in 46s`

> 本阶段未执行 `syncReleaseToGitee`，符合“默认不同步 Gitee”的约束。

## 4. Release APK

- APK 路径：`casttv-receiver/casttv-receiver-v1.1.145.apk`
- APK 大小：18,501,662 bytes（约 17.64 MiB）

## 5. 迁移清单完成度

- 当前勾选完成度：17 / 105 = 16.2%
- 已标 ✅ 的核心项：全局顶部状态栏、全局壁纸、页面排序、指示栏缩放、PageContainer/BasePage/BottomIndicatorBar/GlobalTopStatusBar/WallpaperLayer/BoundaryFocusHandler。
- 未完成或未完全接线的业务细项继续保留 `☐`，避免误标。

## 6. 已知遗留 TODO / 风险

1. `PhoneHubPage` 的已连接设备列表仍依赖 `PhoneHubServer` 暴露客户端连接信息，当前保留空列表提示。
2. 新架构 8 个页面已接入，但部分深层业务操作仍待逐项与老 MainActivity 细节对齐。
3. `GlobalTopStatusBar` 当前投屏红点表示 `CastReceiverService` 常驻服务运行态；如果后续需要“正在播放/正在接收媒体流”的更精确语义，建议在 DLNA/AirPlay 实际媒体接收入口增加独立 active flag。

## 7. 建议下一步

1. 将 v1.1.145 作为架构重构测试包，在用户实机验证至少 1 周。
2. 验证重点：
   - 老 launcher 流程是否完全不受影响。
   - 手动进入 NewMainActivity 后页面切换、焦点、指示栏、壁纸、顶部状态栏是否稳定。
   - 开机自启、DLNA、AirPlay、网络变化时顶部状态是否符合预期。
3. 实机验证 1 周无问题后，再将 `NewMainActivity` 设为 launcher，并规划下线老 `MainActivity`。

## 8. 手动进入新架构 Demo

```bash
adb shell am start -n com.bd.casttv/.ui.framework.NewMainActivity
```
