import { useRouter } from 'vue-router';
import {
  cancelCodingTask,
  createCodingWorkspace,
  fetchCodingCapabilities,
  fetchCodingRepositories,
  fetchCodingTask,
  fetchCodingTasks,
  fetchCodingWorkspaces,
  publishCodingTask
} from '@/service/api/coding';
import type { CodingRepository, CodingTask, CodingTaskSummary, WorkspacePermission } from '@/service/api/coding';
import { useChatStore } from '@/store/modules/chat';

export const codingStatusLabels: Record<string, string> = {
  QUEUED: '排队中',
  PREPARING: '准备环境',
  RUNNING: '执行中',
  REVIEW_READY: '待审核',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '已超时',
  INTERRUPTED: '已中断'
};
export const codingActive = (status: string) => ['QUEUED', 'PREPARING', 'RUNNING'].includes(status);
interface Draft {
  message: string;
  branch: string;
  preset: 'read' | 'edit';
  source: Api.Chat.SourceMode;
  bootstrap?: { request: string; fingerprint: string; workspace?: string; conversation?: string };
}
export const useCodingStore = defineStore('coding-workbench', () => {
  const auth = useAuthStore();
  const chat = useChatStore();
  const router = useRouter();
  const repositories = ref<CodingRepository[]>([]);
  const tasks = ref<CodingTaskSummary[]>([]);
  const projectId = ref('');
  const selectedTaskId = ref('');
  const task = ref<CodingTask | null>(null);
  const drafts = ref<Record<string, Draft>>({});
  const enabled = ref(false);
  const loaded = ref(false);
  const loading = ref(false);
  const detailLoading = ref(false);
  const submitting = ref(false);
  const notice = ref('');
  const error = ref('');
  const inspectorOpen = ref(false);
  const inspectorTab = ref('changes');
  let epoch = 0;
  let detailRevision = 0;
  let polling = false;
  const currentProject = computed(() => repositories.value.find(r => r.id === projectId.value));
  const draft = computed(() => {
    if (!drafts.value[projectId.value])
      drafts.value[projectId.value] = {
        message: '',
        branch: currentProject.value?.branches[0] || '',
        preset: 'edit',
        source: 'auto'
      };
    return drafts.value[projectId.value];
  });
  const projectTasks = (id: string) => tasks.value.filter(t => t.repository_id === id);
  async function refresh() {
    if (polling || !enabled.value) return;
    polling = true;
    const owner = epoch;
    try {
      const [list, workspaces] = await Promise.all([fetchCodingTasks(), fetchCodingWorkspaces()]);
      if (owner !== epoch) return;
      if (list.error) {
        error.value = '任务列表加载失败，请重试';
        return;
      }
      const byId = new Map((workspaces.data || []).map(w => [w.id, w]));
      tasks.value = list.data.map(t => ({
        ...t,
        repository_id: t.repository_id || byId.get(t.workspace_id)?.repositoryId
      }));
      error.value = workspaces.error ? '部分项目信息加载失败，请重试' : '';
      if (selectedTaskId.value) await readTask(selectedTaskId.value, false);
    } finally {
      polling = false;
    }
  }
  async function load() {
    const owner = epoch;
    loading.value = true;
    error.value = '';
    try {
      const capabilities = await fetchCodingCapabilities();
      if (owner !== epoch) return;
      loaded.value = true;
      if (capabilities.error) {
        error.value = '无法连接 Coding 服务，请重试';
        enabled.value = false;
        return;
      }
      enabled.value = capabilities.data.enabled;
      if (!enabled.value) return;
      const result = await fetchCodingRepositories();
      if (owner !== epoch) return;
      if (result.error) {
        error.value = '无法加载已授权项目';
        return;
      }
      repositories.value = result.data;
      if (!result.data.some(r => r.id === projectId.value)) projectId.value = result.data[0]?.id || '';
      await refresh();
    } finally {
      if (owner === epoch) loading.value = false;
    }
  }
  async function readTask(id: string, clear = true) {
    const owner = epoch;
    detailRevision += 1;
    const revision = detailRevision;
    if (clear) {
      task.value = null;
      detailLoading.value = true;
    }
    const result = await fetchCodingTask(id);
    if (owner !== epoch || revision !== detailRevision || id !== selectedTaskId.value) return;
    detailLoading.value = false;
    if (result.error) {
      task.value = null;
      error.value = '任务不存在或无法访问，请重新选择';
    } else task.value = result.data;
  }
  function selectProject(id: string) {
    if (!repositories.value.some(r => r.id === id)) return;
    projectId.value = id;
    selectedTaskId.value = '';
    task.value = null;
    inspectorOpen.value = false;
    router.push({ path: '/coding', query: { project: id } });
  }
  function selectTask(id: string) {
    const summary = tasks.value.find(t => t.id === id);
    if (summary?.repository_id) projectId.value = summary.repository_id;
    selectedTaskId.value = id;
    router.push({ path: '/coding', query: { project: projectId.value, task: id } });
    readTask(id);
  }
  async function restore(project?: string, id?: string) {
    if (!loaded.value) await load();
    else if (enabled.value) await refresh();
    if (project && repositories.value.some(r => r.id === project)) projectId.value = project;
    if (id) {
      selectedTaskId.value = id;
      await readTask(id);
    } else {
      selectedTaskId.value = '';
      task.value = null;
    }
  }
  async function prepare(d: Draft, project: string, owner: number) {
    const fingerprint = JSON.stringify([project, d.branch, d.preset, d.message, d.source]);
    if (!d.bootstrap || d.bootstrap.fingerprint !== fingerprint)
      d.bootstrap = { request: crypto.randomUUID(), fingerprint };
    const bootstrap = d.bootstrap;
    const permissions: WorkspacePermission[] = d.preset === 'edit' ? ['READ', 'WRITE', 'EXECUTE'] : ['READ'];
    if (!bootstrap.workspace) {
      const workspace = await createCodingWorkspace({ repositoryId: project, branch: d.branch, permissions });
      if (workspace.error) throw new Error('无法准备项目，请检查仓库授权或执行环境');
      if (owner !== epoch) return null;
      bootstrap.workspace = workspace.data.id;
    }
    if (!bootstrap.conversation) {
      const session = await request<{ conversationId: string }>({
        url: 'memory/sessions',
        method: 'post',
        data: { kind: 'coding' }
      });
      if (session.error) throw new Error('无法创建任务会话，请重试');
      if (owner !== epoch) return null;
      bootstrap.conversation = session.data.conversationId;
    }
    return bootstrap;
  }
  async function submit() {
    if (submitting.value || !draft.value.message.trim() || !currentProject.value || chat.wsStatus !== 'OPEN') return;
    submitting.value = true;
    notice.value = '';
    error.value = '';
    const owner = epoch;
    const project = projectId.value;
    const d = draft.value;
    try {
      if (d.bootstrap?.workspace) {
        await refresh();
        const accepted = tasks.value.find(t => t.workspace_id === d.bootstrap?.workspace);
        if (accepted) {
          selectTask(accepted.id);
          d.message = '';
          d.bootstrap = undefined;
          return;
        }
      }
      const bootstrap = await prepare(d, project, owner);
      if (!bootstrap || owner !== epoch) return;
      const id = await chat.submitCoding({
        message: d.message,
        conversationId: bootstrap.conversation!,
        workspaceId: bootstrap.workspace!,
        requestId: bootstrap.request,
        sourceMode: d.source
      });
      if (owner !== epoch) return;
      d.message = '';
      d.bootstrap = undefined;
      notice.value = '任务已提交，关闭页面后仍会继续执行。';
      await refresh();
      selectTask(id);
    } catch (failure) {
      if (owner === epoch) error.value = failure instanceof Error ? failure.message : '提交失败，草稿已保留';
    } finally {
      if (owner === epoch) submitting.value = false;
    }
  }
  async function cancel() {
    if (!task.value) return;
    const result = await cancelCodingTask(task.value.id);
    notice.value = result.error ? '停止请求失败，请重试' : '已请求停止，正在清理执行环境';
    await refresh();
  }
  async function publish(snapshot: { taskId: string; baseSha: string; changeSetHash: string; verificationId: string }) {
    const { taskId, ...data } = snapshot;
    const result = await publishCodingTask(taskId, data);
    notice.value = result.error ? 'PR 发布失败，请检查仓库发布授权或基准变化' : 'PR 已创建';
    await refresh();
    return !result.error;
  }
  watch(
    () => auth.userInfo.id,
    () => {
      epoch += 1;
      detailRevision += 1;
      repositories.value = [];
      tasks.value = [];
      drafts.value = {};
      projectId.value = '';
      selectedTaskId.value = '';
      task.value = null;
      loaded.value = false;
      enabled.value = false;
      loading.value = false;
      submitting.value = false;
      notice.value = '';
      error.value = '';
      inspectorOpen.value = false;
    },
    { flush: 'sync' }
  );
  const timer = window.setInterval(() => {
    if (
      loaded.value &&
      enabled.value &&
      (router.currentRoute.value.name === 'coding' || tasks.value.some(t => codingActive(t.status)))
    )
      refresh();
  }, 2500);
  onScopeDispose(() => window.clearInterval(timer));
  return {
    repositories,
    tasks,
    task,
    projectId,
    selectedTaskId,
    drafts,
    draft,
    currentProject,
    enabled,
    loaded,
    loading,
    detailLoading,
    submitting,
    notice,
    error,
    inspectorOpen,
    inspectorTab,
    projectTasks,
    load,
    refresh,
    restore,
    selectProject,
    selectTask,
    readTask,
    submit,
    cancel,
    publish
  };
});
