# HotpotQA 开发集与留出集：200题

> **已归档：权重study200的历史说明（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](../README.md)；历史状态不代表当前状态。

从与 hotpotqa50 相同的官方 validation 原始文件确定性抽取新200题。原输入 SHA-256 为 `c20b638ca82b21d04fe12e14ff417ad05153d4d215a65de54497fca4e972f7c6`，HF revision 为 `1908d6afbbead072334abe2965f91bd2709910ab`，文件为 `distractor/validation-00000-of-00001.parquet`。

- development：100题，bridge/comparison 各50题，991份完整候选段落文件。
- holdout：100题，bridge/comparison 各50题，989份完整候选段落文件。
- 排除旧 hotpotqa50 的来源 ID 和规范化问题，并在新两组之间去重。规范化为 NFKC、casefold、仅保留字母数字；不声称已经做过语义近似问题或实体级隔离。
- 每组分别合并全部候选材料，保留干扰段落，固定文件/语料指纹；两组语料并非相同，绝对分数不能直接相减解释为优化效果。对照只在同一留出集、同一语料内进行。
- 这是本地 shared-candidate-subset，不是官方 distractor 或 fullwiki 榜单条件。公开来源映射不等于人工批准。

`study.json` 保存划分、排除集指纹、子套件全部文件哈希、预先确定的四个融合权重和选取规则；`study.sha256` 校验该协议。两个子目录内的 `SOURCE.md` 保留署名与许可。

本次只调整 BM25 权重（0.25、0.5、1、2），固定 Vector 权重1、RRF k=60及其他控制项。选取顺序为 CompleteHit@5、Coverage@5、Hit@5；相同成绩优先保留原参数，再选变化较小者。开发集复用真实两路召回工件和生产 `RankFusion`，不为每个权重重新调用生成模型。

完整流程示例（原始 JSON 无需 pyarrow；Parquet 需要可选 pyarrow）：

```bash
scripts/rag-eval.sh study prepare /path/to/hotpot_dev_distractor_v1.json /tmp/hotpot-study \
  --exclude-dataset evaluation/hotpotqa50 --development-count 100 --holdout-count 100 \
  --source-url '<官方固定来源>' --revision '<固定版本>'

# 上传两个子目录并等待 Kafka 入库；分别 check 到全部 READY。
# 完成构建后，实际实验期间保持构建产物固定。
scripts/rag-eval.sh run /tmp/hotpot-study/development /tmp/hotpot-development-001 \
  --spring.config.additional-location=file:/path/to/evaluation.properties \
  --ai.rag.eval.baseline=true --ai.rag.eval.recall-strategy=HYBRID \
  --ai.rag.eval.support-enabled=false --ai.retrieval.bm25-weight=1 \
  --ai.retrieval.knn-weight=1 --ai.retrieval.rrf-rank-constant=60

scripts/rag-eval.sh study tune /tmp/hotpot-study /tmp/hotpot-development-001 /tmp/hotpot-selection-001
scripts/rag-eval.sh study validate /tmp/hotpot-study /tmp/hotpot-selection-001/selection.json \
  /tmp/hotpot-holdout-001 \
  --spring-arg=--spring.config.additional-location=file:/path/to/evaluation.properties
```

真实运行需要已有部署配置及 `AI_RAG_EVAL_IDENTITY`。选择产物记录开发工件、协议和网格哈希、选择时间、代码版本与参数。在第一次留出运行前，套件根目录写入 `holdout-opened.json`。此后 `tune` 拒绝对同一套件重新选参；`validate --resume` 只允许相同选择和输出目录，复用完整且校验通过的运行，部分回答不覆盖。该约束是本地实验流程保护，不是针对有文件写权限操作者的安全隔离。

与 matrix 一样，validate 进程成功表示实验完成；门禁结果读取 `validation.json` 的 gateStatus。CI 使用 compare 命令执行门禁，FAIL/INCONCLUSIVE 返回非零。显式允许的配置差异仅限融合权重或 RRF 常数，预算、TopK、身份、题集、模型和其他参数仍需一致。

```bash
scripts/rag-eval.sh compare /tmp/hotpot-holdout-001/baseline /tmp/hotpot-holdout-001/candidate \
  /tmp/hotpot-gate-001 --policy evaluation/policies/no-quality-drop.json \
  --allow-config-change retrieval.bm25Weight
```

本次真实执行、失败候选及验证证据见[实验记录](../experiments/2026-10-27-development-holdout.md)。
