package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 遥测观测模型：把"上帝视角真值"变成"传感器观测值"。
 *
 * <p>仿真内部（动力学、冲突检测）用真值；对外发布的遥测加入：
 * <ul>
 *   <li><b>噪声</b>：位置、高度、速度、航向、电量都有测量误差；</li>
 *   <li><b>延迟</b>：实时帧使用上一拍（可配置）的观测；</li>
 *   <li><b>丢包</b>：整帧丢失，前端拿到的是"上一帧 + 丢包标记"；</li>
 *   <li><b>故障</b>：通信中断时标记失联；GPS 漂移时位置误差显著放大。</li>
 * </ul>
 *
 * <p>为什么丢包不直接"不返回"：那会让地图上的飞机静止，用户无法区分
 * "飞机悬停"和"遥测断了"。所以实时帧始终返回，只是带 {@code stale/commLost} 标记，
 * 由前端显示"延迟/丢包/失联"。落库遥测仍然按真实丢包跳过（没有包就没有记录）。
 *
 * <p>随机数由 (droneId, step) 派生，保证同一段仿真重跑时观测序列完全一致。
 */
@Component
public class TelemetryObservationService {

    private final SkyProperties properties;
    private final FaultInjectionService faults;
    /** droneId → 最近若干拍的真值，用于模拟观测延迟。 */
    private final Map<Long, Deque<Observation>> history = new ConcurrentHashMap<>();

    public TelemetryObservationService(SkyProperties properties, FaultInjectionService faults) {
        this.properties = properties;
        this.faults = faults;
    }

    /**
     * 一帧观测值。
     *
     * @param delayed  是否为延迟样本（用了几拍前的数据）
     * @param stale    是否为陈旧样本（丢包后沿用上一帧）
     * @param commLost 通信中断（失联）
     * @param gpsDrift GPS 漂移中
     */
    public record Observation(double lat, double lon, double altitudeM, double speedMps,
                              double headingDeg, double batteryPct,
                              boolean delayed, boolean stale, boolean commLost, boolean gpsDrift,
                              Instant observedAt) {
    }

    /**
     * 实时帧观测：始终返回一帧，通过标记表达数据质量。
     */
    public Observation observeForFrame(Drone drone, double simTimeS, long step) {
        if (drone.getId() == null) {
            return actual(drone);
        }
        SkyProperties.Telemetry cfg = properties.telemetry();
        Deque<Observation> deque = history.computeIfAbsent(drone.getId(),
                k -> new ConcurrentLinkedDeque<>());
        Observation previous = deque.peekLast();
        Observation current = actual(drone);
        deque.addLast(current);
        int delay = cfg.noiseEnabled() ? Math.max(0, cfg.delayTicks()) : 0;
        while (deque.size() > delay + 2) {
            deque.removeFirst();
        }

        boolean commLost = faults.commLost(drone.getId(), simTimeS);
        boolean gpsDrift = faults.gpsDrifting(drone.getId(), simTimeS);
        if (commLost) {
            return flagged(previous == null ? current : previous, false, true, true, gpsDrift);
        }
        if (dropped(drone.getId(), step)) {
            return flagged(previous == null ? current : previous, false, true, false, gpsDrift);
        }
        if (!cfg.noiseEnabled()) {
            return new Observation(current.lat(), current.lon(), current.altitudeM(),
                    current.speedMps(), current.headingDeg(), current.batteryPct(),
                    false, false, false, gpsDrift, current.observedAt());
        }

        boolean delayed = delay > 0 && deque.size() > delay;
        Observation base = delayed ? deque.peekFirst() : current;
        Observation noisy = noisy(drone, base, step, gpsDrift ? faults.gpsDriftMeters() : 0.0);
        return new Observation(noisy.lat(), noisy.lon(), noisy.altitudeM(), noisy.speedMps(),
                noisy.headingDeg(), noisy.batteryPct(), delayed, false, false, gpsDrift,
                base.observedAt());
    }

    /**
     * 落库遥测观测：丢包/失联时没有包，返回 null；其余只加噪声。
     */
    public Observation observeForRecord(Drone drone, double simTimeS, long step) {
        if (drone.getId() == null) {
            return actual(drone);
        }
        if (faults.commLost(drone.getId(), simTimeS)) {
            return null;
        }
        if (dropped(drone.getId(), step)) {
            return null;
        }
        SkyProperties.Telemetry cfg = properties.telemetry();
        if (!cfg.noiseEnabled()) {
            return actual(drone);
        }
        boolean gpsDrift = faults.gpsDrifting(drone.getId(), simTimeS);
        return noisy(drone, actual(drone), step, gpsDrift ? faults.gpsDriftMeters() : 0.0);
    }

    private Observation actual(Drone drone) {
        return new Observation(
                drone.getCurrentLat() == null ? 0 : drone.getCurrentLat(),
                drone.getCurrentLon() == null ? 0 : drone.getCurrentLon(),
                drone.getAltitudeM() == null ? 0 : drone.getAltitudeM().doubleValue(),
                drone.getSpeedMps() == null ? 0 : drone.getSpeedMps().doubleValue(),
                drone.getHeadingDeg() == null ? 0 : drone.getHeadingDeg().doubleValue(),
                drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue(),
                false, false, false, false, Instant.now());
    }

    private Observation flagged(Observation base, boolean delayed, boolean stale,
                                boolean commLost, boolean gpsDrift) {
        return new Observation(base.lat(), base.lon(), base.altitudeM(), base.speedMps(),
                base.headingDeg(), base.batteryPct(), delayed, stale, commLost, gpsDrift,
                base.observedAt());
    }

    private Observation noisy(Drone drone, Observation base, long step, double extraDriftM) {
        SkyProperties.Telemetry cfg = properties.telemetry();
        SplittableRandom rng = new SplittableRandom(31L * drone.getId() + 1_000_003L * step);

        // 位置误差：高斯噪声 + 可选的 GPS 漂移（漂移是系统性偏差，不是随机噪声）
        double bearing = rng.nextDouble() * 360.0;
        double distance = Math.abs(rng.nextGaussian()) * Math.max(0.0, cfg.positionNoiseM())
                + Math.max(0.0, extraDriftM);
        GeoPoint drifted = distance <= 1e-6
                ? new GeoPoint(base.lat(), base.lon())
                : GeoUtils.destinationPoint(new GeoPoint(base.lat(), base.lon()), bearing, distance);

        double altitude = Math.max(0.0, base.altitudeM() + rng.nextGaussian() * cfg.altitudeNoiseM());
        double speed = Math.max(0.0, base.speedMps() + rng.nextGaussian() * 0.3);
        double heading = (base.headingDeg() + rng.nextGaussian() * 2.0 + 360.0) % 360.0;
        double battery = Math.max(0.0, Math.min(100.0,
                base.batteryPct() + rng.nextGaussian() * cfg.batteryNoisePct()));

        return new Observation(drifted.lat(), drifted.lon(), altitude, speed, heading, battery,
                false, false, false, false, base.observedAt());
    }

    private boolean dropped(long droneId, long step) {
        double rate = Math.max(0.0, Math.min(1.0, properties.telemetry().dropRate()));
        if (rate <= 1e-9) {
            return false;
        }
        SplittableRandom rng = new SplittableRandom(17L * droneId + 6_700_417L * step);
        return rng.nextDouble() < rate;
    }
}
