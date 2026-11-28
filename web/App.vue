<script setup lang="ts">
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  watch,
  nextTick,
} from "vue";
import {
  ShoppingBag,
  UserRound,
  Search,
  SlidersHorizontal,
  ArrowRight,
  ArrowUpRight,
  X,
  Plus,
  Minus,
  GitCompareArrows,
  MessageCircle,
  Send,
  Check,
  ShoppingBasket,
  Headphones,
  Shirt,
  LampDesk,
  Layers3,
} from "lucide-vue-next";
import {
  api,
  products,
  user,
  cart,
  orders,
  returns,
  quote,
  chat,
  inbox,
  staff,
  count,
  money,
  states,
  catalog,
  refresh,
  reset,
  stream,
  type Product,
} from "./store";
const cats = ["全部", "数码", "服饰", "家居"],
  icons = [Layers3, Headphones, Shirt, LampDesk],
  cat = ref("全部"),
  q = ref(""),
  budget = ref(""),
  stock = ref(false),
  filters = ref(false),
  sort = ref("featured");
const featuredImages = ["47", "101", "173", "14", "78", "88", "45", "100"].map(
  (id) => "/media/products/" + id + ".webp",
);
function rank(p: Product) {
  const i = featuredImages.indexOf(p.image);
  return i < 0 ? 100 : i;
}
const allProducts = computed(() =>
  products.value
    .filter(
      (p) =>
        (cat.value === "全部" || p.category === cat.value) &&
        [
          p.title,
          p.description,
          p.category,
          p.kind,
          ...Object.values(p.attributes || {}),
          ...p.skus.map((s) => s.label),
        ]
          .join(" ")
          .toLowerCase()
          .includes(q.value.trim().toLowerCase()) &&
        p.skus.some(
          (s) =>
            (!stock.value || s.stock > 0) &&
            (!budget.value || s.price <= Number(budget.value)),
        ),
    )
    .sort((a, b) =>
      sort.value === "low"
        ? min(a) - min(b)
        : sort.value === "high"
          ? min(b) - min(a)
          : rank(a) - rank(b),
    ),
);
const min = (p: Product) => Math.min(...p.skus.map((s) => s.price)),
  fallback = (p: Product) =>
    "/media/" +
    ({ 数码: "tech", 家居: "home", 服饰: "fashion" }[p.category] || "home") +
    ".webp";
const panel = ref(""),
  detail = ref<Product | null>(null),
  variant = ref(""),
  selected = computed(() =>
    detail.value?.skus.find((s) => s.id === variant.value),
  ),
  compare = ref<Product[]>([]),
  busy = ref(false),
  notice = ref(""),
  error = ref(""),
  loadError = ref(""),
  loading = ref(true);
const panelTitles: Record<string, string> = {
  login: "登录 ShopWise",
  account: "我的账户",
  detail: "商品详情",
  cart: "购物袋",
  compare: "商品对比",
  orders: "我的订单",
  chat: "选购助手",
  staff: "店铺工作台",
};
const loginName = ref("buyer"),
  pwd = ref("ShopWise123!"),
  afterLogin = ref(""),
  tab = ref("订单"),
  skuEdit = ref(""),
  editPrice = ref(""),
  editStock = ref(""),
  selectedChat = ref(""),
  reply = ref(""),
  reason = ref(""),
  returnQty = ref(1),
  returnKey = ref(""),
  chatInput = ref(""),
  chatStatus = ref(""),
  chatBody = ref<HTMLElement>(),
  dialog = ref<HTMLElement>(),
  chatQuote = ref(false);
const contact = computed(
  () => inbox.value.find((c) => c.id === selectedChat.value) || inbox.value[0],
);
let pendingTurn: { text: string; id: string } | null = null;
let timer: ReturnType<typeof setTimeout>,
  poll: ReturnType<typeof setInterval>,
  previous: HTMLElement | null = null;
