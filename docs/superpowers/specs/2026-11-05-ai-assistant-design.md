# Web 端 AI 助手 — 设计文档

**日期**:2026-11-05
**作者**:Claude
**状态**:设计待审

## 背景与目标

SimpleWMS 已有的检索能力是"点筛选条件 → 看列表"。仓库管理员真正想问的是自然语言问题
("洗发水还有多少""这周哪些货快过期""哪些该补货了"),现在只能自己在几个页面之间切换、
拼凑答案。

本设计给 Web 端加一个 **AI 助手**:右下角悬浮球唤起右侧抽屉,用户用自然语言提问,助手
调用现有只读能力取数并给出中文回答,**同时给出业务建议**(补货提醒、FEFO 拣货建议、
盘点差异处置建议)。

**关键约束**:

- 助手**只读**,不建单、不提交、不改库存。写操作仍然只能走原有界面。
- 取数**按当前登录用户的权限**进行,不因为"问 AI"就绕过 `@RequireRole`。
- 模型 provider **可切换**:局域网自建(Ollama / vLLM,OpenAI 兼容接口)与 Anthropic
  Claude API 两种,换 provider 只改配置。
- 后端**不引入新依赖**:项目是离线构建(`.m2-repo` 镜像),两家都用 JDK 自带的
  `java.net.http.HttpClient` + 已有的 Jackson 手写客户端。
- 对话**不落库**,刷新即清空,无表结构变更。

## 非目标(YAGNI)

- 不做多轮工具编排(一次提问最多 3 个工具,不做 A→B 链式依赖)
- 不做对话持久化、不做审计回放
- 不做语音 / 图片 / 文件上传问答
- 不做 PDA 端
- 不做向量检索 / RAG 语料库(工具取数已经足够)
- 不做"AI 直接下单"这类写操作

---

## 1. 架构总览

```
浏览器 (frontend/js/assistant.js)
   │  POST /api/ai/chat   (SSE, Bearer JWT)
   ▼
nginx (9080) ──▶ backend (8080)
                   │
            AiChatController
                   │
            AiChatService ──── AiPlanner ──▶ AiClient ──▶ 模型服务
                   │                  │        ├ OpenAICompatibleClient
                   │                  │        └ AnthropicClient
                   └──▶ AiTools ──────┘
                              │  只读调用
                              ▼
                   ProductService / InventoryItemService /
                   DashboardService / InboundService /
                   OutboundService / SupplierService
```

分层沿用现有约定(`controller → service → dao`),AI 相关代码集中在 `com.wms.service.ai`,
**不修改任何现有业务逻辑**。唯一对既有代码的改动是 `SupplierService` 增加一个只读方法
(见 §3)。

## 2. 取数链路

一次提问 = **两轮模型调用 + 一次工具执行**,后端全程编排,浏览器只收最终答案的流。

```
用户问题 ──▶ ① 组 prompt(角色 + 工具清单 + 当前页面上下文 + 语言)
              │
              ▼
        ② 第一轮调用:模型只输出 JSON 计划
              {"tools":[{"name":"find_product","args":{"keyword":"洗发水"}}]}
              │
              ▼
        ③ AiPlanner 校验:工具名白名单 / 参数类型 / 参数范围 / 最多 3 个
              │
       ┌──────┴───────┐
     通过             不通过(JSON 坏 / 越权 / 未知工具)
       │                    │
       │                    ▼
       │            降级:关键词规则匹配到等价的工具
       │                    │
       └────────┬───────────┘
                ▼
        ④ AiTools 执行(只读,带当前用户角色)
                │  结果截断:每表最多 20 行 + "共 N 条"
                ▼
        ⑤ 第二轮调用:数据 + 原问题 → 流式生成答案
                │
                ▼
        SSE 逐段回传(每段一条 delta 事件)
```

**为什么是"JSON 计划"而不是原生 function calling**:换 provider 时,原生 tools 的
行为差异最大(参数格式、多轮语义各有各的坑);让模型只输出一段受约束 JSON,四家模型
的差异就收敛了,后端拿到的是一个可控的、可校验的东西。

**上下文裁剪**:只带最近 4 轮对话;单次工具结果序列化后不超过 4000 字符。

## 3. 工具集(第一版 6 个,全部只读)

