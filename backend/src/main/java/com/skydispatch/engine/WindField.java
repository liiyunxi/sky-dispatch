package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import org.springframework.stereotype.Component;

/**
 * 风场模型：给仿真提供"可复现的确定性风"。
 *
 * <p><b>为什么需要风</b>：没有风时，同一架飞机、同一条航路，到达时间和能耗永远是确定的，
 * 系统中永远不会出现"逆风延误"，调度策略之间的差异也就体现不出来。
 * 加上风之后，一条链路被串起来：
 * <pre>
 *   风 → 地速（逆风变慢）→ 到达时间 → 准时率 → 能耗（按空速算）→ 是否需要补能 → 派单结果
 * </pre>
 *
 * <p><b>模型由三部分叠加</b>：
 * <ol>
 *   <li><b>高度剪切</b>：地面风小、高空风大，用工程上常用的幂律
 *       {@code v(z) = v_ref · (z / z_ref)^α}（α≈0.14，开阔地形）；</li>
 *   <li><b>阵风</b>：两个不可通约周期的正弦叠加，形成"看起来不重复"的起伏；</li>
 *   <li><b>风向摆动</b>：幅度很小（默认 ±6°），避免风在整场仿真里一动不动。</li>
 * </ol>
 *
 * <p><b>为什么全部用 simTime 推导，而不是随机数</b>：
 * 仿真必须可复现。用 {@code Random} 会导致同一 seed 下两次运行结果不同，
 * 演示、回归测试、答辩复盘都会对不上。这里的每一项都是 simTime 的纯函数。
 *
 * <p><b>物理口径：固定空速</b>。真实多旋翼的自动驾驶仪保持的是<b>空速</b>，
 * 不是地速。所以这里假设无人机以巡航空速飞行，风把它的地速推走：
 * <pre>
 *   地速矢量 = 空速矢量 + 风矢量
 * </pre>
 * 由此得到三个自然结果（都不需要额外拍参数）：
 * <ul>
 *   <li><b>逆风</b>：地速下降 → 同样距离耗时更长 → 每米能耗上升；</li>
 *   <li><b>顺风</b>：地速上升 → 更快到达 → 每米能耗下降；</li>
 *   <li><b>侧风</b>：机头必须偏转（偏航角 / crab angle）才能保持航迹。</li>
 * </ul>
 * 这也是"固定空速"比"固定地速"更适合仿真的原因：它让风同时影响时间与能耗，
 * 而不是只影响其中一个。
 */
@Component
public class WindField {

    private final SkyProperties properties;

    public WindField(SkyProperties properties) {
        this.properties = properties;
    }

    /** 风矢量（局部平面，x 向东、y 向北，单位米/秒）。 */
    public record Vector(double eastMps, double northMps) {

        /** 风速大小（米/秒）。 */
        public double speedMps() {
            return Math.hypot(eastMps, northMps);
        }

        /** 风的<b>来向</b>（度，正北为 0、顺时针），与气象报文口径一致。 */
        public double fromDirectionDeg() {
            if (speedMps() < 1e-6) {
                return 0.0;
            }
            double toward = Math.toDegrees(Math.atan2(eastMps, northMps));
            return (toward + 180.0 + 360.0) % 360.0;
        }
    }

    /**
     * 一次"风修正"的结果。
     *
     * @param groundSpeedFactor 地速系数：真实地速 = 期望地速 × 该系数（逆风 &lt; 1）
     * @param energyFactor      每米能耗系数：逆风 &gt; 1，顺风 &lt; 1
     * @param noseBearingDeg    机头朝向（度）：含偏航角，侧风时机头不指航迹方向
     * @param windSpeedMps      当前风速（米/秒）
     * @param windFromDeg       当前风的来向（度）
     * @param headwindMps       沿航迹的逆风分量（米/秒，正数表示逆风）
     */
    public record Adjustment(
            double groundSpeedFactor,
            double energyFactor,
            double noseBearingDeg,
            double windSpeedMps,
            double windFromDeg,
            double headwindMps
    ) {
        /** 无风/关闭时的中性结果。 */
        public static Adjustment calm(double trackBearingDeg) {
            return new Adjustment(1.0, 1.0, trackBearingDeg, 0.0, 0.0, 0.0);
        }
    }

