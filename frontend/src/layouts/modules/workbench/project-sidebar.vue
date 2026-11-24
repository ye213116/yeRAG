<script setup lang="ts">
import { codingActive, codingStatusLabels, useCodingStore } from '@/store/modules/coding';
const coding = useCodingStore();
const expanded = ref<Record<string, boolean>>({});
const query = ref('');
const visible = computed(() =>
  coding.repositories.filter(
    r =>
      !query.value ||
      r.name.toLowerCase().includes(query.value.toLowerCase()) ||
      coding.projectTasks(r.id).some(t => (t.title || '').includes(query.value))
  )
);
function select(id: string) {
  expanded.value[id] = true;
  coding.selectProject(id);
}
</script>

<template>
  <div class="context-list">
    <button class="context-action" :disabled="!coding.projectId" @click="coding.selectProject(coding.projectId)">
      <SvgIcon icon="lucide:square-pen" />
      新任务
    </button>
    <NInput
      v-model:value="query"
      size="small"
      clearable
      placeholder="搜索项目或任务"
      :input-props="{ 'aria-label': '搜索项目或任务' }"
    />
    <div class="context-section-label">云端项目</div>
    <div v-if="!coding.repositories.length && !coding.loading" class="context-empty">
      {{ coding.enabled ? '暂无授权项目' : '配置 Coding 后即可开始' }}
    </div>
    <div v-for="project in visible" :key="project.id" class="project-group">
      <div class="project-heading">
        <button
          class="icon-control"
          :aria-label="`展开项目 ${project.name}`"
          :aria-expanded="expanded[project.id] !== false"
          @click="expanded[project.id] = expanded[project.id] === false"
        >
          <SvgIcon :icon="expanded[project.id] === false ? 'lucide:chevron-right' : 'lucide:chevron-down'" />
        </button>
        <button class="project-select" :title="project.name" @click="select(project.id)">
          <SvgIcon icon="lucide:folder" />
          <span>{{ project.name.split('/').at(-1) }}</span>
        </button>
        <button class="icon-control row-more" :aria-label="`在 ${project.name} 新建任务`" @click="select(project.id)">
          <SvgIcon icon="lucide:plus" />
        </button>
      </div>
      <div v-if="expanded[project.id] !== false" class="project-tasks">
        <button
          v-for="task in coding
            .projectTasks(project.id)
            .filter(t => !query || (t.title || '').includes(query) || project.name.includes(query))"
          :key="task.id"
          class="project-task"
          :data-task-id="task.id"
          :class="{ selected: coding.selectedTaskId === task.id }"
          :title="task.title || task.id"
          @click="coding.selectTask(task.id)"
        >
          <span>{{ task.title || task.summary || `开发任务 ${task.id.slice(0, 8)}` }}</span>
          <span v-if="codingActive(task.status)" class="running-indicator" aria-label="任务执行中"></span>
          <SvgIcon
            v-else
            :icon="task.status === 'REVIEW_READY' ? 'lucide:check' : 'lucide:circle-alert'"
            :title="codingStatusLabels[task.status]"
            class="task-state-icon"
          />
        </button>
        <div v-if="!coding.projectTasks(project.id).length" class="project-empty">还没有任务</div>
      </div>
    </div>
    <NButton text :loading="coding.loading" @click="coding.load()">刷新项目</NButton>
  </div>
</template>
