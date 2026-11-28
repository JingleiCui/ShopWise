"""Portable checks for the committed local host port registry."""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "docs/local-ports.json"
HOST = "127.0.0.1"
COMPOSE_OWNERS = {
    "shopwise": "ShopWise", "shopwise-demo": "ShopWise",
    "scholarship": "ScholarShip", "travel-agent": "TripPal",
    "travel-agent-server": "TripPal", "enterprise-kb-v2": "enterprise_knowledge_rag_v2",
}


def assignments():
    data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    owners = {}
    for project, info in data.items():
        for service, definition in info["services"].items():
            port = definition["port"]
            owner = f"{project}/{service}"
            if type(port) is not int or not 1 <= port <= 65535:
                raise ValueError(f"{owner} 端口无效：{port}")
            if port in owners:
                raise ValueError(f"端口 {port} 重复登记：{owners[port]}、{owner}")
            owners[port] = owner
    return data, owners


def check_reservations(ports, project, expected=None):
    _, owners = assignments()
    for service, port in ports.items():
        owner = owners.get(port)
        permitted = owner == f"{project}/{service}"
        if owner and not permitted:
            raise ValueError(f"端口 {port} 已登记给 {owner}，{project}/{service} 不能使用")
        if expected is not None and port != expected[service]:
            raise ValueError(f"{project}/{service} 的固定端口是 {expected[service]}，当前配置为 {port}")


def windows_exclusions():
    if os.name != "nt":
        return []
    ranges = []
    for family in ("ipv4", "ipv6"):
        result = subprocess.run(["netsh", "int", family, "show", "excludedportrange", "protocol=tcp"],
                                capture_output=True, timeout=10)
        if result.returncode:
            raise ValueError(f"无法核对 Windows {family} 保留端口")
        fields = re.findall(rb"(?m)^\s*(\d+)\s+(\d+)[ \t*]*\r?$",result.stdout)
        ranges.extend((int(lo), int(hi)) for lo, hi in fields)
    return ranges


def listener(port):
    with socket.socket() as connection:
        connection.settimeout(.15)
        if connection.connect_ex((HOST, port)):
            return None
    info = {"port": port}
    if shutil.which("lsof"):
        result = subprocess.run(["lsof", "-nP", "-tiTCP:" + str(port), "-sTCP:LISTEN"],
                                capture_output=True, text=True, timeout=3)
        info["pids"] = [int(line) for line in result.stdout.splitlines() if line.isdecimal()]
    elif os.name == "nt":
        result = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, text=True, timeout=5)
        info["pids"] = sorted({
            int(row[-1]) for line in result.stdout.splitlines()
            if len(row := line.split()) == 5 and row[1].endswith(f":{port}")
            and row[2].endswith(":0") and row[-1].isdecimal()
        })
    return info


def docker_listeners():
    if not shutil.which("docker"):
        return {}
    result = subprocess.run(
        ["docker", "ps", "--format",
         '{{.Label "com.docker.compose.project"}}\t{{.Ports}}'],
        capture_output=True, text=True, timeout=8,
    )
    owners = {}
    for line in result.stdout.splitlines():
        name, _, bindings = line.partition("\t")
        for port in re.findall(r":(\d+)->", bindings):
            owners[int(port)] = COMPOSE_OWNERS.get(name, name or "unknown-container")
    return owners


def process_owner(pids, roots):
    for pid in pids:
        if shutil.which("lsof"):
            executable = subprocess.run(["ps", "-p", str(pid), "-o", "comm="],
                                        capture_output=True, text=True, timeout=3)
            if Path(executable.stdout.strip()).name == "ollama":
                return "Ollama"
            result = subprocess.run(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"],
                                    capture_output=True, text=True, timeout=3)
            for line in result.stdout.splitlines():
                if line.startswith("n/"):
                    directory = Path(line[1:])
                    for name, project in roots.items():
                        if directory == project or project in directory.parents:
                            return name
        elif os.name == "nt":
            executable = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"Get-CimInstance Win32_Process -Filter 'ProcessId = {pid}' | "
                 "Select-Object -ExpandProperty CommandLine"],
                capture_output=True, text=True, errors="replace", timeout=5)
            line = executable.stdout.casefold()
            if re.search(r'\bollama(?:\.exe)?(?:[\s"]|$)', line):
                return "Ollama"
            for name, root in roots.items():
                if str(root).casefold() + "\\" in line or root.as_posix().casefold() + "/" in line:
                    return name
    return None


def check():
    data, owners = assignments()
    errors = []
    parent = Path(os.environ.get("SHOPWISE_PROJECTS_ROOT", str(ROOT.parent)))
    roots = {}
    for name, info in data.items():
        if info["root"] is None:
            continue
        path = ROOT if name == "ShopWise" else parent / name
        if not path.is_dir():
            print(f"SKIP {name}：未检出")
            continue
        roots[name] = path.resolve()
        for service, definition in info["services"].items():
            port = definition["port"]
            for relative in definition["files"]:
                file = path / relative
                if not file.is_file() or not re.search(
                        rf"(?<!\d){port}(?!\d)", file.read_text(encoding="utf-8")):
                    errors.append(f"{name}/{service} 的配置未找到端口 {port}：{relative}")
    ranges = windows_exclusions()
    containers = docker_listeners()
    for port, owner in sorted(owners.items()):
        if any(start <= port <= end for start, end in ranges):
            errors.append(f"{port} {owner} 位于 Windows 保留端口范围")
        current = listener(port)
        if current:
            pids = current.get("pids") or []
            actual = containers.get(port) or process_owner(pids, roots)
            if actual != owner.split("/", 1)[0]:
                errors.append(f"{port} 登记给 {owner}，监听归属 {actual or '待确认'}，PID {pids or '未知'}")
            else:
                print(f"LISTEN {port} · {owner} · 已核对监听归属")
    for error in errors:
        print(f"FAIL {error}")
    print(f"{len(owners)} 个固定端口，{len(errors)} 个登记、配置或监听异常")
    return int(bool(errors))
