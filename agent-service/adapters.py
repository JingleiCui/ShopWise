import re
import time
from contextvars import ContextVar
from dataclasses import dataclass, field
from urllib.parse import urlparse

import httpx

import config

@dataclass
class Tools:
    grant: str
    metrics: dict
    request_id: str
    external: list = field(default_factory=list)

    def call(self, method, path, **kwargs):
        if urlparse(config.COMMERCE_URL).hostname not in config.COMMERCE_HOSTS:
            raise RuntimeError("invalid commerce host")
        start = time.monotonic()
        metric = {"method":method, "tool":re.sub(r"/[^/]+(?=/returns$)|(?<=/quotes)/[^/]+|(?<=/orders)/[^/]+|(?<=/items)/[^/]+", "/{id}", path)}
        self.metrics["tools"].append(metric)
        try:
            response = httpx.request(method, config.COMMERCE_URL + "/api" + path,
                headers={"X-ShopWise-Grant":self.grant, "Idempotency-Key":self.request_id},
                follow_redirects=False, timeout=config.HTTP_TIMEOUT, trust_env=False, **kwargs)
            metric["status"] = response.status_code
            response.raise_for_status()
            return response.json() if response.content else {}
        finally:
            metric["ms"] = round((time.monotonic() - start) * 1000)

runtime: ContextVar[Tools] = ContextVar("runtime")

def shopify_search(query):
    tools = runtime.get()
    batch = tools.call("GET", "/v1/discovery", params={"q": query[:120]})
    source = next((item for item in batch["sources"] if item["provider"] == "shopify"), None)
    if not source or source["status"] == "disabled":
        return "外部 Shopify 目录尚未启用。"
    if source["status"] not in {"ok", "empty"}:
        return "外部 Shopify 目录暂时无法查询。"
    tools.external.extend(source["offers"])
    return f"按中国大陆收货查询到 {len(tools.external)} 条 Shopify 报价。运费与税费待确认。"
