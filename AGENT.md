# AGENT.md

- 禁止引入 Material。
- 每次打包版本号必须递增：`versionCode` 在当前基础上 +1，`versionName` 使用三段式版本号，末位固定两位数字并按两位数字步进（例如 `1.2.01` → `1.2.02`）；出包后默认不自动同步 Gitee，只有用户明确要求推送/同步 Gitee 时才执行。
- 代码修改完成后的 Kotlin 编译验证命令必须使用 `/opt/gradle-8.9/bin/gradle compileDebugKotlin --offline`。
- 输出前必须自查修改文件、构建结果、APK 路径与版本号。
- GlobalTopStatusBar 顶部状态栏的在线态图标颜色必须使用白色；离线态保持灰色。
- 预置/批量/焦点规则以用户最新纠正为准。
- 批量悬浮菜单浮层展开时按返回键：必须在 `super.dispatchKeyEvent(ev)` **之前**最高优先级消费 BACK（根因：若 super 先执行，系统会把 BACK 当"返回上一层"，将焦点回收到 Dock/主页）。浮层展开时对 BACK 的 `ACTION_DOWN` 与 `ACTION_UP` 都 `return true`（绝不调用 super、不上抛父容器/系统），仅在 `ACTION_DOWN` 执行「关闭浮层 + 落焦」，配对的 `ACTION_UP`（及关闭后可能到来的重复 DOWN）凭标记吞掉。落焦目标为 `favoritesGrid` 第一个可见 ViewHolder 内首个可聚焦子 View（复用 `findFirstFocusable`），采用 `post{}` → `postDelayed(100L)` → 兜底 `btnCollectionSelect.requestFocus()` 三级重试。浮层内 item 的 `OnKeyListener` BACK 分支统一收敛到同一关闭+落焦方法。
- 手机端 HTTP 页面（`HtmlPages.kt`）的云同步 Tab 必须与 TV 端 `CloudSyncDialog` 语义对齐，且需在对应 HTTP Server（`PhoneHubServer`）注册支撑 API：上传展示全部本地合集（private 置灰不可上传+点击提示、shared 可勾选、每条🔒/🔓切换本次加锁）、下载预置合集独立分组（可直接勾选无需解锁）+非预置仅展示 shared+有锁项一次密码验证解锁本页全部+超上限（预置5+maxNonPresetDownload）拦截并提示「已达下载上限（x/x）」；密码明文经 LAN 提交、服务端用 `PasswordUtil` 加盐 SHA-256，Salt 不外泄；HTTP 请求处理保持在工作线程。
- 手机端 HTTP 页面内联 JS 若经 `loadTab` 的 `(0,eval)` 二次执行（AJAX 切 Tab），顶层声明只能用 `var`/`function`（禁止顶层 `let`/`const`，避免重复声明报错）；优先用字符串拼接而非模板字符串，规避 Kotlin `${...}` 转义；改动后用 `node --check` 核验内联 JS 语法。

- 播放器快捷菜单「收藏当前内容」文本不得带星星前缀；「收藏当前内容」与「稍后播放」列表项三态样式必须保持：仅焦点=暖黄边框+白色图标/字体，仅选中=白色边框+暖黄图标/字体，选中+焦点=暖黄边框+暖黄图标/字体；切换焦点不得自动点击/选中/播放，必须按 OK 才触发对应操作。

- 设置页新增二级入口（例如 Dock 管理）时，必须在设置 Tab 内实现二级页/子页面，并完善焦点导航：从设置页 OK 进入二级页；二级页内 LEFT/BACK 先返回设置页锚点入口，再 LEFT 回一级 Dock；避免焦点穿透或丢失。
- 收藏页合集列表与视频卡片列表需做焦点切换频控：视频卡片方向键第一次按下后开启 600 毫秒固定窗口（期间新按键不重新计时），窗口内多次按键只记录最后一次方向且不立即切换，窗口结束后仅按最后一次方向触发一次焦点切换；焦点切换到最底部卡片时继续按下键必须触发边界抖动拦截且不切换焦点；焦点落在合集列表时按右键应默认落到第一个视频卡片的播放按钮。

- 网络诊断与帮助页面的按钮焦点态必须完整展示，按钮所在根容器、卡片、工具栏、滚动容器及按钮行需为焦点缩放/加粗边框预留安全边距，并设置 `clipChildren=false`、`clipToPadding=false`，避免暖黄焦点边框或放大效果被截断。

