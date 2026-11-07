// 把模型回复的 Markdown 转成 HTML。只支持常用的写法，并且是安全的：
// 所有文字先转义，模型输出里的 HTML 标签原样显示、不会执行；链接只允许 http / https / mailto。
//
// 支持：围栏代码块（带语言和复制按钮）、标题、段落、列表（有序 / 无序，按缩进嵌套）、引用、分隔线、表格，
//       以及行内的 `代码`、**粗体**、*斜体*、~~删除线~~、[链接](地址)。

/** Markdown 文本 → HTML 字符串。 */
export function renderMarkdown(source) {
  const lines = String(source ?? "").replace(/\r\n?/g, "\n").split("\n");
  return renderBlocks(lines);
}

// ------------------------------------------------------------------ 块级

const FENCE = /^\s*(`{3,}|~{3,})\s*([\w#+.-]*)/;
const HEADING = /^(#{1,6})\s+(.*)$/;
const RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;
const QUOTE = /^\s*>\s?/;
const LIST_ITEM = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/;
const TABLE_SEPARATOR = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

function renderBlocks(lines) {
  const html = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (line.trim() === "") {
      index++;
    } else if (FENCE.test(line)) {
      index = renderCodeBlock(lines, index, html);
    } else if (HEADING.test(line)) {
      const [, hashes, text] = line.match(HEADING);
      html.push(`<h${hashes.length}>${renderInline(text)}</h${hashes.length}>`);
      index++;
    } else if (RULE.test(line)) {
      html.push("<hr>");
      index++;
    } else if (QUOTE.test(line)) {
      index = renderQuote(lines, index, html);
    } else if (LIST_ITEM.test(line)) {
      index = renderList(lines, index, html);
    } else if (isTableStart(lines, index)) {
      index = renderTable(lines, index, html);
    } else {
      index = renderParagraph(lines, index, html);
    }
  }
  return html.join("\n");
}

function renderCodeBlock(lines, start, html) {
  const [, fence, language] = lines[start].match(FENCE);
  const body = [];
  let index = start + 1;
  while (index < lines.length && !lines[index].trim().startsWith(fence)) {
    body.push(lines[index]);
    index++;
  }
  const label = language || "text";
  html.push(`<div class="code-block"><div class="code-banner"><span>${escapeHtml(label)}</span>`
    + `<button type="button" class="copy-button" data-copy-code>复制</button></div>`
    + `<pre><code>${escapeHtml(body.join("\n"))}</code></pre></div>`);
  return index + 1; // 跳过结尾的围栏（流式输出时可能还没到，没关系）
}

function renderQuote(lines, start, html) {
  const quoted = [];
  let index = start;
  while (index < lines.length && QUOTE.test(lines[index])) {
    quoted.push(lines[index].replace(QUOTE, ""));
    index++;
  }
  html.push(`<blockquote>${renderBlocks(quoted)}</blockquote>`);
  return index;
}

/** 列表：同一缩进的连续条目是一组；缩进更深的行属于上一个条目（可以是嵌套列表或续行）。 */
function renderList(lines, start, html) {
  const [, indent, marker] = lines[start].match(LIST_ITEM);
  const ordered = /\d/.test(marker);
  const baseIndent = indent.length;
  const items = [];
  let index = start;
  while (index < lines.length) {
    const match = lines[index].match(LIST_ITEM);
    if (match && match[1].length === baseIndent && /\d/.test(match[2]) === ordered) {
      items.push([match[3]]);
      index++;
    } else if (items.length && lines[index].trim() !== "" && leadingSpaces(lines[index]) > baseIndent) {
      items[items.length - 1].push(lines[index].slice(Math.min(leadingSpaces(lines[index]), baseIndent + 2)));
      index++;
    } else {
      break;
    }
  }
  const tag = ordered ? "ol" : "ul";
  const firstNumber = ordered ? parseInt(marker, 10) : 1;
  const startAttribute = ordered && firstNumber !== 1 ? ` start="${firstNumber}"` : "";
  const renderedItems = items.map(([first, ...rest]) => {
    const nested = rest.length ? renderBlocks(rest) : "";
    return `<li>${renderInline(first)}${nested}</li>`;
  });
  html.push(`<${tag}${startAttribute}>${renderedItems.join("")}</${tag}>`);
  return index;
}

function isTableStart(lines, index) {
  return lines[index].includes("|") && index + 1 < lines.length && TABLE_SEPARATOR.test(lines[index + 1]);
}

function renderTable(lines, start, html) {
  const header = splitTableRow(lines[start]);
  let index = start + 2;
  const rows = [];
  while (index < lines.length && lines[index].includes("|") && lines[index].trim() !== "") {
    rows.push(splitTableRow(lines[index]));
    index++;
  }
  const head = header.map(cell => `<th>${renderInline(cell)}</th>`).join("");
  const body = rows.map(row => `<tr>${row.map(cell => `<td>${renderInline(cell)}</td>`).join("")}</tr>`).join("");
  html.push(`<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`);
  return index;
}

function splitTableRow(line) {
  return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map(cell => cell.trim());
}

/** 段落：一直到空行或者另一种块开始；段落里的单个换行保留成 <br>。 */
function renderParagraph(lines, start, html) {
  const text = [];
  let index = start;
  while (index < lines.length && lines[index].trim() !== "" && !startsAnotherBlock(lines, index)) {
    text.push(lines[index]);
    index++;
  }
  if (text.length === 0) { // 防御：不会走到这里，但保证一定前进
    text.push(lines[index]);
    index++;
  }
  html.push(`<p>${text.map(line => renderInline(line)).join("<br>")}</p>`);
  return index;
}

function startsAnotherBlock(lines, index) {
  const line = lines[index];
  return FENCE.test(line) || HEADING.test(line) || RULE.test(line) || QUOTE.test(line)
    || LIST_ITEM.test(line) || isTableStart(lines, index);
}

function leadingSpaces(line) {
  return line.length - line.trimStart().length;
}

// ------------------------------------------------------------------ 行内

/** 只扫描原始文本：生成的 HTML 永不再交给 Markdown 正则处理。 */
function renderInline(text, allowLinks = true, depth = 0) {
  if (depth > 32) {
    return escapeHtml(text);
  }
  const html = [];
  let index = 0;
  while (index < text.length) {
    const rest = text.slice(index);
    const code = rest.match(/^`([^`]+)`/);
    if (code) {
      html.push(`<code>${escapeHtml(code[1])}</code>`);
      index += code[0].length;
      continue;
    }
    const link = allowLinks && rest.match(/^\[([^\]]+)\]\(([^)\s]+)\)/);
    if (link) {
      const url = safeUrl(link[2]);
      html.push(url
        ? `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">${renderInline(link[1], false, depth + 1)}</a>`
        : escapeHtml(link[0]));
      index += link[0].length;
      continue;
    }
    const formats = [["**", "strong"], ["__", "strong"], ["~~", "del"], ["*", "em"]];
    let formatted = false;
    for (const [delimiter, tag] of formats) {
      if (!rest.startsWith(delimiter)) {
        continue;
      }
      const end = rest.indexOf(delimiter, delimiter.length);
      if (end <= delimiter.length) {
        continue;
      }
      html.push(`<${tag}>${renderInline(rest.slice(delimiter.length, end), allowLinks, depth + 1)}</${tag}>`);
      index += end + delimiter.length;
      formatted = true;
      break;
    }
    if (!formatted) {
      html.push(escapeHtml(text[index++]));
    }
  }
  return html.join("");
}

/** 不解码实体、不接受相对地址或控制字符；调用者仍需转义 HTML 属性。 */
export function safeUrl(value, allowMail = true) {
  if (typeof value !== "string" || /[\u0000-\u0020\u007f]/.test(value)) {
    return null;
  }
  try {
    const url = new URL(value);
    const allowed = ["https:", "http:", ...(allowMail ? ["mailto:"] : [])];
    return allowed.includes(url.protocol) ? value : null;
  } catch (invalidUrl) {
    return null;
  }
}

export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
