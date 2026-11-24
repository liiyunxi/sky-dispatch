package com.skydispatch.repository;

import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.enums.TaskStatus;
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
 * 配送任务仓储：服务于「任务执行 / 仿真推进 / 派单调度」模块。
 *
 * <p>核心用法有两类：
 * 一是按状态（集合）找任务，驱动仿真状态机推进；
 * 二是按 droneId + 活动状态集合判断"某架机是否正忙"，这是派单前必做的前置校验。
 */
@Repository
public interface DispatchTaskRepository extends JpaRepository<DispatchTask, Long> {

    /** 按任务号查询（唯一约束）。 */
    Optional<DispatchTask> findByTaskNo(String taskNo);

    /**
     * 中止任务时锁住任务行。
     *
     * <p>否则可能出现"人工中止"和"仿真推进"同时改同一条任务：
     * 仿真把它改回 CRUISING、人工把它改成 ABORTED，最后谁后提交谁生效。
     * 行锁 + 实体 @Version 可以让冲突方直接失败，而不是静默覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DispatchTask t where t.id = :id")
    Optional<DispatchTask> findByIdForUpdate(@Param("id") Long id);

    /** 按状态分页查询，供任务列表页使用。 */
    Page<DispatchTask> findByStatus(TaskStatus status, Pageable pageable);

    /** 按状态集合查询，如一次取出全部活动中的任务。 */
    List<DispatchTask> findByStatusIn(Collection<TaskStatus> statuses);

    /** 按状态集合分页查询：任务指挥台的「飞行阶段」一次筛选多个状态。 */
    Page<DispatchTask> findByStatusIn(Collection<TaskStatus> statuses, Pageable pageable);

    /** 按无人机 + 状态分页查询（两个筛选条件同时生效）。 */
    Page<DispatchTask> findByDroneIdAndStatus(Long droneId, TaskStatus status, Pageable pageable);

    /** 按无人机 + 状态集合分页查询。 */
    Page<DispatchTask> findByDroneIdAndStatusIn(Long droneId, Collection<TaskStatus> statuses, Pageable pageable);

    /**
     * 按状态分组计数：一次查询拿到任务指挥台全部阶段的读数。
     *
     * <p>任务量随时间线性增长，逐状态发 count 会让首屏随历史数据变慢；
     * GROUP BY 只扫一次索引。
     */
    @Query("select t.status, count(t) from DispatchTask t group by t.status")
    List<Object[]> countGroupByStatus();

    /** 查某架无人机在指定状态集合下的任务，用于判断它是否还有未结束的活儿。 */
    List<DispatchTask> findByDroneIdAndStatusIn(Long droneId, Collection<TaskStatus> statuses);

    /** 统计某状态任务数，看板 KPI 用。 */
    long countByStatus(TaskStatus status);

    /**
     * 已完成任务的平均耗时。
     *
     * <p>原来把全部已完成任务读进内存再求平均，任务量增长后 KPI 刷新会越来越慢；
     * 平均值交给数据库聚合。
     */
    @Query("select avg(t.durationS) from DispatchTask t where t.status = :status and t.durationS is not null")
    Double averageDurationByStatus(@Param("status") TaskStatus status);

    /** 有对应订单的已完成任务数（准时率分母）。 */
    @Query("""
            select count(t) from DispatchTask t
            where t.status = :status
              and exists (select 1 from DeliveryOrder o where o.id = t.orderId)
            """)
    long countByStatusWithOrder(@Param("status") TaskStatus status);

    /**
     * 准时完成的任务数（准时率分子）：订单未设期望时间，或实际完成时间不晚于期望时间。
     *
     * <p>用一条聚合 SQL 代替"加载全部完成任务 + 全部订单再内存匹配"。
     */
    @Query("""
            select count(t) from DispatchTask t
            where t.status = :status
              and exists (select 1 from DeliveryOrder o
                          where o.id = t.orderId
                            and (o.expectDeliveryAt is null or t.finishedAt <= o.expectDeliveryAt))
            """)
    long countOnTimeByStatus(@Param("status") TaskStatus status);

    /** 统计某机在指定状态集合下的任务数，用于"负载均衡"策略比较各机忙碌程度。 */
    long countByDroneIdAndStatusIn(Long droneId, Collection<TaskStatus> statuses);

    /** 查某架无人机的任务（不限状态），用于"这架飞机都干过什么"。 */
    Page<DispatchTask> findByDroneId(Long droneId, Pageable pageable);

    /** 取某订单最新的一条任务，重派场景下就是"当前有效的那次执行"。 */
    Optional<DispatchTask> findFirstByOrderIdOrderByIdDesc(Long orderId);

    /** 某订单的全部执行任务（重派会有多条），按 id 倒序。 */
    List<DispatchTask> findByOrderIdOrderByIdDesc(Long orderId);
}
