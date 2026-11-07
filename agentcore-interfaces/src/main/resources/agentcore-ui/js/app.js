// 页面入口。运行的 DOM 与当前视图分开保存：切换会话不会丢掉事件或让审批卡片串到别的会话。
import { AgentApi, ApiError } from "./api.js";
import { LiveRun, renderHistory } from "./conversation.js";
import { ApprovalCard, noticeRow, userTurn } from "./components.js";
import { describeUsage, el, formatRelativeTime, toast } from "./dom.js";
import { fillIcons, icon } from "./icons.js";
import { ModelControls } from "./models.js";

const NARROW_SCREEN = matchMedia("(max-width: 800px)");
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_IMAGES = 4;
const IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);
const SUGGESTIONS = ["看看当前目录里有哪些文件", "总结一下 README 的内容", "帮我写一个 Python 脚本统计代码行数"];
const byId = id => document.getElementById(id);
const page = {
  app: byId("app"), list: byId("conversation-list"), model: byId("model-badge"),
  title: byId("conversation-title"), meta: byId("conversation-meta"),
  messages: byId("messages"), content: byId("messages-inner"),
  input: byId("composer-input"), attachments: byId("attachments"), hint: byId("composer-hint"),
  send: byId("send-button"), stop: byId("stop-button"), attach: byId("attach-button"),
  tokenDialog: byId("token-dialog"), tokenInput: byId("token-input"),
  search: byId("conversation-search"),
};
const api = new AgentApi(document.querySelector('meta[name="agentcore-api-base"]').content);
const state = {
  id: null, conversations: [], drafts: new Map(), images: [],
  run: null, externalRunning: false, externalModel: null, submitting: false, readingImages: false,
  ready: false, loading: false, stopping: false,
  // 每次导航/切换身份都增加版本号，过期的请求不能再覆盖当前页面。
  navigation: 0, identity: 0, listRequest: 0,
  pollTimer: null, tokenPrompt: null, followOutput: true,
};

const models = new ModelControls(api, {
  select: byId("model-select"), manage: byId("model-settings-button"), badge: page.model,
  status: byId("model-status"), retry: byId("model-retry"), dialog: byId("model-dialog"),
  close: byId("model-close"), create: byId("model-create"), form: byId("model-form"),
  fields: byId("model-fields"), remove: byId("model-delete"), keyState: byId("model-key-state"),
  defaultSelect: byId("model-default-select"), defaultFields: byId("model-default-fields"),
  setDefault: byId("model-default-save"), list: byId("model-profile-list"),
  storage: byId("model-storage"), settingsStatus: byId("model-settings-status"),
  formTitle: byId("model-form-title"), cancel: byId("model-cancel"),
}, toast, saveDraft);

fillIcons();
wireEvents();
setSidebarCollapsed(NARROW_SCREEN.matches);
showWelcome();
start();

// ------------------------------------------------------------------ 连接与身份

async function start() {
  const identity = state.identity;
  state.ready = false;
  updateControls();
  try {
    await api.health();
    if (identity !== state.identity) {
      return;
    }
    await models.refresh();
    if (identity !== state.identity) {
      return;
    }
    await refreshList();
    if (identity !== state.identity) {
      return;
    }
    state.ready = true;
    await openLocation();
  } catch (error) {
    if (identity !== state.identity) {
      return;
    }
    if (error instanceof ApiError && error.status === 401) {
      if (await askForToken("请填写有效的访问令牌。")) {
        await changeIdentity();
      }
    } else {
      page.model.textContent = "未连接";
      page.content.replaceChildren(noticeRow("error", connectionError(error)));
    }
  } finally {
    updateControls();
  }
}

function connectionError(error) {
  return error instanceof ApiError && error.status === 403
    ? "服务端拒绝访问。未配置令牌时仅允许本机请求；远程访问请配置 agentcore.web.token。"
    : `连接失败：${error.message}。可点击左下角的令牌按钮重新连接。`;
}

