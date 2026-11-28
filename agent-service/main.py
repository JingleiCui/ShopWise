"""ShopWise Agent. Start with uv run python main.py."""
import hmac
import asyncio
import logging
from contextlib import suppress
import re
import time
from contextlib import asynccontextmanager
from threading import Lock
from typing import Literal, TypedDict

import httpx
import psycopg
import uvicorn
from fastapi import FastAPI, Header, HTTPException, Request
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool
from pydantic import BaseModel, Field

import config
import discovery
from adapters import Tools, runtime
from shopping import api, execute, part, result

class State(TypedDict, total=False):
    message: str
    request: str
    filters: dict
    ids: list
    pending: str
    order: str
    response: dict
    cache: dict

class Turn(BaseModel):
    threadId: str = Field(min_length=1,max_length=200)
    message: str = Field(default="",max_length=4000)
    requestId: str = Field(min_length=1,max_length=150)
    confirmedQuoteId: str | None = Field(default=None,max_length=150)

def respond(state, update):
    data = {**update["response"], "mode":runtime.get().metrics["mode"]}
    cache = dict(list(state.get("cache",{}).items())[-15:])
    cache[state["request"]] = data
    return {**update,"response":data,"cache":cache}

def act(state):
    return respond(state, execute(state))

def wait_quote(state):
    outcome = interrupt({"quoteId":state["pending"]})
    if outcome.get("order"):
        return {"pending":"","order":outcome["order"]}
    return {"pending":""}

def graph_for(saver):
    builder = StateGraph(State)
    builder.add_node("assist",act)
    builder.add_node("approval",wait_quote)
    builder.add_edge(START,"assist")
    builder.add_conditional_edges("assist", lambda state: "approval" if state.get("pending") else END, {"approval":"approval", END:END})
    builder.add_edge("approval",END)
    return builder.compile(checkpointer=saver)

@asynccontextmanager
async def lifespan(app):
    with ConnectionPool(config.DATABASE_URL,min_size=1,max_size=8,
                        kwargs={"autocommit":True,"prepare_threshold":0,"row_factory":dict_row}) as pool:
        saver = PostgresSaver(pool)
        saver.setup()
        app.state.graph = graph_for(saver)
        app.state.discovery = discovery.graph_for(saver)
        app.state.pool=pool
        task=asyncio.create_task(expire_discovery(pool,saver))
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task

app = FastAPI(title="ShopWise Agent",lifespan=lifespan)
locks = [Lock() for _ in range(128)]
discovery_active=set()
discovery_guard=Lock()

def try_discovery_thread(thread):
    with discovery_guard:
        if thread in discovery_active:
            return False
        discovery_active.add(thread)
        return True

def release_discovery_thread(thread):
    with discovery_guard:
        discovery_active.remove(thread)
LOG=logging.getLogger("uvicorn.error")

def expire_threads(pool,saver):
    with pool.connection(timeout=1) as conn:
        rows=conn.execute("""
            select thread_id from checkpoints where thread_id like 'discovery/%'
            group by thread_id having max((checkpoint->>'ts')::timestamptz)<now()-interval '24 hours'
            limit 100
        """).fetchall()
    for row in rows:
        thread=row["thread_id"]
        if not try_discovery_thread(thread):
            continue
        try:
            with pool.connection(timeout=1) as conn:
                fresh=conn.execute("""
                    select 1 from checkpoints where thread_id=%s
                    and (checkpoint->>'ts')::timestamptz>=now()-interval '24 hours' limit 1
                """,(thread,)).fetchone()
            if not fresh:
                saver.delete_thread(thread)
        finally:
            release_discovery_thread(thread)

async def expire_discovery(pool,saver):
    while True:
        try:
            await asyncio.to_thread(expire_threads,pool,saver)
        except Exception as failure:
            LOG.warning("checkpoint retention failed type=%s",type(failure).__name__)
        await asyncio.sleep(300)

class OfferReference(BaseModel):
    provider: Literal["shopify", "ebay"]
    variantId: str = Field(min_length=1, max_length=220)


class SearchTurn(BaseModel):
    threadId: str = Field(min_length=1,max_length=64)
    message: str = Field(min_length=1,max_length=400)
    references: list[OfferReference] = Field(default_factory=list, max_length=4)

