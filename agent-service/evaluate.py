"""Run the fixed Chinese dialogues from the IDE or with uv run python evaluate.py.
Default evaluation is deterministic and uses an in-memory commerce fixture.
Set EVAL_LIVE=true to exercise real qwen-plus tool calls against the same oracle.
"""
import json
import os
import statistics
import time
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path

from langgraph.checkpoint.memory import InMemorySaver

import config
from adapters import Tools, runtime
from main import Turn, graph_for, process

LIVE = os.getenv("EVAL_LIVE","false").lower() == "true"
REPORT_PATH = os.getenv("EVAL_REPORT_PATH","")
FIXTURE = Path(__file__).resolve().parent.parent / "commerce-api/src/main/resources/catalog.json"

class Demo(Tools):
    def __init__(self):
        super().__init__("test-only",{"mode":"offline","tools":[],"tokens":0},"fixture")
        self.cart = {}
        self.quotes = {}
        self.orders = [{"id":"SW-DEMO-001","status":"COMPLETED","events":[{"status":"COMPLETED","at":datetime.now(timezone.utc).isoformat()}],
                        "lines":[{"skuId":470,"quantity":2}],"total":378}]
        self.products = []
        for i,p in enumerate(json.loads(FIXTURE.read_text())):
            p["id"] = p["sourceId"]
            for index,s in enumerate(p["skus"]):
                s["id"] = p["id"]*10 + index
            self.products.append(p)
        self.products.sort(key=lambda p:p["id"])

    def call(self, method,path,**kwargs):
        self.metrics["tools"].append({"method":method,"tool":path})
        if path == "/products":
            args = kwargs["params"]
            return [deepcopy(p) for p in self.products
                    if (not args.get("category") or args["category"] == p["category"])
                    and args.get("q","").lower() in json.dumps([p["kind"],p["title"],p["description"],p["attributes"]],ensure_ascii=False).lower()]
        if path == "/cart":
            return {"lines":[{"skuId":k,"quantity":v,"label":"标准款","price":189,"title":"布罩阅读台灯"} for k,v in self.cart.items()],"subtotal":sum(self.cart.values())*189}
        if path.startswith("/cart/items/"):
            sku_id = int(path.split("/")[3])
            self.cart[sku_id] = self.cart.get(sku_id, 0) + kwargs["json"]["quantity"]
            return {}
        if path == "/quotes":
            q = {"id":f"q{len(self.quotes)+1}","total":sum(self.cart.values())*189}
            self.quotes[q["id"]] = q
            return q
        if path.startswith("/quotes/"):
            return self.quotes[path.split("/")[-1]]
        if path == "/orders":
            return self.orders
        if path.endswith("/returns"):
            return {"id":"R-DEMO-001"}
        if path.startswith("/orders/"):
            return next(o for o in self.orders if path.endswith(o["id"]))
        if path == "/policies":
            return [{"id":"returns","title":"退款规则","content":"签收后 7 天内可申请退货。","source":"商城测试规则","version":"1.0"}]
        raise AssertionError(f"unexpected tool {method} {path}")

# Each assertion inspects output/data, and tool traces independently of the router.
CASES = [
    ("找300元内的台灯","search",["GET /products"],lambda r:len(r["products"])==2),
    ("只看有货的","search",["GET /products"],lambda r:all(s["stock"]>0 for p in r["products"] for s in p["skus"]) and len(r["products"])==2),
    ("比较前两款","compare",["GET /products"],lambda r:len(r["comparison"]["titles"])==2 and "起售价" in r["text"]),
    ("把第一款加入购物车","add",["GET /products","POST /cart/items/470/add"],lambda r:"已加入" in r["text"]),
    ("帮我下单","quote",["POST /quotes"],lambda r:r["quote"]["id"]=="q1"),
    ("确认","approval",["GET /quotes/q1"],lambda r:r["quote"]["id"]=="q1" and "点击" in r["text"]),
    ("取消报价","abandon",["GET /quotes/q1"],lambda r:"退出" in r["text"]),
    ("我的订单到哪了","orders",["GET /orders","GET /orders/SW-DEMO-001"],lambda r:"已签收" in r["text"]),
    ("我的订单可以退货吗","policy",["GET /policies","GET /orders","GET /orders/SW-DEMO-001"],lambda r:"仍在申请期限内" in r["text"] and r["citations"][0]["version"]=="1.0"),
    ("给订单 SW-DEMO-001 申请退货 SKU 470 一件，原因是规格不合适","return",["GET /orders","GET /orders/SW-DEMO-001","POST /orders/SW-DEMO-001/returns"],lambda r:"R-DEMO-001" in r["text"]),
    ("转人工客服","handoff",[],lambda r:r.get("handoff") is True),
]

def run(live=LIVE):
    if live and not config.API_KEY:
        raise SystemExit("EVAL_LIVE=true 需要 DASHSCOPE_API_KEY 或 QWEN_API_KEY")
    previous = config.MODE
    config.MODE = "live" if live else "offline"
    demo = Demo()
    token = runtime.set(demo)
    graph = graph_for(InMemorySaver())
    reports = []
    try:
        for index,(message,intent,expected,check) in enumerate(CASES):
            demo.metrics = {"mode":config.MODE,"tools":[],"tokens":0}
            start = time.monotonic()
            error = None
            answer = {}
            try:
                answer = process(graph,Turn(threadId="evaluation",requestId=str(index),message=message))
                content_pass = bool(check(answer))
            except Exception as exc:
                error = type(exc).__name__
                content_pass = False
            actual = [x["method"]+" "+x["tool"] for x in demo.metrics["tools"]]
            reports.append({"turn":index+1,"expectedIntent":intent,"actualIntent":demo.metrics.get("intent"),
                "toolsExpected":expected,"toolsActual":actual,"toolPass":expected==actual,
                "answerPass":content_pass,"error":error, "tokens":demo.metrics["tokens"],
                "latencyMs":round((time.monotonic()-start)*1000)})
    finally:
        runtime.reset(token)
        config.MODE = previous
    latencies = sorted(r["latencyMs"] for r in reports)
    return {"mode":"live" if live else "offline","commerce":"fixed fixture","cases":len(reports),
        "passed":sum(r["toolPass"] and r["answerPass"] for r in reports),
        "toolAccuracy":sum(r["toolPass"] for r in reports)/len(reports),
        "answerAccuracy":sum(r["answerPass"] for r in reports)/len(reports),
        "tokens":sum(r["tokens"] for r in reports),"latencyP50Ms":statistics.median(latencies),
        "latencyP95Ms":latencies[round((len(latencies)-1)*.95)],"turns":reports}

if __name__ == "__main__":
    report = run()
    text = json.dumps(report,ensure_ascii=False,indent=2)
    print(text)
    if REPORT_PATH:
        Path(REPORT_PATH).write_text(text+"\n")
    raise SystemExit(int(report["passed"] != report["cases"]))
