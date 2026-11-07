// 对话里的各种块：用户消息、助手回复（思考 + 正文）、工具调用卡片、审批卡片、提示行。
// 每个组件自己持有 DOM，提供几个更新方法，由 conversation.js 根据历史或实时事件调用。

import { describeUsage, el, formatDuration, formatTokens } from "./dom.js";
import { icon } from "./icons.js";
import { renderMarkdown, safeUrl } from "./markdown.js";

/** 压缩之后，新历史的第一条用户消息以摘要开头，后面可能跟着压缩时最新的那句用户输入（见 ConversationCompactor）。 */
const SUMMARY_PREFIX = "<conversation_summary>";
const SUMMARY_SUFFIX = "</conversation_summary>";
const LATEST_USER_MESSAGE = "Latest user message:";

// ------------------------------------------------------------------ 用户消息

/**
 * 用户消息气泡（右侧）。
 * @param images [{media_type, data} | {url} | {data_omitted, bytes}]
 * @param tag    气泡上方的小标签，例如"插话"
 */
export function userTurn(text, images = [], tag = null) {
  if (text.startsWith(SUMMARY_PREFIX)) {
    const summary = text.slice(SUMMARY_PREFIX.length).split(SUMMARY_SUFFIX)[0].trim();
    const latestIndex = text.indexOf(LATEST_USER_MESSAGE);
    const latest = latestIndex >= 0 ? text.slice(latestIndex + LATEST_USER_MESSAGE.length).trim() : "";
    return el("div", {}, summaryCard(summary), latest || images.length ? userTurn(latest, images, tag) : null);
  }
  const imageRow = images.length
    ? el("div", { class: "bubble-images" }, images.map(imageElement))
    : null;
  return el("div", { class: "turn-user" },
    tag ? el("div", { class: "bubble-tag" }, tag) : null,
    imageRow,
    text ? el("div", { class: "bubble" }, text) : null);
}

function imageElement(image) {
  const mediaType = image.media_type || "image/png";
  if (typeof image.data === "string" && /^(image\/(png|jpeg|gif|webp))$/i.test(mediaType)
      && /^[A-Za-z0-9+/]+={0,2}$/.test(image.data)) {
    return el("img", { src: `data:${mediaType};base64,${image.data}`, alt: "图片" });
  }
  const url = safeUrl(image.url, false);
  if (url) {
    // 历史中的远程图片不自动请求，避免跟踪及访问浏览器所在的内网。
    return el("a", { href: url, target: "_blank", rel: "noopener noreferrer" }, "查看远程图片");
  }
  const size = image.bytes ? `（${formatTokens(image.bytes)}B）` : "";
  return el("span", { class: "image-placeholder" }, `图片${size}`);
}

/** 上下文压缩后的摘要：收起显示，避免占满屏幕。 */
function summaryCard(summary) {
  return el("details", { class: "summary-card" },
    el("summary", {}, "之前的对话已压缩成摘要（点击展开）"),
    el("div", { class: "markdown" }, markdownFragment(summary)));
}

// ------------------------------------------------------------------ 助手回复

/**
 * 助手的一次回复：可选的思考过程 + Markdown 正文。流式输出时不断追加，结束时用完整文本校正。
 * 正文的重新渲染合并到下一帧，避免每个字都重排一次。
 */
