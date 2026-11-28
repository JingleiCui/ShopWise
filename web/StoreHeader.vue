<script setup lang="ts">
import { ArrowUpRight } from "lucide-vue-next";
defineProps<{ orders?: boolean }>();
const emit = defineEmits<{ home: [] }>();
</script>

<template>
  <header class="sw-header">
    <div class="sw-header-inner">
      <a class="sw-wordmark" href="#/" aria-label="ShopWise 首页" @click="emit('home')">ShopWise<span aria-hidden="true">.</span></a>
      <nav class="sw-nav" aria-label="主导航">
        <a href="#/" :aria-current="orders ? undefined : 'page'" @click="emit('home')">选购</a>
        <a href="#/merchant" :aria-current="orders ? 'page' : undefined">合作店订单</a>
      </nav>
      <div v-if="$slots.tools" class="sw-header-tools"><slot name="tools" /></div>
      <a v-else class="sw-header-return" href="#/">继续选购 <ArrowUpRight :size="15" /></a>
    </div>
  </header>
</template>
