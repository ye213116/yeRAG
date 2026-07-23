# PaiSmart AI 知识库管理系统 - 课程大纲

> 30 节系统化课程，围绕 5 大核心模块深入 RAG 技术实战

---

## 模块一：架构设计（第 1-3 课）

### 第 1 课：系统架构总览与 RAG 技术原理
**学习目标**：理解系统整体架构和 RAG 核心技术

**内容**：
- RAG 技术背景与发展（Retrieval-Augmented Generation）
- PaiSmart 系统架构全景图
- 核心技术栈介绍（Spring Boot + Vue 3 + Elasticsearch + Kafka）
- 数据流向：从文档上传到 AI 回答

**实践**：
- 绘制系统架构图
- 绘制 RAG 工作流程图

**代码阅读**：
- `SmartPaiApplication.java` - 应用入口
- `application.yml` - 核心配置

---

### 第 2 课：后端分层架构设计
**学习目标**：理解 Spring Boot 分层架构和代码组织

**内容**：
- 包结构设计与职责划分
- Controller-Service-Repository 分层模式
- 实体类（Entity）与数据模型（Model）设计
- 配置类的组织方式

**实践**：
- 追踪一个 API 请求的完整链路
- 绘制包依赖关系图

**代码阅读**：
- `controller/` 目录结构
- `service/` 目录结构
- `repository/` 目录结构

---

### 第 3 课：数据模型与数据库设计
**学习目标**：理解数据模型设计和 JPA 实战

**内容**：
- 核心实体关系：User、FileUpload、Conversation
- JPA 实体映射与关系定义
- Repository 接口设计与查询方法
- 多租户数据隔离设计

**代码阅读**：
- `model/User.java` - 用户实体
- `model/FileUpload.java` - 文件上传记录
- `model/Conversation.java` - 对话实体
- `model/ChunkInfo.java` - 分块信息
- `model/DocumentVector.java` - 文档向量

**实践**：
- 导出并分析数据库 ER 图
- 编写自定义 Repository 查询

---

## 模块二：用户管理（第 4-7 课）

### 第 4 课：用户认证与 JWT 机制
**学习目标**：掌握用户认证流程和 JWT Token 设计

**内容**：
- Spring Security 配置
- JWT Token 结构与 Claims 设计（tokenId、userId、role、orgTags）
- 认证过滤器实现
- Token 刷新机制（预刷新 + 宽限期）
- Redis Token 缓存与黑名单

**代码阅读**：
- `controller/UserController.java` - 登录/登出接口
- `service/UserService.java` - 认证逻辑
- `utils/JwtUtils.java` - JWT 工具类
- `service/TokenCacheService.java` - Token 缓存服务
- `config/JwtAuthenticationFilter.java` - JWT 认证过滤器

**实践**：
- 解析 JWT Token 内容
- 使用 Redis CLI 查看 Token 缓存
- 测试 Token 失效场景

---

### 第 5 课：用户注册与组织标签分配
**学习目标**：理解用户注册流程和初始权限分配

**内容**：
- 注册流程设计
- 默认组织标签分配
- 私人组织标签创建（PRIVATE_{userId}）
- 密码加密存储（BCrypt）

**代码阅读**：
- `service/UserService.java` (registerUser 方法)
- `controller/UserController.java` - 注册接口

**实践**：
- 追踪注册流程的数据变化
- 查看数据库中的用户和标签数据

---

### 第 6 课：用户信息管理
**学习目标**：掌握用户 CRUD 操作和列表查询

**内容**：
- 用户信息获取（/api/v1/users/me）
- 用户列表分页查询
- 用户组织标签管理
- 主组织标签设置

**代码阅读**：
- `controller/UserController.java` - 用户管理接口
- `service/UserService.java` (getUserOrgTags、setUserPrimaryOrg 方法)

**实践**：
- 实现用户搜索功能
- 添加用户状态过滤

---

### 第 7 课：组织标签架构设计
**学习目标**：理解多租户权限隔离机制

**内容**：
- 组织标签（OrganizationTag）实体设计
- 用户 - 组织关系模型
- 文档权限：公开/私有/组织共享
- 组织标签授权过滤器（OrgTagAuthorizationFilter）
- 标签层级与继承

