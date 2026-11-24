package com.skydispatch.service;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.entity.VertiportSite;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.dto.InfrastructureDtos;
import com.skydispatch.engine.ChargingStationRegistry;
import com.skydispatch.engine.SimulationService;
import com.skydispatch.engine.StrategicDeconflictionService;
import com.skydispatch.engine.VertiportService;
import com.skydispatch.repository.ChargingStationRepository;
import com.skydispatch.repository.DroneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基础设施聚合服务：把充电站、机巢、战略时隙三类运行态拼成一张"能不能撑住"的读数。
 *
 * <p>原先这些信息埋在 map tooltip、机队卡片和日志里：站点排队看不到、
 * 机巢容量只有全局配置、战略时隙完全不可见。这里统一出口，页面只发一个请求。
 */
@Service
public class InfrastructureService {

    private final ChargingStationRepository stationRepository;
    private final DroneRepository droneRepository;
    private final ChargingStationRegistry chargingRegistry;
    private final VertiportSiteService siteService;
    private final VertiportService vertiports;
    private final StrategicDeconflictionService strategic;
    private final SimulationService simulationService;
    private final SkyProperties properties;

    public InfrastructureService(ChargingStationRepository stationRepository,
                                 DroneRepository droneRepository,
                                 ChargingStationRegistry chargingRegistry,
                                 VertiportSiteService siteService,
                                 VertiportService vertiports,
                                 StrategicDeconflictionService strategic,
                                 SimulationService simulationService,
                                 SkyProperties properties) {
        this.stationRepository = stationRepository;
        this.droneRepository = droneRepository;
        this.chargingRegistry = chargingRegistry;
        this.siteService = siteService;
        this.vertiports = vertiports;
        this.strategic = strategic;
        this.simulationService = simulationService;
        this.properties = properties;
    }

    /**
     * 基础设施总览。
     *
     * <p>标 {@code @Transactional} 而不是 readOnly：首次打开时可能需要为机队补齐机巢台账。
     */
    @Transactional
    public InfrastructureDtos.Overview overview() {
        siteService.ensureSites();
        double simTime = currentSimTime();
        List<Drone> drones = droneRepository.findAll();
        Map<String, List<Drone>> dronesBySite = drones.stream()
                .filter(drone -> drone.getHomeLat() != null && drone.getHomeLon() != null)
                .collect(Collectors.groupingBy(drone ->
                        VertiportSiteService.keyOf(drone.getHomeLat(), drone.getHomeLon())));

        List<InfrastructureDtos.StationView> stations = stationViews();
        List<InfrastructureDtos.VertiportView> vertiports = siteService.all().stream()
                .map(site -> toVertiportView(site, dronesBySite, simTime))
                .toList();
        InfrastructureDtos.SlotSnapshot slots = slotSnapshot();

        int chargingDrones = stations.stream().mapToInt(s -> value(s.occupancy())).sum();
        int queuedDrones = stations.stream().mapToInt(s -> value(s.queueLength())).sum();
        int totalChargeSlots = stations.stream().mapToInt(s -> value(s.chargeSlots())).sum();
        long maxWait = stations.stream().mapToLong(s -> value(s.estimatedWaitSeconds())).max().orElse(0);
        int totalPads = vertiports.stream().mapToInt(v -> value(v.slots())).sum();
        int busyVertiports = (int) vertiports.stream()
                .filter(v -> value(v.nextFreeInSeconds()) > 0 || value(v.busyDrones()) > 0)
                .count();
        int slotOccupancy = slots.slots().stream()
                .mapToInt(slot -> slot.occupants() == null ? 0 : slot.occupants().size())
                .sum();
        int slotCapacity = slots.slots().stream().mapToInt(slot -> value(slot.capacity())).sum();

        InfrastructureDtos.Summary summary = new InfrastructureDtos.Summary(
                stations.size(), totalChargeSlots, chargingDrones, queuedDrones, maxWait,
                vertiports.size(), totalPads, busyVertiports, slotOccupancy, slotCapacity);

        return new InfrastructureDtos.Overview(Instant.now(), simTime, summary, stations, vertiports, slots);
    }

