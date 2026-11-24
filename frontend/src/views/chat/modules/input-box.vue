<script setup lang="ts">
const chat = useChatStore();
const textarea = ref<HTMLTextAreaElement>();
const sourceOptions = [
  { label: '自动选择来源', value: 'auto' },
  { label: '仅知识库', value: 'knowledge' },
  { label: '仅联网', value: 'web' }
];
const disabled = computed(
  () => chat.wsStatus !== 'OPEN' || !chat.memoryReady || (!chat.isSending && !chat.input.message.trim())
);
function send() {
  if (disabled.value) return;
  if (chat.isSending) chat.stopResponse();
  else chat.sendMessage();
}
function shortcut(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.isComposing && !event.shiftKey && !event.ctrlKey) {
    event.preventDefault();
    send();
  }
}
function resize() {
  if (textarea.value) {
    textarea.value.style.height = 'auto';
    textarea.value.style.height = `${Math.min(180, textarea.value.scrollHeight)}px`;
  }
}
watch(
  () => chat.input.message,
  () => nextTick(resize)
);
</script>

<template>
  <div class="work-composer">
    <textarea
      ref="textarea"
      v-model="chat.input.message"
      rows="1"
      aria-label="消息内容"
      placeholder="向 yeRAG 提问，或描述你要完成的工作"
      @keydown="shortcut"
      @input="resize"
    />
    <div class="composer-toolbar">
      <NSelect
        v-model:value="chat.sourceMode"
        :options="sourceOptions"
        :disabled="chat.isSending"
        :bordered="false"
        class="source-select"
        size="small"
        aria-label="信息来源"
      />
      <span class="composer-shortcut">Shift + Enter 换行</span>
      <button
        class="send-control"
        :disabled="disabled"
        :aria-label="chat.isSending ? '停止生成' : '发送消息'"
        @click="send"
      >
        <SvgIcon :icon="chat.isSending ? 'lucide:square' : 'lucide:arrow-up'" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.work-composer {
  border: 1px solid var(--shell-border);
  border-radius: 24px;
  padding: 16px 16px 11px;
  background: var(--shell-bg);
  box-shadow: 0 3px 16px #00000005;
  transition: border-color 0.15s;
}
.work-composer:focus-within {
  border-color: #aaaab2;
}
textarea {
  display: block;
  width: 100%;
  resize: none;
  min-height: 36px;
  max-height: 180px;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--shell-text);
  font-family: inherit;
  font-size: 15px;
  line-height: 1.7;
  padding: 0 4px 6px;
  overflow: auto;
}
textarea::placeholder {
  color: var(--shell-muted);
}
.composer-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.source-select {
  width: 160px;
}
.composer-shortcut {
  font-size: 10px;
  color: var(--shell-muted);
  margin-left: auto;
}
.send-control {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: var(--shell-text);
  color: var(--shell-bg);
  display: grid;
  place-items: center;
  cursor: pointer;
  font-size: 19px;
  flex: none;
}
.send-control:disabled {
  opacity: 0.25;
}
@media (max-width: 600px) {
  .composer-shortcut {
    display: none;
  }
  .send-control {
    margin-left: auto;
  }
}
</style>
