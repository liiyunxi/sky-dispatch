package com.skydispatch.domain.enums;

/**
 * 无人机状态。
 *
 * <p>为什么单独定义状态机而不是用布尔量：无人机同时要表达"能不能接单""在忙什么"，
 * 用状态枚举可以和服务层调度策略一一对应，也便于数据库加 CHECK 约束防脏数据。
 */
public enum DroneStatus {

    /** 空闲，可被派单。 */
    IDLE,

    /** 已被派单，但尚未起飞。 */
    ASSIGNED,

    /** 执行配送中（飞行）。 */
    IN_FLIGHT,

    /** 任务结束返航中。 */
    RETURNING,

    /** 充电中，暂时不可调度。 */
    CHARGING,

    /** 检修中，人工置为不可用。 */
    MAINTENANCE,

    /** 离线/失联，心跳超时会被置为该状态。 */
    OFFLINE;

    /**
     * 是否可被调度。
     *
     * <p>只有 IDLE 才算真正空闲：其余状态要么在执行任务，要么在补能/维修，
     * 派单算法用这个方法快速过滤候选机，避免在 SQL 里写一长串 IN (...) 条件。
     */
    public boolean isAvailable() {
        return this == IDLE;
    }
}
