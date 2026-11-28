import { api } from "./store";

export interface Offer {
  provider: string;
  productId: string;
  variantId: string;
  title: string;
  variant: string;
  source: string;
  url: string;
  imageUrl?: string | null;
  currency: string | null;
  price: number | null;
  referenceCny?: { amount: number; date: string };
  observedAt: string;
  available: boolean | null;
  attributes?: Record<string, string> | null;
}
export type OfferReference = Pick<Offer, "provider" | "variantId">;
interface Source {
  provider: string;
  status: string;
  offers: Offer[];
}
interface Match {
  evidence: "GTIN" | "BRAND_MPN";
  offers: string[];
}
interface CatalogResult {
  sources: Source[];
  matches?: Match[];
}
export interface AssistantResult {
  text: string;
  offers: Offer[];
  filters: { city?: string; budget?: number };
  degradation?: string | null;
}
const sourceNames: Record<string, string> = { shopify: "Shopify", ebay: "eBay" };
const failures: Record<string, string> = { busy: "繁忙", timeout: "查询超时", unavailable: "暂时无法连接" };

export const keyOf = (o: Offer) => o.provider + "/" + o.variantId;
export const fresh = (o: Offer, now = Date.now()) => now - Date.parse(o.observedAt) < 600000;
export const sourceName = (provider: string) => sourceNames[provider] ?? provider;
export const stockText = (o: Offer) => o.available === true ? "商家显示有货" : o.available === false ? "商家显示缺货" : "库存待核实";
export function priceText(o: Offer) {
  if (o.price == null || !o.currency) return "待商家报价";
  return new Intl.NumberFormat("zh-CN", { style: "currency", currency: o.currency }).format(o.price);
}
export function referenceText(o: Offer) {
  if (o.currency === "CNY" || !o.referenceCny) return "";
  return "约 ¥" + o.referenceCny.amount.toFixed(2) + " · ECB " + o.referenceCny.date;
}

export async function searchCatalog(query: string, budget: string) {
  const params = new URLSearchParams({ q: query.trim() });
  if (budget) params.set("budget", budget);
  const result: CatalogResult = await api("/v1/discovery?" + params);
  const offers = new Map<string, Offer>();
  for (const source of result.sources) {
    for (const offer of source.offers) if (fresh(offer)) offers.set(keyOf(offer), offer);
  }
  return {
    offers: [...offers.values()],
    matches: result.matches ?? [],
    notices: result.sources.flatMap(source => failures[source.status] ? [sourceName(source.provider) + " " + failures[source.status]] : []),
    anyAvailable: result.sources.some(source => ["ok", "empty"].includes(source.status)),
  };
}

export async function verifyOffer(offer: Offer) {
  const params = new URLSearchParams({ id: offer.variantId });
  const source: Source = await api("/v1/discovery/" + offer.provider + "/lookup?" + params);
  const updated = source.offers.find(item => keyOf(item) === keyOf(offer) && fresh(item));
  if (updated) return updated;
  if (source.status === "empty") throw Error("商家当前无法提供该规格，请换一款或稍后核对。");
  throw Error("暂时无法核对这款商品，请稍后重试。");
}

export async function askAssistant(
  message: string, references: OfferReference[], onStatus: (text: string) => void,
): Promise<AssistantResult> {
  const csrf = await api("/csrf");
  const res = await fetch("/api/v1/assistant/turn", {
    method: "POST",
    headers: { "Content-Type": "application/json", [csrf.header]: csrf.token },
    body: JSON.stringify(references.length ? { message, references } : { message }),
  });
  if (!res.ok || !res.body) throw Error((await res.json().catch(() => null))?.message || "导购暂时无法响应");
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  let result: AssistantResult | undefined;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      buffer = (buffer + (value || "")).replace(/\r\n/g, "\n");
      let end: number;
      while ((end = buffer.indexOf("\n\n")) >= 0) {
        const lines = buffer.slice(0, end).split("\n");
        buffer = buffer.slice(end + 2);
        const event = lines.find(line => line.startsWith("event:"))?.slice(6).trim();
        const payload = lines.filter(line => line.startsWith("data:")).map(line => line.slice(5)).join("\n");
        if (!payload) continue;
        const data = JSON.parse(payload);
        if (event === "error") throw Error(data.message);
        if (event === "status") onStatus(data.text);
        if (event === "message") result = data;
      }
      if (done) break;
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  if (!result) throw Error("连接提前结束，请重新查询");
  return result;
}
