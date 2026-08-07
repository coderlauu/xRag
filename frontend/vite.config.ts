import { loadEnv } from 'vite'
// defineConfig 从 vitest/config 引才能带上 test 字段的类型；vitest 4 的 vitest/config
// 不再 re-export loadEnv，所以那个仍从 vite 引。
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 第三个参数传 '' 表示不只加载 VITE_ 前缀的变量——BACKEND_ORIGIN 只给开发服务器用，
  // 不该被打进前端产物。
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    server: {
      // 开发期同源代理。没有它，:5173 直连 :3001 是跨源请求，后端没配 CORS 会被浏览器
      // 拦掉，表现为"无法连接到服务器"——看起来像后端没启动，实际是同源策略。
      //
      // 代理让 VITE_API_BASE_URL 在本地保持为空（同源），生产构建时再按部署形态决定
      // 是继续同源反向代理还是显式指向后端域名。
      proxy: {
        '/api': {
          target: env.BACKEND_ORIGIN || 'http://localhost:3001',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      // 测试文件与被测组件同目录，不集中到 __tests__——改一个组件时它的测试就在旁边。
      include: ['src/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  }
})