async function changeIdentity() {
  // 不保留上一身份的消息、草稿和待处理审批。
  state.identity++;
  state.navigation++;
  clearTimeout(state.pollTimer);
  state.id = null;
  state.run = null;
  state.drafts.clear();
  state.conversations = [];
  state.externalRunning = false;
  state.externalModel = null;
  models.reset();
  setComposer({ text: "", images: [], model_id: null });
  history.replaceState(null, "", location.pathname + location.search);
  renderList();
  showWelcome();
  await start();
}

function askForToken(reason) {
  if (state.tokenPrompt) {
    return state.tokenPrompt;
  }
  byId("token-reason").textContent = reason
    || "填写服务端访问令牌。仅保存在当前标签页的会话存储中；清空并保存可移除令牌。";
  page.tokenInput.value = api.token || "";
  page.tokenDialog.returnValue = "";
  state.tokenPrompt = new Promise(resolve => {
    page.tokenDialog.addEventListener("close", () => {
      const saved = page.tokenDialog.returnValue === "save";
      if (saved) {
        api.token = page.tokenInput.value.trim();
      }
      page.tokenInput.value = "";
      state.tokenPrompt = null;
      resolve(saved);
    }, { once: true });
  });
  page.tokenDialog.showModal();
  page.tokenInput.focus();
  return state.tokenPrompt;
}

// ------------------------------------------------------------------ 事件

function wireEvents() {
  page.search.addEventListener("input", renderList);
  byId("new-conversation").addEventListener("click", () => navigate(null));
  byId("collapse-sidebar").addEventListener("click", () => setSidebarCollapsed(true));
  byId("expand-sidebar").addEventListener("click", () => setSidebarCollapsed(false));
  byId("sidebar-backdrop").addEventListener("click", () => setSidebarCollapsed(true));
  byId("theme-button").addEventListener("click", toggleTheme);
  byId("refresh-conversation").addEventListener("click", () => openLocation());
  byId("token-button").addEventListener("click", async () => {
    if (state.run || state.submitting) {
      return toast("请先结束当前运行，再切换访问令牌。");
    }
    if (await askForToken()) {
      await changeIdentity();
    }
  });
  window.addEventListener("hashchange", openLocation);
  window.addEventListener("beforeunload", event => {
    if (state.run || page.input.value || state.images.length) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  byId("composer").addEventListener("submit", event => {
    event.preventDefault();
    submit();
  });
  page.input.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      submit();
    }
  });
  page.input.addEventListener("input", resizeComposer);
  page.input.addEventListener("paste", event => {
    const files = [...(event.clipboardData?.files || [])];
    if (files.length) {
      event.preventDefault();
      addImages(files);
    }
  });
  page.attach.addEventListener("click", () => byId("attach-input").click());
  byId("attach-input").addEventListener("change", event => {
    addImages([...event.target.files]);
    event.target.value = "";
  });
  page.stop.addEventListener("click", stopRun);
  page.messages.addEventListener("scroll", () => {
    state.followOutput = page.messages.scrollHeight - page.messages.scrollTop - page.messages.clientHeight < 120;
  }, { passive: true });
  page.content.addEventListener("click", event => {
    const button = event.target.closest("[data-copy-code]");
    if (button) {
      copyCode(button);
    }
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && NARROW_SCREEN.matches) {
      setSidebarCollapsed(true);
    }
  });
}

// ------------------------------------------------------------------ 会话列表与导航

async function refreshList() {
  const identity = state.identity;
  const request = ++state.listRequest;
  const list = await api.listConversations(200);
  if (identity !== state.identity || request !== state.listRequest) {
    return;
  }
  state.conversations = list;
  renderList();
}

/**
 * 侧栏的对话列表：按最后更新时间分成"今天 / 昨天 / 最近 7 天 / 更早"几组；搜索框里有字时只显示标题包含它的对话。
 */
function renderList() {
  const query = page.search.value.trim().toLowerCase();
  const visible = state.conversations.filter(item => !query || (item.title || "").toLowerCase().includes(query));
  const rows = [];
  let currentGroup = null;
  for (const item of visible) {
    const group = dateGroup(item.updated_at);
    if (group !== currentGroup) {
      rows.push(el("div", { class: "conversation-group" }, group));
      currentGroup = group;
    }
    rows.push(conversationRow(item));
  }
  if (visible.length === 0) {
    rows.push(el("div", { class: "conversation-empty" }, query ? "没有找到匹配的对话" : "还没有对话"));
  }
  page.list.replaceChildren(...rows);
}

