package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DroneModel;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /**
     * 估算一条折线航路的飞行时间：<b>逐航段</b>取该段航向的规划风，再累加。
     *
     * <p>单段版本（{@link #estimate}）用"起点 → 终点总方位"取一次风。
     * 航路被禁飞区绕成多段后，各段航向可能差异很大（例如先向东绕行再向北），
     * 单一方位会让逆风段和顺风段互相抵消，ETA 系统性失真。
     *
     * @param points     航路折线（含起终点，按飞行顺序）
     * @param model      机型
     * @param layerAltM  巡航高度层（米）
     */
    public Estimate estimateRoute(List<GeoPoint> points, DroneModel model, double layerAltM) {
        if (points == null || points.size() < 2) {
            return estimate(0.0, model, layerAltM, 0.0);
        }
        double distanceM = 0.0;
        double horizontalSeconds = 0.0;
        for (int i = 1; i < points.size(); i++) {
            GeoPoint from = points.get(i - 1);
            GeoPoint to = points.get(i);
            distanceM += from.distanceTo(to);
            horizontalSeconds += segmentSeconds(from, to, model, layerAltM);
        }
        // 整条航路都是零长度（所有点重合）：退化为零航程，仍含起降开销
        if (horizontalSeconds <= 1e-9) {
            return estimate(0.0, model, layerAltM, 0.0);
        }
        double altitude = Math.max(0.0, layerAltM);
        double verticalSeconds = altitude / Math.max(0.5, properties.flight().climbSpeedMps())
                + altitude / Math.max(0.5, properties.flight().descentSpeedMps());
        // 垂直段期间仍在水平前飞，按一半折算，避免把爬升/下降的位移重复计算
        double seconds = Math.max(0.0, horizontalSeconds - 0.5 * verticalSeconds)
                + verticalSeconds + GROUND_OVERHEAD_SECONDS;
        return new Estimate((int) Math.ceil(seconds), distanceM / horizontalSeconds);
    }

    /**
     * 单段水平飞行时间（秒，已按该段航向修正风，不含垂直段与起降开销）。
     *
     * <p>公开给 {@code RouteService} 分摊航路点 ETA：按"累计飞行时间"占比
     * 比按"累计距离"占比更贴合实际，因为各段地速不同。
     */
    public double segmentSeconds(GeoPoint from, GeoPoint to, DroneModel model, double layerAltM) {
        double segmentM = from.distanceTo(to);
        if (segmentM <= 1e-6) {
            return 0.0;
        }
        double cruise = energyModel.cruiseSpeedOf(model);
        double groundSpeed = Math.max(1.0, cruise * windField.planningGroundSpeedFactor(
                cruise, from.bearingTo(to), Math.max(0.0, layerAltM)));
        return segmentM / groundSpeed;
    }

    /** ETA 结果：总秒数与规划地速（米/秒）。 */
    public record Estimate(int seconds, double groundSpeedMps) {
    }
}
