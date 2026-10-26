# 历史合成工件

本目录保留 Pipeline 初版的冻结 JSON，演示第 300 字之后的例外被裁掉；这些是人工构造的测试材料，不是真实 ES 或模型质量结果。

当前评测已收敛为必要证据单元、Hit@5/Coverage@5 与独立 Judge，不再运行旧 A/B/C 和相关度并行评分器。旧文件仅作历史或一次性迁移参考，不自动充当当前标注或评委结论。

当前入口见 [单链路评测说明](../../../../evaluation/README.md)。定向测试 `EvaluationFiftyCaseTest` 会生成使用真实 Pipeline 和 HTTP 客户端、模拟 ES/模型语义的 50 题运行，路径保存在 `target/phase1-latest-simulation.txt`。其工件可交给同一个离线报告命令重算，但不能解释为真实服务验收。
