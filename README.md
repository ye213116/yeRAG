# yeRAG

yeRAG 是一个面向企业知识库的 Agentic RAG 应用。它把文档处理、权限感知检索、原生 Tool Calling、实时对话和可选的用户记忆组合在一起：模型可以根据问题自主决定是否检索知识库，同时保留传统 RAG fallback，保证不支持工具协议的模型也能工作。

当前项目的 Java 包路径仍为 `com.yizhaoqi.smartpai`，这是代码兼容性命名；产品和文档主体统一使用 **yeRAG**。

## 当前能力

- 文档分片上传、断点续传、MinIO 文件存储和 Apache Tika 解析
- Kafka 驱动的异步文档处理、切分与向量化
- Elasticsearch 的 BM25 与 KNN 双路并行召回、RRF 排名融合和 gte-rerank 重排序
- 结合公开文档、用户文档和组织标签的权限过滤
- 基于 WebSocket 的流式 AI 对话
- 原生 OpenAI-compatible `tools/tool_calls` 知识库 Agent
- 可选的 MCP Streamable HTTP 只读工具接入
- 会话上下文、长期用户记忆和 Markdown 记忆文档
- JWT 认证、Spring Security、组织标签隔离和管理能力

## 架构概览

```text
文档上传
  -> MinIO
  -> Kafka
  -> Tika 解析 / 文本切分
  -> Embedding
  -> Elasticsearch knowledge_base

用户问题
  -> WebSocket / ChatHandler
  -> MemoryContextService（启用记忆时）
  -> KnowledgeAgentService
       -> LLM 判断是否调用工具
       -> AgentToolRegistry
       -> search_knowledge_base
       -> HybridSearchService（双路召回 + 权限前置过滤 + RRF 融合 + gte-rerank 重排）
       -> MCP Tool Adapter（启用时）
       -> 远程 MCP Server
  -> 流式回答与引用
```

聊天始终使用统一记忆链路。业务会话、完整回合和滚动摘要以 MySQL 为准；Redis 仅保存有 TTL 的缓存。

## RAG Pipeline 与评测证据

知识库工具和固定 RAG 共用 Query、Retrieve、Rerank、Merge、Assemble 阶段；固定 RAG 继续执行 Generate。问答、工具和模型调用通过运行 ID 关联，保存候选变化、裁剪范围与实际模型请求；completion 附带可查询本人证据的 `runId`。

配置、证据接口和 MySQL 迁移见 [Pipeline 使用文档](docs/rag-pipeline.md)。评测机制、数据集、真实结果、运行方法与后续工作统一维护在 [yeRAG 评测主文档](evaluation/README.md)。该文档明确区分完整 RRF + rerank 主链路、召回消融、独立留出、企业人审和真实权限验收；旧评测说明与实验纪要保留为历史快照。

## Agent 与 Tool Calling

Agent 入口是 `KnowledgeAgentService`，模型客户端通过 `AgentLlmClient` 抽象接入。当前工具由 `AgentToolRegistry` 注册，内置工具为：

| 工具 | 用途 | 参数 |
| --- | --- | --- |
| `search_knowledge_base` | 检索当前用户有权限访问的企业知识库 | 必填 `query`，可选 `topK` |
| `read_knowledge_document` | 重新校验权限后，深入阅读原文与相邻片段 | 必填 `fileMd5`、`startChunk`，可选 `chunkCount`（1～5）、`offset` |
| `search_web` | 搜索公开资料，返回带链接的摘要 | 必填 `query`，可选 `count`；需启用联网并配置搜索密钥 |
| `read_web_page` | 读取公开 HTML / 纯文本网页正文 | 必填 `url`，可选 `offset`；需启用联网 |

运行规则：