export class AssistantSegment {
  constructor() {
    this.text = "";
    this.thinkingText = "";
    this.thinkingBody = el("div", { class: "thinking-body" });
    this.thinking = el("details", { class: "thinking", hidden: true },
      el("summary", {}, icon("chevron"), el("span", {}, "思考过程")), this.thinkingBody);
    this.body = el("div", { class: "markdown" });
    this.copyButton = el("button", { type: "button", class: "message-action", title: "复制回复", "aria-label": "复制回复",
      onclick: () => this.#copy() }, icon("copy"), el("span", {}, "复制"));
    this.actions = el("div", { class: "message-actions", hidden: true }, this.copyButton);
    this.element = el("div", { class: "turn-assistant" }, this.thinking, this.body, this.actions);
    this.renderScheduled = false;
  }

  appendThinking(delta) {
    this.thinkingText += delta;
    this.thinking.hidden = false;
    this.thinkingBody.textContent = this.thinkingText;
  }

  appendText(delta) {
    this.text += delta;
    this.body.classList.add("streaming-caret");
    this.#scheduleRender();
  }

  /** 用完整的最终文本替换（以服务端保存的为准）。 */
  setText(text) {
    this.text = text;
    this.#scheduleRender();
  }

  setThinking(text) {
    this.thinkingText = text || "";
    this.thinkingBody.textContent = this.thinkingText;
    this.thinking.hidden = !this.thinkingText;
  }

  /** 这一轮要重新生成：丢掉已经显示的部分。 */
  reset() {
    this.text = "";
    this.thinkingText = "";
    this.thinkingBody.textContent = "";
    this.thinking.hidden = true;
    this.#scheduleRender();
  }

  /** 这段回复结束：去掉光标，有正文时显示"复制"。 */
  finish() {
    this.body.classList.remove("streaming-caret");
    this.finished = true;
    this.#scheduleRender();
  }

  /** 复制 Markdown 原文（而不是渲染后的文字），粘贴到别处时格式不丢。 */
  async #copy() {
    const label = this.copyButton.querySelector("span");
    try {
      await navigator.clipboard.writeText(this.text);
      label.textContent = "已复制";
    } catch (clipboardUnavailable) {
      label.textContent = "复制失败";
    }
    setTimeout(() => {
      label.textContent = "复制";
    }, 1500);
  }

  isEmpty() {
    return !this.text && !this.thinkingText;
  }

  #scheduleRender() {
    if (this.renderScheduled) {
      return;
    }
    this.renderScheduled = true;
    requestAnimationFrame(() => {
      this.renderScheduled = false;
      this.body.replaceChildren(markdownFragment(this.text));
      this.body.hidden = !this.text;
      this.actions.hidden = !(this.finished && this.text);
    });
  }
}

function markdownFragment(markdown) {
  const template = document.createElement("template");
  template.innerHTML = renderMarkdown(markdown); // renderMarkdown 已经转义了所有文字
  return template.content;
}

// ------------------------------------------------------------------ 工具调用

/**
 * 一次工具调用：标题行（状态、工具名、摘要、耗时）+ 点开后的参数和结果。
 * 状态：生成参数中 → 等待审批 / 执行中 → 成功 / 失败。
 */
export class ToolCard {
  constructor(callId, name) {
    this.callId = callId;
    this.name = name;
    this.status = el("span", { class: "tool-status pending" }, el("span", { class: "spinner" }));
    this.summary = el("span", { class: "tool-summary" }, "生成参数中…");
    this.meta = el("span", { class: "tool-meta" });
    this.argumentsView = el("pre");
    this.resultView = el("pre", { hidden: true });
    this.resultTitle = el("div", { class: "tool-section-title", hidden: true }, "结果");
    this.progress = el("div", { class: "tool-progress", hidden: true });
    /** 等待确认时，确认卡片放在这里（标题行下面）。 */
    this.approvalSlot = el("div", { class: "tool-approval", hidden: true });
    this.element = el("div", { class: "tool-card" },
      el("button", { type: "button", class: "tool-header", onclick: () => this.toggle() },
        this.status,
        el("span", { class: "tool-name" }, name),
        this.summary,
        this.meta,
        el("span", { class: "tool-chevron" }, icon("chevron"))),
      this.progress,
      this.approvalSlot,
      el("div", { class: "tool-body" },
        el("div", { class: "tool-section-title" }, "参数"), this.argumentsView,
        this.resultTitle, this.resultView));
  }

  toggle() {
    this.element.classList.toggle("open");
  }

  setArguments(args, summary) {
    this.argumentsView.textContent = JSON.stringify(args ?? {}, null, 2);
    this.summary.textContent = toolSummaryText(this.name, summary) || summarize(args);
  }

  setRunning(summary) {
    if (summary) {
      this.summary.textContent = toolSummaryText(this.name, summary);
    }
    this.meta.textContent = "执行中";
  }

