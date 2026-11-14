package com.skydispatch.repository;

import com.skydispatch.domain.entity.ChargingStation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 充电站仓储。站点是低频变更的台账数据，所以只需要最简单的查询。 */
public interface ChargingStationRepository extends JpaRepository<ChargingStation, Long> {

    /** 全部启用的站点：自主补能选点只看启用中的站。 */
    List<ChargingStation> findAllByEnabledTrue();

    /** 是否存在同编码站点（演示数据重复灌入时做幂等判断）。 */
    boolean existsByCode(String code);
}
