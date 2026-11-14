<script setup lang="ts">
import { computed } from 'vue'
import type { Kpi } from '@/api'
import { formatDuration, formatPercent, formatRate } from '@/utils/format'

/**
 * 核心指标卡片组。
 *
 * 布局取舍：每行 4 张（`:lg="6"` → 24/6 = 4 列），窄屏自动降到 2 列。
 * 指标挑选原则是"运营每天早上第一眼要看的 8 个数"：有多少单、卡在哪、飞了几架、电够不够、准不准时、有没有告警。
 *
 * 全部数字都做 `?? 0` 兜底：后端 `non_null` 序列化会省略 null 字段，
 * 直接渲染会得到 `undefined` 并让 a-statistic 报错。
 */
const props = defineProps<{ kpi: Kpi | null }>()

interface KpiMeter {
  kind: 'segments' | 'fill' | 'cells'
  /** fill：0-100 的填充比例 */
  ratio?: number
  color?: string
  /** fill：阈值刻度位置（0-100），例如准时率的 90% 线 */
  tick?: number
  /** segments：订单状态分布 */
  segments?: Array<{ pct: number; color: string }>
  /** cells：点亮的格数（电量 0-10 格 / 冲突 0-10 格） */
  cells?: number
}

interface KpiCard {
  title: string
  value: string | number
  suffix?: string
  /** 数值的语义色：danger 用来让异常指标一眼可见 */
  tone?: 'default' | 'danger' | 'warning' | 'success'
  hint?: string
  /** 卡片底部的读数条：用真实数据画分布/占比/格数，而不是纯装饰 */
  meter: KpiMeter
  /** 异常时叠一层红色扫描，提示"这条需要处理" */
  alert?: boolean
}

/** 百分比裁剪：基数无效时返回 0，避免 NaN/Infinity 污染样式 */
function pct(value: number, base: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(base) || base <= 0) return 0
  return Math.min(100, Math.max(0, (value / base) * 100))
}

const EMPTY_METER: KpiMeter = { kind: 'fill', ratio: 0, color: 'rgba(56, 232, 255, 0.35)' }

