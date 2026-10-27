# 前端回归

`pnpm test` 验证请求取消；`pnpm test:browser` 使用 Chromium 验证真实 Vue 页面与状态管理。浏览器测试启动独立 Vite（19527）和本机 HTTP/WebSocket fixture，不使用线上模型或修改现有业务数据；覆盖历史记录、401/403、流式消息、断线重连、切换会话、迟到完成、停止和跨账号隔离。

要求 Node 22、已安装的 Chromium。通过 `BROWSER_EXECUTABLE_PATH=/path/to/chrome pnpm test:browser` 指定浏览器；也可自动发现系统 Chromium 或 Playwright 缓存。不要同时执行会重写前端文件的 lint/build，以免 HMR 干扰测试导航。

真实服务闭环从仓库根目录运行：

```bash
python3 scripts/local-e2e.py
```

默认连接前端 9527、后端 8081；可设置 `YERAG_E2E_FRONTEND` / `YERAG_E2E_BACKEND`。要求本地 `pai_smart` 的 mysql、redis、Kafka、ES、MinIO 及模型配置可用。脚本创建随机临时账号和私有文档，经过浏览器上传、解析检索、MinIO 预览下载、真实模型回答、多轮追问、会话恢复、权限隔离与取消持久化检查，最后删除自己的文档、账号、会话和缓存。报告写入独立 `/tmp/yerag-live-e2e-*` 目录；成功清理后删除账号凭据文件。

如清理失败，保留该目录，通过 `python3 scripts/local-e2e.py --cleanup /tmp/yerag-live-e2e-...` 重试。清理器仅接受本脚本格式的精确账号名；仍存在文档时拒绝删除账号。真实测试会产生模型调用，耗时结果只代表本地样本，不是容量或生产 SLO。

`python3 scripts/local-e2e.py --load`运行固定四用户并发协议，独立记录检索、首字和完成时延，门槛及结果说明见 `evaluation/README.md`。
