<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { ArrowLeft, ArrowRight, ArrowUpRight, Check, GitCompareArrows, MapPin, MessageCircle, Search, SlidersHorizontal, X } from "lucide-vue-next";
import DiscoveryPhoto from "./DiscoveryPhoto.vue";
import StoreHeader from "./StoreHeader.vue";
import StoreFooter from "./StoreFooter.vue";
import {
  askAssistant, fresh, keyOf, priceText, referenceText, searchCatalog, sourceName, stockText, verifyOffer,
  type AssistantResult, type Offer,
} from "./discovery";

const photoSource = "https://commons.wikimedia.org/wiki/File:Type_1227_desk_lamp,_Anglepoise,_designed_by_George_Carwardine,_manufactured_by_Herbert_Terry_and_Sons,_1935_-_Design_Museum,_Kensington_-_London_-_DSC01571.jpg";
const categories = [
  { label: "阅读灯", query: "desk lamp" },
  { label: "耳机", query: "headphones" },
  { label: "背包", query: "backpack" },
];
const productShelf = ref<HTMLElement>();
const keyword = ref("");
const budget = ref("");
const catalog = ref<Awaited<ReturnType<typeof searchCatalog>>>();
const catalogError = ref("");
const loading = ref(false);
const activeCategory = ref(categories[0].label);
const shelfTitle = ref("精选阅读灯");
const displayedQuery = ref("");
const displayedBudget = ref("");
const compared = ref<Offer[]>([]);
const comparing = ref(false);
const compareHeading = ref<HTMLElement>();
const compareAction = ref<HTMLElement>();
let shelfScroll = 0;
const now = ref(Date.now());
let clock: ReturnType<typeof setInterval>;
let catalogSequence = 0;
const shelf = computed(() => catalog.value?.offers.filter(offer => fresh(offer, now.value)) ?? []);
const expiredShelf = computed(() => Boolean(catalog.value?.offers.length && !shelf.value.length));
const matching = computed(() => catalog.value?.matches.flatMap(match => {
  const offers = shelf.value.filter(offer => match.offers.includes(keyOf(offer)));
  return new Set(offers.map(offer => offer.provider)).size > 1 ? [{ ...match, items: offers }] : [];
}) ?? []);

async function find(query: string, category = "", limit = budget.value) {
  const sequence = ++catalogSequence;
  loading.value = true;
  catalogError.value = "";
  activeCategory.value = category;
  shelfTitle.value = category ? "精选" + category : "搜索结果";
  displayedQuery.value = query;
  displayedBudget.value = limit;
  catalog.value = undefined;
  try {
    const result = await searchCatalog(query, limit);
    if (sequence === catalogSequence) catalog.value = result;
  } catch (error) {
    if (sequence === catalogSequence) catalogError.value = (error as Error).message;
  } finally {
    if (sequence === catalogSequence) loading.value = false;
  }
}
function selectCategory(category: typeof categories[number], reveal = false) {
  keyword.value = "";
  if (!(reveal && activeCategory.value === category.label && displayedQuery.value === category.query && catalog.value)) {
    void find(category.query, category.label);
  }
  if (reveal) void revealShelf();
}
async function revealShelf() {
  await nextTick();
  productShelf.value?.scrollIntoView({ block: "start" });
  productShelf.value?.focus({ preventScroll: true });
}
async function goHome() {
  if (detail.value) await closeDetail(false);
  if (comparing.value) await showComparison(false);
  window.scrollTo({ top: 0 });
}
async function submitSearch() {
  const query = keyword.value.trim();
  if (query) {
    if (detail.value) await closeDetail(false);
    if (comparing.value) await showComparison(false);
    const category = categories.find(item => item.label === query);
    void find(category?.query ?? query, "", budget.value);
    void revealShelf();
  } else {
    void find(displayedQuery.value || categories[0].query, activeCategory.value, budget.value);
  }
}
onMounted(() => {
  selectCategory(categories[0]);
  clock = setInterval(() => { now.value = Date.now(); }, 1000);
});
onBeforeUnmount(() => {
  clearInterval(clock);
  catalogSequence++;
});

