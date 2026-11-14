import L from 'leaflet'
import type { DroneFrame, DroneStatus } from '@/api'
import { DRONE_STATUS_HEX } from '@/utils/format'

/**
 * 四旋翼无人机图标（俯视图）。
 *
 * 设计要点：
 *  1. **俯视四旋翼造型**：机身 + 四条机臂 + 四个旋翼 + 云台。
 *     圆点只能表达"有个东西在这里"，而四旋翼一眼就能看出朝向、机型和姿态。
 *  2. **按航向偏航**：外层 `div.drone-rot` 做 CSS `rotate(heading)`，
 *     机头在 SVG 里朝上，因此旋转后机头始终指向飞行方向 —— 这是"在飞"最直观的证据。
 *  3. **旋翼旋转动画**：旋翼画成两叶薄片并持续旋转（并给四个旋翼不同相位），
 *     悬停/飞行时看起来"活着"，而不是一张贴图。
 *  4. **状态用颜色表达**：机身主色跟随无人机状态（空闲绿 / 飞行蓝 / 返航橙 / 维护黄）；
 *     低电量时机身外圈红色脉冲，载货时机腹出现货箱。
 *  5. **飞行姿态真实**：俯视图里"俯仰/横滚"物理上表现为**透视压缩** ——
 *     横滚（转弯倾斜）把机身沿横向压扁，俯仰（爬升/下降前倾）沿纵向压扁。
 *     真实倾角在俯视下几乎看不出，所以加了一个制图夸张系数让它"读得出来"。
 *     同时旋翼转速随速度变化（巡航快、悬停慢），机腹阴影随高度淡出、缩小、错开。
 *
 * 性能取舍：DOM + SVG 图标在几十架规模下完全够用；如果机上到 60 架以上，
 * DroneMap 会自动退回 Canvas 圆点渲染（见 DroneMap.vue 的 SIMPLE_MODE_THRESHOLD）。
 */

/** 图标显示尺寸（像素）。Leaflet 用它来定位与命中测试。 */
export const DRONE_ICON_SIZE = 46

/** 机头朝向：heading 为 0 时指向正北（SVG 里机头朝上） */
const NOSE_UP_OFFSET = 0

/** 倾角上限（度）：防止姿态解算的瞬时尖峰把机身压成一条线 */
const MAX_TILT_DEG = 42

/**
 * 制图夸张系数。
 *
 * 真实巡航倾角只有 5°~15°，横滚通常 < 30°，俯视图下压缩不到 5%，肉眼根本看不出来。
 * 乘上这个系数后，转弯时能明显看到机身"扁"下去 —— 这是**有意的可视化夸张**，
 * 与地图上把道路画得比实际更粗是同一种取舍。
 */
const ATTITUDE_EXAGGERATION = 1.35

/**
 * 一帧的飞行姿态（由 DroneMap 的姿态解算给出）。
 *
 * 说明：这些量**不是**后端契约字段，而是前端从位置/航向/高度的时序变化里
 * 反推出来的 —— 服务端只负责"飞机在哪、多快、多高"，"姿态好不好看"属于渲染层职责。
 */
export interface DroneAttitude {
  /** 俯仰（度）：正 = 抬头（爬升），负 = 低头（下降） */
  pitchDeg: number
  /** 横滚（度）：正 = 右倾（右转），负 = 左倾 */
  rollDeg: number
  /** 旋翼转速等级 0~1：0 = 悬停最慢，1 = 巡航最快 */
  rotorLevel: number
  /** 已插值平滑的高度（米），用于缩放与阴影 */
  altitudeM: number
}

/**
 * 生成无人机的 HTML 结构。
 *
 * 只生成一次，之后通过 `applyDroneFrame` 改样式属性 ——
 * 绝不在每帧重建 innerHTML（那会让浏览器反复解析 SVG，几十架就会卡）。
 */
