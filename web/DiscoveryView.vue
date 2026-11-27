<script setup lang="ts">
import { computed, ref, onBeforeUnmount, onMounted } from "vue";
import { ShoppingBag, ArrowUpRight, Search, MapPin } from "lucide-vue-next";
import { fresh, keyOf, priceText, useDiscovery, type Offer } from "./discovery";
const shop=useDiscovery();
const mode=ref<"agent"|"search">("agent"), query=ref(""), budget=ref(""), error=ref(""), checked=ref<Offer|null>(null);
const selected=computed(()=>shop.offers.filter(o=>shop.selected.includes(keyOf(o))));
const sameProducts=computed(()=>shop.matches.map(group=>({
  evidence: group.evidence,
  offers: group.offers.flatMap(key=>shop.offers.filter(o=>keyOf(o)===key)),
})).filter(group=>new Set(group.offers.map(o=>o.provider)).size>1));
let timer:ReturnType<typeof setInterval>;
onMounted(()=>{ timer=setInterval(()=>{shop.setOffers(shop.offers); if(checked.value&&!fresh(checked.value)) checked.value=null;},1000); });
onBeforeUnmount(()=>{clearInterval(timer); shop.$reset();});
async function run() {
  if (!query.value.trim() || shop.busy) return;
  error.value=""; checked.value=null; shop.busy=true; shop.status="正在查询商品…";
  try {
    if (mode.value==="agent") await shop.assist(query.value.trim());
    else { shop.city=""; shop.limit=budget.value; await shop.search(query.value,budget.value); }
  } catch(e) { shop.setOffers([]); shop.notice=""; error.value=(e as Error).message; }
  finally {shop.busy=false; shop.status="";}
}
async function check(o:Offer) {
  shop.busy=true; error.value=""; checked.value=null;
  try { checked.value=await shop.recheck(o); } catch(e) { error.value=(e as Error).message; }
  finally { shop.busy=false; }
}
</script>
<template>
<header><div class="wrap nav"><a class="brand" href="#/"><span class="mark"><ShoppingBag :size="22"/></span>ShopWise</a>
<nav aria-label="购物导航"><a href="#/merchant">合作店订单</a><span class="dest"><MapPin :size="16"/> 中国大陆收货</span></nav></div></header>
<main class="wrap discovery">
  <section class="finder"><div>
    <h1>跨境选购助手</h1>
    <p>告诉我想买什么、寄到哪个城市，以及你的到手预算。</p>
    <div class="switches"><button :aria-pressed="mode==='agent'" @click="mode='agent'">AI 导购</button>
    <button :aria-pressed="mode==='search'" @click="mode='search'">按关键词搜索</button></div>
    <form @submit.prevent="run"><label for="query">{{ mode==='agent'?'购物需求':'商品关键词' }}</label>
    <input id="query" v-model="query" :maxlength="mode==='agent'?400:120" required :placeholder="mode==='agent'?'能寄到杭州、到手不超过 300 元的阅读灯':'例如 desk lamp 或台灯'"/>
    <label v-if="mode==='search'">人民币商品价上限<input v-model="budget" type="number" min="0" max="100000000" step="0.01" placeholder="不限"/></label>
    <button type="submit" class="dark" :disabled="shop.busy || !query.trim()"><Search :size="17"/> {{ shop.busy?'正在处理':'查找商品' }}</button></form>
  </div><img src="/media/feature.webp" alt="桌灯与家居选物"/></section>
  <p v-if="shop.status" role="status">{{ shop.status }}</p><p v-if="error" role="alert">{{ error }}</p>
  <section v-if="shop.notice || shop.offers.length"><h2>查询结果</h2>
    <p v-if="shop.city || shop.limit">{{ shop.city ? '收货地 '+shop.city : '中国大陆收货' }} {{ shop.limit ? ' · 预算 ¥'+shop.limit : '' }}</p>
    <p role="status">{{ shop.notice }}</p>
    <article v-for="o in shop.offers" :key="keyOf(o)" class="offer">
      <div><small>{{ o.provider }} · {{ o.source }}</small><h3>{{ o.title }}</h3><p>{{ o.variant }}</p>
      <small>查询于 {{ new Date(o.observedAt).toLocaleTimeString('zh-CN') }} · {{ o.available===true?'来源显示有货':'库存待核实' }}</small></div>
      <div><strong>{{ priceText(o) }}</strong>
      <p v-if="o.referenceCny"><small>约 ¥{{ o.referenceCny.amount.toFixed(2) }} · ECB {{o.referenceCny.date}}</small></p>
      <p>商品价 · 税费和运费待确认</p>
      <button @click="shop.toggle(o)" :aria-pressed="shop.selected.includes(keyOf(o))" :disabled="!shop.selected.includes(keyOf(o)) && selected.length>=4">{{shop.selected.includes(keyOf(o))?'移出比较':'加入比较'}}</button>
      <button :disabled="shop.busy" @click="check(o)">前往商家前核价</button></div></article>
  </section>
  <section v-if="sameProducts.length"><h2>跨平台同款</h2>
    <article v-for="group in sameProducts" :key="keyOf(group.offers[0])" class="offer">
      <div><h3>{{group.offers[0].title}}</h3><p>{{group.evidence==='GTIN'?'商品条码一致':'品牌、制造商料号和规格一致'}}</p></div>
      <ul><li v-for="o in group.offers" :key="keyOf(o)">{{o.provider}} · {{o.source}} · {{priceText(o)}}</li></ul>
    </article>
  </section>
  <section v-if="selected.length>=2"><h2>规格与报价对比</h2>
    <div class="scroll"><table><thead><tr><th>比较项</th><th v-for="o in selected" :key="keyOf(o)">{{o.title}}</th></tr></thead>
    <tbody><tr v-for="field in ['商家','规格','商品价','到手价']" :key="field"><th>{{field}}</th><td v-for="o in selected" :key="keyOf(o)">{{field==='商家'?o.source:field==='规格'?(o.variant||'待确认'):field==='商品价'?priceText(o):'运费与税费待确认'}}</td></tr></tbody></table></div>
  </section>
  <section v-if="checked" class="handoff" aria-live="polite"><h2>商家最新报价</h2><strong>{{ checked.title }} · {{ priceText(checked) }}</strong>
    <p>{{checked.variant}} · 请在商家页面确认具体地址、运费和税费。</p>
    <a class="dark" :href="checked.url" target="_blank" rel="noopener noreferrer">去 {{checked.source}} 核对并购买 <ArrowUpRight :size="16"/></a></section>
