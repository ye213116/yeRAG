/* eslint-disable no-await-in-loop -- Browser fixtures and user actions run in order. */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import process from 'node:process';
import { spawn } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { createServer } from 'node:http';
import { after, before, test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import puppeteer from 'puppeteer-core';
import { WebSocketServer } from 'ws';

const frontend = resolve(import.meta.dirname, '../..');
const origin = 'http://127.0.0.1:19529';
const workspaces = [];
const tasks = [];
const publications = [];
const submissions = [];
let server;
let websocket;
let vite;
let browser;
let logs = '';
let capabilitiesEnabled = true;
let capabilitiesStatus = 200;
let workspaceReads = 0;

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
  throw new Error('Set BROWSER_EXECUTABLE_PATH to an installed Chromium executable');
}

async function waitFor(check, label, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await delay(50);
  }
  throw new Error(`Timed out: ${label}; ${logs.slice(-1500)}`);
}

before(async () => {
  // eslint-disable-next-line complexity -- Minimal HTTP fixture for real frontend navigation and editing.
  server = createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Request-Id');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    if (req.method === 'OPTIONS') {
      res.writeHead(204).end();
      return;
    }
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname;
    const owner = (req.headers.authorization || '').replace('Bearer token-', '');
    let body = '';
    for await (const chunk of req) body += chunk;
    const send = (data, status = 200) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code: status, message: status === 200 ? 'success' : '版本冲突或不可访问', data }));
    };
    if (path === '/api/v1/users/login') {
      send({ token: `token-${JSON.parse(body).username}`, refreshToken: 'fixture-refresh' });
      return;
    }
    if (!owner) {
      send(null, 401);
      return;
    }
    if (path === '/api/v1/users/me') {
      send({ id: owner === 'alice' ? 1 : 2, username: owner, role: 'USER', orgTags: [], primaryOrg: '' });
      return;
    }
    if (path === '/api/v1/memory/sessions/current') {
      send({ conversationId: owner });
      return;
    }
    if (path === '/api/v1/memory/sessions') {
      send(
        req.method === 'POST'
          ? { conversationId: owner }
          : [{ id: owner, title: '新对话', status: 'OPEN', last_activity_at: '2026-11-19T01:00:00Z' }]
      );
      return;
    }
    if (/\/memory\/sessions\/[^/]+\/run$/.test(path)) {
      send({ state: 'IDLE', seq: 0 });
      return;
    }
    if (/\/memory\/sessions\/[^/]+\/messages$/.test(path)) {
      send([]);
      return;
    }
    if (path.endsWith('/coding/capabilities')) {
      send({ enabled: capabilitiesEnabled }, capabilitiesStatus);
      return;
    }
    if (path.endsWith('/coding/repositories')) {
      send(owner === 'alice' ? [{ id: 'demo', name: 'example/repo', branches: ['main'] }] : []);
      return;
    }
    if (path.endsWith('/coding/workspaces')) {
      workspaceReads += 1;
      if (req.method === 'POST') {
        const input = JSON.parse(body);
        const workspace = {
          id: randomUUID(),
          owner,
          repositoryId: input.repositoryId,
          branch: input.branch,
          baseSha: 'a'.repeat(40),
          permissions: input.permissions,
          status: 'NEW',
          activeTaskId: null
        };
        workspaces.push(workspace);
        send(workspace);
      } else send(workspaces.filter(w => w.owner === owner));
      return;
    }
    if (path.endsWith('/coding/tasks')) {
      send(
        tasks
          .filter(t => t.owner === owner)
          .map(t => ({
            id: t.id,
            workspace_id: t.workspaceId,
            status: t.status,
            repository_id: 'demo',
            title: t.message
          }))
      );
      return;
    }
    const taskId = path.match(/\/coding\/tasks\/([^/]+)/)?.[1];
    const task = tasks.find(t => t.id === taskId && t.owner === owner);
    if (task && path.endsWith('/pull-request')) {
      publications.push(JSON.parse(body));
      task.publishState = 'CREATED';
      task.prUrl = 'https://github.com/example/repo/pull/1';
      send({ url: task.prUrl });
      return;
    }
    if (task) {
      send(task);
      return;
    }
    send(null, 404);
  });
  await new Promise(done => {
    server.listen(0, '127.0.0.1', done);
  });
  const backend = `http://127.0.0.1:${server.address().port}`;
  websocket = new WebSocketServer({ server });
  websocket.on('connection', socket => {
    socket.send(JSON.stringify({ type: 'connection', sessionId: 'fixture' }));
    socket.on('message', data => {
      const request = JSON.parse(data.toString());
      submissions.push(request);
      assert.equal(request.agentMode, 'coding');
      const workspace = workspaces.find(w => w.id === request.workspaceId);
      assert.ok(workspace);
      const task = {
        id: randomUUID(),
        owner: workspace.owner,
        workspaceId: workspace.id,
        status: 'RUNNING',
        summary: null,
        message: request.message,
        result: null,
        baseSha: workspace.baseSha,
        events: [],
        publishState: 'NONE',
        prUrl: null
      };
      tasks.push(task);
      workspace.activeTaskId = task.id;
      const tagged = { requestId: request.requestId, conversationId: request.conversationId, taskId: task.id };
      socket.send(JSON.stringify({ ...tagged, chunk: 'Coding 任务已提交' }));
      setTimeout(() => {
        if (socket.readyState === 1) socket.send(JSON.stringify({ ...tagged, type: 'completion', status: 'finished' }));
      }, 30);
    });
  });
  vite = spawn(
    process.execPath,
    ['node_modules/vite/bin/vite.js', '--mode', 'test', '--host', '127.0.0.1', '--port', '19529', '--strictPort'],
    {
      cwd: frontend,
      env: {
        ...process.env,
        VITE_HTTP_PROXY: 'N',
        VITE_SERVICE_BASE_URL: `${backend}/api/v1`,
        VITE_OTHER_SERVICE_BASE_URL: JSON.stringify({ api: `${backend}/api/v1`, ws: backend.replace('http:', 'ws:') }),
        VITE_STORAGE_PREFIX: 'coding-e2e-'
      },
      stdio: ['ignore', 'pipe', 'pipe']
    }
  );
  vite.stdout.on('data', value => {
    logs += value;
  });
  vite.stderr.on('data', value => {
    logs += value;
  });
  await waitFor(async () => {
    try {
      return (await fetch(origin)).ok;
    } catch {
      return false;
    }
  }, 'Vite startup');
  browser = await puppeteer.launch({ executablePath: chromePath(), headless: true, args: ['--no-sandbox'] });
});

