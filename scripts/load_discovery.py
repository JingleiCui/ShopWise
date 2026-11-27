"""Local release load test. Start Java with the loopback-only load-fixture profile."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.cookiejar import CookieJar
import json
import math
from pathlib import Path
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HOST = "127.0.0.1"
QUERY = "帮我找一盏能寄到杭州、到手不超过 300 元的阅读灯"


def call(client, base, path, data=None, headers=None):
    req = urllib.request.Request(base+path, data=None if data is None else json.dumps(data).encode(),
                                 headers=headers or {})
    with client.open(req, timeout=15) as response:
        return response.read()


def attempt(action):
    start = time.perf_counter()
    try:
        status = action()
    except urllib.error.HTTPError as failure:
        status = "HTTP_"+str(failure.code)
    except (OSError, ValueError, KeyError, IndexError) as failure:
        status = type(failure).__name__
    return round((time.perf_counter()-start)*1000, 2), status


def run(port, report, both):
    base = f"http://{HOST}:{port}"
    def browser():
        return urllib.request.build_opener(urllib.request.ProxyHandler(), urllib.request.HTTPCookieProcessor(CookieJar()))
    def visitor():
        client = browser()
        csrf = json.loads(call(client, base, "/api/csrf"))
        headers = {csrf["header"]: csrf["token"], "Content-Type": "application/json"}
        return client, headers
    def read():
        result = json.loads(call(browser(), base, "/api/v1/discovery?q=desk%20lamp"))
        source = next(s for s in result["sources"] if s["provider"] == "shopify")
        if source["status"] != "ok":
            return source["status"]
        if both and not valid_match(result):
            return "missing_match"
        return "ok" if source["offers"][0]["amountMinor"] == 22300 else "unexpected_offer"
    def valid_match(result):
        return any(group.get("evidence") == "GTIN" and set(group.get("offers", [])) ==
                   {"shopify/load-variant", "ebay/load-variant"} for group in result.get("matches", []))
    def assistant(client, headers):
        data = call(client, base, "/api/v1/assistant/turn", {"message": QUERY}, headers)
        events = [json.loads(line[6:]) for line in data.decode().splitlines() if line.startswith("data: ")]
        final = events[-1]
        if "message" in final:
            return "sse_error"
        if not final.get("offers"):
            return "no_offer"
        if both and not valid_match(final):
            return "missing_match"
        if final.get("filters", {}).get("city") != "杭州" or final["offers"][0]["amountMinor"] != 22300:
            return "wrong_offer"
        return "ok"
    client, headers = visitor()
    assert read() == "ok" and assistant(client, headers) == "ok", "Start ShopWise against the provided local fixture."
    results = {"paced_reads": [], "concurrent_assistant": []}
    with ThreadPoolExecutor(max_workers=20) as pool:
        next_at = time.monotonic()
        futures=[]
        for _ in range(60):
            time.sleep(max(0,next_at-time.monotonic()))
            futures.append(pool.submit(attempt,read))
            next_at += .30
        results["paced_reads"]=[future.result() for future in futures]
        for _ in range(3):
            visitors=list(pool.map(lambda _: visitor(),range(20)))
            barrier=threading.Barrier(20)
            def launch(auth):
                barrier.wait(timeout=10)
                return attempt(lambda: assistant(*auth))
            results["concurrent_assistant"].extend(pool.map(launch,visitors))
            time.sleep(.3)

    def phase(samples, target):
        counts = Counter(status for _, status in samples)
        successful = sorted(ms for ms, status in samples if status == "ok")
        p95 = successful[math.ceil(len(successful)*.95)-1] if successful else None
        return {"requests": len(samples), "statuses": dict(counts), "p95_success_ms": p95,
                "target_ms": target, "passed": counts["ok"] == len(samples) and p95 is not None and p95 <= target}
    result = {"timestamp": datetime.now(timezone.utc).isoformat(), "target": base,
              "fixture_delay_ms": 80, "fixture_sources": 2 if both else 1, "assistant_concurrency": 20, "assistant_bursts": 3,
              "paced_reads": phase(results["paced_reads"], 500),
              "concurrent_assistant": phase(results["concurrent_assistant"], 8000)}
    report.parent.mkdir(parents=True,exist_ok=True)
    payload = json.dumps(result, indent=2)
    report.write_text(payload+"\n",encoding="utf-8")
    print(payload)
    return 0 if all(result[key]["passed"] for key in results) else 1


if __name__ == "__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--port",type=int)
    cli.add_argument("--report",type=Path,default=Path(".local/discovery-load.json"))
    cli.add_argument("--both",action="store_true",help="Require a same-product group from both fixture sources")
    args=cli.parse_args()
    raise SystemExit(run(args.port or 25173,args.report,args.both))
