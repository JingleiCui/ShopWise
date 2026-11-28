"""CI-only real service smoke test on a newly provisioned development database."""
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
LAUNCH = str(ROOT / ("shopwise.cmd" if os.name == "nt" else "shopwise"))


def run(*args, capture=False):
    return subprocess.run([LAUNCH, *args], cwd=ROOT, check=True, capture_output=capture)


def check_start(client, message, budget):
    fixture_env = {**os.environ, "SPRING_PROFILES_ACTIVE": "load-fixture", "SHOPWISE_LOAD_BOTH": "true"}
    with subprocess.Popen([LAUNCH, "start"], cwd=ROOT, env=fixture_env) as supervisor:
        try:
            deadline = time.monotonic() + 150
            while supervisor.poll() is None and time.monotonic() < deadline:
                state = json.loads(run("status", capture=True).stdout)
                if state["phase"] == "ready":
                    break
                time.sleep(.5)
            else:
                raise RuntimeError("services did not become ready")
            urls = [record["health"] for record in state["services"].values()]
            assert len(urls) == 3, state
            for url in urls:
                with client.open(url, timeout=4) as response:
                    assert response.status == 200
            base = state["services"]["web"]["health"].rstrip("/")
            with client.open(base + "/api/csrf", timeout=4) as response:
                csrf = json.load(response)
            request = urllib.request.Request(
                base + "/api/v1/assistant/turn",
                data=json.dumps({"message": message}).encode("utf-8"),
                headers={csrf["header"]: csrf["token"], "Content-Type": "application/json"},
            )
            with client.open(request, timeout=12) as response:
                events = [json.loads(line[6:]) for line in response.read().decode("utf-8").splitlines()
                          if line.startswith("data: ")]
            result = events[-1]
            assert result.get("filters", {}).get("city") == "杭州", result
            assert result["filters"]["budget"] == budget, result
            assert result["offers"][0]["amountMinor"] == 22300, result
        finally:
            if supervisor.poll() is None:
                run("stop")
                supervisor.wait(timeout=20)
        assert supervisor.returncode == 0
        assert json.loads(run("status", capture=True).stdout)["phase"] == "stopped"


if __name__ == "__main__":
    if os.getenv("CI") != "true":
        raise SystemExit("This test requires an isolated CI database")
    run("doctor")
    run("init")
    browser = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                          urllib.request.HTTPCookieProcessor(CookieJar()))
    check_start(browser, "找一盏寄到杭州、到手不超过300元的阅读灯", 300)
    check_start(browser, "把预算改成500", 500)
    run("export", str(ROOT.parent / "shopwise-ci.swdb"))
