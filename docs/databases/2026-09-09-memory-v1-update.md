# 短期与长期记忆 V1 库表更新说明

本文对应本次代码实现。迁移文件为 [2026-09-09-memory-v1.sql](migrations/2026-09-09-memory-v1.sql)。迁移已在当前本地开发库执行并完成冒烟验证；其他部署环境仍须独立执行，代码交付不代表生产库已经迁移。

## 1. 变更范围

| 存储 | 更新内容 |
| --- | --- |
| MySQL 8.0 | 新增 chat_session、chat_turn、memory_extraction_task、memory_slot、memory_version 共 5 张表 |
| Redis | 新增 memory:v1 独立键空间，保留旧聊天、JWT 和组织缓存 |
| Elasticsearch | 不修改 knowledge_base，不新增个人记忆索引 |
| Kafka / MinIO | 不新增记忆 topic 或对象结构 |

长期记忆的权威数据与版本历史在 MySQL，第一版直接加载用户有效记忆。个人记忆不调用 Embedding，不使用 ES 同步 outbox。会话摘要只用于当前会话，不作为长期记忆提取来源。

数据访问使用 JdbcTemplate 和 TransactionTemplate，复用现有数据源/事务管理器，未增加 ORM 实体或数据库驱动依赖。因此 Hibernate ddl-auto 不会替本功能创建新表，必须显式执行迁移。旧 conversations 实体/表保持原样，新聊天启用后使用 chat_turn。仓库旧 ddl.sql 未包含 conversations 的历史缺口不由本次迁移补建，也不影响新表工作。

## 2. MySQL 表结构

### chat_session：业务会话

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | CHAR(36) PK | 业务会话 UUID，与 WebSocket sessionId 分离 |
| user_id | BIGINT FK | 关联 users.id |
| status | VARCHAR(20) | OPEN / CLOSED |
| last_activity_at | DATETIME(6) | 最近有效用户请求时间，用于空闲切换 |
| context_expires_at | DATETIME(6) | Redis 上下文恢复截止时间 |
| created_at / closed_at | DATETIME(6) | 创建与关闭时间 |
| revision | BIGINT | 状态修改版本 |
| active_turn_id | BIGINT nullable | 当前在途回合标识，完成/取消时条件清除 |

索引：`(user_id, status, last_activity_at)`。新建与空闲切换在用户行锁保护的短事务内关闭旧会话并新建，缓存失效不等同于业务会话结束。

### chat_turn：回合账本与完整历史

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT AUTO_INCREMENT PK | 回合 ID |
| session_id / user_id | CHAR(36) / BIGINT FK | 会话及其所属用户 |
| user_turn_seq | BIGINT | 接收时分配；等于本回合 id，允许跳号 |
| request_id / request_hash | VARCHAR(80) / CHAR(64) | 客户端幂等 ID 与问题 SHA-256 |
| question / answer | MEDIUMTEXT nullable | 原始用户输入、完整最终回答；到期清空 |
| status | VARCHAR(20) | RUNNING / COMPLETED / CANCELLED / FAILED |
| context_sync_status | VARCHAR(20) | NONE / PENDING / SYNCED / EXPIRED |
| context_sync_attempts | INT | Redis 同步失败次数 |
| context_next_attempt_at | DATETIME(6) | 下一次补偿时间 |
| created_at / completed_at | DATETIME(6) | 接收和完成时间 |
| content_expires_at | DATETIME(6) | 正文保留截止时间 |

唯一约束：`(user_id, request_id)`、`(user_id, user_turn_seq)`。索引：`(session_id, user_turn_seq)`、`(context_sync_status, context_next_attempt_at)`。

接收请求时短暂锁定 users 行，插入回合并赋予顺序号；锁在模型调用前释放。重复 request_id 还要匹配内容哈希和请求会话，不同内容返回冲突。问题与最终回答持久化不依赖 Redis 成功。

完成事务同时更新回答、回合状态、Redis 待同步状态，并在提取开关开启时插入提取任务。停止/取消后的迟到完成不会创建任务。历史 API 目前最多返回最近 200 个保留期内完整回合，不含未完成回答。

