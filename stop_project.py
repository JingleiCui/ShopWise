"""Stop ShopWise, with optional PostgreSQL shutdown via --with-db."""
import sys

from run_project import run


if __name__ == "__main__":
    raise SystemExit(run("stop", sys.argv[1:]))