function isSelected(offer: Offer) { return compared.value.some(item => keyOf(item) === keyOf(offer)); }
function toggle(offer: Offer) {
  if (isSelected(offer)) compared.value = compared.value.filter(item => keyOf(item) !== keyOf(offer));
  else if (compared.value.length < 4) compared.value = [...compared.value, offer];
  if (comparing.value && compared.value.length < 2) void showComparison(false);
}
async function showComparison(show: boolean) {
  if (show) shelfScroll = window.scrollY;
  comparing.value = show;
  await nextTick();
  const target = show ? compareHeading.value : compareAction.value;
  target?.focus({ preventScroll: true });
  window.scrollTo({ top: show ? 0 : shelfScroll });
}
const specKeys = computed(() => [...new Set(compared.value.flatMap(item => Object.keys(item.attributes ?? {})))]);
const specLabels: Record<string, string> = {
  color: "颜色", size: "尺寸", material: "材质", style: "款式",
};
const specLabel = (name: string) => specLabels[name.toLowerCase()] ?? name;

const detailHeading = ref<HTMLElement>();
const detail = ref<Offer>();
const checked = ref<Offer>();
const checkError = ref("");
const checking = ref(false);
let detailSequence = 0;
let detailTrigger: HTMLElement | null = null;
let detailScroll = 0;
const verified = computed(() => checked.value && fresh(checked.value, now.value) ? checked.value : undefined);
const purchaseUrl = computed(() => {
  if (verified.value?.available === false || !verified.value?.url?.startsWith("https://")) return "";
  return verified.value.url;
});
const detailOffer = computed(() => checked.value ?? detail.value);
async function showDetail(offer: Offer) {
  if (!detail.value) {
    detailScroll = window.scrollY;
    detailTrigger = document.activeElement as HTMLElement | null;
  }
  detailSequence++;
  detail.value = offer;
  checked.value = undefined;
  checkError.value = "";
  checking.value = false;
  await nextTick();
  detailHeading.value?.focus({ preventScroll: true });
  window.scrollTo({ top: 0 });
}
async function closeDetail(restore = true) {
  detailSequence++;
  detail.value = undefined;
  checked.value = undefined;
  await nextTick();
  if (restore) {
    const key = detailTrigger?.dataset.offerKey;
    const replacement = key && Array.from(document.querySelectorAll<HTMLElement>("[data-offer-key]"))
      .find(button => button.dataset.offerKey === key && button.className === detailTrigger?.className);
    const target = detailTrigger?.isConnected ? detailTrigger : replacement || compareHeading.value || productShelf.value;
    target?.focus({ preventScroll: true });
    window.scrollTo({ top: detailScroll });
  }
}
function updateOffer(offer: Offer) {
  compared.value = compared.value.map(item => keyOf(item) === keyOf(offer) ? offer : item);
  if (catalog.value) {
    catalog.value.offers = catalog.value.offers.map(item => keyOf(item) === keyOf(offer) ? offer : item);
    catalog.value.matches = catalog.value.matches.filter(match => !match.offers.includes(keyOf(offer)));
  }
}
async function recheck() {
  if (!detail.value || checking.value) return;
  const sequence = detailSequence;
  checking.value = true;
  checked.value = undefined;
  checkError.value = "";
  try {
    const result = await verifyOffer(detail.value);
    if (sequence !== detailSequence) return;
    checked.value = result;
    updateOffer(result);
  } catch (error) {
    if (sequence === detailSequence) checkError.value = (error as Error).message;
  } finally {
    if (sequence === detailSequence) checking.value = false;
  }
}

