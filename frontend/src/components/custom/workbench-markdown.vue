<script setup lang="ts">
import MarkdownIt from 'markdown-it';
const props = defineProps<{ content: string }>();
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true });
const original = markdown.renderer.rules.link_open;
markdown.renderer.rules.link_open = (...args) => {
  const [tokens, index, options, env, renderer] = args;
  tokens[index].attrSet('rel', 'noopener noreferrer');
  tokens[index].attrSet('target', '_blank');
  return original ? original(tokens, index, options, env, renderer) : renderer.renderToken(tokens, index, options);
};
const html = computed(() => markdown.render(props.content));
</script>

<template><div class="workbench-markdown" v-html="html"></div></template>

<style scoped>
.workbench-markdown {
  font-size: 14px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}
.workbench-markdown :deep(p) {
  margin: 0 0 14px;
}
.workbench-markdown :deep(h1) {
  font-size: 23px;
  margin: 24px 0 12px;
  font-weight: 600;
}
.workbench-markdown :deep(h2) {
  font-size: 19px;
  margin: 24px 0 12px;
  font-weight: 600;
}
.workbench-markdown :deep(h3) {
  font-size: 16px;
  margin: 20px 0 10px;
  font-weight: 600;
}
.workbench-markdown :deep(pre) {
  overflow: auto;
  padding: 16px;
  border: 1px solid var(--shell-border);
  border-radius: 10px;
  background: var(--side-bg);
  font:
    12px/1.8 ui-monospace,
    monospace;
}
.workbench-markdown :deep(code) {
  font-family: ui-monospace, monospace;
  font-size: 0.9em;
}
.workbench-markdown :deep(table) {
  display: block;
  overflow: auto;
  border-collapse: collapse;
  max-width: 100%;
  margin-bottom: 16px;
}
.workbench-markdown :deep(th),
.workbench-markdown :deep(td) {
  padding: 8px 12px;
  border: 1px solid var(--shell-border);
}
.workbench-markdown :deep(ul),
.workbench-markdown :deep(ol) {
  padding-left: 24px;
  margin-bottom: 16px;
}
.workbench-markdown :deep(a) {
  color: #16966d;
  text-decoration: underline;
}
.workbench-markdown :deep(blockquote) {
  padding-left: 16px;
  border-left: 3px solid var(--shell-border);
  color: var(--shell-muted);
}
.workbench-markdown :deep(img) {
  max-width: 100%;
}
</style>
