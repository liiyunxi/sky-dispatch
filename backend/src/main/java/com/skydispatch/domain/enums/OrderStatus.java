package com.skydispatch.domain.enums;

/**
 * 配送订单状态。
 *
 * <p>覆盖订单从下单到签收的完整生命周期，前 4 个是"还没飞"的阶段，
 * 便于看板把 CREATED/PLANNING/QUEUED 归为"待处理订单"。
 */
public enum OrderStatus {

    /** 刚创建，等待进入规划。 */
    CREATED,

    /** 正在做路径规划。 */
    PLANNING,

    /** 规划完成，排队等待派单。 */
    QUEUED,

    /** 已分配无人机（生成了任务）。 */
    ASSIGNED,

    /** 配送飞行中。 */
    IN_FLIGHT,

    /** 已送达。 */
    DELIVERED,

    /** 配送失败（超时、电量不足、被取消执行等）。 */
    FAILED,

    /** 人工取消。 */
    CANCELLED
}
