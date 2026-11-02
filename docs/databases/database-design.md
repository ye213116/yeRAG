# yeRAG 数据库表设计

本文档描述 yeRAG 当前完整的 MySQL 8.0 关系模型。空数据库初始化脚本是 [ddl.sql](ddl.sql)，包含用户、组织、文件、文档和统一记忆链路的 10 张表。

## 1. 存储边界

| 存储 | 职责 |
| --- | --- |
| MySQL | 用户、组织、文件元数据、解析文本、会话回合、提取任务和长期记忆文档的权威数据源 |
| Redis | 登录令牌、限流、上传状态以及 memory 短期上下文缓存，不是长期记忆权威来源 |
| Elasticsearch | RAG 文档检索索引，不保存个人长期记忆 |
| MinIO | 原始文件和上传分片对象 |
| Kafka | 文件解析与向量化任务传递，不承担持久业务状态 |

`ddl.sql` 只创建 MySQL 对象。Redis、Elasticsearch、MinIO 和 Kafka 需要分别初始化。

## 2. 表清单

| 领域 | 表 | 说明 |
| --- | --- | --- |
| 身份与组织 | `users` | 用户账号、角色和组织归属 |
| 身份与组织 | `organization_tags` | 可形成树形关系的组织标签 |
| 文件与知识库 | `file_upload` | 用户文件及访问范围 |
| 文件与知识库 | `chunk_info` | 分片上传期间的 MinIO 对象元数据 |
| 文件与知识库 | `document_vectors` | 解析后的文本块及检索权限元数据 |
| Memory | `chat_session` | 业务会话与滚动摘要 |
| Memory | `chat_turn` | 长期保留、可恢复的完整问答回合 |
| Memory | `memory_extraction_task` | 长期记忆异步提取队列 |
| Memory | `memory_document` | 用户当前 Markdown 长期记忆正文 |
| Memory | `memory_document_section` | Markdown section 的稳定身份和顺序屏障 |

## 3. 关系总览

```mermaid
erDiagram
    users ||--o{ organization_tags : creates
    organization_tags o|--o{ organization_tags : parent_of
    users ||--o{ chat_session : owns
    users ||--o{ chat_turn : owns
    chat_session ||--o{ chat_turn : contains
    chat_turn ||--o| memory_extraction_task : produces
    users ||--o{ memory_document : owns
    memory_document ||--o{ memory_document_section : contains

    file_upload ||..o{ chunk_info : file_md5
    file_upload ||..o{ document_vectors : file_md5
```

实线表示数据库外键；虚线表示应用层逻辑关联。文件相关表使用 `file_md5` 和字符串形式的 `user_id` 兼容上传与知识库链路，因此没有指向 `users.id` 的数据库外键。

## 4. 身份与组织

### 4.1 `users`

用户与 memory 所有权的根表。

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 非空，自增 | 主键 |
| `username` | `VARCHAR(255)` | 非空 | 唯一登录名，`uk_users_username` |
| `password` | `VARCHAR(255)` | 非空 | 编码后的密码，不存明文 |
| `role` | `ENUM('USER','ADMIN')` | 默认 `USER` | 应用角色 |
| `org_tags` | `VARCHAR(255)` | 可空 | 逗号分隔的组织标签集合 |
| `primary_org` | `VARCHAR(255)` | 可空 | 主组织标签 |
| `created_at` | `DATETIME(6)` | 当前时间 | 创建时间 |
| `updated_at` | `DATETIME(6)` | 当前时间，自动更新 | 最后修改时间 |

删除用户前必须显式处理其组织、会话和 memory 数据；DDL 不配置级联删除，避免误删审计链和长期记忆。

### 4.2 `organization_tags`

组织标签采用邻接表表达层级。

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `tag_id` | `VARCHAR(255)` | 非空 | 主键，业务标签 ID |
| `name` | `VARCHAR(255)` | 非空 | 展示名称 |
| `description` | `TEXT` | 可空 | 描述 |
| `parent_tag` | `VARCHAR(255)` | 可空 | 自关联父标签；父标签删除时置空 |
| `created_by` | `BIGINT` | 非空 | 外键到 `users.id` |
| `created_at` | `DATETIME(6)` | 当前时间 | 创建时间 |
| `updated_at` | `DATETIME(6)` | 当前时间，自动更新 | 最后修改时间 |

索引 `ix_organization_tags_parent` 支持向下查找子组织，`ix_organization_tags_creator` 支持按创建者查询。

