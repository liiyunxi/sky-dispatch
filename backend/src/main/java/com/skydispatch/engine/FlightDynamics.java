package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞行动力学积分器：无人机位置由速度积分产生，而不是沿航路"按已飞距离插值"。
 *
 * <p><b>为什么必须积分</b>：插值模型下，飞机的位置永远精确落在航路上，
 * 速度可以瞬间从 0 跳到巡航，转弯没有惯性，逆风也不会把飞机吹偏。
 * 这些"过于完美"的行为会让所有与运动相关的结论失真：
 * 到达时间、加减速能耗、转弯半径、冲突预测都只是几何计算结果。
 *
 * <p><b>模型</b>（每拍内部再切子步，半隐式欧拉）：
 * <pre>
 *   1) 制导：前视点（lookahead）→ 期望航迹方向；
 *   2) 速度指令：末端制动曲线 v = √(2a·剩余距离) + 低速地板，再做空速上限约束；
 *   3) 加速度：a = clamp(Kp·(v指令 − v地速), ±a_max)；
 *   4) 积分：v += a·dt，位置 += v·dt；
 *   5) 能耗：空速 = |地速 − 风矢量|，P = P_induced(载重) + B·空速³，按时间积分。
 * </pre>
 *
 * <p><b>三个自然涌现的行为</b>：
 * <ul>
 *   <li>起飞先垂直爬升、再水平加速（离地 3m 内不前进），巡航/降落有高度惯性；</li>
 *   <li>末端按制动曲线减速并停在终点，而不是"到点瞬间归零"；</li>
 *   <li>逆风时为了维持额定空速，地速被压下来；顺风时地速更高 —— 能耗按真实空速积分。</li>
 * </ul>
 *
 * <p><b>状态放在内存而不是数据库</b>：位置/速度每拍都会写回 {@code drone} 实体（看板与冲突检测用），
 * 但"上一拍的速度矢量"属于仿真运行态，重启后从数据库位置重建即可。
 * 因此这里用 {@link ConcurrentHashMap} 保存，任务结束/充电/失败时清理。
 */
@Component
public class FlightDynamics {

    /** 1 度纬度对应的米数（球面近似）。 */
    private static final double METERS_PER_DEGREE = Math.toRadians(1.0) * GeoUtils.EARTH_RADIUS_M;

    /** 离地低于该高度时只爬升、不前进（真实多旋翼的起飞顺序）。 */
    private static final double TAKEOFF_CLIMB_ONLY_M = 3.0;

    /** 低空水平限速系数：贴地时最多 30% 巡航速度，随高度展开到 100%。 */
    private static final double LOW_ALTITUDE_SPEED_FACTOR = 0.30;

    /** 高度误差 → 垂直速度的增益（1/s）。 */
    private static final double VERTICAL_GAIN = 1.2;

    /** 垂直速度的一阶跟随速率（1/s），让爬升/下降也有惯性。 */
    private static final double VERTICAL_LAG_PER_SEC = 4.0;

    private final SkyProperties properties;
    private final EnergyModel energyModel;
    private final WindField windField;

    /** 每架无人机的运动状态（key = droneId）。 */
    private final Map<Long, State> states = new ConcurrentHashMap<>();

    public FlightDynamics(SkyProperties properties, EnergyModel energyModel, WindField windField) {
        this.properties = properties;
        this.energyModel = energyModel;
        this.windField = windField;
    }

    /**
     * 一拍推进的结果（已换算回经纬度，可直接写入遥测）。
     *
     * @param distanceM 沿本段航路的已飞距离（米）
     * @param arrived   是否已到达航路终点并停稳
     */
    public record Motion(double lat, double lon, double altitudeM,
                         double groundSpeedMps, double headingDeg, double airspeedMps,
                         double energyWh, double distanceM, boolean arrived) {
    }

