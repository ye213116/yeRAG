<script setup lang="ts">
import { fetchCodingEvent } from '@/service/api/coding';
import { useCodingStore } from '@/store/modules/coding';
const coding = useCodingStore();
const selectedFile = ref('');
const output = ref('');
const reviewOpen = ref(false);
const publishing = ref(false);
const reviewed = ref<{ taskId: string; baseSha: string; changeSetHash: string; verificationId: string } | null>(null);
const verificationNames: Record<string, string> = { PASSED: '通过', FAILED: '失败', NOT_VERIFIED: '未验证' };
const files = computed(() => coding.task?.result?.changes || []);
const canPublish = computed(
  () =>
    coding.task?.status === 'REVIEW_READY' &&
    coding.task.result?.diff &&
    coding.task.result.changeSetHash &&
    coding.task.result.verification?.id &&
    !coding.task.result.cleanupPending &&
    !['PUBLISHING', 'CREATED'].includes(coding.task.publishState)
);
const diff = computed(() => {
  const full = coding.task?.result?.diff || '';
  if (!selectedFile.value) return full;
  const start = full.indexOf(`diff --git a/${selectedFile.value} b/${selectedFile.value}`);
  if (start < 0) return full;
  const end = full.indexOf('\ndiff --git ', start + 1);
  return full.slice(start, end < 0 ? undefined : end);
});
let revision = 0;
watch(
  () => coding.selectedTaskId,
  () => {
    revision += 1;
    output.value = '';
    selectedFile.value = '';
    reviewOpen.value = false;
    reviewed.value = null;
  }
);
async function readOutput(id: number) {
  const taskId = coding.task?.id;
  if (!taskId) return;
  revision += 1;
  const current = revision;
  const result = await fetchCodingEvent(taskId, id);
  if (current !== revision || taskId !== coding.task?.id) return;
  if (result.error) {
    output.value = '无法读取输出，请重试';
    return;
  }
  try {
    const data = JSON.parse(result.data.data_json);
    output.value = data.result?.text || data.text || JSON.stringify(data, null, 2);
  } catch {
    output.value = result.data.data_json;
  }
}
function prepare() {
  const task = coding.task;
  if (!task?.result?.changeSetHash || !task.result.verification?.id) return;
  reviewed.value = {
    taskId: task.id,
    baseSha: task.baseSha,
    changeSetHash: task.result.changeSetHash,
    verificationId: task.result.verification.id
  };
}
async function publish() {
  if (!reviewed.value) return;
  publishing.value = true;
  try {
    await coding.publish(reviewed.value);
  } finally {
    publishing.value = false;
  }
}
</script>

<template>
  <section class="task-inspector" aria-label="任务成果">
    <div class="inspector-heading">
      <strong>任务成果</strong>
      <button class="icon-control" aria-label="收起成果面板" @click="coding.inspectorOpen = false">
        <SvgIcon icon="lucide:panel-right-close" />
      </button>
    </div>
    <div class="inspector-tabs" role="tablist" aria-label="成果视图">
      <button
        v-for="tab in [
          { key: 'changes', label: '变更' },
          { key: 'verification', label: '验证' },
          { key: 'events', label: '执行记录' }
        ]"
        :key="tab.key"
        role="tab"
        :aria-selected="coding.inspectorTab === tab.key"
        :class="{ active: coding.inspectorTab === tab.key }"
        @click="coding.inspectorTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>
    <div class="inspector-body">
      <template v-if="coding.inspectorTab === 'changes'">
        <div v-if="!coding.task?.result?.diff" class="inspector-empty">
          <SvgIcon icon="lucide:file-code" />
          <p>执行完成后，在这里审核代码变更。</p>
        </div>
        <template v-else>
          <div class="changed-files">
            <button :class="{ active: !selectedFile }" @click="selectedFile = ''">
              全部变更 · {{ files.length }} 个文件
            </button>
            <button
              v-for="file in files"
              :key="file.path"
              :class="{ active: selectedFile === file.path }"
              :title="file.path"
              @click="selectedFile = file.path"
            >
              <SvgIcon icon="lucide:file-code-2" />
              <span>{{ file.path }}</span>
            </button>
          </div>
          <div class="diff-code" aria-label="审核 Diff">
            <div
              v-for="(line, index) in diff.split('\n')"
              :key="index"
              class="diff-line"
              :class="
                line.startsWith('+') && !line.startsWith('+++')
                  ? 'addition'
                  : line.startsWith('-') && !line.startsWith('---')
                    ? 'deletion'
                    : /^(diff |@@|---|\+\+\+)/.test(line)
                      ? 'diff-meta'
                      : ''
              "
            >
              {{ line || ' ' }}
            </div>
          </div>
        </template>
      </template>
      <div v-else-if="coding.inspectorTab === 'verification'" class="verification-view">
        <div class="verification-title">
          <SvgIcon
            :icon="
              coding.task?.result?.verification?.status === 'PASSED' ? 'lucide:circle-check' : 'lucide:circle-help'
            "
          />
          <h3>测试{{ verificationNames[coding.task?.result?.verification?.status || 'NOT_VERIFIED'] }}</h3>
        </div>
        <template v-if="coding.task?.result?.verification">
          <dl>
            <dt>执行测试</dt>
            <dd>{{ coding.task.result.verification.tests ?? '—' }}</dd>
            <dt>失败</dt>
            <dd>{{ coding.task.result.verification.failures ?? '—' }}</dd>
            <dt>错误</dt>
            <dd>{{ coding.task.result.verification.errors ?? '—' }}</dd>
          </dl>
        </template>
        <p>验证结果与本次代码变更绑定；未验证的变更不会显示为通过。</p>
        <p v-if="coding.task?.result?.cleanupPending">正在清理执行环境，清理完成后可发布。</p>
      </div>
      <div v-else class="execution-view">
        <div v-if="!coding.task?.events.length" class="inspector-empty">暂无执行记录</div>
        <button v-for="event in coding.task?.events" :key="event.id" class="audit-event" @click="readOutput(event.id)">
          <span>{{ event.kind }}</span>
          <small>{{ event.preview }}</small>
        </button>
        <pre v-if="output" class="audit-output">{{ output }}</pre>
      </div>
    </div>
    <div v-if="canPublish || coding.task?.prUrl" class="inspector-footer">
      <NPopconfirm v-if="canPublish" v-model:show="reviewOpen" @positive-click="publish">
        <template #trigger>
          <NButton type="primary" block :loading="publishing" @click="prepare">确认 Diff 并创建 PR</NButton>
        </template>
        将已审核的 Diff 发布到独立任务分支并创建 GitHub PR？测试状态：{{
          verificationNames[coding.task?.result?.verification?.status || 'NOT_VERIFIED']
        }}。
      </NPopconfirm>
      <a
        v-if="coding.task?.prUrl && /^https:\/\/github\.com\//.test(coding.task.prUrl)"
        :href="coding.task.prUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        查看 GitHub PR ↗
      </a>
    </div>
  </section>
