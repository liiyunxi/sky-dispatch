import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { isAuthenticated } from '@/api/token'

/**
 * 路由表。
 *
 * 用 `createWebHistory`（HTML5 history 模式）而不是 hash 模式，URL 更干净。
 * 代价是**部署时必须配置 fallback**：直接访问 /orders 时服务器要回落到 index.html，
 * 否则刷新页面会 404。Nginx 配置示例见 vite.config.ts 末尾注释。
 *
 * 所有页面都用动态 import 懒加载：首屏只加载看板，其它页面点了才下载。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    // public：不套侧栏/顶栏，也不要求登录态
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '实时看板' },
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('@/views/OrdersView.vue'),
    meta: { title: '订单管理' },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('@/views/TasksView.vue'),
    meta: { title: '任务管理' },
  },
  {
    path: '/drones',
    name: 'drones',
    component: () => import('@/views/DronesView.vue'),
    meta: { title: '机队管理' },
  },
  {
    path: '/airspace',
    name: 'airspace',
    component: () => import('@/views/AirspaceView.vue'),
    meta: { title: '空域管理' },
  },
  {
    path: '/infrastructure',
    name: 'infrastructure',
    component: () => import('@/views/InfrastructureView.vue'),
    meta: { title: '基础设施' },
  },
  // 兜底：未知路径回看板（比显示 404 页面对运营更友好）
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 路由守卫：未登录只能去登录页；已登录访问登录页则回看板。
 *
 * 这里只做“有没有 token 且未过期”的判断；token 是否被服务端认可由接口 401 兜底，
 * 前端不解析 token 内容，避免把安全判断建立在客户端。
 */
router.beforeEach((to) => {
  const isPublic = Boolean(to.meta.public)
  if (isPublic) {
    if (to.path === '/login' && isAuthenticated()) {
      return { path: '/' }
    }
    return true
  }
  if (!isAuthenticated()) {
    return {
      path: '/login',
      query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : undefined,
    }
  }
  return true
})

// 顺手把页面标题同步到浏览器标签页，方便运营开多个标签时区分
router.afterEach((to) => {
  const title = (to.meta?.title as string) || ''
  document.title = title ? `${title} · sky-dispatch` : 'sky-dispatch'
})

export default router
