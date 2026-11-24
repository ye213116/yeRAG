<script setup lang="ts">
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
const chat = useChatStore();
const route = useRoute();
watch(
  () => route.query.conversation,
  async id => {
    if (typeof id === 'string') await chat.viewConversation(id);
    else if (!chat.conversationId) await chat.loadCurrentConversation();
  },
  { immediate: true }
);
</script>

<template>
  <section
    class="work-chat"
    :class="{ 'is-empty': !chat.list.length }"
    aria-label="Work 聊天"
    :data-ready="chat.memoryReady"
    :data-conversation-id="chat.conversationId"
  >
    <div v-if="!chat.list.length" class="chat-welcome">
      <div class="welcome-mark">y</div>
      <h1>今天想完成什么？</h1>
      <p>问问题、查资料，或把想法变成成果。</p>
    </div>
    <ChatList v-else :key="chat.conversationId" />
    <div class="composer-region">
      <NAlert v-if="chat.memoryNotice" type="warning" class="mb-3">
        {{ chat.memoryNotice }}
        <NButton text @click="chat.viewConversation(chat.conversationId)">重新加载</NButton>
      </NAlert>
      <InputBox />
      <p class="composer-caption">yeRAG 可能会出错，重要信息请结合来源核对。</p>
    </div>
  </section>
</template>

<style scoped>
.work-chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.work-chat.is-empty {
  justify-content: center;
  padding-bottom: 12vh;
}
.chat-welcome {
  text-align: center;
  margin: 0 0 30px;
}
.welcome-mark {
  font-size: 38px;
  font-weight: 750;
  color: #16966d;
  letter-spacing: -4px;
  margin-bottom: 15px;
}
.chat-welcome h1 {
  font-size: 30px;
  font-weight: 550;
  letter-spacing: -1px;
}
.chat-welcome p {
  font-size: 14px;
  color: var(--shell-muted);
  margin-top: 10px;
}
.composer-region {
  width: min(800px, calc(100% - 56px));
  margin: 0 auto;
  padding: 14px 0 12px;
  flex: none;
}
.composer-caption {
  font-size: 11px;
  text-align: center;
  color: var(--shell-muted);
  margin-top: 10px;
}
@media (max-width: 899px) {
  .composer-region {
    width: calc(100% - 28px);
  }
  .chat-welcome h1 {
    font-size: 25px;
  }
}
</style>
