<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "./store";

type Line = { id:string; title:string; quantity:number };
type Order = { id:string; name:string; returnableLines?:Line[] };
type Return = { id:string; orderId:string; lineId:string; quantity:number; reason:string; status:string;
  staffNote?:string; refundAmount?:number; refundCurrency?:string };
const props=defineProps<{ orders:Order[]; staff:boolean }>();
const statuses:Record<string,string>={REQUESTED:"待审核",APPROVED:"已批准，待商家收货",DECLINED:"未批准",
  RECEIVED:"已收货，待核对退款",REFUNDED:"退款已核实"};
const rows=ref<Return[]>([]), orderId=ref(""), lineId=ref(""), quantity=ref(1), reason=ref("");
const notes=ref<Record<string,string>>({}), receipts=ref<Record<string,string>>({});
const pending=ref<{ id:string; body:string }>();
const busy=ref(false), error=ref("");
const buyerPath="/v1/merchant/returns", staffPath="/v1/merchant/staff/returns";

async function refresh() { rows.value=await api(props.staff?staffPath:buyerPath); }
async function run(action:()=>Promise<void>) {
  if (busy.value) return;
  busy.value=true; error.value="";
  try { await action(); } catch (e) { error.value=(e as Error).message; } finally { busy.value=false; }
}
async function submit() {
  const data={orderId:orderId.value,lineId:lineId.value,quantity:quantity.value,reason:reason.value};
  const body=JSON.stringify(data);
  if (pending.value?.body!==body) pending.value={id:crypto.randomUUID(),body};
  await api(buyerPath,"POST",{...data,requestId:pending.value.id});
  pending.value=undefined; reason.value=""; quantity.value=1;
  await refresh();
}
async function decide(row:Return,action:string) {
  await api(staffPath+"/"+row.id+"/"+action,"POST",{note:notes.value[row.id]?.trim()||null,
    refundId:receipts.value[row.id]?.trim()||null});
  await refresh();
}
onMounted(()=>run(refresh));
</script>

<template>
<section class="return-panel">
  <h2 id="return-title" tabindex="-1">退货与退款</h2>
  <p v-if="error" role="alert">{{error}}</p>
  <form v-if="!staff && orders.some(o=>o.returnableLines?.length)" @submit.prevent="run(submit)">
    <label>订单<select v-model="orderId" required @change="lineId=''; quantity=1">
      <option disabled value="">选择订单</option>
      <option v-for="o in orders.filter(o=>o.returnableLines?.length)" :key="o.id" :value="o.id">{{o.name}}</option>
    </select></label>
    <label>商品<select v-model="lineId" required @change="quantity=1">
      <option disabled value="">选择商品</option>
      <option v-for="line in orders.find(o=>o.id===orderId)?.returnableLines||[]" :key="line.id" :value="line.id">
        {{line.title}}
      </option>
    </select></label>
    <label>数量<input v-model.number="quantity" type="number" min="1" :max="orders.find(o=>o.id===orderId)?.returnableLines?.find(l=>l.id===lineId)?.quantity||1" required/></label>
    <label>原因<textarea v-model="reason" maxlength="800" required/></label>
    <button class="dark" :disabled="busy">确认提交退货申请</button>
  </form>
  <button :disabled="busy" @click="run(refresh)">刷新退货进度</button>
  <p v-if="!rows.length">暂无退货申请。</p>
  <article v-for="row in rows" :key="row.id">
    <h3>{{statuses[row.status]}}</h3>
    <p>{{row.reason}} · {{row.quantity}} 件</p>
    <p v-if="row.staffNote">{{row.staffNote}}</p>
    <p v-if="row.refundCurrency && row.refundAmount!=null">退款金额 {{new Intl.NumberFormat('zh-CN',{style:'currency',currency:row.refundCurrency}).format(row.refundAmount)}}</p>
    <template v-if="staff && !['DECLINED','REFUNDED'].includes(row.status)">
      <label>处理说明<input v-model="notes[row.id]" maxlength="800"/></label>
      <template v-if="row.status==='REQUESTED'">
        <button :disabled="busy" @click="run(()=>decide(row,'approve'))">批准</button>
        <button :disabled="busy" @click="run(()=>decide(row,'decline'))">拒绝</button>
      </template>
      <button v-if="row.status==='APPROVED'" :disabled="busy" @click="run(()=>decide(row,'receive'))">确认收到退货</button>
      <template v-if="row.status==='RECEIVED'">
        <label>Shopify 退款编号<input v-model="receipts[row.id]" placeholder="gid://shopify/Refund/…"/></label>
        <button :disabled="busy || !receipts[row.id]?.trim()" @click="run(()=>decide(row,'reconcile'))">核对退款记录</button>
      </template>
    </template>
  </article>
</section>
</template>
