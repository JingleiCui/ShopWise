# ShopWise 发布与验收

## 启动与部署

Windows、macOS 本地操作见 [双平台开发](development.md)。生产使用 Linux 镜像，分别以 commerce-api、agent-service 作为构建上下文。Java 使用 JDBC 共享 HTTP 会话和数据库级会话锁。试点仍按单副本配额控制开始，上游配额验证通过后再扩容。前端运行 npm ci 与 npm run build，将 web/dist 上传静态托管。CDN 同源反代 /api、/webhooks 到 Java，禁用这些路径的缓存，对 SSE 关闭缓冲。Agent 仅开放给 Java。Java 上游连接使用受控的私有网段。若私有服务名不同于 commerce-api，需要通过 COMMERCE_HOSTS 将实际服务名加入允许列表。

在托管 PostgreSQL 建业务库和独立检查点 schema，Flyway 权限只给业务迁移和应用所需角色。Agent 的 DATABASE_URL 配置到独立角色与 schema。为生产配置 DB_URL、DB_USER、DB_PASSWORD、SERVICE_KEY、AGENT_URL、COMMERCE_URL。运行生产 Java profile，设置真实 Shopify Profile 和 Catalog Client ID/Secret。未核实生产外部接口时关闭对应来源。使用服务器端秘密管理注入凭据。

## 发布批次

- M1 的匿名目录请求仅用于开发和验证。生产使用登记后的 Shopify Agent Profile 与凭据。先在部署地域验证流量容量、税运费文案及站点合规，再开放邀请用户。
- M2 的配置包括 SHOPIFY_PILOT_SHOP、SHOPIFY_WEBHOOK_SECRET、SHOPIFY_STOREFRONT_TOKEN、SHOPIFY_CART_ENCRYPTION_KEY。当前代码提供合作店报价、确认后取得托管结账链接、签名订单事件与归属查询。merchant_binding 必须来自已验证的 Shopify Customer Account 身份。买家绑定使用 Customer Account OAuth 与 PKCE，配置 SHOPIFY_CUSTOMER_CLIENT_ID、SHOPIFY_CUSTOMER_AUTHORIZE_URL、SHOPIFY_CUSTOMER_TOKEN_URL、SHOPIFY_CUSTOMER_GRAPHQL_URL 与 SHOPIFY_CUSTOMER_REDIRECT_URL，且商家需要在 Shopify 登记回调。可配置 SHOPIFY_ADMIN_TOKEN 启用按更新时间增量对账。本地客服工单支持买家登记和授权店员回复。退货按已发货订单行申请，客服审核并确认收货。商家在 Shopify 后台执行退款，ShopWise 核对签名通知或 Admin API 成功交易。当前自动核对支持单订单行、同一币种的成功交易；包含多行、退运费或退税费的商家退款交由人工核对。生产商家验收完成前保持 M2 关闭。签约后先用开发店测试，再验证正式店的已支付订单、两省收货、对账和退货。
- M3 默认关闭。沙箱凭据仅用于契约验证。获准生产后配置 EBAY_ENABLED、EBAY_SANDBOX=false 与正式密钥，并验证中国配送、商品匹配和限流。同款分组采用有效 GTIN 或结构化品牌、MPN 和一致规格，证据不足的候选可手工比较。

平台开通步骤见 [Shopify 与 eBay 上线授权](platform-access.md)。

## 回归与恢复

- Java 集成测试使用独立的 PostgreSQL 17，并设置 SHOPWISE_DB_TESTS=true。Python 使用 uv run --frozen python -m pytest 和 uv run --frozen python evaluate.py。前端运行 npm run build。Playwright 的 tests/discovery.spec.ts 可对已启动的本机栈和真实目录跑浏览器验收。
- GitHub Actions 提供 Windows x64、macOS arm64 和 macOS x64 启停及跨平台数据库恢复矩阵。CI 通过后保留运行记录。
- 已在本地按 20 并发验证线上入口，详见 [负载测试](load-test.md)。首发前补齐 200 条标注评测、授权后的预发压测、真实试点订单、数据库恢复演练及生产脱敏遥测。出现订单归属错误、重复商业动作或凭据泄露时关闭对应交易入口，保留事件用于核对。
