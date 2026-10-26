# yeRAG 第一阶段：单链路评测

本模块复用每题一次的 `RagPipeline.run(FIXED_QA)`，不拆 Pipeline，不执行相邻块扩展或双分支实验。当前范围是 Hit@5、Coverage@5、独立 Judge 和阶段证据诊断。

程序证据结果、模型语义判断和人工复核是三个独立输出。Judge 不能创建金标准、修改证据位置或覆盖程序指标。

## 1. 数据与事实标准

`phase1/` 包含 7 份公开可展示的**合成**资料与 50 题：fact 12、para 12、multi 8、conflict 3、cross-doc 5、无答案 6、ACL 4。

这些标注由 AI 起草，状态为 **DRAFT_REQUIRES_HUMAN_CONFIRMATION**，没有声称已由人审核。程序仍能输出观测到的覆盖结果，但没有对应人工确认记录时，正式指标分母为 0，而不是 0% 正确率。文件检查、ES 预检和人工审核不能相互替代。

- `dataset.json`：题集/语料版本、文档路径、MD5、SHA-256 和预期题数。
- `cases.jsonl`：唯一事实标注，包含 `id/type/question/referenceAnswer/keyPoints/evidenceUnits`。
- 每个必要单元的 `alternatives` 为等价原文位置；单元之间全部必要，单元内部满足一个即可。
- `unanswerable` 与 `acl` 的证据单元为空，不参加正例 Hit/Coverage。
- ACL 的 `forbiddenDocuments` 用于核对不可见前提；`forbiddenQuotes` 仅作答案中的精确泄漏检测，不发送给 Judge。

正文匹配使用 NFKC 和 Unicode 去空白，并将匹配位置映射回原始 UTF-16 坐标。不得拼接不同子块来伪造子块命中。原文哈希、来源映射和实际正文都要校验。

## 2. 命令行

所有命令从仓库根目录运行。默认先构建代码和依赖 classpath；准备好构建产物后，可设置 `RAG_EVAL_SKIP_BUILD=1` 进行不访问 ES/模型的离线报告。

仅检查文件完整性、题数及格式：

```bash
scripts/rag-eval.sh validate evaluation/phase1
```

### 准备测试语料

使用专用测试环境，保持语料、分块和模型配置固定，避免动态业务资料影响复现。通过现有账号/组织管理能力准备两个身份：

1. 评测身份：可见公开文档，不属于 `EVAL_RESTRICTED`。
2. 准备身份：有权向 `EVAL_RESTRICTED` 上传并读取受限资料。

从现有登录接口取得各自的令牌，通过环境变量注入，不把令牌写入命令参数或仓库。上传通过现有 `/api/v1/upload/chunk` 和 `/merge`，不直接写 ES：

```bash
# 在当前终端配置 YERAG_EVAL_TOKEN 和 YERAG_EVAL_SETUP_TOKEN 后运行。
scripts/rag-eval.sh upload evaluation/phase1 http://localhost:8081
```

公开文件使用评测令牌；受限文件使用准备身份令牌，`isPublic=false`、组织为 `EVAL_RESTRICTED`。上传成功不代表 Kafka 解析和索引已经完成，必须继续做真实预检。脚本不会自动创建账号、组织或索引。

### 真实预检与运行

配置部署所需的 MySQL、Redis、ES、MinIO、Kafka、Embedding 和生成模型连接。脚本不读取或 `source .env`；由部署工具把所需环境变量安全注入进程。

```bash
# 值应是你实际准备的身份，不要照抄占位符。
export AI_RAG_EVAL_IDENTITY='id:<评测用户ID>'
export AI_RAG_EVAL_SETUP_IDENTITY='id:<准备用户ID>'

scripts/rag-eval.sh check evaluation/phase1 /tmp/yerag-check-001 --spring.profiles.active=dev
scripts/rag-eval.sh run evaluation/phase1 /tmp/yerag-run-001 --spring.profiles.active=dev
```

