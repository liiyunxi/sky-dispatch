<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive } from 'vue'

/**
 * 全局 HUD 滚动条（自制，不使用浏览器原生滚动条）。
 *
 * 原生滚动条已在 global.css 中通过 scrollbar-width:none / ::-webkit-scrollbar{display:none}
 * 完全隐藏；这里用 fixed 定位的 DOM 轨道与滑块接管视觉：
 *  - 自动发现可滚动容器：挂载时扫描已知容器，之后靠 scroll / pointerover 捕获 + 定时补扫；
 *  - 滑块长度与位置按 clientHeight/scrollHeight、scrollTop 实时计算；
 *  - 支持拖拽滑块（只改 scrollTop/scrollLeft，滚轮、触控板、键盘滚动行为不变）。
 *
 * 为什么不直接用 ::-webkit-scrollbar 改颜色：
 * Chrome 121+ 同时支持标准属性与伪元素，两者并存时标准属性会覆盖伪元素样式，
 * 视觉上仍然像"浏览器自带滚动条"。DOM 自绘才能真正做到外观可控。
 */

interface BarState {
  key: number
  vVisible: boolean
  vLeft: number
  vTop: number
  vHeight: number
  vThumbHeight: number
  vThumbTop: number
  hVisible: boolean
  hLeft: number
  hTop: number
  hWidth: number
  hThumbWidth: number
  hThumbLeft: number
}

interface DragState {
  key: number
  axis: 'v' | 'h'
  startScroll: number
  startPointer: number
  trackRange: number
  scrollRange: number
}

/** 常见滚动容器：不需要等用户滚动就能先挂上滚动条 */
const KNOWN_SCROLLERS = [
  '.app-content',
  '.app-sider',
  '.conflict-list',
  '.event-timeline',
  '.ant-table-content',
  '.ant-table-body',
  '.ant-drawer-body',
  '.ant-modal-body',
  '.ant-select-dropdown .rc-virtual-list-holder',
  '.ant-picker-dropdown .rc-virtual-list-holder',
].join(',')

const bars = reactive<BarState[]>([])
const targets = new Map<number, HTMLElement>()
const keyOf = new WeakMap<HTMLElement, number>()

let nextKey = 1
let rafId = 0
let timer: number | null = null
let drag: DragState | null = null

function createBar(key: number): BarState {
  return {
    key,
    vVisible: false,
    vLeft: 0,
    vTop: 0,
    vHeight: 0,
    vThumbHeight: 0,
    vThumbTop: 0,
    hVisible: false,
    hLeft: 0,
    hTop: 0,
    hWidth: 0,
    hThumbWidth: 0,
    hThumbLeft: 0,
  }
}

/** 元素当前是否可被用户滚动（只认 auto/scroll，hidden 不产生滚动条） */
function scrollable(el: HTMLElement): { v: boolean; h: boolean } {
  const style = getComputedStyle(el)
  const v =
    (style.overflowY === 'auto' || style.overflowY === 'scroll') &&
    el.scrollHeight > el.clientHeight + 2
  const h =
    (style.overflowX === 'auto' || style.overflowX === 'scroll') &&
    el.scrollWidth > el.clientWidth + 2
  return { v, h }
}

function register(el: HTMLElement): void {
  if (keyOf.has(el)) return
  const { v, h } = scrollable(el)
  if (!v && !h) return
  const key = nextKey++
  keyOf.set(el, key)
  targets.set(key, el)
  const bar = createBar(key)
  bars.push(bar)
  updateBar(bar, el)
}

function removeBar(key: number): void {
  targets.delete(key)
  const index = bars.findIndex((bar) => bar.key === key)
  if (index >= 0) bars.splice(index, 1)
}

