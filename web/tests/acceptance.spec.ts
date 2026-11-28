import { expect, test } from "@playwright/test";

const merchant = "https://merchant.shopwise.test/lamp?quote=refreshed";

test.use({
  launchOptions: process.env.SHOPWISE_CHROME
    ? { executablePath: process.env.SHOPWISE_CHROME }
    : {},
});

test("从货架对比商品、请助手核对和复价跳转", async ({ page, context }) => {
  const base = process.env.SHOPWISE_ACCEPT_WEB_URL;
  test.skip(!base, "Run via the ShopWise accept command");
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
  await expect(page.getByRole("heading", { name: "精选阅读灯" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "购物需求" })).toHaveCount(0);
  await page.getByLabel("商品价上限").fill("300");
  await page.getByRole("button", { name: "筛选" }).click();

  const cards = page.locator(".market-product");
  await expect(cards).toHaveCount(2, { timeout: 20000 });
  await expect(cards.filter({ hasText: "可调光阅读灯" })).toContainText(/约 ¥160\.00 · ECB/);
  await expect(cards.filter({ hasText: "经典阅读灯" })).toBeVisible();
  await expect(page.getByText("高价阅读灯")).toHaveCount(0);
  await cards.first().getByRole("button", { name: "加入对比" }).click();
  await expect(page.getByText("再选 1 款即可比较")).toBeVisible();
  await cards.nth(1).getByRole("button", { name: "加入对比" }).click();
  await page.getByRole("button", { name: "对比 2 款" }).click();
  const table = page.getByRole("table");
  await expect(table).toContainText("可调光阅读灯");
  await expect(table).toContainText("经典阅读灯");
  await expect(table.getByRole("row", { name: /运费与税费/ })).toContainText("待商家确认");

  await page.getByRole("button", { name: "帮我看差异" }).click();
  const assistant = page.getByRole("complementary", { name: "选购助手" });
  await expect(assistant.getByRole("list", { name: "助手参考的商品" }).getByRole("listitem")).toHaveCount(2);
  await assistant.getByLabel("购物需求").fill("哪款支持调光？");
  const response = page.waitForResponse(result => result.url().endsWith("/api/v1/assistant/turn"));
  await assistant.getByRole("button", { name: "发送" }).click();
  expect((await response).request().postDataJSON().references).toEqual([
    { provider: "shopify", variantId: "adjustable" },
    { provider: "shopify", variantId: "classic" },
  ]);
  await expect(assistant.locator(".answer-bubble")).toContainText("三级调光", { timeout: 20000 });
  await expect(assistant.locator(".answer-bubble")).toContainText("商家未提供可核实的调光规格");
  await assistant.getByRole("button", { name: "关闭选购助手" }).click();
  await expect(table.getByRole("columnheader", { name: /经典阅读灯/ })).toBeVisible();

  await table.getByRole("button", { name: "可调光阅读灯" }).click();
  const detail = page.getByRole("region", { name: "可调光阅读灯" });
  const lookup = page.waitForResponse(result => result.url().includes("/api/v1/discovery/shopify/lookup?"));
  await detail.getByRole("button", { name: "核对最新报价" }).click();
  expect((await lookup).ok()).toBe(true);
  const link = detail.getByRole("link", { name: /去 验收商家 购买/ });
  await expect(link).toHaveAttribute("href", merchant);
  await expect(detail).toContainText(/商品价已更新.*26\.00/);
  const [popup] = await Promise.all([page.waitForEvent("popup"), link.click()]);
  await expect(popup).toHaveURL(merchant);
  await expect(popup.getByRole("heading", { name: "商家跳转验收页" })).toBeVisible();
});
