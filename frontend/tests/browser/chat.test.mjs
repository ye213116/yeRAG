/* eslint-disable no-await-in-loop -- Browser scenarios and polling intentionally run in order. */
import process from 'node:process';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { after, before, test } from 'node:test';
import puppeteer from 'puppeteer-core';
import { WebSocketServer } from 'ws';

const frontend = resolve(import.meta.dirname, '../..');
const origin = 'http://127.0.0.1:19527';
const state = {
  sessions: new Map(),
  sockets: new Map(),
  requests: new Map(),
  stopped: 0,
  historyStatus: 200,
  historyCode: 200
};
let server;
let websocket;
let vite;
let browser;
let backend;
let logs = '';

function chromePath() {
  if (process.env.BROWSER_EXECUTABLE_PATH) return process.env.BROWSER_EXECUTABLE_PATH;
  for (const path of ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome']) {
    if (existsSync(path)) return path;
  }
  const cache = resolve(homedir(), '.cache/ms-playwright');
  if (existsSync(cache)) {
    for (const folder of readdirSync(cache)
      .filter(name => name.startsWith('chromium-'))
      .reverse()) {
      const path = resolve(cache, folder, 'chrome-linux64/chrome');
      if (existsSync(path)) return path;
    }
  }
  throw new Error('Set BROWSER_EXECUTABLE_PATH to an installed Chromium executable');
}

function sessions(owner) {
  if (!state.sessions.has(owner))
    state.sessions.set(owner, [
      { id: `${owner}-1`, status: 'OPEN', last_activity_at: '2026-10-28T00:00:00', messages: [] }
    ]);
  return state.sessions.get(owner);
}

async function waitFor(check, label, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await delay(50);
  }
  throw new Error(`Timed out: ${label}`);
}

