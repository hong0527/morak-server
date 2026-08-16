import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 백엔드 주소는 프록시로만 바꾼다. 코드에는 /api 상대경로만 남긴다 —
// 그래야 배포 때 오리진이 바뀌어도 고칠 곳이 여기 하나다.
const BACKEND = process.env.MORAK_BACKEND ?? "http://localhost:8180";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: BACKEND, changeOrigin: true },
    },
  },
  optimizeDeps: {
    // file: 로 링크된 감지 모듈은 사전 번들에서 뺀다. 임계값을 고치고
    // sync:ai 를 다시 돌렸을 때 캐시된 옛 코드가 남지 않게 하려는 것이다.
    exclude: ["molock-ai-detection"],
  },
});
