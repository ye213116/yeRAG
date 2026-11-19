/* eslint-disable no-await-in-loop -- Browser fixtures and user actions run in order. */
import assert from 'node:assert/strict';
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
const origin = 'http://127.0.0.1:19528';
const id = '11111111-1111-4111-8111-111111111111';
const initial = {
  id,
  version: 1,
  title: '检索升级报告',
  content: '第一版正文 <script>window.pwned=true</script>',
  sources: [],
  createdAt: '2026-11-19T01:00:00Z'
};
const history = [initial];
let conflict = false;
let denied = false;
let server;
let websocket;
let vite;
let browser;
let logs = '';

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
      send([{ id: owner, status: 'OPEN', last_activity_at: '2026-11-19T01:00:00Z' }]);
      return;
    }
    if (/\/memory\/sessions\/[^/]+\/messages$/.test(path)) {
      send([]);
      return;
    }
    if (path === '/api/v1/artifacts') {
      send({
        items:
          owner === 'alice' && !denied
            ? history.slice(-1).map(v => ({ ...v, sourceCount: 0, updatedAt: v.createdAt }))
            : [],
        nextOffset: null
      });
      return;
    }
    if (path.startsWith('/api/v1/artifacts/')) {
      if (owner !== 'alice' || denied) {
        send(null, 404);
        return;
      }
      if (path.endsWith('/versions') && req.method === 'POST') {
        const input = JSON.parse(body);
        if (conflict || input.expectedVersion !== history.at(-1).version) {
          send(null, 409);
          return;
        }
        const v = { ...history.at(-1), title: input.title, content: input.content, version: history.length + 1 };
        history.push(v);
        send(v);
        return;
      }
      if (path.endsWith('/versions')) {
        send(history.toReversed());
        return;
      }
      send(
        url.searchParams.has('version')
          ? history.find(v => v.version === Number(url.searchParams.get('version')))
          : history.at(-1)
      );
      return;
    }
    send(null, 404);
  });
  await new Promise(done => {
    server.listen(0, '127.0.0.1', done);
  });
  const backend = `http://127.0.0.1:${server.address().port}`;
  websocket = new WebSocketServer({ server });
  websocket.on('connection', socket => socket.send(JSON.stringify({ type: 'connection', sessionId: 'fixture' })));
  vite = spawn(
    process.execPath,
    ['node_modules/vite/bin/vite.js', '--mode', 'test', '--host', '127.0.0.1', '--port', '19528', '--strictPort'],
    {
      cwd: frontend,
      env: {
        ...process.env,
        VITE_HTTP_PROXY: 'N',
        VITE_SERVICE_BASE_URL: `${backend}/api/v1`,
        VITE_OTHER_SERVICE_BASE_URL: JSON.stringify({ api: `${backend}/api/v1`, ws: backend.replace('http:', 'ws:') }),
        VITE_STORAGE_PREFIX: 'artifact-e2e-'
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
  await page.evaluate(async artifactId => {
    const { router } = await import('/src/router/index.ts');
    await router.push(`/artifacts?id=${artifactId}`);
  }, id);
  return page;
}

async function clickText(page, text) {
  await page.evaluate(
    value => [...document.querySelectorAll('button')].find(button => button.textContent.trim() === value).click(),
    text
  );
}

test('report editing, history, safe export, conflict preservation and revoked reads', async () => {
  const page = await login('alice');
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.waitForSelector('.report-body');
    assert.equal(await page.evaluate(() => window.pwned), undefined);
    await clickText(page, '编辑此版本');
    await page.waitForSelector('textarea[aria-label="报告正文"]');
    await page.$eval('textarea[aria-label="报告正文"]', el => {
      el.value = '第二版正文';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await clickText(page, '保存为新版本');
    await page.waitForFunction(() => document.querySelector('.report-body')?.textContent === '第二版正文');
    assert.equal(history.length, 2);
    await page.click('[aria-label="报告版本"]');
    await page.waitForSelector('.n-base-select-option');
    await page.evaluate(() =>
      [...document.querySelectorAll('.n-base-select-option')].find(el => el.textContent.includes('v1 ·')).click()
    );
    await page.waitForFunction(() => document.querySelector('.report-body')?.textContent.includes('第一版正文'));
    assert.equal(
      await page.evaluate(
        () => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '编辑此版本').disabled
      ),
      true
    );
    await page.evaluate(() => {
      const original = URL.createObjectURL;
      URL.createObjectURL = function createObjectURL(blob) {
        blob.text().then(text => {
          window.exported = text;
        });
        return original.call(this, blob);
      };
    });
    await clickText(page, '下载 HTML');
    await page.waitForFunction(() => Boolean(window.exported));
    assert.match(await page.evaluate(() => window.exported), /&lt;script&gt;/);
    assert.doesNotMatch(await page.evaluate(() => window.exported), /<script>/);
    await clickText(page, '读取最新版本');
    await page.waitForFunction(() => document.querySelector('.report-body')?.textContent === '第二版正文');
    await clickText(page, '编辑此版本');
    await page.waitForSelector('textarea[aria-label="报告正文"]');
    await page.$eval('textarea[aria-label="报告正文"]', el => {
      el.value = '发生冲突也要保留的修改';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    conflict = true;
    await clickText(page, '保存为新版本');
    await page.waitForFunction(() => document.body.textContent.includes('编辑内容已保留'));
    assert.equal(await page.$eval('textarea[aria-label="报告正文"]', el => el.value), '发生冲突也要保留的修改');
    assert.equal(history.length, 2);
    await clickText(page, '取消修改');
    await page.setViewport({ width: 390, height: 844 });
    await page.evaluate(
      () =>
        new Promise(resolveFrame => {
          requestAnimationFrame(() => {
            requestAnimationFrame(resolveFrame);
          });
        })
    );
    await page.screenshot({ path: '/tmp/yerag-artifacts-mobile.png', fullPage: true });
    // Scope to this page: the pre-existing global desktop header overflows at 390px.
    const overflow = await page.$$eval('.artifact-page, .artifact-page *', elements =>
      elements
        .filter(el => el.getBoundingClientRect().right > innerWidth + 1)
        .map(el => ({ tag: el.tagName, right: el.getBoundingClientRect().right }))
    );
    assert.deepEqual(overflow, []);
    await page.setViewport({ width: 1440, height: 1000 });
    await page.screenshot({ path: '/tmp/yerag-artifacts-desktop.png', fullPage: true });
    denied = true;
    await clickText(page, '读取最新版本');
    await page.waitForFunction(() => document.body.textContent.includes('报告读取失败'));
    assert.equal(await page.$('.report-body'), null);
    assert.deepEqual(errors, []);
  } finally {
    conflict = false;
    denied = false;
    await page.browserContext().close();
  }
});

test('another user cannot see the report or its body', async () => {
  const page = await login('bob');
  try {
    await page.waitForFunction(() => document.body.textContent.includes('报告读取失败'));
    assert.equal(await page.$('.report-body'), null);
    assert.equal(await page.$$eval('.artifact-item', els => els.length), 0);
  } finally {
    await page.browserContext().close();
  }
});