输出目录必须是新的或空的，避免覆盖旧结果。评测启动器仅在自己的上下文使用随机回环 HTTP 端口，关闭自动 DDL/SQL 初始化、Kafka 消费、MCP 与定时任务，并排除账号/组织/ES 初始化器和旧 Memory 迁移；不会顺带创建索引或处理其他用户的后台任务。完成后关闭应用上下文；实际上传解析仍由正常运行的服务处理，生产启动路径不受这些设置影响。

`check` 用与检索相同的 ACL 分页扫描指定基准文件，排除向量字段，限制最多 100 个文件、10000 个块。超时、分片失败和越权结果拒绝接受，不将不完整扫描当作空语料。

正例检查必要证据是否存在于可见的原始子块；ACL 负例用准备身份确认已索引，再确认评测身份不可见。缺少准备身份时，4 道 ACL 题会预检失败。无答案题必须由人工基于固定语料确认，预检不会用“搜索没找到”代替确认。

`run` 每道预检通过的题恰好执行一次完整 Pipeline，单轮、无 history、topK=5；生成温度固定为 0，Judge 使用独立请求及温度 0。改写、重排和预算沿用当前主链路配置并记录。不预设需要改变生产默认值。

运行前后检查基准语料/权限指纹；中断、失败或变化明确记录。单题失败继续其余题，按题更新 `capture.partial.json`。不自动续跑；可以用 partial 文件生成未完成报告，重新执行使用新目录。

## 3. 指标与诊断

对有答案题，Top5 在**重排后、合并与组装前**冻结：

- Hit@5：至少覆盖一个必要单元记 1，否则 0。
- Coverage@5：已覆盖单元数 / 必要单元数。
- NONE / PARTIAL / FULL 区分未命中、部分、全部。

3 个单元找到 2 个时为 `Hit=1, Coverage=2/3, PARTIAL`。汇总为有效题的逐题均值，暴露每项分母和排除理由；不称为穷举相关块的 Recall。

Top5 保存 owner、fileMd5、chunkId、ES index、原始 hit ID、原文与 SHA-256、分数类型/值及名次，后续裁剪不覆盖它。程序分别检查：

1. `retrievalPool`：召回/融合池。
2. `top5`：重排后的交付窗口。
3. `merged`：合并后正文。
4. `assembled`：组装后实际保留范围。
5. `wire`：完整正文确实出现在实际发送请求中后，才确认入模。

候选池 FULL、Top5 PARTIAL 会记录排序/窗口丢失；组装前存在、之后丢失记录 ASSEMBLY_LOSS，并保留组装决策。来源元数据与正文不一致时不确认入模。证据已发送不等于模型已正确使用。

Judge 默认使用生成模型，可通过 `ai.rag.eval.judge-model` 独立指定同一兼容服务上的模型；不另建一套 URL/API Key 配置，实际请求参数保存在子工件。Judge 只评答案：correct、partial、wrong，遗漏要点、无上下文支持的陈述、矛盾陈述、是否需要人工复核及理由。无依据与事实错误分开保存。标准冲突不能由 Judge 自行修订。

Judge 失败、无效 JSON、截断等独立记录；不抹掉已经有效计算的 Top5 指标。完整固定拒答也接受评审，不必为了评分另调用生成模型。

## 4. 冻结结果与人工复核

运行目录包含：

```text
preflight.json             # 哈希校验的预检与原文定位
capture.json               # 题集/代码/配置与每题运行索引
capture.partial.json       # 中断前已保存的索引
artifacts/<UUID>.json      # question/pipeline/model/judge 工件
dataset/                  # 实际使用的题集和资料副本
report/results.jsonl       # 三层结果分开输出
report/summary.json        # 分母、覆盖状态、Judge 结果与排除数量
report/report.md           # 人可读的逐题索引
report/review-queue.jsonl   # 失败/争议题与按 ID 固定抽查的最多5道通过题
```

