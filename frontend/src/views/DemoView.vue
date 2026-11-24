<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, type Component } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import {
  ExperimentOutlined,
  GlobalOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  StepBackwardOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import {
  scenarioApi,
  simulationApi,
  type EventLogItem,
  type EventSeverity,
  type ScenarioInfo,
  type ScenarioKey,
  type ScenarioResult,
  type SimulationReplay,
} from '@/api'
import { useTelemetry } from '@/store/telemetry'
import {
  EVENT_SEVERITY_COLOR,
  RUN_STATUS_TEXT,
  eventTypeText,
  formatDuration,
} from '@/utils/format'

/**
 * 演示中心。
 *
 * 两块能力：
 *
 *  1. **场景编排**：高峰订单 / 故障风暴 / 临时禁飞 / 补能压力。
 *     剧本不是"改数据库造假数据"，而是走正常业务路径（建单、注入故障、建临时空域），
 *     因此订单链路、事件流、基础设施页看到的都是真实发生的数据。
 *  2. **运行事件回放**：按仿真时间回放某一轮的事件，支持播放/倍速/拖动时间轴。
 *     当前只回放事件，不重建每一拍的坐标（遥测历史不在本阶段留存）。
 */

const router = useRouter()
const {
  currentRun,
  runtimeState,
  runStatus,
  simTimeS,
  tickCount,
  refreshSnapshot,
  refreshRun,
  loadRuntimeState,
  loadRoutes,
  loadZones,
} = useTelemetry()

const scenarios = ref<ScenarioInfo[]>([])
const catalogLoading = ref(false)
const scenarioLoading = ref<ScenarioKey | null>(null)
const lastResult = ref<ScenarioResult | null>(null)

const replay = ref<SimulationReplay | null>(null)
const replayLoading = ref(false)
const cursor = ref(0)
const playing = ref(false)
const speed = ref(4)
const speedOptions = [1, 2, 4, 8].map((value) => ({ value, label: `${value}x` }))
let playbackTimer: number | null = null

const runStatusText = computed(() => RUN_STATUS_TEXT[runStatus.value] ?? runStatus.value)
const autoDispatch = computed(() => runtimeState.value?.autoDispatchEnabled ?? false)

const maxSim = computed(() => Math.max(1, Math.round(Number(replay.value?.maxSimTimeS ?? 0))))

// ---------------------------------------------------------------------------
// 场景
// ---------------------------------------------------------------------------

const SCENARIO_ICONS: Record<ScenarioKey, Component> = {
  PEAK_DEMAND: ExperimentOutlined,
  FAULT_STORM: WarningOutlined,
  TEMP_NO_FLY: GlobalOutlined,
  CHARGING_SURGE: ThunderboltOutlined,
}

function scenarioIcon(key: ScenarioKey): Component {
  return SCENARIO_ICONS[key] ?? ExperimentOutlined
}

async function loadCatalog(): Promise<void> {
  catalogLoading.value = true
  try {
    scenarios.value = await scenarioApi.catalog()
  } catch {
    /* 已提示 */
  } finally {
    catalogLoading.value = false
  }
}

async function triggerScenario(info: ScenarioInfo): Promise<void> {
  scenarioLoading.value = info.key
  try {
    const result = await scenarioApi.trigger(info.key)
    lastResult.value = result
    message.success(result.message || `已触发「${info.title}」`)
    // 剧本会改变订单/无人机/空域，立即刷新态势与地图
    await Promise.all([refreshSnapshot(), loadRoutes(), loadZones(), refreshRun()])
  } catch {
    /* client.ts 已提示 */
  } finally {
    scenarioLoading.value = null
  }
}

// ---------------------------------------------------------------------------
// 回放
// ---------------------------------------------------------------------------

async function loadReplay(): Promise<void> {
  replayLoading.value = true
  try {
    await refreshRun()
    const run = currentRun.value
    if (!run) {
      replay.value = null
      cursor.value = 0
      return
    }
    const data = await simulationApi.replay(run.id)
    replay.value = data
    cursor.value = Math.round(Number(data.maxSimTimeS ?? 0))
  } catch {
    /* 已提示 */
  } finally {
    replayLoading.value = false
  }
}

function stopPlayback(): void {
  if (playbackTimer !== null) {
    window.clearInterval(playbackTimer)
    playbackTimer = null
  }
  playing.value = false
}

function startPlayback(): void {
  if (!replay.value) return
  if (cursor.value >= maxSim.value) cursor.value = 0
  playing.value = true
  playbackTimer = window.setInterval(() => {
    cursor.value = Math.min(maxSim.value, cursor.value + speed.value * 0.3)
    if (cursor.value >= maxSim.value) stopPlayback()
  }, 300)
}

function togglePlayback(): void {
  if (playing.value) stopPlayback()
  else startPlayback()
}

function restartPlayback(): void {
  stopPlayback()
  cursor.value = 0
  startPlayback()
}

function seekTo(index: number): void {
  stopPlayback()
  cursor.value = Math.round((index / TIMELINE_BINS) * maxSim.value)
}

/** 事件按仿真时间过滤；倒序展示，最新发生的在最上面 */
const visibleEvents = computed<EventLogItem[]>(() => {
  const all = replay.value?.events ?? []
  return all
    .filter((event) => Number(event.simTimeS ?? 0) <= cursor.value + 0.001)
    .slice()
    .sort((a, b) => {
      const diff = Number(b.simTimeS ?? 0) - Number(a.simTimeS ?? 0)
      return diff !== 0 ? diff : b.id - a.id
    })
    .slice(0, 120)
})

const TIMELINE_BINS = 60

const timeline = computed(() => {
  const counts = new Array<number>(TIMELINE_BINS).fill(0)
  const events = replay.value?.events ?? []
  for (const event of events) {
    const time = Number(event.simTimeS ?? 0)
    const index = Math.min(TIMELINE_BINS - 1, Math.max(0, Math.floor((time / maxSim.value) * TIMELINE_BINS)))
    counts[index] += 1
  }
  const peak = Math.max(1, ...counts)
  return counts.map((count, index) => ({
    index,
    count,
    height: count === 0 ? 2 : Math.max(8, Math.round((count / peak) * 100)),
  }))
})

const cursorPercent = computed(() => `${Math.min(100, (cursor.value / maxSim.value) * 100)}%`)

function simClock(seconds: number): string {
  const total = Math.max(0, Math.round(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(secs).padStart(2, '0')
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`
}

const SEVERITY_HEX: Record<EventSeverity, string> = {
  DEBUG: '#5b7c99',
  INFO: '#38e8ff',
  WARN: '#f0a93b',
  ERROR: '#ff6060',
}

function severityHex(severity: EventSeverity): string {
  return SEVERITY_HEX[severity] ?? '#5b7c99'
}

function goOrderTrace(orderId?: number): void {
  if (!orderId) return
  void router.push({ path: '/orders', query: { traceOrderId: String(orderId) } })
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(async () => {
  await Promise.all([loadCatalog(), loadRuntimeState(), loadReplay()])
})

onUnmounted(stopPlayback)
</script>

<template>
  <div class="demo-view">
    <!-- ------------------------------ 演示态势 ------------------------------ -->
    <section class="demo-hero">
      <div class="hero-head">
        <div>
          <div class="hero-title">
            <span class="hero-led" aria-hidden="true" />
            演示中心
            <span class="hero-live">LIVE</span>
          </div>
          <div class="hero-sub">一键剧本触发 · 运行事件回放 · 剧本走真实业务路径</div>
        </div>
        <a-button size="small" :loading="replayLoading" @click="loadReplay">
          <template #icon><ReloadOutlined /></template>
          刷新回放
        </a-button>
      </div>

      <div class="hero-metrics">
        <div class="metric">
          <em>当前运行</em>
          <strong>{{ currentRun ? `#${currentRun.id}` : '—' }}</strong>
        </div>
        <div class="metric">
          <em>运行状态</em>
          <strong class="small">{{ runStatusText }}</strong>
        </div>
        <div class="metric">
          <em>仿真时长</em>
          <strong class="small">{{ formatDuration(simTimeS) }}</strong>
        </div>
        <div class="metric">
          <em>拍数</em>
          <strong>{{ tickCount }}<i>拍</i></strong>
        </div>
        <div class="metric">
          <em>自动派单</em>
          <strong class="small" :class="{ danger: !autoDispatch }">{{ autoDispatch ? '开启' : '关闭' }}</strong>
        </div>
        <div class="metric">
          <em>可回放事件</em>
          <strong>{{ replay?.events.length ?? 0 }}<i>条</i></strong>
        </div>
      </div>

      <div class="hero-note">
        <InfoCircleOutlined />
        <span>
          剧本不是伪造数据：订单走正常创建、故障走正常注入、临时禁飞区走正常空域台账，
          因此订单链路、事件时间线与基础设施页都能看到真实影响。
        </span>
      </div>
    </section>

    <!-- ------------------------------ 场景编排 ------------------------------ -->
    <section class="scenario-section">
      <div class="section-head">
        <span class="section-title">场景编排</span>
        <span class="section-note">触发后立即生效，并写入一条 SCENARIO 事件</span>
      </div>

      <a-spin :spinning="catalogLoading">
        <div class="scenario-grid">
          <article
            v-for="info in scenarios"
            :key="info.key"
            class="scenario-card"
            :class="`key-${info.key.toLowerCase()}`"
          >
            <header class="scenario-head">
              <span class="scenario-icon">
                <component :is="scenarioIcon(info.key)" />
              </span>
              <span class="scenario-title">{{ info.title }}</span>
              <a-tag class="scenario-key text-mono">{{ info.key }}</a-tag>
            </header>

            <p class="scenario-desc">{{ info.description }}</p>

            <div class="scenario-meta">
              <span><em>影响</em>{{ info.impact }}</span>
              <span class="scenario-risk"><em>风险</em>{{ info.risk }}</span>
            </div>

            <a-popconfirm
              title="确认触发该演示剧本？触发后会立即改变运行数据。"
              ok-text="触发"
              cancel-text="取消"
              @confirm="triggerScenario(info)"
            >
              <a-button
                type="primary"
                size="small"
                class="scenario-button"
                :loading="scenarioLoading === info.key"
                :disabled="scenarioLoading !== null"
              >
                触发剧本
              </a-button>
            </a-popconfirm>
          </article>
        </div>
      </a-spin>

      <div v-if="lastResult" class="scenario-result" :class="`key-${lastResult.key.toLowerCase()}`">
        <div class="result-head">
          <span class="result-title">最近一次：{{ lastResult.title }}</span>
          <span class="result-time text-mono">T+{{ simClock(Number(lastResult.simTimeS ?? 0)) }}</span>
        </div>
        <div class="result-message">{{ lastResult.message }}</div>
        <ul v-if="lastResult.details.length" class="result-details">
          <li v-for="(detail, index) in lastResult.details" :key="index">{{ detail }}</li>
        </ul>
      </div>
    </section>

    <!-- ------------------------------ 运行事件回放 ------------------------------ -->
    <section class="replay-section">
      <div class="section-head">
        <span class="section-title">运行事件回放</span>
        <span class="section-note">
          <template v-if="replay">
            运行 #{{ replay.runId }} · {{ replay.events.length }} 条事件 · 仅回放事件，不重建每拍坐标
          </template>
          <template v-else>选择当前运行后回放</template>
        </span>
      </div>

      <a-spin :spinning="replayLoading">
        <div v-if="replay" class="replay-body">
          <div class="replay-controls">
            <a-button type="primary" shape="circle" class="play-button" @click="togglePlayback">
              <template #icon>
                <PauseCircleOutlined v-if="playing" />
                <PlayCircleOutlined v-else />
              </template>
            </a-button>
            <a-button size="small" @click="restartPlayback">
              <template #icon><StepBackwardOutlined /></template>
              从头
            </a-button>
            <a-select v-model:value="speed" size="small" :options="speedOptions" class="speed-select" />
            <span class="replay-clock text-mono">
              T+{{ simClock(cursor) }} / T+{{ simClock(maxSim) }}
            </span>
            <span class="replay-count">
              已发生 {{ visibleEvents.length }} 条（展示最近 120 条）
            </span>
          </div>

          <div class="replay-timeline">
            <button
              v-for="bar in timeline"
              :key="bar.index"
              type="button"
              class="tl-bar"
              :class="{ past: (bar.index / 60) * maxSim <= cursor }"
              :style="{ height: `${bar.height}%` }"
              :title="`T+${simClock((bar.index / 60) * maxSim)} · ${bar.count} 条事件`"
              @click="seekTo(bar.index)"
            />
            <span class="tl-cursor" :style="{ left: cursorPercent }" />
          </div>
          <div class="timeline-legend">
            <span>事件密度</span>
            <span>点击时间轴可跳转</span>
          </div>

          <div v-if="visibleEvents.length" class="event-list">
            <div
              v-for="event in visibleEvents"
              :key="event.id"
              class="event-row"
              :class="`sev-${event.severity.toLowerCase()}`"
            >
              <span class="event-time text-mono">T+{{ simClock(Number(event.simTimeS ?? 0)) }}</span>
              <span class="event-dot" :style="{ background: severityHex(event.severity) }" />
              <a-tag :color="EVENT_SEVERITY_COLOR[event.severity]" class="event-sev">
                {{ event.severity }}
              </a-tag>
              <span class="event-type">{{ eventTypeText(event.eventType) }}</span>
              <span class="event-message">{{ event.message }}</span>
              <button
                v-if="event.orderId"
                type="button"
                class="event-link"
                @click="goOrderTrace(event.orderId)"
              >
                <LinkOutlined />
                订单 #{{ event.orderId }}
              </button>
            </div>
          </div>
          <a-empty v-else description="当前时间点之前还没有事件；点播放或拖动时间轴" />
        </div>
        <a-empty v-else class="replay-empty" description="还没有可回放的运行；请先新建/继续一轮仿真" />
      </a-spin>
    </section>
  </div>
</template>

<style scoped>
.demo-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* =============================================================================
   顶部态势
   ============================================================================= */
.demo-hero {
  position: relative;
  padding: 14px 16px 12px;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.18);
  border-radius: 8px;
  background:
    radial-gradient(680px 200px at 12% -30%, rgba(56, 232, 255, 0.14), transparent 70%),
    linear-gradient(180deg, rgba(13, 32, 50, 0.92), rgba(6, 17, 29, 0.92));
  box-shadow:
    inset 0 0 0 1px rgba(56, 232, 255, 0.04),
    0 10px 26px rgba(0, 0, 0, 0.4);
}

.demo-hero::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 26%;
  height: 1px;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  animation: demo-scan 8s linear infinite;
}

@keyframes demo-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(480%);
  }
}

