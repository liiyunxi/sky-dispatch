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
        @DefaultValue Bootstrap bootstrap
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

    /** 演示数据开关。 */
    public record Bootstrap(
            @DefaultValue("true") boolean seedDemoData
    ) {
    }
}