  /** 这次调用在等用户确认：确认卡片显示在工具卡片里，状态图标换成盾牌。 */
  attachApproval(approvalElement) {
    this.approvalSlot.replaceChildren(approvalElement);
    this.approvalSlot.hidden = false;
    this.element.classList.add("awaiting-approval");
    this.status.className = "tool-status pending";
    this.status.replaceChildren(icon("shield"));
    this.meta.textContent = "等待确认";
  }

  /** 确认完成（卡片头部的"已批准 / 已拒绝"会说明结果），收起确认卡片。 */
  detachApproval() {
    this.approvalSlot.replaceChildren();
    this.approvalSlot.hidden = true;
    this.element.classList.remove("awaiting-approval");
    if (!this.finished) {
      this.status.className = "tool-status pending";
      this.status.replaceChildren(el("span", { class: "spinner" }));
    }
  }

  setProgress(message) {
    this.progress.hidden = false;
    this.progress.textContent = message;
  }

  /** 审批结论：拒绝时直接标成失败（工具不会执行）。 */
  setPermission(decision, reason) {
    const allowed = decision === "allow";
    this.meta.replaceChildren(el("span", { class: `permission-note ${allowed ? "allow" : "deny"}` },
      allowed ? "已批准" : `已拒绝${reason ? `：${reason}` : ""}`));
  }

  finish(output, isError, durationMillis) {
    this.finished = true;
    this.progress.hidden = true;
    this.status.className = `tool-status ${isError ? "error" : "success"}`;
    this.status.replaceChildren(icon(isError ? "cross" : "check"));
    this.meta.textContent = durationMillis === undefined ? "" : formatDuration(durationMillis);
    this.resultTitle.hidden = false;
    this.resultView.hidden = false;
    this.resultView.classList.toggle("error", isError);
    this.resultView.textContent = output || "(没有输出)";
  }

  /** 历史里只有调用、没有结果（例如运行被中断）。 */
  markWithoutResult(reason = "运行已中断，未收到结果") {
    this.progress.hidden = true;
    this.meta.textContent = reason;
    this.status.className = "tool-status pending";
    this.status.replaceChildren(icon("info"));
  }
}

/**
 * 服务端给的摘要形如 "bash(ls -la)"，卡片上已经显示了工具名，所以只留括号里的部分。
 */
export function toolSummaryText(name, summary) {
  if (!summary) {
    return "";
  }
  const prefix = `${name}(`;
  if (summary.startsWith(prefix) && summary.endsWith(")")) {
    return summary.slice(prefix.length, -1);
  }
  return summary;
}

/** 没有服务端给的摘要时，用参数里最有代表性的一个值。 */
function summarize(args) {
  if (!args) {
    return "";
  }
  for (const key of ["command", "path", "file_path", "url", "pattern", "query"]) {
    if (typeof args[key] === "string") {
      return args[key];
    }
  }
  return Object.entries(args).slice(0, 2).map(([key, value]) => `${key}=${JSON.stringify(value)}`).join(", ");
}

// ------------------------------------------------------------------ 审批

/**
 * 权限策略给出的原因 → 给人看的说明。工具默认就需要确认（最常见的情况）时不显示原因。
 */
export function describeApprovalReason(reason) {
  if (!reason || reason === "tool default") {
    return "";
  }
  if (reason === "the path is outside the working directory") {
    return "要访问工作目录之外的路径";
  }
  const rule = reason.match(/^rule (.+) requires approval$/);
  if (rule) {
    return `配置的规则 ${rule[1]} 要求确认`;
  }
  return reason;
}

/**
 * 审批卡片：需要用户确认的操作。按钮：允许一次 / 本会话总是允许 / 拒绝（可以写原因，原因会交给模型）。
 * 回答后收起成一行结果；放在工具卡片里时由 onClosed 把它整个拿掉（工具卡片头部会显示结论）。
 *
 * @param onAnswer async (decision, message) => boolean，返回 false 表示已超时或已处理
 * @param onClosed 有了结论之后调用（可选）
 */
