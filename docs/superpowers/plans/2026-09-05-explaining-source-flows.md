# Explaining Source Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a discoverable Codex skill that produces a source-precise execution trace and a developer-oriented technical explanation for one repository-backed module flow.

**Architecture:** Keep the skill self-contained in one `SKILL.md`, with UI metadata in `agents/openai.yaml`. The instructions define evidence gathering once, then require two output projections with explicit ownership and a final consistency check.

**Tech Stack:** Markdown, YAML, Codex skill initializer and validator

---

### Task 1: Establish the behavioral baseline

**Files:**
- Inspect: `docs/module-analysis/file-upload/01-flow.md`
- Inspect: `/home/zyh/.codex/skills/tracing-source-modules/SKILL.md`

- [ ] **Step 1: Check the current flow artifact**

Run:

```bash
rg -n "模块完整流程|核心调用链|为什么|开发者|框架机制" docs/module-analysis/file-upload/01-flow.md
```

Expected: the file contains detailed flow and call-chain sections, but no separately owned developer-explanation artifact or consistent explanation contract.

- [ ] **Step 2: Record the baseline gap**

Treat the observable gap as the failing behavior: the existing module skill generates one flow document and cannot satisfy a request for both a breakpoint-ready source trace and a separately readable developer explanation without ad hoc instructions.

### Task 2: Create the skill

**Files:**
- Create: `/home/zyh/.codex/skills/explaining-source-flows/SKILL.md`
- Create: `/home/zyh/.codex/skills/explaining-source-flows/agents/openai.yaml`

- [ ] **Step 1: Initialize the skill directory**

Run:

```bash
python /home/zyh/.codex/skills/.system/skill-creator/scripts/init_skill.py explaining-source-flows --path /home/zyh/.codex/skills
```

Expected: initializer reports creation of the skill directory, `SKILL.md`, and `agents/openai.yaml`.

- [ ] **Step 2: Replace the scaffold with the approved instructions**

Write `SKILL.md` with:

- a trigger-only description covering requests for a complete cross-component execution path;
- a boundary against full module dossiers and isolated method explanations;
- one evidence-gathering pass that inspects callers, callees, data types, configuration, persistence, errors, transactions, and asynchronous continuation;
- required `01-source-trace.md` and `02-developer-explanation.md` outputs;
- distinct content contracts for breakpoint-ready tracing and developer-oriented explanation;
- source-evidence, branch fidelity, cross-linking, and cross-document consistency rules;
- a file-upload example showing the two representations without encoding project-specific facts as universal rules.

- [ ] **Step 3: Update UI metadata**

Set concise UI fields consistent with the skill:

```yaml
interface:
  display_name: "Explain Source Flows"
  short_description: "Trace and explain one module execution path"
  default_prompt: "Trace a named source flow and produce both the precise source trace and developer explanation."
```

### Task 3: Verify and refine

**Files:**
- Verify: `/home/zyh/.codex/skills/explaining-source-flows/SKILL.md`
- Verify: `/home/zyh/.codex/skills/explaining-source-flows/agents/openai.yaml`

- [ ] **Step 1: Run structural validation**

Run:

```bash
python /home/zyh/.codex/skills/.system/skill-creator/scripts/quick_validate.py /home/zyh/.codex/skills/explaining-source-flows
```

Expected: `Skill is valid!`

- [ ] **Step 2: Check discovery and content invariants**

Run:

```bash
rg -n "^description: Use when|01-source-trace.md|02-developer-explanation.md|源码暂时无法确认|tracing-source-modules|同步|异步|分支" /home/zyh/.codex/skills/explaining-source-flows/SKILL.md
```

Expected: every required trigger, output, evidence boundary, skill boundary, and execution distinction is present.

- [ ] **Step 3: Check for unfinished scaffold text**

Run:

```bash
rg -n "TODO|TBD|\[TODO|Choose a structure|Replace with" /home/zyh/.codex/skills/explaining-source-flows
```

Expected: no matches.

- [ ] **Step 4: Review against the approved design**

Compare with `docs/superpowers/specs/2026-09-05-explaining-source-flows-design.md`. Confirm both output contracts, evidence rules, default path, ownership behavior, framework-explanation limit, and acceptance criteria are represented.

