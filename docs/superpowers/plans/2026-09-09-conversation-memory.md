# 短期记忆与长期记忆 V1 实施计划

状态：V1 代码、迁移和本地运行验证已完成。统一数据库用户 ID、5 张新增表、Redis 独立键空间、MySQL 直接加载长期记忆。当前本地 MySQL/Redis、REST、WebSocket、前端构建及开发代理冒烟通过；生产部署仍需先执行 SQL 再开启总开关，并补充真实模型、并发和故障窗口验收。第 7 节保留设计时的参数讨论，实际采用值和实现差异见第 10 节及库表更新说明。

## 1. 目标与范围

让助手保持当前会话的上下文，并在新会话中使用当前用户的稳定偏好、身份、技能及长期目标。V1 记忆功能复用现有 Java 17 / Spring Boot、Redis、MySQL 与对话模型客户端；现有 Elasticsearch 和 Embedding 继续服务知识库 RAG。

### 已确定的产品规则

- 短期记忆属于聊天会话；第一版不增加正式 Project 实体，不支持共享项目记忆。
- 长期记忆属于用户私有空间，可以跨会话召回；相关话题中，长期目标可以主动影响回答。
- 无明确项目 ID 的技术决策和项目结论不进入跨会话长期记忆。
- 企业制度等权威事实继续来自带 ACL 的 RAG 知识库，个人对话不能自动升级为企业事实。
- 用户主动新建为主要会话边界，空闲超时为兜底。Redis 的 7 天 TTL 仅用于短期上下文存储回收。
- 每轮问答完成后异步提取长期记忆，执行幂等写入；不依赖浏览器关闭或会话结束回调。
- 只允许主体明确为当前用户、内容稳定、有长期价值且置信度足够高的事实进入长期记忆。
- 推测、第三方事实、RAG 检索结果、工具结果和模型推理禁止沉淀；摘要和助手回答不能充当事实来源。
- 模型输出受控的 `category/subject/attribute/value`，服务端规范化生成 `memoryKey`。单值按槽位管理，集合按规范化元素生成子键。
- 同一用户、同一 `memoryKey` 最多一个 active 版本。UPDATE 使旧版本失效并生成新版本；DELETE 逻辑失效，保留审计历史。
- 临时偏好设置 expiry；任务型记忆通过 expiry 或状态生命周期退出正常召回。
- V1 长期记忆的权威存储使用 MySQL，并直接从 MySQL 加载有效记忆；第一版不增加个人记忆向量检索层。
- 当个人记忆规模增大，需要按当前问题做语义相关性召回时，可以增加向量检索层；MySQL 继续作为权威事实源。
- 新会话与记忆结构统一使用 `users.id` 作为用户关联标识；`username` 用于登录、展示及兼容旧数据，不再通过字符串能否转为数字推断身份类型。

### 后续版本

共享项目记忆、企业记忆写入、会话结束 consolidation、历史聊天批量提取暂不纳入 V1。逻辑删除不等于彻底清除个人数据；物理清除和审计保留期限需单独设计。

个人记忆向量索引、Embedding、投影同步 outbox、重建与对账移至后续按需扩展。V1 不创建 `memory_outbox`，不改动现有知识库 ES 存储结构。

## 2. 当前代码与改动入口

以下路径相对于仓库根目录；Java 包路径前缀为 `src/main/java/com/yizhaoqi/smartpai/`。

