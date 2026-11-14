<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'
import { buildDroneAssembly, disposeAssembly, type DroneAssembly } from './droneAssembly'
import { detailOf, spanOf, wheelbaseOf } from './droneSpec'
import {
  DRONE_PARTS,
  PART_BY_ID,
  PART_SYSTEMS,
  qtyOf,
  specTextOf,
  totalPieceCount,
  type PartSystem,
} from './droneParts'

/**
 * 整机结构图谱：把一架无人机拆到零件，并逐件说明它是干什么用的。
 *
 * <p>这个视图要回答两个"看图看不出来"的问题：
 * <ol>
 *   <li><b>里面到底有什么</b> —— 拆解（沿各自方向散开）与透视（壳体半透明）
 *       两种看法，配合旋转缩放，能一直看到电调、锁钩这种藏在内部的零件；</li>
 *   <li><b>每个零件为什么存在</b> —— 右侧列表按系统分组，选中任何一件都会
 *       给出「作用 / 怎么工作 / 与调度的关系」三段说明。</li>
 * </ol>
 *
 * <p><b>为什么不是一张漂亮的爆炸图了事</b>：纯展示的爆炸图看完只记得"零件很多"。
 * 把零件和业务规则接上（电池 → 航程约束、电机 × 拉力 → 载重校验、锁钩 → 送达回执），
 * 这张图才解释得了"调度系统凭什么这么派单"。
 */

const props = withDefaults(
  defineProps<{
    /** 机型代码；决定几何尺寸、机臂数量与涂装 */
    modelCode?: string | null
    /** 机身编号：喷在机身侧面，让"这架机"具体起来 */
    tailNumber?: string | null
  }>(),
  { modelCode: null, tailNumber: null },
)

// ---------------------------------------------------------------------------
// 静态数据
// ---------------------------------------------------------------------------

const spec = computed(() => detailOf(props.modelCode))
const wheelbaseMm = computed(() => Math.round(wheelbaseOf(spec.value) * 1000))
const pieces = computed(() => totalPieceCount(spec.value))

// ---------------------------------------------------------------------------
// 交互状态
// ---------------------------------------------------------------------------

const hostRef = ref<HTMLDivElement>()

/** 选中的部件 id */
const selectedId = ref<string | null>(null)
/** 悬停的部件 id（3D 与列表双向） */
const hoverId = ref<string | null>(null)
/** 拆解程度目标值 0~1（滑杆与"一键拆解"共用） */
const explodeTarget = ref(0)
const xray = ref(false)
const showLabels = ref(false)
const isolate = ref(false)
const autoRotate = ref(false)
/** 系统筛选：'all' 或某个系统 */
const filter = ref<'all' | PartSystem>('all')
/** 一条屏幕标注：ax/ay 是零件在图上的位置，lx/ly 是文字挂牌的位置 */
interface ScreenLabel {
  id: string
  name: string
  color: string
  ax: number
  ay: number
  lx: number
  ly: number
  side: 'left' | 'right'
}

/** 屏幕坐标标注（每 3 帧刷新一次，避免每帧触发 Vue 渲染） */
const labels = shallowRef<ScreenLabel[]>([])

const selectedPart = computed(() => (selectedId.value ? PART_BY_ID[selectedId.value] ?? null : null))
const selectedQty = computed(() => (selectedPart.value ? qtyOf(selectedPart.value, spec.value) : 0))
const selectedSpec = computed(() =>
  selectedPart.value ? specTextOf(selectedPart.value, spec.value) : '',
)
const selectedColor = computed(() =>
  selectedPart.value
    ? PART_SYSTEMS.find((s) => s.key === selectedPart.value?.system)?.color ?? '#475569'
    : '#475569',
)

const visibleGroups = computed(() => {
  const groups = PART_SYSTEMS.map((sys) => ({
    ...sys,
    parts: DRONE_PARTS.filter((p) => p.system === sys.key),
  }))
  return filter.value === 'all' ? groups : groups.filter((g) => g.key === filter.value)
})

function countOf(system: PartSystem): number {
  return DRONE_PARTS.filter((p) => p.system === system).length
}

// ---------------------------------------------------------------------------
// Three.js 场景
// ---------------------------------------------------------------------------

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let rafId = 0
let resizeObserver: ResizeObserver | null = null
let assembly: DroneAssembly | null = null
let envTexture: THREE.Texture | null = null
let contactTexture: THREE.Texture | null = null

/** 拾取目标：整机的全部网格 */
let pickTargets: THREE.Mesh[] = []
/** 材质 → 初始外观（高亮/透视都是临时改动，退出时要还原） */
const baseLook = new WeakMap<
  THREE.MeshStandardMaterial,
  { opacity: number; transparent: boolean; depthWrite: boolean; emissive: number; emissiveIntensity: number }
>()

/** 拆解动画的当前值（缓动追随 explodeTarget） */
let explodeCurrent = 0
/** 首次取景是否已完成（容器尺寸确定后才能算） */
let initialFitDone = false
/** 相机缓动：选中零件时把镜头推过去 */
let focusTween: {
  fromPos: THREE.Vector3
  toPos: THREE.Vector3
  fromTarget: THREE.Vector3
  toTarget: THREE.Vector3
  t: number
} | null = null

const clock = new THREE.Clock()
const raycaster = new THREE.Raycaster()
const pointer = new THREE.Vector2()
let frame = 0

/**
 * 默认观察方向：右前上方约 20°。
 * 再高就看不清机头云台，再平就看不出机身的分层 —— 20° 是"能同时看出俯视布局与立面细节"的角度。
 */
const HOME_DIR = new THREE.Vector3(0.82, 0.34, 1).normalize()

