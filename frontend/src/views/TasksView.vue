<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { reportApi, taskApi } from '@/api'
import type { DispatchTask, EventLogItem, TaskStatus, Waypoint } from '@/api'
import {
  EVENT_SEVERITY_COLOR,
  STRATEGY_TEXT,
  TASK_STATUS_COLOR,
  TASK_STATUS_TEXT,
  eventTypeText,
  formatDateTime as fmtDateTime,
  formatDistance,
  formatDuration,
  formatEnergy,
  formatPercent,
} from '@/utils/format'

/**
 * 任务管理：列表走服务端分页（任务量随时间线性增长，不能全量拉到前端分页），
 * 详情用抽屉承载航路点 / 事件 / 遥测导出，避免在列表页之间来回跳转丢失筛选条件。
 */

type TableColumn = {
  title: string
  dataIndex?: string
  key?: string
  width?: number
  align?: 'left' | 'center' | 'right'
  /** 列固定。操作列固定在右侧，横向滚动时始终可见 */
  fixed?: 'left' | 'right'
}

const loading = ref(false)
/** 抽屉内的航路点 / 事件加载状态，与列表 loading 分开，避免互相干扰 */
const detailLoading = ref(false)
const detailOpen = ref(false)
const currentTask = ref<DispatchTask | null>(null)
const waypoints = ref<Waypoint[]>([])
const events = ref<EventLogItem[]>([])
const exporting = ref<'telemetry' | 'events' | ''>('')

/** droneId 允许为 null（a-input-number 清空 / allowClear 回填 null），构建查询参数时再转成 undefined */
const query = reactive<{ status?: TaskStatus; droneId: number | null }>({ droneId: null })
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const tasks = ref<DispatchTask[]>([])

const statusOptions = (Object.keys(TASK_STATUS_TEXT) as TaskStatus[]).map((s) => ({
  value: s,
  label: TASK_STATUS_TEXT[s],
}))

const columns: TableColumn[] = [
  // 列宽总和 = 1170，与下方 scroll.x 保持一致：
  // 1440 宽的窗口里表格正好铺满卡片，不再出现横向滚动条；更窄的窗口才退化为表格内滚动。
  { title: '任务号', dataIndex: 'taskNo', key: 'taskNo', width: 130 },
  { title: '订单号', dataIndex: 'orderNo', key: 'orderNo', width: 130 },
  { title: '无人机', dataIndex: 'droneCode', key: 'droneCode', width: 95 },
  { title: '派单策略', key: 'strategy', width: 160 },
  { title: '状态', key: 'status', width: 80 },
  { title: '进度', key: 'progress', width: 95 },
  { title: '计划航程', key: 'plannedDistanceM', width: 85 },
  { title: '实际航程', key: 'actualDistanceM', width: 85 },
  { title: '预计/实际能耗', key: 'energy', width: 120 },
  { title: '耗时', key: 'durationS', width: 80 },
  { title: '操作', key: 'action', width: 110, fixed: 'right' as const },
]

const waypointColumns: TableColumn[] = [
  { title: '序号', dataIndex: 'seq', key: 'seq', width: 70 },
  { title: '纬度', dataIndex: 'lat', key: 'lat', width: 120 },
  { title: '经度', dataIndex: 'lon', key: 'lon', width: 120 },
  { title: '高度(m)', dataIndex: 'altitudeM', key: 'altitudeM', width: 90 },
  { title: '累计航程', key: 'cumulativeDistanceM', width: 110 },
  { title: '预计到达', key: 'etaSeconds', width: 110 },
]