### memory_extraction_task：异步提取任务

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id / turn_id | BIGINT PK / BIGINT UNIQUE FK | 一个回合至多一个提取任务 |
| status | VARCHAR(20) | PENDING / RUNNING / DONE / FAILED / EXPIRED |
| attempts / next_attempt_at | INT / DATETIME(6) | 尝试次数和退避时间 |
| lease_until / lease_token | DATETIME(6) / CHAR(36) | 租约截止时间及本次领取令牌 |
| last_error_code | VARCHAR(80) | 不含正文的错误分类 |
| created_at / updated_at | DATETIME(6) | 任务生命周期时间 |

索引：`(status, next_attempt_at)`、`(status, lease_until)`。领取使用 MySQL 8 的 `FOR UPDATE SKIP LOCKED`，模型调用在事务外执行。结果提交前锁定任务并检查令牌与租约；被接管的旧 worker 无权写入记忆。结果写入和任务完成处于同一事务。

失败采用指数退避，达到次数上限为 FAILED。需要重放时先检查来源正文是否仍存在，在运维控制下将指定失败任务改为 PENDING、重置 attempts 并清空租约；本版未提供管理员任务管理 UI。不得重放已 DONE 的任务来制造新事实。

### memory_slot：事实槽位

字段：`id BIGINT PK`、`user_id BIGINT FK`、`memory_key VARCHAR(255)`、`current_version_id BIGINT nullable`、`revision BIGINT`、`last_applied_turn_seq BIGINT`。

`UNIQUE(user_id, memory_key)`，memory_key 使用 utf8mb4_bin。槽位键由服务端生成，当前支持：

- `preference.response.verbosity`：concise / detailed。
- `profile.occupation`：明确职业原文。
- `profile.skill.<element>`：java、go、python、javascript、typescript、rust、kotlin；golang 规范化为 go。
- `goal.interview.company.<element>`：bytedance、alibaba、tencent；中文别名规范化。

未支持分类、无法规范化的集合项和项目决策不写入。受控枚举集中在 MemoryPolicy，可按后续需求扩展。

同值重新确认不制造内容新版本，但会推进 last_applied_turn_seq；旧回合晚到不能覆盖新确认。DELETE 保留槽位与顺序屏障，清空当前指针，防止旧任务复活。

### memory_version：版本与审计

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id / slot_id / version | BIGINT | 主键、槽位外键、版本号 |
| category / attribute | VARCHAR(30) / VARCHAR(80) | 受控分类与属性 |
| operation | VARCHAR(10) | ADD / UPDATE / DELETE，补充明确的变更审计 |
| value_json | JSON | 当前形式为 `{"value":"…"}` |
| status | VARCHAR(20) | ACTIVE / SUPERSEDED / DELETED / EXPIRED |
| expires_at | DATETIME(6) nullable | 临时偏好/目标的到期时间 |
| source_turn_id | BIGINT FK | 来源用户回合 |
| evidence | TEXT nullable | 来源原文连续证据片段 |
| created_at / invalidated_at | DATETIME(6) | 创建与失效时间 |

唯一约束：`(slot_id, version)`。UPDATE 使旧版本失效并追加新版本。DELETE 追加带来源和证据的 DELETED 审计版本，同时使旧版本失效，current_version_id 为空。当前指针的槽位归属在事务中校验；不设置跨表循环外键。

没有级联删除。正文到期清理保留回合 ID、槽位、版本和幂等信息；删除账号前需要独立的数据清理方案。逻辑删除不等于物理抹除个人数据。

## 3. Redis 更新

| Key | 类型 | 内容 |
| --- | --- | --- |
| `memory:v1:user:{uid}:current` | String | 当前业务会话 ID，仅为缓存提示；SQL 负责最终选择和归属校验 |
| `memory:v1:session:{sid}:context` | Hash | 摘要和近期完整问答 |

`{uid}`/`{sid}` 是占位符。uid 是 users.id 的十进制文本，绝不是用户名或 JWT。

Hash 字段为 `owner_user_id`、`schema_version`、`epoch`、`revision`、`summary`、`covered_through_turn_seq`、`last_completed_turn_seq`、`recent_turns_json`。epoch 是缓存实例标识，与 revision 一起防止 key 过期重建后的旧 CAS 写回；schema_version 当前为 1。近期项包含完整的 question/answer 和回合标识。

