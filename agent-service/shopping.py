import json
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

from adapters import runtime, shopify_search
from intents import ADD, EXTERNAL, QUOTE, RETURN, choose

STATES = {"PENDING_PAYMENT":"待支付","PAID":"已支付","SHIPPED":"已发货","COMPLETED":"已签收","CANCELLED":"已取消","EXPIRED":"已超时关闭"}
NUMBERS = {"一":1,"两":2,"二":2,"三":3,"四":4,"五":5,"六":6,"七":7,"八":8,"九":9,"十":10}

def number(value):
    return NUMBERS[value] if value in NUMBERS else int(value)

def result(text, **kwargs):
    return {"text":text, "products":[], "external":[], **kwargs}

def api(method, path, **kwargs):
    return runtime.get().call(method, path, **kwargs)

def part(value):
    return quote(str(value), safe="")

def quantity(message):
    match = re.search(r"(\d+|[一两二三四五六七八九十])\s*(?:件|个|盏|份|台|套|副|条)|数量\s*[:：为]?\s*(\d+)",message)
    return number(match.group(1) or match.group(2)) if match else 1

def filters_for(old, intent, message):
    new = {k:v for k,v in intent.model_dump().items() if k != "action" and v is not None}
    if any(k in new and old.get(k) and old[k] != new[k] for k in ["category","keyword"]):
        old = {k:v for k,v in old.items() if k in {"budget","inStock"}}
    filters = {**old, **new}
    for key, pattern in {"budget":"不限预算|预算不限|不限制价格","spec":"不限规格|规格不限","use":"不限用途","category":"不限分类|全部分类"}.items():
        if re.search(pattern, message):
            filters.pop(key, None)
    return filters

def catalog(filters):
    mapping = {"category":"category","budget":"maxPrice","inStock":"inStock","keyword":"q"}
    rows = api("GET","/products",params={target:filters[key] for key,target in mapping.items() if key in filters})
    selected = []
    for row in rows:
        text = json.dumps([row.get(k) for k in ("title","description","attributes","skus")],ensure_ascii=False).lower()
        if any(filters.get(k) and filters[k].lower() not in text for k in ("use","spec")):
            continue
        skus = [s for s in row["skus"] if (not filters.get("inStock") or s["stock"] > 0)
                and ("budget" not in filters or float(s["price"]) <= filters["budget"])]
        if skus:
            selected.append({**row,"skus":skus})
    return selected

def candidates(state):
    rows = catalog(state.get("filters",{}))
    by_id = {p["id"]:p for p in rows}
    return [by_id[i] for i in state["ids"] if i in by_id] if state.get("ids") else rows

def positions(message):
    return [number(s)-1 for s in re.findall(r"第\s*(\d+|[一二两三四五六七八九十])\s*[款个件]?",message)]

def compare(state, message):
    rows = candidates(state)
    indexes = positions(message)
    named = [p for p in rows if p["title"] in message]
    if indexes:
        chosen = [rows[i] for i in indexes if 0 <= i < len(rows)]
    elif named:
        chosen = named
    else:
        chosen = rows[:2]
    if len({p["id"] for p in chosen}) < 2:
        return result("请先选出两款同类商品，再告诉我比较哪两款。")
    if len({p["kind"] for p in chosen}) != 1:
        return result("这两款商品类型不同。请指定两款同类型商品。")
    attrs = sorted(set().union(*(p.get("attributes",{}) for p in chosen)))
    lines = [{"label":k,"values":[str(p["attributes"].get(k) or "未提供") for p in chosen]} for k in attrs]
    lines += [{"label":"起售价","values":[f"¥{min(float(s['price']) for s in p['skus']):.2f}" for p in chosen]},
              {"label":"库存","values":[str(sum(s["stock"] for s in p["skus"])) for p in chosen]}]
    return result("对比 " + "、".join(p["title"] for p in chosen) + "。\n" + "\n".join(x["label"]+"："+ " / ".join(x["values"]) for x in lines),
                  products=chosen,comparison={"titles":[p["title"] for p in chosen],"rows":lines})

def add(state, message):
    rows = candidates(state)
    indexes = positions(message)
    by_name = [p for p in rows if p["title"] in message]
    sku_match = re.search(r"SKU\s*[:：#-]?\s*(\d+)\b", message,re.I)
    if sku_match:
        matches = [(p,s) for p in rows for s in p["skus"] if str(s["id"]) == sku_match[1]]
    else:
        products = by_name or ([rows[indexes[0]]] if indexes and 0 <= indexes[0] < len(rows) else rows if not indexes else [])
        if len(products) != 1:
            return result("请指定商品名称、列表中的序号或 SKU 编号。")
        p = products[0]
        labels = [s for s in p["skus"] if s["label"].lower() in message.lower()]
        options = labels or p["skus"]
        if len(options) > 1:
            return result("请选择规格："+ "、".join(f"{s['label']}（SKU {s['id']}）" for s in options),products=[p])
        matches = [(p,s) for s in options]
    if len(matches) != 1:
        return result("无法匹配该 SKU，请核对商品规格。")
    p,s = matches[0]
    amount = quantity(message)
    if amount < 1 or amount > 999:
        return result("请填写 1 到 999 之间的商品数量。")
    api("POST","/cart/items/"+part(s["id"])+"/add",json={"quantity":amount})
    return result(f"已加入购物车：{p['title']} · {s['label']} × {amount}。")

def own_order(message, state):
    orders = api("GET","/orders")
    match = next((o for o in orders if str(o["id"]) in message), None)
    explicit = re.search(r"(?:订单号?\s*[:：#]?\s*|\b)([A-Za-z0-9][A-Za-z0-9-]{7,})",message)
    if explicit and not match:
        return None
    if not match and state.get("order") and not re.search("最新|最近",message):
        match = next((o for o in orders if o["id"] == state["order"]),None)
    if not match and (len(orders) == 1 or re.search("最新|最近",message)):
        match = orders[0] if orders else None
    return api("GET","/orders/"+part(match["id"])) if match else None