**代码阅读**：
- `model/OrganizationTag.java`
- `config/OrgTagAuthorizationFilter.java`
- `service/OrgTagCacheService.java` - 组织标签缓存

**实践**：
- 设计多租户测试场景
- 验证权限隔离效果

---

## 模块三：文件上传（第 8-13 课）

### 第 8 课：分片上传架构设计
**学习目标**：理解大文件分片上传的设计思想

**内容**：
- 为什么需要分片上传
- 分片上传协议设计
- 前端分片逻辑
- 后端接收接口设计

**代码阅读**：
- `controller/UploadController.java` - 分片上传接口
- `frontend/src/views/knowledge-base/index.vue` - 前端上传逻辑

**实践**：
- 使用 Postman 模拟分片上传
- 分析上传请求的响应数据

---

### 第 9 课：上传进度追踪
**学习目标**：掌握 Redis Bitmap 进度追踪技术

**内容**：
- Redis Bitmap 数据结构
- 进度位图设计与更新
- 上传状态查询接口
- 断点续传原理

**代码阅读**：
- `service/UploadService.java` (进度追踪相关方法)

**实践**：
- 使用 Redis CLI 查看进度位图
- 模拟断点续传场景

---

### 第 10 课：文件合并与 MD5 去重
**学习目标**：掌握文件合并逻辑和去重机制

**内容**：
- 分片合并流程
- MD5 计算与校验
- 文件去重判断
- MinIO 持久化存储

**代码阅读**：
- `service/UploadService.java` (mergeChunks 方法)
- `utils/MD5Util.java`

**实践**：
- 查看 MinIO 中的文件数据
- 测试相同文件上传

---

### 第 11 课：Kafka 异步文件处理
**学习目标**：理解文件处理的异步解耦设计

**内容**：
- 文件处理任务发布
- Kafka 消息结构
- 消费者组设计
- 异步处理优势

**代码阅读**：
- `service/UploadService.java` (发送 Kafka 消息)
- `consumer/FileProcessingConsumer.java`
- `config/KafkaConfig.java` - Kafka 配置

**实践**：
- 查看 Kafka 中的任务消息
- 分析消费者处理日志

---

### 第 12 课：文档解析技术
**学习目标**：掌握多种文件格式的解析方法

**内容**：
- Apache Tika 使用
- 支持的文件格式（PDF、Word、Excel、TXT 等）
- 文本提取原理
- 编码与格式处理

**代码阅读**：
- `service/ParseService.java` (parseAndSave 方法)

**实践**：
- 测试不同格式文件解析
- 对比解析结果

---

### 第 13 课：智能分块策略
**学习目标**：理解文档分块的设计与实现

**内容**：
- 为什么需要分块（Token 限制、搜索精度）
- 分块大小选择（512 字符）
- HanLP 中文分词
- 父文档 - 子切片策略
- 语义完整性保持

**代码阅读**：
- `service/ParseService.java` (分块逻辑)
- `model/ChunkInfo.java`
- `model/DocumentVector.java`

**实践**：
- 调整分块参数测试效果
- 对比不同分块策略的搜索结果

---

## 模块四：知识库检索（第 14-20 课）

### 第 14 课：Elasticsearch 索引设计
**学习目标**：掌握 ES 索引结构和向量化

**内容**：
- ES 索引与文档结构
- 向量字段设计（dense_vector）
- 批量索引操作
- 文档删除与更新

**代码阅读**：
- `entity/EsDocument.java`
- `service/ElasticsearchService.java`
- `service/VectorizationService.java`

**实践**：
- 使用 Kibana 查看索引数据
- 执行 ES 查询测试

---

### 第 15 课：Embedding 向量化技术
**学习目标**：理解文本向量化原理和 API 调用

**内容**：
- 文本 Embedding 原理
- 通义千问 Embedding API
- 批量向量化优化
- 向量维度与相似度

**代码阅读**：
- `client/EmbeddingClient.java`

**实践**：
- 调用 Embedding API 测试
- 手动计算向量相似度

---

### 第 16 课：KNN 向量相似度搜索
**学习目标**：掌握 KNN 向量搜索原理和实现