function conversationRow(item) {
  const running = state.run?.id === item.id;
  const link = el("button", {
    type: "button", class: "conversation-link", onclick: () => navigate(item.id),
    "aria-current": item.id === state.id ? "page" : null,
  },
  running ? el("span", { class: "conversation-running", title: "运行中" }) : null,
  el("span", { class: "conversation-text" },
    el("span", { class: "conversation-title" }, item.title || "未命名对话"),
    el("span", { class: "conversation-time" }, formatRelativeTime(item.updated_at))));
  const remove = el("button", {
    type: "button", class: "icon-button conversation-delete", title: "删除对话", "aria-label": "删除对话",
    disabled: running,
    onclick: () => deleteConversation(item),
  }, icon("trash"));
  return el("div", { class: `conversation-item${item.id === state.id ? " active" : ""}` }, link, remove);
}

/** 按日历日（本地时区）分组，而不是按"24 小时之内"。 */
function dateGroup(isoTime) {
  const time = new Date(isoTime);
  if (Number.isNaN(time.getTime())) {
    return "更早";
  }
  const startOfToday = new Date();
  startOfToday.setHours(0, 0, 0, 0);
  const dayMillis = 24 * 60 * 60 * 1000;
  const daysAgo = Math.floor((startOfToday.getTime() - time.getTime()) / dayMillis) + 1;
  if (time >= startOfToday) {
    return "今天";
  }
  if (daysAgo <= 1) {
    return "昨天";
  }
  return daysAgo <= 7 ? "最近 7 天" : "更早";
}

function navigate(id) {
  if (state.submitting || state.readingImages) {
    return toast("请求正在提交，请稍候。");
  }
  history.pushState(null, "", id ? `#/c/${encodeURIComponent(id)}` : location.pathname + location.search);
  openLocation();
  if (NARROW_SCREEN.matches) {
    setSidebarCollapsed(true);
  }
}

