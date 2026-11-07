// 模型选择（输入框里的下拉框、侧栏的模型名）和"模型设置"对话框。
//
// 页面只保存模型的 ID，不保存地址和 key；API key 只在密码框和那一次保存请求里短暂存在，
// 不写进浏览器存储，服务端返回的错误也不原样显示（错误里可能回显了提交的内容）。

import { el } from "./dom.js";

export const MODEL_TYPES = ["anthropic", "openai", "openai-responses", "mock"];
/** 服务端内置的、不能新建同名配置的 ID。 */
const RESERVED_IDS = new Set(["server-default", "mock", "default"]);
const PROFILE_FIELDS = ["id", "label", "type", "model", "base_url", "max_tokens", "context_window"];
const ID_PATTERN = /^[a-z][a-z0-9_-]{0,63}$/;
/** 运行中的那一项在下拉框里的值（不是真实的模型 ID）。 */
const RUNNING_OPTION = "__running__";
const TYPE_LABELS = { anthropic: "Anthropic", openai: "OpenAI Chat", "openai-responses": "OpenAI Responses", mock: "离线" };

// ------------------------------------------------------------------ 表单数据

/** 服务端返回的一个配置 → 表单里的值。即使服务端错误地返回了 key，也不放进表单。 */
export function profileValues(profile = {}) {
  const values = Object.fromEntries(PROFILE_FIELDS.map(name => [name, profile[name] ?? ""]));
  values.type ||= "anthropic";
  return { ...values, has_api_key: profile.has_api_key === true, api_key: "", clear_api_key: false };
}

/** 表单里的值 → 保存请求 {id, body}。不合法时抛出给人看的错误。 */
export function profileRequest(values) {
  const id = String(values.id || "").trim();
  if (!ID_PATTERN.test(id) || RESERVED_IDS.has(id)) {
    throw new Error("ID 须以小写字母开头，仅含小写字母、数字、下划线或连字符，最多 64 字符，且不能使用 server-default、mock、default。");
  }
  if (!MODEL_TYPES.includes(values.type)) {
    throw new Error("请选择受支持的协议。");
  }
  const body = Object.fromEntries(["label", "type", "model", "base_url"].map(name => [name, String(values[name] || "").trim()]));
  if (!body.label || !body.model) {
    throw new Error("请填写显示名称与模型名称。");
  }
  if (body.base_url) {
    requireHttpUrl(body.base_url);
  }
  for (const name of ["max_tokens", "context_window"]) {
    const value = String(values[name] ?? "").trim();
    if (value) {
      body[name] = positiveInteger(name, value);
    }
  }
  if (values.clear_api_key) {
    body.clear_api_key = true;
  } else if (values.api_key) {
    body.api_key = values.api_key;
  }
  return { id, body };
}

function requireHttpUrl(text) {
  let url;
  try {
    url = new URL(text);
  } catch {
    throw new Error("Base URL 必须是有效的 HTTP(S) 地址。");
  }
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new Error("Base URL 仅支持 HTTP(S)，且不能包含用户名或密码。");
  }
}

function positiveInteger(name, text) {
  const number = Number(text);
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(number) || number <= 0) {
    throw new Error(`${name} 必须是正整数，或留空使用服务端默认值。`);
  }
  return number;
}

// ------------------------------------------------------------------ 可选的模型

/** 检查并整理 GET /models 的结果；格式不对时抛错，宁可不让发送，也不悄悄用别的模型。 */
export function normalizeCatalog(data) {
  if (!data || !Array.isArray(data.models) || typeof data.default_id !== "string") {
    throw new Error("模型目录格式错误");
  }
  const models = data.models.map(item => {
    if (!item || typeof item.id !== "string" || !item.id) {
      throw new Error("模型 ID 缺失");
    }
    return { id: item.id, label: String(item.label || item.id), model: String(item.model || ""), read_only: item.read_only === true };
  });
  const idsAreUnique = new Set(models.map(item => item.id)).size === models.length;
  const defaultExists = models.some(item => item.id === data.default_id);
  if (!idsAreUnique || !defaultExists) {
    throw new Error("模型目录默认值无效");
  }
  return { default_id: data.default_id, can_manage: data.can_manage === true, persisted: data.persisted === true, models };
}

/** 当前选中的模型。目录加载失败时 error 不为空，此时不允许发送。 */
export class ModelSelection {
  constructor() {
    this.reset();
  }