before(async () => {
  // eslint-disable-next-line complexity -- Explicit fixture routes keep the mock protocol inspectable.
  server = createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Request-Id');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
    if (req.method === 'OPTIONS') {
      res.writeHead(204).end();
      return;
    }
    const path = new URL(req.url, 'http://localhost').pathname;
    const owner = (req.headers.authorization || '').replace('Bearer token-', '');
    const send = (data, status = 200, code = status) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({ code, message: status === 200 && code === 200 ? 'success' : 'Permission denied', data })
      );
    };
    let body = '';
    for await (const chunk of req) body += chunk;
    if (path === '/api/v1/users/login') {
      const { username } = JSON.parse(body);
      send({ token: `token-${username}`, refreshToken: 'fixture-refresh' });
      return;
    }
    if (!owner || owner === req.headers.authorization) {
      send(null, 401);
      return;
    }
    if (path === '/api/v1/users/me') {
      // Let the new token connect before profile loading finishes.
      if (owner === 'alice') await delay(600);
      send({
        id: owner === 'admin' ? 1 : 2,
        username: owner,
        role: owner === 'admin' ? 'ADMIN' : 'USER',
        orgTags: ['DEFAULT'],
        primaryOrg: 'DEFAULT'
      });
      return;
    }
    if (path === '/api/v1/users/logout') {
      send(null);
      return;
    }
    if (path === '/api/v1/chat/websocket-token') {
      send({ cmdToken: 'fixture-stop' });
      return;
    }
    if (path === '/api/v1/admin/users/list') {
      send({ content: [{ userId: 1, username: 'admin' }] });
      return;
    }
    if (path === '/api/v1/admin/conversation') {
      send(
        [
          { role: 'user', content: 'saved history question' },
          { role: 'assistant', content: 'saved history answer', status: 'finished' }
        ],
        state.historyStatus,
        state.historyCode
      );
      return;
    }
    const owned = sessions(owner);
    if (path === '/api/v1/memory/sessions/current') {
      send({ conversationId: owned.find(s => s.status === 'OPEN').id });
      return;
    }
    if (path === '/api/v1/memory/sessions' && req.method === 'POST') {
      owned.forEach(s => {
        s.status = 'CLOSED';
      });
      const next = {
        id: `${owner}-${owned.length + 1}`,
        status: 'OPEN',
        last_activity_at: '2026-10-28T00:01:00',
        messages: []
      };
      owned.unshift(next);
      send({ conversationId: next.id });
      return;
    }
    if (path === '/api/v1/memory/sessions') {
      send(owned);
      return;
    }
    const match = path.match(/^\/api\/v1\/memory\/sessions\/([^/]+)\/messages$/);
    if (match) {
      const session = owned.find(s => s.id === match[1]);
      send(session?.messages, session ? 200 : 404);
      return;
    }
    send(null, 404);
  });
  await new Promise(resolveListening => {
    server.listen(0, '127.0.0.1', resolveListening);
  });
  backend = `http://127.0.0.1:${server.address().port}`;
  websocket = new WebSocketServer({ server });
  websocket.on('connection', (socket, req) => {
    const owner = req.url.split('/').at(-1).replace('token-', '');
    state.sockets.set(owner, socket);
    socket.send(JSON.stringify({ type: 'connection', sessionId: `socket-${owner}` }));
    let cancelled = false;
    socket.on('message', async raw => {
      const message = JSON.parse(raw);
      if (message.type === 'stop') {
        cancelled = true;
        state.stopped += 1;
        return;
      }
      if (message.type !== 'chat') return;
      state.requests.set(owner, message);
      cancelled = false;
      const send = value =>
        socket.readyState === 1 &&
        socket.send(JSON.stringify({ requestId: message.requestId, conversationId: message.conversationId, ...value }));
      if (message.message === 'plan') {
        send(planEvent());
        return;
      }
      send({ type: 'agent_status', message: '正在回答' });
      send({ type: 'chunk', chunk: `${owner} answer: ` });
      await delay(message.message === 'slow' ? 2000 : 100);
      if (cancelled || socket.readyState !== 1) return;
      send({ type: 'chunk', chunk: message.message });
      const session = sessions(owner).find(s => s.id === message.conversationId);
      if (session.status === 'OPEN')
        session.messages.push(
          { role: 'user', content: message.message },
          { role: 'assistant', content: `${owner} answer: ${message.message}`, status: 'finished' }
        );
      send({ type: 'completion', status: 'finished' });
    });
  });
  vite = spawn(
    process.execPath,
    ['node_modules/vite/bin/vite.js', '--mode', 'test', '--host', '127.0.0.1', '--port', '19527', '--strictPort'],
    {
      cwd: frontend,
      env: {
        ...process.env,
        VITE_HTTP_PROXY: 'N',
        VITE_SERVICE_BASE_URL: `${backend}/api/v1`,
        VITE_OTHER_SERVICE_BASE_URL: JSON.stringify({ api: `${backend}/api/v1`, ws: backend.replace('http:', 'ws:') }),
        VITE_STORAGE_PREFIX: 'e2e-'
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
  await waitFor(
    async () => {
      if (vite.exitCode !== null) throw new Error(`Vite exited: ${logs}`);
      try {
        return (await fetch(origin)).ok;
      } catch {
        return false;
      }
    },
    'Vite startup',
    30000
  );
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

async function login(page, username = 'admin', navigate = true) {
  if (navigate) await page.goto(`${origin}/login`, { waitUntil: 'networkidle0' });
  await page.waitForSelector('input[type="password"]');
  await page.evaluate(name => {
    for (const [input, value] of [
      [document.querySelector('input[type="text"]'), name],
      [document.querySelector('input[type="password"]'), 'fixture123']
    ]) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }, username);
  await page.click('button.n-button--primary-type.n-button--large-type');
  await page.waitForFunction(() => location.pathname === '/chat');
  await page.waitForFunction(() =>
    [...document.querySelectorAll('button')].some(button => button.textContent.includes('新建对话') && !button.disabled)
  );
}

async function newPage() {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.setRequestInterception(true);
  page.on('request', request =>
    ['127.0.0.1', 'localhost'].includes(new URL(request.url()).hostname) ? request.continue() : request.abort()
  );
  return page;
}

async function sendMessage(page, message) {
  await page.type('textarea', message);
  await page.keyboard.press('Enter');
}

async function clickText(page, text) {
  await page.evaluate(
    value => [...document.querySelectorAll('button')].find(button => button.textContent.trim() === value).click(),
    text
  );
}

function planEvent(statuses = ['running', 'pending', 'pending']) {
  return {
    type: 'agent_status',
    status: 'plan_updated',
    message: '任务执行进度',
    details: {
      tasks: ['阅读甲方案 <img src=x>', '阅读乙方案', '综合证据并生成回答'].map((title, index) => ({
        id: String(index + 1),
        title,
        status: statuses[index],
        message: '任务状态说明'
      }))
    }
  };
}

function reply(owner, payload, request = state.requests.get(owner)) {
  state.sockets
    .get(owner)
    .send(JSON.stringify({ requestId: request.requestId, conversationId: request.conversationId, ...payload }));
}

test('plan tasks render before the answer, update during streaming and reject late events', async () => {
  const page = await newPage();
  try {
    await login(page);
    await sendMessage(page, 'plan');
    await page.waitForSelector('[aria-label="任务列表"] [data-task-status="running"]');
    assert.equal(await page.$$eval('[aria-label="任务列表"] li', nodes => nodes.length), 3);
    assert.equal(await page.$$eval('[aria-label="任务列表"] img', nodes => nodes.length), 0);
    reply('admin', planEvent(['completed', 'running', 'pending']));
    await page.waitForFunction(
      () => document.querySelector('[aria-label="任务列表"] li').dataset.taskStatus === 'completed'
    );
    reply('admin', { type: 'chunk', chunk: 'planned answer' });
    reply('admin', planEvent(['completed', 'completed', 'completed']));
    reply('admin', { type: 'completion', status: 'finished' });
    await page.waitForFunction(
      () => document.body.textContent.includes('planned answer') && document.body.textContent.includes('3/3')
    );
    reply('admin', planEvent());
    await delay(100);
    assert.equal(await page.$$eval('[aria-label="任务列表"] [data-task-status="completed"]', nodes => nodes.length), 3);
    const oldRequest = state.requests.get('admin');
    await clickText(page, '新建对话');
    reply('admin', planEvent(), oldRequest);
    await delay(100);
    assert.equal(await page.$$eval('[aria-label="任务列表"]', nodes => nodes.length), 0);
    await sendMessage(page, 'simple');
    await page.waitForFunction(() => document.body.textContent.includes('admin answer: simple'));
    assert.equal(await page.$$eval('[aria-label="任务列表"]', nodes => nodes.length), 0);
  } finally {
    await page.browserContext().close();
  }
});

test('stopping a plan before any answer preserves cancelled tasks and errors settle pending tasks', async () => {
  const page = await newPage();
  try {
    await login(page);
    await sendMessage(page, 'plan');
    await page.waitForSelector('[aria-label="任务列表"]');
    const previous = state.stopped;
    await page.focus('button[aria-label="停止生成"]');
    await page.keyboard.press('Enter');
    await waitFor(() => state.stopped > previous, 'plan stop reaches the WebSocket');
    await page.waitForSelector('[data-task-status="cancelled"]');
    reply('admin', planEvent(['completed', 'completed', 'completed']));
    await delay(100);
    assert.equal(await page.$$eval('[data-task-status="skipped"]', nodes => nodes.length), 2);
    await sendMessage(page, 'plan');
    await page.waitForSelector('[data-task-status="running"]');
    reply('admin', { type: 'error', error: '执行失败' });
    await page.waitForSelector('[data-task-status="failed"]');
    assert.equal(await page.$$eval('[data-task-status="running"]', nodes => nodes.length), 0);
    assert.match(await page.$eval('body', element => element.textContent), /执行失败/);
  } finally {
    await page.browserContext().close();
  }
});

test('history renders and both forms of 403 preserve authentication; 401 logs out', async () => {
  for (const [status, code] of [
    [200, 200],
    [403, 403],
    [200, 403],
    [401, 401]
  ]) {
    state.historyStatus = status;
    state.historyCode = code;
    const page = await newPage();
    try {
      await login(page);
      await page.goto(`${origin}/chat-history`, { waitUntil: 'networkidle0' });
      if (status === 401) assert.match(new URL(page.url()).pathname, /^\/login/);
      else {
        assert.equal(new URL(page.url()).pathname, '/chat-history');
        if (code === 200)
          assert.match(await page.$eval('body', element => element.textContent), /saved history answer/);
        assert.equal(await page.evaluate(() => Boolean(localStorage.getItem('e2e-token'))), true);
      }
    } finally {
      await page.browserContext().close();
    }
  }
  state.historyStatus = 200;
  state.historyCode = 200;
});

test('streams, persists, reconnects, isolates a new conversation and ignores late completion', async () => {
  const page = await newPage();
  try {
    await login(page);
    await sendMessage(page, 'remember fixture');
    await page.waitForFunction(() => document.body.textContent.includes('admin answer: remember fixture'));
    state.sockets.get('admin').close();
    await waitFor(() => state.sockets.get('admin')?.readyState === 1, 'websocket reconnect');
    await page.waitForFunction(() => document.body.textContent.includes('admin answer: remember fixture'));
    await sendMessage(page, 'slow');
    await page.waitForSelector('button[aria-label="停止生成"]');
    await clickText(page, '新建对话');
    await delay(2300);
    assert.doesNotMatch(await page.$eval('body', element => element.textContent), /admin answer: slow/);
    await sendMessage(page, 'new session');
    await page.waitForFunction(() => document.body.textContent.includes('admin answer: new session'));
    assert.doesNotMatch(await page.$eval('body', element => element.textContent), /remember fixture/);
  } finally {
    await page.browserContext().close();
  }
});

test('stop generation works without the development proxy', async () => {
  const page = await newPage();
  try {
    await login(page);
    const previous = state.stopped;
    await sendMessage(page, 'slow');
    await page.waitForSelector('button[aria-label="停止生成"]');
    await page.focus('button[aria-label="停止生成"]');
    await page.keyboard.press('Enter');
    await waitFor(() => state.stopped > previous, 'stop reaches the WebSocket');
    await delay(2200);
    assert.equal(state.stopped, previous + 1);
  } finally {
    await page.browserContext().close();
  }
});

test('signing out and into another account replaces socket and clears prior messages', async () => {
  const page = await newPage();
  try {
    await login(page);
    await sendMessage(page, 'private admin message');
    await page.waitForFunction(() => document.body.textContent.includes('admin answer: private admin message'));
    // Exercise the real logout store; its UI dialog only supplies confirmation.
    await page.evaluate(async () => {
      const { useAuthStore } = await import('/src/store/modules/auth/index.ts');
      await useAuthStore().logout();
    });
    await login(page, 'alice', false);
    assert.doesNotMatch(await page.$eval('body', element => element.textContent), /private admin message/);
    await sendMessage(page, 'alice message');
    await page.waitForFunction(() => document.body.textContent.includes('alice answer: alice message'));
    assert.equal(state.sockets.get('admin')?.readyState, 3);
  } finally {
    await page.browserContext().close();
  }
});
