"""Local ShopWise acceptance, shared by the macOS and Windows launchers."""
from datetime import datetime, timezone
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
from threading import Event, Thread
import time
import traceback
from urllib.parse import urlencode
from urllib.request import ProxyHandler, build_opener
from uuid import uuid4

from dev import HOST, PORTS, ROOT, read_env
from port_registry import assignments, windows_exclusions

# Settings shared by both launchers. Tool locations may be overridden in .env.local.
START_TIMEOUT = 150
STOP_TIMEOUT = 45
BUILD_TIMEOUT = 900
BROWSER_TIMEOUT = 120
REPORT_ROOT = ROOT / ".local" / "accept"
CHILD_ENV_KEYS = set("""
    PATH HOME USER LOGNAME USERPROFILE HOMEDRIVE HOMEPATH SYSTEMROOT WINDIR COMSPEC
    PATHEXT TEMP TMP TMPDIR LOCALAPPDATA APPDATA PROGRAMFILES PROGRAMFILES(X86)
    PROGRAMW6432 PROGRAMDATA ALLUSERSPROFILE JAVA_HOME SHOPWISE_UV SHOPWISE_UV_PYTHON
    SHOPWISE_NPM_CLI SHOPWISE_CHROME PLAYWRIGHT_BROWSERS_PATH DOCKER_CONFIG
    DOCKER_CONTEXT SSL_CERT_FILE SSL_CERT_DIR NODE_EXTRA_CA_CERTS
""".split())
TOOL_SETTINGS = {
    "JAVA_HOME", "SHOPWISE_UV", "SHOPWISE_UV_PYTHON", "SHOPWISE_NPM_CLI", "SHOPWISE_CHROME"
}
HTTP = build_opener(ProxyHandler({}))


def free_ports():
    sockets = []
    _, reserved = assignments()
    excluded = windows_exclusions()
    try:
        for _ in range(100):
            sock = socket.socket()
            sock.bind((HOST, 0))
            port = sock.getsockname()[1]
            if port in reserved or any(lo <= port <= hi for lo, hi in excluded):
                sock.close()
                continue
            sockets.append(sock)
            if len(sockets) == len(PORTS):
                break
        if len(sockets) != len(PORTS):
            raise ValueError("无法分配足够的独立验收端口")
        return dict(zip(PORTS, (sock.getsockname()[1] for sock in sockets)))
    finally:
        for sock in sockets:
            sock.close()


def listening(port):
    with socket.socket() as sock:
        sock.settimeout(.3)
        return sock.connect_ex((HOST, port)) == 0


