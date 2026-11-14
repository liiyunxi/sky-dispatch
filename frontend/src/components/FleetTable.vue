<script setup lang="ts">
import { computed } from 'vue'
import type { DroneFrame } from '@/api'
import { DRONE_STATUS_COLOR, DRONE_STATUS_TEXT, formatFromNow } from '@/utils/format'

/**
 * 机队实时状态表。
 *
 * 数据源是 WebSocket 推来的 `DroneFrame`，所以这一列字段比"无人机台账"少
 * （没有累计里程、机型等静态信息）——那些在「机队管理」页看。
 * 这里只回答一个问题：**现在每架飞机在哪、什么状态、还剩多少电**。
 */
const props = defineProps<{
  drones: DroneFrame[]
  selectedDroneId?: number | null
  loading?: boolean
  /** 表格最大高度，超出滚动；实时表格不能无限长高 */
  height?: number
}>()

const emit = defineEmits<{ (e: 'select-drone', droneId: number): void }>()

const columns = [
  { title: '机身编号', dataIndex: 'droneCode', key: 'droneCode', width: 110, fixed: 'left' as const },
  { title: '状态', dataIndex: 'status', key: 'status', width: 96 },
  { title: '电量', dataIndex: 'batteryPct', key: 'batteryPct', width: 150 },
  { title: '任务 / 订单', dataIndex: 'taskId', key: 'task', width: 170 },
  { title: '进度', dataIndex: 'progressPct', key: 'progressPct', width: 130 },
  { title: '速度', dataIndex: 'speedMps', key: 'speedMps', width: 96 },
  { title: '高度', dataIndex: 'altitudeM', key: 'altitudeM', width: 90 },
  { title: '航向', dataIndex: 'headingDeg', key: 'headingDeg', width: 80 },
  { title: '最后心跳', dataIndex: 'ts', key: 'ts', width: 100 },
]

/** 电量进度条颜色分档：<25% 红、<50% 黄、其余绿（与机队管理的口径保持一致） */
function batteryStatus(pct?: number): 'exception' | 'active' | 'success' | 'normal' {
  if (pct === undefined || pct === null) return 'normal'
  if (pct < 25) return 'exception'
  if (pct < 50) return 'active'
  return 'success'
}

const rowClassName = (record: DroneFrame) =>
  record.droneId === props.selectedDroneId ? 'fleet-row-selected' : ''

const customRow = (record: DroneFrame) => ({
  onClick: () => emit('select-drone', record.droneId),
  style: { cursor: 'pointer' },
})

/** 有序展示：飞行中的排在前面，方便运营先看"在忙的" */
const sortedDrones = computed(() => {
  const weight: Record<string, number> = {
    IN_FLIGHT: 0,
    CRUISING: 0,
    DELIVERING: 0,
    ASSIGNED: 1,
    TAKEOFF: 1,
    RETURNING: 2,
    IDLE: 3,
    CHARGING: 4,
    MAINTENANCE: 5,
    OFFLINE: 6,
  }
  return [...props.drones].sort((a, b) => (weight[a.status] ?? 9) - (weight[b.status] ?? 9))
})
</script>

<template>
  <a-table
    :columns="columns"
    :data-source="sortedDrones"
    :loading="loading"
    :pagination="false"
    :row-class-name="rowClassName"
    :custom-row="customRow"
    :scroll="{ y: height ?? 320, x: 1000 }"
    row-key="droneId"
    size="small"
  >
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'droneCode'">
        <strong class="text-mono">{{ record.droneCode }}</strong>
      </template>

      <template v-else-if="column.key === 'status'">
        <a-tag :color="DRONE_STATUS_COLOR[record.status as keyof typeof DRONE_STATUS_COLOR]">
          {{ DRONE_STATUS_TEXT[record.status as keyof typeof DRONE_STATUS_TEXT] ?? record.status }}
        </a-tag>
      </template>

      <template v-else-if="column.key === 'batteryPct'">
        <a-progress
          :percent="Number(record.batteryPct ?? 0)"
          :status="batteryStatus(record.batteryPct)"
          :stroke-color="(record.batteryPct ?? 0) < 25 ? '#ff4d4f' : undefined"
          size="small"
        />
      </template>

      <template v-else-if="column.key === 'task'">
        <template v-if="record.taskId">
          <a-tag color="blue">#{{ record.taskId }}</a-tag>
          <span v-if="record.orderNo" class="text-muted text-mono">{{ record.orderNo }}</span>
        </template>
        <span v-else class="text-muted">—</span>
      </template>

      <template v-else-if="column.key === 'progressPct'">
        <a-progress
          v-if="record.progressPct !== undefined && record.progressPct !== null"
          :percent="Number(record.progressPct)"
          :show-info="true"
          size="small"
          stroke-color="#1677ff"
        />
        <span v-else class="text-muted">—</span>
      </template>

      <template v-else-if="column.key === 'speedMps'">
        {{ record.speedMps !== undefined ? `${Number(record.speedMps).toFixed(1)} m/s` : '—' }}
      </template>

      <template v-else-if="column.key === 'altitudeM'">
        {{ record.altitudeM !== undefined ? `${Number(record.altitudeM).toFixed(0)} m` : '—' }}
      </template>

      <template v-else-if="column.key === 'headingDeg'">
        {{ record.headingDeg !== undefined ? `${Number(record.headingDeg).toFixed(0)}°` : '—' }}
      </template>

      <template v-else-if="column.key === 'ts'">
        <span class="text-muted">{{ formatFromNow(record.ts) }}</span>
      </template>
    </template>

    <template #emptyText>
      <a-empty description="暂无遥测数据，请确认后端仿真已启动" />
    </template>
  </a-table>
</template>

<style scoped>
/* 选中行高亮：与地图上点选的无人机双向联动 */
:deep(.fleet-row-selected) > td {
  background: #e6f4ff !important;
}
</style>
