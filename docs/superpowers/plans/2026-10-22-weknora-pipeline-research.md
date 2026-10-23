# WeKnora Pipeline 源码核验笔记

核验日期：2026-10-22（Asia/Shanghai）。用途：为 yeRAG 的 Java Pipeline 与评测证据设计提供可追溯参考。

## 基线与结论范围

- **事实**：本次通过已连接的 GitHub 工具读取 `Tencent/WeKnora` 的 `main`，随后把所有源码读取固定到完整 SHA **`92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e`**；这代表本次查询时的 main，不承诺之后不变。[分支查询入口](https://api.github.com/repos/Tencent/WeKnora/branches/main)、[固定提交](https://github.com/Tencent/WeKnora/commit/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e)。
- **事实**：本地 WeKnora 工作区 `/home/zyh/projects/WeKnora` 的分支为 `main`，HEAD `aaa1accebdf673b9603aaa555c5610a8fd7727d9`；检查开始时存在未跟踪文件 `packages/dsh-weknora/LEARNING_PLAN_CN.md`，本任务未改动它。本笔记保存在 yeRAG，以上 SHA 不代表 yeRAG 的 HEAD。证据命令：`git branch --show-current`、`git rev-parse HEAD`、`git status --short`。
- **核验方式**：阅读固定提交的官方文档、管线编排器、类型、插件与追踪包装器；没有运行 WeKnora 后端、模型或检索服务，也没有执行 Go 测试。以下“事实”表示静态源码可证，“推断”表示据源码作出的工程判断，“未核实”明确保留未知。
- **结论**：值得借鉴的是职责划分、分离状态、并发分支归并、显式降级和阶段追踪。不能据此声称 WeKnora 已保留用户表格要求的每个阶段的完整、可重放快照；当前追踪混合了阶段耗时、模型输入输出、有限候选预览与局部诊断。[编排器][orchestrator]、[重排插件][rerank]、[追踪摘要][retrieval-observation]。

## 1. “阶段顺序”和 next 链是两层编排

**事实**：`KnowledgeQA` 按请求特性构造事件列表；`KnowledgeQAByEvent` 再逐个 `Trigger`。典型 RAG 路径为：可选历史 → 记忆召回 → 查询理解 → 并行搜索 → 重排 → 可选网页抓取 → 合并 → TopK → 可选数据分析 → 消息组装 → 流式生成。无检索范围、无 Web 搜索时使用较短的聊天路径。[编排器][orchestrator]

**事实**：`EventManager` 的 `next()` 只进入**同一事件中**的下一个插件，不负责从“重排阶段”跳到“合并阶段”。插件按注册顺序形成嵌套调用；插件可以在 `next()` 前或后处理。`QUERY_UNDERSTAND` 挂查询理解与实体抽取；`CHUNK_RERANK` 挂重排、Wiki 加权、记忆亲和加权。Wiki 插件先执行 `next()` 再调整结果，说明不能把注册顺序简单理解成所有业务语句的执行顺序。[插件框架][framework]、[容器注册][container]、[Wiki 加权][wiki]

**yeRAG 建议（推断）**：首版使用明确的有序阶段列表即可；确实出现“同一个阶段需要可插拔前后处理”时再引入责任链，避免同时维护两套编排语义。

## 2. 请求、结果、运行时分离

| 层 | 已核实职责 |
| --- | --- |
| `PipelineRequest` | 原问题、知识范围、阈值、TopK、模型、Prompt、开关等请求配置 |
| `PipelineState` | 改写、意图、历史、Search/Rerank/Merge 三级结果、组装正文、诊断等中间态 |
| `PipelineContext` | EventBus、assistant/user 消息 ID 等执行句柄 |

**事实**：三个类型组合成 `ChatManage`。源码注释把 Request 定义为不可变配置，但 Go 结构体本身不提供语言级不可变约束；不能等同 Java 不可变 record。`Clone()` 显式复制并行搜索需要的配置和中间字段，并省略运行时句柄；它也不是包含所有历史结果的通用快照。[状态类型][state]

**yeRAG 建议（推断）**：Request 用不可变值对象；阶段结果独立保存；运行时单独持有取消、截止时间、追踪和输出端。并发召回分支返回结果值，由父阶段集中写 State，避免依赖复制一个巨大可变 Context。

## 3. 并行检索的边界与失败语义

**事实**：`PluginSearchParallel` 为 chunk/entity 两路各构造 `ChatManage.Clone()`，使用 `RunParallel` 等待分支结束后再集中合并和去重。分支的空召回不是硬错误；若任一路有结果，保留结果并记录其他分支错误。归并后为空且 chunk 分支有硬错误，则返回该错误；否则返回 `ErrSearchNothing`。因此“任一检索失败就整轮失败”和“所有异常都当空召回”都不是准确描述。[并行检索][search-parallel]、[并行工具][common]

**事实**：chunk 路径内部又并行运行 KB 与 Web 检索。KB 失败且没有其他结果会返回搜索错误；KB 部分失败但已有结果则继续并记录告警。这里是检索阶段内部并发，不能理解为全部 Pipeline 阶段一起并发执行。[搜索插件][search]

**yeRAG 建议（推断）**：给每路输出 `status + candidates + error + elapsed`，再由融合策略决定部分成功是否可接受。各路耗时和全量候选清单是 yeRAG 应显式补足的评测证据，不应只保存融合后的候选数。

## 4. 重排失败降级与“模型把候选筛空”有区别

**事实**：当前 `rerank.go` 插件已调用共享的 `internal/reranking`；未配置模型、模型无法加载、模型调用出错可以继续使用检索结果。模型成功但按阈值最终筛空时，插件返回 `ErrSearchNothing`，由编排器进入兜底。[重排插件][rerank]、[共享重排][reranking]

**事实**：共享实现先使用原阈值；全部未通过且阈值高于 0.3 时，用 `max(原阈值 × 0.7, 0.3)` 对**已有模型分数重新过滤**，不是再调用一次模型；仍为空时，最佳候选达到兜底下限才保留 top1。普通下限是 0.15；用户显式圈定文档或标签时使用负无穷，允许保留负分 top1。随后还有复合分、FAQ 加权、MMR 等选择。不要直接沿用文档中“显式范围下限为 0”的旧说法。[共享重排][reranking]

**事实**：`RerankDiagnostics` 与 span 输出记录 outcome、原始/有效阈值、数量等；重排插件的 passage/结果预览最多 25 条、模型分最多 50 条，正文预览限长。另有图片候选诊断，但只采样最多 10 个图片向量结果。它们有助诊断，但不是每个普通候选均有完整淘汰原因。[重排插件][rerank]、[追踪摘要][retrieval-observation]、[选择诊断][selection-observation]

## 5. 合并、TopK 与 Token 预算不能混为一谈

**事实**：合并优先取重排结果；没有重排结果时按检索分取 TopK，再保留受保护的图谱结果。随后去重、注入历史引用、解析父块、按文档和类型合并区间、补 FAQ 答案、扩展相邻块、再次合并和去重。之后独立的 `FILTER_TOP_K` 再恢复确定性排序并筛选；有额外保留策略的图片可超出普通 TopK。[合并][merge]、[TopK][top-k]

**事实**：State 的 `RetrievalTruncation` 记录第一次候选集合缩减的阶段及该阶段输入数量，后续更窄的切片不会覆盖它。这个数量是管线已召回候选数，不能说成知识库全部命中数量。[状态类型][state]

**事实**：`INTO_CHAT_MESSAGE` 渲染上下文、附件和引用信息并写入 `UserContent`；输出日志有正文长度等信息。最终发给模型的消息还会经过 modelcontext 的来源别名编码，所以这个阶段的中间文本也不能自动等同最终模型请求。[消息组装][into-message]、[流式生成][stream-plugin]

**未核实**：本次检查没有证明存在“按目标生成模型 tokenizer 精确计数、预留回答预算、逐片段记录因 Token 不足而删除”的统一机制。TopK 控制候选数；重排 passage 字符限制控制重排模型输入；二者均不能证明生成上下文满足模型 Token 上限。[TopK][top-k]、[共享重排][reranking]、[消息组装][into-message]

## 6. 追踪覆盖度与异步生成生命周期

| 环节 | 当前已核实证据 | 不能据此宣称 |
| --- | --- | --- |
| 阶段编排 | `pipeline.<event>` span，事件、session 与阶段耗时/错误 | 每阶段自动存储完整输入输出快照 |
| 检索/重排 | 候选数量、分数组合、限量预览、重排诊断 | 所有候选始终可完整重放、每项都能查到淘汰原因 |
| 上下文 | 组装文本日志、消息渲染内容、模型请求消息 | 已按 tokenizer 保存每片段 Token 分配证据 |
| 模型调用 | 启用 Langfuse 时记录模型、参数、消息、输出、usage、首个非空输出时间、finish reason | 任意部署均启用追踪，或供应商始终返回 usage |

**事实**：流式生成插件启动后台读取后返回；当前编排器**明确跳过 `CHAT_COMPLETION_STREAM` 的阶段 span**，避免父 span 提前结束。实际 `chat.completion.stream` generation 在模型包装器里开始，读取上游通道并累积输出，通道关闭后才 Finish。首个非空 thinking 或 answer 都会触发 completion-start，所以该指标不严格等于“用户看到第一个答案字符”的耗时。[编排器][orchestrator]、[流式生成][stream-plugin]、[模型追踪包装][model-tracing]

**文档漂移（事实）**：同提交文档仍描述流式阶段 span 提前结束，且重排章节仍对应旧代码结构；本次以固定提交真实源码为准。[官方 Pipeline 文档][pipeline-doc]、[编排器][orchestrator]、[重排插件][rerank]

**未核实**：本次没有部署 Langfuse，没有检查实际导出的 trace、采样/脱敏配置、数据保留期限和中途断流时每种供应商的终态表现。不能把静态埋点存在写成线上证据已可靠留存。

**yeRAG 建议（推断）**：Pipeline 阶段返回可等待的完成句柄；生成阶段在真实结束、失败、超时或取消后才完成。分别记录首个模型输出、首个答案输出与完整耗时；阶段证据清单独立于遥测供应商存储，以便评测稳定关联。

## 7. 取消优先于空召回

**事实**：编排器在阶段触发后先检查 `ctx.Err()`，再判断 `ErrSearchNothing`；用户取消不能变成“没找到资料”并写入兜底答案。`ErrSearchNothing` 本身走固定文案或模型兜底，不按普通阶段失败处理。[编排器][orchestrator]

**事实**：生成插件的消费循环监听 `ctx.Done()`；但模型追踪包装器使用通道 range 和转发，需要上游及时结束、下游及时消费才能 Finish。因此本次只能证明主要路径有取消传递与终态处理，不能证明所有模型实现和所有阻塞窗口均没有泄漏。[流式生成][stream-plugin]、[模型追踪包装][model-tracing]

**yeRAG 建议（推断）**：显式终态 `SUCCEEDED / NO_EVIDENCE / FAILED / CANCELLED / TIMED_OUT`，取消与超时不得被空结果兜底覆盖；对并发召回、模型流、输出缓冲和追踪收尾分别验证取消。

## 对 yeRAG 方案的最小要求

以下为设计建议，不是 WeKnora 已实现事实：每次运行保存配置/Prompt/模型/索引版本；每路召回保留全量候选 ID 与原始分数；重排保留前后顺序和逐项决策；上下文保存实际入模片段、来源、截断区间与 Token；生成保留模型、首答案耗时、总耗时与终态。观测页面可展示采样预览，但评测用证据应有完整的版本化工件，且必须标明是否采样或缺失。

[pipeline-doc]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/website-docs/02-architecture/04-rag-pipeline.md
[orchestrator]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/session_knowledge_qa.go#L674
[framework]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/chat_pipeline.go
[container]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/container/container.go#L557
[wiki]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/wiki_boost.go#L42
[state]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/types/chat_manage.go
[search-parallel]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/search_parallel.go#L97
[common]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/common.go#L216
[search]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/search.go#L61
[rerank]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/rerank.go
[reranking]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/reranking/rerank.go
[selection-observation]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/reranking/observability.go
[retrieval-observation]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/tracing/langfuse/retrieval_obs.go
[merge]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/merge.go#L44
[top-k]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/filter_top_k.go
[into-message]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/into_chat_message.go
[stream-plugin]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/application/service/chat_pipeline/chat_completion_stream.go#L55
[model-tracing]: https://github.com/Tencent/WeKnora/blob/92dfa6a1eef2be18df81c2b5fe23bc43e6f3783e/internal/models/chat/langfuse_wrapper.go#L53