.hero-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-bottom: 8px;
}

.hero-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.6px;
  color: #e9fbff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.3);
}

.hero-led {
  width: 6px;
  height: 6px;
  background: #38e8ff;
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.95);
  transform: rotate(45deg);
  animation: hero-led 1.8s ease-in-out infinite;
}

@keyframes hero-led {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.hero-live {
  padding: 0 6px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #8bffdc;
  border: 1px solid rgba(47, 214, 165, 0.5);
  background: rgba(47, 214, 165, 0.1);
  clip-path: polygon(4px 0, 100% 0, 100% calc(100% - 4px), calc(100% - 4px) 100%, 0 100%, 0 4px);
}

.hero-sub {
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.62);
}

.hero-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  margin-bottom: 8px;
}

.metric {
  display: flex;
  flex-direction: column;
  min-width: 62px;
}

.metric em {
  font-size: 10px;
  font-style: normal;
  letter-spacing: 0.3px;
  color: rgba(150, 199, 232, 0.55);
}

.metric strong {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 17px;
  font-weight: 600;
  color: #d8f7ff;
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.35);
  font-variant-numeric: tabular-nums;
}

.metric strong.small {
  font-size: 12px;
  font-weight: 500;
}

.metric strong i {
  margin-left: 2px;
  font-size: 10px;
  font-style: normal;
  font-weight: 400;
  color: rgba(150, 199, 232, 0.55);
  text-shadow: none;
}