  reset() {
    this.catalog = null;
    this.selected = null;
    this.error = "模型目录尚未加载";
    /** 服务端没有 /models 接口（旧版本）：只能用启动模型，发送时不带 model_id。 */
    this.legacy = false;
  }

  /** 用新的目录替换；之前选的模型不见了时改回默认，并返回提醒。 */
  apply(data, legacy = false) {
    this.catalog = normalizeCatalog(data);
    this.error = "";
    this.legacy = legacy;
    return this.select(this.selected);
  }

  /** 选中一个模型（null 表示默认）。选的模型不存在时改回默认并返回提醒，否则返回空字符串。 */
  select(id) {
    this.selected = id || this.catalog?.default_id || null;
    if (this.catalog && !this.#exists(this.selected)) {
      const missing = this.selected;
      this.selected = this.catalog.default_id;
      return `模型“${missing}”已不可用，已改选“${this.label(this.selected)}”。请确认后再发送。`;
    }
    return "";
  }

  label(id) {
    return this.#find(id)?.label || id || "启动模型";
  }

  /** 实际的模型名称（例如 deepseek:deepseek-v4-pro）；不知道时用显示名称。 */
  modelName(id) {
    return this.#find(id)?.model || this.label(id);
  }

  /** 发消息时附带的字段。 */
  request() {
    if (this.error || !this.catalog) {
      throw new Error(this.error || "模型目录不可用");
    }
    if (!this.#exists(this.selected)) {
      throw new Error("所选模型已不可用，请重新选择。");
    }
    return this.legacy ? {} : { model_id: this.selected };
  }

  #find(id) {
    return this.catalog?.models.find(item => item.id === id);
  }

  #exists(id) {
    return Boolean(this.#find(id));
  }
}

// ------------------------------------------------------------------ 页面控件

/**
 * 模型下拉框、侧栏模型名、"模型设置"对话框。
 *
 * 异步请求返回时页面可能已经变了（换了令牌、又发了一次请求、对话框关了），所以用几个版本号丢掉过期的结果：
 * epoch 在身份变化（reset）时加一，requestVersion 每次刷新目录加一，dialogVersion 每次清空表单加一。
 */
export class ModelControls {
  /**
   * @param nodes    页面元素（select、badge、dialog、form ……）
   * @param notify   显示一条提示
   * @param onChange 用户换了模型
   */
  constructor(api, nodes, notify, onChange = () => {}) {
    this.api = api;
    this.nodes = nodes;
    this.notify = notify;
    this.onChange = onChange;
    this.selection = new ModelSelection();
    this.epoch = 0;
    this.requestVersion = 0;
    this.dialogVersion = 0;
    this.profiles = [];
    this.busy = false;
    /** 页面状态：frozen 是正在运行的那次用的模型（运行中不能换），disabled 表示暂时不能操作。 */
    this.uiState = {};
    this.#wireEvents();
    this.clearForm();
    this.render();
  }