| 当前文件 | 现状 | 计划调整 |
| --- | --- | --- |
| `service/ChatHandler.java` | 用户唯一 current_conversation；Redis 最近 20 条消息；成功回答后写历史 | 委托会话、上下文与完成回合服务，绑定 conversationId/turnId |
| `handler/ChatWebSocketHandler.java` | 处理文本和停止指令，连接 sessionId 与业务会话不同 | 支持明确的会话选择与回合标识，保留旧文本兼容入口 |
| `agent/KnowledgeAgentService.java` | 系统规则、历史、当前问题构成输入 | 使用统一构建的记忆上下文；工具循环也受总预算约束 |
| `service/RagFallbackService.java`、`client/DeepSeekClient.java` | 固定 RAG 使用原始历史结构 | 与 Agent 共用短期/长期记忆规则与预算 |
| `controller/ConversationController.java` | 直接读取 Redis 当前会话 | 委托会话服务，按所有者读取指定会话 |
| `controller/AdminController.java` | 存在依赖旧 Redis key 的历史查询 | 保持兼容或明确迁移；不能因此向管理端新增个人记忆读取权 |
| `model/Conversation.java`、`service/ConversationService.java` | 已有问答记录模型，但当前 ChatHandler 未使用其写入方法 | 不误当作已存在的业务会话、回合账本或可靠异步事件来源 |
| `frontend/src/store/modules/chat/index.ts`、`frontend/src/views/chat/` | 已有 conversationId 字段，但缺少明确新建会话流程 | 新建对话、会话切换、隔离迟到响应、刷新当前会话历史 |
| `utils/JwtUtils.java`、`service/HybridSearchService.java` | JWT 已有数字 ID 的 userId claim，但聊天入口读取 username；检索兼容逻辑通过数字格式猜测身份 | 认证边界取得明确的数据库 ID；兼容旧用户名时显式查询，覆盖纯数字用户名测试 |
| `config/RedisConfig.java`、`docs/databases/ddl.sql` | 全局 Redis 序列化与新 Hash 需求不同；DDL 未包含已有 conversations 实体 | 新键使用专用字符串序列化；增量迁移与实体同步维护，保留旧数据 |

现有 README 和前端依赖文件存在工作区修改。实施时应保留这些修改，文档更新仅做针对性补充。

## 3. 已确认架构

```text
聊天请求（服务端认证用户 + conversationId + turnId）
  → ChatSessionService：归属校验、空闲切换、持久化接收回合并分配顺序
  → MemoryContextService
      ├─ ShortTermMemoryService：会话摘要 + 最近完整问答（Redis）
      └─ MemoryRecallService：MySQL 当前有效记忆 → 分类优先级与预算裁剪
  → KnowledgeAgentService / RagFallbackService → 回答
  → TurnCompletionService：事务保存最终回答、完成状态、提取任务与上下文待同步状态
      ├─ Redis 短期上下文更新（按回合幂等，可从 MySQL 恢复）
      └─ MemoryExtractionWorker：用户原始输入 → 受控候选
          → MemoryWriteService：槽位事务、版本与审计（MySQL）
```

使用 MySQL 持久提取任务和数据库轮询 worker，实现重启恢复和消费幂等；V1 不新增记忆 Kafka topic 或 ES 投影同步任务。异步提取的可靠性仍由持久任务保证，不因推迟向量检索而取消。

新 MySQL 表使用 `user_id BIGINT` 关联 `users.id`；Redis key 中使用该 ID 的十进制字符串。认证时先校验 JWT 有效性及用户存在，再读取其 userId claim。兼容旧 token 或旧 Redis key 时，通过明确的 `findByUsername` 路径映射；认证失败不得回退为原始 token 身份。原有知识库数据不做全量用户字段重写，边界适配保持权限查询语义。

## 4. 会话、短期记忆与上下文预算

### 会话规则

- 业务 conversationId 与 WebSocket sessionId 分离；重连本身不新建会话。
- 用户新建会话后，旧短期上下文不进入新会话。旧上下文按自身 TTL 保留，用户长期记忆仍可使用。
- 按“最后一次有效用户聊天请求时间”计算空闲时长；心跳、连接重建不刷新，正在执行的回合不被空闲切换打断。
- 请求开始时固定 conversationId、turnId、用户级顺序号。迟到的完成回调只能写其原始会话，不能再查 current_conversation 决定写入目标。
- 同一业务会话只允许一个在途回合，重复请求返回已有状态，不同请求冲突时明确拒绝并要求先停止；并发新建/超时切换使用原子更新。
- 新增结构化聊天协议时携带 conversationId 和 requestId；服务端校验归属，不接受模型提供的用户身份。响应附带 conversationId/turnId，前端隔离旧流。
- 当前会话的权威状态在 MySQL。新建或空闲切换时，在同一用户的短事务内关闭旧当前会话、创建新会话，再更新 Redis 指针。读取缓存仍校验 MySQL 归属与状态；缓存缺失按持久状态和空闲规则恢复，不直接等同于新会话。

### 短期上下文设计

