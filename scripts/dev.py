"""ShopWise's shared Windows/macOS local development entry point."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import urllib.request
from urllib.parse import quote, urlsplit

from dev_data import transfer
from port_registry import check_reservations, listener, windows_exclusions


ROOT = Path(__file__).resolve().parents[1]
HOST = "127.0.0.1"
WINDOWS = os.name == "nt"
PORTS = {"db": ("SHOPWISE_DB_PORT", 15439), "api": ("PORT", 20174),
         "agent": ("AGENT_PORT", 18082), "web": ("SHOPWISE_WEB_PORT", 20173)}
DEMO_PORTS = {"db": 26439, "api": 28083, "agent": 28084, "web": 25174}
DEMO_PROJECT = "shopwise-demo"
JAR = ROOT / "commerce-api/target/commerce-api-1.0.0.jar"
INTERPRETER = ROOT / "agent-service/.venv" / ("Scripts/python.exe" if WINDOWS else "bin/python")
VITE = ROOT / "web/node_modules/vite/bin/vite.js"
MAVEN = ROOT / "commerce-api" / ("mvnw.cmd" if WINDOWS else "mvnw")


def read_env(path):
    values = {}
    if not path.exists():
        return values
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError(f"{path.name}:{number} 需要 KEY=value")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def environment(args):
    env = {**read_env(ROOT / ".env.local"), **os.environ}
    demo = args.action.startswith("demo")
    if demo:
        configure_demo(args, env)
    for name, (key, default) in PORTS.items():
        value = getattr(args, name + "_port")
        port = int(value if value is not None else env.get(key, default))
        if not 1 <= port <= 65535:
            raise ValueError(f"{key} 需要 1–65535 之间的端口")
        env[key] = str(port)
    if len({env[key] for key, _ in PORTS.values()}) != len(PORTS):
        raise ValueError("数据库、API、Agent 和网页需要不同的端口")
    env["SHOPWISE_DB_MODE"] = args.db_mode or env.get("SHOPWISE_DB_MODE", "compose")
    if env["SHOPWISE_DB_MODE"] not in ("compose", "external", "native"):
        raise ValueError("SHOPWISE_DB_MODE 需要 compose、external 或 native")
    env["COMPOSE_PROJECT_NAME"] = args.project or env.get("COMPOSE_PROJECT_NAME", "shopwise")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", env["COMPOSE_PROJECT_NAME"]):
        raise ValueError("Compose 项目名仅支持小写字母、数字、下划线与连字符")
    if sys.platform == "darwin" and not env.get("JAVA_HOME"):
        result = subprocess.run(["/usr/libexec/java_home", "-v", "17+"], capture_output=True, text=True)
        if result.returncode == 0 and Path(result.stdout.strip(), "bin", "javac").is_file():
            env["JAVA_HOME"] = result.stdout.strip()
        else:
            for prefix in ("/opt/homebrew", "/usr/local"):
                brew = Path(prefix) / "opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
                if brew.exists():
                    env["JAVA_HOME"] = str(brew)
                    break
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    env.setdefault("DB_USER", "shopwise")
    env.setdefault("DB_PASSWORD", "shopwise")
    hostport = f"{HOST}:{env['SHOPWISE_DB_PORT']}"
    db_name = "postgres" if env["SHOPWISE_DB_MODE"] == "native" else "shopwise"
    env.setdefault("DB_URL", f"jdbc:postgresql://{hostport}/{db_name}")
    user = quote(env["DB_USER"], safe="")
    password = quote(env["DB_PASSWORD"], safe="")
    env.setdefault("DATABASE_URL", f"postgresql://{user}:{password}@{hostport}/{db_name}")
    env.setdefault("COMMERCE_URL", f"http://{HOST}:{env['PORT']}")
    env.setdefault("SHOPWISE_API_URL", env["COMMERCE_URL"])
    env.setdefault("AGENT_URL", f"http://{HOST}:{env['AGENT_PORT']}")
    env.setdefault("PYTHONUTF8", "1")
    return env


def configure_demo(args, env):
    prefixes = ("SHOPIFY_", "EBAY_", "DASHSCOPE_", "QWEN_", "SPRING_", "SERVER_", "SHOPWISE_SANDBOX_")
    settings = {"DB_URL", "DB_USER", "DB_PASSWORD", "DATABASE_URL", "COMMERCE_URL",
                "SHOPWISE_API_URL", "AGENT_URL", "SHOPWISE_ECB_RATES_FILE", "SHOPWISE_ENV",
                "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
    for key in list(env):
        if key.upper().startswith(prefixes) or key.upper() in settings:
            del env[key]
    for name, port in DEMO_PORTS.items():
        env[PORTS[name][0]] = str(port)
    env["COMPOSE_PROJECT_NAME"] = DEMO_PROJECT
    env["SHOPWISE_DB_MODE"] = demo_database_mode(env) if args.action == "demo" and not args.db_mode else "compose"
    env.update(
        AGENT_MODE="offline", SHOPIFY_ENABLED="true", EBAY_ENABLED="false", SHOPWISE_DB_TESTS="false",
        SERVICE_KEY=uuid.uuid4().hex, COMMERCE_HOSTS=HOST, BIND_ADDRESS=HOST, AGENT_HOST=HOST,
        SHOPWISE_ECB_RATES_CACHE=str(ROOT / ".local/run" / (args.project or DEMO_PROJECT) / "ecb-rates.xml"),
    )


def demo_database_mode(env):
    candidates = []
    if WINDOWS and env.get("ProgramFiles"):
        candidates.append(Path(env["ProgramFiles"]) / "PostgreSQL/17/bin")
    elif sys.platform == "darwin":
        candidates.extend(Path(prefix) / "opt/postgresql@17/bin" for prefix in ("/opt/homebrew", "/usr/local"))
    if not env.get("SHOPWISE_PG_BIN"):
        folder = next((p for p in candidates if (p / ("pg_ctl.exe" if WINDOWS else "pg_ctl")).is_file()), None)
        if folder:
            env["SHOPWISE_PG_BIN"] = str(folder)
    try:
        pg_tool("initdb", env)
        pg_tool("pg_ctl", env)
        return "native"
    except ValueError:
        return "compose"


def binary(name, env):
    candidate = env.get("SHOPWISE_UV", name) if name == "uv" else name
    executable = shutil.which(candidate, path=env.get("PATH", ""))
    if not executable:
        raise ValueError(f"找不到 {name}，请检查 PATH 并运行 doctor")
    return executable


def run(argv, env, cwd=ROOT, **kwargs):
    return subprocess.run([str(v) for v in argv], cwd=cwd, env=env, check=True, **kwargs)


def compose(env, *args):
    return [binary("docker", env), "compose", "--project-name", env["COMPOSE_PROJECT_NAME"],
            "-f", str(ROOT / "compose.yaml"), *args]


def database(env):
    if env["SHOPWISE_DB_MODE"] == "compose":
        run(compose(env, "up", "-d", "--wait", "db"), env)
    elif env["SHOPWISE_DB_MODE"] == "native":
        native_database(env)


def pg_tool(name, env):
    folder = env.get("SHOPWISE_PG_BIN")
    if not folder:
        return binary(name, env)
    executable = Path(folder) / (name + (".exe" if WINDOWS else ""))
    if not executable.is_file():
        raise ValueError(f"SHOPWISE_PG_BIN 中找不到 {executable.name}")
    return str(executable)


def native_database(env):
    port = env["SHOPWISE_DB_PORT"]
    directory = ROOT / ".local" / "postgres" / env["COMPOSE_PROJECT_NAME"]
    if directory.is_symlink() or directory.parent.is_symlink():
        raise ValueError("原生开发数据库目录不能使用符号链接")
    control = [pg_tool("pg_ctl", env), "-D", str(directory)]
    initialized = (directory / "PG_VERSION").is_file()
    if initialized:
        status = subprocess.run([*control, "status"], env=env, capture_output=True, timeout=10)
        if status.returncode == 0:
            pid_lines = (directory / "postmaster.pid").read_text(encoding="utf-8").splitlines()
            if len(pid_lines) < 4 or pid_lines[3] != port:
                raise ValueError("该项目的原生 PostgreSQL 正在使用其他端口")
            return
        if status.returncode != 3:
            raise ValueError("无法确认原生 PostgreSQL 的运行状态，请查看 postgresql.log")
    with socket.socket() as sock:
        sock.settimeout(.3)
        if sock.connect_ex((HOST, int(port))) == 0:
            raise ValueError(f"数据库端口 {port} 已被其他程序占用")
    if not initialized:
        directory.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as secret_folder:
            secret = Path(secret_folder) / "postgres-password"
            secret.write_text(env["DB_PASSWORD"] + "\n", encoding="utf-8")
            run([pg_tool("initdb", env), "-D", directory, "-U", env["DB_USER"], "--encoding=UTF8",
                 "--locale=C", "--auth=scram-sha-256", f"--pwfile={secret}"], env)
    log = directory / "postgresql.log"
    try:
        run([*control, "-l", log, "-o", f"-h {HOST} -p {port}", "-w", "-t", "30", "start"],
            env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=35)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as failure:
        raise ValueError(f"原生 PostgreSQL 启动失败，请查看 {log}") from failure


def npm_command(env):
    cli = env.get("SHOPWISE_NPM_CLI")
    if cli:
        if not Path(cli).is_file():
            raise ValueError("SHOPWISE_NPM_CLI 必须指向可读的 npm-cli.js")
        return [binary("node", env), cli]
    return [binary("npm.cmd" if WINDOWS else "npm", env)]


def init(env):
    database(env)
    run([MAVEN, "-q", "package"], env, ROOT / "commerce-api")
    python = env.get("SHOPWISE_UV_PYTHON", "3.12")
    run([binary("uv", env), "sync", "--frozen", "--python", python], env, ROOT / "agent-service")
    npm = npm_command(env)
    run([*npm, "ci", "--no-audit", "--no-fund"], env, ROOT / "web")
    run([*npm, "run", "build"], env, ROOT / "web")


def doctor(env):
    checks = [
        ("JDK 17+", ["javac", "-version"], r"javac (\d+)", 17),
        ("Java 17+", ["java", "-version"], r'version "(\d+)', 17),
        ("uv", ["uv", "--version"], r"uv (\d+)", 0),
        ("Node 22+", ["node", "--version"], r"v(\d+)", 22),
        ("npm", ["npm.cmd" if WINDOWS else "npm", "--version"], r"(\d+)", 1),
    ]
    if env["SHOPWISE_DB_MODE"] == "compose":
        checks += [("Docker daemon", ["docker", "info", "--format", "{{.ServerVersion}}"], r"(\d+)", 1),
                   ("Docker Compose v2", ["docker", "compose", "version", "--short"], r"(\d+)", 2)]
    elif env["SHOPWISE_DB_MODE"] == "native":
        checks += [(name, [name, "--version"], r"(\d+)", 17) for name in ("pg_ctl", "initdb")]
    else:
        print("INFO 导出或导入外部 PostgreSQL 时需安装 pg_dump 和 pg_restore")
    print(f"{platform.system()} {platform.machine()} / Python {platform.python_version()} / DB {env['SHOPWISE_DB_MODE']}")
    errors = []
    for label, argv, pattern, floor in checks:
        try:
            if label == "npm":
                command = [*npm_command(env), "--version"]
            elif label in ("pg_ctl", "initdb"):
                command = [pg_tool(argv[0], env), *argv[1:]]
            else:
                command = [binary(argv[0], env), *argv[1:]]
            result = run(command, env, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, errors="replace", timeout=12)
            match = re.search(pattern, result.stdout)
            if not match or int(match[1]) < floor:
                raise ValueError("版本不满足要求")
            print(f"OK   {label}: {result.stdout.strip().splitlines()[0]}")
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            errors.append(label)
            print(f"FAIL {label}: {error}")
    for name, (key, _) in PORTS.items():
        with socket.socket() as sock:
            sock.settimeout(.3)
            busy = sock.connect_ex((HOST, int(env[key]))) == 0
        print(f"PORT {name} {HOST}:{env[key]} {'占用中' if busy else '空闲'}")
    return 1 if errors else 0


class Busy(Exception):
    pass


@contextmanager
def ownership(path):
    """The OS releases the lock on exit, even if the supervisor crashes."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as handle:
        if path.stat().st_size == 0:
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if WINDOWS:
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (PermissionError, BlockingIOError) as error:
            raise Busy("同一项目已有运行中的 ShopWise 命令") from error
        yield


