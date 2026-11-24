const taskStatuses = new Set(['pending', 'running', 'completed', 'failed', 'skipped', 'cancelled', 'interrupted']);

/** Accept only bounded display data from the status event. Titles render as plain text. */
export function parsePlanTasks(value: unknown): Api.Chat.PlanTask[] | undefined {
  if (!Array.isArray(value) || value.length < 3 || value.length > 6) return undefined;
  const tasks: Api.Chat.PlanTask[] = [];
  for (const task of value) {
    if (
      !task ||
      typeof task.id !== 'string' ||
      !task.id ||
      task.id.length > 32 ||
      typeof task.title !== 'string' ||
      !task.title.trim() ||
      task.title.length > 120 ||
      !taskStatuses.has(task.status) ||
      typeof task.message !== 'string' ||
      task.message.length > 200
    )
      return undefined;
    const parsed: Api.Chat.PlanTask = { id: task.id, title: task.title, status: task.status, message: task.message };
    if (Number.isInteger(task.attempts) && task.attempts >= 0) parsed.attempts = task.attempts;
    if (typeof task.outcome === 'string' && task.outcome.length < 40) parsed.outcome = task.outcome;
    tasks.push(parsed);
  }
  return new Set(tasks.map(task => task.id)).size === tasks.length ? tasks : undefined;
}

/** A resumed attempt replaces partial output; older attempts cannot append to it. */
export function acceptPlanAttempt(message: Api.Chat.Message, attempt: unknown): boolean {
  if (attempt === undefined) return !message.planAttempt;
  if (!Number.isInteger(attempt) || (attempt as number) < 1) return false;
  const next = attempt as number;
  const current = message.planAttempt || 0;
  if (next < current) return false;
  if (next > current) {
    if (current > 0 || next > 1) {
      message.content = '';
      message.status = 'pending';
      message.resumable = false;
    }
    message.planAttempt = next;
  }
  return true;
}

export function interruptPlan(message: Api.Chat.Message, status: 'failed' | 'cancelled' | 'skipped') {
  message.planTasks?.forEach(task => {
    if (task.status === 'running') {
      task.status = status;
      task.message = status === 'cancelled' ? '已停止' : '执行中断';
    } else if (task.status === 'pending') {
      task.status = 'skipped';
      task.message = '未执行';
    }
  });
}