export class ApprovalCard {
  constructor(request, onAnswer, onClosed = () => {}) {
    this.onAnswer = onAnswer;
    this.onClosed = onClosed;
    this.closed = false;
    this.submitting = false;
    this.feedback = el("input", { class: "approval-feedback", placeholder: "拒绝原因（可选，会告诉模型）" });
    this.actions = el("div", { class: "approval-actions" },
      el("button", { type: "button", class: "button button-primary", onclick: () => this.#answer("allow") }, "允许"),
      el("button", { type: "button", class: "button", onclick: () => this.#answer("always") }, "本会话总是允许"),
      this.feedback,
      el("button", { type: "button", class: "button button-danger", onclick: () => this.#answer("deny") }, "拒绝"));
    // 结果行：回答后显示结论（带图标），提交失败时显示错误（不带图标，按钮仍可再点）
    this.result = el("span", {});
    this.resultIcon = icon("shield");
    this.resultRow = el("div", { class: "approval-result", hidden: true }, this.resultIcon, this.result);
    const reason = describeApprovalReason(request.reason);
    const argumentText = JSON.stringify(request.arguments ?? {}, null, 2);
    this.details = el("div", { class: "approval-details" },
      el("div", { class: "approval-title" }, icon("shield"), el("span", {}, "需要你的确认"),
        el("span", { class: "approval-tool" }, request.tool)),
      el("div", { class: "approval-summary" }, toolSummaryText(request.tool, request.summary) || request.subject || request.tool),
      reason ? el("div", { class: "approval-reason" }, reason) : null,
      el("details", { class: "approval-arguments" }, el("summary", {}, "查看完整参数"), el("pre", {}, argumentText)),
      this.actions);
    this.element = el("div", { class: "approval", role: "group", "aria-label": "需要确认的操作" }, this.details, this.resultRow);
  }

  async #answer(decision) {
    if (this.closed || this.submitting) {
      return;
    }
    this.submitting = true;
    this.resultRow.hidden = true;
    this.#setButtonsDisabled(true);
    try {
      const resolved = await this.onAnswer(decision, this.feedback.value.trim());
      if (this.closed) {
        return;
      }
      const labels = { allow: "已允许", always: "已允许（本会话不再询问同类操作）", deny: "已拒绝" };
      this.close(resolved ? labels[decision] : "这个审批已经超时或被处理了");
    } catch (error) {
      if (!this.closed) {
        this.resultRow.hidden = false;
        this.resultIcon.hidden = true;
        this.result.textContent = `提交失败：${error.message}`;
      }
    } finally {
      this.submitting = false;
      this.#setButtonsDisabled(this.closed);
    }
  }

  /** 有了结论（用户回答、超时、运行结束）：收起成一行结果；不会被尚未返回的提交覆盖。 */
  close(text) {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.#setButtonsDisabled(true);
    this.actions.hidden = true;
    this.details.hidden = true;
    this.element.classList.add("resolved");
    this.resultRow.hidden = false;
    this.resultIcon.hidden = false;
    this.result.textContent = text;
    this.onClosed();
  }

  #setButtonsDisabled(disabled) {
    for (const button of this.actions.querySelectorAll("button")) {
      button.disabled = disabled;
    }
  }
}

// ------------------------------------------------------------------ 提示行

/** level: info / warning / error */
export function noticeRow(level, text) {
  const iconName = level === "error" ? "alert" : level === "warning" ? "alert" : "info";
  return el("div", { class: `notice ${level}` }, icon(iconName), el("span", {}, text));
}

export function dividerRow(text) {
  return el("div", { class: "divider" }, text);
}

/** 运行结束：停止原因、轮数、用量。 */
export function runSummaryRow(data) {
  const reasons = { end_turn: "完成", max_tokens: "输出达到上限", cancelled: "已取消", error: "出错",
    max_turns: "达到轮数上限", refusal: "模型拒绝回答" };
  const reason = reasons[data.stop_reason] ?? data.stop_reason;
  const turns = data.turns ? `${data.turns} 轮` : null;
  return el("div", { class: "run-summary" }, icon("check"),
    el("span", {}, describeUsage(data.usage, [reason, turns].filter(Boolean))));
}