- 使用“滚动摘要 + 最近完整问答”，按 token 预算组装。压缩不是长期记忆提取。
- 摘要保留当前任务、约束、已确认结论、待解决问题，并保留其数据来源性质；文档中的指令不能被摘要升级为系统规则。
- 摘要记录 `coveredThroughTurnSeq` 和版本；只压缩已完成前缀，用 CAS 更新摘要，防止并发压缩覆盖新状态或重复拼接。
- 最近问答保持成对完整，未完成助手输出和工具轨迹不写入长期会话历史。
- 预算覆盖系统提示、工具定义、摘要、近期消息、长期记忆、当前输入、工具结果，并预留输出空间；每次模型调用重新检查。
- 使用适配实际模型的 token 计数；缺少 tokenizer 时明确采用保守估算和余量，不能把字符数冒充精确 token 数。
- 原始消息的存储保留与模型输入裁剪分开；摘要成功提交前不删除源消息。
- **压缩失败策略待确认**：建议保留旧摘要，裁剪最早完整问答以满足预算，并向前端提示上下文有所缩减；如果当前输入与必要规则本身超限，则要求缩短输入。该策略可能遗漏早期约束。

### Redis 键值结构

下列 `{uid}`、`{sid}` 为占位符，分别替换为数据库用户 ID 和业务会话 UUID；不包含用户名或 JWT。

| Key | 类型 | 值/职责 | 过期策略 |
| --- | --- | --- | --- |
| `memory:v1:user:{uid}:current` | String | 当前业务会话 ID，MySQL 状态的缓存 | 按缓存 TTL 回收，缺失时回源；不承担会话生命周期判断 |
| `memory:v1:session:{sid}:context` | Hash | 会话摘要、近期完整问答与压缩进度 | 成功更新时刷新 7 天 TTL |

context Hash 字段：

```text
owner_user_id               数据库用户 ID
schema_version              存储格式版本，初始为 1
revision                    上下文修改版本，用于 CAS
summary                     仅当前会话使用的摘要
covered_through_turn_seq    已纳入摘要的回合前缀位置
last_completed_turn_seq     已同步的最新完成回合位置
recent_turns_json           [{turn_id, seq, question, answer, completed_at}, ...]
```

- 新键使用专用 StringRedisTemplate 或等价的显式字符串序列化配置，包含 hash key/value；不替换现有全局序列化器，避免破坏令牌和组织缓存。
- Lua 或事务加版本检查原子更新摘要、覆盖位置、近期问答、revision 和 TTL。操作时校验所有者与预期版本，不能仅依赖 Java 内存锁。
- 完成回合按 turn_id 幂等应用；恢复按本会话的回合顺序执行，不以“最大序号已存在”为由跳过尚未同步的较早回合。摘要提交前校验其前缀已完整同步。
- 历史展示使用保留期内的 MySQL 完整回合，模型输入使用 Redis 压缩上下文。两者独立清理，摘要不替代历史记录。
- 重放旧同步任务不能重新激活已结束会话或无限延长过期上下文；恢复先检查会话状态、上下文保留截止时间及源数据是否仍在保留期内。
- V1 不在 Redis 缓存长期记忆，直接读 MySQL 的当前有效版本。

### 旧 Redis 历史兼容

旧键 `user:{username}:current_conversation` 与 `conversation:{conversationId}` 保持可读。仅按已认证用户的准确用户名解析其旧 JSON 历史，映射到新用户 ID 和会话结构，迁移过程幂等；旧消息没有可靠回合 ID 时生成迁移标识，不推测历史跨会话顺序。迁入内容只恢复短期历史，不触发批量长期记忆提取。不清空 Redis，旧键自然过期；管理端历史查询同步调整。

## 5. 长期记忆模型与写入协议

### 已确认数据模型

| 表/结构 | 关键字段与职责 |
| --- | --- |
| `chat_session` | id、user_id、status、last_activity_at、created_at、closed_at、context_expires_at、revision、active_turn_id；业务会话归属、生命周期与在途回合 |
| `chat_turn` | id、session_id、user_id、user_turn_seq、request_id、request_hash、question、answer、status、context_sync_status、context_sync_attempts、context_next_attempt_at、created_at、completed_at、content_expires_at；可靠提取来源、完整问答恢复与幂等标识 |
| `memory_extraction_task` | id、turn_id、status、attempts、next_attempt_at、lease_until、lease_token、last_error_code、created_at、updated_at；可靠领取、过期接管和重启恢复 |
| `memory_slot` | UNIQUE(user_id, memory_key)、current_version_id、revision、last_applied_turn_seq；单槽位互斥与顺序屏障 |
| `memory_version` | id、slot_id、version、category、attribute、value_json、status、expires_at、source_turn_id、evidence、created_at、invalidated_at；版本内容、来源与审计 |

