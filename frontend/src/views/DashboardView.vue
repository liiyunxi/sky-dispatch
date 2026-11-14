<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import DroneMap from '@/components/DroneMap.vue'
import FleetTable from '@/components/FleetTable.vue'
import KpiCards from '@/components/KpiCards.vue'
import ConflictList from '@/components/ConflictList.vue'
import EventTimeline from '@/components/EventTimeline.vue'
import StatusCharts from '@/components/StatusCharts.vue'
import { dispatchApi, type DispatchResult } from '@/api'
import { useTelemetry } from '@/store/telemetry'

/**
 * 实时看板（首页）。
 *
 * 布局思路是"先看态势、再看明细"：
 *   第一屏   KPI 卡片 —— 一句话回答"今天怎么样"
 *   第二屏   地图 + 图表 —— 空间分布与结构分布
 *   第三屏   机队明细 + 告警/事件 —— 需要深挖时才往下看
 *
 * 数据节奏（这是实时看板的关键设计）：
 *   - 位置/电量：WebSocket 每秒推，直接渲染，不做节流（8~200 架都撑得住）
 *   - 航路线：15 秒拉一次（航路规划完就不变了，没必要每秒拉）
 *   - 冲突列表：30 秒兜底拉一次（主要靠 WebSocket 推新冲突）
 *   - 仿真运行信息：10 秒拉一次
 * 全部定时器在 onUnmounted 里清理，否则切页面后会有一堆幽灵请求。
 */

const telemetry = useTelemetry()
const {
  kpi,
  drones,
  events,
  conflicts,
  zones,
  stations,
  routes,
  orderPins,
  algorithmAvailable,
  algorithmEnabled,
  usingPolling,
} = telemetry

const selectedDroneId = ref<number | null>(null)
const refreshing = ref(false)
const conflictTab = ref('conflicts')

/** 批量派单结果弹窗：让"派单到底做了什么"变成看得见的东西 */
const dispatchResultOpen = ref(false)
const dispatchResults = ref<DispatchResult[]>([])
const dispatchPendingCount = computed(() => kpi.value?.pendingOrders ?? 0)

let routeTimer: number | null = null
let conflictTimer: number | null = null
let runTimer: number | null = null
let orderPinTimer: number | null = null

function onSelectDrone(droneId: number) {
  // 再次点击同一架则取消选中，避免"点不回去"
  selectedDroneId.value = selectedDroneId.value === droneId ? null : droneId
}

async function handleResolveConflict(id: number) {
  try {
    await telemetry.resolveConflict(id, '看板人工确认解除')
    message.success('冲突已标记为已处置')
  } catch {
    /* client.ts 已提示 */
  }
}

/**
 * 立即批量派单。
 *
 * 早期版本的体验问题：按钮点下去只有一句 message，
 * 而"待派单 3 单、成功 0 单"看起来就像没反应。现在做三件事让效果可见：
 *  1. 按钮上直接显示待派单数量，为 0 时禁用（而不是点完才说没有）；
 *  2. 派单结果用弹窗逐单列出：派给了谁，或者为什么没派成；
 *  3. 提示可以关掉「自动派单」，否则手动派单会和每 10 秒的定时任务抢活干。
 */
async function handleAutoDispatch() {
  refreshing.value = true
  try {
    const results = await dispatchApi.autoDispatch(undefined, 5)
    if (results.length === 0) {
      message.info('当前没有待派单的订单（可能都已被派发或正在配送中）')
      return
    }
    dispatchResults.value = results
    dispatchResultOpen.value = true
    const succeeded = results.filter((item) => item.task).length
    if (succeeded > 0) {
      message.success(`批量派单：尝试 ${results.length} 单，成功 ${succeeded} 单`)
    } else {
      message.warning(
        `批量派单：尝试 ${results.length} 单，全部失败 —— 通常是续航/距离/载重不满足，逐单原因见弹窗`,
      )
    }
    await Promise.all([telemetry.refreshSnapshot(), telemetry.loadRoutes()])
  } catch {
    /* 已提示 */
  } finally {
    refreshing.value = false
  }
}

