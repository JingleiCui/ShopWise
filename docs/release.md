# ShopWise 发布与验收

## 启动与部署

Windows、macOS 本地操作见 [双平台开发](development.md)。生产使用 Linux 镜像，分别以 commerce-api、agent-service 作为构建上下文。Java 使用 JDBC 共享 HTTP 会话和数据库级会话锁。试点按 Java 和 Agent 各单副本开始。多副本发布前验收目录总配额、数据库连接预算与检查点清理协调。Java 会话锁使用独立连接池，每个 Java 实例最多 24 条锁连接，另计业务连接池和 Agent 检查点连接池。前端运行 npm ci 与 npm run build，将 web/dist 上传静态托管。CDN 同源反代 /api、/webhooks 到 Java，禁用这些路径的缓存，对 SSE 关闭缓冲。Agent 仅开放给 Java。容器探针使用 Java 的 `/api/health/live` 和 `/api/health/ready`、Agent 的 `/health/live` 和 `/health/ready`。两个就绪端点检查各自的 PostgreSQL。Java 上游连接使用受控的私有网段。若私有服务名不同于 commerce-api，需要通过 COMMERCE_HOSTS 将实际服务名加入允许列表。

在托管 PostgreSQL 建业务库和独立检查点 schema，Flyway 权限只给业务迁移和应用所需角色。Agent 的 DATABASE_URL 配置到独立角色与 schema。为生产配置 DB_URL、DB_USER、DB_PASSWORD、SERVICE_KEY、AGENT_URL、COMMERCE_URL。运行生产 Java profile，设置真实 Shopify Profile 和 Catalog Client ID/Secret。未核实生产外部接口时关闭对应来源。使用服务器端秘密管理注入凭据。

## 发布批次

- M1 的匿名目录请求仅用于开发和验证。生产使用登记后的 Shopify Agent Profile 与凭据。先在部署地域验证流量容量、税运费文案及站点合规，再开放邀请用户。
- M2 的配置包括 SHOPIFY_PILOT_SHOP、SHOPIFY_WEBHOOK_SECRET、SHOPIFY_STOREFRONT_TOKEN、SHOPIFY_CART_ENCRYPTION_KEY。当前代码提供合作店报价、确认后取得托管结账链接、签名订单事件与归属查询。merchant_binding 必须来自已验证的 Shopify Customer Account 身份。买家绑定使用 Customer Account OAuth 与 PKCE，配置 SHOPIFY_CUSTOMER_CLIENT_ID、SHOPIFY_CUSTOMER_AUTHORIZE_URL、SHOPIFY_CUSTOMER_TOKEN_URL、SHOPIFY_CUSTOMER_GRAPHQL_URL 与 SHOPIFY_CUSTOMER_REDIRECT_URL，且商家需要在 Shopify 登记回调。可配置 SHOPIFY_ADMIN_TOKEN 启用按更新时间增量对账。本地客服工单支持买家登记和授权店员回复。退货按已发货订单行申请，客服审核并确认收货。商家在 Shopify 后台执行退款，ShopWise 核对签名通知或 Admin API 成功交易。当前自动核对支持单订单行、同一币种的成功交易；包含多行、退运费或退税费的商家退款交由人工核对。生产商家验收完成前保持 M2 关闭。签约后先用开发店测试，再验证正式店的已支付订单、两省收货、对账和退货。
- M3 默认关闭。沙箱凭据仅用于契约验证。获准生产后配置 EBAY_ENABLED、EBAY_SANDBOX=false 与正式密钥，并验证中国配送、商品匹配和限流。同款分组采用有效 GTIN 或结构化品牌、MPN 和一致规格，证据不足的候选可手工比较。

合作店报价请求按店铺、买家与请求 ID 持久化。相同内容可重放有效报价。Shopify 响应丢失时标记为待核实，避免同一请求再次调用 `cartCreate`。运行人员可按 `merchant_checkout_request` 查询 `PENDING` 和 `UNKNOWN` 请求的店铺与创建时间。核价只创建商家购物车，订单与收款在商家结账完成。退款投影仍按单订单行验收；超出当前轮询页的发货明细依赖完整 Webhook 或人工核查。

平台开通步骤见 [Shopify 与 eBay 上线授权](platform-access.md)。

## 回归与恢复

- Java 集成测试使用独立 PostgreSQL 17，设置 `SHOPWISE_DB_TESTS=true`。Agent 在 `agent-service` 运行 `uv run --frozen pytest -q`、`uv run --frozen python evaluate.py` 与 `uv run --frozen python evaluate_discovery.py`。导购评测含 200 个由人工定义条件和预期组成的组合场景。配置百炼密钥后可加 `--live --report ../.local/discovery-live.json` 验证模型，模型回退计为该场景失败。在线评测发送人工构造的输入并继续使用固定目录数据。
- Linux 发布检查搭建双来源测试栈，运行浏览器回归、OpenAPI 校验、负载门槛、前端依赖审计、生产镜像构建与 Trivy 扫描。2026-11-27 在 Ubuntu 24.04（WSL2）完成本地复验。Java 使用 Spring Boot 4.0.10 和 Tomcat 11.0.29，Agent 使用 Alpine 上的 Python 3.12。uv 仅用于构建阶段。发布时仍要使用当时的漏洞库扫描。
- GitHub Actions 提供 Windows x64、macOS arm64 和 macOS x64 启停及跨平台数据库恢复矩阵。CI 通过后保留运行记录。
- 使用请求 ID 关联 Java 和 Agent 的导购日志，按降级类型、时延、模型及 token 用量排查。当前 Agent 定时清理 24 小时未活动的导购检查点。匿名检查点只保留筛选约束及候选引用。应用级结构化日志已接通，云端采集、告警和完整 OpenTelemetry 链路仍需预发验证。
- 首发前完成百炼在线评测、真实平台授权及限额测试、正式店订单与退款核对、预发负载测试以及生产库恢复演练。出现订单归属错误、重复商业动作或凭据泄露时关闭对应交易入口，保留事件用于核对。详见 [负载测试](load-test.md)。

## 数据库恢复演练

在独立预发库使用托管 PostgreSQL 时间点恢复，选取故意中断前的目标时间。记录最后一笔可核验订单、事件和检查点的时间，核对实际数据缺口。重接预发服务并验证就绪端点、登录、导购、订单归属及 Webhook 重放。记录演练起止与验证数据，发布门槛为 RPO ≤15 分钟、RTO ≤2 小时。应用回退保留新增 Flyway 表，先关闭受影响交易入口，再切回上一版镜像。