## 5. 文件与知识库

### 5.1 `file_upload`

保存完整文件的业务元数据。实际文件内容存放在 MinIO。

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 非空，自增 | 主键 |
| `file_md5` | `VARCHAR(32)` | 非空 | 完整文件 MD5 |
| `file_name` | `VARCHAR(255)` | 非空 | 原始文件名 |
| `total_size` | `BIGINT` | 非空 | 文件字节数 |
| `status` | `TINYINT` | 默认 `0` | `0` 上传中，`1` 已完成 |
| `user_id` | `VARCHAR(64)` | 非空 | 上传链路中的认证用户标识 |
| `org_tag` | `VARCHAR(255)` | 可空 | 所属组织标签 |
| `is_public` | `TINYINT(1)` | 默认 `0` | 是否公开 |
| `created_at` | `DATETIME(6)` | 当前时间 | 创建时间 |
| `merged_at` | `DATETIME(6)` | 可空 | 分片合并完成时间 |

同一用户不能重复创建同一 `file_md5`，由 `uk_file_upload_md5_user` 保证。用户、组织和公开性均有查询索引。

同一内容允许多个上传者。删除必须同时限定 `file_md5` 与经认证的 `user_id`，分别清理当前用户的上传记录、文本块和ES索引，不能按MD5全局删除。MinIO的 `merged/{md5}` 是共享对象，存在其他上传记录时保留；最后一位上传者删除时清理该对象和 `chunk_info` 上传分片元数据；不按非唯一的原始文件名删除兼容旧路径对象。外部删除失败返回失败，保留SQL记录供重试；SQL与ES/MinIO没有跨系统原子事务，不据此宣称并发上传/删除的完整一致性。

### 5.2 `chunk_info`

记录上传阶段的物理文件分片。分片合并后可以按业务清理，不能把它当作文档语义切块。

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 非空，自增 | 主键 |
| `file_md5` | `VARCHAR(32)` | 非空 | 完整文件 MD5 |
| `chunk_index` | `INT` | 非空 | 上传分片顺序，从 0 开始 |
| `chunk_md5` | `VARCHAR(32)` | 非空 | 单个分片 MD5 |
| `storage_path` | `VARCHAR(255)` | 非空 | MinIO 对象路径 |

`ix_chunk_info_file_order(file_md5, chunk_index)` 支持按顺序合并文件。

### 5.3 `document_vectors`

保存解析后的语义文本块和访问控制元数据。在线向量检索索引位于 Elasticsearch；该表名称沿用项目现有模型。

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `vector_id` | `BIGINT` | 非空，自增 | 主键 |
| `file_md5` | `VARCHAR(32)` | 非空 | 来源文件 MD5 |
| `chunk_id` | `INT` | 非空 | 文本块顺序 |
| `text_content` | `LONGTEXT` | 可空 | 解析后的文本 |
| `model_version` | `VARCHAR(32)` | 可空 | Embedding 模型版本 |
| `user_id` | `VARCHAR(64)` | 非空 | 上传者标识 |
| `org_tag` | `VARCHAR(50)` | 可空 | 权限组织标签 |
| `is_public` | `TINYINT(1)` | 默认 `0` | 是否公开 |

`file_md5` 用于整文件重建或删除；`user_id`、`org_tag`、`is_public` 用于 RAG 检索前的访问范围过滤。

## 6. Memory

Memory 只保存两类长期记忆：

| `memory_type` | 生成文件 | 内容 |
| --- | --- | --- |
| `PROFILE` | `profile.md` | 用户稳定背景、角色、技能和知识水平 |
| `PREFERENCE` | `preferences.md` | 用户稳定的回答、格式和交互偏好 |

运行时的 `MEMORY.md` 是召回索引，由当前有效 section 确定性生成，不单独落库。

### 6.1 `chat_session`

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `CHAR(36)` | 非空 | UUID 主键 |
| `user_id` | `BIGINT` | 非空 | 外键到 `users.id` |
| `status` | `VARCHAR(20)` | 非空 | `OPEN`、`CLOSED`、`DELETED` |
| `last_activity_at` | `DATETIME(6)` | 非空 | 最近一次被接受的活动时间 |
| `context_expires_at` | `DATETIME(6)` | 可空 | 退役兼容字段；不再控制摘要或会话生命周期，新数据为 NULL |
| `created_at` | `DATETIME(6)` | 非空 | 创建时间 |
| `closed_at` | `DATETIME(6)` | 可空 | 关闭时间 |
| `revision` | `BIGINT` | 默认 `0` | 会话状态版本 |
| `context_summary` | `TEXT` | 可空 | MySQL 持久化的滚动摘要 |
| `summary_covered_turn_seq` | `BIGINT` | 默认 `0` | 已纳入摘要的最后回合 ID |
| `summary_revision` | `BIGINT` | 默认 `0` | 摘要条件更新版本 |
| `active_turn_id` | `BIGINT` | 可空 | 当前执行中的回合 ID |

