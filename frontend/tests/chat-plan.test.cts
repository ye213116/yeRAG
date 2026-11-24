import assert from 'node:assert/strict';
import { test } from 'node:test';
import { acceptPlanAttempt, interruptPlan, parsePlanTasks } from '../src/utils/chat-plan';

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

test('a resumed attempt replaces partial text and ignores late or unversioned frames', () => {
  const message = {
    role: 'assistant' as const,
    content: 'old partial answer',
    status: 'error' as const,
    planAttempt: 1,
    resumable: true
  };
  assert.equal(acceptPlanAttempt(message, 2), true);
  assert.equal(message.content, '');
  assert.equal(message.status, 'pending');
  assert.equal(message.resumable, false);
  message.content = 'new answer';
  assert.equal(acceptPlanAttempt(message, 1), false);
  assert.equal(acceptPlanAttempt(message, undefined), false);
  assert.equal(message.content, 'new answer');
  assert.equal(acceptPlanAttempt(message, 2), true);
  assert.equal(message.content, 'new answer');
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
