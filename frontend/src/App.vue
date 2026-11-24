<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, theme as antTheme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import {
  ApiOutlined,
  AppstoreOutlined,
  CarryOutOutlined,
  DashboardOutlined,
  GlobalOutlined,
  LogoutOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ProfileOutlined,
  ReloadOutlined,
  StepForwardOutlined,
} from '@ant-design/icons-vue'
import { useTelemetry } from '@/store/telemetry'
import { useAuth } from '@/store/auth'
import { isAuthenticated } from '@/api/token'
import TechScrollbar from '@/components/TechScrollbar.vue'
import { RUN_STATUS_TEXT, formatDuration } from '@/utils/format'

/**
 * 应用外壳：左侧导航 + 顶部状态条 + 内容区。
 *
 * 顶部状态条承担三件运营最关心的事：
 *  1. 实时通道是否健康（WebSocket 已连接 / 重连中 / 已降级为轮询）；
 *  2. 仿真跑在哪一拍、倍速多少；
 *  3. 算法服务是否在线 —— 它挂了派单会直接 503，必须一眼可见。
 *
 * 应用启动时建立 WebSocket 连接并拉一次全量快照，卸载时主动断开，
 * 避免开发热更新时残留旧连接（表现为"服务端一直有幽灵客户端"）。
 */

const router = useRouter()
const route = useRoute()
const telemetry = useTelemetry()
const auth = useAuth()
const authUsername = auth.username
/** 登录页等 public 路由不套侧栏/顶栏，也不建立实时连接 */
const isPublicRoute = computed(() => Boolean(route.meta.public))

/**
 * 深色科技风主题（Ant Design 的 Design Token 换肤）。
 *
 * <p>只调配色、圆角与边框，<b>不改任何组件结构或交互</b> —— 换肤不该改变业务行为。
 * 底色取深海军蓝而不是纯黑：纯黑下卡片与背景没有层次，表格长时间看很累；
 * 青色（#22d3ee）只作为描边与辉光点缀，写在 global.css 里，不抢主色。
 */
const techTheme = {
  algorithm: antTheme.darkAlgorithm,
  token: {
    // 主色保持品牌蓝，保证白字对比度；青色仅在按钮辉光/选中条上做点缀
    colorPrimary: '#1677ff',
    colorInfo: '#1677ff',
    /* 链接与页签选中态需要更亮的青色：主色 #1677ff 直接当文字用在深底上只有 3:1，读不清 */
    colorLink: '#4fc3ff',
    colorSuccess: '#2fd6a5',
    colorWarning: '#f0a93b',
    colorError: '#ff6060',
    // 底色：深海军蓝，卡片比背景亮一级，形成"仪表盘"的层次感
    colorBgBase: '#050d18',
    colorBgContainer: '#0c1b2c',
    colorBgElevated: '#102338',
    colorBgLayout: '#050d18',
    colorTextBase: '#d8e8fb',
    colorBorder: '#1d3a55',
    colorBorderSecondary: '#152c44',
    borderRadius: 6,
    // 表格/统计数字等密集信息用等宽字体，读数时不抖动
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif",
  },
  components: {
    Layout: {
      bodyBg: 'transparent',
      headerBg: 'transparent',
      siderBg: 'transparent',
    },
    Card: { colorBgContainer: 'rgba(12, 27, 44, 0.88)' },
    Table: { headerBg: '#0b1a2c', rowHoverBg: 'rgba(34, 211, 238, 0.07)' },
    Menu: {
      darkItemBg: 'transparent',
      darkSubMenuItemBg: 'transparent',
      darkItemSelectedBg: 'rgba(34, 211, 238, 0.14)',
      darkItemSelectedColor: '#6fe8ff',
      darkItemHoverBg: 'rgba(34, 211, 238, 0.08)',
    },
    Tabs: {
      itemSelectedColor: '#4fc3ff',
      itemHoverColor: '#7ff0ff',
      inkBarColor: '#22d3ee',
    },
  },
}

const {
  kpi,
  wsState,
  usingPolling,
  algorithmAvailable,
  algorithmEnabled,
  runStatus,
  simTimeS,
  tickCount,
  runtimeState,
  wind,
} = telemetry

/**
 * 算法服务徽标三态：
 *  - 启用且在线：青色「在线」
 *  - 启用但连不上：红色「离线」（派单会失败，需要启动 algorithm-service）
 *  - 主动关闭（dev 默认）：金色「离线降级」，走直线航路，派单与仿真仍可正常进行
 */
const algorithmBadge = computed<{ color: string; text: string; tip: string }>(() => {
  if (algorithmEnabled.value === false) {
    return {
      color: 'gold',
      text: '离线降级',
      tip: '后端未启用 Python 算法服务，路径规划使用直线航路（不避让禁飞区）；派单与仿真可正常进行。如需 A* 规划请启动 algorithm-service 并开启 sky.algorithm.enabled',
    }
  }
  if (algorithmEnabled.value === null) {
    return { color: 'default', text: '算法服务未知', tip: '尚未探测算法服务状态' }
  }
  return algorithmAvailable.value
    ? { color: 'cyan', text: '算法服务在线', tip: 'Python 算法服务在线（路径规划 / 冲突检测可用）' }
    : { color: 'red', text: '算法服务离线', tip: '算法服务不可用：派单会失败，请启动 algorithm-service（默认 8000 端口）' }
})

