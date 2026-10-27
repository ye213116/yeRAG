<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message;
  sessionId?: string;
}>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

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
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

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
  <div class="mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar class="bg-primary">
        <SystemLogo class="text-6 text-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">yeRAG</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
      <span class="ml-12 text-14px">{{ msg.agentStatus || '正在思考' }}</span>
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic">
      {{ msg.content || '服务器繁忙，请稍后再试' }}
    </NText>
    <div v-else-if="msg.role === 'assistant'" class="assistant-content mt-2 pl-12" @click="handleContentClick">
      <VueMarkdownIt :content="content" />
    </div>
    <div v-else-if="msg.role === 'user'" class="ml-12 mt-2">
      <div class="user-bubble text-4">{{ content }}</div>
    </div>
    <NDivider class="ml-12 w-[calc(100%-3rem)] opacity-40 mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
.user-bubble {
  width: fit-content;
  max-width: 82%;
  padding: 8px 14px;
  border-radius: 16px;
  border-top-left-radius: 4px;
  background-color: rgb(var(--primary-color) / 0.1);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.assistant-content {
  line-height: 1.75;
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
