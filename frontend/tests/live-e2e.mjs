/* eslint-disable no-await-in-loop -- Polling and fixture cleanup must be sequential. */
import process from 'node:process';
import { Buffer } from 'node:buffer';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import puppeteer from 'puppeteer-core';

const origin = process.env.YERAG_E2E_FRONTEND || 'http://localhost:9527';
const api = process.env.YERAG_E2E_BACKEND || 'http://localhost:8081/api/v1';
const output = process.env.YERAG_E2E_OUTPUT;
assert.ok(output, 'YERAG_E2E_OUTPUT must be an isolated temporary directory');
mkdirSync(output, { recursive: true, mode: 0o700 });
const fixtures = { users: [], documents: [] };
const report = { startedAt: new Date().toISOString(), checks: [], timings: {} };
const save = () => writeFileSync(resolve(output, 'fixtures.json'), JSON.stringify(fixtures), { mode: 0o600 });
// eslint-disable-next-line max-params -- Small HTTP helper preserves the method/body call shape.
async function request(path, token, method, data) {
  const response = await fetch(`${api}/${path}`, {
    method: method || 'GET',
    signal: AbortSignal.timeout(30000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: data ? JSON.stringify(data) : undefined
  });
  let body;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  return { status: response.status, body };
}
async function account() {
  const user = { username: `e2e${randomBytes(5).toString('hex')}`, password: randomBytes(8).toString('hex') };
  assert.equal((await request('users/register', null, 'POST', user)).status, 200, 'register fixture');
  fixtures.users.push(user);
  save();
  const login = await request('users/login', null, 'POST', user);
  assert.equal(login.status, 200, 'login fixture');
  const token = login.body.data.token;
  user.id = (await request('users/me', token)).body.data.id;
  save();
  return { ...user, token };
}
async function waitFor(check, label, timeout = 90000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await check()) return;
    await delay(500);
  }
  throw new Error(`Timed out: ${label}`);
}
async function clickText(page, text, root = 'body') {
  await page.evaluate(
    ({ value, selector }) => {
      const button = [...document.querySelector(selector).querySelectorAll('button')].find(
        element => element.textContent.trim() === value
      );
      if (!button) throw new Error(`Button unavailable: ${value}`);
      button.click();
    },
    { value: text, selector: root }
  );
}
let browser;
let owner;
let outsider;
try {
  owner = await account();
  outsider = await account();
  const marker = `YERAG-${randomBytes(4).toString('hex')}`;
  const fileName = `e2e-${marker}.txt`;
  const contents = `联调验收文档 ${marker}\n本项目的验收口令为 ${marker}。\n费用上限为37元。交付日期是2027年1月15日。\n这是自动化联调的私有资料，只允许上传者访问。\n`;
  const md5 = createHash('md5').update(contents).digest('hex');
  const file = resolve(output, fileName);
  writeFileSync(file, contents);
  fixtures.documents.push({ fileMd5: md5, owner: owner.username });
  save();
  browser = await puppeteer.launch({
    executablePath: process.env.BROWSER_EXECUTABLE_PATH,
    headless: true,
    args: ['--no-sandbox']
  });
  const page = await browser.newPage();
  page.setDefaultTimeout(20000);
  await page.setRequestInterception(true);
  page.on('request', req =>
    ['localhost', '127.0.0.1'].includes(new URL(req.url()).hostname) ? req.continue() : req.abort()
  );
  await page.goto(`${origin}/login`, { waitUntil: 'networkidle0' });
  await page.evaluate(
    user => {
      for (const [input, value] of [
        [document.querySelector('input[type="text"]'), user.username],
        [document.querySelector('input[type="password"]'), user.password]
      ]) {
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
    },
    { username: owner.username, password: owner.password }
  );
  await page.click('button.n-button--primary-type.n-button--large-type');
  await page.waitForFunction(() => location.pathname === '/chat');
  await page.goto(`${origin}/knowledge-base`, { waitUntil: 'networkidle0' });
  await clickText(page, '新增');
  await page.waitForSelector('.n-dialog input[type="file"]');
  await page.click('.n-dialog .n-base-selection');
  await page.waitForSelector('.n-base-select-option');
  await page.click('.n-base-select-option');
  await (await page.$('.n-dialog input[type="file"]')).uploadFile(file);
  const uploadStart = Date.now();
  await clickText(page, '保存', '.n-dialog');
  await waitFor(
    async () => {
      const r = await request(`search/hybrid?query=${encodeURIComponent(marker)}&topK=5`, owner.token);
      return r.body?.data?.some(hit => hit.fileMd5 === md5 && hit.textContent.includes(marker));
    },
    'uploaded document parsed and indexed',
    120000
  );
  report.timings.uploadToSearchMs = Date.now() - uploadStart;
  report.checks.push('browser upload → MinIO → Kafka → parsing → embedding/index → authorized retrieval');
  const denied = await request(`search/hybrid?query=${encodeURIComponent(marker)}&topK=5`, outsider.token);
  assert.equal(denied.body.code, 200);
  assert.ok(!denied.body.data.some(hit => hit.fileMd5 === md5), 'private upload must not be visible to another user');
  report.checks.push('cross-user private-document retrieval isolation');
  const preview = await request(
    `documents/preview?fileName=${encodeURIComponent(fileName)}&fileMd5=${md5}`,
    owner.token
  );
  assert.equal(preview.status, 200);
  assert.ok(preview.body.data.content.includes(marker));
  const download = await request(`documents/download-by-md5?fileMd5=${md5}`, owner.token);
  assert.equal(download.status, 200);
  const downloaded = await fetch(download.body.data.downloadUrl);
  assert.equal(downloaded.status, 200);
  assert.equal(await downloaded.text(), contents);
  report.checks.push('authenticated preview and real MinIO download');
  await page.goto(`${origin}/chat`, { waitUntil: 'networkidle0' });
  await page.waitForFunction(() =>
    [...document.querySelectorAll('button')].some(button => button.textContent.includes('新建对话') && !button.disabled)
  );
  await page.click('[aria-label="信息来源"] .n-base-selection');
  await page.waitForSelector('.n-base-select-option');
  await page.evaluate(() =>
    [...document.querySelectorAll('.n-base-select-option')]
      .find(option => option.textContent.includes('仅知识库'))
      .click()
  );
  const question = `根据我的私有文件 ${fileName}，项目验收口令是什么？请给出来源。`;
  await page.type('textarea', question);
  const chatStart = Date.now();
  await page.keyboard.press('Enter');
  const current = await request('memory/sessions/current', owner.token);
  const sessionId = current.body.data.conversationId;
  await waitFor(
    async () => {
      const r = await request(`memory/sessions/${sessionId}/messages`, owner.token);
      return r.body?.data?.some(message => message.role === 'assistant' && message.content.includes(marker));
    },
    'stream completion persisted with retrieved fact',
    120000
  );
  const history = await request(`memory/sessions/${sessionId}/messages`, owner.token);
  assert.ok(
    history.body.data.some(message => message.role === 'assistant' && message.content.includes(marker)),
    'completed answer must contain retrieved private fact'
  );
  report.timings.chatMs = Date.now() - chatStart;
  report.checks.push('real Agent tool retrieval, streaming answer and durable complete turn');
  assert.equal((await request(`memory/sessions/${sessionId}/messages`, outsider.token)).status, 404);
  report.checks.push('cross-user durable-session isolation');
  await page.reload({ waitUntil: 'networkidle0' });
  await page.waitForFunction(value => document.body.textContent.includes(value), {}, marker);
  report.checks.push('reconnect restores persisted answer');
  // A follow-up exercises recent-turn context on the same production Agent path.
  await page.type('textarea', '请只重复刚才的验收口令，不用重新检索。');
  await page.keyboard.press('Enter');
  await waitFor(
    async () => {
      const r = await request(`memory/sessions/${sessionId}/messages`, owner.token);
      return r.body.data.length >= 4 && r.body.data.at(-1).content.includes(marker);
    },
    'multi-turn recent memory',
    120000
  );
  report.checks.push('real multi-turn recent memory preserves the preceding fact');
  await clickText(page, '新建对话');
  await page.waitForFunction(value => !document.body.textContent.includes(value), {}, marker);
  await page.type('textarea', '请写一篇详细介绍知识管理的长文，分十个部分，每部分至少三百字。');
  await page.keyboard.press('Enter');
  await page.waitForSelector('button[aria-label="停止生成"]');
  await page.focus('button[aria-label="停止生成"]');
  await page.keyboard.press('Enter');
  await page.waitForSelector('button[aria-label="发送消息"]');
  await delay(1500);
  const stoppedSession = (await request('memory/sessions/current', owner.token)).body.data.conversationId;
  assert.equal(
    (await request(`memory/sessions/${stoppedSession}/messages`, owner.token)).body.data.length,
    0,
    'cancelled answer must not be saved as complete'
  );
  report.checks.push('new conversation isolation and cancellation never persists a partial answer');
  // The same physical bytes may have multiple upload owners. One owner must not delete another copy.
  const form = new FormData();
  for (const [key, value] of Object.entries({
    fileMd5: md5,
    chunkIndex: '0',
    totalSize: String(Buffer.byteLength(contents)),
    fileName,
    totalChunks: '1',
    isPublic: 'false',
    orgTag: `PRIVATE_${outsider.username}`
  }))
    form.append(key, value);
  form.append('file', new Blob([contents], { type: 'text/plain' }), fileName);
  const secondUpload = await fetch(`${api}/upload/chunk`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${outsider.token}` },
    body: form,
    signal: AbortSignal.timeout(30000)
  });
  assert.equal((await secondUpload.json()).code, 200);
  assert.equal((await request('upload/merge', outsider.token, 'POST', { fileMd5: md5, fileName })).body.code, 200);
  await waitFor(
    async () => {
      const result = await request(`search/hybrid?query=${encodeURIComponent(marker)}&topK=5`, outsider.token);
      return result.body?.data?.some(hit => hit.fileMd5 === md5 && hit.userId === String(outsider.id));
    },
    'second owner copy indexed',
    120000
  );
  assert.equal((await request(`documents/${md5}`, owner.token, 'DELETE')).status, 200);
  const surviving = await request(`documents/download-by-md5?fileMd5=${md5}`, outsider.token);
  assert.equal(surviving.status, 200);
  assert.equal(
    await (await fetch(surviving.body.data.downloadUrl)).text(),
    contents,
    'shared MinIO object survives another owner deletion'
  );
  const survivingHits = await request(`search/hybrid?query=${encodeURIComponent(marker)}&topK=5`, outsider.token);
  assert.ok(
    survivingHits.body.data.some(hit => hit.fileMd5 === md5),
    'other owner ES copy survives'
  );
  const deletedHits = await request(`search/hybrid?query=${encodeURIComponent(marker)}&topK=5`, owner.token);
  assert.ok(!deletedHits.body.data.some(hit => hit.fileMd5 === md5), 'deleted private copy is no longer visible');
  report.checks.push('same-MD5 owner deletion preserves another user metadata, index and physical download');
  assert.equal((await request(`documents/${md5}`, outsider.token, 'DELETE')).status, 200);
  assert.equal((await fetch(surviving.body.data.downloadUrl)).status, 404, 'last owner removal deletes MinIO object');
  report.checks.push('last owner removal deletes shared object and obsolete upload metadata');
  report.status = 'PASS';
} catch (error) {
  report.status = 'FAIL';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  await browser?.close();
  for (const user of [owner, outsider].filter(Boolean)) {
    for (const document of fixtures.documents) await request(`documents/${document.fileMd5}`, user.token, 'DELETE');
  }
  report.finishedAt = new Date().toISOString();
  writeFileSync(resolve(output, 'report.json'), JSON.stringify(report, null, 2));
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
}
