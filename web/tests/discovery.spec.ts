import { test, expect } from "@playwright/test";

test.use({ launchOptions: process.env.SHOPWISE_CHROME ? { executablePath: process.env.SHOPWISE_CHROME } : {} });

test("China destination, retained assistant context and fresh merchant link", async ({ page }) => {
  const base = process.env.SHOPWISE_WEB_URL || "http://127.0.0.1:20173";
  await page.goto(base);
  await page.getByRole("button", { name: "选购助手", exact: true }).click();
  await page.getByLabel("购物需求").fill("帮我找一盏能寄到杭州、商品价不超过 300 元的阅读灯");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.getByText(/收货地 杭州.*预算/)).toBeVisible({ timeout: 40000 });
  await page.locator(".assistant-offers button").first().click();
  const detail = page.locator(".market-detail");
  await expect(detail.getByText(/运费和进口税费待商家确认/)).toBeVisible();
  await detail.getByRole("button", { name: "核对最新报价" }).click();
  await expect(detail.getByRole("link", { name: /去 .* 购买/ })).toHaveAttribute("href", /^https:\/\//, { timeout: 15000 });
  await detail.getByRole("button", { name: "返回商品列表" }).click();
  await page.getByLabel("购物需求").fill("把预算改成 500");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.getByText(/收货地 杭州.*500/)).toBeVisible({ timeout: 40000 });
});

test("ready endpoint and direct search data boundary", async ({ request }) => {
  const base = process.env.SHOPWISE_WEB_URL || "http://127.0.0.1:20173";
  expect((await request.get(base + "/api/health/ready")).ok()).toBeTruthy();
  const search = base + "/api/v1/discovery";
  const privateQuery = await request.get(search, { params: { q: "desk lamp test@example.invalid" } });
  expect(privateQuery.status()).toBe(400);
  expect((await request.get(search, { params: { q: "036000291452" } })).ok()).toBeTruthy();
});

test("mobile keeps shopping usable when the assistant and an offer are unavailable", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const base = process.env.SHOPWISE_WEB_URL || "http://127.0.0.1:20173";
  const offer = {
    provider: "shopify", productId: "lamp", variantId: "lamp", title: "阅读灯", variant: "白色",
    source: "验收商家", url: "https://example.com/lamp", currency: "CNY", price: 188,
    available: true, observedAt: new Date().toISOString(),
  };
  await page.route("**/api/v1/discovery?*", route => route.fulfill({ json: { sources: [
    { provider: "shopify", status: "ok", offers: [offer] },
    { provider: "ebay", status: "timeout", offers: [] },
  ] } }));
  await page.route("**/api/v1/discovery/shopify/lookup?*", route => route.fulfill({ json: {
    provider: "shopify", status: "ok", offers: [{ ...offer, available: false }],
  } }));
  await page.route("**/api/csrf", route => route.fulfill({ json: { header: "X-CSRF-TOKEN", token: "fixture" } }));
  await page.route("**/api/v1/assistant/turn", route => route.fulfill({ status: 503, json: { message: "导购服务暂不可用" } }));
  await page.goto(base);
  await expect(page.getByText("eBay 查询超时")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "浏览分类" }).getByRole("button", { name: "阅读灯" })).toBeVisible();
  await expect(page.getByText("商家暂无商品图")).toBeVisible();
  await page.getByRole("button", { name: "查看商品", exact: true }).click();
  const detail = page.locator(".market-detail");
  await detail.getByRole("button", { name: "核对最新报价" }).click();
  await expect(detail.getByText("已核对 · 商家显示缺货")).toBeVisible();
  await expect(detail.getByRole("link", { name: /购买/ })).toHaveCount(0);
  await detail.getByRole("button", { name: "问问这款" }).click();
  const assistant = page.getByRole("complementary", { name: "选购助手" });
  await expect(assistant.getByLabel("购物需求")).toBeFocused();
  await assistant.getByRole("button", { name: "核对商品价格" }).click();
  await expect(assistant.getByRole("alert")).toContainText("导购服务暂不可用");
  await assistant.getByRole("button", { name: "关闭选购助手" }).click();
  await expect(detail.getByRole("button", { name: "问问这款" })).toBeFocused();
  await detail.getByRole("button", { name: "返回商品列表" }).click();
  await expect(page.getByRole("button", { name: "查看商品", exact: true })).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
});
