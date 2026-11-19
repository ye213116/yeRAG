<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import type { ArtifactSource, ArtifactSummary, ArtifactVersion } from '@/service/api/artifacts';
import {
  createArtifact,
  fetchArtifact,
  fetchArtifactVersions,
  fetchArtifacts,
  saveArtifactVersion
} from '@/service/api/artifacts';
import { useChatStore } from '@/store/modules/chat';
import FilePreview from '@/components/custom/file-preview.vue';

const route = useRoute();
const router = useRouter();
const chat = useChatStore();
const items = ref<ArtifactSummary[]>([]);
const nextOffset = ref<number | null>(null);
const loadingList = ref(false);
const loading = ref(false);
const saving = ref(false);
const report = ref<ArtifactVersion | null>(null);
const versions = ref<ArtifactSummary[]>([]);
const hasOlder = ref(false);
const loadingVersions = ref(false);
const latestVersion = ref(0);
const editing = ref(false);
const creating = ref(false);
const title = ref('');
const content = ref('');
const errorNotice = ref('');
const listError = ref(false);
const pendingRequestId = ref('');
const preview = ref<{ fileName: string; fileMd5: string } | null>(null);
let selection = 0;

const hasChanges = computed(() =>
  creating.value
    ? Boolean(title.value.trim() || content.value.trim())
    : Boolean(report.value && (title.value !== report.value.title || content.value !== report.value.content))
);
const versionOptions = computed(() =>
  versions.value.map(v => ({ label: `v${v.version} · ${v.title}`, value: v.version }))
);
const canGenerate = computed(
  () => !chat.activeRequestId || !['loading', 'pending'].includes(chat.list.at(-1)?.status || '')
);

function date(value: string) {
  return new Date(value).toLocaleString();
}

async function loadList(more = false) {
  if (loadingList.value || (more && nextOffset.value === null)) return;
  loadingList.value = true;
  try {
    const { data, error } = await fetchArtifacts(more ? nextOffset.value! : 0);
    listError.value = Boolean(error || !data);
    if (!error && data) {
      items.value = more ? [...items.value, ...data.items] : data.items;
      nextOffset.value = data.nextOffset;
    }
  } finally {
    loadingList.value = false;
  }
}

async function open(id: string, version?: number) {
  selection += 1;
  const current = selection;
  loading.value = true;
  errorNotice.value = '';
  editing.value = false;
  creating.value = false;
  // Clear old body immediately; a denied or failed read must not display a previous report.
  report.value = null;
  try {
    const { data, error } = await fetchArtifact(id, version);
    if (current !== selection) return;
    if (error || !data) {
      errorNotice.value = '报告读取失败，可能已失去来源访问权限。可以重新读取。';
      return;
    }
    report.value = data;
    title.value = data.title;
    content.value = data.content;
    if (version === undefined) {
      latestVersion.value = data.version;
      versions.value = [];
      const history = await fetchArtifactVersions(id);
      if (current !== selection) return;
      versions.value = history.error ? [] : history.data || [];
      hasOlder.value = versions.value.length === 20;
    }
  } finally {
    if (current === selection) loading.value = false;
  }
}

async function olderVersions() {
  if (!report.value || loadingVersions.value) return;
  const id = report.value.id;
  const current = selection;
  loadingVersions.value = true;
  try {
    const result = await fetchArtifactVersions(id, versions.value.at(-1)?.version);
    if (!result.error && result.data && current === selection) {
      versions.value.push(...result.data);
      hasOlder.value = result.data.length === 20;
    }
  } finally {
    loadingVersions.value = false;
  }
}

async function select(id: string) {
  if (saving.value) return;
  if (editing.value && hasChanges.value) {
    window.$message?.warning('请先保存或取消当前修改，再切换报告。');
    return;
  }
  if (route.query.id === id) await open(id);
  else await router.replace({ query: { id } });
}

