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

## Frontend entry and task recovery

The shared shell has a 64px icon rail and a contextual sidebar. Chat/Work uses one
conversation per row, with title search, rename, delete and pagination. Knowledge and
Artifacts keep the same navigation structure. Coding at `/coding` shows registered
repositories as project folders, with independent tasks nested below each project.
The entry remains visible when the service is disabled or repository authorization
is missing, with explicit retry and service-state feedback.

Selecting a project opens a task composer. Submitting automatically creates a fresh,
branch-pinned workspace and an internal CODING session; the user does not manage
workspace IDs. Drafts survive failed submissions in the current page. Ambiguous
submission retries reconcile accepted tasks before reusing their request ID. Every
task has its own isolated filesystem; a new task starts from its selected registered
branch, without carrying a prior task's files forward.

Task links use `/coding?project=<repositoryId>&task=<taskId>`. Opening or refreshing
a link restores that task. The main pane shows the request, progress and Markdown
summary; the optional results pane shows changed files, colored escaped Diff, tests,
audit logs and the explicit PR confirmation. Narrow displays use a drawer. Closing
a browser, switching projects or visiting Work does not stop accepted tasks.

Work answers are also server-owned background runs. Switching threads preserves
execution, per-thread drafts and scroll; reconnecting merges an owner-scoped snapshot
with sequenced events. Defaults are three concurrent Work runs per user, sixteen
globally, with a bounded 2 MiB active-answer snapshot. Stop targets the conversation
and request. Startup marks interrupted Work runs without automatically rerunning
them. See the database workbench migration and single-instance constraint in
`docs/databases/database-design.md`.

`apply_patch` accepts standard Git unified diffs, including file headers and numbered
hunks. Its schema and helper errors explain that contract; `*** Begin Patch` syntax
is rejected with an actionable `unified_diff_required` response. Existing file hashes
remain mandatory. Git recounts model-generated hunk lengths, while exact file hashes
and matching context still prevent stale or conflicting writes. Commands run without
a `.git` directory; use `git_diff` for changes and `execute_command(test=true)` for
network-enabled Maven verification. The executor overrides inherited image entrypoints to keep stdout
exclusive to the helper JSON protocol, and command environments retain the fixed
image's Java 17 installation without forwarding credentials.

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


## Pre-runner local validation (2026-11-23)

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

## Frontend and Docker integration (2026-11-23)

The subsequent Docker Desktop session built the helper and both proxies, applied the
Coding migration after a local database backup, and enabled the local backend through
an ignored configuration overlay. The repository default remains disabled. This is
a local development setup; production still uses the dedicated execution-node topology
described above. Certificate stores, private configuration, logs and browser artifacts
remain under the ignored `.codex/integration/coding-runner/` directory.

Checks executed in this session:

- Full backend suite with `CODING_MYSQL_TEST_*` configured: 373 tests, 0 failures/errors,
  15 environment-gated skips. Both real MySQL timezone regressions executed and passed;
  the other memory MySQL suite was not enabled.
- The opt-in `CodingDockerIntegrationTest` executed against the actual mTLS runner and
  passed, including non-root execution, missing socket/clone credentials, blocked metadata
  networking, bounded storage and real Maven verification. The public fixture was pinned
  to `jenkins-docs/simple-java-maven-app@bba641c3a0ff1be4fb4262bcef008bb437480032`.
- Real proxy CONNECT checks allowed github.com on the clone network and
  repo.maven.apache.org on the build network. Both rejected the other destination,
  example.com and 169.254.169.254 with HTTP 403.
- Helper contracts: 7 passed, including model hunk recounting while mismatched context
  leaves files unchanged. Frontend: 3 unit tests, 13 browser scenarios, typecheck and
  production build passed. Full lint completed with 0 errors and 18 existing warnings.

Real startup uncovered and corrected unquoted Compose tmpfs options, inherited Maven
entrypoint output corrupting helper JSON, a missing JDK path in sanitized command
environments, and mixed JDBC/database clocks prematurely stopping fresh tasks. Live
model calls also established the need for an explicit unified-diff schema, actionable
format errors and Git hunk recounting. Rebuild the helper image when deploying these
helper changes; the clock correction requires no additional SQL migration.

