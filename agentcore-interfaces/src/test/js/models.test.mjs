import test from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './fake-dom.mjs';
import { loadModule } from './module-loader.mjs';
const { ModelControls, ModelSelection, normalizeCatalog, profileValues, profileRequest } = await loadModule('models.js');

const catalog = (overrides = {}) => ({ default_id: 'server-default', can_manage: true, persisted: true,
  models: [{ id: 'server-default', label: '启动模型', model: 'startup', read_only: true },
    { id: 'mock', label: '离线模型', model: 'mock', read_only: true },
    { id: 'opaque/id:% model', label: '工作模型', model: 'real-model', read_only: false }], ...overrides });
const profile = (overrides = {}) => ({ id: 'work_model-1', label: '工作模型', type: 'openai-responses',
  model: 'example', base_url: 'https://example.test/v1', ...overrides });

function fixture(api = {}) {
  const dom = installDom();
  const nodes = Object.fromEntries(['select', 'manage', 'badge', 'status', 'retry', 'dialog', 'close', 'create', 'form',
    'fields', 'remove', 'keyState', 'defaultSelect', 'defaultFields', 'setDefault', 'list', 'storage', 'settingsStatus']
    .map(name => [name, dom.create(name)]));
  const fields = Object.fromEntries(['id', 'label', 'type', 'model', 'base_url', 'max_tokens', 'context_window', 'api_key', 'clear_api_key']
    .map(name => [name, dom.create('input')]));
  nodes.form.elements = { namedItem: name => fields[name] };
  nodes.dialog.showModal = () => { nodes.dialog.open = true; };
  nodes.dialog.close = () => { nodes.dialog.open = false; nodes.dialog.listeners.get('close')?.(); };
  const warnings = [];
  const controls = new ModelControls({ models: async () => catalog(), modelSettings: async () => ({ default_id: 'mock', persisted: true,
    profiles: [profile({ has_api_key: true, api_key: 'never-copy-this' })] }), ...api }, nodes, text => warnings.push(text));
  return { controls, nodes, fields, warnings };
}

function fill(fields, values = profile()) {
  for (const [key, value] of Object.entries(values)) if (fields[key]) fields[key].value = value;
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
}

test('model form uses safe defaults, omits blank keys and optional limits, and never hydrates old key', () => {
  const values = profileValues({ ...profile(), api_key: 'old-secret', has_api_key: true, unexpected: 'secret' });
  assert.equal(values.api_key, '');
  assert.equal(values.has_api_key, true);
  assert.equal(values.unexpected, undefined);
  assert.equal(profileValues().type, 'anthropic');
  const request = profileRequest(values);
  assert.deepEqual(request, { id: 'work_model-1', body: { label: '工作模型', type: 'openai-responses', model: 'example', base_url: 'https://example.test/v1' } });
  assert.equal(JSON.stringify(request).includes('old-secret'), false);
  assert.deepEqual(profileRequest({ ...values, api_key: 'new-secret', clear_api_key: true }).body,
    { ...request.body, clear_api_key: true });
  assert.equal(profileRequest({ ...values, api_key: 'new-secret' }).body.api_key, 'new-secret');
  assert.equal(profileRequest({ ...values, max_tokens: ' 123 ', context_window: '4096' }).body.max_tokens, 123);
});

test('form validates protocol, safe slug/reserved IDs, URL and positive safe integer limits', () => {
  for (const id of ['server-default', 'mock', 'default', '../a', 'Upper', '1abc', 'a b', 'a'.repeat(65), '']) {
    assert.throws(() => profileRequest(profile({ id })), /ID/);
  }
  assert.equal(profileRequest(profile({ id: 'a'.repeat(64) })).id.length, 64);
  for (const type of ['anthropic', 'openai', 'openai-responses', 'mock']) assert.equal(profileRequest(profile({ type })).body.type, type);
  assert.throws(() => profileRequest(profile({ type: 'unknown' })), /协议/);
  for (const base_url of ['javascript:alert(1)', 'ftp://example.test', 'not-url', 'https://user:password@example.test']) {
    assert.throws(() => profileRequest(profile({ base_url })), /URL/);
  }
  assert.equal(profileRequest(profile({ base_url: '' })).body.base_url, '');
  for (const max_tokens of ['0', '-1', '1.1', 'NaN', 'Infinity', '9007199254740992', '1e3']) {
    assert.throws(() => profileRequest(profile({ max_tokens })), /正整数/);
  }
});

test('catalog preserves opaque IDs, chooses registered default and drops unexpected secrets', () => {
  const data = normalizeCatalog(catalog({ api_key: 'secret' }));
  assert.equal(data.api_key, undefined);
  const selection = new ModelSelection();
  selection.apply(catalog({ default_id: 'mock' }));
  assert.deepEqual(selection.request(), { model_id: 'mock' });
  selection.select('opaque/id:% model');
  assert.deepEqual(selection.request(), { model_id: 'opaque/id:% model' });
  assert.throws(() => normalizeCatalog(catalog({ default_id: 'missing' })), /默认值/);
  assert.throws(() => normalizeCatalog(catalog({ models: [catalog().models[0], catalog().models[0]] })), /默认值/);
  assert.match(selection.select('removed'), /已不可用/);
  assert.equal(selection.selected, 'mock');
});

