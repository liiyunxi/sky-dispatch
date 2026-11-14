import type { DroneDetailSpec } from './droneSpec'

/**
 * 整机的部件目录：这架飞机由哪些零件组成，每个零件干什么用。
 *
 * <p><b>为什么把"作用"写进代码而不是画在模型上</b>：三维模型能表达"在哪里、
 * 长什么样"，但表达不了"为什么必须有它"。运营和面试官真正会问的是后者 ——
 * 所以每个部件都配三段文字：
 * <ul>
 *   <li>{@code role}：一句话作用，列表里就能扫完；</li>
 *   <li>{@code detail}：它怎么工作、坏了会怎样，回答"为什么是这个设计"；</li>
 *   <li>{@code dispatch}：它和调度系统的关系 —— 把零件和业务闭环接上，
 *       这一栏是这个视图存在的理由（否则只是一张好看的爆炸图）。</li>
 * </ul>
 *
 * <p>数量 {@code qty} 用 {@code 'arms'} 表示"随机臂数变化"（四/六/八轴），
 * 其余部件是固定数量。
 */

/** 部件所属系统 */
export type PartSystem = 'structure' | 'propulsion' | 'avionics' | 'payload' | 'safety'

export interface PartSystemMeta {
  key: PartSystem
  label: string
  /** 语义色：部件高亮、列表色块、标签描边都用它 */
  color: string
  desc: string
}

/** 五个系统：从"承力"到"保命"，按整机功能分层 */
export const PART_SYSTEMS: PartSystemMeta[] = [
  {
    key: 'structure',
    label: '结构',
    color: '#475569',
    desc: '承力与包覆：把机臂的弯矩收回机身，给电池和飞控一个不受力的家',
  },
  {
    key: 'propulsion',
    label: '动力',
    color: '#1677ff',
    desc: '从电池到桨叶的能量通路：电 → 转速 → 拉力',
  },
  {
    key: 'avionics',
    label: '航电',
    color: '#0891b2',
    desc: '感知、决策与链路：知道自己在哪、要往哪飞、怎么把状态报回去',
  },
  {
    key: 'payload',
    label: '任务载荷',
    color: '#d97706',
    desc: '真正干活的部分：装货、看路、投放、留证',
  },
  {
    key: 'safety',
    label: '安全',
    color: '#dc2626',
    desc: '出问题时兜底：把"掉下来"变成"落下来"',
  },
]

export interface DronePart {
  /** 与三维装配体里的部件 id 一一对应（droneAssembly.ts） */
  id: string
  name: string
  /** 英文名：读型号手册时的对照 */
  en: string
  system: PartSystem
  /** 数量；'arms' = 随机臂数（4/6/8） */
  qty: number | 'arms'
  /** 一句话作用 */
  role: string
  /** 展开说明：怎么工作、失效会怎样 */
  detail: string
  /** 关键规格；需要随机型变化的用函数 */
  spec: string | ((s: DroneDetailSpec) => string)
  /** 与调度系统的关系（最有价值的一栏，没有就留空） */
  dispatch?: string
  /** 是否默认在三维画面里标注（全标会糊成一片，只标关键件） */
  anchor?: boolean
}

