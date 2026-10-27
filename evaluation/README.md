# yeRAG 评测主文档

> **唯一维护入口**：后续评测机制、当前结果、运行方法和工作计划只更新本文。其他评测说明、调研、设计和实验纪要作为历史快照保留；题集、来源许可、协议、机器验证记录及运行工件作为版本化证据保留，不另维护一份“当前状态”。
>
> 更新日期：2026-10-27。工作区：`yeRAG-agent`；分支：`agent`；核对 HEAD：`fd8d6a3134f48f7a34422a5b3dc4e1547ae74334`。本批代码、数据与文档仍有**未提交**变更，不能只用 HEAD 代表实测代码。

阅读入口：[当前结论](#1-当前结论与采用的基线) · [指标口径](#4-指标口径与可解释边界) · [真实结果](#6-已完成的真实实验) · [运行方法](#8-环境与可复现运行) · [证据与人审](#9-工件人审与证据保存) · [收工清单](#11-本轮收工清单与后续顺序)。

## 1. 当前结论与采用的基线

主检索链路为 **带 ACL 的 BM25 + Vector → RRF → rerank → TopK → 上下文组装 → 生成**。今后以这条完整链路作为主基线；BM25、Vector、RRF 单独作为消融，用于解释收益来自哪个环节。

| 项目 | 当前已核实结论 | 使用边界 |
|---|---|---|
| 主检索链路 | 原50题诊断后，新的100题两轮均CompleteHit67%→89%，EM分别63%→70%、62%→71%，开发门禁均PASS | v2重排200次全部成功；独立于旧250题，仍是英语共享候选子库，非生产验收 |
| 召回消融 | 已完成 BM25 / Vector / Hybrid 两轮；其中 Hybrid 是关闭重排的 RRF | 不能用该 Hybrid 成绩代表完整主链路，也不能由其低于 Vector 推断重排无收益 |
| 融合权重优化 | 开发集选中 BM25权重0.5，留出集 EM 从64%降到62%，开发门禁 FAIL | **不采纳该候选，维持1:1默认权重**；旧留出集已开放，不再用于重新选参 |
| 数据覆盖 | 已真实使用350道不同HotpotQA题：旧50＋study200＋新留出100 | 新100题排除旧250题ID和规范化问题；未完成多个公开基准或中文基准 |
| 企业50题 | zyh批准50题标签，真实50题均完成；40道正例证据全部入模，Judge48正确/2部分完整 | 4道ACL＋6道无答案均拒答；受限来源/精确泄漏0，安全检查PASS；生成答案未冒充人工全判 |
| 评测机制 | 来源校验、阶段证据定位、原生答案评分、独立 Judge、冻结工件、离线重算和配对检查已落地 | 程序事实、模型意见与人工结论分开，不安装框架来替代标签和验证 |

本轮完整后端测试为 **258项：248通过、10跳过、0失败/错误**；Python收工工具测试 **36项通过**。企业题集范围约束已通过失败回归、定向权限测试及完整测试；新100题仍使用修复前已封存的同一Java版本，不混合代码版本计算增益。跳过的9项 MySQL、1项 ES 测试不宣称通过；真实公开评测使用实际 MySQL/ES，与这些可选测试的运行状态分开记录。

新100题与此前主链路诊断实测 Java/pom SHA-256 为 `d92ca6760836064530cded5df872d268f5373fb2264407dbd5a856879a6bfff5`，完整 `codeVersion` 为 HEAD 加该指纹。企业范围约束修复后的Java/pom指纹为 `714edce6b8f2584f85c9ae0e394fcff38fa6557ced2b54250999ac399f35fb9f`。旧实验使用各自记录的源码指纹，不能当作与当前实现完全相同。

## 2. 评测范围与实际实现

### 2.1 复用生产路径

```text
UploadController → MinIO → Kafka → 解析/分块 → Embedding → Elasticsearch

可信身份 + 题集语料范围
  → Query（可配置改写）
  → Retrieve（BM25 / Vector，两个路由均进行 ACL AND 语料范围预过滤）
  → RRF（按名次融合，默认权重1:1、k=60）
  → Rerank（候选头部，默认topN=50；失败退回RRF）
  → TOP_K（冻结重排后、合并前的Top5原始子块）
  → Merge → Assemble → Generate
  → 独立正确性Judge；可选独立回答支持度Judge
```

每道 READY 题只订阅一次现有 `RagPipeline.run(FIXED_QA)`，单轮、空 history、TopK=5；Judge 是独立请求。没有复制一套旁路检索或评分专用生成模型。

Agent 的知识库工具复用同一 Pipeline 的 `RETRIEVE_ONLY` 路径。固定 QA 能验证共享检索/重排实现，**不能代表已经评了 Agent 是否查库、工具参数、循环、失败回退、会话隔离或多轮 Memory**。本次重排对照还固定关闭了查询改写。

模型只能提交 query/topK，身份与权限来自服务端可信上下文。评测语料范围只能缩小原授权，不能授予权限。BM25、KNN 和 legacy 路径均保留范围与 ACL 的交集约束；普通模型工具不新增身份、组织或 ACL 参数。

### 2.2 主要入口与实现状态

| 能力 | 入口或实现 | 状态 |
|---|---|---|
| 统一命令 | [`scripts/rag-eval.sh`](../scripts/rag-eval.sh) | 已实现 |
| 数据集加载、来源校验 | `EvaluationCases`、`PublicDatasetSource` | 已实现 schema v2/v3 分层 |
| 真实预检和执行 | `EvaluationCorpus`、`EvaluationPreflight`、`RagExperimentRunner` | 已运行公开集 |
| 原生答案评分 | `BenchmarkAnswerMetrics` | 已实现 HotpotQA；通用包装不虚填 scorer |
| 阶段证据与跨块范围 | `EvidencePositions`、`EvidenceSpanMapping` | 已实现，跨块映射仅在满足原文范围条件时成立 |
| 质量评审与测量 | `EvaluationJudge`、`EvaluationSupportJudge`、`EvaluationMeasurements` | 已实现；自定义支持度未完成人工校准 |
| 报告和外部输入 | `EvaluationRunner`、`EvaluationExport` | 已实现及离线核验 |
| 召回/重排对照、配对门禁 | [`rag-eval-experiment.py`](../scripts/rag-eval-experiment.py) | 已真实执行 |
| 开发/留出与权重重放 | [`rag-eval-study.py`](../scripts/rag-eval-study.py)、`EvaluationRankReplay` | 已执行一次权重选参与留出检查 |
| 待人审材料 | [`rag-eval-review.py`](../scripts/rag-eval-review.py) | 已导出，不生成批准记录 |

Java 实现目录为 [`src/main/java/com/yizhaoqi/smartpai/rag/eval`](../src/main/java/com/yizhaoqi/smartpai/rag/eval)。现有 Java Runner、Trace 和人工复核流程继续保留；目前没有引入 EvalScope、MTEB 或 RAGAS 作为运行依赖。

## 3. 题集、来源与标注语义

### 3.1 当前资产

| 目录 | 题数与构成 | 语料 | 当前用途/状态 |
|---|---|---|---|
| [`phase1`](phase1/dataset.json) | 50：fact12、para12、multi8、conflict3、cross-doc5、无答案6、ACL4 | 7份合成资料 | 原manifest不回写；zyh已批准，真实运行完成，安全检查PASS |
| [`hotpotqa50`](hotpotqa50/dataset.json) | 50：bridge25、comparison25 | 500份候选段落文档；首次实测1022子块 | 快速回归与诊断集；已多次观察，不再称独立留出 |
| [`study200/development`](hotpotqa-study200/development/dataset.json) | 100：bridge50、comparison50 | 991份候选文档 | 权重开发集；已使用 |
| [`study200/holdout`](hotpotqa-study200/holdout/dataset.json) | 100：bridge50、comparison50 | 989份候选文档 | 已完成一次锁定权重候选对照，已开放 |
| [新留出100](hotpotqa-holdout100-20261027/dataset/dataset.json) | 100：bridge50、comparison50 | 958份完整候选文档 | 预登记完整主链路两轮已完成，PASS；现已开放 |

公开集来源为官方 HF HotpotQA validation/distractor 的7405条记录：revision `1908d6afbbead072334abe2965f91bd2709910ab`，文件 `distractor/validation-00000-of-00001.parquet`，原输入 SHA-256 `c20b638ca82b21d04fe12e14ff417ad05153d4d215a65de54497fca4e972f7c6`。来源署名和许可保存在各套件的 `SOURCE.md`，不因文档整合而修改。

旧50题种子为20261026，分层按 `SHA-256(seed:id)` 选取。新增200题种子为20261027，排除旧50题 source ID 和规范化问题，新两组之间同样去重。问题规范化为 NFKC、casefold、仅保留字母数字；未声称做过语义近似去重。两组间仍有5份相同文档，不声称实体或文档完全隔离。

各组将选中题目的**全部候选段落去重合并**为共享语料，保留干扰段落；不只索引金证据，也不按当前题的参考答案筛选上下文。此设置叫 `shared-candidate-subset`，既非官方每题10段 distractor，也非 fullwiki，不能直接对照官方榜单。开发集和留出集语料不同，绝对分数不能跨组相减解释为增益。

### 3.2 版本化文件

- `dataset.json`：schema、题集/语料版本、来源 revision、校验和、抽样清单、文档路径和文件指纹。
- `cases.jsonl`：唯一题目标签；公开集保留 source ID、`referenceAnswers[]` 与原始支持句关系。
- `source.json`：选中来源记录快照；不能只改 schemaVersion 将企业草稿变成公开标签。
- `documents/`：全部候选原文，同名异文保留为不同文件；答案标签不附加到正文。
- `SOURCE.md`：来源、许可与转换说明。
- study 另有 `study.json`、`study.sha256`、选择锁和 `holdout-opened.json`，用于绑定协议、数据和使用顺序。

不同必要证据单元是 **AND**；同一单元的等价位置是 **OR**；短答案别名通常也是 **OR**，不能当作多个必须同时答出的要点。无答案/ACL 负例没有必要证据单元，不对空集合算满分。ACL 的 `forbiddenDocuments` 核验不可见前提，`forbiddenQuotes` 只作精确泄漏线索，不发给 Judge。

### 3.3 已支持与未支持的公开格式

| 输入 | 当前能力 | 明确边界 |
|---|---|---|
| HotpotQA 原始 JSON / 官方 HF Parquet | 来源快照、完整候选导入、支持句定位、多答案、原生 EM/F1 | Parquet 需可选 `pyarrow`；不支持全量 Wikipedia |
| UltraRAG 基础文本 QA JSON/JSONL | `id/question/golden_answers/meta_data` 与语料 `id/contents` 导入；别名保留 | 不猜 metadata 的证据语义；缺支持标签时 Coverage 为 N/A；原生 scorer 为 unavailable |
| 选择题、图片/多模态 | 顶层 choices/image 会明确拒绝 | 不丢弃字段后冒充普通文本 QA |
| BEIR/qrels、中文专用格式 | 尚未实现 | 缺少纯检索模式、标准排名指标及中文官方 scorer |

UltraRAG 给出了通用 QA 与语料包装，但接入 yeRAG 仍需保留每个数据集自身的语义和评分器，不能宣称“通用 JSON 能导入”就已适配全部任务。[官方数据格式](https://ultrarag.openbmb.cn/pages/cn/develop_guide/dataset)

## 4. 指标口径与可解释边界

### 4.1 确定性证据指标

在**重排后、合并和组装前**冻结的 Top5 上，必要单元数为 N，完整覆盖数为 M：

| 指标 | 单题定义 | 分母与边界 |
|---|---|---|
| Hit@5 | M>0记1，否则0 | 有必要证据且验证有效的题，表示至少命中一项 |
| Coverage@5 | M/N | 必要证据覆盖比例；不是穷举相关集 Recall |
| CompleteHit@5 | M=N记1，否则0 | 表示全部必要单元已覆盖 |
| 覆盖状态 | NONE / PARTIAL / FULL | 负例为 N/A，不将无标注伪装成无答案 |

例如3个必要单元覆盖2个：Hit=1、Coverage=2/3、CompleteHit=0、PARTIAL。汇总采用有效题逐题均值，保留有效分母和排除原因。

Top5 保存 owner、fileMd5、chunkId、ES index/hit ID、正文及 SHA-256、分数类型/值与名次。RRF 与 rerank 分数不混用；后续裁剪不能覆盖原 Top5 快照。

### 4.2 阶段损失定位

同一必要单元分别检查 `retrievalPool → top5 → merged → assembled → wire`。只有原文与来源映射核验通过、正文确实出现在已发送的模型请求中，才确认入模。块 ID 相同不代表关键文字仍然存在。

- 池 FULL、Top5 PARTIAL：排序或交付窗口损失。
- Top5 FULL、组装后 PARTIAL：进一步核对 TopK 窗口、Token 预算或 legacy 裁剪，分别记录原因，不一律归咎预算。
- 坏工件、权限变化、不完整扫描、原文映射不成立：记数据/工件不可用，不伪装成检索 miss。
- Merge 可能处理整个候选池，其阶段 FULL 不等于最终全部送入模型；同一缺失单元跨阶段出现也不重复算作多次独立丢失。

匹配使用 NFKC、Unicode 去空白与回原 UTF-16 范围映射。公开支持句跨真实子块时，可建立 `CONTIGUOUS_SOURCE_SPAN`：每个子块独立匹配同一已校验原文的唯一位置，只有实际返回/保留片段共同覆盖整条支持句的全部原文范围才算完整。缺片段、归属不一致、位置歧义或裁剪均不成立；不拼接子块伪造“某单块含完整原文”。企业 v2 原规则不因该扩展改变。

### 4.3 答案、Judge 与人工结论分开

| 输出 | 当前规则 | 不代表什么 |
|---|---|---|
| HotpotQA `nativeAnswer` | 官方规范化和词频F1、yes/no/noanswer规则；备选答案取最高分 | 不评价全部公开数据集，也不等于证据使用正确 |
| `publicBenchmark` | 来源映射标签独立计分，全部所选题进入答案分母，缺失/未完成计零并另报技术状态 | `PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED` 不是人工批准 |
| 企业正式指标 | 需真实、绑定题集哈希的 LABELS/APPROVED | 未批准时有效分母为0，不称为正确率0% |
| 正确性 Judge | 独立请求，完整回答、实际上下文、参考答案/要点；correct/partial/wrong及理由 | 不改金标、程序定位、原生 EM/F1；失败不填 wrong |
| 独立支持度 Judge | 可选 `yerag-claim-support-v1`；仅看真实上下文、问题和回答，不接收金答案 | 自定义 `judgeClaimSupportRate`，不是 RAGAS Faithfulness，尚未校准 |
| 回答完整性 | 仅有真实 keyPoints 时计算 | 别名和支持句不冒充必答要点；缺标注时 N/A |
| `humanReview` | 人实际复核后的追加记录 | 工作表、Judge 和程序不能自动创建 APPROVED |

公开短答案在生成前固定 short-answer 提示，保留完整真实回答直接评分；不事后用另一个模型抽取答案或补别名抬分。英文 HotpotQA 规则不套给中文、长答案或选择题。

支持度评审检查陈述提取完成度、回答引用和上下文原文引用；无陈述、提取不完整或评审失败为 N/A。程序能确认引用存在，不能因此证明语义推断成立。旧样本已发现 Judge 用“未提及属性”推导否定等争议，须保留人工校准入口。

### 4.4 延迟、用量和技术健康

记录 Pipeline/首段回答延迟 mean/P50/P95、阶段耗时、估算上下文 Token，以及生成/正确性Judge/支持度Judge各自实报 Token。P95 使用 nearest-rank；Pipeline 延迟不含离线 Judge。估算与实报分开，缺失不补零；当前汇总不含 Embedding/Rerank 账单，不换算金额。顺序运行不是并发压测，也未严格控制冷暖缓存。

同时报告生成完成、Judge 失败、各召回路由健康及 RERANK 的 SUCCEEDED/DEGRADED/SKIPPED。开启开关不等于调用成功；实际 TopK 分数类型与重排候选工件也要核验。

## 5. 实验、冻结和门禁规则

### 5.1 可比性

正式对照绑定题集/语料/权限指纹、逐题 ID、代码版本、生成模型和参数、预算、TopK、候选深度、Pipeline配置及 Judge 配置。允许的处理变量必须显式声明：`retrieval.bm25Weight`、`retrieval.knnWeight`、`options.rrfK` 或 `options.rerank`；其他差异拒绝比较。主动比较代码变更才使用 `--allow-code-change`，不因此跳过其他控制检查。

主链路对照中记录重排模型、topN、截断、超时与服务地址指纹，不存 URL 或凭据。`rerankRequest` 保留实际 query/候选，`RERANK` 保留排序结果，TopK 标注 `scoreKind=RERANK`；失败保留降级与异常类别。

冻结温度0和配置不能保证在线模型或近似检索完全确定，重复运行须报告实际变化。离线重算则要求冻结工件导出一致。这是两种验证，不混称“保证每次得到同一答案”。上传、重索引、全库扩充可能改变物理 ID、统计或排序，跨轮不能只凭题集名称认定语料相同。

### 5.2 开发、选择与留出

现有 `study` 工作流是**关闭改写/重排的 RRF 权重实验**：开发集冻结两路候选，用生产 `RankFusion` 离线重放网格，不为每个参数重新生成答案。协议和网格在运行前冻结，选择结果记录开发工件、代码、协议哈希和时间。

`holdout-opened.json` 在首次留出运行前写入；之后禁止同一套件再选参。`validate --resume` 只复用相同选择、相同输出位置且完整、哈希验证通过的运行，不覆盖部分回答。本地流程保护不能阻止有文件写权限的人改文件，不作为对抗性安全隔离。

**现有权重选择流程不是完整重排验收协议。** 新的 RRF + rerank 留出验证应另建预登记协议和数据排除清单；不要直接套旧 `study tune`，也不要把已开放的100题重新称为未见留出。

### 5.3 门禁与进程状态

当前 [`no-quality-drop.json`](policies/no-quality-drop.json) 仅规定 CompleteHit@5、EM 的最大允许下降都为0；它是开发检查，未设未经校准的延迟/Token阈值，不是生产上线标准。

- 无质量策略：`NOT_CONFIGURED`。
- 所需指标缺失、生成不完整等：不能报完整 PASS，按检查为 `INCONCLUSIVE`。
- 超过下降预算：`FAIL`。
- 重排消融开启组须逐题实际成功；有跳过/降级时纯重排执行检查为 `INCONCLUSIVE`，质量退化仍可判 FAIL。
- 总体 PASS 不表示每道题均获益，也不表示统计显著。

`matrix`、`rerank` 和 `study validate` 的进程成功/COMPLETED 表示实验完成。CI 必须读取 comparison/validation 的 gateStatus，或使用 `compare`：**FAIL/INCONCLUSIVE 返回非零退出码**。不能只看调度退出0就放行。

## 6. 已完成的真实实验

以下各表只在本实验内比较，保留原始失败结果；不同表的语料/代码不完全相同，不拼接成统一排行榜。

### 6.1 首次公开50题接入（2026-10-26）

目录 `target/hotpotqa50-run-20261026-001`：50/50 READY、50次生成和正确性Judge完成；EM64%、F1 68.33%、Hit98%、Coverage82.67%。重排未配置，不能称为重排主链路成绩。后续离线重分析得到 CompleteHit66%；旧指标与答案不变。原始证据见 [`hotpotqa50/verification-status.json`](hotpotqa50/verification-status.json)。

阶段重分析：候选池50题 FULL，Top5/入模33题 FULL、16题 PARTIAL、1题 NONE；22个缺失单元位于窗口损失，并非发现了22次 Token 预算不足。相邻扩展只可能触及其中少量单元，因此没有盲目增加预算或加入相邻块扩展。

### 6.2 召回消融：BM25 / Vector / RRF

目录 `target/hotpotqa50-matrix-20261027-002`，顺序 BM25→Vector→Hybrid，再反向；各行50题，均关闭改写与重排、开启独立支持度Judge。

| 策略/轮次 | Hit@5 | Coverage@5 | CompleteHit@5 | EM | F1 |
|---|---:|---:|---:|---:|---:|
| BM25-01 | 94% | 70.50% | 46% | 58% | 61.00% |
| Vector-01 | 100% | 87.17% | 74% | 66% | 70.14% |
| Hybrid/RRF-01 | 98% | 82.67% | 66% | 66% | 68.90% |
| Hybrid/RRF-02 | 98% | 82.67% | 66% | 64% | 67.00% |
| Vector-02 | 100% | 87.17% | 74% | 66% | 70.14% |
| BM25-02 | 94% | 70.50% | 46% | 58% | 61.00% |

共300次生成、300次正确性Judge、300次支持度评审完成。RRF相对BM25的检查两轮 PASS，相对Vector两轮 FAIL；缺失必要单元仍在RRF池中，典型名次6–12。结论限于这个关闭重排的子集，不能推出 Vector 在完整主链路或企业中文场景普遍占优。

两轮RRF出现同上下文回答和EM波动，保留原始结果，不选择较高一轮当唯一成绩。机器证据见 [`verification-2026-10-27.json`](experiments/verification-2026-10-27.json)。

### 6.3 新增200题：权重开发与留出

目录 `target/hotpotqa-study200-experiment-20261027-001`。开发100题先运行一次原参数，再重放固定候选。选择规则预先确定：优先 CompleteHit，其次Coverage/Hit；并列优先原参数，再选变化较小者。

| BM25权重（Vector=1、k=60） | 开发Hit@5 | 开发Coverage@5 | 开发CompleteHit@5 |
|---|---:|---:|---:|
| 0.25 | 99% | 84.32% | 67% |
| **0.5，按规则选中** | 97% | 83.49% | **68%** |
| 1，原参数 | 96% | 79.49% | 62% |
| 2 | 92% | 75.49% | 57% |

原参数100个Top5均被生产融合算法精确重放。开发候选没有重新生成答案，不能为其推算 EM/F1。锁定0.5后，只在同一留出100题、相同语料内做对照：

| 留出方案 | Hit@5 | Coverage@5 | CompleteHit@5 | EM | F1 |
|---|---:|---:|---:|---:|---:|
| BM25=1，原参数 | 97% | 88.92% | 80% | 64% | 76.56% |
| BM25=0.5，锁定候选 | 97% | 89.08% | 80% | 62% | 74.29% |

完整证据改善1题、退化1题、持平98题；EM改善0题、退化2题，最终 **FAIL，不采纳**。单对运行不足以指定两题退化的唯一因果原因，也不根据已开放留出集再选其他权重。

共300次生成和正确性Judge完成，未开启独立支持度Judge。来源跨块映射修复后开发/留出均100/100 READY；旧50题冻结报告用新跨度代码重算仍逐字节一致。机器证据见 [`verification-study200-2026-10-27.json`](experiments/verification-study200-2026-10-27.json)。

### 6.4 主链路补测：RRF 与 RRF + rerank

修复后正式目录 `target/hotpotqa50-rerank-20261027-002`。仅切换重排，其他控制项相同；顺序 RRF→重排→重排→RRF。重排模型为现有账号可调用的 `gte-rerank-v2`，不是旧 `gte-rerank` 成绩；没有按本批分数择优挑模型。

| 方案 | Hit@5 | Coverage@5 | CompleteHit@5 | EM | F1 |
|---|---:|---:|---:|---:|---:|
| RRF-01 | 98% | 79.33% | 60% | 66% | 69.00% |
| RRF + rerank-01 | 100% | 97.17% | 94% | 72% | 77.38% |
| RRF + rerank-02 | 100% | 97.17% | 94% | 72% | 77.38% |
| RRF-02 | 98% | 79.33% | 60% | 66% | 69.00% |

两轮各50对的完整、有序 RRF 输入池逐题一致，所有运行的题集、语料、权限、生成模型与代码指纹一致。100次重排全部 SUCCEEDED，0跳过/降级；每题50个不同候选，实际Top5均来自窗口并使用重排分数。

两轮配对结果相同：CompleteHit增加34个百分点（17改善/0退化/33持平），EM增加6个百分点（4改善/1退化/45持平），F1增加8.38个百分点（6改善/2退化/42持平）。开发质量检查与重排执行检查均 PASS。仍有单题答案退化，且此50题已见，不能用总体增益替代新留出验证。

| 运行 | Pipeline P50/P95（ms） | 生成实报Token |
|---|---:|---:|
| RRF-01 | 911 / 1464 | 33181 |
| RRF + rerank-01 | 1081 / 1507 | 34022 |
| RRF + rerank-02 | 1136 / 1841 | 34035 |
| RRF-02 | 1002 / 1595 | 33166 |

每行50个延迟观测与50次生成用量，不含离线Judge时延或重排账单。没有用这组顺序测量设定生产时延门槛。

本轮重新运行控制组，没有复用旧矩阵的66%完整证据成绩：扩充study200索引后语料指纹已变，在新旧共同观察的1020个逻辑块中有4个ES ID变化、正文哈希未变。新旧条件不能直接拼接比较。

200条原生答案评分与固定 SHA 官方函数逐条一致；四份报告的results/summary/外部输入/sidecar、两份配对报告的JSON/Markdown均离线重算逐字节一致。机器证据见 [`verification-rerank-2026-10-27.json`](experiments/verification-rerank-2026-10-27.json)。

### 6.5 全新100题：冻结完整主链路验证

目录 `target/hotpotqa-holdout100-20261027-001`。排除旧250题source ID和规范化问题，bridge/comparison各50；958份候选资料全部导入，100/100 READY。只有重排开关不同，不在本轮选择或调整参数。

| 方案 | Hit@5 | Coverage@5 | CompleteHit@5 | EM | F1 |
|---|---:|---:|---:|---:|---:|
| 新100题RRF-01 | 98% | 84.42% | 67% | 63% | 76.29% |
| 新100题RRF＋rerank-01 | 100% | 95.25% | 89% | 70% | 82.81% |
| 新100题RRF＋rerank-02 | 100% | 95.25% | 89% | 71% | 83.81% |
| 新100题RRF-02 | 98% | 84.42% | 67% | 62% | 74.94% |

两轮分别100对，有序RRF输入池均100/100逐题一致；200次重排全部SUCCEEDED。完整证据每轮改善22/退化0/持平78；EM首轮9/2/89，次轮10/1/89；F1首轮12/3/85，次轮14/2/84。两个质量检查均PASS，没有因为某道答案退化删题或重选配置。

| 运行 | Pipeline P50/P95（ms） | 生成实报Token |
|---|---:|---:|
| 新100题RRF-01 | 942 / 1297 | 68432 |
| 新100题RRF＋rerank-01 | 1097 / 1465 | 70807 |
| 新100题RRF＋rerank-02 | 1150 / 1456 | 70860 |
| 新100题RRF-02 | 947 / 1457 | 68438 |

共400次生成与正确性Judge，属于100道不同题的重复观测，不是400道题。400条EM/F1与固定官方函数逐条一致；四份报告各四个核心文件、两份比较JSON/Markdown离线重算一致。全局索引前后保持docs=6960、indexTotal=5938，未在对照期间写入语料；原始有序候选一致也单独核验。用量/时延边界同前，不含重排账单，不宣称显著性或生产SLO。

首次调度在四组完成后，后验校验将JSON的list与内存tuple直接比较，误报Judge配置变化。最小测试复现后改用规范JSON比较，保留原producer源码、原protocol/SHA及追加的validator-amendment；只在已完整记录的运行上重新验证，未改变Java、模型、数据、参数或门禁，未重跑模型。其他模型/配置差异仍拒绝。证据见[新100题机器验证](experiments/verification-holdout100-2026-10-27.json)和[校验器修正记录](hotpotqa-holdout100-20261027/validator-amendment.json)。

### 6.6 企业50题：真实人工标签与双身份运行

目录 `target/phase1-enterprise-live-20261027-001`；人工标签记录由zyh在本轮明确确认，绑定原题集哈希。评测USER id:2不属于EVAL_RESTRICTED；准备USER id:3拥有该组织，受限文件真实存在。四个认证检索对照均为评测用户不返回受限文件、准备用户能返回受限文件，不以“资料未入库”制造安全通过。

修复Runner后所有题显式限定manifest的7份资料，并继续与原ACL取交集；实际可见6份公开资料。50/50预检和生成完成，50次重排均成功，0执行/Judge异常。原始report保留，再用真实人审输入生成独立reviewed-report：

| 分组 | 题数 | Judge正确 | Judge部分完整 | Judge错误 |
|---|---:|---:|---:|---:|
| fact | 12 | 12 | 0 | 0 |
| para | 12 | 12 | 0 | 0 |
| multi | 8 | 8 | 0 | 0 |
| conflict | 3 | 2 | 1 | 0 |
| cross-doc | 5 | 4 | 1 | 0 |
| 无答案 | 6 | 6 | 0 | 0 |
| ACL | 4 | 4 | 0 | 0 |
| 合计 | 50 | 48 | 2 | 0 |

正式检索分母40，Top5与wire均40/40 FULL，Hit/Coverage/CompleteHit均100%；正式答案分母50，Judge正确48、部分完整2。50题标签有真实人工批准，**生成答案没有被伪造成人工逐题批准**。这是模型意见，未配置额外的生产答案质量阈值。

逐层核对890条候选来源记录，均在6份授权公开文件内；4道ACL输出均拒答，所有50题上下文和回答未出现4个受限合成标识。结合4个双身份正负检索对照，安全检查PASS、0阻断项。范围仅覆盖本套合成资料、两个身份及本轮场景，不外推所有组织/身份变更或生产流量。

Pipeline P50/P95为1221/2083ms，生成实报32141 Token（50次），不含离线Judge时延及Embedding/Rerank账单。

两题部分完整保留原分数：`conflict-02`已正确给出300/500不一致，但缺少标签要求的“无优先级规则”；`cross-doc-03`已正确回答SAML和恢复目标相同，但未列标签要求的RTO4小时/RPO30分钟。两题必要证据均FULL，不能直接称为检索失败；有些要点超出问题直接比较的措辞，若未来调整完整性标准需另发标签版本，不能在本轮自动升分。见[两题技术复核](experiments/enterprise-partial-review-2026-10-27.json)。

复核队列7条是2题partial加5题固定通过抽查，不是7个失败；本轮Judge未设置强制复核标志，正式答案分母仍为50。证据见[企业50机器验证](experiments/verification-enterprise50-2026-10-27.json)、[真实人工标签记录](reviews/phase1-labels.jsonl)、[双身份对照](experiments/enterprise-acl-controls-2026-10-27.json)。

## 7. 已修复问题与保留的诊断

| 问题 | 核实与修复 | 回归及保留证据 |
|---|---|---|
| 重排成功后仍回退RRF | `RerankStage.publish` 中基本类型double与Double混用，窗口外null分数自动拆箱引发空指针；改为保留boxed null，并记录降级异常类名 | 2候选/1重排窗口最小用例修复前 DEGRADED、修复后 SUCCEEDED；真实100次重排成功。见 [`RerankStage`](../src/main/java/com/yizhaoqi/smartpai/rag/stage/RerankStage.java)、`RagPipelineTest` |
| CLI报告已完成但JVM不退出 | ES transport未由Spring管理导致非守护线程残留；注册transport并在销毁时close | `EsConfigLifecycleTest` 修复前失败、修复后通过；真实50题check自行退出0 |
| 支持句跨真实512子块被判预检无效 | 增加同一原文连续范围的显式映射和覆盖验证 | 开发2个、留出1个必要单元使用该映射，200题均READY；缺片段/错归属/裁剪不能算完整 |
| 留出调度时间解析失败 | Python3.10兼容Java纳秒时间戳；相同选择支持resume，仅复用完整验证运行 | 保留原baseline100题，无重复生成；候选仍为原锁定配置 |
| 复核材料把措辞变化算成分数变化 | 比较真实EM/F1而非整个prediction对象 | 使用复核包002；001有SUPERSEDED标识 |
| 企业v2题集未限制声明语料 | 统一通过可信Request.forEvaluation约束manifest文件，保持v2完整回答与原ACL | 新增50题请求范围回归，修复前失败、修复后通过，完整258项测试通过 |
| Maven编译与真实评测并发造成实体扫描失败 | 先构建/测试，再固定classes顺序运行 | 仅为启动失败诊断，未计入模型质量；今后不并发改写构建产物 |

重排首次尝试目录 `target/hotpotqa50-rerank-20261027-001` 标记 `ABORTED_DIAGNOSTIC`：关闭组50题，开启组已捕获47题全部DEGRADED，**不纳入成功重排成绩**。独立真实50候选探测返回50条合法分数，结合无网络最小用例定位内部缺陷。旧 `gte-rerank` 探测为403/AccessDenied，不能仅凭该响应断言具体账户原因；官方资料另已将旧模型列为停用。私有评测配置改用v2，仓库生产默认模型尚未替换。[官方模型说明](https://help.aliyun.com/en/model-studio/rerank)

先前矩阵001的部分完整结果、启动失败与旧资源泄漏日志继续保留，不混进正式矩阵002。模拟数据中的 `SIMULATED_TEST_ANSWER` 只验证程序流程，不是模型质量成绩。

## 8. 环境与可复现运行

### 8.1 本机环境事实

专用 Docker 项目为 `yerag-public-eval`，正常后端用于上传/解析，评测另起隔离CLI上下文。本次文档整理时下列端口均可TCP连接；这仅是连通性检查，不等于重跑了完整业务健康检查。

| 依赖 | 本地端口 |
|---|---:|
| MySQL | 33306 |
| Redis | 16379 |
| Elasticsearch | 19200 |
| MinIO | 19000 |
| Kafka | 19092 |
| 上传/解析后端 | 18081 |

私有Compose、配置、登录资料位于忽略目录 `target/public-eval-runtime/`；不将凭据、`.env`、auth文件或真实认证头写进本文/源码。仓库没有可供新机器直接使用的生产 `application.yml` 或源码Compose，不能只凭本文路径宣称已可全新一键部署。

实测 Embedding 为 `text-embedding-v4`、2048维；生成为 `deepseek-chat`；chunk-size=512，专用ES使用standard analyzer，未修改生产映射。生成temperature=0、top_p=0.9、max_tokens=2000；主对照关闭改写、merge和legacyClipping，RRF k=60/权重1:1、每路150候选、route-timeout=2000ms、TopK=5、evidence/context/safety tokens=4000/32000/512。重排v2、topN=50、maxDocChars=1000、timeout=1500ms。以实际工件为准，模型名不等于供应商不可变服务版本。

CLI隔离设置只作用于自身：随机回环HTTP端口、禁用DDL/SQL初始化、Kafka消费、MCP、定时任务及账号/组织/ES初始化器和旧Memory迁移；完成后关闭上下文。它不会代替正常后端解析上传语料，也不证明既有后端进程已重启加载新代码或重排配置。

### 8.2 文件验证、导入与上传

从仓库根目录运行。JSON/JSONL导入只需Python标准库；Parquet另外需要pyarrow。以下路径/身份是占位符；凭据由部署安全注入，脚本不会 `source .env`。

```bash
scripts/rag-eval.sh validate evaluation/phase1
scripts/rag-eval.sh validate evaluation/hotpotqa50

scripts/rag-eval.sh import hotpotqa /path/to/hotpot.json /tmp/hotpot-new \
  --count 50 --seed 20261026 --source-url '<固定来源URL>' --revision '<固定版本>'

scripts/rag-eval.sh import ultrarag /path/to/questions.jsonl /tmp/public-qa \
  --corpus /path/to/corpus.jsonl --dataset-name nq --language en \
  --source-url '<固定来源URL>' --revision '<固定版本>'

# 先在当前环境安全注入YERAG_EVAL_TOKEN；受限资料另需YERAG_EVAL_SETUP_TOKEN。
scripts/rag-eval.sh upload evaluation/hotpotqa50 http://localhost:18081
```

上传走既有chunk/merge、MinIO、Kafka和解析链路，不直接写ES。企业受限文件由准备身份上传到 `EVAL_RESTRICTED`，评测身份不能属于该组织；脚本不自动创建账号、组织和索引。上传成功不等于索引完成。

### 8.3 构建、预检和单次执行

```bash
mvn -q test
mvn -q -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/rag-eval-classpath.txt

export RAG_EVAL_SKIP_BUILD=1
export AI_RAG_EVAL_IDENTITY='id:<实际评测用户ID>'
# 企业ACL预检另设AI_RAG_EVAL_SETUP_IDENTITY='id:<实际受限准备用户ID>'。

scripts/rag-eval.sh check evaluation/hotpotqa50 /tmp/hotpot-check-001 \
  --spring.config.additional-location=file:/path/to/evaluation.properties
scripts/rag-eval.sh run evaluation/hotpotqa50 /tmp/hotpot-run-001 \
  --spring.config.additional-location=file:/path/to/evaluation.properties
```

默认脚本会先构建；设置SKIP_BUILD前应完成构建、测试，随后整个可比实验期间保持源码和classes固定，不能并发Maven编译。

预检使用同一授权路径扫描指定文件，单批最多100文件/10000块，整体最多1000文件/100000块；v3覆盖全部候选文件。超时、分片失败、越权或跨批权限变化拒绝接受。正例必须映射到可见原文；ACL题需准备身份确认受限资料存在，再检查评测身份不可见。无答案不能由“搜索没找到”代替人工在固定语料中的确认。

输出目录必须新建/为空。`run` 前后校验语料与权限；v3结束快照确认前partial保持corpusVerified=false。中断/失败/变化记录诊断，单题失败不丢掉其他题。前后相同不能排除中途发生又恢复的变化，因此仍需冻结环境。

### 8.4 消融、重排与CI检查

私有配置须显式提供可用的 `ai.retrieval.rerank.url/model` 和凭据；API Key可按现有配置复用Embedding Key，不把值放到命令参数中。

```bash
# 召回消融；Hybrid=RRF，统一关闭改写/重排。
scripts/rag-eval.sh matrix evaluation/hotpotqa50 /tmp/hotpot-matrix-001 \
  --repeats 2 --support --policy evaluation/policies/no-quality-drop.json \
  --spring-arg=--spring.config.additional-location=file:/path/to/evaluation.properties

# 重排主链路对照；固定关闭改写，只切换重排。
scripts/rag-eval.sh rerank evaluation/hotpotqa50 /tmp/hotpot-rerank-001 \
  --repeats 2 --policy evaluation/policies/no-quality-drop.json \
  --spring-arg=--spring.config.additional-location=file:/path/to/evaluation.properties

scripts/rag-eval.sh compare /tmp/hotpot-rerank-001/rrf-01 \
  /tmp/hotpot-rerank-001/rrf-rerank-01 /tmp/hotpot-gate-001 \
  --policy evaluation/policies/no-quality-drop.json --allow-config-change options.rerank
```

`--support` 额外调用自定义支持度Judge；主链路本批对照未开启。`run` 默认沿用配置，`matrix`显式关闭改写/重排，`rerank`始终HYBRID并关闭改写，不能混淆这些入口。

`matrix/rerank --resume` 仅继续同种未完成实验、复用完整哈希校验运行，部分目录不覆盖。遇到部分回答保留诊断，另开目录；不要删掉失败题伪造完整结果。

### 8.5 已有权重study的复现方式

以下是已完成权重实验的工作流说明，不表示可以重新在已开放套件上选参；重做必须使用新输出和新的未见留出。

```bash
scripts/rag-eval.sh study prepare /path/to/hotpot.json /tmp/new-study \
  --exclude-dataset evaluation/hotpotqa50 \
  --development-count 100 --holdout-count 100 \
  --source-url '<固定来源URL>' --revision '<固定版本>'

# 两个子套件先上传并各自check到全部READY，再固定构建。
scripts/rag-eval.sh run /tmp/new-study/development /tmp/development-run-001 \
  --spring.config.additional-location=file:/path/to/evaluation.properties \
  --ai.rag.eval.baseline=true --ai.rag.eval.recall-strategy=HYBRID \
  --ai.retrieval.bm25-weight=1 --ai.retrieval.knn-weight=1 \
  --ai.retrieval.rrf-rank-constant=60
scripts/rag-eval.sh study tune /tmp/new-study /tmp/development-run-001 /tmp/selection-001
scripts/rag-eval.sh study validate /tmp/new-study /tmp/selection-001/selection.json \
  /tmp/holdout-run-001 \
  --spring-arg=--spring.config.additional-location=file:/path/to/evaluation.properties
```

prepare允许重复传入 `--exclude-dataset`；下一批必须排除现有旧50、development100、holdout100三个套件，不能照搬示例只排除旧50。当前每个split限制偶数2–100题；不能默默放大语料扫描上限。`replay RUN GRID_JSON NEW_OUTPUT` 是生产融合离线重放入口，只适用于满足绑定和路由健康条件的RRF baseline，不是重排模型评分回放。

## 9. 工件、人审与证据保存

### 9.1 每次运行保留

```text
preflight.json              # 原文定位、权限/语料指纹和预检状态
capture.json                # 代码/题集/配置与逐题运行索引
capture.partial.json        # 中断前检查点，不能冒充完整capture
artifacts/<UUID>.json       # question/pipeline/model/judge原始工件
dataset/                    # 实际题集、来源和资料副本
report/results.jsonl        # deterministic/nativeAnswer/semantic/humanReview
report/summary.json         # 分母、技术失败、指标及测量
report/report.md            # 自动生成的冻结结果，不是另一本维护文档
report/review-queue.jsonl    # 争议/失败及固定抽查
report/ragas.jsonl          # user_input/response/reference/retrieved_contexts
report/ragas-index.jsonl    # ID、来源、备选答案与跳过原因
report/provenance.json      # 题集、原始工件、输入/输出SHA-256
```

`ragas.jsonl` 仅导出已完成、上下文核验通过的样本，是外部交叉评分入口，**不代表已运行RAGAS**。导出缺失不等于把失败题从原生答案总分母删掉。

离线重算不访问ES/模型；使用已构建产物、新目录：

```bash
RAG_EVAL_SKIP_BUILD=1 scripts/rag-eval.sh report /tmp/hotpot-run-001/dataset \
  /tmp/hotpot-run-001/preflight.json /tmp/hotpot-run-001/capture.json \
  /tmp/hotpot-report-replay-001

scripts/rag-eval.sh review /tmp/run-1 /tmp/run-2 --output /tmp/answer-review-001
scripts/rag-eval.sh review --dataset evaluation/phase1 --output /tmp/labels-review-001
```

### 9.2 人审与标签修订

已有公开复核包 `target/hotpotqa50-review-pack-20261027-002` 将六组300条观测合为50张卡；企业包 `target/phase1-label-review-pack-20261027-001` 含标签工作表与原7份资料。历史工作表条目保留 **PENDING_HUMAN_REVIEW**，不能直接作为批准文件提交report。本轮收到zyh的整套人工批准后，另追加了 `reviews/phase1-labels.jsonl`；原工作表不回写，公开50题争议标签也未因此获批。

公开复核线索包括同上下文的措辞/分数变化、严格EM与语义判断不一致、参考答案与支持度分歧等；类别有交集，不能相加当成独立问题数。不能由AI替人填姓名或批准，也不能补别名、删题来抬分。

实际人审日志的 `LABELS` 记录绑定 `datasetHash/caseId`，填写真实reviewer、reason、createdAt及APPROVED/DISPUTED；整套可用caseId=`*`，单题记录优先。更新追加新记录并引用previousReviewId，不覆盖历史。

`ANSWER` 修订另绑定 `pipelineRunId/answerSha256`，记录correct/partial/wrong/disputed；不自动批准标签，不改写原 `semantic.assessment`。报告可在最后追加真实人审JSONL参数。

修订金标必须新题集版本、同语料重新预检并输出新报告；旧Judge标为 `LABEL_VERSION_CHANGED_REJUDGE_REQUIRED`，旧人工批准不沿用。`convert LEGACY_JSON_OR_JSONL OUTPUT_JSONL` 只生成待补标模板，不导入历史Judge分数或批准。旧6题和300/600/full报告仅做过文件核对，未把历史分数接到当前标准。

### 9.3 证据索引与持久性

| 证据 | 位置 | 定位 |
|---|---|---|
| 新100题主链路验证 | [`verification-holdout100-2026-10-27.json`](experiments/verification-holdout100-2026-10-27.json) | 冻结两轮配对，质量与执行检查均PASS |
| 企业50题真实验证 | [`verification-enterprise50-2026-10-27.json`](experiments/verification-enterprise50-2026-10-27.json) | 真实标签审核，48正确/2部分完整，安全PASS |
| 原50题主链路诊断 | [`verification-rerank-2026-10-27.json`](experiments/verification-rerank-2026-10-27.json) | 已见题诊断，VERIFIED |
| 召回消融验证 | [`verification-2026-10-27.json`](experiments/verification-2026-10-27.json) | 历史关闭重排条件，VERIFIED |
| 权重开发/留出验证 | [`verification-study200-2026-10-27.json`](experiments/verification-study200-2026-10-27.json) | 流程VERIFIED、候选质量FAIL |
| 首次公开接入 | [`hotpotqa50/verification-status.json`](hotpotqa50/verification-status.json) | 2026-10-26历史快照 |
| 企业模拟初验 | [`verification-status.json`](verification-status.json) | 历史程序验证，不是当前真实企业质量 |
| 原始主链路工件 | `target/hotpotqa50-rerank-20261027-002`及同名`-offline-replay` | 本机可核对的完整结果 |
| 原始矩阵/留出工件 | 上述实验各自的target目录及同名离线重算目录 | 不覆盖、不混合 |

官方HotpotQA函数固定SHA为 `d35fc91a6db21d791dbdda11daf3856e9359f5701d54e3eefba20d88fecc02c0`；原主链路四组200条、旧矩阵300条、study300条和新100题四组400条分别验证过，1200条重复观测来自350道不同题。[官方评分器](https://github.com/hotpotqa/hotpot/blob/master/hotpot_evaluate_v1.py)

最终完整归档为 `/home/zyh/yerag-eval-archive/2026-10-27-closeout-v4-complete`，共23228文件、18组原始运行及1组带真实人审输入的报告，76个核心报告文件脱离原target重算一致，见[最终恢复证据](experiments/archive-complete-2026-10-27.json)。此前v2/v3归档继续保留，v3保存新100题执行时的源码/类。私密runtime配置不与公开工件混包；这是本机持久副本，尚非异机灾备。

## 10. 尚未完成与已决定不盲目加入的内容

| 范围 | 当前状态 | 处理原则 |
|---|---|---|
| 新未见题上的完整主链路验证 | 新100题两轮完成，均PASS | 此集现已开放，未来新假设再准备未见题；不外推生产验收 |
| 真实企业ACL/拒答/冲突 | 本套50题完成，安全检查PASS，Judge48正确/2部分完整 | 实际角色与范围已核验，不外推所有生产权限场景，精确检测不覆盖所有潜在语义泄漏 |
| 中文数据与官方评分器 | 未实现 | 单独数据集/语言/指标，不能复用英文HotpotQA scorer |
| BEIR/qrels、纯检索与可变K | 未实现 | qrels按相关度评分，不能伪装成全部必要支持句 |
| Agent、改写和多轮Memory | 未纳入当前质量评测 | 单独场景和状态/权限断言，不能由固定QA分数替代 |
| Judge人工校准 | 待完成 | 校准前只作诊断，不把自定义支持度设为硬门禁 |
| 统计置信区间、生产SLO/成本门槛 | 未落地 | 根据独立样本和实际业务目标确定，不凭空设置百分比 |
| 全量Wiki/大规模语料 | 当前上限内不支持 | 先设计容量、快照和索引统计冻结，不能只提高常量 |
| 相邻块扩展、更大Token预算 | 未引入 | 已观察到主要损失在排序窗口；有新证据再做单变量实验 |
| 多框架接入 | 未引入 | 只有明确交叉验证缺口才考虑；保留标准输入导出即可 |

## 11. 本轮收工清单与后续顺序

以用户确认的四项为本轮范围，**4/4完成**，最终归档恢复检查通过。任务完成不等于所有回答满分：企业2题partial、新100题的逐题退化均照实保留。中文、BEIR、更多英文基准及Agent/多轮扩展保留为后续储备，不在本轮增加任务。

| 项目 | 优先级 | 当前状态 | 验收条件 |
|---|---|---|---|
| 1. 归档实验工件＋复核失败样本 | P0 | **已完成，最终76个核心文件重算一致** | 独立归档可恢复，18组原始运行＋1组人审报告，4个异常样本有可追溯技术结论 |
| 2. 新100题完整主链路 | P1核心 | **已完成，两轮PASS，17组归档恢复验证通过** | 未参与旧实验的100题、两轮RRF/重排配对，完整指标、胜负及门禁，不能依据结果重选参数 |
| 3. 企业50题人工与真实验收 | P1核心 | **已完成，安全PASS，Judge48正确/2部分完整** | 真实复核记录绑定题集哈希；实际验证ACL、拒答、跨文档、冲突；授权扩大或泄漏为阻断项 |
| 4. 面试实验报告 | 收尾 | **已完成，见第13节** | 在本文形成可口述总结、真实数据/边界/关键代码及3个可深入追问案例 |

### 11.1 已完成的持久归档与异常复核

初次归档位于 `/home/zyh/yerag-eval-archive/2026-10-27-closeout-v2`，SHA清单为 `archive.json` / `archive.sha256`，共16075个文件、13组完整运行。包含原始工件、报告、数据副本、复核包、源码、编译类及依赖JAR；私有application配置链接、环境密钥和登录资料未打包。

已在`/tmp`工作目录，仅用归档中的脚本、类和依赖恢复13组报告；无Maven、ES或模型调用，52个核心报告文件逐字节一致。恢复产物位于 `/home/zyh/yerag-eval-archive/restore-check-2026-10-27-closeout-v2`。这是本机迁出构建目录的持久副本，尚非异机灾备。

```bash
python3 scripts/rag-eval-archive.py verify /home/zyh/yerag-eval-archive/2026-10-27-closeout-v4-complete
python3 /home/zyh/yerag-eval-archive/2026-10-27-closeout-v4-complete/source/scripts/rag-eval-archive.py replay \
  /home/zyh/yerag-eval-archive/2026-10-27-closeout-v4-complete /path/to/new-recovery-output
```

[归档恢复证据](experiments/archive-closeout-2026-10-27.json)、[四题技术复核](experiments/closeout-failure-review-2026-10-27.json)均为机器证据，不另维护说明文档。技术复核不创建人工金标批准，原标签与分数未改：

| 题目（省略hotpotqa-） | 已核实技术结论 | 处理 |
|---|---|---|
| 5adddccd5542997dc7907069 | Front Row支持句已在50候选窗口，重排第7，Top5/入模缺失第二跳；Siri Remote干扰信息仍在 | 排序/多跳覆盖开发样本，不能归因Token预算 |
| 5ab2b8fe5542991669774070 | ACRISS成员关系块从RRF第3变为重排第6，缺失跨文档连接；两组EM均0 | 记录局部覆盖退化，不用总体增益掩盖 |
| 5a8ec7cc5542995a26add518 | 停刊句排第6，频率比较信息已入模，EM1但标注全覆盖仍PARTIAL | 标签最小性待人审，不自动删支持句抬分 |
| 5a7299e75542991f9a20c524 | 两条标注全入模，EM从1到0；My Dog Tulip标注/上下文缺少上映日期，无法仅凭这些证据判断先后 | 严格保留EM退化，标注支持不足待核对，不武断宣称事实错误或唯一因果 |

新100题完成后已追加归档 `/home/zyh/yerag-eval-archive/2026-10-27-closeout-v3-public100`，共22942文件、17组运行、68个核心报告重算一致，见[追加恢复证据](experiments/archive-public100-2026-10-27.json)。该包保留新100题执行时的Java源码/类与冻结producer版本，后续企业范围修复不回写这批结果。

最终v4-complete包加入企业50题、真实人工标签输入、reviewed-report和完整测试证据，共23228文件。已在`/tmp`仅使用归档类与依赖重算18组原始报告和1组人审报告，76个核心文件逐字节一致，无Maven、ES或模型调用。恢复产物为 `/home/zyh/yerag-eval-archive/restore-check-2026-10-27-closeout-v4-complete`，见[最终恢复证据](experiments/archive-complete-2026-10-27.json)。v4中的当前类用于重算报告；原执行代码版本仍分别绑定capture，旧版本实测源码/类保留在v3，不混称同一次运行。归档封存后，工作区本文只追加收工状态和证据链接，封存副本不回写。

### 11.2 新100题预登记

套件位于 `evaluation/hotpotqa-holdout100-20261027/`，含dataset、protocol.json和SHA锁；种子2026102702，bridge/comparison各50，排除既有250个来源ID及规范化问题，合并全部958份候选文档，不删除干扰文档。

新增 `scripts/rag-eval-holdout.py`：锁定原主对照的源码、模型/生成参数、RRF/重排/上下文配置、运行顺序、门禁和私有配置文件指纹；首次运行前写opened.json，只允许完全相同协议的恢复，不允许事后调参。本套件的“新”仅指未参与既往实验，不声称供应商预训练从未见过。

```bash
AI_RAG_EVAL_IDENTITY=id:1 RAG_EVAL_SKIP_BUILD=1 python3 scripts/rag-eval-holdout.py run \
  evaluation/hotpotqa-holdout100-20261027 target/hotpotqa-holdout100-20261027-001
```

冻结权重1:1、k60、v2/50候选/1500ms、TopK5和原预算，关闭改写；顺序RRF→重排→重排→RRF。结果无论通过或失败均保留，不在该套件上重选配置。本轮实际结果与验证见第6.5节。该运行已闭合，以上为历史发起命令；不要在已开放套件上重选参数或用后来修改的代码覆盖重跑。

### 11.3 企业人工审核来源

用户明确回复：**“审核完毕没问题 审核人zyh”**，对应本轮7份合成资料/50题整套审核请求。已追加[真实人工审核记录](reviews/phase1-labels.jsonl)，reviewer=zyh、LABELS/APPROVED、caseId=*，绑定原题集哈希 `d7439251f66c39398de8412758692294602496265044ab6465a31d8bf7c9f455`。

该记录仅批准事实标注，不替代真实运行、不批准任何Judge判断。已准备评测USER id:2和受限准备USER id:3，后者属于EVAL_RESTRICTED，前者不属于该组织。50/50预检与4个真实双身份检索对照均通过，见[身份记录](experiments/enterprise-identities-2026-10-27.json)和[ACL对照](experiments/enterprise-acl-controls-2026-10-27.json)。企业Runner已补齐manifest语料范围，所有题只检索7份声明资料并与原ACL取交集，不强制短答案。最小回归修复前失败、修复后与完整258项测试通过；已保留原始capture/report，另用真实人审文件输出reviewed-report；50条人审标签生效，答案Judge48正确/2部分完整，未伪造人工答案批准。

### 11.4 后续工作顺序

1. **先校准标签和Judge。** 人工复核企业2道partial、5道固定通过抽查及公开4个异常样本，分别记录标签充分性与答案质量；当前技术复核不冒充人工批准。若完整性标准变更，发新标签版本并重算，保留旧分数。
2. **有明确假设再优化。** 将桥接证据排第6/7的问题作为开发案例，单独检验候选覆盖或排序策略；先冻结单变量配置与门禁，再取全新题验证，不能继续用这100题选参。
3. **按业务需求扩展。** 中文、BEIR纯检索、Agent/多轮Memory分别定义指标和权限断言；建立实际并发与成本数据后，再制定生产SLO和上线阈值。异机备份与提交评审另行处理；本轮未提交、未部署。

## 12. 后续只维护本文的规则

1. **当前事实只写在本文。** 新的进展增量修改对应章节、结果表和待办状态，不按对话轮次创建新的计划/复盘/汇总Markdown。更新日期、分支、完整HEAD、实际源码指纹及检查范围；未提交内容明确标未提交。
2. **证据可以新增，不能回写。** 新运行使用新目录，保存题集/协议/校验和与自动生成报告；机器验证JSON按运行追加保留。数据集版本、SOURCE许可与真实人审日志继续作为数据维护，它们不是第二份当前状态文档。
3. **历史文字一次性归档。** 旧public-datasets、题集README、研究/设计/计划和三份实验纪要已加历史标识并指回本文，保留内容供追溯，后续不再同步更新。根README只提供本文入口，不复制实验数字。
4. **状态以证据为准。** 区分代码存在、单元/模拟测试通过、真实运行通过、人工批准、独立留出通过与生产验收；计划不能提前改成已完成，质量FAIL不能因进程成功改写为PASS。
5. **维护时检查。** 校对本文数值与对应机器证据、相对链接、协议/题集哈希未被文档整理改变；纯文档更新不重复消耗模型调用。代码变更再跑相应测试和必要真实检查。

先前文档整合轮核对了代码入口、四个题集manifest、三份机器验证记录与历史说明。本轮新增归档/冻结留出工具、新100题与真实人工审核记录，并修复企业评测范围；旧标签、协议、capture及报告不覆盖。新100题4组、企业50题及人审报告分别记录，运行源码指纹不混用。

先前文档整合轮校验通过：四套题集 `validate` 均为 FILES_VALID；13份历史真实运行的原始 provenance 绑定校验通过；本文60个核心表格指标值与机器结果一致；11份文档的本地链接及主文档导航有效；study冻结协议与全部子文件、Java/pom指纹未改变；`git diff --check`通过。该次文档整理没有新增模型调用；本轮真实运行另按实际结果记录，不将历史测试结果写成此次重新执行。

本轮最终核验通过：五套题集均为FILES_VALID，18份原始运行provenance及人审报告绑定有效，study/新100题原协议与数据未改，当前Java指纹匹配企业实测版本；本文新100题20个质量指标、企业汇总、本地链接与导航均核对一致，`git diff --check`通过。完整后端测试258项（248通过/10跳过/0失败错误），Python36项再次通过；最后文件/恢复检查未调用模型。最终收工证据见[closeout-validation](experiments/closeout-validation-2026-10-27.json)，归档恢复证据另行保留，后续只增量维护本文。


## 13. 面试实验报告：可口述版本与技术追问

### 13.1 项目与个人工作范围的表述

yeRAG是带用户/组织权限的企业RAG服务，技术栈为Spring Boot、Elasticsearch、MinIO、Kafka与兼容模型API。本文可用于说明本项目已完成的设计、实验和缺陷修复；本批仍有未提交变更，不推断提交作者。本地隔离环境里的真实模型调用不是生产流量，未证明线上业务收益或已经生产部署。

**约90秒口述稿：**

> 我在yeRAG里完善了可追溯的RAG评测。检索阶段先在BM25和向量两路中做相同的权限预过滤，再按名次做RRF融合，取候选头部重排，最后将Top5组装给生成模型。评测同时看答案EM/F1、必要证据是否找齐，以及证据是否真的进入模型，能区分召回、排序窗口和上下文裁剪的问题。
>
> 我先做召回消融，再准备开发/留出划分。一次把BM25权重从1改到0.5的实验，在开发集上提高了完整证据命中率，但留出集EM从64%降到62%，所以没有采用。随后补完整重排链路时发现，重排API正常返回，Pipeline却因为窗口外空分数的自动拆箱异常退回RRF；用最小失败用例定位并修复后，50题两轮的完整证据命中率从60%到94%，EM从66%到72%。这组数据只作为已见题诊断，另在排除旧250题的新100题上冻结验证，两轮完整证据从67%到89%，EM从62%–63%到70%–71%，均通过预设开发检查。
>
> 这些实验保留了配置、数据和原始请求工件，可以脱离服务离线重算。企业50题用真实人工标签单独验证双身份权限、拒答与冲突信息：40道正例证据全入模，Judge48正确、2部分完整，4道ACL和6道无答案均拒答，未检出受限来源或精确泄漏；授权问题作为阻断项。我的结论以实验范围为限，不把标签全覆盖等同于答案一定有充分证据，也不把某次答对当成模型必然用了检索。

### 13.2 可核对的实验事实

| 事实 | 可口述数字/结论 | 边界 |
|---|---|---|
| 主链路诊断增益 | 两轮CompleteHit60%→94%，EM66%→72%，F1 69%→77.38% | 同一已见50题，本地shared-candidate-subset，重排v2 |
| 延迟代价 | Pipeline中位数两轮分别911→1081ms、1002→1136ms；P95为1464→1507ms、1595→1841ms | 未做并发压测，测量不含离线Judge，不外推生产SLO |
| 失败选参 | 开发CompleteHit62%→68%；留出CompleteHit80%持平，EM64%→62%，FAIL | 未重选参数，未宣称2个百分点具有统计显著性 |
| 实际链路缺陷 | 首次开启组捕获47题全部降级；修复后100次真实重排全部成功 | 生产代码路径中的缺陷，在本地验证；不虚构线上事故 |
| 可恢复证据 | 18组原始运行＋1组人审报告，76个核心文件脱离原target重算一致 | 已有本机持久副本，不等同异机备份 |
| 新100题独立验证 | 两轮CompleteHit67%→89%，EM63%→70%、62%→71%，均PASS；中位延迟942→1097ms、947→1150ms | 排除旧250题，100题重复两轮；仍有单题退化和输出波动 |
| 企业50题 | zyh真实批准50题标签；40正例证据全入模，Judge48正确/2部分完整，4ACL和6无答案拒答、安全PASS | 两题partial和5条固定抽查保留；非生产权限全覆盖，不冒充人工逐题批准答案 |

### 13.3 深挖案例一：为何同时保留RRF和Rerank

**设计。** BM25与向量分数不在同一量纲，RRF使用每路名次而非混加原始分数；两路先做ACL过滤，避免未授权候选挤占召回窗口。默认每路TopK×30，TopK5即每路150，融合后取前50交给重排模型，最后交付Top5。重排失败保留RRF顺序，不能混入部分重排分数重新排序。

**实验。** 关闭重排的RRF消融未超过纯向量；补完整链路后，同样有序候选输入的50对问题，CompleteHit提高34个百分点。剩余案例说明语义重排也会把跨文档桥接块排到第6、第7，不能把总体提升当成逐题无损。

**指标。** Hit表示至少一个必要单元，CompleteHit表示全部必要单元。杂志频率题缺少标注的停刊句却能答对，说明公开支持句全覆盖和解题充分性仍不同；严格指标保留原标签，标签最小性另做复核。

**可追问：** 为什么不做BM25/cosine加权求和？为何过滤要在每路召回前？RRF权重、候选深度和topN如何影响质量/时延？多跳关系块被挤出Top5应如何设计独立开发实验？

**代码：** [`HybridSearchService`](../src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java)、[`HybridRecallClient`](../src/main/java/com/yizhaoqi/smartpai/service/HybridRecallClient.java)、[`RankFusion`](../src/main/java/com/yizhaoqi/smartpai/rag/RankFusion.java)、[`RerankStage`](../src/main/java/com/yizhaoqi/smartpai/rag/stage/RerankStage.java)。

### 13.4 深挖案例二：一次应当拒绝的“优化”

**问题。** 原RRF里部分必要证据落在第6–12名；可以检验融合权重，但不能直接在同一50题上追分后宣称泛化。

**协议。** 新增开发/留出各100题；先冻结网格0.25/0.5/1/2、选择顺序和下降预算。开发集只重放冻结路由，调用生产融合算法，不为每个参数重新生成回答。原参数100个Top5精确复现。按事先规则选择0.5后锁定，只在留出验证一次。

**结果。** 开发完整证据从62%到68%，留出80%持平、EM从64%到62%。保留FAIL、不采纳0.5，也不在已经开放的留出上再改选0.25。温度0仍可能波动，单次差值不能证明唯一因果或统计显著性。

**可追问：** 为什么候选开发集Hit不是最高仍被选中？如何防止把留出集用成开发集？单次EM下降两题如何解释？如何区分离线重算一致和在线回答完全确定？

**代码/证据：** [`rag-eval-study.py`](../scripts/rag-eval-study.py)、[`EvaluationRankReplay`](../src/main/java/com/yizhaoqi/smartpai/rag/eval/EvaluationRankReplay.java)、[`verification-study200`](experiments/verification-study200-2026-10-27.json)。新100题预登记另用 [`rag-eval-holdout.py`](../scripts/rag-eval-holdout.py)，不复用权重调参的选择流程。

### 13.5 深挖案例三：HTTP成功却没有真正重排

**症状。** 重排接口返回了50条不同下标的合法分数，约297ms，低于1500ms超时；但Pipeline的重排阶段全部DEGRADED，Top5仍用RRF分数。只看enabled或HTTP200会漏掉问题。

**最小复现。** 召回2条、topN=1，模拟服务正常返回第1条的分数。真实Pipeline应该SUCCEEDED，修复前却DEGRADED；无需外部服务即可稳定复现。

**根因。** `scores == null ? v.score() : scores.get(id)` 一侧是基本类型double，Java会对另一侧Double自动拆箱。窗口外候选没有分数，null引发空指针，被统一恢复逻辑处理成服务降级。默认候选数超过50时就会触发这个路径。

**修复。** 明确保留boxed Double/null，窗口外候选记录OUTSIDE_RERANK_WINDOW，不把它补回结果，不混合两种分数；补充异常类名Trace和回归用例。之后真实100次重排均成功，并验证实际TopK使用RERANK分数。另一个资源生命周期问题通过托管并关闭ES transport修复，保证CLI完成后自行退出，适合补充说明资源所有权。

**可追问：** 为什么没有直接看到空指针？如何设计降级而不掩盖实现缺陷？怎样证明用户实际拿到的是重排结果？单元测试为何应让候选数超过topN？

**代码/证据：** [`RerankStage`](../src/main/java/com/yizhaoqi/smartpai/rag/stage/RerankStage.java)、[`RagPipelineTest`](../src/test/java/com/yizhaoqi/smartpai/rag/RagPipelineTest.java)、[`RagPipeline`](../src/main/java/com/yizhaoqi/smartpai/rag/RagPipeline.java)、[`EsConfigLifecycleTest`](../src/test/java/com/yizhaoqi/smartpai/EsConfigLifecycleTest.java)、[主链路验证](experiments/verification-rerank-2026-10-27.json)。
