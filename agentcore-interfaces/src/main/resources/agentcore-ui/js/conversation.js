// 对话区域的内容：把保存的历史画出来（renderHistory），以及根据一次运行的实时事件不断更新（LiveRun）。
// 两者用同样的组件，所以刷新页面后看到的和运行时看到的一致。

import {
  ApprovalCard, AssistantSegment, ToolCard, dividerRow, noticeRow, runSummaryRow, userTurn,
} from "./components.js";
import { formatTokens } from "./dom.js";

// ------------------------------------------------------------------ 历史

/**
 * 画出会话的全部消息。
 * 历史里，工具结果放在下一条"用户"消息里（tool_result 块）：按 tool_call_id 填回对应的工具卡片，不单独显示；
 * 那条消息里另外的文字是用户运行中的插话，显示成带"插话"标签的气泡。
 */
export function renderHistory(container, messages) {
  const toolCards = new Map();
  for (const message of messages) {
    if (message.role === "assistant") {
      renderAssistantMessage(container, message, toolCards);
    } else {
      renderUserMessage(container, message, toolCards);
    }
  }
  for (const card of toolCards.values()) {
    if (!card.finished) {
      card.markWithoutResult();
    }
  }
}

function renderAssistantMessage(container, message, toolCards) {
  const segment = new AssistantSegment();
  segment.setThinking(blocksOfType(message, "thinking").map(block => block.text ?? "").join(""));
  segment.setText(blocksOfType(message, "text").map(block => block.text).join(""));
  segment.finish();
  if (!segment.isEmpty()) {
    container.append(segment.element);
  }
  for (const call of blocksOfType(message, "tool_call")) {
    const card = new ToolCard(call.id, call.name);
    card.setArguments(call.arguments);
    toolCards.set(call.id, card);
    container.append(card.element);
  }
}

function renderUserMessage(container, message, toolCards) {
  const results = blocksOfType(message, "tool_result");
  for (const result of results) {
    const card = toolCards.get(result.tool_call_id);
    if (card) {
      card.finish(textOf(result.content), result.is_error);
      card.finished = true;
    }
  }
  const text = blocksOfType(message, "text").map(block => block.text).join("\n");
  const images = blocksOfType(message, "image");
  if (text || images.length) {
    container.append(userTurn(text, images, results.length ? "插话" : null));
  }
}

function blocksOfType(message, type) {
  return (message.content ?? []).filter(block => block.type === type);
}

/** 工具结果的内容块 → 文字（图片写成占位说明）。 */
function textOf(blocks) {
  return (blocks ?? []).map(block => (block.type === "text" ? block.text : `[${block.type}]`)).join("\n");
}

// ------------------------------------------------------------------ 实时运行

/**
 * 一次运行的实时显示：发消息后，接口返回的每个 SSE 事件交给 handle()。
 * 一次运行可能有多轮：每轮是一次模型回复（思考、正文、工具调用），工具执行完再进入下一轮。
 */
export class LiveRun {
  /**
   * @param container 追加内容的位置
   * @param answerApproval async (requestId, decision, message) => boolean
   * @param onContentAdded 内容变化时调用（用来滚动到底部）
   */
  constructor(container, answerApproval, onContentAdded) {
    this.container = container;
    this.answerApproval = answerApproval;
    this.onContentAdded = onContentAdded ?? (() => {});
    this.closed = false;
    this.provisionalTools = new Set();
    this.segment = null;
    this.toolCards = new Map();
    this.approvals = [];
    this.finished = null; // run_finished 事件的数据
  }

  handle(name, data) {
    // run_finished 后仍可能收到持久化失败的 error，不能吞掉该提示。
    if (this.closed && !(this.finished && name === "error")) {
      return;
    }
    const handler = this.#handlers[name];
    if (handler) {
      handler(data);
      this.onContentAdded();
    }
  }