export const DRONE_PARTS: DronePart[] = [
  // ------------------------------ 结构 ------------------------------
  {
    id: 'center-frame',
    name: '中心承力板',
    en: 'Center Frame',
    system: 'structure',
    qty: 1,
    role: '全机骨架，把各支机臂的弯矩收进机身',
    detail:
      '机臂根部的弯矩全部经由这块碳板传到机身，而不是压在电池或飞控上。折叠机构每次开合都会在螺栓孔附近累积疲劳，所以航前检查的第一项就是机臂根部有没有裂纹、有没有旷量。',
    spec: 'T700 碳纤维 / 2.5 mm / 静载 ≥ 3 倍最大起飞重量',
    dispatch: '它决定了标称载重能不能兑现：结构疲劳的机器即便电量满格，也不能接满载单。',
    anchor: true,
  },
  {
    id: 'shell-top',
    name: '设备舱盖',
    en: 'Upper Shell',
    system: 'structure',
    qty: 1,
    role: '保护飞控与电池，兼作散热风道的上盖',
    detail:
      '上盖内侧贴铜箔屏蔽层，隔开电机磁场对 IMU 的干扰。做成快拆是为了让地勤在 30 秒内换电池或插调试线 —— 换电时间直接决定一个起降点一天能翻多少架次。',
    spec: 'PC+ABS 阻燃 V0 / 快拆卡扣 / 4 颗定位螺钉',
    dispatch: '舱盖未锁到位会触发航前自检不通过，这架机不会进入"空闲"状态。',
  },
  {
    id: 'shell-bottom',
    name: '下壳体',
    en: 'Lower Shell',
    system: 'structure',
    qty: 1,
    role: '密封电气仓，机腹留出云台与货舱的开孔',
    detail:
      '下壳体同时承托电池仓与货舱挂点，是整机重心最低的一块。它的开孔位置决定了云台能俯仰到什么角度，也决定了货舱能挂多厚的东西。',
    spec: '一体注塑 / IP43 / 底部加强筋',
  },
  {
    id: 'arm',
    name: '折叠机臂',
    en: 'Folding Arm',
    system: 'structure',
    qty: 'arms',
    role: '把电机推到力臂末端：力臂越长越稳，也越费电',
    detail:
      '多旋翼靠力臂长度换控制力矩：轴距每增加 10%，同样的姿态偏差能产生更大的修正力矩，但电机要推更长的力臂，悬停功率也跟着上升。这就是"重载机又大又费电"的物理原因。折叠铰链让整机收纳宽度减半，才装得进配送车的后备厢。',
    spec: (s) =>
      `碳管 φ${Math.round(18 + s.podDistance * 20)} mm / 展开锁死 / 力臂 ${(
        s.podDistance * 1000
      ).toFixed(0)} mm`,
    dispatch: '轴距与桨径共同决定"这架机扛得动多重"，是载重校验的物理来源。',
  },
  {
    id: 'skid',
    name: '起落架滑橇',
    en: 'Landing Skid',
    system: 'structure',
    qty: 2,
    role: '让机腹离地，给云台和货舱留出净空；着陆时吸收冲击',
    detail:
      '滑橇比四点支腿更耐侧风着陆：接地瞬间整个滑橇面接触，不容易单腿插入地面把机身扭翻。橡胶减震垫把着陆冲击的高频分量挡在机体之外，避免 IMU 误判为"碰撞"。',
    spec: (s) => `碳纤维滑橇 + 橡胶减震垫 / 离地净空 ${(s.gearHeight * 1000).toFixed(0)} mm`,
    dispatch: '净空高度决定货舱能挂多厚的包裹，超出净空的订单要转人工改派。',
  },
  {
    id: 'belly-mount',
    name: '机腹快拆挂载板',
    en: 'Belly Mount',
    system: 'structure',
    qty: 1,
    role: '云台、货舱、抛投器的统一机械与供电接口',
    detail:
      '换任务只需要换挂载件，不用换飞机。供电与信号都走这块板上的弹簧针，插上即识别挂载类型与承重等级 —— 这是"一机多用"的实现方式。',
    spec: '4×M3 快拆 / 24 V + 12 V 双路供电 / 承重 35 kg',
    dispatch: '派单时按挂载能力匹配订单：重货单只会派给装了重载货舱的机器。',
  },

  // ------------------------------ 动力 ------------------------------
  {
    id: 'battery',
    name: '智能电池组',
    en: 'Smart Battery',
    system: 'propulsion',
    qty: 1,
    role: '全机唯一能源，BMS 实时上报电压、温度与剩余电量',
    detail:
      'BMS 在单体电压不一致时会主动限流，此时"还剩 30%"可能已经飞不动了 —— 所以调度看的是电压与电量双条件，不是单纯的百分比。低温下放电能力下降，冬季航前需要把电池预热到 15 ℃ 以上。',
    spec: (s) => `6S 高压锂电 / 标称 ${s.ledger.batteryWh} Wh / 循环 300 次`,
    dispatch:
      '派单的第一约束。可用往返半径 = 容量 ÷ 每公里能耗 × (1 − 25% 安全冗余) ÷ 2；电量不够的机器直接进不了候选集。',
    anchor: true,
  },
  {
    id: 'pdb',
    name: '电源分配板',
    en: 'Power Distribution Board',
    system: 'propulsion',
    qty: 1,
    role: '把电池的 22.2 V 分给各路电调，并做电流采样',
    detail:
      '电流采样是"能耗"这个指标的唯一来源：飞控把实时电流对时间积分，得到已用电量。平台侧的能耗曲线、剩余航程估算、乃至"电量优先"派单策略，全都建立在这一次采样上。',
    spec: (s) => `${s.arms} 路输出 / 200 A 采样 / 12 V·5 V 双路 BEC`,
    dispatch: '遥测里的能耗与剩余电量由此而来；采样漂移会让调度高估航程，派出去的机器可能回不来。',
  },
  {
    id: 'esc',
    name: '电调',
    en: 'ESC',
    system: 'propulsion',
    qty: 'arms',
    role: '把直流变成三相驱动，按飞控指令调整转速',
    detail:
      'FOC（磁场定向）正弦驱动让电机在低转速下也平顺，悬停时的细微抖动主要靠它压住。电调过热会先降额、再保护性停转 —— 表现为"越飞越没劲"，而不是直接掉下来。',
    spec: '40 A / FOC 正弦驱动 / 支持再生制动',
    dispatch: '单机电调失效即判定该机进入"维护"，调度器不再派单，直到人工确认。',
  },
  {
    id: 'motor',
    name: '无刷电机',
    en: 'Brushless Motor',
    system: 'propulsion',
    qty: 'arms',
    role: '提供全部升力，单机拉力决定整机载重上限',
    detail:
      '拉力随油门非线性上升，悬停通常只用到 40%~50% 油门，留出的余量用来抗风与姿态修正。四轴失去任意一个电机就必然失稳，所以电机是航前必检项，也是唯一"坏一个就报废整架次"的部件。',
    spec: (s) =>
      `外转子盘式 / KV ${Math.round(70 / s.propRadius)} / 单机拉力 ${(
        (2 * (s.emptyWeightKg + s.ledger.payloadKg)) /
        s.arms
      ).toFixed(1)} kg`,
    dispatch:
      '总拉力的一半 ≈ 可用载重，这个数和 maxPayloadKg 对得上；超载单在派单前就会被载重校验拒掉。',
    anchor: true,
  },
  {
    id: 'prop',
    name: '螺旋桨',
    en: 'Propeller',
    system: 'propulsion',
    qty: 'arms',
    role: '把转速变成拉力；桨径越大，同样转速下效率越高',
    detail:
      '桨叶前缘缺损 10% 就能让拉力掉 15%，还会带来振动 —— 振动会直接污染 IMU 数据，表现为姿态抖动、航向漂移。所以桨是消耗件，按飞行小时强制更换，而不是"看着没坏就继续用"。',
    spec: (s) =>
      `${Math.round((s.propRadius * 2) / 0.0254)} × 4.4 折叠桨 / 正反桨成对 / 桨径 ${(
        s.propRadius * 2000
      ).toFixed(0)} mm`,
    dispatch: '桨况差会让实际能耗高于模型值，是"能耗偏高"告警最常见的物理原因。',
    anchor: true,
  },
  {
    id: 'cable',
    name: '动力线束',
    en: 'Power Harness',
    system: 'propulsion',
    qty: 1,
    role: '电池 → 配电板 → 电调的高电流通路',
    detail:
      '线束走机身内部，避开桨叶扫掠面。插头接触电阻变大会发热，是大电流系统里最常见的隐患 —— 所以电池插头有寿命计数，插拔到次数就换。',
    spec: '12 AWG 硅胶线 / XT90 插头 / 耐温 200 ℃',
  },

  // ------------------------------ 航电 ------------------------------
  {
    id: 'fc',
    name: '飞控计算机',
    en: 'Flight Controller',
    system: 'avionics',
    qty: 1,
    role: '姿态解算、位置控制与任务执行，并把遥测上报给调度平台',
    detail:
      '平台只下发"航点序列"这种意图，每个桨转多快由飞控自己决定 —— 这样链路中断时它仍能按预设航线自主返航，而不是原地悬停等指令。这是"平台管调度、飞控管飞行"的职责边界。',
    spec: '双 IMU 冗余 / 400 Hz 主循环 / 1 Hz 遥测上行',
    dispatch: '遥测（位置 / 电量 / 进度）由它上行；它一旦失效，这架机在地图上就消失了，调度器会把它标为失联。',
    anchor: true,
  },
  {
    id: 'imu',
    name: '惯性测量单元',
    en: 'IMU',
    system: 'avionics',
    qty: 1,
    role: '200 Hz 测量角速度与加速度，是姿态解算的基准',
    detail:
      'IMU 装在减震球上，隔开电机振动。它漂移时最典型的现象是"悬停时缓慢画圈" —— 飞控以为自己在平飞，实际在绕圈，最后靠 GNSS 才拉回来。',
    spec: '双冗余 IMU / 陀螺零偏 0.5 °/h / 减震安装',
  },
  {
    id: 'gnss',
    name: 'GNSS / RTK 天线',
    en: 'GNSS Antenna',
    system: 'avionics',
    qty: 1,
    role: '提供厘米级定位与航向基准',
    detail:
      '双天线测向让飞机静止时也知道机头朝哪，起飞前不必"画八字"校准。城市峡谷里卫星被高楼遮挡时会降级到惯导推算，此时定位误差按秒累积 —— 所以穿越楼群的任务要缩短航点间距。',
    spec: 'GNSS + RTK 双频 / 水平 1 cm + 1 ppm / 双天线定向',
    dispatch: '定位精度决定投放点误差，也是"已到达投放点"这个任务节点的判定依据。',
  },
  {
    id: 'antenna',
    name: '图传 / 数传天线',
    en: 'Antenna',
    system: 'avionics',
    qty: 2,
    role: '维持与地面站的链路，回传遥测与视频',
    detail:
      '两根天线分集接收，机体转向时也不容易断链。被楼体遮挡时先降码率、再断图传 —— 遥测走的是低速链路，通常比视频晚得多才丢。',
    spec: '2.4 / 5.8 GHz 双频 / 全向 / 增益 3 dBi',
    dispatch: '链路质量直接影响遥测频率，信号差的机器在地图上会"跳点"。',
  },
  {
    id: 'radar',
    name: '前向毫米波雷达',
    en: 'Obstacle Radar',
    system: 'avionics',
    qty: 1,
    role: '探测前方障碍，触发绕行或刹停',
    detail:
      '它只回答"前方有没有东西"，不负责规划绕行路线 —— 绕行是飞控按平台下发的航点做的局部避让。毫米波对雨雾和光线都不敏感，是视觉避障失效时的兜底。',
    spec: '24 GHz / 视场 ±30° / 有效 30 m',
    dispatch: '避障绕行会让实际航程超过计划航程，是"预计超时"告警最常见的触发原因。',
    anchor: true,
  },
  {
    id: 'downward-sensor',
    name: '下视感知模组',
    en: 'Downward Vision',
    system: 'avionics',
    qty: 1,
    role: '降落与投放时测距、识别地面纹理',
    detail:
      '投放阶段靠它把包裹放到指定点位，精度约 ±20 cm。地面反光、纯色或水面会让视觉失效，此时退回 ToF 定高 —— 所以投放点选在硬化地面而不是草地。',
    spec: '双目视觉 + ToF / 0.2 ~ 8 m / 定位精度 ±20 cm',
    dispatch: '投放确认（任务完成回执）依赖它与云台相机的双重校验。',
  },
  {
    id: 'lights',
    name: '航行灯',
    en: 'Navigation Lights',
    system: 'avionics',
    qty: 3,
    role: '左红右绿尾白，夜航与目视识别',
    detail:
      '航空惯例：从后方看左红右绿。夜间作业时，地面人员靠它判断机头朝向；频闪模式还能让人在背景灯光里一眼把飞机挑出来。',
    spec: 'LED / 亮度 100 cd / 闪烁模式可配',
    dispatch: '夜航时段的派单会校验航行灯状态，灯故障的机器不参与夜间排班。',
  },
  {
    id: 'fan',
    name: '散热风扇',
    en: 'Cooling Fan',
    system: 'avionics',
    qty: 1,
    role: '电池仓强制风冷，把大电流放电的热量带走',
    detail:
      '重载爬升时电池温升最快，风扇转速跟着温度走。高温会触发降功率保护，表现为"爬升无力" —— 不是电机坏了，是电池被限流了。',
    spec: '30 mm 涡轮 / 温控调速 / 独立风道',
    dispatch: '高温天气持续满载派单，会显著缩短电池寿命，进而抬高整机维护成本。',
  },

  // ------------------------------ 任务载荷 ------------------------------
  {
    id: 'gimbal',
    name: '三轴增稳云台相机',
    en: '3-Axis Gimbal',
    system: 'payload',
    qty: 1,
    role: '投放点识别、投放确认与事件取证',
    detail:
      '航向 / 横滚 / 俯仰三轴各自独立电机，机体倾斜时镜头保持水平，画面才可用于识别与取证。俯仰轴下翻到 −90° 就是垂直向下看投放点。',
    spec: '三轴机械增稳 / 4K 广角 + 红外 / 俯仰 −90° ~ +30°',
    dispatch: '任务完成回执里的"投放确认"就是它拍的；异常事件也用它留证，是纠纷时的唯一依据。',
    anchor: true,
  },
  {
    id: 'cargo-hold',
    name: '货舱',
    en: 'Cargo Bay',
    system: 'payload',
    qty: 1,
    role: '固定包裹，防止飞行中位移改变重心',
    detail:
      '包裹必须被约束在货舱几何中心附近 —— 重心偏移会让飞控持续修正，能耗上升、姿态发散。内衬缓冲同时兼作防水，雨天配送才不用换机型。',
    spec: (s) => `快拆货舱 / 最大 ${s.ledger.payloadKg} kg / 内衬缓冲 + 防水`,
    dispatch: '订单的重量与尺寸都要与货舱容积匹配后才允许派单，装不下的单直接拒派。',
    anchor: true,
  },
  {
    id: 'lock-hook',
    name: '电磁锁钩',
    en: 'Release Hook',
    system: 'payload',
    qty: 1,
    role: '到达投放点后释放挂载，失电保持',
    detail:
      '"失电保持"是安全设计：断电时钩子仍然锁住，包裹不会在半路掉下去。只有收到明确的投放指令才解锁 —— 宁可把货带回来，也不能在错误的位置松手。',
    spec: '电磁铁 + 机械自锁 / 保持力 60 kg / 12 V',
    dispatch: '投放动作由任务状态机触发；投放成功回执到达后，订单才置为"已送达"。',
    anchor: true,
  },
  {
    id: 'cargo-door',
    name: '货舱门',
    en: 'Cargo Door',
    system: 'payload',
    qty: 2,
    role: '装载后封闭货舱，投放时开合',
    detail:
      '舱门与锁钩联动：门未关到位，飞控拒绝起飞。舵机带防夹检测，夹住包裹会回弹并上报"装载异常"，而不是硬顶着关门把包裹挤坏。',
    spec: '舵机驱动 / 开合 0.6 s / 防夹检测',
    dispatch: '装载阶段的状态会回传；卡住时任务停在"装载中"，不会进入"配送中"。',
  },

  // ------------------------------ 安全 ------------------------------
  {
    id: 'parachute',
    name: '应急降落伞',
    en: 'Parachute',
    system: 'safety',
    qty: 1,
    role: '失控时开伞，把对地速度降到 5 m/s 以内',
    detail:
      '触发条件是姿态发散或双 IMU 失效。开伞后飞机不可控，只能等它落地 —— 所以它是最后一道防线，不是常规手段。最低开伞高度 15 m：再低伞来不及充气。',
    spec: '弹射式 / 开伞 0.8 s / 最低开伞高度 15 m',
    dispatch: '超视距、跨越道路或人群密集区的航线，合规上要求随机携带降落伞，否则航线审批不过。',
    anchor: true,
  },
  {
    id: 'buzzer',
    name: '蜂鸣器与频闪',
    en: 'Buzzer & Strobe',
    system: 'safety',
    qty: 1,
    role: '迫降后发出声光信号，便于地面搜寻',
    detail:
      '独立于主电源，即使电池被抛掉也能靠备用电池工作 30 分钟。频闪比蜂鸣器更远可见，所以两者同时工作 —— 白天靠烟/反光，晚上靠频闪。',
    spec: '95 dB / 1 Hz 频闪 / 独立备用电池',
    dispatch: '失联告警时，运维按最后上报坐标 + 声光信号寻找机体。',
  },
]

/** 按 id 取部件（列表与三维互相跳转时用） */
export const PART_BY_ID: Record<string, DronePart> = Object.fromEntries(
  DRONE_PARTS.map((p) => [p.id, p]),
)

/** 解析某部件在某机型上的实际数量 */
export function qtyOf(part: DronePart, spec: DroneDetailSpec): number {
  return part.qty === 'arms' ? spec.arms : part.qty
}

/** 解析规格文案（固定文案与随机型变化的文案统一成字符串） */
export function specTextOf(part: DronePart, spec: DroneDetailSpec): string {
  return typeof part.spec === 'function' ? part.spec(spec) : part.spec
}

/** 某系统的语义色 */
export function systemColor(system: PartSystem): string {
  return PART_SYSTEMS.find((s) => s.key === system)?.color ?? '#475569'
}

/** 分母：整机零件总数（把"26 类部件"翻译成"多少个零件"更有体感） */
export function totalPieceCount(spec: DroneDetailSpec): number {
  return DRONE_PARTS.reduce((sum, p) => sum + qtyOf(p, spec), 0)
}
