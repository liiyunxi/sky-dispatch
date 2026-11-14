<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import type { DroneFrame, Kpi } from '@/api'

/**
 * 两张小图表：订单状态分布（雷达扫描盘）+ 机队电量分布（示波器波形）。
 *
 * 视觉上刻意做成"仪器读数"而不是普通统计图：
 * 左侧环形图叠加同心刻度环 + 旋转扫描扇区，右侧用阶梯折线模拟示波器波形，
 * 外部再套一层扫描光栅，让图表区与 HUD 外壳是同一套语言。
 *
 * 为什么按需引入 ECharts 模块而不是 `import * as echarts from 'echarts'`？
 * 完整包约 1MB（压缩后 300KB+），而这里只用到「环形图 + 折线图 + 提示 + 图例 + 网格」，
 * 按需引入后体积降到几十 KB。这是前端性能优化里性价比最高的一类改动。
 *
 * 另一个关键点：**必须 resize + dispose**。
 * 容器尺寸变化（侧栏折叠、窗口缩放）时图表不会自动重绘，会留白或被拉伸；
 * 组件卸载时若只清 DOM 不 dispose，ECharts 实例和它的监听器会一直驻留内存。
 */
echarts.use([
  PieChart,
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  CanvasRenderer,
])

const props = defineProps<{
  kpi: Kpi | null
  drones: DroneFrame[]
}>()

const orderChartEl = ref<HTMLElement | null>(null)
const batteryChartEl = ref<HTMLElement | null>(null)

let orderChart: EChartsType | null = null
let batteryChart: EChartsType | null = null
let observer: ResizeObserver | null = null

/** 订单状态分布：只取互斥的四个状态，避免"总数对不上"的困惑 */
const orderSeries = computed(() => {
  const k = props.kpi
  if (!k) return []
  return [
    { name: '待派单', value: k.pendingOrders, itemStyle: { color: '#f0a93b' } },
    { name: '配送中', value: k.inFlightOrders, itemStyle: { color: '#38e8ff' } },
    { name: '已送达', value: k.deliveredOrders, itemStyle: { color: '#2fd6a5' } },
    { name: '失败', value: k.failedOrders, itemStyle: { color: '#ff6060' } },
  ]
})

/** 机队电量分布：按 20% 一档分箱，直接把"该不该安排充电"画出来 */
const batteryBins = computed(() => {
  const bins = [
    { label: '0-20%', min: 0, max: 20, count: 0, color: '#ff6060' },
    { label: '20-40%', min: 20, max: 40, count: 0, color: '#f0a93b' },
    { label: '40-60%', min: 40, max: 60, count: 0, color: '#e8d44a' },
    { label: '60-80%', min: 60, max: 80, count: 0, color: '#2fd6a5' },
    { label: '80-100%', min: 80, max: 100.01, count: 0, color: '#38e8ff' },
  ]
  props.drones.forEach((drone) => {
    const pct = Number(drone.batteryPct ?? 0)
    const bin = bins.find((item) => pct >= item.min && pct < item.max)
    if (bin) bin.count += 1
  })
  return bins
})

/** 深色 HUD 下统一的提示框外观（默认白底黑字在深色面板上非常刺眼） */
const TOOLTIP_STYLE = {
  backgroundColor: 'rgba(8, 22, 36, 0.94)',
  borderColor: 'rgba(56, 232, 255, 0.35)',
  textStyle: { color: '#d8e8fb', fontSize: 12 },
}