const ticking = ref(false)
const switchingRun = ref(false)

/** 引擎是否在推进（决定按钮显示「暂停仿真」还是「继续仿真」） */
const engineActive = computed(() => runtimeState.value?.engineActive ?? false)
/** 自动派单开关（本地镜像，变更后立刻写回后端） */
const autoDispatchOn = ref(true)

// 后端状态是唯一事实来源：每次拉到状态就同步本地开关，避免界面与后端不一致
watch(
  () => runtimeState.value?.autoDispatchEnabled,
  (enabled) => {
    if (enabled !== undefined) autoDispatchOn.value = enabled
  },
  { immediate: true },
)

/** 菜单选中项跟随路由，刷新页面也能高亮正确 */
const selectedKeys = computed(() => [route.path])

const menus = [
  { key: '/', title: '实时看板', icon: DashboardOutlined },
  { key: '/orders', title: '订单管理', icon: ProfileOutlined },
  { key: '/tasks', title: '任务管理', icon: CarryOutOutlined },
  { key: '/drones', title: '机队管理', icon: AppstoreOutlined },
  { key: '/airspace', title: '空域管理', icon: GlobalOutlined },
]

/** 实时通道状态 → 徽标颜色与文案 */
const channelStatus = computed(() => {
  if (wsState.value === 'open') return { color: 'success' as const, text: 'WebSocket 已连接' }
  if (usingPolling.value) return { color: 'warning' as const, text: '已降级为 REST 轮询（3 秒）' }
  if (wsState.value === 'connecting') return { color: 'processing' as const, text: '正在连接…' }
  return { color: 'error' as const, text: '未连接' }
})

const runStatusText = computed(() => RUN_STATUS_TEXT[runStatus.value] ?? runStatus.value)

/** 16 方位中文罗盘，用于把风的来向翻译成"西南风" */
const COMPASS_16 = [
  '北', '北东北', '东北', '东东北', '东', '东东南', '东南', '南东南',
  '南', '南西南', '西南', '西西南', '西', '西西北', '西北', '北西北',
]

/**
 * 风场读数：风速 + 风向来向。
 *
 * 只做展示，真正影响飞行的是后端的 WindField（地速/能耗/机头偏航）。
 * 后端未返回风场（老版本或已关闭）时整块隐藏，不留空占位。
 */
const windBadge = computed(() => {
  const w = wind.value
  if (!w || !w.enabled) return null
  const dir = ((Math.round(w.directionDeg / 22.5) % 16) + 16) % 16
  const speed = Number(w.speedMps ?? 0)
  return {
    text: `${COMPASS_16[dir]}风 ${speed.toFixed(1)}m/s`,
    tip: `风向 ${Number(w.directionDeg ?? 0).toFixed(0)}°（来向），逆风航段地速下降、能耗上升，侧风时机头会偏航`,
    level: speed >= 8 ? 'red' : speed >= 5 ? 'gold' : 'cyan',
  }
})

/** 有运行记录（RUNNING 或 PAUSED）时手动步进才有意义 */
const canStep = computed(() => runtimeState.value?.runId != null)

function onMenuClick({ key }: { key: string | number }) {
  void router.push(String(key))
}

async function handleManualTick() {
  ticking.value = true
  try {
    const result = await telemetry.manualTick()
    message.success(
      `第 ${result.stepIndex} 拍：移动 ${result.movedDrones} 架，完成 ${result.completedTasks} 单，新增冲突 ${result.newConflicts} 条`,
    )
    await telemetry.loadRuntimeState()
  } catch {
    // client.ts 已经弹过错误提示，这里只需要收尾
  } finally {
    ticking.value = false
  }
}

/**
 * 暂停 / 继续。
 *
 * 这两个动作才是真正的"仿真暂停"：暂停后定时推进真的停下，
 * 但「步进一拍」仍然可用，可以一拍一拍地观察状态机怎么走。
 */
async function handleToggleEngine() {
  switchingRun.value = true
  try {
    if (engineActive.value) {
      await telemetry.pauseRun()
      message.success('仿真已暂停（仍可用「步进一拍」逐拍观察）')
    } else {
      await telemetry.resumeRun()
      message.success('仿真已继续，从暂停处接着跑')
    }
  } catch {
    /* 已提示 */
  } finally {
    switchingRun.value = false
  }
}

async function handleAutoDispatchChange(checked: boolean | string | number) {
  const enabled = Boolean(checked)
  switchingRun.value = true
  try {
    await telemetry.setAutoDispatch(enabled)
    message.success(
      enabled
        ? '已开启自动派单：引擎每隔若干拍自动扫单派发'
        : '已关闭自动派单：订单会排队等待，由你在订单页或看板上手动派单',
    )
  } catch {
    // 失败时回滚开关，保持与后端一致
    autoDispatchOn.value = !enabled
  } finally {
    switchingRun.value = false
  }
}

