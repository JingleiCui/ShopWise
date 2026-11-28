import { computed, ref } from "vue";
export interface Sku {
  id: string;
  label: string;
  price: number;
  stock: number;
}
export interface Product {
  id: string;
  title: string;
  category: string;
  kind: string;
  description: string;
  image: string;
  attributes: Record<string, unknown>;
  skus: Sku[];
}
interface Line {
  skuId: string;
  title: string;
  label: string;
  price: number;
  quantity: number;
}
interface Quote {
  expiresAt?: string;
  id: string;
  lines: Line[];
  subtotal: number;
  shipping: number;
  total: number;
}
interface Order extends Quote {
  tracking?: string;
  status: string;
  events: { status: string; at: string }[];
}
interface Return {
  id: string;
  orderId: string;
  skuId: string;
  title: string;
  quantity: number;
  reason: string;
  status: string;
  amount: number;
}
export interface Message {
  id: string;
  role: string;
  text: string;
  products?: any[];
  quote?: Quote;
  external?: any;
  mode?: string;
}
interface Chat {
  id: string;
  buyer?: string;
  takenOver: boolean;
  messages: Message[];
}
export const user = ref<{ username: string; role: string } | null>(null),
  products = ref<Product[]>([]),
  cart = ref<{ lines: Line[]; subtotal: number }>({ lines: [], subtotal: 0 }),
  orders = ref<Order[]>([]),
  returns = ref<Return[]>([]),
  chat = ref<Chat | null>(null),
  inbox = ref<Chat[]>([]),
  quote = ref<Quote | null>(null);
export const staff = computed(() => user.value?.role === "STAFF"),
  count = computed(() => cart.value.lines.reduce((n, l) => n + l.quantity, 0));
export const money = (v: number) =>
  "¥" + Number(v).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
export const states: Record<string, string> = {
  PENDING_PAYMENT: "待付款",
  PAID: "待发货",
  SHIPPED: "待收货",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  EXPIRED: "已过期",
  REQUESTED: "待审核",
  APPROVED: "待退款",
  REJECTED: "已驳回",
  REFUNDED: "已退款",
};
async function writeHeaders(): Promise<Record<string,string>> {
  const res=await fetch("/api/csrf",{cache:"no-store",credentials:"same-origin"});
  if (!res.ok) throw Error("无法获取操作凭据，请刷新页面");
  const csrf=await res.json();
  return { [csrf.header]: csrf.token, "Content-Type":"application/json" };
}
export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}
export async function api(path: string, method = "GET", body?: unknown) {
  const r = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers: method === "GET" ? {} : await writeHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await r.json().catch(() => null);
  if (!r.ok) throw new ApiError(data?.message || "请求失败（" + r.status + "）", r.status);
  return data;
}
export const catalog = async () => {
  products.value = await api("/products");
};
const temporaryLinks = new Map<string, { expires: number; links: unknown[] }>();
export async function refresh() {
  if (!user.value) return;
  const active = user.value.username;
  const [o, r, c, h] = await Promise.all([
    api("/orders"),
    api("/returns"),
    staff.value ? null : api("/cart"),
    api(staff.value ? "/staff/chats" : "/chat"),
  ]);
  if (user.value?.username !== active) return;
  orders.value = o;
  returns.value = r;
  if (c) cart.value = c;
  if (staff.value) {
    inbox.value = h;
    return;
  }
  for (const [id, entry] of temporaryLinks)
    if (Date.now() >= entry.expires) temporaryLinks.delete(id);
  for (const message of h.messages)
    message.external = temporaryLinks.get(String(message.id))?.links || [];
  chat.value = h;
}
export function reset() {
  temporaryLinks.clear();
  user.value = null;
  cart.value = { lines: [], subtotal: 0 };
  quote.value = null;
  orders.value = [];
  returns.value = [];
  chat.value = null;
  inbox.value = [];
}
export async function stream(
  message: string,
  requestId: string,
  onStatus: (s: string) => void,
  confirmedQuoteId?: string,
) {
  const r = await fetch("/api/chat/stream", {
    method: "POST",
    headers: await writeHeaders(),
    body: JSON.stringify({ message, requestId, confirmedQuoteId }),
  });
  if (!r.ok || !r.body)
    throw Error((await r.json().catch(() => null))?.message || "连接助手失败");
  const reader = r.body.getReader(),
    decoder = new TextDecoder();
  let buffer = "";
  function event(block: string) {
    const lines = block.split("\n"),
      type = lines
        .find((x) => x.startsWith("event:"))
        ?.slice(6)
        .trim(),
      data = lines
        .filter((x) => x.startsWith("data:"))
        .map((x) => x.slice(5).trimStart())
        .join("\n");
    if (!data) return;
    const v = JSON.parse(data);
    if (type === "status") onStatus(v.text);
    if (type === "error") throw Error(v.message);
    if (type === "message" && Array.isArray(v.external) && v.external.length) {
      temporaryLinks.set(String(v.id), {
        expires: Date.now() + 540000,
        links: v.external,
      });
    }
    if (
      type === "message" &&
      chat.value &&
      !chat.value.messages.some((m) => m.id === v.id)
    )
      chat.value.messages.push(v);
  }
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let n;
      while ((n = buffer.indexOf("\n\n")) >= 0) {
        event(buffer.slice(0, n));
        buffer = buffer.slice(n + 2);
      }
      if (done) {
        if (buffer.trim()) event(buffer);
        break;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
