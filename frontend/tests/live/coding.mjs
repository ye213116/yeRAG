/* eslint-disable no-await-in-loop -- Live task status and browser actions are sequential. */
import assert from 'node:assert/strict';
import process from 'node:process';
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import puppeteer from 'puppeteer-core';

// Opt-in real backend, model and Docker check. Keeps its labelled task for review; never publishes a PR.
const frontend = process.env.YERAG_E2E_FRONTEND || 'http://127.0.0.1:9527';
const backend = process.env.YERAG_E2E_BACKEND || 'http://127.0.0.1:8081';
const repositoryId = process.env.CODING_E2E_REPOSITORY_ID || 'demo';
const output = resolve(process.env.CODING_E2E_OUTPUT || '.codex/integration/coding-runner');
const username = process.env.ADMIN_BOOTSTRAP_USERNAME || 'admin';
const password = process.env.ADMIN_BOOTSTRAP_PASSWORD;
assert.ok(password, 'Set ADMIN_BOOTSTRAP_PASSWORD locally');
mkdirSync(output, { recursive: true, mode: 0o700 });
let token = '';
let taskId = '';
let browser;
let page;
const report = { repositoryId, checks: [], errors: [] };

function chromePath() {
  if (process.env.BROWSER_EXECUTABLE_PATH) return process.env.BROWSER_EXECUTABLE_PATH;
  for (const path of ['/usr/bin/chromium', '/usr/bin/google-chrome']) if (existsSync(path)) return path;
  const cache = resolve(homedir(), '.cache/ms-playwright');
  for (const folder of existsSync(cache)
    ? readdirSync(cache)
        .filter(name => name.startsWith('chromium-'))
        .reverse()
    : []) {
    const path = resolve(cache, folder, 'chrome-linux64/chrome');
    if (existsSync(path)) return path;
  }
  throw new Error('Set BROWSER_EXECUTABLE_PATH to Chromium');
}
async function api(path, options = {}) {
  const response = await fetch(`${backend}/api/v1${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    signal: AbortSignal.timeout(30000)
  });
  assert.equal(response.status, 200, `${path}: HTTP ${response.status}`);
  const result = await response.json();
  assert.equal(result.code, 200, `${path}: rejected`);
  return result.data;
}
async function clickText(text) {
  await page.waitForFunction(
    value => [...document.querySelectorAll('button')].some(el => el.textContent.trim() === value && !el.disabled),
    {},
    text
  );
  await page.evaluate(
    value => [...document.querySelectorAll('button')].find(el => el.textContent.trim() === value).click(),
    text
  );
}
function check(label) {
  report.checks.push(label);
  process.stdout.write(`${label}\n`);
}
try {
  browser = await puppeteer.launch({ executablePath: chromePath(), headless: true, args: ['--no-sandbox'] });
  page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 1100 });
  page.on('pageerror', error => report.errors.push(error.message.replaceAll(token || 'unused-token', '[REDACTED]')));
  await page.goto(`${frontend}/login`);
  await page.waitForSelector('input[type="password"]');
  await page.locator('input:not([type="password"])').fill(username);
  await page.locator('input[type="password"]').fill(password);
  const [loginResponse] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/users/login') && response.request().method() === 'POST'),
    clickText('登录账号')
  ]);
  token = (await loginResponse.json()).data.token;
  assert.ok(token);
  await page.waitForFunction(() => location.pathname === '/chat');
  assert.equal((await api('/coding/capabilities')).enabled, true);
  const repository = (await api('/coding/repositories')).find(item => item.id === repositoryId);
  assert.ok(repository, 'Register the fixture repository for this account before running');
  assert.equal(
    repository.name,
    'jenkins-docs/simple-java-maven-app',
    'Live check only edits its designated public fixture'
  );
  report.baseRef = repository.branches[0];
  await page.click('button[aria-label="Coding"]');
  await page.waitForFunction(
    () => location.pathname === '/coding' && document.body.textContent.includes('在项目中开始一个任务')
  );
  check('browser login and Coding navigation');
  await page.evaluate(
    name => [...document.querySelectorAll('.project-select')].find(el => el.title === name).click(),
    repository.name
  );
  const workspaceResponse = page.waitForResponse(
    response => response.url().endsWith('/coding/workspaces') && response.request().method() === 'POST'
  );
  await page.type(
    'textarea',
    '本地 Coding 联调：仅修改此示例仓库。保留 App.getMessage() 及现有测试，在 App 中新增 public String greet(String name)：name 为 null 或空白时返回 Hello World!；否则返回 Hello, 加去除首尾空白后的名字再加 !（例如 Hello, yeRAG!）。在 AppTest 中新增至少三个测试，覆盖普通名字、null、空白。不要修改 pom.xml，不需要外部资料。请执行 Maven 测试，修复失败后再结束，并总结 Diff 与验证结果。'
  );
  await page.keyboard.press('Enter');
  const workspace = (await (await workspaceResponse).json()).data;
  assert.ok(workspace?.id);
  report.workspaceId = workspace.id;
  check('automatic workspace creation from the project composer');
  await page.waitForFunction(() => document.body.textContent.includes('任务已提交'));
  const submitted = (await api('/coding/tasks')).find(item => item.workspace_id === workspace.id);
  assert.ok(submitted);
  taskId = submitted.id;
  report.taskId = taskId;
  check('WebSocket submission persisted as a background task');
  await page.close();
  let task;
  let lastStatus = '';
  const deadline = Date.now() + 16 * 60 * 1000;
  while (Date.now() < deadline) {
    task = await api(`/coding/tasks/${taskId}`);
    if (task.status !== lastStatus) {
      process.stdout.write(`task ${taskId}: ${task.status}\n`);
      lastStatus = task.status;
    }
    if (
      !['QUEUED', 'PREPARING', 'RUNNING'].includes(task.status) &&
      (task.status !== 'REVIEW_READY' || !task.result?.cleanupPending)
    )
      break;
    await delay(2000);
  }
  report.task = task;
  assert.equal(task.status, 'REVIEW_READY');
  assert.equal(task.result?.verification?.status, 'PASSED');
  assert.ok(task.result.verification.tests >= 5);
  assert.equal(task.result.cleanupPending, false);
  assert.match(task.result.diff, /greet/);
  assert.ok(task.result.changes.some(change => change.path.endsWith('/App.java')));
  assert.ok(task.result.changes.some(change => change.path.endsWith('/AppTest.java')));
  assert.equal(task.publishState, 'NONE');
  check('real model edits, Maven tests and durable result after leaving Coding');
  assert.equal(task.result.verification.changeSetHash, task.result.changeSetHash);
  page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 1100 });
  page.on('pageerror', error => report.errors.push(error.message.replaceAll(token || 'unused-token', '[REDACTED]')));
  await page.goto(`${frontend}/coding?project=${repositoryId}&task=${taskId}`);
  await page.waitForFunction(() => document.body.textContent.includes('测试：通过'));
  await page.reload();
  await page.waitForFunction(() => document.body.textContent.includes('测试：通过'));
  await page.waitForSelector('[aria-label="审核 Diff"]');
  await page.waitForFunction(() => document.body.textContent.includes('greet'));
  await delay(500);
  await page.screenshot({ path: resolve(output, 'live-coding.png'), fullPage: true });
  assert.deepEqual(report.errors, []);
  check('refresh restores task, passing verification and reviewable Diff');
  report.passed = true;
} catch (error) {
  report.passed = false;
  report.failure = error.message.replaceAll(token || 'unused-token', '[REDACTED]');
  if (taskId) {
    try {
      report.task = await api(`/coding/tasks/${taskId}`);
      if (['QUEUED', 'PREPARING', 'RUNNING'].includes(report.task.status))
        await api(`/coding/tasks/${taskId}/cancel`, { method: 'POST' });
    } catch {
      /* Preserve the original failure; the task remains visible for diagnosis. */
    }
  }
  if (page) await page.screenshot({ path: resolve(output, 'live-coding-failure.png'), fullPage: true }).catch(() => {});
  process.stderr.write(`${report.failure}\n`);
  process.exitCode = 1;
} finally {
  writeFileSync(resolve(output, 'live-coding-report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  await browser?.close();
}