| 工具名 | 参数 | 返回(截断后) | 复用 |
| ---- | ---- | ---- | ---- |
| `find_product` | `keyword`(必填)、`limit`(≤10,默认 5) | SKU / 名称 / 库存数量 / 低库存标记 | `ProductService.list(keyword)` |
| `stock_by_sku` | `sku`(必填) | 在库单元数、按库位分布、临期批次 | `ProductDao.findBySku` + `InventoryItemService.list(...)` |
| `low_stock` | `limit`(≤20,默认 10) | 低于阈值的商品 + 当前数量 + 阈值 | `ProductService.list(null)` 后按 `quantity <= lowStock` 过滤 |
| `expiring` | `kind`(`EXPIRING`\|`EXPIRED`,默认 `EXPIRING`)、`limit`(≤20) | 批次 / 商品 / 到期日 / 剩余天数 | `DashboardService.expiringItems(kind, limit)` |
| `recent_orders` | `type`(`IN`\|`OUT`\|`ALL`)、`days`(≤90,默认 7)、`limit`(≤10) | 单号 / 类型 / 状态 / 合作方 / 数量 / 创建时间 | `InboundService.listOrders` + `OutboundService.listOrders` |
| `supplier_quotes` | `productId` 或 `sku` | 供应商名 + 供货价(`BigDecimal`) | **新增** `SupplierService.listQuotesByProduct(int)` |

**对既有代码的唯一改动**:`SupplierService` 增加只读方法
`listQuotesByProduct(int productId)`,包一层 `SupplierDao.findSuppliersByProduct`
(该方法已存在,只是没往 Service 暴露)。AI 取数走 `service` 层,不直连 DAO,符合分层
约定;按 CLAUDE.md 要求,新增公共方法必须配套单测。

**金额一律 `BigDecimal`**,序列化时保留两位;不转 `double`。

## 4. Provider 适配

`AiClient` 是一个极薄的接口:

```java
public interface AiClient {
    /** 非流式:用于第一轮(只输出 JSON 计划) */
    String complete(String systemPrompt, List<Message> messages, int maxTokens);
    /** 流式:用于第二轮答案生成 */
    void stream(String systemPrompt, List<Message> messages, int maxTokens,
                Consumer<String> onDelta, Consumer<String> onDone);
}
```

| provider 取值 | 端点 | 关键字段 |
| ---- | ---- | ---- |
| `openai-compatible` | `POST {base-url}/chat/completions` | `messages[{role,content}]`、`max_tokens`、`stream`、`stream_options.include_usage` |
| `anthropic` | `POST {base-url}/v1/messages` | `system`(顶层)、`messages`(user/assistant 交替)、`max_tokens`、`stream`、`anthropic-version: 2023-06-01` |

- 客户端用 `java.net.http.HttpClient`,超时由 `wms.ai.timeout-ms` 控制(建连/读取都设)。
- Anthropic 流式解析 `content_block_delta` 里的 `text_delta`;OpenAI 兼容解析 `data: {...}`
  行的 `choices[0].delta.content`,**忽略 `[DONE]` 之前的空 delta**。
- 密钥只从配置读,拼进请求头(`x-api-key` 或 `Authorization: Bearer`),**不进日志**。
- 语言:system prompt 用中文还是英文,由前端请求里带的 `locale` 决定(与页面当前 i18n
  语言一致),后端不自行猜测。`locale` 缺省按 `zh-CN` 处理。

> 说明:Anthropic 有官方 Java SDK(`com.anthropic:anthropic-java`),这里仍选择手写,
> 原因是本项目的离线构建约束,以及 OpenAI 兼容侧本来就没有官方客户端——两边手写反而
> 对称。后续如引入 SDK,只需替换 `AnthropicClient` 内部实现。

## 5. 接口契约

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET | `/api/ai/status` | `{enabled, provider, model}`;不含密钥。前端据此决定显不显示悬浮球 |
| POST | `/api/ai/chat` | 请求 `{question, routeContext?}`,响应 `text/event-stream` |

**请求体**(`AiChatRequest`):

```json
{
  "question": "洗发水还有多少库存?",
  "locale": "zh-CN",
  "routeContext": { "route": "inventory", "filters": {"warehouse":"主仓"} }
}
```

- `question` 必填;超过 500 字符时**截断到 500** 并在 `meta.warning` 里提示,不返回错误。
- `locale` 可选(`zh-CN` | `en-US`,缺省 `zh-CN`),决定 system prompt 语言。
- `routeContext` 可选,只作为提示信息传给模型,后端**不据此做任何权限判断**。

