<script setup lang="ts">
import {
  cancelCodingTask,
  createCodingWorkspace,
  fetchCodingRepositories,
  fetchCodingTask,
  fetchCodingTasks,
  fetchCodingWorkspaces,
  publishCodingTask,
  updateCodingPermissions
} from '@/service/api/coding';
import type {
  CodingRepository,
  CodingTask,
  CodingTaskSummary,
  CodingWorkspace,
  WorkspacePermission
} from '@/service/api/coding';

const chat = useChatStore();
const repositories = ref<CodingRepository[]>([]);
const workspaces = ref<CodingWorkspace[]>([]);
const tasks = ref<CodingTaskSummary[]>([]);
const repositoryId = ref('');
const branch = ref('');
const preset = ref<'read' | 'edit'>('edit');
const selectedTask = ref('');
const task = ref<CodingTask | null>(null);
const notice = ref('');
const busy = ref(false);
let alive = true;
let revision = 0;
let polling = false;
const permissionOptions = [
  { label: '只读分析', value: 'read' },
  { label: '允许修改和运行测试', value: 'edit' }
];
const grants = (): WorkspacePermission[] => (preset.value === 'edit' ? ['READ', 'WRITE', 'EXECUTE'] : ['READ']);
const repositoryOptions = computed(() => repositories.value.map(r => ({ label: r.name, value: r.id })));
const branchOptions = computed(
  () =>
    repositories.value.find(r => r.id === repositoryId.value)?.branches.map(value => ({ label: value, value })) || []
);
const workspaceOptions = computed(() =>
  workspaces.value.map(w => ({
    label: `${w.repositoryId} · ${w.branch} · ${w.status} · ${w.id.slice(0, 8)}`,
    value: w.id
  }))
);
const taskOptions = computed(() => tasks.value.map(t => ({ label: `${t.status} · ${t.id.slice(0, 8)}`, value: t.id })));
const terminal = (status: string) => !['QUEUED', 'PREPARING', 'RUNNING'].includes(status);
const canPublish = computed(
  () =>
    task.value?.status === 'REVIEW_READY' &&
    task.value.result?.changeSetHash &&
    task.value.result.verification?.id &&
    task.value.publishState !== 'PUBLISHING' &&
    task.value.publishState !== 'CREATED'
);
watch(repositoryId, () => {
  branch.value = branchOptions.value[0]?.value || '';
});
watch(
  () => chat.codingWorkspaceId,
  id => {
    const w = workspaces.value.find(item => item.id === id);
    chat.codingWorkspaceReady = Boolean(w && ['NEW', 'READY'].includes(w.status) && !w.activeTaskId);
    if (w) preset.value = w.permissions.includes('EXECUTE') ? 'edit' : 'read';
  }
);
watch(selectedTask, async id => {
  revision += 1;
  const current = revision;
  task.value = null;
  if (!id) return;
  const result = await fetchCodingTask(id);
  if (!alive || current !== revision) return;
  if (result.error) notice.value = '无法读取任务，请重新加载';
  else task.value = result.data;
});
watch(
  () => chat.lastCodingTaskId,
  id => {
    if (id) {
      selectedTask.value = id;
      refresh();
    }
  }
);
async function refresh() {
  if (polling) return;
  polling = true;
  try {
    const current = revision;
    const id = selectedTask.value;
    const [ws, list, detail] = await Promise.all([
      fetchCodingWorkspaces(),
      fetchCodingTasks(),
      id ? fetchCodingTask(id) : Promise.resolve(null)
    ]);
    if (!alive || current !== revision) return;
    if (!ws.error) {
      workspaces.value = ws.data;
      const w = ws.data.find(item => item.id === chat.codingWorkspaceId);
      chat.codingWorkspaceReady = Boolean(w && ['NEW', 'READY'].includes(w.status) && !w.activeTaskId);
    }
    if (!list.error) tasks.value = list.data;
    if (detail && !detail.error) task.value = detail.data;
    if (ws.error || list.error || detail?.error) notice.value = '任务状态加载失败，正在重试';
  } finally {
    polling = false;
  }
}
async function createWorkspace() {
  busy.value = true;
  try {
    const result = await createCodingWorkspace({
      repositoryId: repositoryId.value,
      branch: branch.value,
      permissions: grants()
    });
    if (!alive) return;
    if (result.error) notice.value = '无法创建 Workspace，请检查仓库授权和云端执行环境';
    else {
      workspaces.value.unshift(result.data);
      chat.codingWorkspaceId = result.data.id;
      chat.codingWorkspaceReady = true;
      notice.value = 'Workspace 已创建。请在对话中描述开发任务，提交后云端会继续执行。';
    }
  } finally {
    busy.value = false;
  }
}
async function savePermissions() {
  const result = await updateCodingPermissions(chat.codingWorkspaceId, grants());
  if (!alive) return;
  notice.value = result.error ? '权限保存失败' : '权限已保存；运行中任务不会因新增权限而扩大授权';
  await refresh();
}
async function cancel() {
  if (!task.value) return;
  const result = await cancelCodingTask(task.value.id);
  if (!alive) return;
  notice.value = result.error ? '取消请求失败' : '已请求停止，正在清理运行环境';
  await refresh();
}
async function publish() {
  const reviewed = task.value;
  if (!reviewed?.result?.changeSetHash || !reviewed.result.verification?.id) return;
  busy.value = true;
  try {
    const result = await publishCodingTask(reviewed.id, {
      baseSha: reviewed.baseSha,
      changeSetHash: reviewed.result.changeSetHash,
      verificationId: reviewed.result.verification.id
    });
    if (!alive) return;
    notice.value = result.error ? 'PR 发布失败；请检查授权或基准变化，已保存的改动仍可审核' : 'PR 已创建';
    await refresh();
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  const result = await fetchCodingRepositories();
  if (!alive) return;
  if (result.error) notice.value = '无法加载已授权仓库';
  else {
    repositories.value = result.data;
    repositoryId.value = result.data[0]?.id || '';
  }
  await refresh();
});
const timer = window.setInterval(refresh, 2000);
onUnmounted(() => {
  alive = false;
  revision += 1;
  window.clearInterval(timer);
});
</script>

