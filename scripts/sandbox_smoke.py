"""Read-only Shopify and eBay sandbox checks. Configuration values are never printed."""
import argparse
import base64
import json
import os
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

from dev import ROOT, read_env


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(url, headers=None, data=None):
    req = Request(url, data=data, headers=headers or {})
    opener = build_opener(NoRedirect()) if headers or data is not None else build_opener()
    with opener.open(req, timeout=12) as response:
        return json.load(response)


def retry_catalog(action):
    for attempt in range(3):
        result = action()
        if result["status"] not in {"busy", "timeout", "unavailable"} or attempt == 2:
            return result
        time.sleep(16 if result["status"] == "busy" else attempt + 1)


def local_catalog(port, query):
    base = f"http://127.0.0.1:{port}/api/v1/discovery"
    def search():
        batch = request(base + "?" + urlencode({"q": query}))
        if batch["destination"] != "CN":
            raise ValueError("unexpected query destination")
        return next(row for row in batch["sources"] if row["provider"] == "shopify")

    source = retry_catalog(search)
    if source["status"] != "ok" or not source["offers"]:
        raise ValueError("Shopify local search: " + source["status"])
    print(f"PASS Shopify Catalog search · {len(source['offers'])} offers · CN")
    variant = source["offers"][0]["variantId"]
    for action in ("lookup", "details"):
        row = retry_catalog(lambda: request(f"{base}/shopify/{action}?" + urlencode({"id": variant})))
        if row["status"] != "ok" or not any(o["variantId"] == variant for o in row["offers"]):
            raise ValueError("Shopify variant " + action + ": " + row["status"])
        print(f"PASS Shopify Catalog {action} · variant retained")


def shopify_dev(env):
    shop = env.get("SHOPIFY_PILOT_SHOP", "")
    if not shop:
        print("SKIP Shopify development store · missing SHOPIFY_PILOT_SHOP")
        return
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*\.myshopify\.com", shop):
        raise ValueError("Invalid Shopify development store domain")
    base = "https://" + shop
    storefront = env.get("SHOPIFY_STOREFRONT_TOKEN")
    if storefront:
        data = request(base + "/api/2026-10/graphql.json", {
            "Content-Type": "application/json", "Shopify-Storefront-Private-Token": storefront
        }, json.dumps({"query": "query @inContext(country: CN) { products(first: 1) { nodes { id } } }"}).encode())
        if data.get("errors") or "products" not in data.get("data", {}):
            raise ValueError("Shopify development Storefront GraphQL rejected the scoped query")
        print(f"PASS Shopify development Storefront CN · {len(data['data']['products']['nodes'])} visible products")
    else:
        print("SKIP Shopify development Storefront · missing SHOPIFY_STOREFRONT_TOKEN")
    oidc = request(base + "/.well-known/openid-configuration")
    if not oidc.get("authorization_endpoint") or not oidc.get("token_endpoint"):
        raise ValueError("Shopify Customer Accounts OIDC metadata incomplete")
    print("PASS Shopify Customer Accounts discovery")
    admin = env.get("SHOPIFY_ADMIN_TOKEN")
    if env.get("SHOPIFY_ADMIN_CLIENT_ID") and env.get("SHOPIFY_ADMIN_CLIENT_SECRET"):
        issued = request(base + "/admin/oauth/access_token", {"Content-Type": "application/json"},
                         json.dumps({"client_id": env["SHOPIFY_ADMIN_CLIENT_ID"],
                                     "client_secret": env["SHOPIFY_ADMIN_CLIENT_SECRET"],
                                     "grant_type": "client_credentials"}).encode())
        admin = issued["access_token"]
    if admin:
        data = request(base + "/admin/api/2026-10/graphql.json", {
            "Content-Type": "application/json", "X-Shopify-Access-Token": admin
        }, json.dumps({"query": "{ orders(first: 1) { nodes { id } } }"}).encode())
        if data.get("errors") or "orders" not in data.get("data", {}):
            raise ValueError("Shopify Admin orders query rejected; verify read_orders and protected data access")
        print("PASS Shopify Admin · orders read")
    else:
        print("SKIP Shopify Admin · missing Admin app credentials or token")


def ebay_sandbox(env, query):
    settings = ("EBAY_CLIENT_ID", "EBAY_CLIENT_SECRET")
    missing = [key for key in settings if not env.get(key)]
    if missing:
        print("SKIP eBay Sandbox · missing " + ", ".join(missing))
        return
    if env.get("EBAY_SANDBOX", "true").lower() != "true":
        raise ValueError("The smoke check accepts sandbox credentials only")
    base = "https://api.sandbox.ebay.com"
    basic = base64.b64encode(":".join(env[key] for key in settings).encode()).decode()
    token = request(base + "/identity/v1/oauth2/token", {
        "Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"
    }, urlencode({"grant_type": "client_credentials", "scope": "https://api.ebay.com/oauth/api_scope"}).encode())
    headers = {"Authorization": "Bearer " + token["access_token"], "X-EBAY-C-MARKETPLACE-ID": "EBAY_US"}
    print("PASS eBay Sandbox application token")
    browse = base + "/buy/browse/v1"
    listing = request(browse + "/item_summary/search?" + urlencode({"q": query, "limit": 5}), headers)
    china = request(browse + "/item_summary/search?" + urlencode({
        "q": query, "limit": 5, "filter": "deliveryCountry:CN"
    }), {**headers, "X-EBAY-C-ENDUSERCTX": "contextualLocation=country%3DCN"})
    print(f"PASS eBay Sandbox search · baseline {len(listing.get('itemSummaries', []))} · CN {len(china.get('itemSummaries', []))}")
    candidates = china.get("itemSummaries") or listing.get("itemSummaries") or []
    item_id = env.get("EBAY_SANDBOX_ITEM_ID") or (candidates[0]["itemId"] if candidates else "")
    if not item_id:
        print("SKIP eBay Sandbox details · no listing; configure EBAY_SANDBOX_ITEM_ID or change --query")
        return
    details = request(browse + "/item/" + quote(item_id, safe=""), headers)
    if details.get("itemId") != item_id:
        raise ValueError("eBay Sandbox details returned another item")
    batch = request(browse + "/item?" + urlencode({"item_ids": item_id}), headers)
    if not any(entry.get("itemId") == item_id for entry in batch.get("items", [])):
        raise ValueError("eBay Sandbox batch details missing requested item")
    print("PASS eBay Sandbox single and batch details")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=15173)
    parser.add_argument("--query", default="desk lamp")
    parser.add_argument("--ebay-query", help="Use a separate known listing term for eBay Sandbox")
    args = parser.parse_args()
    config = {**read_env(ROOT / ".env.local"), **os.environ}
    checks = (("ShopWise", lambda: local_catalog(args.port, args.query)),
              ("Shopify development store", lambda: shopify_dev(config)),
              ("eBay Sandbox", lambda: ebay_sandbox(config, args.ebay_query or args.query)))
    failed = False
    for name, check in checks:
        try:
            check()
        except HTTPError as error:
            print(f"FAIL {name} · HTTP {error.code}")
            failed = True
        except (URLError, OSError, KeyError, ValueError) as error:
            detail = str(error) if type(error) is ValueError else type(error).__name__
            print(f"FAIL {name} · {detail}")
            failed = True
    raise SystemExit(int(failed))