**SSE 事件**:

| event | data | 说明 |
| ---- | ---- | ---- |
| `meta` | `{"tools":["find_product"],"degraded":false,"warning":null}` | 首帧,告诉前端用了哪些工具、是否降级;`warning` 用于"问题过长已截断"这类提示 |
| `delta` | `{"text":"共 128 件"}` | 答案分片,按顺序拼接 |
| `error` | `{"message":"AI 服务暂时不可用"}` | 出错时替代 `done` |
| `done` | `{}` | 正常结束 |

## 6. 权限与安全

- **取数跟人走**:`AiTools` 从 request attribute 读 `role`(由 `JwtAuthFilter` 写入)。
  `supplier_quotes` 仅 `ADMIN` 可用;`OPERATOR` 调用时返回
  `{"error":"无权限查看供货价"}`,模型据此如实回答"你看不到供货价",不给假数据。
- **模型看不到它不该看的数据**:数据是后端查完再塞进 prompt 的,模型本身没有任何数据库
  访问能力。
- **日志**:只记 `userId / 工具名 / 耗时 / token 数 / degraded 标记 / 错误摘要`。
  **不记录问题原文、不记录答案、不记录工具返回的数据**(含商品、供应商、价格)。
- **密钥**:`WMS_AI_API_KEY` 走环境变量,`application.yml` 只写占位符,与现有数据库
  密码的处理方式一致。
- **SSRF 面**:`base-url` 只从配置文件读,请求体里没有任何 URL 字段。
- **限流**:每用户每分钟 6 次(内存计数,参考 `LoginThrottle` 的写法),超出返回 429。
  LLM 调用慢且贵,没有这道闸门容易被连点打穿。
- **输入校验**:空白问题直接 400;控制字符剥离;超长截断到 500(见 §5)。工具参数在
  `AiTools` 里再校验一遍——**不信任模型输出**,就当它是不可信输入。
- **只读白名单**:`AiTools` 里没有任何写入方法,新增工具必须显式登记。

## 7. 前端

- 新增 `frontend/js/assistant.js`,样式追加到 `frontend/css/app.css`,`index.html` 加
  悬浮球 + 右侧抽屉容器(文案走 `data-i18n`)。
- **SSE 用手写 fetch 解析,不用 `EventSource`**——`EventSource` 带不了 `Authorization`
  头,会 401。复用 `api.js` 里"带 token 的 fetch"这一既有约定。
- 抽屉打开时把当前路由与筛选条件作为 `routeContext` 传过去,让"这个页面上的临期货有哪些"
  这类问题问得通。
- 加载态:首帧 `meta` 到达前显示"正在查询…";`delta` 到达后逐字追加;`error` 用
  `toast(..., 'error')` 展示。
- 小屏(<720px)抽屉改为全屏。
- `GET /api/ai/status` 返回 `enabled=false` 时**不渲染悬浮球**(避免用户点了报错)。
- 中英双语:`i18n.js` 补 `ai.*` 键;`t()` 用法与现有页面一致。

## 8. 配置

```yaml
wms:
  ai:
    enabled: true                     # 关掉后 /api/ai/* 返回 404,前端不显示入口
    provider: openai-compatible       # openai-compatible | anthropic
    base-url: ${WMS_AI_BASE_URL:http://localhost:11434/v1}
    api-key: ${WMS_AI_API_KEY:}       # anthropic 必填;ollama 可空
    model: ${WMS_AI_MODEL:qwen2.5:7b}
    timeout-ms: 30000                 # 建连 + 读超时
    max-tools-per-question: 3
    max-question-length: 500
    rate-limit-per-minute: 6
```

注入方式沿用现有风格:构造器 `@Value`(项目里没有用 `@ConfigurationProperties`)。

`.env.example` 追加 `WMS_AI_BASE_URL` / `WMS_AI_API_KEY` / `WMS_AI_MODEL` 三行说明。

## 9. 错误处理与降级

