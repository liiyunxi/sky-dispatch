package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.dto.DroneDtos;
import com.skydispatch.repository.DroneModelRepository;
import com.skydispatch.repository.DroneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 无人机台账与状态管理。
 *
 * <p>注意每一处返回 DTO 的 public 方法上都标了 {@code @Transactional}：
 * 因为 {@code DroneDtos.Response.from()} 要访问懒加载的 {@code drone.model}，
 * 而项目里关掉了 OSIV（{@code spring.jpa.open-in-view=false}），
 * 事务外访问懒加载字段会直接抛 {@code LazyInitializationException}。
 * 这是初学者最常踩的坑之一：DTO 转换要么在事务内，要么显式 JOIN FETCH。
 */
@Service
public class DroneService {

    private static final Logger log = LoggerFactory.getLogger(DroneService.class);

    private final DroneRepository droneRepository;
    private final DroneModelRepository droneModelRepository;

    public DroneService(DroneRepository droneRepository, DroneModelRepository droneModelRepository) {
        this.droneRepository = droneRepository;
        this.droneModelRepository = droneModelRepository;
    }

    /** 注册一架新无人机，初始状态 IDLE（空闲待命）。 */
    @Transactional
    public DroneDtos.Response register(DroneDtos.CreateRequest request) {
        // 编号可以不填：留空就自动生成。用户显式给的就按用户的来（但仍要查重）。
        boolean autoCode = request.code() == null || request.code().isBlank();
        String code = autoCode ? nextDroneCode() : request.code().trim();
        droneRepository.findByCode(code).ifPresent(d -> {
            throw BusinessException.invalidParam("机身编号已存在: " + code
                    + (autoCode ? "（自动生成时撞号，请重试）" : ""));
        });
        DroneModel model = droneModelRepository.findByCode(request.modelCode())
                .orElseThrow(() -> BusinessException.notFound("无人机型号 " + request.modelCode()));

        BigDecimal battery = request.batteryPct() == null ? BigDecimal.valueOf(100) : request.batteryPct();

        Drone drone = Drone.builder()
                .code(code)
                .model(model)
                .status(DroneStatus.IDLE)
                .batteryPct(battery)
                .currentLat(request.home().lat())
                .currentLon(request.home().lon())
                .altitudeM(BigDecimal.ZERO)
                .speedMps(BigDecimal.ZERO)
                .headingDeg(BigDecimal.ZERO)
                .homeLat(request.home().lat())
                .homeLon(request.home().lon())
                .totalFlightMin(BigDecimal.ZERO)
                .totalDistanceKm(BigDecimal.ZERO)
                .totalTasks(0)
                .version(0L)
                .build();

        Drone saved = droneRepository.save(drone);
        log.info("注册无人机: code={}, 型号={}, 初始电量={}%, 编号来源={}",
                saved.getCode(), model.getCode(), battery, autoCode ? "自动生成" : "用户指定");
        return DroneDtos.Response.from(saved);
    }

    /** 自动生成机身编号的前缀（与演示数据同一族，便于在列表里一眼认出"正式机"和"补录机"）。 */
    private static final String AUTO_DRONE_CODE_PREFIX = "SKY-DR-";

    /**
     * 生成一个未被占用的机身编号（如 {@code SKY-DR-0007}）。
     *
     * <p>为什么不直接用 {@code count() + 1}？因为删除过机、或并发注册时计数会撞号。
     * 所以从当前数量往后**逐个试**，编号列上的数据库唯一约束做最后兜底；
     * 找不到可用编号时给出可读的失败信息，而不是抛一条约束违例。
     */
    private String nextDroneCode() {
        int start = (int) droneRepository.count();
        for (int i = start + 1; i < start + 10_000; i++) {
            String candidate = AUTO_DRONE_CODE_PREFIX + String.format("%04d", i);
            if (droneRepository.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw BusinessException.illegalState("无法自动生成机身编号：编号空间已用尽，请手动指定编号");
    }

    /** 查询无人机列表，可按状态过滤。 */
    @Transactional(readOnly = true)
    public List<DroneDtos.Response> list(DroneStatus status) {
        List<Drone> drones = status == null ? droneRepository.findAll() : droneRepository.findByStatus(status);
        return drones.stream().map(DroneDtos.Response::from).toList();
    }

    /** 查询单架无人机。 */
    @Transactional(readOnly = true)
    public DroneDtos.Response get(Long id) {
        return DroneDtos.Response.from(require(id));
    }

    /**
     * 手动改变无人机状态。
     *
     * <p>业务校验：正在执行任务的无人机不允许直接改成 CHARGING/OFFLINE，
     * 否则会出现"任务还在飞，飞机已经下线"的脏数据。
     */
    @Transactional
    public DroneDtos.Response updateStatus(Long id, DroneStatus status) {
        Drone drone = require(id);
        if (drone.getStatus() == DroneStatus.IN_FLIGHT && status != DroneStatus.RETURNING) {
            throw BusinessException.illegalState(
                    "无人机 " + drone.getCode() + " 正在执行任务，只能改为 RETURNING 或先中止任务");
        }
        drone.setStatus(status);
        // 充满电后自动恢复待命，是小细节但能省掉一次人工操作
        if (status == DroneStatus.IDLE) {
            drone.setSpeedMps(BigDecimal.ZERO);
        }
        log.info("无人机 {} 状态改为 {}", drone.getCode(), status);
        return DroneDtos.Response.from(drone);
    }

    /** 按 id 取实体（不存在就抛 404）。返回实体供其它 Service 在事务内继续加工。 */
    @Transactional(readOnly = true)
    public Drone require(Long id) {
        return droneRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("无人机 id=" + id));
    }

    /** 保存实体（供仿真引擎每 tick 更新位置与电量）。 */
    @Transactional
    public Drone save(Drone drone) {
        return droneRepository.save(drone);
    }

    /** 全部无人机实体，供调度模块做候选筛选。 */
    @Transactional(readOnly = true)
    public List<Drone> findAll() {
        return droneRepository.findAll();
    }
}
