package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障注入：让仿真不再是"完美世界"。
 *
 * <p>真实运营里一定会遇到电池异常、动力失效、通信中断、定位漂移。
 * 这些故障如果不建模，看板上的电量曲线、任务成功率、遥测质量都会过于理想，
 * 调度策略在"有故障"场景下的鲁棒性也无从验证。
 *
 * <p><b>确定性</b>：故障由 (runId, droneId, 仿真时刻) 派生随机数，同一段仿真重跑结果一致；
 * 概率按"每架每分钟"配置，再按本拍时长换算，调倍速不会改变故障频率。
 */
@Component
public class FaultInjectionService {

    private final SkyProperties properties;

    /** droneId → 通信中断截止仿真时刻。 */
    private final Map<Long, Double> commLossUntil = new ConcurrentHashMap<>();
    /** droneId → GPS 漂移截止仿真时刻。 */
    private final Map<Long, Double> gpsDriftUntil = new ConcurrentHashMap<>();
    /** droneId → 人工排队的故障（下一拍生效，用于演示精确复现）。 */
    private final Map<Long, ManualFault> manualFaults = new ConcurrentHashMap<>();

    public FaultInjectionService(SkyProperties properties) {
        this.properties = properties;
    }

    /** 人工故障请求：只描述故障本身，具体生效时刻由下一拍决定。 */
    public record ManualFault(Type type, double batteryDropPct) {
    }

    /** 故障类型。 */
    public enum Type {
        NONE, BATTERY_DROP, POWER_LOSS, COMM_LOSS, GPS_DRIFT
    }

    /**
     * 一次故障事件。
     *
     * @param batteryDropPct 电池异常压降的百分点（0 表示无）
     */
    public record Fault(Type type, double batteryDropPct, String message) {
        public boolean present() {
            return type != Type.NONE;
        }

        public boolean fatal() {
            return type == Type.POWER_LOSS;
        }
    }

    private static final Fault NONE = new Fault(Type.NONE, 0.0, "");

    /**
     * 按概率尝试注入一次故障（每架飞机每拍最多一个）。
     *
     * @param simTimeS     当前仿真时刻（秒）
     * @param deltaSeconds 本拍仿真秒数
     */
    public Fault maybeInject(Drone drone, double deltaSeconds, double simTimeS, Long runId) {
        // 人工注入优先：即使随机故障关闭，演示者也要能精确复现某一种故障
        if (drone.getId() != null) {
            ManualFault manual = manualFaults.remove(drone.getId());
            if (manual != null) {
                return materialize(drone, manual, simTimeS);
            }
        }
        SkyProperties.Fault cfg = properties.fault();
        if (!cfg.enabled() || deltaSeconds <= 0 || drone.getId() == null) {
            return NONE;
        }
        double minutes = deltaSeconds / 60.0;
        // 用确定性的种子：同一运行、同一架、同一时刻永远得到同一结果
        long seed = 1_000_003L * (runId == null ? 1 : runId)
                + 7919L * drone.getId()
                + Math.round(simTimeS * 1000);
        SplittableRandom rng = new SplittableRandom(seed);

        double pBattery = perMinute(cfg.batteryDropRatePerDroneMin(), minutes);
        double pPower = perMinute(cfg.powerLossRatePerDroneMin(), minutes);
        double pComm = perMinute(cfg.commLossRatePerDroneMin(), minutes);
        double pGps = perMinute(cfg.gpsDriftRatePerDroneMin(), minutes);

        double roll = rng.nextDouble();
        if (roll < pBattery) {
            double drop = 5.0 + rng.nextDouble() * 10.0;
            return new Fault(Type.BATTERY_DROP, drop,
                    String.format("无人机 %s 电池异常压降 %.1f%%", drone.getCode(), drop));
        }
        roll -= pBattery;
        if (roll < pPower) {
            return new Fault(Type.POWER_LOSS, 0,
                    String.format("无人机 %s 动力系统失效，紧急迫降", drone.getCode()));
        }
        roll -= pPower;
        if (roll < pComm) {
            commLossUntil.put(drone.getId(), simTimeS + cfg.commLossSeconds());
            return new Fault(Type.COMM_LOSS, 0,
                    String.format("无人机 %s 通信中断，预计 %.0fs 恢复", drone.getCode(), cfg.commLossSeconds()));
        }
        roll -= pComm;
        if (roll < pGps) {
            gpsDriftUntil.put(drone.getId(), simTimeS + cfg.commLossSeconds());
            return new Fault(Type.GPS_DRIFT, 0,
                    String.format("无人机 %s 定位漂移，遥测位置误差增大", drone.getCode()));
        }
        return NONE;
    }