V1 新增以上 5 张表。长期记忆本身由 `memory_slot` 与 `memory_version` 两张表承载，其余表用于会话和可靠异步提取；向量字段和投影 outbox 不纳入本版。沿用现有 users 表，不把旧 conversations 表改造为记忆版本表。

字段约定：会话 UUID 用 `CHAR(36)`；回合/任务/槽位/版本主键和顺序号用 `BIGINT`；状态用受控 `VARCHAR`；正文用 `TEXT`（实施时结合输入上限核对容量），结构化值用 `JSON`；时间统一按 UTC 使用 `DATETIME(6)`。`memory_key` 使用有长度上限、规范化且大小写规则明确的字符串。DTO 的 conversationId 对应 `chat_session.id`，外键统一为 session_id，turnId 对应 `chat_turn.id`。

| 表 | 必需约束与查询索引 |
| --- | --- |
| `chat_session` | PRIMARY KEY(id)；INDEX(user_id, status, last_activity_at) |
| `chat_turn` | PRIMARY KEY(id)；UNIQUE(user_id, request_id)；UNIQUE(user_id, user_turn_seq)；INDEX(session_id, user_turn_seq)；INDEX(context_sync_status, context_next_attempt_at) |
| `memory_extraction_task` | PRIMARY KEY(id)；UNIQUE(turn_id)；INDEX(status, next_attempt_at)；INDEX(status, lease_until) |
| `memory_slot` | PRIMARY KEY(id)；UNIQUE(user_id, memory_key) |
| `memory_version` | PRIMARY KEY(id)；UNIQUE(slot_id, version) |

request_id 非空，重复请求还需校验 session_id 和 request_hash；相同 ID 携带不同内容应拒绝。外键/事务校验保证回合属于其会话所有者、current_version_id 指向本槽位版本、提取来源属于同一用户；索引不能代替归属校验。单 active 版本通过锁定槽位和事务切换实现，不使用 `UNIQUE(slot_id, status)`，因为需要保留多个失效历史版本。

### 持久回合顺序与在途状态

接收新请求时在短事务内锁定对应 users 行，检查请求幂等和会话状态，再创建 chat_turn。V1 使用新回合的自增 id 作为 user_turn_seq，允许跨用户和失败事务造成跳号；同用户接收顺序由该锁串行确定，因此无需额外计数表或可过期的 Redis 计数器。提交后立即释放锁，不跨模型调用持锁。唯一请求重试复用原回合及序号。

会话 active_turn_id 的设置、停止、完成均使用条件更新。完成回调只有在回合仍为 RUNNING 且拥有该在途标识时才可生效；已取消的回合不能创建提取任务。新建会话时按同一协议取消旧在途回合。接收阶段已持久化但未完成的回合可标记 CANCELLED/FAILED，仅用于账本与恢复判断，不进入问答上下文或长期提取。

### 保留与恢复

完整问答、任务来源原文与审计内容分别配置保留期限，不能把 7 天短期 TTL 隐式变成无限保存聊天全文。content_expires_at 控制正文保留，context_expires_at 控制短期上下文恢复资格。清理时保留必要的回合幂等标识、版本关联和顺序屏障；任务在来源保留期内处理，来源到期后转为明确的不可提取状态，不再无限重试。来源正文清理后，审计能展示哪些证据取决于单独的 evidence 保留策略，不能声称仍可恢复全部原文。

### 受控键空间

建议初始支持 `preference.response.verbosity`、`profile.occupation`、`profile.skill.<normalized-element>`、`goal.interview.<normalized-company>`。具体枚举和同义词表在 Task 1 固化。

- category/attribute 必须来自服务端白名单，subject 只能为当前用户；userId 从认证上下文获得。
- 对值进行长度、类型和规范化校验；集合元素规范化，避免 Java/java 生成不同 key。
- 未知分类或无法可靠规范化的候选跳过；模型不能创建任意层级 key。
- 区分“修改目标”和“新增并行目标”；例如更换面试公司不能只新增新公司而遗留明确放弃的旧目标。需要显式证据支持旧 key 失效，否则不猜测删除。

