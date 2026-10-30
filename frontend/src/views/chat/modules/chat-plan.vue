<script setup lang="ts">
defineProps<{ tasks: Api.Chat.PlanTask[] }>();

const labels: Record<Api.Chat.PlanTaskStatus, string> = {
  pending: '等待执行',
  running: '进行中',
  completed: '已完成',
  failed: '未完成',
  skipped: '已跳过',
  cancelled: '已停止'
};
</script>

<template>
  <section class="chat-plan ml-12 mt-2 rounded-3 bg-primary/5 p-4" aria-label="任务列表" aria-live="polite">
    <div class="mb-3 flex items-center justify-between gap-3">
      <span class="font-medium">执行计划</span>
      <span class="text-12px opacity-65">
        {{ tasks.filter(task => task.status === 'completed').length }}/{{ tasks.length }} 已完成
      </span>
    </div>
    <ol class="m-0 list-none p-0 space-y-3">
      <li v-for="(task, index) in tasks" :key="task.id" :data-task-status="task.status" class="flex gap-3">
        <span class="task-number mt-0.5 h-5 w-5 flex shrink-0 items-center justify-center rounded-full text-12px">
          {{ task.status === 'completed' ? '✓' : index + 1 }}
        </span>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <span class="break-words">{{ task.title }}</span>
            <span class="task-status text-12px">{{ labels[task.status] }}</span>
          </div>
          <p v-if="task.message" class="m-0 mt-1 text-12px opacity-60">{{ task.message }}</p>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.task-number {
  background: rgb(var(--primary-color) / 0.12);
}

[data-task-status='running'] .task-status {
  color: rgb(var(--primary-color));
  font-weight: 600;
}

[data-task-status='failed'] .task-status {
  color: #c2410c;
}

[data-task-status='skipped'],
[data-task-status='cancelled'] {
  opacity: 0.65;
}
</style>
