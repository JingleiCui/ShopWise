import { createApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createWebHashHistory, RouterView, type RouteRecordRaw } from "vue-router";
import DiscoveryView from "./DiscoveryView.vue";
import "./base.css";
import "./storefront.css";
const routes: RouteRecordRaw[] = [{ path:"/", component:DiscoveryView },
  {path:"/merchant",component:()=>import("./MerchantView.vue")}];
if (import.meta.env.DEV) routes.push({ path:"/demo", component:()=>import("./App.vue") });
const router=createRouter({history:createWebHashHistory(),routes});
createApp(RouterView).use(createPinia()).use(router).mount("#app");