function updateBar(bar: BarState, el: HTMLElement): void {
  const rect = el.getBoundingClientRect()
  const inView =
    rect.width > 0 &&
    rect.height > 0 &&
    rect.bottom > 0 &&
    rect.right > 0 &&
    rect.top < window.innerHeight &&
    rect.left < window.innerWidth
  const vScroll = el.scrollHeight - el.clientHeight
  const hScroll = el.scrollWidth - el.clientWidth

  // 只注册过 overflow:auto/scroll 的容器，这里不再重复读 computedStyle（滚动帧里很贵）
  const canV = vScroll > 2 && rect.height > 40
  const canH = hScroll > 2 && rect.width > 40

  bar.vVisible = canV && inView
  if (bar.vVisible) {
    const track = Math.max(0, rect.height - 8)
    const thumb = Math.min(track, Math.max(28, (el.clientHeight / el.scrollHeight) * track))
    const offset = vScroll > 0 ? (el.scrollTop / vScroll) * (track - thumb) : 0
    bar.vLeft = Math.round(rect.right - 10)
    bar.vTop = Math.round(rect.top + 4)
    bar.vHeight = Math.round(track)
    bar.vThumbHeight = Math.round(thumb)
    bar.vThumbTop = Math.max(0, Math.min(track - thumb, offset))
  }

  bar.hVisible = canH && inView
  if (bar.hVisible) {
    const track = Math.max(0, rect.width - 8)
    const thumb = Math.min(track, Math.max(28, (el.clientWidth / el.scrollWidth) * track))
    const offset = hScroll > 0 ? (el.scrollLeft / hScroll) * (track - thumb) : 0
    bar.hLeft = Math.round(rect.left + 4)
    bar.hTop = Math.round(rect.bottom - 10)
    bar.hWidth = Math.round(track)
    bar.hThumbWidth = Math.round(thumb)
    bar.hThumbLeft = Math.max(0, Math.min(track - thumb, offset))
  }
}

function updateAll(): void {
  for (const [key, el] of targets) {
    if (!el.isConnected) {
      removeBar(key)
      continue
    }
    const bar = bars.find((item) => item.key === key)
    if (bar) updateBar(bar, el)
  }
}

function scheduleUpdate(): void {
  if (rafId) return
  rafId = window.requestAnimationFrame(() => {
    rafId = 0
    updateAll()
  })
}

/** 只在已知容器里补扫，代价很低；新出现的抽屉/弹窗也能被覆盖 */
function scanKnown(): void {
  document.querySelectorAll<HTMLElement>(KNOWN_SCROLLERS).forEach((el) => register(el))
}

function onAnyScroll(event: Event): void {
  const el = event.target
  if (el instanceof HTMLElement && !keyOf.has(el)) register(el)
  scheduleUpdate()
}

function onPointerOver(event: PointerEvent): void {
  let el = event.target as HTMLElement | null
  let depth = 0
  while (el && el !== document.body && depth < 8) {
    // 先做廉价的尺寸判断，避免每次 hover 都对整条祖先链调用 getComputedStyle
    if (el.scrollHeight > el.clientHeight + 2 || el.scrollWidth > el.clientWidth + 2) {
      const { v, h } = scrollable(el)
      if (v || h) {
        register(el)
        break
      }
    }
    el = el.parentElement
    depth++
  }
}

function onResize(): void {
  scanKnown()
  scheduleUpdate()
}

function startDrag(event: PointerEvent, key: number, axis: 'v' | 'h'): void {
  const el = targets.get(key)
  const bar = bars.find((item) => item.key === key)
  if (!el || !bar) return
  event.preventDefault()
  event.stopPropagation()
  const trackRange = axis === 'v' ? bar.vHeight - bar.vThumbHeight : bar.hWidth - bar.hThumbWidth
  const scrollRange = axis === 'v' ? el.scrollHeight - el.clientHeight : el.scrollWidth - el.clientWidth
  drag = {
    key,
    axis,
    startScroll: axis === 'v' ? el.scrollTop : el.scrollLeft,
    startPointer: axis === 'v' ? event.clientY : event.clientX,
    trackRange,
    scrollRange,
  }
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', onDragEnd, { once: true })
}

function onDragMove(event: PointerEvent): void {
  if (!drag) return
  const el = targets.get(drag.key)
  if (!el) return
  const delta = (drag.axis === 'v' ? event.clientY : event.clientX) - drag.startPointer
  const ratio = drag.trackRange > 0 ? drag.scrollRange / drag.trackRange : 0
  if (drag.axis === 'v') {
    el.scrollTop = drag.startScroll + delta * ratio
  } else {
    el.scrollLeft = drag.startScroll + delta * ratio
  }
  scheduleUpdate()
}