interface Message { question: string; result?: AssistantResult; error?: string }
const assistantOpen = ref(false);
const assistantInput = ref<HTMLTextAreaElement>();
const assistantTrigger = ref<HTMLElement>();
const assistantContext = ref<Offer[]>([]);
const thread = ref<Message[]>([]);
const question = ref("");
const thinking = ref(false);
const progress = ref("");
const contextKey = (offers: Offer[]) => offers.map(keyOf).join("\n");
const threads = new Map<string, Message[]>();
let assistantOrigin: HTMLElement | null = null;
async function openAssistant(offers: Offer[] = []) {
  assistantOrigin = document.activeElement as HTMLElement | null;
  assistantContext.value = [...offers];
  thread.value = threads.get(contextKey(offers)) ?? [];
  question.value = "";
  assistantOpen.value = true;
  await nextTick();
  assistantInput.value?.focus({ preventScroll: true });
}
async function closeAssistant() {
  assistantOpen.value = false;
  await nextTick();
  (assistantOrigin?.isConnected ? assistantOrigin : assistantTrigger.value)?.focus({ preventScroll: true });
}
async function ask(text = question.value) {
  const input = text.trim();
  if (!input || thinking.value) return;
  const references = assistantContext.value.map(({ provider, variantId }) => ({ provider, variantId }));
  const key = contextKey(assistantContext.value);
  const conversation = [...thread.value, { question: input }] as Message[];
  const last = conversation[conversation.length - 1];
  threads.set(key, conversation);
  thread.value = conversation;
  question.value = "";
  thinking.value = true;
  progress.value = references.length ? "正在核对所选商品…" : "正在查询商品…";
  try {
    last.result = await askAssistant(input, references, status => {
      if (!references.length) progress.value = status;
    });
  } catch (error) {
    last.error = (error as Error).message;
  } finally {
    if (contextKey(assistantContext.value) === key) thread.value = [...conversation];
    thinking.value = false;
    progress.value = "";
  }
}
</script>