.metric strong.danger {
  color: #ffb4b4;
}

.hero-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 5px 9px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba(159, 240, 255, 0.78);
  border: 1px solid rgba(56, 232, 255, 0.16);
  background: rgba(56, 232, 255, 0.04);
}

/* =============================================================================
   场景编排
   ============================================================================= */
.scenario-section,
.replay-section {
  padding: 12px 14px;
  border: 1px solid rgba(56, 232, 255, 0.14);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(11, 28, 45, 0.86), rgba(6, 17, 29, 0.86));
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.36);
}

.section-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-bottom: 10px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: #d9f6ff;
}

.section-title::before {
  content: '';
  width: 3px;
  height: 12px;
  background: #38e8ff;
  box-shadow: 0 0 8px rgba(56, 232, 255, 0.9);
}

.section-note {
  font-size: 11px;
  color: rgba(150, 199, 232, 0.5);
}

.scenario-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
}

.scenario-card {
  --scenario-tone: #38e8ff;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 11px 13px;
  border: 1px solid rgba(56, 232, 255, 0.15);
  border-left: 3px solid var(--scenario-tone);
  border-radius: 8px;
  background: rgba(8, 21, 35, 0.55);
  transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}

.scenario-card:hover {
  transform: translateY(-2px);
  border-color: rgba(56, 232, 255, 0.4);
  box-shadow: 0 10px 22px rgba(0, 0, 0, 0.4);
}

