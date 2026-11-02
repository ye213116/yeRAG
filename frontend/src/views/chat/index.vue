<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
const chatStore = useChatStore();
</script>

<template>
  <div class="mx-auto max-w-1080px w-full flex-col gap-4">
    <div class="flex items-center gap-4">
      <NButton :disabled="!chatStore.memoryReady" @click="chatStore.newConversation">新建对话</NButton>
      <NPopconfirm @positive-click="chatStore.deleteConversation">
        <template #trigger>
          <NButton :disabled="!chatStore.memoryReady || !chatStore.conversationId" type="error" quaternary>
            删除对话
          </NButton>
        </template>
        删除此会话及全部原始问答？此操作无法撤销。
      </NPopconfirm>
      <NSelect
        class="w-72"
        :value="chatStore.conversationId"
        :options="chatStore.sessionOptions"
        :disabled="!chatStore.memoryReady || ['loading', 'pending'].includes(chatStore.list.at(-1)?.status || '')"
        @update:value="chatStore.viewConversation"
      />
      <NButton v-if="!chatStore.memoryReady" @click="chatStore.loadCurrentConversation()">重新加载会话</NButton>
      <NText depth="3">本轮上下文按会话隔离，个人记忆可跨会话使用</NText>
    </div>
    <NAlert v-if="chatStore.memoryNotice" type="info">{{ chatStore.memoryNotice }}</NAlert>
    <NButton v-if="chatStore.hasOlderMessages" :loading="chatStore.historyLoading" @click="chatStore.loadOlderMessages">
      加载更早消息
    </NButton>
    <ChatList />
    <InputBox />
  </div>
</template>

<style scoped></style>
