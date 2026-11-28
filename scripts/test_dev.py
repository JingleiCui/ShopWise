"""Stdlib tests, including a real three-process lifecycle in a Unicode path."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

import dev
import dev_data


@contextmanager
def temporary_root():
    with tempfile.TemporaryDirectory(prefix="商城 测试 ") as folder:
        root = Path(folder)
        with patch.object(dev, "ROOT", root):
            yield root


class LauncherTests(unittest.TestCase):
    def test_environment_precedence(self):
        with temporary_root() as root, patch.dict(os.environ, {"PORT": "20002"}, clear=True):
            (root / ".env.local").write_text('PORT=20001\nSHOPWISE_WEB_PORT="20010"\n', encoding="utf-8-sig")
            env = dev.environment(dev.parser().parse_args(["--api-port", "20003"]))
            self.assertEqual(env["PORT"], "20003")
            self.assertEqual(env["SHOPWISE_WEB_PORT"], "20010")
            self.assertEqual(env["SHOPWISE_DB_PORT"], "15439")
            self.assertEqual(env["COMMERCE_URL"], "http://127.0.0.1:20003")

    def test_demo_is_anonymous_and_uses_its_own_addresses(self):
        with temporary_root() as root, patch.dict(os.environ, {
            "AGENT_MODE": "live", "DASHSCOPE_API_KEY": "example-secret", "EBAY_ENABLED": "true",
            "SHOPIFY_CATALOG_CLIENT_ID": "example-client", "SPRING_PROFILES_ACTIVE": "prod",
            "DATABASE_URL": "postgresql://elsewhere/production", "PORT": "38081",
            "COMPOSE_PROJECT_NAME": "personal", "SHOPWISE_ECB_RATES_FILE": "old.xml",
        }, clear=True):
            args = dev.parser().parse_args(["demo", "--db-mode", "native", "--api-port", "29081"])
            env = dev.environment(args)
            self.assertEqual(env["AGENT_MODE"], "offline")
            self.assertEqual(env["SHOPIFY_ENABLED"], "true")
            self.assertEqual(env["EBAY_ENABLED"], "false")
            for key in ("DASHSCOPE_API_KEY", "SHOPIFY_CATALOG_CLIENT_ID", "SPRING_PROFILES_ACTIVE",
                        "SHOPWISE_ECB_RATES_FILE"):
                self.assertNotIn(key, env)
            self.assertEqual(env["COMPOSE_PROJECT_NAME"], "shopwise-demo")
            self.assertEqual(env["SHOPWISE_WEB_PORT"], "25174")
            self.assertEqual(env["COMMERCE_URL"], "http://127.0.0.1:29081")
            self.assertEqual(env["DATABASE_URL"], "postgresql://shopwise:shopwise@127.0.0.1:26439/postgres")
            self.assertEqual(Path(env["SHOPWISE_ECB_RATES_CACHE"]), root / ".local/run/shopwise-demo/ecb-rates.xml")
            for command in ("demo-stop", "demo-status"):
                control = dev.environment(dev.parser().parse_args([command]))
                self.assertEqual(control["COMPOSE_PROJECT_NAME"], "shopwise-demo")

    def test_stale_state_cannot_stop_another_process(self):
        with temporary_root() as local:
            dev.write_state(local, {"pid": os.getpid(), "token": "old", "phase": "ready"})
            dev.stop(local)
            self.assertEqual(dev.active_state(local), {})
            self.assertFalse((local / "stop-old").exists())

    def test_native_database_does_not_reuse_unrelated_listening_port(self):
        with temporary_root() as root, socket.socket() as other:
            other.bind((dev.HOST, 0))
            other.listen()
            port = str(other.getsockname()[1])
            env = dev.environment(dev.parser().parse_args(["--db-mode", "native", "--db-port", port]))
            self.assertTrue(env["DB_URL"].endswith(f":{port}/postgres"))
            with patch.object(dev, "pg_tool", return_value="pg_ctl"), patch.object(dev, "run") as run:
                with self.assertRaisesRegex(ValueError, "已被其他程序占用"):
                    dev.native_database(env)
                run.assert_not_called()
                self.assertFalse((root / ".local" / "postgres").exists())

    def test_stop_native_rejects_a_reused_listener_pid(self):
        with temporary_root() as root:
            directory = root / ".local/postgres/shopwise"
            directory.mkdir(parents=True)
            (directory / "PG_VERSION").write_text("17", encoding="ascii")
            (directory / "postmaster.pid").write_text(f"42\n{directory}\n100\n15439\n", encoding="utf-8")
            env = {"COMPOSE_PROJECT_NAME": "shopwise", "DB_USER": "shopwise", "DB_PASSWORD": "test"}
            with (patch.object(dev, "pg_tool", side_effect=lambda name, _: name),
                  patch.object(dev, "listener", return_value={"pids": [42]}),
                  patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0)),
                  patch.object(dev, "postgres_start_time", return_value=300),
                  patch.object(dev, "run") as control):
                with self.assertRaisesRegex(ValueError, "进程启动时间"):
                    dev.stop_native_database(env, 15439)
                control.assert_not_called()

    def test_archive_roundtrip_and_checksum(self):
        with temporary_root() as root:
            archive, dump = root / "数据.swdb", root / "out.dump"
            payload = b"PGDMP\x00binary\n"
            def pg_dump(argv, **kw):
                kw["stdout"].write(payload)
            with patch.object(dev_data, "pg_command", return_value=(["pg_dump"], {})), patch.object(subprocess, "run", pg_dump):
                dev_data.transfer("export", archive, {}, lambda: None, None)
            dev_data.unpack(archive, dump)
            self.assertEqual(dump.read_bytes(), payload)
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("database.dump", payload)
                z.writestr("manifest.json", json.dumps({"format": "shopwise-dev", "version": 1, "sha256": "bad"}))
            with self.assertRaisesRegex(ValueError, "摘要"):
                dev_data.transfer("import", archive, {}, lambda: self.fail("DB touched"), None)

    def test_controlled_shutdown_and_failed_port_are_isolated(self):
        # The fixture uses the actual supervisor with a tiny stdlib server per service.
        with temporary_root() as local:
            sockets = [socket.socket() for _ in range(3)]
            for sock in sockets:
                sock.bind((dev.HOST, 0))
            ports = [sock.getsockname()[1] for sock in sockets]
            for sock in sockets:
                sock.close()
            fixture = Path(__file__).resolve()
            with subprocess.Popen([sys.executable, str(fixture), "--fixture", str(local), *map(str, ports)]) as proc:
                try:
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline and proc.poll() is None:
                        if dev.active_state(local).get("phase") == "ready":
                            break
                        time.sleep(.1)
                    state = dev.active_state(local)
                    if state.get("phase") != "ready":
                        logs = "\n".join(f"{path.name}: {path.read_text(errors='replace')}" for path in local.glob("*.log"))
                        self.fail(f"Fixture exit={proc.poll()} phase={state.get('phase')}\n{logs}")
                    dev.stop(local)
                    self.assertEqual(proc.wait(timeout=15), 0)
                finally:
                    if proc.poll() is None:
                        proc.terminate()
            for port in ports:
                with socket.socket() as sock:
                    sock.settimeout(.3)
                    self.assertNotEqual(sock.connect_ex((dev.HOST, port)), 0)
            with socket.socket() as other:
                other.bind((dev.HOST, 0))
                other.listen()
                occupied = other.getsockname()[1]
                conflict_ports = [occupied, *ports[1:]]
                result = subprocess.run([sys.executable, str(fixture), "--fixture", str(local), *map(str, conflict_ports)],
                                        stderr=subprocess.PIPE, timeout=15)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(dev.active_state(local))
                with socket.create_connection((dev.HOST, occupied), timeout=2):
                    pass


def fixture(folder, ports):
    local = Path(folder)
    env = dev.environment(dev.parser().parse_args(["--api-port", ports[0], "--agent-port", ports[1],
                                                  "--web-port", ports[2]]))
    service = [(name, [sys.executable, str(Path(__file__).resolve()), "--serve-health", port], local,
                f"http://{dev.HOST}:{port}/") for name, port in zip(("commerce", "agent", "web"), ports)]
    dummy_jar = local / "fixture.jar"
    dummy_jar.touch()
    with patch.multiple(dev, JAR=dummy_jar, INTERPRETER=dummy_jar, VITE=dummy_jar), patch.object(dev, "database"), \
            patch.object(dev, "services_for", return_value=service), dev.ownership(local / "owner.lock"):
        dev.serve(env, local)


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()


if __name__ == "__main__":
    if sys.argv[1:2] == ["--serve-health"]:
        with ThreadingHTTPServer((dev.HOST, int(sys.argv[2])), HealthHandler) as server:
            server.serve_forever()
    elif sys.argv[1:2] == ["--fixture"]:
        fixture(sys.argv[2], sys.argv[3:])
    else:
        unittest.main()
