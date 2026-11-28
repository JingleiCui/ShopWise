# ShopWise 本地开发

Windows x64 在 PowerShell 使用 `./shopwise.cmd`，macOS Intel 与 Apple Silicon 使用 `./shopwise`。两个入口都以脚本所在位置定位项目，支持含中文和空格的路径。PyCharm 也可以运行 `scripts/dev.py`，把同样的命令和选项放入参数栏。

## 启动

安装 Python 3.9+、JDK 17+、Node.js 22+（带 npm）、uv，以及已启动的 Docker Desktop 和 Compose v2。uv 会为 Agent 项目选择 Python 3.12。首次构建需要网络以获取锁文件指定的依赖。

| 任务 | Windows | macOS |
| --- | --- | --- |
| 查看环境和端口 | `./shopwise.cmd doctor` | `./shopwise doctor` |
| 构建 Java、创建 venv、安装并构建网页 | `./shopwise.cmd init` | `./shopwise init` |
| 前台启动并等待三个健康检查 | `./shopwise.cmd start` | `./shopwise start` |
| 在另一个终端查看状态 | `./shopwise.cmd status` | `./shopwise status` |
| 停止本次启动的应用进程 | `./shopwise.cmd stop` | `./shopwise stop` |

打开 <http://127.0.0.1:15173>。Java API、Agent 和 PostgreSQL 默认端口依次为 18081、18082、15439。首次 `start` 缺少构建产物时会自动执行 `init`；拉取代码或切换分支后执行 `init` 更新构建产物。Ctrl+C 或 `stop` 会关闭应用进程，保留数据库容器与数据卷。再次启动可继续使用此前的数据。单个服务失败时，查看 `.local/run/<项目名>/commerce.log`、`agent.log` 与 `web.log`。

## 本机配置

把根目录 `.env.example` 复制为 `.env.local`，按需编辑。命令行选项优先，其次是调用进程的环境变量、`.env.local`、内置默认值。文件使用 UTF-8、每行一个 `KEY=value`；支持整行注释与单、双引号包裹值。Shell 不执行这个文件。

例如 `./shopwise start --project shopwise2 --db-port 25439 --api-port 28081 --agent-port 28082 --web-port 25173`。同一实例的后续命令使用相同的 `--project`。给不同实例指定不同项目名与全部端口。端口被其他程序占用时，启动命令会报告冲突。控制命令用由操作系统释放的项目锁和本次运行的随机标识识别监督进程，端口和历史 PID 仅用于诊断。

默认使用 Compose。已安装 PostgreSQL 17 的机器可使用 `--db-mode external` 并准备本机数据库 `shopwise`、同名角色（默认开发密码 `shopwise`）；`--db-port` 会同时设置 Java、Python 客户端的默认连接。自定义库名或连接时，同时提供匹配的 `DB_URL`（JDBC）、`DATABASE_URL`（PostgreSQL URI）、`DB_USER` 和 `DB_PASSWORD`。显式服务 URL 和数据库 URL 会覆盖从端口派生的默认 URL。建议把自定义连接统一放在 `.env.local` 并在切换环境时重新核对。迁移外部本地库另需 `pg_dump` 和 `pg_restore`，客户端主版本应与服务端一致。

已有 PostgreSQL 17 工具且没有 Docker 时，可在 `.env.local` 设置 `SHOPWISE_DB_MODE=native` 和 `SHOPWISE_PG_BIN`。启动器会在 `.local/postgres/<项目名>` 初始化独立开发库，通过环回地址和密码连接默认的 `postgres` 库。首次启动前可设置 `DB_USER`、`DB_PASSWORD`；已初始化的开发库沿用原有角色和密码。常规 `stop` 保留数据库进程与本地数据。原生模式同样需要客户端工具才可执行导出或导入。

