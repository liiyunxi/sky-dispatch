package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DroneModel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 电量消耗模型（仿真的"物理引擎"）。
 *
 * <p><b>为什么不用"每公里固定 Wh × 距离"</b>：那是线性模型，会犯三个错：
 * <ol>
 *   <li>起降阶段速度掉到 30%，单位距离能耗本应飙升，线性模型却算出同样便宜；</li>
 *   <li>载重只按"每公斤 +5%"线性加成，重载边际成本被低估；</li>
 *   <li>速度越快越省电的错觉——真实多旋翼的功率随速度<b>三次方</b>增长。</li>
 * </ol>
 *
 * <p><b>本模型：旋翼功率曲线</b>
 * <pre>
 *   P(v, m) = P_induced(m) + B · v³
 *   P_induced(m) = P_hover · ((m_empty + payload) / m_empty)^1.5
 *   B = (P_cruise_rated − P_hover) / v_cruise³
 * </pre>
 * 其中：
 * <ul>
 *   <li>{@code P_induced} 用动量理论的 1.5 次方标度：载重越大，诱导功率越陡；</li>
 *   <li>{@code B·v³} 是气动型阻功率，随速度三次方增长；</li>
 *   <li>{@code B} 用"额定巡航速度下的额定每公里能耗"反解，保证
 *       <b>机型参数表里的 energy_per_km_wh 仍然等于该机型巡航时的真实油耗</b>，
 *       历史标定数据不会失效。</li>
 * </ul>
 *
 * <p>由此得到的每公里能耗是 <b>WhPerKm = P(v)/v</b>：低速段（起降加速）明显偏高，
 * 巡航段接近标定值，超速段按三次方抬升 —— 这正是真实多旋翼的 U 形/上扬曲线。
 *
 * <p>所有计算仍用 {@link BigDecimal} 返回，避免 {@code double} 累加出现
 * "电量 0.30000000000000004%" 这种脏数据；中间物理量用 double 计算，最后统一收敛。
 */
@Component
public class EnergyModel {

    /** 重力加速度（m/s²），仅用于文档中的量纲说明。 */
    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final double METERS_PER_KM = 1000.0;
    /** J/m → Wh/km 的换算系数：×1000（米→公里）÷3600（焦→瓦时）。 */
    private static final double J_PER_M_TO_WH_PER_KM = METERS_PER_KM / SECONDS_PER_HOUR;
    /** m/s → km/h。 */
    private static final double MPS_TO_KMH = 3.6;

    private final SkyProperties properties;

