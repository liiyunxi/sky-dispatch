package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.repository.DroneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 机队补能（仅在 dev 演示环境开启）。
 *
 * <p><b>为什么需要它</b>：电量模型只扣不补，固定几单送完后，飞机普遍跌到
 * 40%~50%，再往后要么因"可用电量不足"接不了单、要么触发低电量保护进入维护，
 * 机队会在几分钟内集体趴窝 —— 打开实时看板就看不到任何活动的无人机。
 *
 * <p>演示语义：把起降点假想为"带充电桩的机巢"，飞机在站待命（IDLE）时持续补能；
 * 因低电量进入维护（MAINTENANCE）的飞机充到恢复阈值后自动回到可调度状态。
 * 这样机队形成"接单 → 送货 → 返航 → 补能 → 再接单"的持续周转。
 *
 * <p><b>生产环境必须关闭</b>（{@code sky.simulation.demo-recharge-enabled=false}，
 * postgres profile 默认即此值）：真实的电量应来自换电/充电调度与电池循环，
 * 不能凭空回充。
 */
@Service
public class FleetRechargeService {

    private static final Logger log = LoggerFactory.getLogger(FleetRechargeService.class);

    private final DroneRepository droneRepository;
    private final ChargingStationRegistry chargingRegistry;
    private final SkyProperties properties;

    public FleetRechargeService(DroneRepository droneRepository,
                                ChargingStationRegistry chargingRegistry,
                                SkyProperties properties) {
        this.droneRepository = droneRepository;
        this.chargingRegistry = chargingRegistry;
        this.properties = properties;
    }

    /**
     * 推进一拍的补能。
     *
     * @param simDeltaSeconds 本拍推进的<b>仿真秒</b>（已含倍速）
     * @return 本拍参与补能的飞机数
     */
    @Transactional
    public int recharge(double simDeltaSeconds) {
        SkyProperties.Simulation cfg = properties.simulation();
        if (!cfg.demoRechargeEnabled() || simDeltaSeconds <= 0) {
            return 0;
        }
        double gain = cfg.rechargeRatePctPerSimSec() * simDeltaSeconds;
        double target = cfg.rechargeTargetPct();
        double resume = cfg.rechargeResumePct();

        int chargingCount = 0;
        // 只取"在站且电量未满"的飞机，避免每拍扫描整张机队表
        for (Drone drone : droneRepository.findRechargeable(
                java.util.List.of(DroneStatus.IDLE, DroneStatus.MAINTENANCE),
                BigDecimal.valueOf(target))) {
            DroneStatus status = drone.getStatus();
            if (status == null) {
                continue;
            }
            double battery = drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue();

            // 只给"在站"的飞机补能：待命的 IDLE，以及 dev 中仅由低电量保护置为 MAINTENANCE 的飞机。
            // 飞行/已派单/离线的不动。
            boolean atStation = status == DroneStatus.IDLE || status == DroneStatus.MAINTENANCE;
            if (!atStation || battery >= target) {
                continue;
            }

            double next = Math.min(target, battery + gain * chargingRegistry.powerFactor(battery));
            drone.setBatteryPct(BigDecimal.valueOf(next).setScale(2, RoundingMode.HALF_UP));

            // 维护中的飞机充到恢复阈值即回到可调度；IDLE 机补能期间保持可接单（在桩待命）
            if (status == DroneStatus.MAINTENANCE && next >= resume) {
                drone.setStatus(DroneStatus.IDLE);
                drone.setCurrentTaskId(null);
                log.info("无人机 {} 补能至 {}%，恢复为可调度", drone.getCode(), drone.getBatteryPct());
            }
            droneRepository.save(drone);
            chargingCount++;
        }
        return chargingCount;
    }
}
