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

## Runner deployment and configuration

Coding is a delegated development task, not a browser IDE. Enable it only after the
execution node is prepared and its opt-in integration check passes. The business
backend keeps its existing deployment; no Docker socket is mounted into it.

1. On a dedicated Linux Docker execution node, build the fixed helper image from the
   repository root: `docker build -f deploy/coding/Dockerfile -t yerag-coding:java17 .`.
2. Start its isolated egress networks with
   `docker compose -f deploy/coding/compose.runner.yaml up -d --build`.
   Clone traffic can CONNECT only to github.com:443; build traffic only to
   repo.maven.apache.org:443. Neither network is attached to business middleware.
   Generic command containers have no network. Proxies reject private destination
   addresses and do not publish host ports. The supplied proxy images/configuration
   require actual-runner validation; fixture tests cannot prove firewall behavior.
3. Expose the execution node's Docker Engine through an authenticated HTTPS endpoint
   with client certificates. Store the CA trust material and client key in separate
   PKCS12 stores, readable only by the backend process. Configure systemd/cgroup
   delegation for CPU, memory and PID enforcement. The implementation also requires
   Linux `local` tmpfs volumes with bounded capacity; some rootless configurations
   cannot mount these. An incompatible runner is rejected rather than using an
   unbounded volume or host execution. A dedicated VM with a supported Docker daemon
   and non-root task containers is the initial deployment target.
4. Apply the Coding SQL migration. Load
   `deploy/coding/application-coding.properties.example` through an explicit additional
   configuration location, supplying environment values locally. Both Coding flags
   default to false. Do not commit populated configuration or certificate stores.
5. Register exact repository names, allowed database user IDs, branch names and the
   server permission ceiling. Token configuration contains environment-variable names,
   not token values. Public clones may omit the read-token reference. Private clones
   require a repository-scoped read token. Publishing requires a separate GitHub
   credential with Contents write and Pull requests write permission on that repository.
   The publish credential is never passed into containers. Workflow-file modifications
   may require additional GitHub permissions; failures preserve the reviewed result.
6. Use one backend execution instance for this version. Worker concurrency is bounded
   to 1–4; database task claims and workspace ownership prevent duplicate writers.
   Do not deploy multiple active Coding schedulers until distributed execution leases
   are implemented. Accepted tasks continue across browser disconnection; application
   crashes become INTERRUPTED after stale-heartbeat detection, with no automatic rerun.

A workspace is pinned to one base commit and freezes after an execution. To continue
editing, create a new workspace. Submodules, symlinks and binary modifications are not
supported; unchanged binary assets are preserved. Changed text files are limited to
1 MiB, the complete result to 2 MiB, and helper request envelopes to 64 KiB. Maven
cache and build output live within the workspace storage quota. Expensive commands
can exhaust that quota and fail without affecting the business host.

## Verification and publication contract

The server runs a fixed Maven test recipe, clears old Surefire XML before each run,
records command exit and test counts, and compares the code fingerprint before and
after execution. A zero exit with no executed tests is not PASSED. The initial clean
baseline is an audit event; subsequent verification permits at most three attempts
(initial implementation plus two repairs). Later edits invalidate old verification.
The Coding completion rule feeds missing/stale/failed verification back into the same
agent loop. Budget exhaustion produces a summary without inventing a passing result.

Final files, Diff, verification and summary are persisted before volumes are deleted.
Cleanup is tracked separately and retried; publication is blocked while cleanup is
pending. A deterministic operation-container name permits recovery after a lost API
response or controller interruption. Queued cancellation is serialized with task
claiming. Closing a WebSocket is not a Coding cancellation request.

PR creation is an authenticated explicit POST outside the Agent tool catalog. The
confirmation binds base SHA, change fingerprint and verification ID. The frontend
captures those values when opening the confirmation and clears it if the selected
task changes. Failed verification remains visibly FAILED and may be explicitly
published for review; it is never reported as passing. Read-only/unverified tasks
cannot publish. A changed target branch requires a new workspace and verification.

Publication creates a task-specific GitHub tree/commit/reference, then a PR; it does
not modify the target branch. Existing task branches are checked before reuse, never
force-updated. Lost PR responses are reconciled through GitHub before any new mutation.
Concurrent publication is rejected. A publishing process lost for five minutes becomes
retryable, but never publishes again without another user confirmation. The PR URL and
validation status remain associated with the durable task.

Task APIs are under `/api/v1/coding`: capabilities, repositories, workspace creation
and permission updates, task list/detail/diff, individual audit output, cancellation,
and `tasks/{id}/pull-request`. List/detail/output/publication enforce ownership and
current repository authorization. Workspace and task identities never come from LLM
arguments. Existing Work REST/WebSocket contracts and source selectors remain supported.

## Optional real-runner check

Set `CODING_DOCKER_TEST_URL`, `CODING_DOCKER_TEST_TRUST_STORE`,
`CODING_DOCKER_TEST_KEY_STORE`, `CODING_DOCKER_TEST_STORE_PASSWORD` and
`CODING_DOCKER_TEST_REPOSITORY` locally, optionally `CODING_DOCKER_TEST_BRANCH`,
`CODING_DOCKER_TEST_IMAGE` and `CODING_DOCKER_TEST_TOKEN_ENV` (the name of the read
credential environment variable). Use a small, green Java 17/Maven fixture repository.
Run `mvn -Dtest=CodingDockerIntegrationTest test`. This creates and removes only its
isolated workspace, checks non-root execution, absent credentials/socket, blocked
metadata networking, quota capacity and real Maven verification. It never creates a PR.

Local checks: `python3 -m unittest discover -s deploy/coding -p 'test_*.py'`,
`mvn test`, and in frontend `pnpm test`, `pnpm test:browser`, `pnpm typecheck`,
`pnpm lint`, `pnpm build`. Browser and Docker API fixtures are separate from actual
container or live-model validation; report the latter only when explicitly executed.


## Final local validation (2026-11-23)

- Full backend `mvn -B -Dstyle.color=never test`: 371 tests, 0 failures/errors,
  15 skipped (13 opt-in MySQL, 1 Elasticsearch, 1 real Docker runner).
- The enabled Coding Spring context test wires all six tools into the existing
  runtime. Loop fixtures cover editing, failed tests, repair, verification invalidation
  and server-required verification. Worker tests cover result-before-delete ordering,
  SQL failure preserving files, pending cleanup and cancellation before preparation.
- Fixed local helper contracts: 6 passed. They validate paths, conflicts, safe file
  handling, bounded output, continuation and unchanged large binary assets; these
  tests do not execute generated commands on the business host.
- Frontend: 3 unit tests, 12 browser scenarios, typecheck and production build passed.
  `pnpm lint` completed with 0 errors and 18 pre-existing warnings outside this change.
- Real Docker execution, the helper/proxy images, enforced network/storage/resource
  isolation, live-model Coding tasks and real GitHub PR creation were not validated:
  no running Docker Engine or configured isolated runner/test repository was available.
  Coding remains disabled by default. Apply the migration, register the repository,
  supply local credentials and run the real-runner suite before enabling it.
