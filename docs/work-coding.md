# Work and Coding execution

Work and Coding share `KnowledgeAgentService`, `AgentLlmClient`, the tool registry,
user memory and trace infrastructure. `AgentMode` selects execution policy;
`SourceMode` independently restricts knowledge/web sources.

## Profile admission

Old `run(...)` calls remain Work calls. Each run resolves an immutable profile.
Tools are filtered before declaration and checked again before execution. A Work
request cannot use workspace tools, even with AUTO or a misconfigured allowlist.
Unknown tools require an explicit profile allowlist entry. Registered, approved
read-only MCP tools retain their existing Work behavior; Coding MCP access is opt-in.

`ai.agent.profiles.work` inherits existing agent budgets when overrides are absent.
`ai.agent.profiles.coding.enabled` defaults to false. Coding requires a server-created
task/workspace context; model arguments cannot supply authorization.

Coding defaults: 40 ordinary tool calls, 50 decisions, 90s LLM calls, 135s tool
operations and a 15-minute run. Work planning remains enabled; the research planner
is not used for Coding. Memory and report tool quotas remain separate.

## Validation

2026-11-23: Phase 1 full `mvn -B -Dstyle.color=never test` passed: 340 tests,
0 failures/errors, 14 skipped (opt-in MySQL/Elasticsearch suites). This verifies
profile admission and existing backend behavior; it does not verify a Docker
deployment, live models or remote GitHub publishing.