| 情况 | 行为 |
| ---- | ---- |
| 模型超时 / 连不上 / 5xx | SSE `error` 事件 "AI 服务暂时不可用,请稍后重试";HTTP 仍是 200(流已开),不静默返回空 |
| 第一轮 JSON 解析失败 | 降级到关键词匹配,`meta.degraded=true`,日志记一条 warn |
| 降级也匹配不到 | 返回引导语:"这个问题我暂时答不上来,可以试试问:某商品库存多少 / 哪些商品临期 / 哪些商品该补货了" |
| 工具执行抛异常 | 该工具以 `{"error": "..."}` 结果回填,不影响整轮;**不吞异常**,记 warn 日志 |
| 限流命中 | HTTP 429 + `{code:429, message:"请求过于频繁"}` |
| `enabled=false` | `POST /api/ai/chat` 返回 404;`GET /api/ai/status` **始终可用**并返回 `enabled:false`(否则前端无从判断该不该显示入口) |

异常统一经现有 `GlobalExceptionHandler` 转成 `ApiResponse`;SSE 一旦开始就不能再改状态码,
所以流内的错误走 `error` 事件,不在流内抛 HTTP 异常。

## 10. 测试

JUnit5 + Mockito,**全部 mock `AiClient`,单测不联网**:

| 测试类 | 覆盖 |
| ---- | ---- |
| `AiPlannerTest` | 正常计划 / 坏 JSON / 未知工具名 / 参数类型错 / 超过工具数上限 / 关键词降级命中与未命中 |
| `AiToolsTest` | `OPERATOR` 查 `supplier_quotes` 被拦、`ADMIN` 可查;参数越界被 clamp;结果截断生效 |
| `AiChatServiceTest` | 正常两轮、降级路径、模型不可用(抛超时)转 error 事件 |
| `SupplierServiceTest` | 新增的 `listQuotesByProduct` 返回 `BigDecimal` 且透传 DAO 结果 |

**手工验证**(有 Ollama 的环境):

1. `docker compose up -d` 起服务,`WMS_AI_BASE_URL=http://host:11434/v1`、`WMS_AI_MODEL=qwen2.5:7b`。
2. 实问三句:"洗发水还有多少库存""这周有哪些商品要过期""哪些商品该补货了",答案里的数字
   与库存页 / 仪表盘一致。
3. 用 OPERATOR 账号问"某某供应商供货价多少",应得到"无权限"而不是价格。
4. 把 `base-url` 指到一个不存在的地址,确认前端出现错误提示而不是卡住。

## 11. 文件清单

**新增**

```
src/main/java/com/wms/controller/AiChatController.java
src/main/java/com/wms/service/ai/AiChatService.java
src/main/java/com/wms/service/ai/AiPlanner.java
src/main/java/com/wms/service/ai/AiTools.java
src/main/java/com/wms/service/ai/AiClient.java
src/main/java/com/wms/service/ai/OpenAICompatibleClient.java
src/main/java/com/wms/service/ai/AnthropicClient.java
src/main/java/com/wms/service/ai/AiSettings.java        (配置读取 + enabled 开关)
src/main/java/com/wms/service/ai/AiThrottle.java       (每用户每分钟限流)
src/main/java/com/wms/model/ai/AiChatRequest.java
src/main/java/com/wms/model/ai/AiChatResponse.java      (SSE 事件的 data 形状)
frontend/js/assistant.js
```

**修改**

```
frontend/index.html       悬浮球 + 抽屉容器 + <script>
frontend/css/app.css      抽屉样式
frontend/js/i18n.js       ai.* 文案键
src/main/resources/application.yml   wms.ai.*
src/main/java/com/wms/service/SupplierService.java  + listQuotesByProduct(只读)
README.md                 功能表 + 接口表 + 配置说明(一行)
.env.example              WMS_AI_* 占位
```

**无表结构变更**,不需要 SQL 脚本。

## 12. 风险与后续

- **本地小模型的 JSON 稳定性**是最大风险。缓解:降级到关键词匹配(§9)、第一轮
  `max_tokens` 限 512、schema 尽量扁平(只有 `tools[].name/args`)。
- **延迟**:两轮调用 + 工具查询,7B 模型单次问答预计 3~8 秒。缓解:第二轮流式输出,
  首字到达即开始渲染;`meta` 首帧先告诉用户"在查什么"。
- **数据出网**:选 `anthropic` / 国内云 API 时,商品、供应商、价格会离开局域网。这是
  部署方的选择,默认配置指向局域网自建;README 需写明这一点。
- 后续可做(不在本设计内):对话式下单(需二次确认 + 审计表)、按角色裁剪工具集、
  对话历史持久化。