`active_turn_id` 不设置循环外键：`chat_turn` 本身已经引用 `chat_session`，当前回合一致性由锁定 `users.id` 的事务维护。一个用户同时只应有一个可写的 `OPEN` 会话。

### 6.2 `chat_turn`

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 非空，自增 | 主键 |
| `session_id` | `CHAR(36)` | 非空 | 外键到 `chat_session.id` |
| `user_id` | `BIGINT` | 非空 | 外键到 `users.id`，便于所有权校验和按用户排序 |
| `user_turn_seq` | `BIGINT` | 可空 | 创建后写入回合 ID，作为用户级顺序屏障 |
| `request_id` | `VARCHAR(80)` | 非空 | 客户端幂等键 |
| `request_hash` | `CHAR(64)` | 非空 | 原始问题的 SHA-256 |
| `question` | `MEDIUMTEXT` | 可空 | 原始问题，保留至用户删除会话 |
| `answer` | `MEDIUMTEXT` | 可空 | 完整回答，保留至用户删除会话 |
| `status` | `VARCHAR(20)` | 非空 | `RUNNING`、`COMPLETED`、`CANCELLED`、`FAILED` |
| `context_sync_status` | `VARCHAR(20)` | 默认 `NONE` | `NONE`、`PENDING`、`SYNCED`、`EXPIRED` |
| `context_sync_attempts` | `INT` | 默认 `0` | 短期上下文同步尝试次数 |
| `context_next_attempt_at` | `DATETIME(6)` | 可空 | 下次同步时间 |
| `created_at` | `DATETIME(6)` | 非空 | 创建时间 |
| `completed_at` | `DATETIME(6)` | 可空 | 完成时间 |
| `content_expires_at` | `DATETIME(6)` | 可空 | 退役兼容字段；不再自动清理正文，新数据为 NULL |

关键约束：

- `(user_id, request_id)` 唯一，保证客户端重试幂等。
- `(user_id, user_turn_seq)` 唯一，保证用户回合顺序。
- `request_id` 重用时必须同时匹配 `request_hash` 和目标会话。
- 完成回合后才创建长期记忆提取任务。

管理员聊天记录接口 `GET /api/v1/admin/conversation` 按 `userid`、`start_date`、`end_date` 查询此表；两端日期均包含在内，按服务端时区解释。仅管理员可跨会话查看所选用户已完成且正文完整的问答，最多返回最近 200 个回合，并按时间正序展开为用户和助手消息。普通用户仍通过 `/api/v1/memory/sessions/{id}/messages` 查看自己的会话；不读取已停用的 `conversations` 表。此查询不需要数据库结构迁移。

历史摘要与近期原文使用独立预算：16,384 tokens 触发压缩，压缩后目标 8,192 tokens，其中摘要最多 2,048 tokens、近期原文最多 6,144 tokens。当前问题、系统提示、长期 Markdown 记忆、检索证据、工具定义及结果另计，模型客户端发送前统一检查总输入预算。使用配置模型的 tokenizer，字节上限仅作资源保护。

`MemoryContextService.build` 在调用 Agent 前同步生成摘要，模型调用在事务外。正文、`summary_covered_turn_seq` 和递增的 `summary_revision` 由一条带版本及非删除条件的 UPDATE 原子提交；冲突重新读取，不覆盖新版本，失败不推进覆盖位置。保留旧摘要及预算内近期原文，通过 `context_reduced` 通知用户。超过预算的单个近期回合保留带回合 ID、截断标记的片段，可用历史工具续读。

近期上下文读取使用 `id < before` 向前游标，每页最多 200 轮；摘要使用 `id > covered` 向后游标，每批最多 200 轮，并以模型输入预算进一步限制。页满时继续读取，不能把单页上限当作历史终点。单次请求最多尝试 32 个摘要批次，达到保护上限或遇到无法完整入模的单轮时保留旧边界并提示缩减，后续请求可继续推进。只有实际送入模型并成功保存的完整前缀才被标记为已摘要。