    /**
     * 推进一步。
     *
     * @param drone           无人机（提供当前经纬度/高度/速度，作为首次初始化依据）
     * @param model           机型（巡航速度、功率曲线参数）
     * @param path            本段航路折线（去程为原始航路，返航为反向航路或改航后的新航路）
     * @param startFlownM     本拍开始时沿 path 的已飞距离
     * @param cruiseAltitudeM 本段巡航高度（米）
     * @param payloadKg       载重（返航传 0）
     * @param deltaSeconds    本拍仿真时长（秒，= tickMs/1000 × 倍速）
     * @param simTimeSec      当前仿真时刻（秒），用于采样风场
     */
    public Motion advance(Drone drone, DroneModel model, List<GeoPoint> path, double startFlownM,
                          double cruiseAltitudeM, double payloadKg,
                          double deltaSeconds, double simTimeSec) {
        State state = stateOf(drone, path);
        if (path == null || path.size() < 2 || deltaSeconds <= 0) {
            return snapshot(state, startFlownM, 0.0, 0.0, 0.0, false, 0.0, 0.0, path);
        }

        // 把航路换算到以状态参考点为原点的局部平面（米），后续全部用向量运算
        int n = path.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        double[] cum = new double[n];
        for (int i = 0; i < n; i++) {
            GeoPoint p = path.get(i);
            xs[i] = (p.lon() - state.refLon) * state.lonScale;
            ys[i] = (p.lat() - state.refLat) * state.latScale;
            if (i > 0) {
                cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            }
        }
        double total = cum[n - 1];
        if (total < 0.5) {
            return snapshot(state, total, 0.0, 0.0, 0.0, true, 0.0, 0.0, path);
        }

        double flown = clamp(startFlownM, 0.0, total);
        double cruise = Math.max(1.0, energyModel.cruiseSpeedOf(model));
        SkyProperties.Dynamics cfg = properties.dynamics();
        double maxAccel = Math.max(0.2, cfg.maxAccelMps2());
        double gain = Math.max(0.1, cfg.speedGainPerSec());
        double lookahead = Math.max(10.0, cfg.lookaheadM());
        double arrivalRadius = Math.max(0.5, cfg.arrivalRadiusM());
        double arrivalSpeed = Math.max(0.3, cfg.arrivalSpeedMps());
        double cruiseAlt = Math.max(0.0, cruiseAltitudeM);
        double climbSpeed = Math.max(0.5, properties.flight().climbSpeedMps());
        double descentSpeed = Math.max(0.5, properties.flight().descentSpeedMps());

        // 下降过渡距离：按额定下降率与巡航速度换算；短航路最多占 45%，避免"没爬上去就下降"
        double descentRun = cruiseAlt <= 0.1 ? 0
                : Math.min(cruiseAlt / descentSpeed * cruise, total * 0.45);

        double stepSec = Math.max(0.05, cfg.integrationStepS());
        int steps = Math.max(1, (int) Math.ceil(deltaSeconds / stepSec));
        double h = deltaSeconds / steps;

        double energyWh = 0.0;
        boolean arrived = false;
        double dirX = 1.0;
        double dirY = 0.0;
        double windEast = 0.0;
        double windNorth = 0.0;

        for (int step = 0; step < steps && !arrived; step++) {
            double now = simTimeSec + step * h;
            WindField.Vector wind = windField.vectorAt(now, Math.max(1.0, state.altitudeM));
            windEast = wind.eastMps();
            windNorth = wind.northMps();

            double remaining = Math.max(0.0, total - flown);
            double[] target = pointAt(xs, ys, cum, Math.min(total, flown + lookahead));
            double dx = target[0] - state.eastM;
            double dy = target[1] - state.northM;
            double dist = Math.hypot(dx, dy);
            if (dist > 0.5) {
                dirX = dx / dist;
                dirY = dy / dist;
            } else {
                double[] dir = directionAt(xs, ys, cum, flown);
                dirX = dir[0];
                dirY = dir[1];
            }

            // 1) 高度：接近终点时按剩余距离线性下降到 0
            double altTarget = descentRun <= 0.1 || remaining >= descentRun
                    ? cruiseAlt
                    : cruiseAlt * clamp01(remaining / descentRun);
            double vzCmd = clamp((altTarget - state.altitudeM) * VERTICAL_GAIN, -descentSpeed, climbSpeed);
            state.vUp += (vzCmd - state.vUp) * Math.min(1.0, VERTICAL_LAG_PER_SEC * h);
            state.altitudeM += state.vUp * h;
            if (state.altitudeM < 0) {
                state.altitudeM = 0;
                state.vUp = 0;
            }

            // 2) 水平速度指令：末端制动曲线 + 起飞垂直优先 + 低空限速
            double brakeLimit = Math.sqrt(2 * maxAccel * Math.max(0.0, remaining - arrivalRadius)) + 0.2;
            double vTarget = Math.min(cruise, brakeLimit);
            // 只在"需要爬升且还贴着地"时垂直优先；下降末段不能再锁水平速度，否则会卡在终点前
            if (state.altitudeM < TAKEOFF_CLIMB_ONLY_M
                    && altTarget > state.altitudeM + 0.5
                    && remaining > TAKEOFF_CLIMB_ONLY_M) {
                vTarget = 0;
            }
            double altitudeFactor = cruiseAlt <= 1 ? 1.0 : clamp01(state.altitudeM / cruiseAlt);
            vTarget *= LOW_ALTITUDE_SPEED_FACTOR + (1 - LOW_ALTITUDE_SPEED_FACTOR) * altitudeFactor;

            // 固定空速换算成地速：逆风地速被压低、顺风地速更高，与派单/冲突预测口径一致。
            // 由"空速矢量 + 风矢量 = 地速矢量"解二次方程得到沿航迹的地速。
            double alongWind = windEast * dirX + windNorth * dirY;
            double windSq = windEast * windEast + windNorth * windNorth;
            double discriminant = alongWind * alongWind - windSq + cruise * cruise;
            double groundAtCruise = discriminant >= 0
                    ? alongWind + Math.sqrt(discriminant)
                    : Math.max(0.0, alongWind);
            double minGround = cruise * Math.max(0.05, properties.wind().minGroundSpeedFactor());
            groundAtCruise = Math.max(minGround, groundAtCruise);
            vTarget *= groundAtCruise / cruise;

            vTarget = Math.min(vTarget, 0.9 * dist + 0.2);
            // 最后几米用更强的阻尼收敛，避免"围绕终点来回抖"进不了到达判定
            if (remaining < TAKEOFF_CLIMB_ONLY_M) {
                vTarget = Math.min(vTarget, remaining * 0.6);
            }

            // 3) 空速上限：多旋翼的额定空速是硬约束，逆风时地速只能被压下来
            double desiredEast = dirX * vTarget;
            double desiredNorth = dirY * vTarget;
            double airEast = desiredEast - windEast;
            double airNorth = desiredNorth - windNorth;
            double airMag = Math.hypot(airEast, airNorth);
            if (airMag > cruise) {
                double scale = cruise / airMag;
                desiredEast = windEast + airEast * scale;
                desiredNorth = windNorth + airNorth * scale;
            }

            // 4) 加速度受限 → 速度与位置积分（半隐式欧拉）
            double ax = clamp((desiredEast - state.vEast) * gain, -maxAccel, maxAccel);
            double ay = clamp((desiredNorth - state.vNorth) * gain, -maxAccel, maxAccel);
            state.vEast += ax * h;
            state.vNorth += ay * h;
            state.eastM += state.vEast * h;
            state.northM += state.vNorth * h;

            // 5) 已飞距离按"沿航路分量"推进，只增不减（风把飞机吹偏不会让进度倒退）
            double along = state.vEast * dirX + state.vNorth * dirY;
            flown = Math.min(total, Math.max(flown, flown + along * h));

            // 6) 能耗按真实空速积分（悬停时空速≈风速，爬升时≈0）
            double airspeed = Math.hypot(state.vEast - windEast, state.vNorth - windNorth);
            energyWh += energyModel.instantPowerW(model, airspeed, payloadKg) * h / 3600.0;

            double groundSpeed = Math.hypot(state.vEast, state.vNorth);
            if (flown >= total - arrivalRadius && groundSpeed <= arrivalSpeed) {
                arrived = true;
            }
        }

        // 用几何投影校正进度（子步内沿航路分量是近似值）
        if (!arrived) {
            double[] project = project(xs, ys, cum, state.eastM, state.northM);
            flown = Math.max(flown, Math.min(total, Math.max(startFlownM, project[0])));
            if (flown >= total - arrivalRadius
                    && Math.hypot(state.vEast, state.vNorth) <= arrivalSpeed) {
                arrived = true;
            }
        }

        if (arrived) {
            // 落点吸附：停稳在航路终点，避免残留速度把飞机带出站点
            state.vEast = 0;
            state.vNorth = 0;
            state.vUp = 0;
            state.altitudeM = 0;
            state.eastM = xs[n - 1];
            state.northM = ys[n - 1];
            flown = total;
        }

        return snapshot(state, flown, energyWh, windEast, windNorth, arrived, dirX, dirY, path);
    }

