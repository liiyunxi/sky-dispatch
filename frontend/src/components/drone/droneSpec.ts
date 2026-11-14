/**
 * 机型的"工程图纸级"规格：真实外形尺寸、涂装色与台账能力。
 *
 * <p><b>为什么要单独维护一份更细的规格</b>：{@code droneGeometry.ts} 里的
 * {@code AirframeSpec} 服务的是"态势地图里二十多架飞机同时在场"的场景，只求外形轮廓
 * 能区分机型；而 {@code DroneAnatomy.vue} 是一架飞机占满屏幕、要看清一颗螺钉的
 * 解剖视图，两者对精度的要求差一个数量级。硬塞进同一个结构会让地图视图被迫
 * 加载用不上的细节，所以这里单独放一份。
 *
 * <p><b>尺寸单位统一为米</b>，与真实机体一致：这样"轴距 0.55 m""桨径 0.33 m"
 * 直接来自几何参数本身，画面上量到的比例就是真机的比例，不需要另做换算。
 *
 * <p><b>能力参数与后端 {@code drone_model} 表严格一致</b>（载重 / 巡航 / 续航 /
 * 电池 / 能耗）。解剖视图里讲"电池决定航程"时，引用的必须是调度器真正用的那组数字，
 * 否则画面讲一套、派单算一套，等于自相矛盾。
 */

/** 机身形态：轻量紧凑 / 六边机身 / 重载方舱 */
export type BodyStyle = 'compact' | 'hex' | 'heavy'

/** 台账里的能力参数（与 {@code drone_model} 表同源） */
export interface LedgerSpec {
  /** 最大载重(kg) */
  payloadKg: number
  /** 巡航速度(m/s) */
  cruiseMps: number
  /** 满电最长飞行(min) */
  flightMin: number
  /** 电池容量(Wh) */
  batteryWh: number
  /** 巡航能耗(Wh/km) */
  energyPerKmWh: number
  /** 悬停功率(W) */
  hoverPowerW: number
  /** 最大升限(m) */
  ceilingM: number
}

/** 一架飞机的完整三维规格 */
export interface DroneDetailSpec {
  code: string
  name: string
  style: BodyStyle
  /** 机臂数量（= 电机数 = 桨数 = 电调数） */
  arms: number
  /** 机身中心到电机轴的水平距离(m)；轴距 = 2 × podDistance */
  podDistance: number
  /** 机身宽（左右，X 向） */
  bodyW: number
  /** 机身长（前后，Z 向，+Z 为机头） */
  bodyL: number
  /** 机身厚（Y 向，不含起落架与云台） */
  bodyH: number
  /** 单支桨叶长度(m) */
  propRadius: number
  /** 桨叶弦长(m) */
  propChord: number
  /** 两根滑橇的左右间距(m) */
  skidSpread: number
  /** 滑橇前后长度(m) */
  skidLength: number
  /** 机腹离地高度(m) */
  gearHeight: number
  /** 空机自重(kg)：不是台账字段，用于"自重 + 载重 vs 总拉力"的说明 */
  emptyWeightKg: number
  /** 壳体主色（浅色工程塑料/复材蒙皮） */
  shell: number
  /** 壳体亮面（上盖、舱盖） */
  panel: number
  /** 石墨深色（机臂、结构件、设备外壳） */
  graphite: number
  /** 型号强调色（装饰条、锁扣、标识） */
  accent: number
  ledger: LedgerSpec
}

/** 三种在册机型的详细规格 */
export const DRONE_DETAILS: Record<string, DroneDetailSpec> = {
  // 轻量四旋翼：机身紧凑，机臂短，冷灰蓝涂装
  'SKY-X4': {
    code: 'SKY-X4',
    name: '天巡 X4 轻量配送机',
    style: 'compact',
    arms: 4,
    podDistance: 0.275,
    bodyW: 0.21,
    bodyL: 0.3,
    bodyH: 0.082,
    propRadius: 0.15,
    propChord: 0.03,
    skidSpread: 0.1,
    skidLength: 0.24,
    gearHeight: 0.075,
    emptyWeightKg: 4.2,
    shell: 0xdfe5ee,
    panel: 0xf2f5f9,
    graphite: 0x2b3038,
    accent: 0x1677ff,
    ledger: {
      payloadKg: 5,
      cruiseMps: 15,
      flightMin: 35,
      batteryWh: 1300,
      energyPerKmWh: 42,
      hoverPowerW: 900,
      ceilingM: 120,
    },
  },
  // 中型六旋翼：六边形机身 + 圆顶，青绿涂装，机身更厚（塞得下更大的电池仓）
  'SKY-M8': {
    code: 'SKY-M8',
    name: '天巡 M8 中型配送机',
    style: 'hex',
    arms: 6,
    podDistance: 0.45,
    bodyW: 0.29,
    bodyL: 0.38,
    bodyH: 0.108,
    propRadius: 0.198,
    propChord: 0.036,
    skidSpread: 0.135,
    skidLength: 0.3,
    gearHeight: 0.1,
    emptyWeightKg: 11.5,
    shell: 0xd6e4e6,
    panel: 0xeef7f7,
    graphite: 0x26302f,
    accent: 0x13c2c2,
    ledger: {
      payloadKg: 15,
      cruiseMps: 18,
      flightMin: 45,
      batteryWh: 2800,
      energyPerKmWh: 58,
      hoverPowerW: 1600,
      ceilingM: 150,
    },
  },
  // 重载八旋翼：方舱式机身、外挂电池包、宽起落架，沙色 + 橙色警示涂装
  'SKY-H2': {
    code: 'SKY-H2',
    name: '天巡 H2 重载配送机',
    style: 'heavy',
    arms: 8,
    podDistance: 0.625,
    bodyW: 0.4,
    bodyL: 0.52,
    bodyH: 0.16,
    propRadius: 0.228,
    propChord: 0.042,
    skidSpread: 0.175,
    skidLength: 0.4,
    gearHeight: 0.13,
    emptyWeightKg: 24,
    shell: 0xe6e0d6,
    panel: 0xf7f3ea,
    graphite: 0x332f28,
    accent: 0xfa8c16,
    ledger: {
      payloadKg: 30,
      cruiseMps: 12,
      flightMin: 30,
      batteryWh: 2000,
      energyPerKmWh: 95,
      hoverPowerW: 2800,
      ceilingM: 180,
    },
  },
}

/** 未知型号的兜底：按轻量四旋翼画（宁可普通，不要空白） */
const FALLBACK = DRONE_DETAILS['SKY-X4']

/** 取某型号的详细规格 */
export function detailOf(modelCode?: string | null): DroneDetailSpec {
  if (!modelCode) return FALLBACK
  return DRONE_DETAILS[modelCode] ?? FALLBACK
}

/** 轴距（对角两电机轴之间的距离，米）——运营口里的"这架机多大" */
export function wheelbaseOf(spec: DroneDetailSpec): number {
  return spec.podDistance * 2
}

/** 含桨的整机最大外形尺寸（米） */
export function spanOf(spec: DroneDetailSpec): number {
  return (spec.podDistance + spec.propRadius) * 2
}

/** 桨径（英寸）：0.163m × 2 ÷ 0.0254 ≈ 12.8 → 13 寸 */
export function propInchOf(spec: DroneDetailSpec): number {
  return Math.round((spec.propRadius * 2) / 0.0254)
}