普通历史展示接口 `GET /api/v1/memory/sessions/{id}/messages?before=<turnId>` 返回该游标之前最近 200 轮，按时间正序展开，每条消息带 `turnId`；前端通过“加载更早消息”继续翻页。

两个本地只读 Agent 工具使用 MySQL：`search_conversation_history` 按关键词查当前会话，`read_conversation_turns` 读取指定回合及最多前后各 2 轮，使用角色和 Unicode 码点 offset 续读。用户和当前会话由服务器注入，模型不能指定身份或会话；查询限定所有者和已完成回合，工具完成前再次检查删除状态。每次返回 JSON 默认最多 2,048 tokens；搜索使用 `before` 游标。历史助手回答不是已验证事实。历史工具可用于各来源模式以找回对话，但不能替代本轮来源限制下的知识库或网页证据。

### 6.3 `memory_extraction_task`

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 非空，自增 | 主键 |
| `turn_id` | `BIGINT` | 非空、唯一 | 外键到来源 `chat_turn.id` |
| `status` | `VARCHAR(20)` | 非空 | `PENDING`、`RUNNING`、`DONE`、`FAILED`、`EXPIRED` |
| `attempts` | `INT` | 默认 `0` | 领取次数 |
| `next_attempt_at` | `DATETIME(6)` | 非空 | 最早可再次领取时间 |
| `lease_until` | `DATETIME(6)` | 可空 | worker 租约到期时间 |
| `lease_token` | `CHAR(36)` | 可空 | 当前 worker 的 UUID token |
| `last_error_code` | `VARCHAR(80)` | 可空 | 最后一次受限错误码 |
| `failure_stage` | `VARCHAR(32)` | 可空 | candidate / integration / commit 失败阶段 |
| `created_at` | `DATETIME(6)` | 非空 | 创建时间 |
| `updated_at` | `DATETIME(6)` | 非空 | 更新时间 |

一个完成回合最多对应一个提取任务。worker 使用 `FOR UPDATE SKIP LOCKED` 领取任务；同一用户必须按 `user_turn_seq` 顺序处理，不同用户可以并行。提交时必须再次校验任务状态、租约 token、租约期限和来源回合。

### 6.4 `memory_document`

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 非空，自增 | 主键 |
| `user_id` | `BIGINT` | 非空 | 外键到 `users.id` |
| `memory_type` | `VARCHAR(20)` | 非空 | `PROFILE` 或 `PREFERENCE` |
| `content` | `MEDIUMTEXT` | 非空 | 完整 Markdown 正文 |
| `version` | `BIGINT` | 默认 `0` | 单调递增的文档版本 |
| `created_at` | `DATETIME(6)` | 非空 | 创建时间 |
| `updated_at` | `DATETIME(6)` | 非空 | 正文最后更新时间 |

`(user_id, memory_type)` 唯一，因此每个用户最多拥有一份 profile 和一份 preferences 文档。空提取结果不会创建空文档。

### 6.5 `memory_document_section`

| 字段 | 类型 | 可空/默认 | 约束与说明 |
| --- | --- | --- | --- |
| `id` | `CHAR(36)` | 非空 | 稳定 section UUID 主键 |
| `document_id` | `BIGINT` | 非空 | 外键到 `memory_document.id` |
| `heading` | `VARCHAR(120)` | 非空 | Markdown 二级标题 |
| `description` | `VARCHAR(240)` | 非空 | 模型生成的发现描述，默认限制 120 码点 |
| `section_version` | `BIGINT` | 默认 `1` | 单调递增的 section 版本 |
| `last_applied_turn_seq` | `BIGINT` | 默认 `0` | 最近一次成功写入的用户回合顺序 |
| `deleted` | `TINYINT(1)` | 默认 `0` | 逻辑删除标记 |
| `created_at` | `DATETIME(6)` | 非空 | 创建时间 |
| `updated_at` | `DATETIME(6)` | 非空 | 更新时间 |

section UUID 不写入 Markdown 正文。正文负责向模型提供可读内容，section 表负责身份、所有权、逻辑删除和防止旧回合覆盖新内容。删除 section 时保留 tombstone，只有明确引用原 UUID 的后续写入才能恢复。

### 6.6 `memory_document_version`

