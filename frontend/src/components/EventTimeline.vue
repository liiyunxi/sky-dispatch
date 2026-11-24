<script setup lang="ts">
import { computed, ref } from 'vue'
import { EVENT_SEVERITY_COLOR, eventTypeText, formatTime } from '@/utils/format'
import type { SkyEvent } from '@/api'

/**
 * 事件流（时间线）。
 *
 * 数据来自 WebSocket 的 EVENT 消息，按"最新在最上"排列。
 * 每条事件的 severity 决定圆点颜色，运营扫一眼就能定位异常。
 *
 * 这里刻意不做分页：事件流是"气氛指标"，滚动看最近 100 条足够；
 * 要精确追溯请去「任务管理」页看某个任务的完整时间线（那是从数据库读的全量记录）。
 *
 * P2：事件一多，"故障"和"地面等待"会被任务状态变化淹没，
 * 所以顶部按类型分组筛选；分组是**语义分组**，不是枚举逐个列 ——
 * 4 种 FAULT_* 对运营是同一件事，没必要占四行。
 */
const props = defineProps<{
  events: SkyEvent[]
}>()

type EventCategory = 'ALL' | 'FAULT' | 'HOLD' | 'RELAY' | 'CONFLICT' | 'TASK' | 'ORDER' | 'SIM'

const CATEGORY_LABEL: Record<EventCategory, string> = {
  ALL: '全部',
  FAULT: '故障',
  HOLD: '地面等待',
  RELAY: '补能',
  CONFLICT: '冲突',
  TASK: '任务',
  ORDER: '订单',
  SIM: '仿真',
}

/** 事件类型 → 语义分组 */
function categoryOf(eventType: string): EventCategory | null {
  if (eventType.startsWith('FAULT_') || eventType === 'DRONE_BATTERY_LOW') return 'FAULT'
  if (eventType === 'GROUND_HOLD') return 'HOLD'
  if (eventType.startsWith('DRONE_RELAY')) return 'RELAY'
  if (eventType === 'CONFLICT_DETECTED') return 'CONFLICT'
  if (eventType.startsWith('TASK_')) return 'TASK'
  if (eventType.startsWith('ORDER_')) return 'ORDER'
  if (eventType.startsWith('SIMULATION_')) return 'SIM'
  return null
}

const activeFilter = ref<EventCategory>('ALL')

const categoryCounts = computed<Record<EventCategory, number>>(() => {
  const counts: Record<EventCategory, number> = {
    ALL: props.events.length,
    FAULT: 0,
    HOLD: 0,
    RELAY: 0,
    CONFLICT: 0,
    TASK: 0,
    ORDER: 0,
    SIM: 0,
  }
  props.events.forEach((event) => {
    const category = categoryOf(event.eventType)
    if (category) counts[category] += 1
  })
  return counts
})

/** 筛选按钮：计数为 0 的类型不占位置（"故障 0"没有信息量） */
const filterOptions = computed(() =>
  (Object.keys(CATEGORY_LABEL) as EventCategory[])
    .filter((key) => key === 'ALL' || categoryCounts.value[key] > 0)
    .map((key) => ({ key, label: CATEGORY_LABEL[key], count: categoryCounts.value[key] })),
)

const filteredEvents = computed(() => {
  if (activeFilter.value === 'ALL') return props.events
  return props.events.filter((event) => categoryOf(event.eventType) === activeFilter.value)
})
</script>

<template>
  <div class="event-timeline">
    <div class="event-filter">
      <button
        v-for="option in filterOptions"
        :key="option.key"
        type="button"
        class="event-filter-chip"
        :class="{ active: activeFilter === option.key }"
        @click="activeFilter = option.key"
      >
        {{ option.label }}
        <b>{{ option.count }}</b>
      </button>
    </div>

    <a-timeline v-if="filteredEvents.length > 0">
      <a-timeline-item
        v-for="event in filteredEvents"
        :key="`${event.ts}-${event.eventType}-${event.taskId ?? 0}-${event.droneId ?? 0}`"
        :class="`sev-${String(event.severity).toLowerCase()}`"
        :color="
          event.severity === 'ERROR'
            ? 'red'
            : event.severity === 'WARN'
              ? 'orange'
              : event.severity === 'INFO'
                ? 'blue'
                : 'gray'
        "
      >
        <div class="event-card">
          <span class="event-scan" aria-hidden="true" />
          <div class="event-head">
            <a-tag :color="EVENT_SEVERITY_COLOR[event.severity] ?? 'default'" class="event-type">
              {{ eventTypeText(event.eventType) }}
            </a-tag>
            <span class="text-muted event-time">{{ formatTime(event.ts) }}</span>
          </div>
          <div class="event-message">{{ event.message }}</div>
        </div>
      </a-timeline-item>
    </a-timeline>

    <a-empty
      v-else
      :description="activeFilter === 'ALL' ? '暂无事件，等待订单流转' : '该类型暂无事件'"
    />
  </div>