<template>
  <div class="sw-shell market">
    <StoreHeader @home="goHome">
      <template #tools>
        <form class="market-search" role="search" @submit.prevent="submitSearch">
          <Search :size="19" aria-hidden="true" />
          <input v-model="keyword" aria-label="商品关键词" placeholder="搜索商品或型号" maxlength="120" />
          <button type="submit">搜索</button>
        </form>
        <button ref="assistantTrigger" class="market-help" type="button" :aria-expanded="assistantOpen"
                @click="assistantOpen ? closeAssistant() : openAssistant()">
          <MessageCircle :size="18" aria-hidden="true" /> 选购助手
        </button>
      </template>
    </StoreHeader>

    <div class="market-layout sw-width" :class="{ 'with-assistant': assistantOpen }">
      <main class="market-main">
        <template v-if="!comparing && !detail">
          <section v-if="activeCategory" class="market-cover" aria-labelledby="market-title">
            <div class="market-cover-copy">
              <h1 id="market-title">跨店选购</h1>
              <p>浏览阅读灯、耳机和背包，比较不同商家的报价与规格。</p>
              <button class="sw-button primary" type="button" @click="selectCategory(categories[0], true)">
                浏览阅读灯 <ArrowRight :size="17" />
              </button>
            </div>
            <figure class="market-cover-photo">
              <img src="/media/editorial-anglepoise.webp" alt="设计博物馆展出的黄铜色 Anglepoise 1227 台灯"
                   width="1100" height="1271" fetchpriority="high" />
              <figcaption><a :href="photoSource" target="_blank" rel="noopener noreferrer">Anglepoise 1227 · 馆藏实拍 <ArrowUpRight :size="13" /></a></figcaption>
            </figure>
          </section>
          <section v-else class="search-intro" aria-labelledby="market-title">
            <h1 id="market-title">搜索商品</h1>
          </section>
          <section ref="productShelf" class="market-shelf" aria-labelledby="shelf-title" :aria-busy="loading" tabindex="-1">
            <div class="shelf-heading">
              <h2 id="shelf-title">{{ shelfTitle }}</h2>
              <span class="market-destination"><MapPin :size="16" aria-hidden="true" /> 中国大陆收货</span>
            </div>
            <div class="shelf-toolbar">
              <nav class="market-categories" aria-label="浏览分类">
                <button v-for="category in categories" :key="category.label" :aria-pressed="activeCategory === category.label"
                        type="button" @click="selectCategory(category)">{{ category.label }}</button>
              </nav>
              <form class="market-budget" @submit.prevent="submitSearch">
                <label for="market-budget"><SlidersHorizontal :size="16" aria-hidden="true" /> 商品价上限</label>
                <input id="market-budget" v-model="budget" type="number" min="0" max="100000000" step=".01" placeholder="¥ 不限" />
                <button class="sw-button secondary" type="submit">筛选</button>
              </form>
            </div>
            <p v-if="loading" class="sr-only" role="status">正在查询商品…</p>
            <div v-if="loading" class="product-grid" aria-hidden="true">
              <div v-for="index in 4" :key="index" class="product-skeleton"><div></div><span></span><span></span></div>
            </div>
            <p v-else-if="catalogError" class="market-notice" role="alert">
              {{ catalogError }} <button type="button" @click="find(displayedQuery, activeCategory, displayedBudget)">重试</button>
            </p>
            <p v-else-if="catalog && !catalog.anyAvailable" class="market-notice" role="status">
              商品来源暂不可用。 <button type="button" @click="find(displayedQuery, activeCategory, displayedBudget)">重试</button>
            </p>
            <p v-else-if="expiredShelf" class="market-notice" role="status">
              这批报价已过期。 <button type="button" @click="find(displayedQuery, activeCategory, displayedBudget)">刷新商品</button>
            </p>
            <p v-else-if="catalog && !shelf.length" class="market-notice" role="status">暂无匹配商品。试试其他关键词或调整商品价上限。</p>
            <p v-if="catalog?.anyAvailable && catalog.notices.length" class="market-source-status" role="status">{{ catalog.notices.join(" · ") }}</p>
            <div v-if="shelf.length" class="product-grid">
              <article v-for="offer in shelf" :key="keyOf(offer)" class="market-product">
                <button type="button" class="product-open" :data-offer-key="keyOf(offer)" :aria-label="'查看商品 ' + offer.title" @click="showDetail(offer)">
                  <DiscoveryPhoto :src="offer.imageUrl" :title="offer.title" />
                </button>
                <div class="product-body">
                  <p class="product-source">{{ offer.source }} · {{ sourceName(offer.provider) }}</p>
                  <h3>{{ offer.title }}</h3>
                  <p class="product-variant">{{ offer.variant || stockText(offer) }}</p>
                  <div class="product-quote">
                    <strong class="product-price">{{ priceText(offer) }}</strong>
                    <small class="product-reference">{{ referenceText(offer) }}</small>
                  </div>
                  <div class="product-actions">
                    <button class="market-action primary compact" type="button" :data-offer-key="keyOf(offer)" @click="showDetail(offer)">查看商品</button>
                    <button class="market-action secondary compact" type="button" :aria-pressed="isSelected(offer)"
                            :disabled="!isSelected(offer) && compared.length >= 4" @click="toggle(offer)">
                      <component :is="isSelected(offer) ? Check : GitCompareArrows" :size="15" />
                      {{ isSelected(offer) ? "已加入" : "加入对比" }}
                    </button>
                  </div>
                </div>
              </article>
            </div>
            <p v-if="shelf.length" class="shelf-footnote">所示为商品价，运费、进口税费及实际配送由商家确认。</p>
          </section>

          <section v-if="matching.length" class="same-products" aria-labelledby="same-title">
            <h2 id="same-title">跨平台同款</h2>
            <div v-for="group in matching" :key="group.offers.join()" class="market-notice">
              <strong>{{ group.items[0].title }}</strong>
              <p>{{ group.evidence === "GTIN" ? "商品条码一致" : "品牌、制造商料号和规格一致" }}</p>
              <ul><li v-for="offer in group.items" :key="keyOf(offer)">{{ offer.source }} · {{ priceText(offer) }}</li></ul>
            </div>
          </section>
        </template>

        <section v-else-if="!detail" class="comparison" aria-labelledby="compare-title">
          <button type="button" class="market-action text-action" @click="showComparison(false)"><ArrowLeft :size="17" /> 继续逛</button>
          <div class="comparison-heading">
            <div><h1 id="compare-title" ref="compareHeading" tabindex="-1">商品对比</h1><p>{{ compared.length }} 款商品</p></div>
            <button class="market-action secondary" type="button" @click="openAssistant(compared)"><MessageCircle :size="17" /> 帮我看差异</button>
          </div>
          <p class="comparison-mobile-hint">左右滑动查看全部商品</p>
          <div class="comparison-scroll" :style="{ '--offer-columns': compared.length }" tabindex="0" aria-label="商品规格和报价对比">
            <table>
              <thead><tr><th scope="col">比较项</th><th v-for="offer in compared" :key="keyOf(offer)" scope="col">
                <DiscoveryPhoto :src="offer.imageUrl" :title="offer.title" />
                <button class="comparison-title" type="button" :data-offer-key="keyOf(offer)" @click="showDetail(offer)">{{ offer.title }}</button>
              </th></tr></thead>
              <tbody>
                <tr><th scope="row">商家</th><td v-for="offer in compared" :key="keyOf(offer)">{{ offer.source }}</td></tr>
                <tr><th scope="row">规格</th><td v-for="offer in compared" :key="keyOf(offer)">{{ offer.variant || "商家未提供" }}</td></tr>
                <tr v-for="spec in specKeys" :key="spec"><th scope="row">{{ specLabel(spec) }}</th><td v-for="offer in compared" :key="keyOf(offer)">{{ offer.attributes?.[spec] || "商家未提供" }}</td></tr>
                <tr><th scope="row">库存</th><td v-for="offer in compared" :key="keyOf(offer)">{{ fresh(offer, now) ? stockText(offer) : "报价已过期" }}</td></tr>
                <tr><th scope="row">商品价</th><td v-for="offer in compared" :key="keyOf(offer)"><strong>{{ priceText(offer) }}</strong>
                  <small v-if="referenceText(offer)">{{ referenceText(offer) }}</small></td></tr>
                <tr><th scope="row">运费与税费</th><td v-for="offer in compared" :key="keyOf(offer)">待商家确认</td></tr>
                <tr><th scope="row">下一步</th><td v-for="offer in compared" :key="keyOf(offer)">
                  <div class="comparison-actions">
                    <button class="market-action primary compact" type="button" @click="showDetail(offer)">查看与核价</button>
                    <button class="market-action text-action compact" type="button" @click="toggle(offer)">移出对比</button>
                  </div>
                </td></tr>
              </tbody>
            </table>
          </div>
        </section>
        <section v-else-if="detail && detailOffer" class="market-detail" aria-labelledby="detail-title">
          <button class="sw-button plain" type="button" @click="closeDetail()">
            <ArrowLeft :size="17" /> {{ comparing ? "返回对比" : "返回商品列表" }}
          </button>
          <div class="detail-layout">
            <DiscoveryPhoto :src="detailOffer.imageUrl ?? detail.imageUrl" :title="detailOffer.title" />
            <div class="detail-body">
              <p class="product-source">{{ detailOffer.source }} · {{ sourceName(detailOffer.provider) }}</p>
              <h1 id="detail-title" ref="detailHeading" tabindex="-1">{{ detailOffer.title }}</h1>
              <p v-if="detailOffer.variant">{{ detailOffer.variant }}</p>
              <div class="detail-price">
                <strong class="product-price">{{ priceText(detailOffer) }}</strong>
                <small v-if="referenceText(detailOffer)">{{ referenceText(detailOffer) }}</small>
              </div>
              <p>{{ fresh(detailOffer, now) ? stockText(detailOffer) : "报价已过期" }} · 运费和进口税费待商家确认</p>
              <div class="detail-actions">
                <a v-if="purchaseUrl" class="sw-button primary" :href="purchaseUrl" target="_blank" rel="noopener noreferrer">
                  去 {{ detailOffer.source }} 购买 <ArrowUpRight :size="17" />
                </a>
                <button v-else class="sw-button primary" type="button" :disabled="checking" @click="recheck">
                  {{ checking ? "正在核对…" : "核对最新报价" }}
                </button>
                <div>
                  <button class="sw-button secondary" type="button" @click="openAssistant([detailOffer])">
                    <MessageCircle :size="17" /> 问问这款
                  </button>
                  <button class="sw-button secondary" type="button" :aria-pressed="isSelected(detailOffer)"
                          :disabled="!isSelected(detailOffer) && compared.length >= 4" @click="toggle(detailOffer)">
                    <GitCompareArrows :size="17" /> {{ isSelected(detailOffer) ? "移出对比" : "加入对比" }}
                  </button>
                </div>
              </div>
              <div class="verification" aria-live="polite">
                <p v-if="checkError" role="alert">{{ checkError }}</p>
                <template v-if="verified">
                  <p><Check :size="16" /> 已核对 · {{ stockText(verified) }}</p>
                  <p v-if="priceText(detail) !== priceText(verified)">商品价已更新，{{ priceText(detail) }} → {{ priceText(verified) }}</p>
                </template>
                <p v-else-if="checked">核对结果已过期，请重新核价。</p>
              </div>
              <dl v-if="Object.keys(detailOffer.attributes ?? {}).length" class="detail-specs">
                <div v-for="(value, name) in detailOffer.attributes" :key="name"><dt>{{ specLabel(name) }}</dt><dd>{{ value }}</dd></div>
              </dl>
            </div>
          </div>
        </section>
      </main>

      <aside v-if="assistantOpen" id="market-assistant" class="market-assistant" aria-labelledby="assistant-title" @keydown.esc="closeAssistant">
        <div class="assistant-heading">
          <h2 id="assistant-title"><MessageCircle :size="21" /> 选购助手</h2>
          <button class="market-icon" type="button" aria-label="关闭选购助手" @click="closeAssistant"><X :size="21" /></button>
        </div>
        <ul v-if="assistantContext.length" class="assistant-context" aria-label="助手参考的商品">
          <li v-for="offer in assistantContext" :key="keyOf(offer)">{{ offer.title }}</li>
        </ul>
        <p v-else class="assistant-hint">告诉我想买什么、收货城市和商品价预算。</p>
        <div v-if="assistantContext.length" class="assistant-suggestions">
          <button class="market-action secondary compact" type="button" :disabled="thinking"
                  @click="ask(assistantContext.length > 1 ? '帮我看差异' : '核对商品价格')">
            {{ assistantContext.length > 1 ? "帮我看差异" : "核对商品价格" }}
          </button>
          <button class="market-action secondary compact" type="button" :disabled="thinking" @click="ask('运费和税费怎么算？')">运费和税费</button>
        </div>
        <div class="assistant-thread" aria-live="polite" aria-relevant="additions text">
          <article v-for="(message, index) in thread" :key="index">
            <p class="question-bubble">{{ message.question }}</p>
            <p v-if="message.error" class="market-notice" role="alert">{{ message.error }}
              <button type="button" :disabled="thinking" @click="ask(message.question)">重试</button></p>
            <template v-if="message.result">
              <p class="answer-bubble">{{ message.result.text }}</p>
              <p v-if="message.result.filters.city || message.result.filters.budget != null" class="assistant-hint">
                {{ message.result.filters.city ? "收货地 " + message.result.filters.city : "" }}
                {{ message.result.filters.budget == null ? "" : " · 商品价预算 ¥" + message.result.filters.budget }}
              </p>
              <p v-if="message.result.degradation?.startsWith('model_')" class="assistant-hint">当前使用基础筛选，也可直接搜索商品。</p>
              <ul class="assistant-offers">
                <li v-for="offer in message.result.offers" :key="keyOf(offer)">
                  <button type="button" @click="showDetail(offer)"><span>{{ offer.title }}</span><strong>{{ priceText(offer) }}</strong></button>
                </li>
              </ul>
            </template>
          </article>
          <p v-if="thinking" class="assistant-hint" role="status">{{ progress }}</p>
        </div>
        <form class="assistant-form" @submit.prevent="ask()">
          <textarea ref="assistantInput" v-model="question" aria-label="购物需求" maxlength="400"
                    rows="2" :placeholder="assistantContext.length ? '问问规格、价格或库存' : '例如：找一盏商品价 300 元以内的阅读灯'" />
          <button class="market-action primary" type="submit" :disabled="thinking || !question.trim()">发送</button>
        </form>
      </aside>
    </div>

    <div v-if="compared.length && !comparing && !detail" class="compare-bar" aria-label="已选对比商品">
      <span><GitCompareArrows :size="19" /> <strong>已选 {{ compared.length }} / 4</strong></span>
      <div class="compare-items">
        <button v-for="offer in compared" :key="keyOf(offer)" type="button" :aria-label="'移出对比 ' + offer.title" @click="toggle(offer)">
          {{ offer.title }} <X :size="14" />
        </button>
      </div>
      <span v-if="compared.length < 2" class="compare-instruction">再选 1 款即可比较</span>
      <button v-else ref="compareAction" class="market-action primary compact" type="button" @click="showComparison(true)">对比 {{ compared.length }} 款</button>
    </div>

    <StoreFooter />
  </div>
</template>

<style scoped src="./discovery.css"></style>