.scenario-card.key-peak_demand {
  --scenario-tone: #38e8ff;
}
.scenario-card.key-fault_storm {
  --scenario-tone: #ff6060;
}
.scenario-card.key-temp_no_fly {
  --scenario-tone: #fa8c16;
}
.scenario-card.key-charging_surge {
  --scenario-tone: #f0a93b;
}

.scenario-head {
  display: flex;
  align-items: center;
  gap: 7px;
}

.scenario-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  font-size: 13px;
  color: var(--scenario-tone);
  border: 1px solid color-mix(in srgb, var(--scenario-tone) 40%, transparent);
  background: color-mix(in srgb, var(--scenario-tone) 10%, transparent);
}

.scenario-title {
  font-size: 13px;
  font-weight: 600;
  color: #e3f7ff;
}

.scenario-key {
  margin-left: auto;
  font-size: 9px;
  opacity: 0.65;
}

.scenario-desc {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: rgba(216, 240, 255, 0.78);
}

.scenario-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 10px;
  line-height: 1.5;
  color: rgba(150, 199, 232, 0.6);
}

.scenario-meta em {
  margin-right: 4px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.42);
}

.scenario-risk {
  color: rgba(240, 169, 59, 0.82);
}

.scenario-button {
  align-self: flex-start;
  margin-top: auto;
}

