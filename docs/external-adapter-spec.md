# casttv-receiver 外部 Adapter JSON 当前实现规范（AI 生成专用）

## 1. 概述

本文档是给 AI 生成可执行外部 Adapter JSON 使用的执行规范。所有规则以 `RuleBasedAdapter.kt` 当前实现为准，而不是理想 DSL 或未来设计能力。AI 生成 JSON 时必须优先保证导入后能被当前 App 解析、能被当前 `RuleBasedAdapter` 执行、不会依赖未实现字段。

外部 Adapter JSON 用来描述一个网页解析规则，核心流程是：用 `match` 判断当前页面是否适配，用 `detail` 提取影片基础信息，用 `sources` 提取播放线路和集数，用 `playResolve` 把播放页地址解析成真实播放地址。

运行时规则文件从 App 私有目录 `filesDir/json_adapters` 加载，保存时文件名来自 `meta.name`。保存校验当前只强制检查 `match`、`sources`、`playResolve` 三个对象存在；但 AI 生成时仍建议完整写出 `schemaVersion`、`meta`、`match`、`detail`、`sources`、`playResolve`，便于维护和排查。

正确示例：生成完整 JSON 对象，包含清晰的匹配条件、详情字段、播放资源和解析流水线。

```json
{
  "schemaVersion": "1.0",
  "meta": {
    "name": "示例站点 Adapter",
    "version": "1.0.0",
    "author": "李明杰",
    "description": "适配示例站点详情页和播放页"
  },
  "match": {
    "operator": "AND",
    "conditions": [
      { "type": "domainContains", "value": "example.com" },
      { "type": "htmlContains", "values": ["playlist", "player_aaaa"] }
    ]
  },
  "detail": {
    "fields": {
      "title": {
        "rules": [
          { "type": "css_selector", "selector": "h1", "attr": "text" },
          { "type": "meta_og", "property": "og:title" }
        ],
        "default": "未命名影片"
      }
    }
  },
  "sources": {
    "mode": "single",
    "sourceName": "默认线路",
    "episodeName": "播放",
    "playPageUrl": {
      "rules": [
        { "type": "fixed", "value": "{{currentUrl}}" }
      ],
      "default": "{{currentUrl}}"
    }
  },
  "playResolve": {
    "pipeline": [
      { "type": "direct" },
      { "type": "fetch_html" },
      { "type": "regex", "pattern": "https?://[^\\\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv)(?:[^\\\"'<>\\s]*)?", "group": 0 },
      { "type": "return_if_playable" }
    ]
  }
}
```

错误示例：只写设计字段或省略运行必需对象，当前保存校验或运行会失败。

```json
{
  "schemaVersion": "1.0",
  "meta": { "name": "错误示例" },
  "detail": {}
}
```

## 2. 当前实现范围 vs 设计预留字段

AI 生成 JSON 时只能依赖当前已实现字段。未实现字段可以被 JSON 解析器忽略，但不会改变运行结果。为了避免误导，AI 不得把无效字段当成能力使用。

当前已实现字段和能力如下。

| 模块 | 已实现字段 / 能力 | 说明 |
| --- | --- | --- |
| 顶层 | `match`、`sources`、`playResolve` | 保存校验强制要求这三个对象存在 |
| 元信息 | `meta.name`、`meta.version` | 用于展示和保存文件名；`author`、`description` 可作为说明 |
| match | `operator`、`conditions`、`group` | 支持 AND/OR 和嵌套组 |
| detail | `fields.title`、`cover`、`description`、`category`、`type`、`year`、`area`、`director`、`actors` | `category` 优先于 `type` |
| 通用规则 | `fixed`、`meta_og`、`regex`、`css_selector`、`js_var`、`json_path` | 只支持本文档列出的真实行为 |
| sources | `single`、`html_blocks`、`json_fields` | 未知 mode 会按 `html_blocks` 处理 |
| html_blocks | `sourceBlocks.selectors`、`sourceName.rules`、`sourceName.defaultTemplate`、`episodes.itemSelector`、`episodes.name`、`episodes.url`、`episodes.filters`、`fallback` | block 找不到时总会退回完整 HTML |
| json_fields | `jsonSources`、`vodObjectPaths`、`sourceNameField`、`episodeUrlField`、`episodeUrlPostprocess` | 只支持简单对象路径和分隔符拆分 |
| playResolve | `variables`、`pipeline` | pipeline 是单一 current 流 |

当前无效或不完整实现的字段如下。AI 不得依赖它们。

| 字段 | 当前状态 | 正确处理方式 |
| --- | --- | --- |
| `sourceName.tabMappings` | 未实现 | 不要依赖外部 Tab 到 block 的名称映射，改用 block 内标题或默认线路名 |
| `sourceName.blockIdRule` | 未实现 | 不要依赖 block ID 查线路名 |
| `sourceId` | 未使用 | 不要写业务逻辑依赖线路 ID |
| `errorHandling` | 基本未使用 | 不要依赖它触发失败或切换 Adapter |
| `http_api.method` | 未使用 | 当前只支持 GET |
| `http_api.headers` | 未使用 | 不要依赖自定义请求头 |
| `http_api.query` | 未使用 | 直接把查询参数拼进 `urlTemplate` |
| `http_api.bodyTemplate` | 未使用 | 不支持 POST body |
| `http_api.responseType` | 未使用 | 响应统一按文本取回，再由 `extract` 解析 |
| `episodes.distinctBy` | 未使用 | 实际固定按 `playPageUrl` 去重 |
| `defaultSourceNameTemplate` | 未使用 | `json_fields` 默认线路名固定为 `线路1`、`线路2` |
| `defaultEpisodeNameTemplate` | 未使用 | `json_fields` 默认集名固定为 `第1集`、`第2集` |
| `sourceBlocks.fallbackToWholeHtml=false` | 无效 | 找不到 block 时仍会扫描完整 HTML |
| `required=true` 触发 Adapter 失败 | 未实现 | 只会尝试 default 兜底，不会真正失败 |

