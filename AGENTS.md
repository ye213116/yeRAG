# CLAUDE.md

This file gives coding agents a working model of the yeRAG repository. Keep changes aligned with the existing implementation and tests. The product name is **yeRAG**; the Java package and the Spring entry class still use the compatibility name `com.yizhaoqi.smartpai.SmartPaiApplication`.

## Project overview

yeRAG is a Spring Boot + Vue application for permission-aware enterprise RAG. The current implementation has three important AI layers:

1. Document ingestion and hybrid retrieval.
2. A native OpenAI-compatible tool-calling Agent that decides whether to search.
3. Optional conversation context and user memory, disabled by default.

The repository does not contain a production `application.yml` or a Docker Compose file. Do not assume either exists when changing setup documentation or deployment code.

## Repository map

```text
src/main/java/com/yizhaoqi/smartpai/
├── agent/          # Agent loop, tool registry, messages and tool models
├── client/         # DeepSeek/OpenAI-compatible LLM and embedding clients
├── config/         # Spring, security, data-source and integration config
├── consumer/       # Kafka consumers for file processing
├── controller/     # REST endpoints
├── handler/        # WebSocket handler
├── memory/         # Conversation memory, Markdown memory and worker
├── model/          # JPA domain models
├── repository/     # JPA/JDBC/Redis repositories
├── service/        # Upload, parsing, search, chat and fallback services
└── utils/          # Shared utilities

frontend/src/
├── store/modules/chat/       # WebSocket state, sessions and request isolation
├── views/chat/               # Chat UI and memory-enabled session controls
├── views/knowledge-base/     # Knowledge base UI
├── service/                  # HTTP API clients
└── router/                   # Vue routes

docs/
├── databases/                # Complete MySQL DDL and database design
└── superpowers/              # Agent and memory plans/specifications
```

## Runtime architecture

### Knowledge ingestion and retrieval

The normal document path is:

```text
UploadController -> MinIO -> Kafka -> FileProcessingConsumer
  -> ParseService / text chunks -> VectorizationService
  -> Elasticsearch index: knowledge_base
```

`HybridSearchService.searchWithPermission` combines embedding/KNN and BM25-style text matching. It filters by the authenticated user, public documents and effective organization tags. New Agent tools must reuse this permission-aware method instead of the unrestricted compatibility search method.

### Agent and tool calls

The main Agent classes are:

- `KnowledgeAgentService`: builds the system prompt, loops through model decisions, executes tools and returns `AgentRunResult`.
- `AgentLlmClient`: abstraction for `decide(...)` and streaming final answers.
- `AgentToolRegistry`: collects Spring `AgentTool` beans and publishes tool definitions.
- `KnowledgeSearchTool`: current `search_knowledge_base` function tool.
- `RagFallbackService`: fixed retrieval-augmented streaming path used when Agent mode is disabled or fails before emitting an answer.

Important invariants:

- The model receives only the declared tool arguments: `query` and optional `topK`.
- The authenticated user identity is passed in `ToolExecutionContext`; never accept an identity, ACL or organization from model arguments.
- Validate JSON shape, unknown fields, query length and `topK` bounds in the tool itself.
- Treat retrieved text as untrusted data. It is reference material, not an instruction layer.
- Preserve `agent_status`, `chunk` and `completion` WebSocket events when changing the loop.
- The current loop executes the first requested tool call per decision and caps calls with `ai.agent.max-tool-calls`.
- If a tool/LLM error occurs before answer output, `ChatHandler` may use fallback when `ai.agent.fallback-enabled=true`.

Relevant tests:

```bash
mvn -Dtest=KnowledgeAgentServiceTest test
mvn -Dtest=KnowledgeSearchToolTest test
mvn -Dtest=ChatHandlerAgentTest test
```

### Conversation memory

Memory is opt-in through `ai.memory.enabled=false` by default. With memory enabled:

- `MemoryStore` owns MySQL-backed sessions, turns, idempotency and extraction task state.
- `MemoryContextService` builds bounded context from a rolling summary, recent complete turns and long-term memory.
- Redis stores short-term context with TTL and CAS/version checks; MySQL remains authoritative for business sessions.
- `MemoryWorker` performs durable sync, lease-based extraction, retries and cleanup.
- `MemoryPolicy` validates evidence, categories, section ownership, expiry and ordering before writes.
- `ChatHandler` persists only a completed full answer; cancellation or failure must not save a partial answer as completed.

