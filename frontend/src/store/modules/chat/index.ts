import { useWebSocket } from '@vueuse/core';
import { getServiceBaseURL } from '@/utils/service';

const isWebSocketProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { otherBaseURL } = getServiceBaseURL(import.meta.env, isWebSocketProxy);

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const memoryReady = ref(false);
  const activeRequestId = ref('');
  const memoryNotice = ref('');
  const historyReadOnly = ref(false);
  const sessionOptions = ref<Array<{ label: string; value: string; closed: boolean }>>([]);
  let historyRevision = 0;
  const input = ref<Api.Chat.Input>({ message: '' });
  const list = ref<Api.Chat.Message[]>([]);

  async function loadCurrentConversation(fresh = false) {
    historyRevision += 1;
    const revision = historyRevision;
    memoryReady.value = false;
    const { error, data } = await request<{ conversationId: string }>({
      url: fresh ? 'memory/sessions' : 'memory/sessions/current',
      method: fresh ? 'post' : 'get'
    });
    if (revision !== historyRevision) return;
    if (error) {
      memoryNotice.value = '无法加载会话，请稍后重试';
      return;
    }
    conversationId.value = data.conversationId;
    historyReadOnly.value = false;
    activeRequestId.value = '';
    const history = await request<Api.Chat.Message[]>({ url: `memory/sessions/${data.conversationId}/messages` });
    if (revision !== historyRevision) return;
    if (!history.error) list.value = history.data;
    else {
      list.value = [];
      memoryNotice.value = '暂时无法加载历史消息';
    }
    memoryReady.value = true;
    const sessions = await request<Array<{ id: string; status: string; last_activity_at: string }>>({
      url: 'memory/sessions'
    });
    if (!sessions.error && revision === historyRevision)
      sessionOptions.value = sessions.data.map(item => ({
        value: item.id,
        closed: item.status !== 'OPEN',
        label: `${item.status === 'OPEN' ? '当前对话' : '历史对话'} · ${item.last_activity_at}`
      }));
  }

  async function viewConversation(id: string) {
    historyRevision += 1;
    const revision = historyRevision;
    activeRequestId.value = '';
    memoryReady.value = false;
    const history = await request<Api.Chat.Message[]>({ url: `memory/sessions/${id}/messages` });
    if (revision !== historyRevision) return;
    if (!history.error) {
      conversationId.value = id;
      list.value = history.data;
      historyReadOnly.value = sessionOptions.value.find(item => item.value === id)?.closed ?? true;
    } else memoryNotice.value = '无法加载该会话';
    memoryReady.value = true;
  }

  async function newConversation() {
    activeRequestId.value = '';
    memoryNotice.value = '';
    await loadCurrentConversation(true);
  }
  const store = useAuthStore();

  const sessionId = ref<string>(''); // WebSocket session ID

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`${otherBaseURL.ws.replace(/\/$/, '')}/chat/${store.token}`, {
    autoReconnect: true
  });

  // 监听WebSocket消息，捕获sessionId
  watch(wsData, val => {
    if (!val) return;
    try {
      const data = JSON.parse(val);
      if (data.type === 'connection' && data.sessionId) {
        sessionId.value = data.sessionId;
        loadCurrentConversation();
      }
      if (data.requestId && data.requestId === activeRequestId.value) {
        if (data.type === 'conversation') {
          conversationId.value = data.conversationId;
          if (data.switched) {
            list.value = list.value.slice(-2);
            memoryNotice.value = '由于长时间未交互，已开始新对话';
            sessionOptions.value = sessionOptions.value.map(item => ({ ...item, closed: true }));
            sessionOptions.value.unshift({ value: data.conversationId, label: '当前对话', closed: false });
            historyReadOnly.value = false;
          }
        }
        if (data.type === 'context_reduced' || data.type === 'request_rejected') memoryNotice.value = data.message;
      }
    } catch {
      // Ignore JSON parse errors for non-JSON messages
    }
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  return {
    input,
    conversationId,
    memoryReady,
    activeRequestId,
    memoryNotice,
    historyReadOnly,
    sessionOptions,
    viewConversation,
    loadCurrentConversation,
    newConversation,
    list,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    sessionId,
    scrollToBottom
  };
});