after(async () => {
  await browser?.close();
  vite?.kill('SIGTERM');
  for (const socket of websocket?.clients || []) socket.terminate();
  await new Promise(done => {
    if (websocket) websocket.close(done);
    else done();
  });
  await new Promise(done => {
    if (server) server.close(done);
    else done();
  });
});

async function login(owner) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.setViewport({ width: 1440, height: 1000 });
  await page.setViewport({ width: 1440, height: 1000 });
  await page.setRequestInterception(true);
  page.on('request', request =>
    ['127.0.0.1', 'localhost'].includes(new URL(request.url()).hostname) ? request.continue() : request.abort()
  );
  await page.goto(`${origin}/login`);
  await page.waitForSelector('input[type="password"]');
  await page.evaluate(async username => {
    const { useAuthStore } = await import('/src/store/modules/auth/index.ts');
    await useAuthStore().login(username, 'fixture123');
  }, owner);
  await page.waitForFunction(() => location.pathname === '/chat');
  return page;
}

async function clickText(page, text) {
  await page.evaluate(
    value => [...document.querySelectorAll('button')].find(button => button.textContent.trim() === value).click(),
    text
  );
}

async function openCodingEntry(page) {
  await page.waitForSelector('button[aria-label="Coding"]');
  await page.click('button[aria-label="Coding"]');
  await page.waitForFunction(() => location.pathname === '/coding');
}
async function coding(page) {
  await openCodingEntry(page);
  await page.waitForFunction(
    () =>
      document.body.textContent.includes('在项目中开始一个任务') || document.body.textContent.includes('还没有可用项目')
  );
}

