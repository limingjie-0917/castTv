package com.bd.casttv.webparse

import android.content.Context

object JsonAdapterSpecBuilder {
    fun build(context: Context, fallbackUrl: String = "", pageKind: ParsePageKind = ParsePageKind.DETAIL): String {
        val spec = context.applicationContext.assets
            .open("json_adapter_spec.md")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val pageKindSpec = when (pageKind) {
            ParsePageKind.LIST -> listPageSpec()
            ParsePageKind.DETAIL -> detailPageSpec()
        }
        val html = WebParseExtractor.lastParsedHtml.orEmpty()
        val url = WebParseExtractor.lastParsedUrl.orEmpty().ifBlank { fallbackUrl.trim() }
        return if (html.isNotBlank()) {
            "$pageKindSpec\n\n---\n$spec\n\n---\n目标URL：$url\n\n---\n页面源码：\n$html"
        } else {
            "$pageKindSpec\n\n---\n$spec\n\n---\n注意：未获取到页面源码，请先在 App 中解析目标 URL 后再下载此文件。"
        }
    }

    private fun listPageSpec(): String = """
        ## 影片列表页 JSON 解析规范

        > ⚠️ **重要：列表页只生成可直接执行的轻量 JSON。** 最终 JSON 必须能被 App 的 `parseWithJsonRule` 直接接受；字段名必须逐字匹配，只能使用 `type`、`titleSelector`、`detailUrlSelector`、`coverSelector`、`baseUrl`、`nextPageSelector`。禁止生成 `schemaVersion`、`meta`、`match`、`detail`、`sources`、`playResolve` 等详情页 Adapter 字段；这些字段属于详情页规则，列表页解析器不会读取。

        ### 必填字段
        - `type`: 固定值 "list"
        - `titleSelector`: CSS selector，用于提取影片标题。优先读取元素文本，其次读取 title / alt 属性
        - `detailUrlSelector`: CSS selector，用于提取详情页链接。优先读取 href，其次读取 data-href / data-url / src 属性

        ### 可选字段
        - `coverSelector`: CSS selector，用于提取封面图。优先读取 data-original / data-src / src / poster 属性；当前不支持从 style background / background-image 中提取封面
        - `baseUrl`: 相对链接补全用的基础地址。可不填，不填时使用当前列表页 URL
        - `nextPageSelector`: CSS selector，用于从列表页 HTML 中提取"下一页"链接。命中元素后按 href → data-href → data-url → src 顺序读取链接地址，基于 baseUrl 补全为绝对 URL。用户上滑到列表底部时自动加载下一页数据并追加到现有列表。如果页面有分页导航（如 `<a class="next" href="...">下一页</a>`），应填写此字段指向该 `<a>` 元素；如果页面无分页或只有单页，留空即可

        ### nextPageSelector 使用指南
        - 如果列表页 HTML 中存在明确的分页导航元素（如 `<a class="next" href="...?page=2">下一页</a>` 或 `<a class="page-next" href="...">下页</a>`），应填写 `nextPageSelector` 指向该 `<a>` 标签
        - `nextPageSelector` 必须直接命中自身带 `href` 的 `<a>` 元素，不要命中外层 `div` / `li`
        - 如果页面没有分页或只有单页内容，`nextPageSelector` 留空或不生成即可；App 会在底部显示"没有更多了"
        - 当 `nextPageSelector` 为空时，解析器也会尝试通用提取（匹配 class 含 next/page-next 或文字含"下一页"的 `<a>` 标签），但显式填写更精准

        ### 字段类型与可空规则
        - 所有字段都是字符串
        - `type`、`titleSelector`、`detailUrlSelector` 必须非空
        - `coverSelector`、`baseUrl`、`nextPageSelector` 可省略或为空字符串
        - 每条结果要求 title 与补全后的 detailUrl 非空；coverUrl 可为空，UI 会展示默认封面

        ### selector 支持范围与禁止写法
        - 当前 selector 是 App 内置轻量选择器，不是浏览器完整 CSS Selector
        - 支持：标签名（如 `a`、`img`）、单个 class（如 `a.vod-item`）、属性存在（如 `a[href]`）、属性等于（如 `a[class='vod-item']`）、属性前缀（如 `a[href^='/detail/']`）、逗号分隔多个简单 selector
        - 禁止：后代选择器（如 `div.item a[href]`）、子选择器（如 `div > a`）、ID 简写（如 `#list`）、伪类（如 `:not()`、`:nth-child()`）、属性包含 `*=`、属性后缀 `$=`
        - `detailUrlSelector` 必须直接命中自身带 `href` / `data-href` / `data-url` / `src` 的元素，不要命中外层 `li` / `div`，因为解析器不会继续向子元素查找链接

        ### 多字段对齐规则
        - `titleSelector`、`detailUrlSelector`、`coverSelector` 会分别匹配元素列表，并按 index 拼装同一条影片
        - 三组 selector 应尽量命中同一批影片卡片内同序元素，避免标题、链接、封面错位
        - 推荐让 `titleSelector` 与 `detailUrlSelector` 指向同一批影片链接元素；如果封面元素数量可能少于链接数量，允许 coverUrl 为空
        - 解析结果会按补全后的 `detailUrl` 去重，相同详情页链接只保留第一条

        ### baseUrl 与 URL 补全规则
        - detailUrl 和 coverUrl 如果是相对地址，会基于 `baseUrl` 补全
        - `baseUrl` 为空时使用当前列表页 URL
        - 当当前 URL 是搜索页、分页页、伪路径，可能导致 `../` 或无斜杠相对路径解析错误时，应显式填写站点根地址，例如 `https://example.com/`
        - `baseUrl` 必须是完整 http/https URL
        - `baseUrl` 只能写纯 URL，例如 `https://example.com/`；禁止写成 Markdown 超链接（如 `[https://example.com/](https://example.com/)`）或带说明文字。App 会尽量清洗 Markdown 超链接，但最终 JSON 仍必须优先输出纯 URL

        ### 当前能力边界
        - 支持通过 `nextPageSelector` 或通用提取获取"下一页"地址，用户上滑到列表底部时自动加载下一页数据并追加到现有列表
        - 跨页结果按 `detailUrl` 去重，避免重复条目；下一页解析为空或请求失败时底部显示对应提示
        - 不支持滚动懒加载后的 DOM；如果影片列表由 JS 运行后生成且不在原始 HTML 中，当前轻量 JSON 无法提取
        - 不支持先选列表容器 / 卡片容器再做相对提取的嵌套结构；遇到推荐区、导航区、正片区混排时，必须通过简单 selector 尽量精准地命中正片卡片元素
        - selector 匹配为空或最终没有有效条目时，App 会显示解析失败，不会自动兜底到内置列表解析

        ### JSON Schema（字段名大小写必须完全一致）
        {
          "type": "list",
          "titleSelector": "string, required",
          "detailUrlSelector": "string, required",
          "coverSelector": "string, optional",
          "baseUrl": "string, optional",
          "nextPageSelector": "string, optional"
        }

        ### 最终输出示例
        {
          "type": "list",
          "titleSelector": "a.stui-vodlist__thumb",
          "detailUrlSelector": "a.stui-vodlist__thumb",
          "coverSelector": "a.stui-vodlist__thumb",
          "baseUrl": "https://example.com/",
          "nextPageSelector": "a[class='next']"
        }

        ### 可执行性要求
        - `type` 必须固定为 `list`
        - `titleSelector` 与 `detailUrlSelector` 不能为空
        - selector 必须能直接用于 App 内置轻量 selector 解析
        - 必须在示例网页 HTML 中验证至少提取到 3 个有效条目，并确认标题、详情链接、封面顺序一致
        - detailUrl 必须能转为绝对 URL
        - 最终输出必须是纯 JSON 对象，不要 Markdown 代码围栏、注释、省略号、解释文字或尾逗号
    """.trimIndent()

