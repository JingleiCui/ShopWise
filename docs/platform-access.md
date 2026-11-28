# Shopify 与 eBay 上线授权

截至 2026-11-27。协作中可以确认账号角色、店铺域名和审批状态。Client Secret、Admin Token、Webhook Secret 等敏感值请由账号所有者直接放入部署密钥管理器。

## 个人项目的开通顺序

1. **先使用 Shopify Global Catalog 验证导购**。没有合作商家也能查询全球目录、比较商品并打开商家链接。匿名模式可用于低频开发，先保留 `SHOPIFY_ENABLED=true`；目录凭据暂留空。公开使用时，在 [Shopify Dev Dashboard](https://dev.shopify.com/dashboard) 进入 **Catalogs → Create API key**，按提示完成身份验证，核对 `read_global_api_catalog_search`。配置 `SHOPIFY_CATALOG_CLIENT_ID`、`SHOPIFY_CATALOG_CLIENT_SECRET` 和自有的 HTTPS `SHOPIFY_AGENT_PROFILE`。官方文档提供免 Key 访问和认证限额，已认证客户端默认每秒 5 次，未公布按调用收费的价目。只做跨店导购无需订阅 Shopify 商店套餐。[Global Catalog](https://shopify.dev/docs/agents/catalog/global-catalog) · [认证和限额](https://shopify.dev/docs/agents/profiles/auth-and-rate-limiting)
2. **需要验证真实模型时开通百炼**。进入 [华北 2（北京）API Key 页面](https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key)，开通模型服务，由主账号或业务空间管理员创建 ShopWise 专用 Key，可将模型范围限制为 `qwen-plus`。配置 `DASHSCOPE_API_KEY`、`QWEN_MODEL=qwen-plus`、`QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1` 和 `AGENT_MODE=auto`。北京常用输入档（≤128k）非思考原价为输入 ¥0.80、输出 ¥2.00 每百万 token。若控制台提供免费额度，可开启“免费额度用完即停”。本地规则模式可用 `AGENT_MODE=offline`。[API Key](https://help.aliyun.com/zh/model-studio/get-api-key/) · [模型定价](https://help.aliyun.com/zh/model-studio/qwen-plus) · [费用控制](https://help.aliyun.com/zh/model-studio/bill-query-and-cost-management)
3. **需要第二来源时开通 eBay Sandbox**。登录 [eBay Developers Program](https://developer.ebay.com/) 的 [Application Keys](https://developer.ebay.com/my/keys)，选择 **Sandbox → Create a keyset**，使用 App ID 与 Cert ID 配置 `EBAY_CLIENT_ID`、`EBAY_CLIENT_SECRET`、`EBAY_ENABLED=true`、`EBAY_SANDBOX=true`。开发者账号和沙箱免费。沙箱使用测试商品。正式 Browse API 按下文提交流量用途及资格审批，生产商业条件按 eBay 审批和协议确认。[开发者账号](https://www.developer.ebay.com/api-docs/creating-edp-account.html) · [沙箱说明](https://www.developer.ebay.com/api-docs/sandbox-environment.html) · [生产准入](https://developer.ebay.com/api-docs/buy/static/buy-requirements.html)
4. **需要演示交易时，自建 Shopify 开发店**。在 Dev Dashboard 选择 **Stores → Create store → Dev**，可生成测试商品并走测试付款。开发店免费，无需已有合作商家。真实付款、订单查询和售后仍需你自己的正式商店或获得合作店授权，届时再按下方权限表开通。自建正式店另有套餐和可能的支付费用。[Shopify 开发店](https://shopify.dev/docs/apps/build/stores/development-stores) · [商店套餐](https://www.shopify.com/pricing)

按 [沙箱联调指南](sandbox.md) 配置开发店或 eBay Sandbox。以下为正式权限细节。

## Shopify 跨店目录

1. 在 Shopify Dev Dashboard 的 **Catalogs** 完成组织或身份验证（如果面板提示），为 ShopWise 创建 API key，并确认 `read_global_api_catalog_search`。
2. 注入 `SHOPIFY_CATALOG_CLIENT_ID` 和 `SHOPIFY_CATALOG_CLIENT_SECRET`。服务端按需兑换并更新 JWT。无需手工轮换短效 JWT。
3. 提供可公开访问的 HTTPS Agent Profile URL，作为 `SHOPIFY_AGENT_PROFILE`。若 Profile 由项目部署，需要提供域名与部署访问权限。
4. 需要扩大流量时，由组织管理员向 Shopify 申请配额，并提供业务说明与预期流量。

[Catalog 凭据](https://shopify.dev/docs/apps/build/dev-dashboard/catalogs) · [认证与 Scope](https://shopify.dev/docs/agents/get-started/authentication) · [速率限制](https://shopify.dev/docs/agents/profiles/auth-and-rate-limiting)

## Shopify 合作店

| 你或商家需要开通 | 项目所用配置 |
|---|---|
| 可安装测试应用的开发店和管理员。正式验收另需允许安装 ShopWise 单店试点应用、能向中国大陆配送和收款的正式店 | `SHOPIFY_PILOT_SHOP`、店铺安装许可、买家测试账号与收货场景 |
| 启用 Headless Storefront 私有访问，允许商品目录和 Cart 读写。核对 `unauthenticated_read_product_listings`、`unauthenticated_read_checkouts`、`unauthenticated_write_checkouts` | `SHOPIFY_STOREFRONT_TOKEN` |
| 启用新版 Customer Accounts，提供支持 PKCE 的公共 Client ID，允许读取客户本人 ID，登记 HTTPS 回调 `/api/v1/merchant/binding/callback`。核对应用对应的 `customer_read_customers` | `SHOPIFY_CUSTOMER_CLIENT_ID`、`SHOPIFY_CUSTOMER_AUTHORIZE_URL`、`SHOPIFY_CUSTOMER_TOKEN_URL`、`SHOPIFY_CUSTOMER_GRAPHQL_URL`、`SHOPIFY_CUSTOMER_REDIRECT_URL` |
| 批准 Admin API `read_orders` 和所需 Protected Customer Data 权限。订阅 `orders/create`、`orders/updated`、`refunds/create`，投递到 `/webhooks/shopify`。若确需访问超出默认期限的历史订单，再核对 `read_all_orders` | `SHOPIFY_ADMIN_TOKEN`、`SHOPIFY_WEBHOOK_SECRET` |
| 指定可在 Shopify 后台实际退款的员工，准备测试订单、订单行、数量及 Refund GID | 项目配置本地店员权限与购物车加密密钥。此流程只读核对成功交易，无需 `write_orders` 或 `write_returns`。以后若让 ShopWise 直接向 Shopify 发起退货或退款，再开对应写权限及操作员权限，单独验收 |

[应用分发](https://shopify.dev/docs/apps/launch/distribution) · [Headless](https://shopify.dev/docs/storefronts/headless/overview) · [客户登录](https://shopify.dev/docs/storefronts/headless/building-with-the-customer-account-api/authenticate-customers) · [客户数据审批](https://shopify.dev/docs/apps/launch/protected-customer-data) · [权限表](https://shopify.dev/docs/api/usage/access-scopes) · [退款查询](https://shopify.dev/docs/api/admin-graphql/2026-10/queries/refund)

## eBay Browse API

1. 提供 eBay Developers Program 账号及 **Sandbox App ID / Cert ID**，用于沙箱验收。
2. 在 eBay Partner Network 提交 Buy API 申请，用途为面向中国大陆收货的商品发现、比较和跳转。获业务批准后提交 Developer Support 工单，完成页面、数据流及沙箱访问审查，并签署对应协议。
3. 提供获批的 **Production App ID / Cert ID**，对应 `EBAY_CLIENT_ID`、`EBAY_CLIENT_SECRET`。确认允许 Browse 搜索、单件详情和批量详情。项目通过 client credentials 换取 application token，Scope 为 `https://api.ebay.com/oauth/api_scope`。获批并验证后设置 `EBAY_ENABLED=true`、`EBAY_SANDBOX=false`。当前流程无需 eBay 下单和支付权限。
4. 激活生产 keyset 前，配置 Marketplace Account Deletion 通知，或在 eBay 确认当前数据保留方式适用后申请豁免。ShopWise 会话保留用于复查的商品引用，应先核对资格。

[Browse 生产准入](https://developer.ebay.com/api-docs/buy/static/buy-requirements.html) · [应用密钥](https://developer.ebay.com/api-docs/static/oauth-credentials.html) · [账户删除通知](https://developer.ebay.com/develop/guides/buy/marketplace-user-account-deletion)

凭据到位后，在预发低频核验两个来源的已知同款、两省配送、实付订单和商家发起退款后的状态与金额。双方平台允许的流量决定后续外部压测规模。
