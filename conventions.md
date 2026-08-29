# casttv-receiver 项目规范（派发任务时附上）

## 工作流程

* 修改前不需要评估，直接动手改代码

* 改后必须评估影响面：①受影响的类/方法/文件清单 ②对外行为变化点 ③内部状态/存储/接口变化点 ④跨页面/跨模块耦合点 ⑤DPAD 焦点链影响 ⑥风险等级+建议冒烟用例

* 改后仅告知「代码验证结果：通过 / 有风险 + 简要说明」，不输出详细 review 报告

* 每次构建 APK 后，必须标明 APK 完整路径，使用文件树形式展示

## 构建约束 (与 AGENT.md §版本号对齐)

* **每次执行 assembleDebug / assembleRelease 前必须自动递增版本号**：由 app/build.gradle.kts 的 `bumpVersion` 任务（在 preBuild 之前执行）完成：
  - `versionCode = current + 1`
  - `versionName = 1.2.XXX` 末位十进制 +1（例 `1.2.164` → `1.2.165`），不足三位时按三位补（AGENT.md 原约定 "末位固定两位" 已被工程实际三位末位覆盖，以工程实际为准）
  - 递增结果直接写回 `app/build.gradle.kts` 的 `defaultConfig { versionCode = ... / versionName = "..." }` 字面量，避免 Agent 手工改漏或记错
* **禁止人工只改 versionCode 或只改 versionName**，两者必须由脚本联动，保持 `versionCode 比 versionName 末位大固定基数（当前=1.2.XXX 对应 versionCode= 275 + XXX）`，漂移后继续以脚本自增保持即可
* **APK 输出命名**：
  - Gradle 标准目录：`app/build/outputs/apk/<buildType>/casttv-receiver-v<versionName>-<buildType>.apk`
  - 工程根镜像目录（便于取用）：`builds/<versionName>/casttv-receiver-v<versionName>-<buildType>.apk`
  - 交付给用户时同时以 `文件树形式` 展示两个路径，注明 `versionCode / versionName / 构建类型 / 签名信息`

## 工程规范

* RecyclerView 局部刷新用 DiffUtil，禁止 notifyDataSetChanged()

* 选中态切换用 notifyItemChanged(pos)

* UI 背景色用 BasePage.contentPanelBg() 读主题渐变，不硬编码

* 集剧列表用 NaturalSorter 按 displayName 自然排序

* Dialog 圆形贴纸用 ClippedImageView setCircle(true)

* 2选1胶囊组切换用 (cur + dir + 2) % 2，禁止 coerceIn(0,1)

* HTTP 请求固定 User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36

* JSON 适配器选择器现已基于 Jsoup，支持完整标准 CSS Selector（后代/子/伪类/属性前缀等）

## TV 遥控器交互

* 左/右键：仅焦点移动，禁止绑定业务逻辑，onKey 返回 false

* OK/确认键：触发业务操作

* 上/下键：列表导航，边界抖动拦截

* 弹窗焦点：默认聚焦主操作按钮，关闭后恢复调用方

## 错误处理

* 所有 findViewById/getString/json 解析结果使用前判空

* list\[0]/list\[position] 前判 isEmpty()/indices

* runOnUiThread 前判 isFinishing||isDestroyed

* 网络请求：超时+异常catch+Toast提示，不静默吞掉

* AlertDialog.show() 前判 isFinishing||isDestroyed，try-catch 兜底

## 硬性约束

* APK versionName 格式：1.2.XXX

* UI 焦点交互必须维护 TV 视觉状态（focused/selected/normal）

* 从处理焦点恢复的页面启动 PlayerActivity 时传 EXTRA\_RETURN\_SKIP\_PAGE\_RELOAD=true

* 列表项只有 focused 和 default 状态，selected 不加额外边框

## 改后检查清单

* TV 交互：上下左右+OK 各路径

* 手机交互：触摸+滑动

* 空数据：列表空/网络失败/解析空

* 边界：首项/末项/单项/大量数据

* 生命周期：弹窗关闭/页面返回/Activity销毁

* 跨模块：排序/播放队列/收藏等共享状态

<br />