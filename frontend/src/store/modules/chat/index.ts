import { useWebSocket } from '@vueuse/core';
import { getServiceBaseURL } from '@/utils/service';
import { interruptPlan, parsePlanTasks } from '@/utils/chat-plan';

const isWebSocketProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { otherBaseURL } = getServiceBaseURL(import.meta.env, isWebSocketProxy);
export interface ConversationSummary {
  id: string;
  title?: string;
  status: string;
  kind?: string;
  last_activity_at: string;
  active_request_id?: string;
  run_status?: string;
}
interface Thread {
  list: Api.Chat.Message[];
  input: Api.Chat.Input;
  source: Api.Chat.SourceMode;
  request: string;
  seq: number;
  ready: boolean;
  loading: boolean;
  more: boolean;
  before?: number;
  revision: number;
  syncing: boolean;
  buffer: Record<string, any>[];
  notice: string;
  scroll: number;
}
function newThread(): Thread {
  return {
    list: [],
    input: { message: '' },
    source: 'auto',
    request: '',
    seq: 0,
    ready: false,
    loading: false,
    more: false,
    revision: 0,
    syncing: false,
    buffer: [],
    notice: '',
    scroll: -1
  };
}

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const auth = useAuthStore();
  const threads = ref<Record<string, Thread>>({});
  const conversationId = ref('');
  const changingConversation = ref(false);
  const sessions = ref<ConversationSummary[]>([]);
  const sessionId = ref('');
  const sessionLoading = ref(false);
  const sessionsError = ref('');
  const hasMoreSessions = ref(false);
  const sessionQuery = ref('');
  let sessionRevision = 0;
  let identity = 0;
  let focusRevision = 0;
  const agentMode = ref<'work' | 'coding'>('work');
  const codingWorkspaceId = ref('');
  const codingWorkspaceReady = ref(false);
  const lastCodingTaskId = ref('');
  const scrollToBottom = ref<null | (() => void)>(null);
  const codingSubmissions = new Map<
    string,
    { resolve: (id: string) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }
  >();
  function thread(id = conversationId.value) {
    if (!threads.value[id]) threads.value[id] = newThread();
    return threads.value[id];
  }
  const list = computed({
    get: () => thread().list,
    set: value => {
      thread().list = value;
    }
  });
  const input = computed({
    get: () => thread().input,
    set: value => {
      thread().input = value;
    }
  });
  const sourceMode = computed({
    get: () => thread().source,
    set: value => {
      thread().source = value;
    }
  });
  const activeRequestId = computed({
    get: () => thread().request,
    set: value => {
      thread().request = value;
    }
  });
  const memoryReady = computed(() => thread().ready && !changingConversation.value);
  const memoryNotice = computed({
    get: () => thread().notice,
    set: value => {
      thread().notice = value;
    }
  });
  const historyReadOnly = ref(false);
  const hasOlderMessages = computed(() => thread().more);
  const historyLoading = computed(() => thread().loading);
  const isSending = computed(() => ['loading', 'pending'].includes(list.value.at(-1)?.status || ''));
  const currentTitle = computed(() => sessions.value.find(s => s.id === conversationId.value)?.title || '新对话');
  const sessionOptions = computed(() =>
    sessions.value.map(s => ({ value: s.id, label: s.title || '新对话', closed: s.status !== 'OPEN' }))
  );
  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(
    computed(() => (auth.token ? `${otherBaseURL.ws.replace(/\/$/, '')}/chat/${auth.token}` : undefined)),
    { autoReconnect: true }
  );
  async function refreshSessions(more = false, query = sessionQuery.value) {
    sessionRevision += 1;
    const current = sessionRevision;
    const owner = identity;
    sessionQuery.value = query;
    sessionLoading.value = true;
    const offset = more ? sessions.value.length : 0;
    const result = await request<ConversationSummary[]>({
      url: 'memory/sessions',
      params: { kind: 'work', q: query, offset, limit: 50 }
    });
    if (owner !== identity || current !== sessionRevision) return;
    sessionLoading.value = false;
    sessionsError.value = result.error ? '会话列表加载失败，请重试' : '';
    if (!result.error) {
      sessions.value = more
        ? [...new Map([...sessions.value, ...result.data].map(s => [s.id, s])).values()]
        : result.data;
      hasMoreSessions.value = result.data.length === 50;
    }
  }
  function restoreAssistant(assistant: Api.Chat.Message, snapshot: Record<string, any>) {
    assistant.status = snapshot.state === 'COMPLETED' ? 'finished' : 'error';
    if (snapshot.state === 'RUNNING') assistant.status = assistant.content ? 'loading' : 'pending';
    assistant.agentStatus = snapshot.agentStatus;
    assistant.planTasks = parsePlanTasks(snapshot.planTasks) || undefined;
    if (snapshot.state === 'CANCELLED') assistant.content ||= '已停止生成';
    if (snapshot.state === 'FAILED')
      assistant.content ||=
        snapshot.failureCode === 'SERVER_RESTARTED'
          ? '服务重启，本轮回答已中断，可重新发送。'
          : '本轮回答未完成，可重新发送。';
  }
  function mergeSnapshot(id: string, snapshot: Record<string, any>) {
    if (!snapshot.requestId || snapshot.state === 'IDLE') return;
    const t = thread(id);
    const existing = t.list.find(m => m.role === 'assistant' && m.requestId === snapshot.requestId);
    if (existing?.status === 'finished' && snapshot.state === 'RUNNING') return;
    t.request = snapshot.requestId;
    t.seq = snapshot.seq || 0;
    if (!t.list.some(m => m.role === 'user' && m.requestId === snapshot.requestId))
      t.list.push({
        role: 'user',
        content: snapshot.question || '',
        requestId: snapshot.requestId,
        turnId: snapshot.turnId
      });
    const assistant: Api.Chat.Message = existing || {
      role: 'assistant',
      content: '',
      requestId: snapshot.requestId,
      turnId: snapshot.turnId
    };
    assistant.content = snapshot.text || '';
    restoreAssistant(assistant, snapshot);
    if (!existing) t.list.push(assistant);
  }
  async function hydrate(id: string) {
    const t = thread(id);
    t.revision += 1;
    const revision = t.revision;
    const owner = identity;
    t.syncing = true;
    t.buffer = [];
    const [history, snapshot] = await Promise.all([
      request<Api.Chat.Message[]>({ url: `memory/sessions/${id}/messages` }),
      request<Record<string, any>>({ url: `memory/sessions/${id}/run` })
    ]);
    if (owner !== identity || t.revision !== revision) return;
    if (!history.error) {
      t.list = history.data;
      t.before = Number(history.data[0]?.turnId) || undefined;
      t.more = history.data.length >= 400;
      t.ready = true;
      t.notice = '';
    } else t.notice = '无法加载历史消息，请重试';
    if (!snapshot.error) mergeSnapshot(id, snapshot.data);
    t.syncing = false;
    const buffered = t.buffer;
    t.buffer = [];
    for (const event of buffered) handleEvent(event);
  }
  async function loadCurrentConversation(fresh = false) {
    const owner = identity;
    focusRevision += 1;
    const focus = focusRevision;
    const response = await request<{ conversationId: string }>({
      url: fresh ? 'memory/sessions' : 'memory/sessions/current',
      method: fresh ? 'post' : 'get'
    });
    if (owner !== identity) return;
    if (focus !== focusRevision) return;
    if (response.error) {
      memoryNotice.value = '无法加载会话，请稍后重试';
      return;
    }
    conversationId.value = response.data.conversationId;
    await Promise.all([hydrate(conversationId.value), refreshSessions()]);
  }
  async function viewConversation(id: string) {
    focusRevision += 1;
    const owner = identity;
    conversationId.value = id;
    const result = await request({ url: `memory/sessions/${id}/resume`, method: 'post' });
    if (owner !== identity) return;
    if (result.error) {
      thread(id).ready = false;
      thread(id).notice = '会话不存在或无法访问';
      thread(id).list = [];
      return;
    }
    await hydrate(id);
  }
  async function newConversation() {
    if (changingConversation.value) return;
    changingConversation.value = true;
    try {
      await loadCurrentConversation(true);
    } finally {
      changingConversation.value = false;
    }
  }
  async function renameConversation(id: string, title: string) {
    const result = await request({ url: `memory/sessions/${id}`, method: 'patch', data: { title } });
    if (!result.error) await refreshSessions();
    return !result.error;
  }
  async function deleteConversation(id = conversationId.value) {
    const owner = identity;
    const result = await request({ url: `memory/sessions/${id}`, method: 'delete' });
    if (owner !== identity) return;
    if (result.error) {
      thread(id).notice = '删除失败，请稍后重试';
      return;
    }
    threads.value = Object.fromEntries(Object.entries(threads.value).filter(([key]) => key !== id));
    if (id === conversationId.value) await loadCurrentConversation();
    else await refreshSessions();
  }
  async function loadOlderMessages() {
    const id = conversationId.value;
    const t = thread(id);
    const owner = identity;
    if (t.loading || !t.more || !t.before) return;
    t.loading = true;
    const result = await request<Api.Chat.Message[]>({
      url: `memory/sessions/${id}/messages`,
      params: { before: t.before }
    });
    if (owner !== identity) return;
    t.loading = false;
    if (result.error) {
      t.notice = '无法加载更早消息';
      return;
    }
    t.before = Number(result.data[0]?.turnId) || undefined;
    t.more = result.data.length >= 400;
    t.list = [...result.data, ...t.list];
  }
  function handleCodingEvent(data: Record<string, any>) {
    const submission = data.requestId ? codingSubmissions.get(data.requestId) : null;
    if (submission) {
      if (data.taskId || data.type === 'request_rejected' || data.error) {
        clearTimeout(submission.timer);
        codingSubmissions.delete(data.requestId);
        if (data.taskId) {
          lastCodingTaskId.value = data.taskId;
          submission.resolve(data.taskId);
        } else submission.reject(new Error(data.message || data.error || '任务提交失败'));
      }
      return true;
    }
    return false;
  }
  function updateAssistant(assistant: Api.Chat.Message, data: Record<string, any>) {
    if (data.type === 'request_rejected' || data.error) {
      interruptPlan(assistant, 'failed');
      assistant.status = 'error';
      assistant.content = data.message || data.error;
      refreshSessions();
    } else if (data.type === 'agent_status') {
      assistant.agentStatus = data.message;
      if (data.status === 'plan_updated')
        assistant.planTasks = parsePlanTasks(data.details?.tasks) || assistant.planTasks;
    } else if (data.type === 'completion') {
      interruptPlan(assistant, data.status === 'cancelled' ? 'cancelled' : 'skipped');
      assistant.status = data.status === 'finished' ? 'finished' : 'error';
      if (data.status !== 'finished') assistant.content ||= data.status === 'cancelled' ? '已停止生成' : '回答已中断';
      refreshSessions();
    } else if (data.chunk) {
      assistant.content += data.chunk;
      assistant.status = 'loading';
    }
  }
  function eventAssistant(t: Thread, data: Record<string, any>) {
    if (data.requestId !== t.request) return null;
    const assistant = t.list.find(m => m.role === 'assistant' && m.requestId === data.requestId);
    if (!assistant || ['finished', 'error'].includes(assistant.status || '')) return null;
    if (data.seq && data.seq <= t.seq) return null;
    if (data.seq) t.seq = data.seq;
    return assistant;
  }
  function handleEvent(data: Record<string, any>) {
    if (data.type === 'connection') {
      sessionId.value = data.sessionId;
      if (conversationId.value) {
        hydrate(conversationId.value);
        refreshSessions();
      } else loadCurrentConversation();
      return;
    }
    if (handleCodingEvent(data)) return;
    if (!data.conversationId || !data.requestId || data.taskId) return;
    const t = thread(data.conversationId);
    if (t.syncing) {
      t.buffer.push(data);
      return;
    }
    if (data.type === 'run_snapshot') {
      mergeSnapshot(data.conversationId, data);
      return;
    }
    if (data.type === 'run_started' && data.requestId !== t.request) {
      t.request = data.requestId;
      t.seq = 0;
      t.list.push({ role: 'user', content: data.question || '', requestId: data.requestId });
      t.list.push({ role: 'assistant', content: '', status: 'pending', requestId: data.requestId });
    }
    const assistant = eventAssistant(t, data);
    if (!assistant) return;
    updateAssistant(assistant, data);
    const summary = sessions.value.find(s => s.id === data.conversationId);
    if (summary) summary.run_status = ['pending', 'loading'].includes(assistant.status || '') ? 'RUNNING' : 'IDLE';
  }
  watch(wsData, value => {
    if (value) {
      try {
        handleEvent(JSON.parse(value));
      } catch {
        /* Ignore malformed transport frames. */
      }
    }
  });
  function sendMessage() {
    const t = thread();
    if (!t.ready || isSending.value || !t.input.message.trim() || wsStatus.value !== 'OPEN') return;
    const message = t.input.message;
    t.request = crypto.randomUUID();
    t.seq = 0;
    t.list.push({ role: 'user', content: message, requestId: t.request, timestamp: new Date().toISOString() });
    t.list.push({ role: 'assistant', content: '', status: 'pending', requestId: t.request });
    wsSend(
      JSON.stringify({
        type: 'chat',
        message,
        conversationId: conversationId.value,
        requestId: t.request,
        sourceMode: t.source,
        agentMode: 'work'
      })
    );
    t.input.message = '';
    const summary = sessions.value.find(s => s.id === conversationId.value);
    if (summary) {
      summary.run_status = 'RUNNING';
      summary.title ||= message.slice(0, 100);
    }
  }
  async function stopResponse() {
    const t = thread();
    if (!t.request) return;
    await request({ url: `memory/sessions/${conversationId.value}/runs/${t.request}/stop`, method: 'post' });
  }
  function submitCoding({
    message,
    conversationId: id,
    workspaceId: workspace,
    requestId,
    sourceMode: source = 'auto'
  }: {
    message: string;
    conversationId: string;
    workspaceId: string;
    requestId: string;
    sourceMode?: Api.Chat.SourceMode;
  }) {
    return new Promise<string>((resolve, reject) => {
      if (wsStatus.value !== 'OPEN') {
        reject(new Error('连接尚未就绪，请重试'));
        return;
      }
      const timer = setTimeout(() => {
        codingSubmissions.delete(requestId);
        reject(new Error('提交确认超时，请刷新任务列表后重试'));
      }, 30000);
      codingSubmissions.set(requestId, { resolve, reject, timer });
      wsSend(
        JSON.stringify({
          type: 'chat',
          message,
          conversationId: id,
          requestId,
          agentMode: 'coding',
          workspaceId: workspace,
          sourceMode: source
        })
      );
    });
  }
  function reset() {
    identity += 1;
    sessionRevision += 1;
    threads.value = {};
    sessions.value = [];
    conversationId.value = '';
    sessionId.value = '';
    sessionLoading.value = false;
    changingConversation.value = false;
    hasMoreSessions.value = false;
    sessionsError.value = '';
    sessionQuery.value = '';
    agentMode.value = 'work';
    codingWorkspaceId.value = '';
    codingWorkspaceReady.value = false;
    lastCodingTaskId.value = '';
    for (const submission of codingSubmissions.values()) {
      clearTimeout(submission.timer);
      submission.reject(new Error('账号已切换'));
    }
    codingSubmissions.clear();
  }
  watch(() => auth.userInfo.id, reset, { flush: 'sync' });
  return {
    input,
    list,
    sourceMode,
    conversationId,
    sessions,
    currentTitle,
    sessionOptions,
    sessionLoading,
    sessionsError,
    sessionQuery,
    hasMoreSessions,
    memoryReady,
    memoryNotice,
    historyReadOnly,
    hasOlderMessages,
    historyLoading,
    isSending,
    activeRequestId,
    refreshSessions,
    viewConversation,
    loadCurrentConversation,
    newConversation,
    renameConversation,
    deleteConversation,
    loadOlderMessages,
    sendMessage,
    stopResponse,
    submitCoding,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    sessionId,
    scrollToBottom,
    threads,
    agentMode,
    codingWorkspaceId,
    codingWorkspaceReady,
    lastCodingTaskId
  };
});
