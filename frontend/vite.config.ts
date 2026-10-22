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
    /**
     * 预览（`npm run preview`）也要代理 `/api` —— 否则预览版本调不通后端，
     * 表现为"页面打开了、所有数据都失败"。★ 它**不继承** server.proxy，必须单独写。
     * 用生产构建 + preview 排查"dev 模式卡顿"时，这一条是前提。
     */
    preview: {
      port: 5280,
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