test('404 disabled mode keeps old chat working without model_id; all other errors block fresh sends', async () => {
  const { controls, nodes } = fixture({ models: async () => { throw { status: 404 }; } });
  assert.deepEqual(await controls.prepareMessage(), {});
  assert.equal(controls.selected, 'server-default');
  assert.equal(nodes.manage.hidden, true);
  for (const status of [401, 403, 500, undefined]) {
    controls.api.models = async () => { throw { status }; };
    await assert.rejects(controls.prepareMessage(), /不会自动/);
    assert.equal(nodes.select.disabled, true);
    assert.equal(nodes.retry.hidden, false);
  }
});

test('deleted selected model warns and falls back but aborts that send; explicit retry uses new ID', async () => {
  const { controls, warnings } = fixture();
  await controls.refresh();
  controls.select('opaque/id:% model');
  controls.api.models = async () => catalog({ models: catalog().models.slice(0, 2) });
  await assert.rejects(controls.prepareMessage(), /已不可用/);
  assert.equal(controls.selected, 'server-default');
  assert.match(warnings.at(-1), /确认后再发送/);
  assert.deepEqual(await controls.prepareMessage(), { model_id: 'server-default' });
});

test('run model remains frozen when catalog changes and restores per-view draft selection after run', async () => {
  const { controls, nodes } = fixture();
  await controls.refresh();
  controls.select('opaque/id:% model');
  const draft = { model_id: controls.selected };
  controls.update({ frozen: { modelId: controls.selected, model: 'actual-run-model' } });
  controls.select('mock'); // 导航另一会话只恢复草稿，显示仍是运行模型。
  assert.equal(nodes.select.disabled, true);
  assert.equal(nodes.select.children[0].textContent, '运行中 · actual-run-model');
  controls.api.models = async () => catalog({ models: catalog().models.slice(0, 2) });
  await controls.refresh();
  assert.match(nodes.badge.textContent, /actual-run-model/);
  controls.select(draft.model_id); // 删除后的草稿明确提示回退。
  controls.update({});
  assert.equal(nodes.select.value, 'server-default');
  assert.equal(nodes.select.disabled, false);
});

test('ordinary user can select registered model but never opens admin endpoint', async () => {
  let reads = 0;
  const { controls, nodes } = fixture({ models: async () => catalog({ can_manage: false }), modelSettings: async () => { reads++; } });
  await controls.refresh();
  assert.equal(nodes.manage.hidden, true);
  controls.select('mock');
  assert.deepEqual(await controls.prepareMessage(), { model_id: 'mock' });
  await controls.openSettings();
  assert.equal(reads, 0);
});

test('settings render key existence only, support defaults and refresh after every mutation without network test', async () => {
  const calls = [];
  const { controls, nodes, fields } = fixture({
    models: async () => { calls.push('models'); return catalog(); },
    modelSettings: async () => { calls.push('settings'); return { default_id: 'mock', persisted: true,
      profiles: [profile({ has_api_key: true, api_key: 'old-secret' }), { id: 'mock' }, { id: 'server-default' }] }; },
    saveModel: async (id, body) => { calls.push(['save', id, body]); return { api_key: 'bad-response-secret' }; },
    setDefaultModel: async id => { calls.push(['default', id]); },
  });
  await controls.refresh();
  await controls.openSettings();
  assert.equal(nodes.defaultSelect.value, 'mock');
  assert.equal(nodes.list.children.length, 1);
  assert.match(nodes.storage.textContent, /服务器本地/);
  await nodes.list.children[0].click();
  assert.equal(fields.api_key.value, '');
  assert.equal(fields.id.disabled, true);
  assert.match(nodes.keyState.textContent, /已设置/);
  fields.api_key.value = 'new-secret';
  await controls.save();
  assert.equal(fields.api_key.value, '');
  assert.equal(calls.find(item => Array.isArray(item) && item[0] === 'save')[2].api_key, 'new-secret');
  assert.deepEqual(calls.slice(-2), ['models', 'settings']);
  assert.equal(JSON.stringify(controls.profiles).includes('old-secret'), false);
  assert.equal(nodes.settingsStatus.textContent.includes('bad-response-secret'), false);
  await nodes.setDefault.click();
  assert.deepEqual(calls.slice(-3), [['default', 'mock'], 'models', 'settings']);
});