正确示例：只使用已实现字段。

```json
{
  "sourceName": {
    "rules": [
      { "type": "css_selector", "selector": "h2", "attr": "text" }
    ],
    "defaultTemplate": "线路{{index1}}"
  }
}
```

错误示例：依赖当前无效字段映射线路名。

```json
{
  "sourceName": {
    "tabMappings": [
      {
        "idRule": { "type": "css_selector", "selector": "a[href^='#']", "attr": "href" },
        "nameRule": { "type": "css_selector", "selector": "a[href^='#']", "attr": "text" }
      }
    ],
    "blockIdRule": { "type": "css_selector", "selector": ":scope", "attr": "id" }
  }
}
```

## 3. 完整执行流程

当前 `RuleBasedAdapter` 的真实执行流程如下。

第一步，加载规则文件。如果没有指定文件名，会从 `filesDir/json_adapters` 读取所有 `.json` 文件并按文件名排序。若指定了文件名，则只加载该文件，并且强制使用它。

第二步，执行 `match` 选择规则。未指定文件名时，会按顺序找到第一个 `match` 返回 true 的规则，并把它设为 `activeRule`。指定文件名时跳过匹配，直接使用该规则。

第三步，执行 `detail` 提取。代码读取 `detail.fields`，依次提取 `title`、`cover`、`description`、`category/type`、`year`、`area`、`director`、`actors`。字段提取失败时返回 default 或空字符串。`title` 最终为空时兜底为 `未命名影片`。

第四步，执行 `sources`。`mode="single"` 走单集模式；`mode="json_fields"` 走 JSON 字段模式；其他值或缺失都走 `html_blocks`。如果 sources 最终为空，详情解析会构造 `默认线路` + `播放` + 当前详情页 URL。

第五步，执行 `playResolve.pipeline`。播放时用当前规则的 `playResolve` 把 `playPageUrl` 解析为真实播放地址。pipeline 结束后，只有结果满足 `looksPlayable` 才返回，否则返回 null。

正确示例：让 `match` 精准命中，再用 `sources` 和 `playResolve` 分别处理列表和播放页。

```json
{
  "match": {
    "operator": "AND",
    "conditions": [
      { "type": "domainContains", "value": "example.com" },
      { "type": "htmlContains", "operator": "OR", "values": ["playlist", "player_aaaa"] }
    ]
  },
  "sources": {
    "mode": "html_blocks",
    "sourceBlocks": { "selectors": ["ul.playlist"] },
    "episodes": {
      "itemSelector": "a[href]",
      "name": { "rules": [{ "type": "css_selector", "selector": ":scope", "attr": "text" }] },
      "url": { "rules": [{ "type": "css_selector", "selector": ":scope", "attr": "href", "postprocess": ["trim", "decode_html_entities", "absolute_url"] }] }
    }
  },
  "playResolve": {
    "pipeline": [
      { "type": "direct" },
      { "type": "fetch_html" },
      { "type": "js_var", "name": "player_aaaa", "valueType": "object", "path": "$.url", "postprocess": ["url_decode", "base64_decode", "absolute_url"] },
      { "type": "return_if_playable" }
    ]
  }
}
```

错误示例：以为 `errorHandling` 会让必填字段失败后自动切换规则。

```json
{
  "detail": {
    "fields": {
      "title": { "rules": [], "required": true }
    }
  },
  "errorHandling": {
    "onRequiredFieldFail": "fail_adapter",
    "adapterFallback": "next_adapter"
  }
}
```

## 4. match 配置

`match` 用于判断规则是否适配当前 URL 和 HTML。顶层结构包含 `operator` 和 `conditions`。`operator` 支持 `AND` 和 `OR`，默认 `OR`。

支持的条件类型如下。

| type | 输入 | 说明 |
| --- | --- | --- |
| `domainContains` | URL host | host 包含指定字符串 |
| `domainEquals` | URL host | host 等于指定域名 |
| `domainRegex` | URL host | host 匹配正则 |
| `urlRegex` | 完整 URL | URL 匹配正则 |
| `htmlRegex` | HTML | HTML 匹配正则，默认忽略大小写并支持跨行 |
| `htmlContains` | HTML | HTML 包含关键字，内部支持 AND/OR |
| `group` | 条件组 | 嵌套组合条件 |

`caseSensitive` 默认 false。大多数站点应使用域名条件加 HTML 特征条件，避免 match 过宽。

> ⚠️ 高频失败原因：`meta.domains` 数组和 `match.conditions` 中的域名值必须是纯字符串，严禁写成 Markdown 链接格式（如 `"[cctv.com](cctv.com)"`）。App 做字符串 contains 比对，Markdown 格式永远不会匹配真实 host。AI 在输出 JSON 时必须自查域名字段是否为纯域名字符串。
>
> - ❌ 错误：`"value": "[cctv.com](cctv.com)"`
> - ✅ 正确：`"value": "cctv.com"`

正确示例：域名和页面特征同时满足才匹配。

```json
{
  "operator": "AND",
  "conditions": [
    { "type": "domainContains", "value": "example.com" },
    {
      "type": "group",
      "operator": "OR",
      "conditions": [
        { "type": "htmlContains", "values": ["player_aaaa", "vod_play"] },
        { "type": "urlRegex", "pattern": "/vod/detail/" }
      ]
    }
  ]
}
```

错误示例：匹配过宽，容易抢占其他站点。

```json
{
  "operator": "OR",
  "conditions": [
    { "type": "htmlContains", "values": ["video"] }
  ]
}
```

## 5. css_selector 能力说明（标准 CSS Selector）

