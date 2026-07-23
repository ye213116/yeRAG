# 第 1 课：系统架构总览与 RAG 技术原理

> 课程重点内容总结

---

## 一、核心概念

### 1.1 RAG 是什么？

**RAG (Retrieval-Augmented Generation)** = 检索 + 生成

```
传统 LLM 局限：
- 知识截止于训练数据
- 无法访问私有数据
- 容易产生幻觉（胡编乱造）
- 答案来源不可追溯

RAG 解决方案：
1. 检索 (Retrieval) - 从知识库找到相关文档
2. 增强 (Augmentation) - 把文档内容添加到 Prompt
3. 生成 (Generation) - LLM 基于文档生成准确回答
```

### 1.2 为什么需要 RAG？

| 问题 | 传统 LLM | RAG 方案 |
|------|----------|----------|
| 知识时效性 | ❌ 训练数据截止后不知道 | ✅ 可以检索最新文档 |
| 私有知识 | ❌ 无法访问企业内部数据 | ✅ 可以索引私有文档 |
| 幻觉问题 | ❌ 容易胡编乱造 | ✅ 基于检索结果回答 |
| 可追溯性 | ❌ 不知道答案来源 | ✅ 可以标注引用来源 |

---

## 二、系统架构全景

### 2.1 技术栈

```
前端层：Vue 3 + TypeScript + Pinia + Vite
        ↓ ↑ (WebSocket / HTTP)
应用层：Spring Boot + Spring Security + JWT + JPA
        ↓
数据层：MySQL + Redis + Elasticsearch + Kafka + MinIO
        ↓
AI 服务：DeepSeek API (LLM) + DashScope Embedding (向量化)
```

### 2.2 核心中间件及作用

| 中间件 | 作用 | 使用场景 |
|--------|------|----------|
| **MySQL** | 关系型数据库 | 用户、对话、文件元数据 |
| **Redis** | 缓存 | 上传进度、对话历史、组织标签缓存 |
| **Elasticsearch** | 向量搜索 | 文档检索（KNN + BM25） |
| **Kafka** | 消息队列 | 异步文件处理 |
| **MinIO** | 对象存储 | 文件存储 |

---

## 三、核心数据流

### 3.1 文档处理流水线（写路径）

```
用户上传 → 分片存储 (MinIO) → 合并文件 → Kafka 异步处理 → 解析分块 → 向量化 → ES 索引
```

**10 个步骤**：
1. 前端分片上传
2. 后端存储分片到 MinIO
3. Redis 记录上传进度（Bitmap）
4. 上传完成，返回成功
5. 发送 Kafka 任务消息
6. 消费者接收任务
7. 解析文档并分块（存入 MySQL）
8. 调用 Embedding API 向量化
9. 批量索引到 Elasticsearch
10. 处理完成，通知前端

### 3.2 知识检索流水线（读路径）

```
用户提问 → 生成向量 → KNN 召回 → 权限过滤 → BM25 重排序 → 构建 Prompt → DeepSeek → 流式响应
```

**10 个步骤**：
1. 前端通过 WebSocket 发送问题
2. 后端调用 Embedding API 生成查询向量
3. ES 执行 KNN 向量召回（召回 30 条）
4. 根据用户权限过滤（用户/组织/公开）
5. BM25 重排序（取前 5 条）
6. 构建 RAG Prompt（System + Context + Question）
7. 流式调用 DeepSeek API
8. 通过 WebSocket 流式推送回答
9. 保存对话历史到 Redis
10. 前端渲染回答和引用来源

---

## 四、关键设计亮点

### 4.1 分片上传 + Redis Bitmap 进度追踪

```
文件 MD5: abc123...
分片大小：1MB
总大小：5MB → 需要 5 个分片

Redis Key: upload:progress:abc123...
Redis Value (Bitmap): 11111 (5 个分片都到了)

查询进度：数 1 的个数 / 总分片数 = 100%
```

**优势**：
- 支持大文件（50MB+）
- 支持断点续传
- 精确的进度追踪

### 4.2 智能分块策略