<template>
  <NCard title="委托开发任务" size="small">
    <NText depth="3">选择仓库和权限，在下方对话中提交任务；云端完成修改和验证后，在此审核 Diff 并创建 PR。</NText>
    <div class="mt-3 flex flex-wrap gap-2">
      <NSelect v-model:value="repositoryId" class="w-64" :options="repositoryOptions" placeholder="授权 GitHub 仓库" />
      <NSelect v-model:value="branch" class="w-36" :options="branchOptions" placeholder="基准分支" />
      <NSelect v-model:value="preset" class="w-56" :options="permissionOptions" aria-label="Coding 权限" />
      <NButton :loading="busy" :disabled="!repositoryId || !branch" @click="createWorkspace">新建 Workspace</NButton>
    </div>
    <div class="mt-3 flex flex-wrap gap-2">
      <NSelect
        v-model:value="chat.codingWorkspaceId"
        class="min-w-72 flex-1"
        :options="workspaceOptions"
        placeholder="选择 Workspace"
      />
      <NButton :disabled="!chat.codingWorkspaceId" @click="savePermissions">保存权限</NButton>
      <NButton @click="refresh">刷新任务</NButton>
    </div>
    <NAlert v-if="notice" class="mt-3" type="info">{{ notice }}</NAlert>
    <NSelect
      v-model:value="selectedTask"
      class="mt-3"
      :options="taskOptions"
      placeholder="查看后台任务"
      aria-label="开发任务"
    />
    <template v-if="task">
      <div class="mt-3 flex gap-3">
        <NTag>{{ task.status }}</NTag>
        <NTag v-if="task.result?.verification">测试：{{ task.result.verification.status }}</NTag>
        <NButton v-if="!terminal(task.status)" size="small" @click="cancel">停止任务</NButton>
      </div>
      <pre class="mt-3 max-h-48 overflow-auto whitespace-pre-wrap text-12px">{{
        task.summary || '云端正在执行；离开此页面后任务仍会继续。'
      }}</pre>
      <NCollapse class="mt-3">
        <NCollapseItem title="执行记录" name="events">
          <pre v-for="event in task.events" :key="event.id" class="overflow-auto whitespace-pre-wrap text-12px"
            >{{ event.kind }} {{ event.preview }}</pre
          >
        </NCollapseItem>
        <NCollapseItem title="审核 Diff" name="diff">
          <pre class="max-h-120 overflow-auto whitespace-pre text-12px">{{
            task.result?.diff || '暂无可审核的 Diff'
          }}</pre>
        </NCollapseItem>
      </NCollapse>
      <NPopconfirm v-if="canPublish" @positive-click="publish">
        <template #trigger><NButton class="mt-3" type="primary" :loading="busy">确认 Diff 并创建 PR</NButton></template>
        将此 Diff 发布到独立任务分支并创建 GitHub PR？当前测试状态：{{ task.result?.verification?.status }}。
      </NPopconfirm>
      <a
        v-if="task.prUrl && /^https:\/\/github\.com\//.test(task.prUrl)"
        class="mt-3 block"
        :href="task.prUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        查看 GitHub PR
      </a>
    </template>
  </NCard>
</template>