async function handleRefreshAll() {
  refreshing.value = true
  try {
    await Promise.all([
      telemetry.refreshSnapshot(),
      telemetry.loadRoutes(),
      telemetry.loadZones(),
      telemetry.refreshRun(),
      telemetry.probeAlgorithm(),
    ])
    message.success('已刷新')
  } catch {
    /* 已提示 */
  } finally {
    refreshing.value = false
  }
}

onMounted(async () => {
  if (zones.value.length === 0) await telemetry.loadZones()
  // 充电站是低频台账，进页面拉一次即可（地图上标出"中途能落哪儿"）
  if (stations.value.length === 0) void telemetry.loadStations()
  await Promise.all([telemetry.loadRoutes(), telemetry.refreshRun()])

  routeTimer = window.setInterval(() => void telemetry.loadRoutes(), 15_000)
  conflictTimer = window.setInterval(() => void telemetry.loadConflicts(), 30_000)
  runTimer = window.setInterval(() => void telemetry.refreshRun(), 10_000)
  // 订单的取货点 / 目的地标记不随每秒遥测推送，低频刷新快照即可反映"已送达变绿"
  orderPinTimer = window.setInterval(() => void telemetry.refreshSnapshot(), 10_000)
})

onUnmounted(() => {
  if (routeTimer !== null) window.clearInterval(routeTimer)
  if (conflictTimer !== null) window.clearInterval(conflictTimer)
  if (runTimer !== null) window.clearInterval(runTimer)
  if (orderPinTimer !== null) window.clearInterval(orderPinTimer)
})
</script>