工具放在非标准安装路径时，可以在同一文件设置 `JAVA_HOME`、`SHOPWISE_UV`（uv 可执行文件）、`SHOPWISE_UV_PYTHON`（Python 3.12 可执行文件）和 `SHOPWISE_NPM_CLI`（用 Node 执行的 npm-cli.js）。本机绝对路径和数据库密码只保存在 `.env.local`。

## 免费导购演示

Windows 在 PowerShell 执行 `.\shopwise.cmd demo`，macOS 在终端执行 `./shopwise demo`。打开 <http://127.0.0.1:25174/>。使用相同入口执行 `demo-status` 或 `demo-stop`，也可以在启动终端按 Ctrl+C 停止应用。

演示固定使用匿名 Shopify 目录和离线 Agent，采用独立的本地 PostgreSQL、项目名和端口。安装 PostgreSQL 17 原生工具即可自动启动数据库，或者使用 Docker Compose。首次运行会构建缺失的应用依赖。指定数据库方式可加 `--db-mode native` 或 `--db-mode compose`。演示不读取日常开发环境中的 Shopify、eBay 或模型凭据。

输入“帮我找一盏能寄到杭州、到手不超过 300 元的阅读灯”，再输入“只看有货的”和“比较第 1 和第 2 款”。按页面编号选商品，用“前往商家前核价”刷新链接。目录按中国大陆筛选，杭州具体地址、运费及进口税费在商家页确认。

应用启动后自动刷新 ECB 汇率，并把有效汇率写入 `.local/run/shopwise-demo/ecb-rates.xml`。网络异常时沿用五天内的缓存，包括重启后的缓存。超过有效期或缺少对应币种时，页面标记为预算待核对。[ECB 每日汇率 XML](https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml)

## 跨机转移开发数据

在源、目标两边使用同一 Git 版本与 PostgreSQL 主版本。先停止正在运行的 ShopWise。以下命令使用 macOS 入口，Windows 将首项替换为 `./shopwise.cmd`。

1. 源机执行 `./shopwise export "shopwise-data.swdb"`，用安全渠道把归档送到目标机器。
2. 目标机选择一个空的开发库或先导出当前库备份。执行 `./shopwise import "shopwise-data.swdb" --confirm-import`，然后启动应用核对商品、订单和会话。

归档包含 PostgreSQL 的逻辑备份、格式版本和 SHA-256 校验。它可能含有用户对话和本地业务记录，应作为私有数据保管，仅导入自己信任的备份。导入先验证完整性，再通过单事务恢复归档中的对象；失败会回滚。机器环境变量、模型密钥、依赖和运行日志由目标机单独配置。已有归档文件会被保护，第二次导出请另选文件名。

`seed` 运行开发数据库迁移与幂等种子导入。`reset --confirm-reset` 清空所选 Compose 项目的数据卷，适合确定要丢弃该实例数据时使用。外部 PostgreSQL 由其管理员维护。

## 验证

`python -m unittest discover -s scripts -p test_dev.py -v` 验证端口覆盖、中文路径、归档摘要、停止控制和外来进程隔离。GitHub Actions 的 Development environments 工作流在 Windows x64、macOS Intel 和 Apple Silicon 上分别构建服务、使用本机 PostgreSQL 执行 Java 数据库测试、验证启动/重启/停止、运行 Agent 离线评测，并将归档交给另一系统恢复。验收会在首轮导购写入真实 LangGraph 检查点，重启后复用同一浏览器会话更新预算，再验证归档能在另一系统恢复商品、检查点和 V15 表结构。云端失败时可下载服务日志定位进程。

2026-11-27 在原生 Windows x64 和 Ubuntu 24.04（WSL2）上验证了中文空格路径、两轮启停、会话恢复和 PostgreSQL 17 双向归档导入。两个恢复目标都查到 60 条种子商品、V15 迁移和 8 条导购检查点。macOS Intel 与 Apple Silicon 的状态以 GitHub Actions 实际运行结果为准。
