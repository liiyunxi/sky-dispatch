<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { CaretRightOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { createCargoBox, createDroneModel, disposeObject } from './droneGeometry'

/**
 * 无人机 3D 建模与装载过程动画。
 *
 * 用 Three.js 程序化搭建一架无人机（机身 / 机臂 / 电机座 / 旋翼 / 起落架 /
 * 云台相机 / 电池），完整演示「待命悬停 → 下降 → 抓取货物 → 载荷锁定 →
 * 载货起飞」五个阶段。支持鼠标拖拽旋转 / 滚轮缩放，可随时重放。
 *
 * **外形随型号变化**：轻量四旋翼 / 中型六旋翼 / 重载八旋翼的机臂数量、
 * 机身尺寸、起落架与货舱数都不同（见 `droneGeometry.ts`），
 * 所以弹窗里展示的就是这架机真实的样子，而不是三种型号共用一套模型。
 *
 * 不加载任何外部模型文件：全部几何体在代码里生成，保持离线可用。
 */

const props = withDefaults(
  defineProps<{
    /** 机型代码（SKY-X4 / SKY-M8 / SKY-H2）；决定外形，未知型号退回通用四旋翼 */
    modelCode?: string | null
  }>(),
  { modelCode: null },
)

/** 动画各阶段（时间轴，单位秒）。 */
const STAGES = [
  { label: '待命悬停', end: 1.2, hint: '旋翼怠速，等待装载指令' },
  { label: '垂直下降', end: 3.4, hint: '对准取货台，缓缓降落到抓取高度' },
  { label: '抓取货物', end: 4.8, hint: '货仓锁钩收紧，把包裹提到机腹挂点' },
  { label: '载荷锁定', end: 5.5, hint: '自检挂载状态与重量，确认可起飞' },
  { label: '载货起飞', end: 7.6, hint: '满油门爬升到巡航高度，进入配送航线' },
] as const

const TOTAL = STAGES[STAGES.length - 1].end

const containerRef = ref<HTMLDivElement>()
const playing = ref(false)
/** 时间轴进度（0~1），驱动顶部进度条 */
const progress = ref(0)
const stageIndex = ref(0)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let rafId = 0
let resizeObserver: ResizeObserver | null = null

/** 场景中的可动对象 */
let droneGroup: THREE.Group | null = null
let rotors: THREE.Group[] = []
let rotorDiscs: THREE.Mesh[] = []
let cargo: THREE.Group | null = null
/** 抓取阶段的钩绳（视觉辅助） */
let hookLine: THREE.Line | null = null

/** 起飞 / 抓取的关键高度（场景单位 ≈ 米）。抓取高度与机腹偏移随型号缩放，见下方 airframeScale */
const HOVER_Y = 2.3
const CARGO_PAD_Y = 0.28
const TAKEOFF_Y = 3.1
/** 当前机型在场景里的缩放系数（由 droneGeometry 的 sceneScale 给出） */
let airframeScale = 1

/** 降落抓取时无人机停在哪：由起落架长度决定，所以随型号缩放 */
function grabY(): number {
  return CARGO_PAD_Y + 0.34 * airframeScale + 0.1
}

/** 货箱挂在机腹下方多远处 */
function bellyOffset(): number {
  return -0.52 * airframeScale
}

let timeline = 0
const clock = new THREE.Clock()

const currentStage = computed(() => STAGES[stageIndex.value] ?? STAGES[0])

// ---------------------------------------------------------------------------
// 几何体搭建（几何体本身已抽到 droneGeometry.ts，与态势地图的立体视图共用）
// ---------------------------------------------------------------------------

function buildDroneGroup(): THREE.Group {
  const parts = createDroneModel(props.modelCode)
  rotors = parts.rotors
  rotorDiscs = parts.rotorDiscs
  airframeScale = parts.spec.sceneScale
  // 重载机真实尺寸更大，缩到与其他机型相近的"取景尺寸"，但四种外形差异仍然清晰
  parts.group.scale.setScalar(airframeScale)
  return parts.group
}

function buildScene(): void {
  if (!containerRef.value) return

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x081522)
  scene.fog = new THREE.Fog(0x081522, 12, 26)

  camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100)
  camera.position.set(3.6, 2.4, 4.2)

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  containerRef.value.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.target.set(0, 1.1, 0)
  controls.enableDamping = true
  controls.minDistance = 2.5
  controls.maxDistance = 10
  controls.maxPolarAngle = Math.PI / 2 - 0.05
  controls.autoRotate = true
  controls.autoRotateSpeed = 0.8

  // 灯光
  scene.add(new THREE.HemisphereLight(0xdfefff, 0x14304a, 0.95))
  const sun = new THREE.DirectionalLight(0xffffff, 1.6)
  sun.position.set(4, 7, 3)
  sun.castShadow = true
  sun.shadow.mapSize.set(1024, 1024)
  sun.shadow.camera.left = -5
  sun.shadow.camera.right = 5
  sun.shadow.camera.top = 5
  sun.shadow.camera.bottom = -5
  scene.add(sun)

  // 冷色补光：深色场景里只有顶光时，机身的朝向面会全压成黑块，从镜头方向补一盏才看得出结构
  const fill = new THREE.DirectionalLight(0x9fd8ff, 0.85)
  fill.position.set(-6, 5, 8)
  scene.add(fill)

  // 地面：取货平台 + 网格
  const pad = new THREE.Mesh(
    new THREE.CylinderGeometry(1.7, 1.7, 0.08, 48),
    // 起降平台用深蓝金属，不再是一块白盘：深色场景里白盘会抢走视觉重心
    new THREE.MeshStandardMaterial({ color: 0x1a3b55, roughness: 0.55, metalness: 0.4 }),
  )
  pad.position.y = -0.04
  pad.receiveShadow = true
  scene.add(pad)

  const padRing = new THREE.Mesh(
    new THREE.RingGeometry(1.45, 1.7, 48),
    new THREE.MeshBasicMaterial({ color: 0x22d3ee, transparent: true, opacity: 0.55, side: THREE.DoubleSide }),
  )
  padRing.rotation.x = -Math.PI / 2
  padRing.position.y = 0.012
  scene.add(padRing)

  const grid = new THREE.GridHelper(10, 20, 0x2a587e, 0x1b3d57)
  grid.position.y = -0.001
  scene.add(grid)

  droneGroup = buildDroneGroup()
  droneGroup.position.set(0, HOVER_Y, 0)
  scene.add(droneGroup)

  cargo = createCargoBox()
  cargo.position.set(0, CARGO_PAD_Y, 0)
  scene.add(cargo)

  // 钩绳：抓取阶段从机身连到货物（Line 每帧更新端点）
  const hookGeometry = new THREE.BufferGeometry().setFromPoints([
    new THREE.Vector3(),
    new THREE.Vector3(),
  ])
  hookLine = new THREE.Line(
    hookGeometry,
    new THREE.LineBasicMaterial({ color: 0x475569, transparent: true, opacity: 0 }),
  )
  scene.add(hookLine)

  resize()
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(containerRef.value)
}