    /**
     * 人工注入一次故障：排入队列，下一拍由 {@link #maybeInject} 取出并生效。
     *
     * <p>电池压降默认 15%，并夹在 1~50 之间；其它故障不使用压降值。
     */
    public ManualFault injectManual(Long droneId, Type type, Double batteryDropPct) {
        if (droneId == null) {
            throw new IllegalArgumentException("droneId 不能为空");
        }
        if (type == null || type == Type.NONE) {
            throw new IllegalArgumentException("故障类型不能为空");
        }
        double drop = 0.0;
        if (type == Type.BATTERY_DROP) {
            drop = batteryDropPct == null ? 15.0 : Math.max(1.0, Math.min(50.0, batteryDropPct));
        }
        ManualFault fault = new ManualFault(type, drop);
        manualFaults.put(droneId, fault);
        return fault;
    }

    /** 人工故障 → 统一的 Fault 事件（消息里带机号，事件流可直接读懂）。 */
    private Fault materialize(Drone drone, ManualFault manual, double simTimeS) {
        SkyProperties.Fault cfg = properties.fault();
        return switch (manual.type()) {
            case BATTERY_DROP -> new Fault(Type.BATTERY_DROP, manual.batteryDropPct(),
                    String.format("无人机 %s 电池异常压降 %.1f%%（人工注入）", drone.getCode(), manual.batteryDropPct()));
            case POWER_LOSS -> new Fault(Type.POWER_LOSS, 0,
                    String.format("无人机 %s 动力系统失效，紧急迫降（人工注入）", drone.getCode()));
            case COMM_LOSS -> {
                commLossUntil.put(drone.getId(), simTimeS + cfg.commLossSeconds());
                yield new Fault(Type.COMM_LOSS, 0,
                        String.format("无人机 %s 通信中断，预计 %.0fs 恢复（人工注入）",
                                drone.getCode(), cfg.commLossSeconds()));
            }
            case GPS_DRIFT -> {
                gpsDriftUntil.put(drone.getId(), simTimeS + cfg.commLossSeconds());
                yield new Fault(Type.GPS_DRIFT, 0,
                        String.format("无人机 %s 定位漂移，遥测位置误差增大（人工注入）", drone.getCode()));
            }
            default -> NONE;
        };
    }

    /** 新建/重置运行时清空：故障持续状态与待注入队列都属于本次演练。 */
    public void clearAll() {
        commLossUntil.clear();
        gpsDriftUntil.clear();
        manualFaults.clear();
    }

    /** 该机是否处于通信中断状态。 */
    public boolean commLost(long droneId, double simTimeS) {
        Double until = commLossUntil.get(droneId);
        return until != null && simTimeS < until;
    }

    /** 该机是否处于 GPS 漂移状态。 */
    public boolean gpsDrifting(long droneId, double simTimeS) {
        Double until = gpsDriftUntil.get(droneId);
        return until != null && simTimeS < until;
    }

    /** GPS 漂移的额外位置偏差（米）。 */
    public double gpsDriftMeters() {
        return properties.fault().gpsDriftMeters();
    }

    private double perMinute(double ratePerMinute, double minutes) {
        return Math.max(0.0, Math.min(1.0, ratePerMinute * minutes));
    }
}
