<script setup lang="ts">
import { interruptPlan, parsePlanTasks } from '@/utils/chat-plan';

const chatStore = useChatStore();
const { input, list, wsStatus, wsData, memoryReady, activeRequestId, sourceMode } = storeToRefs(chatStore);
const sourceOptions = [
  { label: '自动', value: 'auto' },
  { label: '仅知识库', value: 'knowledge' },
  { label: '仅联网', value: 'web' }
];

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () =>
    (!input.value.message && !isSending.value) ||
    ['CLOSED', 'CONNECTING'].includes(wsStatus.value) ||
    !memoryReady.value ||
    chatStore.historyReadOnly
);

function handleAgentStatus(
  assistant: Api.Chat.Message,
  event: { status?: string; requestId?: string; message?: string; details?: { tool?: string; tasks?: unknown } }
) {
  if (event.status === 'plan_updated' && event.requestId && event.requestId === activeRequestId.value) {
    const tasks = parsePlanTasks(event.details?.tasks);
    if (tasks) assistant.planTasks = tasks;
    chatStore.scrollToBottom?.();
  }
  const toolLabels: Record<string, string> = {
    search_knowledge_base: '搜索知识库',
    read_knowledge_document: '阅读知识库原文',
    search_web: '搜索公开网络',
    read_web_page: '阅读网页正文'
  };
  assistant.agentStatus = toolLabels[event.details?.tool ?? ''] || event.message;
}

watch(wsData, val => {
  if (!val) return;
  let data;
  try {
    data = JSON.parse(val);
  } catch {
    return;
  }
  if (data.requestId && data.requestId !== activeRequestId.value) return;
  if (data.conversationId && data.conversationId !== chatStore.conversationId) return;
  const assistant = list.value[list.value.length - 1];
  if (!assistant || assistant.role !== 'assistant') return;
  if (assistant.status === 'finished' || assistant.status === 'error') return;
  if (data.type === 'request_rejected') {
    interruptPlan(assistant, 'failed');
    assistant.status = 'error';
    assistant.content = data.message;
    return;
  }
  if (data.type === 'agent_status') {
    handleAgentStatus(assistant, data);
    return;
  }

  if (data.type === 'completion' && data.status === 'finished') {
    interruptPlan(assistant, 'skipped');
    assistant.status = 'finished';
  }
  if (data.error) {
    interruptPlan(assistant, 'failed');
    assistant.status = 'error';
    assistant.content = data.error;
  } else if (data.chunk) {
    assistant.status = 'loading';
    assistant.content += data.chunk;
  }
});

const handleSend = async () => {
  if (sendable.value) return;
  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));
    activeRequestId.value = '';

    const assistant = list.value[list.value.length - 1];
    interruptPlan(assistant, 'cancelled');
    assistant.status = 'finished';
    if (!assistant.content && assistant.planTasks?.length) assistant.content = '已停止生成';
    if (!assistant.content) list.value.pop();
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  activeRequestId.value = crypto.randomUUID();
  chatStore.wsSend(
    JSON.stringify({
      type: 'chat',
      message: input.value.message,
      conversationId: chatStore.conversationId,
      requestId: activeRequestId.value,
      sourceMode: sourceMode.value
    })
  );
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  input.value.message = '';
};

const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};
</script>

<template>
  <div class="input-card relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 card-wrapper dark:bg-#1c1c1c">
    <textarea
      ref="inputRef"
      v-model.trim="input.message"
      placeholder="给 yeRAG 发送消息"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="flex items-center gap-2 pb-2">
      <NText depth="3">信息来源</NText>
      <NSelect
        v-model:value="sourceMode"
        class="w-36"
        size="small"
        :options="sourceOptions"
        :disabled="isSending || chatStore.historyReadOnly"
        aria-label="信息来源"
      />
      <NText v-if="sourceMode === 'auto'" depth="3" class="text-12px">内部资料优先，需要公开或最新信息时联网</NText>
    </div>
    <div class="flex items-center justify-between pt-2">
      <div class="flex items-center text-18px color-gray-500">
        <NText class="text-14px">连接状态：</NText>
        <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
      </div>
      <NButton
        :disabled="sendable"
        :aria-label="isSending ? '停止生成' : '发送消息'"
        strong
        circle
        type="primary"
        @click="handleSend"
      >
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" />
          <icon-guidance:send v-else />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.input-card {
  border-radius: 18px;
  box-shadow: 0 6px 24px rgb(0 0 0 / 0.05);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.input-card:focus-within {
  border-color: rgb(var(--primary-color) / 0.55);
  box-shadow: 0 6px 24px rgb(var(--primary-color) / 0.12);
}
</style>