- 根据当前问题和最近对话识别任务与所需来源。简单问题直接进入原有 Loop；复杂比较、跨文档综合按需启用 Plan-and-Execute，见下文。内部资料优先知识库；涉及最新公开信息、外部资料或内外对比时联网。知识库无结果本身不是联网理由，不用公开资料替代内部依据。
- 前端支持自动、仅知识库、仅联网三种来源模式。后端按每轮模式过滤工具目录并限制执行；MCP 工具未声明来源，当前仅在自动模式可用。
- `userId` 由服务端从认证上下文传入，模型不能通过工具参数改变权限范围。
- `query` 默认最多 500 个字符，`topK` 限制在 1～10，单次 Agent 默认最多调用工具 8 次，总超时仍为 60 秒。显式配置会覆盖默认值。一个决策中的多个调用按顺序执行并逐个回填结果，超过预算的调用返回预算耗尽错误。
- 工具结果会限制正文长度，并显式标记为不可信参考数据；文档内容不能覆盖系统规则，也不能诱导模型执行命令。
- WebSocket 保留 `chunk`、`completion` 消息，并发送 `agent_status` 的 `thinking`、`tool_started`、`tool_finished` 等状态。
- 仅知识库模式下，Agent 失败且尚未输出答案时，可以按 `ai.agent.fallback-enabled` 切换到固定 RAG。自动及仅联网模式不做固定 RAG 降级，避免将内部检索当作公开证据；Agent 关闭时需选择仅知识库模式使用固定 RAG。
- 统一来源包含编号、类型、标题、文档片段或网页 URL、获取时间和证据层级。服务端把可点击的来源列表附入完整答案后持久化，历史会话和重复请求仍能查看来源；完成事件额外带 `sources` 元数据。搜索摘要与已读取网页正文明确区分。

### 按需 Plan-and-Execute

默认开启。先用本地规则筛选复杂比较和跨文档综合候选（支持中英文，以及带指代的后续提问）；普通问答、单篇摘要和简单概念比较不增加规划模型调用。候选再通过一次无工具的模型调用确认，输出 2～3 个证据任务，服务端追加“综合证据并生成回答”。模型认为无需计划、返回非法结构、规划超时或失败时回到原有 Loop。规则筛选是启发式判断，可能漏掉未覆盖的表达，漏选问题仍由原有 Loop 处理。

每个证据任务串行复用现有工具 Loop；整轮共享来源模式、认证身份、工具调用预算、引用编号和总超时，不为每个任务重置预算。中间记录留作最终综合的上下文，不作为用户答案输出或持久化，也不作为独立证据。只有最终综合通过 `chunk` 输出并由现有 Memory 路径保存完整回答。工具失败会标记任务未完成；预算耗尽会标记当前任务未完成、后续任务跳过，最终综合说明证据缺口。执行异常与取消沿用原有失败/fallback 和取消持久化规则。