test('cloud task survives leaving the page, displays safe Diff and publishes only after confirmation', async () => {
  let page = await login('alice');
  await coding(page);
  await page.waitForSelector('textarea[aria-label="开发任务描述"]');
  await page.type('textarea[aria-label="开发任务描述"]', '修复计算错误并运行测试');
  await page.keyboard.press('Enter');
  await waitFor(() => workspaces.length === 1, 'workspace');
  assert.deepEqual(workspaces[0].permissions.sort(), ['EXECUTE', 'READ', 'WRITE']);
  await waitFor(() => submissions.length === 1, 'task submission');
  assert.equal(submissions[0].sourceMode, 'auto');
  await page.waitForFunction(() => document.body.textContent.includes('任务已提交'));
  await page.close();
  assert.equal(tasks[0].status, 'RUNNING');
  tasks[0].status = 'REVIEW_READY';
  tasks[0].summary = '修复完成，测试通过';
  tasks[0].result = {
    diff: '+<script>window.pwned=true</script>',
    changeSetHash: 'reviewed-hash',
    verification: { id: 'verified-run', status: 'PASSED', tests: 1 }
  };
  workspaces[0].status = 'FROZEN';
  workspaces[0].activeTaskId = null;
  page = await login('alice');
  await coding(page);
  await page.waitForSelector('.project-task');
  await page.click('.project-task');
  await page.waitForFunction(() => document.body.textContent.includes('修复完成，测试通过'));
  await page.reload();
  await page.waitForFunction(() => document.body.textContent.includes('修复完成，测试通过'));
  assert.equal(await page.evaluate(() => location.pathname), '/coding');
  await page.waitForFunction(() => document.body.textContent.includes('window.pwned=true'));
  assert.equal(await page.evaluate(() => window.pwned), undefined);
  assert.equal(publications.length, 0);
  await clickText(page, '确认 Diff 并创建 PR');
  await page.waitForSelector('.n-popconfirm__action');
  assert.equal(publications.length, 0);
  await page.evaluate(() => document.querySelector('.n-popconfirm__action button:last-child').click());
  await waitFor(() => publications.length === 1, 'confirmed publication');
  assert.deepEqual(publications[0], {
    baseSha: 'a'.repeat(40),
    changeSetHash: 'reviewed-hash',
    verificationId: 'verified-run'
  });
  await page.waitForSelector('a[href="https://github.com/example/repo/pull/1"]');
  await page.close();
  page = await login('bob');
  await coding(page);
  await page.waitForFunction(() => document.body.textContent.includes('当前账号没有可用仓库'));
  assert.equal(await page.evaluate(() => document.body.textContent.includes('修复完成，测试通过')), false);
  assert.equal(await page.$('[aria-label="提交 Coding 任务"]'), null);
  await page.close();
});

test('Coding entry remains visible when disabled, retries capability failures and returns to Work', async () => {
  capabilitiesEnabled = false;
  const page = await login('alice');
  const reads = workspaceReads;
  await openCodingEntry(page);
  await page.waitForFunction(() => document.body.textContent.includes('当前服务尚未启用 Coding'));
  assert.equal(await page.$('textarea'), null);
  assert.equal(workspaceReads, reads);
  capabilitiesStatus = 503;
  await clickText(page, '重新检查');
  await page.waitForFunction(() => document.body.textContent.includes('无法连接 Coding 服务'));
  capabilitiesStatus = 200;
  capabilitiesEnabled = true;
  await clickText(page, '重新检查');
  await page.waitForFunction(() => document.body.textContent.includes('在项目中开始一个任务'));
  await page.click('button[aria-label="聊天"]');
  await page.waitForFunction(
    () => location.pathname === '/chat' && !document.body.textContent.includes('在项目中开始一个任务')
  );
  assert.match(await page.$eval('textarea', el => el.placeholder), /向 yeRAG 提问/);
  await page.close();
});