function beginCreate() {
  if (editing.value && hasChanges.value) {
    window.$message?.warning('请先保存或取消当前修改，再新建报告。');
    return;
  }
  selection += 1;
  loading.value = false;
  report.value = null;
  versions.value = [];
  hasOlder.value = false;
  title.value = '';
  content.value = '';
  errorNotice.value = '';
  creating.value = true;
  editing.value = true;
  pendingRequestId.value = crypto.randomUUID();
}

function beginEdit() {
  if (!report.value || report.value.version !== latestVersion.value) return;
  editing.value = true;
  pendingRequestId.value = crypto.randomUUID();
}

function cancelEdit() {
  if (creating.value) {
    creating.value = false;
    editing.value = false;
    if (typeof route.query.id === 'string') open(route.query.id);
    return;
  }
  if (!report.value) return;
  title.value = report.value.title;
  content.value = report.value.content;
  editing.value = false;
  errorNotice.value = '';
}

async function save() {
  if ((!report.value && !creating.value) || saving.value || !title.value.trim() || !content.value.trim()) return;
  saving.value = true;
  const current = selection;
  try {
    const input = {
      title: title.value,
      content: content.value,
      expectedVersion: creating.value ? 0 : report.value!.version,
      requestId: pendingRequestId.value
    };
    const result = creating.value ? await createArtifact(input) : await saveArtifactVersion(report.value!.id, input);
    if (current !== selection) return;
    if (result.error || !result.data) {
      const reason = result.error?.response?.data?.message;
      errorNotice.value = `${reason || '未确认保存成功'}。编辑内容已保留。可重试；若版本冲突，请复制修改内容后重新读取最新版本。`;
      return;
    }
    const saved = result.data;
    editing.value = false;
    creating.value = false;
    window.$message?.success(`已保存为 v${saved.version}，旧版本保留`);
    if (route.query.id === saved.id) await open(saved.id);
    else await router.replace({ query: { id: saved.id } });
    await loadList();
  } finally {
    saving.value = false;
  }
}

// Identical retries retain their key; a changed payload starts a distinct save request.
watch([title, content], () => {
  if (editing.value) pendingRequestId.value = crypto.randomUUID();
});

function confirmLeave() {
  if (saving.value && editing.value) return false;
  if (!editing.value || !hasChanges.value) return true;
  return new Promise<boolean>(resolve => {
    if (!window.$dialog) {
      resolve(false);
      return;
    }
    window.$dialog.warning({
      title: '尚有未保存的报告',
      content: '离开会丢失当前修改，是否放弃修改？',
      positiveText: '放弃修改',
      negativeText: '继续编辑',
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
      onAfterLeave: () => resolve(false)
    });
  });
}

onBeforeRouteLeave(confirmLeave);
onBeforeRouteUpdate(to => (to.query.id !== route.query.id ? confirmLeave() : true));

async function generate(existing = false) {
  if (!canGenerate.value) return;
  const message =
    existing && report.value
      ? `请读取成果 ${report.value.id} 的最新完整正文，按以下要求修改并保存为新版本：【请填写修改要求】。保留来源和证据缺口。`
      : '请根据相关资料，围绕【请填写报告主题】生成一份报告并保存到成果库，注明来源和证据缺口。';
  const failure = await router.push('/chat');
  if (!failure) chat.input.message = message;
}

function publicUrl(source: ArtifactSource) {
  try {
    const url = new URL(source.url || '');
    return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined;
  } catch {
    return undefined;
  }
}