    /** 风场是否启用。 */
    public boolean enabled() {
        return properties.wind().enabled();
    }

    /**
     * 规划用风矢量：<b>平均风</b>（不含阵风与风向摆动）。
     *
     * <p>派单决策不能用"此刻的阵风"：那会让同一单在不同秒被派给不同飞机，
     * 结果不可复现。规划用确定性的平均风 + 一个固定安全系数，执行阶段才用瞬时风。
     */
    public Vector planningVectorAt(double altitudeM) {
        SkyProperties.Wind cfg = properties.wind();
        if (!cfg.enabled()) {
            return new Vector(0.0, 0.0);
        }
        double refAlt = Math.max(1.0, cfg.referenceAltitudeM());
        double alt = Math.max(1.0, altitudeM);
        double shear = Math.pow(alt / refAlt, Math.max(0.0, cfg.shearExponent()));
        double speed = Math.max(0.0, cfg.baseSpeedMps() * shear);
        double towardRad = Math.toRadians(cfg.directionDeg() + 180.0);
        return new Vector(speed * Math.sin(towardRad), speed * Math.cos(towardRad));
    }

    /**
     * 派单阶段使用的"每米能耗裕度系数"。
     *
     * <p>和 {@link #adjust} 的区别：
     * <ul>
     *   <li>用<b>平均风</b>而不是瞬时阵风，保证同一单在任何时刻派都是一个结果；</li>
     *   <li>额外乘 {@code planningSafetyFactor}，覆盖阵风与预报误差。</li>
     * </ul>
     *
     * @return 每米能耗系数（逆风 &gt; 1，顺风 &lt; 1），未启用风/风裕度时返回 1.0
     */
    public double planningEnergyFactor(double cruiseSpeedMps, double trackBearingDeg, double altitudeM) {
        SkyProperties.Wind cfg = properties.wind();
        if (!cfg.enabled() || !cfg.planningEnabled() || cruiseSpeedMps <= 1e-6) {
            return 1.0;
        }
        double factor = adjust(planningVectorAt(altitudeM), cruiseSpeedMps, trackBearingDeg).energyFactor();
        return factor * Math.max(1.0, cfg.planningSafetyFactor());
    }

    /**
     * 取某一仿真时刻、某一高度上的风矢量。
     *
     * @param simTimeSec 仿真时间（秒）
     * @param altitudeM  飞行高度（米）
     */
    public Vector vectorAt(double simTimeSec, double altitudeM) {
        SkyProperties.Wind cfg = properties.wind();
        if (!cfg.enabled()) {
            return new Vector(0.0, 0.0);
        }

        // 1) 高度剪切：地面上风小，越高风越大
        double refAlt = Math.max(1.0, cfg.referenceAltitudeM());
        double alt = Math.max(1.0, altitudeM);
        double shear = Math.pow(alt / refAlt, Math.max(0.0, cfg.shearExponent()));

        // 2) 阵风：两个不可通约周期的正弦叠加，周期比取黄金分割近似，避免周期性"打拍子"
        double t = Math.max(0.0, simTimeSec);
        double period = Math.max(5.0, cfg.gustPeriodS());
        double gust = 1.0 + cfg.gustRatio()
                * (0.62 * Math.sin(2 * Math.PI * t / period)
                + 0.38 * Math.sin(2 * Math.PI * t / (period * 0.618) + 1.1));

        double speed = Math.max(0.0, cfg.baseSpeedMps() * shear * gust);

        // 3) 风向轻微摆动，让风看起来"活"但不突变
        double wander = cfg.directionWanderDeg()
                * Math.sin(2 * Math.PI * t / (period * 1.7) + 0.4);
        double fromDeg = (cfg.directionDeg() + wander + 360.0) % 360.0;
        // 来向 → 去向（吹向方向）
        double towardRad = Math.toRadians(fromDeg + 180.0);

        return new Vector(speed * Math.sin(towardRad), speed * Math.cos(towardRad));
    }

