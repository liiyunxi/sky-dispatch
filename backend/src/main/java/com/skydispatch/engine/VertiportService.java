package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.service.VertiportSiteService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机巢/起降坪资源模型。
 *
 * <p>每架无人机都从某个起降点（{@code homeLat/homeLon}）出发、回到同一点。
 * 真实起降坪不是无限资源：
 * <ul>
 *   <li><b>容量</b>：同一时刻能进行的起降架次有限（停机位/起降坪数量）；</li>
 *   <li><b>地面周转</b>：降落后要检查、装卸、换电，期间不能立刻再次起飞。</li>
 * </ul>
 * <p>这里按"同一坐标点"聚合成一个起降点，用 {@code operationSeconds / slots}
 * 控制起飞间隔，用<b>站点自己的</b> {@code turnaroundSeconds} 控制单机周转。
 *
 * <p>容量与周转来自 {@link VertiportSiteService} 的站点台账：
 * 每个机巢可以单独配置坪位数与周转时间（机场 6 坪位、社区 2 坪位），
 * 而不是所有站点共用一份全局配置。
 *
 * <p>拒绝起飞不是错误：任务保持 ASSIGNED 在地面等待，下一拍重试，
 * 事件流里会说明"地面周转中/起降坪繁忙"，这比让飞机凭空瞬移真实得多。
 */
@Component
public class VertiportService {

    private final SkyProperties properties;
    private final VertiportSiteService siteService;

    /** 起降点 → 下一次允许起飞的最早仿真时刻。 */
    private final Map<String, Double> nextDepartureAt = new ConcurrentHashMap<>();
    /** 无人机 → 可再次起飞的仿真时刻（降落后周转）。 */
    private final Map<Long, Double> readyAt = new ConcurrentHashMap<>();

    public VertiportService(SkyProperties properties, VertiportSiteService siteService) {
        this.properties = properties;
        this.siteService = siteService;
    }

    /**
     * 起飞放行结果。
     *
     * @param granted        是否放行
     * @param notBeforeSimSec 最早可起飞时刻（拒绝时用于事件说明）
     * @param reason         拒绝原因
     */
    public record Decision(boolean granted, double notBeforeSimSec, String reason) {
    }

    /** 请求起飞：检查地面周转与起降坪容量。 */
    public Decision requestTakeoff(Drone drone, double simTimeS) {
        SkyProperties.Vertiport cfg = properties.vertiport();
        double ready = readyAt.getOrDefault(drone.getId(), 0.0);
        if (simTimeS < ready - 1e-6) {
            return new Decision(false, ready, "地面周转中");
        }
        String site = siteKey(drone);
        double next = nextDepartureAt.getOrDefault(site, 0.0);
        if (simTimeS < next - 1e-6) {
            return new Decision(false, next, "起降坪繁忙");
        }
        // 按站点的坪位数把一次起降占用时间摊开：6 坪位、15s 占用 → 平均 2.5s 可放行一架
        VertiportSiteService.SiteConfig siteConfig = siteService.configFor(
                site, homeLat(drone), homeLon(drone));
        int slots = Math.max(1, siteConfig.slots());
        double interval = Math.max(0.5, cfg.operationSeconds() / slots);
        nextDepartureAt.put(site, Math.max(simTimeS, next) + interval);
        return new Decision(true, simTimeS, "放行");
    }

    /** 记录降落：占用该站点的地面周转时间。 */
    public void landed(Drone drone, double simTimeS) {
        VertiportSiteService.SiteConfig siteConfig = siteService.configFor(
                siteKey(drone), homeLat(drone), homeLon(drone));
        readyAt.put(drone.getId(), simTimeS + Math.max(0.0, siteConfig.turnaroundSeconds()));
    }

    /** 该机最早可再次起飞的时刻（用于事件与排查）。 */
    public double readyAt(Long droneId) {
        return readyAt.getOrDefault(droneId, 0.0);
    }

    /** 该站点下一次可放行起飞的仿真时刻；没有记录返回 0。 */
    public double nextDepartureAt(String siteKey) {
        return nextDepartureAt.getOrDefault(siteKey, 0.0);
    }

    /** 起降点键：同一坐标视为同一机巢。 */
    public String siteKey(Drone drone) {
        return VertiportSiteService.keyOf(homeLat(drone), homeLon(drone));
    }

    /** 新建/重置运行时清空：机巢放行节奏与单机周转都属于本次演练的运行态。 */
    public void clearAll() {
        nextDepartureAt.clear();
        readyAt.clear();
    }

    private double homeLat(Drone drone) {
        return drone.getHomeLat() == null ? 0.0 : drone.getHomeLat();
    }

    private double homeLon(Drone drone) {
        return drone.getHomeLon() == null ? 0.0 : drone.getHomeLon();
    }
}