function onDragEnd(): void {
  drag = null
  window.removeEventListener('pointermove', onDragMove)
}

onMounted(() => {
  scanKnown()
  document.addEventListener('scroll', onAnyScroll, true)
  document.addEventListener('pointerover', onPointerOver, true)
  window.addEventListener('resize', onResize, { passive: true })
  // 列表变长/卡片展开后滚动条长度会变，低频补扫一次即可
  timer = window.setInterval(() => {
    scanKnown()
    scheduleUpdate()
  }, 1200)
  scheduleUpdate()
})

onBeforeUnmount(() => {
  if (rafId) window.cancelAnimationFrame(rafId)
  if (timer !== null) window.clearInterval(timer)
  document.removeEventListener('scroll', onAnyScroll, true)
  document.removeEventListener('pointerover', onPointerOver, true)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onDragMove)
  targets.clear()
  bars.splice(0)
})
</script>

<template>
  <div class="tech-scrollbars" aria-hidden="true">
    <template v-for="bar in bars" :key="bar.key">
      <div
        v-if="bar.vVisible"
        class="tsb-track tsb-track--v"
        :style="{
          left: `${bar.vLeft}px`,
          top: `${bar.vTop}px`,
          height: `${bar.vHeight}px`,
        }"
      >
        <div
          class="tsb-thumb"
          :style="{
            height: `${bar.vThumbHeight}px`,
            transform: `translateY(${bar.vThumbTop}px)`,
          }"
          @pointerdown="startDrag($event, bar.key, 'v')"
        />
      </div>
      <div
        v-if="bar.hVisible"
        class="tsb-track tsb-track--h"
        :style="{
          left: `${bar.hLeft}px`,
          top: `${bar.hTop}px`,
          width: `${bar.hWidth}px`,
        }"
      >
        <div
          class="tsb-thumb"
          :style="{
            width: `${bar.hThumbWidth}px`,
            transform: `translateX(${bar.hThumbLeft}px)`,
          }"
          @pointerdown="startDrag($event, bar.key, 'h')"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.tech-scrollbars {
  position: fixed;
  inset: 0;
  z-index: 6000;
  pointer-events: none;
}

/* 轨道：深色刻度导轨，和侧栏右缘的刻度尺同一套视觉语言 */
.tsb-track {
  position: fixed;
  pointer-events: none;
  border-radius: 3px;
  background-color: rgba(4, 14, 24, 0.55);
  box-shadow:
    inset 0 0 0 1px rgba(56, 232, 255, 0.1),
    0 0 6px rgba(0, 0, 0, 0.35);
}

.tsb-track--v {
  width: 6px;
  background-image: repeating-linear-gradient(
    180deg,
    rgba(56, 232, 255, 0.2) 0 1px,
    transparent 1px 7px
  );
}

.tsb-track--h {
  height: 6px;
  background-image: repeating-linear-gradient(
    90deg,
    rgba(56, 232, 255, 0.2) 0 1px,
    transparent 1px 7px
  );
}

/* 滑块：青蓝渐变光条，可拖拽 */
.tsb-thumb {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  border-radius: 3px;
  background: linear-gradient(180deg, #c8f8ff, #38e8ff 45%, #2f8dff);
  box-shadow:
    0 0 8px rgba(56, 232, 255, 0.75),
    inset 0 0 2px rgba(255, 255, 255, 0.8);
  pointer-events: auto;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.tsb-track--h .tsb-thumb {
  left: 0;
  top: 0;
  width: auto;
  height: 100%;
  background: linear-gradient(90deg, #c8f8ff, #38e8ff 45%, #2f8dff);
}

.tsb-thumb:hover {
  box-shadow:
    0 0 12px rgba(56, 232, 255, 0.95),
    inset 0 0 2px rgba(255, 255, 255, 0.9);
}

.tsb-thumb:active {
  cursor: grabbing;
  filter: brightness(1.15);
}
</style>