def is_busy(local):
    try:
        with ownership(local / "owner.lock"):
            return False
    except Busy:
        return True


def write_state(local, state):
    temporary = local / "state.tmp"
    temporary.write_text(json.dumps(state), encoding="utf-8")
    temporary.replace(local / "state.json")


def active_state(local):
    if not is_busy(local):
        return {}
    try:
        return json.loads((local / "state.json").read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {"phase": "busy"}


def stop(local):
    state = active_state(local)
    if not state:
        print("ShopWise 已停止")
        return
    token = state.get("token")
    if not token:
        raise Busy("当前正在执行初始化或数据维护，请在该命令结束后重试")
    (local / f"stop-{token}").touch()
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        if active_state(local).get("token") != token:
            print("ShopWise 已停止")
            return
        time.sleep(.2)
    raise ValueError("停止请求已送达，当前命令尚未退出，请查看状态和服务日志")


def terminate(children):
    for child in reversed(children):
        if child.poll() is not None:
            continue
        if WINDOWS:
            try:
                child.send_signal(signal.CTRL_BREAK_EVENT)
            except OSError:
                child.terminate()
        else:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    for child in children:
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            if WINDOWS:
                child.kill()
            else:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            child.wait()


def services_for(env, runtime_jar):
    return [
        ("commerce", [binary("java", env), "-jar", runtime_jar.name], runtime_jar.parent,
         f"http://{HOST}:{env['PORT']}/api/health"),
        ("agent", [str(INTERPRETER), "main.py"], ROOT / "agent-service",
         f"http://{HOST}:{env['AGENT_PORT']}/health"),
        ("web", [binary("node", env), str(VITE.relative_to(ROOT / "web")), "--host", HOST, "--port", env["SHOPWISE_WEB_PORT"],
                 "--strictPort"], ROOT / "web", f"http://{HOST}:{env['SHOPWISE_WEB_PORT']}"),
    ]


def serve(env, local, *, rebuild=False):
    token = uuid.uuid4().hex
    stop_file = local / f"stop-{token}"
    state = {"token": token, "phase": "starting", "services": {}}
    children = []
    def check():
        if stop_file.exists():
            raise KeyboardInterrupt
        for name, child in children:
            if (code := child.poll()) is not None:
                raise ValueError(f"{name} 意外退出（{code}），请查看 {local / (name + '.log')}")

    def interrupt(*_):
        raise KeyboardInterrupt

    previous = signal.signal(signal.SIGTERM, interrupt)
    write_state(local, state)
    try:
        for name, key in (("Java API", "PORT"), ("Agent", "AGENT_PORT"), ("Web", "SHOPWISE_WEB_PORT")):
            with socket.socket() as sock:
                sock.settimeout(.3)
                if sock.connect_ex((HOST, int(env[key]))) == 0:
                    raise ValueError(
                        f"ShopWise {name} 的固定端口 {env[key]} 已被占用。"
                        "请先运行 status 检查本项目实例，再确认当前监听进程。"
                    )
        if not all(path.exists() for path in (JAR, INTERPRETER, VITE)):
            init(env)
        else:
            database(env)
            if rebuild:
                run([MAVEN, "-q", "package", "-DskipTests"], env, ROOT / "commerce-api")
        runtime_jar = local / "commerce-runtime.jar"
        shutil.copy2(JAR, runtime_jar)
        services = services_for(env, runtime_jar)
        group = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if WINDOWS
                 else {"start_new_session": True})
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for name, argv, folder, url in services:
            check()
            with (local / f"{name}.log").open("ab") as log:
                child = subprocess.Popen(argv, cwd=folder, env=env, stdout=log,
                                         stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **group)
            children.append((name, child))
            for _ in range(240):
                check()
                try:
                    with client.open(url, timeout=1) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                time.sleep(.5)
            else:
                raise ValueError(f"{name} 启动超时，请查看 {local / (name + '.log')}")
            state["services"][name] = {"pid": child.pid, "health": url}
            write_state(local, state)
        state["phase"] = "ready"
        write_state(local, state)
        print(f"ShopWise 已启动  {services[-1][-1]}  （Ctrl+C 停止）", flush=True)
        while True:
            check()
            time.sleep(.25)
    except KeyboardInterrupt:
        pass
    finally:
        state["phase"] = "stopping"
        write_state(local, state)
        terminate([child for _, child in children])
        stop_file.unlink(missing_ok=True)
        (local / "state.json").unlink(missing_ok=True)
        signal.signal(signal.SIGTERM, previous)


