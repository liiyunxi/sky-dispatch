package com.skydispatch.repository;

import com.skydispatch.domain.entity.FlightWaypoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 航路点仓储：服务于「路径规划结果管理 / 仿真飞行推进」模块。
 *
 * <p>航路点是"父表删除后必须一起清掉"的明细数据（SQL 里是 ON DELETE CASCADE），
 * 这里提供 deleteByRouteId 让服务层可以显式先删明细再删航路头，
 * 避免只依赖数据库级联而出现 Hibernate 一级缓存与库内数据不一致。
 */
@Repository
public interface FlightWaypointRepository extends JpaRepository<FlightWaypoint, Long> {

    /** 按航路查询航路点，按 seq 升序返回——飞行顺序必须靠 seq，不能靠主键。 */
    List<FlightWaypoint> findByRouteIdOrderBySeqAsc(Long routeId);

    /**
     * 一次取多条航路的航路点（按航路、seq 排序）。
     *
     * <p>仿真一拍要推进所有在飞任务，逐条航路查明细会形成 N+1；
     * 批量取回后按 routeId 分组即可，查询次数固定为 1。
     */
    List<FlightWaypoint> findByRouteIdInOrderByRouteIdAscSeqAsc(Collection<Long> routeIds);

    /** 统计航路点个数，用于校验规划结果与 flight_route.waypoint_count 是否一致。 */
    long countByRouteId(Long routeId);

    /**
     * 删除某航路的全部航路点。
     *
     * <p>派生删除方法会先 select 再逐条 delete，必须在事务中执行，
     * 所以这里显式加 {@code @Transactional}，调用方（Service）也建议自己再确保事务边界。
     */
    @Transactional
    void deleteByRouteId(Long routeId);

    /**
     * 批量删除某航路的全部航路点（单条 DELETE 立即执行）。
     *
     * <p>改航时必须用它而不是派生删除：Hibernate 默认会把新 insert 排在 delete 之前 flush，
     * "先删后插"在同一事务里会立刻撞上 (route_id, seq) 唯一索引，导致改航整体回滚。
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from FlightWaypoint w where w.routeId = :routeId")
    @Transactional
    void deleteBulkByRouteId(Long routeId);
}