离线重新生成报告，使用新输出目录：

```bash
scripts/rag-eval.sh report /tmp/yerag-run-001/dataset \
  /tmp/yerag-run-001/preflight.json /tmp/yerag-run-001/capture.json \
  /tmp/yerag-report-001 /tmp/yerag-reviews.jsonl
```

复核文件由实际复核人维护，程序和 Judge 均不会生成“已批准”的记录。以下是字段示意，只有真正完成复核后才填写实际值：

```json
{"reviewId":"唯一ID","type":"LABELS","datasetHash":"validate输出的哈希","caseId":"*","state":"APPROVED","reviewer":"实际复核人","reason":"实际核对内容","createdAt":"实际ISO时间"}
```

`caseId=*` 表示审核整套题；单题可以使用自己的 ID。争议使用 `state=DISPUTED`；单题记录优先于全局记录。更新同一题/运行的同一类复核时，追加新记录并用 `previousReviewId` 指向上一条，禁止覆盖历史。

修改模型判断另追加 `type=ANSWER`，绑定实际 `datasetHash/caseId/pipelineRunId/answerSha256`，填写 `verdict=correct|partial|wrong|disputed`、复核人、理由和时间。它不会自动批准事实标注，也不会改写 `semantic.assessment` 中的原 Judge 判断。

`deterministic`、`semantic`、`humanReview` 分开输出。标注待确认/争议、生成不完整和待处理标准冲突不进入对应正式指标。精确 ACL 泄漏单独报告并进入人工复核，不由 Judge 掩盖。

### 修订标注

保留旧版本，修改题集版本并对同一语料重新预检；把新题集和新 preflight 配合旧 capture 输出到新报告目录。程序校验语料指纹、身份和原问题一致，重新定位并计算指标。旧 Judge 被标为 `LABEL_VERSION_CHANGED_REJUDGE_REQUIRED`，旧人工批准不沿用；旧报告和工件保持原样。

旧 JSON/JSONL 可一次性转换为待补标模板：

```bash
scripts/rag-eval.sh convert /path/to/legacy-cases.json /tmp/cases-draft.jsonl
```

模板需要人工映射文件、摘录必要原文并审核，不把整块自动当作最小必要证据，不导入历史 Judge 分数或运行 ID。

## 5. 验证范围

`EvaluationFiftyCaseTest` 使用真实 Pipeline、Trace 和 HTTP 客户端，但 ES 和模型语义为测试替身，验证每题恰好一次、50 行不丢失和单题失败隔离。生成的 `target/phase1-latest-simulation.txt` 指向模拟运行目录；这是程序验证，不是真实质量评测。

旧 6 题及 300/600/full 历史报告的文件核对见 `legacy-review.json`；原始上传资料及当前索引尚未核验，没有复用历史分数。

真实验收仍需要实际服务、正确身份、受限资料入库和人工标注确认。真实运行与模拟测试分别记录，不互相替代。

### 本次执行记录（2026-10-26）

- `mvn -q test`：232 项，222 通过，10 跳过，失败/错误均为 0。跳过为 9 项真实 MySQL 和 1 项真实 ES 测试，未提供其测试环境变量。
- `scripts/rag-eval.sh validate evaluation/phase1`：50 题文件/哈希校验通过。
- 50 题模拟流程：每题一次 Pipeline、50 次生成、50 次 Judge；故意注入 1 次无效 Judge JSON，其余结果不丢失。离线重算 50 条确定性结果逐条一致。
- 旧 6 题一次性迁移模板生成通过；Shell/Python 语法、文档相对链接与 `git diff --check` 通过。
- 真实预检因未提供 `AI_RAG_EVAL_IDENTITY` 在接触服务前停止；真实 50 题、实际 ES/供应商与人工标注确认仍未验收。

机器可读记录见 [verification-status.json](verification-status.json)。本次未提交、推送或执行数据库迁移。
