# 公开数据集评测

> **已归档：公开数据适配的历史说明（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](README.md)；历史状态不代表当前状态。

当前实现：HotpotQA 与 UltraRAG JSON/JSONL 导入、来源快照验证、多参考答案保留、公开来源单独报告、语料范围与 ACL 交集、分批语料预检。HotpotQA 支持原始 JSON 和官方 Hugging Face Parquet 表示；Parquet 导入可选安装 `pyarrow`，JSON/JSONL 仅需 Python 标准库。

企业集 `phase1` 保持 schema v2、50题和原评分口径。公开集使用 schema v3，不能通过修改 schemaVersion 将企业草稿变成公开标签：加载器要求来源快照、问题/答案对应关系，并对 HotpotQA 所有候选段落及支持句进行逐项校验。

## 题目与语料

首批数据目录为 `hotpotqa50`。从官方 HotpotQA dev distractor 的完整 validation 集按类型抽 bridge/comparison 各25题；种子 `20261026`，每层按 `SHA-256(seed:id)` 排序取前25题。选中题目的全部候选段落去重合并为一个共享语料，保留干扰段落。

这改变了官方每题10段候选的条件，也没有索引 fullwiki，因此本套件叫 **shared-candidate-subset**。分数只代表这个冻结的本地子集，不能当作官方榜单结果。

每个数据目录包含：

- `dataset.json`：来源 URL/revision、原输入和选中快照校验和、抽样算法与 ID、语料版本和文档指纹。
- `source.json`：选中的原始记录快照；HotpotQA 的 HF 列式字段转为原始列表表示，同时保留原始 ID。
- `cases.jsonl`：答案备选、支持句序号和必要证据；答案不会附加到可检索正文。
- `documents/`：全部候选原文，按标题与正文哈希去重；同名异文保留为不同文件。
- `SOURCE.md`：来源署名、许可证和转换说明。

本地文件验证通过只代表映射和文件一致。`PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED` 不等于人工 `APPROVED`，也不代表程序鉴定了远端作者身份；来源 URL/revision 与上游下载校验和需要一起保留。

## 导入格式

从已有原始 HotpotQA JSON 导入：

```bash
scripts/rag-eval.sh import hotpotqa /path/to/hotpot_dev_distractor_v1.json \
  /tmp/hotpotqa50 --count 50 --seed 20261026 \
  --source-url '<下载来源URL>' --revision '<下载版本或校验标识>'
scripts/rag-eval.sh validate /tmp/hotpotqa50
```

Parquet 采用同一命令，输入文件扩展名为 `.parquet`，运行的 Python 环境需安装 `pyarrow`。首批 HF 来源与固定 revision 见 `hotpotqa50/dataset.json`；未把27MB完整上游文件纳入源码，只保留所选记录和语料。

UltraRAG 通用 QA 包装导入：

```bash
scripts/rag-eval.sh import ultrarag /path/to/questions.jsonl /tmp/public-qa \
  --corpus /path/to/corpus.jsonl --dataset-name nq --language en \
  --source-url '<来源URL>' --revision '<固定版本>'
```

问题为 `id/question/golden_answers/meta_data`，语料为 `id/contents`。`golden_answers` 保留为 `referenceAnswers`，不会变成多个必答要点。通用包装没有统一的证据语义，所以不从 metadata 猜支持句，不生成占位证据，证据覆盖为 N/A；可不提供 corpus 做离线标签整理，但不能执行无语料的本地 RAG。

目前通用 UltraRAG 导入只处理基础文本 QA，遇到顶层 choices/image 字段会明确拒绝，不丢弃选项或图片继续评测。它启用独立 Judge，原生答案 scorer 为 `unavailable`；后续应为 NQ/TriviaQA、中文、长答案、选择题分别增加其规定的 scorer。不会把 HotpotQA 的英文规则套到所有数据集。支持单路召回对照但仍执行端到端生成；BEIR/qrels、DuReader 专用适配器、可变 K、纯检索跑分模式和 Agent/多轮评测尚未实现。

## 运行

使用隔离评测环境及真实身份。公共题集上传只需评测令牌；含受限资料的企业题集还需受限准备身份令牌。