/** 新建一轮运行：计时与拍数从 0 开始（想延续当前演练请用「继续仿真」） */
async function handleStartRun() {
  switchingRun.value = true
  try {
    const run = await telemetry.startRun({ name: `看板新建 ${new Date().toLocaleTimeString()}` })
    message.success(`已新建仿真运行 #${run.id}（${run.tickMs}ms / 拍，${run.speedFactor} 倍速）`)
    await Promise.all([telemetry.refreshSnapshot(), telemetry.loadRoutes()])
  } catch {
    /* 已提示 */
  } finally {
    switchingRun.value = false
  }
}

async function handleRefresh() {
  await Promise.all([
    telemetry.refreshSnapshot(),
    telemetry.loadRoutes(),
    telemetry.refreshRun(),
    telemetry.loadRuntimeState(),
  ])
  message.success('已刷新')
}

let telemetryStarted = false
let stateTimer: number | null = null

/** 只有进入受保护页面后才建立实时连接，登录页不产生任何未授权请求 */
async function startTelemetry(): Promise<void> {
  // 未登录（或 token 已过期）时绝不发请求：登录页不该产生 401/WS 握手错误
  if (telemetryStarted || !isAuthenticated()) return
  telemetryStarted = true
  // 先建连接：连上后服务端会主动推 SNAPSHOT，比 REST 拉取更"一条路"
  telemetry.connect()
  // 并行拉一次兜底数据，即使 WebSocket 被拦也能立刻看到内容
  try {
    await Promise.all([
      telemetry.refreshSnapshot(),
      telemetry.refreshRun(),
      telemetry.loadRuntimeState(),
      telemetry.probeAlgorithm(),
    ])
  } catch {
    // 401/网络异常由 API 客户端统一处理（清理 token 并回登录页），这里避免未处理拒绝
  }
  // 运行时状态也可能被其它页面改变（比如订单页的批量派单），定期同步一次
  if (stateTimer === null) {
    stateTimer = window.setInterval(() => void telemetry.loadRuntimeState(), 10_000)
  }
}

function stopTelemetry(): void {
  telemetryStarted = false
  if (stateTimer !== null) {
    window.clearInterval(stateTimer)
    stateTimer = null
  }
  telemetry.disconnect()
}

/** 退出登录：断开实时连接、清 token、回登录页 */
function handleLogout(): void {
  stopTelemetry()
  auth.logout()
  void router.replace('/login')
}

// 登录页 ↔ 主应用之间切换时自动建立/断开实时连接
watch(
  isPublicRoute,
  (isPublic) => {
    if (isPublic) stopTelemetry()
    else void startTelemetry()
  },
  { immediate: true },
)

onUnmounted(() => {
  stopTelemetry()
})
</script>

