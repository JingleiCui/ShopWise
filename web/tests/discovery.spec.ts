import { test, expect } from "@playwright/test";

test.use({ launchOptions: process.env.SHOPWISE_CHROME ? { executablePath: process.env.SHOPWISE_CHROME } : {} });
test("China destination, exact source and fresh merchant link", async ({ page }) => {
  const base = process.env.SHOPWISE_WEB_URL || "http://127.0.0.1:20173";
  await page.goto(base);
  await page.getByLabel("购物需求").fill("帮我找一盏能寄到杭州、到手不超过 300 元的阅读灯");
  await page.getByRole("button", { name: "查找商品" }).click();
  await expect(page.getByText(/收货地 杭州.*预算/)).toBeVisible({ timeout: 40000 });
  const result = page.locator("article.offer").first();
  await expect(result.getByText("商品价 · 税费和运费待确认")).toBeVisible();
  await result.getByRole("button", { name: "前往商家前核价" }).click();
  await expect(page.getByRole("link", { name: /核对并购买/ })).toHaveAttribute("href", /^https:\/\//, { timeout: 15000 });
  await page.getByLabel("购物需求").fill("把预算改成 500");
  await page.getByRole("button", { name: "查找商品" }).click();
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