### 提取与版本事务

1. 在同一 MySQL 事务内保存最终 question/answer、完成状态、唯一提取任务和 `context_sync_status=PENDING`。Redis 不参与该事务；提交后尝试同步，失败由回合表的持久状态驱动退避重试，成功后标记 SYNCED。Redis 已更新但标记失败时允许重放，按回合去重。MySQL 提交前若进程退出，尚未持久化的答案无法保证恢复；应明确记录/反馈保存失败，不声称跨存储原子成功，也不能重新生成已流出的回答。
2. worker 读取当前回合用户原始输入及必要的已有记忆元数据；旧记忆只用于定位更新，不能作为本轮新事实的证据。
3. 模型给出受控字段、建议 ADD/UPDATE/DELETE/NONE、置信度及用户原文证据位置；服务端复核证据存在、类型合法与作用域。自报置信度不单独构成写入许可。
4. 对省略主体、引用转述、“可能”等难以可靠确认的内容保守跳过。失败不触发聊天重生成。
5. 提取完成后，在事务内锁定任务并核验当前 lease_token 和未过期租约，再按固定顺序锁定涉及槽位，完成版本变更、顺序屏障、审计及任务完成状态。首次插入使用唯一键处理竞争；模型调用在事务外执行。过期或已被接管的 worker 不得提交记忆变更。
6. 相同规范化事实不产生无意义版本，但较新回合重新确认同值时仍推进该槽位的 last_applied_turn_seq，防止中间旧事实晚到覆盖。重复任务不重复写入；一个槽位在同一提取结果中只允许一个确定操作，冲突候选保守跳过。按回合标识去重与按事实槽位去重是两种不同约束。
7. 用户级 turnSeq 在接收请求时生成；较旧回合的提取结果不能覆盖同槽位较新回合的结果，不能按 worker 完成时间决定新旧。
8. DELETE 保留槽位 tombstone/顺序屏障，防止旧任务复活记忆；用户后来明确重述时，较新证据可产生新版本。
9. 手动逻辑删除若纳入 V1，必须同步推进同一顺序屏障；该操作的 UI/API 范围目前未确定。

任务领取采用有限租约、随机 lease_token 和重试退避；续租、成功、失败更新均匹配领取令牌。超过上限进入可查看/重放的失败状态，重放重新领取。记录任务 ID、状态、耗时、错误码，不记录完整记忆或输入正文。

## 6. V1 MySQL 召回与后续向量扩展

### V1 召回建议

- 通过 `memory_slot.current_version_id` 关联当前版本，在查询中按认证 userId、有效状态和 expiry 过滤，不加载历史失效版本。
- 少量全局回答偏好优先加载；身份、技能、目标按可配置分类优先级、更新时间及稳定次序选取，限制读取条数和注入 token 预算。该排序是有界加载策略，不代表按当前问题完成了语义检索。
- 个人记忆较少时，预算内可注入全部有效记忆，由回答模型结合当前问题决定是否使用身份和目标。超出预算时按上述规则裁剪，可能遗漏相关记忆；记录裁剪数量供后续评估。
- 当前明确要求优先于历史偏好；相关目标可调整回答角度，但不得改变用户问题。例如面试目标可带来面试考点，不能把所有 Java 问题改写为面试练习。
- 注入为带来源标识的数据，不把原始记忆内容提升成高优先级系统指令。记忆不能修改 ACL、工具权限或代替企业制度的知识库证据。
- MySQL 读取失败时，建议跳过长期记忆注入并记录故障，使用可用的短期上下文继续处理；不让长期记忆读取失败触发重复生成。
- V1 个人记忆读写不调用 Embedding 或 ES。知识库 RAG 的检索依赖及其故障处理仍按现有流程运行。
- 异步提取尚未完成时，新会话可能暂时看不到最新记忆；同一会话依靠近期消息保持连续性，不承诺跨会话即时读己之写。

### 后续按需扩展（不属于 V1 实施任务）

当个人记忆持续超过加载预算、相关记忆被裁掉，或需要按问题语义选择记忆时，再增加向量召回。触发条件根据记忆条数、token 开销和召回效果评估，暂不指定固定条数门槛。

