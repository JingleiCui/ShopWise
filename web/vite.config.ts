import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
const proxy = {
  "/api": {
    target: process.env.SHOPWISE_API_URL || "http://localhost:18081",
    changeOrigin: true,
  },
};
export default defineConfig({
  plugins: [vue()],
  server: { host: "127.0.0.1", port: 15173, proxy },
  preview: { port: 15173, proxy },
});