<template>
  <!-- Ant Design Vue 的中文语言包：日期选择器、分页文案等都会跟着变中文 -->
  <a-config-provider :locale="zhCN" :theme="techTheme">
    <!-- public 路由（登录页）不套侧栏/顶栏 -->
    <router-view v-if="isPublicRoute" />

    <a-layout v-else class="app-layout">
      <!-- ------------------------------ 左侧导航 ------------------------------ -->
      <a-layout-sider :width="208" theme="dark" class="app-sider">
        <!-- 纯装饰层：纵向自检扫描 + 右缘刻度导轨（不参与布局与交互） -->
        <span class="sider-sweep" aria-hidden="true" />
        <span class="sider-ruler" aria-hidden="true" />

        <div class="brand">
          <div class="brand-logo">SD</div>
          <div class="brand-text">
            <div class="brand-title">sky-dispatch</div>
            <div class="brand-sub">低空配送调度仿真</div>
          </div>
        </div>
        <div class="nav">
          <span class="nav-spine" aria-hidden="true" />
          <a-menu
            :selected-keys="selectedKeys"
            theme="dark"
            mode="inline"
            @click="onMenuClick"
          >
            <a-menu-item v-for="item in menus" :key="item.key">
              <!-- 动效层：悬停扫描光带 / 选中能量条 / 电路节点 -->
              <span class="nav-glow" aria-hidden="true" />
              <span class="nav-node" aria-hidden="true" />
              <template #icon><component :is="item.icon" /></template>
              {{ item.title }}
            </a-menu-item>
          </a-menu>
        </div>
        <!-- 底部信号频谱：纯装饰，让侧栏有"持续运行"的读数感 -->
        <div class="sider-signal" aria-hidden="true">
          <span
            v-for="(height, index) in [34, 58, 42, 76, 48, 88, 62, 40, 70, 52, 82, 46, 64, 36]"
            :key="index"
            class="signal-bar"
            :style="{ height: `${height}%`, animationDelay: `-${index * 0.11}s` }"
          />
        </div>
      </a-layout-sider>

      <a-layout>
        <!-- ------------------------------ 顶部状态条 ------------------------------ -->
        <a-layout-header class="app-header">
          <!-- HUD 四角装饰：纯视觉，不参与布局与交互 -->
          <span class="hud-corner hud-corner--lt" aria-hidden="true" />
          <span class="hud-corner hud-corner--rb" aria-hidden="true" />

          <div class="header-left">
            <a-badge class="hud-status" :status="channelStatus.color" :text="channelStatus.text" />
            <a-divider class="hud-divider" type="vertical" />
            <span class="hud-label">在线客户端</span>
            <a-tag class="hud-chip hud-chip--info">{{ kpi?.onlineClients ?? 0 }}</a-tag>

            <!-- 风场读数：逆风会拖慢地速、抬高能耗，这里让"风"在看板上可见 -->
            <template v-if="windBadge">
              <a-divider class="hud-divider" type="vertical" />
              <a-tooltip :title="windBadge.tip">
                <a-tag class="hud-chip" :class="`hud-chip--${windBadge.level}`">
                  {{ windBadge.text }}
                </a-tag>
              </a-tooltip>
            </template>
          </div>

          <div class="header-right">
            <a-tag
              class="hud-chip"
              :class="runStatus === 'RUNNING' ? 'hud-chip--run' : 'hud-chip--idle'"
            >
              仿真 {{ runStatusText }}
            </a-tag>
            <a-tag class="hud-chip hud-chip--data">第 {{ tickCount }} 拍</a-tag>
            <a-tag class="hud-chip hud-chip--data">仿真时长 {{ formatDuration(simTimeS) }}</a-tag>

            <!-- 算法服务状态：在线 / 异常离线 / 离线降级（dev 默认，仍可派单） -->
            <a-tooltip :title="algorithmBadge.tip">
              <a-tag class="hud-chip" :class="`hud-chip--${algorithmBadge.color}`">
                <template #icon><ApiOutlined /></template>
                {{ algorithmBadge.text }}
              </a-tag>
            </a-tooltip>

            <a-divider class="hud-divider" type="vertical" />

            <!-- 自动派单开关：关掉后订单只排队，派单时机完全由人控制 -->
            <a-tooltip
              title="关闭后引擎不再自动扫单，订单会排队等待；这样「立即批量派单」才是你能看见效果的动作"
            >
              <span class="switch-group">
                <a-switch
                  v-model:checked="autoDispatchOn"
                  size="small"
                  :loading="switchingRun"
                  @change="handleAutoDispatchChange"
                />
                <span class="switch-label">自动派单</span>
              </span>
            </a-tooltip>

            <a-space wrap>
              <a-button
                size="small"
                :loading="ticking"
                :disabled="!canStep"
                @click="handleManualTick"
              >
                <template #icon><StepForwardOutlined /></template>
                步进一拍
              </a-button>

              <!-- 暂停 / 继续：暂停后定时推进真的停下，但手动步进仍可用 -->
              <a-button
                size="small"
                :type="engineActive ? 'default' : 'primary'"
                :loading="switchingRun"
                @click="handleToggleEngine"
              >
                <template #icon>
                  <PauseCircleOutlined v-if="engineActive" />
                  <PlayCircleOutlined v-else />
                </template>
                {{ engineActive ? '暂停仿真' : '继续仿真' }}
              </a-button>

              <a-button size="small" :loading="switchingRun" @click="handleStartRun">
                新建运行
              </a-button>

              <a-button size="small" type="text" @click="handleRefresh">
                <template #icon><ReloadOutlined /></template>
              </a-button>
            </a-space>

            <a-tooltip :title="`退出登录（${authUsername || 'admin'}）`">
              <a-button size="small" type="text" class="logout-btn" @click="handleLogout">
                <template #icon><LogoutOutlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </a-layout-header>

        <!-- ------------------------------ 内容区 ------------------------------ -->
        <a-layout-content class="app-content">
          <router-view />
        </a-layout-content>
      </a-layout>

      <!-- 自制 HUD 滚动条：原生滚动条已在 global.css 中隐藏，这里接管所有滚动容器的视觉 -->
      <TechScrollbar />
    </a-layout>
  </a-config-provider>
</template>

<style scoped>
/* =============================================================================
   深色科技风外壳
   只做视觉：深海军蓝底 + 青色描边/辉光 + 细网格，结构、尺寸、交互全部保持原样。
   ============================================================================= */

.app-layout {
  height: 100%;
  /* 两团冷光投影在网格底上：左上品牌蓝、右上青色，让深色背景有"能量场"的层次 */
  background:
    radial-gradient(1200px 620px at 8% -14%, rgba(22, 119, 255, 0.2), transparent 62%),
    radial-gradient(1000px 560px at 104% -8%, rgba(56, 232, 255, 0.12), transparent 60%),
    #040c16;
}

.app-sider {
  /* 侧栏固定，内容区独立滚动：长表格滚动时导航不跟着跑 */
  overflow: auto;
  position: relative;
  background:
    radial-gradient(180px 120px at 14% 0%, rgba(56, 232, 255, 0.16), transparent 70%),
    linear-gradient(180deg, #06182b 0%, #040e1b 52%, #030b16 100%);
  border-right: 1px solid rgba(56, 232, 255, 0.2);
  box-shadow:
    inset -1px 0 0 rgba(56, 232, 255, 0.08),
    4px 0 24px rgba(0, 0, 0, 0.35);
}

/* 让品牌区、导航、底部频谱能从上下依次排布 */
.app-sider :deep(.ant-layout-sider-children) {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 侧栏细网格：只做质感，不参与交互 */
.app-sider::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(56, 232, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 232, 255, 0.05) 1px, transparent 1px);
  background-size: 26px 26px;
  mask-image: linear-gradient(180deg, #000 0%, transparent 85%);
}

/* 侧栏 HUD 四角括号：给整块导航一个"仪表边框"的定位标记 */
.app-sider::after {
  content: '';
  position: absolute;
  inset: 5px;
  z-index: 3;
  pointer-events: none;
  --c: rgba(56, 232, 255, 0.55);
  background:
    linear-gradient(var(--c), var(--c)) left top / 12px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) left top / 1px 12px no-repeat,
    linear-gradient(var(--c), var(--c)) right top / 12px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) right top / 1px 12px no-repeat,
    linear-gradient(var(--c), var(--c)) left bottom / 12px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) left bottom / 1px 12px no-repeat,
    linear-gradient(var(--c), var(--c)) right bottom / 12px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) right bottom / 1px 12px no-repeat;
}

