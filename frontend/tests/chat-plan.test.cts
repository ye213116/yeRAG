import assert from 'node:assert/strict';
import { test } from 'node:test';
import { interruptPlan, parsePlanTasks } from '../src/utils/chat-plan';

const tasks = () => [
  { id: '1', title: '阅读甲文档', status: 'completed', message: '完成' },
  { id: '2', title: '阅读乙文档', status: 'running', message: '正在执行' },
  { id: '3', title: '综合回答', status: 'pending', message: '等待执行' }
];

test('plan events accept bounded display data and reject invalid snapshots', () => {
  assert.deepEqual(parsePlanTasks(tasks()), tasks());
  for (const invalid of [
    null,
    [],
    tasks().slice(0, 2),
    [...tasks(), ...tasks(), ...tasks()],
    tasks().map(task => ({ ...task, id: 'duplicate' })),
    tasks().map(task => ({ ...task, status: 'unknown' })),
    tasks().map(task => ({ ...task, title: 'a'.repeat(121) })),
    tasks().map(task => ({ ...task, title: {} })),
    tasks().map(task => ({ ...task, message: undefined }))
  ])
    assert.equal(parsePlanTasks(invalid), undefined);
});

test('stop and failure settle active tasks while preserving completed work', () => {
  for (const status of ['cancelled', 'failed', 'skipped'] as const) {
    const message = { role: 'assistant' as const, content: '', planTasks: parsePlanTasks(tasks()) };
    interruptPlan(message, status);
    assert.deepEqual(
      message.planTasks?.map(task => task.status),
      ['completed', status, 'skipped']
    );
    interruptPlan(message, 'failed');
    assert.deepEqual(
      message.planTasks?.map(task => task.status),
      ['completed', status, 'skipped']
    );
  }
});