    public EnergyModel(SkyProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 一、功率曲线（模型核心）
    // ------------------------------------------------------------------

    /** 机型巡航速度（m/s），参数缺失时按 15 m/s 兜底，避免除零。 */
    public double cruiseSpeedOf(DroneModel model) {
        return model.getCruiseSpeedMps() == null || model.getCruiseSpeedMps().doubleValue() <= 0
                ? 15.0 : model.getCruiseSpeedMps().doubleValue();
    }

    /**
     * 空机质量（kg）：没有独立字段，按"最大载重 × 空机质量比"估算。
     *
     * <p>配送多旋翼的空机质量通常是最大载重的 2~3 倍（5kg 载重对应 12~15kg 机身），
     * 默认取 2.5，可通过 {@code sky.energy.airframe-mass-ratio} 调整。
     */
    public double airframeMassKg(DroneModel model) {
        double maxPayload = model.getMaxPayloadKg() == null
                ? 0 : model.getMaxPayloadKg().doubleValue();
        double ratio = properties.energy().airframeMassRatio();
        return Math.max(1.0, maxPayload * Math.max(0.5, ratio));
    }

    /**
     * 诱导功率（W）：悬停与低速段的主要开销。
     *
     * <p>动量理论里 P ∝ (mg)^1.5，所以载重不是线性加成，而是随总质量 1.5 次方上升。
     * 这比"每公斤 +5%"更接近真实的重载边际成本。
     */
    public double inducedPowerW(DroneModel model, double payloadKg) {
        double hover = hoverPowerOf(model);
        double emptyMass = airframeMassKg(model);
        double totalMass = emptyMass + Math.max(0.0, payloadKg);
        double exponent = Math.max(1.0, properties.energy().inducedExponent());
        return hover * Math.pow(totalMass / emptyMass, exponent);
    }

    /**
     * 型阻系数 B（W/(m/s)³）：由"额定巡航点"反解。
     *
     * <p>额定点：{@code P_rated = energyPerKmWh × v_cruise × 3.6}，
     * 于是 {@code B = (P_rated − P_hover) / v_cruise³}。
     * 这样在巡航速度、空载时，模型算出的每公里能耗严格等于参数表里的值。
     */
    private double parasiteCoefficient(DroneModel model) {
        double vc = cruiseSpeedOf(model);
        double ratedWhPerKm = energyPerKmOf(model);
        double ratedW = ratedWhPerKm * vc * MPS_TO_KMH;
        double hover = hoverPowerOf(model);
        double numerator = Math.max(0.0, ratedW - hover);
        return numerator / (vc * vc * vc);
    }

    /**
     * 空速 v、载重 payload 下的飞行功率（W）。
     *
     * <p>速度会被夹到不低于 {@code cruiseSpeed × minSpeedFactor}（默认 30%），
     * 避免 v→0 时功率表达式退化成纯悬停、每公里能耗趋于无穷。
     */
    public double powerAtSpeed(DroneModel model, double speedMps, double payloadKg) {
        double v = effectiveSpeed(model, speedMps);
        return inducedPowerW(model, payloadKg) + parasiteCoefficient(model) * v * v * v;
    }

    /**
     * 某空速下的每公里能耗（Wh/km）。
     *
     * <p>这就是"能耗随速度非线性变化"的直接体现：
     * 低速时 {@code P_induced} 被摊到很少的公里数上，每公里很贵；
     * 高速时型阻按三次方增长，每公里同样变贵。
     */
    public double whPerKm(DroneModel model, double speedMps, double payloadKg) {
        double v = effectiveSpeed(model, speedMps);
        return powerAtSpeed(model, v, payloadKg) / v * J_PER_M_TO_WH_PER_KM;
    }

    private double effectiveSpeed(DroneModel model, double speedMps) {
        double vc = cruiseSpeedOf(model);
        double floor = vc * Math.max(0.05, properties.energy().minSpeedFactor());
        return Math.max(Math.max(0.5, floor), speedMps);
    }

    // ------------------------------------------------------------------
    // 二、能耗计算（对外 API）
    // ------------------------------------------------------------------

    /**
     * 估算一次飞行需要的电量（Wh）：巡航（按巡航速度）+ 一次起降悬停。
     */
    public BigDecimal estimateEnergyWh(DroneModel model, double distanceM, double payloadKg) {
        return cruiseEnergyWh(model, distanceM, payloadKg).add(hoverEnergyWh(model, payloadKg));
    }

    /** 按机型额定巡航速度飞的能耗（无风）。 */
    public BigDecimal cruiseEnergyWh(DroneModel model, double distanceM, double payloadKg) {
        return cruiseEnergyWh(model, distanceM, payloadKg, cruiseSpeedOf(model), 1.0);
    }

    /**
     * 按机型额定巡航速度飞、并计入风修正系数的巡航能耗。
     *
     * <p>航路规划判断"这单能不能飞到"时用这个重载：只关心风速带来的时间伸缩，
     * 不额外指定空速（空速取机型额定巡航速度）。
     *
     * @param energyFactor 风修正系数（1.0 = 无风）
     */
    public BigDecimal cruiseEnergyWh(DroneModel model, double distanceM, double payloadKg,
                                     double energyFactor) {
        return cruiseEnergyWh(model, distanceM, payloadKg, cruiseSpeedOf(model), energyFactor);
    }

    /**
     * 带"实际空速 + 风修正"的巡航能耗。
     *
     * <p>两个乘数各管一件事：
     * <ul>
     *   <li>{@code airspeedMps} 进功率曲线：起降加减速段速度低，每公里能耗自然上升；</li>
     *   <li>{@code energyFactor} 是风带来的时间伸缩（逆风飞得慢、耗时长），
     *       由 {@link WindField} 按"固定空速"算出。</li>
     * </ul>
     *
     * @param airspeedMps  本拍实际空速（m/s），传 ≤0 时退化为巡航速度
     * @param energyFactor 风修正系数（1.0 = 无风）
     */
    public BigDecimal cruiseEnergyWh(DroneModel model, double distanceM, double payloadKg,
                                     double airspeedMps, double energyFactor) {
        if (distanceM <= 0) {
            return BigDecimal.ZERO;
        }
        double speed = airspeedMps > 0.1 ? airspeedMps : cruiseSpeedOf(model);
        double whPerKm = whPerKm(model, speed, payloadKg);
        double km = distanceM / METERS_PER_KM;
        double factor = energyFactor > 0 ? energyFactor : 1.0;
        return BigDecimal.valueOf(whPerKm * km * factor).setScale(6, RoundingMode.HALF_UP);
    }

    /** 起降悬停能耗（空载），保留旧签名给不关心载重的调用方（如航程粗估）。 */
    public BigDecimal hoverEnergyWh(DroneModel model) {
        return hoverEnergyWh(model, 0);
    }

    /**
     * 起降悬停能耗（含载重）。
     *
     * <p>悬停时没有前进速度，功率就是诱导功率，随总质量 1.5 次方上升；
     * 载重越大，起降本身越费电——这是线性模型完全没有体现的。
     */
    public BigDecimal hoverEnergyWh(DroneModel model, double payloadKg) {
        double powerW = inducedPowerW(model, payloadKg);
        double seconds = Math.max(0.0, properties.energy().takeoffLandingSeconds());
        return BigDecimal.valueOf(powerW * seconds / SECONDS_PER_HOUR).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 一次完整派送任务需要多少能量：<b>空载调机 + 去程载货 + 回程空载 + 两次起降悬停</b>。
     *
     * <p>这是调度做"能不能接这单"判断的依据。最容易犯的错误是只算单程——
     * 无人机送完货还得飞回来，只按单程选机会导致飞机半路没电。
     * 回程按空载算，是因为货已经卸下了，这一点也必须体现在模型里。
     *
     * <p>两段悬停：送货点降落（载货）与最终落地（空载），因此载重会同时抬高
     * 巡航段与其中一次起降的能量开销。
     */
    public BigDecimal missionEnergyWh(DroneModel model, double deadheadMeters, double oneWayMeters, double payloadKg) {
        BigDecimal deadhead = cruiseEnergyWh(model, deadheadMeters, 0);
        BigDecimal outbound = cruiseEnergyWh(model, oneWayMeters, payloadKg);
        BigDecimal inbound = cruiseEnergyWh(model, oneWayMeters, 0);
        BigDecimal hover = hoverEnergyWh(model, payloadKg).add(hoverEnergyWh(model, 0));
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
     * <p>用同一套非线性模型反解：先扣掉带载悬停，再除以巡航每公里能耗。
     * 这样"航程"和"实际扣电"永远出自同一个模型，不会出现
     * "参数表说能飞 11km、仿真里 9km 就没电"的矛盾。
     *
     * @param usableBatteryPct 可用电量百分比（已扣除安全冗余）
     */
    public double maxRangeMeters(DroneModel model, double payloadKg, double usableBatteryPct) {
        if (usableBatteryPct <= 0) {
            return 0;
        }
        double usableWh = model.getBatteryCapacityWh() == null
                ? 0 : model.getBatteryCapacityWh().doubleValue() * usableBatteryPct / 100.0;
        double hoverWh = hoverEnergyWh(model, payloadKg).doubleValue();
        double netWh = usableWh - hoverWh;
        if (netWh <= 0) {
            return 0;
        }
        double perKm = whPerKm(model, cruiseSpeedOf(model), payloadKg);
        if (perKm <= 1e-9) {
            return 0;
        }
        return netWh / perKm * METERS_PER_KM;
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

    private double hoverPowerOf(DroneModel model) {
        return model.getHoverPowerW() == null ? 0 : model.getHoverPowerW().doubleValue();
    }

    private double energyPerKmOf(DroneModel model) {
        return model.getEnergyPerKmWh() == null ? 0 : model.getEnergyPerKmWh().doubleValue();
    }
}
