# yeRAG 公开数据集评测扩展建议

> **已归档：初期公开数据适配计划（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](../../../evaluation/README.md)；历史状态不代表当前状态。

日期：2026-10-26。核对分支：`agent`；HEAD：`fd8d6a3134f48f7a34422a5b3dc4e1547ae74334`。研究开始时工作区干净。本文保留最初调研基线与分阶段方案。用户随后授权实施，第一阶段实现、命令与验证范围见[公开数据集评测](../../../evaluation/public-datasets.md)；BEIR、DuReader 专用适配等后续阶段仍未实现。

## 结论

可以从 UltraRAG 提供的公开测试集中固定抽取 50 题，也可以让 yeRAG 逐步支持常见公开数据格式。应保留现有企业场景 50 题，新增公开集套件，按可用标注选择指标。不要为了沿用现有格式而捏造证据、把答案别名当作多个必要事实，或者把缺少证据标注的题记成无答案。

建议首先落地 **HotpotQA 50 题及其支持句映射**，验证公开数据导入、语料入库与现有证据 Trace 的衔接；随后加入中文 DuReader、短答案 NQ/TriviaQA，以及独立的 BEIR 检索评测。50 题适合接入验收和快速回归；扩大覆盖时按数据集分别建立更大的固定评测集，不用混合 50 题的总分代表所有能力。

外部数据格式、候选集与一手来源核实见[公开数据研究](../../research/2026-10-26-public-rag-datasets.md)。

## 调研时实现与接入差距

| 项目 | 当前事实与代码来源 | 扩展含义 |
|---|---|---|
| 题数 | `EvaluationCases.load` 按 manifest 的 `expectedCount` 验证，没有固定 50 的限制 | 新建版本化数据目录即可扩题，主要障碍不是题数 |
| 当前题集 | `evaluation/phase1`：7 份合成资料，50 题；标注待人工确认 | 继续覆盖权限、拒答、冲突、跨文档等企业行为 |
| 答案格式 | `EvaluationCases.validate` 要求单个 `referenceAnswer`、`keyPoints`；有答案题必须有非空 `evidenceUnits` | 不能直接读取只有 `question/golden_answers` 的 UltraRAG 数据 |
| 旧格式转换 | `scripts/rag-eval-convert.py` 生成待人工补标模板；不读取 `golden_answers` | 不是公开集适配器，直接使用会丢失参考答案 |
| 评分 | `EvaluationRunner` 为 Hit@5、必要证据 Coverage@5、Judge 与人工复核分层 | 缺少标准答案 EM/F1、qrels 排序指标和按数据集分组输出 |
| 运行方式 | `RagExperimentRunner` 固定 `FIXED_QA`、单轮无 history、topK=5 | 当前只评固定 RAG，不能据此声称评了 Agent 工具选择或多轮 Memory |
| 正式分母 | 当前正式检索/答案指标要求真实的 `LABELS/APPROVED` 记录 | 新的数据来源验证状态须显式建模，不能伪造人工批准 |
| 语料预检 | `HybridSearchService.scanCorpusWithPermission` 最多 100 文件、10000 块；Runner 每题前后扫描 | 大语料需新的分页/快照校验方式，直接放宽常量不足以扩展 |

本次检查的实现文件：

- [EvaluationCases.java](../../../src/main/java/com/yizhaoqi/smartpai/rag/eval/EvaluationCases.java)
- [EvaluationRunner.java](../../../src/main/java/com/yizhaoqi/smartpai/rag/eval/EvaluationRunner.java)
- [RagExperimentRunner.java](../../../src/main/java/com/yizhaoqi/smartpai/rag/eval/RagExperimentRunner.java)
- [HybridSearchService.java](../../../src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java)
- [现有评测说明](../../../evaluation/README.md)
- [旧格式转换模板](../../../scripts/rag-eval-convert.py)

## 数据集与指标按标注能力组合

| 可用标注 | 可评内容 | 缺失项如何处理 |
|---|---|---|
| 问题 + 一个或多个可接受答案 | 数据集规定的答案 EM/F1、独立 Judge | 没有语料不能运行本地端到端 RAG；没有证据标签，证据 Coverage 为 N/A |
| 问题 + corpus + qrels | 标准检索 nDCG、MRR、Recall 等 | 没有参考答案，不强制调用生成模型/Judge |
| 问题 + 原始语料 + supporting facts | 必要证据 Hit/Coverage 和阶段损失诊断；有答案时再评答案 | 原文映射失败应记数据/映射问题，不计成检索失败 |
| 受控身份 + 权限/拒答标注 | ACL、越权泄漏、无证据时拒答 | 普通公开 QA 的正确答案不能替代权限测试 |