- 保持 `MemoryRecallService` 的调用边界，后续可加入向量候选查询；MySQL 的槽位、版本和审计语义保持不变。
- 向量存储作为可重建检索投影，具体技术届时选择；如复用 ES，应建立独立个人记忆索引，维度与 Embedding 配置一致。
- 届时再增加投影 outbox/worker、版本防乱序、删除屏障、重试、对账和重建机制。
- 向量候选按 userId 过滤并回 MySQL 核验当前版本、状态和有效期，避免陈旧结果进入上下文。

## 7. 参数草案与待确认项

以下数值均为建议默认值，需要结合实际模型容量和测试调整。

| 项目 | 建议 | 状态 |
| --- | --- | --- |
| 空闲切换 | 30 分钟 | 待确认 |
| 短期 Redis TTL | 7 天；成功更新有效短期上下文时刷新，旧任务重放遵守保留截止时间 | 已确认 |
| 记忆功能开关 | 短期增强、长期提取、长期召回可独立关闭 | 建议 |
| 摘要上限 | 1,000 tokens | 待确认 |
| 长期记忆注入上限 | 800 tokens，含固定偏好 | 待确认 |
| 最近完整问答 | 最多 10 轮，同时服从总预算 | 待确认 |
| MySQL 记忆加载 | 查询条数设上限，固定偏好优先，其余按分类和更新时间选择，最终服从 token 预算 | 条数与排序细则待确认；V1 无向量召回 |
| 输入总预算 | 按实际模型上下文窗口减去输出预留和余量 | 待实际配置确认 |
| 提取超时/重试 | 单次 30 秒，最多 5 次尝试，指数退避 | 建议 |
| 写入置信度阈值 | 通过正负样本校准后配置 | 待验证，不能只凭分数放行 |
| 压缩失败 | 旧摘要 + 有界近期问答，并告知缩减 | 待确认 |
| 临时偏好/任务期限 | 明确期限优先；模糊“最近”使用分类默认期限 | 默认期限与是否跳过模糊项待确认 |
| 用户查看/修改/删除记忆 | 建议提供，逻辑删除要阻止旧任务复活 | V1 交互范围待确认 |
| 回合输入与审计保留期 | 分开配置，不能继承无限保存 | 待确认 |

## 8. 分阶段实施任务

以下保留原任务拆分；实施映射见第 10 节。勾选表示对应代码/检查已经完成，真实基础设施验收不以 mock 或 H2 代替。

### Task 1：协议、配置与数据约束

- [x] 新建 `memory/MemoryTypes.java`、`memory/MemoryProperties.java`、`MemoryPolicy.java`，定义受控字段、key 规范化、expiry 和操作协议。
- [x] 使用显式 JDBC repository 和建表/索引迁移，记录旧 conversations 定义缺口；新表不依赖 Hibernate 自动建表。
- [x] 新表统一 users.id，旧历史仅通过准确用户名映射；实现用户短事务分配顺序、非空请求 ID、内容冲突检查和任务租约。
- [x] 单元/H2 测试覆盖规范化、非法字段、过期边界、重复请求及并发请求幂等。

### Task 2：会话边界与前端新建对话

- [x] 在 MemoryStore 内实现会话事务，通过 MemoryController 暴露创建、列表和指定会话历史，统一归属校验。
- [x] 调整 ChatHandler、ChatWebSocketHandler、ConversationController 和管理端问答历史查询。
- [x] 新记忆聊天边界取得 JWT 数据库 ID，兼容旧数据通过准确用户名；SQL 决定当前会话，Redis 仅作缓存提示。
- [x] 前端新增新建对话、历史会话只读选择、空闲切换处理和 requestId 迟到事件隔离。
- [ ] 验证重连续接、新建隔离、空闲边界、跨用户访问拒绝、并发切换与迟到 chunk/completion 隔离。

### Task 3：短期摘要和统一上下文构建

- [x] 在 MemoryContextService 实现摘要、近期完整回合和预算组装；缺少专用 tokenizer，使用明确标注的 UTF-8 字节预算。
- [x] 实现 String/Hash 键空间、独立字符串序列化及 epoch/revision Lua CAS；MemoryLegacyImporter 迁入旧历史且不触发提取。
- [x] 历史读取保留期内完整回合，恢复受截止时间限制；单元测试覆盖过期上下文不重建。
- [x] Agent 与 fallback 共用组装结果；DeepSeekClient 在每次调用前检查完整请求字节预算。
- [x] 单元测试覆盖摘要保留约束、摘要失败提示和完整回合裁剪。
- [ ] 在真实 Redis 验证 Lua 并发、CAS 冲突与过期重建。

