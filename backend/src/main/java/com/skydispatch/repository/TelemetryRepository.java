package com.skydispatch.repository;

import com.skydispatch.domain.entity.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 遥测仓储：服务于「实时监控 / 轨迹回放 / 仿真遥测推送」模块。
 *
 * <p>这张表数据量最大，因此只提供两条必要查询，且都带 limit 语义：
 * 回放按 taskId 顺序取全量，实时看板只取最近 200 条，避免一次拉爆内存。
 */
@Repository
public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    /** 按任务取轨迹，按主键升序（追加型时序表里 id 顺序等价于写入顺序）。 */
    List<Telemetry> findByTaskIdOrderByIdAsc(Long taskId);

    /** 取某次仿真运行最新的 200 条遥测，供前端实时刷新。 */
    List<Telemetry> findTop200ByRunIdOrderByIdDesc(Long runId);
}
