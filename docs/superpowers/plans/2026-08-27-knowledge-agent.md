# Knowledge Agent V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unconditional RAG chat pipeline with an OpenAI-compatible tool-calling agent that can invoke the existing permission-aware hybrid search and otherwise answer directly.

**Architecture:** A typed `KnowledgeAgentService` owns the bounded tool loop. `DeepSeekClient` exposes non-streaming decision calls and streaming final-answer calls, while `KnowledgeSearchTool` is the only registered tool and injects the authenticated user ID outside model-controlled arguments. `ChatHandler` delegates orchestration, forwards status/chunk events, persists only complete final answers, and retains a fixed-RAG fallback.

**Tech Stack:** Java 17, Spring Boot 3.4, WebClient/Reactor, Jackson, JUnit 5, Mockito, MockWebServer-compatible HTTP fixtures.

---

### Task 1: Agent configuration and protocol types

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/AiProperties.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/model/AgentMessage.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/model/ToolCall.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/model/AgentDecision.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/AgentEventListener.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/config/AiPropertiesTest.java`

- [ ] Write a binding/defaults test asserting agent enabled, fallback enabled, three calls, query/topK/result limits, and 30/10/60 second durations.
- [ ] Run `mvn -q -Dtest=AiPropertiesTest test` and confirm RED because `Agent` configuration is absent.
- [ ] Add the nested `AiProperties.Agent` configuration and immutable protocol records for system/user/assistant/tool messages, tool calls, decisions, and event callbacks.
- [ ] Re-run the focused test and confirm GREEN.

### Task 2: Permission-safe knowledge search tool

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/tool/AgentTool.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/tool/ToolExecutionContext.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/tool/ToolExecutionResult.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/tool/KnowledgeSearchTool.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/agent/tool/KnowledgeSearchToolTest.java`

- [ ] Write tests for schema, default/clamped topK, blank/oversized query rejection, authenticated user injection, result truncation, references, empty results, and prompt-injection content being placed only in `untrusted_content`.
- [ ] Run the focused test and confirm RED because the tool types do not exist.
- [ ] Implement strict Jackson argument parsing and delegate only to `HybridSearchService.searchWithPermission(query, context.userId(), topK)`.
- [ ] Serialize bounded JSON observations with reference number, file name, MD5, score, and untrusted content; return reference mappings separately for the WebSocket layer.
- [ ] Re-run the focused test and confirm GREEN.

### Task 3: Tool registry and bounded agent loop

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/AgentLlmClient.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/AgentToolRegistry.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/KnowledgeAgentService.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/agent/AgentRunResult.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/agent/KnowledgeAgentServiceTest.java`

- [ ] Write tests for direct answer, one/multiple tool calls, unknown tool, malformed arguments, tool error observation, three-call cap, final answer streaming, status events, and fallback-disabled failure.
- [ ] Run the focused test and confirm RED because the orchestrator is absent.
- [ ] Implement an exact-name registry with no reflective bean invocation.
- [ ] Implement the loop: build messages, request a decision, append assistant/tool messages, execute serially, and after the configured cap request a forced final answer without tools.
- [ ] Apply total timeout and emit only public status/chunk events; keep tool observations in the current run.
- [ ] Re-run the focused test and confirm GREEN.

### Task 4: OpenAI-compatible DeepSeek tool protocol

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/client/DeepSeekClientTest.java`

- [ ] Write request/response tests for `tools`, `tool_choice`, assistant `tool_calls`, tool-role messages, normal final decisions, SSE chunks, `[DONE]`, explicit completion, and protocol errors.
- [ ] Run the focused test and confirm RED against the legacy RAG-only client.
- [ ] Make `DeepSeekClient` implement `AgentLlmClient`, reuse the configured model/generation fields, and add typed request serialization.
- [ ] Parse non-streaming decisions and streaming final answers with WebClient timeouts; signal completion explicitly instead of using response-silence heuristics.
- [ ] Keep the legacy `streamResponse` entry point until fallback extraction is complete.
- [ ] Re-run the focused test and confirm GREEN.

### Task 5: Fixed-RAG fallback extraction

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/RagFallbackService.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/RagFallbackServiceTest.java`

- [ ] Write a failing test proving fallback performs permission search, builds bounded references/context, streams chunks, and returns the final response/reference map.
- [ ] Extract the old search/context/client behavior from `ChatHandler` without changing its public response semantics.
- [ ] Re-run the focused test and confirm GREEN.

### Task 6: WebSocket chat integration and cancellation

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/handler/ChatWebSocketHandler.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerAgentTest.java`

- [ ] Write tests proving status JSON is additive, chunks/completion remain compatible, only final user/assistant messages persist, references update from tool results, Agent failure invokes fallback when configured, and stop/close cancel active execution.
- [ ] Replace unconditional search with `KnowledgeAgentService`; use one tracked Reactor `Disposable`/future per WebSocket session and one completion path.
- [ ] Remove polling threads and response-silence completion detection.
- [ ] Add a disconnect callback that cancels the active run before clearing references.
- [ ] Re-run focused tests and confirm GREEN.

### Task 7: Configuration, security regression, and full verification

**Files:**
- Modify: `src/test/resources/application.yml`
- Modify: `README.md`
- Test: all new and existing tests

- [ ] Add documented `ai.agent` defaults to test/example configuration without adding secrets.
- [ ] Add README notes describing direct answers, knowledge search, status events, limits, fallback, and model `tool_calls` compatibility.
- [ ] Run prompt-injection, permission-injection, tool-limit, malformed-protocol, and cancellation focused tests.
- [ ] Run `mvn test` and require zero failures/errors.
- [ ] Run `git diff --check` and inspect `git status --short` to ensure only intended files changed.
