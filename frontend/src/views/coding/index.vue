<script setup lang="ts">
import { useRoute } from 'vue-router';
import { useMediaQuery } from '@vueuse/core';
import { codingActive, codingStatusLabels, useCodingStore } from '@/store/modules/coding';
import WorkbenchMarkdown from '@/components/custom/workbench-markdown.vue';
import TaskInspector from './modules/task-inspector.vue';
const coding = useCodingStore();
const chat = useChatStore();
const route = useRoute();
const inspectorDrawer = useMediaQuery('(max-width: 1279px)');
const branches = computed(
  () =>
    coding.currentProject?.branches.map(value => ({ label: value.length > 20 ? value.slice(0, 12) : value, value })) ||
    []
);
const permissionOptions = [
  { label: '允许修改与测试', value: 'edit' },
  { label: '只读分析', value: 'read' }
];
const verificationNames: Record<string, string> = { PASSED: '通过', FAILED: '失败', NOT_VERIFIED: '未验证' };
const tools: Record<string, string> = {
  list_files: '浏览项目文件',
  read_file: '读取文件',
  search_code: '搜索代码',
  apply_patch: '修改代码',
  execute_command: '执行命令与测试',
  git_diff: '检查变更'
};
function eventLabel(event: { kind: string; preview: string }) {
  try {
    const data = JSON.parse(event.preview);
    if (event.kind === 'baseline') return '完成基线测试';
    return tools[data.details?.tool] || data.message || event.kind;
  } catch {
    return event.kind;
  }
}
const progress = computed(() =>
  (coding.task?.events || [])
    .filter(e => ['tool_started', 'baseline', 'execution_ended'].includes(e.kind))
    .slice(0, 16)
    .reverse()
);
watch(
  () => [route.query.project, route.query.task],
  ([project, id]) =>
    coding.restore(typeof project === 'string' ? project : undefined, typeof id === 'string' ? id : undefined),
  { immediate: true }
);
watch(
  () => coding.task?.status,
  status => {
    if (status === 'REVIEW_READY' && coding.task?.result?.diff) coding.inspectorOpen = true;
  }
);
function shortcut(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.isComposing) {
    event.preventDefault();
    coding.submit();
  }
}
</script>