/** 整机在场景里的落位：机腹位于 y = gearHeight，滑橇贴地 */
function rootOffset(): THREE.Vector3 {
  return new THREE.Vector3(0, spec.value.gearHeight + 0.012, 0)
}

/**
 * 当前拆解状态下整机的包围盒。
 *
 * <p>为什么不用"固定的取景距离"：拆开后零件的横向跨度会大出一倍，
 * 写死距离的结果就是推进时零件飞出画面。按包围盒实时算是唯一稳妥的做法。
 */
function boundsOf(explode: number): THREE.Box3 {
  const box = new THREE.Box3()
  if (!assembly) return box.setFromCenterAndSize(new THREE.Vector3(), new THREE.Vector3(1, 1, 1))
  const offset = rootOffset()
  for (const inst of assembly.instances) {
    // 直接用零件自己的包围盒，而不是"中心 ± 半径"——后者会把细长机臂当成球，
    // 把整体高度虚增一倍，画面就显得很小
    const move = inst.dir.clone().multiplyScalar(inst.dist * explode).add(offset)
    box.union(inst.box.clone().translate(move))
  }
  return box
}

/**
 * 让包围盒刚好装进画面的相机距离。
 *
 * <p>分别按水平/垂直视场算一次取较大者 —— 只按球半径算会让模型缩得很小
 * （爆炸图是个扁平的大盒子，包围球比它大得多）。
 */
function fitDistance(box: THREE.Box3): number {
  if (!camera) return 1
  const size = box.getSize(new THREE.Vector3())
  const vFov = THREE.MathUtils.degToRad(camera.fov)
  const hFov = 2 * Math.atan(Math.tan(vFov / 2) * camera.aspect)
  const distV = size.y / 2 / Math.tan(vFov / 2)
  const distH = size.x / 2 / Math.tan(hFov / 2)
  // 透视下靠近镜头的零件会被放大，所以再按厚度补一点距离；1.12 是留白系数
  return Math.max(distV, distH) * 1.12 + size.z * 0.3
}

/** 默认取景距离：装得下整机；拆解后自动退远，否则零件会飞出画面 */
function homeDistance(exploded = 0): number {
  return fitDistance(boundsOf(exploded))
}

/** 观察中心：机身中段略偏下，兼顾云台与货舱 */
function homeTarget(): THREE.Vector3 {
  return new THREE.Vector3(0, spec.value.bodyH * 0.5, 0)
}

/** 一块柔和的接地阴影贴图（阴影贴图之外再补一层环境遮蔽，落地感强很多） */
function contactShadowTexture(): THREE.CanvasTexture {
  const canvas = document.createElement('canvas')
  canvas.width = 256
  canvas.height = 256
  const ctx = canvas.getContext('2d')!
  const grad = ctx.createRadialGradient(128, 128, 8, 128, 128, 126)
  grad.addColorStop(0, 'rgba(15, 23, 42, 0.34)')
  grad.addColorStop(0.45, 'rgba(15, 23, 42, 0.12)')
  grad.addColorStop(1, 'rgba(15, 23, 42, 0)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, 256, 256)
  const tex = new THREE.CanvasTexture(canvas)
  tex.colorSpace = THREE.SRGBColorSpace
  return tex
}

function buildScene(): void {
  const host = hostRef.value
  if (!host) return

  const s = spec.value
  scene = new THREE.Scene()

  camera = new THREE.PerspectiveCamera(38, 1, 0.01, 60)
  // 用 addScaledVector 而不是 dir.multiplyScalar(...)：后者会就地改掉 HOME_DIR，
  // 之后所有"按单位向量算距离"的地方都会跟着偏（这个坑排查了很久）
  camera.position.copy(homeTarget().addScaledVector(HOME_DIR, homeDistance(0)))
  camera.lookAt(homeTarget())

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.08
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  // 画布绝对定位占满视口，且压在浮层之下（浮层用 z-index:2 提上来）
  renderer.domElement.style.display = 'block'
  renderer.domElement.style.position = 'absolute'
  renderer.domElement.style.inset = '0'
  renderer.domElement.style.zIndex = '0'
  host.appendChild(renderer.domElement)

  // 程序化环境贴图：金属件没有反射就会像塑料，这一步对质感影响最大
  const pmrem = new THREE.PMREMGenerator(renderer)
  envTexture = pmrem.fromScene(new RoomEnvironment(), 0.04).texture
  scene.environment = envTexture
  scene.environmentIntensity = 0.85
  pmrem.dispose()

  controls = new OrbitControls(camera, renderer.domElement)
  controls.target.copy(homeTarget())
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.minDistance = spanOf(s) * 0.35
  controls.maxDistance = spanOf(s) * 5
  controls.maxPolarAngle = Math.PI / 2 - 0.03
  controls.autoRotateSpeed = 0.9
  // 用户一动镜头就放弃自动聚焦，避免"我拖到一半被拽回去"
  controls.addEventListener('start', () => {
    focusTween = null
  })

  // ---- 灯光：主光 + 侧补光 + 轮廓光，三层才立体 ----
  scene.add(new THREE.HemisphereLight(0xffffff, 0xdfe6ef, 0.55))

  const key = new THREE.DirectionalLight(0xffffff, 2.1)
  const keyDist = spanOf(s) * 2.4
  key.position.set(keyDist * 0.7, keyDist * 1.1, keyDist * 0.6)
  key.castShadow = true
  key.shadow.mapSize.set(2048, 2048)
  const shadowCam = key.shadow.camera
  const bound = spanOf(s) * 0.9
  shadowCam.left = -bound
  shadowCam.right = bound
  shadowCam.top = bound
  shadowCam.bottom = -bound
  shadowCam.near = 0.05
  shadowCam.far = keyDist * 4
  key.shadow.bias = -0.0006
  key.shadow.normalBias = 0.004
  scene.add(key)

  const fill = new THREE.DirectionalLight(0xdce8ff, 0.7)
  fill.position.set(-keyDist, keyDist * 0.7, keyDist * 0.4)
  scene.add(fill)

  const rim = new THREE.DirectionalLight(0xffffff, 0.9)
  rim.position.set(-keyDist * 0.3, keyDist * 0.5, -keyDist)
  scene.add(rim)

  // ---- 地面：只接收阴影的透明平面 + 一层柔和接地阴影 ----
  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(spanOf(s) * 6, spanOf(s) * 6),
    new THREE.ShadowMaterial({ opacity: 0.22 }),
  )
  floor.rotation.x = -Math.PI / 2
  floor.receiveShadow = true
  scene.add(floor)

  const contact = new THREE.Mesh(
    new THREE.PlaneGeometry(spanOf(s) * 2.4, spanOf(s) * 2.4),
    new THREE.MeshBasicMaterial({
      map: contactShadowTexture(),
      transparent: true,
      depthWrite: false,
      opacity: 0.9,
    }),
  )
  contact.rotation.x = -Math.PI / 2
  contact.position.y = 0.001
  contactTexture = (contact.material as THREE.MeshBasicMaterial).map
  scene.add(contact)

  // ---- 整机 ----
  assembly = buildDroneAssembly(s, props.tailNumber ?? undefined)
  assembly.root.position.copy(rootOffset())
  scene.add(assembly.root)

  pickTargets = []
  assembly.root.traverse((obj) => {
    if (obj instanceof THREE.Mesh) pickTargets.push(obj)
  })
  assembly.instances.forEach((inst) => {
    inst.materials.forEach((m) => {
      baseLook.set(m, {
        opacity: m.opacity,
        transparent: m.transparent,
        depthWrite: m.depthWrite,
        emissive: m.emissive.getHex(),
        emissiveIntensity: m.emissiveIntensity,
      })
    })
  })

  resize()
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host)
}