export function buildDroneIconHtml(): string {
  return `
<div class="drone-rot" style="transform: rotate(0deg)">
  <svg class="drone-svg" viewBox="0 0 64 64" width="${DRONE_ICON_SIZE}" height="${DRONE_ICON_SIZE}">
    <!-- 低电量脉冲圈 -->
    <circle class="drone-alert" cx="32" cy="32" r="29" />

    <!-- 四条机臂 -->
    <g class="drone-arms">
      <line x1="32" y1="32" x2="14" y2="14" />
      <line x1="32" y1="32" x2="50" y2="14" />
      <line x1="32" y1="32" x2="14" y2="50" />
      <line x1="32" y1="32" x2="50" y2="50" />
    </g>

    <!-- 四个旋翼（两叶薄片 + 持续旋转） -->
    <g class="drone-rotor drone-rotor-1" transform="translate(14,14)"><ellipse rx="10" ry="2.1" /></g>
    <g class="drone-rotor drone-rotor-2" transform="translate(50,14)"><ellipse rx="10" ry="2.1" /></g>
    <g class="drone-rotor drone-rotor-3" transform="translate(14,50)"><ellipse rx="10" ry="2.1" /></g>
    <g class="drone-rotor drone-rotor-4" transform="translate(50,50)"><ellipse rx="10" ry="2.1" /></g>

    <!-- 机腹阴影：给出"离地高度"的视觉线索 -->
    <ellipse class="drone-shadow" cx="32" cy="36" rx="13" ry="4" />

    <!-- 机身（含机头） -->
    <path class="drone-body" d="M32 17 L40 25 L40 38 Q40 45 32 47 Q24 45 24 38 L24 25 Z" />
    <path class="drone-nose" d="M32 17 L37 23 L27 23 Z" />

    <!-- 云台相机 -->
    <circle class="drone-gimbal" cx="32" cy="40" r="3.4" />

    <!-- 货箱：仅在载货时显示 -->
    <rect class="drone-payload" x="25.5" y="45.5" width="13" height="9" rx="1.6" />
  </svg>
</div>`.trim()
}

/** 创建 Leaflet 图标对象。四个旋翼相位不同，避免整齐划一显得假。 */
export function createDroneDivIcon(): L.DivIcon {
  return L.divIcon({
    html: buildDroneIconHtml(),
    className: 'drone-marker-host',
    iconSize: [DRONE_ICON_SIZE, DRONE_ICON_SIZE],
    iconAnchor: [DRONE_ICON_SIZE / 2, DRONE_ICON_SIZE / 2],
    popupAnchor: [0, -DRONE_ICON_SIZE / 2],
  })
}

/**
 * 缓存每个图标里负责旋转的内层元素。
 *
 * 动画循环每秒要调用三十次 `applyDroneFrame`，每次都 `querySelector` 是浪费
 * （几十架飞机就是每秒上千次 DOM 查询）。用 WeakMap 缓存：
 * 标记被移除后条目会被自动回收，不会造成内存泄漏。
 */
const rotElementCache = new WeakMap<HTMLElement, HTMLElement | null>()

function rotElementOf(host: HTMLElement): HTMLElement | null {
  if (!rotElementCache.has(host)) {
    rotElementCache.set(host, host.querySelector<HTMLElement>('.drone-rot'))
  }
  return rotElementCache.get(host) ?? null
}

/**
 * 缓存「旋翼转速档位」。
 *
 * 为什么不用连续值？因为改 `animation-duration` 会让正在跑的 CSS 动画按新时长
 * 重新映射进度，连续微调会看到旋翼"一顿一顿"。量化成 7 档、只有跨档才写，
 * 抖动就消失了 —— 视觉上依然能看出"加速时旋翼变快"。
 */
const rotorBucketCache = new WeakMap<HTMLElement, number>()

function clampAbs(value: number, limit: number): number {
  if (value > limit) {
    return limit
  }
  return value < -limit ? -limit : value
}

/**
 * 把一帧遥测应用到已有图标元素上（增量更新，不重建 DOM）。
 *
 * @param host     Leaflet 图标的根元素（`marker.getElement()`）
 * @param frame    最新遥测
 * @param heading  已插值平滑的航向（度）
 * @param attitude 已插值平滑的飞行姿态（俯仰 / 横滚 / 旋翼转速 / 高度）
 */
