// 和 agentcore 的 HTTP 接口打交道：带上 token、把错误变成 ApiError、读取发消息时返回的 SSE 事件流。
// 接口说明见 AgentController（GET/POST /conversations、/messages、/cancel、/steer、/approvals ……）。

const TOKEN_STORAGE_KEY = "agentcore.token";

/** 接口返回了错误状态。status：401 要 token，403 只接受本机请求，404 不存在，409 正在运行。 */
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export class AgentApi {
  #token;

  /** @param base 接口前缀，例如 /api/agent */
  constructor(base) {
    this.base = base.replace(/\/+$/, "");
    this.storageKey = `${TOKEN_STORAGE_KEY}:${this.base}`;
    this.#token = readStorage(this.storageKey);
  }

  // ---------------------------------------------------------------- token（只保存在这个浏览器里）

  get token() {
    return this.#token;
  }

  set token(value) {
    this.#token = value || null;
    writeStorage(this.storageKey, this.#token);
  }

  // ---------------------------------------------------------------- 接口

  health() {
    return this.#json("GET", "/health");
  }

  models() { return this.#json("GET", "/models"); }

  modelSettings() { return this.#json("GET", "/model-settings"); }

  saveModel(id, profile) {
    return this.#json("PUT", `/model-settings/${encodeURIComponent(id)}`, profile);
  }

  deleteModel(id) { return this.#json("DELETE", `/model-settings/${encodeURIComponent(id)}`); }

  setDefaultModel(modelId) { return this.#json("PUT", "/model-settings/default", { model_id: modelId }); }

  async listConversations(limit = 100) {
    return (await this.#json("GET", `/conversations?limit=${limit}`)).conversations;
  }

  async createConversation() {
    return (await this.#json("POST", "/conversations", {})).id;
  }

  getConversation(id) {
    return this.#json("GET", `/conversations/${encodeURIComponent(id)}`);
  }

  async deleteConversation(id) {
    return (await this.#json("DELETE", `/conversations/${encodeURIComponent(id)}`)).deleted;
  }

  async cancel(id) {
    return (await this.#json("POST", `/conversations/${encodeURIComponent(id)}/cancel`)).cancelled;
  }

  /** 运行中插话；返回 false 表示运行已经结束，应该作为新消息发送。 */
  async steer(id, text) {
    return (await this.#json("POST", `/conversations/${encodeURIComponent(id)}/steer`, { text })).accepted;
  }

  async pendingApprovals(id) {
    const query = id == null ? "" : `?conversationId=${encodeURIComponent(id)}`;
    return (await this.#json("GET", `/approvals${query}`)).approvals;
  }

  /** 回答审批：decision 是 allow / always / deny。返回 false 表示已超时或已处理。 */
  async answerApproval(requestId, decision, message) {
    try {
      return (await this.#json("POST", `/approvals/${encodeURIComponent(requestId)}`, { decision, message })).resolved;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return false;
      }
      throw error;
    }
  }

  /**
   * 发消息并读取事件流，直到运行结束。每个事件调用一次 onEvent(名字, 数据)。
   * @param input {text, images: [{media_type, data}], model_id?: string}（服务端登记的 opaque ID）
   */
  async sendMessage(id, input, onEvent) {
    const response = await fetch(`${this.base}/conversations/${encodeURIComponent(id)}/messages`, {
      method: "POST",
      headers: this.#headers(),
      body: JSON.stringify(input),
    });
    if (!response.ok) {
      throw await errorFrom(response);
    }
    await readEventStream(response.body, onEvent);
  }

  // ---------------------------------------------------------------- 内部

  async #json(method, path, body) {
    const response = await fetch(this.base + path, {
      method,
      headers: this.#headers(),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      throw await errorFrom(response);
    }
    return response.status === 204 ? null : response.json();
  }

  #headers() {
    const headers = { "Content-Type": "application/json" };
    if (this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }
    return headers;
  }
}

/** 错误响应 → ApiError（接口的错误体是 {"error": "..."}）。 */
async function errorFrom(response) {
  let message = `${response.status} ${response.statusText}`;
  try {
    const body = await response.json();
    message = body.error || message;
  } catch (notJson) {
    // 保留状态码说明
  }
  return new ApiError(response.status, message);
}

/**
 * 逐块读取 SSE：事件之间空一行，每行是 "event:名字" 或 "data:内容"。
 * （发消息是 POST 请求，浏览器的 EventSource 只支持 GET，所以自己解析。）
 */
export class StreamProtocolError extends Error {
  constructor(message) {
    super(message);
    this.name = "StreamProtocolError";
  }
}

export async function readEventStream(body, onEvent) {
  if (!body) {
    throw new StreamProtocolError("事件流为空，未收到 run_finished");
  }
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let line = "";
  let lines = [];
  let afterCR = false;
  let finished = false;

  // 逐行处理，跨 chunk 的 CRLF 仍然只是一个换行。
  function consume(text) {
    for (const character of text) {
      if (afterCR && character === "\n") {
        afterCR = false;
        continue;
      }
      afterCR = character === "\r";
      if (character !== "\r" && character !== "\n") {
        line += character;
        continue;
      }
      if (line !== "") {
        lines.push(line);
      } else {
        const event = parseEventBlock(lines.join("\n"));
        lines = [];
        if (event) {
          onEvent(event.name, event.data);
          if (event.name === "run_finished") {
            finished = true;
          }
        }
      }
      line = "";
    }
  }

  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        consume(decoder.decode());
        // SSE 只有空行才能提交事件；不把 EOF 的半帧假装成完成事件。
        if (!finished) {
          throw new StreamProtocolError("事件流提前结束，未收到 run_finished；请刷新历史确认运行状态");
        }
        break;
      }
      consume(decoder.decode(value, { stream: true }));
    }
  } catch (error) {
    try { await reader.cancel(); } catch (closed) { /* 已断开的连接 */ }
    throw error;
  } finally {
    reader.releaseLock();
  }
}

function parseEventBlock(block) {
  let name = "message";
  const dataLines = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      name = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).replace(/^ /, ""));
    }
  }
  if (dataLines.length === 0) {
    return null;
  }
  let data;
  try {
    data = JSON.parse(dataLines.join("\n"));
  } catch (notJson) {
    throw new StreamProtocolError(`事件 ${name} 包含无效 JSON`);
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new StreamProtocolError(`事件 ${name} 的数据必须是对象`);
  }
  if (data.type !== undefined && data.type !== name) {
    throw new StreamProtocolError(`事件类型不匹配：${name} / ${data.type}`);
  }
  return { name, data };
}

function readStorage(key) {
  try {
    return sessionStorage.getItem(key);
  } catch (storageUnavailable) {
    return null;
  }
}

function writeStorage(key, value) {
  try {
    if (value === null) {
      sessionStorage.removeItem(key);
    } else {
      sessionStorage.setItem(key, value);
    }
  } catch (storageUnavailable) {
    // 隐私模式等：这次会话里有效，不保存
  }
}