    /** 清理某架无人机的运动状态（任务完成、失败、开始充电时调用）。 */
    public void clear(Long droneId) {
        if (droneId != null) {
            states.remove(droneId);
        }
    }

    private Motion snapshot(State state, double flown, double energyWh,
                            double windEast, double windNorth,
                            boolean arrived, double dirX, double dirY, List<GeoPoint> path) {
        double groundSpeed = Math.hypot(state.vEast, state.vNorth);
        double airEast = state.vEast - windEast;
        double airNorth = state.vNorth - windNorth;
        double airspeed = Math.hypot(airEast, airNorth);

        // 机头方向优先取空速矢量（有侧风时体现偏航角）；静止时取航路方向
        double heading;
        if (groundSpeed > 0.5 && airspeed > 0.3) {
            heading = (Math.toDegrees(Math.atan2(airEast, airNorth)) + 360.0) % 360.0;
        } else if (Math.hypot(dirX, dirY) > 1e-6) {
            heading = (Math.toDegrees(Math.atan2(dirX, dirY)) + 360.0) % 360.0;
        } else if (path != null && path.size() >= 2) {
            GeoPoint a = path.get(path.size() - 2);
            GeoPoint b = path.get(path.size() - 1);
            heading = GeoUtils.bearingDegrees(a.lat(), a.lon(), b.lat(), b.lon());
        } else {
            heading = 0.0;
        }

        double lat = state.refLat + state.northM / state.latScale;
        double lon = state.refLon + state.eastM / state.lonScale;
        return new Motion(lat, lon, state.altitudeM, groundSpeed, heading, airspeed,
                energyWh, flown, arrived);
    }