function renderOrderChart() {
  if (!orderChart) return
  const data = orderSeries.value
  const total = data.reduce((sum, item) => sum + item.value, 0)

  orderChart.setOption({
    backgroundColor: 'transparent',
    // 中心总数用 title 画在圆心上。
    // 不能用 pie 的 label：每个扇区都会各画一份同样的文字，导致重影。
    title: {
      text: String(total),
      subtext: '总订单',
      left: 'center',
      top: '31%',
      itemGap: 2,
      textStyle: {
        fontSize: 20,
        fontWeight: 'bold',
        color: '#eafcff',
        fontFamily: 'SFMono-Regular, Consolas, monospace',
        textShadowBlur: 12,
        textShadowColor: 'rgba(56, 232, 255, 0.85)',
      },
      subtextStyle: { fontSize: 10, color: 'rgba(150, 199, 232, 0.72)' },
    },
    tooltip: { trigger: 'item', ...TOOLTIP_STYLE, formatter: '{b}：{c} 单（{d}%）' },
    // 显式列出图例项：外圈刻度环是装饰序列，不能出现在图例里
    legend: {
      data: data.map((item) => item.name),
      bottom: 0,
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 10,
      textStyle: {
        fontSize: 11,
        color: 'rgba(159, 199, 232, 0.85)',
        fontFamily: 'SFMono-Regular, Consolas, monospace',
      },
    },
    series: [
      {
        type: 'pie',
        radius: ['48%', '66%'],
        center: ['50%', '44%'],
        startAngle: 90,
        avoidLabelOverlap: true,
        // 扇区之间留出深色缝隙，像雷达盘上的分段刻度
        itemStyle: { borderColor: '#050d18', borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        data,
      },
      {
        // 外圈刻度环：只做雷达盘外缘，不响应鼠标、不进图例
        type: 'pie',
        radius: ['70%', '70.8%'],
        center: ['50%', '44%'],
        silent: true,
        legendHoverLink: false,
        tooltip: { show: false },
        itemStyle: { color: 'rgba(56, 232, 255, 0.3)' },
        label: { show: false },
        data: [{ value: 1 }],
      },
    ],
  })
}

function renderBatteryChart() {
  if (!batteryChart) return
  const bins = batteryBins.value
  batteryChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(56, 232, 255, 0.5)' } },
      ...TOOLTIP_STYLE,
      formatter: '{b}：{c} 架',
    },
    grid: { left: 8, right: 16, top: 24, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category',
      // 波形从最左侧起笔，不留分类间隙
      boundaryGap: false,
      data: bins.map((item) => item.label),
      axisLabel: {
        fontSize: 10,
        color: 'rgba(143, 182, 214, 0.9)',
        fontFamily: 'SFMono-Regular, Consolas, monospace',
      },
      axisTick: { show: false },
      axisLine: { lineStyle: { color: 'rgba(56, 232, 255, 0.22)' } },
      splitLine: { show: true, lineStyle: { color: 'rgba(56, 232, 255, 0.06)' } },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: {
        fontSize: 10,
        color: 'rgba(143, 182, 214, 0.9)',
        fontFamily: 'SFMono-Regular, Consolas, monospace',
      },
      splitLine: { lineStyle: { type: 'dashed', color: 'rgba(56, 232, 255, 0.12)' } },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [
      {
        type: 'line',
        // 阶梯线：像示波器里的数字信号，而不是平滑的统计曲线
        step: 'middle',
        symbol: 'circle',
        symbolSize: 7,
        showSymbol: true,
        lineStyle: {
          width: 2,
          color: '#38e8ff',
          shadowBlur: 12,
          shadowColor: 'rgba(56, 232, 255, 0.9)',
        },
        itemStyle: {
          color: '#04101d',
          borderColor: '#9ff0ff',
          borderWidth: 2,
          shadowBlur: 10,
          shadowColor: 'rgba(56, 232, 255, 0.9)',
        },
        areaStyle: { color: 'rgba(56, 232, 255, 0.16)' },
        data: bins.map((item) => ({
          value: item.count,
          // 每个采样点的描边跟随电量档位颜色，低电量点自然更抢眼
          itemStyle: {
            color: '#04101d',
            borderColor: item.color,
            borderWidth: 2,
            shadowBlur: 10,
            shadowColor: item.color,
          },
        })),
        label: {
          show: true,
          position: 'top',
          fontSize: 10,
          color: '#bff4ff',
          fontFamily: 'SFMono-Regular, Consolas, monospace',
        },
      },
    ],
  })
}

onMounted(() => {
  if (orderChartEl.value) {
    orderChart = echarts.init(orderChartEl.value)
    renderOrderChart()
  }
  if (batteryChartEl.value) {
    batteryChart = echarts.init(batteryChartEl.value)
    renderBatteryChart()
  }

  // 用 ResizeObserver 监听容器而不是 window：侧栏折叠时窗口尺寸没变，但容器变了
  observer = new ResizeObserver(() => {
    orderChart?.resize()
    batteryChart?.resize()
  })
  if (orderChartEl.value) observer.observe(orderChartEl.value)
  if (batteryChartEl.value) observer.observe(batteryChartEl.value)
})

onUnmounted(() => {
  observer?.disconnect()
  observer = null
  orderChart?.dispose()
  batteryChart?.dispose()
  orderChart = null
  batteryChart = null
})

watch(orderSeries, renderOrderChart, { deep: true })
watch(batteryBins, renderBatteryChart, { deep: true })
</script>

