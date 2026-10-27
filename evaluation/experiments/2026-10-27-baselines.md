# 2026-10-27：评测机制改进与召回对照

> **已归档：关闭重排的召回消融纪要（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](../README.md)；历史状态不代表当前状态。

范围说明：本文 Hybrid 是 **RRF、关闭 rerank** 的召回消融。这里的 66% 完整证据命中率不能代表 RRF + rerank 主链路，也不能据此判断重排无收益。后续主检索链路补测见 [RRF 与重排对照](2026-10-27-rrf-rerank.md)。

按现有实现核对用户提供的建议后，保留 Java Runner、真实 `RagPipeline`、必要证据标注、Trace 和人工复核流程，补齐可比实验及独立测量。没有安装 EvalScope、MTEB 或 RAGAS，也没有为了增加指标改动生产默认策略。

分支 `agent`，基线 HEAD `fd8d6a3134f48f7a34422a5b3dc4e1547ae74334`。本次实现为工作区未提交变更，运行 capture 另记录 Java 源码与 pom 的实际哈希；不推断未提交变更的作者。

## 对建议的取舍

| 建议 | 本次处理与依据 |
|---|---|
| 系统对比 BM25 / Vector / Hybrid | 已实现显式 baseline，并执行同题、同语料、同预算、同模型的两轮顺序对照；baseline 统一关闭 rewrite/rerank |
| 将“严格 Hit”改为 CompleteHit | 原实现 Hit 本来就是任一证据命中，保持原语义；新增 CompleteHit@5 表示全部必要单元命中 |
| 生成支持度、正确性、完整性分开 | 原生答案 EM/F1、原正确性 Judge、自定义陈述支持度独立保存；有真实 keyPoints 才计算答案完整性 |
| 相邻块扩展 | 暂不引入。旧工件的22个缺失证据单元中，只有5个与 Top5 同文档、4个处在 Top5 相邻块，不能先假定它是主要解法 |
| Recall/MRR/NDCG | 暂不新增。现有必要支持句不构成穷举相关文档 qrels，Coverage 不改名为标准 Recall |
| 接入评测框架 | 暂不引入。提供标准字段 JSONL 导出，保留将来交叉评分入口，不声称已经做过 RAGAS 验证 |
| 线上质量门禁 | 先实现离线配对开发检查；未校准的延迟、Token 阈值不填写，不称为生产上线标准 |

建议文本基于旧状态：企业 `phase1` 的50题仍为 AI 起草、待人工确认；公开 HotpotQA50 的来源映射也不是人工批准。两者始终使用独立分母。

