import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './module-loader.mjs';
const { AgentApi, readEventStream, StreamProtocolError } = await loadModule('api.js');
const encoder = new TextEncoder();
function stream(chunks) {
  return new ReadableStream({ start(controller) { chunks.forEach(chunk => controller.enqueue(chunk)); controller.close(); } });
}
const terminal = 'event: run_finished\r\ndata: {"type":"run_finished","stop_reason":"end_turn"}\r\n\r\n';

test('SSE parses every byte boundary, including CRLF and multi-byte UTF-8', async () => {
  const bytes = encoder.encode(': heartbeat\r\nevent: text_delta\r\ndata: {"text":"你好"}\r\n\r\n' + terminal);
  for (let split = 0; split <= bytes.length; split++) {
    const events = [];
    await readEventStream(stream([bytes.slice(0, split), bytes.slice(split)]), (...args) => events.push(args));
    assert.deepEqual(events.map(e => e[0]), ['text_delta', 'run_finished'], `split ${split}`);
    assert.equal(events[0][1].text, '你好');
  }
  const events = [];
  await readEventStream(stream([...bytes].map(byte => Uint8Array.of(byte))), (...args) => events.push(args));
  assert.equal(events.length, 2);
});

test('SSE supports CR-only, LF, comments and multiline JSON', async () => {
  const text = ': comment\revent: text_delta\rdata: {\rdata: "text":"ok"}\r\r' + terminal.replaceAll('\r\n', '\n');
  const events = [];
  await readEventStream(stream([encoder.encode(text)]), (...args) => events.push(args));
  assert.equal(events[0][1].text, 'ok');
});

test('SSE rejects empty, truncated, malformed and mismatched protocol frames', async () => {
  for (const text of ['', 'event: text_delta\ndata: {"text":"partial"}\n\n', terminal.trimEnd(),
    'event: text_delta\ndata: invalid\n\n', 'event: run_finished\ndata: []\n\n',
    'event: run_finished\ndata: {"type":"text_delta"}\n\n']) {
    await assert.rejects(readEventStream(stream([encoder.encode(text)]), () => {}), StreamProtocolError);
  }
  await assert.rejects(readEventStream(null, () => {}), StreamProtocolError);
});

test('run_finished does not cancel or stop reading before EOF and later errors are delivered', async () => {
  let reads = 0;
  let cancels = 0;
  let releases = 0;
  const events = [];
  const frames = [terminal, 'event: error\ndata: {"error":"save failed"}\n\n'];
  const body = { getReader: () => ({
    read: async () => reads < frames.length ? { value: encoder.encode(frames[reads++]), done: false } : { done: true },
    cancel: async () => { cancels++; }, releaseLock: () => { releases++; },
  }) };
  await readEventStream(body, (...args) => events.push(args));
  assert.deepEqual(events.map(e => e[0]), ['run_finished', 'error']);
  assert.equal(cancels, 0);
  assert.equal(releases, 1);
});

test('protocol or callback failures cancel the reader and release its lock', async () => {
  let cancelled = false;
  let released = false;
  const body = { getReader: () => ({ read: async () => ({ value: encoder.encode(terminal), done: false }),
    cancel: async () => { cancelled = true; }, releaseLock: () => { released = true; } }) };
  await assert.rejects(readEventStream(body, () => { throw new Error('render failed'); }), /render failed/);
  assert.ok(cancelled && released);
});

test('model endpoints use registered opaque IDs and optional send model_id; steering never changes model', async t => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, method: options.method, body: options.body && JSON.parse(options.body) });
    if (url.endsWith('/messages')) return { ok: true, body: stream([encoder.encode(terminal)]) };
    if (options.method === 'DELETE') return { ok: true, status: 204 };
    return { ok: true, json: async () => ({ accepted: true }) };
  });
  const api = new AgentApi('/a');
  await api.models();
  await api.modelSettings();
  await api.saveModel('custom/id', { label: 'Custom', api_key: 'only-in-put' });
  await api.deleteModel('custom/id');
  await api.setDefaultModel('opaque/model id');
  await api.sendMessage('conversation', { text: 'hello', images: [], model_id: 'opaque/model id' }, () => {});
  await api.sendMessage('conversation', { text: 'legacy', images: [] }, () => {});
  await api.steer('conversation', 'interrupt');
  assert.deepEqual(calls.map(call => [call.method, call.url]), [
    ['GET', '/a/models'], ['GET', '/a/model-settings'], ['PUT', '/a/model-settings/custom%2Fid'],
    ['DELETE', '/a/model-settings/custom%2Fid'], ['PUT', '/a/model-settings/default'],
    ['POST', '/a/conversations/conversation/messages'], ['POST', '/a/conversations/conversation/messages'],
    ['POST', '/a/conversations/conversation/steer'],
  ]);
  assert.deepEqual(calls[4].body, { model_id: 'opaque/model id' });
  assert.equal(calls[5].body.model_id, 'opaque/model id');
  assert.equal(Object.hasOwn(calls[6].body, 'model_id'), false);
  assert.deepEqual(calls[7].body, { text: 'interrupt' });
  assert.equal(JSON.stringify(calls.slice(3)).includes('only-in-put'), false);
});

test('tokens are session-scoped and isolated by API base; approvals endpoint returns array', async t => {
  const values = new Map();
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/a/approvals?conversationId=id%2Fone');
    assert.equal(options.headers.Authorization, 'Bearer secret');
    return { ok: true, json: async () => ({ approvals: [{ request_id: 'pending' }] }) };
  });
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key),
  } });
  t.after(() => previous ? Object.defineProperty(globalThis, 'sessionStorage', previous) : delete globalThis.sessionStorage);
  const api = new AgentApi('/a/');
  api.token = 'secret';
  assert.equal(new AgentApi('/a').token, 'secret');
  assert.equal(new AgentApi('/b').token, null);
  assert.deepEqual(await api.pendingApprovals('id/one'), [{ request_id: 'pending' }]);
  api.token = '';
  assert.equal(new AgentApi('/a').token, null);
});