const cards = computed<KpiCard[]>(() => {
  const k = props.kpi
  if (!k) {
    return [
      { title: '订单总数', value: '—', meter: EMPTY_METER },
      { title: '待派单', value: '—', meter: EMPTY_METER },
      { title: '配送中', value: '—', meter: EMPTY_METER },
      { title: '已送达', value: '—', meter: EMPTY_METER },
      { title: '活动无人机', value: '—', meter: EMPTY_METER },
      { title: '机队平均电量', value: '—', meter: EMPTY_METER },
      { title: '准时率', value: '—', meter: EMPTY_METER },
      { title: '未处理冲突', value: '—', meter: EMPTY_METER },
    ]
  }

  const avgBattery = Number(k.avgBatteryPct ?? 0)
  const totalOrders = Number(k.totalOrders ?? 0)
  const totalDrones = Number(k.totalDrones ?? 0)
  const pending = Number(k.pendingOrders ?? 0)
  const inFlight = Number(k.inFlightOrders ?? 0)
  const delivered = Number(k.deliveredOrders ?? 0)
  const failed = Number(k.failedOrders ?? 0)
  const active = Number(k.activeDrones ?? 0)
  const openConflicts = Number(k.openConflicts ?? 0)
  const onTimePct = pct(Number(k.onTimeRate ?? 0) * 100, 100)

  return [
    {
      title: '订单总数',
      value: k.totalOrders,
      suffix: '单',
      hint: `已完成任务 ${k.completedTasks} / 总任务 ${k.totalTasks}`,
      // 一张卡里直接画出四状态占比，读者不需要心算
      meter: {
        kind: 'segments',
        segments: [
          { pct: pct(pending, totalOrders), color: '#f0a93b' },
          { pct: pct(inFlight, totalOrders), color: '#38e8ff' },
          { pct: pct(delivered, totalOrders), color: '#2fd6a5' },
          { pct: pct(failed, totalOrders), color: '#ff6060' },
        ],
      },
    },
    {
      title: '待派单',
      value: k.pendingOrders,
      suffix: '单',
      tone: pending > 0 ? 'warning' : 'default',
      hint: pending > 0 ? '等待分配无人机' : '队列已清空',
      meter: { kind: 'fill', ratio: pct(pending, totalOrders), color: '#f0a93b' },
    },
    {
      title: '配送中',
      value: k.inFlightOrders,
      suffix: '单',
      hint: '已派单或正在飞行',
      meter: { kind: 'fill', ratio: pct(inFlight, totalOrders), color: '#38e8ff' },
    },
    {
      title: '已送达',
      value: k.deliveredOrders,
      suffix: '单',
      tone: 'success',
      hint: failed > 0 ? `失败 ${k.failedOrders} 单` : '无失败订单',
      meter: { kind: 'fill', ratio: pct(delivered, totalOrders), color: '#2fd6a5' },
    },
    {
      title: '活动无人机',
      value: `${k.activeDrones} / ${k.totalDrones}`,
      suffix: '架',
      hint: '执行任务中的机数 / 机队总数',
      meter: { kind: 'fill', ratio: pct(active, totalDrones), color: '#38e8ff' },
    },
    {
      title: '机队平均电量',
      value: formatPercent(avgBattery, 1),
      tone: avgBattery < 40 ? 'danger' : avgBattery < 60 ? 'warning' : 'success',
      hint: '低于 40% 需要安排充电',
      // 10 格电量条：一格 10%，比进度条更像电池读数
      meter: {
        kind: 'cells',
        cells: Math.round(pct(avgBattery, 100) / 10),
        color: avgBattery < 40 ? '#ff6060' : avgBattery < 60 ? '#f0a93b' : '#2fd6a5',
      },
      alert: avgBattery < 40,
    },
    {
      title: '准时率',
      value: formatRate(k.onTimeRate, 1),
      tone:
        k.onTimeRate === undefined || k.onTimeRate === null
          ? 'default'
          : k.onTimeRate >= 0.9
            ? 'success'
            : 'warning',
      hint: '已完成任务中未超期望时间的比例',
      meter: {
        kind: 'fill',
        ratio: onTimePct,
        color: onTimePct >= 90 ? '#2fd6a5' : '#f0a93b',
        tick: 90,
      },
    },
    {
      title: '未处理冲突',
      value: k.openConflicts,
      suffix: '条',
      tone: openConflicts > 0 ? 'danger' : 'default',
      hint: openConflicts > 0 ? '需要人工确认解除' : '空域态势正常',
      // 一格代表一条冲突（最多点亮 10 格），数量再多由数值本身说明
      meter: { kind: 'cells', cells: Math.min(openConflicts, 10), color: '#ff6060' },
      alert: openConflicts > 0,
    },
  ]
})

/** 平均配送时长单独展示（它是"好不好"的关键结论，不适合塞进小卡片） */
const avgDeliveryText = computed(() => formatDuration(props.kpi?.avgDeliverySeconds))

const toneClass = (tone?: KpiCard['tone']) => (tone && tone !== 'default' ? `tone-${tone}` : '')
</script>

<template>
  <div class="kpi-wrap">
    <a-row :gutter="[12, 12]">
      <a-col v-for="card in cards" :key="card.title" :xs="12" :sm="8" :md="6" :lg="6" :xl="6">
        <a-card :bordered="false" size="small" class="kpi-card" :class="toneClass(card.tone)">
          <!-- 异常指标叠一层扫描：告警不只靠颜色，而是"在动" -->
          <span v-if="card.alert" class="kpi-alert" aria-hidden="true" />
          <a-statistic :title="card.title" :value="card.value" :suffix="card.suffix" />

          <!-- 读数条：用真实指标画分布 / 占比 / 格数，而不是纯装饰 -->
          <div class="kpi-meter" :class="`meter-${card.meter.kind}`">
            <template v-if="card.meter.kind === 'segments'">
              <span
                v-for="(seg, index) in card.meter.segments || []"
                :key="index"
                class="meter-seg"
                :style="{ width: `${seg.pct}%`, background: seg.color, color: seg.color }"
              />
            </template>
            <template v-else-if="card.meter.kind === 'fill'">
              <span
                class="meter-fill"
                :class="{ empty: !card.meter.ratio }"
                :style="{
                  width: `${card.meter.ratio || 0}%`,
                  background: card.meter.color,
                  color: card.meter.color,
                }"
              />
              <span
                v-if="card.meter.tick !== undefined"
                class="meter-tick"
                :style="{ left: `${card.meter.tick}%` }"
              />
            </template>
            <template v-else>
              <span
                v-for="n in 10"
                :key="n"
                class="meter-cell"
                :class="{ on: n <= (card.meter.cells || 0) }"
                :style="{ '--cell-color': card.meter.color }"
              />
            </template>
          </div>

          <div v-if="card.hint" class="kpi-hint">{{ card.hint }}</div>
        </a-card>
      </a-col>
    </a-row>

    <div class="kpi-footnote">
      <span class="text-muted">平均配送时长</span>
      <strong>{{ avgDeliveryText }}</strong>
      <a-divider type="vertical" />
      <span class="text-muted">统计口径：已完成任务的 startedAt → finishedAt</span>
    </div>
  </div>