    /**
     * 计算风对本段航行的修正（固定空速模型）。
     *
     * <p>求解思路：空速矢量 = {@code k · u − w}（u 为航迹单位向量，w 为风矢量），
     * 要求其模等于巡航空速 cruise，解出 {@code k} 即地速大小：
     * <pre>
     *   k² − 2k(u·w) + |w|² − cruise² = 0
     *   k = (u·w) + √((u·w)² − |w|² + cruise²)
     * </pre>
     * 取正根。判别式小于 0 表示"风太强，这个空速下根本无法保持该航迹"，
     * 此时退化为尽力而为：地速被压到下限，仿真继续推进但会明显变慢。
     *
     * @param cruiseSpeedMps  机型巡航空速（固定空速）
     * @param trackBearingDeg 计划航迹方向（度）
     * @param altitudeM       巡航高度（米）
     * @param simTimeSec      仿真时间（秒）
     */
    public Adjustment adjust(double cruiseSpeedMps, double trackBearingDeg,
                             double altitudeM, double simTimeSec) {
        SkyProperties.Wind cfg = properties.wind();
        if (!cfg.enabled() || cruiseSpeedMps <= 1e-6) {
            return Adjustment.calm(trackBearingDeg);
        }
        return adjust(vectorAt(simTimeSec, altitudeM), cruiseSpeedMps, trackBearingDeg);
    }

    /**
     * 风修正的核心计算（瞬时风与规划风共用）。
     *
     * @param wind            风矢量
     * @param cruiseSpeedMps  固定空速
     * @param trackBearingDeg 航迹方向
     */
    private Adjustment adjust(Vector wind, double cruiseSpeedMps, double trackBearingDeg) {
        SkyProperties.Wind cfg = properties.wind();
        double bearing = Math.toRadians(trackBearingDeg);
        // 航迹单位向量（东, 北）
        double ux = Math.sin(bearing);
        double uy = Math.cos(bearing);

        // 风沿航迹的分量：正 = 顺风，负 = 逆风
        double along = wind.eastMps() * ux + wind.northMps() * uy;
        double headwind = -along;

        double windSq = wind.eastMps() * wind.eastMps() + wind.northMps() * wind.northMps();
        double discriminant = along * along - windSq + cruiseSpeedMps * cruiseSpeedMps;

        double groundSpeed;
        if (discriminant >= 0) {
            groundSpeed = along + Math.sqrt(discriminant);
        } else {
            // 风强到无法保持航迹：按"迎着风尽力飞"退化处理，地速取逆风水平
            groundSpeed = Math.max(0.0, along);
        }

        // 兜底：极端逆风也不允许把地速压到"原地不动"，否则仿真会卡死
        double minGround = cruiseSpeedMps * Math.max(0.05, cfg.minGroundSpeedFactor());
        groundSpeed = Math.max(minGround, groundSpeed);

        double groundFactor = groundSpeed / cruiseSpeedMps;

        // 固定空速 → 单位时间能耗恒定 → 每米能耗 ∝ 1 / 地速
        double energyFactor = clamp(
                1.0 / Math.max(0.05, groundFactor),
                Math.max(0.1, cfg.minEnergyFactor()),
                Math.max(1.0, cfg.maxEnergyFactor()));

        // 机头朝空速矢量方向：侧风时表现为偏航角（crab angle）
        double airEast = ux * groundSpeed - wind.eastMps();
        double airNorth = uy * groundSpeed - wind.northMps();
        double nose = Math.hypot(airEast, airNorth) < 1e-6
                ? trackBearingDeg
                : (Math.toDegrees(Math.atan2(airEast, airNorth)) + 360.0) % 360.0;

        return new Adjustment(groundFactor, energyFactor, nose,
                wind.speedMps(), wind.fromDirectionDeg(), headwind);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
