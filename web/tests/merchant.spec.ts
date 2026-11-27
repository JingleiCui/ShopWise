import { test, expect } from "@playwright/test";

test.use({launchOptions:process.env.SHOPWISE_CHROME?{executablePath:process.env.SHOPWISE_CHROME}:{}});
test("buyer reviews the quote before full checkout navigation",async({page})=>{
  const base=process.env.SHOPWISE_WEB_URL||"http://127.0.0.1:25173";
  await page.route("https://example.com/checkout",route=>route.fulfill({body:"Merchant checkout"}));
  await page.route("**/api/**",async(route)=>{
    const path=new URL(route.request().url()).pathname;
    const data:Record<string,unknown>={
      "/api/me":{username:"pilot",role:"BUYER"},
      "/api/csrf":{header:"X-CSRF-TOKEN",token:"fixture"},
      "/api/v1/merchant/binding":{enabled:true,bound:true},
      "/api/v1/merchant/orders":[],
      "/api/v1/merchant/support":[],
      "/api/v1/merchant/returns":[],
      "/api/v1/merchant/products":[{id:"gid://shopify/ProductVariant/17",product:"台灯",variant:"白色",amount:"223",currency:"CNY"}],
      "/api/v1/merchant/checkout":{id:"a-quote",cost:{totalAmount:{amount:"223",currencyCode:"CNY"}}},
      "/api/v1/merchant/checkout/a-quote/confirm":{checkoutUrl:"https://example.com/checkout"},
    };
    await route.fulfill({json:data[path]??{},status:path in data?200:404});
  });
  await page.goto(base+"/#/merchant");
  const confirm=page.getByRole("button",{name:"确认并到商家结账"});
  await expect(confirm).toHaveCount(0);
  await page.getByRole("spinbutton").fill("1");
  await page.getByRole("button",{name:"核对价格"}).click();
  await expect(page.getByText(/商家当前金额.*223/)).toBeVisible();
  await confirm.click();
  await expect(page).toHaveURL("https://example.com/checkout");
});
