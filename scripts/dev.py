"""Run in PyCharm, or use python3 scripts/dev.py [start|init|seed|reset]."""
from pathlib import Path
import argparse
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

# Local development defaults. Environment overrides support a second isolated stack.
ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"
DATABASE_PORT = int(os.getenv("SHOPWISE_DB_PORT", "15439"))
API_PORT = int(os.getenv("PORT", "18081"))
AGENT_PORT = int(os.getenv("AGENT_PORT", "18082"))
WEB_PORT = int(os.getenv("SHOPWISE_WEB_PORT", "15173"))
HOST = "127.0.0.1"
JAR = ROOT / "commerce-api/target/commerce-api-1.0.0.jar"
AGENT_PYTHON = ROOT / "agent-service/.venv/bin/python"


def environment():
    env = os.environ.copy()
    brew_jdk = Path("/opt/homebrew/opt/openjdk@17")
    if not env.get("JAVA_HOME") and brew_jdk.exists():
        env["JAVA_HOME"] = str(brew_jdk)
    if env.get("JAVA_HOME"):
        env["PATH"] = env["JAVA_HOME"] + "/bin:" + env["PATH"]
    env.update(PORT=str(API_PORT), AGENT_PORT=str(AGENT_PORT), SHOPWISE_DB_PORT=str(DATABASE_PORT))
    env.setdefault("DB_URL", f"jdbc:postgresql://{HOST}:{DATABASE_PORT}/shopwise")
    env.setdefault("DATABASE_URL", f"postgresql://shopwise:shopwise@{HOST}:{DATABASE_PORT}/shopwise")
    env.setdefault("COMMERCE_URL", f"http://{HOST}:{API_PORT}")
    env.setdefault("SHOPWISE_API_URL", env["COMMERCE_URL"])
    env.setdefault("AGENT_URL", f"http://{HOST}:{AGENT_PORT}")
    return env


def command(args, cwd=ROOT):
    subprocess.run(args, cwd=cwd, env=environment(), check=True)


def database():
    command(["docker", "compose", "up", "-d", "--wait", "db"])


def init():
    database()
    command(["./mvnw", "-q", "package"], ROOT / "commerce-api")
    command(["uv", "sync", "--frozen", "--python", "3.12"], ROOT / "agent-service")
    command(["npm", "ci", "--no-audit", "--no-fund"], ROOT / "web")
    command(["npm", "run", "build"], ROOT / "web")


def serve():
    if not JAR.exists() or not AGENT_PYTHON.exists() or not (ROOT / "web/node_modules/.bin/vite").exists():
        init()
    else:
        database()
    for port in (API_PORT, AGENT_PORT, WEB_PORT):
        with socket.socket() as sock:
            if sock.connect_ex((HOST, port)) == 0:
                raise SystemExit(f"端口 {port} 已被占用。如 ShopWise 已启动，可直接打开网页。")
    LOCAL.mkdir(exist_ok=True)
    runtime_jar = LOCAL / "commerce-runtime.jar"
    shutil.copy2(JAR, runtime_jar)
    services = [
        ("commerce", ["java", "-jar", str(runtime_jar)], ROOT, f"http://{HOST}:{API_PORT}/api/health"),
        ("agent", [str(AGENT_PYTHON), "main.py"], ROOT / "agent-service", f"http://{HOST}:{AGENT_PORT}/health"),
        ("web", ["./node_modules/.bin/vite", "--host", HOST, "--port", str(WEB_PORT), "--strictPort"], ROOT / "web", f"http://{HOST}:{WEB_PORT}"),
    ]
    children = []
    def stop(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    try:
        for name, args, folder, url in services:
            logfile = LOCAL / f"{name}.log"
            with logfile.open("a") as output:
                proc = subprocess.Popen(args, cwd=folder, env=environment(), stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            children.append(proc)
            for _ in range(240):
                if proc.poll() is not None:
                    raise SystemExit(f"{name} 启动失败，请查看 {logfile}")
                try:
                    with urllib.request.urlopen(url, timeout=1) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                time.sleep(.5)
            else:
                raise SystemExit(f"{name} 启动超时，请查看 {logfile}")
        print(f"ShopWise 已启动  http://{HOST}:{WEB_PORT}  （Ctrl+C 停止）", flush=True)
        while all(proc.poll() is None for proc in children):
            time.sleep(.5)
        raise SystemExit(f"服务意外退出，请查看 {LOCAL} 下的日志")
    except KeyboardInterrupt:
        pass
    finally:
        for proc in reversed(children):
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
        for proc in children:
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=("start", "init", "seed", "reset"), default="start")
    parser.add_argument("--confirm-reset", action="store_true", help="删除此 Compose 项目的演示数据库")
    args = parser.parse_args()
    if args.action == "init":
        init()
    elif args.action == "start":
        serve()
    elif args.action == "seed":
        database()
        # A short-lived Spring context applies Flyway and the same idempotent seed runner.
        command(["./mvnw", "-q", "package", "-DskipTests"], ROOT / "commerce-api")
        command(["java", "-jar", str(JAR), "--shopwise.seed-only=true", "--server.port=0"])
    else:
        if not args.confirm_reset:
            sys.exit("先停止运行中的服务，再执行 reset --confirm-reset。此操作会清空本项目的订单及会话。")
        command(["docker", "compose", "down", "--volumes"])
        database()
        print("数据库已重置。下次启动会重新导入本地商品。")