/* 纵向自检扫描：一条淡青光带自上而下刷新，像仪表开机自检 */
.sider-sweep {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}

.sider-sweep::before {
  content: '';
  position: absolute;
  left: -10%;
  right: -10%;
  top: -140px;
  height: 140px;
  background: linear-gradient(
    180deg,
    transparent,
    rgba(56, 232, 255, 0.1) 55%,
    rgba(56, 232, 255, 0.02)
  );
  animation: sider-sweep 9s linear infinite;
}

@keyframes sider-sweep {
  0% {
    top: -140px;
    opacity: 0;
  }
  8% {
    opacity: 1;
  }
  92% {
    opacity: 1;
  }
  100% {
    top: 100%;
    opacity: 0;
  }
}

/* 右缘刻度尺 + 巡游光点：侧栏边缘看起来像一条仪器导轨 */
.sider-ruler {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 7px;
  z-index: 2;
  pointer-events: none;
  background-image: repeating-linear-gradient(
    180deg,
    rgba(56, 232, 255, 0.42) 0 1px,
    transparent 1px 9px
  );
  opacity: 0.45;
}

.sider-ruler::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: -12%;
  height: 42px;
  background: linear-gradient(180deg, transparent, rgba(120, 240, 255, 0.9), transparent);
  animation: ruler-scan 6.5s linear infinite;
}

@keyframes ruler-scan {
  0% {
    top: -12%;
    opacity: 0;
  }
  10% {
    opacity: 1;
  }
  90% {
    opacity: 1;
  }
  100% {
    top: 100%;
    opacity: 0;
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 64px;
  padding: 0 16px;
  color: #e8f6ff;
  position: relative;
  z-index: 1;
  border-bottom: 1px solid rgba(56, 232, 255, 0.18);
}

/* 绕铭牌旋转的刻环：两段不等长弧线，像雷达/陀螺的转子 */
.brand::before {
  content: '';
  position: absolute;
  left: 10px;
  top: 10px;
  width: 44px;
  height: 44px;
  pointer-events: none;
  background: conic-gradient(
    from 0deg,
    rgba(159, 240, 255, 0.95),
    transparent 26%,
    transparent 52%,
    rgba(56, 232, 255, 0.6) 72%,
    transparent 84%
  );
  -webkit-mask: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 3px));
  mask: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 3px));
  filter: drop-shadow(0 0 5px rgba(56, 232, 255, 0.75));
  animation: brand-orbit 4.5s linear infinite;
}

@keyframes brand-orbit {
  to {
    transform: rotate(360deg);
  }
}

/* 品牌区底部扫描线：与顶栏同款，慢速掠过一次 */
.brand::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -1px;
  width: 30%;
  height: 2px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.9), transparent);
  animation: brand-line 7s linear infinite;
}

@keyframes brand-line {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(340%);
  }
}

.brand-logo {
  position: relative;
  z-index: 1;
  width: 32px;
  height: 32px;
  overflow: hidden;
  /* 切角 + 青色辉光：一小块"仪表盘铭牌"的观感 */
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  background: linear-gradient(135deg, #1677ff, #38e8ff);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 13px;
  letter-spacing: 0.5px;
  color: #03111d;
  box-shadow: 0 0 18px rgba(56, 232, 255, 0.55);
}

/* 铭牌上的扫光：每隔几秒掠过一次，静止时只是一块渐变角标 */
.brand-logo::after {
  content: '';
  position: absolute;
  inset: -60% -20%;
  background: linear-gradient(115deg, transparent 42%, rgba(255, 255, 255, 0.85) 50%, transparent 58%);
  transform: translateX(-120%);
  animation: brand-sweep 5.5s ease-in-out infinite;
}

@keyframes brand-sweep {
  0%,
  55% {
    transform: translateX(-120%);
  }
  85%,
  100% {
    transform: translateX(120%);
  }
}

.brand-title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0.6px;
  color: #eafdff;
  text-shadow: 0 0 12px rgba(56, 232, 255, 0.25);
}

.brand-sub {
  font-size: 11px;
  color: rgba(150, 199, 232, 0.66);
  line-height: 1.4;
  letter-spacing: 0.3px;
}

/* 文字压在旋转刻环之上，避免被光弧盖住 */
.brand-text {
  position: relative;
  z-index: 1;
  min-width: 0;
}

/* 导航容器：占满剩余高度，把底部频谱压到侧栏下沿 */
.nav {
  position: relative;
  z-index: 1;
  flex: 1 1 auto;
  min-height: 0;
  padding-top: 8px;
}

