<script setup lang="ts">
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
 */
defineProps<{
  events: SkyEvent[]
}>()
</script>

<template>
  <div class="event-timeline">
    <a-timeline v-if="events.length > 0">
      <a-timeline-item
        v-for="event in events"
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

    <a-empty v-else description="暂无事件，等待订单流转" />
  </div>
</template>

<style scoped>
.event-timeline {
  max-height: 340px;
  overflow-y: auto;
  padding: 4px 2px 0 4px;
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
