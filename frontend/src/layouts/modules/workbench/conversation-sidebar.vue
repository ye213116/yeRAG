<script setup lang="ts">
const chat = useChatStore();
const router = useRouter();
const query = ref('');
const renameId = ref('');
const title = ref('');
const renameOpen = ref(false);
let timer: ReturnType<typeof setTimeout>;
watch(query, value => {
  clearTimeout(timer);
  timer = setTimeout(() => chat.refreshSessions(false, value), 250);
});
onUnmounted(() => clearTimeout(timer));
function select(id: string) {
  router.push({ path: '/chat', query: { conversation: id } });
}
async function create() {
  await chat.newConversation();
  select(chat.conversationId);
}
function actions(id: string, value: string) {
  if (value === 'rename') {
    renameId.value = id;
    title.value = chat.sessions.find(s => s.id === id)?.title || '';
    renameOpen.value = true;
  } else
    window.$dialog?.warning({
      title: '删除会话',
      content: '删除此会话及全部问答？运行中的回答也会停止。',
      positiveText: '删除',
      negativeText: '取消',
      onPositiveClick: async () => {
        await chat.deleteConversation(id);
        if (router.currentRoute.value.name === 'chat') select(chat.conversationId);
      }
    });
}
async function rename() {
  if (await chat.renameConversation(renameId.value, title.value)) renameOpen.value = false;
}
</script>

<template>
  <div class="context-list">
    <button class="context-action" @click="create">
      <SvgIcon icon="lucide:square-pen" />
      新聊天
    </button>
    <NInput
      v-model:value="query"
      size="small"
      clearable
      placeholder="搜索会话"
      :input-props="{ 'aria-label': '搜索会话' }"
    >
      <template #prefix><SvgIcon icon="lucide:search" /></template>
    </NInput>
    <div class="context-section-label">最近的会话</div>
    <div v-if="chat.sessionsError" class="context-empty">
      <p>{{ chat.sessionsError }}</p>
      <NButton text @click="chat.refreshSessions()">重试</NButton>
    </div>
    <div v-else-if="!chat.sessions.length && !chat.sessionLoading" class="context-empty">从一个新问题开始</div>
    <div
      v-for="item in chat.sessions"
      :key="item.id"
      class="thread-row"
      :data-conversation-id="item.id"
      :class="{ selected: chat.conversationId === item.id }"
    >
      <button
        class="thread-select"
        :title="item.title || '新对话'"
        :aria-current="chat.conversationId === item.id ? 'page' : undefined"
        @click="select(item.id)"
      >
        <span>{{ item.title || '新对话' }}</span>
        <span v-if="item.run_status === 'RUNNING'" class="running-indicator" aria-label="后台回答中"></span>
      </button>
      <NDropdown
        trigger="click"
        :options="[
          { label: '重命名', key: 'rename' },
          { label: '删除', key: 'delete' }
        ]"
        @select="actions(item.id, $event)"
      >
        <button class="icon-control row-more" :aria-label="`管理会话 ${item.title || '新对话'}`">
          <SvgIcon icon="lucide:ellipsis" />
        </button>
      </NDropdown>
    </div>
    <NButton
      v-if="chat.hasMoreSessions"
      class="mt-3"
      text
      :loading="chat.sessionLoading"
      @click="chat.refreshSessions(true)"
    >
      加载更多会话
    </NButton>
    <NSpin v-if="chat.sessionLoading" class="self-center py-4" size="small" />
    <NModal
      v-model:show="renameOpen"
      preset="dialog"
      title="重命名会话"
      positive-text="保存"
      negative-text="取消"
      @positive-click="rename"
    >
      <NInput
        v-model:value="title"
        :maxlength="100"
        :input-props="{ 'aria-label': '会话标题' }"
        @keydown.enter.prevent="rename"
      />
    </NModal>
  </div>
</template>
