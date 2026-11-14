package com.skydispatch.repository;

import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 仿真运行仓储：服务于「仿真引擎 / 回放与实验管理」模块。
 *
 * <p>引擎启动时要回答"当前有没有正在跑的运行"，这就是 findFirstByStatusOrderByIdDesc 的用途：
 * 取最新一条处于指定状态的运行，避免维护额外的全局状态变量。
 */
@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    /** 取最新一条处于指定状态的运行（按 id 倒序，即最后创建的那次）。 */
    Optional<SimulationRun> findFirstByStatusOrderByIdDesc(RunStatus status);

    /** 按状态集合查询，如同时看 RUNNING 与 PAUSED 的运行。 */
    List<SimulationRun> findByStatusIn(Collection<RunStatus> statuses);
}
