package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DroneModel;
import org.springframework.stereotype.Component;

/**
 * 统一飞行时间估算（ETA）。
 *
 * <p>旧口径是 {@code 距离 / 巡航速度}，忽略了爬升、下降、加减速和风，
 * 于是"预计 105 秒"的任务实际飞了 150 秒，准时率与 ETA 都失真。
 * 这里把时间拆成：
 * <pre>
 *   垂直爬升 + 垂直下降 + 水平巡航（按规划风修正地速） + 起降/悬停固定开销
 * </pre>
 * 派单时的任务预计耗时、航路点 ETA、订单期望送达时间都用这套口径。
 */
@Component
public class FlightTimeEstimator {

    /** 起降悬停/地面操作的固定开销（仿真秒）。 */
    private static final double GROUND_OVERHEAD_SECONDS = 20.0;

    private final SkyProperties properties;
    private final WindField windField;
    private final EnergyModel energyModel;

    public FlightTimeEstimator(SkyProperties properties, WindField windField, EnergyModel energyModel) {
        this.properties = properties;
        this.windField = windField;
        this.energyModel = energyModel;
    }

    /**
     * 估算一段航程的飞行时间。
     *
     * @param distanceM   水平航程（米）
     * @param model       机型
     * @param layerAltM   巡航高度层（米）
     * @param bearingDeg  航迹方位角（度），用于取规划风
     */
    public Estimate estimate(double distanceM, DroneModel model, double layerAltM, double bearingDeg) {
        double cruise = energyModel.cruiseSpeedOf(model);
        double altitude = Math.max(0.0, layerAltM);
        double climb = altitude / Math.max(0.5, properties.flight().climbSpeedMps());
        double descent = altitude / Math.max(0.5, properties.flight().descentSpeedMps());
        double groundFactor = windField.planningGroundSpeedFactor(cruise, bearingDeg, altitude);
        double groundSpeed = Math.max(1.0, cruise * groundFactor);
        // 垂直段期间仍在水平前飞，按一半折算，避免把爬升/下降的位移重复计算
        double cruiseDistance = Math.max(0.0, distanceM - 0.5 * (climb + descent) * cruise);
        double seconds = climb + descent + cruiseDistance / groundSpeed + GROUND_OVERHEAD_SECONDS;
        return new Estimate((int) Math.ceil(seconds), groundSpeed);
    }

    /** ETA 结果：总秒数与规划地速（米/秒）。 */
    public record Estimate(int seconds, double groundSpeedMps) {
    }
}
