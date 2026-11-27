<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "./store";
import MerchantReturns from "./MerchantReturns.vue";
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
async function preview() {
  quote.value=undefined;
  const items=products.value.flatMap(p => quantities.value[p.id]>0?[{variantId:p.id,quantity:quantities.value[p.id]}]:[]);
  if (!items.length) throw Error("请选择需要购买的商品");
  quote.value=await api("/v1/merchant/checkout","POST",{requestId:crypto.randomUUID(),items});
}
async function checkout() {
  if (!quote.value) return;
  const {checkoutUrl}=await api("/v1/merchant/checkout/"+quote.value.id+"/confirm","POST",{});
  location.assign(checkoutUrl);
}
async function ask(order:Order) {
  await api("/v1/merchant/support","POST",{requestId:crypto.randomUUID(),orderId:order.id,reason:reasons.value[order.id]});
  reasons.value[order.id]=""; await update();
}
async function answer(ticket:Ticket,close:boolean) {
  await api("/v1/merchant/staff/support/"+ticket.id,"POST",{message:replies.value[ticket.id],close});
  await update();
}
</script>
<template>
  <header><div class="wrap nav"><a class="brand" href="#/">ShopWise</a><a href="#/">返回跨境选购</a></div></header>
  <main class="wrap merchant">
    <h1>合作店订单</h1>
    <p v-if="error" role="alert">{{error}}</p>
    <form v-if="!signedIn" @submit.prevent="run(login)">
      <label>账号<input v-model="username" autocomplete="username" required/></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required/></label>
      <button class="dark" :disabled="busy">登录</button>
    </form>
    <template v-else-if="staff"><section><h2>店铺客服工单</h2>
      <button @click="run(update)" :disabled="busy">刷新工单</button>
      <p v-if="!tickets.length">暂无工单。</p>
      <article v-for="t in tickets" :key="t.id"><h3>{{t.reason}}</h3><p>{{t.reply}}</p>
        <template v-if="t.status!=='CLOSED'">
          <label>回复<input v-model="replies[t.id]" maxlength="1200"/></label>
          <button :disabled="busy || !replies[t.id]?.trim()" @click="run(()=>answer(t,false))">发送回复</button>
          <button :disabled="busy || !replies[t.id]?.trim()" @click="run(()=>answer(t,true))">确认回复并关闭</button>
        </template><p v-else>已关闭</p>
      </article>
    </section><MerchantReturns :orders="[]" staff /></template>
    <template v-else-if="!bound">
      <button v-if="enabled" class="dark" :disabled="busy" @click="run(bind)">绑定合作店账号</button>
      <p v-else>合作店暂未开放。</p>
    </template>
    <template v-else>
      <section><h2>商品与购物车</h2><p v-if="shopError">{{shopError}}</p>
        <form v-if="products.length" @submit.prevent="run(preview)">
          <label v-for="p in products" :key="p.id">{{p.product}} · {{p.variant}} · {{price(p.amount,p.currency)}}
            <input type="number" min="0" max="20" v-model.number="quantities[p.id]" placeholder="0" @input="quote=undefined"/>
          </label><button class="dark" :disabled="busy">核对价格</button>
        </form>
        <div v-if="quote">
          <p>商家当前金额 {{price(quote.cost.totalAmount.amount,quote.cost.totalAmount.currencyCode)}}。具体配送、税费和最终总额由结账页确认。</p>
          <button class="dark" :disabled="busy" @click="run(checkout)">确认并到商家结账</button>
        </div>
      </section>
      <section><h2>订单与物流</h2>
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
      <MerchantReturns :orders="orders" :staff="false" />
      <section v-if="tickets.length"><h2>客服进度</h2>
        <article v-for="t in tickets" :key="t.id">
          <h3>{{t.reason}}</h3><p>{{t.status==='OPEN'?'待商家处理':t.status==='CLOSED'?'已关闭':'商家已回复'}}</p>
          <p v-if="t.reply">{{t.reply}}</p>
        </article>
      </section>
    </template>
  </main>
</template>
<style scoped>
.merchant{max-width:800px;padding:1.5rem 0 5rem}.merchant section{border-top:1px solid var(--line);margin:1.5rem 0}.merchant form{display:grid;gap:.75rem}.merchant label{display:grid;gap:.35rem}.merchant input{max-width:24rem;padding:.65rem;border:1px solid var(--line)}.merchant .dark{width:max-content;padding:.65rem 1rem}.merchant article{border-top:1px solid var(--line)}
</style>
