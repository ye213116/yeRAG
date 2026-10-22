# yeRAG 评测 v1 设计：检索命中优先归因

## 目标

给 yeRAG 建立一套可复现、可下钻的 50 题评测：检索用与线上问答**完全同一套混合检索**（含同一套权限前置过滤），生成与评委看到的是**扩展过前后相邻分块的父块**，最终按“检索先于生成”的优先级把每道题归因到四个桶之一，回答“这一轮到底该改检索、改提示词/模型，还是数据标注有问题”。

评测对象是能力，不是容器：它不新建索引、不走旁路检索、不复制一份 prompt。

## 判定骨架

检索是自变量、评委是因变量，2×2 分桶，检索优先：

| | 评委判错 | 评委判对 |
|---|---|---|
| **检索 miss** | **A 检索问题**（源头在检索，即使生成也失败，归因不改） | **C 待定义类**（v1 只记录、不下结论） |
| **检索 hit** | **B 提示词 / 模型问题** | 通过 |

推论：

- 评委必须对 50 道题全部执行。C 桶完全依赖“miss 但评委判对”，只评 hit 会永远为空。
- A 桶仍要记录生成失败的具体形态（答错 / 拒答 / 编造），但不拿它做归因。
- C 桶在 v1 只保证“存在、可下钻”，归类规则后续单独定义，不留返工。

## 范围

### 包含

- 50 道题的数据集格式与出题配比。
- 生产侧“父块扩展”一次必要改造，评测复用同一实现。
- 评测运行器：强制检索口径（不经 Agent 决策）。
- LLM 评委与四桶归因。
- 运行与逐题结果落库、报告导出。

### 不包含（v1 明确不做）

- **私有文档（`PRIVATE_`）题**：统一以管理员身份执行，管理员拿不到他人私有文档，出这类题会稳定产生假阴性并被误算成 A 桶。权限口径改由“代码复用同一检索方法 + 组织标签边界题”保证。
- **Agent 循环口径**：v1 先强制检索，Agent 决策层留到后续作为对照诊断，不进四桶。
- **多跳题的分组证据判定**：所有题型共用一条命中规则，不引入 `evidenceGroups`。
- **父块精确重建**：不新增 `parentChunkId` 字段、不回灌历史数据，父块按 `chunkId ± 1` 近似。
- 前端管理页面：v1 提供接口与导出，页面后续补。

## 检索与生成口径

### 检索（指标计算表面）

调用 `HybridSearchService.searchWithPermission(rewrittenQuery, principalId, 5)`，与线上同一方法、同一 ACL 前置过滤；`principalId` 由服务端解析，题目作者不能指定他人身份。

- 主口径 `topK=5`，与 `ai.agent.default-top-k` 对齐；另跑 `topK=10` 报 Recall@10 作对照。
- **指标只在小分块原文上算**，即 `SearchResult.textContent`，不使用父块。父子两种结果在代码里用不同的返回结构隔开，避免以后误用父块刷高 Recall。
- 命中的 `chunkId`、名次、命中的短语都要落库，用于下钻定位。

### 命中规则（全题型统一）

`top-5` 小分块中，任意一块的正文包含 `expectedEvidence` 中的任意短语，即判 **hit**。匹配前做 NFKC 归一化并去掉空白（沿用 `paismart-eval-v1` 的做法），保证换行、全角空格、排版变化不会让标注失效。

同时记录：命中块的名次分布、`top1EvidenceRate`、文件级 `docRecallAtK`（保留作对照，用来说明小分块口径的必要性）。

`retrievalExpectation = "miss"` 的题（`neg-`、`acl-`）**不进入 A 桶**：它们的正确行为就是检索不命中，单列“符合预期”。否则 10 道负例题会被自动判成 10 个检索问题，把信号淹掉。

### 父块扩展（生产共享实现）

新增 `ParentBlockExpander`（`service` 包），检索之后、进入上下文之前执行：

```text
输入：List<SearchResult> hits + userDbId + orgTags + span=1
1. 按 fileMd5 聚合命中的 chunkId
2. 一次 ES 查询取邻域（不是每块一次）：
   bool { must: [ terms(fileMd5), range(chunkId ∈ [min-span, max+span]) ],
          filter: aclQuery(userDbId, orgTags) }
3. 同一 fileMd5 按 chunkId 排序，每个命中块取 ±1，窗口合并、去重
4. 输出 ParentBlock{fileMd5, fileName, firstChunkId, lastChunkId, text, hitChunkIds, bestScore}
```