/** 表格分页配置：total 取后端 totalElements，size 变化时后端也按新 size 重新取数 */
const tablePagination = computed(() => ({
  current: pagination.current,
  pageSize: pagination.pageSize,
  total: pagination.total,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 条`,
}))

function strategyText(strategy?: string): string {
  if (!strategy) return '—'
  return STRATEGY_TEXT[strategy] ?? strategy
}

function energyText(planned?: number, consumed?: number): string {
  return `${formatEnergy(planned)} / ${formatEnergy(consumed)}`
}

async function loadTasks(): Promise<void> {
  loading.value = true
  try {
    const page = await taskApi.list({
      status: query.status,
      droneId: query.droneId ?? undefined,
      page: pagination.current,
      size: pagination.pageSize,
    })
    tasks.value = page.content ?? []
    pagination.total = page.totalElements ?? 0
  } catch {
    // client.ts 已统一提示错误，这里只做收尾；列表保留上一次数据便于比对
  } finally {
    loading.value = false
  }
}

/** 筛选条件变化要回到第 1 页，否则会出现"筛选后空白页" */
function handleFilterChange(): void {
  pagination.current = 1
  void loadTasks()
}

function handleTableChange(paginator: { current?: number; pageSize?: number }): void {
  pagination.current = paginator.current ?? 1
  pagination.pageSize = paginator.pageSize ?? pagination.pageSize
  void loadTasks()
}

async function openDetail(record: DispatchTask): Promise<void> {
  currentTask.value = record
  detailOpen.value = true
  waypoints.value = []
  events.value = []
  detailLoading.value = true
  try {
    // 两个接口互不依赖，并行请求缩短抽屉空白时间；
    // 用 allSettled 而不是 all：其中一个失败时另一个仍能正常渲染
    const [wp, ev] = await Promise.allSettled([taskApi.waypoints(record.id), taskApi.events(record.id)])
    if (wp.status === 'fulfilled') waypoints.value = wp.value ?? []
    if (ev.status === 'fulfilled') events.value = ev.value ?? []
  } finally {
    detailLoading.value = false
  }
}

async function abortTask(record: DispatchTask): Promise<void> {
  try {
    await taskApi.abort(record.id, '运营人工中止')
    message.success(`任务 ${record.taskNo} 已中止`)
    await loadTasks()
  } catch {
    // 中止失败（例如任务已完成）时刷新列表，拿回后端的最新状态
    await loadTasks()
  }
}

async function exportTelemetry(): Promise<void> {
  const task = currentTask.value
  if (!task) return
  exporting.value = 'telemetry'
  try {
    await reportApi.telemetryCsv(task.id)
  } catch {
    // 下载失败已由 client.ts 提示
  } finally {
    exporting.value = ''
  }
}

async function exportEvents(): Promise<void> {
  const task = currentTask.value
  if (!task) return
  exporting.value = 'events'
  try {
    await reportApi.eventsCsv(task.id)
  } catch {
    // 下载失败已由 client.ts 提示
  } finally {
    exporting.value = ''
  }
}

onMounted(() => {
  void loadTasks()
})
</script>

<template>
  <div class="tasks-view">
    <!-- ------------------------------ 工具条 ------------------------------ -->
    <a-card :bordered="false" size="small" class="toolbar-card">
      <a-space wrap>
        <a-select
          v-model:value="query.status"
          class="filter"
          placeholder="全部任务状态"
          allow-clear
          :options="statusOptions"
          @change="handleFilterChange"
        />
        <a-input-number
          v-model:value="query.droneId"
          class="filter filter-narrow"
          placeholder="无人机 ID"
          :min="1"
          :precision="0"
          @change="handleFilterChange"
        />
        <a-button :loading="loading" @click="loadTasks">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </a-space>
    </a-card>

    <!-- ------------------------------ 任务表格 ------------------------------ -->
    <a-card :bordered="false" size="small" class="table-card">
      <a-table
        row-key="id"
        :columns="columns"
        :data-source="tasks"
        :loading="loading"
        :pagination="tablePagination"
        :scroll="{ x: 1170 }"
        size="middle"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'taskNo'">
            <span class="text-mono">{{ record.taskNo ?? '—' }}</span>
          </template>

          <template v-else-if="column.key === 'orderNo'">
            <span class="text-mono">{{ record.orderNo ?? '—' }}</span>
          </template>

          <template v-else-if="column.key === 'droneCode'">
            {{ record.droneCode ?? '—' }}
          </template>

          <template v-else-if="column.key === 'strategy'">
            {{ strategyText(record.strategy) }}
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag :color="TASK_STATUS_COLOR[record.status as TaskStatus]">
              {{ TASK_STATUS_TEXT[record.status as TaskStatus] ?? record.status }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'progress'">
            <a-progress :percent="Number(record.progressPct ?? 0)" size="small" />
          </template>

          <template v-else-if="column.key === 'plannedDistanceM'">
            {{ formatDistance(record.plannedDistanceM) }}
          </template>

          <template v-else-if="column.key === 'actualDistanceM'">
            {{ formatDistance(record.actualDistanceM) }}
          </template>

          <template v-else-if="column.key === 'energy'">
            {{ energyText(record.plannedEnergyWh, record.consumedEnergyWh) }}
          </template>

          <template v-else-if="column.key === 'durationS'">
            {{ formatDuration(record.durationS) }}
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-popconfirm
                title="确认中止该任务？中止后无人机将返航。"
                ok-text="确认中止"
                cancel-text="取消"
                :disabled="record.status === 'COMPLETED' || record.status === 'ABORTED'"
                @confirm="abortTask(record)"
              >
                <a-button type="link" size="small" danger>中止</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-drawer
      v-model:open="detailOpen"
      :width="760"
      title="任务详情"
      placement="right"
      :body-style="{ paddingBottom: '80px' }"
    >
      <a-spin :spinning="detailLoading">
        <template v-if="currentTask">
          <a-descriptions title="任务信息" bordered :column="2" size="small">
            <a-descriptions-item label="任务号">{{ currentTask.taskNo ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="订单号">{{ currentTask.orderNo ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="无人机">{{ currentTask.droneCode ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="无人机 ID">{{ currentTask.droneId ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="订单 ID">{{ currentTask.orderId ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="航路 ID">{{ currentTask.routeId ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="派单策略">{{ strategyText(currentTask.strategy) }}</a-descriptions-item>
            <a-descriptions-item label="任务状态">
              <a-tag :color="TASK_STATUS_COLOR[currentTask.status as TaskStatus]">
                {{ TASK_STATUS_TEXT[currentTask.status as TaskStatus] ?? currentTask.status }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="执行进度">
              {{ formatPercent(currentTask.progressPct) }}
            </a-descriptions-item>
            <a-descriptions-item label="当前航路点">
              {{ currentTask.currentWaypointSeq ?? '—' }}
            </a-descriptions-item>
            <a-descriptions-item label="计划航程">
              {{ formatDistance(currentTask.plannedDistanceM) }}
            </a-descriptions-item>
            <a-descriptions-item label="实际航程">
              {{ formatDistance(currentTask.actualDistanceM) }}
            </a-descriptions-item>
            <a-descriptions-item label="预计能耗">
              {{ formatEnergy(currentTask.plannedEnergyWh) }}
            </a-descriptions-item>
            <a-descriptions-item label="实际能耗">
              {{ formatEnergy(currentTask.consumedEnergyWh) }}
            </a-descriptions-item>
            <a-descriptions-item label="起始电量">
              {{ formatPercent(currentTask.batteryStartPct, 0) }}
            </a-descriptions-item>
            <a-descriptions-item label="结束电量">
              {{ formatPercent(currentTask.batteryEndPct, 0) }}
            </a-descriptions-item>
            <a-descriptions-item label="派发时间">
              {{ fmtDateTime(currentTask.assignedAt) }}
            </a-descriptions-item>
            <a-descriptions-item label="开始时间">
              {{ fmtDateTime(currentTask.startedAt) }}
            </a-descriptions-item>
            <a-descriptions-item label="结束时间">
              {{ fmtDateTime(currentTask.finishedAt) }}
            </a-descriptions-item>
            <a-descriptions-item label="执行耗时">
              {{ formatDuration(currentTask.durationS) }}
            </a-descriptions-item>
            <a-descriptions-item label="失败原因" :span="2">
              {{ currentTask.failReason ?? '—' }}
            </a-descriptions-item>
          </a-descriptions>

          <a-divider orientation="left">航路点（{{ waypoints.length }} 个）</a-divider>
          <a-table
            row-key="seq"
            :columns="waypointColumns"
            :data-source="waypoints"
            size="small"
            :pagination="false"
            :scroll="{ y: 240 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'lat'">{{ record.lat?.toFixed(5) ?? '—' }}</template>
              <template v-else-if="column.key === 'lon'">{{ record.lon?.toFixed(5) ?? '—' }}</template>
              <template v-else-if="column.key === 'altitudeM'">{{ record.altitudeM ?? '—' }}</template>
              <template v-else-if="column.key === 'cumulativeDistanceM'">
                {{ formatDistance(record.cumulativeDistanceM) }}
              </template>
              <template v-else-if="column.key === 'etaSeconds'">
                {{ formatDuration(record.etaSeconds) }}
              </template>
            </template>
          </a-table>

          <a-divider orientation="left">事件时间线（{{ events.length }} 条）</a-divider>
          <a-empty v-if="events.length === 0" description="暂无事件记录" />
          <a-timeline v-else>
            <a-timeline-item v-for="event in events" :key="event.id">
              <div class="event-head">
                <span class="event-time text-mono">{{ fmtDateTime(event.ts) }}</span>
                <a-tag :color="EVENT_SEVERITY_COLOR[event.severity]">{{ event.severity }}</a-tag>
                <span class="event-type">{{ eventTypeText(event.eventType) }}</span>
              </div>
              <div class="event-message">{{ event.message ?? '—' }}</div>
              <div v-if="event.payload" class="event-payload text-mono">{{ event.payload }}</div>
            </a-timeline-item>
          </a-timeline>
        </template>
      </a-spin>

      <template #footer>
        <a-space>
          <a-button :loading="exporting === 'telemetry'" @click="exportTelemetry">
            <template #icon><DownloadOutlined /></template>
            导出遥测 CSV
          </a-button>
          <a-button :loading="exporting === 'events'" @click="exportEvents">
            <template #icon><DownloadOutlined /></template>
            导出事件 CSV
          </a-button>
        </a-space>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.tasks-view {
  /* 外层 .app-content 已给内边距，这里只负责卡片之间的间距 */
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar-card,
.table-card {
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.filter {
  width: 220px;
}

.filter-narrow {
  width: 130px;
}

.event-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.event-time {
  color: rgba(180, 208, 232, 0.55);
}

.event-type {
  font-weight: 600;
}

.event-message {
  margin-top: 2px;
  color: #d8e8fb;
}

.event-payload {
  margin-top: 2px;
  color: rgba(180, 208, 232, 0.55);
  word-break: break-all;
}
</style>
