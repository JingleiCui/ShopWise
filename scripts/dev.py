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
import time
import uuid
import urllib.request
from urllib.parse import quote

from dev_data import transfer


ROOT = Path(__file__).resolve().parents[1]
HOST = "127.0.0.1"
WINDOWS = os.name == "nt"
PORTS = {"db": ("SHOPWISE_DB_PORT", 15439), "api": ("PORT", 18081),
         "agent": ("AGENT_PORT", 18082), "web": ("SHOPWISE_WEB_PORT", 15173)}
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
    for name, (key, default) in PORTS.items():
        value = getattr(args, name + "_port")
        port = int(value if value is not None else env.get(key, default))
        if not 1 <= port <= 65535:
            raise ValueError(f"{key} 需要 1–65535 之间的端口")
        env[key] = str(port)
    if len({env[key] for key, _ in PORTS.values()}) != len(PORTS):
        raise ValueError("数据库、API、Agent 和网页需要不同的端口")
    env["SHOPWISE_DB_MODE"] = args.db_mode or env.get("SHOPWISE_DB_MODE", "compose")
    if env["SHOPWISE_DB_MODE"] not in ("compose", "external"):
        raise ValueError("SHOPWISE_DB_MODE 需要 compose 或 external")
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
    env.setdefault("DB_URL", f"jdbc:postgresql://{hostport}/shopwise")
    user = quote(env["DB_USER"], safe="")
    password = quote(env["DB_PASSWORD"], safe="")
    env.setdefault("DATABASE_URL", f"postgresql://{user}:{password}@{hostport}/shopwise")
    env.setdefault("COMMERCE_URL", f"http://{HOST}:{env['PORT']}")
    env.setdefault("SHOPWISE_API_URL", env["COMMERCE_URL"])
    env.setdefault("AGENT_URL", f"http://{HOST}:{env['AGENT_PORT']}")
    env.setdefault("PYTHONUTF8", "1")
    return env


def binary(name, env):
    executable = shutil.which(name, path=env["PATH"])
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


def init(env):
    database(env)
    run([MAVEN, "-q", "package"], env, ROOT / "commerce-api")
    run([binary("uv", env), "sync", "--frozen", "--python", "3.12"], env, ROOT / "agent-service")
    npm = binary("npm.cmd" if WINDOWS else "npm", env)
    run([npm, "ci", "--no-audit", "--no-fund"], env, ROOT / "web")
    run([npm, "run", "build"], env, ROOT / "web")


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
    else:
        checks += [(name, [name, "--version"], r"(\d+)", 1) for name in ("pg_dump", "pg_restore")]
    print(f"{platform.system()} {platform.machine()} / Python {platform.python_version()} / DB {env['SHOPWISE_DB_MODE']}")
    errors = []
    for label, argv, pattern, floor in checks:
        try:
            result = run([binary(argv[0], env), *argv[1:]], env, stdout=subprocess.PIPE,
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
        ("commerce", [binary("java", env), "-jar", str(runtime_jar)], ROOT,
         f"http://{HOST}:{env['PORT']}/api/health"),
        ("agent", [str(INTERPRETER), "main.py"], ROOT / "agent-service",
         f"http://{HOST}:{env['AGENT_PORT']}/health"),
        ("web", [binary("node", env), str(VITE), "--host", HOST, "--port", env["SHOPWISE_WEB_PORT"],
                 "--strictPort"], ROOT / "web", f"http://{HOST}:{env['SHOPWISE_WEB_PORT']}"),
    ]


def serve(env, local):
    token = uuid.uuid4().hex
    stop_file = local / f"stop-{token}"
    state = {"token": token, "phase": "starting", "services": {}}
    children = []
    def check():
        if stop_file.exists():
            raise KeyboardInterrupt
        for child in children:
            if child.poll() is not None:
                raise ValueError(f"服务意外退出，请查看 {local} 下的日志")

    def interrupt(*_):
        raise KeyboardInterrupt

    previous = signal.signal(signal.SIGTERM, interrupt)
    write_state(local, state)
    try:
        for key in ("PORT", "AGENT_PORT", "SHOPWISE_WEB_PORT"):
            with socket.socket() as sock:
                sock.settimeout(.3)
                if sock.connect_ex((HOST, int(env[key]))) == 0:
                    raise ValueError(f"端口 {env[key]} 已被占用，请修改端口或检查 status")
        if not all(path.exists() for path in (JAR, INTERPRETER, VITE)):
            init(env)
        else:
            database(env)
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
            children.append(child)
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
        terminate(children)
        stop_file.unlink(missing_ok=True)
        (local / "state.json").unlink(missing_ok=True)
        signal.signal(signal.SIGTERM, previous)


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("action", nargs="?", default="start",
                     choices=("doctor", "init", "start", "stop", "status", "seed", "reset", "export", "import"))
    cli.add_argument("archive", nargs="?", type=Path, help="export/import 的 .swdb 归档路径")
    for name in PORTS:
        cli.add_argument("--" + name + "-port", type=int)
    cli.add_argument("--project", help="隔离本地实例和 Compose 项目")
    cli.add_argument("--db-mode", choices=("compose", "external"))
    cli.add_argument("--confirm-reset", action="store_true")
    cli.add_argument("--confirm-import", action="store_true")
    return cli


def main():
    args = parser().parse_args()
    env = environment(args)
    local = ROOT / ".local/run" / env["COMPOSE_PROJECT_NAME"]
    if args.action == "doctor":
        return doctor(env)
    if args.action == "status":
        state = active_state(local)
        state.pop("token", None)
        print(json.dumps(state or {"phase": "stopped"}, ensure_ascii=False))
        return 0
    if args.action == "stop":
        stop(local)
        return 0
    with ownership(local / "owner.lock"):
        (local / "state.json").unlink(missing_ok=True)
        if args.action == "init":
            init(env)
        elif args.action == "start":
            serve(env, local)
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