整轮输入采用当前实际模型 `deepseek-flash` 对应的官方 BPE tokenizer，默认上下文 1,048,576 tokens、回答预留 8,192 tokens、安全预留 4,096 tokens；只在达到剩余容量的 95% 时裁剪工具正文。Memory 默认按 token 预算保留完整回合，用户输入和工具结果同步放宽，HTTP JSON 转义不当作模型正文重复计量。公开覆盖配置在 [config/application.properties](config/application.properties)，计量口径、资源上限和换模型步骤见 [Pipeline 输入预算](docs/rag-pipeline.md#配置)。

```yaml
ai:
  agent:
    planning:
      enabled: true  # false：所有问题直接进入原有 Loop
      max-steps: 4   # 含最终综合，运行时限制在 3～6
      timeout: 5s    # 单次规划上限，且受 llm-timeout 和 total-timeout 限制
```

前端在助手消息中展示任务列表；`pending`、`running`、`completed`、`failed`、`skipped`、`cancelled` 分别对应等待、进行中、完成、未完成、跳过、停止。这里的完成表示任务执行结束，不保证检索一定找到了证据。任务标题按纯文本渲染；列表只属于当前请求，按 `requestId` / `conversationId` 隔离；完成、失败、停止后的迟到进度不再更新。任务列表是本轮实时状态，刷新/历史回放只恢复已保存的回答和来源。

沿用 `agent_status`，新增 `planning` 和 `plan_updated`，后者每次发送完整快照，不改变 `chunk` / `completion` 协议：

```json
{"type":"agent_status","status":"plan_updated","message":"任务执行进度","requestId":"...","conversationId":"...","details":{"tasks":[{"id":"1","title":"阅读甲方案的成本与风险","status":"running","message":"正在执行"},{"id":"2","title":"阅读乙方案的成本与风险","status":"pending","message":"等待执行"},{"id":"3","title":"综合证据并生成回答","status":"pending","message":"等待执行"}]}}
```

### 知识库深读与联网配置

知识库搜索会返回 `file_md5`、`chunk_id` 和截断标志，模型可据此调用 `read_knowledge_document`。读取使用与双路召回相同的 ACL 前置过滤，按 `chunkId` 升序返回指定范围，不依赖模型提供用户或组织身份。正文受工具结果预算限制；`next_start_chunk`、`next_offset` 支持续读，局部片段不代表全文。

联网默认关闭。首个搜索实现使用 [Brave Search Web API](https://api-dashboard.search.brave.com/api-reference/web/search/get)，只发送模型显式生成的公开查询词；不把整段对话或文档自动提交给搜索提供商。

```yaml
ai:
  agent:
    max-tool-calls: 8
  web:
    enabled: ${AI_WEB_ENABLED:false}
    api-key: ${AI_WEB_API_KEY:}
    search-url: https://api.search.brave.com/res/v1/web/search
    timeout: 6s
    max-results: 5
    max-response-bytes: 16777216
    max-page-characters: 1048576
```

启用后，未配置密钥仍可读取用户给出的公开网页，搜索会明确返回未配置错误。网页读取仅支持公开 HTTP(S) 标准端口、HTML 与纯文本，不执行 JavaScript，也不携带用户登录凭据。不支持 PDF 和需要登录的页面。每次连接的 DNS 结果、IP 字面量和每跳重定向目标都检查公开地址；请求有超时、响应体大小和正文长度上限，搜索提供商请求不跟随重定向。

模型提示词要求仅向外发送公开产品、版本和技术问题，禁止发送内部原文、客户信息和凭据；此语义约束依赖模型遵循，不能当作独立的数据脱敏系统。文档与网页内容均作为不可信参考材料处理。

WebSocket 请求兼容原协议，新增可选字段 `sourceMode`，缺省 `auto`：

```json
{"type":"chat","message":"比较内部方案和最新官方建议","conversationId":"...","requestId":"...","sourceMode":"auto"}
```

`sourceMode` 只接受 `auto`、`knowledge`、`web`。限制按每轮请求生效，历史对话不会改变本轮工具权限。

相关实现：

```text
agent/KnowledgeAgentService.java
agent/AgentLlmClient.java
agent/AgentToolRegistry.java
agent/tool/KnowledgeSearchTool.java
agent/model/ToolCall.java
service/ChatHandler.java
service/RagFallbackService.java
```

### 检索 Query Rewrite

`search_knowledge_base` 在参数校验后，先用当前 LLM 将 query 改写为一条检索查询，再执行带权限过滤的混合检索。改写保留原意、实体和限制条件；仅向模型提供 query，不传入用户身份或授权信息。改写失败、超时或输出无效时使用原 query。固定 RAG fallback 路径不执行此步骤。

```yaml
ai:
  agent:
    query-rewrite-enabled: true
    query-rewrite-timeout: 2s
```

改写时间包含在 `tool-timeout` 内，实际等待上限为 `query-rewrite-timeout` 与 `tool-timeout` 一半的较小值，给后续检索预留时间。关闭开关可直接使用工具收到的 query。

### 检索链路：双路召回 + RRF + 重排序

检索不再把关键词分和向量分加权相加，而是拆成互不依赖的三步：

```text
BM25 路（bool.filter 前置 ACL）┐
                               ├─ RRF 按排名融合 ─ gte-rerank 精排前 50 ─ 返回 topK
KNN  路（knn.filter 前置 ACL） ┘
```

- 两路各自取 `topK × 30` 的召回窗口，互不影响；同一 chunk（`fileMd5 + chunkId`）在两路都命中时在 RRF 中累加分数，这也是 RRF 奖励“多路共识”的方式。
- RRF 只比较排名（`score = Σ 1/(k + rank)`），因此不需要为两种分数量纲找换算系数；默认等权，权重可配。
- 重排序只对融合结果的前 50 条候选打分，按重排分返回 `topK`。
- 降级路径：向量生成失败只走 BM25 路（仍重排）；任一路召回失败或超时只丢该路；重排序超时或失败直接返回 RRF 排序。重排序是增益项，不会成为检索的故障点；任何降级都不会扩大授权范围。
- 权限过滤在每一路内部前置执行，避免无权限文档先吃掉召回窗口。

```yaml
ai:
  retrieval:
    mode: rrf               # rrf | legacy（legacy 仅用于回滚旧实现）
    recall-multiplier: 30   # 每路窗口 = topK × 30
    max-top-k: 50           # 入参 topK 上限，避免窗口被放大
    rrf-rank-constant: 60
    bm25-weight: 1.0
    knn-weight: 1.0
    route-timeout: 2s
    rerank:
      enabled: true
      url: ${RERANK_API_URL}       # gte-rerank 端点，留空则只用 RRF
      api-key: ${RERANK_API_KEY}   # 留空时复用 embedding.api.key
      model: gte-rerank
      top-n: 50                    # 进入重排的候选数
      timeout: 1500ms
```

`ai.retrieval.mode=legacy` 会切回旧的“单请求 KNN + BM25 rescore”实现，仅用于灰度对比与线上回滚。

### MCP 只读工具

MCP 默认关闭。启用后，应用在后台连接配置的 Streamable HTTP Server，发现显式白名单中的只读工具，并以 `{serverId}__{alias}` 注册到同一个 `AgentToolRegistry`。MCP 连接失败不会移除本地 `search_knowledge_base`，服务会后台重连。

```yaml
ai:
  mcp:
    enabled: true
    connect-timeout: 5s
    request-timeout: 8s
    refresh-interval: 5m
    identity-token-ttl: 30s
    max-list-pages: 20
    max-tools-per-server: 200
    max-argument-characters: 16384
    max-result-characters: 4194304
    allow-insecure-http: false
    servers:
      ops:
        base-url: https://mcp.example.internal
        endpoint: /mcp
        service-token: ${OPS_MCP_SERVICE_TOKEN}
        identity-signing-key: ${OPS_MCP_IDENTITY_SIGNING_KEY}
        read-only-tools:
          - remote-name: lookup_metrics
            alias: lookup_metrics
            description-override: 查询只读业务指标
```

`identity-signing-key` 是每个 MCP Server 独立的 Base64 编码 HMAC 密钥，解码后至少 32 字节。yeRAG 为每次工具调用签发短期用户令牌并放入 `X-YeRAG-User-Token` 请求头；MCP Server 必须验签并根据 `sub` 重新查询用户权限。该身份不会加入模型可控的工具参数。生产配置只应通过部署环境注入密钥和 service token，且默认只允许 HTTPS。

首版只转发文本和结构化 JSON 结果，不支持图片、音频、嵌入资源、STDIO、写操作或人工确认流程。远程结果会被裁剪并按不可信数据回填模型。

## Memory：会话上下文与用户长期记忆

启动前必须先初始化完整数据库结构。个人记忆不能改变权限、工具规则，也不能代替企业知识库证据。

### 短期会话上下文

- MySQL 保存业务会话、完整问答回合、滚动摘要、请求幂等信息和异步任务状态。成功回答后，短期上下文续期 7 天。
- Redis 只缓存短期上下文；缓存丢失时从 MySQL 恢复。记忆读取失败时用当前问题继续回答，并通过 `context_reduced` 通知前端。
- 业务 `conversationId` 与 WebSocket `sessionId` 分离；新建会话、空闲超时切换、重连和迟到事件都有边界处理。
- 前端支持新建会话、历史会话只读查看和 `requestId` 事件隔离。

### Markdown 长期记忆

yeRAG 只保留 Markdown document memory：每个用户维护 `profile.md` 和 `preferences.md` 两类受控文档。`MemoryWorker` 根据用户原始问题生成 section patch，服务端校验 section 所有权和证据后事务更新文档；召回时动态生成不落库的 `MEMORY.md` 索引。

长期记忆使用持久化提取任务、租约、重试和幂等处理，不使用 Elasticsearch 向量召回。

配置示例：

```yaml
ai:
  agent:
    enabled: true
    fallback-enabled: true
  memory:
    short-term-enabled: true
    extraction-enabled: true
    recall-enabled: true
```

这些子功能默认开启，可使用对应的 `AI_MEMORY_*` 环境变量配置。

记忆接口包括：

```text
GET  /api/v1/memory/sessions/current
POST /api/v1/memory/sessions
GET  /api/v1/memory/sessions
GET  /api/v1/memory/sessions/{id}/messages
GET  /api/v1/memory/documents
```

数据库初始化和设计说明：

- [完整 MySQL DDL](docs/databases/ddl.sql)
- [完整数据库表设计](docs/databases/database-design.md)
- [现有数据库升级 SQL](docs/databases/migrations/20260927_durable_short_term_memory.sql)
- [现有数据库部署说明](docs/databases/migrations/20260927_durable_short_term_memory.md)

`ddl.sql` 用于空数据库初始化。现有数据库先执行升级 SQL，再部署新版本。应用启动时检查记忆表及摘要列；旧 Redis 有效会话和摘要由可重试迁移任务导入。

## 技术栈

### 后端

- Java 17、Spring Boot 3.4.2、Maven
- Spring Web、WebFlux、WebSocket、Spring Security、Spring Data JPA
- MySQL 8、Redis 7、Elasticsearch 8.10
- Apache Kafka 3.2、MinIO 8.5.12、Apache Tika 2.9.1
- DeepSeek 或其他 OpenAI-compatible Chat Completions 服务
- Embedding API（地址、模型和向量维度由配置指定）
- 重排序 API（gte-rerank 兼容的文本重排服务，可选；未配置时只用 RRF）

### 前端

- Vue 3、TypeScript、Vite、Vue Router、Pinia
- Naive UI、UnoCSS、Markdown 渲染
- pnpm workspace

## 代码结构

```text
src/main/java/com/yizhaoqi/smartpai/
├── agent/          # Agent loop、tool registry、tool call 模型
├── client/         # LLM、Embedding 与重排序客户端
├── config/         # AI、认证、ES、Kafka、MinIO、WebSocket 配置
├── consumer/       # Kafka 文档处理消费者
├── controller/     # REST API
├── handler/        # WebSocket 连接与聊天处理
├── memory/         # 会话、上下文、长期记忆与 worker
├── model/          # JPA 领域模型
├── repository/     # 数据访问
├── service/        # 上传、解析、双路召回、检索编排、RAG fallback
└── utils/          # 通用工具（含 RRF 融合）

frontend/src/
├── store/modules/chat/       # WebSocket、会话和记忆状态
├── views/chat/               # Agent 对话与会话切换
├── views/knowledge-base/     # 知识库管理
├── service/                  # API 请求
└── router/                   # 页面路由
```

## 本地开发

### 前置依赖

- Java 17
- Maven 3.8.6+
- Node.js 18.20+
- pnpm 8.7+
- MySQL 8、Redis 7、Elasticsearch 8.10、Kafka 3.2、MinIO 8.5

Docker 可用于运行这些基础设施，但当前仓库没有提交 `docker-compose.yml`；请按部署环境自行启动并在 Spring 配置中填写连接信息。

### 后端

仓库没有提交生产环境 `application.yml`，请在本地配置 Spring Boot 所需的数据源、Redis、Kafka、ES、MinIO、JWT、LLM 和 Embedding 参数。测试配置位于 `src/test/resources/application.yml`。

首次启动前创建空数据库并执行完整 DDL：

```bash
mysql -u root -p -e "CREATE DATABASE yerag CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql -u root -p yerag < docs/databases/ddl.sql
```

常用命令：

```bash
# 启动
mvn spring-boot:run

# 打包
mvn clean package

# 测试
mvn test

# 运行指定测试
mvn -Dtest=KnowledgeAgentServiceTest test
```

真实 MySQL 记忆并发测试是 opt-in 的，设置 `MEMORY_MYSQL_TEST_URL`、`MEMORY_MYSQL_TEST_USER` 和 `MEMORY_MYSQL_TEST_PASSWORD` 后才会执行；测试会创建并删除随机数据库。

### 前端

```bash
cd frontend
pnpm install
pnpm dev
```

常用命令：

```bash
pnpm typecheck
pnpm lint
pnpm build
pnpm preview
```

前端本地后端地址可在 `frontend/.env` 或对应 mode 的环境文件中配置，默认 API 为 `http://localhost:8081/api/v1`，WebSocket 为 `ws://localhost:8081`。

## 安全边界

- 知识库搜索必须经过用户、公开文档和组织标签权限过滤。
- 本地知识库工具只接收 `query` 和 `topK`；所有工具的身份、组织和权限均由服务端决定。
- MCP 只暴露显式白名单中的只读工具，并使用独立短期签名令牌传递当前用户身份。
- 工具结果、历史记忆和 Markdown section 都按不可信数据注入模型上下文。
- 长期记忆只接受受控分类和可在用户原文中定位的证据，不接受模型任意创建 key。
- 生产环境应限制 WebSocket allowed origins，并使用强 JWT secret、真实服务凭据和非默认管理员密码。

## 相关文档

- [Knowledge Agent 设计](docs/superpowers/specs/2026-08-27-knowledge-agent-design.md)
- [Knowledge Agent 实现计划](docs/superpowers/plans/2026-08-27-knowledge-agent.md)
- [数据库 DDL](docs/databases/ddl.sql)
- [数据库表设计](docs/databases/database-design.md)
