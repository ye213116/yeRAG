# 公开 RAG 数据集调研

> **已归档：公开数据集调研快照（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](../../evaluation/README.md)；历史状态不代表当前状态。

调研日期：2026-10-26。本文记录资料核验时的结论；下文“未实现/待确认”以调研时间点为准。用户随后授权的第一阶段实施与实际运行状态见[公开数据集评测](../../evaluation/public-datasets.md)，不将后续实现反写为调研时已完成。

## 结论

可以从 UltraRAG 提供的评测集固定抽取 50 题作为快速回归集，但需要同步准备可检索语料并保留原始标注。50 题适合查回归，不能代表整个公开基准。建议先接入 **官方 HotpotQA dev distractor 的 50 题**，它有答案与支持句，便于验证当前文本检索和引用；另保留企业权限、无答案、提示注入等自有用例。后续分别接入 MuSiQue、NQ/TriviaQA、BEIR 和中文数据。具体本地改造见[公开数据集评测适配方案](../superpowers/plans/2026-10-26-public-dataset-evaluation.md)。这是建议，接入状态仍为计划中。

## UltraRAG 实际提供什么

UltraRAG 的标准 QA 行为 `id / question / golden_answers / meta_data`，文本语料行为 `id / contents`。文档列出 NQ、TriviaQA、HotpotQA、MuSiQue 等，多数 leaderboard 子集为 1,000 题；原版和 leaderboard 抽样版分开发布。它还包括视觉、选择题等不同任务，不能一律当作文本企业 RAG 题。[UltraRAG 评测数据文档](https://ultrarag.openbmb.cn/pages/cn/develop_guide/dataset)

数据卡说明了会影响评测范围的转换：MarcoQA 已删除无答案题，FEVER 只保留支持/反驳，ASQA 使用短答案标签，长答案放在 metadata。因此不能用这些转换版直接宣称测到了完整的拒答、NEI 或长文生成能力。数据卡把 Wiki-2018 指向 FlashRAG 语料；题目 JSONL 与大规模检索语料是不同资源。[UltraRAG 数据卡](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark)

本次经 Hugging Face API 查询到数据仓库 revision 为 `4cd446c7587fd08bde0926ce350d1ea6573d1b01`；并通过 Python `urllib.request.urlopen(...).readline()` 读取以下 **leaderboard 原始 JSONL 首行**，核对了实际字段。以下是首行观察，不代表已经验证整个子集。

| 数据 | 首行实际标注 | 接入含义 |
| --- | --- | --- |
| NQ | `golden_answers` 有多个可接受答案；`meta_data={}` | 可以测答案匹配；仅该行无法得到 gold 文档/支持句，不能自动生成完整检索证据标签。 |
| TriviaQA | `golden_answers` 有大量别名；metadata 有 `EntityPages / QuestionId / QuestionSource / SearchResults` | 文档文件名与 URL 元数据不等于已经下载的正文；需另取对应证据文件。 |
| HotpotQA | metadata 有 `_id / supporting_facts / context / type / level` | 有支持句定位，但须验证支持文档确实存在于选定语料。 |
| MuSiQue | metadata 有 `id / paragraphs / question_decomposition / answer_aliases / answerable`；paragraph 含 `idx / title / paragraph_text / is_supporting` | 适合保留多跳支持段落；`answer_aliases` 也必须参与答案计分。 |
| 2WikiMultiHopQA | metadata 有 `_id / type / context / supporting_facts / evidences` | 可以进一步适配多跳支持句、关系证据，仍需校验文本映射。 |

原始文件：[NQ](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark/blob/4cd446c7587fd08bde0926ce350d1ea6573d1b01/benchmark/leaderboard_sample/nq/nq.jsonl)、[TriviaQA](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark/blob/4cd446c7587fd08bde0926ce350d1ea6573d1b01/benchmark/leaderboard_sample/triviaqa/triviaqa.jsonl)、[HotpotQA](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark/blob/4cd446c7587fd08bde0926ce350d1ea6573d1b01/benchmark/leaderboard_sample/hotpotqa/hotpotqa.jsonl)、[MuSiQue](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark/blob/4cd446c7587fd08bde0926ce350d1ea6573d1b01/benchmark/leaderboard_sample/MuSiQue/MuSiQue.jsonl)、[2Wiki](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark/blob/4cd446c7587fd08bde0926ce350d1ea6573d1b01/benchmark/leaderboard_sample/2WikiMultihopQA/2WikiMultihopQA.jsonl)。

**发现的证据缺口：** UltraRAG HotpotQA leaderboard 的首条 `id=0`，原始 `_id=5ae143ed55429920d5234360`，`supporting_facts` 标注 `Sergei Aleksandrovich Tokarev` 第 0 句和 `Moscow State University` 第 3 句；该条 `context.title` 的 10 个标题中没有 `Moscow State University`。因此不能默认把每题 `meta_data.context` 导出就得到完整支持语料。此次未确认这是否来自 fullwiki 设置或其他预处理，不能推断原因；导入前应对每题执行支持事实到语料的覆盖校验。整子集缺失比例未核实。

## 公开集分别适合测什么

| 候选 | 一手资料确认的能力与形式 | 对 yeRAG 的接入建议 |
| --- | --- | --- |
| HotpotQA | 多跳 QA，有句级 supporting facts；distractor 设置读取 10 段，fullwiki 设置另有官方 Wikipedia 语料；官方指标含答案、支持事实、联合 EM/F1。 | 首批用官方 dev distractor，导入候选段落并保留句索引；共享候选子集须明确为自建 subset，不能报告为官方 fullwiki 分数。 |
| MuSiQue | 官方区分 MuSiQue-Ans 与 MuSiQue-Full；评价答案、支持段落，Full 另评价证据充分性。 | 第二批扩展 2–4 跳；不能把多篇必需支持段落简化为“任意一个命中”。若测 Full，保留成组的 answerable/unanswerable 结构。 |
| NQ-Open | 英文输入输出，官方原始 dev 为 3,610 题；QA 行只有问题和答案列表，NQ-Open 与原始整页 NQ 任务有区别。 | 测事实短答案，独立配套语料；没有人工文档相关性标签时，检索 Recall/NDCG 记 N/A，答案包含率只能另命名为代理指标。 |
| TriviaQA | 有问题、答案别名及远程收集的证据；官方区分 RC 与 unfiltered open-domain，后者并非每篇候选文档都含答案。 | 适合实体/别名覆盖及开放域噪声；固定版本与证据来源，不把每个候选文件都当作 gold。 |
| BEIR | 检索基准，标准文件为 corpus、queries、qrels，qrels 关联 query-id、corpus-id、相关性分数。 | 测 BM25/KNN/RRF/rerank 的 Recall@K、MRR、NDCG；一般没有生成答案标签，不能硬造 `referenceAnswer`。可从较小的 SciFact 开始，再扩展领域。 |
| DuReader-Retrieval | 中文真实搜索查询，官方提供约 809 万段落、开发集 2,000、测试集 4,000；主指标 MRR@10，另报 Recall@1/50。 | 中文检索优先候选；先核查实际可下载数据与标注格式。完整语料很大，抽题不代表可以只导入正例。 |
| DuReader 2.0 | 中文阅读理解，官方 baseline 用 BLEU/ROUGE；其预处理会按答案或问题选择段落。 | 补中文回答能力；保留原始多答案及原文，不照搬训练时使用答案筛段的流程到端到端评测。 |

一手依据：[HotpotQA 官网](https://hotpotqa.github.io/)、[HotpotQA 官方评价代码](https://github.com/hotpotqa/hotpot/blob/master/hotpot_evaluate_v1.py)、[MuSiQue 官方仓库](https://github.com/StonyBrookNLP/musique)、[MuSiQue 官方评价代码](https://github.com/StonyBrookNLP/musique/blob/main/evaluate_v1.0.py)、[Google NQ-Open 说明](https://github.com/google-research-datasets/natural-questions/blob/master/nq_open/README.md)、[TriviaQA 官网](https://nlp.cs.washington.edu/triviaqa/)、[BEIR 官方格式](https://github.com/beir-cellar/beir/wiki/Load-your-custom-dataset)、[BEIR 官方评测示例](https://github.com/beir-cellar/beir)、[DuReader-Retrieval 官方说明](https://github.com/baidu/DuReader/tree/master/DuReader-Retrieval)、[DuReader 2.0 官方说明](https://github.com/baidu/DuReader/tree/master/DuReader-2.0)。

## 适配时应保留的语义

下列为基于上述一手格式和指标的设计建议：

- **答案备选与必需事实不同。** 多个答案别名是 OR：匹配任一正确表达即可；多跳必要证据通常是 AND：要覆盖全部支持事实。MuSiQue 官方代码把 `answer + answer_aliases` 送入答案指标，把所有 `is_supporting` 段落另送入支持指标，二者不能复用同一数组语义。
- **先声明能力，再启用指标。** 只有答案则启用答案 EM/F1；有 qrels 再启用检索指标；有支持句/段落再启用支持覆盖与引用评价；有 ACL/身份标签再测授权。缺少标签的指标记 N/A，并报告可计分样本数；不要默认零分或自动伪造标签。
- **保持原始 ID 与检索文本映射。** 源文档 ID、句号/段号、导入后的 fileMd5/chunkId 应有稳定映射；重切块时重新核验覆盖。保留原文，不把 gold 答案或支持标志混入可检索正文。
- **区别三种语料设置。** 每题官方固定候选、跨题合并的候选子语料、官方完整语料是三个不同实验条件。只导入支持文档会显著降低难度；跨题候选合并也改变了 distractor 设置。采样清单应记录题目种子/ID、split、source revision、语料 hash、setting、标注来源。
- **开放问答不能代替企业边界测试。** 公共 QA 的支持标注没有真实用户/组织权限语义；授权拒答应有单独的跨用户、跨组织和不可见正例。公开答案 EM 也不能证明模型使用了检索证据，需要保留证据/引用指标和必要的无检索对照。
- **各数据集使用适当 scorer。** 英文答案的规范化、别名匹配，中文分词/字符规则，以及长答案 ROUGE/BLEU 不是同一个通用 EM/F1；汇总时分数据集、语言和能力报告，不能把不同原生指标混为一项官方总分。

## 许可证与待确认事项

这里记录发布方声明，接入时还需保存具体文件的来源与条款，不能用集合的数据卡覆盖所有上游资料的许可：

- UltraRAG HF 集合声明 CC BY-SA 4.0；HotpotQA 官方声明其数据与处理后的 Wikipedia 为 CC BY-SA 4.0。[UltraRAG 卡片](https://huggingface.co/datasets/UltraRAG/UltraRAG_Benchmark)、[HotpotQA](https://hotpotqa.github.io/)
- MuSiQue 声明 CC BY 4.0，并提醒其 dev/test 的单跳种子题可能出现在其他集的训练集中，混合集时需要按官方单跳 ID 排除泄漏。[MuSiQue](https://github.com/StonyBrookNLP/musique)
- Google 的 NQ-Open 文档声明数据为 CC BY-SA 3.0，不能把代码仓库的 Apache 2.0 当作数据许可。[NQ-Open](https://github.com/google-research-datasets/natural-questions/blob/master/nq_open/README.md)
- TriviaQA 官网明确指出华盛顿大学不拥有问题和文档的版权；不能仅据可下载就认定可统一再分发。[TriviaQA](https://nlp.cs.washington.edu/triviaqa/)
- BEIR 与 DuReader 仓库的代码许可不能自动推导各组成语料的许可。DuReader-Retrieval 的数据下载/格式页面涉及报名，实际获取条件与数据使用条款待核验。[BEIR](https://github.com/beir-cellar/beir)、[DuReader-Retrieval](https://github.com/baidu/DuReader/tree/master/DuReader-Retrieval)

尚待验证：实际抽样及分层清单；UltraRAG HotpotQA 整体支持覆盖与上游转换原因；MuSiQue 转换版是否完整合并别名、answerable 分布；题目证据与选定 Wiki 版本的对应；中文下载条款及实际 schema；导入后 chunk 映射；公开集运行成本与实际分数。本次只完成外部资料、API 元数据和五类 JSONL 首行检查，没有执行这些后续实验。
