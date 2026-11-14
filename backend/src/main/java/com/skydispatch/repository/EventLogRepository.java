package com.skydispatch.repository;

import com.skydispatch.domain.entity.EventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 事件日志仓储：服务于「运行时间线 / 回放 / 审计」模块。
 *
 * <p>同时提供分页与 TopN 两类查询：分页给"按任务翻日志"的详情页，
 * TopN 给"最近发生了什么"的实时侧栏。
 */
@Repository
public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    /** 按任务取完整时间线，按事件时间升序（回放要正序）。 */
    List<EventLog> findByTaskIdOrderByTsAsc(Long taskId);

    /** 全量日志按时间倒序分页，供全局日志列表页使用。 */
    Page<EventLog> findAllByOrderByTsDesc(Pageable pageable);

    /** 某任务日志按默认顺序分页（排序由 Pageable 决定）。 */
    Page<EventLog> findByTaskId(Long taskId, Pageable pageable);

    /** 取最近 200 条日志，实时侧栏用。 */
    List<EventLog> findTop200ByOrderByTsDesc();
}
