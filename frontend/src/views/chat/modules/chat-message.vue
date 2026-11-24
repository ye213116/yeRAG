<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { useRouter } from 'vue-router';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import ChatPlan from './chat-plan.vue';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message;
  sessionId?: string;
}>();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const router = useRouter();

const artifactIds = computed(() => {
  if (props.msg.role !== 'assistant') return [];
  const links = (props.msg.content || '').matchAll(
    /(?:^|[\s([（:：])\/artifacts\?id=([a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12})(?=$|[^a-f\d-])/gi
  );
  return [...new Set(Array.from(links, match => match[1]))];
});

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{ fileName: string; id: string; referenceNumber: number; fileMd5?: string }>>([]);

function escapeHtml(value: string): string {
  const entities: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  return value.replace(/[&<>"']/g, character => entities[character]);
}

// Handle both historical citations and the server-generated source appendix once.
function processSourceLinks(text: string): string {
  sourceFiles.value = [];
  const pattern = /([(（])来源#(\d+):\s*([^|\n\r()（）<>]+?)(?:\s*\|\s*MD5:\s*([a-fA-F0-9]{32}))?([)）])/g;
  return text.replace(pattern, (...matches) => {
    const [, leftParen, sourceNum, fileName, fileMd5, rightParen] = matches;
    const fileId = `source-file-${sourceFiles.value.length}`;
    const trimmedName = fileName.trim();
    sourceFiles.value.push({
      id: fileId,
      fileName: trimmedName,
      referenceNumber: Number.parseInt(sourceNum, 10),
      fileMd5
    });
    return `${leftParen}来源#${sourceNum}: <span class="source-file-link" data-file-id="${fileId}">${escapeHtml(trimmedName)}</span>${rightParen}`;
  });
}

const content = computed(() => {
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  if (!(event.target instanceof Element)) return;
  const target = event.target;
  const anchor = target.closest('a[href]');
  if (anchor && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) {
    let url: URL;
    try {
      url = new URL(anchor.getAttribute('href')!, window.location.href);
    } catch {
      return;
    }
    const id = url.searchParams.get('id');
    if (
      url.origin === window.location.origin &&
      url.pathname === '/artifacts' &&
      id &&
      /^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(id)
    ) {
      event.preventDefault();
      router.push({ name: 'artifacts', query: { id } });
      return;
    }
  }

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick({
          fileName: file.fileName,
          referenceNumber: file.referenceNumber,
          fileMd5: file.fileMd5
        });
      }
    }
  }
}

async function resolveFileMd5(fileInfo: { fileMd5?: string; referenceNumber: number }): Promise<string | undefined> {
  if (fileInfo.fileMd5) return fileInfo.fileMd5;
  if (!props.sessionId) return undefined;
  const { error, data } = await request<Api.Document.ReferenceMd5Response>({
    url: 'documents/reference-md5',
    params: { sessionId: props.sessionId, referenceNumber: String(fileInfo.referenceNumber) }
  });
  return error ? undefined : data?.fileMd5;
}

async function downloadSource(fileName: string, fileMd5?: string) {
  const { error, data } = await request<Api.Document.DownloadResponse>({
    url: fileMd5 ? 'documents/download-by-md5' : 'documents/download',
    params: fileMd5 ? { fileMd5 } : { fileName }
  });
  return error ? undefined : data;
}

async function handleSourceFileClick(fileInfo: { fileName: string; referenceNumber: number; fileMd5?: string }) {
  const { fileName } = fileInfo;
  const notice = window.$message?.loading(`正在获取文件下载链接: ${fileName}`, { duration: 0 });
  try {
    const md5 = await resolveFileMd5(fileInfo);
    const exact = md5 ? await downloadSource(fileName, md5) : undefined;
    const data = exact?.downloadUrl ? exact : await downloadSource(fileName);
    if (!data?.downloadUrl) {
      window.$message?.error('未能获取到文件下载链接');
      return;
    }
    window.open(data.downloadUrl, '_blank', 'noopener,noreferrer');
    window.$message?.success(`文件下载链接已打开: ${data.fileName || fileName}`);
  } catch {
    window.$message?.error(`文件下载失败: ${fileName}`);
  } finally {
    notice?.destroy();
  }
}
</script>

<template>
  <article class="chat-message" :class="msg.role">
    <div v-if="msg.role === 'user'" class="user-bubble">{{ content }}</div>
    <template v-else>
      <ChatPlan v-if="msg.planTasks?.length" :tasks="msg.planTasks" />
      <div v-if="msg.status === 'pending'" class="thinking">
        <span class="thinking-dot"></span>
        {{ msg.agentStatus || '正在思考' }}
      </div>
      <div v-else-if="msg.status === 'error'" class="answer-error">{{ msg.content || '本轮回答未完成，请重试' }}</div>
      <div v-else class="assistant-content" @click="handleContentClick"><VueMarkdownIt :content="content" /></div>
      <div v-if="artifactIds.length" class="mt-3 flex flex-wrap gap-2">
        <NButton
          v-for="id in artifactIds"
          :key="id"
          size="small"
          @click="router.push({ name: 'artifacts', query: { id } })"
        >
          {{ artifactIds.length === 1 ? '打开报告' : `打开报告 ${artifactIds.indexOf(id) + 1}` }}
        </NButton>
      </div>
      <div v-if="msg.status !== 'pending'" class="answer-actions">
        <button class="icon-control" aria-label="复制回答" @click="handleCopy(msg.content)">
          <SvgIcon icon="lucide:copy" />
        </button>
        <span v-if="msg.status === 'loading'">{{ msg.agentStatus || '正在生成…' }}</span>
      </div>
    </template>
  </article>
</template>

<style scoped lang="scss">
.chat-message {
  margin-bottom: 30px;
  min-width: 0;
}
.chat-message.user {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 32px;
}
.user-bubble {
  max-width: 84%;
  padding: 11px 18px;
  border-radius: 20px;
  background: var(--shell-hover);
  font-size: 15px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.assistant-content {
  font-size: 15px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}
.assistant-content :deep(pre) {
  font-size: 13px;
  border-radius: 10px;
  overflow: auto;
}
.assistant-content :deep(table) {
  display: block;
  overflow: auto;
}
.answer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--shell-muted);
  font-size: 11px;
  margin-top: 8px;
}
.answer-actions .icon-control {
  font-size: 15px;
}
.thinking {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--shell-muted);
  font-size: 14px;
  padding: 8px 0;
}
.thinking-dot {
  width: 9px;
  height: 9px;
  background: var(--shell-text);
  border-radius: 50%;
  animation: pulse 1.5s ease-in-out infinite;
}
.answer-error {
  font-size: 14px;
  color: var(--shell-muted);
  line-height: 1.8;
}
.assistant-content :deep(h1) {
  font-size: 23px;
}
.assistant-content :deep(h2) {
  font-size: 20px;
}
@keyframes pulse {
  50% {
    opacity: 0.25;
  }
}

:deep(.source-file-link) {
  color: rgb(var(--primary-color));
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: rgb(var(--primary-color) / 0.75);
    text-decoration: none;
  }

  &:active {
    color: rgb(var(--primary-color) / 0.9);
  }
}
</style>
