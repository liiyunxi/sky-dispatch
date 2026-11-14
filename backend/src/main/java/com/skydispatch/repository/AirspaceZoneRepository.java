package com.skydispatch.repository;

import com.skydispatch.domain.entity.AirspaceZone;
import com.skydispatch.domain.enums.ZoneType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 空域仓储：服务于「空域管理 / 路径规划避障」模块。
 *
 * <p>规划器最常用的入口是 findByEnabledTrue()：一次取出全部生效空域在内存里做几何判定，
 * 比逐点查库快得多（空域数量通常只有几十到几百条）。
 */
@Repository
public interface AirspaceZoneRepository extends JpaRepository<AirspaceZone, Long> {

    /** 取出所有启用空域，供规划与冲突检测加载。 */
    List<AirspaceZone> findByEnabledTrue();

    /** 按空域编码查询（唯一约束）。 */
    Optional<AirspaceZone> findByCode(String code);

    /** 按类型查询启用空域，如只取禁飞区做强制绕行。 */
    List<AirspaceZone> findByEnabledTrueAndZoneType(ZoneType zoneType);
}
