"""Start the local ShopWise development supervisor."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def run(action, args):
    command = [sys.executable, "-X", "utf8", str(ROOT / "scripts" / "dev.py"), action, *args]
    try:
        return subprocess.call(command, cwd=ROOT)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(run("start", sys.argv[1:]))
