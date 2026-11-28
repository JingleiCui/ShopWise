"""Read-only shopping graph. Vendor results live only in the current request."""
import re
import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
from urllib.parse import urlsplit
from contextvars import ContextVar
from typing import TypedDict

import httpx
from langgraph.graph import END, START, StateGraph

import config
from intents import choose, offline, SPECS, USES, WORDS

class DiscoveryState(TypedDict, total=False):
    turn: str
    filters: dict
    refs: list
    action: str
    note: str
    indices: list[int]

live_cards: ContextVar[list] = ContextVar("live_cards")
live_matches: ContextVar[list] = ContextVar("live_matches")
user_message: ContextVar[str] = ContextVar("user_message")
turn_meta: ContextVar[dict] = ContextVar("turn_meta")
product_refs: ContextVar[list] = ContextVar("product_refs")
TERMS = {"阅读灯":"desk lamp","台灯":"desk lamp","背包":"backpack","手机壳":"phone case",
         "充电器":"charger","运动鞋":"sneakers","耳机":"headphones","收纳盒":"storage box",
         "保温杯":"insulated cup","键盘":"keyboard","鼠标":"mouse","夹克":"jacket","外套":"coat"}

PRIVATE = re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|(?:\d[\s+-]*){7,}|https?://\S+|(?:详细地址|手机号|身份证|门牌)\s*[:：]?[^，。；,;]+|[\u4e00-\u9fff]{2,}(?:路|街道|巷)\s*\d+\s*号")

def external_text(text):
    return PRIVATE.sub(" ",text).strip()

def compare_indices(message):
    digits={c:i for i,c in enumerate("零一二三四五六七八九十")}
    ranks=re.findall(r"第\s*([0-9]{1,2}|[一二三四五六七八九十])", message)
    return list(dict.fromkeys(int(rank) if rank.isdigit() else digits[rank] for rank in ranks))

def route(state):
    if product_refs.get():
        return {"action": "inspect", "indices": [], "note": ""}
    msg=external_text(user_message.get())
    filters={k:v for k,v in state.get("filters",{}).items() if k not in {"keyword","spec","use"} or not PRIVATE.search(str(v))}
    metrics=turn_meta.get()
    explicit=offline(msg)
    try:
        intent=choose(msg,{k:v for k,v in filters.items() if k!="city"},metrics,timeout=2)
    except Exception as error:
        metrics.update(mode="offline",degradation="model_timeout" if "timeout" in type(error).__name__.lower() else "model_unavailable")
        intent=explicit
    new_product = re.search(r"^(?:换成|换个|换一种|换看|改看|改搜|想改看)\s*(.+)", msg)
    term=next((en for zh,en in TERMS.items() if zh in msg),None) or (intent.keyword if intent.keyword in WORDS and intent.keyword in msg else None)
    if term and term != filters.get("keyword") or new_product:
        for key in ("keyword", "spec", "use", "category"):
            filters.pop(key, None)
    if new_product and not term:
        return {"filters": filters, "action": "clarify", "refs": [], "indices": [],
                "note": "已切换商品。请提供商品的英文名或型号，也可直接用页面搜索。"}
    for key in ("budget","spec","use","inStock","category"):
        val=getattr(explicit,key)
        if val is None:
            val=getattr(intent,key)
        if key=="spec" and val not in SPECS or key=="use" and val not in USES:
            continue
        if val is not None:
            filters[key]=val
    if re.search("预算不限|不限预算",msg):
        filters.pop("budget",None)
    city=re.search(r"(?:寄到|送到|收货地[为是]?|配送至)\s*([\u4e00-\u9fff]{2,8}?)(?:市|[，,。、\s]|的|$)",msg)
    if city:
        filters["city"]=city[1]
    if "到手" in msg:
        filters["landed"]=True
    if term and not re.search(r"[@:/]|\d{5,}",term):
        filters["keyword"]=term
    if not filters.get("keyword") and re.fullmatch(r"[a-zA-Z][a-zA-Z -]{1,55}",msg):
        filters["keyword"]=msg
    # Explicit commercial and support actions never run on this read-only graph.
    action = explicit.action if explicit.action not in {"search", "external"} else intent.action
    action="compare" if action=="compare" else ("search" if action in {"search","external"} else "help")
    return {"filters":filters,"action":action,"indices":compare_indices(msg),"note":""}