test('delete refreshes both endpoints, warns on selected model removal and keeps running model frozen', async t => {
  let removed = false;
  const calls = [];
  const { controls, nodes } = fixture({
    models: async () => { calls.push('models'); return catalog({ default_id: 'mock', models: [...catalog().models.slice(0, 2),
      ...(removed ? [] : [{ id: 'work_model-1', label: '工作模型', model: 'example' }])] }); },
    deleteModel: async id => { calls.push(['delete', id]); removed = true; },
    modelSettings: async () => { calls.push('settings'); return { default_id: 'mock', persisted: true, profiles: removed ? [] : [profile()] }; },
  });
  const previous = globalThis.confirm;
  globalThis.confirm = () => true;
  t.after(() => { if (previous) globalThis.confirm = previous; else delete globalThis.confirm; });
  await controls.refresh();
  controls.select('work_model-1');
  controls.update({ frozen: { modelId: 'work_model-1', model: 'running-original' } });
  await controls.openSettings();
  await nodes.list.children[0].click();
  await controls.remove();
  assert.deepEqual(calls.slice(-3), [['delete', 'work_model-1'], 'models', 'settings']);
  assert.equal(controls.selected, 'mock');
  assert.match(nodes.status.textContent, /已不可用/);
  assert.match(nodes.badge.textContent, /running-original/);
  assert.equal(nodes.remove.hidden, true);
});

test('newer catalog and identity win over delayed requests or mutation results', async () => {
  const old = deferred();
  const { controls, fields, nodes } = fixture();
  controls.api.models = () => old.promise;
  const previous = controls.refresh();
  controls.api.models = async () => catalog({ default_id: 'mock' });
  await controls.refresh();
  old.resolve(catalog());
  assert.deepEqual(await previous, { stale: true });
  assert.equal(controls.selected, 'mock');
  await controls.openSettings();
  const save = deferred();
  controls.api.saveModel = () => save.promise;
  fill(fields, profile({ api_key: 'temporary-secret' }));
  const saving = controls.save();
  controls.reset();
  save.resolve({ label: 'previous identity' });
  await saving;
  assert.equal(controls.selection.catalog, null);
  assert.equal(controls.profiles.length, 0);
  assert.equal(nodes.settingsStatus.textContent, '');
  assert.equal(fields.api_key.value, '');
});

test('key is never written to browser storage and server errors do not echo it in UI', async t => {
  const writes = [];
  for (const name of ['localStorage', 'sessionStorage']) {
    const previous = Object.getOwnPropertyDescriptor(globalThis, name);
    Object.defineProperty(globalThis, name, { configurable: true, value: { setItem: (...args) => writes.push(args) } });
    t.after(() => previous ? Object.defineProperty(globalThis, name, previous) : delete globalThis[name]);
  }
  const { controls, nodes, fields } = fixture({ saveModel: async () => { throw { status: 400, message: 'invalid key TOP-SECRET' }; } });
  await controls.refresh();
  await controls.openSettings();
  fill(fields, profile({ api_key: 'TOP-SECRET' }));
  await controls.save();
  assert.equal(fields.api_key.value, '');
  assert.match(nodes.settingsStatus.textContent, /HTTP 400/);
  assert.equal(nodes.settingsStatus.textContent.includes('TOP-SECRET'), false);
  assert.deepEqual(writes, []);
  fields.api_key.value = 'another-secret';
  nodes.dialog.close();
  assert.equal(fields.api_key.value, '');
});

test('identity reset clears preferences/settings and ignores delayed catalog or admin responses', async () => {
  const request = deferred();
  const { controls, nodes, fields } = fixture();
  await controls.refresh();
  controls.select('mock');
  controls.api.modelSettings = () => request.promise;
  const opening = controls.openSettings();
  fields.api_key.value = 'secret';
  controls.reset();
  request.resolve({ default_id: 'mock', persisted: true, profiles: [profile()] });
  await opening;
  assert.equal(controls.selected, null);
  assert.equal(fields.api_key.value, '');
  assert.equal(fields.label.value, '');
  assert.equal(nodes.list.children.length, 0);
  assert.equal(nodes.dialog.open, false);
  const pending = deferred();
  controls.api.models = () => pending.promise;
  const refreshing = controls.refresh();
  controls.reset();
  pending.resolve(catalog());
  assert.deepEqual(await refreshing, { stale: true });
  assert.equal(controls.selection.catalog, null);
});

test('memory persistence warning and clear-key option are explicit; failed settings read stays disabled', async () => {
  const { controls, nodes, fields } = fixture({ modelSettings: async () => ({ default_id: 'mock', persisted: false, profiles: [] }) });
  await controls.refresh();
  await controls.openSettings();
  assert.match(nodes.storage.textContent, /重启后丢失/);
  fill(fields, profile({ api_key: 'discard-me' }));
  fields.clear_api_key.checked = true;
  fields.clear_api_key.listeners.get('change')({ target: fields.clear_api_key });
  assert.equal(fields.api_key.value, '');
  assert.equal(fields.api_key.disabled, true);
  controls.api.modelSettings = async () => { throw { status: 403 }; };
  await controls.loadSettings();
  assert.equal(nodes.fields.disabled, true);
  assert.equal(nodes.defaultFields.disabled, true);
  assert.match(nodes.settingsStatus.textContent, /403/);
});
