package com.skydispatch.repository;

import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.enums.DroneStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 无人机仓储：服务于「派单调度 / 机队状态监控」模块。
 *
 * <p>这里的派生查询都围绕 status 与 batteryPct 两个高频筛选维度：
 * 派单时先按状态圈定候选集，再按电量排序挑机。
 */
@Repository
public interface DroneRepository extends JpaRepository<Drone, Long> {

    /** 按机身编号查询（唯一约束，最多一条）。 */
    Optional<Drone> findByCode(String code);

    /** 按状态查询，如列出所有空闲机。 */
    List<Drone> findByStatus(DroneStatus status);

    /** 按状态集合查询，如"在途相关状态"一次查完（ASSIGNED/IN_FLIGHT/RETURNING）。 */
    List<Drone> findByStatusIn(Collection<DroneStatus> statuses);

    /** 统计某状态的无人机数量，看板 KPI 用。 */
    long countByStatus(DroneStatus status);

    /** 一次统计多个状态的数量，替代多次 countByStatus。 */
    long countByStatusIn(Collection<DroneStatus> statuses);

    /** 机队平均电量：数据库聚合，避免为了一个平均值把整张机队表读进内存。 */
    @Query("select avg(d.batteryPct) from Drone d where d.batteryPct is not null")
    Double averageBatteryPct();

    /**
     * 查出需要补能的在站无人机。
     *
     * <p>仿真每拍都会补齐电量，原先用 findAll() 把整张机队表读进内存再判断；
     * 只查"在站且电量未满"的飞机，机队规模变大后也不会随拍数放大开销。
     */
    @Query("""
            select d from Drone d
            where d.status in :statuses
              and (d.batteryPct is null or d.batteryPct < :target)
            """)
    List<Drone> findRechargeable(@Param("statuses") Collection<DroneStatus> statuses,
                                 @Param("target") BigDecimal target);

    /** 按状态查出并按电量从高到低排序，对应"电量优先"策略的候选顺序。 */
    List<Drone> findByStatusOrderByBatteryPctDesc(DroneStatus status);

    /**
     * 对选中的无人机加写锁并抓取机型。
     *
     * <p>派单在路径规划完成后必须重新锁行复核状态：候选筛选阶段读到的 IDLE
     * 可能已经被另一个并发派单占用，只有行锁能保证"检查 + 占用"是原子的。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drone d join fetch d.model where d.id = :id")
    Optional<Drone> findByIdForUpdate(@Param("id") Long id);

    /**
     * 查出全部无人机，并<b>一次性抓取型号</b>（{@code model} 是 LAZY 关联）。
     *
     * <p>为什么需要它：派单时要读机型的载重、速度、能耗参数，
     * 如果用普通 {@code findAll()}，一旦这段代码跑在事务之外就会抛
     * {@code LazyInitializationException: no session}。
     * 这里用 JPQL 的 {@code join fetch} 把型号一起捞出来，从根上消除这个隐患
     * （顺带解决 N+1：8 架飞机不再发 1+8 条 SQL）。
     *
     * <p>注意：<b>派生查询不支持这种写法</b>。方法名如果只写 {@code findAllWithModel}，
     * Spring Data 会把它当成"按 find 解析属性 AllWithModel"，启动时直接报
     * {@code No property 'findAllWithModel' found for type 'Drone'}。
     * 所以这里必须显式给出 {@code @Query}。
     */
    @Query("select d from Drone d join fetch d.model")
    List<Drone> findAllWithModel();

    /**
     * 批量按 id 取无人机并抓取机型。
     *
     * <p>仿真一拍要推进所有活动任务，原先在循环里逐架 {@code findById} + 懒加载机型，
     * 200 架飞机就是 400 条 SQL。批量预加载后固定为 1 条。
     */
    @Query("select d from Drone d join fetch d.model where d.id in :ids")
    List<Drone> findAllWithModelByIdIn(@Param("ids") Collection<Long> ids);
}