async function openLocation() {
  if (state.submitting || state.readingImages) {
    history.replaceState(null, "", state.id ? `#/c/${encodeURIComponent(state.id)}` : location.pathname + location.search);
    return;
  }
  let id = null;
  try {
    const match = location.hash.match(/^#\/c\/(.+)$/);
    if (match) {
      id = decodeURIComponent(match[1]);
    }
  } catch (malformedLink) {
    return toast("会话链接格式不正确。");
  }
  saveDraft();
  clearTimeout(state.pollTimer);
  const navigation = ++state.navigation;
  state.id = id;
  state.externalRunning = false;
  state.externalModel = null;
  state.loading = false;
  setComposer(state.drafts.get(id) || { text: "", images: [], model_id: null });
  page.meta.textContent = "";
  renderList();
  if (!id) {
    showWelcome();
    updateControls();
    return;
  }
  if (state.run?.id === id) {
    page.title.textContent = state.run.title;
    page.content.replaceChildren(state.run.view);
    updateControls();
    scrollToBottom(true);
    return;
  }
  state.loading = true;
  page.title.textContent = "加载对话…";
  page.content.replaceChildren(noticeRow("info", "正在读取历史记录…"));
  updateControls();
  try {
    const detail = await api.getConversation(id);
    if (navigation !== state.navigation) {
      return;
    }
    const view = el("div", { class: "conversation-view" });
    renderHistory(view, detail.messages);
    page.content.replaceChildren(view);
    page.title.textContent = detail.title || "未命名对话";
    page.meta.textContent = describeUsage(detail.usage);
    state.externalRunning = detail.running;
    // 草稿的显式选择优先于历史；历史只消费服务端记录的 opaque ID。
    if (!state.drafts.get(id)?.model_id) {
      models.select(detail.metadata?.model_id || null);
    }
    state.externalModel = { modelId: detail.metadata?.model_id, model: detail.metadata?.model };
    if (detail.running) {
      view.append(noticeRow("info", "此会话正在运行。可处理待审批操作、插话或停止；结束后自动刷新历史。"));
      await showPendingApprovals(view, id, navigation);
      scheduleRunningCheck(id, navigation);
    }
    scrollToBottom(true);
  } catch (error) {
    if (navigation !== state.navigation) {
      return;
    }
    page.content.replaceChildren(noticeRow("error", error.message));
  } finally {
    if (navigation === state.navigation) {
      state.loading = false;
      updateControls();
    }
  }
}

async function showPendingApprovals(view, id, navigation) {
  const requests = await api.pendingApprovals(id);
  if (navigation !== state.navigation) {
    return;
  }
  const holder = el("div", { class: "pending-approvals" });
  for (const request of requests) {
    holder.append(new ApprovalCard(request, (decision, message) =>
      api.answerApproval(request.request_id, decision, message)).element);
  }
  view.querySelector(".pending-approvals")?.remove();
  view.append(holder);
  view.dataset.approvals = requests.map(request => request.request_id).join(",");
}

// 只轮询从别处开始的运行；本标签页自己的运行直接使用 SSE，不重复请求历史。
function scheduleRunningCheck(id, navigation) {
  if (navigation !== state.navigation) {
    return;
  }
  state.pollTimer = setTimeout(async () => {
    try {
      const detail = await api.getConversation(id);
      if (navigation !== state.navigation) {
        return;
      }
      if (!detail.running) {
        await openLocation();
        return;
      }
      const requests = await api.pendingApprovals(id);
      if (navigation !== state.navigation) {
        return;
      }
      const view = page.content.firstElementChild;
      const signature = requests.map(request => request.request_id).join(",");
      // 同一审批不重建，以保留用户正在输入的拒绝原因。
      if (view && view.dataset.approvals !== signature) {
        await showPendingApprovals(view, id, navigation);
      }
      scheduleRunningCheck(id, navigation);
    } catch (error) {
      if (navigation === state.navigation) {
        toast(`刷新暂停：${error.message}，可点击顶部刷新重试。`);
      }
    }
  }, 2000);
}

function showWelcome() {
  page.title.textContent = "新对话";
  page.meta.textContent = "";
  page.content.replaceChildren(el("div", { class: "empty-state" },
    el("span", { class: "welcome-mark", "aria-hidden": "true" }, "◇"),
    el("div", { class: "eyebrow" }, "AgentCore · 你的工作区助手"),
    el("h1", {}, "从一个想法开始"),
    el("p", {}, "读代码、整理文件、解决问题。工具操作由你确认。"),
    el("div", { class: "suggestions" }, SUGGESTIONS.map(text => el("button", {
      type: "button", class: "suggestion", onclick: () => {
        page.input.value = text;
        resizeComposer();
        page.input.focus();
      },
    }, text)))));
}

async function deleteConversation(item) {
  if (state.submitting || state.run?.id === item.id) {
    return;
  }
  if (!confirm(`删除“${item.title || "未命名对话"}”？删除后无法恢复。`)) {
    return;
  }
  try {
    await api.deleteConversation(item.id);
    state.drafts.delete(item.id);
    if (state.id === item.id) {
      navigate(null);
    }
    await refreshList();
  } catch (error) { toast(error.message); }
}

// ------------------------------------------------------------------ 发送与运行

async function submit() {
  if (!state.ready || state.loading || state.submitting || state.readingImages) {
    return;
  }
  const input = { text: page.input.value.trim(), images: [...state.images] };
  if (!input.text && !input.images.length) {
    return;
  }
  if (state.run?.id === state.id || state.externalRunning) {
    if (input.images.length) {
      return toast("插话暂不支持图片，图片和文字已保留，请在运行结束后发送。");
    }
    await steer(input);
    return;
  }
  if (state.run) {
    return toast("另一个会话正在运行，请先回到该会话停止或等待完成。");
  }
  state.submitting = true;
  updateControls();
  let id = state.id;
  try {
    // 发送前重新校验目录。删除模型时只提示并回退，不用回退后的模型静默发出这一条。
    Object.assign(input, await models.prepareMessage());
    if (!id) {
      id = await api.createConversation();
      state.drafts.delete(null);
      state.id = id;
      history.replaceState(null, "", `#/c/${encodeURIComponent(id)}`);
      page.content.replaceChildren(el("div", { class: "conversation-view" }));
      page.title.textContent = input.text.split("\n")[0].slice(0, 80) || "图片对话";
    }
    await runMessage(id, input);
  } catch (error) {
    // 创建会话/HTTP 校验失败前不清空输入，不需要用户重新输入。
    toast(`发送失败：${error.message}`);
  } finally {
    state.submitting = false;
    updateControls();
  }
}

async function runMessage(id, input) {
  const view = page.content.querySelector(".conversation-view") || el("div", { class: "conversation-view" });
  if (!view.isConnected) {
    view.append(...page.content.childNodes);
    page.content.append(view);
  }
  // 未被服务端接受的提交不是历史消息；再次提交时清掉旧的“忙碌/鉴权失败”提示。
  view.querySelectorAll(".submission-error").forEach(element => element.remove());
  const bubble = userTurn(input.text, input.images);
  view.append(bubble);
  const context = { id, view, title: page.title.textContent, accepted: false, live: null,
    modelId: input.model_id || "server-default", model: models.selection.label(models.selected) };
  const answerApproval = (requestId, decision, message) => api.answerApproval(requestId, decision, message);
  // 只有正在看这个对话时才跟着滚动；切到别的对话后，后台运行不打扰
  const followOutput = () => {
    if (state.id === id) {
      scrollToBottom();
    }
  };
  context.live = new LiveRun(view, answerApproval, followOutput);
  state.run = context;
  renderList();
  updateControls();
  scrollToBottom(true);
  try {
    await api.sendMessage(id, input, (name, data) => {
      if (name === "run_started" && typeof data.model === "string") {
        context.model = data.model;
        updateControls();
      }
      if (!context.accepted) {
        context.accepted = true;
        setComposer({ text: "", images: [] });
        state.drafts.delete(id);
        state.submitting = false;
        updateControls();
        page.input.focus({ preventScroll: true });
        refreshList().catch(error => toast(error.message));
      }
      // 即使暂时离开此会话，也继续更新独立的 DOM；返回时直接挂回，不会漏审批。
      context.live.handle(name, data);
    });
    if (!context.live.finished) {
      throw new Error("事件流提前结束；请刷新历史确认结果，不要直接重复发送。");
    }
  } catch (error) {
    if (!context.accepted) {
      bubble.remove();
    }
    context.live.close(describeRunError(error));
    if (!context.accepted) {
      view.lastElementChild?.classList.add("submission-error");
    }
  } finally {
    if (state.run === context) {
      state.run = null;
    }
    state.stopping = false;
    state.submitting = false;
    updateControls();
    renderList();
    await refreshList().catch(error => toast(error.message));
  }
}

function describeRunError(error) {
  if (error instanceof ApiError && error.status === 409) {
    return "会话仍在运行。输入已保留，请刷新状态后再发送。";
  }
  if (error instanceof ApiError && error.status === 401) {
    return "令牌失效，输入已保留。请通过左下角更新令牌。";
  }
  return `运行中断：${error.message}`;
}

async function steer(input) {
  const id = state.id;
  state.submitting = true;
  updateControls();
  try {
    if (await api.steer(id, input.text)) {
      const view = state.run?.id === id ? state.run.view : page.content;
      view.append(userTurn(input.text, [], "插话已排队"));
      setComposer({ text: "", images: [] });
      state.drafts.delete(id);
      scrollToBottom(true);
    } else {
      // 不自动重发：旧 SSE 可能还没收尾，自动开新运行会触发 409 或覆盖旧运行的状态。
      toast("运行正在收尾或已结束，文字已保留，请稍后点击发送。");
    }
  } catch (error) { toast(`插话未提交：${error.message}`); }
  finally { state.submitting = false; updateControls(); }
}

async function stopRun() {
  if (!state.id || state.stopping) {
    return;
  }
  state.stopping = true;
  updateControls();
  try {
    const cancelled = await api.cancel(state.id);
    toast(cancelled ? "已请求停止" : "运行已结束");
    if (!state.run && !cancelled) {
      await openLocation();
    }
  } catch (error) { toast(error.message); }
  finally { state.stopping = false; updateControls(); }
}

function updateControls() {
  const runningHere = (state.run && state.run.id === state.id) || state.externalRunning;
  const busy = state.submitting || state.readingImages;
  page.input.disabled = busy;
  page.send.disabled = !state.ready || state.loading || busy;
  page.attach.disabled = busy || Boolean(runningHere);
  page.stop.hidden = !runningHere;
  page.stop.disabled = state.stopping;
  page.hint.textContent = busy ? "正在提交…" : runningHere ? "运行中 · 发送文字可插话" : "Enter 发送 · Shift + Enter 换行";
  page.hint.classList.toggle("steering", Boolean(runningHere));
  byId("new-conversation").disabled = busy;
  byId("refresh-conversation").disabled = busy || !state.ready;
  byId("token-button").disabled = Boolean(state.run) || busy;
  models.update({ disabled: busy || state.loading || !state.ready,
    frozen: state.run || (state.externalRunning ? state.externalModel : null) });
}

// ------------------------------------------------------------------ 草稿与图片

function saveDraft() {
  state.drafts.set(state.id, { text: page.input.value, images: [...state.images], model_id: models.selected });
}

function setComposer(draft) {
  page.input.value = draft.text;
  state.images = [...draft.images];
  if (Object.hasOwn(draft, "model_id")) {
    models.select(draft.model_id);
  }
  renderAttachments();
  resizeComposer();
}

function resizeComposer() {
  page.input.style.height = "auto";
  page.input.style.height = `${Math.min(page.input.scrollHeight, 240)}px`;
}

async function addImages(files) {
  if (state.readingImages || state.submitting) {
    return;
  }
  if (state.externalRunning || state.run?.id === state.id) {
    return toast("运行中的插话暂不支持图片。");
  }
  state.readingImages = true;
  updateControls();
  try {
    for (const file of files) {
      if (!IMAGE_TYPES.has(file.type)) {
        toast("仅支持 PNG、JPEG、GIF、WebP 图片。");
        continue;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        toast("每张图片最多 5 MB。");
        continue;
      }
      if (state.images.length >= MAX_IMAGES) {
        toast("每条消息最多 4 张图片。");
        break;
      }
      const url = await readImage(file);
      state.images.push({ media_type: file.type, data: url.slice(url.indexOf(",") + 1) });
    }
    renderAttachments();
  } catch (error) { toast(`读取图片失败：${error.message}`); }
  finally { state.readingImages = false; updateControls(); }
}

function readImage(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

function renderAttachments() {
  page.attachments.hidden = !state.images.length;
  page.attachments.replaceChildren(...state.images.map((image, index) => el("div", { class: "attachment" },
    el("img", { src: `data:${image.media_type};base64,${image.data}`, alt: "待发送的图片" }),
    el("button", { type: "button", "aria-label": "移除图片", onclick: () => {
      if (state.submitting) {
        return;
      }
      state.images.splice(index, 1);
      renderAttachments();
    } }, "×"))));
}

// ------------------------------------------------------------------ 显示辅助

function scrollToBottom(force = false) {
  if (force) {
    state.followOutput = true;
  }
  if (state.followOutput) {
    requestAnimationFrame(() => { page.messages.scrollTop = page.messages.scrollHeight; });
  }
}

function setSidebarCollapsed(collapsed) {
  page.app.classList.toggle("sidebar-collapsed", collapsed);
  byId("expand-sidebar").hidden = !collapsed;
  byId("sidebar-backdrop").hidden = collapsed;
}

function toggleTheme() {
  const current = document.documentElement.dataset.theme || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  const next = current === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem("agentcore.theme", next); } catch { /* 存储不可用时仍可切换 */ }
}

async function copyCode(button) {
  const text = button.closest(".code-block")?.querySelector("code")?.textContent || "";
  try { await navigator.clipboard.writeText(text); button.textContent = "已复制"; }
  catch { button.textContent = "请手动选择复制"; }
  setTimeout(() => { button.textContent = "复制"; }, 1500);
}