官方资料只用于核对工具定位：[EvalScope RAGEval](https://github.com/modelscope/evalscope/blob/main/docs/en/user_guides/backend/rageval_backend/index.md)、[MTEB 文档](https://docs.mteb.org/)、[Ragas Faithfulness](https://docs.ragas.io/en/latest/concepts/metrics/available_metrics/faithfulness/)。本次自定义 `judgeClaimSupportRate` 不冒用这些框架的指标名称。

## 旧工件重算给出的诊断

原真实运行保留在 `target/hotpotqa50-run-20261026-001`，新规则离线重算至 `target/hotpotqa50-reanalysis-20261027-001`，没有重新生成答案或覆盖旧报告。

| 位置 | FULL | PARTIAL | NONE |
|---|---:|---:|---:|
| retrievalPool | 50 | 0 | 0 |
| top5 | 33 | 16 | 1 |
| merged | 50 | 0 | 0 |
| assembled | 33 | 16 | 1 |
| wire | 33 | 16 | 1 |

原 Hit@5 98%、Coverage@5 82.67%，新增 CompleteHit@5 66%；EM 64%、F1 68.33% 保持不变。Merge 处理整个排序池，随后 Assemble 的 TopK 窗口才排除其余段落；22个缺失单元在排序窗口和组装窗口各被定位一次，不能加成44个独立缺失。旧 `ASSEMBLY_LOSS` 细化后为22个 `CONTEXT_TOP_K_WINDOW_LOSS`，本次没有发现 Token 预算裁剪证据。

这说明应先检验排序、窗口和实际入模结果，不能看到“组装后丢失”就扩大 Token 预算。旧运行重排实际为 `SKIPPED / NOT_CONFIGURED`，不包装成“有重排的高阶基线”。

## 新机制的测量边界

- **证据**：Hit、Coverage、CompleteHit 的分母都是具备必要单元且程序验证有效的题；无答案/ACL 负例保持独立。`diagnosticCohorts` 按真实入模 FULL/PARTIAL/NONE 分组显示原生 EM 与 Judge 结果，只描述相关现象，不将错答自动判定为幻觉或证据利用失败。
- **支持度**：可选 `yerag-claim-support-v1` 独立请求，仅用真实上下文判断回答中模型提取的陈述，服务端检查回答及上下文引用。无陈述或提取不完整为 N/A；引用存在不等于语义支持已被程序证明，模型也可能漏提陈述。此结果尚未经过人工一致性校准。
- **完整性**：按真实 keyPoints 单独判断。本次 HotpotQA 短答案没有必答要点，因此全部 N/A；不拿答案别名或 supporting facts 伪造完整性标签。
- **性能**：Pipeline 和首段回答延迟使用工件实际时间，分开报告 P50/P95（nearest-rank）；离线两个 Judge 不计入 Pipeline 延迟。上下文 Token 为估算，生成与两个 Judge 的 Token 分开使用供应商实报值。Embedding/Rerank 账单与货币成本尚未统计，不把 Token 当作金额。
- **可比性**：验证原始文件哈希、逐题 ID、题集/语料/权限/身份、生成模型及参数、Judge 模型及提示版本、预算和代码版本。回归输出逐题改善/退化/持平；缺测不补零，不自动通过。
- **外部评分入口**：导出真实 `user_input/response/reference/retrieved_contexts`；sidecar 保留 ID、来源、答案别名和跳过原因。未安装/调用第三方框架，没有交叉评分结果。

## 真实实验设置与结果

语料为冻结的 HotpotQA **shared-candidate-subset**：50题，bridge/comparison 各25题，合并500个候选段落文件，实际索引1022块，保留干扰项；既非官方每题10段条件，也非 fullwiki。来源 revision、下载校验和与抽样算法见 [数据集说明](../hotpotqa50/README.md)。

生成模型 `deepseek-chat`，Embedding `text-embedding-v4`（2048维），chunk-size=512，TopK=5，temperature=0；专用本地 ES 使用 standard analyzer。三种策略使用同一个带 ACL 的语料范围预过滤，候选池/上下文预算一致，均关闭改写、重排，不启用相邻扩展。支持度与正确性 Judge 同一模型、独立请求。

实际请求控制项：candidateK=150，RRF k=60、两路权重各1，route-timeout=2000ms，merge=false、legacyClipping=false，evidenceTokens=4000、contextTokens=32000、safetyTokens=512；生成 top_p=0.9、max_tokens=2000。这些字段来自冻结 Pipeline/模型工件，不根据配置默认值猜测。Embedding 使用相同本地部署配置，但供应商模型名称不是不可变后端版本，跨时间运行仍有服务侧变化可能。

正式对照目录：`target/hotpotqa50-matrix-20261027-002`。计划顺序：BM25-01 → Vector-01 → Hybrid-01 → Hybrid-02 → Vector-02 → BM25-02；每次保留独立 capture、模型响应、工件、报告与原始文件校验和。温度0仍不能保证供应商输出完全确定，两轮用于暴露波动，不是显著性检验。

六组均完成，300次生成、300次正确性 Judge、300次支持度评审成功，0执行失败；以下每行均为同一50题，重复次数不增加独立题数。

| 策略/轮次 | Hit@5 | Coverage@5 | CompleteHit@5 | EM | F1 |
|---|---:|---:|---:|---:|---:|
| BM25-01 | 94.00% | 70.50% | 46.00% | 58.00% | 61.00% |
| VECTOR-01 | 100.00% | 87.17% | 74.00% | 66.00% | 70.14% |
| HYBRID-01 | 98.00% | 82.67% | 66.00% | 66.00% | 68.90% |
| BM25-02 | 94.00% | 70.50% | 46.00% | 58.00% | 61.00% |
| VECTOR-02 | 100.00% | 87.17% | 74.00% | 66.00% | 70.14% |
| HYBRID-02 | 98.00% | 82.67% | 66.00% | 64.00% | 67.00% |

配对开发检查（候选均为同轮 Hybrid；两项最大允许下降均为0）：

| Baseline → Candidate | CompleteHit 差值（百分点） | EM 差值（百分点） | 状态 |
|---|---:|---:|---|
| bm25-01 → hybrid-01 | +20.00 | +8.00 | PASS |
| vector-01 → hybrid-01 | -8.00 | +0.00 | FAIL |
| bm25-02 → hybrid-02 | +20.00 | +6.00 | PASS |
| vector-02 → hybrid-02 | -8.00 | -2.00 | FAIL |

两轮完整证据配对均为：BM25 → Hybrid 改善11题、退化1题、持平38题；Vector → Hybrid 改善3题、退化7题、持平40题。混合相对关键词检索有收益，但没有在这个子集上超过纯向量；没有据此修改生产默认值。

运行测量与自定义支持度（延迟单位ms；支持度列同时给出适用题数）：

| 策略/轮次 | Pipeline P50/P95 | 生成 Token | 正确性 Judge Token | 支持度 Judge Token | 支持度均值（分母） |
|---|---:|---:|---:|---:|---:|
| BM25-01 | 669/1177 | 35042 | 49750 | 58150 | 71.28%（47/50） |
| VECTOR-01 | 923/1409 | 31557 | 45829 | 54459 | 75.51%（49/50） |
| HYBRID-01 | 977/1664 | 33546 | 48021 | 56495 | 76.53%（49/50） |
| BM25-02 | 693/1147 | 35004 | 49620 | 57614 | 68.48%（46/50） |
| VECTOR-02 | 980/1269 | 31556 | 45851 | 54557 | 71.43%（49/50） |
| HYBRID-02 | 921/1507 | 33548 | 47953 | 56343 | 76.53%（49/50） |

所有 Token 均为供应商实报，六组共824895 Token：生成200253、正确性评审287024、支持度评审337618；均不是金额，未包含 Embedding/Rerank 账单。每组 Pipeline 延迟与三类用量的有效调用数均为50。计时是本机顺序实验的描述性分布，未做并发压测或严格冷暖缓存控制，不当作生产 SLO。

支持度为适用题的宏平均，不同分母不能直接视为质量提升；配对文件另报告共同有效题数。完整性因为没有真实 keyPoints，六组均为 N/A。支持度未经人工校准，且下方已有推断不可靠的具体样本，因此不用于证明可靠性或决定本次门禁。

重复运行稳定性（每项分母50）：

| 策略 | Top5 逐项相同 | 实际上下文相同 | 回答逐字相同 |
|---|---:|---:|---:|
| BM25 | 50 | 50 | 47 |
| VECTOR | 50 | 50 | 47 |
| HYBRID | 49 | 49 | 48 |

六组共300条原生答案分数逐题与固定 SHA-256 的 HotpotQA 官方函数一致。六份报告全部从原始冻结工件离线重算，results、summary、RAGAS 输入和 sidecar 四类文件逐字节一致；校验不调用模型。数据、语料、权限、代码和模型配置的可比性检查全部通过。机器记录见 [verification-2026-10-27.json](verification-2026-10-27.json)，原始详细工件保留在上述 target 目录。

首轮逐题 Trace 进一步定位：Vector 完整、Hybrid 不完整的7题，缺失单元仍在 Hybrid 候选池中，名次落在6–12；本次没有重排、相邻扩展或预算差异，观察到的差异位于 RRF 后的交付窗口。例：

| Case ID（省略 hotpotqa-） | 必要单元 | Vector 池名次 | Hybrid 池名次 |
|---|---|---:|---:|
| `5a9094ba55429933b8a2056d` | u1 | 3 | 8 |
| `5adc6c4c5542994650320ce0` | u1 / u2 | 2 / 4 | 7 / 6 |
| `5a7f95685542995d8a8ddeaa` | u2 | 5 | 12 |

这支持下一步单独检验融合权重、RRF 常数或有真实可用服务的重排，而不是先加大 Token 预算或假定相邻扩展会修复它。这里只提出待验证方向，不在同一50题上调参后把提高的分数当作独立验收；优化应先准备开发集与冻结留出集。纯向量在此子集占优也不足以推出它在企业中文、精确实体或权限场景中全面优于混合检索。

两轮 Hybrid 的检索窗口与实际上下文各有49/50题逐项相同，完整证据命中率均为66%，答案48/50逐字相同，EM 却从66%变为64%。其中 `hotpotqa-5abcf46255429959677d6b6b` 实际上下文完全相同，必要证据两轮都仅 PARTIAL，回答从 Rhode Island 变为 Colorado，严格 EM 由1变0；两次支持度评审都为0。这里只能确认冻结条件下仍存在输出波动，不能由某次答对推导证据链完整。唯一检索窗口改变的另一题仍回答 Pine，没有造成该次 EM 下降；其变化原因未进一步归因。在线重复波动与离线重算一致性是不同验证目标。

## 需要人工复核的评分分歧

以下来自正式矩阵 BM25-01 的真实输出，只作复核线索，不构成人工批准或对官方标签的修订：

| Case ID（去掉共同前缀 hotpotqa-） | 实际现象 | 处理 |
|---|---|---|
| `5a835e215542992ef85e2296` | 回答 Pine，原参考答案为带引号的 Pinus；EM=0，正确性 Judge=correct | 保留官方严格 EM，不能事后自动补别名制造提升 |
| `5ac120c55542992a796dedfc` | 回答 Kohlberg Kravis Roberts，参考 KKR & Co；EM=0，正确性 Judge=correct | 名称展开/别名差异，保持确定性和语义判断分离 |
| `5ac1b8ee5542994d76dccedc` | 回答 Lev Yilmaz，参考 Levni Yilmaz；EM=0，正确性 Judge=correct | 人名写法需要人工核对，不由 Judge 改金标 |
| `5ab51ddf5542990594ba9d11` | 两座教堂的宗教归属问题：回答 no，参考 yes；必要支持句全部入模，支持度 Judge=1，但其理由用“未指明另一座教堂是 Catholic”推导否定结论 | 缺少属性信息本身不能推出否定。待复核标签、上下文与语义推断；不能据支持度1断言回答可靠，也不能据 EM=0直接断言模型幻觉 |

这也是保留原始响应、引用、分母和人审队列的原因。上述公开来源题仍全部待人审；不通过修改参考答案、删题或事后抽取短答案来抬高分数。自定义支持度尚未完成人工校准，不纳入本次开发门禁阈值。

## 执行中修复的资源生命周期问题

先前目录 `target/hotpotqa50-matrix-20261027-001` 保留了 BM25 和 Vector 两组完整结果，分别为 EM 58%/64%、CompleteHit@5 46%/74%，只作排查记录，不拼入正式新矩阵。BM25 的50条结果、汇总和两份导出经离线重算逐字节一致，重算目录 `target/hotpotqa50-matrix-20261027-bm25-offline-replay`。

中间开发依赖曾全部离线，Vector 首次在应用启动阶段连接 MySQL 失败，未生成任何题目答案。已启动本机 Docker Desktop 并恢复现有五个容器，ES 中1022块仍在。后续 Vector 完成后，应用已关闭却不退出；线程转储显示 `DestroyJavaVM` 等待，剩余非守护业务线程均为 `elasticsearch-rest-client-*`。

最小复现为 `mvn -q -Dtest=EsConfigLifecycleTest test`：使用真实低层客户端、无需 ES 服务或网络调用，关闭 Spring 上下文后断言 `isRunning()==false`。原实现1项失败，明确得到 `expected false but was true`（`target/eval-es-shutdown-before.log`）。已有线程与生命周期断言的直接证据，故跳过无依据的多分支故障猜测，直接验证资源所有权。

根因是 `EsConfig` 只将高层客户端注册为 Bean，内部创建的 transport 没有生命周期管理。修复为注册 `ElasticsearchTransport` 并声明 `destroyMethod="close"`，高层客户端引用同一 transport；不修改请求、认证或检索配置。回归测试通过；原始真实预检场景也已复测（50题全部 READY，CLI 自行退出、退出码0，`target/eval-es-shutdown-real-check.log`）。没有以无限等待或强制结束进程掩盖问题。

两组旧结果及启动失败日志独立保留；正式矩阵另用新目录，六组统一使用修复后的 Java 源码版本。调度的 `--resume` 只复用完整且校验通过的运行，部分答案不覆盖。开发策略在发起模型调用前校验并冻结在新矩阵的 `matrix.json` 中。

## 验证与尚未完成的范围

- 本次 `mvn -q test`：250项，240通过，0失败/错误，10跳过（9项 opt-in MySQL、1项 opt-in ES）。不声称这些跳过的真实库测试已验证。
- Python 配对、门禁、完整性及恢复保护测试：13项通过；包括缺测/未配置不通过、代码变更需显式声明、Judge 配置不一致拒绝比较、题集/工件篡改拒绝、外部路径拒绝、部分运行不覆盖、无效策略在构建/模型调用前拒绝。
- 真实对照运行与上述替身测试分开；原企业50题的人审、更多数据集专用 scorer、BEIR/qrels、正式 RAGAS 交叉验证、生产阈值和线上监控仍待后续按需求建设。