</template>

<style scoped>
.kpi-card {
  --tone: #38e8ff;
  position: relative;
  height: 100%;
  overflow: hidden;
  /* 切角面板 + 顶部冷光，指标卡也像一块仪表读数屏。
     ⚠️ 切角会把 box-shadow 一起裁掉，所以外投影改用 filter: drop-shadow。 */
  border-radius: 0;
  clip-path: polygon(10px 0, 100% 0, 100% calc(100% - 10px), calc(100% - 10px) 100%, 0 100%, 0 10px);
  background-image: linear-gradient(180deg, rgba(22, 50, 76, 0.55), rgba(8, 21, 35, 0.55));
  filter: drop-shadow(0 3px 8px rgba(0, 0, 0, 0.5));
  transition:
    filter 0.2s ease,
    transform 0.2s ease;
}

/* KPI 卡本身已经切角，全局的四角 L 角标会被切成残线，这里关掉 */
.kpi-card::before {
  display: none;
}

/* 右上角斜纹：机载面板的散热格栅观感，纯装饰 */
.kpi-card::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 64px;
  height: 64px;
  pointer-events: none;
  background: repeating-linear-gradient(
    135deg,
    rgba(56, 232, 255, 0.22) 0 1px,
    transparent 1px 6px
  );
  mask-image: linear-gradient(225deg, #000, transparent 72%);
  opacity: 0.6;
}

.kpi-card:hover {
  transform: translateY(-1px);
  filter: drop-shadow(0 5px 14px rgba(0, 0, 0, 0.55)) drop-shadow(0 0 10px rgba(56, 232, 255, 0.3));
}

/* 用左侧色条表达语义，比给整张卡片上色更克制、也更易读；
   色条加同色外发光，异常指标在深色底上一眼可见 */
.kpi-card.tone-danger {
  --tone: #ff6060;
  border-left: 3px solid #ff6060;
  box-shadow:
    inset 0 0 22px rgba(255, 96, 96, 0.08),
    0 4px 16px rgba(0, 0, 0, 0.45);
}

.kpi-card.tone-warning {
  --tone: #f0a93b;
  border-left: 3px solid #f0a93b;
  box-shadow:
    inset 0 0 22px rgba(240, 169, 59, 0.08),
    0 4px 16px rgba(0, 0, 0, 0.45);
}

.kpi-card.tone-success {
  --tone: #2fd6a5;
  border-left: 3px solid #2fd6a5;
  box-shadow:
    inset 0 0 22px rgba(47, 214, 165, 0.08),
    0 4px 16px rgba(0, 0, 0, 0.45);
}

/* -----------------------------------------------------------------------------
   指标读数：菱形指示灯 + 等宽大数字 + 底部数据条
   ----------------------------------------------------------------------------- */
.kpi-card :deep(.ant-statistic-title) {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
  font-size: 12px;
  letter-spacing: 0.6px;
  color: rgba(150, 199, 232, 0.75);
}

/* 标题前的菱形 LED，颜色跟随语义色 */
.kpi-card :deep(.ant-statistic-title)::before {
  content: '';
  flex: 0 0 auto;
  width: 4px;
  height: 4px;
  background: var(--tone);
  box-shadow: 0 0 7px var(--tone);
  transform: rotate(45deg);
}

