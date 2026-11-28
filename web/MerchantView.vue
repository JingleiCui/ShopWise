<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, ApiError } from "./store";
import MerchantReturns from "./MerchantReturns.vue";
import StoreHeader from "./StoreHeader.vue";
import StoreFooter from "./StoreFooter.vue";
import { Link2, Package, RotateCcw, MessageSquare } from "lucide-vue-next";
interface Product {id:string;product:string;variant:string;amount:string;currency:string}
interface Quote {id:string;cost:{totalAmount:{amount:string;currencyCode:string}}}
interface Order {id:string;name:string;financial_status:string;fulfillment_status:string;current_total_price:string;currency:string;
  fulfillments:{tracking_company:string;tracking_number:string}[]}
const bound=ref(false), enabled=ref(false), signedIn=ref(false), busy=ref(false), error=ref(""), shopError=ref("");
const orderState:Record<string,string>={paid:"已付款",pending:"待付款",refunded:"已退款",partially_refunded:"部分退款",fulfilled:"已发货",partial:"部分发货",restocked:"已补库存",voided:"已取消"};
const username=ref(""), password=ref(""), quantities=ref<Record<string,number>>({});
interface Ticket {id:string;status:string;reason:string;reply?:string}
const products=ref<Product[]>([]), orders=ref<Order[]>([]), quote=ref<Quote>();
const staff=ref(false), tickets=ref<Ticket[]>([]), reasons=ref<Record<string,string>>({}), replies=ref<Record<string,string>>({});
function price(amount:string,currency:string) {
  if (!amount || !currency) return "待商家确认";
  return new Intl.NumberFormat("zh-CN",{style:"currency",currency}).format(Number(amount));
}
async function update() {
  if (staff.value) { tickets.value=await api("/v1/merchant/staff/support"); return; }
  const status=await api("/v1/merchant/binding");
  enabled.value=status.enabled; bound.value=status.bound; shopError.value="";
  if (bound.value) {
    orders.value=await api("/v1/merchant/orders");
    tickets.value=await api("/v1/merchant/support");
    try { products.value=await api("/v1/merchant/products"); }
    catch (e) { shopError.value=(e as Error).message; }
  }
}
async function run(action:()=>Promise<void>) {
  if (busy.value) return;
  busy.value=true; error.value="";
  try { await action(); } catch(e) {error.value=(e as Error).message;} finally {busy.value=false;}
}
onMounted(async ()=>{
  try { const me=await api("/me"); staff.value=me.role==="STAFF"; signedIn.value=true; await update(); }
  catch(e) { if (signedIn.value) error.value=(e as Error).message; }
});
async function login() {
  const me=await api("/login","POST",{username:username.value,password:password.value});
  staff.value=me.role==="STAFF";
  password.value=""; signedIn.value=true; await update();
}
async function bind() {
  const result=await api("/v1/merchant/binding/start","POST",{}); location.assign(result.authorizationUrl);
}
let pendingQuote: { id:string; payload:string } | undefined;
async function preview() {
  quote.value=undefined;
  const items=products.value.flatMap(p => quantities.value[p.id]>0?[{variantId:p.id,quantity:quantities.value[p.id]}]:[]);
  if (!items.length) throw Error("请选择需要购买的商品");
  const payload=JSON.stringify(items);
  if (pendingQuote?.payload!==payload) pendingQuote={id:crypto.randomUUID(),payload};
  try {
    quote.value=await api("/v1/merchant/checkout","POST",{requestId:pendingQuote.id,items});
  } catch (failure) {
    if (failure instanceof ApiError && [410, 422].includes(failure.status)) {
      pendingQuote=undefined;
      throw Error(failure.status===410 ? "报价已过期，请重新核价" : "商家暂无法按此规格备货，请核对商品或数量");
    }
    throw failure;
  }
}
async function checkout() {
  if (!quote.value) return;
  try {
    const {checkoutUrl}=await api("/v1/merchant/checkout/"+quote.value.id+"/confirm","POST",{});
    location.assign(checkoutUrl);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status===409) {
      quote.value=undefined;
      pendingQuote=undefined;
      throw Error("报价已变动或过期，请重新核价");
    }
    throw failure;
  }
}
async function ask(order:Order) {
  await api("/v1/merchant/support","POST",{requestId:crypto.randomUUID(),orderId:order.id,reason:reasons.value[order.id]});
  reasons.value[order.id]=""; await update();
}
async function answer(ticket:Ticket,close:boolean) {
  await api("/v1/merchant/staff/support/"+ticket.id,"POST",{message:replies.value[ticket.id],close});
  await update();
}
function goToSection(id: string) {
  const heading = document.getElementById(id);
  heading?.scrollIntoView({ block: "start" });
  heading?.focus({ preventScroll: true });
}
</script>
<template>
  <div class="sw-shell merchant-shell">
    <StoreHeader orders />
    <main class="sw-width merchant">
      <div class="order-intro">
        <h1>合作店订单</h1>
        <p>查询合作店订单、配送进度和售后记录。</p>
      </div>
      <p v-if="error" class="order-feedback" role="alert">{{error}}</p>
      <section v-if="!signedIn" class="order-entry" aria-labelledby="merchant-login">
        <h2 id="merchant-login">登录账户</h2>
        <p>登录后即可查看已关联的合作店订单。</p>
        <form @submit.prevent="run(login)">
      <label>账号<input v-model="username" autocomplete="username" required/></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required/></label>
          <button class="sw-button primary" :disabled="busy">{{ busy ? "正在登录…" : "登录" }}</button>
        </form>
      </section>
    <div v-else-if="staff" class="order-workspace">
      <nav class="order-sections" aria-label="客服管理">
        <button type="button" @click="goToSection('order-support')"><MessageSquare :size="17" /> 客服工单</button>
        <button type="button" @click="goToSection('return-title')"><RotateCcw :size="17" /> 退货与退款</button>
      </nav>
      <div class="order-content"><section><h2 id="order-support" tabindex="-1">店铺客服工单</h2>
      <button @click="run(update)" :disabled="busy">刷新工单</button>
      <p v-if="!tickets.length">暂无工单。</p>
      <article v-for="t in tickets" :key="t.id"><h3>{{t.reason}}</h3><p>{{t.reply}}</p>
        <template v-if="t.status!=='CLOSED'">
          <label>回复<input v-model="replies[t.id]" maxlength="1200"/></label>
          <button :disabled="busy || !replies[t.id]?.trim()" @click="run(()=>answer(t,false))">发送回复</button>
          <button :disabled="busy || !replies[t.id]?.trim()" @click="run(()=>answer(t,true))">确认回复并关闭</button>
        </template><p v-else>已关闭</p>
      </article>
      </section><MerchantReturns :orders="[]" staff />
      </div>
    </div>
    <section v-else-if="!bound" class="order-entry" aria-labelledby="merchant-binding">
      <h2 id="merchant-binding">{{ enabled ? "关联合作店账号" : "合作店暂未开放" }}</h2>
      <p v-if="enabled">关联你在商家的账户，查看对应订单与物流。</p>
      <button v-if="enabled" class="sw-button primary" :disabled="busy" @click="run(bind)">
        <Link2 :size="17" /> 绑定合作店账号
      </button>
      <a v-else class="sw-button plain" href="#/">继续跨店选购</a>
    </section>
    <div v-else class="order-workspace">
      <nav class="order-sections" aria-label="订单服务">
        <button type="button" @click="goToSection('merchant-orders')"><Package :size="17" /> 订单与物流</button>
        <button type="button" @click="goToSection('merchant-cart')"><Package :size="17" /> 商品与购物车</button>
        <button type="button" @click="goToSection('return-title')"><RotateCcw :size="17" /> 退货与退款</button>
        <button v-if="tickets.length" type="button" @click="goToSection('merchant-support')"><MessageSquare :size="17" /> 客服进度</button>
      </nav>
      <div class="order-content">
      <section><h2 id="merchant-orders" tabindex="-1">订单与物流</h2>
        <p v-if="!orders.length">没有已同步的订单。付款完成后可返回此页刷新。</p>
        <button @click="run(update)" :disabled="busy">刷新订单</button>
        <article v-for="o in orders" :key="o.id"><h3>{{o.name}}</h3>
          <p>{{price(o.current_total_price,o.currency)}} · {{orderState[o.financial_status]||o.financial_status}} · {{orderState[o.fulfillment_status]||"待更新"}}</p>
          <p v-for="p in o.fulfillments" :key="p.tracking_number">{{p.tracking_company}} {{p.tracking_number}}</p>
          <form v-if="o.id" @submit.prevent="run(()=>ask(o))">
            <label>售后问题<input v-model="reasons[o.id]" maxlength="800" required/></label>
            <button class="dark" :disabled="busy">联系商家处理</button>
          </form>
        </article>
      </section>
      <section><h2 id="merchant-cart" tabindex="-1">商品与购物车</h2><p v-if="shopError">{{shopError}}</p>
        <form v-if="products.length" @submit.prevent="run(preview)">
          <label v-for="p in products" :key="p.id">{{p.product}} · {{p.variant}} · {{price(p.amount,p.currency)}}
            <input type="number" min="0" max="20" v-model.number="quantities[p.id]" placeholder="0" @input="quote=undefined"/>
          </label><button class="dark" :disabled="busy">核对价格</button>
        </form>
        <p v-else-if="!shopError">暂无可选商品。</p>
        <div v-if="quote" class="order-quote">
          <p>商家当前金额 {{price(quote.cost.totalAmount.amount,quote.cost.totalAmount.currencyCode)}}。具体配送、税费和最终总额由结账页确认。</p>
          <button class="dark" :disabled="busy" @click="run(checkout)">确认并到商家结账</button>
        </div>
      </section>
      <MerchantReturns :orders="orders" :staff="false" />
      <section v-if="tickets.length"><h2 id="merchant-support" tabindex="-1">客服进度</h2>
        <article v-for="t in tickets" :key="t.id">
          <h3>{{t.reason}}</h3><p>{{t.status==='OPEN'?'待商家处理':t.status==='CLOSED'?'已关闭':'商家已回复'}}</p>
          <p v-if="t.reply">{{t.reply}}</p>
        </article>
      </section>
      </div>
    </div>
    </main>
    <StoreFooter />
  </div>
</template>
<style scoped src="./merchant.css"></style>