</main>
</template>
<style scoped>
.discovery{max-width:1050px;padding-bottom:5rem}.dest,.switches{display:flex;align-items:center;gap:.5rem}.finder{display:grid;grid-template-columns:minmax(0,3fr) 2fr;align-items:stretch;gap:2.5rem;margin:3rem 0}.finder>img{width:100%;height:100%;max-height:340px;object-fit:cover;border-radius:10px}.finder h1{font-size:clamp(2rem,5vw,3.5rem);margin:.4rem 0}.finder form,.offer{display:grid;gap:.7rem}.finder form{margin-top:1rem}.finder input{width:100%;padding:.7rem;border:1px solid var(--line);background:white;border-radius:5px}.finder label{display:block;font-size:.9rem}.finder .dark,.handoff .dark{width:max-content;display:inline-flex;align-items:center;gap:.6rem;padding:.7rem 1.1rem}.switches button,.offer button{padding:.5rem .7rem;border:1px solid var(--line);border-radius:5px}.switches [aria-pressed=true],.offer [aria-pressed=true]{background:var(--pine);color:white}.offer{grid-template-columns:3fr 2fr;align-items:center;border-top:1px solid var(--line);padding:1rem 0}.offer h3{margin:.35rem 0}.offer p{margin:.4rem 0}.offer small{color:var(--muted)}.offer button{margin:.3rem .5rem 0 0}.scroll{overflow:auto}th,td{text-align:left;border-bottom:1px solid var(--line);padding:.7rem;min-width:6rem}.handoff{padding:1rem;border:1px solid var(--line);margin:1.5rem 0}@media(max-width:680px){.finder,.offer{grid-template-columns:1fr}.finder{margin:1.5rem 0}.finder>img{display:none}.dest{font-size:.83rem}}
</style>
