<script setup lang="ts">
import { useResizeObserver } from '@vueuse/core';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import type MarkdownIt from 'markdown-it';
import ChatMessage from './chat-message.vue';
const chat = useChatStore();
const theme = useThemeStore();
const viewport = ref<HTMLElement>();
const column = ref<HTMLElement>();
const owner = chat.conversationId;
const initialPosition = chat.threads[owner]?.scroll ?? -1;
const following = ref(initialPosition < 0);
let restored = false;
const markdownOptions = computed(() => ({
  theme: theme.darkMode ? ('github-dark' as const) : ('github-light' as const),
  defaultHighlightLang: 'text',
  config(md: MarkdownIt) {
    const render = md.renderer.rules.link_open;
    md.renderer.rules.link_open = (...args) => {
      const [tokens, index, options, , renderer] = args;
      const href = tokens[index].attrGet('href') || '';
      if (/^\/artifacts\?id=[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(href))
        return renderer.renderToken(tokens, index, options);
      return render ? render(...args) : renderer.renderToken(tokens, index, options);
    };
  }
}));
function scrolled() {
  const el = viewport.value;
  if (!el || !restored) return;
  following.value = el.scrollHeight - el.clientHeight - el.scrollTop < 100;
  if (chat.threads[owner]) chat.threads[owner].scroll = el.scrollTop;
}
function bottom() {
  if (restored && following.value)
    nextTick(() => {
      const el = viewport.value;
      if (el) el.scrollTop = el.scrollHeight;
    });
}
watch(() => [chat.list.length, chat.list.at(-1)?.content.length], bottom, { flush: 'post' });
useResizeObserver(column, bottom);
async function restoreScroll() {
  await nextTick();
  const el = viewport.value;
  if (!el || restored) return;
  el.scrollTop = initialPosition >= 0 ? initialPosition : el.scrollHeight;
  restored = true;
  scrolled();
}
onMounted(() => {
  chat.scrollToBottom = bottom;
});
onBeforeUnmount(() => {
  if (restored && viewport.value && chat.threads[owner]) chat.threads[owner].scroll = viewport.value.scrollTop;
});
onUnmounted(() => {
  if (chat.scrollToBottom === bottom) chat.scrollToBottom = null;
});
</script>

<template>
  <div ref="viewport" class="message-viewport" @scroll="scrolled">
    <div ref="column" class="message-column">
      <NButton v-if="chat.hasOlderMessages" text :loading="chat.historyLoading" @click="chat.loadOlderMessages">
        加载更早消息
      </NButton>
      <Suspense @resolve="restoreScroll">
        <VueMarkdownItProvider :options="markdownOptions">
          <ChatMessage
            v-for="(message, index) in chat.list"
            :key="`${message.requestId || message.turnId || index}-${message.role}`"
            :msg="message"
            :session-id="chat.conversationId"
          />
        </VueMarkdownItProvider>
      </Suspense>
    </div>
  </div>
</template>

<style scoped>
.message-viewport {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  overflow-anchor: none;
  scrollbar-gutter: stable;
}
.message-column {
  max-width: 800px;
  margin: 0 auto;
  padding: 28px 28px 4px;
}
@media (max-width: 899px) {
  .message-column {
    padding: 18px 18px 4px;
  }
}
</style>
