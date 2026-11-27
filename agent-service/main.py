"""ShopWise Agent. Start with uv run python main.py."""
import hmac
import re
import time
from contextlib import asynccontextmanager
from threading import Lock
from typing import TypedDict

import httpx
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
        yield

app = FastAPI(title="ShopWise Agent",lifespan=lifespan)
locks = [Lock() for _ in range(128)]

class SearchTurn(BaseModel):
    threadId: str = Field(min_length=1,max_length=64)
    message: str = Field(min_length=1,max_length=400)

@app.post("/discovery/turn")
def discovery_turn(turn: SearchTurn, request: Request, x_service_key: str = Header(default="")):
    if not hmac.compare_digest(x_service_key.encode(),config.SERVICE_KEY.encode()):
        raise HTTPException(403,"需要服务身份")
    return discovery.turn(request.app.state.discovery,turn.threadId,turn.message)

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
