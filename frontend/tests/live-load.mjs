/* eslint-disable no-await-in-loop -- Each worker intentionally issues requests in order. */
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';
import { performance } from 'node:perf_hooks';
import WebSocket from 'ws';

const api = process.env.YERAG_E2E_BACKEND || 'http://localhost:8081/api/v1';
const output = process.env.YERAG_E2E_OUTPUT;
assert.ok(output, 'YERAG_E2E_OUTPUT is required');
mkdirSync(output, { recursive: true, mode: 0o700 });
const protocol = JSON.parse(
  readFileSync(new URL('../../evaluation/protocols/local-performance-v1.json', import.meta.url))
);
const fixtures = { users: [], documents: [] };
const sockets = new Set();
const report = {
  startedAt: new Date().toISOString(),
  protocol,
  scope: 'Bounded local smoke; not production capacity or sustained-load SLO',
  retrieval: [],
  chat: []
};
function save() {
  writeFileSync(resolve(output, 'fixtures.json'), JSON.stringify(fixtures), { mode: 0o600 });
}
async function request(path, token, body) {
  const response = await fetch(`${api}/${path}`, {
    method: body ? 'POST' : 'GET',
    signal: AbortSignal.timeout(30000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  assert.equal(response.status, 200, 'HTTP request');
  const data = await response.json();
  assert.equal(data.code, 200, 'business response');
  return data.data;
}
async function account() {
  const user = { username: `e2e${randomBytes(5).toString('hex')}`, password: randomBytes(8).toString('hex') };
  await request('users/register', null, user);
  fixtures.users.push(user);
  save();
  const { token } = await request('users/login', null, user);
  user.id = (await request('users/me', token)).id;
  save();
  return { ...user, token };
}
function chat(user, session) {
  return new Promise((resolveChat, rejectChat) => {
    const socket = new WebSocket(`${api.replace('http', 'ws').replace(/\/api\/v1\/?$/, '')}/chat/${user.token}`);
    sockets.add(socket);
    const requestId = randomUUID();
    let started;
    let first;
    let chunks = 0;
    let answer = '';
    const timer = setTimeout(() => {
      rejectChat(new Error('Chat timeout'));
      socket.terminate();
    }, protocol.chatTimeoutMs);
    socket.on('error', () => rejectChat(new Error('WebSocket connection failed')));
    socket.on('message', raw => {
      const message = JSON.parse(raw);
      if (message.type === 'connection') {
        started = performance.now();
        socket.send(
          JSON.stringify({
            type: 'chat',
            message: protocol.chatQuestion,
            requestId,
            conversationId: session,
            sourceMode: 'auto'
          })
        );
      }
      if (message.requestId !== requestId) return;
      if (message.error || message.type === 'request_rejected') rejectChat(new Error('Chat rejected or failed'));
      if (message.chunk) {
        first ??= performance.now();
        chunks += 1;
        answer += message.chunk;
      }
      if (message.type === 'completion') {
        if (message.status !== 'finished' || !answer.trim()) rejectChat(new Error('Incomplete answer'));
        else
          resolveChat({
            firstChunkMs: first - started,
            completeMs: performance.now() - started,
            chunks,
            answerCharacters: answer.length
          });
        socket.close();
      }
    });
    socket.on('close', () => {
      clearTimeout(timer);
      sockets.delete(socket);
    });
  });
}
function stats(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = q => sorted[Math.ceil(q * sorted.length) - 1];
  return {
    count: sorted.length,
    p50: percentile(0.5),
    p95: percentile(0.95),
    max: sorted.at(-1),
    mean: sorted.reduce((a, b) => a + b, 0) / sorted.length,
    percentileMethod: 'NEAREST_RANK'
  };
}
try {
  const users = [];
  for (let i = 0; i < protocol.workers; i += 1) users.push(await account());
  await Promise.all(
    users.map(async (user, worker) => {
      for (let i = 0; i < protocol.retrievalRequestsPerWorker; i += 1) {
        const started = performance.now();
        const hits = await request(
          `search/hybrid?query=${encodeURIComponent(protocol.retrievalQuestion)}&topK=5`,
          user.token
        );
        report.retrieval.push({ worker, request: i, ms: performance.now() - started, hits: hits.length });
      }
    })
  );
  await Promise.all(
    users.map(async (user, worker) => {
      const session = (await request('memory/sessions/current', user.token)).conversationId;
      report.chat.push({ worker, ...(await chat(user, session)) });
      const messages = await request(`memory/sessions/${session}/messages`, user.token);
      assert.equal(messages.length, 2, 'each user receives only its completed turn');
    })
  );
  report.metrics = {
    retrievalMs: stats(report.retrieval.map(row => row.ms)),
    firstChunkMs: stats(report.chat.map(row => row.firstChunkMs)),
    completeMs: stats(report.chat.map(row => row.completeMs))
  };
  report.developmentGate = {
    zeroErrors: true,
    retrievalP95: report.metrics.retrievalMs.p95 <= protocol.developmentGates.retrievalP95Ms,
    chatP95: report.metrics.completeMs.p95 <= protocol.developmentGates.chatP95Ms
  };
  report.status = Object.values(report.developmentGate).every(Boolean) ? 'PASS' : 'FAIL';
  report.billing = {
    status: 'UNAVAILABLE',
    reason: 'WebSocket protocol does not expose complete Agent tool/decision/answer usage; no price or money inferred'
  };
  if (report.status !== 'PASS') process.exitCode = 1;
} catch (error) {
  report.status = 'FAIL';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  for (const socket of sockets) socket.terminate();
  report.finishedAt = new Date().toISOString();
  writeFileSync(resolve(output, 'report.json'), JSON.stringify(report, null, 2));
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
}
