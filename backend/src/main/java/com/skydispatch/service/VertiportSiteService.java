package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.VertiportSite;
import com.skydispatch.dto.InfrastructureDtos;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.VertiportSiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机巢站点台账服务：为每个实际使用的起降点维护独立的坪位数与周转时间。
 *
 * <p>站点不是让用户手工录的：无人机的 home 坐标本身就是"这里有一个起降点"的事实，
 * 启动后（以及基础设施页每次打开时）自动扫描机队、补齐缺失站点，保证
 * "调度认得的站点"与"页面能配置的站点"永远是同一套。
 */
@Service
public class VertiportSiteService {

    private static final Logger log = LoggerFactory.getLogger(VertiportSiteService.class);
    private static final String CODE_PREFIX = "VP-";

    private final VertiportSiteRepository siteRepository;
    private final DroneRepository droneRepository;

    /** siteKey → 站点配置缓存：起飞/降落是高频路径，不能每次都查库。 */
    private final Map<String, VertiportSite> cache = new ConcurrentHashMap<>();

    public VertiportSiteService(VertiportSiteRepository siteRepository, DroneRepository droneRepository) {
        this.siteRepository = siteRepository;
        this.droneRepository = droneRepository;
    }

    /** 站点业务键：与 VertiportService 的运行态 key 必须完全一致。 */
    public static String keyOf(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);
    }

    /** 单站配置（运行态需要的两个值）。 */
    public record SiteConfig(int slots, double turnaroundSeconds) {
    }

    /** 启动后补齐一次；即使演示数据在之后才灌入，基础设施页仍会再调用 ensureSites 兜底。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            ensureSites();
        } catch (RuntimeException e) {
            log.warn("机巢站点初始化失败（不影响启动，稍后打开基础设施页会重试）: {}", e.getMessage());
        }
    }

    /**
     * 扫描机队，为每个 home 坐标补齐站点台账。
     *
     * @return 本次新建的站点数
     */
    @Transactional
    public int ensureSites() {
        List<Drone> drones = droneRepository.findAll();
        Set<String> seen = new HashSet<>();
        int created = 0;
        for (Drone drone : drones) {
            if (drone.getHomeLat() == null || drone.getHomeLon() == null) {
                continue;
            }
            String key = keyOf(drone.getHomeLat(), drone.getHomeLon());
            if (!seen.add(key)) {
                continue;
            }
            VertiportSite site = cache.get(key);
            if (site == null) {
                site = siteRepository.findBySiteKey(key).orElse(null);
            }
            if (site == null) {
                site = createSite(key, drone.getHomeLat(), drone.getHomeLon());
                created++;
            }
            cache.put(key, site);
        }
        if (created > 0) {
            log.info("机巢站点台账补齐 {} 个（机队 {} 架）", created, drones.size());
        }
        return created;
    }

    /**
     * 取站点配置；未知站点自动建档（新注册无人机的 home 可能从未出现过）。
     *
     * <p>方法本身带事务：它可能被仿真 tick 的调用链触发，需要能安全插入新行。
     */
    @Transactional
    public SiteConfig configFor(String siteKey, double lat, double lon) {
        VertiportSite site = cache.get(siteKey);
        if (site == null) {
            site = siteRepository.findBySiteKey(siteKey)
                    .orElseGet(() -> createSite(siteKey, lat, lon));
            cache.put(siteKey, site);
        }
        int slots = site.getSlots() == null ? 4 : Math.max(1, site.getSlots());
        double turnaround = site.getTurnaroundSeconds() == null ? 45.0 : Math.max(0.0, site.getTurnaroundSeconds());
        return new SiteConfig(slots, turnaround);
    }

    /** 全部站点（基础设施页用）。 */
    @Transactional(readOnly = true)
    public List<VertiportSite> all() {
        List<VertiportSite> sites = siteRepository.findAllByOrderByIdAsc();
        sites.forEach(site -> cache.put(site.getSiteKey(), site));
        return sites;
    }

    /** 修改某站点的坪位数 / 周转时间。 */
    @Transactional
    public VertiportSite update(Long id, InfrastructureDtos.VertiportUpdate request) {
        VertiportSite site = siteRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("机巢站点 id=" + id));
        if (request.slots() != null) {
            int slots = request.slots();
            if (slots < 1 || slots > 32) {
                throw BusinessException.invalidParam("坪位数需在 1 ~ 32 之间");
            }
            site.setSlots(slots);
        }
        if (request.turnaroundSeconds() != null) {
            double turnaround = request.turnaroundSeconds();
            if (turnaround < 0 || turnaround > 600) {
                throw BusinessException.invalidParam("周转时间需在 0 ~ 600 秒之间");
            }
            site.setTurnaroundSeconds(turnaround);
        }
        VertiportSite saved = siteRepository.save(site);
        cache.put(saved.getSiteKey(), saved);
        log.info("机巢站点 {} 配置更新：坪位={}, 周转={}s", saved.getCode(), saved.getSlots(), saved.getTurnaroundSeconds());
        return saved;
    }

    private VertiportSite createSite(String siteKey, double lat, double lon) {
        String code = nextCode();
        VertiportSite site = VertiportSite.builder()
                .siteKey(siteKey)
                .code(code)
                .name("起降点 " + code)
                .lat(lat)
                .lon(lon)
                .slots(4)
                .turnaroundSeconds(45.0)
                .build();
        return siteRepository.save(site);
    }

    /** 生成未占用的站点编码（VP-001 起顺延）。 */
    private String nextCode() {
        int start = (int) siteRepository.count();
        for (int i = start + 1; i < start + 10_000; i++) {
            String code = CODE_PREFIX + String.format("%03d", i);
            if (!siteRepository.existsByCode(code)) {
                return code;
            }
        }
        throw BusinessException.illegalState("机巢站点编码空间已用尽");
    }
}