### Task 4：可靠完成回合与异步提取

- [x] 在 MemoryStore/MemoryWorker 实现完成回合事务与受控提取，复用 AgentLlmClient 的无工具请求。
- [x] Agent/fallback 共用完成路径，检查在途状态，事务保存完整回答、任务与待同步标记；取消不沉淀部分答案。
- [x] Redis 按回合补偿，提取任务实现租约令牌、退避和失败状态。
- [x] 单元/H2 测试覆盖重复完成、完整回答恢复状态、旧 worker 提交拒绝、非法 JSON 字段及来源限制。
- [ ] 在真实基础设施验证进程故障窗口和 Redis 成功但 SQL 标记失败后的恢复。

### Task 5：版本写入、冲突与防复活

- [x] 在 MemoryStore 的事务写入中处理版本和审计，DELETE 也追加带来源的审计版本。
- [x] 实现 turnSeq 比较、重复事实 no-op、同值确认推进屏障、删除防复活和状态复核。
- [ ] 使用真实 MySQL 隔离测试库验证锁和唯一约束；mock 测试不能证明并发事务正确。
- [x] H2/单元测试覆盖旧结果晚到、职业更新、同值屏障、集合规范化和删除后迟到写入。

### Task 6：MySQL 记忆加载、上下文注入与降级

- [x] MemoryStore 查询当前有效版本，按用户、状态和有效期过滤；MemoryContextService 完成统一上下文注入。
- [x] 实现偏好优先、其余按更新时间排序，限制条数与 UTF-8 字节预算。
- [x] 单元/H2 测试覆盖用户隔离、旧版本排除、过期、摘要/近期回合预算及偏好注入。
- [x] 个人记忆读写不调用 Embedding/ES，不创建向量同步设施。

### Task 7：集成验收、兼容与交付

- [x] 新增 memory 数据事务、策略、上下文、提取与聊天接入测试，运行已有后端回归。
- [x] 兼容读取旧历史，只迁入已认证用户的准确用户名键，不扫描或批量提取。
- [x] 保留 chunk/completion/error 语义并附带请求/业务会话标识；新版前端隔离旧响应。
- [x] 新增 README 功能入口和库表更新说明，保留原有用户修改。
- [x] 完成本地真实 MySQL/Redis 基础冒烟及前端类型检查、生产构建、开发代理验收；并发与故障专项仍按各任务未完成项继续跟踪。

## 9. 验证与验收标准

单元测试使用 mock 模型和受控时钟；HTTP 协议测试使用本地模拟服务。记忆功能的真实 MySQL/Redis 集成测试使用隔离环境，单独运行，不能把未启动依赖导致的跳过报告为通过。现有知识库 RAG 的 ES 回归测试按原有依赖运行，V1 不增加个人记忆向量投影测试。

建议命令：`mvn test`；前端在其目录运行 `pnpm typecheck`、`pnpm build`；文档和代码提交前运行 `git diff --check`。数据库并发与 Redis 上下文集成测试的 profile/命令需在对应测试落地时一并文档化。