- 首页自定义 Tab 预览区播放器应无边框、无圆角，水平方向充满预览区域且不留左右边距；周边标题/信息文案可保留内边距。
- 首页自定义 Tab 频道列表右侧预览区加载播放源时，若连接/加载超过 15 秒仍未就绪或播放器报错，必须自动切换播放当前频道的下一个可用源；如果当前频道只有一个源则保留原有失败提示，不要切换到下一个频道。

- 首页自定义 Tab 内容页资源列表宽度应收窄到仅容纳约 13 个字符，避免挤占右侧播放器区域。

- 首页自定义 Tab 内容页资源标题超过 13 个字符时，不截断省略；应在资源列表项内开启横向跑马灯滚动播放完整标题。
- TV App 通用按钮样式必须区分两类：只有图标的按钮默认态=浅灰图标+浅灰整圆实线边框，选中态=暖黄图标+浅灰整圆边框，焦点态=浅灰图标+暖黄整圆实线边框，选中+焦点态=暖黄图标+暖黄整圆实线边框；图标+文字/只有文字的按钮默认态=浅灰字体+浅灰实线边框，选中态=暖黄字体+浅灰实线边框，焦点态=浅灰字体+暖黄实线边框，选中+焦点态=暖黄字体+暖黄实线边框。
- TV App 全局焦点态不得叠加暖黄前景/foreground 半透明遮罩；全局 FocusFx 仅保留轻微缩放、translationZ 浮起/发光等物理层次效果，页面自身背景、边框与三态颜色由各页面样式自行控制。
- 侧边栏 Tab 管理弹窗中的「＋新建 Tab」按钮样式必须遵循 TV App 通用图标+文字/只有文字按钮样式；不得出现虚线边框、焦点态字体变暖黄或选中态丢失浅灰边框。
- 侧边栏 Tab 管理弹窗列表项右侧的「编辑 / 删除」图标按钮样式必须遵循 TV App 通用纯图标按钮样式；不得因选中态或默认态移除浅灰整圆边框。
- 侧边栏管理弹窗列表项中的资源数量展示必须并入“绑定合集”副标题后，以“ · ”分隔；不得在列表项右侧再单独展示数量徽标。

- 排查抖音/DLNA 搜不到设备时，必须优先补齐并查看网络诊断关键节点：SSDP M-SEARCH、SSDP 200 OK、NOTIFY alive/byebye、description.xml GET、SCPD GET、SOAP Action、SOAP 响应状态、SetAVTransportURI、播放 URL 解析、服务绑定网卡/IP。
- 强化 DLNA 兜底命中率时，应保持标准 MediaRenderer 能力：稳定 UUID/USN、正确 LOCATION 局域网 IP、覆盖 ssdp:all/rootdevice/MediaRenderer/AVTransport/RenderingControl/ConnectionManager 响应、GetProtocolInfo 声明常见视频 MIME。

- 当用户明确表示“别急”“先讨论”“先别动手”等暂停/讨论意图时，必须停止代码实现与文件修改，仅做方案沟通，等待用户明确确认后再继续落地。

- 在 TV App 首页自定义 Tab 中引入频道聚合时，频道概念仅作用于自定义 Tab 展示层，不改导入直播源链路；新增侧边栏 Tab 绑定资源合集后，应在该 Tab 内按资源聚合产出频道，重复源作为备选源保留，允许手动配置主源；异常源仅打标不删除、不参与播放；无可用源频道仍需展示；第一期不做源检测，第二期再做无声/卡顿/健康检测。

- 在 TV App 首页自定义 Tab 频道功能落地实现中：频道由绑定合集资源按标题归一实时聚合（`ChannelNormalizer` + `TabChannelAggregator`），仅保守归一大小写/全半角/空格标点/清晰度后缀，不做激进中文别名合并；同一 URL 完全重复只保留一条，其余作为备源保留。用户配置（主源指定、异常打标）通过 `TabChannelConfigStore` 按 `tabId+channelKey` 持久化，频道每次实时聚合后套用配置。主源优先用用户指定，否则取第一个非异常且有地址的源；异常源仅打标不删除、不参与预览/播放；无可用源频道仍展示（置灰、点击 Toast 提示）。自定义 Tab 左侧展示频道列表（`CustomDockChannelAdapter`），右侧预览主源；菜单键或「源管理」按钮打开 `ChannelSourceManageDialog`（深色主题 AlertDialog）进行设为主源/标记异常/恢复可用。第一期不做源检测（无声/卡顿/健康评分/复检），留待第二期。