当前 `css_selector` 使用 [Jsoup](https://jsoup.org) 的标准 CSS Selector 引擎实现，支持完整标准 CSS Selector 语法。AI 可以放心使用浏览器中常见的 CSS 选择器写法，包括后代、子代、伪类、属性前缀/后缀/包含等。

实际支持写法如下（节选常用，非完整清单）。

| 写法 | 示例 | 说明 |
| --- | --- | --- |
| 标签名 | `a`、`div`、`ul` | 匹配指定标签 |
| class | `ul.playlist`、`a.title` | 支持 class 选择 |
| ID | `#playlist1` | 支持 ID 简写 |
| 后代选择器 | `div.playlist a[href]` | 任意层级后代 |
| 子选择器 | `li.next > a` | 仅直接子级 |
| 相邻/通用兄弟 | `h2 + ul`、`h2 ~ ul` | `+` 紧邻兄弟、`~` 后续兄弟 |
| 属性存在 | `a[href]` | 要求属性存在 |
| 属性等于 | `div[id='playlist1']` | 属性值完全相等 |
| 属性前缀 | `a[href^='/detail/']` | 属性值以指定内容开头 |
| 属性后缀 | `img[src$='.jpg']` | 属性值以指定内容结尾 |
| 属性包含 | `a[href*='play']` | 属性值包含指定内容 |
| 逗号多选 | `ul.playlist,div.playlist` | 命中任一即保留 |
| 伪类 | `li:last-child`、`li:nth-child(1)`、`a:not(.disabled)` | 标准伪类 |
| 文本包含 | `a:contains('下一页')` | 按文本内容筛选 |
| `:scope` | `:scope` | 当前元素上下文中表示当前元素本身 |

> 说明：`:scope` 作为单独的 selector 时，表示「当前元素自身」（在 `episodes`/`sourceName` 等元素上下文中直接取 text/href），由解析器特殊处理，不交给 Jsoup 重新查找；其他标准选择器全部由 Jsoup 执行。

普通 `css_selector` 的 `attr="text"` 返回清洗后的文本，其他 attr 读取同名属性。只有在 `:scope` 的元素上下文里，额外支持 `attr="html"` 返回 inner HTML、`attr="outerHtml"` 返回 outer HTML。

正确示例：用后代选择器 + 属性后缀精准定位封面图。

```json
{
  "type": "css_selector",
  "selector": "div.detail .poster img[src$='.jpg']",
  "attr": "src",
  "index": 0,
  "postprocess": ["trim", "decode_html_entities", "absolute_url"]
}
```

正确示例：用伪类 + 属性前缀组合定位最后一集链接。

```json
{
  "type": "css_selector",
  "selector": "ul.playlist li:last-child a[href^='/play/']",
  "attr": "href",
  "postprocess": ["trim", "decode_html_entities", "absolute_url"]
}
```

> 历史限制已解除：此前的「不支持后代选择器/子选择器/伪类/属性包含 `*=`/属性后缀 `$=`/ID 简写」等限制在引入 Jsoup 后全部取消，存量适配器无需改动即可继续工作。

## 6. `:scope` 专章

`:scope` 在当前实现里最常用于 `episodes.name.rules` 和 `episodes.url.rules`。当 `episodes.itemSelector` 选中一个 `<a>` 元素后，`:scope` 表示这个当前 `<a>` 本身。

读取当前集数元素文本时，使用 `selector=":scope"` 和 `attr="text"`。读取当前集数元素 href 时，使用 `selector=":scope"` 和 `attr="href"`。

正确示例：从当前 `<a>` 读取集名和播放页 URL。

```json
{
  "itemSelector": "a[href]",
  "name": {
    "rules": [
      { "type": "css_selector", "selector": ":scope", "attr": "text", "postprocess": ["trim", "decode_html_entities"] }
    ],
    "defaultTemplate": "第{{index1}}集"
  },
  "url": {
    "rules": [
      { "type": "css_selector", "selector": ":scope", "attr": "href", "postprocess": ["trim", "decode_html_entities", "absolute_url"] }
    ],
    "required": true
  }
}
```

错误示例：在当前 `<a>` 内再次查找 `a`，通常取不到结果。

```json
{
  "itemSelector": "a[href]",
  "url": {
    "rules": [
      { "type": "css_selector", "selector": "a", "attr": "href" }
    ]
  }
}
```

另一个错误示例：`itemSelector` 选中的是 `li`，却直接从 `:scope` 取 `href`。如果 href 在子级 `<a>` 上，这会取空。

```json
{
  "itemSelector": "li",
  "url": {
    "rules": [
      { "type": "css_selector", "selector": ":scope", "attr": "href" }
    ]
  }
}
```

## 7. postprocess 完整列表与默认行为

`postprocess` 用于对提取结果做串联处理。当前支持字符串步骤和对象步骤。

支持的字符串步骤如下。

| 步骤 | 说明 |
| --- | --- |
| `trim` | 去掉首尾空白 |
| `decode_html_entities` | 解码 HTML 实体 |
| `url_decode` | UTF-8 URL Decode，失败保留原值 |
| `absolute_url` | 基于当前页面 URL 转绝对 URL |
| `base64_decode` | 尝试 URLDecode 后再 Base64 解码，只有结果像 URL 或播放地址才替换 |

支持的对象步骤如下。

| op | 说明 |
| --- | --- |
| `replace` | Kotlin Regex 替换 |
| `substring_before` | 截取指定字符串之前，找不到则保留原值 |
| `substring_after` | 截取指定字符串之后，找不到则保留原值 |

默认行为很重要：如果完全不写 `postprocess`，代码会默认执行 HTML 实体解码和 trim；如果显式写了 `postprocess`，则只按数组步骤执行，最后统一 trim，不会自动补 `decode_html_entities`。因此 URL 字段推荐显式写完整流水线。

URL 字段推荐流水线如下。

```json
["trim", "decode_html_entities", "url_decode", "absolute_url"]
```

如果 URL 可能是 Base64，再加入 `base64_decode`。

```json
["trim", "decode_html_entities", "url_decode", "base64_decode", "absolute_url"]
```

正确示例：相对地址转绝对地址。

```json
{
  "type": "css_selector",
  "selector": ":scope",
  "attr": "href",
  "postprocess": ["trim", "decode_html_entities", "absolute_url"]
}
```

错误示例：显式写了 postprocess 却漏掉实体解码，`&amp;` 可能保留在 URL 中。

```json
{
  "type": "css_selector",
  "selector": ":scope",
  "attr": "href",
  "postprocess": ["trim", "absolute_url"]
}
```

## 8. detail 字段提取

`detail.fields` 用于生成 `ParsedMovie` 基础信息。当前支持字段如下。

| 字段 | 映射 | 说明 |
| --- | --- | --- |
| `title` | `ParsedMovie.title` | 建议必写，最终为空会兜底为 `未命名影片` |
| `cover` | `ParsedMovie.coverUrl` | URL 建议加 `absolute_url` |
| `description` | `ParsedMovie.description` | 简介 |
| `category` | `ParsedMovie.category` | 优先级高于 `type` |
| `type` | `ParsedMovie.category` | 仅在 `category` 为空时兜底 |
| `year` | `ParsedMovie.year` | 年份 |
| `area` | `ParsedMovie.area` | 地区 |
| `director` | `ParsedMovie.director` | 导演 |
| `actors` | `ParsedMovie.actors` | 主演 |

每个字段可以写成 `{ "rules": [...] }` 容器，也可以直接写单条规则。多条 rules 按顺序尝试，遇到非空结果就返回。`required=true` 当前不会导致 Adapter 失败，只会在提取失败时优先返回 `default`。不要依赖 required 触发错误处理。

正确示例：标题多候选兜底。

```json
{
  "title": {
    "rules": [
      { "type": "css_selector", "selector": "h1", "attr": "text", "postprocess": ["trim", "decode_html_entities"] },
      { "type": "meta_og", "property": "og:title", "postprocess": ["trim", "decode_html_entities"] },
      { "type": "regex", "pattern": "<title[^>]*>([\\s\\S]*?)</title>", "group": 1, "postprocess": ["trim", "decode_html_entities"] },
      { "type": "fixed", "value": "未命名影片" }
    ],
    "default": "未命名影片"
  }
}
```

错误示例：以为 required 会让规则失败并切换 Adapter。

```json
{
  "title": {
    "rules": [
      { "type": "css_selector", "selector": "h1.not-exists", "attr": "text" }
    ],
    "required": true
  }
}
```

## 9. sources 三种模式详解

`sources` 输出 `ParsedSource` 列表。每个线路包含线路名和集数列表。每个集数包含展示名、播放页 URL，以及可选真实播放地址。

### 9.1 `single` 模式

`single` 适合没有剧集列表、只有一个播放页或直链的页面。当前读取 `sourceName`、`episodeName`、`playPageUrl`、`resolvedUrlWhenPlayable`。`playPageUrl` 失败时默认当前详情页 URL。`resolvedUrlWhenPlayable` 默认 true，如果 URL 看起来像 m3u8/mp4/flv/mkv，会直接写入 `resolvedUrl`。

正确示例：单集页面。

```json
{
  "mode": "single",
  "sourceName": "默认线路",
  "episodeName": "播放",
  "playPageUrl": {
    "rules": [
      { "type": "regex", "pattern": "https?://[^\\\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv)(?:[^\\\"'<>\\s]*)?", "group": 0, "postprocess": ["trim", "decode_html_entities"] },
      { "type": "fixed", "value": "{{currentUrl}}" }
    ],
    "default": "{{currentUrl}}"
  },
  "resolvedUrlWhenPlayable": true
}
```

错误示例：没有集数列表却写 `html_blocks`，可能整页误抓导航链接。

```json
{
  "mode": "html_blocks",
  "sourceBlocks": { "selectors": [] },
  "episodes": { "itemSelector": "a[href]" }
}
```

### 9.2 `html_blocks` 模式

`html_blocks` 适合详情页 HTML 中包含播放线路和剧集列表的站点。真实流程是：先用 `sourceBlocks.selectors` 找线路块；所有 selector 都没命中时，无论 `fallbackToWholeHtml` 如何配置，都会把完整 HTML 当成一个 block。然后在每个 block 中用 `episodes.itemSelector` 找集数元素。每个 block 先严格过滤，若结果为空，再放松 URL 和名称软过滤重试。所有 block 都没有结果时，才执行 `sources.fallback`，fallback 会按 single 模式处理。

正确示例：用 block 限定范围，再用 `a[href]` 抓集数。

```json
{
  "mode": "html_blocks",
  "sourceBlocks": {
    "selectors": [
      "ul.playlist",
      "div[id^='playlist']",
      "div.anthology-list"
    ]
  },
  "sourceName": {
    "rules": [
      { "type": "css_selector", "selector": "h2", "attr": "text", "postprocess": ["trim", "decode_html_entities"] }
    ],
    "defaultTemplate": "线路{{index1}}"
  },
  "episodes": {
    "itemSelector": "a[href]",
    "name": {
      "rules": [
        { "type": "css_selector", "selector": ":scope", "attr": "text", "postprocess": ["trim", "decode_html_entities"] }
      ],
      "defaultTemplate": "第{{index1}}集"
    },
    "url": {
      "rules": [
        { "type": "css_selector", "selector": ":scope", "attr": "href", "postprocess": ["trim", "decode_html_entities", "absolute_url"] }
      ],
      "required": true
    },
    "filters": {
      "skipEmptyUrl": true,
      "skipJavascriptUrl": true,
      "maxNameLength": 60,
      "urlContainsAny": ["/play/", "vodplay", "episode"],
      "urlRegexAny": ["/\\d+-\\d+-\\d+\\.html"],
      "nameRegexAny": ["第\\s*\\d+\\s*[集话]", "^\\s*\\d{1,4}\\s*$", "正片|全集|上集|下集|HD|超清"],
      "allowPlayableUrl": true
    }
  },
  "fallback": {
    "sourceName": "默认线路",
    "episodeName": "播放",
    "playPageUrl": {
      "rules": [
        { "type": "fixed", "value": "{{currentUrl}}" }
      ],
      "default": "{{currentUrl}}"
    }
  }
}
```

错误示例：依赖 `fallbackToWholeHtml=false` 禁止整页扫描，当前不会生效。

```json
{
  "mode": "html_blocks",
  "sourceBlocks": {
    "selectors": ["div.not-exists"],
    "fallbackToWholeHtml": false
  },
  "episodes": {
    "itemSelector": "a[href]"
  }
}
```

### 9.3 `json_fields` 模式

`json_fields` 适合苹果 CMS / MacCMS API 一类结构：线路字段如 `vod_play_from`，播放字段如 `vod_play_url`，线路用 `$$$` 分隔，集数用 `#` 分隔，集名和 URL 用 `$` 分隔。

当前 `jsonSources` 支持 `body_json`、`js_var`、`http_api`。`http_api` 只会 GET `urlTemplate`，不会处理 method、query、headers、bodyTemplate、responseType。`vodObjectPaths` 和其他 paths 只支持简单 JSON Path：`.field` 和 `[0]`。不支持通配符、过滤表达式、特殊字段名，也不支持复杂数组结构。

正确示例：苹果 CMS API。

```json
{
  "mode": "json_fields",
  "jsonSources": [
    { "type": "body_json" },
    { "type": "http_api", "urlTemplate": "{{origin}}/api.php/provide/vod/?ac=detail&ids={{detailId}}", "timeoutMs": 15000 }
  ],
  "vodObjectPaths": ["$.list[0]", "$.data[0]", "$.data.list[0]", "$"],
  "sourceNameField": {
    "paths": ["$.vod_play_from", "$.play_from"],
    "split": "$$$"
  },
  "episodeUrlField": {
    "paths": ["$.vod_play_url", "$.play_url"],
    "sourceSplit": "$$$",
    "episodeSplit": "#",
    "nameUrlSplit": "$"
  },
  "episodeUrlPostprocess": ["trim", "decode_html_entities", "url_decode", "absolute_url"]
}
```

错误示例：依赖 POST、headers 或复杂 JSON Path。

```json
{
  "mode": "json_fields",
  "jsonSources": [
    {
      "type": "http_api",
      "method": "POST",
      "headers": { "Authorization": "Bearer token" },
      "bodyTemplate": "id={{detailId}}",
      "urlTemplate": "{{origin}}/api/search"
    }
  ],
  "vodObjectPaths": ["$.data.items[?(@.type=='vod')]"]
}
```

## 10. episodes 过滤策略（重点章节）

`episodes.filters` 决定某个链接是否被当作集数。真实逻辑分为基础过滤和软过滤。

基础过滤始终优先执行：`skipEmptyUrl` 默认 true，URL 为空跳过；`skipJavascriptUrl` 默认 true，URL 以 javascript 开头跳过；`maxNameLength` 默认 60，名称超过长度跳过。

软过滤支持 `urlContainsAny`、`urlRegexAny`、`nameRegexAny`、`allowPlayableUrl`。严格模式下，命中任一条件即可保留。`allowPlayableUrl` 默认 true，但只有当 filters 显式存在并参与 strict 规则判断时，才会影响组合逻辑。

当前代码有一个 `hasStrictRule` 机制：`urlRegexAny`、`nameRegexAny` 或显式存在 `allowPlayableUrl` 都会让过滤进入严格判断。如果只写 `urlContainsAny`，而没有 `urlRegexAny`、`nameRegexAny`，也没有显式写 `allowPlayableUrl`，则 `hasStrictRule=false`，不命中 `urlContainsAny` 的链接也可能通过。因此建议要么不写软过滤，要么同时写 `urlContainsAny`、`urlRegexAny`、`nameRegexAny`，并显式写 `allowPlayableUrl: true`。

每个 block 会先严格过滤。如果严格过滤得到 0 集，会自动放松 URL 和名称软过滤重试，只保留基础过滤。这是为了避免合法剧集被误杀，但也意味着 block 范围太大时可能误抓无关链接。

推荐模板如下。

```json
{
  "filters": {
    "skipEmptyUrl": true,
    "skipJavascriptUrl": true,
    "maxNameLength": 60,
    "urlContainsAny": ["/play/", "vodplay", "episode", "video"],
    "urlRegexAny": ["/\\d+-\\d+-\\d+\\.html", "/play/[^\\s]+", "(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv)(?:[?#][^\\s]*)?$"],
    "nameRegexAny": ["第\\s*\\d+\\s*[集话]", "^\\s*\\d{1,4}\\s*$", "正片|全集|上集|下集|HD|超清|蓝光"],
    "allowPlayableUrl": true
  }
}
```

正确示例：结构准确时，可以少写软过滤，降低误杀风险。

```json
{
  "sourceBlocks": { "selectors": ["ul.playlist"] },
  "episodes": {
    "itemSelector": "a[href]",
    "filters": {
      "skipEmptyUrl": true,
      "skipJavascriptUrl": true,
      "maxNameLength": 80
    }
  }
}
```

错误示例：只写一个过窄 URL 关键字，既可能误杀，也可能因 `hasStrictRule=false` 变成过松。

```json
{
  "filters": {
    "urlContainsAny": ["/play/"]
  }
}
```

另一个错误示例：集名正则只覆盖“第 N 集”，漏掉“01”“正片”“HD中字”。

```json
{
  "filters": {
    "nameRegexAny": ["第\\s*\\d+\\s*集"],
    "allowPlayableUrl": true
  }
}
```

## 11. playResolve pipeline

`playResolve.pipeline` 用来把 `playPageUrl` 解析成真实播放地址。pipeline 是单一 `current` 流：初始值是 `playPageUrl`，每一步读取并改写 current。最后只有 current 满足 `looksPlayable` 才返回，否则返回 null。

支持的 step type 如下。

| type | 实际行为 |
| --- | --- |
| `direct` | 如果 current 已经是可播放地址，直接返回 |
| `return_if_playable` | 如果 current 是可播放地址，直接返回 |
| `jianpian_unwrap` | 从 `jianpian://...path=真实URL` 中剥出真实 URL |
| `fetch_html` | GET current，把响应文本作为新的 current |
| `regex` | 对 current 执行正则提取 |
| `js_var` | 从 current HTML 中提取 JS 变量 |
| `json_path` | 把 current 当 JSON 对象，用简单路径提取 |
| `url_decode` | URL Decode current |
| `base64_decode` | 尝试 Base64 解码 current |
| `absolute_url` | 基于原始 playPageUrl 转绝对 URL |
| `http_api` | GET `urlTemplate`，再用 `extract` 从响应中取值 |

`http_api` 当前只支持 GET。`headers`、`method`、`query`、`bodyTemplate`、`responseType` 不生效。需要查询参数时，直接写进 `urlTemplate`。

`variables` 的提取依赖 current 内容。pipeline 开始前会从原始 `playPageUrl` 提取变量；执行 `http_api` 前会再从当前 current 提取一次变量。如果变量在播放页 HTML 中，必须先执行 `fetch_html`，再让变量正则能从 HTML 中取到值。

直链模板如下。

```json
{
  "pipeline": [
    { "type": "jianpian_unwrap" },
    { "type": "direct" }
  ]
}
```

JS 变量模板如下。

```json
{
  "pipeline": [
    { "type": "direct" },
    { "type": "fetch_html" },
    { "type": "js_var", "name": "player_aaaa", "valueType": "object", "path": "$.url", "postprocess": ["url_decode", "base64_decode", "absolute_url"] },
    { "type": "return_if_playable" }
  ]
}
```

Base64 模板如下。

```json
{
  "pipeline": [
    { "type": "fetch_html" },
    { "type": "regex", "pattern": "url['\"]?\\s*[:=]\\s*['\"]([^'\"]+)['\"]", "group": 1 },
    { "type": "url_decode" },
    { "type": "base64_decode" },
    { "type": "absolute_url" },
    { "type": "return_if_playable" }
  ]
}
```

二次 API 模板如下。

```json
{
  "variables": {
    "pid": {
      "rules": [
        { "type": "regex", "pattern": "[?&](?:pid|guid|videoCenterId)=([^&#]+)", "group": 1, "postprocess": ["trim", "url_decode"] },
        { "type": "regex", "pattern": "[\"'](?:pid|guid|videoCenterId)[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']", "group": 1, "postprocess": ["trim", "url_decode"] }
      ],
      "required": true
    }
  },
  "pipeline": [
    { "type": "direct" },
    { "type": "fetch_html" },
    {
      "type": "http_api",
      "urlTemplate": "https://example.com/api/video?id={{pid}}",
      "timeoutMs": 15000,
      "extract": {
        "rules": [
          { "type": "json_path", "path": "$.url" },
          { "type": "json_path", "path": "$.data.playUrl" }
        ],
        "required": true
      }
    },
    { "type": "url_decode" },
    { "type": "return_if_playable" }
  ]
}
```

错误示例：最后没有得到 looksPlayable，pipeline 会返回 null。

```json
{
  "pipeline": [
    { "type": "fetch_html" },
    { "type": "regex", "pattern": "<title>([\\s\\S]*?)</title>", "group": 1 }
  ]
}
```

## 12. 完整 JSON 示例

### 12.1 html_blocks 模式示例：影视站剧集列表

这个示例适合详情页里有 `ul.playlist` 或 `div[id^='playlist']` 剧集列表，播放页里有 `player_aaaa.url` 的站点。

正确示例：

```json
{
  "schemaVersion": "1.0",
  "meta": {
    "name": "影视站剧集列表示例",
    "version": "1.0.0",
    "author": "李明杰",
    "description": "使用 html_blocks 提取多集列表，并从 player_aaaa.url 解析播放地址"
  },
  "match": {
    "operator": "AND",
    "conditions": [
      { "type": "domainContains", "value": "example.com" },
      { "type": "htmlContains", "operator": "OR", "values": ["playlist", "player_aaaa", "vod_play"] }
    ]
  },
  "detail": {
    "fields": {
      "title": {
        "rules": [
          { "type": "css_selector", "selector": "h1", "attr": "text", "postprocess": ["trim", "decode_html_entities"] },
          { "type": "meta_og", "property": "og:title", "postprocess": ["trim", "decode_html_entities"] },
          { "type": "regex", "pattern": "<title[^>]*>([\\s\\S]*?)</title>", "group": 1, "postprocess": ["trim", "decode_html_entities", { "op": "replace", "pattern": "[-_].*$", "replacement": "" }, "trim"] },
          { "type": "fixed", "value": "未命名影片" }
        ],
        "default": "未命名影片"
      },
      "cover": {
        "rules": [
          { "type": "meta_og", "property": "og:image", "postprocess": ["trim", "decode_html_entities", "absolute_url"] },
          { "type": "css_selector", "selector": "img[data-src]", "attr": "data-src", "postprocess": ["trim", "decode_html_entities", "absolute_url"] },
          { "type": "css_selector", "selector": "img[src]", "attr": "src", "postprocess": ["trim", "decode_html_entities", "absolute_url"] }
        ],
        "default": ""
      },
      "description": {
        "rules": [
          { "type": "meta_og", "property": "og:description", "postprocess": ["trim", "decode_html_entities"] },
          { "type": "css_selector", "selector": "div.desc", "attr": "text", "postprocess": ["trim", "decode_html_entities"] }
        ],
        "default": ""
      },
      "category": { "rules": [{ "type": "fixed", "value": "" }], "default": "" },
      "year": { "rules": [{ "type": "fixed", "value": "" }], "default": "" },
      "area": { "rules": [{ "type": "fixed", "value": "" }], "default": "" },
      "director": { "rules": [{ "type": "fixed", "value": "" }], "default": "" },
      "actors": { "rules": [{ "type": "fixed", "value": "" }], "default": "" }
    }
  },
  "sources": {
    "mode": "html_blocks",
    "sourceBlocks": {
      "selectors": [
        "ul.playlist",
        "ul.play-list",
        "div[id^='playlist']",
        "div.anthology-list"
      ]
    },
    "sourceName": {
      "rules": [
        { "type": "css_selector", "selector": "h2", "attr": "text", "postprocess": ["trim", "decode_html_entities"] },
        { "type": "css_selector", "selector": "h3", "attr": "text", "postprocess": ["trim", "decode_html_entities"] }
      ],
      "defaultTemplate": "线路{{index1}}"
    },
    "episodes": {
      "itemSelector": "a[href]",
      "name": {
        "rules": [
          { "type": "css_selector", "selector": ":scope", "attr": "text", "postprocess": ["trim", "decode_html_entities"] }
        ],
        "defaultTemplate": "第{{index1}}集"
      },
      "url": {
        "rules": [
          { "type": "css_selector", "selector": ":scope", "attr": "href", "postprocess": ["trim", "decode_html_entities", "absolute_url"] }
        ],
        "required": true
      },
      "filters": {
        "skipEmptyUrl": true,
        "skipJavascriptUrl": true,
        "maxNameLength": 60,
        "urlContainsAny": ["/play/", "vodplay", "episode"],
        "urlRegexAny": ["/\\d+-\\d+-\\d+\\.html", "/play/[^\\s]+"],
        "nameRegexAny": ["第\\s*\\d+\\s*[集话]", "^\\s*\\d{1,4}\\s*$", "正片|全集|上集|下集|HD|超清"],
        "allowPlayableUrl": true
      }
    },
    "fallback": {
      "sourceName": "默认线路",
      "episodeName": "播放",
      "playPageUrl": {
        "rules": [
          { "type": "fixed", "value": "{{currentUrl}}" }
        ],
        "default": "{{currentUrl}}"
      },
      "resolvedUrlWhenPlayable": true
    }
  },
  "playResolve": {
    "pipeline": [
      { "type": "direct" },
      { "type": "jianpian_unwrap" },
      { "type": "fetch_html" },
      { "type": "js_var", "name": "player_aaaa", "valueType": "object", "path": "$.url", "postprocess": ["url_decode", "base64_decode", "absolute_url"] },
      { "type": "return_if_playable" },
      { "type": "regex", "pattern": "https?://[^\\\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv)(?:[^\\\"'<>\\s]*)?", "group": 0 },
      { "type": "return_if_playable" }
    ]
  }
}
```

错误示例：同类站点不应依赖未实现的 `tabMappings`；且 `sourceBlocks.selectors` 应选到「剧集列表容器」（如 `div.play-panel ul.playlist`），而不是直接选到叶子 `<a>`，否则 `itemSelector` 没有可用的下级范围。

```json
{
  "sources": {
    "mode": "html_blocks",
    "sourceBlocks": { "selectors": ["div.play-panel ul.playlist a[href]"] },
    "sourceName": { "tabMappings": [] }
  }
}
```

### 12.2 json_fields 模式示例：苹果 CMS API

这个示例适合接口响应包含 `vod_play_from` 和 `vod_play_url` 的页面或 API。

正确示例：

```json
{
  "schemaVersion": "1.0",
  "meta": {
    "name": "苹果CMS API 示例",
    "version": "1.0.0",
    "author": "李明杰",
    "description": "从苹果 CMS API JSON 中解析线路和集数"
  },
  "match": {
    "operator": "OR",
    "conditions": [
      { "type": "urlRegex", "pattern": "api\\.php/provide/vod" },
      { "type": "htmlContains", "operator": "AND", "values": ["vod_play_from", "vod_play_url"] }
    ]
  },
  "detail": {
    "fields": {
      "title": {
        "rules": [
          { "type": "json_path", "path": "$.list[0].vod_name", "postprocess": ["trim", "decode_html_entities"] },
          { "type": "json_path", "path": "$.data[0].vod_name", "postprocess": ["trim", "decode_html_entities"] },
          { "type": "fixed", "value": "未命名影片" }
        ],
        "default": "未命名影片"
      },
      "cover": {
        "rules": [
          { "type": "json_path", "path": "$.list[0].vod_pic", "postprocess": ["trim", "decode_html_entities", "absolute_url"] },
          { "type": "json_path", "path": "$.data[0].vod_pic", "postprocess": ["trim", "decode_html_entities", "absolute_url"] }
        ],
        "default": ""
      },
      "description": {
        "rules": [
          { "type": "json_path", "path": "$.list[0].vod_content", "postprocess": ["trim", "decode_html_entities"] },
          { "type": "json_path", "path": "$.data[0].vod_content", "postprocess": ["trim", "decode_html_entities"] }
        ],
        "default": ""
      },
      "category": { "rules": [{ "type": "json_path", "path": "$.list[0].vod_class", "postprocess": ["trim", "decode_html_entities"] }], "default": "" },
      "year": { "rules": [{ "type": "json_path", "path": "$.list[0].vod_year", "postprocess": ["trim", "decode_html_entities"] }], "default": "" },
      "area": { "rules": [{ "type": "json_path", "path": "$.list[0].vod_area", "postprocess": ["trim", "decode_html_entities"] }], "default": "" },
      "director": { "rules": [{ "type": "json_path", "path": "$.list[0].vod_director", "postprocess": ["trim", "decode_html_entities"] }], "default": "" },
      "actors": { "rules": [{ "type": "json_path", "path": "$.list[0].vod_actor", "postprocess": ["trim", "decode_html_entities"] }], "default": "" }
    }
  },
  "sources": {
    "mode": "json_fields",
    "jsonSources": [
      { "type": "body_json" },
      { "type": "http_api", "urlTemplate": "{{currentUrl}}", "timeoutMs": 15000 }
    ],
    "vodObjectPaths": ["$.list[0]", "$.data[0]", "$.data.list[0]", "$"],
    "sourceNameField": {
      "paths": ["$.vod_play_from", "$.play_from"],
      "split": "$$$"
    },
    "episodeUrlField": {
      "paths": ["$.vod_play_url", "$.play_url"],
      "sourceSplit": "$$$",
      "episodeSplit": "#",
      "nameUrlSplit": "$"
    },
    "episodeUrlPostprocess": ["trim", "decode_html_entities", "url_decode", "absolute_url"]
  },
  "playResolve": {
    "pipeline": [
      { "type": "direct" },
      { "type": "jianpian_unwrap" },
      { "type": "fetch_html" },
      { "type": "js_var", "name": "player_aaaa", "valueType": "object", "path": "$.url", "postprocess": ["url_decode", "base64_decode", "absolute_url"] },
      { "type": "return_if_playable" },
      { "type": "regex", "pattern": "https?://[^\\\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv)(?:[^\\\"'<>\\s]*)?", "group": 0 },
      { "type": "return_if_playable" }
    ]
  }
}
```

错误示例：苹果 CMS API 不要使用复杂 JSON Path 或未实现默认模板字段。

```json
{
  "sources": {
    "mode": "json_fields",
    "vodObjectPaths": ["$.list[?(@.vod_id==1)]"],
    "defaultSourceNameTemplate": "播放源{{index1}}",
    "defaultEpisodeNameTemplate": "播放{{index1}}"
  }
}
```

## 13. AI 生成前检查清单

AI 输出最终 JSON 前必须逐项自查。

| 检查项 | 要求 |
| --- | --- |
| JSON 格式 | 必须是完整 JSON 对象，不能有注释、Markdown 代码围栏、省略号、尾逗号 |
| 顶层字段 | 必须包含 `match`、`sources`、`playResolve`，建议包含 `schemaVersion`、`meta`、`detail` |
| match | 不能过宽，优先使用域名条件 + HTML 特征条件 |
| 域名纯字符串 | ⚠️ 高频失败原因：`meta.domains` 数组和 `match.conditions` 中的域名值必须是纯字符串，严禁写成 Markdown 链接格式；App 做字符串 contains 比对，Markdown 格式永远不会匹配真实 host。AI 输出 JSON 前必须自查：❌ `"value": "[cctv.com](cctv.com)"`；✅ `"value": "cctv.com"` |
| css_selector | 使用标准 CSS Selector（Jsoup），支持后代/子/伪类/属性前缀后缀/逗号等完整语法；`:scope` 表示当前元素自身 |
| :scope | `episodes.name` 用 `:scope + text`，`episodes.url` 用 `:scope + href` |
| URL 字段 | 必须显式配置 `absolute_url`，必要时加 `url_decode`、`base64_decode` |
| 正则转义 | JSON 字符串中反斜杠必须双写，如 `\\d`、`\\s`、`\\.` |
| detail | `title` 必须有可靠兜底；不要依赖 `required=true` 触发失败 |
| sources | 没有集数列表时使用 `single`；有列表时优先用 `html_blocks` 缩小 block 范围 |
| filters | 不要只写过窄的 `urlContainsAny`；推荐配合 `urlRegexAny`、`nameRegexAny`、`allowPlayableUrl` |
| playResolve | pipeline 至少应能返回 m3u8/mp4/flv/mkv 等 looksPlayable 地址 |
| 未实现字段 | 不得依赖 `tabMappings`、`sourceId`、`errorHandling`、`http_api.headers/method/query/bodyTemplate/responseType`、`episodes.distinctBy`、`defaultSourceNameTemplate`、`defaultEpisodeNameTemplate`、`fallbackToWholeHtml=false` |

正确示例：输出纯 JSON，不附带解释。

```json
{
  "schemaVersion": "1.0",
  "meta": { "name": "最终示例", "version": "1.0.0", "author": "李明杰" },
  "match": { "operator": "AND", "conditions": [{ "type": "domainContains", "value": "example.com" }] },
  "sources": { "mode": "single", "sourceName": "默认线路", "episodeName": "播放", "playPageUrl": { "rules": [{ "type": "fixed", "value": "{{currentUrl}}" }] } },
  "playResolve": { "pipeline": [{ "type": "direct" }, { "type": "fetch_html" }, { "type": "regex", "pattern": "https?://[^\\\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv|\\.mkv)(?:[^\\\"'<>\\s]*)?", "group": 0 }, { "type": "return_if_playable" }] }
}
```

错误示例：包含解释、注释或依赖未实现字段。

```json
{
  "schemaVersion": "1.0",
  "meta": { "name": "错误示例" },
  "sources": {
    "mode": "html_blocks",
    "sourceName": { "tabMappings": [] },
    "episodes": { "distinctBy": "name" }
  },
  "errorHandling": { "onRequiredFieldFail": "fail_adapter" }
}
```
