// 创建 DOM 的小工具和几个格式化函数，让组件代码读起来像结构而不是一堆 createElement。

/**
 * 创建元素：el("div", { class: "card", onclick: handler }, "文字", childElement)。
 * 以 on 开头的属性当作事件监听；值为 null / false 的属性跳过；子节点里的字符串当作纯文本（不会被当成 HTML）。
 */
export function el(tag, attributes = {}, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === null || value === undefined || value === false) {
      continue;
    }
    if (name.startsWith("on") && typeof value === "function") {
      element.addEventListener(name.slice(2), value);
    } else if (value === true) {
      element.setAttribute(name, "");
    } else {
      element.setAttribute(name, String(value));
    }
  }
  append(element, children);
  return element;
}

function append(parent, children) {
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) {
      continue;
    }
    parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/** 1234 → "1.2k"，1234567 → "1.2M"。 */
export function formatTokens(count) {
  if (!count) {
    return "0";
  }
  if (count >= 1_000_000) {
    return `${(count / 1_000_000).toFixed(1)}M`;
  }
  return count >= 1000 ? `${(count / 1000).toFixed(1)}k` : String(count);
}

/** 毫秒 → "820ms" / "3.4s" / "2m 05s"。 */
export function formatDuration(milliseconds) {
  if (milliseconds < 1000) {
    return `${milliseconds}ms`;
  }
  const seconds = milliseconds / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)}s`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${String(Math.round(seconds % 60)).padStart(2, "0")}s`;
}

/** 时间 → "刚刚" / "5 分钟前" / "3 小时前" / "昨天" / "11-05"。 */
export function formatRelativeTime(isoTime) {
  const time = new Date(isoTime);
  if (Number.isNaN(time.getTime())) {
    return "";
  }
  const minutes = Math.floor((Date.now() - time.getTime()) / 60_000);
  if (minutes < 1) {
    return "刚刚";
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`;
  }
  if (minutes < 24 * 60) {
    return `${Math.floor(minutes / 60)} 小时前`;
  }
  if (minutes < 48 * 60) {
    return "昨天";
  }
  return `${String(time.getMonth() + 1).padStart(2, "0")}-${String(time.getDate()).padStart(2, "0")}`;
}

/** 运行结束时的用量说明："end_turn · 2 轮 · 输入 1.2k · 输出 320"。 */
export function describeUsage(usage, extra = []) {
  if (!usage) {
    return extra.join(" · ");
  }
  const input = (usage.input_tokens ?? 0) + (usage.cache_read_tokens ?? 0) + (usage.cache_write_tokens ?? 0);
  const parts = [...extra, `输入 ${formatTokens(input)}`, `输出 ${formatTokens(usage.output_tokens ?? 0)}`];
  if (usage.cache_read_tokens) {
    parts.push(`缓存命中 ${formatTokens(usage.cache_read_tokens)}`);
  }
  return parts.join(" · ");
}

/** 显示一会儿就消失的提示。 */
export function toast(message) {
  const element = el("div", { class: "toast", role: "status" }, message);
  document.body.append(element);
  setTimeout(() => element.remove(), 2600);
}
