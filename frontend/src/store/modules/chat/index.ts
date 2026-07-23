import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);

  const store = useAuthStore();

  const sessionId = ref<string>(''); // WebSocket session ID

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true
  });

  // 监听WebSocket消息，捕获sessionId
  watch(wsData, (val) => {
    if (!val) return;
    try {
      const data = JSON.parse(val);
      if (data.type === 'connection' && data.sessionId) {
        sessionId.value = data.sessionId;
        console.log('WebSocket会话ID已更新:', sessionId.value);
      }
    } catch (e) {
      // Ignore JSON parse errors for non-JSON messages
    }
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  return {
    input,
    conversationId,
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