使用独立 StringRedisTemplate；不更换已有 RedisConfig 的全局序列化器。Lua 原子检查 epoch/revision 并更新上下文与 TTL。失败后从 MySQL 已完成回合重建，按回合去重；不能把 Redis 写入与 MySQL 提交宣称为跨库原子事务。

默认 TTL 7 天，同时受 chat_session.context_expires_at 限制。重放老任务不延长会话截止时间。V1 不使用 Redis 缓存长期记忆。

旧键 `user:{username}:current_conversation` 与 `conversation:{id}` 只按已认证用户的准确用户名读取，不扫描 Redis，不尝试把纯数字用户名转换成用户 ID。首次进入且没有新会话时，最多迁入旧格式的 20 条消息，保留有效问答对；剩余 TTL 限制迁入数据的保留期。旧时间无时区，按应用时区解析，缺失/非法时记录迁入时间。迁入回合不创建长期提取任务，旧键不删除。

## 4. 部署与启用

1. 备份目标数据库，确认部署账户拥有新建表/索引权限、users 表已经存在；保持 `ai.memory.enabled=false`。
2. 在明确选定的业务数据库执行迁移一次，例如：

   ```bash
   mysql -u <部署用户> -p <业务数据库> < docs/databases/migrations/2026-09-09-memory-v1.sql
   ```

3. 检查 5 张表、外键和唯一索引。MySQL DDL 可能逐条提交；部分失败时检查已创建结构后补齐，不应直接重复运行整个脚本或删除业务数据。
4. 配置数据库连接/会话时区为 UTC；建议 JDBC 参数包含 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。完成隔离环境验证后启用功能并重启应用。
5. 前端根据 WebSocket connection 事件的 memoryEnabled 显示新建与历史会话入口。启用时缺少 userId claim 的旧 token 需要重新登录。

启用后启动检查会验证关键表字段，缺少迁移时启动失败并给出迁移路径，不会自动修改数据库。

代码中的 `MemoryEnvironmentPostProcessor` 会把根目录 `.env` 属性源里的 `AI_MEMORY_*` 名称映射为 `ai.memory.*`；显式配置的标准 Spring 属性优先，操作系统环境变量仍按 Spring 规则处理。因此无需把记忆配置写入被忽略的本地 `application.yml`。本地前端首次启动可执行 `cp frontend/.env.example frontend/.env`，然后在 `frontend` 目录执行 `pnpm install`、`pnpm dev`。

配置示例（放入部署使用的 application 配置，不覆盖现有凭据）：

```yaml
ai:
  memory:
    enabled: true                 # 代码默认 false，迁移后显式开启
    short-term-enabled: true
    extraction-enabled: true
    recall-enabled: true
    idle-timeout: 30m
    context-ttl: 7d
    content-retention: 7d
    audit-evidence-retention: 365d
    llm-timeout: 30s
    task-lease: 2m
    run-timeout: 3m
    worker-delay-ms: 2000
    max-attempts: 5
    max-recent-turns: 10
    max-memories: 30
    max-input-bytes: 48000
    max-question-bytes: 12000
    summary-bytes: 4000
    memory-bytes: 3200
    min-confidence: 0.9
```

本版没有模型专用 tokenizer，明确使用 UTF-8 字节预算而非声称精确 token 计数。完整序列化请求（含工具定义与结果）在每次模型调用前再次检查；输出 token 上限继续由 ai.generation.max-tokens 控制。部署者应按实际模型容量设置输入字节预算并预留输出空间，不能将 48000 字节直接当作 48000 tokens。

摘要失败或过长时保留旧摘要并使用有界近期完整问答，前端显示上下文缩减提示。目标无明确期限默认 90 天，包含“最近/暂时/这几天”的临时偏好默认 7 天。这些是本次落地采用的默认策略，均不意味着旧原文被当成新的确定事实。

## 5. API 与兼容行为

- `GET /api/v1/memory/sessions/current`：当前会话（必要时新建/迁入旧历史）。
- `POST /api/v1/memory/sessions`：主动新建，关闭旧会话并取消旧在途回合。
- `GET /api/v1/memory/sessions`：当前用户最近 100 个会话。
- `GET /api/v1/memory/sessions/{id}/messages`：本人的保留期内最近 200 个完整回合。
- `GET /api/v1/memory/facts`：本人有效记忆；管理员不会因此获得他人记忆权限。

