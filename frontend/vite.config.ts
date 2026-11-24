import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import type { ProxyOptions } from 'vite'

/**
 * Vite 配置。
 *
 * 两个关键点：
 *  1. `resolve.alias` 里的 `@` 指向 src，避免出现 `../../../` 这种难读的相对路径。
 *  2. `server.proxy` 把 /api 和 /ws 转发到 Java 后端 —— 这是**开发期避免跨域的标准做法**：
 *     浏览器只跟 http://localhost:5173 打交道，同源，不需要后端开 CORS。
 *     生产部署时通常用 Nginx 做同样的转发（见本文件末尾注释）。
 */

/**
 * 后端地址：默认 8080，可用环境变量 VITE_BACKEND_URL 覆盖。
 * 一旦后端没起（或起在了别的端口），代理会对每个接口返回 500，
 * 页面上只会看到笼统的"响应不是合法 JSON（HTTP 500）"，完全无法定位问题。
 */
const BACKEND_TARGET = process.env.VITE_BACKEND_URL ?? 'http://127.0.0.1:8080'

/**
 * 代理统一的错误兜底：后端连接被拒绝 / 超时时，不再抛出裸 500，
 * 而是返回与业务接口同构的 JSON 信封，前端 client.ts 能正常解析并弹出
 * "请先启动后端"的可操作提示，而不是让人摸不着头脑的 500。
 */
function friendlyProxy(target: string, ws = false): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    ws,
    configure(proxy) {
      // 通过公网隧道访问时，浏览器会带 Origin（如 https://xxx.trycloudflare.com）。
      // 本地后端只放行了 localhost 的 CORS，会直接 403；
      // 这里把 Origin/Referer 去掉，让请求以同源身份到达后端。
      // 生产部署由 Spring Boot 直接托管前端，不存在这个代理层。
      proxy.on('proxyReq', (proxyReq) => {
        proxyReq.removeHeader('origin')
        proxyReq.removeHeader('referer')
      })
      proxy.on('proxyReqWs', (proxyReq) => {
        proxyReq.removeHeader('origin')
        proxyReq.removeHeader('referer')
      })
      proxy.on('error', (err, _req, res) => {
        // WebSocket 升级阶段没有可写的 HTTP 响应，只在控制台留痕
        if (!res || typeof (res as { writeHead?: unknown }).writeHead !== 'function') {
          console.warn(`[vite-proxy] ${ws ? 'WS' : 'HTTP'} 代理无法连接后端 ${target}：${err.message}`)
          return
        }
        const payload = JSON.stringify({
          code: 50301,
          message:
            `无法连接 Java 后端（${target}）。请先在 backend 目录执行 mvn spring-boot:run` +
            '（默认 8080 端口），或直接运行项目根目录的 start-dev.ps1 一键启动前后端。',
          data: null,
        })
        ;(res as import('node:http').ServerResponse).writeHead(503, {
          'Content-Type': 'application/json; charset=utf-8',
        })
        ;(res as import('node:http').ServerResponse).end(payload)
      })
    },
  }
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    // 允许免费临时隧道域名访问（仅开发调试用）
    allowedHosts: ['.trycloudflare.com', '.lhr.life'],
    proxy: {
      // REST 接口转发。后端不可达时由 friendlyProxy 返回明确的 JSON 提示
      '/api': friendlyProxy(BACKEND_TARGET),
      '/actuator': friendlyProxy(BACKEND_TARGET),
      // WebSocket 必须声明 ws: true，否则握手会失败
      '/ws': friendlyProxy(BACKEND_TARGET, true),
    },
  },
  build: {
    outDir: 'dist',
    // 关掉 sourcemap 让产物小一些；排查线上问题时可临时打开
    sourcemap: false,
    /*
     * antd 单独分包后仍有约 1.5MB（gzip 467KB），会触发 Vite 的体积告警。
     * 这是**有意接受的取舍**：内部运营后台首屏体积不敏感，换来的是模板里可以直接写
     * <a-table> 等标签（全量注册），代码更干净、对初学者更友好。
     * 真要优化：装 unplugin-vue-components 改成按需引入，或对次屏页面继续拆包。
     * 这里把阈值提到 1600KB，让构建输出保持"零告警"，避免真正的告警被淹没。
     */
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      output: {
        // 手工分包：echarts 和 leaflet 体积大，单独成 chunk 有利于浏览器缓存
        manualChunks: {
          echarts: ['echarts'],
          leaflet: ['leaflet'],
          antd: ['ant-design-vue', '@ant-design/icons-vue'],
        },
      },
    },
  },
})

/*
 * 生产部署（Nginx）示例：
 *
 *   server {
 *     listen 80;
 *     root /usr/share/nginx/html;          # 指向 vite build 产出的 dist 目录
 *
 *     location / {
 *       try_files $uri $uri/ /index.html;  # 前端路由用 history 模式，必须回落到 index.html
 *     }
 *     location /api/ {
 *       proxy_pass http://sky-dispatch-backend:8080;
 *     }
 *     location /ws/ {
 *       proxy_pass http://sky-dispatch-backend:8080;
 *       proxy_http_version 1.1;
 *       proxy_set_header Upgrade $http_upgrade;      # WebSocket 升级必需
 *       proxy_set_header Connection "upgrade";
 *     }
 *   }
 */