.scenario-result {
  margin-top: 10px;
  padding: 8px 11px;
  border: 1px solid rgba(56, 232, 255, 0.18);
  background: rgba(56, 232, 255, 0.05);
}

.scenario-result.key-fault_storm {
  border-color: rgba(255, 96, 96, 0.28);
  background: rgba(255, 96, 96, 0.06);
}

.scenario-result.key-temp_no_fly {
  border-color: rgba(250, 140, 22, 0.28);
  background: rgba(250, 140, 22, 0.06);
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 3px;
}

.result-title {
  font-size: 12px;
  font-weight: 600;
  color: #d9f6ff;
}

.result-time {
  font-size: 10px;
  color: rgba(159, 240, 255, 0.7);
}

.result-message {
  font-size: 11px;
  color: rgba(216, 240, 255, 0.82);
}

.result-details {
  margin: 5px 0 0;
  padding-left: 16px;
  font-size: 10px;
  line-height: 1.6;
  color: rgba(150, 199, 232, 0.68);
}

/* =============================================================================
   回放
   ============================================================================= */
.replay-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.replay-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.play-button {
  box-shadow: 0 0 14px rgba(56, 232, 255, 0.25);
}

.speed-select {
  width: 84px;
}

.replay-clock {
  margin-left: 6px;
  font-size: 12px;
  color: #9ff0ff;
}

