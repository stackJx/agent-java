import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { installDom } from './fake-dom.mjs';
import { loadModule } from './module-loader.mjs';

// 只测试应用状态接线；原生布局、焦点圈与 select/dialog 行为仍需真实浏览器验证。
test('app preserves model drafts/history, freezes live DOM/model, steers without model and resets identity', async t => {
  const dom = installDom();
  const proto = Object.getPrototypeOf(dom.create('div'));
  Object.defineProperties(proto, {
    childNodes: { get() { return this.children; } },
    lastElementChild: { get() { return this.children.at(-1); } },
    isConnected: { get() { return this === document.body || Boolean(this.parentNode?.isConnected); } },
  });
  proto.querySelector = function (selector) { return this.querySelectorAll(selector)[0] || null; };
  proto.focus = () => {};
  const create = document.createElement;
  document.createElement = tag => Object.assign(create(tag), { dataset: {}, style: {}, scrollHeight: 100, clientHeight: 100 });
  const html = await readFile(new URL('../../main/resources/agentcore-ui/index.html', import.meta.url), 'utf8');
  const nodes = new Map([...html.matchAll(/<([a-z]+)\b[^>]*\bid="([^"]+)"/g)]
    .map(([, tag, id]) => [id, document.createElement(tag)]));
  const node = id => nodes.get(id);
  document.body.append(...nodes.values());
  document.getElementById = node;
  document.querySelector = () => ({ content: '/api/agent' });
  document.querySelectorAll = () => [];
  document.addEventListener = () => {};
  document.documentElement = { dataset: {} };
  const fields = new Map(['id', 'label', 'type', 'model', 'base_url', 'api_key', 'clear_api_key', 'max_tokens', 'context_window']
    .map(name => [name, document.createElement('input')]));
  node('model-form').elements = { namedItem: name => fields.get(name) };
  for (const id of ['model-dialog', 'token-dialog']) {
    node(id).showModal = () => { node(id).open = true; };
    node(id).close = () => { node(id).open = false; node(id).listeners.get('close')?.(); };
  }
  const events = new Map();
  globalThis.window = { addEventListener: (name, handler) => events.set(name, handler) };
  globalThis.matchMedia = () => ({ matches: false });
  globalThis.location = { pathname: '/', search: '', hash: '' };
  globalThis.history = { pushState: (_, __, path) => { location.hash = path.startsWith('#') ? path : ''; },
    replaceState: (_, __, path) => { location.hash = path.startsWith('#') ? path : ''; } };
  globalThis.confirm = () => true;
  const nativeTimeout = globalThis.setTimeout;
  t.mock.method(globalThis, 'setTimeout', (...args) => nativeTimeout(...args).unref());
  const encoder = new TextEncoder();
  let streamController;
  let currentModels = ['server-default', 'mock', 'work'];
  let canManage = true;
  let defaultId = 'server-default';
  const requests = [];
  const json = value => ({ ok: true, json: async () => value });
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    const body = options.body && JSON.parse(options.body);
    requests.push({ url, body, method: options.method });
    if (url.endsWith('/health')) return json({ model: 'startup' });
    if (url.endsWith('/models')) return json({ default_id: defaultId, can_manage: canManage, persisted: false,
      models: currentModels.map(id => ({ id, label: id, model: id })) });
    if (url.includes('/conversations?')) return json({ conversations: [] });
    if (url.endsWith('/messages')) return { ok: true, body: new ReadableStream({ start(controller) { streamController = controller; } }) };
    if (url.endsWith('/steer')) return json({ accepted: true });
    if (url.endsWith('/conversations/a')) return json({ title: 'A', messages: [], metadata: { model_id: 'mock' }, running: false });
    if (url.endsWith('/conversations/b')) return json({ title: 'B', messages: [], metadata: { model_id: 'server-default' }, running: false });
    throw new Error(`Unexpected test request: ${url}`);
  });
  const tick = () => new Promise(resolve => setImmediate(resolve));
  const until = async predicate => {
    for (let i = 0; i < 100; i++) { if (predicate()) return; await tick(); }
    assert.fail('app did not reach expected state');
  };
  const navigate = async id => { location.hash = `#/c/${id}`; await events.get('hashchange')(); };
  const choose = id => { node('model-select').value = id; node('model-select').listeners.get('change')(); };
  const send = text => { node('composer-input').value = text; node('composer').listeners.get('submit')({ preventDefault() {} }); };
  const event = (name, data) => streamController.enqueue(encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`));
  const sent = () => requests.filter(item => item.url.endsWith('/messages'));
  await loadModule('app.js');
  await until(() => !node('send-button').disabled);
  await navigate('a');
  assert.equal(node('model-select').value, 'mock', 'history metadata must restore model');
  choose('work');
  node('composer-input').value = 'draft A';
  await navigate('b');
  choose('mock');
  node('composer-input').value = 'draft B';
  await navigate('a');
  assert.equal(node('composer-input').value, 'draft A');
  assert.equal(node('model-select').value, 'work');

  send('start A');
  await until(() => sent().length === 1);
  assert.equal(sent()[0].body.model_id, 'work');
  event('run_started', { model: 'actual-model-from-server' });
  await until(() => !node('send-button').disabled);
  assert.equal(node('model-select').disabled, true);
  assert.match(node('model-badge').textContent, /actual-model-from-server/);
  const liveView = node('messages-inner').firstElementChild;
  await navigate('b');
  assert.equal(node('composer-input').value, 'draft B');
  assert.equal(node('model-select').disabled, true);
  assert.match(node('model-select').children[0].textContent, /actual-model-from-server/);
  event('notice', { level: 'info', message: 'only-in-live-A' });
  await tick();
  assert.equal(node('messages-inner').textContent.includes('only-in-live-A'), false);
  await navigate('a');
  assert.equal(node('messages-inner').firstElementChild, liveView);
  assert.match(liveView.textContent, /only-in-live-A/);
  send('steer A');
  await until(() => requests.some(item => item.url.endsWith('/steer')) && !node('send-button').disabled);
  assert.deepEqual(requests.find(item => item.url.endsWith('/steer')).body, { text: 'steer A' });
  event('run_finished', { stop_reason: 'end_turn' });
  streamController.close();
  await until(() => !node('model-select').disabled);
  assert.equal(node('model-select').value, 'work');
  await navigate('b');
  assert.equal(node('model-select').value, 'mock', 'background run must not replace another draft model');
  await navigate('a');

  currentModels = ['server-default', 'mock'];
  send('must retain after removal');
  await until(() => !node('send-button').disabled);
  assert.equal(sent().length, 1);
  assert.equal(node('composer-input').value, 'must retain after removal');
  assert.equal(node('model-select').value, 'server-default');
  assert.match(node('model-status').textContent, /已不可用/);

  fields.get('api_key').value = 'must-clear';
  const switching = node('token-button').click();
  assert.equal(node('token-dialog').open, true);
  defaultId = 'mock';
  canManage = false;
  node('token-input').value = 'new-identity';
  node('token-dialog').returnValue = 'save';
  node('token-dialog').close();
  await switching;
  assert.equal(fields.get('api_key').value, '');
  assert.equal(node('composer-input').value, '');
  assert.equal(node('model-select').value, 'mock');
  assert.equal(node('model-settings-button').hidden, true);
  await navigate('a');
  assert.equal(node('composer-input').value, '', 'previous identity drafts must not survive');
});