    /** 只读时隙快照（给需要单独刷新的调用方）。 */
    @Transactional(readOnly = true)
    public InfrastructureDtos.SlotSnapshot slotSnapshot() {
        StrategicDeconflictionService.Snapshot snapshot = strategic.snapshot();
        List<InfrastructureDtos.SlotView> slots = new ArrayList<>();
        for (StrategicDeconflictionService.SlotView slot : snapshot.slots()) {
            List<InfrastructureDtos.SlotOccupant> occupants = slot.occupants().stream()
                    .map(o -> new InfrastructureDtos.SlotOccupant(
                            o.taskId(), o.taskNo(), o.droneCode(), o.orderId()))
                    .toList();
            slots.add(new InfrastructureDtos.SlotView(
                    slot.key(), slot.sector(), slot.layerM(), slot.startSimSec(), slot.endSimSec(),
                    slot.capacity(), occupants));
        }
        return new InfrastructureDtos.SlotSnapshot(
                snapshot.enabled(), snapshot.slotSeconds(), snapshot.departuresPerSlot(), slots);
    }

    /** 修改机巢站点配置，返回更新后的站点视图。 */
    @Transactional
    public InfrastructureDtos.VertiportView updateVertiport(Long id, InfrastructureDtos.VertiportUpdate request) {
        VertiportSite site = siteService.update(id, request);
        double simTime = currentSimTime();
        Map<String, List<Drone>> dronesBySite = droneRepository.findAll().stream()
                .filter(drone -> drone.getHomeLat() != null && drone.getHomeLon() != null)
                .collect(Collectors.groupingBy(drone ->
                        VertiportSiteService.keyOf(drone.getHomeLat(), drone.getHomeLon())));
        return toVertiportView(site, dronesBySite, simTime);
    }

    // ------------------------------------------------------------------
    // 内部组装
    // ------------------------------------------------------------------

    private List<InfrastructureDtos.StationView> stationViews() {
        List<ChargingStation> stations = stationRepository.findAllByEnabledTrue();
        return stations.stream().map(station -> {
            int occupancy = chargingRegistry.occupancy(station.getId());
            int queue = chargingRegistry.queueLength(station.getId());
            return new InfrastructureDtos.StationView(
                    station.getId(), station.getCode(), station.getName(), station.getCity(),
                    station.getLat(), station.getLon(), station.getChargeSlots(),
                    occupancy, queue, estimatedWaitSeconds(station, occupancy, queue),
                    station.getEnabled(), station.getRemark());
        }).toList();
    }

    /**
     * 预计等待：有空桩且无人排队 = 0；否则按"还要排几轮"乘以一次完整补能时长。
     *
     * <p>补能时长按当前充电速率与目标电量的保守估计（从 20% 充到目标），
     * 不是精确值，但足以让运营判断"这一站现在要不要分流"。
     */
    private long estimatedWaitSeconds(ChargingStation station, int occupancy, int queue) {
        int slots = Math.max(1, station.getChargeSlots() == null ? 1 : station.getChargeSlots());
        if (occupancy < slots && queue == 0) {
            return 0;
        }
        double rate = properties.simulation().rechargeRatePctPerSimSec();
        double target = properties.simulation().rechargeTargetPct();
        double fullChargeSeconds = rate > 0 ? Math.max(30, (target - 20.0) / rate) : 300;
        int waves = (int) Math.ceil((double) (queue + 1) / slots);
        return Math.round(waves * fullChargeSeconds);
    }

    private InfrastructureDtos.VertiportView toVertiportView(VertiportSite site,
                                                             Map<String, List<Drone>> dronesBySite,
                                                             double simTime) {
        List<Drone> based = dronesBySite.getOrDefault(site.getSiteKey(), List.of());
        int busy = 0;
        int turnaround = 0;
        for (Drone drone : based) {
            DroneStatus status = drone.getStatus();
            if (status == DroneStatus.ASSIGNED || status == DroneStatus.IN_FLIGHT
                    || status == DroneStatus.RETURNING) {
                busy++;
            }
            if (vertiports.readyAt(drone.getId()) > simTime) {
                turnaround++;
            }
        }
        double nextFree = Math.max(0, vertiports.nextDepartureAt(site.getSiteKey()) - simTime);
        return new InfrastructureDtos.VertiportView(
                site.getId(), site.getCode(), site.getName(), site.getSiteKey(),
                site.getLat(), site.getLon(), site.getSlots(), site.getTurnaroundSeconds(),
                based.size(), busy, turnaround, Math.round(nextFree));
    }

    private double currentSimTime() {
        SimulationRun run = simulationService.currentOrNull();
        return run == null || run.getSimTimeS() == null ? 0.0 : run.getSimTimeS().doubleValue();
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static long value(Long number) {
        return number == null ? 0 : number;
    }
}