.replay-count {
  margin-left: auto;
  font-size: 10px;
  color: rgba(150, 199, 232, 0.5);
}

.replay-timeline {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 56px;
  padding: 0 2px;
  border: 1px solid rgba(56, 232, 255, 0.12);
  background:
    linear-gradient(180deg, rgba(56, 232, 255, 0.03), rgba(6, 17, 29, 0.2)),
    repeating-linear-gradient(90deg, rgba(56, 232, 255, 0.06) 0 1px, transparent 1px 10%);
}

.tl-bar {
  flex: 1 1 0;
  min-width: 2px;
  padding: 0;
  cursor: pointer;
  border: 0;
  background: rgba(56, 232, 255, 0.14);
  transition: background-color 0.15s ease;
}

.tl-bar.past {
  background: linear-gradient(180deg, #38e8ff, #1677ff);
  box-shadow: 0 0 6px rgba(56, 232, 255, 0.45);
}

.tl-bar:hover {
  background: #7ff0ff;
}

.tl-cursor {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  pointer-events: none;
  background: #ffe0b0;
  box-shadow: 0 0 8px rgba(255, 224, 176, 0.9);
  transform: translateX(-1px);
}

.timeline-legend {
  display: flex;
  justify-content: space-between;
  font-size: 9px;
  color: rgba(150, 199, 232, 0.4);
}

.event-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 360px;
  overflow-y: auto;
  padding-right: 4px;
}

.event-row {
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 4px 7px;
  font-size: 11px;
  border-left: 2px solid rgba(56, 232, 255, 0.2);
  background: rgba(8, 21, 35, 0.5);
}

.event-row.sev-warn {
  border-left-color: #f0a93b;
  background: rgba(240, 169, 59, 0.05);
}

.event-row.sev-error {
  border-left-color: #ff6060;
  background: rgba(255, 96, 96, 0.06);
}

.event-time {
  flex: 0 0 auto;
  font-size: 10px;
  color: rgba(159, 240, 255, 0.75);
}

.event-dot {
  flex: 0 0 auto;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  box-shadow: 0 0 6px currentColor;
}

.event-sev {
  flex: 0 0 auto;
  margin-inline-end: 0;
  transform: scale(0.86);
  transform-origin: left center;
}

.event-type {
  flex: 0 0 auto;
  font-weight: 600;
  color: #d9f6ff;
}

.event-message {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(216, 240, 255, 0.7);
}

.event-link {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  font-size: 10px;
  cursor: pointer;
  color: #9fd9f5;
  border: 0;
  background: transparent;
}

.event-link:hover {
  color: #7ff0ff;
}

.replay-empty {
  padding: 40px 0;
}

@media (max-width: 900px) {
  .replay-count {
    margin-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .demo-hero::after,
  .hero-led {
    animation: none;
  }
}
</style>
