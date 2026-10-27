import assert from 'node:assert/strict';
import { test } from 'node:test';
import axiosModule from '../packages/axios/src';

const { REQUEST_ID_KEY, createFlatRequest } = axiosModule;

test('generated request IDs cancel only the intended in-flight request', async () => {
  const signals = new Map<string, AbortSignal>();
  let release: () => void = () => {};
  const pending = new Promise<void>(resolve => {
    release = resolve;
  });
  let started: () => void = () => {};
  const bothStarted = new Promise<void>(resolve => {
    started = resolve;
  });
  const request = createFlatRequest({
    adapter: async config => {
      signals.set(String(config.headers.get(REQUEST_ID_KEY)), config.signal as AbortSignal);
      if (signals.size === 2) started();
      await pending;
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
    }
  });
  const first = request({ url: '/first' });
  const second = request({ url: '/second' });
  await bothStarted;
  const [firstId, secondId] = [...signals.keys()];
  assert.notEqual(firstId, secondId);
  request.cancelRequest(firstId);
  assert.equal(signals.get(firstId)?.aborted, true);
  assert.equal(signals.get(secondId)?.aborted, false);
  request.cancelAllRequest();
  assert.equal(signals.get(secondId)?.aborted, true);
  release();
  assert.equal((await first).error?.code, 'ERR_CANCELED');
  assert.equal((await second).error?.code, 'ERR_CANCELED');
});