    private fun detailPageSpec(): String = """
        ## 影片详情页 JSON 解析规范

        > ⚠️ **重要：详情页必须生成完整外部 Adapter JSON。** 当前 App 真实保存和执行的是 `RuleBasedAdapter` JSON，顶层必须包含 `match`、`sources`、`playResolve` 三个对象；建议同时包含 `schemaVersion`、`meta`、`detail`。`type: "detail"` 不是运行必需字段。

        ### 不可用的旧式字段
        - 不要使用顶层 `playUrlExtractor`、`playUrlPattern`、`episodeSelector`、`titleSelector`、`coverSelector` 作为最终规则字段
        - 这些字段属于旧式/简化描述，当前详情页 `RuleBasedAdapter` 不会按它们执行
        - 标题、封面、简介等信息应写入 `detail.fields`；线路和集数应写入 `sources`；真实播放地址解析应写入 `playResolve.pipeline`

        ### 必填顶层对象
        - `match`: 判断规则是否适配当前站点，建议使用域名条件 + HTML 特征条件，避免匹配过宽
        - `sources`: 提取播放线路和集数
        - `playResolve`: 把播放页 URL 或直链解析为真实 m3u8/mp4/flv/mkv 等可播放地址

        ### sources.mode 选择策略
        - `single`: 适合没有剧集列表、只有一个播放页或一个直链的详情页
        - `html_blocks`: 适合详情页 HTML 中存在播放线路和剧集列表的页面，应先用 `sourceBlocks.selectors` 缩小到播放列表容器，再用 `episodes.itemSelector` 选集数链接
        - `json_fields`: 适合页面或接口中存在 `vod_play_from`、`vod_play_url`、`play_from`、`play_url` 等结构化字段的站点
        - ⚠️ `sourceBlocks.selectors` 全部未命中时，当前实现会退回整页 HTML 扫描；因此 block selector 过宽或未命中都可能误抓导航、推荐、广告链接

        ### playResolve 解析策略
        - `episodes.url` 提取的是播放页 URL 或媒体直链
        - 如果 URL 已经是 m3u8/mp4/flv/mkv 等媒体直链，`playResolve.pipeline` 可用 `direct` / `return_if_playable` 直接返回
        - 如果 URL 是播放页，需要先 `fetch_html`，再通过 `js_var`、`regex`、`json_path` 或 `http_api` 提取真实播放地址，最后用 `return_if_playable` 校验
        - pipeline 是单一 current 流，每一步以上一步结果作为输入；关键节点建议及时放置 `return_if_playable`

        ### 字段空值与兜底
        - `detail.fields.title` 最终为空时会显示 `未命名影片`
        - `cover`、`description`、`category`、`year`、`area`、`director`、`actors` 可以为空
        - `sources` 最终为空时会兜底为 `默认线路` + `播放` + 当前详情页 URL，但后续不一定能解析出真实直链
        - `required=true` 当前不会触发 Adapter 失败或自动切换规则，只会影响 default 返回；不要把它当错误处理机制

        ### json_fields 对齐规则
        - `sourceNameField.split` 拆出的线路名会与 `episodeUrlField.sourceSplit` 拆出的播放串按下标对应
        - 线路名缺失时使用 `线路1`、`线路2` 等默认名称
        - 集数串按 `episodeSplit` 拆集，再按 `nameUrlSplit` 拆出集名和 URL；AI 生成前必须确认线路数量和播放串数量基本一致

        ### http_api 与 js_var 使用规则
        - `http_api` 当前只支持 GET；`method`、`headers`、`query`、`bodyTemplate`、`responseType` 不生效，查询参数必须直接拼进 `urlTemplate`
        - `urlTemplate` 支持 `{{currentUrl}}`、`{{detailUrl}}`、`{{origin}}`、`{{host}}`、`{{path}}`，以及 `variables` 中提取的变量
        - `js_var` 适合提取 `var/let/const/window.xxx` 形式的 JS 变量；对象变量使用 `valueType: "object"` 和简单 JSON Path，字符串变量才使用 `valueType: "string"`

        ### iframe 二跳示例
        如果真实播放地址在 iframe 页面中，可以在 pipeline 中先 `fetch_html`，用 `regex` 提取 `<iframe src="...">`，再 `absolute_url`，再 `fetch_html` 进入 iframe 页面，继续提取 m3u8/mp4 并 `return_if_playable`。

        ### 正则与最终输出要求
        - JSON 字符串中的正则反斜杠必须双写，例如 `\\d`、`\\s`、`\\.`
        - 最终输出必须是完整纯 JSON 对象，不要 Markdown 代码围栏、注释、省略号、解释文字或尾逗号
    """.trimIndent()
}