/* -----------------------------------------------------------------------------
   贯穿式电路脊线
   一条 1px 光轨沿菜单左缘贯通到侧栏底部，每个菜单项在轨道上有一个菱形节点，
   一段亮光周期性从上往下流过 —— 菜单像插在一条电路总线上。
   ----------------------------------------------------------------------------- */
.nav-spine {
  position: absolute;
  left: 8px;
  top: 8px;
  bottom: 8px;
  width: 1px;
  z-index: 3;
  pointer-events: none;
  background: linear-gradient(
    180deg,
    transparent,
    rgba(56, 232, 255, 0.55) 5%,
    rgba(56, 232, 255, 0.18) 55%,
    rgba(56, 232, 255, 0.5) 95%,
    transparent
  );
}

/* 沿脊线流动的电流脉冲 */
.nav-spine::after {
  content: '';
  position: absolute;
  left: -1px;
  width: 3px;
  height: 36px;
  top: -36px;
  background: linear-gradient(180deg, transparent, #9ff0ff 45%, rgba(56, 232, 255, 0.2));
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.9);
  animation: spine-run 4.6s linear infinite;
}

@keyframes spine-run {
  0% {
    top: -36px;
    opacity: 0;
  }
  10% {
    opacity: 1;
  }
  90% {
    opacity: 1;
  }
  100% {
    top: 100%;
    opacity: 0;
  }
}

/* 菜单项左缘的电路节点（菱形），选中/悬停时点亮 */
.app-sider :deep(.nav-node) {
  position: absolute;
  left: 1px;
  top: 50%;
  width: 5px;
  height: 5px;
  margin-top: -2.5px;
  pointer-events: none;
  background: #082033;
  border: 1px solid rgba(56, 232, 255, 0.55);
  transform: rotate(45deg);
  box-shadow: 0 0 6px rgba(56, 232, 255, 0.35);
  transition: background-color 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease;
}

.app-sider :deep(.ant-menu-item:hover .nav-node) {
  border-color: #9ff0ff;
  box-shadow: 0 0 9px rgba(56, 232, 255, 0.85);
}

.app-sider :deep(.ant-menu-item-selected .nav-node) {
  background: #9ff0ff;
  border-color: #9ff0ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
}

.app-sider :deep(.ant-menu) {
  background: transparent;
  border-inline-end: none !important;
}

/* 菜单项：切角轮廓 + 内置动效层，悬停/选中都有光效反馈 */
.app-sider :deep(.ant-menu-item) {
  position: relative;
  height: 42px;
  line-height: 42px;
  margin: 4px 8px;
  width: calc(100% - 16px);
  border-radius: 0;
  color: rgba(196, 222, 245, 0.78);
  /* 右上 / 左下切角，和按钮、读数条同一套轮廓 */
  clip-path: polygon(
    0 0,
    calc(100% - 9px) 0,
    100% 9px,
    100% 100%,
    9px 100%,
    0 calc(100% - 9px)
  );
  transition: color 0.22s ease, background-color 0.22s ease, box-shadow 0.22s ease;
}

/* 悬停：底色渐亮 + 1px 内描边 */
.app-sider :deep(.ant-menu-item:not(.ant-menu-item-selected):hover) {
  background: linear-gradient(90deg, rgba(56, 232, 255, 0.14), rgba(56, 232, 255, 0.03)) !important;
  box-shadow: inset 0 0 0 1px rgba(56, 232, 255, 0.22);
  color: #d9fbff;
}

/* 选中：斜向底光 + 内辉光，左缘能量条由 .nav-glow::after 绘制 */
.app-sider :deep(.ant-menu-item-selected) {
  color: #8ff4ff;
  background:
    linear-gradient(90deg, rgba(56, 232, 255, 0.24) 0%, rgba(56, 232, 255, 0.06) 58%, transparent 100%) !important;
  box-shadow:
    inset 0 0 0 1px rgba(56, 232, 255, 0.3),
    inset 0 0 24px rgba(56, 232, 255, 0.12);
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.55);
}

/* 动效层：绝对定位铺满菜单项，不参与布局 */
.app-sider :deep(.nav-glow) {
  position: absolute;
  inset: 0;
  z-index: 2;
  overflow: hidden;
  pointer-events: none;
}

/* 悬停时一条斜向光带反复扫过（和按钮同款语言） */
.app-sider :deep(.nav-glow)::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 55%;
  background: linear-gradient(100deg, transparent, rgba(159, 240, 255, 0.18), transparent);
  transform: translateX(-160%);
}

.app-sider :deep(.ant-menu-item:hover .nav-glow)::before {
  animation: nav-scan 1.4s linear infinite;
}

@keyframes nav-scan {
  from {
    transform: translateX(-160%);
  }
  to {
    transform: translateX(260%);
  }
}

