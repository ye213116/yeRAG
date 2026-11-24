<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { useMediaQuery } from '@vueuse/core';
import { useCodingStore } from '@/store/modules/coding';
import { $t } from '@/locales';
import { provideFeatureSidebar } from '../context/feature-sidebar';
import GlobalContent from '../modules/global-content/index.vue';
import ConversationSidebar from '../modules/workbench/conversation-sidebar.vue';
import ProjectSidebar from '../modules/workbench/project-sidebar.vue';

defineOptions({ name: 'BaseLayout' });
const featureContent = provideFeatureSidebar();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const theme = useThemeStore();
const chat = useChatStore();
const coding = useCodingStore();
const narrow = useMediaQuery('(max-width: 899px)');
const sidebarOpen = ref(!narrow.value);
const navigationExpanded = ref(false);
const navigation = [
  { path: '/chat', label: '聊天', icon: 'lucide:house', key: 'chat' },
  { path: '/knowledge-base', label: '知识库', icon: 'lucide:files', key: 'knowledge-base' },
  { path: '/coding', label: 'Coding', icon: 'lucide:code-2', key: 'coding' },
  { path: '/artifacts', label: '成果库', icon: 'lucide:panels-top-left', key: 'artifacts' }
];
const section = computed(() => navigation.find(n => route.path === n.path));
const sidebarTitle = computed(() => (section.value?.key === 'chat' ? 'yeRAG' : section.value?.label || 'yeRAG'));
const pageTitle = computed(() => {
  if (route.name === 'chat') return chat.currentTitle;
  if (route.name === 'coding')
    return coding.tasks.find(t => t.id === coding.selectedTaskId)?.title || coding.currentProject?.name || '新任务';
  return route.meta.i18nKey ? $t(route.meta.i18nKey) : route.meta.title;
});
const moreOptions = computed(() =>
  auth.userInfo.role === 'ADMIN'
    ? [
        { label: '聊天记录', key: '/chat-history' },
        { label: '用户管理', key: '/user' },
        { label: '组织标签', key: '/org-tag' }
      ]
    : [{ label: '个人中心', key: '/personal-center' }]
);
const accountOptions = computed(() => [
  { label: auth.userInfo.username, key: 'account', disabled: true },
  { label: '个人中心', key: 'profile' },
  { label: theme.darkMode ? '切换浅色外观' : '切换深色外观', key: 'theme' },
  { type: 'divider' as const, key: 'divider' },
  { label: '退出登录', key: 'logout' }
]);
function navigate(path: string) {
  navigationExpanded.value = false;
  sidebarOpen.value = !narrow.value;
  router.push(path);
}
function account(key: string) {
  if (key === 'profile') navigate('/personal-center');
  if (key === 'theme') theme.setThemeScheme(theme.darkMode ? 'light' : 'dark');
  if (key === 'logout')
    window.$dialog?.warning({
      title: '退出登录',
      content: '确认退出当前账号？',
      positiveText: '退出',
      negativeText: '取消',
      onPositiveClick: () => auth.logout()
    });
}
watch(narrow, value => {
  if (value) sidebarOpen.value = false;
});
watch(
  () => route.fullPath,
  () => {
    if (narrow.value) sidebarOpen.value = false;
  }
);
onMounted(() => {
  if (!chat.conversationId) chat.loadCurrentConversation();
});
</script>

