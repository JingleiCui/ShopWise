import json
import re
import time
from contextvars import ContextVar
from dataclasses import dataclass, field
from decimal import Decimal
from urllib.parse import urlparse

import httpx

import config

@dataclass
class Tools:
    cookie: str
    metrics: dict
    request_id: str
    external: list = field(default_factory=list)

    def call(self, method, path, **kwargs):
        if urlparse(config.COMMERCE_URL).hostname not in {"localhost","127.0.0.1","::1","commerce-api"}:
            raise RuntimeError("invalid commerce host")
        start = time.monotonic()
        metric = {"method":method, "tool":re.sub(r"/[^/]+(?=/returns$)|(?<=/quotes)/[^/]+|(?<=/orders)/[^/]+|(?<=/items)/[^/]+", "/{id}", path)}
        self.metrics["tools"].append(metric)
        try:
            response = httpx.request(method, config.COMMERCE_URL + "/api" + path,
                headers={"Cookie":self.cookie, "Idempotency-Key":self.request_id},
                follow_redirects=False, timeout=config.HTTP_TIMEOUT, trust_env=False, **kwargs)
            metric["status"] = response.status_code
            response.raise_for_status()
            return response.json() if response.content else {}
        finally:
            metric["ms"] = round((time.monotonic() - start) * 1000)

runtime: ContextVar[Tools] = ContextVar("runtime")

def shopify_search(query):
    if not config.SHOPIFY_ENABLED:
        return "外部 Shopify 目录尚未启用。"
    if urlparse(config.SHOPIFY_MCP_URL).scheme != "https":
        raise ValueError("Shopify endpoint requires HTTPS")
    tool = {"method":"POST","tool":"shopify.search_catalog"}
    runtime.get().metrics["tools"].append(tool)
    start = time.monotonic()
    try:
        response = httpx.post(config.SHOPIFY_MCP_URL, headers={"Accept":"application/json, text/event-stream"},
            json={"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_catalog",
                  "arguments":{"meta":{"ucp-agent":{"profile":config.SHOPIFY_AGENT_PROFILE}},
                  "catalog":{"query":query, "context":{"address_country":"US"}, "pagination":{"limit":5}}}}},
            timeout=config.HTTP_TIMEOUT)
        tool["status"] = response.status_code
        response.raise_for_status()
        if "text/event-stream" in response.headers.get("content-type",""):
            payload = next(json.loads(line[5:]) for line in response.text.splitlines() if line.startswith("data:"))
        else:
            payload = response.json()
        result = payload.get("result",{})
        if payload.get("error") or result.get("isError"):
            raise RuntimeError("Shopify search failed")
        content = result.get("structuredContent")
        if content is None:
            content = next((json.loads(item["text"]) for item in result.get("content",[]) if item.get("type")=="text"), {})
        for p in content.get("products",[]):
            v = next(iter(p.get("variants",[])),{})
            link = v.get("url") or p.get("url") or ""
            if urlparse(link).scheme != "https":
                continue
            money = v.get("price") or (p.get("price_range") or {}).get("min") or {}
            currency = money.get("currency")
            # UCP uses ISO 4217 minor units; leave unsupported currencies unpriced.
            decimals = {"USD":2,"CNY":2,"CAD":2,"AUD":2,"EUR":2,"GBP":2,"JPY":0,"KRW":0,"KWD":3}
            price = float(Decimal(str(money["amount"])).scaleb(-decimals[currency])) if currency in decimals and "amount" in money else None
            seller = v.get("seller") or p.get("seller") or {}
            runtime.get().external.append({"title":str(p["title"]),"source":seller.get("name") or "Shopify",
                "url":link,"currency":currency,"price":price})
        return f"外部 Shopify 目录返回 {len(runtime.get().external)} 款商品，售价与库存以商家页面为准。"
    finally:
        tool["ms"] = round((time.monotonic()-start)*1000)
