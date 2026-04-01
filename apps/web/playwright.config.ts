import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  use: {
    baseURL: "http://127.0.0.1:4173",
    browserName: "chromium",
    headless: true
  },
  webServer: [
    {
      command: "corepack pnpm --filter @xrag/api build && PORT=3001 node ../api/dist/apps/api/src/main.js",
      url: "http://127.0.0.1:3001/api/v1/health",
      reuseExistingServer: true,
      timeout: 60_000
    },
    {
      command: "VITE_API_BASE_URL=http://127.0.0.1:3001 corepack pnpm --filter @xrag/web exec vite --host 127.0.0.1 --port 4173",
      url: "http://127.0.0.1:4173",
      reuseExistingServer: true,
      timeout: 60_000
    }
  ]
});
