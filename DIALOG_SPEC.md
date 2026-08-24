# CastTV 弹窗设计规范 V1

## 一、AlertDialog 容器配置（必须）

```kotlin
// ✅ 正确：始终传入深色主题
AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)

// ✅ 大型弹窗：设置弹窗尺寸
dialog.window?.apply {
    setBackgroundDrawableResource(android.R.color.transparent)
    setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
}
```

`Theme.CastTV.Dialog` 核心配置：
- `android:windowBackground` → 透明
- `android:colorBackground` → `#070809`（近黑）
- `colorAccent` → `@color/crayon_yellow`（`#F6C445`）
- 文字色 → `#EEFFFFFF`（主） / `#B3FFFFFF`（副）

## 二、面板背景

| 类型 | Drawable | 说明 |
|------|----------|------|
| 弹窗主面板 | `bg_dialog_crayon_panel` | 深色渐变 + 暖黄描边 2dp，圆角 26dp |
| 标题牌 | `bg_dialog_crayon_header` | 橙→黄→蓝横向渐变，圆角 18dp |
| 输入框 | `bg_dialog_input` | 深色纯色 `#181B22` + 暖黄描边 1dp，圆角 14dp |
| 可点击标签/徽章（默认） | `bg_dialog_badge` | 暖黄半透明底 + 暖黄描边 |
| 可点击标签/徽章（三态） | `bg_dialog_badge_focus` | 默认/焦点/按下三态，焦点暖黄描边 2dp |
| 通用可聚焦选项行 | `bg_dialog_focus_item` | 默认白色虚线描边，焦点暖黄实线 2dp，圆角 18dp |
| 虚线按钮 | `bg_dialog_dashed_button` | 焦点/按下暖黄实线描边 2dp，默认无边框，圆角 8dp |

## 三、标题行

```xml
<TextView
    android:background="@drawable/bg_dialog_crayon_header"
    android:paddingLeft="16~18dp"
    android:paddingTop="8~10dp"
    android:paddingRight="16~18dp"
    android:paddingBottom="8~10dp"
    android:textColor="#101217"
    android:textSize="18~22sp"
    android:textStyle="bold" />
```

- 右侧如有副标题/计数，用 `@color/crayon_yellow`（`#F6C445`），15sp，bold
- 标题与内容区间距：14~16dp

## 四、底部操作按钮

**标准型（`@style/BatchBottomButton`）**

| 状态 | 背景 | 文字色 |
|------|------|--------|
| 默认 | 暗底 `#22FFFFFF` + 白色描边 1dp | `#EEFFFFFF`（近白） |
| 焦点 | 暖黄半透明底 `#33F6C445` + 暖黄描边 2dp | `@color/crayon_yellow` |
| 按下 | 橙色半透明底 `#33F2913D` + 橙色描边 2dp | `@color/crayon_yellow` |
| 选中 | — | `@color/crayon_yellow` |
| 禁用 | 代码侧 `alpha = 0.4f` | — |

样式参数：
- 宽：`wrap_content`，最小宽度 88dp
- 内边距：左右 18dp，上下 10dp
- 按钮间距：`marginLeft/Right = 6dp`
- 字号：15sp，bold
- 圆角：18dp

**确认型（`@style/TvButton`）**

适用于「取消/确定」两按钮行：
- 内边距：左右 28dp，上下 12dp
- 字号：18sp
- 背景：`@drawable/bg_tv_button`
- 横排排列，右对齐，间距 `marginStart = 14dp`

**仅图标型按钮**

| 状态 | 背景/边框 | 图标色 |
|------|----------|--------|
| 默认 | 浅灰色正圆实线边框 1dp（`#BFC3CC`） | 灰色（`@color/standard_action_gray` `#BFC3CC`） |
| 焦点 | 暖黄正圆实线边框 2dp（`@color/crayon_yellow`） | 灰色 |
| 选中 | 浅灰正圆实线边框 1dp | 暖黄（`@color/crayon_yellow`） |
| 选中+焦点 | 暖黄正圆实线边框 2dp | 暖黄 |
| 按下 | 橙色正圆实线边框 2dp（`@color/crayon_orange`） | 暖黄 |

样式参数：
- 形状：正圆（`oval` 或固定宽高相等 + `cornerRadius = 50%`）
- 边框：实线，默认 1dp 浅灰，焦点/选中+焦点 2dp 暖黄

## 五、内容区元素

| 元素 | 规格 |
|------|------|
| 说明文字 | 15sp，`@color/text_secondary`（`#98A0AC`），marginTop 16dp |
| 输入框 | `bg_dialog_input`，17sp，`@color/text_primary`，hint `@color/text_hint` |
| 选项行（可聚焦列表） | `bg_dialog_focus_item`，16sp，白色文字，焦点暖黄实线边框 |
| 空态提示 | 16sp，`@color/text_secondary`，居中 |

## 六、面板尺寸

| 类型 | 宽 | 高 |
|------|----|----|
| 全屏型（批量操作、云同步） | 92% 屏宽 | 90% 屏高 |
| 标准型（输入框、列表选择） | 560dp | wrap_content |
| 紧凑型（简单确认/提示） | 460dp | wrap_content |

## 七、面板内边距

| 弹窗类型 | padding |
|----------|---------|
| 全屏型 | 左右 28dp，上 20dp，下 18dp |
| 标准/紧凑型 | 四周 26dp |

## 八、颜色速查表

| Token | 色值 | 用途 |
|-------|------|------|
| `crayon_yellow` | `#F6C445` | 主强调色、焦点描边、选中文字 |
| `crayon_orange` | `#F2913D` | 按下态描边、标题渐变起点 |
| `crayon_blue` | `#3E9BE8` | 标题渐变终点 |
| `text_primary` | `#E8EAED` | 主文字色 |
| `text_secondary` | `#98A0AC` | 副文字、说明文字 |
| `text_hint` | `#5E6773` | 输入框占位符 |
| `bg_dark` | `#070809` | 对话框背景底色 |
| `error` / 危险操作 | `#C4463E` | 删除类按钮文字色 |