/* 选中项左缘能量条：呼吸辉光 */
.app-sider :deep(.ant-menu-item-selected .nav-glow)::after {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  bottom: 7px;
  width: 2px;
  background: linear-gradient(180deg, transparent, #9ff0ff 30%, #38e8ff 70%, transparent);
  box-shadow: 0 0 12px rgba(56, 232, 255, 0.95);
  animation: nav-led 1.8s ease-in-out infinite;
}

@keyframes nav-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

/* 图标：悬停点亮上浮，选中常亮并带青光 */
.app-sider :deep(.ant-menu-item .anticon) {
  transition: color 0.22s ease, filter 0.22s ease, transform 0.22s ease;
}

.app-sider :deep(.ant-menu-item:hover .anticon) {
  color: #7ff0ff;
  filter: drop-shadow(0 0 6px rgba(56, 232, 255, 0.85));
  transform: translateY(-1px);
}

.app-sider :deep(.ant-menu-item-selected .anticon) {
  color: #9ff0ff;
  filter: drop-shadow(0 0 8px rgba(56, 232, 255, 0.95));
}

/* 底部信号频谱：纯装饰，让侧栏看起来在持续采样 */
.sider-signal {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 44px;
  margin: 8px 16px 14px;
  padding-bottom: 5px;
  border-bottom: 1px solid rgba(56, 232, 255, 0.3);
}

.signal-bar {
  flex: 1 1 0;
  min-width: 2px;
  transform-origin: bottom;
  background: linear-gradient(180deg, #9ff0ff, rgba(56, 232, 255, 0.16));
  box-shadow: 0 0 6px rgba(56, 232, 255, 0.55);
  animation: signal-bob 1.6s ease-in-out infinite alternate;
}

@keyframes signal-bob {
  from {
    transform: scaleY(0.42);
    opacity: 0.5;
  }
  to {
    transform: scaleY(1);
    opacity: 1;
  }
}

.app-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* 状态条内容会随窗口宽度变多（状态标签 + 开关 + 4 个按钮 ≈ 1180px），
     1366/1280 的笔记本屏幕减去 208px 侧栏后放不下一行，以前直接被窗口右边缘裁掉。
     这里允许折行：宁可顶栏变成两行，也不能让「新建运行 / 刷新」消失。 */
  flex-wrap: wrap;
  gap: 6px 12px;
  height: auto;
  min-height: 56px;
  padding: 8px 20px;
  /* 上下渐变 + 细刻度纹：像一条机载状态条，而不是普通后台的灰色横条 */
  background: linear-gradient(180deg, rgba(10, 29, 48, 0.96) 0%, rgba(4, 13, 24, 0.96) 100%);
  border-bottom: 1px solid rgba(56, 232, 255, 0.3);
  box-shadow:
    inset 0 1px 0 rgba(120, 220, 255, 0.08),
    0 1px 0 rgba(56, 232, 255, 0.12),
    0 10px 28px rgba(0, 0, 0, 0.45);
  line-height: normal;
}

/* 竖向刻度：仪表盘上的分度线，两端渐隐，不抢读数 */
.app-header::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image: linear-gradient(90deg, rgba(56, 232, 255, 0.06) 1px, transparent 1px);
  background-size: 24px 100%;
  mask-image: linear-gradient(90deg, transparent, #000 10%, #000 90%, transparent);
}

/* 底部扫描线：一条青光缓慢掠过，静态界面也有"系统在运行"的暗示 */
.app-header::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -1px;
  width: 28%;
  height: 2px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  filter: blur(0.4px);
  animation: hud-scan 9s linear infinite;
}

@keyframes hud-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(460%);
  }
}

/* HUD 四角括号：切角面板的定位标记 */
.hud-corner {
  position: absolute;
  width: 12px;
  height: 12px;
  pointer-events: none;
  border: 1px solid rgba(56, 232, 255, 0.7);
  filter: drop-shadow(0 0 4px rgba(56, 232, 255, 0.5));
}

.hud-corner--lt {
  left: 6px;
  top: 6px;
  border-right: 0;
  border-bottom: 0;
}

.hud-corner--rb {
  right: 6px;
  bottom: 6px;
  border-left: 0;
  border-top: 0;
}

.header-left,
.header-right {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

/* 右侧按钮组优先占满剩余宽度并靠右，空间不足时在组内折行 */
.header-right {
  flex: 1 1 auto;
  justify-content: flex-end;
}

/* -----------------------------------------------------------------------------
   读数条（替代 Ant 默认的圆角浅色标签）
   切角 + 等宽数字 + 微弱内辉光，看起来像设备读数而不是后台徽标。
   ----------------------------------------------------------------------------- */
.hud-chip.ant-tag {
  --chip: 56, 232, 255;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  margin: 0;
  padding: 0 10px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.4px;
  color: #bff4ff !important;
  background: linear-gradient(180deg, rgba(var(--chip), 0.16), rgba(var(--chip), 0.04)) !important;
  border: 1px solid rgba(var(--chip), 0.45) !important;
  border-radius: 0 !important;
  clip-path: polygon(7px 0, 100% 0, 100% calc(100% - 7px), calc(100% - 7px) 100%, 0 100%, 0 7px);
  box-shadow: inset 0 0 14px rgba(var(--chip), 0.14);
  font-variant-numeric: tabular-nums;
  text-shadow: 0 0 8px rgba(var(--chip), 0.45);
}

/* 读数条前的菱形指示灯 */
.hud-chip.ant-tag::before {
  content: '';
  width: 4px;
  height: 4px;
  flex: 0 0 auto;
  background: rgb(var(--chip));
  box-shadow: 0 0 7px rgba(var(--chip), 0.95);
  transform: rotate(45deg);
  animation: hud-led 2.4s ease-in-out infinite;
}

@keyframes hud-led {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 1;
  }
}

