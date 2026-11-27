"""Read-only shopping graph. Vendor results live only in the current request."""
import re
from uuid import uuid4
from urllib.parse import urlsplit
from contextvars import ContextVar
from typing import TypedDict

import httpx
from langgraph.graph import END, START, StateGraph

import config
from intents import choose, offline

class DiscoveryState(TypedDict, total=False):
    turn: str
    filters: dict
    refs: list
    action: str
    note: str

live_cards: ContextVar[list] = ContextVar("live_cards")
live_matches: ContextVar[list] = ContextVar("live_matches")
user_message: ContextVar[str] = ContextVar("user_message")
TERMS = {"阅读灯":"desk lamp","台灯":"desk lamp","背包":"backpack","手机壳":"phone case",
         "充电器":"charger","运动鞋":"sneakers","耳机":"headphones","收纳盒":"storage box",
         "保温杯":"insulated cup","键盘":"keyboard","鼠标":"mouse","夹克":"jacket","外套":"coat"}

def route(state):
    msg=user_message.get()
    filters=dict(state.get("filters",{}))
    explicit=offline(msg)
    try:
        intent=choose(msg,filters,{"tokens":0})
    except Exception:
        intent=explicit
    for key in ("budget","spec","use","inStock","category"):
        val=getattr(explicit,key)
        if val is None:
            val=getattr(intent,key)
        if val is not None:
            filters[key]=val
    if re.search("预算不限|不限预算",msg):
        filters.pop("budget",None)
    city=re.search(r"(?:寄到|送到|收货地[为是]?|配送至)\s*([\u4e00-\u9fff]{2,8}?)(?:市|[，,。、\s]|的|$)",msg)
    if city:
        filters["city"]=city[1]
    if "到手" in msg:
        filters["landed"]=True
    term=next((en for zh,en in TERMS.items() if zh in msg),None) or intent.keyword
    if term and not re.search(r"[@:/]|\d{5,}",term):
        filters["keyword"]=term
    if not filters.get("keyword") and re.fullmatch(r"[a-zA-Z][a-zA-Z -]{1,55}",msg):
        filters["keyword"]=msg
    action="compare" if intent.action=="compare" else ("search" if intent.action in {"search","external"} else "help")
    return {"filters":filters,"action":action,"note":""}

def get(path,params):
    if urlsplit(config.COMMERCE_URL).hostname not in config.COMMERCE_HOSTS:
        raise RuntimeError("invalid commerce host")
    r=httpx.get(config.COMMERCE_URL+"/api/v1/discovery"+path,params=params,timeout=10,follow_redirects=False,trust_env=False)
    r.raise_for_status()
    return r.json()

def fetch(state):
    filters=state["filters"]
    cards=live_cards.get()
    if state["action"]=="help":
        return {"note":"这里可查跨境商品、调整筛选条件并比较报价。购买和售后请到对应商家办理。"}
    if state["action"]=="compare":
        refs=state.get("refs",[])[:2]
        if len(refs)<2:
            return {"note":"请先搜索并选出至少两款商品。"}
        for provider,variant in refs:
            source=get("/"+provider+"/lookup",{"id":variant})
            cards.extend(source["offers"])
        return {"note":"已重新核对候选规格与商品报价。运费及进口税费由商家确认。" if len(cards)>=2 else "部分候选暂时无法复价，可重新搜索。"}
    if not filters.get("keyword"):
        return {"note":"请告诉我商品类型，例如阅读灯、耳机或背包。"}
    descriptors={"阅读":"reading","可调光":"dimmable","黑色":"black","白色":"white","纯棉":"cotton",
                 "运动":"sports","办公":"office","防水":"waterproof","无线":"wireless","降噪":"noise cancelling"}
    query=" ".join(descriptors.get(str(filters[k]),str(filters[k])) for k in ("keyword","spec","use") if filters.get(k))
    args={"q":query}
    if "budget" in filters:
        args["budget"]=filters["budget"]
    batch=get("",args)
    for source in batch["sources"]:
        cards.extend(o for o in source["offers"] if not filters.get("inStock") or o.get("available") is True)
    live_matches.get().extend(batch.get("matches", []))
    refs=[[o["provider"],o["variantId"]] for o in cards[:16]]
    failed=[s["provider"] for s in batch["sources"] if s["status"] not in {"ok","empty","disabled"}]
    note="按中国大陆收货查询。运费与进口税费待确认。"
    if not refs:
        note="暂时没有可展示的候选，可以调整商品类型或预算。"
    if failed:
        note+=" 以下来源暂不可用："+"、".join(failed)+"。"
    return {"refs":refs,"note":note}

def graph_for(saver):
    builder=StateGraph(DiscoveryState)
    builder.add_node("understand",route)
    builder.add_node("read_catalog",fetch)
    builder.add_edge(START,"understand")
    builder.add_edge("understand","read_catalog")
    builder.add_edge("read_catalog",END)
    return builder.compile(checkpointer=saver)

def turn(graph,thread,message):
    cards=[]
    token=live_cards.set(cards)
    message_token=user_message.set(message)
    matches=[]
    match_token=live_matches.set(matches)
    try:
        state=graph.invoke({"turn":uuid4().hex},{"configurable":{"thread_id":"discovery/"+thread}})
        return {"text":state["note"],"filters":state["filters"],"offers":cards,"matches":matches}
    finally:
        live_cards.reset(token)
        user_message.reset(message_token)
        live_matches.reset(match_token)
