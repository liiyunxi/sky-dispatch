package com.skydispatch.repository;

import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.enums.ConflictStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 冲突事件仓储：服务于「冲突检测与告警 / 看板风险指标」模块。
 *
 * <p>查询一律带时间倒序：冲突是"越新越要紧"的数据，看板与告警都只关心最近的。
 */
@Repository
public interface ConflictEventRepository extends JpaRepository<ConflictEvent, Long> {

    /** 按处置状态查询并按检测时间倒序，如列出所有待处置冲突。 */
    List<ConflictEvent> findByStatusOrderByDetectedAtDesc(ConflictStatus status);

    /**
     * 待处置冲突只取最近 200 条。
     *
     * <p>冲突是持续累积的数据，未处理列表如果无限返回，长时间运行后
     * 一次看板刷新就会把上万条记录读进内存。封顶后响应大小可控。
     */
    List<ConflictEvent> findTop200ByStatusOrderByDetectedAtDesc(ConflictStatus status);

    /** 首帧快照只带最近 10 条未处理冲突。 */
    List<ConflictEvent> findTop10ByStatusOrderByDetectedAtDesc(ConflictStatus status);

    /** 统计某状态冲突数，看板 KPI 的"未处置冲突数"用它。 */
    long countByStatus(ConflictStatus status);

    /** 取最近 50 条冲突（不限状态），用于风险面板首屏。 */
    List<ConflictEvent> findTop50ByOrderByDetectedAtDesc();

    /** 按状态集合查询，便于一次取多个状态（如 OPEN + IGNORED）。 */
    List<ConflictEvent> findByStatusIn(Collection<ConflictStatus> statuses);
}
