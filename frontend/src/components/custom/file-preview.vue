<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  fileName: string;
  fileMd5?: string;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const content = ref('');
const error = ref('');

// 获取文件图标
function getFileIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png', 'gif'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

watch(
  [() => props.fileName, () => props.fileMd5, () => props.visible],
  ([fileName, , visible], _previous, onCleanup) => {
    if (!fileName || !visible) return;
    const controller = new AbortController();
    onCleanup(() => controller.abort());
    loadPreviewContent(controller.signal);
  },
  { immediate: true }
);

async function loadPreviewContent(signal: AbortSignal) {
  loading.value = true;
  error.value = '';
  content.value = '';
  try {
    const { error: requestError, data } = await request<Api.Document.PreviewResponse>({
      url: '/documents/preview',
      params: { fileName: props.fileName, fileMd5: props.fileMd5 },
      signal
    });
    if (signal.aborted) return;
    if (requestError) error.value = `预览失败：${requestError.message || '未知错误'}`;
    else if (data) content.value = data.content;
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

async function downloadFile() {
  if (!props.fileName) return;
  downloading.value = true;
  try {
    const { error: requestError, data } = await request<Api.Document.DownloadResponse>({
      url: props.fileMd5 ? '/documents/download-by-md5' : '/documents/download',
      params: props.fileMd5 ? { fileMd5: props.fileMd5 } : { fileName: props.fileName }
    });
    if (requestError) {
      window.$message?.error(`下载失败：${requestError.message || '未知错误'}`);
      return;
    }
    if (!data) return;
    const link = document.createElement('a');
    link.href = data.downloadUrl;
    link.download = data.fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.$message?.success('开始下载文件');
  } finally {
    downloading.value = false;
  }
}

// 关闭预览
function closePreview() {
  emit('close');
}
</script>

<template>
  <div class="file-preview-container">
    <!-- 预览头部 -->
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium">{{ fileName }}</span>
      </div>
      <div class="flex items-center gap-2">
        <NButton size="small" :loading="downloading" @click="downloadFile">
          <template #icon>
            <icon-mdi-download />
          </template>
          下载
        </NButton>
        <NButton size="small" @click="closePreview">
          <template #icon>
            <icon-mdi-close />
          </template>
        </NButton>
      </div>
    </div>

    <!-- 预览内容 -->
    <div class="preview-content">
      <template v-if="loading">
        <div class="h-full flex items-center justify-center">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="h-full flex flex-col items-center justify-center text-gray-500">
          <icon-mdi-alert-circle class="mb-4 text-48" />
          <p>{{ error }}</p>
        </div>
      </template>
      <template v-else>
        <div class="content-wrapper">
          <pre class="preview-text">{{ content }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.file-preview-container {
  @apply h-full flex flex-col bg-white border-l border-gray-200;

  .preview-header {
    @apply flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50;
  }

  .preview-content {
    @apply flex-1 overflow-hidden;

    .content-wrapper {
      @apply h-full overflow-auto p-4;
    }

    .preview-text {
      @apply text-sm font-mono whitespace-pre-wrap break-words;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      line-height: 1.5;
      margin: 0;
    }
  }
}
</style>
