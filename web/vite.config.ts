import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
const proxy = {
  "/api": {
    target: process.env.SHOPWISE_API_URL || "http://127.0.0.1:20174",
    changeOrigin: true,
  },
};
export default defineConfig({
  plugins: [vue()],
  server: { host: "127.0.0.1", port: 20173, strictPort: true, proxy },
  preview: { host: "127.0.0.1", port: 20173, strictPort: true, proxy },
});