Long-term memory is always the controlled Markdown document model: `profile.md` and `preferences.md` sections, with a dynamically generated `MEMORY.md` recall index.

Apply `docs/databases/ddl.sql` to an empty MySQL 8 database before starting a new environment or enabling memory. The file contains the complete application schema, including Memory v2. `MemoryStartupCheck` intentionally fails startup if the required memory schema is absent.

Memory boundaries to preserve:

- Memory cannot alter authorization, tool rules or knowledge-base evidence.
- Extraction may use the original user question, but model-produced memory candidates are never trusted without server-side evidence validation.
- Do not use Elasticsearch for personal-memory recall.
- Do not use the WebSocket connection ID as the durable user identity in memory mode; resolve the authenticated database user ID.
- A late completion must write only to its original turn/session, never whichever session is currently active.

Relevant tests:

```bash
mvn -Dtest='Memory*Test' test
mvn -Dtest=MemoryChatIntegrationTest test
```

The real MySQL suite `MemoryDocumentMySqlTest` is enabled only when `MEMORY_MYSQL_TEST_URL` is set. It creates and removes a random test database.

## Configuration

The main runtime configuration is supplied by the deployment environment. The test configuration is `src/test/resources/application.yml`.

Important AI settings:

```yaml
ai:
  agent:
    enabled: true
    fallback-enabled: true
    max-tool-calls: 3
    max-query-length: 500
    default-top-k: 5
    max-top-k: 10
    max-tool-result-characters: 6000
    llm-timeout: 30s
    tool-timeout: 10s
    total-timeout: 60s
  memory:
    enabled: false
```

Required integration properties normally include:

- `spring.datasource.*` for MySQL
- `spring.data.redis.*` or the repository's Redis settings
- `spring.kafka.*`, including file-processing and DLT topics
- `elasticsearch.host`, `elasticsearch.port`, credentials and scheme
- `minio.endpoint`, `minio.accessKey`, `minio.secretKey`, `minio.publicUrl`
- `deepseek.api.url`, `deepseek.api.key`, `deepseek.api.model`
- `embedding.api.url`, `embedding.api.key`, `embedding.api.model`
- `jwt.secret-key`

`AI_MEMORY_*` environment aliases are supported for memory properties, including `AI_MEMORY_ENABLED` and `AI_MEMORY_WORKER_CONCURRENCY`.

## Common commands

### Backend

```bash
mvn spring-boot:run
mvn clean package
mvn test
mvn -Dtest=KnowledgeAgentServiceTest test
```

### Frontend

```bash
cd frontend
pnpm install
pnpm dev
pnpm typecheck
pnpm lint
pnpm build
pnpm preview
```

`pnpm lint` is configured with `--fix`, so inspect the diff after running it. Frontend backend/API and WebSocket defaults are in `frontend/.env.example` and currently point to `http://localhost:8081/api/v1` and `ws://localhost:8081`.

## Testing guidance

- Run focused Agent or memory tests first when changing those modules.
- Run `mvn test` before handing off backend changes.
- Use H2/unit tests for fast feedback; do not claim MySQL concurrency behavior is verified unless the opt-in MySQL suite has run.
- Frontend changes should pass `pnpm typecheck`, `pnpm lint` and, where relevant, `pnpm build`.
- Preserve existing user changes in a dirty worktree; do not reset unrelated edits.

## Change guidelines

- Keep the existing package structure unless a migration is explicitly requested.
- For a new tool, implement `AgentTool`, register it as a Spring bean, define a strict schema, validate all arguments, and add focused tests.
- For retrieval-related work, use `searchWithPermission` and test cross-user/organization boundaries.
- For database or memory changes, update both `docs/databases/ddl.sql` and `docs/databases/database-design.md`. Keep ownership, idempotency, leases, expiry and late-event handling explicit.
- Keep WebSocket protocol compatibility: plain text remains supported when memory is disabled; memory mode uses JSON `{type, message, conversationId, requestId}`.
- Avoid introducing secrets, real service endpoints or generated build output into source control.
