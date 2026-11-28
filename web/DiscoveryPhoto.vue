<script setup lang="ts">
import { ref, watch } from "vue";
import { ImageOff } from "lucide-vue-next";
const props = defineProps<{ src?: string | null; title: string }>();
const failed = ref(false);
watch(() => props.src, () => { failed.value = false; });
</script>

<template>
  <div class="discovery-photo">
    <img v-if="src?.startsWith('https://') && !failed" :src="src" :alt="title"
         loading="lazy" referrerpolicy="no-referrer" @error="failed = true" />
    <span v-else><ImageOff :size="24" aria-hidden="true" /> 商家暂无商品图</span>
  </div>
</template>

<style scoped>
.discovery-photo { display: grid; place-items: center; aspect-ratio: 4 / 3; background: #f0f1f3; overflow: hidden; }
.discovery-photo img { width: 100%; height: 100%; object-fit: contain; display: block; min-height: 0; }
.discovery-photo span { display: grid; justify-items: center; gap: .7rem; font-size: .8rem; color: #626670; }
</style>
