# 2026-10-27：补齐 RRF + rerank 主检索链路评测

> **已归档：主检索链路补测纪要（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](../README.md)；历史状态不代表当前状态。

主检索实现是 ACL 预过滤的 BM25/Vector 双路召回 → RRF → rerank → TopK → 上下文 → 生成。之前的 Hybrid 是关闭重排的召回消融，不能代表完整主链路。本文单独补测 RRF 与 RRF + rerank，不以召回消融成绩判断重排价值。

分支 `agent`，基线 HEAD `fd8d6a3134f48f7a34422a5b3dc4e1547ae74334`。修改仍未提交，实际 Java/pom 哈希另存于 capture 和验证文件。

## 评测中发现并修复的生产问题

私有评测配置原本没有重排 URL。使用现有 Embedding 账号测试 DashScope：`gte-rerank` 返回 HTTP 403/AccessDenied，`gte-rerank-v2` 和 `qwen3-rerank` 的两文档探测均返回 HTTP 200。此次选择同系列 v2，不据此作多模型择优；旧模型停用信息见 [官方说明](https://help.aliyun.com/en/model-studio/rerank)。连通性与实际结果分开，403 的根因未由探测单独证明。

首次正式尝试中，重排开启组已有47题全部 DEGRADED，但一次独立重放的真实50文档请求返回50条、50个不同合法下标，约297ms完成；不是该请求超过1500ms超时。该尝试已中止，保留 `target/hotpotqa50-rerank-20261027-001`，标记 ABORTED_DIAGNOSTIC，不作为成功重排的质量成绩。

最小复现：召回2条、重排窗口1条、模拟重排返回第一条的正常分数，真实 `RagPipeline` 应 SUCCEEDED，却 DEGRADED。命令：

```bash
mvn -q -Dtest=RagPipelineTest#successfulRerankWindowDoesNotDegradeWhenRecallHasUnscoredTail test
```

修复前本次执行失败：`expected SUCCEEDED but was DEGRADED`，见 `target/eval-rerank-window-before.log`。修复后本次 `RagPipelineTest` 全部通过，见 `target/eval-rerank-window-after.log`。

根因在 `RerankStage.publish`：默认召回候选超过重排 topN=50，窗口外的候选应记录空重排分数。`scores == null ? v.score() : scores.get(id)` 的一侧是基本类型 double，Java 将另一侧 Double 自动拆箱，窗口外的 null 引发 NullPointerException，被统一异常处理静默回退到 RRF。服务成功仍无法交付重排排序。

修复保留 boxed Double，让窗口外分数合法为空，保留 OUTSIDE_RERANK_WINDOW 决策；不混合 RRF 与重排分数，也不把窗口外候选补回重排结果。另记录只含异常类名的 `rerankFailure`，供区分内部错误和服务降级。权限、排序阈值、窗口、超时及降级策略未改变。

## 受控对照

新增 `scripts/rag-eval.sh rerank`，顺序执行 RRF-01 → RRF+rerank-01 → RRF+rerank-02 → RRF-02。两组均使用原权重1:1、k=60、每路150候选、TopK=5、4000/32000证据/上下文预算、同一生成模型与参数；固定关闭改写。仅切换 request.options.rerank。重排固定 `gte-rerank-v2`、topN=50、maxDocChars=1000、timeout=1500ms；不根据成绩更改配置。

开启组启动前检查服务配置；工件绑定重排模型、窗口、截断、超时及服务地址指纹，不存地址或凭据。逐题检查实际 RERANK 状态、模型和 TopK 分数类型，报告跳过/失败数量。只允许显式 `options.rerank` 差异，其他配置不匹配拒绝比较。纯重排对照须逐题实际成功，存在降级则执行检查 INCONCLUSIVE；没有质量策略不显示质量 PASS。

使用原50题（25 bridge、25 comparison、500来源文档）的英语 shared-candidate-subset，属于已见题诊断，不是独立留出验证或官方 distractor/fullwiki 成绩。本轮不包含独立 claim-support Judge，不推算重排账单。固定 QA 覆盖与 Agent 共享的检索和重排实现，未覆盖 Agent 决策、查询改写和多轮记忆。仓库生产默认模型和配置未调整，仅私有评测配置显式使用 v2。

不能直接拼接旧矩阵的向量/Hybrid成绩做本轮因果比较：扩充 study200 索引后，本轮与旧矩阵的语料指纹不同。在两轮各自观察到的1020个相同逻辑块中，有4个 ES ID 变化，正文哈希均不变；索引扩充也可能影响检索排序。本轮重新运行 RRF 控制组，要求四份新运行的语料、权限与配置指纹一致。

## 结果与验证

修复后在新目录 `target/hotpotqa50-rerank-20261027-002` 完成四组真实运行，共200次生成、200次正确性 Judge。每轮仍为同一50道题，重复运行不增加独立题数。

| 方案 | Hit@5 | Coverage@5 | CompleteHit@5 | EM | F1 |
|---|---:|---:|---:|---:|---:|
| RRF-01 | 98% | 79.33% | 60% | 66% | 69.00% |
| RRF + rerank-01 | 100% | 97.17% | 94% | 72% | 77.38% |
| RRF + rerank-02 | 100% | 97.17% | 94% | 72% | 77.38% |
| RRF-02 | 98% | 79.33% | 60% | 66% | 69.00% |

开启组100/100次 RERANK 为 SUCCEEDED，模型均为 `gte-rerank-v2`，0跳过、0降级。逐题实际请求均为50个不同候选；实际 Top5 均来自该窗口并使用 RERANK 分数。关闭组100次均按预期 SKIPPED/DISABLED_OR_EMPTY，Top5 使用 RRF 分数。

两轮各50对的完整、有序 RRF 输入池均逐题一致，语料/权限/模型/代码及其余配置均一致；记录的唯一配置处理变量为 `options.rerank: false → true`。因此本轮没有把候选池变化混作排序增益。

两轮配对结果相同：

- CompleteHit@5 增加34个百分点：改善17题、退化0题、持平33题。
- EM 增加6个百分点：改善4题、退化1题、持平45题。
- F1 增加8.38个百分点：改善6题、退化2题、持平42题。

预先指定的 `no-quality-drop.json` 开发检查及实际重排执行检查两轮均 PASS。它检查总体均值，不意味着每道题均获益；重排后仍有答案退化。改写、Agent、多轮、中文、真实受限权限集和独立新留出题目仍未由此验收，不将两轮稳定结果当作显著性检验。

| 运行 | Pipeline P50/P95（ms） | 生成 Token |
|---|---:|---:|
| RRF-01 | 911 / 1464 | 33181 |
| RRF + rerank-01 | 1081 / 1507 | 34022 |
| RRF + rerank-02 | 1136 / 1841 | 34035 |
| RRF-02 | 1002 / 1595 | 33166 |

每行50个延迟观测、50次供应商实报生成用量。P95 为 nearest-rank，Pipeline 不含离线 Judge；这是顺序运行观测，不是并发压测或严格冷暖缓存实验。用量不含 Embedding/Rerank 账单，不推算金额。

本次验证：

- `mvn -q test`：257项，247通过、10跳过，0失败/错误；跳过的是9项按环境变量启用的 MySQL 测试与1项 ES 测试。
- Python 30项通过，覆盖显式开关、模型/窗口/预算差异拒绝、纯重排执行检查、假重排 TopK 拒绝、无质量策略不误报 PASS，以及既有划分/冻结/留出流程。
- 200条原生 EM/F1 全部与固定 SHA 的官方 HotpotQA 函数逐条一致。
- 四份报告从原始冻结工件重算，results、summary、外部评分输入和 sidecar 均逐字节一致；两份配对报告的 JSON 和 Markdown 也重算一致。
- 四组原始文件 SHA 绑定、数据/语料/权限/模型/代码一致性、重排真实候选、模型与 TopK 分数类型均核验通过。

机器可读证据见 [verification-rerank-2026-10-27.json](verification-rerank-2026-10-27.json)，离线重算目录为原运行目录加 `-offline-replay`。此前召回消融和权重留出报告保持原始含义，不用本轮结果改写旧成绩。

后续应以修复后的 RRF + rerank 为主链路基线，BM25/Vector/RRF 继续作为解释增益来源的消融；新增数据集或独立留出验证复用这一分层，不再把 Hybrid/RRF 直接当作完整主链路成绩。
