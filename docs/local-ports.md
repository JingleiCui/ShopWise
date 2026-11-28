# 本地项目固定端口

适用于 macOS 和 Windows 上的自有项目。表中为宿主机 TCP 端口。容器内继续使用标准端口，生产环境由部署配置管理。

| 项目 | 宿主机端口 |
| --- | --- |
| ShopWise | Web 20173；Java 20174；Agent 18082；PostgreSQL 15439。免费演示 Web 25174、Java 28083、Agent 28084、PostgreSQL 26439。 |
| ScholarShip | Web 15173；容器网关 18081；API 18080；PostgreSQL 15432；Redis 16379；Qdrant 16333。 |
| TripPal | 应用 8808；Vite 8809；MySQL 13316；Redis 16389；Elasticsearch 19220；Mailpit SMTP/UI 11026/18026。 |
| AuditBot | Web 与 API 共用 18870；Vite 开发及预览 18872。 |
| CanvasEditor | React/Rspack 开发服务器 15017。 |
| FaceOcclusion | Flask 开发服务器 18871。 |
| medicalProject | 面瘫标注服务 8000；NME 服务 8001；API_RPC 本地服务 8002；dispatch 本地回调样例 29090。 |
| enterprise_knowledge_rag_v2 | 知识服务 8796；评测 8797；恢复验证 8798；管理 MySQL 13306 和 Elasticsearch 9200。使用共用 Ollama 11434。 |
| 旧 travel_agent 路径 | 旧容器使用 TripPal 当前的 Compose 项目名 `travel-agent` 和相同的基础设施映射。旧路径已撤下，登记为 TripPal 的同一套依赖。 |
| 共用 Ollama | 11434。多个项目复用同一服务。 |

## 归属依据

ScholarShip 在 2026-11-13 的 Git 提交 `0635734` 已登记网页 15173 和容器网关 18081。ShopWise 2026-11-27 的初始提交 `6eea3cb` 后继使用这两个数字，所以由 ShopWise 迁到 20173 和 20174。退役项目释放各自专用端口。留下的企业知识库 V2 延续此前实际使用的 MySQL 13306 和 Elasticsearch 9200，并管理这两个依赖。

## 分配与变更

机器可读版本见 [local-ports.json](local-ports.json)。Mac 在项目根目录运行 `./shopwise ports check`，Windows 运行 `.\shopwise.cmd ports check`。若其他项目检出在自定义目录，可通过环境变量 `SHOPWISE_PROJECTS_ROOT` 指向项目父目录。固定端口只登记监听服务，反向代理的目标和共享依赖沿用对应所有者的登记。

1. 新项目先查表，再核对宿主机监听与 Docker 已发布端口。macOS 使用 `lsof -nP -iTCP -sTCP:LISTEN`，Windows 使用 `Get-NetTCPConnection -State Listen` 和 `netsh int ipv4 show excludedportrange protocol=tcp`。运行时避免自动递增端口。
2. 寻常开发使用版本控制中的项目默认值。跨机配置文件只保存该机器的凭据、路径或显式部署参数。ShopWise 旧本机覆盖 `SHOPWISE_WEB_PORT=15173` 和 `PORT=18081` 应更新为 `20173` 和 `20174`。拉取更新后，检查旧本机配置与 IDE 启动参数是否仍覆盖端口。新建端口优先在 20000–29999 中选择未登记且可绑定的值。
3. 每个项目同时维护启动器、前端代理、服务端和 Compose 映射、测试与 `AGENTS.md`。调整端口须同步更新总表及项目说明。临时验收可以动态申请端口，实际端口写日志。
4. Compose 项目名及数据卷按项目隔离。共用依赖明确标注拥有者。端口冲突只报告现有占用，应由服务所有者决定是否停止或迁移。
