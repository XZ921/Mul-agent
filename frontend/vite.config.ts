import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    /**
     * 路由懒加载已经把首屏主包从约 1.46 MB 降到了约 632 kB。
     * 剩余体积主要来自多个页面共享的 Ant Design 运行时代码，
     * 因此这里将 warning 阈值调到贴近当前稳定产物的水平，避免持续出现误报噪音。
     */
    chunkSizeWarningLimit: 700,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:9093',
        changeOrigin: true,
      },
    },
  },
})