<template>
  <div
    class="agent-shell"
    :class="{ 'sidebar-open': sidebarOpen && section, 'navigation-expanded': navigationExpanded }"
  >
    <nav class="primary-rail" aria-label="主导航">
      <button class="rail-brand" aria-label="展开主导航" @click="navigationExpanded = !navigationExpanded">
        <span>y</span>
        <strong v-if="navigationExpanded">yeRAG</strong>
      </button>
      <div class="rail-links">
        <NTooltip v-for="item in navigation" :key="item.key" placement="right" :disabled="navigationExpanded">
          <template #trigger>
            <button
              class="rail-link"
              :class="{ active: section?.key === item.key }"
              :aria-label="item.label"
              :aria-current="section?.key === item.key ? 'page' : undefined"
              @click="navigate(item.path)"
            >
              <SvgIcon :icon="item.icon" />
              <span v-if="navigationExpanded">{{ item.label }}</span>
            </button>
          </template>
          {{ item.label }}
        </NTooltip>
        <NDropdown placement="right-start" trigger="click" :options="moreOptions" @select="navigate">
          <button class="rail-link" aria-label="更多功能">
            <SvgIcon icon="lucide:ellipsis" />
            <span v-if="navigationExpanded">更多</span>
          </button>
        </NDropdown>
      </div>
      <NDropdown placement="right-end" trigger="click" :options="accountOptions" @select="account">
        <button class="rail-account" aria-label="账号与设置">
          <span>{{ (auth.userInfo.username || 'Y').slice(0, 2).toUpperCase() }}</span>
          <strong v-if="navigationExpanded">{{ auth.userInfo.username }}</strong>
        </button>
      </NDropdown>
    </nav>
    <button
      v-if="narrow && sidebarOpen && section"
      class="sidebar-backdrop"
      aria-label="关闭副栏"
      @click="sidebarOpen = false"
    ></button>
    <aside v-if="section" class="feature-sidebar" :aria-label="`${sidebarTitle}侧边栏`" :inert="!sidebarOpen">
      <div class="sidebar-heading">
        <h1>{{ sidebarTitle }}</h1>
        <button class="icon-control" aria-label="收起副栏" @click="sidebarOpen = false">
          <SvgIcon icon="lucide:panel-left-close" />
        </button>
      </div>
      <div id="feature-sidebar-content" class="sidebar-content">
        <ConversationSidebar v-if="section.key === 'chat'" />
        <ProjectSidebar v-else-if="section.key === 'coding'" />
        <component
          :is="featureContent.component"
          v-else-if="section.key === 'artifacts' && featureContent"
          v-bind="featureContent.props"
        />
        <div v-else-if="section.key === 'knowledge-base'" class="context-list">
          <button class="context-action" @click="router.push({ path: '/knowledge-base', query: { action: 'upload' } })">
            <SvgIcon icon="lucide:upload" />
            上传资料
          </button>
          <button class="context-action" @click="router.push({ path: '/knowledge-base', query: { action: 'search' } })">
            <SvgIcon icon="lucide:search" />
            检索知识库
          </button>
          <div class="context-section-label">资料</div>
          <button class="context-action selected" @click="router.push('/knowledge-base')">
            <SvgIcon icon="lucide:files" />
            全部文档
          </button>
          <p class="context-empty">可访问的资料集中在这里，支持上传、检索和原文预览。</p>
        </div>
      </div>
    </aside>
    <main class="agent-main">
      <header class="workspace-heading">
        <div class="workspace-heading-title">
          <button
            v-if="!sidebarOpen || !section"
            class="icon-control"
            aria-label="展开副栏"
            @click="sidebarOpen = true"
          >
            <SvgIcon icon="lucide:panel-left" />
          </button>
          <span>{{ pageTitle }}</span>
        </div>
        <button
          v-if="route.name === 'coding' && coding.task"
          class="quiet-button"
          @click="coding.inspectorOpen = !coding.inspectorOpen"
        >
          <SvgIcon icon="lucide:panel-right" />
          查看成果
        </button>
        <span
          v-if="chat.wsStatus !== 'OPEN' && ['chat', 'coding'].includes(String(route.name))"
          class="connection-hint"
        >
          {{ chat.wsStatus === 'CONNECTING' ? '正在连接…' : '连接已断开，正在重连' }}
        </span>
      </header>
      <GlobalContent />
    </main>
  </div>
</template>

<style>
:root {
  --shell-bg: #fff;
  --rail-bg: #f7f7f8;
  --side-bg: #fafafa;
  --shell-text: #252527;
  --shell-muted: #8b8b90;
  --shell-border: #e9e9ec;
  --shell-hover: #efeff1;
  --shell-active: #e9e9ec;
}
html.dark {
  --shell-bg: #18181a;
  --rail-bg: #202023;
  --side-bg: #1d1d20;
  --shell-text: #ededee;
  --shell-muted: #92929a;
  --shell-border: #303035;
  --shell-hover: #2a2a30;
  --shell-active: #34343a;
}

