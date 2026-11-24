package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.entity.DroneModel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 可达性分析：判断"这架飞机能不能把这单送完"，而不是只比电量。
 *
 * <p><b>为什么不能只看电量</b>：有补能网络之后，"电量不够"并不等于"飞不到"。
 * 一次任务可以拆成若干个航段，中途落到充电站把电补到 {@code relayResumePct}
 * （默认 90%）再继续飞。于是真正的判断变成一张<b>带补能节点的图</b>：
 *
 * <pre>
 *   起点(调机) ──▶ 取货点 ──▶ 送货点 ──▶ 机巢
 *          ↘        ↘         ↘        ↗
 *            ─────── 充电站网络 ───────
 * </pre>
 *
 * <p>本类在这张图上做一次带"补能次数预算"的搜索（节点只有几十个，用动态规划即可），
 * 输出<b>最少经停几站、经停哪些站、每段耗多少电、最紧一段还剩多少余量</b>。
 *
 * <p>三个关键口径：
 * <ol>
 *   <li><b>每个航段都算一次起降悬停</b>：飞机落到站/落到取货点都要悬停，不能只算巡航；</li>
 *   <li><b>载重只作用于取货后到送货前</b>：调机与返航是空载，能耗更低；</li>
 *   <li><b>补能只能补到 {@code relayResumePct}</b>，且仍要保留安全冗余，
 *       所以补能后的可用电量是 {@code (relayResumePct - minBatteryReservePct)%}，
 *       而不是满电——这与飞行中自主补能的口径完全一致。</li>
 * </ol>
 *
 * <p>搜索上限 {@value #MAX_CHARGING_STOPS} 次补能：超过这个数说明这单本来就不该接，
 * 与其排一张"落七八次站"的计划，不如让运营看到"不可达"。
 */
@Service
public class ReachabilityService {

    /** 一次任务最多考虑几次中途补能。 */
    private static final int MAX_CHARGING_STOPS = 3;

    // ---- 图中的任务节点（充电站从 FIRST_STATION 开始编号） ----
    private static final int START = 0;
    private static final int PICKUP = 1;
    private static final int DROPOFF = 2;
    private static final int HOME = 3;
    private static final int FIRST_STATION = 4;

    // ---- 任务阶段：调机（空载）→ 载货 → 返航（空载） ----
    private static final int PHASE_DEADHEAD = 0;
    private static final int PHASE_DELIVERY = 1;
    private static final int PHASE_RETURN = 2;

    private static final double EPS = 1e-6;

    private final EnergyModel energyModel;
    private final SkyProperties properties;
    private final WindField windField;

    public ReachabilityService(EnergyModel energyModel, SkyProperties properties, WindField windField) {
        this.energyModel = energyModel;
        this.properties = properties;
        this.windField = windField;
    }

    /** 计划中的一个补能点。 */
    public record ChargingStop(
            /** 第几次补能（从 1 开始） */
            int sequence,
            Long stationId,
            String stationName,
            String city,
            double lat,
            double lon,
            /** 所在的航段：调机段 / 载货段 / 返航段 */
            String leg,
            /** 从上一节点飞到本站的距离（米） */
            double legDistanceM,
            /** 这一段消耗的能量（含一次起降悬停） */
            double hopEnergyWh
    ) {
    }

    /**
     * 可达性结论。
     *
     * @param feasible       能否完成"调机 → 取货 → 送达 → 返航"
     * @param direct         是否无需补能（直飞）
     * @param stopCount      中途补能次数
     * @param stops          补能点明细（按飞行顺序）
     * @param totalDistanceM 实际飞行总距离（含为补能多飞的绕行）
     * @param totalEnergyWh  实际飞行总能耗（含每次起降悬停）
     * @param initialEnergyWh 出发时可用能量
     * @param tightestMarginWh 余量最小的一段（可用 - 需求），越大越安全
     * @param summary        一句话结论，可直接展示给运营
     * @param failureReason  不可达时说明卡在哪一段
     */
    public record Plan(
            boolean feasible,
            boolean direct,
            int stopCount,
            List<ChargingStop> stops,
            double totalDistanceM,
            double totalEnergyWh,
            double initialEnergyWh,
            double tightestMarginWh,
            String summary,
            String failureReason
    ) {
        /** 能飞，但必须中途补能。 */
        public boolean needsCharging() {
            return feasible && stopCount > 0;
        }

        public static Plan unreachable(String reason) {
            return new Plan(false, false, 0, List.of(), 0, 0, 0, 0, null, reason);
        }
    }

    /** 一次航段（用于还原路径）。 */
    private record Hop(int from, int to, int phase, boolean refueled) {
    }

    /**
     * 分析一次完整任务。
     *
     * @param model          机型（能耗/载重/电池参数）
     * @param start          无人机当前位置
     * @param pickup         取货点
     * @param dropoff        送货点
     * @param home           机巢（返航终点）；为 null 表示只要求送达、不要求返航
     * @param payloadKg      货物重量
     * @param initialEnergyWh 出发时可用能量（已扣除安全冗余）
     * @param stations       可用充电站；继电器开关关闭时传空列表即可退化为"只看电量"
     */
    public Plan analyze(DroneModel model,
                        GeoPoint start,
                        GeoPoint pickup,
                        GeoPoint dropoff,
                        GeoPoint home,
                        double payloadKg,
                        BigDecimal initialEnergyWh,
                        List<ChargingStation> stations) {
        double initialWh = initialEnergyWh == null ? 0 : initialEnergyWh.doubleValue();
        if (initialWh <= 0) {
            return Plan.unreachable("可用电量为 0，无法起飞");
        }

        boolean hasHome = home != null;
        double stationWh = stationAvailableWh(model);
        List<ChargingStation> usableStations = corridorStations(
                model, start, pickup, dropoff, home, payloadKg, initialWh, stationWh, stations);

        int nodeCount = FIRST_STATION + usableStations.size();
        double[][][] best = new double[MAX_CHARGING_STOPS + 1][3][nodeCount];
        int[][][] prevK = new int[MAX_CHARGING_STOPS + 1][3][nodeCount];
        int[][][] prevPhase = new int[MAX_CHARGING_STOPS + 1][3][nodeCount];
        int[][][] prevNode = new int[MAX_CHARGING_STOPS + 1][3][nodeCount];
        boolean[][][] refueled = new boolean[MAX_CHARGING_STOPS + 1][3][nodeCount];
        for (int k = 0; k <= MAX_CHARGING_STOPS; k++) {
            for (int p = 0; p < 3; p++) {
                Arrays.fill(best[k][p], -1);
                Arrays.fill(prevK[k][p], -1);
                Arrays.fill(prevPhase[k][p], -1);
                Arrays.fill(prevNode[k][p], -1);
            }
        }
        best[0][PHASE_DEADHEAD][START] = initialWh;

        relax(model, start, pickup, dropoff, home, payloadKg, stationWh, usableStations,
                nodeCount, best, prevK, prevPhase, prevNode, refueled, hasHome);

        int goalPhase = hasHome ? PHASE_RETURN : PHASE_DELIVERY;
        int goalNode = hasHome ? HOME : DROPOFF;

        int stopK = -1;
        for (int k = 0; k <= MAX_CHARGING_STOPS; k++) {
            if (best[k][goalPhase][goalNode] >= 0) {
                stopK = k;
                break;
            }
        }
        if (stopK < 0) {
            return Plan.unreachable(diagnose(model, start, pickup, dropoff, payloadKg,
                    initialWh, stationWh, best, hasHome));
        }

        List<Hop> hops = reconstruct(stopK, goalPhase, goalNode, prevK, prevPhase, prevNode, refueled);
        return describe(model, start, pickup, dropoff, home, payloadKg, initialWh,
                stationWh, usableStations, hops);
    }

    // ------------------------------------------------------------------
    // 一、图搜索（带补能次数预算的动态规划）
    // ------------------------------------------------------------------

    /**
     * 松弛所有边，直到能量不再提升。
     *
     * <p>状态 = (已补能次数, 阶段, 当前节点)，值 = 到达该状态时<b>最多还剩多少电</b>。
     * 补能会把电量重置为 {@code stationWh}，所以不能用普通的"最短路"，
     * 而要做"能量最大化"的松弛——这正是本方法循环到收敛的原因。
     */
    private void relax(DroneModel model,
                       GeoPoint start, GeoPoint pickup, GeoPoint dropoff, GeoPoint home,
                       double payloadKg, double stationWh, List<ChargingStation> stations,
                       int nodeCount,
                       double[][][] best, int[][][] prevK, int[][][] prevPhase,
                       int[][][] prevNode, boolean[][][] refueled, boolean hasHome) {
        boolean changed = true;
        int guard = (MAX_CHARGING_STOPS + 1) * 3 * Math.max(1, nodeCount) + 8;
        while (changed && guard-- > 0) {
            changed = false;
            for (int k = 0; k <= MAX_CHARGING_STOPS; k++) {
                for (int phase = 0; phase < 3; phase++) {
                    if (phase == PHASE_RETURN && !hasHome) {
                        continue;
                    }
                    int target = targetOf(phase);
                    for (int u = 0; u < nodeCount; u++) {
                        double available = best[k][phase][u];
                        if (available < 0) {
                            continue;
                        }
                        // 到达阶段目标即结束该阶段，不再从目标继续飞（否则会绕圈）
                        if (u == target || !isValidOrigin(u, phase)) {
                            continue;
                        }
                        GeoPoint from = pointOf(u, start, pickup, dropoff, home, stations);
                        double payload = payloadForPhase(phase, payloadKg);

                        // ① 直飞本阶段目标
                        GeoPoint targetPoint = pointOf(target, start, pickup, dropoff, home, stations);
                        double toTarget = planningHopEnergy(model, from, targetPoint, payload);
                        if (toTarget <= available + EPS) {
                            double arrival = Math.max(0, available - toTarget);
                            if (arrival > best[k][phase][target] + EPS) {
                                best[k][phase][target] = arrival;
                                prevK[k][phase][target] = k;
                                prevPhase[k][phase][target] = phase;
                                prevNode[k][phase][target] = u;
                                changed = true;
                            }
                            // 进入下一阶段（调机 → 载货 → 返航）
                            int nextPhase = phase + 1;
                            if (nextPhase <= (hasHome ? PHASE_RETURN : PHASE_DELIVERY)
                                    && arrival > best[k][nextPhase][target] + EPS) {
                                best[k][nextPhase][target] = arrival;
                                prevK[k][nextPhase][target] = k;
                                prevPhase[k][nextPhase][target] = phase;
                                prevNode[k][nextPhase][target] = u;
                                changed = true;
                            }
                        }

                        // ② 先落到某个充电站补能，再继续
                        for (int s = 0; s < stations.size(); s++) {
                            int node = FIRST_STATION + s;
                            if (node == u) {
                                continue;
                            }
                            GeoPoint stationPoint = pointOf(node, start, pickup, dropoff, home, stations);
                            double need = planningHopEnergy(model, from, stationPoint, payload);
                            if (need > available + EPS) {
                                continue;
                            }
                            double arrival = Math.max(0, available - need);
                            // 到站后最多补到 stationWh；若到站时电量反而更高，就不补
                            double after = Math.max(arrival, stationWh);
                            boolean didRefuel = after > arrival + EPS;
                            int nextK = didRefuel ? k + 1 : k;
                            if (nextK > MAX_CHARGING_STOPS) {
                                continue;
                            }
                            if (after > best[nextK][phase][node] + EPS) {
                                best[nextK][phase][node] = after;
                                prevK[nextK][phase][node] = k;
                                prevPhase[nextK][phase][node] = phase;
                                prevNode[nextK][phase][node] = u;
                                refueled[nextK][phase][node] = didRefuel;
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
    }

    /** 该节点能否作为某阶段继续出发的点：阶段起点或任意充电站。 */
    private static boolean isValidOrigin(int node, int phase) {
        if (node >= FIRST_STATION) {
            return true;
        }
        return switch (phase) {
            case PHASE_DEADHEAD -> node == START;
            case PHASE_DELIVERY -> node == PICKUP;
            case PHASE_RETURN -> node == DROPOFF;
            default -> false;
        };
    }

    private static int targetOf(int phase) {
        return switch (phase) {
            case PHASE_DEADHEAD -> PICKUP;
            case PHASE_DELIVERY -> DROPOFF;
            default -> HOME;
        };
    }

    private static double payloadForPhase(int phase, double payloadKg) {
        return phase == PHASE_DELIVERY ? payloadKg : 0;
    }

    /** 一个航段的能耗：巡航 + 一次起降悬停（落地/起飞都要悬停，载重越大越费电）。 */
    private double hopEnergy(DroneModel model, GeoPoint from, GeoPoint to, double payloadKg) {
        double distance = from.distanceTo(to);
        BigDecimal energy = energyModel.cruiseEnergyWh(model, distance, payloadKg)
                .add(energyModel.hoverEnergyWh(model, payloadKg));
        return energy.doubleValue();
    }

    /**
     * 规划用航段能耗：在 {@link #hopEnergy} 的基础上按<b>航段航向</b>计入风裕度。
     *
     * <p>为什么按航段而不是整单算一个系数：一单的"去程"和"返程"航向相反，
     * 逆风与顺风会自然抵消；如果把整单都乘一个逆风系数，就会把返程的顺风收益也抹掉，
     * 导致明明飞得回来的单被判成不可达。按段计价才是物理上正确的做法，
     * 也让"长距离调机撞上逆风"这种真实难点被准确识别。
     *
     * <p>风裕度只用于<b>可行性判断</b>（能否飞完、要不要经停补能），
     * 不改变展示的"预计能耗"与仿真的实际扣电。
     */
    private double planningHopEnergy(DroneModel model, GeoPoint from, GeoPoint to, double payloadKg) {
        double distance = from.distanceTo(to);
        if (distance <= 1e-6) {
            return energyModel.hoverEnergyWh(model, payloadKg).doubleValue();
        }
        double cruiseSpeed = model.getCruiseSpeedMps() == null
                ? 0 : model.getCruiseSpeedMps().doubleValue();
        double bearing = GeoUtils.bearingDegrees(from.lat(), from.lon(), to.lat(), to.lon());
        double factor = windField.planningEnergyFactor(
                cruiseSpeed, bearing, properties.flight().defaultAltitudeM());
        return energyModel.cruiseEnergyWh(model, distance, payloadKg, factor)
                .add(energyModel.hoverEnergyWh(model, payloadKg))
                .doubleValue();
    }

    /** 补能后可用能量：补到 {@code relayResumePct} 再扣掉安全冗余。 */
    private double stationAvailableWh(DroneModel model) {
        SkyProperties.Dispatch cfg = properties.dispatch();
        double usablePct = Math.max(0, cfg.relayResumePct() - cfg.minBatteryReservePct());
        return Math.max(0, energyModel.usableEnergyWh(model, usablePct).doubleValue());
    }

    private static GeoPoint pointOf(int node, GeoPoint start, GeoPoint pickup, GeoPoint dropoff,
                                    GeoPoint home, List<ChargingStation> stations) {
        return switch (node) {
            case START -> start;
            case PICKUP -> pickup;
            case DROPOFF -> dropoff;
            case HOME -> home;
            default -> {
                ChargingStation s = stations.get(node - FIRST_STATION);
                yield new GeoPoint(s.getLat(), s.getLon());
            }
        };
    }

    /**
     * 只保留"跳得到"的充电站，避免把全国 40 座站都塞进搜索。
     *
     * <p>判据：站点必须落在某个任务节点的"单段最大航程"之内，否则任何一跳都飞不到，
     * 放进图里也只是增加无用的松弛。
     */
    private List<ChargingStation> corridorStations(DroneModel model,
                                                   GeoPoint start, GeoPoint pickup, GeoPoint dropoff,
                                                   GeoPoint home, double payloadKg,
                                                   double initialWh, double stationWh,
                                                   List<ChargingStation> stations) {
        if (stations == null || stations.isEmpty()) {
            return List.of();
        }
        double capacity = model.getBatteryCapacityWh() == null
                ? 0 : model.getBatteryCapacityWh().doubleValue();
        double maxUsableWh = Math.max(initialWh, stationWh);
        double maxUsablePct = capacity <= 0 ? 0 : Math.min(100, maxUsableWh / capacity * 100);
        double maxLegM = energyModel.maxRangeMeters(model, payloadKg, maxUsablePct) * 1.15;
        if (maxLegM <= 0) {
            return List.of();
        }
        List<GeoPoint> anchors = new ArrayList<>(4);
        anchors.add(start);
        anchors.add(pickup);
        anchors.add(dropoff);
        if (home != null) {
            anchors.add(home);
        }
        List<ChargingStation> candidates = new ArrayList<>();
        List<GeoPoint> points = new ArrayList<>();
        for (ChargingStation station : stations) {
            if (station.getLat() == null || station.getLon() == null) {
                continue;
            }
            candidates.add(station);
            points.add(new GeoPoint(station.getLat(), station.getLon()));
        }

        boolean[] keep = new boolean[candidates.size()];
        for (int i = 0; i < points.size(); i++) {
            for (GeoPoint anchor : anchors) {
                if (points.get(i).distanceTo(anchor) <= maxLegM) {
                    keep[i] = true;
                    break;
                }
            }
        }
        /*
         * 再做一次"沿站接力"的扩散：任务点 20km 外的站本身跳不到，但它可能是
         * "任务点 9km 的站 → 9km 的下一站"这条补给链的第二跳。只保留第一圈会漏掉
         * 这种多跳方案。站点最多几十个，这里直接反复扩散到不再增长即可。
         */
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < points.size(); i++) {
                if (keep[i]) {
                    continue;
                }
                for (int j = 0; j < points.size(); j++) {
                    if (keep[j] && points.get(i).distanceTo(points.get(j)) <= maxLegM) {
                        keep[i] = true;
                        grew = true;
                        break;
                    }
                }
            }
        }

        List<ChargingStation> kept = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (keep[i]) {
                kept.add(candidates.get(i));
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------
    // 二、还原路径与结论
    // ------------------------------------------------------------------

    /** 从终点状态回溯到起点，得到按飞行顺序排列的航段。 */
    private static List<Hop> reconstruct(int stopK, int goalPhase, int goalNode,
                                         int[][][] prevK, int[][][] prevPhase,
                                         int[][][] prevNode, boolean[][][] refueled) {
        List<Hop> hops = new ArrayList<>();
        int k = stopK;
        int phase = goalPhase;
        int node = goalNode;
        int guard = 256;
        while (guard-- > 0) {
            if (phase == PHASE_DEADHEAD && node == START) {
                break;
            }
            int pk = prevK[k][phase][node];
            int pp = prevPhase[k][phase][node];
            int pn = prevNode[k][phase][node];
            if (pk < 0 || pp < 0 || pn < 0) {
                break;
            }
            hops.add(new Hop(pn, node, phase, refueled[k][phase][node]));
            k = pk;
            phase = pp;
            node = pn;
        }
        Collections.reverse(hops);
        return hops;
    }

    /** 沿还原出的路径重算能耗、余量与补能点，生成可展示的结论。 */
    private Plan describe(DroneModel model, GeoPoint start, GeoPoint pickup, GeoPoint dropoff,
                          GeoPoint home, double payloadKg, double initialWh, double stationWh,
                          List<ChargingStation> stations, List<Hop> hops) {
        double available = initialWh;
        double totalDistance = 0;
        double totalEnergy = 0;
        double tightest = Double.MAX_VALUE;
        List<ChargingStop> stops = new ArrayList<>();

        for (Hop hop : hops) {
            GeoPoint from = pointOf(hop.from(), start, pickup, dropoff, home, stations);
            GeoPoint to = pointOf(hop.to(), start, pickup, dropoff, home, stations);
            double distance = from.distanceTo(to);
            double payload = payloadForPhase(hop.phase(), payloadKg);
            double need = hopEnergy(model, from, to, payload);
            double arrival = available - need;

            totalDistance += distance;
            totalEnergy += need;
            tightest = Math.min(tightest, arrival);

            boolean isStation = hop.to() >= FIRST_STATION;
            if (isStation && hop.refueled()) {
                ChargingStation station = stations.get(hop.to() - FIRST_STATION);
                stops.add(new ChargingStop(stops.size() + 1, station.getId(), station.getName(),
                        station.getCity(), station.getLat(), station.getLon(),
                        phaseText(hop.phase()), distance, need));
                available = Math.max(arrival, stationWh);
            } else {
                available = arrival;
            }
        }

        if (tightest == Double.MAX_VALUE) {
            tightest = initialWh;
        }
        String summary = stops.isEmpty()
                ? String.format("直飞可达：全程 %.1fkm，预计耗电 %.0fWh，可用 %.0fWh（最紧一段余量 %.0fWh）%s",
                totalDistance / 1000, totalEnergy, initialWh, Math.max(0, tightest), windNote(model, start, pickup, dropoff, home))
                : String.format("需经停 %d 次补能（%s）：全程 %.1fkm，预计耗电 %.0fWh%s",
                stops.size(), joinStationNames(stops), totalDistance / 1000, totalEnergy,
                windNote(model, start, pickup, dropoff, home));

        return new Plan(true, stops.isEmpty(), stops.size(), List.copyOf(stops),
                totalDistance, totalEnergy, initialWh, Math.max(0, tightest), summary, null);
    }

    /**
     * 结论里补一句"这单受了多大风影响"，让派单/决策明细能看出逆风因素。
     *
     * <p>取三个主航段（调机 / 载货去程 / 返航）里最不利的一段，
     * 顺风段（系数 &lt; 1）不算"不利"，所以只报 &gt; 1 的情况。
     */
    private String windNote(DroneModel model, GeoPoint start, GeoPoint pickup,
                            GeoPoint dropoff, GeoPoint home) {
        double cruiseSpeed = model.getCruiseSpeedMps() == null
                ? 0 : model.getCruiseSpeedMps().doubleValue();
        if (cruiseSpeed <= 0 || !windField.enabled()) {
            return "";
        }
        double altitude = properties.flight().defaultAltitudeM();
        double deadhead = windField.planningEnergyFactor(cruiseSpeed,
                bearing(start, pickup), altitude);
        double outbound = windField.planningEnergyFactor(cruiseSpeed,
                bearing(pickup, dropoff), altitude);
        double inbound = home == null ? 1.0
                : windField.planningEnergyFactor(cruiseSpeed, bearing(dropoff, home), altitude);
        double worst = Math.max(deadhead, Math.max(outbound, inbound));
        return worst > 1.02
                ? String.format("（已计入逆风裕度 ×%.2f）", worst)
                : "";
    }

    private static double bearing(GeoPoint from, GeoPoint to) {
        return GeoUtils.bearingDegrees(from.lat(), from.lon(), to.lat(), to.lon());
    }

    private static String joinStationNames(List<ChargingStop> stops) {
        StringBuilder sb = new StringBuilder();
        for (ChargingStop stop : stops) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append(stop.stationName());
        }
        return sb.toString();
    }

    private static String phaseText(int phase) {
        return switch (phase) {
            case PHASE_DEADHEAD -> "调机段";
            case PHASE_DELIVERY -> "载货段";
            default -> "返航段";
        };
    }

    /**
     * 不可达时说明卡在哪一段 —— 这句话会写进派单决策，运营据此判断：
     * 是该加站点、换大电池机型，还是这单确实太远。
     */
    private String diagnose(DroneModel model, GeoPoint start, GeoPoint pickup, GeoPoint dropoff,
                            double payloadKg, double initialWh, double stationWh,
                            double[][][] best, boolean hasHome) {
        if (!reached(best, PHASE_DEADHEAD, PICKUP)) {
            double need = planningHopEnergy(model, start, pickup, 0);
            return String.format("空载调机段就飞不到取货点（含风裕度需 %.0fWh，可用 %.0fWh）", need, initialWh);
        }
        if (!reached(best, PHASE_DELIVERY, DROPOFF)) {
            double availableAtPickup = maxAt(best, PHASE_DEADHEAD, PICKUP);
            double need = planningHopEnergy(model, pickup, dropoff, payloadKg);
            return String.format("取货后无法抵达送货点（载货含风裕度需 %.0fWh，落地取货后最多可用 %.0fWh，"
                            + "且沿途没有可落脚的补能站）",
                    need, Math.max(availableAtPickup, stationWh));
        }
        // 能送达但回不来
        double need = planningHopEnergy(model, dropoff, start, 0);
        return String.format("可以送达，但无法返航（回程含风裕度需 %.0fWh，卸货后余量不足，"
                        + "且沿途没有可落脚的补能站）",
                need);
    }

    private static boolean reached(double[][][] best, int phase, int node) {
        for (int k = 0; k < best.length; k++) {
            if (best[k][phase][node] >= 0) {
                return true;
            }
        }
        return false;
    }

    private static double maxAt(double[][][] best, int phase, int node) {
        double max = 0;
        for (int k = 0; k < best.length; k++) {
            max = Math.max(max, best[k][phase][node]);
        }
        return max;
    }
}