</template>

<style scoped>
.task-inspector {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--side-bg);
  font-size: 12px;
}
.inspector-heading {
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  flex: none;
}
.inspector-heading strong {
  font-size: 13px;
  font-weight: 550;
}
.inspector-tabs {
  display: flex;
  gap: 18px;
  padding: 0 16px;
  border-bottom: 1px solid var(--shell-border);
  flex: none;
}
.inspector-tabs button {
  font: inherit;
  border: 0;
  padding: 12px 0;
  background: none;
  color: var(--shell-muted);
  cursor: pointer;
  border-bottom: 2px solid transparent;
}
.inspector-tabs button.active {
  color: var(--shell-text);
  border-bottom-color: var(--shell-text);
}
.inspector-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.inspector-empty {
  padding: 45px 22px;
  color: var(--shell-muted);
  text-align: center;
  line-height: 1.9;
}
.inspector-empty svg {
  font-size: 28px;
  margin: 0 auto 12px;
}
.changed-files {
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  border-bottom: 1px solid var(--shell-border);
}
.changed-files button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  border: 0;
  background: none;
  color: var(--shell-muted);
  text-align: left;
  cursor: pointer;
  font:
    11px ui-monospace,
    monospace;
}
.changed-files button > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.changed-files .active {
  color: var(--shell-text);
  background: var(--shell-active);
}
.diff-code {
  font:
    11px/1.85 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  overflow: auto;
  padding: 12px 0;
}
.diff-line {
  min-width: max-content;
  white-space: pre;
  padding: 0 14px;
}
.addition {
  background: #36b46a18;
  color: #20854d;
}
.deletion {
  background: #e4595915;
  color: #c24d4d;
}
.diff-meta {
  color: var(--shell-muted);
  background: var(--shell-hover);
}
.verification-view {
  padding: 24px;
  line-height: 1.9;
}
.verification-title {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 17px;
}
.verification-title h3 {
  font-size: 16px;
  font-weight: 550;
}
.verification-view dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 24px 0;
}
.verification-view dt,
.verification-view p {
  color: var(--shell-muted);
}
.verification-view dd {
  text-align: right;
}
.execution-view {
  padding: 12px;
}
.audit-event {
  display: block;
  width: 100%;
  text-align: left;
  cursor: pointer;
  padding: 10px;
  border: 0;
  border-bottom: 1px solid var(--shell-border);
  background: none;
  color: inherit;
  font: inherit;
}
.audit-event small {
  display: block;
  font-size: 10px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--shell-muted);
  margin-top: 4px;
}
.audit-output {
  font:
    11px/1.7 ui-monospace,
    monospace;
  padding: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--shell-hover);
}
.inspector-footer {
  padding: 16px;
  border-top: 1px solid var(--shell-border);
  flex: none;
}
.inspector-footer a {
  color: #16966d;
}
.dark .addition {
  color: #8ecda4;
}
.dark .deletion {
  color: #e7a1a1;
}
</style>