.agent-shell {
  --shell-bg: #fff;
  --rail-bg: #f7f7f8;
  --side-bg: #fafafa;
  --shell-text: #252527;
  --shell-muted: #8b8b90;
  --shell-border: #e9e9ec;
  --shell-hover: #efeff1;
  --shell-active: #e9e9ec;
  height: 100dvh;
  display: flex;
  background: var(--shell-bg);
  color: var(--shell-text);
  overflow: hidden;
}
.dark .agent-shell {
  --shell-bg: #18181a;
  --rail-bg: #202023;
  --side-bg: #1d1d20;
  --shell-text: #ededee;
  --shell-muted: #92929a;
  --shell-border: #303035;
  --shell-hover: #2a2a30;
  --shell-active: #34343a;
}
.primary-rail {
  width: 64px;
  flex: none;
  border-right: 1px solid var(--shell-border);
  background: var(--rail-bg);
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 16px 8px 12px;
  z-index: 40;
  gap: 24px;
}
.rail-brand,
.rail-account,
.rail-link,
.icon-control,
.quiet-button,
.context-action,
.thread-select,
.project-select,
.project-task {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
}
.rail-brand,
.rail-account {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rail-brand > span {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  font-size: 29px;
  font-weight: 750;
  letter-spacing: -3px;
  color: #16966d;
}
.rail-brand strong {
  font-size: 18px;
  letter-spacing: -0.5px;
}
.rail-links {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.rail-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  color: var(--shell-muted);
  font-size: 21px;
}
.rail-link span {
  font-size: 14px;
}
.rail-link:hover,
.icon-control:hover,
.quiet-button:hover {
  background: var(--shell-hover);
}
.rail-link.active {
  background: var(--shell-active);
  color: var(--shell-text);
}
.rail-account {
  margin-top: auto;
}
.rail-account > span {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #a68a55;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}
.rail-account strong {
  font-size: 13px;
}
.navigation-expanded .primary-rail {
  position: absolute;
  inset: 0 auto 0 0;
  width: 200px;
  align-items: flex-start;
  padding-left: 18px;
  box-shadow: 8px 0 24px #00000008;
}
.navigation-expanded {
  padding-left: 64px;
}
.navigation-expanded .rail-link {
  width: 164px;
  justify-content: flex-start;
  padding-left: 12px;
}
.feature-sidebar {
  width: 0;
  min-width: 0;
  flex: none;
  background: var(--side-bg);
  overflow: hidden;
  transition: width 0.18s ease;
  border-right: 0 solid var(--shell-border);
  display: flex;
  flex-direction: column;
}
.sidebar-open .feature-sidebar {
  width: 280px;
  border-right-width: 1px;
}
.sidebar-heading {
  height: 72px;
  flex: none;
  padding: 0 20px 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 280px;
}
.sidebar-heading h1 {
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -0.5px;
}
.icon-control {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  flex: none;
  font-size: 17px;
  color: var(--shell-muted);
}
.sidebar-content {
  min-height: 0;
  overflow: auto;
  flex: 1;
  padding: 0 12px 18px;
  min-width: 280px;
}
.context-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.context-action {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 40px;
  text-align: left;
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 14px;
}
.context-action:hover,
.thread-row:hover,
.project-task:hover {
  background: var(--shell-hover);
}
.context-action svg {
  font-size: 18px;
}
.context-section-label {
  font-size: 12px;
  color: var(--shell-muted);
  margin: 22px 12px 6px;
}
.context-empty {
  font-size: 13px;
  line-height: 1.8;
  color: var(--shell-muted);
  padding: 12px;
}
.thread-row {
  display: flex;
  align-items: center;
  border-radius: 8px;
  min-height: 38px;
}
.selected {
  background: var(--shell-active);
}
.thread-select {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  text-align: left;
  padding: 10px 12px;
  font-size: 13px;
}
.thread-select > span:first-child,
.project-task > span:first-child,
.project-select > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.row-more {
  opacity: 0;
}
.thread-row:hover .row-more,
.project-heading:hover .row-more,
.row-more:focus-visible {
  opacity: 1;
}
.running-indicator {
  width: 12px;
  height: 12px;
  flex: none;
  border: 1.5px solid var(--shell-border);
  border-top-color: #16966d;
  border-radius: 50%;
  animation: work-spin 1s linear infinite;
}
.project-heading {
  display: flex;
  align-items: center;
  min-height: 38px;
}
.project-select {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  text-align: left;
  font-weight: 550;
  font-size: 13px;
}
.project-tasks {
  padding-left: 23px;
}
.project-task {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  font-size: 13px;
  padding: 10px;
  border-radius: 8px;
}
.task-state-icon {
  font-size: 14px;
  color: var(--shell-muted);
  flex: none;
}
.project-empty {
  font-size: 12px;
  color: var(--shell-muted);
  padding: 8px 10px;
}
.agent-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  height: 100%;
  overflow: hidden;
}
.workspace-heading {
  height: 56px;
  flex: none;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  justify-content: space-between;
}
.workspace-heading-title {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  color: var(--shell-muted);
  font-size: 13px;
}
.workspace-heading-title > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quiet-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  border: 1px solid var(--shell-border);
  border-radius: 8px;
  padding: 6px 10px;
  white-space: nowrap;
}
.connection-hint {
  color: var(--shell-muted);
  font-size: 12px;
}
.workspace-page {
  height: 0;
  min-height: 0;
  flex: 1;
  overflow: hidden;
  background: var(--shell-bg) !important;
}
.business-page {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 20px 28px 28px;
  background: var(--shell-bg) !important;
}
.sidebar-backdrop {
  display: none;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.agent-shell button:focus-visible {
  outline: 2px solid #16966d;
  outline-offset: 2px;
}
.agent-shell .n-card {
  box-shadow: none;
  border-radius: 10px;
}
.agent-shell .n-input {
  --n-border-radius: 8px;
}
.agent-shell .n-button {
  font-size: 13px;
}
.agent-shell .n-layout {
  background: transparent;
}
@keyframes work-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .feature-sidebar {
    transition: none;
  }
  .running-indicator {
    animation: none;
  }
}
@media (max-width: 899px) {
  .feature-sidebar {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 64px;
    z-index: 30;
  }
  .sidebar-backdrop {
    display: block;
    position: absolute;
    inset: 0 0 0 64px;
    background: #0005;
    z-index: 25;
    border: 0;
  }
  .workspace-heading {
    padding: 0 14px;
  }
  .business-page {
    padding: 12px;
  }
  .row-more {
    opacity: 1;
  }
}
</style>
