<script setup lang="ts">
import type { ArtifactSummary } from '@/service/api/artifacts';
defineProps<{
  items: ArtifactSummary[];
  nextOffset: number | null;
  loadingList: boolean;
  saving: boolean;
  loading: boolean;
  canGenerate: boolean;
  listError: boolean;
  selectedId?: string;
  beginCreate: () => void;
  generate: () => void;
  loadList: (more?: boolean) => void;
  select: (id: string) => void;
}>();
</script>

<template>
  <div class="context-list">
    <button class="context-action" :disabled="saving || loading" @click="beginCreate">
      <SvgIcon icon="lucide:square-pen" />
      新建报告
    </button>
    <button class="context-action" :disabled="!canGenerate" @click="generate()">
      <SvgIcon icon="lucide:sparkles" />
      去对话生成报告
    </button>
    <button class="context-action" :disabled="loadingList" @click="loadList()">
      <SvgIcon icon="lucide:refresh-cw" />
      刷新列表
    </button>
    <div class="context-section-label">已保存的报告</div>
    <div class="artifact-sidebar-list">
      <NSpin :show="loadingList">
        <NAlert v-if="listError" type="warning" class="mb-3">成果列表加载失败，请点击“刷新列表”重试。</NAlert>
        <NEmpty
          v-if="!items.length && !loadingList && !listError"
          description="尚无可访问成果，可新建报告或在对话中生成并保存"
        />
        <div v-for="item in items" :key="item.id" class="artifact-item">
          <NButton
            text
            :type="selectedId === item.id ? 'primary' : 'default'"
            class="max-w-full text-left"
            @click="select(item.id)"
          >
            <span class="truncate">{{ item.title }}</span>
          </NButton>
          <div class="mt-2 flex flex-wrap gap-2 text-12px text-gray-500">
            <span>v{{ item.version }}</span>
          </div>
        </div>
        <NButton v-if="nextOffset !== null" class="mt-4" :loading="loadingList" @click="loadList(true)">
          加载更多成果
        </NButton>
      </NSpin>
    </div>
  </div>
</template>