// ---------------------------------------------------------------------------
// 动画时间轴
// ---------------------------------------------------------------------------

/** 在 [a,b] 之间做平滑插值（smoothstep），避免生硬的匀速运动 */
function smooth(t: number, a: number, b: number): number {
  const x = Math.min(1, Math.max(0, (t - a) / (b - a)))
  return x * x * (3 - 2 * x)
}

function updateFrame(t: number): void {
  if (!droneGroup || !cargo || !hookLine) return

  const GRAB_Y = grabY()
  const CARGO_BELLY_OFFSET = bellyOffset()

  let droneY = HOVER_Y
  let cargoLift = 0 // 0 = 在货台上，1 = 完全挂到机腹
  let rotorSpeed = 14 // rad/s，视觉转速
  let discOpacity = 0.1
  let droneX = 0
  let heading = 0

  // 阶段 0：待命悬停（轻微上下浮动）
  if (t < STAGES[0].end) {
    droneY = HOVER_Y + Math.sin(t * 2.2) * 0.04
  }
  // 阶段 1：垂直下降
  else if (t < STAGES[1].end) {
    const k = smooth(t, STAGES[0].end, STAGES[1].end)
    droneY = HOVER_Y + (GRAB_Y - HOVER_Y) * k
    rotorSpeed = 14 + k * 8
    discOpacity = 0.1 + k * 0.12
  }
  // 阶段 2：抓取（货物升到机腹）
  else if (t < STAGES[2].end) {
    droneY = GRAB_Y
    cargoLift = smooth(t, STAGES[1].end, STAGES[2].end)
    rotorSpeed = 22 * (1 - cargoLift * 0.55)
    discOpacity = 0.22 * (1 - cargoLift * 0.8)
  }
  // 阶段 3：载荷锁定（旋翼重新起转）
  else if (t < STAGES[3].end) {
    droneY = GRAB_Y
    cargoLift = 1
    const k = smooth(t, STAGES[2].end, STAGES[3].end)
    rotorSpeed = 10 + k * 16
    discOpacity = 0.05 + k * 0.2
  }
  // 阶段 4：载货起飞
  else {
    const k = smooth(t, STAGES[2].end, TOTAL)
    droneY = GRAB_Y + (TAKEOFF_Y - GRAB_Y) * k
    droneX = k * k * 1.6
    heading = k * 0.35
    cargoLift = 1
    rotorSpeed = 26 + k * 10
    discOpacity = 0.25 + k * 0.15
  }

  // 悬停呼吸（除下降 / 抓取阶段外都加一点）
  if (t < STAGES[0].end || t >= STAGES[3].end) {
    droneY += Math.sin(t * 2.2) * 0.03
  }

  droneGroup.position.set(droneX, droneY, 0)
  droneGroup.rotation.y = heading

  // 货物：0 → 货台；1 → 挂在机腹跟随无人机
  const padPos = new THREE.Vector3(0, CARGO_PAD_Y, 0)
  const bellyPos = new THREE.Vector3(droneX, droneY + CARGO_BELLY_OFFSET, 0)
  cargo.position.lerpVectors(padPos, bellyPos, cargoLift)
  cargo.rotation.y = heading * cargoLift

  // 钩绳：只在抓取 / 锁定阶段可见
  const hookVisible = t >= STAGES[1].end && t < STAGES[3].end
  const hookMat = hookLine.material as THREE.LineBasicMaterial
  hookMat.opacity = hookVisible ? 0.7 : 0
  if (hookVisible) {
    const positions = hookLine.geometry.attributes.position as THREE.BufferAttribute
    positions.setXYZ(0, droneX, droneY - 0.18, 0)
    positions.setXYZ(1, cargo.position.x, cargo.position.y + 0.18, cargo.position.z)
    positions.needsUpdate = true
  }

  // 旋翼转动 + 模糊盘透明度
  const dt = Math.min(clock.getDelta(), 0.05)
  rotors.forEach((rotor, i) => {
    rotor.rotation.y += rotorSpeed * dt * (i % 2 === 0 ? 1 : -1)
  })
  rotorDiscs.forEach((disc) => {
    ;(disc.material as THREE.MeshBasicMaterial).opacity = discOpacity
  })
}