.kpi-card :deep(.ant-statistic-content) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0.5px;
  color: #eafcff;
  text-shadow: 0 0 14px rgba(56, 232, 255, 0.35);
  font-variant-numeric: tabular-nums;
}

.kpi-card :deep(.ant-statistic-content-suffix) {
  margin-inline-start: 5px;
  font-size: 12px;
  font-weight: 400;
  letter-spacing: 0;
  color: rgba(150, 199, 232, 0.7);
  text-shadow: none;
}

/* 异常数值直接染色，扫一眼就能定位 */
.kpi-card.tone-danger :deep(.ant-statistic-content) {
  color: #ffc2c2;
  text-shadow: 0 0 14px rgba(255, 96, 96, 0.5);
}

.kpi-card.tone-warning :deep(.ant-statistic-content) {
  color: #ffe0b0;
  text-shadow: 0 0 14px rgba(240, 169, 59, 0.45);
}

/* 底部读数条：轨道 + 填充/分段/格子三种形态共用一套尺寸 */
.kpi-meter {
  position: relative;
  display: flex;
  gap: 2px;
  height: 5px;
  margin-top: 8px;
  overflow: hidden;
  background: rgba(56, 232, 255, 0.08);
  box-shadow: inset 0 0 4px rgba(0, 0, 0, 0.4);
}

.meter-seg {
  height: 100%;
  min-width: 0;
  box-shadow: 0 0 6px currentColor;
  transition: width 0.45s ease;
}

.meter-fill {
  position: absolute;
  inset-block: 0;
  left: 0;
  box-shadow: 0 0 8px currentColor;
  transition: width 0.45s ease;
}

/* 填充条右端的亮点，像仪表上的当前读数指针 */
.meter-fill::after {
  content: '';
  position: absolute;
  right: 0;
  top: -2px;
  bottom: -2px;
  width: 10px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.75));
  opacity: 0.5;
}

/* 0% 时不要留下一个悬空的亮点 */
.meter-fill.empty::after {
  display: none;
}

/* 阈值刻度（准时率 90% 线） */
.meter-tick {
  position: absolute;
  top: -2px;
  bottom: -2px;
  z-index: 2;
  width: 1px;
  background: rgba(234, 252, 255, 0.55);
  box-shadow: 0 0 4px rgba(234, 252, 255, 0.6);
}

/* 格数形态：电量 / 冲突 */
.meter-cell {
  flex: 1 1 0;
  height: 100%;
  background: rgba(56, 232, 255, 0.1);
  transition: background-color 0.3s ease, box-shadow 0.3s ease;
}

.meter-cell.on {
  background: var(--cell-color);
  box-shadow: 0 0 6px var(--cell-color);
}

/* 异常卡片的红色扫描带 */
.kpi-alert {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background: linear-gradient(100deg, transparent 20%, rgba(255, 96, 96, 0.16) 50%, transparent 80%);
  transform: translateX(-120%);
  animation: kpi-alert-scan 2.6s linear infinite;
}

@keyframes kpi-alert-scan {
  from {
    transform: translateX(-120%);
  }
  to {
    transform: translateX(120%);
  }
}

.kpi-hint {
  margin-top: 6px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  letter-spacing: 0.2px;
  color: rgba(150, 199, 232, 0.68);
  /* 提示文案可能为空字符串，用固定行高避免卡片高度跳动 */
  min-height: 18px;
  font-variant-numeric: tabular-nums;
}

/* 底部汇总：一条 HUD 读数带 */
.kpi-footnote {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 6px 12px;
  border: 1px solid rgba(56, 232, 255, 0.14);
  background: linear-gradient(90deg, rgba(56, 232, 255, 0.06), rgba(8, 21, 35, 0.35));
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  letter-spacing: 0.3px;
  color: rgba(150, 199, 232, 0.7);
  font-variant-numeric: tabular-nums;
}

.kpi-footnote strong {
  font-size: 14px;
  color: #9ff0ff;
  text-shadow: 0 0 10px rgba(56, 232, 255, 0.5);
}

@media (prefers-reduced-motion: reduce) {
  .kpi-alert {
    animation: none;
  }
}
</style>
