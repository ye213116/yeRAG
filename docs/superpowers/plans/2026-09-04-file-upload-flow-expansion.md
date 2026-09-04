# File Upload Flow Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand `01-flow.md` into a complete execution-order account of file upload, with explicit MySQL, Redis, MinIO, Kafka, parsing, and indexing operations and validation branches.

**Architecture:** Keep the existing documentation set and modify only its flow-owned file. Organize the document as a synchronous upload/merge path, auxiliary endpoints, an asynchronous Kafka continuation, and a compact method call chain, with repository-relative evidence links throughout.

**Tech Stack:** Markdown, Spring MVC/Security, Spring Data JPA, Redis Bitmap, MinIO Java SDK, Spring Kafka, Apache Tika, WebClient, Elasticsearch Java Client.

---

### Task 1: Reconstruct the synchronous upload flow

**Files:**
- Modify: `docs/module-analysis/file-upload/01-flow.md`
- Inspect: `frontend/src/store/modules/knowledge-base/index.ts`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/UploadService.java`

- [ ] **Step 1: Document frontend task creation and request input**

Describe `calculateMD5()`, the 5 MB slicing formula, sequential per-file uploads, three-file concurrency, multipart fields, and JWT-derived `userId`.

- [ ] **Step 2: Document every `uploadChunk()` query and mutation in order**

Record `file_upload` lookup/insert, Redis `GETBIT`, `chunk_info` lookup, all duplicate-state branches, MinIO `statObject`/`putObject`, Redis `SETBIT`, `ChunkInfoRepository.save()`, progress Bitmap read, and HTTP response.

- [ ] **Step 3: Add an explicit branch matrix and storage snapshots**

Use a table for Redis/`chunk_info`/MinIO combinations and state snapshots showing exactly what exists before and after a successful chunk.

### Task 2: Reconstruct merge and auxiliary endpoints

**Files:**
- Modify: `docs/module-analysis/file-upload/01-flow.md`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/UploadService.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/config/OrgTagAuthorizationFilter.java`

- [ ] **Step 1: Document merge preconditions**

Record ownership lookup, Redis completeness calculation, ordered `chunk_info` query, exact-count validation, and per-object MinIO `statObject()` validation.

- [ ] **Step 2: Document merge mutations in exact order**

Record `composeObject()`, merged-object verification, best-effort part deletion, Redis deletion, `file_upload` update/save, one-hour presigned URL generation, `FileProcessingTask` construction, Kafka transaction, and HTTP response boundary.

- [ ] **Step 3: Document auxiliary paths without merging them into the happy path**

Describe `/upload/status` and `/upload/supported-types`; state that `ParseController` is an independent endpoint and is not invoked by the chunk/merge chain.

### Task 3: Reconstruct the asynchronous continuation

**Files:**
- Modify: `docs/module-analysis/file-upload/01-flow.md`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/ElasticsearchService.java`

- [ ] **Step 1: Document the Kafka boundary and file download**

Identify `file-processing-topic1`, `file-processing-group`, the six message fields, local/HTTP input stream branches, and the one-hour URL dependency.

- [ ] **Step 2: Document parse persistence and vector indexing**

Trace Tika character buffering, semantic splitting, each `DocumentVectorRepository.save()`, `findByFileMd5()`, batched `/embeddings` calls, `EsDocument` creation, and `knowledge_base` Bulk API write.

- [ ] **Step 3: Replace the core call chain with storage-aware descriptions**

Keep the `## 5. 核心调用链` section compact while naming every actual cross-system handoff and the asynchronous boundary.

### Task 4: Verify document completeness

**Files:**
- Verify: `docs/module-analysis/file-upload/01-flow.md`

- [ ] **Step 1: Validate required headings and factual coverage**

Run:

```bash
rg -n '^## (2|5)\.' docs/module-analysis/file-upload/01-flow.md
rg -n 'file_upload|chunk_info|upload:\{userId\}:\{fileMd5\}|putObject|statObject|composeObject|removeObject|FileProcessingTask|document_vectors|knowledge_base' docs/module-analysis/file-upload/01-flow.md
```

Expected: exactly one section 2 and one section 5; every listed storage term or operation appears.

- [ ] **Step 2: Validate repository-relative links**

Extract all Markdown links and resolve each non-HTTP target relative to `docs/module-analysis/file-upload`; expected result is zero broken links.

- [ ] **Step 3: Review scoped diff**

Run:

```bash
git diff -- docs/module-analysis/file-upload/01-flow.md
git status --short
```

Expected: implementation changes are limited to `01-flow.md`; pre-existing unrelated worktree changes remain untouched.