function tick(): void {
  rafId = requestAnimationFrame(tick)
  if (!playing.value) {
    controls?.update()
    renderer?.render(scene!, camera!)
    return
  }
  timeline += clock.getDelta()
  if (timeline >= TOTAL) {
    timeline = TOTAL
    playing.value = false
  }
  progress.value = timeline / TOTAL
  stageIndex.value = STAGES.findIndex((s) => timeline < s.end)
  if (stageIndex.value < 0) stageIndex.value = STAGES.length - 1

  updateFrame(timeline)
  controls?.update()
  if (renderer && scene && camera) renderer.render(scene, camera)
}

function resize(): void {
  if (!containerRef.value || !renderer || !camera) return
  const w = containerRef.value.clientWidth
  const h = containerRef.value.clientHeight
  if (w === 0 || h === 0) return
  renderer.setSize(w, h)
  camera.aspect = w / h
  camera.updateProjectionMatrix()
}

function play(): void {
  if (timeline >= TOTAL) timeline = 0
  clock.getDelta()
  playing.value = true
}

function replay(): void {
  timeline = 0
  progress.value = 0
  stageIndex.value = 0
  clock.getDelta()
  playing.value = true
}

onMounted(() => {
  buildScene()
  // 初始摆放一次（未播放时也有完整画面）
  updateFrame(0)
  tick()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  resizeObserver?.disconnect()
  controls?.dispose()
  if (scene) disposeObject(scene)
  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null
  scene = null
})
</script>

