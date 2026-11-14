import * as THREE from 'three'

/**
 * 无人机的程序化几何体（不加载任何外部模型文件，保证离线可用）。
 *
 * <p><b>为什么单独抽一个模块</b>：同一架飞机有两个使用场景，
 *  - {@code DroneModel3D.vue}：机队页的"抓取货物"动画，一架飞机占满整屏；
 *  - {@code Scene3DView.vue}：态势地图的立体视图，二十多架飞机同时在场。
 * 如果两边各写一份几何体，改一次机臂长度就得改两个地方，迟早对不上。
 *
 * <p><b>为什么按型号区分外形</b>：三种机型的载重与任务完全不同，
 * 长得一模一样会让看板与 3D 演示失去辨识度 —— 运营无法从画面区分"哪架是重载机"。
 * 所以这里按 {@link AIRFRAMES} 参数化：轻量四旋翼、中型六旋翼、重载八旋翼，
 * 机臂数量、机身尺寸、起落架、货舱数都随型号变化。
 * 未知型号退回通用四旋翼（宁可画得普通，也不要画不出来）。
 */

/** 机型 → 外形规格 */
export interface AirframeSpec {
  /** 机臂数量（= 旋翼数量） */
  arms: number
  /** 机身中心到电机座（旋翼）的水平距离 */
  podDistance: number
  bodyWidth: number
  bodyHeight: number
  bodyDepth: number
  bladeLength: number
  skidLength: number
  /** 两根滑橇的左右间距 */
  skidSpread: number
  bodyColor: number
  accentColor: number
  /** 机腹货舱数量：重载机是双货舱 */
  cargoBays: number
  /**
   * 放进演示场景时的整体缩放。
   *
   * <p>重载机真实尺寸约为轻量机的 1.7 倍，直接同尺度摆放会把画面撑爆，
   * 所以大机型缩一点 —— 保留"更重更大"的相对感觉，又不至于放不下。
   */
  sceneScale: number
}

/** 三种在册机型的外形（与后端 DroneModel 的 code 一一对应） */
export const AIRFRAMES: Record<string, AirframeSpec> = {
  // 轻量配送机：紧凑四旋翼，小机身细机臂，蓝色装饰
  'SKY-X4': {
    arms: 4,
    podDistance: 0.98,
    bodyWidth: 0.78,
    bodyHeight: 0.22,
    bodyDepth: 0.86,
    bladeLength: 0.92,
    skidLength: 0.72,
    skidSpread: 0.26,
    bodyColor: 0x334155,
    accentColor: 0x1677ff,
    cargoBays: 1,
    sceneScale: 1,
  },
  // 中型长航程机：六旋翼，机身更厚、机臂更长，青色装饰
  'SKY-M8': {
    arms: 6,
    podDistance: 1.28,
    bodyWidth: 1.02,
    bodyHeight: 0.3,
    bodyDepth: 1.1,
    bladeLength: 1.0,
    skidLength: 0.86,
    skidSpread: 0.34,
    bodyColor: 0x1f2937,
    accentColor: 0x13c2c2,
    cargoBays: 1,
    sceneScale: 0.84,
  },
  // 重载机：八旋翼、宽起落架、双货舱，橙色装饰（与"重载/警示"的语义色一致）
  'SKY-H2': {
    arms: 8,
    podDistance: 1.66,
    bodyWidth: 1.3,
    bodyHeight: 0.4,
    bodyDepth: 1.46,
    bladeLength: 1.1,
    skidLength: 1.1,
    skidSpread: 0.46,
    bodyColor: 0x3b3a36,
    accentColor: 0xfa8c16,
    cargoBays: 2,
    sceneScale: 0.72,
  },
}

/** 未知型号的兜底外形：通用四旋翼 */
const DEFAULT_AIRFRAME: AirframeSpec = AIRFRAMES['SKY-X4']