export function applyDroneFrame(
  host: HTMLElement,
  frame: DroneFrame,
  heading: number,
  attitude: DroneAttitude,
): void {
  const altitude = attitude.altitudeM

  // 高度 → 轻微放大，制造"飞得更高"的视觉线索。
  // 注意：缩放必须和旋转写在同一个 transform 里。
  // Leaflet 会给图标根元素写 translate3d 做定位，所以根元素上不能有 transform；
  // 旋转与缩放都落在内层 .drone-rot 上，互不干扰。
  const altitudeScale = 1 + Math.min(altitude, 200) / 900

  // 俯仰/横滚 → 俯视透视压缩：纵向（机头方向）压扁 = 俯仰，横向压扁 = 横滚。
  // 两轴分别缩放，`rotate(heading)` 之后这两个轴就是机体的纵轴与横轴。
  const roll = clampAbs(attitude.rollDeg, MAX_TILT_DEG)
  const pitch = clampAbs(attitude.pitchDeg, MAX_TILT_DEG)
  const scaleX = altitudeScale * Math.cos((roll * ATTITUDE_EXAGGERATION * Math.PI) / 180)
  const scaleY = altitudeScale * Math.cos((pitch * ATTITUDE_EXAGGERATION * Math.PI) / 180)

  const rot = rotElementOf(host)
  if (rot) {
    rot.style.transform =
      `rotate(${(heading + NOSE_UP_OFFSET).toFixed(1)}deg) ` +
      `scale(${scaleX.toFixed(3)}, ${scaleY.toFixed(3)})`
  }

  // 状态色走 CSS 变量，改一个变量就能让 SVG 里所有引用它的部件一起变色
  const color: string = DRONE_STATUS_HEX[frame.status as DroneStatus] ?? '#1677ff'
  host.style.setProperty('--drone-color', color)

  // 旋翼转速：悬停慢、巡航快（量化成 7 档，见 rotorBucketCache 的说明）
  const level = attitude.rotorLevel < 0 ? 0 : attitude.rotorLevel > 1 ? 1 : attitude.rotorLevel
  const bucket = Math.round(level * 6)
  if (rotorBucketCache.get(host) !== bucket) {
    rotorBucketCache.set(host, bucket)
    host.style.setProperty('--rotor-duration', (0.3 - (bucket / 6) * 0.18).toFixed(3) + 's')
  }

  // 机腹阴影：越高越淡、越小、越往机身后方错开 —— 三件事一起才像"离地很高"
  const altNorm = Math.min(altitude / 120, 1)
  host.style.setProperty('--shadow-scale', (1 - 0.38 * altNorm).toFixed(3))
  host.style.setProperty('--shadow-opacity', (1 - 0.6 * altNorm).toFixed(3))
  host.style.setProperty('--shadow-shift-y', (altNorm * 3.4).toFixed(2) + 'px')
  host.style.setProperty('--shadow-shift-x', (roll * 0.05).toFixed(2) + 'px')

  const battery = frame.batteryPct ?? 100
  host.classList.toggle(
    'is-low-battery',
    battery < 25 && frame.status !== 'IDLE' && frame.status !== 'CHARGING',
  )
  host.classList.toggle('is-airborne', altitude > 1)
  // 有任务 = 载货；返航阶段已经卸货，所以排除 RETURNING
  const carrying = Boolean(frame.taskId) && frame.status !== 'RETURNING'
  host.classList.toggle('is-loaded', carrying)
}

/** 角度插值：始终走最短弧，避免从 350° 转到 10° 时反向绕一大圈 */
export function lerpAngle(from: number, to: number, t: number): number {
  const delta = ((to - from + 540) % 360) - 180
  return (from + delta * t + 360) % 360
}

/**
 * 计算"航向锥"的顶点：从当前位置沿航向前方张开一个扇面。
 * 用于选中无人机时显示它的前方视野/航向意图（纯视觉提示，非真实传感器模型）。
 */
export function headingConeLatLngs(
  lat: number,
  lon: number,
  headingDeg: number,
  lengthM: number,
  spreadDeg = 34,
): Array<[number, number]> {
  const points: Array<[number, number]> = [[lat, lon]]
  const steps = 8
  for (let i = 0; i <= steps; i++) {
    const angle = headingDeg - spreadDeg / 2 + (spreadDeg * i) / steps
    const rad = (angle * Math.PI) / 180
    const dLat = (Math.cos(rad) * lengthM) / 111320
    const dLon = (Math.sin(rad) * lengthM) / (111320 * Math.cos((lat * Math.PI) / 180))
    points.push([lat + dLat, lon + dLon])
  }
  return points
}
