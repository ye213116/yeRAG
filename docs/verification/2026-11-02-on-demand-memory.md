# yeRAG 长短期记忆统一交付与 Docker 部署记录

日期：2026-11-02。分支：`agent`。基线 HEAD：`4898c48269e88cc05487fe71f4eaf5e9fb838364`。本次长短期记忆改造、测试和 Docker 部署已完成，本记录随改造代码一并提交；上述 HEAD 为实现前基线。原有两个未跟踪的项目概览文档未改动。

## 实现

- 默认只注入元数据生成的 MEMORY.md 索引（1,024 tokens），移除自动拼接正文。超预算时用文档概要 + 列表工具发现。
- 新增 list_memory_sections / read_memory，支持批量、有界 JSON 输出（2,048 tokens）、版本绑定续读、来源返回、服务器身份和三种来源模式。每轮两次，与检索工具独立计数、共享 Agent 超时。
- Worker 两阶段模型：候选及来源定位 → 读取目标旧正文 → 语义整合为完整 Patch。空候选跳过第二阶段，冲突重新读取并重新整合。
- Policy 不再按引号、代码和“最近”等词整批拒绝；严格检查结构、来源原话、服务器提供的回合、当前用户证据、归属、明确删除、版本与长度。
- 正文、描述、section/document 版本、理由/来源快照和任务 DONE 原子提交。旧租约、旧版本和旧轮次不能覆盖新内容。用户显式回滚创建新版本，不降低轮次屏障。
- 新增版本查询/主动回滚接口，完整 DDL 与增量迁移同步更新。保留现有 Markdown 编辑器及短期记忆实现。

设计与接口详见 [长期记忆设计](../superpowers/specs/2026-11-02-on-demand-memory-design.md) 与 [数据库设计](../databases/database-design.md)。

## 本次验证

| 命令/场景 | 本次结果 |
| --- | --- |
| `mvn -q test`（显式启用真实 MySQL/ES） | 322 项执行通过，失败/错误/跳过均为 0 |
| `mvn -q package`（最终镜像前，显式启用真实 MySQL/ES） | 322 项通过，0 失败/错误/跳过，成功生成最终 JAR |
| 真实 MySQL MemoryDocumentMySqlTest | 13 项通过，包含同用户顺序、不同用户领取、来源快照/删除竞争、版本、原子回滚和租约 |
| 旧库迁移（临时 Docker MySQL 8.4.11） | 保留 Markdown、稳定 ID、删除布尔值和轮次屏障，生成初始版本；有/无命名 CHECK 两种历史基线均通过 |
| 真实 Redis 7 的缓存 Lua | 7 天 TTL、删除标记及原子拒绝迟到写入通过；临时容器已清理 |
| `pnpm typecheck`、`pnpm lint`、`pnpm build` | 通过；lint 0 errors、18 条其他文件原有 warnings |
| `pnpm test` / `pnpm test:browser` | 3 / 7 项通过；浏览器使用协议测试服务，不代表真实模型联调 |
| 前后端 Docker build | 两个镜像构建通过 |
| Docker Compose config | 校验通过 |
| 后端镜像非 root 检查 | UID=10001、HOME/证据目录可写、JAR 可读、Java 17.0.20.1 |
| `git diff --check` | 通过 |

关键测试为 MemoryPolicyTest、MemoryWorkerTest、MemoryDocumentStoreTest / MySqlTest、PersonalMemoryToolsTest、MemoryContextServiceTest 和 KnowledgeAgentServiceTest；分别验证来源/语义边界、两阶段空候选/非法输出/冲突重整合、事务/审计/回滚、按需读取/跨用户拒绝/续读、只注入索引、独立记忆调用配额。短期 Memory/历史工具/ChatHandler 回归及真实 ES 授权过滤测试继续通过。

## 本机 Docker 状态

- Windows Docker CLI 可连接 Docker Desktop；WSL 的 docker 包装命令不可用。已新增 Dockerfile、compose.yaml、Nginx 代理及 [部署脚本和说明](../../deploy/README.md)。镜像中不包含 .env；JAR 内也未包含运行期 application 配置文件。
- 原 `.env` 指向已删除相邻仓库，按用户要求恢复为当前仓库的实际文件，权限 600，继续被 Git 忽略。中间件凭据从现有容器恢复，业务库已核对为 paismart；JWT 和管理员初始化密码生成新值，既有数据库账号未重置。
- 已启动原 MySQL、Redis、ES、MinIO 及修正后的 Kafka。旧 Kafka 广播 localhost:9092，现以原数据卷重建为 kafka:9092，原容器保留为 `kafka-before-memory-20261102-152920` 且保持停止；两者不可同时使用同一数据卷运行。
- 原业务 MySQL 已备份后应用短期及长期增量迁移；2 份文档、2 个 section 保留，初始版本创建。私有 SQL 备份在 `.codex/deployment/database-before-memory-20261102-153106.sql`（权限 600），未提交。迁移兼容了原库缺少命名 CHECK 的实际情况。
- 已构建 `yerag-backend:memory-20261102` 与 `yerag-frontend:memory-20261102`。前端产物使用同源 API/WebSocket 代理。