/** 取某型号的外形规格；未知型号或未给型号时退回通用四旋翼 */
export function airframeOf(modelCode?: string | null): AirframeSpec {
  if (!modelCode) return DEFAULT_AIRFRAME
  return AIRFRAMES[modelCode] ?? DEFAULT_AIRFRAME
}

/** 一架飞机拆出来的可动部件 */
export interface DroneModelParts {
  /** 整机（机身 / 机臂 / 电机座 / 旋翼 / 起落架 / 云台 / 电池） */
  group: THREE.Group
  /** 各旋翼（绕自身 Y 轴自转） */
  rotors: THREE.Group[]
  /** 旋翼下方的旋转模糊盘（转速越高越明显） */
  rotorDiscs: THREE.Mesh[]
  /** 本机使用的外形规格（调用方需要 sceneScale 时不必再查一次表） */
  spec: AirframeSpec
}

/**
 * 造一架无人机。几何尺寸单位≈米，机身朝 +Z（机头方向）。
 *
 * @param modelCode 机型代码（SKY-X4 / SKY-M8 / SKY-H2）；未知型号退回通用四旋翼
 */
export function createDroneModel(modelCode?: string | null): DroneModelParts {
  const spec = airframeOf(modelCode)
  const group = new THREE.Group()

  // 自发光只给一点点（0.18）：机身配色本来就是深色，深色场景里纯靠灯光会压成剪影，
  // 抬一档自发光能看出机身的转折面，又不至于变成"发光玩具"
  const bodyMat = new THREE.MeshStandardMaterial({
    color: spec.bodyColor,
    roughness: 0.45,
    metalness: 0.35,
    emissive: spec.bodyColor,
    emissiveIntensity: 0.18,
  })
  const darkMat = new THREE.MeshStandardMaterial({ color: 0x1e293b, roughness: 0.6, metalness: 0.2 })
  const accentMat = new THREE.MeshStandardMaterial({ color: spec.accentColor, roughness: 0.4, metalness: 0.3 })
  const rotorMat = new THREE.MeshStandardMaterial({ color: 0x0f172a, roughness: 0.35, metalness: 0.4 })

  // ---- 机身：四旋翼用扁方盒，六旋翼用六边形机身，八旋翼用厚重方舱 ----
  if (spec.arms === 6) {
    const body = new THREE.Mesh(
      new THREE.CylinderGeometry(spec.bodyWidth * 0.62, spec.bodyWidth * 0.66, spec.bodyHeight, 6),
      bodyMat,
    )
    body.position.y = 0.1
    group.add(body)
  } else {
    const body = new THREE.Mesh(
      new THREE.BoxGeometry(spec.bodyWidth, spec.bodyHeight, spec.bodyDepth),
      bodyMat,
    )
    body.position.y = 0.1
    group.add(body)
  }

  // 顶盖（型号色：一眼区分机型）——重载机做成装甲板，中型机做成圆顶，轻量机做成窄条
  if (spec.arms === 8) {
    const deck = new THREE.Mesh(
      new THREE.BoxGeometry(spec.bodyWidth * 0.86, spec.bodyHeight * 0.5, spec.bodyDepth * 0.86),
      accentMat,
    )
    deck.position.y = 0.1 + spec.bodyHeight * 0.6
    group.add(deck)
    // 两侧外露电池包：重载机的典型特征
    ;[-1, 1].forEach((side) => {
      const pack = new THREE.Mesh(
        new THREE.BoxGeometry(spec.bodyWidth * 0.2, spec.bodyHeight * 0.8, spec.bodyDepth * 0.5),
        darkMat,
      )
      pack.position.set(side * spec.bodyWidth * 0.62, 0.1, 0)
      group.add(pack)
    })
  } else if (spec.arms === 6) {
    const dome = new THREE.Mesh(
      new THREE.SphereGeometry(spec.bodyWidth * 0.34, 18, 12, 0, Math.PI * 2, 0, Math.PI / 2),
      accentMat,
    )
    dome.position.y = 0.1 + spec.bodyHeight * 0.5
    group.add(dome)
  } else {
    const canopy = new THREE.Mesh(
      new THREE.BoxGeometry(spec.bodyWidth * 0.64, spec.bodyHeight * 0.55, spec.bodyDepth * 0.65),
      accentMat,
    )
    canopy.position.y = 0.1 + spec.bodyHeight * 0.7
    group.add(canopy)
  }

  // 电池（机腹可见）
  const battery = new THREE.Mesh(
    new THREE.BoxGeometry(spec.bodyWidth * 0.54, spec.bodyHeight * 0.45, spec.bodyDepth * 0.35),
    darkMat,
  )
  battery.position.set(0, -0.06, spec.bodyDepth * 0.23)
  group.add(battery)

  // 云台相机（机头下方，所有机型都有）
  const gimbalArm = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.05, 0.08), darkMat)
  gimbalArm.position.set(0, -0.08, spec.bodyDepth * 0.42)
  group.add(gimbalArm)
  const cameraBall = new THREE.Mesh(
    new THREE.SphereGeometry(0.075, 16, 12),
    new THREE.MeshStandardMaterial({ color: 0x111827, roughness: 0.25, metalness: 0.6 }),
  )
  cameraBall.position.set(0, -0.16, spec.bodyDepth * 0.42)
  group.add(cameraBall)
  const lens = new THREE.Mesh(
    new THREE.CylinderGeometry(0.035, 0.035, 0.05, 12),
    new THREE.MeshStandardMaterial({ color: 0x60a5fa, roughness: 0.1, metalness: 0.8 }),
  )
  lens.rotation.x = Math.PI / 2
  lens.position.set(0, -0.16, spec.bodyDepth * 0.42 + 0.07)
  group.add(lens)

  // 重载机的双货舱：机腹两只吊舱，一眼看出"这是拉重货的"
  if (spec.cargoBays > 1) {
    ;[-1, 1].forEach((side) => {
      const bay = new THREE.Mesh(
        new THREE.BoxGeometry(spec.bodyWidth * 0.34, spec.bodyHeight * 0.9, spec.bodyDepth * 0.4),
        new THREE.MeshStandardMaterial({ color: 0x475569, roughness: 0.7, metalness: 0.15 }),
      )
      bay.position.set(side * spec.bodyWidth * 0.3, -spec.bodyHeight * 0.75, -spec.bodyDepth * 0.1)
      group.add(bay)
    })
  }

  // ---- 机臂 / 电机座 / 旋翼：按旋翼数量均匀分布 ----
  const armLength = Math.max(0.2, spec.podDistance - spec.bodyWidth * 0.4)
  const armGeometry = new THREE.CylinderGeometry(0.042, 0.055, armLength, 10)
  const podGeometry = new THREE.CylinderGeometry(0.095, 0.105, 0.14, 14)
  const bladeGeometry = new THREE.BoxGeometry(spec.bladeLength, 0.012, 0.075)
  const discGeometry = new THREE.CircleGeometry(spec.bladeLength * 0.54, 28)

  const rotors: THREE.Group[] = []
  const rotorDiscs: THREE.Mesh[] = []
  for (let i = 0; i < spec.arms; i += 1) {
    // 从半格起排：四旋翼落在四个角上，六/八旋翼也不会出现"正前方一根机臂挡住视线"
    const angle = Math.PI / spec.arms + (i * 2 * Math.PI) / spec.arms

    const arm = new THREE.Mesh(armGeometry, bodyMat)
    arm.position.set(Math.cos(angle) * (spec.podDistance * 0.55), 0.14, Math.sin(angle) * (spec.podDistance * 0.55))
    arm.quaternion.setFromUnitVectors(
      new THREE.Vector3(0, 1, 0),
      new THREE.Vector3(Math.cos(angle), 0.18, Math.sin(angle)).normalize(),
    )
    group.add(arm)

    const pod = new THREE.Mesh(podGeometry, darkMat)
    pod.position.set(Math.cos(angle) * spec.podDistance, 0.2, Math.sin(angle) * spec.podDistance)
    group.add(pod)

    const rotor = new THREE.Group()
    rotor.position.set(Math.cos(angle) * spec.podDistance, 0.29, Math.sin(angle) * spec.podDistance)
    const bladeA = new THREE.Mesh(bladeGeometry, rotorMat)
    const bladeB = bladeA.clone()
    bladeB.rotation.y = Math.PI / 2
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.035, 0.045, 10), rotorMat)
    rotor.add(bladeA, bladeB, hub)

    // 旋转模糊盘：转速越高越明显
    const disc = new THREE.Mesh(
      discGeometry,
      new THREE.MeshBasicMaterial({
        color: 0x94a3b8,
        transparent: true,
        opacity: 0.12,
        side: THREE.DoubleSide,
        depthWrite: false,
      }),
    )
    disc.rotation.x = -Math.PI / 2
    disc.position.y = 0.01
    rotor.add(disc)
    rotorDiscs.push(disc)

    group.add(rotor)
    rotors.push(rotor)
  }

  // ---- 起落架：两根滑橇 + 支柱（重载机滑橇更宽、支柱更多）----
  const skidGeometry = new THREE.CylinderGeometry(0.028, 0.028, spec.skidLength, 8)
  const strutGeometry = new THREE.CylinderGeometry(0.022, 0.022, 0.24, 8)
  ;[-1, 1].forEach((side) => {
    const skid = new THREE.Mesh(skidGeometry, darkMat)
    skid.rotation.z = Math.PI / 2
    skid.position.set(side * spec.skidSpread, -0.24, 0)
    group.add(skid)
    const depths = spec.arms >= 8 ? [-1, 0, 1] : [-1, 1]
    depths.forEach((depth) => {
      const strut = new THREE.Mesh(strutGeometry, darkMat)
      strut.position.set(side * spec.skidSpread, -0.13, depth * spec.skidLength * 0.38)
      group.add(strut)
    })
  })

  group.traverse((obj) => {
    if (obj instanceof THREE.Mesh) obj.castShadow = true
  })
  return { group, rotors, rotorDiscs, spec }
}

