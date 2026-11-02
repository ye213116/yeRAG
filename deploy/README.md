# Docker 统一部署

`compose.yaml` 部署 yeRAG 后端和 Nginx 前端，复用现有 MySQL、Redis、Elasticsearch、Kafka、MinIO 所在的 Docker 网络。它不会自动创建一套空的业务数据卷或替换现有中间件；默认网络是 `pai_smart_default`。

1. 准备真实的私有配置文件，参考 [变量示例](.env.docker.example)。`DOCKER_DATASOURCE_URL` 指向网络内现有 MySQL 与原数据库；Redis/ES/Kafka/MinIO 使用容器内地址，Kafka 的 advertised listener 必须可从后端容器访问。不要把模型密钥或 `.env` 加入镜像和 Git。
2. 先完成后端、前端验证；停止所有旧版 yeRAG 及 Worker。脚本只停止本 Compose 项目的旧后端，不能识别其他机器/项目启动的 Worker。
3. 执行：

```bash
python3 scripts/deploy-docker.py --env /absolute/path/to/private.env --mysql-container mysql --network pai_smart_default
```

脚本执行 Maven package、前端构建、镜像构建；备份现有数据库到忽略目录 `.codex/deployment/`（权限 600），按结构检查应用短期和长期记忆迁移，再启动两个容器，验证后端认证响应、前端首页、`/chat` 直接刷新和 API 代理。已验证并构建镜像后可用 `--skip-build`。Docker Desktop 的 WSL CLI 不可用时可用 `DOCKER_BIN` 指向 Windows `docker.exe`，脚本自动转换客户端文件路径。

Compose 会将 `DOCKER_*` 地址显式映射为后端的环境变量，避免 `.env` 中用于宿主机运行的 `SPRING_DATASOURCE_URL`、Redis、Kafka 等地址覆盖容器内配置。

前端使用同源 `/api/v1` 与 `/chat/` 代理；精确的 `/chat` 路径由 Nginx 返回 SPA，避免自动补斜杠时重定向到容器内端口。默认访问 `http://localhost:9527`，后端 `http://localhost:8081`；仅绑定主机回环地址。容器镜像仅复制已编译 JAR、前端 dist 和公开配置；运行期密钥由私有 env 文件注入。

已有数据库先应用 [持久会话迁移](../docs/databases/migrations/20261102_persistent_conversation_history.sql)，再应用 [按需 Markdown 记忆迁移](../docs/databases/migrations/20261102_on_demand_markdown_memory.sql)。空库使用完整 DDL。迁移不能恢复旧版本已经清空的问答。检测到部分迁移时停止，使用备份核对修复，不能重复盲跑增量 SQL。

启动成功不代表真实模型的语义更新和历史找回验收已通过；后续需以真实问答、任务状态、版本快照和工具事件验证。回退应用镜像前还要确认旧 Worker 不会重新启用到期清理；保留数据库备份，不回退单调版本号或轮次屏障。