- 在 TV App 首页自定义 Tab 频道源健康（第二期）实现中：源评分必须采用「滑动/累加式」最终分，写入 `SourceHealthStore` 时由 `SourceHealthScorer.blendedScore` 融合本次快照分与累计成功率（最终分 = 当次分 × 0.6 + 历史成功率 × 0.4，累计样本为空时直接用当次分），`successCount/failCount` 为累计计数，不得只用单次快照分排序。手动打标为异常的源默认不被后台检测自动翻转恢复（尊重用户主观选择）；当后台检测判定该源已恢复可播放（HEALTHY/DEGRADED）时，必须在 `ChannelSourceManageDialog` 的源行与恢复操作项上明确提示「检测正常，可恢复」，由用户手动一键恢复，严禁自动恢复。

- 架构重构相关新代码放在 `com.bd.casttv.ui.framework` 包下；阶段 1~5 都不得修改老 `MainActivity` 现有业务代码，改动通过新建 `NewMainActivity` 承载；业务迁移一律等新架构页面跑通后逐步进行。

- SettingsPage 内新增设置项时，必须先在 `Settings.kt` 添加对应的 SharedPreferences key，再在 SettingsPage 中挂 UI，且默认值必须写在 Settings.kt 中。

- 在 TV App 新框架首页/多页容器中，屏幕左右两侧的翻页按钮（‹ ›）必须默认常驻显示（仅受当前页索引边界约束：首页隐藏左箭头、末页隐藏右箭头），不得再挂靠"移动模式"或任何形式的用户开关；首页/任何页面均不得再放置"切换到手机端页面/移动端开关/Switch"等入口按钮，用户无需手动开启即可看到侧边翻页按钮。
- 在 TV App 新框架多页容器中，遥控器 BACK 必须遵循两级逻辑：当前页内容区任意子元素/导航获焦时，第一次 BACK 只回到当前页根节点并展示 BottomIndicatorBar；当前页根节点已获焦时，再次 BACK 才跳转到首页根节点（首页根节点继续 BACK 才走退出确认）。

- PlayerActivity 退出（BACK、流结束、镜像停止等任何 finish 路径）后必须回到新版主页 `NewMainActivity`，不得回到旧版 `MainActivity`。统一在 `PlayerActivity.finish()` 中先 `startActivity(NewMainActivity)`（FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP | NEW_TASK）再 `super.finish()`。
- 在 TV App 新框架自定义 Tab 页面设计/实现中，自定义 Tab 是 `PageContainer` 内的独立 Page，通过屏幕左右两侧常驻翻页箭头与底部 `BottomIndicatorBar` 切换进入，不得再画成或实现为左侧 Dock/侧边栏入口；页面内部仍采用左频道/资源列表主焦点 + 右侧 16:9 预览播放器区，左侧 `onFocus` 防抖刷新右侧预览，OK 全屏播放，非全屏 BACK 遵循新框架 Page 根焦点/首页根节点两级逻辑。

- 在 TV App 新框架多页容器中优化屏幕左右两侧翻页按钮时：按钮需更贴近屏幕边缘；默认态尺寸可按用户要求整体放大，焦点态呼吸放大比例保持既有倍率；根节点/底部指示栏展示时如需侧边光效，应绘制屏幕左右边缘的侧边框光晕，不要误做成按钮周围光晕。

- 抖音投屏页设备名称组列表中，当前组的「使用中」标签必须展示在设备组名称上方；抖音投屏播放记录默认生成阈值为播放 3 秒后落库。

- 收藏页面合集列表中，合集名称右侧资源数必须使用括号展示；合集名称超过 8 个字符时截断为前 8 个字符并追加 `...`。

- 设置页所有自定义弹窗按钮必须复用统一 `dialogButton(...)` 或等效工厂接入全局焦点态；默认态为浅灰文字、浅灰边框、深灰半透明背景，焦点态仅边框变暖黄并叠加 `FocusFxHelper`，不得在默认态使用暖黄文字/暖黄背景/暖黄边框。

- 抖音投屏历史记录必须严格区分投屏来源：只有 SetAVTransportURI 的 URI、DIDL 元数据、creator/sourceHint 或 User-Agent 命中抖音特征（如 douyin、抖音、aweme、amemv、snssdk1128、iesdouyin、douyinvod、douyinpic、douyinstatic）时，才允许写入 DouyinCastHistoryStore；抖音投屏页不得展示非抖音来源的当前播放或历史记录。

