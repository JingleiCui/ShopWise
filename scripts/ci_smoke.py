"""CI-only real service smoke test on a newly provisioned development database."""
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


def check_start():
    with subprocess.Popen([LAUNCH, "start"], cwd=ROOT) as supervisor:
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
                with urllib.request.urlopen(url, timeout=4) as response:
                    assert response.status == 200
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
    check_start()
    check_start()
    run("export", str(ROOT.parent / "shopwise-ci.swdb"))
