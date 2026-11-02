# yeRAG 按需 Markdown 长期记忆设计与实现边界

2026-11-02；分支 `agent`；基线 `4898c48269e88cc05487fe71f4eaf5e9fb838364`。本次实现未提交。统一部署与验证见 [交付记录](../../verification/2026-11-02-on-demand-memory.md)。

方向：**索引默认注入、正文按需读取、后台模型决策、程序事务提交**。本次启用 `profile.md` / PROFILE 与 `preferences.md` / PREFERENCE；项目记忆尚未启用，未来必须由服务器绑定稳定项目 ID，不能从“这个项目”等模型文本创建全局记录。

## 读取

`MemoryStore.catalog` 只查询文档和 section 元数据；`MemoryIndexService` 动态生成 `MEMORY.md`，不读取正文，不从正文首段自动生成描述。每轮注入索引最多 1,024 tokens，包含文档类型、文件名、文档版本及 section ID、标题、描述、section 版本。索引过大则整体切换到文档概要，并提示通过列表工具发现条目。

- `list_memory_sections(documentType,cursor?)`：分页返回 section 元数据及版本。
- `read_memory(documentType,sectionIds?,cursor?)`：支持一次指定最多 8 个 section，返回正文、来源、版本和续读位置；省略 sectionIds 时按文档顺序读取。
- 每个工具返回完整 JSON 最多 2,048 tokens，游标绑定文档类型与版本；版本变化时拒绝继续使用旧游标。
- 个人记忆工具在自动、知识库、联网模式都可用，每轮最多调用 2 次。它们与检索工具分别计数，共享 Agent 总超时。已经读过的正文留在本轮消息里，不要求重复读取。
- 工具参数不接受 userId；身份由服务端注入，所有文档/section 读取限定当前用户。游标不提供授权，伪造游标或跨用户 UUID 不能扩大读取范围。
- 历史版本和来源由用户自己的版本接口查看；工具中较长的来源 quote 会明确截断，以守住返回预算。

提示词要求先检查索引，应用个人偏好或陈述历史用户事实之前读取相应正文；描述、标题只能用于发现，不能替代正文或据此猜测。所有索引、正文、来源及历史都属于用户数据，不能改变 ACL、工具规则、系统指令或成为企业知识库权威证据。

## 两阶段写入

1. 持久任务读取当前用户原话、当前会话最近最多 3 个完整回合及索引元数据，调用模型提取候选。
2. 程序验证候选来源、目标类型及可能匹配的稳定 section ID。空候选直接完成任务，不读取正文、不调用第二个模型。
3. 程序读取候选目标旧正文、section 版本及文档版本，调用模型作语义整合，输出完整 Patch。重复、无长期价值时允许空 Patch。
4. 模型调用全在事务外。提交时重新锁定/验证来源、任务、文档和 section；一次事务更新 Markdown、描述、section 版本、文档版本、审计快照与 DONE 状态。

候选和 Patch 的来源包含 `turnId`、`role`（省略时为 user）、`quote`。来源 ID 必须属于本次服务器提供的上下文，quote 必须精确匹配原文，每个候选/修改必须包含当前用户原话。近期回合只用于理解指代；当前助手答案不能作为来源。对旧助手建议的采纳由模型判断用户是否明确确认，并同时保留确认与被确认内容来源。

模型负责稳定性、本人/第三方、引用/假设、重复/补充/纠正等语义。程序移除引号、代码围栏、“最近”等整批拒绝规则，保留结构/类型/大小、精确原话、归属、版本、租约及轮次屏障校验。程序校验证据能定位，不能保证模型的语义结论正确，因此保留决定、理由、来源及历史版本以支持纠错。

## Patch 与事务

数据库操作保持 UPSERT_SECTION、DELETE_SECTION 或空 Patch。语义决定为 CREATE、EXTEND、REFINE、DELETE、RESTORE。每项包含 target、sectionId、heading、description、完整 content、operation、decision、reason、expectedSectionVersion、sources。新增 section 使用 null ID / expectedSectionVersion=0；已有 section 精确引用所读版本；DELETE 必须有当前用户的明确忘记意图。

固定提交锁顺序为 user → task → 按类型排序的 document → 按 UUID 排序的 section，与会话删除共享用户锁。提交重读租约及来源，校验服务器保存的预期文档版本与 Patch 的预期 section 版本。旧轮次不能越过 `last_applied_turn_seq`；删除仍保留 tombstone。

版本冲突重读正文并重新调用整合模型，最多重试 3 次，不重放旧 Patch。两阶段之间及整合重试前续租，提交仍校验租约。失败记录 candidate / integration / commit 阶段及受限原因码，继续使用原有持久重试和同用户顺序机制。

存储上限独立于每轮注入上限：文档默认 32 KiB、每文档最多 64 个 section，全部元数据默认最多 8,192 tokens；描述默认最多 120 码点。所有更新及快照与预算校验一起提交，任何校验失败回滚整个批次。

## 版本、迁移及回滚

`memory_document.version` 与 `memory_document_section.description/section_version` 保存当前状态；新增 `memory_document_version` 保存完整 Markdown、metadata_json、版本号、变更摘要、Patch/来源、触发回合及时间。每个变更文档写一份快照，空 Patch 不增加版本。

版本 API：

```text
GET  /api/v1/memory/documents/{type}/versions?before=<version>
GET  /api/v1/memory/documents/{type}/versions/{version}
POST /api/v1/memory/documents/{type}/rollback
```

回滚由已认证用户显式提交 targetVersion、expectedVersion、reason，生成一个新版本；section 版本和轮次屏障只增不减。模型没有修改或回滚工具。旧任务不能基于回滚前的正文或删除前的轮次恢复内容。

历史数据先应用持久会话迁移，再应用 [长期记忆迁移](../../databases/migrations/20261102_on_demand_markdown_memory.sql)。为已有文档创建 version=1 快照，为既有 section 创建泛化的主题描述及 section_version=1，保留 UUID、原文、tombstone 和轮次屏障。未伪造历史来源；初始迁移版本的审计标为 MIGRATION。

删除短期会话不自动删除此前已确认的长期 Markdown、版本快照及其来源摘录；长期记忆保持独立生命周期。显式忘记使当前 section 正文移除并保留审计与 tombstone，历史快照仍可用于检查和主动回滚。