@app.post("/discovery/turn")
def discovery_turn(turn: SearchTurn, request: Request, x_service_key: str = Header(default=""),
                   x_request_id: str = Header(default="")):
    if not hmac.compare_digest(x_service_key.encode(),config.SERVICE_KEY.encode()):
        raise HTTPException(403,"需要服务身份")
    request_id=x_request_id if re.fullmatch(r"[0-9a-f-]{36}",x_request_id) else None
    thread="discovery/"+turn.threadId
    if not try_discovery_thread(thread):
        raise HTTPException(409,"导购会话正在处理")
    try:
        references = [(ref.provider, ref.variantId) for ref in turn.references]
        if len(set(references)) != len(references) or any(not variant.strip() for _, variant in references):
            raise HTTPException(400, "请选择不同的商品规格")
        response=discovery.turn(request.app.state.discovery,turn.threadId,turn.message,request_id,references)
    finally:
        release_discovery_thread(thread)
    LOG.info("discovery requestId=%s model=%s routing=%s outcome=%s degradation=%s tokens=%s",
             response["requestId"],config.MODEL,response["routing"],response["status"],response["degradation"],response["tokens"])
    return response

@app.get("/health/live")
def live():
    return {"status":"ok"}

@app.get("/health/ready")
def ready(request: Request):
    try:
        with request.app.state.pool.connection(timeout=.5) as conn:
            conn.execute("select 1")
    except (psycopg.Error, TimeoutError):
        raise HTTPException(503,"检查点数据库暂不可用") from None
    return {"status":"ok"}

@app.get("/health")
def health():
    return {"status":"ok","mode":configured_mode(),"checkpoint":"postgresql"}

def configured_mode():
    return "offline" if config.MODE=="offline" or (config.MODE=="auto" and not config.API_KEY) else "live"

def process(graph, turn):
    cfg = {"configurable":{"thread_id":turn.threadId}}
    snapshot = graph.get_state(cfg)
    state = snapshot.values
    pending = state.get("pending")
    cached = state.get("cache",{}).get(turn.requestId)
    if cached:
        return cached
    if turn.confirmedQuoteId and pending and turn.confirmedQuoteId != pending:
        raise HTTPException(409,"确认的报价与此对话待确认报价不一致。")
    if pending or turn.confirmedQuoteId:
        qid = pending or turn.confirmedQuoteId
        q = api("GET","/quotes/"+part(qid))
        oid = q.get("orderId")
        if turn.confirmedQuoteId and not oid:
            raise HTTPException(409,"商城尚未记录该报价的确认。")
        if pending and (oid or not re.search(r"^(确认|确认订单|确定|好|继续|下单|帮我下单)[。！! ]*$",turn.message)):
            graph.invoke(Command(resume={"order":oid}),cfg)
        if oid and (turn.confirmedQuoteId or re.search(r"^(确认|确认订单|确定|好|继续|下单|帮我下单|)[。！! ]*$",turn.message)):
            return result(f"订单 {oid} 已创建。可在订单页查看支付和履约进度。",mode=configured_mode())
        if pending and not oid and re.search(r"^(确认|确认订单|确定|好|继续|下单|帮我下单)[。！! ]*$",turn.message):
            return result("请在报价卡片点击确认订单。",quote=q,mode=configured_mode())
        if re.search(r"取消报价|先不买|暂不下单|放弃|不下单|取消下单",turn.message):
            return result("已退出本次报价，可继续调整购物车。",mode=configured_mode())
    value = graph.invoke({"message":turn.message,"request":turn.requestId,"pending":""},cfg)
    return value["response"]

@app.post("/turn")
def turn_endpoint(turn: Turn, request: Request, x_service_key: str = Header(default=""),x_shopwise_grant: str = Header(default="")):
    if not hmac.compare_digest(x_service_key.encode(),config.SERVICE_KEY.encode()) or not x_shopwise_grant:
        raise HTTPException(403,"需要已认证的商城会话。")
    start = time.monotonic()
    metrics = {"mode":configured_mode(),"tools":[],"tokens":0}
    tools = Tools(x_shopwise_grant,metrics,turn.requestId)
    token = runtime.set(tools)
    try:
        with locks[hash(turn.threadId)%len(locks)]:
            answer = process(request.app.state.graph,turn)
    except HTTPException:
        raise
    except httpx.HTTPStatusError as error:
        status = error.response.status_code
        if status >= 500:
            raise HTTPException(503,"商城工具暂时不可用，请稍后重试。") from None
        details = {}
        try:
            details = error.response.json()
        except ValueError:
            pass
        answer = result(str(details.get("message") or details.get("detail") or "商城拒绝了本次操作，请核对登录状态、购物车和数量。")[:350],mode=metrics["mode"])
        metrics["failure"] = "commerce_rejected"
    except Exception as error:
        metrics["failure"] = type(error).__name__
        # No exception strings, headers or upstream request bodies in responses/logs.
        raise HTTPException(503,detail={"message":"模型或工具暂时不可用，请稍后重试。","metrics":metrics}) from None
    finally:
        runtime.reset(token)
    return {**answer,"external":tools.external,"metrics":{**metrics,"latencyMs":round((time.monotonic()-start)*1000)}}

if __name__ == "__main__":
    uvicorn.run(app,host=config.HOST,port=config.PORT,access_log=False)