The real-model browser check `node --env-file=.env frontend/tests/live/coding.mjs`
also passed. Task `7913f1d4-4f76-40c0-b18c-e54372520bda` added `App.greet(String)`
and four tests while preserving the existing two tests and `pom.xml`. Maven executed
6 tests with 0 failures/errors/skips. The final verification fingerprint matches the
persisted Diff; `cleanupPending=false`, and no task containers or volumes remained.
Navigation away from Coding did not stop the task; revisiting and refreshing restored
the completed result and Diff without browser errors. The successful task remains in
the local admin account for review; intermediate diagnostic tasks and their own test
sessions were removed. The private report and screenshot are
`.codex/integration/coding-runner/live-coding-report.json` and `live-coding.png`.

No real GitHub PR was created. Confirmation and fingerprint binding were covered by
browser/API fixtures; the local fixture repository has no publishing credential.


## Agent workbench integration (2026-11-24)

The shared icon rail and contextual sidebars are now used by Work, Coding, knowledge
and artifacts, including dark appearance and a mobile sidebar drawer. Coding uses
project folders and independent task rows, a task conversation pane, and an optional
results inspector. Icons are compiled locally. Layouts load lazily to avoid router/store
initialization cycles when reopening an authenticated browser directly on a task link.

After backing up the local database and stopping the previous backend, the workbench
migration was applied. Work executions now belong to user/conversation/request IDs,
rather than WebSocket connections. The local process is still a single backend instance.

Executed against real services:

- `frontend/tests/live/workbench.mjs` started two real Work requests in separate
  conversations, verified the first still ran while viewing Coding and submitting the
  second, then closed the entire browser. Both completed durably. Reopening the browser
  and refreshing a conversation link restored exactly one complete answer matching the
  stored text. Desktop, dark and narrow-screen checks passed without browser errors.
- `frontend/tests/live/coding.mjs` submitted task
  `58ece1f2-cd96-4695-9025-4c66227396ad` from the project composer, which automatically
  created its independent workspace. After the browser page closed, the real model
  added `greet(String)` and three tests in the isolated Docker runner. Maven ran 5 tests
  with 0 failures/errors/skips. Refreshing its task link restored the summary, changed
  file list and colored Diff without browser errors. The persisted change fingerprint
  matches verification; cleanup completed, and no task containers or volumes remained.
- The full backend suite with both `CODING_MYSQL_TEST_*` and
  `WORKBENCH_MYSQL_TEST_*` configured passed: 384 tests, 0 failures/errors, 15
  environment-gated skips. The four Workbench and two Coding MySQL contracts executed
  and passed. Seven helper contract tests also passed.

The successful conversations and Coding task remain in the local admin account for
review. Private evidence is in `.codex/integration/workbench/`: `live-work-report.json`,
`live-coding-report.json`, `work-home.png`, `work-conversation.png`, `work-dark.png`,
`work-mobile.png` and `live-coding.png`. No real PR was published; publishing still
requires an explicitly confirmed Diff and a configured repository credential.

Final frontend checks for this workbench: `pnpm typecheck`, `pnpm lint`,
`pnpm build`, 3 unit tests and all 16 browser scenarios passed. Lint reported
0 errors and the same 18 existing warnings. Browser coverage includes per-thread
drafts and reading position, background reconnect/deduplication, task deep links,
explicit publication confirmation, revoked access and cross-account isolation.

The additional opt-in `MemoryDocumentMySqlTest` also ran against real MySQL:
13 tests passed with no skips, including two-connection claims, same-user ordering
and deletion racing extraction. Together with the four Workbench and two Coding
checks, 19 MySQL contract tests executed. A final browser smoke check against the
restarted local services covered Work, Coding inspector tabs, knowledge, artifacts,
dark appearance and mobile layout without browser errors.