<template>
  <section class="coding-workbench" aria-label="Coding 工作台">
    <div class="coding-main">
      <NAlert v-if="coding.error" type="warning" class="coding-alert">
        {{ coding.error }}
        <NButton text @click="coding.load()">重新加载</NButton>
      </NAlert>
      <NAlert v-if="coding.notice" type="info" closable class="coding-alert" @close="coding.notice = ''">
        {{ coding.notice }}
      </NAlert>
      <div v-if="coding.loading && !coding.loaded" class="coding-empty">
        <NSpin size="small" />
        <p>正在加载项目…</p>
      </div>
      <div v-else-if="!coding.enabled" class="coding-empty">
        <SvgIcon icon="lucide:code-2" />
        <h1>Coding 开发任务</h1>
        <p>{{ coding.error || '当前服务尚未启用 Coding，请先配置执行环境和授权仓库。' }}</p>
        <NButton :loading="coding.loading" @click="coding.load()">重新检查</NButton>
      </div>
      <div v-else-if="!coding.repositories.length" class="coding-empty">
        <SvgIcon icon="lucide:folder" />
        <h1>还没有可用项目</h1>
        <p>当前账号没有可用仓库，请联系管理员授权 GitHub 仓库和基准分支。</p>
        <NButton @click="coding.load()">重新加载项目</NButton>
      </div>
      <NSpin v-else-if="coding.detailLoading" class="coding-empty" size="small" />
      <template v-else-if="coding.selectedTaskId && coding.task">
        <div class="task-thread">
          <div class="task-thread-column">
            <div class="task-state">
              <span v-if="codingActive(coding.task.status)" class="running-indicator"></span>
              <SvgIcon
                v-else
                :icon="coding.task.status === 'REVIEW_READY' ? 'lucide:circle-check' : 'lucide:circle-alert'"
              />
              <span>{{ codingStatusLabels[coding.task.status] || coding.task.status }}</span>
              <span v-if="coding.task.result?.verification">
                测试：{{ verificationNames[coding.task.result.verification.status] }}
                <template v-if="coding.task.result.verification.tests !== undefined">
                  · {{ coding.task.result.verification.tests }} 项
                </template>
              </span>
              <NButton v-if="codingActive(coding.task.status)" size="tiny" quaternary @click="coding.cancel()">
                停止任务
              </NButton>
            </div>
            <div class="task-user-message">
              {{ coding.task.message || coding.tasks.find(t => t.id === coding.task?.id)?.title || '开发任务' }}
            </div>
            <NCollapse v-if="progress.length" class="task-progress">
              <NCollapseItem :title="`执行过程 · ${progress.length} 步`" name="progress">
                <div v-for="event in progress" :key="event.id" class="progress-row">
                  <SvgIcon icon="lucide:check" />
                  <span>{{ eventLabel(event) }}</span>
                </div>
              </NCollapseItem>
            </NCollapse>
            <WorkbenchMarkdown v-if="coding.task.summary" :content="coding.task.summary" />
            <div v-else-if="codingActive(coding.task.status)" class="task-waiting">
              <span class="thinking-dot"></span>
              正在处理任务，离开页面后仍会继续执行。
            </div>
            <p v-else class="task-waiting">本次任务已结束，可查看执行记录，或新建任务重试。</p>
            <div class="task-thread-actions">
              <button class="quiet-button" @click="coding.inspectorOpen = true">
                <SvgIcon icon="lucide:git-pull-request" />
                查看变更与验证
              </button>
              <button class="quiet-button" @click="coding.selectProject(coding.projectId)">
                <SvgIcon icon="lucide:plus" />
                新建任务
              </button>
            </div>
          </div>
        </div>
        <div class="task-frozen-note">每个任务独立执行。继续开发可从当前仓库分支新建任务。</div>
      </template>
      <div v-else-if="coding.selectedTaskId" class="coding-empty"><p>任务不存在或无法访问，请从左侧重新选择。</p></div>
      <div v-else class="coding-start">
        <div class="coding-welcome">
          <SvgIcon icon="lucide:folder-code" />
          <h1>在项目中开始一个任务</h1>
          <p>
            {{ coding.currentProject?.name }}
            <span>/</span>
          </p>
        </div>
        <div class="coding-composer">
          <textarea
            v-model="coding.draft.message"
            rows="4"
            aria-label="开发任务描述"
            placeholder="描述要完成的改动，例如修复一个问题，并添加测试…"
            :disabled="coding.submitting"
            @keydown="shortcut"
          />
          <div class="coding-composer-controls">
            <SvgIcon icon="lucide:git-branch" />
            <NSelect
              v-model:value="coding.draft.branch"
              :options="branches"
              class="branch-control"
              :disabled="coding.submitting"
              :bordered="false"
              size="small"
              aria-label="基准分支"
            />
            <NSelect
              v-model:value="coding.draft.preset"
              :options="permissionOptions"
              class="permission-control"
              :disabled="coding.submitting"
              :bordered="false"
              size="small"
              aria-label="Coding 权限"
            />
            <button
              class="coding-submit"
              :disabled="
                coding.submitting || !coding.draft.message.trim() || !coding.draft.branch || chat.wsStatus !== 'OPEN'
              "
              aria-label="提交 Coding 任务"
              @click="coding.submit()"
            >
              <SvgIcon :icon="coding.submitting ? 'lucide:loader-circle' : 'lucide:arrow-up'" />
            </button>
          </div>
        </div>
        <p class="coding-start-caption">任务在隔离环境中执行，完成后审核变更和测试结果。</p>
      </div>
    </div>
    <aside v-if="coding.inspectorOpen && !inspectorDrawer && coding.task" class="coding-results">
      <TaskInspector />
    </aside>
    <NDrawer v-if="inspectorDrawer" v-model:show="coding.inspectorOpen" width="min(94vw, 560px)" placement="right">
      <TaskInspector v-if="coding.task" />
    </NDrawer>
  </section>