def get(path,params):
    if urlsplit(config.COMMERCE_URL).hostname not in config.COMMERCE_HOSTS:
        raise RuntimeError("invalid commerce host")
    meta=turn_meta.get()
    remaining=max(.1,meta["deadline"]-time.monotonic())
    r=httpx.get(config.COMMERCE_URL+"/api/v1/discovery"+path,params=params,timeout=min(remaining,7.8),
                headers={"X-Request-Id":meta["requestId"]},follow_redirects=False,trust_env=False)
    r.raise_for_status()
    return r.json()

def fetch(state):
    if state["action"] == "inspect":
        turn_meta.get()["answer"] = inspect_products(product_refs.get(), user_message.get())
        return {}
    if state["action"] == "clarify":
        return {}
    filters=state["filters"]
    cards=live_cards.get()
    if state["action"]=="help":
        return {"note":"这里可查跨境商品、调整筛选条件并比较报价。购买和售后请到对应商家办理。"}
    if state["action"]=="compare":
        available=state.get("refs",[])
        indices=state.get("indices",[])
        if indices and (len(indices)<2 or len(indices)>4 or min(indices)<1 or max(indices)>len(available)):
            return {"note":"请按上一轮结果中的序号选择 2 到 4 款商品。"}
        refs=[available[i-1] for i in indices] if indices else available[:2]
        if len(refs)<2:
            return {"note":"请先搜索并选出至少两款商品。"}
        meta=turn_meta.get()
        by_ref = lookup_references(refs)
        if filters.get("inStock"):
            by_ref = {ref: offer for ref, offer in by_ref.items() if offer.get("available") is True}
        visible=[ref for ref in refs if tuple(ref) in by_ref]
        cards.extend(by_ref[tuple(ref)] for ref in visible)
        if len(cards)<len(refs):
            meta["degradation"]="source_partial"
        note="已重新核对候选规格与商品报价。运费及进口税费由商家确认。"
        if len(cards)<2:
            note="可复价的商品不足两款，请重新搜索或更换候选。"
        elif len(cards)<len(refs):
            note+=" 部分候选暂时无法复价。"
        return {"refs":visible,"note":note}
    if not filters.get("keyword"):
        return {"note":"请告诉我商品类型，例如阅读灯、耳机或背包。"}
    descriptors={"阅读":"reading","可调光":"dimmable","黑色":"black","白色":"white","纯棉":"cotton",
                 "运动":"sports","办公":"office","防水":"waterproof","无线":"wireless","降噪":"noise cancelling"}
    safe_keyword=external_text(str(filters["keyword"]))
    if not safe_keyword or len(safe_keyword)>80:
        return {"note":"请提供用于检索的商品名称。"}
    query=" ".join([safe_keyword]+[descriptors.get(filters[k],filters[k]) for k,allowed in
        (("spec",SPECS),("use",USES)) if filters.get(k) in allowed])
    args={"q":query}
    if "budget" in filters:
        args["budget"]=filters["budget"]
    try:
        batch=get("",args)
    except httpx.HTTPError:
        turn_meta.get()["degradation"]="catalog_unavailable"
        return {"refs":[],"note":"商品来源暂不可用，请稍后重试。"}
    turn_meta.get()["sources"]={source["provider"]:source["status"] for source in batch["sources"]}
    for source in batch["sources"]:
        cards.extend(o for o in source["offers"] if not filters.get("inStock") or o.get("available") is True)
    live_matches.get().extend(batch.get("matches", []))
    refs=[[o["provider"],o["variantId"]] for o in cards[:16]]
    failed=[s["provider"] for s in batch["sources"] if s["status"] not in {"ok","empty","disabled"}]
    note="按中国大陆收货查询。具体地址、运费与进口税费待商家确认。"
    available_source=any(s["status"] in {"ok","empty"} for s in batch["sources"])
    if failed and not available_source:
        turn_meta.get()["degradation"]="catalog_unavailable"
        note="当前商品来源暂不可用，请稍后重试。"
    elif not refs:
        note="暂时没有可展示的候选，可以调整商品类型或预算。"
    if failed:
        if available_source:
            turn_meta.get()["degradation"]="source_partial"
        note+=" 以下来源暂不可用："+"、".join(failed)+"。"
    return {"refs":refs,"note":note}