```bash
# 在当前终端设置 YERAG_EVAL_TOKEN；不把令牌写到参数或源码。
scripts/rag-eval.sh upload evaluation/hotpotqa50 http://localhost:8081

# Kafka 消费、解析与向量入库完成后，使用与上传相同的真实身份。
export AI_RAG_EVAL_IDENTITY='id:<评测用户ID>'
scripts/rag-eval.sh check evaluation/hotpotqa50 /tmp/hotpot-check-001 \
  --spring.config.additional-location=file:/path/to/evaluation.properties
scripts/rag-eval.sh run evaluation/hotpotqa50 /tmp/hotpot-run-001 \
  --spring.config.additional-location=file:/path/to/evaluation.properties
```

v3 每次运行在开始和结束分别生成 ACL 约束下的全语料快照，每批最多100文件，每个套件最多1000个文件、100000个块。每个公共候选文件（包含干扰文档）都必须存在。支持句须完整映射到可见子块，或通过显式 `CONTIGUOUS_SOURCE_SPAN` 覆盖同一已验证原文中的全部范围；缺片段、归属不一致或原文位置歧义不算完整。它不是拼接子块伪造单块命中，判定与测试见[主说明](README.md)。暂不支持全量 Wikipedia。

查询通过可信 Runner 将 manifest 语料范围传入 Pipeline。BM25、KNN/legacy 共同使用“原 ACL AND fileMd5 范围”的预过滤，范围约束不能授予权限。普通聊天和模型工具参数没有新增身份或 ACL 参数。

部分 capture 在结束快照校验前保持 `corpusVerified=false`；只有结束语料/权限指纹一致时才确认该次公共运行。中断或变化会留下诊断，不会据部分快照报告有效证据覆盖。结束前后快照不能证明运行中没有发生又恢复的瞬时变更，因此仍需专用、冻结语料的评测环境。

## 评分与报告

`publicBenchmark` 与企业正式人审口径分开：

- HotpotQA 原生答案使用 `hotpotqa-answer-v1`：大小写、ASCII 标点、英文冠词、空白规范化，词频交集 F1，yes/no/noanswer 特殊规则。备选答案取最高分。
- v3 的 `answerStyle=short` 在生成前固定短答案提示；保存完整真实模型回答，直接对它评分。没有答案抽取模型，也不让 Judge 事后改写答案。
- 答案分母包含所选全部题，缺失/未完成回答计零，另报 completed/incomplete；Judge 失败不影响已完成答案的 EM/F1。
- `observedSupportingEvidence` 使用原公开支持句计算 Hit@5（任一必要单元）、Coverage@5（覆盖比例）和 CompleteHit@5（全部必要单元），注明有效分母。它不是完整相关集 Recall，也不是官方 supporting-fact F1 或 joint 指标。
- `nativeAnswer`、`deterministic`、`semantic`、`humanReview` 单独保留。公共标签没有虚构的人工批准；原人审汇总显示0时，应读取旁边的 `publicBenchmark`，不能混用两种分母。
- 工件按原有方式冻结并支持离线重算；归档包括 source 快照。Judge 使用独立请求，接收备选答案的 OR 语义。
- 支持显式 BM25 / Vector / Hybrid 对照、配对回归检查、独立回答支持度评审和延迟/实报 Token 汇总，命令与边界见[主说明](README.md)。公开短答案没有真实必答要点时，回答完整性为 N/A。
- `ragas.jsonl` 导出 `user_input/response/reference/retrieved_contexts`，仅包含已完成且上下文核验通过的回答；对应 ID、来源、备选答案和跳过原因见 sidecar。它是可选的跨工具入口，不代表已运行 RAGAS 或可直接跨数据集比较其分数。

官方规则来源：[HotpotQA](https://hotpotqa.github.io/)、[官方答案评分代码](https://github.com/hotpotqa/hotpot/blob/master/hotpot_evaluate_v1.py)。

## 本次验证

定向测试覆盖：公开来源映射篡改、答案别名与必要事实分离、缺标注 N/A、答案重复词与 yes/no、语料批次间权限变更、两路 ACL 与范围交集、Judge 失败独立、公开工件离线重算。真实服务运行状态另记 `hotpotqa50/verification-status.json`，不把测试替身的分数当作模型质量。

后续新增200题的开发/留出划分、来源范围校验与一次真实选参验证见 [study200](hotpotqa-study200/README.md) 和[实验记录](experiments/2026-10-27-development-holdout.md)。该候选在留出集未通过门禁，未作为已验证提升采纳。
