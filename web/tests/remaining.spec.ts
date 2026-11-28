import { expect, test } from "@playwright/test";

test.use({launchOptions:process.env.SHOPWISE_CHROME?{executablePath:process.env.SHOPWISE_CHROME}:{}});
const base=process.env.SHOPWISE_WEB_URL||"http://127.0.0.1:20173";

test("verified cross-platform offers appear together",async({page})=>{
  const make=(provider:string)=>({provider,productId:"desk-light",variantId:provider+"-black",title:"Desk Light",
    variant:"black",source:provider+" merchant",url:"https://example.com/lamp",currency:"CNY",
    amountMinor:22300,price:223,observedAt:new Date().toISOString(),available:true,condition:"new"});
  await page.route("**/api/v1/discovery?*",route=>route.fulfill({json:{
    sources:["shopify","ebay"].map(provider=>({provider,status:"ok",offers:[make(provider)]})),
    matches:[{evidence:"GTIN",offers:["shopify/shopify-black","ebay/ebay-black"]}],
  }}));
  await page.goto(base);
  await page.getByRole("button",{name:"按关键词搜索"}).click();
  await page.getByLabel("商品关键词").fill("desk light");
  await page.getByRole("button",{name:"查找商品"}).click();
  await expect(page.getByRole("heading",{name:"跨平台同款"})).toBeVisible();
  await expect(page.getByText("商品条码一致")).toBeVisible();
  await expect(page.getByText(/ebay · ebay merchant ·/)).toBeVisible();
});

test("buyer submits a return for the selected order line",async({page})=>{
  const orderId="gid://shopify/Order/1", lineId="gid://shopify/LineItem/2";
  let submitted:Record<string,unknown>|undefined;
  await page.route("**/api/**",async(route)=>{
    const path=new URL(route.request().url()).pathname;
    const data:Record<string,unknown>={
      "/api/me":{username:"pilot",role:"BUYER"},
      "/api/csrf":{header:"X-CSRF-TOKEN",token:"fixture"},
      "/api/v1/merchant/binding":{enabled:true,bound:true},
      "/api/v1/merchant/orders":[{id:orderId,name:"订单 1",financial_status:"paid",fulfillments:[],
        returnableLines:[{id:lineId,title:"阅读灯",quantity:2}]}],
      "/api/v1/merchant/support":[],
      "/api/v1/merchant/products":[],
      "/api/v1/merchant/returns":submitted?[{id:"case",status:"REQUESTED",quantity:1,reason:"型号不合"}]:[],
    };
    if(path==="/api/v1/merchant/returns" && route.request().method()==="POST"){
      submitted=route.request().postDataJSON();
      await route.fulfill({json:{status:"REQUESTED"}});
    } else await route.fulfill({json:data[path]??{},status:path in data?200:404});
  });
  await page.goto(base+"/#/merchant");
  await page.getByRole("combobox",{name:"订单",exact:true}).selectOption(orderId);
  await page.getByRole("combobox",{name:"商品",exact:true}).selectOption(lineId);
  await page.getByLabel("原因",{exact:true}).fill("型号不合");
  await page.getByRole("button",{name:"确认提交退货申请"}).click();
  await expect(page.getByRole("heading",{name:"待审核"})).toBeVisible();
  expect(submitted).toMatchObject({orderId,lineId,quantity:1,reason:"型号不合"});
});
