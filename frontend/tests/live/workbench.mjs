/* eslint-disable no-await-in-loop -- Real background runs and browser actions are sequential. */
import assert from 'node:assert/strict';
import process from 'node:process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import puppeteer from 'puppeteer-core';

// Opt-in real-model check. Retains labelled conversations for review, without changing existing threads.
const frontend = process.env.YERAG_E2E_FRONTEND || 'http://127.0.0.1:9527';
const backend = process.env.YERAG_E2E_BACKEND || 'http://127.0.0.1:8081';
const output = resolve(process.env.WORKBENCH_E2E_OUTPUT || '.codex/integration/workbench');
const password = process.env.ADMIN_BOOTSTRAP_PASSWORD;
assert.ok(password, 'Set ADMIN_BOOTSTRAP_PASSWORD locally');
mkdirSync(output, { recursive: true, mode: 0o700 });
const profile = mkdtempSync(resolve(output, 'browser-profile-'));
const report = { checks: [], errors: [], runs: [] };
let token = '';
let browser;
let page;
function chromePath() {
  if (process.env.BROWSER_EXECUTABLE_PATH) return process.env.BROWSER_EXECUTABLE_PATH;
  for (const path of ['/usr/bin/chromium', '/usr/bin/google-chrome']) if (existsSync(path)) return path;
  const cache = resolve(homedir(), '.cache/ms-playwright');
  for (const folder of existsSync(cache)
    ? readdirSync(cache)
        .filter(n => n.startsWith('chromium-'))
        .reverse()
    : []) {
    const path = resolve(cache, folder, 'chrome-linux64/chrome');
    if (existsSync(path)) return path;
  }
  throw new Error('Set BROWSER_EXECUTABLE_PATH to Chromium');
}
async function launch() {
  browser = await puppeteer.launch({
    executablePath: chromePath(),
    headless: true,
    userDataDir: profile,
    args: ['--no-sandbox']
  });
  page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 1000 });
  page.on('pageerror', e => report.errors.push(e.message));
}
async function api(path) {
  const response = await fetch(`${backend}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(15000)
  });
  assert.equal(response.status, 200, path);
  const result = await response.json();
  assert.equal(result.code, 200, path);
  return result.data;
}
async function click(text) {
  await page.waitForFunction(
    value => [...document.querySelectorAll('button')].some(b => b.textContent.trim() === value && !b.disabled),
    {},
    text
  );
  await page.evaluate(
    value => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === value).click(),
    text
  );
}
async function fresh() {
  const previous = await page.$eval('.work-chat', el => el.dataset.conversationId);
  await click('新聊天');
  await page.waitForFunction(
    id => {
      const el = document.querySelector('.work-chat');
      return el?.dataset.ready === 'true' && el.dataset.conversationId !== id;
    },
    {},
    previous
  );
  return page.$eval('.work-chat', el => el.dataset.conversationId);
}
async function send(question) {
  await page.type('textarea', question);
  await page.keyboard.press('Enter');
  await page.waitForSelector('[aria-label="停止生成"]');
}
async function waitRun(id, done = false) {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const run = await api(`/memory/sessions/${id}/run`);
    if (run.requestId && (!done || run.state !== 'RUNNING')) return run;
    await delay(250);
  }
  throw new Error(`Background run timeout: ${id}`);
}
function check(label) {
  report.checks.push(label);
  process.stdout.write(`${label}\n`);
}
try {
  await launch();
  await page.goto(`${frontend}/login`);
  await page.waitForSelector('input[type="password"]');
  await page.locator('input:not([type="password"])').fill(process.env.ADMIN_BOOTSTRAP_USERNAME || 'admin');
  await page.locator('input[type="password"]').fill(password);
  const login = page.waitForResponse(r => r.url().endsWith('/users/login') && r.request().method() === 'POST');
  await click('登录账号');
  token = (await (await login).json()).data.token;
  await page.waitForSelector('.work-chat[data-ready="true"]');
  const a = await fresh();
  await send(
    '本地工作台联调 A：请直接用中文写一篇约 1500 字的原创短文，介绍如何把一个大目标分解成每周可执行的计划。分五节并各举一个例子，不需要查资料或调用任何工具。'
  );
  const first = await waitRun(a);
  report.runs.push({ conversationId: a, requestId: first.requestId });
  assert.equal(first.state, 'RUNNING');
  await page.click('button[aria-label="Coding"]');
  await page.waitForSelector('[aria-label="Coding 工作台"]');
  assert.equal((await api(`/memory/sessions/${a}/run`)).state, 'RUNNING');
  check('Work answer continues while viewing Coding');
  await page.click('button[aria-label="聊天"]');
  await page.waitForSelector('.work-chat[data-ready="true"]');
  const b = await fresh();
  await send('本地工作台联调 B：直接回答 17 加 25 等于多少，不要调用工具，只需一句话。');
  const second = await waitRun(b);
  report.runs.push({ conversationId: b, requestId: second.requestId });
  assert.equal((await api(`/memory/sessions/${a}/run`)).state, 'RUNNING');
  check('two separate Work conversations run concurrently');
  await browser.close();
  browser = null;
  page = null;
  const completed = await Promise.all([waitRun(a, true), waitRun(b, true)]);
  for (const run of completed) assert.equal(run.state, 'COMPLETED');
  assert.match(completed[1].text, /42/);
  check('both real model answers persist after closing the entire browser');
  await launch();
  await page.goto(`${frontend}/chat?conversation=${a}`);
  await page.waitForSelector('.work-chat[data-ready="true"]');
  await page.waitForFunction(() => document.querySelectorAll('.assistant-content').length === 1);
  const restored = await page.evaluate(async () => {
    const { useChatStore } = await import('/src/store/modules/chat/index.ts');
    return useChatStore()
      .list.filter(m => m.role === 'assistant')
      .map(m => m.content);
  });
  assert.deepEqual(restored, [completed[0].text]);
  await page.reload();
  await page.waitForSelector('.work-chat[data-ready="true"]');
  assert.equal(await page.$$eval('.assistant-content', els => els.length), 1);
  check('deep link and refresh restore the exact answer without duplicate chunks');
  await page.screenshot({ path: resolve(output, 'work-conversation.png') });
  await fresh();
  await page.screenshot({ path: resolve(output, 'work-home.png') });
  assert.equal(await page.$eval('.primary-rail', el => Math.round(el.getBoundingClientRect().width)), 64);
  assert.ok(await page.$('.rail-link svg'));
  await page.evaluate(async () => {
    const { useThemeStore } = await import('/src/store/modules/theme/index.ts');
    useThemeStore().setThemeScheme('dark');
  });
  await page.waitForFunction(() => document.documentElement.classList.contains('dark'));
  await page.screenshot({ path: resolve(output, 'work-dark.png') });
  await page.setViewport({ width: 390, height: 844 });
  await page.waitForFunction(() => document.querySelector('.feature-sidebar').getBoundingClientRect().width === 0);
  await page.click('[aria-label="展开副栏"]');
  await page.waitForFunction(() => document.querySelector('.feature-sidebar').getBoundingClientRect().width === 280);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= 390));
  await page.screenshot({ path: resolve(output, 'work-mobile.png') });
  check('desktop icon rail, bundled icons, dark theme and mobile sidebar');
  assert.deepEqual(report.errors, []);
  report.passed = true;
} catch (failure) {
  report.passed = false;
  report.failure = failure.message.replaceAll(token || 'unused-token', '[REDACTED]');
  process.stderr.write(`${report.failure}\n`);
  process.exitCode = 1;
  for (const run of report.runs) {
    await fetch(`${backend}/api/v1/memory/sessions/${run.conversationId}/runs/${run.requestId}/stop`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(10000)
    }).catch(() => {});
  }
} finally {
  writeFileSync(resolve(output, 'live-work-report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  await browser?.close();
  rmSync(profile, { recursive: true, force: true });
}