**内容**：
- 余弦相似度计算
- KNN 搜索算法
- ES KNN 查询语法
- 召回率与精度

**代码阅读**：
- `service/HybridSearchService.java` (KNN 部分)

**实践**：
- 执行 KNN 查询
- 分析召回结果

---

### 第 17 课：BM25 文本匹配
**学习目标**：理解传统文本检索算法

**内容**：
- TF-IDF 原理
- BM25 算法详解
- ES BM25 配置
- 与向量搜索的对比

**代码阅读**：
- `service/HybridSearchService.java` (BM25 部分)

**实践**：
- 执行 BM25 查询
- 对比 KNN 与 BM25 结果差异

---

### 第 18 课：混合搜索实现
**学习目标**：掌握混合搜索的实现方式

**内容**：
- 为什么需要混合搜索
- 两阶段召回策略
- 结果融合算法
- 重排序（Rerank）策略

**代码阅读**：
- `service/HybridSearchService.java` (核心方法)

**实践**：
- 调整混合权重参数
- 测试搜索效果变化

---

### 第 19 课：权限过滤机制
**学习目标**：理解搜索结果的权限控制

**内容**：
- 文档可见性规则
- 权限过滤实现
- 用户 - 组织标签匹配
- 公开/私有文档处理

**代码阅读**：
- `service/HybridSearchService.java` (权限过滤部分)
- `service/DocumentService.java` (权限相关)

**实践**：
- 创建多用户测试权限
- 验证权限过滤效果

---

### 第 20 课：搜索结果优化
**学习目标**：学习搜索结果优化技巧

**内容**：
- 相关性评分计算
- 结果去重策略
- 来源文件关联
- 分页与高亮

**代码阅读**：
- `entity/SearchResult.java`
- `service/HybridSearchService.java` (优化部分)

**实践**：
- 实现搜索结果分页
- 添加文件名关联

---

## 模块五：聊天助手（第 21-30 课）

### 第 21 课：RAG Prompt 工程设计
**学习目标**：掌握 RAG Prompt 设计技巧

**内容**：
- Prompt 基础结构
- 系统指令（System Prompt）设计
- 上下文组织方式
- 引用标注格式

**代码阅读**：
- `service/ChatHandler.java` (Prompt 构建部分)

**实践**：
- 分析现有 Prompt 模板
- 设计优化的 Prompt

---

### 第 22 课：DeepSeek API 集成
**学习目标**：掌握 LLM API 调用方式

**内容**：
- DeepSeek API 介绍
- API 请求结构
- 参数配置说明
- 错误处理与重试

**代码阅读**：
- `client/DeepSeekClient.java`

**实践**：
- 调用 DeepSeek API 测试
- 处理 API 错误响应

---

### 第 23 课：RAG 上下文构建
**学习目标**：理解检索结果到上下文的转换

**内容**：
- 搜索结果格式化
- Token 数量控制
- 来源 MD5 标注
- 上下文与问题拼接

**代码阅读**：
- `service/ChatHandler.java` (上下文构建部分)

**实践**：
- 调整上下文长度
- 测试不同 Token 限制

---

### 第 24 课：WebSocket 实时通信
**学习目标**：掌握 WebSocket 在项目中的应用

**内容**：
- WebSocket 协议基础
- Spring WebSocket 配置
- JWT 认证集成
- 连接管理与心跳

**代码阅读**：
- `handler/ChatWebSocketHandler.java`
- `config/WebSocketConfig.java`

**实践**：
- 创建 WebSocket 测试客户端
- 实现连接状态监控

---

### 第 25 课：流式响应实现
**学习目标**：理解流式输出的原理与实现

**内容**：
- SSE 与流式传输
- 流式处理优势
- 前端流式接收
- 停止响应机制

**代码阅读**：
- `client/DeepSeekClient.java` (流式部分)
- `handler/ChatWebSocketHandler.java` (停止逻辑)
- `frontend/src/views/chat/modules/input-box.vue`

**实践**：
- 实现停止生成功能
- 分析流式数据格式

---

### 第 26 课：对话历史管理
**学习目标**：理解对话状态管理

**内容**：
- 会话数据结构
- Redis 存储策略
- 上下文长度控制
- 多轮对话处理