def lookup_references(refs):
    meta = turn_meta.get()
    shopify_ids = [variant for provider, variant in refs if provider == "shopify"]
    requests = [
        (f"/{provider}/lookup", {"id": variant})
        for provider, variant in refs
        if provider != "shopify" or len(shopify_ids) == 1
    ]
    if len(shopify_ids) > 1:
        requests.append(("/shopify/compare", {"id": shopify_ids}))

    def lookup(work):
        token = turn_meta.set(meta)
        try:
            return get(*work).get("offers", [])
        except httpx.HTTPError:
            return []
        finally:
            turn_meta.reset(token)

    with ThreadPoolExecutor(max_workers=len(requests)) as pool:
        return {(offer["provider"], offer["variantId"]): offer
                for offers in pool.map(lookup, requests) for offer in offers}


def inspect_products(refs, question):
    meta = turn_meta.get()
    cards = live_cards.get()
    found = lookup_references(refs)
    cards.extend(found[ref] for ref in refs if ref in found)
    if not cards:
        meta["degradation"] = "catalog_unavailable"
        return "暂时无法核对所选商品，请稍后重试。"

    question = external_text(question)
    show_price = bool(re.search("价格|便宜|预算|多少钱|报价", question))
    show_stock = bool(re.search("库存|有货|缺货|现货", question))
    show_dimming = bool(re.search("调光|亮度", question))
    show_shipping = bool(re.search("运费|税费|到手|配送|发货|寄到|到货", question))
    lines = []
    if show_shipping:
        lines.append("运费、进口税费及具体地址的配送条件由商家确认。")
    if not any((show_price, show_stock, show_dimming, show_shipping)):
        lines.append("以下是商家目前提供、可直接比较的信息。")
    for offer in cards:
        title = offer.get("title") or "所选商品"
        attributes = offer.get("attributes") or {}
        parts = []
        if show_dimming:
            facts = [f"{key} {value}" for key, value in attributes.items()
                     if re.search(r"调光|亮度|dimm|brightness", key, re.I)]
            variant = offer.get("variant") or ""
            if not facts and re.search(r"调光|亮度|dimm|brightness", variant, re.I):
                facts.append(variant)
            parts.append(" / ".join(facts) if facts else "商家未提供可核实的调光规格")
        if show_price or not any((show_stock, show_dimming, show_shipping)):
            currency, amount = offer.get("currency"), offer.get("price")
            parts.append(f"商品价 {currency} {amount}" if currency and amount is not None else "商品价待确认")
        if show_stock:
            parts.append({True: "商家显示有货", False: "商家显示缺货"}.get(offer.get("available"), "库存待核实"))
        if not any((show_price, show_stock, show_dimming, show_shipping)):
            parts.append(offer.get("variant") or "商家未提供规格")
        if parts:
            lines.append(f"{title}：{'；'.join(parts)}。")
    if len(cards) < len(refs):
        meta["degradation"] = "source_partial"
        lines.append("部分所选商品暂时无法核对。")
    return "\n".join(lines)


def graph_for(saver):
    builder=StateGraph(DiscoveryState)
    builder.add_node("understand",route)
    builder.add_node("read_catalog",fetch)
    builder.add_edge(START,"understand")
    builder.add_edge("understand","read_catalog")
    builder.add_edge("read_catalog",END)
    return builder.compile(checkpointer=saver)

def turn(graph,thread,message,request_id=None,references=None):
    meta={"requestId":request_id or uuid4().hex,"deadline":time.monotonic()+8.2,"tokens":0,"mode":"offline"}
    meta_token=turn_meta.set(meta)
    cards=[]
    token=live_cards.set(cards)
    message_token=user_message.set(message)
    matches=[]
    match_token=live_matches.set(matches)
    refs_token=product_refs.set(references or [])
    try:
        state=graph.invoke({"turn":uuid4().hex},{"configurable":{"thread_id":"discovery/"+thread}})
        return {"text":meta.get("answer", state["note"]),"filters":state.get("filters", {}),"offers":cards,"matches":matches,
                "requestId":meta["requestId"],"status":"degraded" if meta.get("degradation") else "ok",
                "degradation":meta.get("degradation"),"routing":meta["mode"],"tokens":meta["tokens"],
                "comparison":state["action"]=="compare","sources":meta.get("sources",{}),"evidence":{"shipping":"unknown","tax":"unknown"}}
    finally:
        product_refs.reset(refs_token)
        live_cards.reset(token)
        user_message.reset(message_token)
        live_matches.reset(match_token)
        turn_meta.reset(meta_token)
