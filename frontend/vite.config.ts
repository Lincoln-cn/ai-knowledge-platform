import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    base: './',
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5280,
      /* 后端地址走环境变量，不写死内网 IP —— 原项目把 172.16.20.130 硬编码在 vite.config 里 */
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://127.0.0.1:18088',
          changeOrigin: true
        }
      }
    },
    build: {
      chunkSizeWarningLimit: 1200
    }
  }
})