<template>
  <div class="drone-3d">
    <div ref="containerRef" class="stage"></div>

    <!-- 阶段信息浮层 -->
    <div class="stage-info">
      <div class="stage-label">
        <span class="stage-step">{{ stageIndex + 1 }}/5</span>
        <span class="stage-name">{{ currentStage.label }}</span>
      </div>
      <div class="stage-hint">{{ currentStage.hint }}</div>
      <a-progress
        :percent="Math.round(progress * 100)"
        :show-info="false"
        size="small"
        stroke-color="#1677ff"
      />
      <div class="stage-ticks">
        <span
          v-for="(s, i) in STAGES"
          :key="s.label"
          class="tick"
          :class="{ active: i === stageIndex, done: i < stageIndex }"
        >
          {{ s.label }}
        </span>
      </div>
    </div>

    <!-- 控制按钮 -->
    <div class="controls">
      <a-button size="small" @click="playing ? (playing = false) : play()">
        <template #icon><CaretRightOutlined /></template>
        {{ playing ? '暂停' : timeline >= TOTAL ? '再看一遍' : '播放装载过程' }}
      </a-button>
      <a-button size="small" @click="replay">
        <template #icon><ReloadOutlined /></template>
        重放
      </a-button>
    </div>

    <div class="orbit-hint">拖拽旋转 · 滚轮缩放</div>
  </div>
</template>

<style scoped>
.drone-3d {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 420px;
  border-radius: 8px;
  overflow: hidden;
  background: #0c1b2c;
}

.stage {
  position: absolute;
  inset: 0;
}

.stage-info {
  position: absolute;
  left: 12px;
  bottom: 12px;
  width: 260px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(12, 27, 44, 0.88);
  backdrop-filter: blur(4px);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
}

.stage-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.stage-step {
  font-size: 12px;
  color: rgba(180, 208, 232, 0.55);
}

.stage-name {
  font-weight: 600;
  font-size: 14px;
  color: #d8e8fb;
}

.stage-hint {
  margin: 2px 0 6px;
  font-size: 12px;
  color: rgba(190, 216, 240, 0.78);
}

.stage-ticks {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.tick {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 9px;
  background: rgba(34, 211, 238, 0.1);
  color: rgba(180, 208, 232, 0.55);
}

.tick.active {
  background: rgba(34, 211, 238, 0.22);
  color: #fff;
}

.tick.done {
  background: rgba(34, 211, 238, 0.1);
  color: #4fc3ff;
}

.controls {
  position: absolute;
  right: 12px;
  top: 12px;
  display: flex;
  gap: 8px;
}

.orbit-hint {
  position: absolute;
  right: 12px;
  bottom: 12px;
  font-size: 11px;
  color: rgba(180, 208, 232, 0.38);
}
</style>