  #wireEvents() {
    const { nodes } = this;
    nodes.select.addEventListener("change", () => {
      if (nodes.select.disabled) {
        return;
      }
      this.select(nodes.select.value);
      this.onChange();
    });
    nodes.manage.addEventListener("click", () => this.openSettings());
    nodes.retry.addEventListener("click", () => this.refresh());
    nodes.dialog.addEventListener("close", () => this.clearForm());
    nodes.close.addEventListener("click", () => nodes.dialog.close());
    nodes.create.addEventListener("click", () => this.editProfile());
    nodes.cancel?.addEventListener("click", () => this.clearForm());
    nodes.form.addEventListener("submit", event => {
      event.preventDefault();
      this.save();
    });
    nodes.remove.addEventListener("click", () => this.remove());
    nodes.setDefault.addEventListener("click", () => this.mutate(() => this.api.setDefaultModel(nodes.defaultSelect.value)));
    // 勾选"删除已保存的 key"时清空并禁用 key 输入框，避免两者同时提交
    this.#field("clear_api_key").addEventListener("change", event => {
      const key = this.#field("api_key");
      if (event.target.checked) {
        key.value = "";
      }
      key.disabled = event.target.checked;
    });
  }

  get selected() {
    return this.selection.selected;
  }

  select(id) {
    const warning = this.selection.select(id);
    if (warning) {
      this.warn(warning);
    }
    this.render();
    return warning;
  }

  warn(message) {
    this.notice = message;
    this.notify(message);
  }

  /** 身份变了（换了令牌）：清掉一切和上一个身份有关的状态，过期的异步结果都会被丢掉。 */
  reset() {
    this.epoch++;
    this.requestVersion++;
    this.selection.reset();
    this.profiles = [];
    this.notice = "";
    this.busy = false;
    this.uiState = {};
    if (this.nodes.dialog.open) {
      this.nodes.dialog.close();
    }
    this.clearForm();
    this.nodes.list.replaceChildren();
    this.nodes.defaultSelect.replaceChildren();
    this.nodes.settingsStatus.textContent = "";
    this.nodes.storage.textContent = "";
    this.nodes.create.disabled = false;
    this.settingsLoaded = false;
    this.render();
  }

  /** 重新读取可选的模型。返回 {stale}（结果已过期）、{warning}（选的模型没了）或 {error}。 */
  async refresh() {
    const epoch = this.epoch;
    const request = ++this.requestVersion;
    const isStale = () => epoch !== this.epoch || request !== this.requestVersion;
    try {
      const { data, legacy } = await this.#loadCatalog();
      if (isStale()) {
        return { stale: true };
      }
      const warning = this.selection.apply(data, legacy);
      if (warning) {
        this.warn(warning);
      }
      if (!this.selection.catalog.can_manage && this.nodes.dialog.open) {
        this.nodes.dialog.close();
      }
      this.render();
      return { warning };
    } catch (error) {
      if (isStale()) {
        return { stale: true };
      }
      const status = error.status ? `（HTTP ${error.status}）` : "";
      this.selection.error = `无法加载模型目录${status}。请重试或更新访问令牌；不会自动改用默认模型发送。`;
      if (this.nodes.dialog.open) {
        this.nodes.dialog.close();
      }
      this.render();
      return { error: this.selection.error };
    }
  }

  /** 服务端没有 /models（404，旧版本或关闭了模型选择）时，只提供启动模型。 */
  async #loadCatalog() {
    try {
      return { data: await this.api.models(), legacy: false };
    } catch (error) {
      if (error.status !== 404) {
        throw error;
      }
      const startupOnly = {
        default_id: "server-default", can_manage: false, persisted: false,
        models: [{ id: "server-default", label: "启动模型", model: "", read_only: true }],
      };
      return { data: startupOnly, legacy: true };
    }
  }

  /** 发送前确认所选模型仍然存在，返回要附带在消息里的字段。 */
  async prepareMessage() {
    const result = await this.refresh();
    if (result.stale) {
      throw new Error("模型目录已变更，请重新发送。");
    }
    if (result.warning) {
      throw new Error(result.warning);
    }
    return this.selection.request();
  }

  update(uiState) {
    this.uiState = uiState;
    this.render();
  }

  // ---------------------------------------------------------------- 显示

  render() {
    this.#renderSelect();
    this.#renderStatus();
    this.#renderBadge();
  }

  /** 下拉框：每个模型显示"名称（实际模型）"。运行中显示那次运行实际用的模型，并且不能改。 */
  #renderSelect() {
    const { select } = this.nodes;
    const { frozen, disabled } = this.uiState;
    const catalog = this.selection.catalog;
    const options = (catalog?.models ?? []).map(item => el("option", { value: item.id }, optionText(item, catalog.default_id)));
    if (frozen) {
      options.unshift(el("option", { value: RUNNING_OPTION }, `运行中 · ${this.#frozenModelName(frozen)}`));
    }
    select.replaceChildren(...options);
    select.value = frozen ? RUNNING_OPTION : this.selected || "";
    select.disabled = Boolean(frozen || disabled || this.selection.error);
    select.title = [...select.children].find(option => option.value === select.value)?.textContent ?? "";
  }

  #renderStatus() {
    const { manage, status, retry } = this.nodes;
    const catalog = this.selection.catalog;
    manage.hidden = !catalog?.can_manage || Boolean(this.selection.error);
    const legacyNote = this.selection.legacy ? "模型设置未启用，使用启动模型。" : "";
    status.textContent = this.selection.error || this.notice || legacyNote;
    status.hidden = !status.textContent;
    retry.hidden = !this.selection.error;
    retry.disabled = Boolean(this.uiState.disabled);
  }

  /** 侧栏：显示实际的模型名称，而不是"服务端默认模型"这样的配置名。 */
  #renderBadge() {
    const { badge } = this.nodes;
    const { frozen } = this.uiState;
    if (frozen) {
      badge.textContent = `运行中 · ${this.#frozenModelName(frozen)}`;
    } else {
      badge.textContent = this.selection.modelName(this.selected);
    }
    badge.title = `当前模型：${this.selection.label(this.selected)} · ${badge.textContent}`;
  }

  /** 运行用的是开始时的快照：设置里后来删除、改名了，也照实显示当时的模型。 */
  #frozenModelName(frozen) {
    return frozen.model || this.selection.label(frozen.modelId);
  }

  // ---------------------------------------------------------------- 设置对话框

  clearForm() {
    this.dialogVersion++;
    this.editing = null;
    const values = profileValues();
    for (const name of PROFILE_FIELDS.concat("api_key")) {
      const field = this.#field(name);
      field.value = values[name];
      field.disabled = false;
    }
    this.#field("clear_api_key").checked = false;
    this.nodes.keyState.textContent = "未设置";
    this.nodes.remove.hidden = true;
    // 不在新建或编辑时，表单收起，对话框里只有默认模型和列表
    this.nodes.form.hidden = true;
    this.#highlightEditing();
  }

  /** 把一个配置填进表单；不传表示新建。旧 key 不显示，只显示有没有。 */
  editProfile(profile) {
    this.clearForm();
    this.editing = profile?.id || null;
    const values = profileValues(profile);
    for (const name of PROFILE_FIELDS) {
      this.#field(name).value = values[name];
    }
    this.#field("id").disabled = Boolean(profile);
    this.nodes.keyState.textContent = values.has_api_key ? "已设置（不显示旧的 key）" : "未设置";
    this.nodes.remove.hidden = !profile;
    this.nodes.form.hidden = false;
    if (this.nodes.formTitle) {
      this.nodes.formTitle.textContent = profile ? `编辑：${profile.label}` : "新建模型";
    }
    this.#highlightEditing();
    this.#field(profile ? "label" : "id").focus?.();
  }

  /** 列表里正在编辑的那一项加上高亮。 */
  #highlightEditing() {
    for (const [card, id] of this.profileCards ?? []) {
      card.classList.toggle("editing", id === this.editing);
    }
  }

  async openSettings() {
    if (!this.selection.catalog?.can_manage || this.selection.error) {
      return;
    }
    this.clearForm();
    this.nodes.dialog.showModal();
    await this.loadSettings();
  }

  /** 读取完整配置。读取完成前表单不可编辑；读失败时保持不可编辑。 */
  async loadSettings() {
    const epoch = this.epoch;
    const version = this.dialogVersion;
    this.nodes.settingsStatus.textContent = "正在读取设置…";
    this.settingsLoaded = false;
    this.#setFormEnabled(false);
    try {
      const data = await this.api.modelSettings();
      if (epoch !== this.epoch || version !== this.dialogVersion || !this.nodes.dialog.open) {
        return;
      }
      if (!Array.isArray(data.profiles)) {
        throw new Error("设置格式错误");
      }
      this.profiles = data.profiles.filter(item => !RESERVED_IDS.has(item.id)).map(profileValues);
      this.#renderSettings(data);
      this.settingsLoaded = true;
      this.#setFormEnabled(true);
    } catch (error) {
      if (epoch !== this.epoch || version !== this.dialogVersion) {
        return;
      }
      const status = error.status ? `（HTTP ${error.status}）` : "";
      this.nodes.settingsStatus.textContent = `无法读取模型设置${status}，请关闭后重试。`;
      this.clearForm();
    }
  }

  #renderSettings(data) {
    this.nodes.storage.textContent = data.persisted
      ? "API key 只保存在服务器本地（不会返回给浏览器，也不存进浏览器）。保存配置不会调用模型，进行中的对话不受影响。"
      : "当前为内存模式：配置和 API key 在服务端重启后丢失。保存配置不会调用模型，进行中的对话不受影响。";
    this.#renderProfileList();
    this.nodes.defaultSelect.replaceChildren(...this.selection.catalog.models.map(item =>
      el("option", { value: item.id }, optionText(item, null))));
    this.nodes.defaultSelect.value = data.default_id;
    this.nodes.settingsStatus.textContent = "";
  }

  /** 每个配置一张卡片：名称、协议，下面一行是模型名称和 key 状态；点击编辑。没有配置时给一句提示。 */
  #renderProfileList() {
    /** 卡片 → 配置 ID，用来高亮正在编辑的那一项。 */
    this.profileCards = new Map();
    if (this.profiles.length === 0) {
      this.nodes.list.replaceChildren(el("div", { class: "model-empty" },
        "还没有自定义模型。点“新建”添加一个，例如 DeepSeek、通义千问或本机的 Ollama。"));
      return;
    }
    this.nodes.list.replaceChildren(...this.profiles.map(profile => {
      const card = el("button", {
        type: "button",
        class: "model-profile",
        onclick: () => {
          if (!this.busy) {
            this.editProfile(profile);
          }
        },
      },
      el("span", { class: "model-profile-main" },
        el("span", { class: "model-profile-label" }, profile.label),
        el("span", { class: "model-profile-type" }, TYPE_LABELS[profile.type] ?? profile.type)),
      el("span", { class: "model-profile-detail" },
        `${profile.model} · ${profile.has_api_key ? "已设置 key" : "未设置 key"}`));
      this.profileCards.set(card, profile.id);
      return card;
    }));
    this.#highlightEditing();
  }

  async save() {
    if (this.busy || this.nodes.fields.disabled) {
      return;
    }
    const values = Object.fromEntries(PROFILE_FIELDS.concat("api_key").map(name => [name, this.#field(name).value]));
    values.clear_api_key = this.#field("clear_api_key").checked;
    try {
      const { id, body } = profileRequest(values);
      const duplicate = !this.editing && this.selection.catalog.models.some(item => item.id === id);
      if (duplicate) {
        throw new Error("此 ID 已存在，请从列表编辑或更换 ID。");
      }
      this.#field("api_key").value = "";
      await this.mutate(() => this.api.saveModel(id, body));
    } catch (error) {
      this.nodes.settingsStatus.textContent = error.message;
    }
  }

  async remove() {
    if (!this.editing || this.busy || !confirm(`删除模型“${this.editing}”？正在运行的模型不受影响。`)) {
      return;
    }
    const id = this.editing;
    await this.mutate(() => this.api.deleteModel(id));
  }

  /**
   * 执行一次修改（保存、删除、设默认），成功后重新读取目录和设置（以服务端为准）。
   * 失败时不显示服务端返回的错误内容：里面可能回显了提交的 key。
   */
  async mutate(operation) {
    if (this.busy || !this.selection.catalog?.can_manage || this.selection.error) {
      return;
    }
    const epoch = this.epoch;
    this.busy = true;
    this.#setFormEnabled(false);
    this.#field("api_key").value = "";
    try {
      await operation();
      if (epoch !== this.epoch) {
        return;
      }
      this.clearForm();
      const result = await this.refresh();
      if (epoch !== this.epoch) {
        return;
      }
      if (result.error) {
        throw new Error("目录刷新失败");
      }
      if (this.nodes.dialog.open) {
        await this.loadSettings();
      }
      this.notify("模型设置已保存；已开始的运行不受影响。");
    } catch (error) {
      if (epoch !== this.epoch) {
        return;
      }
      const status = error.status ? `（HTTP ${error.status}）` : "";
      this.nodes.settingsStatus.textContent = `模型设置操作未完成${status}。请重新读取设置确认；密钥需重新输入。`;
    } finally {
      if (epoch === this.epoch) {
        this.busy = false;
        this.nodes.create.disabled = !this.settingsLoaded;
        // 失败后允许修正表单重试；没读到设置时保持不可编辑
        const canEdit = this.settingsLoaded && this.selection.catalog?.can_manage && !this.selection.error;
        if (canEdit) {
          this.nodes.fields.disabled = false;
          this.nodes.defaultFields.disabled = false;
        }
      }
    }
  }

  #setFormEnabled(enabled) {
    this.nodes.create.disabled = !enabled;
    this.nodes.fields.disabled = !enabled;
    this.nodes.defaultFields.disabled = !enabled;
  }

  #field(name) {
    return this.nodes.form.elements.namedItem(name);
  }
}

/** 下拉框里的一项："服务端默认模型（deepseek:deepseek-v4-pro）· 默认"。 */
function optionText(item, defaultId) {
  const realModel = item.model && item.model !== item.label ? `（${item.model}）` : "";
  const isDefault = item.id === defaultId ? " · 默认" : "";
  return `${item.label}${realModel}${isDefault}`;
}