**代码阅读**：
- `service/ConversationService.java`
- `model/Conversation.java`

**实践**：
- 查看 Redis 中的对话数据
- 实现对话导出功能

---

### 第 27 课：WebSocket 聊天接口（补充）
**学习目标**：深入理解 WebSocket 聊天完整流程

**内容**：
- WebSocket 消息协议设计
- 聊天请求处理流程
- 异常处理与断线重连
- 并发连接管理

**代码阅读**：
- `handler/ChatHandler.java` (WebSocket 消息处理)
- `frontend/src/views/chat/modules/chat.vue`

**实践**：
- 实现聊天消息持久化
- 添加消息已读状态

---

### 第 28 课：缓存优化设计（补充）
**学习目标**：理解系统缓存优化策略

**内容**：
- 对话缓存策略
- 组织标签缓存
- 缓存一致性保证
- 缓存穿透/击穿/雪崩预防

**代码阅读**：
- `service/ConversationService.java` (缓存部分)
- `service/OrgTagCacheService.java`

**实践**：
- 使用 Redis CLI 查看缓存数据
- 设计缓存失效场景

---

### 第 29 课：错误处理与日志系统（补充）
**学习目标**：掌握异常处理和日志记录

**内容**：
- 自定义异常设计
- 全局异常处理器
- 业务日志规范
- 性能监控日志

**代码阅读**：
- `exception/CustomException.java`
- `exception/GlobalExceptionHandler.java`
- `utils/LogUtils.java`

**实践**：
- 添加自定义异常类型
- 实现业务日志记录

---

### 第 30 课：综合实战与扩展
**学习目标**：综合运用所学知识开发新功能

**实践项目**（三选一）：
1. **文档标签系统**：实现文档分类和标签管理
2. **智能问答推荐**：基于历史对话推荐相关问题
3. **向量缓存**：缓存常见问题和答案

**要求**：
- 完整的前后端实现
- 包含单元测试
- 撰写技术文档

**后续方向**：
- RAG 高级优化技术（Query 改写、多路召回）
- 多模态知识检索
- Agent 系统设计
- 大规模部署实践

---

## 附录

### A. 每节课建议时长
- 理论学习：30-45 分钟
- 代码阅读：30-60 分钟
- 实践练习：60-90 分钟
- **总计**：约 2-3 小时/课

### B. 先修知识要求
- Java 编程基础
- Spring Boot 基础
- Vue 3 基础
- SQL 基础语法
- HTTP 协议基础

### C. 推荐学习节奏
| 模块 | 建议时长 | 课程数 |
|-----|---------|-------|
| 架构设计 | 3 天 | 3 课 |
| 用户管理 | 4 天 | 4 课 |
| 文件上传 | 6 天 | 6 课 |
| 知识库检索 | 7 天 | 7 课 |
| 聊天助手 | 10 天 | 10 课 |
| **总计** | **约 30 天** | **30 课** |

### D. 模块依赖关系
```
架构设计 → 用户管理 → 文件上传 → 知识库检索 → 聊天助手
    ↓           ↓          ↓           ↓           ↓
  基础       权限       数据处理     核心搜索    AI 对话
```

### E. 学习检查清单

**模块一（架构设计）检查**：
- [ ] 理解系统整体架构
- [ ] 能够追踪请求链路
- [ ] 理解 JPA 实体关系

**模块二（用户管理）检查**：
- [ ] 理解 JWT 认证流程
- [ ] 理解组织标签设计
- [ ] 理解多租户权限隔离

**模块三（文件上传）检查**：
- [ ] 理解分片上传原理
- [ ] 理解异步处理流程
- [ ] 理解文档解析分块

**模块四（知识库检索）检查**：
- [ ] 理解 KNN 向量搜索
- [ ] 理解 BM25 文本匹配
- [ ] 理解混合搜索原理
- [ ] 理解权限过滤

**模块五（聊天助手）检查**：
- [ ] 理解 RAG Prompt 设计
- [ ] 理解 WebSocket 通信
- [ ] 理解流式响应
- [ ] 完成结业项目

---

*课程大纲版本：3.0*
*最后更新：2026-03-22*
