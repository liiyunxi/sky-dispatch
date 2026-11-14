package com.skydispatch.repository;

import com.skydispatch.domain.entity.FlightRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 航路仓储：服务于「路径规划结果管理 / 任务执行」模块。
 *
 * <p>一个订单可能被重派多次、产生多条航路，因此业务上最常用的是
 * findFirstByOrderIdOrderByIdDesc：取"最新一次规划结果"。
 */
@Repository
public interface FlightRouteRepository extends JpaRepository<FlightRoute, Long> {

    /** 查询某订单的全部航路（含历史重派记录）。 */
    List<FlightRoute> findByOrderId(Long orderId);

    /** 取某订单最新的一条航路（id 最大即最后写入的那次规划）。 */
    Optional<FlightRoute> findFirstByOrderIdOrderByIdDesc(Long orderId);

    /** 查询某架无人机执行过的航路。 */
    List<FlightRoute> findByDroneId(Long droneId);
}