function escapeHtml(value: string) {
  const entities: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  return value.replace(/[&<>"']/g, character => entities[character]);
}

function download() {
  if (!report.value) return;
  const r = report.value;
  const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escapeHtml(r.title)}</title><style>body{max-width:850px;margin:48px auto;padding:0 24px;font:16px/1.8 system-ui;color:#24382e}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}li{margin:12px 0}small{color:#66746c}</style><h1>${escapeHtml(r.title)}</h1><small>v${r.version} · ${escapeHtml(date(r.createdAt))} · 私有工作成果，正文不代表原始证据</small><pre>${escapeHtml(r.content)}</pre><hr><h2>来源记录</h2><ol>${r.sources.map(s => `<li>[来源#${s.number}] ${escapeHtml(s.title || '未命名来源')} · ${s.kind === 'knowledge' ? `内部资料 · 片段 ${s.chunkId ?? ''}` : `公开资料 · ${s.evidence === 'search_snippet' ? '仅搜索摘要' : '网页正文'}`}<br>${escapeHtml(s.kind === 'knowledge' ? s.fileMd5 || '' : s.url || '')}</li>`).join('')}</ol></html>`;
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `yeRAG-${r.id}-v${r.version}.html`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

watch(
  () => route.query.id,
  id => {
    selection += 1;
    if (typeof id === 'string') open(id);
    else {
      report.value = null;
      editing.value = false;
      creating.value = false;
    }
  },
  { immediate: true }
);
loadList();
</script>

<template>
  <div class="artifact-page min-w-0 flex-col gap-4">
    <NCard title="成果库" :bordered="false">
      <NText depth="3">个人报告独立保存，修改产生新版本；来源访问权限会在读取时重新校验。</NText>
      <div class="mt-3 flex flex-wrap gap-2">
        <NButton :disabled="saving || loading" @click="beginCreate">新建报告</NButton>
        <NButton :disabled="!canGenerate" type="primary" @click="generate()">去对话生成报告</NButton>
        <NButton :loading="loadingList" @click="loadList()">刷新列表</NButton>
      </div>
    </NCard>
    <div class="artifact-layout">
      <NCard title="已保存的报告" :bordered="false">
        <NSpin :show="loadingList">
          <NAlert v-if="listError" type="warning" class="mb-3">成果列表加载失败，请点击“刷新列表”重试。</NAlert>
          <NEmpty
            v-if="!items.length && !loadingList && !listError"
            description="尚无可访问成果，可新建报告或在对话中生成并保存"
          />
          <div v-for="item in items" :key="item.id" class="artifact-item">
            <NButton
              text
              :type="report?.id === item.id ? 'primary' : 'default'"
              class="max-w-full text-left"
              @click="select(item.id)"
            >
              <span class="whitespace-normal break-all">{{ item.title }}</span>
            </NButton>
            <div class="mt-2 flex flex-wrap gap-2 text-12px text-gray-500">
              <span>v{{ item.version }}</span>
              <span>{{ item.sourceCount }} 项来源</span>
              <span>{{ date(item.updatedAt) }}</span>
            </div>
          </div>
          <NButton v-if="nextOffset !== null" class="mt-4" :loading="loadingList" @click="loadList(true)">
            加载更多成果
          </NButton>
        </NSpin>
      </NCard>
      <NCard :title="creating ? '新建报告' : report ? report.title : '报告详情'" :bordered="false" class="min-w-0">
        <NSpin :show="loading">
          <NAlert v-if="errorNotice" type="warning" class="mb-4">{{ errorNotice }}</NAlert>
          <div v-if="!report && !creating">
            <NEmpty v-if="!loading" description="选择左侧报告，或从对话中的成果链接进入" />
            <NButton v-if="route.query.id" class="mt-4" @click="open(String(route.query.id))">重新读取</NButton>
          </div>
          <template v-else>
            <div v-if="report" class="mb-4 flex flex-wrap items-center gap-2">
              <NTag :type="report.version === latestVersion ? 'success' : 'default'">
                v{{ report.version }}{{ report.version === latestVersion ? ' · 最新读取版本' : ' · 历史只读版本' }}
              </NTag>
              <NSelect
                v-if="versions.length"
                class="max-w-full w-full sm:w-65"
                aria-label="报告版本"
                :value="report.version"
                :options="versionOptions"
                :disabled="editing || saving"
                @update:value="value => open(report!.id, value)"
              />
              <NButton v-if="hasOlder" :loading="loadingVersions" :disabled="editing" @click="olderVersions">
                更早版本
              </NButton>
              <NPopconfirm v-if="editing" @positive-click="open(report.id)">
                <template #trigger><NButton :disabled="saving">放弃修改并读取最新</NButton></template>
                重新读取会放弃当前未保存的修改，是否继续？
              </NPopconfirm>
              <NButton v-else @click="open(report.id)">读取最新版本</NButton>
            </div>
            <div v-if="editing" class="flex-col gap-3">
              <NText v-if="creating" depth="3">手写报告不绑定来源；需要基于资料生成时，请使用“去对话生成报告”。</NText>
              <NInput
                v-model:value="title"
                :disabled="saving"
                :maxlength="160"
                show-count
                :input-props="{ 'aria-label': '报告标题' }"
              />
              <NInput
                v-model:value="content"
                :disabled="saving"
                type="textarea"
                :autosize="{ minRows: 16, maxRows: 40 }"
                :maxlength="30000"
                show-count
                :input-props="{ 'aria-label': '报告正文' }"
              />
              <div class="flex flex-wrap gap-2">
                <NButton
                  type="primary"
                  :loading="saving"
                  :disabled="!hasChanges || !title.trim() || !content.trim()"
                  @click="save"
                >
                  {{ creating ? '保存报告' : '保存为新版本' }}
                </NButton>
                <NButton :disabled="saving" @click="cancelEdit">取消修改</NButton>
              </div>
            </div>
            <template v-else-if="report">
              <div class="report-body">{{ report.content }}</div>
              <div class="my-4 flex flex-wrap gap-2">
                <NButton type="primary" :disabled="report.version !== latestVersion" @click="beginEdit">
                  编辑此版本
                </NButton>
                <NButton :disabled="!canGenerate" @click="generate(true)">在对话中继续修改</NButton>
                <NButton @click="download">下载 HTML</NButton>
              </div>
            </template>
            <template v-if="report">
              <NDivider />
              <h3 class="mb-2 font-bold">来源记录</h3>
              <NText depth="3">报告是工作成果，来源记录不表示正文已核实。事实结论应回到原文验证。</NText>
              <div v-for="source in report.sources" :key="source.number" class="mt-3 flex-col gap-1">
                <span class="break-all">[来源#{{ source.number }}] {{ source.title }}</span>
                <div class="flex flex-wrap items-center gap-2 text-12px">
                  <NTag size="small">
                    {{
                      source.kind === 'knowledge'
                        ? '内部资料'
                        : source.evidence === 'search_snippet'
                          ? '公开资料 · 仅搜索摘要'
                          : '公开资料 · 网页正文'
                    }}
                  </NTag>
                  <NButton
                    v-if="source.kind === 'knowledge' && source.fileMd5"
                    text
                    type="primary"
                    @click="preview = { fileName: source.title, fileMd5: source.fileMd5 }"
                  >
                    查看原文件 · 片段 {{ source.chunkId ?? 0 }}
                  </NButton>
                  <a
                    v-else-if="publicUrl(source)"
                    :href="publicUrl(source)"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="break-all"
                  >
                    查看公开来源
                  </a>
                  <span v-if="source.fetchedAt">获取于 {{ date(source.fetchedAt) }}</span>
                </div>
              </div>
              <NText v-if="!report.sources.length" depth="3">此成果未绑定原始资料，请自行核对正文依据。</NText>
            </template>
          </template>
        </NSpin>
      </NCard>
    </div>
    <FilePreview
      v-if="preview"
      :visible="true"
      :file-name="preview.fileName"
      :file-md5="preview.fileMd5"
      @close="preview = null"
    />
  </div>
</template>

<style scoped>
.artifact-layout {
  display: grid;
  grid-template-columns: minmax(230px, 1fr) minmax(0, 2.5fr);
  gap: 16px;
  align-items: start;
}
.artifact-item {
  padding: 16px 0;
  border-bottom: 1px solid rgb(128 128 128 / 15%);
}
.report-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
}
@media (width < 900px) {
  .artifact-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