## 真实模型联调（本次执行）

在 Docker 前端同源代理上使用临时账号，实际调用配置的生成模型；未使用模型替身。长期记忆 11 项检查通过：

- 首条长期偏好经候选提取及整合，写入正文、来源和初始版本。
- 新建空会话后，普通线程池问题触发 `read_memory`，回答应用 Java 示例偏好，证明不是依靠上一会话近期原文。
- 更改为 Python 时保留原 section ID 并增加版本；重复确认不产生新版本或重复 section。
- 知识库、联网两种来源模式均实际触发个人记忆读取；自动模式同样通过。
- 版本接口返回修改理由和来源；明确忘记后正文移除、section 标记删除并产生审计版本。
- “我最近开始主要用 Java”被语义识别为持续开发背景并写入，未被关键词拒绝。
- 恢复旧会话后可读取其原始近期内容；删除另一会话后回合与关联提取任务清除。

真实请求暴露的两处提示词缺口已修正并复跑通过：相关偏好应主动读取；明确忘记也必须进入候选阶段。同时告知主 Agent 写入由服务端异步处理，避免因只声明读取工具而错误声称“不支持保存/删除”，或在提交前声称已经完成。

短期历史另建临时会话，以 SQL 测试夹具注入 **260 轮、时间为 7 个月前**的完整问答（属于合成历史，不代表连续运行了七个月）：

- 真实模型在调用前完成 2 个滚动摘要批次，持久化覆盖边界及 `summary_revision=2`；摘要后原始 260 轮仍保留。
- Agent 实际调用 `search_conversation_history` 和 `read_conversation_turns` 找回摘要之外的 Lua 原文，正确回答 `quota_remaining >= 37`。
- 仅清空此测试会话 Redis 缓存后，下一轮从 MySQL 恢复摘要/近期内容并正确回答 `37`；缓存 TTL 为 604,799 秒，符合七天。
- 删除该会话后全部测试原文移除，历史接口返回 404。

Embedding 提供方的实际请求返回 1 个 2,048 维向量。真实模型测试是本次样本验证，不能保证所有自然语言场景的语义判断均正确；结构、来源、版本、租约和事务限制仍由程序负责。

## 最终部署与清理

- 前端访问 **http://localhost:9527**，后端 **http://localhost:8081**。首页、`/chat` 深链接均返回 200，前端 API 代理和后端认证保护均返回预期 401。
- Chromium 使用真实 Docker 页面完成登录、同源 WebSocket 问答、完成后 MySQL 原文核对，以及刷新恢复；4 项通过。测试脚本先前使用的 20 位随机密码超过既有前端的 18 位校验上限，换为符合既有规则的独立测试账号后登录通过，未修改产品密码规则。
- 刷新测试发现 Nginx `/chat/` 代理会对精确 `/chat` 自动重定向并暴露容器内端口，已添加精确 SPA 路由，部署脚本也加入该路径就绪检查。WebSocket URL 含认证令牌，因此关闭其 Nginx access log，Docker 配置抑制包含 URI 的连接日志。
- 最终两个容器均运行且重启计数为 0；Nginx `-t` 通过。运行中的后端 JAR 与本地最终产物 SHA-256 一致：`c70cadeb6617bf44c99c8f29269e335947133163e13b8954e7981bc25003e2ac`。
- 后端镜像 ID：`sha256:f8bc49b0656bb1cdf0230832246a19cb36394b68ddbe2ffc2a3873df78c81e98`。前端镜像 ID：`sha256:26d0019a6e9d8bd300cf71c5177718c795b7a93540c9b5007ab1d03879166323`。
- 临时账号、原文、任务、Markdown/版本快照、会话缓存、认证缓存和测试 trace 已按精确账号归属清理；其他用户数据和原数据卷保留。联调结果留在忽略目录 `.codex/deployment/*-report.json`，不含认证信息；配置和 SQL 备份仍仅保存在本地私有文件中。
- `.env` 已恢复为本仓库实际文件，权限 600，模型配置已补齐；它及部署运行期配置继续被 Git 忽略。本记录随代码一并提交，原有两个未跟踪项目概览文档未修改、未纳入提交。

此前短期阶段“尚未部署/真实服务跳过”的记录已由本次最终验证覆盖。旧版本曾清空的历史无法恢复；自然语言提取与摘要质量仍需持续观察，不将这些有限样本当作所有语义场景的保证。

管理员配置补充（2026-11-02）：按用户指定同步修改本地私有初始化配置与现有 admin 账号的 BCrypt 密码，重建后端容器后，经前端同源登录接口实际验证通过；随后退出验证会话并撤销旧管理员访问令牌。未修改代码、模型配置或其他账号。原密码散列备份只保存在本地私有部署目录。