消费方：`KnowledgeSearchTool`、`RagFallbackService`、评测运行器，三处共用。

约束：

- **子块级评测口径与父块级生成口径必须来自同一次检索结果**，父块不得反向修改 `SearchResult` 的内容或分数。
- 邻域查询**必须带 `aclQuery` 前置过滤**。不得因为“同 fileMd5 已授权”就省掉过滤；扩展后的父块集合必须是命中块 ACL 的子集，并用测试固定这条不变量。
- 多块命中同一文件时窗口合并，父块数量可少于 `topK`；生成上下文按 `bestScore` 排序。

### 上下文预算（与父块扩展同批修改）

父块比子块长，现有两处硬约束必须一起改，否则“检索命中但模型没看到”会被误判成 B 桶：

- `KnowledgeSearchTool` 与 `RagFallbackService` 里**每条正文截断 300 字**改为父块级上限（建议 1200～1500 字/块），超出按窗口裁剪并置 `truncated=true`，不能静默砍。
- `ai.agent.max-tool-result-characters` 由 6000 抬到约 12000：5 个父块约 7.5k 字，沿用 6000 会让后面几个父块被静默丢弃。

### 生成

强制检索后直接生成：同一套系统提示词、默认 `topK=5`、单轮无 history、`temperature=0`。

- 需要非流式调用；`DeepSeekClient` 目前只有流式入口，补一个 `Flux.collect` 的阻塞版本供评测使用。
- 检索前存在 LLM query 改写（`QueryRewriteService`），原始 query 与改写后 query **都要落库**，并把改写开关纳入运行配置快照；否则同一数据集两次跑出不同 Recall 会被误判成检索退化。

## 数据集格式

复用 `paismart-eval-v1.jsonl` 的 `id` 前缀分组与短语证据思路，按本设计增补字段。JSONL 一行一题，文件头用 `#` 注释说明题型与口径。

```jsonc
{
  "id": "fact-01-xxx",
  "question": "……",
  "expectedFileNames": ["baseline-a.pdf"],
  "expectedEvidence": ["……"],          // 命中判定表面 = 小分块原文
  "retrievalExpectation": "hit",        // hit | miss
  "referenceAnswer": "……",
  "keyPoints": ["……", "……"],           // 评委 rubric 必需点
  "forbiddenClaims": ["Helm", "Milvus"] // 编造检测关键词
}
```

- `retrievalExpectation` 仅 `neg-` / `acl-` 题为 `"miss"`，其余为 `"hit"`。
- 期望文档按文件名匹配（忽略大小写）；换环境后文件名不变即可复用，改名时改用 `expectedFileMd5s`。
- 用短语而非分块编号做标注，调整分块策略后标注仍然有效；但短语**不得跨两个子块边界**，否则永远命中不了（由自检脚本拦截）。

### 出题配比（50 题）

| 前缀 | 题数 | 考核点 |
|---|---|---|
| `fact-` | 12 | 单点事实，问法贴近原文，主要压 BM25 路 |
| `para-` | 12 | 口语化同义改写，关键实体在原文不出现，压 KNN 语义路 |
| `multi-` | 8 | 跨段综合，统一命中规则，压 rerank 与召回窗口 |
| `conflict-` | 3 | 资料前后说法不一致，看能否如实指出 |
| `cross-doc-` | 5 | 跨文档综合，同时存在同领域干扰文档 |
| `neg-` | 6 | 无答案或前提错误，评拒答与不编造 |
| `acl-` | 4 | 组织标签外不可见，`retrievalExpectation="miss"` |

`conflict-` 需要基准文档里天然存在前后矛盾，出题时必须先确认矛盾真实存在再落题。

### 基准知识库

使用**新上传的一套专用基准文档**：与线上业务文档隔离，历史 run 之间可比；内容可刻意安排负例与冲突段落。文件名保持稳定。

## 数据集自检（运行前置）

在真正跑评测之前，用**同一套 ACL 的 ES 直查**验证：每条 `expectedEvidence` 短语确实存在于库中，且对该题 `principal` 可见。

