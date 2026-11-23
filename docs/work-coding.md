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


## Delegated workspace tasks

Phase 2 adds registered GitHub repositories, server-owned workspaces, owner-selected
permissions, durable task submission and a background worker. The chat acknowledgement
and queued task commit together; switching/deleting a conversation does not cancel an
accepted Coding task. Runtime execution remains the shared agent loop. Workspaces have
one active writer and become frozen after execution; results and verification records
are private task data. The frontend Coding panel polls progress and displays the Diff.

The only execution adapter is a dedicated Docker Engine using mTLS. A fixed immutable
helper image handles file operations; model commands run only inside non-root containers.
The bootstrap read credential is absent from code-execution containers. Git baseline
metadata is mounted read-only and separately from workspace files. Commands use disposable
containers; bounded tmpfs workspace volumes are kept mounted for the task and destroyed
after result collection. Internal clone/build networks require controlled egress proxies.
There is no fallback to host commands when Docker is unavailable.

2026-11-23 Phase 2 checks: full backend suite passed (349 tests, 14 environment-gated
skips); frontend unit tests (3), typecheck, targeted ESLint and production build passed.
Five helper contract tests passed against fixed local fixtures; Docker API protocol tests
use a local HTTP fixture. Docker Desktop's Linux engine was unavailable in this session,
so actual containers, network/quota enforcement and live-model/GitHub execution remain
unverified. PR publication is delivered in the next increment.