def occupied_apps(env):
    listeners = {}
    for name in ("api", "agent", "web"):
        active = listener(int(env[PORTS[name][0]]))
        if active:
            listeners[name] = active
    return listeners


def validate_ports(args, env):
    prefix = "demo-" if args.action == "demo" else ""
    resolved = {prefix + name: int(env[key]) for name, (key, _) in PORTS.items()}
    expected = None
    if env["COMPOSE_PROJECT_NAME"] == "shopwise":
        expected = {name: port for name, (_, port) in PORTS.items()}
    elif env["COMPOSE_PROJECT_NAME"] == DEMO_PROJECT:
        expected = {"demo-" + name: port for name, port in DEMO_PORTS.items()}
    if expected is not None and resolved.keys() != expected.keys():
        raise ValueError("日常开发和免费演示必须使用各自的 Compose 项目名")
    check_reservations(resolved, "ShopWise", expected)
    targets = {
        "COMMERCE_URL": "api", "SHOPWISE_API_URL": "api", "AGENT_URL": "agent",
        "DB_URL": "db", "DATABASE_URL": "db",
    }
    for key, service in targets.items():
        endpoint = urlsplit(env[key].removeprefix("jdbc:"))
        port = resolved[prefix + service]
        if endpoint.hostname in (HOST, "localhost", "::1") and endpoint.port != port:
            raise ValueError(f"{key} 的本机端口与 ShopWise/{service} 不一致，应使用 {port}")
    for start, end in windows_exclusions():
        for service, port in resolved.items():
            if start <= port <= end:
                raise ValueError(f"Windows 保留端口 {port} 与 ShopWise/{service} 冲突，需统一评估两端配置")


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("action", nargs="?", default="start",
                     choices=("doctor", "init", "start", "stop", "status", "seed", "reset", "export", "import",
                              "demo", "demo-stop", "demo-status", "accept"))
    cli.add_argument("archive", nargs="?", type=Path, help="export/import 的 .swdb 归档路径")
    for name in PORTS:
        cli.add_argument("--" + name + "-port", type=int)
    cli.add_argument("--project", help="隔离本地实例和 Compose 项目")
    cli.add_argument("--db-mode", choices=("compose", "external", "native"))
    cli.add_argument("--confirm-reset", action="store_true")
    cli.add_argument("--confirm-import", action="store_true")
    return cli