  #handlers = {
    turn_started: () => this.#finishSegment(),
    thinking_delta: data => this.#currentSegment().appendThinking(data.text),
    text_delta: data => this.#currentSegment().appendText(data.text),
    tool_call_streaming: data => {
      if (!this.toolCards.has(data.id)) {
        this.provisionalTools.add(data.id);
      }
      this.#toolCard(data.id, data.name);
    },
    assistant_message: data => this.#completeReply(data.message),
    tool_started: data => this.#toolCard(data.call.id, data.call.name).setRunning(data.summary),
    tool_progress: data => this.toolCards.get(data.call_id)?.setProgress(data.message),
    permission_decided: data => this.toolCards.get(data.call_id)?.setPermission(data.decision, data.reason),
    tool_finished: data => this.#toolCard(data.call_id, data.tool).finish(data.output, data.is_error, data.duration_ms),
    approval_required: data => this.#askForApproval(data),
    retrying: data => this.#retry(data),
    compacted: data => this.#append(dividerRow(
      `上下文已压缩：约 ${formatTokens(data.tokens_before)} → ${formatTokens(data.tokens_after)} tokens`)),
    notice: data => this.#append(noticeRow(data.level, data.message)),
    run_finished: data => this.#finish(data),
    error: data => this.#append(noticeRow("error", data.error)),
  };

  /** 当前这一轮的回复；第一次有内容时才创建，避免留下空白块。 */
  #currentSegment() {
    if (!this.segment) {
      this.segment = new AssistantSegment();
      this.#append(this.segment.element);
    }
    return this.segment;
  }

  #finishSegment() {
    this.segment?.finish();
    this.segment = null;
  }

  /** 一轮回复结束：以服务端的完整消息为准校正正文（没有流式输出过的话就新建一段），补上工具调用的完整参数。 */
  #completeReply(message) {
    const text = blocksOfType(message, "text").map(block => block.text).join("");
    const thinking = blocksOfType(message, "thinking").map(block => block.text ?? "").join("");
    if (this.segment || text || thinking) {
      const segment = this.#currentSegment();
      segment.setText(text);
      segment.setThinking(thinking);
    }
    this.#finishSegment();
    const calls = blocksOfType(message, "tool_call");
    const completedIds = new Set(calls.map(call => call.id));
    for (const id of this.provisionalTools) {
      if (!completedIds.has(id)) {
        this.toolCards.get(id)?.element.remove();
        this.toolCards.delete(id);
      }
    }
    this.provisionalTools.clear();
    for (const call of calls) {
      this.#toolCard(call.id, call.name).setArguments(call.arguments);
    }
  }

  #toolCard(callId, name) {
    let card = this.toolCards.get(callId);
    if (!card) {
      // 不结束当前这段回复：同一次回复的正文先于工具调用到达，assistant_message 还要用完整文本校正它
      card = new ToolCard(callId, name);
      this.toolCards.set(callId, card);
      this.#append(card.element);
    }
    return card;
  }

  /** 知道是哪次工具调用时，确认卡片放进那张工具卡片里，回答后收起；否则单独显示在对话末尾。 */
  #askForApproval(request) {
    const toolCard = request.call_id ? this.toolCards.get(request.call_id) : null;
    const answer = (decision, message) => this.answerApproval(request.request_id, decision, message);
    const approval = new ApprovalCard(request, answer, () => toolCard?.detachApproval());
    this.approvals.push(approval);
    if (toolCard) {
      toolCard.attachApproval(approval.element);
    } else {
      this.#append(approval.element);
    }
  }

  /** 这一轮要从头重新生成：丢掉已经显示的半截回复。 */
  #retry(data) {
    this.segment?.finish();
    this.segment?.element.remove();
    this.segment = null;
    for (const id of this.provisionalTools) {
      this.toolCards.get(id)?.element.remove();
      this.toolCards.delete(id);
    }
    this.provisionalTools.clear();
    this.#append(noticeRow("warning", `模型调用出错，第 ${data.attempt} 次重试：${data.error ?? ""}`));
  }

  /** 仅收尾本地显示，不发送服务器 cancel；网络/协议错误时由调用者调用。 */
  close(reason = "连接已中断，请刷新历史确认运行状态") {
    if (this.closed) {
      return;
    }
    this.#cleanup(reason);
    if (reason) {
      this.#append(noticeRow("error", reason));
    }
    this.onContentAdded();
  }

  abort(reason) {
    this.close(reason);
  }

  #cleanup(reason) {
    this.closed = true;
    this.#finishSegment();
    for (const card of this.toolCards.values()) {
      if (!card.finished) {
        card.markWithoutResult(reason);
      }
    }
    for (const approval of this.approvals) {
      approval.close(reason || "运行已结束");
    }
  }

  #finish(data) {
    this.finished = data;
    this.#cleanup("运行已结束，未收到结果");
    if (data.error) {
      this.#append(noticeRow("error", data.error));
    }
    this.#append(runSummaryRow(data));
  }

  #append(element) {
    this.container.append(element);
  }
}