| 场景 | 预期结果 |
| --- | --- |
| 用户要求简短，之后新建会话 | 提取完成后，新会话可应用偏好；当前明确要求详细时优先遵循当前要求 |
| Java 后端工程师 → 产品经理 | 单槽位仅新版本 active，旧版本可审计 |
| 同时掌握 Java、Go | 两个规范化技能子键并存 |
| 面试目标 + Java 问题 | 回答可主动补充相关面试视角，不偏离当前问题 |
| “可能准备面试”“朋友是工程师”、粘贴企业资料 | 不自动转为当前用户的确定长期事实 |
| 无项目 ID 的技术选型 | 可留在当前会话摘要，不写跨会话长期记忆 |
| 重复回调、任务重投、旧任务晚到 | 不重复建版本，旧事实不覆盖新事实 |
| 较新回合确认同值，较旧的不同值提取晚到 | 无需创建同值新版本，但顺序屏障已推进，拒绝旧结果 |
| 任务租约已被新 worker 接管 | 旧 worker 即使完成模型调用也不能提交版本或完成任务 |
| MySQL 已保存完整回答，Redis 写入失败或成功标记丢失 | 从回合账本幂等补偿，不重复生成、不重复追加问答 |
| 纯数字用户名、旧用户名 Redis key | 用户名显式映射到真实 ID，不误作另一账号 ID |
| MySQL 已逻辑删除，旧提取任务晚到 | 后续查询排除已失效版本；旧任务不能复活记忆 |
| 个人有效记忆超出注入预算 | 固定偏好优先，按稳定规则裁剪并记录数量；不声称完成了问题语义召回 |
| 长对话、新建、空闲超时、重连 | 压缩有界；新会话短期隔离；重连不误开新会话 |
| 停止/断线取消、旧流迟到 | 不记入不完整回合，不污染新会话和新前端消息列表 |
| 提取模型或 MySQL 故障 | 使用明确降级；异步任务可重试；已生成回答不因记忆失败被重复生成 |
| 个人记忆存储与加载 | 仅依赖 MySQL，不新增向量索引，不调用 Embedding/ES；现有 RAG 依赖不变 |
| Agent 关闭或协议降级 | 固定 RAG 仍遵循同样的记忆归属、来源与上下文预算 |

完成条件：上述已确认需求对应测试通过；建议项的最终取值与采用策略记录到文档；依赖环境中的集成验证单独报告。此计划文件本身的交付不代表功能已实现或运行测试已通过。

## 10. 本次代码落地记录

- 入口：MemoryController、ChatHandler 和 ChatWebSocketHandler；数据库与事务集中在 MemoryStore，短期上下文与召回组装集中在 MemoryContextService。为避免 V1 过度拆分，没有逐个创建设计草案中的所有 Service 类。
- 持久化采用现有 JdbcTemplate/TransactionTemplate，5 张表由显式 SQL 创建；未新增 JPA 实体。字段清单、索引、部署和回滚以 [库表更新说明](../../databases/2026-09-09-memory-v1-update.md) 和其中链接的 SQL 为准。
- 总开关默认 false，迁移后显式开启；开启后校验表结构。空闲 30 分钟、短期 TTL/正文保留 7 天、提取租约 2 分钟、模型超时 30 秒、最多 5 次尝试、审计证据片段保留 365 天。
- 输入总预算采用 48,000 UTF-8 字节，问题 12,000 字节，摘要 4,000 字节，长期记忆 3,200 字节；这不是精确 token 计数。近期最多 10 轮，长期记忆最多 30 条并受字节预算限制，输出 token 继续沿用 ai.generation 配置。
- 无明确期限的面试目标默认 90 天，临时偏好默认 7 天。压缩失败使用旧摘要及近期完整问答，并告知上下文缩减；不静默声称保留了全部约束。
- 当前受控公司与技能范围明确列在更新说明；未支持的自然语言表达保守跳过。记忆查看提供本人 API，手动修改/删除 UI 未纳入本次；显式自然语言删除走受控提取和版本审计。
- 历史会话列表最多 100 个，历史消息每次最多 200 个完整回合；Redis 丢失时重建也受该窗口限制。历史会话前端只读，通过新建开启新的短期上下文。
- 后端 `mvn -q test` 已通过 58 个测试；前端 `pnpm typecheck`、`pnpm build` 和开发服务器启动已通过。
- 当前本地 MySQL 8 已应用迁移，启动结构检查以及会话 REST/WebSocket 冒烟通过；Redis 当前会话指针和包含近期回合的上下文 Hash 已验证写入、TTL 与 PENDING→SYNCED 补偿流程。测试数据已清理。
- 真实 MySQL 暴露的 `DATETIME`/`LocalDateTime` 驱动差异已通过统一时间转换修复，并增加回归测试。真实模型自动提取、Redis 并发 CAS、MySQL 多实例竞争和进程故障窗口仍属于部署前专项验收，详情见库表更新说明。
- 记忆开关及参数支持从根目录 `.env` 的 `AI_MEMORY_*` 名称自动映射，不依赖被 Git 忽略的本地 `application.yml` 变更；标准 `ai.memory.*` 配置保持更高优先级。