响应沿用 code/message/data 格式。新前端发送结构化 chat 消息：`type/message/conversationId/requestId`；回答事件携带 conversationId/turnId/requestId，前端丢弃旧回合事件。旧普通文本输入仍支持，但服务端每次生成新请求 ID，因此只有新版显式 requestId 支持跨重发幂等。

旧用户历史接口在启用时读取新账本；管理员原有问答历史接口沿用已有权限，读取新回合但不读取个人记忆版本。关闭总开关后恢复旧聊天路径，新账本仍保留；新期间的聊天不会自动反向复制到旧 Redis 窗口。

## 6. 核验、恢复与回滚

只读检查示例：

```sql
SHOW CREATE TABLE chat_turn;
SHOW INDEX FROM memory_slot;
SELECT status, COUNT(*) FROM memory_extraction_task GROUP BY status;
SELECT context_sync_status, COUNT(*) FROM chat_turn GROUP BY context_sync_status;
SELECT slot_id, COUNT(*) FROM memory_version WHERE status='ACTIVE'
GROUP BY slot_id HAVING COUNT(*) > 1;
SELECT s.id FROM memory_slot s JOIN memory_version v ON v.id=s.current_version_id
WHERE v.slot_id<>s.id;
```

最后两条应为空。到期记忆在查询时排除，后台再标记 EXPIRED；过期版本指针可能暂时仍在槽位上，不能单凭指针非空判断记忆可召回。

Redis 同步失败按回合状态重试，提取失败按任务租约重试。MySQL 已保存的回答不重新生成。若答案已流出但数据库尚未提交时进程退出，无法保证恢复这段尚未持久化的答案；客户端会尽可能收到保存失败提示。

回滚先关闭 ai.memory.enabled 并重启服务，保留新表和 Redis 新键，不执行 DROP。不要为回滚清空 Redis 或删除审计表。是否物理清除数据需要独立确认与备份。

## 7. 验证记录与限制

`mvn -q test` 已通过：58 个测试，0 failure、0 error、0 skipped。后端测试包含 H2 MySQL 模式下的幂等、并发请求、版本替换、同值顺序屏障、逻辑删除防复活、租约接管、内容过期、旧历史迁入、MySQL/H2 时间类型兼容、`.env` 记忆配置别名，以及模型输入来源和摘要降级测试。H2 测试将 JSON 列替换为字符串列以验证应用逻辑，不能单独证明 MySQL 隔离级别和锁行为完全相同。

当前本地 Docker MySQL 8 与 Redis 已完成基础设施冒烟：5 张表迁移及启动结构检查通过；JWT 登录后的当前会话、列表、新建、历史、记忆列表和 WebSocket 连接均返回成功；主动新建会正确关闭旧会话；Redis 当前会话 String 与短期上下文 Hash 均成功写入，Hash 包含 8 个字段、近期完整回合和有效 TTL，回合同步状态由 PENDING 变为 SYNCED。冒烟发现并修复了 MySQL Connector/J 将 `DATETIME` 返回为 `LocalDateTime`、而 H2 返回 `Timestamp` 的兼容问题。测试账号及其 MySQL/Redis 数据已清理。

前端 `pnpm typecheck`、`pnpm build` 和开发服务器启动均通过，Vite 代理登录及记忆接口通过。为修复仓库原有构建阻塞，补充了可复制的 `.env.example`、依赖 hoist、`vue-markdown-shiki` 本地类型门面和上传任务缺失字段，并移除了与图标组件编译重复的 UnoCSS 图标 preset。

尚未完成的生产级验收包括：真实模型端到端回答与自动提取、Redis 并发 CAS 冲突/过期重建、MySQL 多实例并发锁竞争和进程故障窗口。上线前仍应在隔离环境完成这些故障与并发测试。

自动提取是保守的受控模型抽取加服务器证据检查，不承诺理解所有自然语言表达。未知技能/公司、推测、引述或含第三方线索的消息可能被整体跳过。记忆容量超预算时按优先级和时间裁剪，第一版没有问题语义召回。
