# ShopWise 上线前本地负载测试

2026-11-27 在 Windows 实测。浏览器入口经过 Vite 代理，调用 Java API、Python LangGraph 及 PostgreSQL 17。Agent 使用离线路由，目录使用每次 80 ms 的确定性测试数据。直搜每 300 ms 发起一次，共 60 次。导购分 3 轮，每轮 20 个已单独领取 CSRF 凭据的新会话同时发起。直搜必须有商品，导购必须返回杭州、预算条件下的预期报价；双来源还须返回正确的 GTIN 同款分组。判定同时要求全部请求成功及 P95 达标。

| 场景 | 直搜成功 / P95 ≤500 ms | 导购成功 / P95 ≤8 s |
|---|---:|---:|
| 调整前，单来源 | 60/60 · 131.64 ms | 12/60 · 1.29 s（仅成功请求） |
| 有界排队后，单来源 | 60/60 · 138.17 ms | 60/60 · 5.62 s |
| 双来源，同时运行浏览器回归 | 60/60 · 538.40 ms | 60/60 · 5.63 s |
| **双来源，独立复测** | **60/60 · 133.34 ms** | **60/60 · 5.62 s** |

同一试验里的成功率和延迟必须一起判断。旧限流在每轮 20 个请求中只放行 4 个。调整后按来源错开发送，等待超过 5 秒的请求会快速返回繁忙，保留整个请求的 8 秒上限。双来源初测与浏览器回归并跑时，直搜超出门槛 38.40 ms。独立复测通过。终版原始报告保存在本机 `.local/discovery-load-both-isolated.json`。

## 复跑

1. 准备本地独立 PostgreSQL，构建 Java、Agent 和前端。以 `SPRING_PROFILES_ACTIVE=load-fixture`、`AGENT_MODE=offline` 启动 ShopWise，双来源加 `SHOPWISE_LOAD_BOTH=true`。测试 Profile 使用环回网卡，不能与生产 Profile 同时启用。
2. 从项目根执行 `python scripts/load_discovery.py --port 25173 --both --report .local/discovery-load-both.json`。单来源省略 `--both`。跑基准时避免在同一个实例并行运行浏览器回归。
3. 核对 JSON 中的成功数、失败类型及 P95，不把空结果或限流计为成功。

测试证明本机服务链路及队列行为。真实 Shopify 与 eBay 额度、云区域网络、生产模型延迟和多副本总体负载需要在取得权限后继续验收。外部压测应使用供应商允许的流量。
