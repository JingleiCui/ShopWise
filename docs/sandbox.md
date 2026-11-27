# Shopify 和 eBay 沙箱联调

先跑 `py -3 scripts/sandbox_smoke.py`。该命令在默认本机端口验证 Shopify Catalog 检索、规格复查和详情，再检查开发店和 eBay Sandbox 凭据。只打印结果、计数或缺少的变量名。配置凭据时，把值加到现有 `.env.local`，不要覆盖原来能启动项目的工具和数据库配置。

## Shopify 开发店

1. 登录 [Shopify Dev Dashboard](https://dev.shopify.com/dashboard)。从 **Stores → Create store → Dev** 创建开发店，记下完整的 `<店名>.myshopify.com`，写入 `SHOPIFY_PILOT_SHOP`。让拥有开发店及应用管理权限的成员执行后续操作。
2. 打开开发店后台。安装 **Headless** 销售渠道，然后进入 **Sales channels → Headless → Add storefront**。在 Storefront API 设置中复制 **Private access token** 到 `SHOPIFY_STOREFRONT_TOKEN`。在 **Manage API access** 开商品读取和 Cart 读写。创建或发布一个允许该渠道销售的测试商品。若市场或配送规则排除中国大陆，测试购物车可能无货或不可配送。[Headless 配置](https://shopify.dev/docs/storefronts/headless/overview) · [创建开发店](https://shopify.dev/docs/apps/build/stores/development-stores)
3. 在 **Settings → Customer accounts** 启用新版 Customer accounts。回到 **Headless → 选中 storefront → Customer Account API settings**，启用 **Public client**，复制 Client ID 到 `SHOPIFY_CUSTOMER_CLIENT_ID`。登记 `https://<预发域名>/api/v1/merchant/binding/callback`，同一完整地址配置为 `SHOPIFY_CUSTOMER_REDIRECT_URL`。从 `https://<店名>.myshopify.com/.well-known/openid-configuration` 读取授权端点与 Token 端点，分别填入 `SHOPIFY_CUSTOMER_AUTHORIZE_URL` 和 `SHOPIFY_CUSTOMER_TOKEN_URL`。从 `/.well-known/customer-account-api` 读取 Customer Account GraphQL URL 并填入 `SHOPIFY_CUSTOMER_GRAPHQL_URL`，选用接口版本 `2026-10`。[官方设置步骤](https://shopify.dev/docs/storefronts/headless/building-with-the-customer-account-api/getting-started) · [端点发现](https://shopify.dev/docs/api/customer/latest)
4. 在同一组织的 **Dev Dashboard → Apps** 创建或打开测试应用，用 **Versions → Create a version** 配置 Admin API `read_orders`，并安装到开发店。到应用 **Settings → Credentials**，将 Client ID、Client Secret 注入 `SHOPIFY_ADMIN_CLIENT_ID`、`SHOPIFY_ADMIN_CLIENT_SECRET`。服务端自动获取和更新 Admin Token。如果应用和店铺不在同一组织，使用该店授权安装流程取得的离线访问凭据，并设 `SHOPIFY_ADMIN_TOKEN`，这与 Catalog key、Customer Account Client ID 是不同凭据。[应用创建](https://shopify.dev/docs/apps/build/dev-dashboard/create-apps-using-dev-dashboard) · [同组织认证](https://shopify.dev/docs/apps/build/authentication-authorization/client-credentials-grant)
5. 用测试应用的 Webhook 订阅配置签入 `orders/create`、`orders/updated`、`refunds/create`，API 版本 `2026-10`，地址为 `https://<预发域名>/webhooks/shopify`。把签名用的应用密钥写入 `SHOPIFY_WEBHOOK_SECRET`。HTTPS 入口把 `/api` 和 `/webhooks` 反代到 Java，并保留前端同源。使用开发店 **Settings → Payments** 的测试网关或付款服务测试模式。结账时用和 ShopWise 绑定的同一 Shopify 买家账号，然后在 Shopify 管理后台完成试单发货和测试退款。[订阅文档](https://shopify.dev/docs/apps/build/webhooks/subscribe) · [测试付款](https://help.shopify.com/en/manual/checkout-settings/test-orders)

Shopify Global Catalog 已可匿名低频检索，不需要开发店账户。若单独验收有身份的目录访问，在 **Dev Dashboard → Catalogs → Get an API key** 创建目录专用密钥，配置 `SHOPIFY_CATALOG_CLIENT_ID`、`SHOPIFY_CATALOG_CLIENT_SECRET` 和公开的 `SHOPIFY_AGENT_PROFILE`。[Catalog 认证](https://shopify.dev/docs/agents/get-started/authentication)

### ShopWise 测试账号与入口

给 `SHOPWISE_SANDBOX_BUYER_PASSWORD`、`SHOPWISE_SANDBOX_STAFF_PASSWORD` 分别设不同的随机强密码。另设 `SHOPIFY_CART_ENCRYPTION_KEY`（项目自生成，至少 32 字符）。启用 Spring 的 `sandbox` Profile 后，程序在沙箱库创建 `sandbox-buyer` 和 `sandbox-staff`，开发店正式登录测试从 HTTPS 预发页面的「合作店订单」进入。

本机需要并行开发时，保持默认 ShopWise 实例不动。在单独 PowerShell 窗口设置 `$env:SPRING_PROFILES_ACTIVE = "sandbox"`，运行：

```powershell
.\shopwise.cmd start --project sandbox --db-port 25449 --api-port 28081 --agent-port 28082 --web-port 25173
```

HTTPS OAuth 跳转和平台回调仍需预发 HTTPS 入口。联调脚本可执行 `py -3 scripts/sandbox_smoke.py --port 25173`。停止用 `.\shopwise.cmd stop --project sandbox`。

## eBay Sandbox

1. 登录 [eBay Developers Program](https://developer.ebay.com/)，进入 **Your Account → Application Keys → Sandbox**。如无 keyset，创建 Sandbox keyset；从该环境复制 **App ID (Client ID)** 到 `EBAY_CLIENT_ID`、**Cert ID (Client Secret)** 到 `EBAY_CLIENT_SECRET`。Dev ID 不用于本项目的应用级 OAuth。[官方密钥说明](https://developer.ebay.com/api-docs/static/oauth-credentials.html)
2. 设置 `EBAY_SANDBOX=true` 和 `EBAY_ENABLED=true`，重启所用实例。先跑联调脚本。它验证 application token、同关键词的普通搜索和 CN 配送搜索、单件与批量详情。沙箱商品较少；若搜索为空，可提供一个 `EBAY_SANDBOX_ITEM_ID`，或用 Sandbox 卖家账号发布一条可配送到中国的测试刊登，再用标题关键词执行 `--query`。提供已知的有效 Sandbox Item ID 就能直接测试详情。
3. Sandbox 仅需开发者账号与 Sandbox keyset。正式 Browse 权限、EPN 和生产 keyset 审批留到生产接入。[eBay 沙箱与正式准入](https://developer.ebay.com/api-docs/buy/static/buy-requirements.html)

密钥直接写入忽略提交的 `.env.local` 或预发密钥管理器。在聊天里只需说明已配置哪些项目、开发店域名及供测试的 eBay 商品 ID。
