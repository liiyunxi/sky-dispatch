package com.skydispatch.repository;

import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.Priority;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 配送订单仓储：服务于「订单管理 / 派单队列」模块。
 *
 * <p>返回 Page 的两个方法用于管理后台分页列表；返回 List 的方法用于批量取待派订单，
 * 让服务层在内存里按优先级权重排序，避免把排序逻辑写进 SQL。
 */
@Repository
public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, Long> {

    /** 按业务单号查询（唯一约束），也是接口幂等校验的入口。 */
    Optional<DeliveryOrder> findByOrderNo(String orderNo);

    /**
     * 派单/取消时对订单行加写锁。
     *
     * <p>没有这把锁时，"手动派单"和"引擎自动扫单"可能同时读到 CREATED 状态，
     * 各自给同一订单建一条任务并占用无人机，造成重复派单。
     * 加锁后同一订单的并发派单会串行化，第二个事务会看到已变更的状态并失败。
     *
     * <p>必须在事务内调用，锁才生效。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DeliveryOrder o where o.id = :id")
    Optional<DeliveryOrder> findByIdForUpdate(@Param("id") Long id);

    /** 按状态分页查询。 */
    Page<DeliveryOrder> findByStatus(OrderStatus status, Pageable pageable);

    /** 按状态 + 优先级分页查询，用于"看某个优先级的积压订单"。 */
    Page<DeliveryOrder> findByStatusAndPriority(OrderStatus status, Priority priority, Pageable pageable);

    /** 按状态集合查询，如一次取出所有待处理订单。 */
    List<DeliveryOrder> findByStatusIn(Collection<OrderStatus> statuses);

    /** 按状态集合分页查询：订单页的「流水线阶段」一次筛选多个状态。 */
    Page<DeliveryOrder> findByStatusIn(Collection<OrderStatus> statuses, Pageable pageable);

    /** 按状态集合 + 优先级分页查询。 */
    Page<DeliveryOrder> findByStatusInAndPriority(Collection<OrderStatus> statuses, Priority priority,
                                                  Pageable pageable);

    /** 按状态集合查询，按 id 倒序；地图只画未完结订单，避免把历史订单全量捞出来。 */
    List<DeliveryOrder> findByStatusInOrderByIdDesc(Collection<OrderStatus> statuses);

    /** 最近 N 条已送达订单（地图保留少量绿色终点标记）。 */
    List<DeliveryOrder> findTop6ByStatusOrderByIdDesc(OrderStatus status);

    /** 按状态集合统计数量（演示补单器用它统计未完结订单数）。 */
    long countByStatusIn(Collection<OrderStatus> statuses);

    /** 统计某状态订单数，看板 KPI 用。 */
    long countByStatus(OrderStatus status);

    /**
     * 按状态分组计数：一次查询拿到全部状态的订单数。
     *
     * <p>看板 KPI 每秒刷新一次，原先按状态发 6 条 count 查询，
     * 订单量增长后会变成 6 次全索引扫描；这里合并为一次 GROUP BY。
     */
    @Query("select o.status, count(o) from DeliveryOrder o group by o.status")
    List<Object[]> countGroupByStatus();
}