/** 造一只货箱（黄箱 + 十字封箱胶带 + 面单），用于"抓取任务用品"的演示。 */
export function createCargoBox(): THREE.Group {
  const group = new THREE.Group()
  const boxMat = new THREE.MeshStandardMaterial({ color: 0xf59e0b, roughness: 0.65 })
  const tapeMat = new THREE.MeshStandardMaterial({ color: 0x92400e, roughness: 0.6 })

  const box = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.34, 0.46), boxMat)
  group.add(box)

  // 封箱胶带（十字两条）
  group.add(new THREE.Mesh(new THREE.BoxGeometry(0.475, 0.35, 0.09), tapeMat))
  group.add(new THREE.Mesh(new THREE.BoxGeometry(0.09, 0.35, 0.475), tapeMat))

  // 面单
  const label = new THREE.Mesh(
    new THREE.PlaneGeometry(0.2, 0.14),
    new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.9 }),
  )
  label.position.set(0, 0.02, 0.235)
  group.add(label)

  group.traverse((obj) => {
    if (obj instanceof THREE.Mesh) obj.castShadow = true
  })
  return group
}

/** 释放一个几何体组占用的 GPU 资源（几何体 + 材质），避免切页后显存越用越多。 */
export function disposeObject(root: THREE.Object3D): void {
  root.traverse((obj) => {
    if (obj instanceof THREE.Mesh || obj instanceof THREE.Line) {
      obj.geometry.dispose()
      const mat = obj.material
      if (Array.isArray(mat)) mat.forEach((m) => m.dispose())
      else mat.dispose()
    }
  })
}