具体约定：

1. `golden_answers` 在常见短答案任务中通常表示可接受的答案变体，应保留为 `referenceAnswers[]`；不能机械转换成必须同时满足的 `keyPoints[]`。多答案任务还要由适配器区分“别名”和“多个都要答出的实体”。
2. `qrels` 是 query 到文档/段落的相关度，不是最小必要原文。不能把所有相关文档声明为必须全部找齐的 `evidenceUnits`。
3. HotpotQA 的 supporting facts 可根据标题和句子序号解析为原文，再适配现有证据定位；保留原始支持句粒度。不同来源的标签语义须明确记录，不能把不同 Coverage 总分当作同一种金标准。
4. 标准 EM/F1 使用对应数据集的官方规范，记录 scorer 版本。英文空格分词不能直接用于中文。现有解释式完整答案与短答案格式不同：若增加短答案评测模式，应在运行前固定输出约定；完整回答和 `shortAnswer` 都保留，不能让 Judge 事后改写答案刷分。
5. 官方指标分母遵循官方缺失预测/失败处理规则，同时另报技术失败数量；不能仅排除失败题再称为官方分数。现有企业集正式指标的分母规则保持原定义。
6. Judge 继续单独输出支持性与语义判断，不改写原始公开标签或程序指标。不能用 Judge 命中率冒充官方 EM/F1。

