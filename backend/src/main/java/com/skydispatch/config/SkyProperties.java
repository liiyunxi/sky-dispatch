package com.skydispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 业务配置集中入口，对应 application.yml 里的 {@code sky.*}。
 *
 * <p>为什么不用 {@code @Value} 到处散落？因为参数一多就难以发现和统一修改。
 * 集中成一个不可变对象后：IDE 有补全、启动时能校验、单元测试里可以直接 new 一个。
 *
 * <p>这里用 record + {@code @DefaultValue}：即使 yml 里漏配某一项，也能用默认值启动，
 * 不会因为一个空指针在半夜炸掉。
 */
@ConfigurationProperties(prefix = "sky")
public record SkyProperties(
        @DefaultValue Algorithm algorithm,
        @DefaultValue Simulation simulation,
        @DefaultValue Dispatch dispatch,
        @DefaultValue Flight flight,
        @DefaultValue Energy energy,
        @DefaultValue Wind wind,
        @DefaultValue Bootstrap bootstrap,
        @DefaultValue Auth auth
) {

    /** 调用 Python 算法服务的参数。 */
    public record Algorithm(
            @DefaultValue("http://localhost:8000") String baseUrl,
            @DefaultValue("2000") int connectTimeoutMs,
            @DefaultValue("10000") int readTimeoutMs,
            /** 关掉后走内置降级路径（直线插值），方便没有 Python 环境时开发 Java 侧。 */
            @DefaultValue("true") boolean enabled
    ) {
    }

    /** 仿真引擎参数。 */
    public record Simulation(
            @DefaultValue("true") boolean enabled,
            /** 每拍的真实毫秒数，即调度周期。 */
            @DefaultValue("1000") long tickMs,
            /** 仿真倍速：1 秒真实时间推进多少仿真秒。 */
            @DefaultValue("4.0") double speedFactor,
            @DefaultValue("5") int conflictCheckEveryTicks,
            @DefaultValue("5") int kpiBroadcastEveryTicks,
            @DefaultValue("2") int telemetryPersistEveryTicks,
            @DefaultValue("10000") long autoDispatchIntervalMs,
            /**
             * 演示用：在站无人机自动补能（默认关，dev profile 打开）。
             * 真实系统的充电由换电/充电调度负责，仿真演示里若电量只降不升，
             * 机队飞几轮就会集体低于安全电量而停摆，打开看板看不到持续活动。
             */
            @DefaultValue("false") boolean demoRechargeEnabled,
            /** 每个仿真秒回充的电量百分比。 */
            @DefaultValue("0.25") double rechargeRatePctPerSimSec,
            /** 回充到该百分比即视为满电、停止补能。 */
            @DefaultValue("98.0") double rechargeTargetPct,
            /** 低电量进入维护的飞机回充到该百分比后恢复为可调度。 */
            @DefaultValue("60.0") double rechargeResumePct,
            /**
             * 演示用：待派/在飞订单不足时自动补充新订单（默认关，dev profile 打开）。
             * 否则固定的几个演示订单送完后系统永久静止。只在演示环境启用，
             * 生产环境的订单必须来自真实客户，绝不能由系统自己造。
             */
            @DefaultValue("false") boolean demoReplenishEnabled,
            /** 维持的"未完结订单"目标数量（待派 + 在飞），低于它就补单。 */
            @DefaultValue("3") int targetActiveOrders,
            /** 一次补单最多生成几单，避免同一拍涌入过多。 */
            @DefaultValue("2") int replenishBatchSize,
            /** 允许作为补单运力锚点的最低电量（%），低于它的飞机先充电、不为它造单。 */
            @DefaultValue("60.0") double replenishMinBatteryPct
    ) {
    }

    /** 调度策略的权重与门槛。 */
    public record Dispatch(
            @DefaultValue("NEAREST_AVAILABLE") String defaultStrategy,
            @DefaultValue("10000.0") double searchRadiusM,
            /** 飞完必须剩下的电量百分比（安全冗余）。 */
            @DefaultValue("25.0") double minBatteryReservePct,
            @DefaultValue("1.0") double weightDistance,
            @DefaultValue("0.5") double weightBattery,
            @DefaultValue("0.3") double weightLoad,
            /**
             * 是否允许无人机"自主判断需要补能"：电量不足以飞完剩余航程时，自行改航到就近充电站，
             * 充满再继续未完成的那一段（默认关，dev profile 打开）。
             *
             * <p>关掉后行为回到旧版：电量低于安全冗余直接强制返航/中止。
             * 生产环境也建议先关——真机的自主改航要过适航与空管评估，不是软件开关说了算。
             */
            @DefaultValue("false") boolean relayChargingEnabled,
            /** 补能到该百分比即认为够用、可以继续飞。 */
            @DefaultValue("90.0") double relayResumePct,
            /**
             * 补能触发的灵敏度：剩余航程能耗 > 可用电量 × 该系数时才改航。
             * 大于 1 是为了留出"估值误差 + 悬停/绕飞"的余量，避免一有风吹草动就去充电。
             */
            @DefaultValue("1.15") double relaySafetyFactor
    ) {
    }

    /** 飞行与栅格参数。 */
    public record Flight(
            @DefaultValue("80.0") double defaultAltitudeM,
            @DefaultValue("50.0") double gridSizeM,
            @DefaultValue("30.0") double keepOutMarginM,
            /**
             * 爬升速率（米/秒）。用于把"垂直段"折算成"水平前进多远才爬到巡航高度"：
             * 爬升耗时 = 高度 / 爬升速率，这段时间飞机同时以巡航速度前飞，
             * 于是爬升水平距离 ≈ 巡航速度 × 爬升耗时。
             */
            @DefaultValue("4.0") double climbSpeedMps,
            /** 下降速率（米/秒），比爬升略慢，接近真实多旋翼的下降策略。 */
            @DefaultValue("3.0") double descentSpeedMps,
            /** 起降加减速的水平过渡距离（米）：从最小速度平滑加到巡航速度（反之亦然）。 */
            @DefaultValue("80.0") double speedRampM
    ) {
    }

    /**
     * 能耗模型参数。
     *
     * <p>模型形式：{@code P(v) = P_hover·((m0+payload)/m0)^k + B·v³}，
     * 其中 B 由"额定巡航点的每公里能耗"反解，保证参数表标定值不失效。
     * 这样每公里能耗随速度、载重非线性变化，而不是一条直线。
     */
    public record Energy(
            /**
             * 诱导功率的质量指数（动量理论约 1.5）。
             *
             * <p>1.0 = 线性、1.5 = 真实旋翼；调大可以让重载单更"难派"。
             */
            @DefaultValue("1.5") double inducedExponent,
            /**
             * 空机质量 / 最大载重。
             *
             * <p>配送多旋翼通常是 2~3 倍（5kg 载重对应 12~15kg 机身）。
             * 它决定"载重占总质量的比例"，从而决定重载的边际能耗有多陡。
             */
            @DefaultValue("2.5") double airframeMassRatio,
            /**
             * 计算每公里能耗时的最低速度系数（相对巡航速度）。
             *
             * <p>速度趋近 0 时每公里能耗在数学上会发散，这里给一个地板（默认 30%），
             * 与仿真里的起降减速地板保持一致。
             */
            @DefaultValue("0.30") double minSpeedFactor,
            /** 一次起降的总悬停时间（秒），用于折算起降能耗。 */
            @DefaultValue("60.0") double takeoffLandingSeconds
    ) {
    }

    /**
     * 风场参数（仿真"真实感"的物理来源之一）。
     *
     * <p>模型是确定性的：风速/风向全部由 simTime 推导，不使用随机数，
     * 保证同一段仿真每次重跑结果一致（演示、回归测试、答辩复盘都需要）。
     *
     * <p>三个物理影响：
     * <ol>
     *   <li>{@code groundSpeedFactor}：逆风时地速下降，到达时间变长；</li>
     *   <li>{@code airspeedFactor}：能耗按空速算，逆风/侧风都会多耗电；</li>
     *   <li>{@code noseBearingDeg}：侧风时机头偏转（偏航角），遥测 heading 随之变化。</li>
     * </ol>
     */
    public record Wind(
            /** 总开关。关掉后行为与"无风"完全一致，便于对比实验。 */
            @DefaultValue("true") boolean enabled,
            /**
             * 风的<b>来向</b>（度，正北 0、顺时针），与气象报文一致。
             * 默认 225°（西南风，吹向东北），上海地区常见风向。
             */
            @DefaultValue("225.0") double directionDeg,
            /** 参考高度处的平均风速（米/秒）。 */
            @DefaultValue("2.5") double baseSpeedMps,
            /** 参考高度（米）：地面气象观测常取 10m。 */
            @DefaultValue("10.0") double referenceAltitudeM,
            /** 高度剪切指数（开阔地形约 0.14，城区更大，可调到 0.25 模拟建筑群）。 */
            @DefaultValue("0.14") double shearExponent,
            /** 阵风幅度占比：0.3 表示风速在 ±30% 内起伏。 */
            @DefaultValue("0.30") double gustRatio,
            /** 阵风主周期（秒），与副周期按黄金分割比叠加。 */
            @DefaultValue("41.0") double gustPeriodS,
            /** 风向摆动幅度（度）。 */
            @DefaultValue("6.0") double directionWanderDeg,
            /** 地速下限系数：极端逆风下的兜底，防止飞机在仿真里"原地不动"。 */
            @DefaultValue("0.55") double minGroundSpeedFactor,
            /** 每米能耗下限系数：顺风过强时也保留基础能耗，避免出现"零能耗"。 */
            @DefaultValue("0.60") double minEnergyFactor,
            /** 每米能耗上限系数：逆风到几乎飞不动时封顶，避免电量被瞬间打空。 */
            @DefaultValue("2.20") double maxEnergyFactor,
            /**
             * 派单阶段是否计入风裕度。
             *
             * <p>开启后，可达性分析会按每个航段的<b>航向</b>计算逆风能耗上浮：
             * 逆风段按实际风况多估能耗，顺风段少估，往返天然抵消；
             * 长距离调机撞上逆风时才会明显变难派。
             */
            @DefaultValue("true") boolean planningEnabled,
            /**
             * 规划附加安全系数（覆盖阵风与预报误差）。
             *
             * <p>只作用于派单的可行性判断，不影响实际消耗，也不改变展示的"预计能耗"。
             * 1.0 表示完全按平均风计算；1.05 表示再留 5% 余量。
             */
            @DefaultValue("1.05") double planningSafetyFactor
    ) {
    }

    /** 演示数据开关。 */
    public record Bootstrap(
            @DefaultValue("true") boolean seedDemoData
    ) {
    }

    /**
     * 登录鉴权参数。
     *
     * <p>内部演示平台使用单账号 + HMAC 签名令牌：
     * 登录接口校验账号密码后签发一个带过期时间的 token，前端放在
     * {@code Authorization: Bearer} 里，HTTP 接口与 WebSocket 握手都校验它。
     *
     * <p>生产环境至少要把 {@code secret} 换成随机值、密码走密钥管理，
     * 并在此基础上替换为统一身份认证（OIDC/LDAP）。
     */
    public record Auth(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("admin") String username,
            @DefaultValue("sky-dispatch") String password,
            @DefaultValue("dev-only-change-me-sky-dispatch-secret-32bytes") String secret,
            @DefaultValue("720") long tokenTtlMinutes
    ) {
    }
}