```
原文档（5000 字）
    ↓
按段落分割 → 保持语义完整
    ↓
HanLP 中文分词 → 按语义边界切分
    ↓
每块约 512 字符（可配置）

结果：
父文档（5000 字全文）→ 用于预览
  ├── 子切片 1 (0-512)  → 用于向量化和搜索
  ├── 子切片 2 (513-1024)
  └── ...
```

**为什么分块？**
- LLM 有 Token 限制
- 整篇文档向量化精度低
- 需要精确定位到具体内容

### 4.3 混合搜索（Hybrid Search）

```
用户问题："报销流程"

阶段 1 - KNN 向量召回：
  召回 30 条语义相似的文档片段

阶段 2 - 权限过滤：
  过滤掉用户无权访问的文档

阶段 3 - BM25 重排序：
  用 BM25 对剩余结果重新打分
  取前 5 条作为最终结果

最终：5 条最相关的文档片段 → 送入 Prompt
```

**为什么混合？**
- 向量搜索：语义相似，但可能关键词不匹配
- BM25：关键词匹配准，但不懂语义
- 混合 = 两者优势结合

---

## 五、核心模块与代码对应

### 5.1 后端核心 Service

| Service | 职责 | 关键方法 | 文件路径 |
|---------|------|----------|----------|
| `UploadService` | 分片上传、合并、进度追踪 | `mergeChunks()` | `service/UploadService.java` |
| `ParseService` | 文档解析、智能分块 | `parseAndSave()` | `service/ParseService.java` |
| `VectorizationService` | 批量向量化、ES 索引 | `vectorize()` | `service/VectorizationService.java` |
| `HybridSearchService` | 混合搜索、权限过滤 | `searchWithPermission()` | `service/HybridSearchService.java` |
| `ChatHandler` | RAG 上下文、流式响应 | `processMessage()` | `service/ChatHandler.java` |
| `UserService` | 用户认证、组织管理 | `authenticateUser()` | `service/UserService.java` |

### 5.2 前端核心页面

| 页面 | 路径 | 功能 | 文件路径 |
|------|------|------|----------|
| 知识库 | `/knowledge-base` | 文档上传、管理、预览 | `frontend/src/views/knowledge-base/index.vue` |
| 聊天 | `/chat` | AI 对话、来源追溯 | `frontend/src/views/chat/index.vue` |
| 用户中心 | `/user-center` | 用户管理、组织管理 | `frontend/src/views/user-center/index.vue` |

---

## 六、实践练习

### 练习 1：绘制 RAG 工作流程图

用你喜欢的工具（Draw.io、Excalidraw、ProcessOn 等）绘制：
1. 文档上传到可搜索的完整流程
2. 用户提问到获得回答的完整流程

### 练习 2：数据库探索

```bash
# 查看数据库表结构
docker exec mysql mysql -u root -pPaiSmart2025 -e "USE PaiSmart; SHOW TABLES;"

# 查看用户数据
docker exec mysql mysql -u root -pPaiSmart2025 -e "USE PaiSmart; SELECT id, username, role, org_tags FROM users;"

# 查看组织标签
docker exec mysql mysql -u root -pPaiSmart2025 -e "USE PaiSmart; SELECT * FROM organization_tags;"
```

### 练习 3：ES 索引探索

```bash
# 查看 ES 中的文档
curl -u elastic:PaiSmart2025 "http://localhost:9200/knowledge_base/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"match_all":{}},"size":5}'
```

---

## 七、课后总结

### 本课要点回顾

| 知识点 | 关键内容 |
|--------|----------|
| RAG 原理 | 检索 → 增强 → 生成 |
| 系统架构 | 前后端分离 + 多中间件 |
| 数据流 | 写路径（上传）+ 读路径（检索） |
| 核心设计 | 分片上传、智能分块、混合搜索 |

### 关键代码文件

- `application.yml` - 核心配置文件
- `SmartPaiApplication.java` - 应用入口

### 下节课预告

**第 2 课：后端分层架构设计**
- 深入代码层面理解包结构
- 追踪一个 API 请求的完整链路
- 理解 Controller-Service-Repository 分层模式

---

*课程版本：2.0*
*更新日期：2026-03-17*