    /** 取/建运动状态：首次从数据库位置与速度重建，之后以内存积分结果为准。 */
    private State stateOf(Drone drone, List<GeoPoint> path) {
        Long id = drone.getId();
        State existing = id == null ? null : states.get(id);
        if (existing != null) {
            return existing;
        }
        GeoPoint origin;
        if (drone.getCurrentLat() != null && drone.getCurrentLon() != null) {
            origin = new GeoPoint(drone.getCurrentLat(), drone.getCurrentLon());
        } else if (path != null && !path.isEmpty()) {
            origin = path.get(0);
        } else {
            origin = new GeoPoint(0.0, 0.0);
        }
        double altitude = drone.getAltitudeM() == null ? 0 : drone.getAltitudeM().doubleValue();
        double speed = drone.getSpeedMps() == null ? 0 : drone.getSpeedMps().doubleValue();
        double heading = drone.getHeadingDeg() == null ? 0 : drone.getHeadingDeg().doubleValue();
        State created = new State(origin.lat(), origin.lon(), altitude, speed, heading);
        if (id != null) {
            states.put(id, created);
        }
        return created;
    }

    /** 沿折线按距离取点（局部平面坐标）。 */
    private static double[] pointAt(double[] xs, double[] ys, double[] cum, double distance) {
        if (distance <= 0) {
            return new double[]{xs[0], ys[0]};
        }
        for (int i = 1; i < xs.length; i++) {
            if (cum[i] >= distance) {
                double seg = cum[i] - cum[i - 1];
                double t = seg <= 1e-9 ? 0 : (distance - cum[i - 1]) / seg;
                return new double[]{xs[i - 1] + (xs[i] - xs[i - 1]) * t,
                        ys[i - 1] + (ys[i] - ys[i - 1]) * t};
            }
        }
        return new double[]{xs[xs.length - 1], ys[ys.length - 1]};
    }

    /** 折线在 distance 处的前进方向（单位向量）。 */
    private static double[] directionAt(double[] xs, double[] ys, double[] cum, double distance) {
        for (int i = 1; i < xs.length; i++) {
            if (cum[i] >= distance) {
                double dx = xs[i] - xs[i - 1];
                double dy = ys[i] - ys[i - 1];
                double len = Math.hypot(dx, dy);
                if (len > 1e-6) {
                    return new double[]{dx / len, dy / len};
                }
            }
        }
        int last = xs.length - 1;
        double dx = xs[last] - xs[Math.max(0, last - 1)];
        double dy = ys[last] - ys[Math.max(0, last - 1)];
        double len = Math.hypot(dx, dy);
        return len <= 1e-6 ? new double[]{1.0, 0.0} : new double[]{dx / len, dy / len};
    }

    /** 点到折线的投影：返回 {沿折线距离, 偏离折线距离}。 */
    private static double[] project(double[] xs, double[] ys, double[] cum, double px, double py) {
        double bestDist = Double.MAX_VALUE;
        double bestAlong = 0;
        for (int i = 1; i < xs.length; i++) {
            double bx = xs[i] - xs[i - 1];
            double by = ys[i] - ys[i - 1];
            double lenSq = bx * bx + by * by;
            double t = lenSq <= 1e-9 ? 0
                    : ((px - xs[i - 1]) * bx + (py - ys[i - 1]) * by) / lenSq;
            t = clamp01(t);
            double qx = xs[i - 1] + bx * t;
            double qy = ys[i - 1] + by * t;
            double d = Math.hypot(px - qx, py - qy);
            if (d < bestDist) {
                bestDist = d;
                bestAlong = cum[i - 1] + Math.sqrt(lenSq) * t;
            }
        }
        return new double[]{bestAlong, bestDist};
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    /** 单架无人机的运动状态（局部平面坐标，单位米、米/秒）。 */
    private static final class State {
        final double refLat;
        final double refLon;
        final double latScale;
        final double lonScale;
        double eastM;
        double northM;
        double altitudeM;
        double vEast;
        double vNorth;
        double vUp;

        State(double lat, double lon, double altitudeM, double groundSpeedMps, double headingDeg) {
            this.refLat = lat;
            this.refLon = lon;
            this.latScale = METERS_PER_DEGREE;
            this.lonScale = Math.max(1.0, METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
            this.altitudeM = Math.max(0.0, altitudeM);
            double rad = Math.toRadians(headingDeg);
            this.vEast = groundSpeedMps * Math.sin(rad);
            this.vNorth = groundSpeedMps * Math.cos(rad);
        }
    }
}
