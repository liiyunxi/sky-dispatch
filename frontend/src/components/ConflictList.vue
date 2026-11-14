<script setup lang="ts">
import { CONFLICT_SEVERITY_COLOR, CONFLICT_SEVERITY_TEXT, CONFLICT_TYPE_TEXT, formatFromNow, formatDistance } from '@/utils/format'
import type { ConflictView } from '@/api'

/**
 * 冲突告警列表。
 *
 * 冲突是**只检测、不自动消解**的（见 docs/06-algorithm-notes.md 第 11 节），
 * 所以这个列表必须支持人工"解除"，否则告警会一直堆着，运营很快就无视它了。
 * 这也符合真实运营流程：冲突处置是需要人确认的决策，而不是静默处理。
 */
defineProps<{
  conflicts: ConflictView[]
  loading?: boolean
}>()

const emit = defineEmits<{ (e: 'resolve', id: number): void }>()

/** 严重度排序权重：让最危险的排在最上面 */
const severityWeight: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }
</script>

<template>
  <a-list
    :data-source="[...conflicts].sort((a, b) => (severityWeight[a.severity] ?? 9) - (severityWeight[b.severity] ?? 9))"
    :loading="loading"
    size="small"
    class="conflict-list"
  >
    <template #renderItem="{ item }">
      <a-list-item
        class="conflict-item"
        :class="`sev-${String(item.severity).toLowerCase()}`"
      >
        <!-- 悬停扫描光带：和按钮/侧栏同一套动效语言 -->
        <span class="conflict-scan" aria-hidden="true" />
        <div class="conflict-body">
          <div class="conflict-head">
            <a-tag :color="CONFLICT_SEVERITY_COLOR[item.severity as keyof typeof CONFLICT_SEVERITY_COLOR]">
              {{ CONFLICT_SEVERITY_TEXT[item.severity as keyof typeof CONFLICT_SEVERITY_TEXT] ?? item.severity }}
            </a-tag>
            <a-tag>{{ CONFLICT_TYPE_TEXT[item.conflictType as keyof typeof CONFLICT_TYPE_TEXT] ?? item.conflictType }}</a-tag>
            <span class="text-muted conflict-time">{{ formatFromNow(item.detectedAt) }}</span>
          </div>

          <div class="conflict-message">{{ item.message ?? '（无描述）' }}</div>

          <div class="conflict-meta text-muted">
            <span v-if="item.horizontalDistanceM !== undefined">
              水平间距 {{ formatDistance(item.horizontalDistanceM) }}
            </span>
            <span v-if="item.verticalDistanceM !== undefined">
              · 垂直间距 {{ formatDistance(item.verticalDistanceM) }}
            </span>
            <!-- 0 秒表示已经发生，不再显示"预计 0.0s 后触发"这种误导性提示 -->
            <span v-if="item.timeToConflictS !== undefined && Number(item.timeToConflictS) > 0.5">
              · 预计 {{ Number(item.timeToConflictS).toFixed(1) }}s 后触发
            </span>
          </div>

          <div class="conflict-actions">
            <a-button type="link" size="small" @click="emit('resolve', item.id)">标记已处置</a-button>
          </div>
        </div>
      </a-list-item>
    </template>

    <template #emptyText>
      <a-empty description="当前无未处理冲突，空域态势正常" />
    </template>
  </a-list>
</template>

<style scoped>
.conflict-list {
  /* 限高滚动：告警多的时候不能把页面撑到几屏长 */
  max-height: 340px;
  overflow-y: auto;
  padding-right: 2px;
}

/* -----------------------------------------------------------------------------
   告警条：切角面板 + 左缘严重度光轨 + 悬停扫描
   严重度通过 --sev 变量驱动描边、底光与光轨颜色，结构文案完全不变。
   ----------------------------------------------------------------------------- */
.conflict-item.ant-list-item {
  --sev: 56, 232, 255;
  position: relative;
  margin: 6px 0;
  padding: 8px 10px !important;
  overflow: hidden;
  border: 1px solid rgba(var(--sev), 0.28) !important;
  background:
    linear-gradient(90deg, rgba(var(--sev), 0.1), rgba(10, 24, 40, 0.55) 55%, rgba(10, 24, 40, 0.32));
  clip-path: polygon(0 0, calc(100% - 9px) 0, 100% 9px, 100% 100%, 9px 100%, 0 calc(100% - 9px));
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.conflict-item.sev-critical {
  --sev: 255, 96, 96;
}

.conflict-item.sev-high {
  --sev: 240, 169, 59;
}

.conflict-item.sev-medium {
  --sev: 56, 232, 255;
}

.conflict-item.sev-low {
  --sev: 148, 170, 190;
}

/* 左缘严重度光轨：CRITICAL 时呼吸闪烁 */
.conflict-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  bottom: 7px;
  width: 2px;
  background: linear-gradient(180deg, transparent, rgb(var(--sev)) 30%, rgba(var(--sev), 0.6) 70%, transparent);
  box-shadow: 0 0 10px rgba(var(--sev), 0.9);
}

.conflict-item.sev-critical::before {
  animation: conflict-led 1.4s ease-in-out infinite;
}

@keyframes conflict-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.conflict-item:hover {
  border-color: rgba(var(--sev), 0.62) !important;
  box-shadow: inset 0 0 22px rgba(var(--sev), 0.12);
}

/* 悬停扫描光带：从告警条左侧扫到右侧 */
.conflict-scan {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(100deg, transparent, rgba(var(--sev), 0.18), transparent);
  transform: translateX(-160%);
}

.conflict-item:hover .conflict-scan {
  animation: conflict-scan 1.2s linear infinite;
}

@keyframes conflict-scan {
  from {
    transform: translateX(-160%);
  }
  to {
    transform: translateX(260%);
  }
}

.conflict-body {
  width: 100%;
}

.conflict-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 标签改成 HUD 读数条：直角切角 + 等宽字 */
.conflict-head :deep(.ant-tag) {
  height: 20px;
  margin: 0;
  padding: 0 7px;
  line-height: 18px;
  border-radius: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  letter-spacing: 0.3px;
  clip-path: polygon(5px 0, 100% 0, 100% calc(100% - 5px), calc(100% - 5px) 100%, 0 100%, 0 5px);
}

.conflict-time {
  margin-left: auto;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  letter-spacing: 0.3px;
}

.conflict-message {
  margin: 5px 0 3px;
  font-size: 13px;
  line-height: 1.55;
  color: #e2f1ff;
  /* 冲突描述是中文长句，允许换行但不要溢出 */
  word-break: break-word;
}

.conflict-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.72);
}

.conflict-actions {
  margin-top: 2px;
}

.conflict-actions :deep(.ant-btn-link) {
  height: auto;
  padding: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  color: rgba(120, 232, 255, 0.9);
}

.conflict-actions :deep(.ant-btn-link:hover) {
  color: #bff4ff;
  text-shadow: 0 0 8px rgba(56, 232, 255, 0.7);
}

@media (prefers-reduced-motion: reduce) {
  .conflict-item::before,
  .conflict-item:hover .conflict-scan {
    animation: none;
  }
}
</style>
