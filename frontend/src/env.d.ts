/// <reference types="vite/client" />

// 让 TypeScript 认识 .vue 单文件组件（否则 import App from './App.vue' 会报错）
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
