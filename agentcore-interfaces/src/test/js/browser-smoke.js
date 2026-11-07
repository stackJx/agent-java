// Playwright function for the local fake-model + bootstrap server described in docs/web-ui.md.
// Run with the browser tool's run_code filename, or load this function in a Playwright harness.
// Does not use cloud models. The only approved command is printf from fake-model.py.
async (page) => {
  const errors = [];
  const onError = error => errors.push(error.message);
  page.on("pageerror", onError);
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  const send = async text => {
    await page.getByRole("textbox", { name: "消息输入框" }).fill(text);
    await page.getByRole("button", { name: "发送", exact: true }).click();
  };
  const waitIdle = async () => {
    await page.waitForFunction(() => document.querySelector("#stop-button").hidden);
    await page.getByRole("button", { name: "发送", exact: true }).waitFor({ state: "visible" });
  };
  const navigateHash = async hash => {
    await page.evaluate(value => { location.hash = value; }, hash);
    await page.waitForFunction(() => !document.querySelector("#send-button").disabled);
  };
  try {
    await page.goto("http://127.0.0.1:18888/");
    await page.waitForFunction(() => !document.querySelector("#send-button").disabled);
    await send("你好，测试 Markdown");
    await page.locator(".run-summary").filter({ hasText: "完成" }).waitFor();
    await waitIdle();
    check(await page.locator(".markdown h2").count() === 1, "streamed reply was duplicated");
    check((await page.locator(".code-block code").textContent()).includes("System.out.println"), "code not rendered");
    const firstHash = await page.evaluate(() => location.hash);

    // Switching away/back must preserve the live DOM and its cancellation control.
    await send("慢速测试");
    await page.locator(".markdown").filter({ hasText: "慢速测试" }).waitFor();
    await page.getByRole("button", { name: "新对话", exact: true }).click();
    await page.getByRole("heading", { name: "从一个想法开始" }).waitFor();
    check(!(await page.locator("#messages-inner").textContent()).includes("慢速测试"), "background output leaked into new view");
    await navigateHash(firstHash);
    await page.getByRole("button", { name: "停止", exact: true }).waitFor();
    await send("请记住插话测试");
    await page.getByText("插话已排队", { exact: true }).waitFor();
    await page.getByRole("button", { name: "停止", exact: true }).click();
    await page.locator(".run-summary").filter({ hasText: "已取消" }).waitFor();
    await waitIdle();

    // Permission prompt survives navigating away and back.
    await send("审批测试");
    await page.getByRole("group", { name: "需要确认的操作" }).waitFor();
    await page.getByRole("button", { name: "新对话", exact: true }).click();
    await page.getByRole("heading", { name: "从一个想法开始" }).waitFor();
    await navigateHash(firstHash);
    await page.getByRole("button", { name: "允许", exact: true }).click();
    await page.locator(".markdown").filter({ hasText: "AGENTCORE_UI_APPROVED" }).waitFor();
    await waitIdle();

    await send("再次审批，测试拒绝");
    const activeApproval = page.locator(".approval-actions:not([hidden])");
    await activeApproval.getByPlaceholder("拒绝原因（可选，会告诉模型）").fill("测试拒绝，不执行");
    await activeApproval.getByRole("button", { name: "拒绝", exact: true }).click();
    await page.waitForFunction(() => {
      const cards = [...document.querySelectorAll(".approval-result")];
      return cards.some(card => card.textContent.includes("已拒绝"));
    });
    await waitIdle();

    // Reload restores saved messages instead of temporary stream fragments.
    await page.reload();
    await page.waitForFunction(() => !document.querySelector("#send-button").disabled);
    check((await page.locator("#messages-inner").textContent()).includes("AGENTCORE_UI_APPROVED"), "history not restored");

    // Failed create and conflict must not discard a draft or submit twice.
    await page.getByRole("button", { name: "新对话", exact: true }).click();
    const createPattern = "**/api/agent/conversations";
    await page.route(createPattern, route => route.request().method() === "POST"
      ? route.fulfill({ status: 503, contentType: "application/json", body: '{"error":"fixture create failure"}' })
      : route.continue());
    await send("失败后保留的草稿");
    await page.waitForFunction(() => !document.querySelector("#send-button").disabled);
    check(await page.locator("#composer-input").inputValue() === "失败后保留的草稿", "create error lost draft");
    await page.unroute(createPattern);
    const messagePattern = "**/api/agent/conversations/*/messages";
    await page.route(messagePattern, route => route.fulfill({ status: 409, contentType: "application/json", body: '{"error":"fixture busy"}' }));
    await send("冲突后保留的草稿");
    await page.locator(".notice").filter({ hasText: "会话仍在运行" }).waitFor();
    check(await page.locator("#composer-input").inputValue() === "冲突后保留的草稿", "conflict lost draft");
    await page.unroute(messagePattern);
    await send("恢复后正常发送");
    await page.locator(".run-summary").filter({ hasText: "完成" }).waitFor();
    await waitIdle();
    check(await page.locator(".submission-error").count() === 0, "obsolete submission error remained after retry");

    // Narrow layout must not overflow, and the drawer remains dismissible.
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("button", { name: "收起侧栏", exact: true }).click();
    await page.getByRole("button", { name: "展开侧栏", exact: true }).click();
    await page.getByRole("button", { name: "关闭会话侧栏", exact: true }).click({ position: { x: 350, y: 300 } });
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "mobile horizontal overflow");
    check(errors.length === 0, `browser errors: ${errors.join("; ")}`);
    return { passed: true, checks: ["stream", "markdown", "navigation", "steering", "cancel", "allow", "deny", "reload", "draft on failure", "409", "mobile"], firstHash };
  } finally {
    page.off("pageerror", onError);
  }
}
