# 报告与成果管理

本阶段实现个人工作台的报告闭环：对话生成 → 保存成果 → 查看/编辑 → 保留旧版本 → 后续对话继续使用。未包含会议业务任务、需求差异专用界面、邮件或日程集成。

## 部署

空库使用 [完整 DDL](databases/ddl.sql)。已有 MySQL 8 数据库先执行 [20261119_work_artifacts.sql](databases/migrations/20261119_work_artifacts.sql)，再部署应用。不对已初始化的数据库重跑完整 DDL。

启用 Agent 后，三个 Spring 工具自动注册，无需新增模型配置。联网调研仍需按原有配置启用 `search_web` 和 `read_web_page`；未启用时仍可基于内部资料生成报告，并说明证据缺口。

`ai.agent.max-artifact-tool-calls` 默认 4，是每轮独立的成果工具预算，与检索和记忆预算分开计数，仍受整轮超时限制。长报告续读可能需要多轮对话或调高此预算。

## 使用

1. 聊天中要求：“根据知识库中的部署方案，生成一份升级建议报告并保存，注明来源和证据缺口。”模型先取证，再调用 `save_artifact`；返回 `saved=true` 后才能声称已保存。
2. 通过聊天页“成果库”按钮或 `/artifacts` 打开个人报告列表。结果链接为 `/artifacts?id=<UUID>`。
3. 打开报告后人工编辑标题与完整正文，保存产生新版本。历史正文只读；继续修改须先读取最新版本。
4. 点击“在对话中继续修改”，填写预置请求中的具体修改要求后发送。Agent 可以读取旧成果，再用 `artifactId` 和 `expectedVersion` 保存新版本。
5. 下载当前查看版本的独立 HTML。正文与来源均按文本转义，不执行报告中的 HTML/脚本。

本版正文按纯文本展示和编辑，允许保存 Markdown 文本。只有完整工具参数通过验证后才保存，不从流式回答半成品自动建报告。已保存的完整成果独立于会话，之后停止回答或删除会话不会删除成果。

## API

全部接口要求认证，身份从 Principal 解析为数据库用户 ID；管理员也只能读取自己的报告。沿用 `{code,message,data}`，保留 400/404/409 等 HTTP 状态。

| 接口 | 行为 |
| --- | --- |
| `GET /api/v1/artifacts?offset=0` | 每页扫描 20 条自己的最新成果；返回 `items` 和可空的 `nextOffset` |
| `GET /api/v1/artifacts/{id}?version=1` | 指定版本，省略版本读取最新 |
| `GET /api/v1/artifacts/{id}/versions?before=2147483647` | 倒序最多 20 个可访问版本，使用最后一项版本号继续向前分页 |
| `POST /api/v1/artifacts` | 创建无绑定来源的用户自撰报告；Agent 有来源的保存走内部服务 |
| `POST /api/v1/artifacts/{id}/versions` | 基于 `expectedVersion` 保存完整新版本，继承原有来源依赖 |

保存字段为 `title`、`content`、`expectedVersion`、`requestId`（UUID）。标题最多 160 字符，正文最多 30,000 字符。新建传 `expectedVersion=0`，修改传读取到的版本号。同一用户重复相同请求 ID 与内容返回原保存结果；换内容重用请求 ID 返回 409。并发过时写入返回 409，前端保留编辑内容，允许复制后重新读取。

## 来源与权限

- 来源从本轮实际工具结果绑定，每份最多 20 项；模型不能传入用户、组织或任意来源 URL。人工修改保留已有依赖。
- 读取时重新检查内部片段的当前权限；失去权限的成果不返回正文，也不列入可访问列表。来源服务故障不会视为有权限。
- 工具在三种来源模式均可发现，但具体读取仍受限制：仅联网不能复用含内部来源的报告，仅知识库不能复用含网络来源的报告。
- 分页读取时引用编号映射到本轮编号，重复读取复用已有编号。报告、来源记录与人工编辑均是不可信数据，不能成为授权或系统指令。
- 来源是可追溯依赖，不证明报告结论已核实，也不会把搜索摘要提升为网页正文。

## 验证

后端运行 `mvn -Dtest='ArtifactStoreTest,ArtifactControllerTest,ArtifactToolsTest,KnowledgeAgentArtifactTest' test` 和 `mvn test`。

前端在 `frontend` 执行 `pnpm typecheck`、`pnpm lint`、`pnpm build`、`pnpm test`、`pnpm test:browser`。浏览器使用本地 HTTP/WebSocket fixture 验证真实 Vue 页面，不代表真实模型或 MySQL 部署已验收。H2 用例覆盖事务和竞争写入，不能替代 MySQL 并发验收。
