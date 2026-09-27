# 统一记忆部署说明（现有 MySQL 数据库）

本次升级只需执行同目录的 [一份 SQL](20260927_durable_short_term_memory.sql)。它为 `chat_session` 增加持久化摘要、摘要覆盖的回合 ID 和独立版本号；可重复执行，不删除旧 `conversations` 表或现有会话、回合。空数据库初始化仍使用 [完整 DDL](../ddl.sql)。

## 当前 Docker 环境的执行结果

2026-09-27 已在运行中的 MySQL 容器 `mysql` 的 `paismart` 数据库执行该 SQL，并重复执行验证幂等。三个字段分别为 `context_summary TEXT NULL`、`summary_covered_turn_seq BIGINT NOT NULL DEFAULT 0`、`summary_revision BIGINT NOT NULL DEFAULT 0`。执行前后均为 4 条 `chat_session`、4 条 `chat_turn`；旧 `conversations` 表保留，当前为 0 条。执行前的数据库备份位于宿主机 `/tmp/yeRAG-paismart-before-memory-20260927.sql`。

同日检查 Redis：旧完整问答 `conversation:*` 和旧会话指针 `user:*:current_conversation` 均为 0 个；两个 `memory:v2:session:*:context` 键的摘要内容均为空，故当前没有有效旧问答或摘要需要导入。新版本启动后仍会重试扫描旧键；其他环境有有效旧数据时，保持 Redis 数据和连接可用，等待迁移日志完成后再放量。

本机后端已打包并启动为宿主机 Java 进程，监听 `8081`。运行时读取相邻 yeRAG 项目已有的开发环境配置，连接目标库时使用实际库名 `paismart`，并覆盖 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`；没有把密钥写入本仓库。进程号记录在 `/tmp/yeRAG-agent-deploy-20260927.pid`，启动日志在 `/tmp/yeRAG-agent-deploy-20260927.log`。未认证访问 `/api/v1/memory/sessions` 返回 403，说明服务已响应且接口受保护；启动后未记录错误。该宿主机进程没有自动重启机制，正式运行需纳入现有进程管理或容器发布流程。

## 在其他现有环境执行

先备份目标数据库，确认使用的是现有业务库，再执行：

```bash
docker exec mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers paismart' > paismart-before-memory.sql
docker exec -i mysql sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" paismart' < docs/databases/migrations/20260927_durable_short_term_memory.sql
```

如容器名、数据库名或认证方式不同，请替换命令中的对应值。MySQL 8 客户端会显示命令行密码警告；密码在容器内从环境变量读取，SQL 和说明文档中没有保存密码。

## 应用发布顺序

1. 先执行 SQL，并确认输出包含三个新增字段。
2. 保留旧 Redis 数据，配置新版本连接同一个 MySQL 和 Redis。启动应用时 `MemoryStartupCheck` 会校验表和列；`LegacyMemoryMigration` 会导入仍有效的旧问答与摘要，按用户归属和会话去重，并定时重试。查看迁移日志，完成后再开放聊天流量。
3. 新版本聊天入口只接受带 `conversationId`、`requestId` 的 JSON WebSocket 消息；前端和后端应一起发布。成功回答会写入 MySQL，短期上下文默认续期 7 天。旧 `conversations` 表暂时保留，不再作为运行时聊天存储。

本仓库没有生产 `application.yml`、Dockerfile 或 Compose 文件；当前 Docker 里只有 MySQL、Redis、Kafka、Elasticsearch、MinIO，没有应用容器。本机暂用已有的外部开发配置启动后端。其他环境启动时需由部署环境提供数据源、Redis、Kafka、Elasticsearch、MinIO、JWT、聊天模型及 Embedding 的真实配置。配置齐备后，可用 `mvn spring-boot:run` 或现有发布系统启动；设置 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`，不要依赖自动建表。

## 验证

```bash
docker exec mysql sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" paismart -e "SHOW COLUMNS FROM chat_session WHERE Field IN ('\''context_summary'\'','\''summary_covered_turn_seq'\'','\''summary_revision'\'')"'
```

应用启动后检查启动日志无 schema 校验错误，再创建会话、完成一轮问答并确认 `chat_turn` 和 `chat_session.context_expires_at` 已更新。清空对应 Redis 缓存并重连时，摘要和近期完整问答应从 MySQL 恢复。旧键迁移失败会保留在 Redis 中等待重试，不应提前删除。