function tell(s: string) {
  notice.value = s;
  clearTimeout(timer);
  timer = setTimeout(() => (notice.value = ""), 3500);
}
async function run(fn: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}
function open(p: string) {
  if (busy.value) return;
  if (!panel.value) previous = document.activeElement as HTMLElement;
  error.value = "";
  panel.value = p;
}
function close() {
  if (busy.value) return;
  panel.value = "";
  afterLogin.value = "";
  previous?.focus();
}
function gate(p: string) {
  if (!user.value) {
    afterLogin.value = p;
    open("login");
  } else open(p);
}
function showProduct(p: Product) {
  detail.value = p;
  variant.value = (p.skus.find((s) => s.stock > 0) || p.skus[0])?.id;
  open("detail");
}
function pick(c: string) {
  cat.value = c;
  document.getElementById("catalog")?.scrollIntoView({ behavior: "smooth" });
}
function addCompare(p: Product) {
  if (compare.value.some((x) => x.id === p.id)) {
    compare.value = compare.value.filter((x) => x.id !== p.id);
    return;
  }
  if (compare.value.length && compare.value[0].kind !== p.kind) {
    tell("请先移除已选商品，再对比其他品类");
    return;
  }
  compare.value.push(p);
  tell("已加入对比");
}
const fields = computed(() =>
  Array.from(
    new Set(compare.value.flatMap((p) => Object.keys(p.attributes || {}))),
  ),
);
async function login() {
  await run(async () => {
    const u = await api("/login", "POST", {
      username: loginName.value,
      password: pwd.value,
    });
    reset();
    user.value = u;
    panel.value = staff.value
      ? "staff"
      : afterLogin.value === "add"
        ? "detail"
        : afterLogin.value || "";
    afterLogin.value = "";
    await refresh();
    tell("已登录 " + u.username);
  });
}
async function logout() {
  await run(async () => {
    await api("/logout", "POST");
    reset();
    pendingTurn = null;
    panel.value = "login";
    chatInput.value = "";
    selectedChat.value = "";
    returnKey.value = "";
    reply.value = "";
  });
}
async function setQty(id: string, n: number) {
  await api("/cart/items/" + id, "PUT", { quantity: n });
  quote.value = null;
  cart.value = await api("/cart");
}
function add() {
  const sku = selected.value;
  if (!sku) return;
  if (!user.value) {
    afterLogin.value = "add";
    open("login");
    return;
  }
  run(async () => {
    await setQty(
      sku.id,
      (cart.value.lines.find((l) => l.skuId === sku.id)?.quantity || 0) + 1,
    );
    tell("已加入购物袋");
  });
}
function checkout() {
  run(async () => {
    quote.value = await api("/quotes", "POST", {});
    chatQuote.value = false;
  });
}
function confirmOrder() {
  const id = quote.value?.id;
  if (!id) return;
  run(async () => {
    await api("/quotes/" + id + "/confirm", "POST", {});
    quote.value = null;
    panel.value = "orders";
    tell("订单已创建");
    await refresh();
    if (chatQuote.value) {
      chatQuote.value = false;
      await stream("已确认报价", crypto.randomUUID(), () => {}, id);
      await refresh();
    }
  });
}
function action(path: string, body: unknown = {}) {
  run(async () => {
    await api(path, "POST", body);
    await refresh();
    await catalog();
    returnKey.value = "";
    reason.value = "";
    returnQty.value = 1;
    tell("已更新");
  });
}
function saveSku() {
  run(async () => {
    await api("/staff/skus/" + skuEdit.value, "PATCH", {
      stock: Number(editStock.value),
      price: Number(editPrice.value),
    });
    await catalog();
    skuEdit.value = "";
    tell("规格已保存");
  });
}
async function send(msg: string) {
  if (!msg.trim() || busy.value) return;
  const text = msg.trim();
  chatInput.value = "";
  chatStatus.value = "正在连接助手…";
  await run(async () => {
    try {
      if (pendingTurn?.text !== text)
        pendingTurn = { text, id: crypto.randomUUID() };
      await stream(text, pendingTurn.id, (s) => (chatStatus.value = s));
      pendingTurn = null;
      await refresh();
    } catch (e) {
      chatInput.value = text;
      await refresh().catch(() => {});
      throw e;
    }
  });
  chatStatus.value = "";
}
function chatProduct(obj: any) {
  return products.value.find(
    (p) => String(p.id) === String(obj?.productId ?? obj?.id ?? obj),
  );
}
function links(
  v: any,
): {
  url: string;
  title?: string;
  source?: string;
  price?: number;
  currency?: string;
}[] {
  return (Array.isArray(v) ? v : v ? [v] : [])
    .map((x) => (typeof x === "string" ? { url: x } : x))
    .filter((x) => /^https?:\/\//i.test(x?.url));
}
function refreshPage() {
  loading.value = true;
  loadError.value = "";
  catalog()
    .catch((e) => (loadError.value = e.message))
    .finally(() => (loading.value = false));
}
function key(e: KeyboardEvent) {
  if (!panel.value) return;
  if (e.key === "Escape") {
    close();
    return;
  }
  if (e.key !== "Tab") return;
  const items = dialog.value?.querySelectorAll<HTMLElement>(
    'button:not([disabled]),input:not([disabled]),select,textarea,a[href],[tabindex="0"]',
  );
  if (!items?.length) return;
  const first = items[0],
    last = items[items.length - 1];
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault();
    last.focus();
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault();
    first.focus();
  }
}
watch(panel, async (p) => {
  document.body.classList.toggle("locked", !!p);
  await nextTick();
  dialog.value?.focus();
  if (
    ["chat", "staff", "orders", "cart"].includes(p) &&
    user.value &&
    !busy.value
  )
    refresh().catch((e) => (error.value = e.message));
});
watch(
  () => [chat.value?.messages.length, chatStatus.value, panel.value],
  async () => {
    await nextTick();
    if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight;
  },
);
onMounted(async () => {
  refreshPage();
  window.addEventListener("keydown", key);
  try {
    user.value = await api("/me");
    await refresh();
  } catch {}
  poll = setInterval(() => {
    if (
      !busy.value &&
      user.value &&
      ["chat", "staff", "orders"].includes(panel.value)
    )
      refresh().catch(() => {});
  }, 8000);
});
onBeforeUnmount(() => {
  clearInterval(poll);
  clearTimeout(timer);
  window.removeEventListener("keydown", key);
  document.body.classList.remove("locked");
});
</script>
<template>
  <header>
    <div class="wrap nav">
      <a class="brand" href="#" aria-label="ShopWise 首页"
        ><span class="mark"><ShoppingBag :size="24" /></span>ShopWise<span
          class="brandcn"
          >生活选物</span
        ></a
      >
      <nav>
        <button @click="pick('全部')">商品目录</button
        ><button @click="gate(staff ? 'staff' : 'orders')">
          {{ staff ? "店铺工作台" : "我的订单" }}
        </button>
      </nav>
      <div class="tools">
        <button class="account" @click="open(user ? 'account' : 'login')">
          <UserRound :size="17" /><span>{{
            user?.username || "登录"
          }}</span></button
        ><i></i
        ><button class="bag" @click="gate(staff ? 'staff' : 'cart')">
          <ShoppingBag :size="18" /><span>{{
            staff ? "工作台" : "购物袋"
          }}</span
          ><b v-if="!staff">{{ count }}</b>
        </button>
      </div>
    </div>
  </header>
  <main class="wrap">
    <section class="hero">
      <div class="heroCopy">
        <div class="heroIcon"><LampDesk :size="22" />生活选物集</div>
        <h1>把好设计，<br />带进日常。</h1>
        <p>
          从桌边的一盏灯，到每天随身的好物。<br />探索数码、服饰与家居，找到适合你的那一件。
        </p>
        <button class="dark" @click="pick('全部')">
          浏览商品 <ArrowRight :size="17" />
        </button>
      </div>
      <div class="heroPhoto">
        <img src="/media/feature.webp" alt="日常家居选物场景" />
      </div>
    </section>
    <section id="catalog">
      <div class="sectionTitle">
        <h2>精选商品</h2>
        <span>{{ allProducts.length }} 件商品</span>
      </div>
      <div class="toolbar">
        <div class="cats">
          <button
            v-for="(c, i) in cats"
            :class="{ active: cat === c }"
            @click="cat = c"
          >
            <component :is="icons[i]" :size="15" />{{ c }}
          </button>
        </div>
        <div class="searchtools">
          <label class="search"
            ><Search :size="16" /><input
              v-model="q"
              aria-label="搜索商品"
              placeholder="搜索商品" /></label
          ><button
            class="filter"
            :aria-expanded="filters"
            @click="filters = !filters"
          >
            <SlidersHorizontal :size="15" />筛选
          </button>
        </div>
      </div>
      <div v-if="filters" class="filterbox">
        <label
          >最高预算（元）<input
            type="number"
            min="0"
            v-model="budget"
            placeholder="不限" /></label
        ><label><input type="checkbox" v-model="stock" />仅看有货</label
        ><label
          >排序<select v-model="sort">
            <option value="featured">精选排序</option>
            <option value="low">价格从低到高</option>
            <option value="high">价格从高到低</option>
          </select></label
        >
      </div>
      <div v-if="loadError" class="empty">
        {{ loadError }} <button @click="refreshPage">重试</button>
      </div>
      <p v-else-if="loading" class="empty">正在载入商品…</p>
      <p v-else-if="!allProducts.length" class="empty">
        没有符合条件的商品。<button
          @click="
            q = '';
            cat = '全部';
            budget = '';
            stock = false;
          "
        >
          清除筛选
        </button>
      </p>
      <div class="grid">
        <article v-for="p in allProducts" :key="p.id">
          <div class="picture">
            <button class="photoBtn" @click="showProduct(p)">
              <img
                :src="p.image || fallback(p)"
                :alt="p.title"
                loading="lazy"
                @error="
                  ($event.target as HTMLImageElement).onerror = null;
                  ($event.target as HTMLImageElement).src = fallback(p);
                "
              /></button
            ><button
              class="compareBtn"
              :aria-label="'对比 ' + p.title"
              :aria-pressed="compare.some((x) => x.id === p.id)"
              @click="addCompare(p)"
            >
              <GitCompareArrows :size="16" />
            </button>
          </div>
          <div class="productMeta">
            {{ p.category }} <span>/</span> {{ p.kind }}
          </div>
          <button class="productTitle" @click="showProduct(p)">
            {{ p.title }}
          </button>
          <div class="priceRow">
            <strong>{{ money(min(p)) }}</strong
            ><button :aria-label="'选购 ' + p.title" @click="showProduct(p)">
              <Plus :size="16" />
            </button>
          </div>
        </article>
      </div>
    </section>
  </main>
  <footer class="wrap"><b>ShopWise</b> 生活选物</footer>
  <div class="float">
    <button v-if="compare.length" @click="open('compare')">
      <GitCompareArrows :size="17" /> 对比 {{ compare.length }}</button
    ><button class="assistantBtn" @click="gate(staff ? 'staff' : 'chat')">
      <MessageCircle :size="18" />选购助手
    </button>
  </div>
  <Transition
    ><div v-if="panel" class="overlay" @click.self="close">
      <section
        class="drawer"
        :class="{ wide: panel === 'staff' || panel === 'compare' }"
        ref="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="panel-title"
        tabindex="-1"
      >
        <div class="drawerHead">
          <h2 id="panel-title">{{ panelTitles[panel] }}</h2>
          <button class="circle" @click="close" aria-label="关闭">
            <X :size="19" />
          </button>
        </div>
        <p class="error" v-if="error" role="alert">{{ error }}</p>
        <div v-if="panel === 'login'" class="pad">
          <p>选择演示账号，体验买家或店员服务。</p>
          <form @submit.prevent="login">
            <label
              >账号<select v-model="loginName">
                <option value="buyer">买家一 · buyer</option>
                <option value="buyer2">买家二 · buyer2</option>
                <option value="staff">店员 · staff</option>
              </select></label
            ><label
              >密码<input
                v-model="pwd"
                type="password"
                autocomplete="current-password"
                required /></label
            ><button class="dark" :disabled="busy">登录</button>
          </form>
        </div>
        <div v-if="panel === 'account'" class="pad">
          <h3>{{ user?.username }}</h3>
          <p>{{ staff ? "店员账号" : "买家账号" }}</p>
          <button class="dark" @click="gate(staff ? 'staff' : 'orders')">
            {{ staff ? "进入工作台" : "查看订单" }}
          </button>
          <button class="outline" @click="logout">退出并切换账号</button>
        </div>
        <div v-if="panel === 'detail' && detail" class="pad">
          <img
            class="detailPhoto"
            :src="detail.image || fallback(detail)"
            :alt="detail.title"
          /><small>{{ detail.category }} / {{ detail.kind }}</small>
          <h2>{{ detail.title }}</h2>
          <p>{{ detail.description }}</p>
          <dl>
            <template v-for="(v, k) in detail.attributes"
              ><dt>{{ k }}</dt>
              <dd>{{ v ?? "暂无资料" }}</dd></template
            >
          </dl>
          <label
            >选择规格<select v-model="variant">
              <option v-for="s in detail.skus" :value="s.id">
                {{ s.label }} · {{ money(s.price) }} · 库存 {{ s.stock }}
              </option>
            </select></label
          >
          <div class="actions">
            <button
              v-if="!staff"
              class="dark"
              :disabled="busy || !selected?.stock"
              @click="add"
            >
              <ShoppingBag :size="16" />加入购物袋</button
            ><button class="outline" @click="addCompare(detail)">
              商品对比</button
            ><button v-if="count" class="outline" @click="gate('cart')">
              查看购物袋
            </button>
          </div>
        </div>
        <div v-if="panel === 'compare'" class="pad">
          <p v-if="!compare.length">从商品图片右上角选择要对比的商品。</p>
          <template v-else
            ><p>{{ compare[0].kind }} · 选择同类商品可逐项比较。</p>
            <div class="tableScroll">
              <table>
                <thead>
                  <tr>
                    <th>商品</th>
                    <th v-for="p in compare">
                      {{ p.title }}
                      <button
                        @click="addCompare(p)"
                        :aria-label="'移除 ' + p.title"
                      >
                        <X :size="13" />
                      </button>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <th>起售价</th>
                    <td v-for="p in compare">{{ money(min(p)) }}</td>
                  </tr>
                  <tr v-for="k in fields">
                    <th>{{ k }}</th>
                    <td v-for="p in compare">
                      {{ p.attributes?.[k] ?? "暂无资料" }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div></template
          >
        </div>
        <div v-if="panel === 'cart'" class="pad">
          <p v-if="!cart.lines.length && !quote" class="empty">
            <ShoppingBasket /><br />购物袋还是空的。<br /><button
              @click="
                close();
                pick('全部');
              "
            >
              浏览商品
            </button>
          </p>
          <template v-if="!quote"
            ><div v-for="l in cart.lines" class="row">
              <div>
                <b>{{ l.title }}</b
                ><small>{{ l.label }} · {{ money(l.price) }}</small>
              </div>
              <div class="stepper">
                <button
                  :disabled="busy"
                  @click="run(() => setQty(l.skuId, l.quantity - 1))"
                  aria-label="减少数量"
                >
                  <Minus :size="13" /></button
                >{{ l.quantity
                }}<button
                  :disabled="busy"
                  @click="run(() => setQty(l.skuId, l.quantity + 1))"
                  aria-label="增加数量"
                >
                  <Plus :size="13" />
                </button>
              </div>
            </div>
            <template v-if="cart.lines.length"
              ><div class="row">
                <b>商品小计</b><strong>{{ money(cart.subtotal) }}</strong>
              </div>
              <button class="dark" :disabled="busy" @click="checkout">
                获取结算报价
              </button></template
            ></template
          ><template v-else
            ><h3>核对报价</h3>
            <p v-if="quote.expiresAt">
              有效至 {{ new Date(quote.expiresAt).toLocaleString("zh-CN") }}
            </p>
            <div v-for="l in quote.lines" class="row">
              <div>
                {{ l.title }}<small>{{ l.label }} × {{ l.quantity }}</small>
              </div>
              {{ money(l.price * l.quantity) }}
            </div>
            <div class="row">
              商品小计 <b>{{ money(quote.subtotal) }}</b>
            </div>
            <div class="row">
              运费 <b>{{ money(quote.shipping) }}</b>
            </div>
            <div class="row">
              合计 <strong>{{ money(quote.total) }}</strong>
            </div>
            <div class="actions">
              <button class="dark" :disabled="busy" @click="confirmOrder">
                确认创建订单</button
              ><button class="outline" @click="quote = null">返回购物袋</button>
            </div></template
          >
        </div>
        <div v-if="panel === 'staff'" class="tabs">
          <button
            v-for="t in ['订单', '售后', '库存', '会话']"
            :class="{ active: tab === t }"
            @click="tab = t"
          >
            {{ t }}
          </button>
        </div>
        <div
          v-if="panel === 'orders' || (panel === 'staff' && tab === '订单')"
          class="pad"
        >
          <p v-if="!orders.length" class="empty">暂无订单</p>
          <section class="record" v-for="o in orders" :key="o.id">
            <div class="row">
              <small>订单 {{ o.id }}</small
              ><b>{{ states[o.status] || o.status }}</b>
            </div>
            <div class="row" v-for="l in o.lines">
              <div>
                {{ l.title }}<small>{{ l.label }} × {{ l.quantity }}</small>
              </div>
              {{ money(l.price * l.quantity) }}
            </div>
            <div class="row">
              含运费 {{ money(o.shipping)
              }}<strong>{{ money(o.total) }}</strong>
            </div>
            <p v-if="o.tracking">物流单号 {{ o.tracking }}</p>
            <details>
              <summary>订单进度</summary>
              <ol>
                <li v-for="e in o.events">
                  {{ states[e.status] || e.status }} ·
                  {{ new Date(e.at).toLocaleString("zh-CN") }}
                </li>
              </ol>
            </details>
            <div class="actions">
              <template v-if="!staff && o.status === 'PENDING_PAYMENT'"
                ><button
                  class="dark"
                  :disabled="busy"
                  @click="action('/orders/' + o.id + '/pay')"
                >
                  模拟付款</button
                ><button
                  class="outline"
                  :disabled="busy"
                  @click="action('/orders/' + o.id + '/cancel')"
                >
                  取消订单
                </button></template
              ><button
                v-if="staff && o.status === 'PAID'"
                class="dark"
                :disabled="busy"
                @click="action('/orders/' + o.id + '/ship')"
              >
                确认发货</button
              ><button
                v-if="!staff && o.status === 'SHIPPED'"
                class="dark"
                :disabled="busy"
                @click="action('/orders/' + o.id + '/receive')"
              >
                确认收货
              </button>
            </div>
            <template v-if="!staff && o.status === 'COMPLETED'"
              ><div v-for="l in o.lines">
                <button
                  class="link"
                  @click="
                    returnKey = o.id + '/' + l.skuId;
                    reason = '';
                    returnQty = 1;
                  "
                >
                  申请售后 · {{ l.label }}
                </button>
                <form
                  v-if="returnKey === o.id + '/' + l.skuId"
                  @submit.prevent="
                    action('/orders/' + o.id + '/returns', {
                      skuId: l.skuId,
                      quantity: returnQty,
                      reason,
                    })
                  "
                >
                  <label
                    >数量<input
                      type="number"
                      min="1"
                      :max="l.quantity"
                      required
                      v-model.number="returnQty" /></label
                  ><label>原因<input required v-model="reason" /></label
                  ><button class="dark" :disabled="busy">提交申请</button>
                </form>
              </div></template
            >
          </section>
        </div>
        <div
          v-if="panel === 'orders' || (panel === 'staff' && tab === '售后')"
          class="pad"
        >
          <h3>售后记录</h3>
          <p v-if="!returns.length">暂无售后申请</p>
          <section class="record" v-for="r in returns">
            <div class="row">
              <b>{{ r.title }} × {{ r.quantity }}</b
              ><span>{{ states[r.status] || r.status }}</span>
            </div>
            <p>{{ r.reason }} · {{ money(r.amount) }}</p>
            <div class="actions" v-if="staff">
              <template v-if="r.status === 'REQUESTED'"
                ><button
                  class="dark"
                  @click="action('/returns/' + r.id + '/approve')"
                >
                  同意退货</button
                ><button
                  class="outline"
                  @click="action('/returns/' + r.id + '/reject')"
                >
                  驳回
                </button></template
              ><button
                v-if="r.status === 'APPROVED'"
                class="dark"
                @click="action('/returns/' + r.id + '/refund')"
              >
                确认退款
              </button>
            </div>
          </section>
        </div>
        <div v-if="panel === 'staff' && tab === '库存'" class="pad">
          <section v-for="p in products" class="record">
            <b>{{ p.title }}</b>
            <div v-for="s in p.skus">
              <div class="row">
                <div>
                  {{ s.label
                  }}<small>{{ money(s.price) }} · 库存 {{ s.stock }}</small>
                </div>
                <button
                  class="link"
                  @click="
                    skuEdit = s.id;
                    editPrice = String(s.price);
                    editStock = String(s.stock);
                  "
                >
                  编辑
                </button>
              </div>
              <form v-if="skuEdit === s.id" @submit.prevent="saveSku">
                <label
                  >价格<input
                    type="number"
                    min="0.01"
                    step="0.01"
                    required
                    v-model="editPrice" /></label
                ><label
                  >库存<input
                    type="number"
                    min="0"
                    step="1"
                    required
                    v-model="editStock" /></label
                ><button class="dark" :disabled="busy">保存规格</button>
              </form>
            </div>
          </section>
        </div>
        <div v-if="panel === 'staff' && tab === '会话'" class="pad">
          <p v-if="!inbox.length">暂无买家会话</p>
          <template v-if="contact"
            ><label
              >买家会话<select
                :value="contact.id"
                @change="
                  selectedChat = ($event.target as HTMLSelectElement).value
                "
              >
                <option v-for="c in inbox" :value="c.id">
                  {{ c.buyer }} · {{ c.takenOver ? "人工服务" : "助手服务" }}
                </option>
              </select></label
            ><button
              class="outline"
              @click="
                action(
                  '/staff/chats/' +
                    contact.id +
                    '/' +
                    (contact.takenOver ? 'release' : 'takeover'),
                )
              "
            >
              {{ contact.takenOver ? "交还助手" : "接管会话" }}
            </button>
            <div class="bubble" v-for="m in contact.messages">
              <small>{{
                m.role === "user"
                  ? "买家"
                  : m.role === "staff"
                    ? "店员"
                    : "助手"
              }}</small
              >{{ m.text }}
            </div>
            <form
              v-if="contact.takenOver"
              @submit.prevent="
                run(async () => {
                  await api('/staff/chats/' + contact!.id + '/reply', 'POST', {
                    text: reply,
                  });
                  reply = '';
                  await refresh();
                })
              "
            >
              <label
                >回复买家<textarea required v-model="reply"></textarea></label
              ><button class="dark" :disabled="busy">发送回复</button>
            </form></template
          >
        </div>
        <template v-if="panel === 'chat'"
          ><div class="conversation" ref="chatBody">
            <p v-if="!chat?.messages.length">
              想找什么商品？告诉我用途和预算，我帮你挑选。
            </p>
            <div
              v-for="m in chat?.messages"
              class="bubble"
              :class="{ own: m.role === 'user' }"
            >
              <small>{{
                m.role === "user"
                  ? "我"
                  : m.role === "staff"
                    ? "人工客服"
                    : "选购助手"
              }}</small
              >{{ m.text
              }}<template v-for="p in m.products"
                ><button
                  v-if="chatProduct(p)"
                  class="chatCard"
                  @click="showProduct(chatProduct(p)!)"
                >
                  {{ chatProduct(p)!.title }}
                  <ArrowUpRight :size="14" /></button></template
              ><button
                v-if="m.quote?.id"
                class="chatCard"
                @click="
                  quote = m.quote;
                  chatQuote = true;
                  open('cart');
                "
              >
                核对报价 {{ money(m.quote.total) }}</button
              ><a
                v-for="l in links(m.external)"
                :href="l.url"
                target="_blank"
                rel="noopener noreferrer"
                >{{ l.title || l.url }} ↗
                <small>{{ l.source }} {{ l.currency }} {{ l.price }}</small></a
              >
            </div>
            <small role="status">{{ chatStatus }}</small>
          </div>
          <div class="composer">
            <button
              class="link"
              :disabled="busy || chat?.takenOver"
              @click="send('转人工')"
            >
              {{ chat?.takenOver ? "人工客服已接入" : "转人工客服" }}
            </button>
            <form @submit.prevent="send(chatInput)">
              <input
                v-model="chatInput"
                placeholder="输入选购或订单问题…"
                aria-label="消息"
                required
              /><button
                class="dark"
                :disabled="busy || !chatInput.trim()"
                aria-label="发送"
              >
                <Send :size="17" />
              </button>
            </form></div
        ></template>
      </section></div
  ></Transition>
  <div v-if="notice" class="toast" role="status">
    <Check :size="16" />{{ notice }}
  </div>
</template>

<style scoped src="./style.css"></style>
