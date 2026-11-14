package com.skydispatch.repository;

import com.skydispatch.domain.entity.DroneModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 无人机型号仓储：服务于「机队型号字典 / 机型能力查询」模块。
 *
 * <p>型号是相对静态的字典数据，服务层通常先按 code 查、再按 enabled 过滤出可选机型。
 */
@Repository
public interface DroneModelRepository extends JpaRepository<DroneModel, Long> {

    /** 按型号编码精确查询（code 在库里有唯一约束，所以最多一条）。 */
    Optional<DroneModel> findByCode(String code);

    /** 查出所有启用的型号，供派单时筛选候选机型。 */
    List<DroneModel> findByEnabledTrue();
}
