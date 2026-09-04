# 文件上传 01-flow 扩写设计

## 目标

扩写 `docs/module-analysis/file-upload/01-flow.md`，让读者无需在多个源码文件间跳转，就能按真实执行顺序运行文件上传模块的心智模型，尤其能看清 MySQL、Redis、MinIO 的读取、验证、写入和清理顺序。

## 范围

只修改 `docs/module-analysis/file-upload/01-flow.md`，保留技能要求的两个一级业务章节：

- `## 2. 模块完整流程`
- `## 5. 核心调用链`

不修改 Java、TypeScript、配置或其他五个模块分析文件。

## 内容设计

采用“执行时序 + 每步状态快照”结构：

1. 前端生成 `fileMd5`、创建任务、按 5 MB 切片和调度上传。
2. 说明 JWT/Spring Security 到 `@RequestAttribute("userId")` 的入口链。
3. 对 `POST /upload/chunk` 逐语句级拆解：首片类型校验、组织补全、`file_upload` 查建、Redis Bitmap 查询、`chunk_info` 查询、MinIO 分支验证、上传、Redis 标记、分片元数据保存和进度响应。
4. 使用分支表完整表达 Redis、MySQL、MinIO 三者状态组合对应的行为，不把替代分支合并成单一路径。
5. 说明 `GET /upload/status` 的查询流程及当前 request attribute 证据边界。
6. 对 `POST /upload/merge` 拆解 Controller 完整性检查、数据库分片检查、MinIO `statObject/composeObject/removeObject`、Redis 清理、上传状态更新、预签名 URL 和 Kafka 发布的严格顺序。
7. 明确 Kafka 为异步边界，再追踪下载、Tika 解析、`document_vectors` 写入、Embedding 和 ES Bulk Index。
8. 单独说明 `GET /upload/supported-types` 是辅助入口，`ParseController` 是独立解析入口，不属于分片上传主链。
9. 核心调用链保留精简，只列实际关键方法，并给出每个存储操作的落点。

每个关键步骤固定交代：

```text
输入来源 → 查询/校验 → 状态变化 → 返回值 → 下一调用者
```

## 准确性边界

- 只记录当前源码实际执行的方法、字段、表、Redis Key、Topic、Bucket 和对象路径。
- HTTP 合并成功与 Kafka 最终处理成功严格分开。
- 无法从源码确认的全局异常响应、事务最终回滚等内容明确标记为 `源码暂时无法确认`。
- 配置存在但业务代码未使用的值不写成运行事实，例如 `minio.bucketName` 不替代 `UploadService` 中写死的 `uploads`。
- 不在流程文档中提出修复方案；只记录现状、分支和证据边界。

## 验证方式

完成改写后检查：

1. `01-flow.md` 仍且仅包含必需章节 2 和 5。
2. 前端、认证、chunk、status、merge、Kafka、解析、Embedding、ES 和辅助入口均有源码依据。
3. `file_upload`、`chunk_info`、Redis Bitmap、MinIO 分片/合并对象的所有实际读写顺序都有描述。
4. 文档内所有仓库相对链接可以解析到现有文件。
5. Git diff 只包含设计文档与目标流程文档，不碰业务代码和其他既有用户改动。
