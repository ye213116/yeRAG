# HotpotQA 50题本地评测集

> **已归档：首次50题接入快照（2026-10-27归档）**：本文保留历史条件与记录，后续不再维护。当前机制、结果、运行方法与工作计划统一见 [yeRAG评测主文档](../README.md)；历史状态不代表当前状态。

完整来源、抽样与许可见 [SOURCE.md](SOURCE.md) 和 [dataset.json](dataset.json)。固定从官方 HF HotpotQA validation/distractor 的7405题中抽取 bridge/comparison 各25题，种子20261026；500份候选段落共享检索，保留干扰资料。

本地环境为独立 Docker 依赖和评测账号 `id:1`。使用 standard 分词器、2048维 embedding、512字符分块、无 history、topK=5、改写关闭、生成与 Judge 温度0；生产映射未修改。实际请求与阶段配置保存在运行工件中。

2026-10-26 首轮真实评测已完成：

| 项目 | 结果 |
|---|---:|
| 原文预检 | 50/50 READY；500文件、1022块 |
| 完整生成 / Judge | 50/50；无失败 |
| 答案 EM | 64.00% |
| 答案 F1 | 68.33% |
| Hit@5 | 98.00% |
| 必要支持句 Coverage@5 | 82.67% |
| Judge 意见 | correct 39、wrong 11 |

50条结果离线重算逐条一致；原生答案指标与下载的 HotpotQA 官方评分代码逐题一致。全量后端测试238项，228通过、10跳过，失败与错误均为0；跳过为9项 opt-in MySQL 和1项真实 ES 测试，未宣称通过这些可选套件。真实评测本身使用本次专用 MySQL/ES 服务。

公开来源标签不冒充人工批准，企业人审指标与这里的公开指标分开。这个共享候选子库不是官方 distractor/fullwiki 条件，不能直接对照官方榜单；Coverage 也不是官方 supporting-fact F1。

- [机器验证记录](verification-status.json)
- [本机完整报告](../../target/hotpotqa50-run-20261026-001/report/report.md)
- [本机机器汇总](../../target/hotpotqa50-run-20261026-001/report/summary.json)
- [评测工具使用说明](../public-datasets.md)

`target/` 中的完整工件与本机运行配置不纳入源码；运行配置含私密凭据，不随公开题集分发。后续 BEIR/qrels、中文专用适配器等仍按分阶段方案推进。