依据：[UltraRAG 数据说明](https://ultrarag.openbmb.cn/pages/cn/develop_guide/dataset)、[HotpotQA 官方评分器](https://github.com/hotpotqa/hotpot/blob/master/hotpot_evaluate_v1.py)、[BEIR 格式与评测](https://github.com/beir-cellar/beir)、[DuReader Robust 官方说明](https://github.com/baidu/DuReader/tree/master/DuReader-Robust)。

## 推荐的数据接入顺序

### 第一步：保留企业 50 题，新增 HotpotQA 50 题

HotpotQA 提供支持句标注，最适合验证现有必要证据追踪能力。首批使用原始有答案标签的 dev distractor split，按 bridge/comparison 等类型固定分层抽样；在可用配额内以 25/25 为目标，保存实际 ID 与不足原因，不挑模型已经答对的题。不能假定 UltraRAG 转换样本的 `meta_data.context` 都含有全部支持文章；下载后逐条检查支持标题与句子序号是否可解析，缺失时回到匹配版本的原始语料，不能凭模型补写。

先建设一个明确标为 `subset-corpus` 的共享小语料：将抽中问题的候选上下文去重合并，包含干扰段落；所有问题检索同一个冻结语料，不能每题只索引它自己的金证据。标题相同但正文不同的记录保留来源和冲突说明，稳定 ID 包含版本或正文哈希。

这套共享小语料是本地检索诊断集，**不是**官方每题 10 段 distractor 设置，也不是 fullwiki 全库评测；不得直接对照官方榜单分数。只导入支持文档的版本可用作单独阅读诊断，须标为 `oracle`，不能报告开放域召回能力。

将支持句通过原文映射到 yeRAG 文档和解析后的原始子块；保留必要单元与实际送入模型的逐阶段结果。如果原始句子被切开导致预检找不到，明确列入映射/分块问题，不能偷偷拼接子块。文档数超过现有 100 文件限制时，必须先实现分批预检；可用导入分片保存多篇原文，但必须有源段落到导入范围的映射，不能通过打包丢失 provenance。

此步骤交付的是一套新的公开来源 50 题、语料映射和可复现运行入口。现有企业集仍单独运行；两套都跑时总计 100 题，分别出报告。

来源：[HotpotQA 官方主页](https://hotpotqa.github.io/)。

### 第二步：增加中文和短答案覆盖

- 中文优先调查 DuReader Robust/Checklist；它们关注不同鲁棒性问题，分开记录。先支持问题、上下文、答案和官方 scorer，再判断是否有足够粒度的证据标注；答案 span 不自动升级为“充分支持结论的证据”。
- NQ/TriviaQA 接入保留多参考答案，固定各自版本和语料。UltraRAG 的统一问题文件可作为输入；没有证据映射时先提供答案指标，不能自动获得证据 Coverage。
- 将多个数据集分别扩到适合预算的固定开发集与独立验收集，例如开发 50 题、验收 200–1000 题。数量是计划，具体受语料与调用预算约束；按来源、语言、任务分别报告，调参不能反复查看验收集。

来源：[DuReader 官方仓库](https://github.com/baidu/DuReader)、[UltraRAG 数据卡](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark)。

### 第三步：检索标准集与更复杂任务

- 接 BEIR 的 `corpus.jsonl / queries.jsonl / qrels/*.tsv`，复用权限检索的检索模式，输出官方文档 ID 与排序，再用标准 scorer 评测。`RagTypes.Mode.RETRIEVE_ONLY` 已存在，但当前评测 Runner 尚未提供这一套件。
- yeRAG 会把源文档分块：评分前须将块映射回原始 corpus ID，按预先固定策略聚合分数并去重；不能让同一文档的重复块占据标准文档排名。源段落本身为评分单位时保持原段落 ID。
- 再考虑 MuSiQue 等更复杂多跳题。ASQA、FEVER、选择题、多轮对话需要各自任务定义和 scorer，不能仅靠统一 question 字段就宣称支持。
- 暂不将 ChartQA/DocVQA 等图像任务算作已适配；当前本地评测入口为文本证据链。

来源：[BEIR 官方仓库](https://github.com/beir-cellar/beir)、[UltraRAG 数据卡](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark)。

## 建议的实现边界

下一次实现以数据导入和评分能力为边界，复用当前 Pipeline、权限校验和 Trace，不另建业务聊天链路。

1. **数据适配器**：先实现 `ultrarag-jsonl` 和 `hotpotqa`，后续增加 `beir`、具体 DuReader 版本。输入错误时列出缺少的字段；不生成看似有效的占位证据。
2. **版本化数据模型**：新增 schema 版本，保留 v2 企业集兼容；记录 task、language、source URL/revision、split、原始 ID、答案语义、可用标注能力。`evidenceUnits` 缺失与 `unanswerable` 明确区分。
3. **独立指标模块**：答案、qrels 检索、必要证据、Judge、ACL 各自判定适用性并记录分母；保留现有 Hit@5/Coverage@5 的定义，不把它更名为 qrels Recall。
4. **运行配置**：明确固定 RAG 与检索模式；topK 成为校验后的运行参数，受既有检索上限约束。新增 K 时同步修改 Runner 的工件绑定检查与报告，不能只修改请求参数。
5. **语料映射与规模**：保存原始 corpus ID → 导入文件/范围 → 实际索引子块的映射。大库预检采用分页、批次和不可变快照/版本验证，保留 ACL；避免每题对全部语料重复扫描。不可把 2100 万段 Wiki 一次性塞进现有预检。
6. **标签来源与信任**：区分人工审核、官方发布、转换映射待验与合成草稿；公开官方标签可有明确的来源验证流程和指标准入条件，不能导入时冒充 reviewer 或自动写 `APPROVED`。企业集既有人审要求不受影响。
7. **隔离与复现**：用专门评测环境冻结可检索语料；当前检索不会因 manifest 列出某些文档就自动限定只能搜索它们。语料范围约束必须与现有 ACL 取交集，不能改变线上授权。
8. **报告**：按 dataset/split/language/task/corpus scope 输出，保留失败数、N/A 与原因。可另报整体宏平均，但不跨定义不同的指标制造一个综合“RAG 分数”。

源文件元数据记录抽样算法/seed、选中 ID、下载 revision、原始与转换后的校验和、语料版本、chunk 配置、embedding/rerank/生成模型、prompt/scorer 版本和调用配置。答案标签留在评测器侧，不附加到检索正文或生成 prompt 中。

各数据集和语料的许可、来源署名与修改说明分别记录，未确认的标为待核实。只下载小规模元数据/样本核对，不默认拉取全量 Wikipedia 或发起付费模型评测。

## 验证要求与调研阶段范围

实施后的必要验证：多参考答案别名不被当作多个必答要点；无证据标注为 N/A；qrels 与必要单元各算各的；支持句原文映射可验证；不同块的相同原始文档不重复得分；采样 ID 可复现；旧 v2 50 题报告口径不变；失败不被静默丢弃；权限边界与降级行为仍成立。后端实现变更还须运行仓库要求的全量测试。

上一阶段仅阅读实现和一手资料，新增方案/研究文档。以下保留当时的验证记录；后续实施和真实运行以[公开评测状态](../../../evaluation/hotpotqa50/verification-status.json)为准：

- `scripts/rag-eval.sh validate evaluation/phase1`：本次执行通过，输出 `FILES_VALID cases=50`，题集哈希 `d7439251f66c39398de8412758692294602496265044ab6465a31d8bf7c9f455`。仅证明文件格式、题数与哈希，不代表人审或真实质量。
- 用 Python 直接调用旧转换器的 `convert()`，输入 `question/golden_answers/meta_data`：本次观察到输出 `referenceAnswer=""`，证据为人工补标占位符，确认该脚本不是 UltraRAG 适配器。
- 调研阶段没有调用真实 ES/生成模型、导入新的公开50题或执行质量评测；这一描述不适用于后续获授权的实施阶段。