def wait_for(predicate, seconds, description, process=None):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"启动器提前退出（退出码 {process.returncode}）")
        if predicate():
            return
        time.sleep(.3)
    raise RuntimeError(f"等待超时：{description}")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class EcbFixture:
    def __init__(self, log):
        self.day = datetime.now(timezone.utc).date().isoformat()
        self.xml = (
            f"<Envelope><Cube time='{self.day}'><Cube currency='CNY' rate='8'/>"
            "<Cube currency='USD' rate='1.25'/></Cube></Envelope>"
        ).encode()
        self.failed = Event()
        self.fail_downloads = False
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status = 503 if fixture.fail_downloads else 200
                body = b"ECB fixture unavailable" if status == 503 else fixture.xml
                self.send_response(status)
                self.send_header("Content-Type", "application/xml" if status == 200 else "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                with log.open("a", encoding="utf-8") as output:
                    print(f"{datetime.now(timezone.utc).isoformat()} GET {self.path} {status}", file=output)
                if status == 503:
                    fixture.failed.set()

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer((HOST, 0), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return f"http://{HOST}:{self.server.server_port}/ecb.xml"

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


def copy_workspace(work):
    exclude = shutil.ignore_patterns(
        "target", ".venv", "node_modules", "dist", "__pycache__", ".pytest_cache",
        "test-results", ".env", ".env.local", ".DS_Store",
    )
    for name in ("commerce-api", "agent-service", "web", "scripts"):
        shutil.copytree(ROOT / name, work / name, ignore=exclude)
    shutil.copy2(ROOT / "compose.yaml", work)
    (work / "docs").mkdir()
    shutil.copy2(ROOT / "docs/local-ports.json", work / "docs/local-ports.json")


def chrome_path(env):
    if env.get("SHOPWISE_CHROME"):
        path = Path(env["SHOPWISE_CHROME"]).expanduser()
        require(path.is_file(), "SHOPWISE_CHROME 路径不存在")
        return str(path.resolve())
    if sys.platform == "darwin":
        candidates = [
            folder / "Google Chrome.app/Contents/MacOS/Google Chrome"
            for folder in (Path("/Applications"), Path.home() / "Applications")
        ]
    elif os.name == "nt":
        candidates = [
            Path(env[key]) / "Google/Chrome/Application/chrome.exe"
            for key in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA") if env.get(key)
        ]
    else:
        candidates = [Path(path) for name in ("google-chrome", "chromium") if (path := shutil.which(name))]
    return next((str(path) for path in candidates if path.is_file()), None)


class Acceptance:
    def __init__(self):
        self.project = "shopwise-accept-" + uuid4().hex[:12]
        self.report = REPORT_ROOT / self.project
        self.work = self.report / "work"
        self.run_dir = self.work / ".local" / "run" / self.project
        self.ports = free_ports()
        self.results = []
        self.child = None
        self.db_attempted = False
        self.report.mkdir(parents=True)
        local_tools = read_env(ROOT / ".env.local")
        self.env = {
            key.upper(): value for key, value in os.environ.items() if key.upper() in CHILD_ENV_KEYS
        }
        for key in TOOL_SETTINGS:
            if key in local_tools:
                self.env.setdefault(key, local_tools[key])
        # Only explicit tooling settings are imported. Application keys and service URLs are generated here.
        self.env.update(
            COMPOSE_PROJECT_NAME=self.project, SHOPWISE_DB_MODE="compose",
            SPRING_PROFILES_ACTIVE="load-fixture,acceptance", AGENT_MODE="offline",
            SHOPIFY_ENABLED="false", EBAY_ENABLED="false", SHOPWISE_DB_TESTS="false",
            SHOPWISE_ECB_RATES_CACHE=str(self.run_dir / "ecb-rates.xml"),
            SERVICE_KEY=uuid4().hex, PYTHONUTF8="1",
        )
        self.env.update({PORTS[name][0]: str(port) for name, port in self.ports.items()})
        self.base = f"http://{HOST}:{self.ports['web']}"
        self.env["SHOPWISE_ACCEPT_WEB_URL"] = self.base

    def launcher(self, action):
        return [sys.executable, str(self.work / "scripts/dev.py"), action]

    def command(self, label, args, *, cwd=None, timeout=60):
        logfile = self.report / (label + ".log")
        with logfile.open("ab") as log:
            completed = subprocess.run(args, cwd=cwd or self.work, env=self.env,
                                       stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        require(completed.returncode == 0, f"{label} 退出码 {completed.returncode}，日志 {logfile}")
        return logfile

    def state(self):
        result = subprocess.run(self.launcher("status"), cwd=self.work, env=self.env,
                                capture_output=True, check=True, timeout=12)
        return json.loads(result.stdout)

    def prepare(self):
        copy_workspace(self.work)
        context = self.command("docker-context", ["docker", "context", "show"]).read_text(encoding="utf-8").strip()
        endpoint = self.command("docker-endpoint", [
            "docker", "context", "inspect", context, "--format", "{{.Endpoints.docker.Host}}",
        ]).read_text(encoding="utf-8").strip()
        require(endpoint.startswith(("unix://", "npipe://")), "本地验收需要本机 Docker Socket")
        self.env["DOCKER_CONTEXT"] = context
        self.command("doctor", self.launcher("doctor"))
        chrome = chrome_path(self.env)
        if chrome:
            self.env["SHOPWISE_CHROME"] = chrome
        self.db_attempted = True
        self.command("build", self.launcher("init"), timeout=BUILD_TIMEOUT)
        if not chrome:
            self.command("browser-install", ["node", "node_modules/@playwright/test/cli.js", "install", "chromium"],
                         cwd=self.work / "web", timeout=BUILD_TIMEOUT)

    def start(self, round_number):
        with (self.report / f"start-{round_number}.log").open("wb") as log:
            self.child = subprocess.Popen(self.launcher("start"), cwd=self.work, env=self.env,
                                          stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        # Poll the atomically published state without competing for the launcher's ownership lock.
        state_file = self.run_dir / "state.json"

        def ready():
            try:
                return json.loads(state_file.read_text(encoding="utf-8"))["phase"] == "ready"
            except FileNotFoundError:
                return False

        wait_for(ready, START_TIMEOUT, "三个服务启动", self.child)
        state = self.state()
        require(set(state["services"]) == {"commerce", "agent", "web"}, "健康检查缺少服务")
        for name, service in state["services"].items():
            with HTTP.open(service["health"], timeout=5) as response:
                require(response.status == 200, f"服务健康检查失败：{service['health']}")
                if name == "agent":
                    require(json.load(response).get("mode") == "offline", "Agent 未进入离线模式")
        for url in (self.base + "/api/health/ready", f"http://{HOST}:{self.ports['agent']}/health/ready"):
            with HTTP.open(url, timeout=5) as response:
                require(response.status == 200, f"依赖未就绪：{url}")

    def stop(self, round_number):
        if self.child is not None:
            if self.child.poll() is None:
                self.command(f"stop-{round_number}", self.launcher("stop"), timeout=STOP_TIMEOUT)
            require(self.child.wait(timeout=10) == 0, "启动器异常退出")
            self.child = None
        require(self.state()["phase"] == "stopped", "状态未恢复为 stopped")
        wait_for(lambda: all(not listening(self.ports[name]) for name in ("api", "agent", "web")),
                 10, "应用端口释放")

    def cache(self):
        return (self.run_dir / "ecb-rates.xml").read_bytes()

    def check_reference(self, fixture):
        query = urlencode({"q": "reading lamp", "budget": 300})
        with HTTP.open(f"{self.base}/api/v1/discovery?{query}", timeout=8) as response:
            batch = json.load(response, parse_float=Decimal)
        require(batch["destination"] == "CN", "目录未保留中国大陆配送范围")
        offers = {item["variantId"]: item for source in batch["sources"] for item in source["offers"]}
        require(set(offers) == {"adjustable", "classic"}, f"预算过滤错误：{list(offers)}")
        require(offers["adjustable"]["referenceCny"] == {"amount": Decimal("160.00"), "date": fixture.day},
                f"人民币换算或汇率日期不符：{offers['adjustable'].get('referenceCny')}")
        require(self.cache() == fixture.xml, "缓存内容与首轮下载不一致")

    def browser(self):
        self.command("browser", ["node", "node_modules/@playwright/test/cli.js", "test",
                     "tests/acceptance.spec.ts", "--workers=1", "--reporter=list", "--trace=retain-on-failure",
                     "--output", str(self.report / "browser-artifacts")],
                     cwd=self.work / "web", timeout=BROWSER_TIMEOUT)

    def cleanup(self):
        problems = []
        if self.child is not None and self.child.poll() is None:
            try:
                self.stop("cleanup")
            except Exception as error:
                problems.append(str(error))
                self.child.terminate()
                try:
                    self.child.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    self.child.kill()
                    self.child.wait()
        if self.db_attempted:
            try:
                self.command("compose-cleanup", ["docker", "compose", "--project-name", self.project,
                             "-f", "compose.yaml", "down", "--volumes"], timeout=70)
            except Exception as error:
                problems.append(str(error))
        ports = [port for port in self.ports.values() if listening(port)] if self.db_attempted else []
        if ports:
            problems.append(f"本次分配的端口仍被占用：{ports}")
        for name in ("commerce.log", "web.log", "agent.log", "ecb-rates.xml"):
            source = self.run_dir / name
            if source.is_file():
                shutil.copy2(source, self.report / name)
        if self.work.is_dir() and not problems:
            shutil.rmtree(self.work)
        require(not problems, "；".join(problems))

    def check(self, name, function, skip=False):
        if skip:
            result = "SKIP"
        else:
            try:
                function()
                result = "PASS"
            except (Exception, KeyboardInterrupt) as error:
                with (self.report / "failures.log").open("a", encoding="utf-8") as log:
                    print(f"\n{name}", file=log)
                    traceback.print_exc(file=log)
                result = "FAIL"
                print(f"FAIL {name} · {error}", flush=True)
        self.results.append((result, name))
        if result != "FAIL":
            print(f"{result} {name}", flush=True)
        return result == "PASS"

    def summary(self):
        (self.report / "summary.txt").write_text(
            "\n".join(f"{result} {name}" for result, name in self.results) + "\n", encoding="utf-8")
        failed = sum(result == "FAIL" for result, _ in self.results)
        print(f"\n验收结果：{sum(result == 'PASS' for result, _ in self.results)} 通过，"
              f"{failed} 失败，{sum(result == 'SKIP' for result, _ in self.results)} 跳过")
        if failed:
            for log in sorted(self.report.glob("*.log")):
                lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
                if lines:
                    tail = 40 if log.name == "browser.log" else 14
                    print(f"\n{log.name}\n" + "\n".join(lines[-tail:]))
        print(f"完整日志：{self.report}")
        return int(failed > 0)


def accept(args):
    if (args.db_mode not in (None, "compose") or args.project or args.archive
            or any(getattr(args, name + "_port") is not None for name in PORTS)):
        raise ValueError("accept 自动分配独立 Compose 项目与端口")
    run = Acceptance()
    fixture = EcbFixture(run.report / "ecb.log")
    print(f"本地验收日志：{run.report}", flush=True)
    try:
        with fixture as url:
            run.env["SHOPWISE_ACCEPT_ECB_URL"] = url
            ready = run.check("本机环境和隔离构建", run.prepare)
            ready = run.check("首次启动与健康检查", lambda: run.start(1), skip=not ready)
            cached = run.check("ECB 下载并生成缓存", lambda: wait_for(
                lambda: (run.run_dir / "ecb-rates.xml").is_file() and run.cache() == fixture.xml,
                20, "ECB 缓存写入"), skip=not ready)
            stopped = run.check("首次停止并释放应用端口", lambda: run.stop(1), skip=not ready)
            fixture.fail_downloads = True
            ready = run.check("重启与健康检查", lambda: run.start(2), skip=not (cached and stopped))

            def fallback():
                require(fixture.failed.wait(20), "重启后未观察到 ECB HTTP 503")
                run.check_reference(fixture)

            cached = run.check("ECB 503 后从磁盘缓存换算", fallback, skip=not ready)
            run.check("杭州 ¥300 比价、核价和商家跳转", run.browser, skip=not cached)
            run.check("第二次停止并释放应用端口", lambda: run.stop(2), skip=not ready)
    finally:
        run.check("清理验收进程和临时数据库", run.cleanup)
    return run.summary()