def signed_at(order):
    return order.get("receivedAt") or next((e["at"] for e in order.get("events",[]) if e["status"] == "COMPLETED"), None)

def policy(state, message):
    term = next((v for rx,v in [(r"退|售后","退货"),(r"运费|包邮|配送","运费"),(r"支付|取消","支付"),(r"报价|下单","报价")] if re.search(rx,message)), message)
    rules = api("GET","/policies",params={"q":term})
    content = "\n".join(f"{r['content']}〔{r['source']} · {r['title']} v{r['version']}〕" for r in rules)
    if not rules:
        return result("商城规则库未收录这项说明，可联系人工客服。")
    if term == "退货":
        order = own_order(message, state)
        if not order:
            content += "\n未定位到具体订单。请提供本人订单号，以核对签收时间。"
        else:
            signed = signed_at(order)
            if signed:
                at = datetime.fromisoformat(signed.replace("Z","+00:00"))
                days = re.search(r"(\d+)\s*天",content)
                content += f"\n订单 {order['id']} 签收于 {signed}。"
                if days:
                    valid = datetime.now(timezone.utc) <= at + timedelta(days=int(days[1]))
                    content += "仍在申请期限内。" if valid else "已超过规则中的申请期限。"
            else:
                content += f"\n订单 {order['id']} 当前{STATES.get(order['status'],order['status'])}，无签收记录，尚不能按签收时间申请退货。"
    return result(content, citations=rules)

def execute(state):
    message = state["message"]
    metrics = runtime.get().metrics
    intent = choose(message,state.get("filters",{}),metrics)
    action = intent.action
    metrics["intent"] = action
    filters = filters_for(state.get("filters",{}),intent,message)
    updates = {}
    if action in {"quote","return","add","external"} and re.search(r"(不要|不用|别|不想|先不|暂不).{0,8}(?:下单|结算|申请|提交|加购|加入|外部|站外|shopify)",message,re.I):
        return {"response":result("好的，可继续浏览或调整筛选条件。")}
    if action == "search":
        if not filters and message.strip() and not re.search("推荐|商品|看看|全部|有哪些|随便|你好|找一款",message):
            raw = re.sub(r"^(请|帮我|我要|我想|搜索|查找|找找|找|看看|推荐|买)+","",message).strip(" 。，！")
            if raw:
                filters["keyword"] = raw[:80]
        rows = catalog(filters)
        shown = rows[:12]
        updates = {"filters":filters, "ids":[p["id"] for p in shown]}
        response = result(f"符合条件的商品共 {len(rows)} 款。"+ ("可指定序号比较或选择规格。" if rows else "可以调整预算、用途或规格再找。"),products=shown)
    elif action == "compare":
        response = compare(state,message)
        if response["products"]:
            updates["ids"] = [p["id"] for p in response["products"]]
    elif action == "add" and re.search(ADD,message):
        response = add(state,message)
    elif action == "cart":
        cart = api("GET","/cart")
        response = result("购物车："+("、".join(f"{x['title']} · {x['label']} × {x['quantity']}" for x in cart["lines"]) or "暂无商品")+f"。小计 ¥{cart['subtotal']}。")
    elif action == "quote" and re.search(QUOTE,message):
        q = api("POST","/quotes",json={})
        updates["pending"] = str(q["id"])
        response = result("请核对报价中的规格、数量、运费和总额，再点击确认订单。",quote=q)
    elif action == "orders":
        order = own_order(message,state)
        if order:
            updates["order"] = order["id"]
            timeline = " → ".join(f"{STATES.get(e['status'],e['status'])} {e['at']}" for e in order.get("events",[]))
            response = result(f"订单 {order['id']} 当前{STATES.get(order['status'],order['status'])}，总额 ¥{order.get('total','—')}。\n{timeline}"+(f"\n物流单号 {order['tracking']}" if order.get("tracking") else ""))
        else:
            orders = api("GET","/orders")
            response = result("你的订单："+("、".join(f"{o['id']}（{STATES.get(o['status'],o['status'])}）" for o in orders[:10]) or "暂无订单")+"。")
    elif action == "policy":
        response = policy(state,message)
    elif action == "return" and re.search(RETURN,message):
        order = own_order(message,state)
        lines = order.get("lines",[]) if order else []
        sku = re.search(r"sku\s*[:：#]?\s*(\d+)",message,re.I)
        if sku:
            lines = [x for x in lines if str(x["skuId"]) == sku[1]]
        reason = re.search(r"(?:原因|理由)\s*[:：为]?\s*(.+)",message)
        if not order or len(lines) != 1 or not reason:
            response = result("请提供本人订单号、SKU 编号、退货数量和原因，以便登记申请。")
        else:
            receipt = api("POST","/orders/"+part(order["id"])+"/returns",json={"skuId":lines[0]["skuId"],"quantity":quantity(message),"reason":reason[1]})
            response = result(f"退货申请已提交，申请号 {receipt['id']}。")
    elif action == "external" and re.search(EXTERNAL,message,re.I):
        query = re.sub(EXTERNAL, "", intent.keyword or message, flags=re.I).strip(" 查找搜一下看看，。:：") or message
        response = result(shopify_search(query))
    elif action == "handoff":
        response = result("已标记转人工需求，请在当前会话提供订单号和问题描述，店员可在这里回复。",handoff=True)
    else:
        response = result("可以告诉我预算、用途和商品类型，也可以查订单、比较商品或咨询售后规则。")
    return {**updates,"response":response}