<template>
  <div class="dashboard">
    <!-- 算法服务被启用却连不上才是真故障：派单会 503，必须显式告警 -->
    <a-alert
      v-if="algorithmEnabled === true && algorithmAvailable === false"
      type="error"
      show-icon
      banner
      class="mb-16"
      message="算法服务离线"
      description="Python 算法服务（默认 http://localhost:8000）不可达，路径规划与冲突检测都无法工作，派单会返回 503。请启动 algorithm-service 后刷新。"
    />
    <!-- dev 默认的离线降级不是故障：直线航路，派单与送达正常进行，提示一句避免误会 -->
    <a-alert
      v-else-if="algorithmEnabled === false"
      type="info"
      show-icon
      banner
      class="mb-16"
      message="算法离线降级模式"
      description="当前未启用 Python 算法服务，路径规划使用直线航路（不主动绕开禁飞区）；派单、飞行与送达均正常，可完整演示调度闭环。顶部状态条显示金色「离线降级」。"
    />
    <a-alert
      v-else-if="usingPolling"
      type="warning"
      show-icon
      banner
      class="mb-16"
      message="实时通道已降级"
      description="WebSocket 连接失败，已自动切换为 REST 轮询（3 秒一次）。数据仍然可用，但刷新频率与流畅度会下降。"
    />

    <!-- ------------------------------ KPI ------------------------------ -->
    <KpiCards :kpi="kpi" />

    <!-- ------------------------------ 地图 + 图表 ------------------------------ -->
    <a-row :gutter="[12, 12]" class="mt-16">
      <a-col :xs="24" :xl="16">
        <a-card :bordered="false" size="small" class="panel">
          <template #title>
            <span>空域态势与实时航迹</span>
            <a-tag v-if="routes.length" color="blue" class="panel-tag">{{ routes.length }} 条在飞航路</a-tag>
          </template>
          <template #extra>
            <a-space size="small">
              <span class="text-muted legend-inline">点地图上的飞机可与右侧表格联动</span>
              <a-button size="small" :loading="refreshing" @click="handleRefreshAll">
                <template #icon><ReloadOutlined /></template>
                刷新
              </a-button>
            </a-space>
          </template>
          <DroneMap
            :drones="drones"
            :zones="zones"
            :stations="stations"
            :routes="routes"
            :order-pins="orderPins"
            :selected-drone-id="selectedDroneId"
            height="480px"
            @select-drone="onSelectDrone"
          />
        </a-card>
      </a-col>

      <a-col :xs="24" :xl="8">
        <a-card :bordered="false" size="small" class="panel">
          <template #title>结构分布</template>
          <StatusCharts :kpi="kpi" :drones="drones" />
        </a-card>
      </a-col>
    </a-row>

    <!-- ------------------------------ 机队 + 告警/事件 ------------------------------ -->
    <a-row :gutter="[12, 12]" class="mt-16">
      <a-col :xs="24" :xl="16">
        <a-card :bordered="false" size="small" class="panel">
          <template #title>
            <span>机队实时状态</span>
            <a-tag class="panel-tag">{{ drones.length }} 架</a-tag>
          </template>
          <template #extra>
            <a-space size="small">
              <a-tag :color="dispatchPendingCount > 0 ? 'orange' : 'default'">
                待派单 {{ dispatchPendingCount }}
              </a-tag>
              <a-tooltip
                :title="
                  dispatchPendingCount === 0
                    ? '当前没有待派单的订单'
                    : '把排队中的订单按优先级分派给可用无人机'
                "
              >
                <a-button
                  size="small"
                  type="primary"
                  ghost
                  :loading="refreshing"
                  :disabled="dispatchPendingCount === 0"
                  @click="handleAutoDispatch"
                >
                  <template #icon><ThunderboltOutlined /></template>
                  立即批量派单
                </a-button>
              </a-tooltip>
            </a-space>
          </template>
          <FleetTable
            :drones="drones"
            :selected-drone-id="selectedDroneId"
            :height="300"
            @select-drone="onSelectDrone"
          />
        </a-card>
      </a-col>

      <a-col :xs="24" :xl="8">
        <a-card :bordered="false" size="small" class="panel">
          <a-tabs v-model:active-key="conflictTab" size="small">
            <a-tab-pane key="conflicts">
              <template #tab>
                <a-badge :count="conflicts.length" :offset="[8, -2]" :dot="false" size="small">
                  <span>冲突告警</span>
                </a-badge>
              </template>
              <ConflictList :conflicts="conflicts" @resolve="handleResolveConflict" />
            </a-tab-pane>
            <a-tab-pane key="events" tab="事件流">
              <EventTimeline :events="events" />
            </a-tab-pane>
          </a-tabs>
        </a-card>
      </a-col>
    </a-row>

    <!-- ------------------------------ 派单结果弹窗 ------------------------------ -->
    <!-- 让"派单到底做了什么"变成看得见的东西：逐单列出派给了谁，或者为什么没派成 -->
    <a-modal
      v-model:open="dispatchResultOpen"
      title="批量派单结果"
      width="720px"
      :footer="null"
    >
      <a-alert
        type="info"
        show-icon
        class="mb-16"
        message="失败不是故障，是约束在起作用"
        description="无人机必须满足载重、电量冗余、往返续航与搜索半径四项硬条件。不满足的会写明原因；想看每架候选机的得分排名，请到「订单管理」页打开该订单的决策明细。"
      />
      <a-table
        :columns="[
          { title: '订单', key: 'order', width: 150 },
          { title: '执行无人机', key: 'drone', width: 120 },
          { title: '结果', key: 'result', width: 90 },
          { title: '说明', key: 'message' },
        ]"
        :data-source="dispatchResults"
        :pagination="false"
        size="small"
        row-key="message"
      >
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'order'">
            <span class="text-mono">{{ record.task?.orderNo ?? `第 ${index + 1} 单` }}</span>
          </template>
          <template v-else-if="column.key === 'drone'">
            <a-tag v-if="record.task?.droneCode" color="blue">{{ record.task.droneCode }}</a-tag>
            <span v-else class="text-muted">—</span>
          </template>
          <template v-else-if="column.key === 'result'">
            <a-tag v-if="record.task" color="success">已派发</a-tag>
            <a-tag v-else color="error">未派发</a-tag>
          </template>
          <template v-else-if="column.key === 'message'">
            <span>{{ record.message }}</span>
          </template>
        </template>
      </a-table>
    </a-modal>
  </div>
</template>

<style scoped>
.dashboard {
  padding-bottom: 8px;
}

.panel {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
  height: 100%;
}

.panel-tag {
  margin-left: 8px;
  font-weight: 400;
}

.legend-inline {
  font-size: 12px;
}
</style>
