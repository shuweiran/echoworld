import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const stripProxyOrigin = (proxy: any) => {
  proxy.on('proxyReq', (proxyReq: any) => proxyReq.removeHeader('origin'))
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        configure: stripProxyOrigin,
      },
      // 2D simulation page is served by the Java backend (classpath:/static/simulation.html)
      '/simulation.html': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        configure: stripProxyOrigin,
      },
    },
  },
  // `npm run preview` 也要把一般模式 2D 的真实 API 请求转给 8000；
  // 否则预览端口会把 POST /api/* 当成静态路径拒绝，演示会退化为假状态。
  preview: {
    proxy: {
      '/api': { target: 'http://localhost:8000', changeOrigin: true, configure: stripProxyOrigin },
      '/simulation.html': { target: 'http://localhost:8000', changeOrigin: true, configure: stripProxyOrigin },
    },
  },
})
