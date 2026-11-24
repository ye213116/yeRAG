# 前端回归

成果库另有 `tests/browser/artifacts.test.mjs`（Vite 19528），覆盖新建报告、保存失败保留草稿、请求幂等键、站内离开提示、列表失败重试、报告编辑、历史版本、HTML 转义导出、冲突保留编辑、失去来源权限及跨账号读取；还验证聊天 Markdown 成果链接和裸路径按钮均在应用内跳转，不附加 `.html`、不刷新页面。使用本地接口 fixture；真实保存事务与所有权由后端 H2/控制器测试覆盖。

`pnpm test` 验证请求取消、计划快照校验与任务终态；`pnpm test:browser` 使用 Chromium 验证真实 Vue 页面与状态管理。浏览器测试启动独立 Vite（19527）和本机 HTTP/WebSocket fixture，不使用线上模型或修改现有业务数据；覆盖任务列表展示与进度更新、标题纯文本渲染、计划停止/失败、历史记录、401/403、流式消息、断线重连、切换会话、迟到完成、停止和跨账号隔离。

要求 Node 22、已安装的 Chromium。通过 `BROWSER_EXECUTABLE_PATH=/path/to/chrome pnpm test:browser` 指定浏览器；也可自动发现系统 Chromium 或 Playwright 缓存。不要同时执行会重写前端文件的 lint/build，以免 HMR 干扰测试导航。

真实服务闭环从仓库根目录运行：

```bash
python3 scripts/local-e2e.py
```

默认连接前端 9527、后端 8081；可设置 `YERAG_E2E_FRONTEND` / `YERAG_E2E_BACKEND`。要求本地 `pai_smart` 的 mysql、redis、Kafka、ES、MinIO 及模型配置可用。脚本创建随机临时账号和私有文档，经过浏览器上传、解析检索、MinIO 预览下载、真实模型回答、多轮追问、会话恢复、权限隔离与取消持久化检查，最后删除自己的文档、账号、会话和缓存。报告写入独立 `/tmp/yerag-live-e2e-*` 目录；成功清理后删除账号凭据文件。

如清理失败，保留该目录，通过 `python3 scripts/local-e2e.py --cleanup /tmp/yerag-live-e2e-...` 重试。清理器仅接受本脚本格式的精确账号名；仍存在文档时拒绝删除账号。真实测试会产生模型调用，耗时结果只代表本地样本，不是容量或生产 SLO。

`python3 scripts/local-e2e.py --load`运行固定四用户并发协议，独立记录检索、首字和完成时延，门槛及结果说明见 `evaluation/README.md`。


Coding 委托任务另有 `tests/browser/coding.test.mjs`（Vite 19529），使用独立的
HTTP/WebSocket fixture，验证权限配置、任务提交、关闭页面后重新查看、Diff 纯文本显示、
确认前不发布、确认指纹及跨账号隔离。该测试不会执行真实容器、调用真实模型或创建真实 PR；
真实执行节点验收见 `docs/work-coding.md` 和 `CodingDockerIntegrationTest`。

Coding 现有独立侧边栏入口 `/coding`；未启用服务时仍显示入口与重试提示。
浏览器回归还覆盖入口跳转、按任务链接直接刷新、项目内任务选择、无授权仓库、服务未启用、
能力查询失败后重试，以及切回 Work 后的输入。

真实 Coding 闭环（会调用模型并保留标记为“本地 Coding 联调”的新会话、Workspace 和任务供审核）：

```bash
node --env-file=.env frontend/tests/live/coding.mjs
```

要求已启动前后端和通过 mTLS 接入的 Docker runner，管理员密码由本地环境提供。
为该账号注册 `demo` 仓库 `jenkins-docs/simple-java-maven-app`，使用已验证的 Java 17
基准 `bba641c3a0ff1be4fb4262bcef008bb437480032`，授予 READ / WRITE / EXECUTE。
该脚本仅接受此示例仓库；不要对业务仓库运行。它通过真实页面登录、在项目输入框自动创建 Workspace、
通过 WebSocket 提交新增 `greet` 方法与测试的任务，离开页面等待执行，再刷新核对
通过的 Maven 验证和 Diff。失败时请求取消仍在运行的任务，不创建 GitHub PR。
使用 `CODING_E2E_REPOSITORY_ID` 选择注册项，`CODING_E2E_OUTPUT` 指定报告与截图目录；
前后端地址沿用 `YERAG_E2E_FRONTEND` / `YERAG_E2E_BACKEND`。

时区回归使用真实 MySQL：配置 `CODING_MYSQL_TEST_URL` / `CODING_MYSQL_TEST_USER` /
`CODING_MYSQL_TEST_PASSWORD` 后运行 `mvn -Dtest=CodingTaskMySqlTest test`。
每项测试仅创建并删除随机测试库，验证 JDBC 为 Asia/Shanghai、数据库会话为 UTC 时，
新心跳和发布请求不会被误判过期，同时真正过期的任务仍能被识别。


工作台布局与后台 Work 回归：聊天浏览器 fixture 将运行状态放在服务端，切换会话或断开 WebSocket 后继续生成；覆盖并行回答、关闭页面再按链接重开、序列去重、会话改名、64px 主导航、深色外观、窄屏副栏及各会话的草稿和阅读位置。成果库侧栏通过布局上下文挂载，保留未保存编辑离开确认。图标由本地 Vite 编译，不依赖浏览器访问 Iconify。布局延迟加载避免直接打开已登录页面时的 router/store 初始化循环。

真实 Work 模型回归从仓库根目录运行（会保留标记为“本地工作台联调”的新会话，不修改已有线程）：

```bash
node --env-file=.env frontend/tests/live/workbench.mjs
```

脚本先验证切到 Coding 时回答仍在执行，再启动第二个 Work 回答，关闭整个 Chromium，等待两份完整答案持久化，重开浏览器按链接恢复并刷新，核对正文完全一致且没有重复回复。还生成桌面、深色和窄屏截图。失败时只停止自己的 requestId；不自动重跑失败模型请求。报告和截图默认写入忽略的 `.codex/integration/workbench/`，可用 `WORKBENCH_E2E_OUTPUT` 更改；浏览器临时登录目录在结束时删除。该检查需要真实模型，耗时及模型输出长度可能变化。

真实 MySQL 会话契约：设置 `WORKBENCH_MYSQL_TEST_URL` / `WORKBENCH_MYSQL_TEST_USER` / `WORKBENCH_MYSQL_TEST_PASSWORD` 后执行 `mvn -Dtest=MemoryWorkbenchMySqlTest test`。其随机测试库检查会话切换不取消后台回合、原会话完成写入、标题与所有权、用户顺序提取、启动中断恢复；不会更改业务库中的聊天记录。
