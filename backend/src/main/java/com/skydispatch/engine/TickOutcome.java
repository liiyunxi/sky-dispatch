package com.skydispatch.engine;

import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.dto.DashboardDtos;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一拍仿真的执行结果。
 *
 * <p>为什么要把结果"打包返回"而不是在 tick 里直接推送 WebSocket？
 * 因为<b>推送必须发生在数据库事务提交之后</b>。
 * 如果 tick 内部一边改数据一边推送，事务一旦回滚，
 * 前端看到的就是一批从未存在过的无人机位置。
 *
 * <p>所以职责这样切分：
 * <ul>
 *   <li>{@link SimulationTickService}：只负责"算 + 写"，事务边界清晰；</li>
 *   <li>{@link SimulationEngine}：负责"推送 + 调度"，在事务外做副作用的对外广播。</li>
 * </ul>
 *
 * @param simTimeS       推进后的仿真时间（秒）
 * @param stepIndex      第几拍
 * @param frames         本拍所有无人机的遥测帧（广播用）
 * @param movedDrones    本拍发生位移的无人机数
 * @param completedTasks 本拍完成的任务数
 * @param newConflicts   本拍新发现的冲突（已落库，待广播）
 */
public record TickOutcome(
        BigDecimal simTimeS,
        long stepIndex,
        List<DashboardDtos.DroneFrame> frames,
        int movedDrones,
        int completedTasks,
        List<ConflictEvent> newConflicts
) {
}