每个变更文档在同一事务中追加快照：`document_id`、`version`、完整 `content`、`metadata_json`（section ID/描述/版本/删除标记/轮次屏障）、`change_summary`、`patches_json`（语义决定、理由、原话来源）、`trigger_turn_id`、`created_at`。`(document_id,version)` 唯一；触发回合 ID 不设置外键，已确认的长期记忆版本及其来源摘录不会因删除短期会话而自动丢失。

## 7. Memory 写入与召回

默认读取链路是元数据索引 → Agent 按需读取正文。`MemoryStore.catalog` 只查询文档版本及 section 元数据，`MemoryIndexService` 动态构建 `MEMORY.md`，不解析正文。索引最多 1,024 tokens；完整列表超预算时改为文档概要并提示使用 `list_memory_sections`。描述和标题只用于发现，不能据此推断具体偏好或用户事实。

`list_memory_sections(documentType,cursor?)` 返回有界元数据；`read_memory(documentType,sectionIds?,cursor?)` 批量读取正文、来源、版本，单次返回 JSON 最多 2,048 tokens。续读位置绑定文档版本，变更后要求重新读取。用户身份来自服务器，模型不能指定 userId。工具在自动、知识库和联网模式都可用，但不代替各模式要求的事实来源。每轮最多调用两次，与检索工具分别计数，共享 Agent 总超时；已读取正文留在本轮消息中。

写入链路：

```text
COMPLETED turn -> durable task / per-user ordered lease
  -> candidate model (current user quote + bounded recent context + metadata index)
  -> empty candidates: DONE, no second call
  -> read matched section bodies + versions
  -> integration model: complete section patch + decision/reason/description/sources
  -> user -> task -> documents sorted by type -> sections sorted by UUID
  -> validate lease, provided-source ownership/quote, target, expected document/section version, turn barrier
  -> update Markdown + metadata + versions + audit snapshots + DONE in one transaction
```

候选来源必须来自本次服务器提供的近期回合，并包含当前用户原话。旧消息用于理解指代；助手建议只有当前用户明确确认才可采纳，需保留确认和被确认内容的来源。模型负责稳定性、临时性、引用/假设、重复/补充/修正等语义判断；程序不再按引号、代码围栏、“最近”等词整批拒绝，而是验证结构、精确原话引用、所有权及并发边界。语义正确性仍需基于理由和版本纠错。

数据库操作仍只有 UPSERT_SECTION、DELETE_SECTION、空 Patch。语义决定为 CREATE、EXTEND、REFINE、DELETE、RESTORE。UPSERT 始终提交完整 section。明确删除需当前用户来源中的忘记意图，保留 tombstone；旧回合屏障不能恢复它。版本冲突会重新读取正文、重新调用整合模型，不重放旧 Patch。任务保留失败阶段和受限原因码，失败沿用租约和重试。

正文默认每文档最多 32 KiB；元数据默认每文档最多 64 个 section，合计索引存储预算 8,192 tokens，防止通过不断增加条目无限膨胀。动态注入预算与元数据存储预算分别控制。

版本查询接口：`GET /api/v1/memory/documents/{type}/versions?before=<version>` 与 `GET /api/v1/memory/documents/{type}/versions/{version}`。显式用户回滚使用 `POST /api/v1/memory/documents/{type}/rollback`，提交 targetVersion、expectedVersion、reason；回滚也创建新快照，section 版本和轮次屏障只增不减。模型没有写入或回滚工具。

本次只启用 PROFILE / PREFERENCE；项目记忆需要未来由服务器提供稳定项目标识，不能由模型根据“这个项目”创建全局项目记录。

## 8. 生命周期与一致性

