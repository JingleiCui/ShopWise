import json
import re
from typing import Literal

from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field

import config

Action = Literal["search", "compare", "cart", "add", "quote", "orders", "policy", "return", "external", "handoff", "help"]

class Intent(BaseModel):
    """Select one permitted store operation; only include parameters explicitly requested."""
    model_config = ConfigDict(extra="forbid")
    action: Action
    category: Literal["数码", "服饰", "家居"] | None = None
    keyword: str | None = Field(None, max_length=80)
    use: str | None = Field(None, max_length=60)
    spec: str | None = Field(None, max_length=60)
    budget: float | None = Field(None, ge=0, le=1e8)
    inStock: bool | None = None

WORDS = ("笔记本","运动鞋","太阳镜","手提包","背包","连衣裙","智能手表","手机壳","手机","平板","沙发","柜子","花盆","椅子","充电器","摄影配件","耳机","键盘","鼠标","音箱","充电宝","显示器","T恤","卫衣","衬衫","外套","夹克","裤","杯","台灯","毛毯","毛巾","床品","枕","收纳")
USES = ("通勤","运动","办公","游戏","阅读","旅行","学习","睡眠")
SPECS = ("主动降噪","降噪","无线","有线","轻量","防水","纯棉","黑色","白色","蓝色","保温","可调光")
EXTERNAL = r"外部|站外|全网|shopify|其他(?:店|商家)|别家"
ADD = r"加入|加到|放入|加购|放进"
QUOTE = r"结算|报价|下单|买单"
RETURN = r"(申请|提交|发起|办理).{0,4}退[货款]"
POLICY = r"售后|政策|规则|保修|质保|退货|退款|包邮|运费|配送范围|几天送"

def offline(message):
    lower = message.lower()
    routes = [
        ("handoff", r"人工|客服介入"), ("external", EXTERNAL),
        ("policy", r"(?:能不能|能否|可以|如何|怎么|能).{0,6}退|(?:申请|提交).{0,4}退[货款].*[吗？?]"),
        ("return", RETURN), ("policy", POLICY), ("add", ADD), ("quote", QUOTE),
        ("cart", r"购物车"), ("orders", r"订单|物流|签收|配送进度|发货|履约|快递|到哪"),
        ("compare", r"比较|对比|区别|哪款更|哪个好|比一比"), ("help", r"^(你好|您好|帮助|hello)[！!。]?$"),
    ]
    action = next((a for a, rx in routes if re.search(rx, lower, re.I)), "search")
    budget = re.search(r"(?:预算|不超过|最多|上限|控制在|降到|提高到|改成|改为|改到)[^\d]{0,8}(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*(?:元|块)?(?:以内|以下|内)", message)
    stock = False if re.search("包括缺货|不限库存", message) else (True if re.search("有货|现货|有库存", message) else None)
    return Intent(action=action, category=next((x for x in ("数码","服饰","家居") if x in message), None),
        keyword=next((x for x in WORDS if x.lower() in lower), None),
        use=next((x for x in USES if x in message), None),
        spec=next((x for x in SPECS if x in message), None),
        budget=min(1e8, float(budget.group(1) or budget.group(2))) if budget else None, inStock=stock)

def choose(message, filters, metrics, *, timeout=18):
    if config.MODE == "offline" or (config.MODE == "auto" and not config.API_KEY):
        metrics["mode"] = "offline"
        return offline(message)
    metrics["mode"] = "live"
    client = ChatOpenAI(model=config.MODEL, api_key=config.API_KEY, base_url=config.MODEL_URL,
                        temperature=0, timeout=timeout, max_retries=0)
    result = client.bind_tools([Intent], tool_choice="Intent").invoke([
        ("system", "你是商店意图路由器。调用 Intent 一次，参数只含本轮明确条件。关键词是商品类型，用途/规格分开。售后咨询用 policy，明确要求提交退货才用 return。站内默认，明确外部请求才用 external。"),
        ("user", json.dumps({"历史筛选":filters, "本轮":message}, ensure_ascii=False))])
    metrics["tokens"] = (result.usage_metadata or {}).get("total_tokens", 0)
    if len(result.tool_calls) != 1 or result.tool_calls[0]["name"] != "Intent":
        raise ValueError("invalid model intent")
    return Intent.model_validate(result.tool_calls[0]["args"])
