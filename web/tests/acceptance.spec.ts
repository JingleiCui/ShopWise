import { expect, test } from "@playwright/test";

const merchant = "https://merchant.shopwise.test/lamp?quote=refreshed";

test.use({
  launchOptions: process.env.SHOPWISE_CHROME
    ? { executablePath: process.env.SHOPWISE_CHROME }
    : {},
});

test("杭州 ¥300 比价、核价和商家跳转", async ({ page, context }) => {
  const base = process.env.SHOPWISE_ACCEPT_WEB_URL;
  if (!base) test.skip(true, "Run via the ShopWise accept command");
  await context.route("**/*", async route => {
    const url = route.request().url();
    if (url === merchant) {
      await route.fulfill({ contentType: "text/html; charset=utf-8", body: "<h1>商家跳转验收页</h1>" });
    } else if (new URL(url).origin === base) {
      await route.continue();
    } else {
      await route.abort();
    }
  });

  await page.goto(base!);
  const search = async (message: string) => {
    await page.getByLabel("购物需求").fill(message);
    await page.getByRole("button", { name: "查找商品" }).click();
  };
  await test.step("预算及 ECB 参考价", async () => {
    await search("帮我找一盏能寄到杭州、到手不超过 300 元的阅读灯");
    await expect(page.getByText("收货地 杭州 · 预算 ¥300")).toBeVisible({ timeout: 20000 });
    const cards = page.locator("section").filter({ has: page.getByRole("heading", { name: "查询结果", exact: true }) }).locator("article.offer");
    await expect(cards).toHaveCount(2);
    await expect(cards.filter({ hasText: "可调光阅读灯" })).toContainText(/约 ¥160\.00 · ECB/);
    await expect(cards.filter({ hasText: "经典阅读灯" })).toBeVisible();
    await expect(page.getByText("高价阅读灯")).toHaveCount(0);
    await expect(cards.first()).toContainText("商品价 · 税费和运费待确认");
  });
  await test.step("用第二轮对话比价", async () => {
    await search("比较第 1 和第 2 款");
    const table = page.getByRole("table");
    await expect(table).toBeVisible({ timeout: 15000 });
    await expect(table).toContainText("可调光阅读灯");
    await expect(table).toContainText("经典阅读灯");
    await expect(table).toContainText("运费与税费待确认");
  });
  await test.step("重新核价并打开更新后的商家链接", async () => {
    const lookup = page.waitForResponse(response => response.url().includes("/api/v1/discovery/shopify/lookup?"));
    await page.locator("article.offer").filter({ hasText: "可调光阅读灯" })
      .getByRole("button", { name: "前往商家前核价" }).click();
    expect((await lookup).ok()).toBe(true);
    const link = page.getByRole("link", { name: /核对并购买/ });
    await expect(link).toHaveAttribute("href", merchant);
    await expect(page.locator(".handoff")).toContainText(/26\.00/);
    const [popup] = await Promise.all([page.waitForEvent("popup"), link.click()]);
    await expect(popup).toHaveURL(merchant);
    await expect(popup.getByRole("heading", { name: "商家跳转验收页" })).toBeVisible();
  });
});
