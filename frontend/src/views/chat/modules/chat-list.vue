<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import type MarkdownIt from 'markdown-it';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, sessionId } = storeToRefs(chatStore);

const markdownOptions = {
  theme: 'dracula-soft',
  defaultHighlightLang: 'javascript',
  config(md: MarkdownIt) {
    const renderLink = md.renderer.rules.link_open;
    md.renderer.rules.link_open = (...args) => {
      const [tokens, index, options, , renderer] = args;
      const href = tokens[index].attrGet('href') || '';
      // The library treats internal URLs as static documentation and appends .html.
      // Report URLs belong to Vue Router and must retain their API-provided path.
      if (/^\/artifacts\?id=[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(href)) {
        return renderer.renderToken(tokens, index, options);
      }
      return renderLink ? renderLink(...args) : renderer.renderToken(tokens, index, options);
    };
  }
};

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <VueMarkdownItProvider :options="markdownOptions">
        <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" :session-id="sessionId" />
      </VueMarkdownItProvider>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss"></style>
