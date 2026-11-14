package com.skydispatch.engine;

import com.skydispatch.domain.entity.DroneModel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 电量消耗模型（仿真的"物理引擎"）。
 *
 * <p>模型刻意做得简单可解释，但抓住了三个真实因素：
 * <ol>
 *   <li><b>巡航能耗</b>：与航程成正比，由机型参数 {@code energy_per_km_wh} 给出。</li>
 *   <li><b>载重惩罚</b>：每多载 1kg，能耗增加约 5%（线性近似）。真实曲线是非线性的，
 *       但线性模型在最大载重范围内误差可接受，且便于初学者理解。</li>
 *   <li><b>起降悬停</b>：起飞与降落的悬停时间按固定 60 秒计，用悬停功率折算成 Wh。</li>
 * </ol>
 *
 * <p>所有计算都用 {@link BigDecimal}，避免 {@code double} 累加后出现
 * "电量 0.30000000000000004%" 这种脏数据。
 */
@Component
public class EnergyModel {

    /** 每公斤载重带来的能耗增幅（5%）。 */
    private static final BigDecimal PAYLOAD_ENERGY_FACTOR_PER_KG = new BigDecimal("0.05");

    /** 起降阶段的总悬停时间（秒）。 */
    private static final BigDecimal TAKEOFF_LANDING_SECONDS = new BigDecimal("60");

    private static final BigDecimal SECONDS_PER_HOUR = new BigDecimal("3600");
    private static final BigDecimal METERS_PER_KM = new BigDecimal("1000");

    /**
     * 估算一次飞行需要的电量（Wh）。
     *
     * @param model      机型
     * @param distanceM  航路长度（米）
     * @param payloadKg  载重（公斤）
     */
    public BigDecimal estimateEnergyWh(DroneModel model, double distanceM, double payloadKg) {
        return cruiseEnergyWh(model, distanceM, payloadKg).add(hoverEnergyWh(model));
    }

    /**
     * 只算巡航部分（不含起降悬停）。
     *
     * <p>仿真引擎每个 tick 都调用它：按"这一拍飞过的距离"扣电，比按整段航程一次扣完更真实，
     * 也能让电量曲线在地图上看得出坡度。
     */
    public BigDecimal cruiseEnergyWh(DroneModel model, double distanceM, double payloadKg) {
        BigDecimal km = BigDecimal.valueOf(distanceM)
                .divide(METERS_PER_KM, 6, RoundingMode.HALF_UP);
        BigDecimal loadFactor = BigDecimal.ONE
                .add(PAYLOAD_ENERGY_FACTOR_PER_KG.multiply(BigDecimal.valueOf(payloadKg)));
        return model.getEnergyPerKmWh().multiply(km).multiply(loadFactor).setScale(6, RoundingMode.HALF_UP);
    }

    /** 起降阶段（固定 60 秒悬停）消耗的能量。 */
    public BigDecimal hoverEnergyWh(DroneModel model) {
        return model.getHoverPowerW()
                .multiply(TAKEOFF_LANDING_SECONDS)
                .divide(SECONDS_PER_HOUR, 6, RoundingMode.HALF_UP);
    }

    /**
     * 一次完整派送任务需要多少能量：<b>去程载货 + 回程空载 + 两次起降悬停</b>。
     *
     * <p>这是调度做"能不能接这单"判断的依据。最容易犯的错误是只算单程——
     * 无人机送完货还得飞回来，只按单程选机会导致飞机半路没电。
     * 回程按空载算，是因为货已经卸下了，这一点也必须体现在模型里。
     */
    public BigDecimal missionEnergyWh(DroneModel model, double deadheadMeters, double oneWayMeters, double payloadKg) {
        BigDecimal deadhead = cruiseEnergyWh(model, deadheadMeters, 0);
        BigDecimal outbound = cruiseEnergyWh(model, oneWayMeters, payloadKg);
        BigDecimal inbound = cruiseEnergyWh(model, oneWayMeters, 0);
        BigDecimal hover = hoverEnergyWh(model).multiply(BigDecimal.valueOf(2));
        return deadhead.add(outbound).add(inbound).add(hover).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 不含调机段的版本（调机距离按 0 处理）。
     * 用于只关心"取货点 → 目的地 → 取货点"这一段的场景，例如按航路长度做粗估。
     */
    public BigDecimal missionEnergyWh(DroneModel model, double oneWayMeters, double payloadKg) {
        return missionEnergyWh(model, 0, oneWayMeters, payloadKg);
    }

    /**
     * 扣掉安全冗余之后，这次任务实际可用的能量（Wh）。
     *
     * @param usableBatteryPct 可用电量百分比（已扣除落地保留值）
     */
    public BigDecimal usableEnergyWh(DroneModel model, double usableBatteryPct) {
        if (usableBatteryPct <= 0) {
            return BigDecimal.ZERO;
        }
        return model.getBatteryCapacityWh()
                .multiply(BigDecimal.valueOf(usableBatteryPct))
                .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
    }

    /**
     * 把能量（Wh）换算成电量百分比消耗。
     */
    public BigDecimal batteryPctUsed(DroneModel model, BigDecimal energyWh) {
        if (model.getBatteryCapacityWh() == null || model.getBatteryCapacityWh().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return energyWh.multiply(BigDecimal.valueOf(100))
                .divide(model.getBatteryCapacityWh(), 4, RoundingMode.HALF_UP);
    }

    /**
     * 在给定可用电量下，还能飞多远（米）。
     *
     * <p>调度模块用它做"电量是否够往返"的硬性校验，属于安全底线，不能用平均值糊弄。
     *
     * @param usableBatteryPct 可用电量百分比（已扣除安全冗余）
     */
    public double maxRangeMeters(DroneModel model, double payloadKg, double usableBatteryPct) {
        if (usableBatteryPct <= 0) {
            return 0;
        }
        BigDecimal usableWh = model.getBatteryCapacityWh()
                .multiply(BigDecimal.valueOf(usableBatteryPct))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

        BigDecimal hoverWh = model.getHoverPowerW()
                .multiply(TAKEOFF_LANDING_SECONDS)
                .divide(SECONDS_PER_HOUR, 6, RoundingMode.HALF_UP);
        BigDecimal loadFactor = BigDecimal.ONE
                .add(PAYLOAD_ENERGY_FACTOR_PER_KG.multiply(BigDecimal.valueOf(payloadKg)));

        BigDecimal netWh = usableWh.subtract(hoverWh);
        if (netWh.signum() <= 0) {
            return 0;
        }
        BigDecimal km = netWh.divide(model.getEnergyPerKmWh().multiply(loadFactor), 6, RoundingMode.HALF_UP);
        return km.multiply(METERS_PER_KM).doubleValue();
    }

    /**
     * 由航程与巡航速度估算飞行时间（秒），用于 ETA 和超时判定。
     */
    public int estimateDurationSeconds(double distanceM, double speedMps) {
        if (speedMps <= 0) {
            return 0;
        }
        return (int) Math.ceil(distanceM / speedMps);
    }
}