</template>

<style scoped>
.coding-workbench {
  display: flex;
  min-height: 0;
  height: 100%;
}
.coding-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.coding-alert {
  margin: 8px 24px;
}
.coding-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 32px;
  text-align: center;
}
.coding-empty > svg {
  font-size: 36px;
  color: var(--shell-muted);
}
.coding-empty h1 {
  font-size: 23px;
  font-weight: 550;
}
.coding-empty p {
  color: var(--shell-muted);
  font-size: 14px;
  max-width: 420px;
  line-height: 1.9;
}
.coding-start {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 740px;
  width: calc(100% - 56px);
  margin: 0 auto;
  padding-bottom: 12vh;
}
.coding-welcome {
  text-align: center;
  margin-bottom: 28px;
}
.coding-welcome > svg {
  font-size: 32px;
  color: var(--shell-muted);
  margin: 0 auto 18px;
}
.coding-welcome h1 {
  font-size: 27px;
  font-weight: 550;
  letter-spacing: -0.7px;
}
.coding-welcome p {
  margin-top: 14px;
  font:
    12px ui-monospace,
    monospace;
  color: var(--shell-muted);
}
.coding-welcome p span {
  opacity: 0.5;
}
.coding-composer {
  border: 1px solid var(--shell-border);
  border-radius: 22px;
  padding: 18px 18px 12px;
  box-shadow: 0 3px 16px #00000004;
}
.coding-composer:focus-within {
  border-color: #aaaab2;
}
.coding-composer textarea {
  width: 100%;
  resize: vertical;
  min-height: 100px;
  max-height: 300px;
  border: 0;
  outline: 0;
  background: none;
  color: var(--shell-text);
  font-size: 15px;
  line-height: 1.8;
}
.coding-composer textarea::placeholder {
  color: var(--shell-muted);
}
.coding-composer-controls {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--shell-muted);
  margin-top: 14px;
}
.branch-control {
  width: 140px;
}
.permission-control {
  width: 170px;
}
.coding-submit {
  margin-left: auto;
  flex: none;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--shell-text);
  color: var(--shell-bg);
  font-size: 19px;
  cursor: pointer;
}
.coding-start-caption {
  font-size: 11px;
  text-align: center;
  color: var(--shell-muted);
  margin-top: 14px;
}
.task-thread {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.task-thread-column {
  max-width: 800px;
  padding: 24px 36px 40px;
  margin: 0 auto;
}
.task-state {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 11px;
  color: var(--shell-muted);
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.task-user-message {
  margin: 0 0 30px auto;
  max-width: 92%;
  background: var(--shell-hover);
  border-radius: 18px;
  padding: 14px 18px;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.task-progress {
  margin-bottom: 28px;
  color: var(--shell-muted);
  font-size: 12px;
}
.progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  font-size: 12px;
  color: var(--shell-muted);
}
.task-waiting {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--shell-muted);
  margin: 20px 0;
}
.task-thread-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 32px;
}
.task-frozen-note {
  flex: none;
  text-align: center;
  padding: 16px;
  font-size: 11px;
  color: var(--shell-muted);
}
.coding-results {
  width: 400px;
  flex: none;
  border-left: 1px solid var(--shell-border);
  min-height: 0;
}
.thinking-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--shell-muted);
}
@media (max-width: 899px) {
  .coding-start {
    width: calc(100% - 28px);
  }
  .task-thread-column {
    padding: 16px 20px;
  }
  .coding-welcome h1 {
    font-size: 23px;
  }
  .branch-control {
    width: 100px;
  }
  .permission-control {
    width: 145px;
  }
}
</style>