function disposeScene(): void {
  cancelAnimationFrame(rafId)
  resizeObserver?.disconnect()
  resizeObserver = null
  controls?.dispose()
  controls = null
  disposeAssembly(assembly)
  assembly = null
  envTexture?.dispose()
  envTexture = null
  contactTexture?.dispose()
  contactTexture = null
  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null
  scene = null
  camera = null
  pickTargets = []
  focusTween = null
  labels.value = []
}

// ---------------------------------------------------------------------------
// 外观状态：高亮 / 透视 / 筛选 / 独显
// ---------------------------------------------------------------------------

/**
 * 把当前交互状态写进材质。
 *
 * <p>只在状态变化时调用（不是每帧），因为改 `transparent` 会让 Three 重新编译着色器；
 * 每帧调用会直接掉帧。
 */
function applyVisualState(): void {
  if (!assembly) return
  const sel = selectedId.value
  const hov = hoverId.value
  const dimSystem = filter.value
  for (const inst of assembly.instances) {
    const part = PART_BY_ID[inst.partId]
    const isSel = sel === inst.partId
    const isHov = hov === inst.partId
    const isShell = xray.value && assembly.shellParts.includes(inst.partId)
    const hiddenByIsolate = !!sel && isolate.value && !isSel
    const dimByFilter = dimSystem !== 'all' && part?.system !== dimSystem
    const color = part ? PART_SYSTEMS.find((s) => s.key === part.system)?.color ?? '#475569' : '#475569'
    const highlight = isSel || isHov

    for (const m of inst.materials) {
      const base = baseLook.get(m)
      if (!base) continue

      let opacity = base.opacity
      if (hiddenByIsolate) opacity = 0.045
      else if (dimByFilter) opacity = Math.min(opacity, 0.1)
      if (isShell) opacity = Math.min(opacity, isSel ? 0.32 : 0.1)

      const transparent = base.transparent || opacity < 0.999
      const depthWrite = opacity < 0.999 ? false : base.depthWrite
      if (m.transparent !== transparent || m.depthWrite !== depthWrite) {
        m.transparent = transparent
        m.depthWrite = depthWrite
        m.needsUpdate = true
      }
      m.opacity = opacity

      if (highlight) {
        m.emissive.set(color)
        m.emissiveIntensity = isSel ? 0.5 : 0.2
      } else {
        m.emissive.setHex(base.emissive)
        m.emissiveIntensity = base.emissiveIntensity
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 选中 / 聚焦
// ---------------------------------------------------------------------------

/** 选中一个部件：高亮 + 镜头推近；再点一次取消 */
function select(partId: string | null): void {
  if (partId && selectedId.value === partId) {
    selectedId.value = null
    resetView()
    return
  }
  selectedId.value = partId
  applyVisualState()
  if (partId) focusOn(partId)
}

/** 把镜头推到某个零件的包围球外侧，方向沿用当前视角 */
function focusOn(partId: string): void {
  if (!assembly || !camera || !controls) return
  const inst = assembly.byPart.get(partId)?.[0]
  if (!inst) return

  const center = inst.center.clone().add(inst.object.position).add(rootOffset())
  // 保留用户当前的观察方向，只改距离与被观察点
  const dir = camera.position.clone().sub(controls.target).normalize()
  const dist = Math.min(homeDistance(explodeCurrent) * 1.1, Math.max(inst.radius * 3.4, spanOf(spec.value) * 0.42))

  focusTween = {
    fromPos: camera.position.clone(),
    toPos: center.clone().add(dir.multiplyScalar(dist)),
    fromTarget: controls.target.clone(),
    toTarget: center,
    t: 0,
  }
}

/** 回到默认取景 */
function resetView(): void {
  fitView(HOME_DIR)
}

/**
 * 把镜头拉到"刚好装下当前状态"的距离。
 *
 * @param dirOverride 指定观察方向；不传则沿用用户当前的视角
 */
function fitView(dirOverride?: THREE.Vector3): void {
  if (!camera || !controls) return
  const box = boundsOf(explodeTarget.value)
  const center = box.getCenter(new THREE.Vector3())
  // 必须 clone：直接引用常量的话，后续任何 multiplyScalar 都会污染它
  const dir = (dirOverride ?? camera.position.clone().sub(controls.target).normalize()).clone()
  const dist = fitDistance(box)
  focusTween = {
    fromPos: camera.position.clone(),
    toPos: center.clone().addScaledVector(dir, dist),
    fromTarget: controls.target.clone(),
    toTarget: center,
    t: 0,
  }
}

function toggleExplode(): void {
  explodeTarget.value = explodeTarget.value > 0.5 ? 0 : 1
  // 选中了某个零件时不要抢镜头——用户正在看细节
  if (!selectedId.value) fitView()
}

/** 滑杆：0~100 的整数映射到 0~1 的拆解系数 */
function onExplodeInput(e: Event): void {
  const input = e.target as HTMLInputElement | null
  if (!input) return
  explodeTarget.value = Number(input.value) / 100
  if (!selectedId.value) fitView()
}

function toggleTool(key: 'xray' | 'labels' | 'isolate' | 'rotate'): void {
  if (key === 'xray') xray.value = !xray.value
  else if (key === 'labels') showLabels.value = !showLabels.value
  else if (key === 'isolate') isolate.value = !isolate.value
  else autoRotate.value = !autoRotate.value
  if (key === 'isolate' && !selectedId.value) {
    // 没有选中任何零件时，"独显"没有意义——提示选中一个
    isolate.value = false
  }
  applyVisualState()
}

// ---------------------------------------------------------------------------
// 射线拾取
// ---------------------------------------------------------------------------

/** 从命中的网格往上找，直到找到带 partId 的零件根节点 */
function partIdOf(object: THREE.Object3D): string | null {
  let node: THREE.Object3D | null = object
  while (node) {
    const id = node.userData.partId as string | undefined
    if (id) return id
    node = node.parent
  }
  return null
}

function partIdAt(clientX: number, clientY: number): string | null {
  const host = hostRef.value
  if (!host || !camera) return null
  const rect = host.getBoundingClientRect()
  pointer.x = ((clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)

  // 透视模式下壳体是"看得见但不该挡住点击"的：先记下壳体的命中，内部零件优先
  let shellHit: string | null = null
  for (const hit of raycaster.intersectObjects(pickTargets, false)) {
    const id = partIdOf(hit.object)
    if (!id) continue
    if (xray.value && assembly?.shellParts.includes(id)) {
      if (!shellHit) shellHit = id
      continue
    }
    return id
  }
  return shellHit
}

let pointerDownAt: { x: number; y: number } | null = null
let hoverThrottle = 0

/** 只有点在画布上才算拾取：否则点"拆解""透视"这些按钮也会顺带改选中状态 */
function fromCanvas(e: PointerEvent): boolean {
  return !!renderer && e.target === renderer.domElement
}

function onPointerDown(e: PointerEvent): void {
  if (!fromCanvas(e)) return
  pointerDownAt = { x: e.clientX, y: e.clientY }
}

function onPointerUp(e: PointerEvent): void {
  if (!pointerDownAt) return
  const moved = Math.hypot(e.clientX - pointerDownAt.x, e.clientY - pointerDownAt.y)
  pointerDownAt = null
  // 拖拽旋转不应该改变选中状态
  if (moved > 5) return
  select(partIdAt(e.clientX, e.clientY))
}

function onPointerMove(e: PointerEvent): void {
  if (!fromCanvas(e)) return
  const now = performance.now()
  if (now - hoverThrottle < 50) return
  hoverThrottle = now
  const id = partIdAt(e.clientX, e.clientY)
  if (id !== hoverId.value) {
    hoverId.value = id
    applyVisualState()
  }
  if (hostRef.value) hostRef.value.style.cursor = id ? 'pointer' : 'grab'
}

function onPointerLeave(): void {
  if (hoverId.value) {
    hoverId.value = null
    applyVisualState()
  }
}

// ---------------------------------------------------------------------------
// 每帧更新
// ---------------------------------------------------------------------------

/**
 * 标注的优先级：先给外围零件挂标签，再给中心零件。
 *
 * <p>位置重叠时必须丢掉一个 —— 先放中心件的话，外围的桨、电机就全被挤掉了，
 * 而那恰好是最需要"指出来"的部件。
 */
const LABEL_ORDER = [
  'prop',
  'motor',
  'parachute',
  'radar',
  'gimbal',
  'cargo-hold',
  'battery',
  'fc',
  'lock-hook',
  'center-frame',
]

/** 标注位置：把零件中心投影到屏幕，再按工程图的习惯排到模型外侧 */
function updateLabels(): void {
  if (!showLabels.value || !assembly || !camera || !hostRef.value) {
    if (labels.value.length) labels.value = []
    return
  }
  const rect = hostRef.value.getBoundingClientRect()
  const W = rect.width
  const H = rect.height

  // 模型在屏幕上的左右边界：标注要排在它外面，而不是压在身上
  const worldBox = boundsOf(explodeCurrent)
  let modelLeft = Infinity
  let modelRight = -Infinity
  for (const x of [worldBox.min.x, worldBox.max.x]) {
    for (const y of [worldBox.min.y, worldBox.max.y]) {
      for (const z of [worldBox.min.z, worldBox.max.z]) {
        const v = new THREE.Vector3(x, y, z).project(camera)
        const sx = (v.x * 0.5 + 0.5) * W
        modelLeft = Math.min(modelLeft, sx)
        modelRight = Math.max(modelRight, sx)
      }
    }
  }
  if (!Number.isFinite(modelLeft)) return

  const anchors = DRONE_PARTS.filter((p) => p.anchor && (filter.value === 'all' || p.system === filter.value))
    .sort((a, b) => {
      const ia = LABEL_ORDER.indexOf(a.id)
      const ib = LABEL_ORDER.indexOf(b.id)
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
    })
    .map((part) => {
      const inst = assembly!.byPart.get(part.id)?.[0]
      if (!inst) return null
      const world = inst.center.clone().add(inst.object.position).add(rootOffset()).project(camera!)
      return {
        part,
        ax: (world.x * 0.5 + 0.5) * W,
        ay: (-world.y * 0.5 + 0.5) * H,
        depth: world.z,
      }
    })
    .filter((a): a is NonNullable<typeof a> => !!a && a.depth <= 1)

  const midX = (modelLeft + modelRight) / 2
  const pad = 34
  const gap = 21
  const minY = pad + 10
  const maxY = H - pad - 10

  /** 一侧的挂牌纵向排布：先按零件的上下顺序排，再压到画面内，避免互相盖住 */
  function layout(list: typeof anchors): number[] {
    const sorted = [...list].sort((a, b) => a.ay - b.ay)
    const ys: number[] = []
    let prev = -Infinity
    sorted.forEach((a) => {
      const y = Math.max(a.ay, prev + gap)
      ys.push(y)
      prev = y
    })
    if (ys.length) {
      const overflow = ys[ys.length - 1] - maxY
      if (overflow > 0) for (let i = 0; i < ys.length; i += 1) ys[i] -= overflow
      const under = minY - ys[0]
      if (under > 0) for (let i = 0; i < ys.length; i += 1) ys[i] += under
    }
    return ys
  }

  const result: ScreenLabel[] = []
  const textWidth = (name: string) => name.length * 11.5 + 14

  const build = (list: typeof anchors, side: 'left' | 'right') => {
    if (!list.length) return
    const ys = layout(list)
    const sorted = [...list].sort((a, b) => a.ay - b.ay)
    const maxW = Math.max(...list.map((a) => textWidth(a.part.name)))
    // 挂牌列：优先排在模型外侧；模型太宽时退到画面两侧的安全位置，
    // 但绝不能因为名字太长而顶出画面
    const lx =
      side === 'right'
        ? Math.min(Math.max(modelRight + 26, W * 0.72), W - maxW - 10)
        : Math.max(Math.min(modelLeft - 26, W * 0.28), maxW + 10)
    sorted.forEach((a, i) => {
      result.push({
        id: a.part.id,
        name: a.part.name,
        color: PART_SYSTEMS.find((s) => s.key === a.part.system)?.color ?? '#475569',
        ax: a.ax,
        ay: a.ay,
        lx,
        ly: ys[i],
        side,
      })
    })
  }

  build(
    anchors.filter((a) => a.ax < midX),
    'left',
  )
  build(
    anchors.filter((a) => a.ax >= midX),
    'right',
  )

  labels.value = result
}

function tick(): void {
  rafId = requestAnimationFrame(tick)
  if (!renderer || !scene || !camera || !controls) return
  const dt = Math.min(clock.getDelta(), 0.05)

  // 拆解位移：目标值缓动，避免"啪"地跳开
  const target = explodeTarget.value
  if (Math.abs(explodeCurrent - target) > 0.0005) {
    explodeCurrent += (target - explodeCurrent) * Math.min(1, dt * 7)
    if (assembly) {
      for (const inst of assembly.instances) {
        inst.object.position.copy(inst.dir).multiplyScalar(inst.dist * explodeCurrent)
      }
    }
  }

  // 镜头缓动
  if (focusTween) {
    focusTween.t = Math.min(1, focusTween.t + dt / 0.55)
    const e = 1 - Math.pow(1 - focusTween.t, 3)
    camera.position.lerpVectors(focusTween.fromPos, focusTween.toPos, e)
    controls.target.lerpVectors(focusTween.fromTarget, focusTween.toTarget, e)
    if (focusTween.t >= 1) focusTween = null
  }

  controls.autoRotate = autoRotate.value
  controls.update()
  renderer.render(scene, camera)

  frame += 1
  if (frame % 3 === 0) updateLabels()
}

function resize(): void {
  const host = hostRef.value
  if (!host || !renderer || !camera) return
  const w = host.clientWidth
  const h = host.clientHeight
  if (w === 0 || h === 0) return
  renderer.setSize(w, h, false)
  renderer.domElement.style.width = '100%'
  renderer.domElement.style.height = '100%'
  camera.aspect = w / h
  camera.updateProjectionMatrix()

  // 首次取景必须等到真实的容器尺寸出来之后再算：挂载瞬间容器常常还是 0×0，
  // 按当时的纵横比算距离，等布局稳定后模型就会偏小或飞出画面。
  if (!initialFitDone && assembly) {
    initialFitDone = true
    applyInitialView()
  }
}

/** 首次进入时的取景：按整机包围盒算距离，直接落位（不做补间） */
function applyInitialView(): void {
  if (!assembly || !camera || !controls) return
  const box = boundsOf(0)
  const center = box.getCenter(new THREE.Vector3())
  camera.position.copy(center.clone().addScaledVector(HOME_DIR, fitDistance(box)))
  controls.target.copy(center)
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(() => {
  buildScene()
  tick()
})

onBeforeUnmount(disposeScene)

// 机型切换（同一个弹窗里换一架飞机）时整体重建：几何尺寸、机臂数、涂装全变了
watch(
  () => [props.modelCode, props.tailNumber],
  () => {
    if (!scene) return
    selectedId.value = null
    hoverId.value = null
    explodeTarget.value = 0
    explodeCurrent = 0

    if (assembly) {
      scene.remove(assembly.root)
      disposeAssembly(assembly)
      assembly = null
    }

    assembly = buildDroneAssembly(spec.value, props.tailNumber ?? undefined)
    assembly.root.position.copy(rootOffset())
    scene.add(assembly.root)

    pickTargets = []
    assembly.root.traverse((obj) => {
      if (obj instanceof THREE.Mesh) pickTargets.push(obj)
    })
    assembly.instances.forEach((inst) => {
      inst.materials.forEach((m) => {
        baseLook.set(m, {
          opacity: m.opacity,
          transparent: m.transparent,
          depthWrite: m.depthWrite,
          emissive: m.emissive.getHex(),
          emissiveIntensity: m.emissiveIntensity,
        })
      })
    })
    resetView()
    applyVisualState()
  },
)

watch([selectedId, hoverId, xray, isolate, filter], () => applyVisualState())
</script>

<template>
  <div class="anatomy">
    <!-- ------------------------------ 三维视口 ------------------------------ -->
    <div
      ref="hostRef"
      class="viewport"
      @pointerdown="onPointerDown"
      @pointerup="onPointerUp"
      @pointermove="onPointerMove"
      @pointerleave="onPointerLeave"
    >
      <!-- 左上角：机型与尺寸 -->
      <div class="vp-head">
        <div class="vp-code">{{ spec.code }}</div>
        <div class="vp-name">{{ spec.name }}</div>
        <div class="vp-dims">
          <span>轴距 <b>{{ wheelbaseMm }}</b> mm</span>
          <span>机长 <b>{{ Math.round(spec.bodyL * 1000) }}</b> mm</span>
          <span>空机 <b>{{ spec.emptyWeightKg }}</b> kg</span>
        </div>
      </div>

      <!-- 右上角：显示模式 -->
      <div class="vp-tools">
        <button class="tool" :class="{ on: explodeTarget > 0.5 }" @click.stop="toggleExplode">
          拆解
        </button>
        <button class="tool" :class="{ on: xray }" @click.stop="toggleTool('xray')">透视</button>
        <button class="tool" :class="{ on: showLabels }" @click.stop="toggleTool('labels')">标注</button>
        <button
          class="tool"
          :class="{ on: isolate, muted: !selectedId }"
          :title="selectedId ? '只显示选中零件' : '先在画面或列表里选中一个零件'"
          @click.stop="toggleTool('isolate')"
        >
          独显
        </button>
        <button class="tool" :class="{ on: autoRotate }" @click.stop="toggleTool('rotate')">自转</button>
        <button class="tool ghost" title="回到默认视角" @click.stop="resetView">复位</button>
      </div>

      <!-- 零件标注：工程图式引线标注，挂牌排在模型外侧，不压住模型 -->
      <div class="label-layer">
        <svg class="leaders">
          <g v-for="l in labels" :key="`ln-${l.id}`">
            <line
              :x1="l.side === 'right' ? l.lx - 5 : l.lx + 5"
              :y1="l.ly"
              :x2="l.ax"
              :y2="l.ay"
              :stroke="l.color"
              stroke-width="1"
              stroke-opacity="0.45"
            />
            <circle :cx="l.ax" :cy="l.ay" r="3" :fill="l.color" stroke="#fff" stroke-width="1.5" />
          </g>
        </svg>
        <button
          v-for="l in labels"
          :key="l.id"
          class="lbl"
          :class="[l.side, { active: l.id === selectedId }]"
          :style="{ left: `${l.lx}px`, top: `${l.ly}px`, color: l.color }"
          @click.stop="select(l.id)"
          @mouseenter="hoverId = l.id"
          @mouseleave="hoverId = null"
        >
          {{ l.name }}
        </button>
      </div>

      <!-- 底部：拆解程度 -->
      <div class="vp-foot">
        <span class="foot-label">拆解程度</span>
        <input
          class="range"
          type="range"
          min="0"
          max="100"
          :value="Math.round(explodeTarget * 100)"
          @input="onExplodeInput"
          @click.stop
        />
        <span class="foot-value">{{ Math.round(explodeTarget * 100) }}%</span>
      </div>

      <div class="vp-hint">拖拽旋转 · 滚轮缩放 · 点零件看作用</div>
    </div>

    <!-- ------------------------------ 部件面板 ------------------------------ -->
    <aside class="side">
      <div class="side-head">
        <div class="side-title">
          <span class="side-count">{{ DRONE_PARTS.length }}</span>
          <span class="side-count-unit">类部件 / {{ pieces }} 个零件</span>
        </div>
        <div class="side-sub">按系统分组；点任意一件，看它为什么必须存在</div>
      </div>

      <div class="chips">
        <button class="chip" :class="{ on: filter === 'all' }" @click="filter = 'all'">
          全部 <em>{{ DRONE_PARTS.length }}</em>
        </button>
        <button
          v-for="s in PART_SYSTEMS"
          :key="s.key"
          class="chip"
          :class="{ on: filter === s.key }"
          :style="filter === s.key ? { color: s.color, borderColor: s.color, background: `${s.color}12` } : {}"
          @click="filter = filter === s.key ? 'all' : s.key"
        >
          {{ s.label }} <em>{{ countOf(s.key) }}</em>
        </button>
      </div>

      <!-- 选中零件的说明卡：作用 / 原理 / 规格 / 与调度的关系 -->
      <div v-if="selectedPart" class="detail">
        <div class="d-top">
          <span
            class="d-sys"
            :style="{ color: selectedColor, background: `${selectedColor}14`, borderColor: `${selectedColor}33` }"
          >
            {{ PART_SYSTEMS.find((s) => s.key === selectedPart?.system)?.label }}
          </span>
          <span v-if="selectedQty > 1" class="d-qty">×{{ selectedQty }}</span>
          <button class="d-close" title="取消选中" @click="select(null)">×</button>
        </div>
        <div class="d-name">
          {{ selectedPart.name }}
          <span class="d-en">{{ selectedPart.en }}</span>
        </div>
        <p class="d-role">{{ selectedPart.role }}</p>
        <p class="d-detail">{{ selectedPart.detail }}</p>
        <div class="d-spec">
          <span class="d-key">规格</span>
          <span class="d-val">{{ selectedSpec }}</span>
        </div>
        <div v-if="selectedPart.dispatch" class="d-dispatch">
          <span class="d-dispatch-label">与调度的关系</span>
          <span>{{ selectedPart.dispatch }}</span>
        </div>
      </div>
      <div v-else class="detail empty">
        <div class="empty-title">每一类部件都对应一条调度规则</div>
        <p>
          电池决定这架机能飞多远，电机与桨决定能扛多重，货舱与锁钩决定这单能不能接、什么时候算送达。
          点画面里的零件，或点下面列表里的一行，这里会给出它的作用、工作原理与失效后果。
        </p>
      </div>

      <!-- 部件清单 -->
      <div class="list">
        <div v-for="group in visibleGroups" :key="group.key" class="group">
          <div class="group-head">
            <i class="group-bar" :style="{ background: group.color }" />
            <span class="group-name">{{ group.label }}</span>
            <span class="group-desc">{{ group.desc }}</span>
          </div>
          <button
            v-for="p in group.parts"
            :key="p.id"
            class="row"
            :class="{ active: selectedId === p.id, hovered: hoverId === p.id }"
            @click="select(p.id)"
            @mouseenter="hoverId = p.id"
            @mouseleave="hoverId = null"
          >
            <i class="row-bar" :style="{ background: group.color }" />
            <span class="row-main">
              <span class="row-name">
                {{ p.name }}
                <em v-if="qtyOf(p, spec) > 1" class="row-qty">×{{ qtyOf(p, spec) }}</em>
              </span>
              <span class="row-role">{{ p.role }}</span>
            </span>
          </button>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.anatomy {
  display: flex;
  height: 100%;
  min-height: 0;
  border: 1px solid #1d3a55;
  border-radius: 10px;
  overflow: hidden;
  background: #0c1b2c;
}

/* ------------------------------ 视口 ------------------------------ */
.viewport {
  position: relative;
  flex: 1;
  min-width: 0;
  /* 深色视口背景：浅灰金属零件在深底上对比正好 */
  background:
    radial-gradient(120% 90% at 62% 18%, #10263c 0%, #0a1a29 45%, #06111c 100%);
  cursor: grab;
  touch-action: none;
}

.viewport:active {
  cursor: grabbing;
}

.vp-head {
  position: absolute;
  left: 16px;
  top: 14px;
  z-index: 2;
  pointer-events: none;
}

.vp-code {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #4fc3ff;
}

.vp-name {
  margin-top: 1px;
  font-size: 15px;
  font-weight: 600;
  color: #d8e8fb;
}

.vp-dims {
  display: flex;
  gap: 12px;
  margin-top: 4px;
  font-size: 11px;
  color: rgba(180, 208, 232, 0.55);
}

.vp-dims b {
  font-weight: 600;
  color: rgba(190, 216, 240, 0.78);
}

.vp-tools {
  position: absolute;
  right: 14px;
  top: 14px;
  z-index: 2;
  display: flex;
  gap: 6px;
}

.tool {
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
  color: rgba(190, 216, 240, 0.78);
  background: rgba(12, 27, 44, 0.82);
  border: 1px solid #1d3a55;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.16s ease;
  backdrop-filter: blur(4px);
}

.tool:hover {
  color: #4fc3ff;
  border-color: rgba(34, 211, 238, 0.45);
}

.tool.on {
  color: #fff;
  background: rgba(34, 211, 238, 0.16);
  border-color: rgba(34, 211, 238, 0.5);
  box-shadow: 0 2px 6px rgba(34, 211, 238, 0.2);
}

.tool.muted {
  opacity: 0.45;
}

.tool.ghost {
  color: rgba(180, 208, 232, 0.55);
}

/* ------------------------------ 标注 ------------------------------ */
.label-layer {
  position: absolute;
  inset: 0;
  z-index: 3;
  pointer-events: none;
  overflow: hidden;
}

.leaders {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

/* 挂牌：右/左两种对齐方式，锚点由 JS 算好后直接用 left/top 定位 */
.lbl {
  position: absolute;
  display: flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  color: inherit;
  background: rgba(12, 27, 44, 0.94);
  border: 1px solid #1d3a55;
  border-radius: 4px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
  cursor: pointer;
  pointer-events: auto;
  transition: background 0.15s ease;
}

.lbl.right {
  transform: translateY(-50%);
}

.lbl.left {
  transform: translate(-100%, -50%);
}

.lbl:hover {
  background: #0c1b2c;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.55);
}

.lbl.active {
  color: #fff !important;
  background: rgba(34, 211, 238, 0.18);
  border-color: rgba(34, 211, 238, 0.5);
}

/* ------------------------------ 底部滑杆 ------------------------------ */
.vp-foot {
  position: absolute;
  left: 50%;
  bottom: 14px;
  z-index: 2;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 14px;
  background: rgba(12, 27, 44, 0.88);
  border: 1px solid #1d3a55;
  border-radius: 999px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(6px);
}

.foot-label {
  font-size: 11px;
  color: rgba(180, 208, 232, 0.55);
}

.foot-value {
  width: 34px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: #4fc3ff;
}

.range {
  width: 160px;
  height: 3px;
  appearance: none;
  background: #16293d;
  border-radius: 2px;
  outline: none;
  cursor: pointer;
}

.range::-webkit-slider-thumb {
  appearance: none;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #4fc3ff;
  border: 2px solid #0c1b2c;
  box-shadow: 0 1px 4px rgba(79, 195, 255, 0.5);
}

.vp-hint {
  position: absolute;
  left: 16px;
  bottom: 16px;
  z-index: 2;
  font-size: 11px;
  /* 操作提示属于"要能读到"的信息，不能和装饰性小字一样压到 0.38 */
  color: rgba(180, 208, 232, 0.66);
  pointer-events: none;
}

/* ------------------------------ 右侧面板 ------------------------------ */
.side {
  display: flex;
  flex-direction: column;
  width: 404px;
  flex: 0 0 404px;
  min-height: 0;
  border-left: 1px solid #16293d;
  background: #0b1a2c;
}

.side-head {
  padding: 14px 16px 10px;
  border-bottom: 1px solid #16293d;
}

.side-title {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.side-count {
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
  color: #4fc3ff;
}

.side-count-unit {
  font-size: 12px;
  color: rgba(190, 216, 240, 0.78);
}

.side-sub {
  margin-top: 4px;
  font-size: 11px;
  color: rgba(180, 208, 232, 0.55);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 16px;
}

.chip {
  height: 24px;
  padding: 0 9px;
  font-size: 11px;
  color: rgba(190, 216, 240, 0.78);
  background: #0c1b2c;
  border: 1px solid #1d3a55;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.chip em {
  font-style: normal;
  opacity: 0.5;
}

.chip:hover {
  border-color: rgba(34, 211, 238, 0.45);
  color: #4fc3ff;
}

.chip.on {
  color: #fff;
  background: rgba(34, 211, 238, 0.16);
  border-color: rgba(34, 211, 238, 0.5);
}

/* ------------------------------ 说明卡 ------------------------------ */
.detail {
  margin: 0 16px 10px;
  padding: 12px 13px;
  background: #0c1b2c;
  border: 1px solid #1d3a55;
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.45);
  max-height: 46%;
  overflow: auto;
}

.d-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.d-sys {
  padding: 0 7px;
  font-size: 11px;
  line-height: 18px;
  border: 1px solid transparent;
  border-radius: 4px;
}

.d-qty {
  font-size: 11px;
  color: rgba(180, 208, 232, 0.55);
}

.d-close {
  margin-left: auto;
  width: 20px;
  height: 20px;
  padding: 0;
  font-size: 15px;
  line-height: 1;
  color: rgba(180, 208, 232, 0.38);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}

.d-close:hover {
  color: #d8e8fb;
  background: rgba(34, 211, 238, 0.1);
}

.d-name {
  margin-top: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #d8e8fb;
}

.d-en {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: rgba(180, 208, 232, 0.38);
}

.d-role {
  margin: 4px 0 0;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
  color: rgba(190, 216, 240, 0.78);
}

.d-detail {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.72;
  color: rgba(180, 208, 232, 0.55);
}

.d-spec {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #16293d;
  font-size: 11px;
}

.d-key {
  flex: 0 0 auto;
  color: rgba(180, 208, 232, 0.38);
}

.d-val {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  color: rgba(190, 216, 240, 0.78);
  line-height: 1.6;
}

.d-dispatch {
  margin-top: 8px;
  padding: 7px 9px;
  font-size: 11.5px;
  line-height: 1.65;
  color: rgba(190, 216, 240, 0.78);
  background: rgba(34, 211, 238, 0.1);
  border-left: 2px solid #4fc3ff;
  border-radius: 0 4px 4px 0;
}

.d-dispatch-label {
  display: block;
  margin-bottom: 1px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #4fc3ff;
  opacity: 0.85;
}

.detail.empty {
  background: #0b1a2c;
  border-style: dashed;
}

.empty-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #d8e8fb;
}

.detail.empty p {
  margin: 5px 0 0;
  font-size: 11.5px;
  line-height: 1.72;
  color: rgba(180, 208, 232, 0.55);
}

/* ------------------------------ 部件列表 ------------------------------ */
.list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 10px 12px;
}

.group + .group {
  margin-top: 6px;
}

.group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 6px 4px;
  position: sticky;
  top: 0;
  background: linear-gradient(#0b1a2c 70%, rgba(11, 26, 44, 0));
  z-index: 1;
}

.group-bar {
  width: 3px;
  height: 11px;
  border-radius: 2px;
}

.group-name {
  font-size: 12px;
  font-weight: 700;
  color: #d8e8fb;
}

.group-desc {
  flex: 1;
  overflow: hidden;
  font-size: 10px;
  color: rgba(180, 208, 232, 0.38);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row {
  display: flex;
  align-items: stretch;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.14s ease;
}

.row:hover,
.row.hovered {
  background: rgba(34, 211, 238, 0.1);
}

.row.active {
  background: rgba(34, 211, 238, 0.12);
  border-color: rgba(34, 211, 238, 0.35);
}

.row-bar {
  flex: 0 0 2px;
  border-radius: 1px;
  opacity: 0.55;
}

.row.active .row-bar,
.row:hover .row-bar {
  opacity: 1;
}

.row-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.row-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #d8e8fb;
}

.row-qty {
  margin-left: 4px;
  font-size: 10px;
  font-weight: 600;
  font-style: normal;
  color: rgba(180, 208, 232, 0.38);
}

.row-role {
  overflow: hidden;
  font-size: 10.5px;
  color: rgba(180, 208, 232, 0.55);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row.active .row-name {
  color: #7ff0ff;
}
</style>