<template>
  <div class="charts">
    <div class="chart-block">
      <div class="chart-title">订单状态分布</div>
      <!-- 雷达盘：同心刻度环（静态）+ 旋转扫描扇区（动态） -->
      <div class="scope scope--radar">
        <span class="scope-rings" aria-hidden="true" />
        <span class="scope-sweep" aria-hidden="true" />
        <div ref="orderChartEl" class="chart-canvas"></div>
      </div>
    </div>
    <a-divider class="chart-divider" />
    <div class="chart-block">
      <div class="chart-title">
        机队电量分布
        <span class="text-muted chart-title-hint">低于 40% 建议安排充电</span>
      </div>
      <!-- 示波器：方格光栅 + 横向扫描线 -->
      <div class="scope scope--wave">
        <span class="scope-beam" aria-hidden="true" />
        <div ref="batteryChartEl" class="chart-canvas"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.charts {
  display: flex;
  flex-direction: column;
}

.chart-block {
  width: 100%;
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.4px;
  color: rgba(216, 232, 251, 0.88);
  margin-bottom: 4px;
}

.chart-title-hint {
  margin-left: 6px;
  font-weight: 400;
  font-size: 11px;
}

/* -----------------------------------------------------------------------------
   仪表屏幕：雷达盘 / 示波器共用外框（切角 + 四角刻度 + 扫描层）
   ----------------------------------------------------------------------------- */
.scope {
  position: relative;
  height: 190px;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.16);
  background-color: rgba(4, 14, 24, 0.55);
}

/* 四个角的 L 形刻度，和卡片、按钮同一套 HUD 语言 */
.scope::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 5;
  pointer-events: none;
  --c: rgba(56, 232, 255, 0.55);
  background:
    linear-gradient(var(--c), var(--c)) left top / 10px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) left top / 1px 10px no-repeat,
    linear-gradient(var(--c), var(--c)) right top / 10px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) right top / 1px 10px no-repeat,
    linear-gradient(var(--c), var(--c)) left bottom / 10px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) left bottom / 1px 10px no-repeat,
    linear-gradient(var(--c), var(--c)) right bottom / 10px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) right bottom / 1px 10px no-repeat;
}

/* ECharts 画布必须占满仪表屏幕，同时压住背后的扫描层 */
.chart-canvas {
  position: relative;
  z-index: 2;
  width: 100%;
  height: 100%;
}

/* ---- 雷达盘 ---- */
.scope--radar {
  background: radial-gradient(circle at 50% 44%, rgba(56, 232, 255, 0.09), transparent 68%);
}

/* 同心刻度环放在正方形图层里，保证是正圆而不是椭圆 */
.scope-rings {
  position: absolute;
  left: 50%;
  top: 44%;
  width: 150%;
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  pointer-events: none;
  z-index: 0;
  background-image: repeating-radial-gradient(
    circle at 50% 50%,
    rgba(56, 232, 255, 0.12) 0 1px,
    transparent 1px 26px
  );
}

/* 旋转扫描扇区：雷达"扫一圈"的核心动效 */
.scope-sweep {
  position: absolute;
  left: 50%;
  top: 44%;
  width: 130%;
  aspect-ratio: 1;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  /* 扇区亮度压得比较低：它只负责"在扫"的感觉，不能盖住图例和读数 */
  background: conic-gradient(
    from 0deg,
    rgba(56, 232, 255, 0.18),
    rgba(56, 232, 255, 0.04) 14%,
    transparent 30%
  );
  animation: radar-sweep 5s linear infinite;
  mix-blend-mode: screen;
  pointer-events: none;
  z-index: 1;
}

@keyframes radar-sweep {
  from {
    transform: translate(-50%, -50%) rotate(0deg);
  }
  to {
    transform: translate(-50%, -50%) rotate(360deg);
  }
}

/* ---- 示波器 ---- */
.scope--wave {
  background-image:
    linear-gradient(rgba(56, 232, 255, 0.09) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 232, 255, 0.09) 1px, transparent 1px);
  background-size: 24px 24px;
}

/* 横向扫描线：像示波器的时基扫描，从左走到右 */
.scope-beam {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 2px;
  z-index: 3;
  pointer-events: none;
  background: linear-gradient(180deg, transparent, rgba(56, 232, 255, 0.85), transparent);
  box-shadow: 0 0 10px rgba(56, 232, 255, 0.8);
  animation: scope-beam 4.2s linear infinite;
}

@keyframes scope-beam {
  from {
    left: 0;
  }
  to {
    left: calc(100% - 2px);
  }
}

.chart-divider {
  margin: 8px 0;
}

@media (prefers-reduced-motion: reduce) {
  .scope-sweep,
  .scope-beam {
    animation: none;
  }
}
</style>
