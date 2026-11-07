import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { loadModule } from './module-loader.mjs';
import { installDom } from './fake-dom.mjs';
const dom = installDom();
const { ApprovalCard, AssistantSegment, userTurn, toolSummaryText, describeApprovalReason } = await loadModule('components.js');
const { LiveRun } = await loadModule('conversation.js');

test('approval submit failure restores visible enabled actions and permits retry', async () => {
  let attempts = 0;
  const card = new ApprovalCard({ tool: 'shell' }, async () => {
    if (++attempts === 1) throw new Error('offline');
    return true;
  });
  const button = card.actions.querySelectorAll('button')[0];
  await button.click();
  assert.equal(card.actions.hidden, false);
  assert.equal(button.disabled, false);
  assert.match(card.result.textContent, /offline/);
  await button.click();
  assert.equal(attempts, 2);
  assert.equal(card.actions.hidden, true);
  assert.equal(card.result.textContent, '已允许');
});

test('closing approval wins over a late failed in-flight submit', async () => {
  let reject;
  const card = new ApprovalCard({ tool: 'shell' }, () => new Promise((resolve, fail) => { reject = fail; }));
  const pending = card.actions.querySelectorAll('button')[0].click();
  card.close('connection lost');
  reject(new Error('late error'));
  await pending;
  assert.equal(card.actions.hidden, true);
  assert.equal(card.result.textContent, 'connection lost');
  assert.ok(card.actions.querySelectorAll('button').every(button => button.disabled));
});

test('thinking completion replaces deltas and supports non-streamed thinking', () => {
  const segment = new AssistantSegment();
  segment.appendThinking('partial');
  segment.setThinking('complete');
  assert.equal(segment.thinkingText, 'complete');
  segment.setThinking('');
  assert.equal(segment.thinking.hidden, true);
  const container = dom.create('div');
  const run = new LiveRun(container, async () => true);
  run.handle('thinking_delta', { text: 'partial' });
  run.handle('assistant_message', { message: { content: [{ type: 'thinking', text: 'final thought' }] } });
  dom.flush();
  assert.equal(container.querySelectorAll('.thinking-body')[0].textContent, 'final thought');
  run.handle('turn_started', { turn: 2 });
  run.handle('assistant_message', { message: { content: [{ type: 'thinking', text: 'no deltas' }] } });
  assert.equal(container.querySelectorAll('.thinking-body')[1].textContent, 'no deltas');
});

test('retry drops only provisional text and tool cards, retaining previous completed turns', () => {
  const container = dom.create('div');
  const run = new LiveRun(container, async () => true);
  run.handle('assistant_message', { message: { content: [
    { type: 'text', text: 'retained' }, { type: 'tool_call', id: 'old', name: 'shell', arguments: {} },
  ] } });
  run.handle('tool_finished', { call_id: 'old', tool: 'shell', output: 'done', is_error: false });
  run.handle('turn_started', { turn: 2 });
  run.handle('text_delta', { text: 'discard me' });
  run.handle('thinking_delta', { text: 'discard thought' });
  const partial = run.segment;
  run.handle('tool_call_streaming', { id: 'temporary', name: 'shell' });
  const temporary = run.toolCards.get('temporary');
  run.handle('retrying', { attempt: 1, error: 'upstream' });
  dom.flush();
  assert.equal(partial.element.parentNode, null);
  assert.equal(temporary.element.parentNode, null);
  assert.equal(run.toolCards.has('temporary'), false);
  assert.equal(run.toolCards.get('old').finished, true);
  assert.equal(container.querySelectorAll('.turn-assistant').length, 1);
});

test('close is idempotent and removes every pending spinner, caret and approval action', () => {
  const container = dom.create('div');
  const run = new LiveRun(container, async () => true);
  run.handle('text_delta', { text: 'partial' });
  run.handle('tool_call_streaming', { id: 'pending', name: 'shell' });
  run.handle('approval_required', { request_id: 'p', tool: 'shell' });
  run.close('network lost');
  run.abort('duplicate');
  dom.flush();
  assert.equal(container.querySelectorAll('.spinner').length, 0);
  assert.equal(container.querySelectorAll('.streaming-caret').length, 0);
  assert.equal(run.approvals[0].actions.hidden, true);
  assert.equal(container.querySelectorAll('.notice').length, 1);
  run.handle('text_delta', { text: 'late' });
  assert.equal(run.segment, null);
});

test('actual AgentEvent completion name is handled, with persistence errors after run_finished', async () => {
  const java = await readFile(new URL('../../../../agentcore-domain/src/main/java/com/agentcore/domain/agent/event/AgentEvent.java', import.meta.url), 'utf8');
  const completion = java.match(/record AssistantMessageCompleted[\s\S]*?return "([^"]+)"/)[1];
  assert.equal(completion, 'assistant_message');
  const container = dom.create('div');
  const run = new LiveRun(container, async () => true);
  run.handle(completion, { message: { content: [{ type: 'text', text: 'authoritative' }] } });
  run.handle('run_finished', { stop_reason: 'end_turn' });
  run.close('should not duplicate');
  run.handle('error', { error: 'save failed' });
  dom.flush();
  assert.equal(container.querySelectorAll('.run-summary').length, 1);
  assert.equal(container.querySelectorAll('.notice').length, 1);
  assert.match(container.textContent, /save failed/);
});

test('image URLs never auto-load remote resources or unsafe schemes; raster data only', () => {
  const turn = userTurn('', [
    { url: 'https://remote.test/tracker' }, { url: 'javascript:alert(1)' },
    { url: 'data:image/svg+xml,evil' }, { data: 'PHN2Zz4=', media_type: 'image/svg+xml' },
    { data: 'YWJj', media_type: 'image/png' },
  ]);
  assert.equal(turn.querySelectorAll('img').length, 1);
  assert.equal(turn.querySelectorAll('img')[0].getAttribute('src'), 'data:image/png;base64,YWJj');
  assert.equal(turn.querySelectorAll('a').length, 1);
  assert.equal(turn.querySelectorAll('a')[0].getAttribute('rel'), 'noopener noreferrer');
  assert.equal(turn.querySelectorAll('.image-placeholder').length, 3);
});

test('tool summaries drop the repeated tool name and approval reasons read like sentences', () => {
  assert.equal(toolSummaryText('bash', 'bash(ls -la)'), 'ls -la');
  assert.equal(toolSummaryText('bash', 'printf x'), 'printf x');
  assert.equal(describeApprovalReason('tool default'), '');
  assert.equal(describeApprovalReason('the path is outside the working directory'), '要访问工作目录之外的路径');
  assert.equal(describeApprovalReason('rule bash(rm *) requires approval'), '配置的规则 bash(rm *) 要求确认');
});

test('approval with a call id sits inside its tool card and is removed once answered', async () => {
  const container = dom.create('div');
  const run = new LiveRun(container, async () => true);
  run.handle('assistant_message', { message: { content: [{ type: 'tool_call', id: 'call-1', name: 'bash', arguments: { command: 'ls' } }] } });
  run.handle('approval_required', { request_id: 'r1', call_id: 'call-1', tool: 'bash', summary: 'bash(ls)' });
  const card = run.toolCards.get('call-1');
  assert.equal(card.approvalSlot.hidden, false);
  assert.equal(container.querySelectorAll('.approval').length, 1, 'shown once, inside the tool card');
  await run.approvals[0].actions.querySelectorAll('button')[0].click();
  assert.equal(card.approvalSlot.hidden, true);
  assert.equal(card.approvalSlot.children.length, 0);
});