- 设置页主题风格设置弹窗打开后，默认焦点必须落在「预置主题配色」下拉项；壁纸设置弹窗打开后，默认焦点必须落在「全屏壁纸开关」Switch 所在行，不得默认落到应用/确认按钮；屏保设置弹窗与页面内容区域背景设置弹窗必须遵循深色主题、暖黄强调色、圆形贴纸、统一 dialogButton 按钮样式。
- WebParseHistoryDialog 等新框架自定义确认弹窗必须接入项目主题风格：使用 `Theme_CastTV_Dialog`/`Theme.CastTV.Dialog` 暗色弹窗主题，背景使用 `ThemeManager.currentPalette(context).dialogTitleGradient` 或项目 `bg_dialog_crayon_panel` 渐变方案，按钮复用统一 `dialogButton` 样式工厂，禁止出现系统默认白底黑字 AlertDialog。

- 抖音投屏页面历史播放记录卡片中，焦点在「删除」按钮时按右键应回到页面根节点，不再做边界抖动拦截。

- TV App 通用按钮三态样式中，焦点态与选中+焦点态边框必须加粗；覆盖纯图标按钮、图标+文字按钮与纯文字按钮，非焦点态保持原有细边框。
- 稍后播放队列（PlayQueue）冷启动时：若落盘状态残留 `PLAYING`，必须归一为 `PENDING`（而非 `FINISHED`）。主页侧边栏与启动弹窗的默认焦点永远落在第 1 条数据；启动弹窗点击「继续播放」必须从队列中第 1 条 `PENDING` 开始播放。
- 在现有页面新增功能时，若用户明确要求“不动原有功能/交互”，必须采用最小侵入改动：保持原有逻辑与交互不变，仅新增必要入口与视图。

- 首页自定义 Tab 右侧预览区资源健康预检范围必须限制为“当前聚焦频道 + 紧随其后的 3 个频道”，一共最多 4 个频道；不得按首屏可见频道或全量频道追加检测，避免预览播放器与健康检测抢资源导致卡顿/闪退。
- 首页自定义 Tab 页面预览播放器需增加停留阈值与离页让渡：进入页面后需先停留至少 3 秒才允许启播；离开页面切页时必须先执行 `player.pause()`，再延迟约 100~200ms 执行页面切换；若该页面已满足 3 秒停留阈值，返回页面时应恢复预览播放，减少切页动画与播放器解码/渲染抢占主线程/GPU 造成的卡顿。

- 收藏页从播放器返回时，不得自动刷新收藏页面；必须后台检查当前选中合集的视频数据签名，如发现本地变更，用深色主题 + 暖黄强调色弹窗提示「本地数据有更新」。用户点「刷新」后才刷新当前合集视频内容：若可局部刷新则优先恢复到上次视频卡片焦点，否则回到当前选中合集 Tab；用户点「取消」不得刷新页面，焦点保持不变。

- casttv-receiver 收藏页顶部「推荐」功能仅将本地资源信息写入云端 `recommendations.json`，不修改云端合集列表数据；因此 private / shared 合集都必须允许正常进入推荐流程，不得再提示“私有合集不支持推荐到云端”，也不得通过置灰、降 alpha 或禁用来限制推荐。该规则只作用于「推荐」功能，不影响「上传到云端共享合集」弹窗中 private 合集仍不可勾选的规则。

- casttv-receiver 合集 `type` 修改权限规则：仅管理员或该合集创建者本人可修改。实现上需生成并封装稳定的 `creatorId`（优先基于 Android `ANDROID_ID`，允许极端场景回退本地 UUID），并在合集元数据中持久化 `creatorId`；旧合集若无 `creatorId`，默认仅管理员可改。

- casttv-receiver 推荐功能中，推荐人唯一标识必须使用 `creatorId`，不得再用设备名称或旧 deviceId 作为判重主键；App 启动「收到新推荐」弹窗必须按合集聚合展示，同一合集被多个不同 creatorId 推荐时，标题展示为“第一位推荐用户的设备名称 等X名用户推荐”，不同合集仍按合集分别展示。

- casttv-receiver 设置页「推荐可见」开关每次开启时，必须重新读取当前 `settings.deviceName`，并在写入云端 `visible_users.json` 的 `visible: true` 记录时同步覆盖 `deviceName`，不得只在首次创建记录时写入设备名称。
