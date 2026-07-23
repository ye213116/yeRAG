<template>
  <div class="file-preview-container">
    <!-- 预览头部 -->
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium">{{ fileName }}</span>
      </div>
      <div class="flex items-center gap-2">
        <NButton size="small" @click="downloadFile" :loading="downloading">
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
        <div class="flex items-center justify-center h-full">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="flex flex-col items-center justify-center h-full text-gray-500">
          <icon-mdi-alert-circle class="text-48 mb-4" />
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

<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';

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

// 监听文件名变化，加载预览内容
watch(() => props.fileName, async (newFileName) => {
  if (newFileName && props.visible) {
    await loadPreviewContent();
  }
}, { immediate: true });

// 监听可见性变化
watch(() => props.visible, async (visible) => {
  if (visible && props.fileName) {
    await loadPreviewContent();
  }
});

// 加载预览内容
async function loadPreviewContent() {
  if (!props.fileName) return;

  console.log('[文件预览] 开始加载预览内容:', {
    fileName: props.fileName,
    fileMd5: props.fileMd5,
    visible: props.visible
  });

  loading.value = true;
  error.value = '';
  content.value = '';

  try {
    const token = localStorage.getItem('token');

    // 优先使用 MD5 预览（如果存在）
    if (props.fileMd5) {
      console.log('[文件预览] 使用MD5模式预览，请求参数:', {
        fileName: props.fileName,
        fileMd5: props.fileMd5,
        hasToken: !!token
      });

      const { error: requestError, data } = await request<{
        fileName: string;
        content: string;
        fileSize: number;
      }>({
        url: '/documents/preview',
        params: {
          fileName: props.fileName,
          fileMd5: props.fileMd5,
          token: token || undefined
        }
      });

      console.log('[文件预览] MD5模式API响应:', {
        hasError: !!requestError,
        error: requestError,
        hasData: !!data,
        contentLength: data?.content?.length || 0,
        contentPreview: data?.content?.substring(0, 100) || ''
      });

      if (requestError) {
        error.value = '预览失败：' + (requestError.message || '未知错误');
      } else if (data) {
        content.value = data.content;
      }
    } else {
      // 降级：使用文件名预览（向后兼容）
      console.log('[文件预览] 使用文件名模式预览（降级）, 请求参数:', {
        fileName: props.fileName,
        hasToken: !!token
      });

      const { error: requestError, data } = await request<{
        fileName: string;
        content: string;
        fileSize: number;
      }>({
        url: '/documents/preview',
        params: {
          fileName: props.fileName,
          token: token || undefined
        }
      });

      console.log('[文件预览] 文件名模式API响应:', {
        hasError: !!requestError,
        error: requestError,
        hasData: !!data,
        contentLength: data?.content?.length || 0,
        contentPreview: data?.content?.substring(0, 100) || ''
      });

      if (requestError) {
        error.value = '预览失败：' + (requestError.message || '未知错误');
      } else if (data) {
        content.value = data.content;
      }
    }
  } catch (err: any) {
    error.value = '预览失败：' + (err.message || '网络错误');
  } finally {
    loading.value = false;
  }
}

// 下载文件
async function downloadFile() {
  if (!props.fileName) return;

  downloading.value = true;

  try {
    const token = localStorage.getItem('token');

    // 优先使用 MD5 下载（如果存在）
    if (props.fileMd5) {
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
        fileMd5?: string;
      }>({
        url: '/documents/download-by-md5',
        params: {
          fileMd5: props.fileMd5,
          token: token || undefined
        }
      });

      if (requestError) {
        window.$message?.error('下载失败：' + (requestError.message || '未知错误'));
      } else if (data) {
        // 使用预签名URL下载文件
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.$message?.success('开始下载文件');
      }
    } else {
      // 降级：使用文件名下载（向后兼容）
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
      }>({
        url: '/documents/download',
        params: {
          fileName: props.fileName,
          token: token || undefined
        }
      });

      if (requestError) {
        window.$message?.error('下载失败：' + (requestError.message || '未知错误'));
      } else if (data) {
        // 使用预签名URL下载文件
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.$message?.success('开始下载文件');
      }
    }
  } catch (err: any) {
    window.$message?.error('下载失败：' + (err.message || '网络错误'));
  } finally {
    downloading.value = false;
  }
}

// 关闭预览
function closePreview() {
  emit('close');
}
</script>

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