# yeRAG 短期记忆改造交付记录

日期：2026-11-02。分支：`agent`。基线 HEAD：`4898c48269e88cc05487fe71f4eaf5e9fb838364`。短期阶段记录如下；后续已完成统一改造与 Docker 部署，本记录随统一改造代码一并提交。最终结果见文末统一部署增量。

设计契约见 [短期记忆设计](../superpowers/specs/2026-11-02-short-term-memory-design.md)。本次检查覆盖 Memory 持久化、上下文构建、Worker、历史工具、Agent 会话传递、ChatHandler、前端聊天会话状态及界面、DDL 和数据库说明。原有两个未跟踪的项目概览文档保持原样。

## 已实现

- 原始问答和滚动摘要不再按空闲时间或到期字段清理。Redis 默认缓存 7 天，失效或版本不匹配时从 MySQL 重建；下一轮直接可见上一轮完整问答。
- 独立历史预算：16,384 tokens 触发，目标 8,192，摘要最多 2,048，近期原文最多 6,144。总模型输入预算仍由模型客户端统一检查。
- 调用前同步滚动摘要，逐页处理完整前缀；原子提交正文、覆盖回合 ID、版本。失败保留旧摘要和近期原文，冲突重读；超长单轮保留带回合引用的截断片段。Worker 只刷新缓存和异步提取长期 Markdown 记忆，不生成短期摘要。
- 两个只读历史工具通过 MySQL 查找当前会话：严格校验 JSON，服务端注入用户及会话，限定结果数量及 JSON token 总量，支持搜索游标、角色/Unicode 码点 offset 续读。历史助手回答不升级为事实依据。
- 显式恢复旧会话、删除会话、前端分页加载。删除移除原始回合和提取任务、清空摘要，仅保留不含正文的会话身份标记，阻止旧缓存迁移恢复相同 UUID。已提取的跨会话长期 Markdown 记忆仍遵循原有生命周期。
- 迟到回答、摘要、长期提取和缓存写入有删除屏障；上下文构建后再次检查活动回合，已被删除或关闭时不再调用 Agent。

## 本次执行结果

| 命令 | 结果及实际范围 |
| --- | --- |
| `mvn -q test` | 成功退出；Surefire 共 314 项：执行通过 301，跳过 13，失败/错误 0 |
| `cd frontend && pnpm typecheck` | 通过 |
| `cd frontend && pnpm lint` | 通过，0 errors；原有 18 warnings，来自其他文件的 console 与 inline style 规则；已检查自动修复差异 |
| `cd frontend && pnpm build` | 通过 |
| `cd frontend && pnpm test` | 3 项通过 |
| `cd frontend && pnpm test:browser` | Chromium 下 7 项通过；使用测试 HTTP/WebSocket 服务，不代表真实后端联调 |
| `git diff --check` | 通过 |

本次关键测试证据：

- `MemoryShortTermStoreTest`：旧到期字段、数月空闲不删数据/不换会话；恢复与删除；拒绝迟到摘要/回答；默认 16k 阈值的跨页压缩及 8k 内输出；下一轮不等 Worker 即可读到新约束与完整答案，未达到下一次阈值不重复摘要。
- `MemoryContextServiceTest`：Redis 故障回源；匹配版本缓存命中；摘要失败保留旧摘要和近期原文；版本冲突重读；输出超预算拒绝推进覆盖位置；单轮过长时保留可找回片段；Worker 缓存同步不调用摘要模型；长期记忆读取失败不清空短期上下文。
- `ConversationHistoryToolsTest`：关键词与 SQL 通配符转义、结果预算、游标翻页、逐码点续读完整长原文、严格 JSON 形状、跨用户/跨会话拒绝、删除后不可读取。
- `MemoryDocumentStoreTest`：删除后有效长期记忆候选也不能写入；启动检查拒绝未执行持久历史迁移的旧非空字段。
- `KnowledgeAgentServiceTest`、`MemoryChatIntegrationTest`、`ChatHandlerAgentTest`：服务器会话传到工具执行；完成前持久化与取消规则；上下文构建期间删除后不调用 Agent；既有来源模式与降级回归。
- 浏览器新增场景：恢复 205 轮旧会话，加载第一页之外的原文，继续发送并核对 `conversationId`，删除后切换会话并继续交流。既有停止、重连、账号隔离和迟到事件场景继续通过。

## 未验证与部署要求

- `MemoryDocumentMySqlTest` 的 12 项因未设置 `MEMORY_MYSQL_TEST_URL` 跳过，包含新增的“读取来源快照后等待用户锁，与删除竞争”的真实 MySQL 并发测试。H2/单测通过不能证明 MySQL 隔离级别、锁等待及并发行为已验证。
- 另有 `HybridRecallElasticsearchTest` 1 项真实 Elasticsearch 测试跳过。
- 本次未调用真实 LLM 校验摘要质量、延迟或端到端历史找回决策；摘要客户端使用测试替身。Redis 不可用/版本失效通过模拟验证，未执行真实 Redis 集成测试。
- 已提供 [MySQL 迁移脚本](../databases/migrations/20261102_persistent_conversation_history.sql)，但未在真实 MySQL 执行。部署时先停止全部旧服务与 Worker，再应用迁移并启动新版。启动检查要求退役到期字段已改为可空。
- 旧清理逻辑已经清空的正文无法恢复。历史极大时每个请求最多尝试 32 个摘要批次；达到上限或单个摘要输入无法完整入模时保留未推进的边界，提示上下文缩减，原始内容仍可通过工具读取。

## 后续统一部署增量（2026-11-02）

长期记忆改造后已重新执行真实 MySQL/ES 全量测试及 package，322 项通过、0 跳过；真实 Redis 缓存 Lua 验证通过。原业务库已备份并执行短期/长期迁移，前后端已部署到 Docker。真实模型在 260 轮、时间设为七个月前的合成历史上完成两批同步摘要，历史工具读回 Lua 原文和数字，清空该会话缓存后恢复成功，删除后历史接口拒绝访问。最终状态以 [统一交付记录](2026-11-02-on-demand-memory.md) 为准，上文保留本次短期阶段的验证边界。
