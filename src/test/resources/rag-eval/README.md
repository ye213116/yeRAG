# 合成评测样例

这些冻结工件由人工构造，只用于验证评测工具；不是实际模型或 ES 的质量评测结果，不包含企业数据。

同一条合成规则把“试用期不适用”放在第 300 个字符之后：

- `legacy-clipping`：召回和重排均保留该块，组装阶段裁掉例外，诊断应为 `CONTEXT_LOSS`。
- `budget-context`：最终模型请求保留例外，覆盖率为 1，诊断应为 `CONTEXT_RETAINED`。

`artifacts/` 的 `runId.json` 保留每个事件 data 的 SHA-256；修改工件必须同步更新事件 hash，否则评测会标为 `EVIDENCE_INCOMPLETE`。真实生成正确性、证据支持度需人工或另行配置的评审标注。

在仓库根目录运行：

```bash
scripts/rag-eval.sh src/test/resources/rag-eval/cases.jsonl src/test/resources/rag-eval/artifacts /tmp/yerag-eval-demo
```

输出 `results.jsonl` 和按 variant 聚合的 `summary.json`。同一 case 的 variant 用于原问题/改写、重排开关、旧裁剪/预算组装等单变量比较；固定样例只演示其中的裁剪比较。
