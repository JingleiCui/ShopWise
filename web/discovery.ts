import { defineStore } from "pinia";
import { api } from "./store";

export interface Offer {
  provider: string; productId: string; variantId: string; title: string; variant: string;
  source: string; url: string; currency: string | null; amountMinor: number | null;
  price: number | null; referenceCny?: { amount: number; date: string }; observedAt: string; available: boolean | null; condition: string;
}
interface Match { evidence: "GTIN" | "BRAND_MPN"; offers: string[] }
interface Source { provider: string; status: string; offers: Offer[] }
export const keyOf = (o: Offer) => o.provider + "/" + o.variantId;
export const fresh = (o: Offer, now = Date.now()) => now - Date.parse(o.observedAt) < 600000;
export function priceText(o: Offer) {
  if (o.price == null || !o.currency) return "待商家报价";
  return new Intl.NumberFormat("zh-CN", { style:"currency", currency:o.currency }).format(o.price);
}
const statuses: Record<string,string> = { empty:"没有匹配商品", disabled:"未启用", busy:"请求繁忙", timeout:"查询超时", unavailable:"暂时无法连接" };
export const useDiscovery = defineStore("discovery", {
  state: () => ({ degradation:"", requestId:"", offers: [] as Offer[], matches: [] as Match[], selected: [] as string[], notice:"", busy:false, status:"", city:"", limit:"" }),
  actions: {
    setOffers(offers: Offer[]) {
      const seen=new Map<string,Offer>();
      for (const offer of offers) if (fresh(offer)) seen.set(keyOf(offer),offer);
      this.offers=[...seen.values()];
      this.selected=this.selected.filter(key=>seen.has(key));
    },
    async search(q:string, budget:string) {
      const params=new URLSearchParams({q:q.trim()});
      if (budget) params.set("budget",budget);
      const data=await api("/v1/discovery?"+params);
      const sources=data.sources as Source[];
      this.degradation="";
      this.setOffers(sources.flatMap(s=>s.offers));
      this.matches=data.matches || [];
      this.notice=sources.filter(s=>s.status!=="ok").map(s=>s.provider+" · "+statuses[s.status]).join("　");
    },
    async assist(message:string) {
      const csrf=await api("/csrf");
      const res=await fetch("/api/v1/assistant/turn",{method:"POST",headers:{"Content-Type":"application/json",[csrf.header]:csrf.token},body:JSON.stringify({message})});
      if (!res.ok || !res.body) throw Error((await res.json().catch(()=>null))?.message || "导购暂时无法响应");
      const reader=res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer="", completed=false;
      try {
        for (;;) {
          const {value,done}=await reader.read();
          buffer=(buffer+(value||"")).replace(/\r\n/g,"\n");
          let end;
          while ((end=buffer.indexOf("\n\n"))>=0) {
            const lines=buffer.slice(0,end).split("\n"); buffer=buffer.slice(end+2);
            const event=lines.find(l=>l.startsWith("event:"))?.slice(6).trim();
            const data=JSON.parse(lines.filter(l=>l.startsWith("data:")).map(l=>l.slice(5)).join("\n"));
            if (event==="error") throw Error(data.message);
            if (event==="status") this.status=data.text;
            if (event==="message") {
              this.setOffers(data.offers); this.matches=data.matches || []; this.notice=data.text;
              if (data.comparison) this.selected=this.offers.slice(0,4).map(keyOf);
              this.requestId=data.requestId||""; this.degradation=data.degradation||""; this.city=data.filters.city||"";
              this.limit=data.filters.budget==null?"":String(data.filters.budget); completed=true;
            }
          }
          if (done) break;
        }
        if (!completed) throw Error("连接提前结束，请重新查询");
      } finally { reader.releaseLock(); }
    },
    toggle(offer:Offer) {
      const key=keyOf(offer);
      if (this.selected.includes(key)) this.selected=this.selected.filter(k=>k!==key);
      else if (this.selected.length<4) this.selected.push(key);
    },
    async recheck(offer:Offer) {
      const params=new URLSearchParams({id:offer.variantId});
      this.matches=this.matches.filter(group=>!group.offers.includes(keyOf(offer)));
      const source:Source=await api("/v1/discovery/"+offer.provider+"/lookup?"+params);
      const updated=source.offers.find(o=>keyOf(o)===keyOf(offer) && o.available!==false);
      if (!updated) throw Error("该规格暂时无法核价或配送，请重新搜索");
      this.setOffers(this.offers.map(o=>keyOf(o)===keyOf(updated)?updated:o));
      return updated;
    },
  },
});
