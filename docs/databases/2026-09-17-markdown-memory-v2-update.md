# Markdown Memory V2 部署与回滚

V2 复用 V1 会话和提取任务表。先执行 `migrations/2026-09-09-memory-v1.sql`（已部署 V1 时跳过），再执行 `migrations/2026-09-17-markdown-memory-v2.sql`。迁移不会复制或删除旧 slot/version 数据。

本地验证环境已备份数据库并执行 V2 migration，确认两张新表存在，以 `AI_MEMORY_STORAGE_MODE=document` 完成后端和浏览器测试。其他环境应分别执行下面的备份、迁移和切换步骤。使用 Docker MySQL 时，确认宿主机 3306 未被其他数据库服务占用，且连接 URL 指向实际发布的端口。

`memory_document` 每用户每类型至多一行，保存 PROFILE/PREFERENCE 的当前完整 Markdown；`memory_document_section` 保存稳定 UUID、标题、删除状态和最后应用的 user_turn_seq。正文不保存 UUID，不建立文档版本表。`MEMORY.md` 每次召回确定性生成，不落库。

## 切换

1. 备份数据库，在所有实例保持 `ai.memory.storage-mode=slot` 时部署新表和代码。
2. 停止所有旧实例领取提取任务，等待 RUNNING 完成或租约过期；不要让 slot/document 两种 Worker 同时运行。可以停止应用实例，待租约过期后以新配置启动。
3. 设置 `ai.memory.storage-mode=document`，再启动 Worker。尚未领取的 PENDING 任务自动使用新协议。
4. 检查任务 DONE/FAILED、Markdown 文档和延迟。默认 `legacy-slot-recall=true`，只对尚无对应类型文档的用户回退旧稳定 slot；goal 不映射。即使文档已经删空，也不再读该类型旧 slot。
5. 观察期后设置 `ai.memory.legacy-slot-recall=false`。

## 配置

```yaml
ai:
  memory:
    enabled: true
    storage-mode: document # 默认 slot
    legacy-slot-recall: true
    worker-concurrency: 2
    max-heading-code-points: 80
    max-section-bytes: 8192
    max-document-bytes: 32768
    max-patches: 8
    max-extraction-input-bytes: 48000
    max-extraction-output-bytes: 16384
    index-description-code-points: 120
```

支持对应大写下划线 `.env` 变量，例如 `AI_MEMORY_STORAGE_MODE=document`。既有 `memory-bytes` 是索引与正文共享的 Recall UTF-8 字节预算。超预算只保留完整索引项和 section，Context.reduced 标记裁剪。提取预算不足时按稳定顺序裁完整 catalog 项/正文，不截断原始问题。

登录用户可通过 `GET /api/v1/memory/documents` 读取本人当前文档；`/facts` 已 deprecated，暂保留旧 slot 只读接口。

## 回滚

停止所有 document Worker 并等待正在执行的任务完成或租约结束，再切回 `storage-mode=slot`。保留新表以便排查和恢复；V2 新增内容不会反向同步到 slot，回滚后的长期召回使用旧 slot 数据。不要直接删除表来回滚。

## 验证边界

2026-09-20 本地验证：后端 91 项测试通过，其中 9 项在真实 MySQL 8.4 隔离测试库运行，覆盖不同连接领取任务、同用户阻塞、跨用户领取、租约接管、section 顺序屏障和事务回滚。前端 `pnpm typecheck`、`pnpm build` 通过；浏览器使用独立测试用户验证了登录、WebSocket 回答完成、本人文档接口以及异步提取后 `preferences.md` 落库，任务状态为 `DONE`。生产预览使用实际 WebSocket 地址及 HTTP 跨域预检请求头，浏览器运行时无脚本错误。

H2 MySQL 模式不能证明真实 MySQL 的锁语义，上述真实 MySQL 测试只覆盖多连接，没有覆盖多个应用进程同时抢占任务；正式多实例部署仍需验证多进程 lease 接管和 slot/document 切换期间的进程隔离。
