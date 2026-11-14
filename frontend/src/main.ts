import { createApp } from 'vue'
import Antd from 'ant-design-vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

// 样式顺序很重要：先 reset.css（Ant Design 的样式重置），再项目自己的全局样式，
// 否则自定义样式会被组件库覆盖，出现"改了不生效"的困惑
import 'ant-design-vue/dist/reset.css'
import 'leaflet/dist/leaflet.css'
import '@/styles/global.css'

import App from './App.vue'
import router from './router'

// dayjs 的中文语言包：日期选择器、相对时间都依赖它
dayjs.locale('zh-cn')

const app = createApp(App)

// 全量注册 Ant Design Vue 组件。
//
// 为什么不全量按需引入？因为这是内部运营后台，包体积不是瓶颈，
// 而全量注册能让模板里直接用 <a-table> 等标签，代码更干净、心智负担更低。
// 如果将来要做公网 C 端页面，再换成 unplugin-vue-components 按需引入。
app.use(Antd)
app.use(router)

// 兜底：未捕获的组件异常打到控制台，方便排查白屏问题
app.config.errorHandler = (err, _instance, info) => {
  console.error('[sky-dispatch] 组件异常:', err, info)
}

app.mount('#app')