- MySQL 是 session、turn、滚动摘要、task 和长期 Markdown 文档的权威来源。
- Redis 的 `memory:user:{uid}:current` 与 `memory:session:{sid}:context` 只是有 TTL 的短期缓存；缓存丢失后从 MySQL 恢复。
- 原始问答与滚动摘要默认不自动到期。`context_expires_at`、`content_expires_at` 退役，仅保留兼容列；旧的到期时间不再限制读取、幂等重放、摘要或长期提取。缓存 TTL 默认仍是 7 天。
- 空闲不切换会话。用户显式创建新会话或调用 `POST /api/v1/memory/sessions/{id}/resume` 恢复旧会话；保持一个 OPEN 会话，切换时取消旧会话活动回合。
- `DELETE /api/v1/memory/sessions/{id}` 锁定用户后删除关联提取任务和所有回合、清空摘要，仅保留不含正文的 DELETED 会话标记，防止旧 Redis 迁移重建原 UUID。列表和所有访问接口排除此标记。此前已提取的跨会话长期 Markdown 记忆保持其原有生命周期。
- 完成回合与删除都锁定 `users.id`；长期 patch 提交按 user → task → document 顺序加锁并重读任务。任务删除后迟到 patch 不执行，迟到回答完成返回 false，迟到摘要条件更新失败。
- Redis 缓存写入使用 Lua 检查删除标记，删除时原子写入短期删除标记并清理缓存。读取始终先验证 MySQL 所有权和会话版本；即使 Redis 在删除期间不可用，旧缓存也不能用于恢复会话。
- 已完成的长期记忆 section 不随问答正文过期；删除通过 tombstone 表示。
- 模型调用发生在数据库事务外；最终 patch 应用与任务完成状态在同一事务内提交。
- 下一轮直接从 MySQL 读取已完成问答，不等待 Worker；缓存的会话版本、摘要版本、预算版本必须匹配。Worker 只刷新缓存与异步提取长期 Markdown 记忆，不生成短期摘要。
- 会话写入先锁定 `users.id`，避免同一用户并发创建多个活动回合。
- 所有用户级查询必须带认证后的数据库 `user_id`，不能使用 WebSocket 连接 ID 代替所有者身份。

## 9. 初始化与校验

创建空数据库并执行完整 DDL：

```bash
mysql -u root -p -e "CREATE DATABASE yerag CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql -u root -p yerag < docs/databases/ddl.sql
```

推荐将生产环境的 `SPRING_JPA_HIBERNATE_DDL_AUTO` 设为 `validate` 或 `none`，不要依赖 Hibernate 自动更新生产表结构。`MemoryStartupCheck` 会检查 memory 表、版本快照表、元数据版本及摘要字段；缺失时应用会拒绝启动。

该 DDL 面向空数据库，不包含 `DROP TABLE`。已有环境先执行 [升级 SQL](migrations/20260927_durable_short_term_memory.sql)，再部署新版本。旧 `conversations` 表保留在现有数据库中，但聊天不再读写它；旧 Redis 有效会话和摘要由可重试迁移任务导入。

## RAG 运行证据（2026-10-23）

默认 `ai.rag.evidence-backend=local`，不访问这三张表。选择 `minio` 时，已有数据库执行 `migrations/20261023_rag_evidence.sql`（不要重跑完整 DDL），并创建私有 MinIO bucket `rag-evidence`。新环境仍使用完整 DDL。

- `rag_run`：运行 ID、父运行、服务端身份、终态、证据完整状态、过期时间和当前对象指针。owner 明确区分 `name:` 与 `id:`，避免纯数字用户名与数据库 ID 混淆。
- `rag_stage_run`：每个运行的事件顺序、阶段名、耗时和内容 hash；正文保存在对象中。
- `rag_artifact`：运行产生的全部对象版本，用于追踪和清理。

先上传不可变 `runId/sha256.json`，再在 SQL 事务中发布索引，避免暴露未上传对象。读取同时检查身份、过期时间和对象 hash。对象上传成功而事务失败会留下不可见孤儿对象，bucket 必须配置生命周期过期规则（至少覆盖配置的保留期，建议保留期加 2 天）。应用按小时删除过期运行的已登记对象，然后删除索引；远端删除失败保留索引以便重试。SQL 和 MinIO 不存在跨系统原子事务，不承诺恰好一次上传。证据故障不会把正常问答改成失败；导出的 INCOMPLETE/RUNNING 运行不参与确定归因。

### 2026-11-02 持久会话迁移

已有数据库在部署前还需应用 [20261102_persistent_conversation_history.sql](migrations/20261102_persistent_conversation_history.sql)。必须先停止旧版本服务和 Worker，再执行迁移并启动新版；旧 Worker 会继续按旧规则清空到期内容，不能与新版混跑。迁移将两个退役列改为可空并清除旧到期时间，增加 DELETED 状态约束。旧版本已清空的原文无法由迁移恢复。

### 2026-11-02 按需 Markdown 记忆迁移

在持久会话迁移之后执行 [20261102_on_demand_markdown_memory.sql](migrations/20261102_on_demand_markdown_memory.sql)。为旧文档创建 version=1 初始快照，为既有 section 添加泛化发现描述及 section_version=1，保持所有原 UUID、正文、删除标记和轮次屏障。迁移期间停止旧 Worker，先备份；空库直接使用完整 DDL。