.hud-chip.hud-chip--info {
  --chip: 96, 165, 250;
  color: #d8eaff !important;
}

.hud-chip.hud-chip--data {
  --chip: 56, 232, 255;
  color: #c9f7ff !important;
}

.hud-chip.hud-chip--run {
  --chip: 74, 222, 128;
  color: #d3ffe4 !important;
}

.hud-chip.hud-chip--idle {
  --chip: 148, 170, 190;
  color: #dbe8f5 !important;
}

.hud-chip.hud-chip--cyan {
  --chip: 56, 232, 255;
}

.hud-chip.hud-chip--gold {
  --chip: 245, 183, 78;
  color: #ffe7bd !important;
}

.hud-chip.hud-chip--red {
  --chip: 255, 107, 107;
  color: #ffd6d6 !important;
}

.hud-chip.hud-chip--default {
  --chip: 148, 170, 190;
  color: #dbe8f5 !important;
}

.hud-chip :deep(.anticon) {
  color: rgb(var(--chip));
  filter: drop-shadow(0 0 5px rgba(var(--chip), 0.8));
}

/* 连接状态：文字用等宽体，指示灯呼吸 + 同色辉光 */
.hud-status :deep(.ant-badge-status-text) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #b8ecff;
}

.hud-status :deep(.ant-badge-status-dot) {
  width: 8px;
  height: 8px;
  animation: hud-breathe 2.2s ease-in-out infinite;
}

.hud-status :deep(.ant-badge-status-dot.ant-badge-status-success) {
  box-shadow:
    0 0 0 2px rgba(47, 214, 165, 0.16),
    0 0 10px rgba(47, 214, 165, 0.9);
}

.hud-status :deep(.ant-badge-status-dot.ant-badge-status-warning) {
  box-shadow:
    0 0 0 2px rgba(240, 169, 59, 0.16),
    0 0 10px rgba(240, 169, 59, 0.9);
}

.hud-status :deep(.ant-badge-status-dot.ant-badge-status-error) {
  box-shadow:
    0 0 0 2px rgba(255, 96, 96, 0.16),
    0 0 10px rgba(255, 96, 96, 0.9);
}

.hud-status :deep(.ant-badge-status-dot.ant-badge-status-processing) {
  box-shadow:
    0 0 0 2px rgba(56, 232, 255, 0.16),
    0 0 10px rgba(56, 232, 255, 0.9);
}

@keyframes hud-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
}

.hud-label {
  font-size: 12px;
  letter-spacing: 0.6px;
  color: rgba(150, 199, 232, 0.72);
}

.hud-divider.ant-divider-vertical {
  height: 16px;
  margin: 0 2px;
  border-inline-start-color: rgba(56, 232, 255, 0.28) !important;
}

/* 顶栏按钮：切角 + 描边 + 悬停辉光，与读数条同一套视觉语言 */
.app-header :deep(.ant-btn) {
  border-radius: 0;
  padding-inline: 10px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  letter-spacing: 0.3px;
  clip-path: polygon(6px 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%, 0 6px);
  background-image: linear-gradient(180deg, rgba(56, 232, 255, 0.1), rgba(56, 232, 255, 0.01));
  border-color: rgba(56, 232, 255, 0.32);
  color: #c9effc;
}

.app-header :deep(.ant-btn-default:not(:disabled):hover) {
  border-color: #38e8ff;
  color: #eafdff;
  /* 切角会裁掉 box-shadow，外发光用 drop-shadow 才不会被裁 */
  filter: drop-shadow(0 0 7px rgba(56, 232, 255, 0.55));
  box-shadow: inset 0 0 12px rgba(56, 232, 255, 0.14);
}

.app-header :deep(.ant-btn-text) {
  background-image: none;
  border-color: transparent;
}

.switch-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.switch-group :deep(.ant-switch-checked) {
  box-shadow: 0 0 12px rgba(56, 232, 255, 0.55);
}

.switch-label {
  font-size: 12px;
  letter-spacing: 0.4px;
  color: rgba(190, 216, 240, 0.84);
}

.app-content {
  padding: 16px;
  overflow: auto;
  /* 顶部冷光 + 细网格：整个内容区像一块发光的作业台面 */
  background-image:
    radial-gradient(900px 420px at 50% -8%, rgba(56, 232, 255, 0.08), transparent 68%),
    linear-gradient(rgba(56, 232, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 232, 255, 0.045) 1px, transparent 1px);
  background-size:
    auto,
    32px 32px,
    32px 32px;
}

/* 尊重"减少动态效果"：关掉扫光与呼吸，保留静态造型 */
@media (prefers-reduced-motion: reduce) {
  .app-header::after,
  .brand-logo::after,
  .brand::before,
  .brand::after,
  .sider-sweep::before,
  .sider-ruler::after,
  .nav-spine::after,
  .signal-bar,
  .hud-chip.ant-tag::before,
  .hud-status :deep(.ant-badge-status-dot),
  .app-sider :deep(.nav-glow)::before,
  .app-sider :deep(.nav-glow)::after {
    animation: none;
  }
}

.logout-btn {
  color: rgba(159, 199, 232, 0.7);
}

.logout-btn:hover {
  color: #ff9a9a !important;
}
</style>