def main():
    if sys.argv[1:] == ["ports", "check"]:
        from port_registry import check
        return check()
    args = parser().parse_args()
    if args.action == "accept":
        from acceptance import accept
        return accept(args)
    env = environment(args)
    local = ROOT / ".local/run" / env["COMPOSE_PROJECT_NAME"]
    if args.action == "doctor":
        return doctor(env)
    if args.action in ("status", "demo-status"):
        state = active_state(local)
        state.pop("token", None)
        if not state:
            listeners = occupied_apps(env)
            state = {"phase": "occupied", "listeners": listeners} if listeners else {"phase": "stopped"}
        print(json.dumps(state, ensure_ascii=False))
        return 0
    if args.action in ("stop", "demo-stop"):
        if not active_state(local) and occupied_apps(env):
            raise ValueError("固定端口有监听，但没有可确认的 ShopWise 实例。请运行 status 查看占用。")
        stop(local)
        return 0
    if args.action in ("start", "demo", "init", "seed"):
        validate_ports(args, env)
    try:
        with ownership(local / "owner.lock"):
            run_owned(args, env, local)
    except Busy:
        if args.action not in ("start", "demo"):
            raise
        state = active_state(local)
        if state.get("phase") == "ready":
            print(f"ShopWise 已运行  {state['services']['web']['health']}")
        elif state.get("phase") == "starting":
            print("ShopWise 正在启动，可运行 status 查看进度")
        else:
            raise
    return 0


def run_owned(args, env, local):
    (local / "state.json").unlink(missing_ok=True)
    if args.action == "init":
        init(env)
    elif args.action in ("start", "demo"):
        serve(env, local, rebuild=args.action == "demo")
    elif args.action == "seed":
        database(env)
        run([MAVEN, "-q", "package", "-DskipTests"], env, ROOT / "commerce-api")
        run([binary("java", env), "-jar", JAR, "--shopwise.seed-only=true", "--server.port=0"], env)
    elif args.action == "reset":
        if not args.confirm_reset or env["SHOPWISE_DB_MODE"] != "compose":
            raise ValueError("仅 Compose 开发库支持 reset，请先停止服务并提供 --confirm-reset")
        run(compose(env, "down", "--volumes"), env)
        database(env)
        print("数据库已重置。下次启动会重新导入本地商品。")
    else:
        if not args.archive:
            raise ValueError("请提供 .swdb 归档路径")
        if args.action == "import" and not args.confirm_import:
            raise ValueError("恢复将替换目标开发库内的备份对象，请提供 --confirm-import")
        transfer(args.action, args.archive, env, lambda: database(env), lambda *a: compose(env, *a))
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, Busy, subprocess.SubprocessError) as error:
        sys.exit(str(error))
