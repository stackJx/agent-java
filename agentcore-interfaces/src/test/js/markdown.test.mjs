import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './module-loader.mjs';
const { renderMarkdown, safeUrl } = await loadModule('markdown.js');

test('generated href is never rewritten by emphasis or code formatting', () => {
  const url = 'https://example.test/**x**/__y__/~~z~~/`code`/"onmouseover="alert';
  const html = renderMarkdown(`[**safe**](${url})`);
  assert.equal(html, '<p><a href="https://example.test/**x**/__y__/~~z~~/`code`/&quot;onmouseover=&quot;alert" target="_blank" rel="noopener noreferrer"><strong>safe</strong></a></p>');
  assert.doesNotMatch(html, /href="[^"]*<strong>/);
});

test('untrusted raw HTML, entities, pseudo placeholders and unsafe protocols stay text', () => {
  const html = renderMarkdown('<img src=x onerror=alert(1)> [x](javascript:alert) [x](data:text/html,evil) [x](jav&#97;script:evil)\u00000\u0000');
  assert.doesNotMatch(html, /<img|<a|undefined/);
  assert.match(html, /&lt;img/);
  assert.equal(safeUrl('java\nscript:alert'), null);
  assert.equal(safeUrl('//evil.test'), null);
  assert.equal(safeUrl('mailto:user@example.test'), 'mailto:user@example.test');
  assert.equal(safeUrl('mailto:user@example.test', false), null);
});

test('common blocks and links on every paragraph line remain available', () => {
  const html = renderMarkdown('# Title\n\n[first](https://example.test)\n[second](https://example.test)\n\n- **bold**\n\n```js\n<script>\n```');
  assert.match(html, /<h1>Title<\/h1>/);
  assert.equal((html.match(/<a /g) ?? []).length, 2);
  assert.match(html, /<li><strong>bold<\/strong>/);
  assert.match(html, /<code>&lt;script&gt;<\/code>/);
});