理由：本设计的全部归因都建立在“miss 就是检索的错”上。只要一条标注短语写错（全角空格、短语跨块、文档还没索引完），它就会稳定地被算成检索问题，而从报告上看和真问题无法区分。自检脚本用几十行代码排除这类噪音，是 P0 的交付物之一，不是可选项。

`acl-` 题反向自检：这些短语对该 principal **必须命中不到**，否则题目假设本身就不成立。

## 指标

检索侧：

- `chunkRecall@5`（主）、`chunkRecall@10`、`mrr@10`、`top1EvidenceRate`
- 命中名次分布（第 1 位 / 2-3 位 / 4-5 位）
- `docRecallAtK`（文件级对照口径）

生成与归因侧：

- `judgePassRate`
- A / B / C / 通过 / 符合预期 的计数
- A 桶内生成失败形态分布（答错 / 拒答 / 编造）

报告纪律：50 题按前缀分组后每组 3～12 道，**只报绝对数不报百分比**；需要百分比时在报告顶部写明分母，避免 3/4=75% 被当成趋势。

## 评委

输入：问题 + 父块证据 + 答案 + `keyPoints` / `forbiddenClaims`。
输出：结构化 JSON。

```jsonc
{
  "verdict": "correct",          // correct | partial | wrong
  "missingKeyPoints": ["……"],
  "fabricatedClaims": ["……"],
  "reason": "……"
}
```

- 二值化口径：`verdict == "correct"` 才算判对，`partial` 计入判错（保守）。`partial` 单独计数，用于观察 B 桶严重程度。
- 评委**不接触**检索分数、命中短语、命中名次，避免从证据来源反推结论。
- 评委对 miss 的题同样执行（C 桶需要）。
- 评委模型与生成模型可以相同，但必须是独立请求、独立 prompt、`temperature=0`；评委原始 JSON 全量落库。

## 存储与接口

MySQL 两张表，按仓库规范同步更新 `docs/databases/ddl.sql` 与 `docs/databases/database-design.md`：

- `eval_run`：数据集文件名与哈希、配置快照（`topK`、`rewrite` 开关、rerank 开关、模型名与版本）、ES 文档数、起止时间、状态、汇总指标。
- `eval_case_result`：题目 id、原始 query、改写后 query、top5 结果（块 id / 分数 / 名次 / 命中短语 / 命中 `chunkId`）、父块全文、答案、评委原始 JSON、四桶判定、A 桶失败形态。

接口挂在 `AdminController` 下：

- `POST /api/v1/admin/eval/runs`：提交一次运行（异步），返回 `runId`
- `GET /api/v1/admin/eval/runs/{runId}`：状态与汇总
- `GET /api/v1/admin/eval/runs/{runId}/cases`：逐题明细
- `GET /api/v1/admin/eval/runs/{runId}/export`：JSONL / CSV 导出

以发起评测的管理员身份执行检索，与线上权限口径一致；执行身份不从请求体接受。

## 可复现性

- 数据集哈希 + 配置快照 + 模型名入库，两次 run 的差异必须能区分“代码变了”还是“配置变了”。
- 记录 ES 索引文档数与基准文档的 fileMd5 清单，文档增删可追溯。
- 记录检索链路降级状态（向量失败 / 单路失败 / rerank 失败）。带降级的 run 在报告中标记，不能和满血 run 混在一起比较。

## 实施顺序

1. **P0** 基准文档入库 + 50 题数据集 + 自检脚本（自检跑通才谈指标）
2. **P1** `ParentBlockExpander` 进生产链路 + 上下文预算改造 + 授权不变量测试
3. **P2** 评测运行器（强制检索口径）+ 两张表 + 四桶归因
4. **P3** 评委 + 报告导出 + 逐题下钻
5. **P4** Agent 循环口径对照实验（诊断指标，不进四桶）
6. **P5** 前端管理页面

## 开放问题

- 基准文档的具体内容与领域尚未确定，`conflict-` 与 `cross-doc-` 题依赖文档里天然存在矛盾段落和同领域干扰文档。
- `acl-` 4 题要求库里真实存在一个管理员不属于的组织标签文档，需在基准文档入库时一并准备。