</template>

<style scoped>
.event-timeline {
  max-height: 340px;
  overflow-y: auto;
  padding: 4px 2px 0 4px;
}

/* 类型筛选：吸顶在滚动区顶部，翻旧事件时筛选入口不会跑掉 */
.event-filter {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 0 0 6px;
  background: rgba(8, 20, 34, 0.94);
  backdrop-filter: blur(4px);
}

.event-filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px;
  font-size: 11px;
  line-height: 1.6;
  cursor: pointer;
  color: rgba(180, 216, 240, 0.75);
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(8, 21, 35, 0.6);
  transition: border-color 0.18s ease, color 0.18s ease, background-color 0.18s ease;
}

.event-filter-chip b {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 600;
  color: #9ff0ff;
}

.event-filter-chip:hover {
  border-color: rgba(56, 232, 255, 0.4);
  color: #7ff0ff;
}

.event-filter-chip.active {
  border-color: #38e8ff;
  background: rgba(56, 232, 255, 0.12);
  color: #d9f6ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.2);
}

/* -----------------------------------------------------------------------------
   事件流：HUD 读数卡 + 严重度发光节点 + 悬停扫描
   时间线轴线、节点、卡片描边全部由 --evt 变量驱动，严重度一眼可辨。
   ----------------------------------------------------------------------------- */
.event-timeline :deep(.ant-timeline-item) {
  --evt: 148, 170, 190;
  padding-bottom: 10px;
}

.event-timeline :deep(.sev-error) {
  --evt: 255, 96, 96;
}

.event-timeline :deep(.sev-warn) {
  --evt: 240, 169, 59;
}

.event-timeline :deep(.sev-info) {
  --evt: 56, 232, 255;
}

/* 轴线：细青色导轨，替代默认灰线 */
.event-timeline :deep(.ant-timeline-item-tail) {
  border-inline-start: 1px solid rgba(56, 232, 255, 0.16) !important;
}

/* 节点：深色芯 + 严重度描边 + 外辉光 */
.event-timeline :deep(.ant-timeline-item-head) {
  width: 10px;
  height: 10px;
  background: #041320 !important;
  border: 1px solid rgb(var(--evt)) !important;
  box-shadow:
    0 0 9px rgba(var(--evt), 0.85),
    0 0 0 2px rgba(var(--evt), 0.12);
}

/* 最新一条事件的节点持续呼吸，提示"这是刚发生的" */
.event-timeline :deep(.ant-timeline-item:first-child .ant-timeline-item-head) {
  animation: event-pulse 1.9s ease-in-out infinite;
}

@keyframes event-pulse {
  0%,
  100% {
    box-shadow:
      0 0 6px rgba(var(--evt), 0.5),
      0 0 0 2px rgba(var(--evt), 0.1);
  }
  50% {
    box-shadow:
      0 0 14px rgba(var(--evt), 1),
      0 0 0 3px rgba(var(--evt), 0.2);
  }
}

/* 事件卡片：切角面板 + 左侧严重度底光 */
.event-card {
  position: relative;
  padding: 6px 9px;
  overflow: hidden;
  border: 1px solid rgba(var(--evt), 0.2);
  background:
    linear-gradient(90deg, rgba(var(--evt), 0.08), rgba(10, 24, 40, 0.5) 60%, rgba(10, 24, 40, 0.28));
  clip-path: polygon(0 0, calc(100% - 8px) 0, 100% 8px, 100% 100%, 8px 100%, 0 calc(100% - 8px));
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.event-card:hover {
  border-color: rgba(var(--evt), 0.55);
  box-shadow: inset 0 0 18px rgba(var(--evt), 0.1);
}

/* 悬停扫描光带 */
.event-scan {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(100deg, transparent, rgba(var(--evt), 0.16), transparent);
  transform: translateX(-160%);
}

.event-card:hover .event-scan {
  animation: event-scan 1.2s linear infinite;
}

@keyframes event-scan {
  from {
    transform: translateX(-160%);
  }
  to {
    transform: translateX(260%);
  }
}

.event-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.event-type {
  margin-inline-end: 0;
  height: 20px;
  padding: 0 7px;
  line-height: 18px;
  border-radius: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  letter-spacing: 0.3px;
  clip-path: polygon(5px 0, 100% 0, 100% calc(100% - 5px), calc(100% - 5px) 100%, 0 100%, 0 5px);
}

.event-time {
  margin-left: auto;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  letter-spacing: 0.3px;
}

.event-message {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.55;
  color: #dceeff;
  word-break: break-word;
}

@media (prefers-reduced-motion: reduce) {
  .event-timeline :deep(.ant-timeline-item:first-child .ant-timeline-item-head),
  .event-card:hover .event-scan {
    animation: none;
  }
}
</style>
