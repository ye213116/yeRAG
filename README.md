# yeRAG

yeRAG 是一个面向企业知识库的 Agentic RAG 应用。它把文档处理、权限感知检索、原生 Tool Calling、实时对话和可选的用户记忆组合在一起：模型可以根据问题自主决定是否检索知识库，同时保留传统 RAG fallback，保证不支持工具协议的模型也能工作。

当前项目的 Java 包路径仍为 `com.yizhaoqi.smartpai`，这是代码兼容性命名；产品和文档主体统一使用 **yeRAG**。

## 当前能力

- 文档分片上传、断点续传、MinIO 文件存储和 Apache Tika 解析
- Kafka 驱动的异步文档处理、切分与向量化
- Elasticsearch 向量检索、BM25 文本检索和混合检索
- 结合公开文档、用户文档和组织标签的权限过滤
- 基于 WebSocket 的流式 AI 对话
- 原生 OpenAI-compatible `tools/tool_calls` 知识库 Agent
- 可选的会话上下文、长期用户记忆和 Markdown 记忆文档
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
       -> HybridSearchService（向量 + BM25 + 权限过滤）
  -> 流式回答与引用
```

未启用记忆时，对话历史使用兼容旧版本的 Redis 会话结构；启用记忆后，业务会话和完整回合以 MySQL 为准，Redis 保存有 TTL 的短期上下文缓存。

## Agent 与 Tool Calling

Agent 入口是 `KnowledgeAgentService`，模型客户端通过 `AgentLlmClient` 抽象接入。当前工具由 `AgentToolRegistry` 注册，内置工具为：

| 工具 | 用途 | 参数 |
| --- | --- | --- |
| `search_knowledge_base` | 检索当前用户有权限访问的企业知识库 | 必填 `query`，可选 `topK` |

运行规则：

- 闲聊和通用知识可以直接回答；涉及企业资料、用户文档或需要知识库证据的问题必须检索。
- `userId` 由服务端从认证上下文传入，模型不能通过工具参数改变权限范围。
- `query` 默认最多 500 个字符，`topK` 限制在 1～10，单次 Agent 默认最多调用工具 3 次。
- 工具结果会限制正文长度，并显式标记为不可信参考数据；文档内容不能覆盖系统规则，也不能诱导模型执行命令。
- WebSocket 保留 `chunk`、`completion` 消息，并发送 `agent_status` 的 `thinking`、`tool_started`、`tool_finished` 等状态。
- Agent 失败且尚未输出答案时，可以按 `ai.agent.fallback-enabled` 自动切换到固定的 RAG 流程。

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

## Memory：会话上下文与用户长期记忆

记忆功能默认关闭，启用前必须先初始化完整数据库结构。它不是知识库检索的替代品：个人记忆不能改变权限、工具规则，也不能代替企业知识库证据。

### 短期会话上下文

- MySQL 保存业务会话、完整问答回合、请求幂等信息和异步任务状态。
- Redis 保存滚动摘要、最近完整问答和压缩进度，默认上下文 TTL 为 7 天。
- 使用 token/字节预算构建模型上下文；摘要失败时保留可用的近期完整问答，并通过 `context_reduced` 通知前端。
- 业务 `conversationId` 与 WebSocket `sessionId` 分离；新建会话、空闲超时切换、重连和迟到事件都有边界处理。
- 前端支持新建会话、历史会话只读查看和 `requestId` 事件隔离。

### Markdown 长期记忆

yeRAG 只保留 Markdown document memory：每个用户维护 `profile.md` 和 `preferences.md` 两类受控文档。`MemoryWorker` 根据用户原始问题生成 section patch，服务端校验 section 所有权和证据后事务更新文档；召回时动态生成不落库的 `MEMORY.md` 索引。

长期记忆使用持久化提取任务、租约、重试和幂等处理，不使用 Elasticsearch 向量召回。

启用示例：

```yaml
ai:
  agent:
    enabled: true
    fallback-enabled: true
  memory:
    enabled: true
```

也可以使用环境变量：

```bash
export AI_MEMORY_ENABLED=true
```

记忆接口包括：

```text
GET  /api/v1/memory/sessions/current
POST /api/v1/memory/sessions
GET  /api/v1/memory/sessions
GET  /api/v1/memory/sessions/{id}/messages
GET  /api/v1/memory/documents
```

数据库初始化和设计说明：

- [完整 MySQL DDL（包含 Memory v2）](docs/databases/ddl.sql)
- [完整数据库表设计](docs/databases/database-design.md)

`ddl.sql` 是当前关系型数据库结构的唯一初始化入口。启用记忆前必须完成初始化；启动检查会在 memory 表结构缺失时阻止应用启动。

## 技术栈

### 后端

- Java 17、Spring Boot 3.4.2、Maven
- Spring Web、WebFlux、WebSocket、Spring Security、Spring Data JPA
- MySQL 8、Redis 7、Elasticsearch 8.10
- Apache Kafka 3.2、MinIO 8.5.12、Apache Tika 2.9.1
- DeepSeek 或其他 OpenAI-compatible Chat Completions 服务
- Embedding API（地址、模型和向量维度由配置指定）

### 前端

- Vue 3、TypeScript、Vite、Vue Router、Pinia
- Naive UI、UnoCSS、Markdown 渲染
- pnpm workspace

## 代码结构

```text
src/main/java/com/yizhaoqi/smartpai/
├── agent/          # Agent loop、tool registry、tool call 模型
├── client/         # LLM 与 Embedding 客户端
├── config/         # AI、认证、ES、Kafka、MinIO、WebSocket 配置
├── consumer/       # Kafka 文档处理消费者
├── controller/     # REST API
├── handler/        # WebSocket 连接与聊天处理
├── memory/         # 会话、上下文、长期记忆与 worker
├── model/          # JPA 领域模型
├── repository/     # 数据访问
├── service/        # 上传、解析、检索、RAG fallback 等业务服务
└── utils/          # 通用工具

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
- Agent 工具只接收 `query` 和 `topK`；身份、组织和权限由服务端决定。
- 工具结果、历史记忆和 Markdown section 都按不可信数据注入模型上下文。
- 长期记忆只接受受控分类和可在用户原文中定位的证据，不接受模型任意创建 key。
- 生产环境应限制 WebSocket allowed origins，并使用强 JWT secret、真实服务凭据和非默认管理员密码。

## 相关文档

- [Knowledge Agent 设计](docs/superpowers/specs/2026-08-27-knowledge-agent-design.md)
- [Knowledge Agent 实现计划](docs/superpowers/plans/2026-08-27-knowledge-agent.md)
- [数据库 DDL](docs/databases/ddl.sql)
- [数据库表设计](docs/databases/database-design.md)
