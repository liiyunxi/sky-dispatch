package com.skydispatch.domain.enums;

/**
 * 配送任务状态（订单 × 无人机 × 航路的执行实例）。
 *
 * <p>比订单状态更细：订单只关心"送没送到"，任务要关心"到哪个飞行阶段了"，
 * 仿真引擎按阶段推进航路点，因此需要 TAKEOFF/CRUISING/DELIVERING/RETURNING 这种粒度。
 */
public enum TaskStatus {

    /** 已生成任务，尚未分配执行者。 */
    PENDING,

    /** 已分配无人机。 */
    ASSIGNED,

    /** 起飞阶段。 */
    TAKEOFF,

    /** 航线巡航中。 */
    CRUISING,

    /** 到达投放点、正在投放。 */
    DELIVERING,

    /** 返航中。 */
    RETURNING,

    /**
     * 在充电站补能中。
     *
     * <p>长航程任务里无人机可能"电量不足以全程直飞"，它会自主改航到就近的充电站、
     * 落到桩上补能，充满再继续未完成的那一段。这一状态就是"停在桩上充电"的那段时间：
     * 飞机不动，电量按充电速率回升。</p>
     */
    CHARGING,

    /** 正常完成。 */
    COMPLETED,

    /** 人工/策略中止。 */
    ABORTED,

    /** 异常失败。 */
    FAILED;

    /**
     * 是否为终态：任务已经结束，不会再有状态迁移。
     *
     * <p>仿真 tick 用它做短路判断（终态任务不再推进）；统计完成率时也用它区分"在途"与"已结束"。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED || this == FAILED;
    }

    /**
     * 是否处于"占用无人机"的活动阶段：此时该机不能再被派新单。
     *
     * <p>注意 PENDING（还没分到机）和终态都不算活动状态，判断"某机是否忙"时应使用本方法。
     */
    public boolean isActive() {
        return this == ASSIGNED
                || this == TAKEOFF
                || this == CRUISING
                || this == DELIVERING
                || this == RETURNING
                || this == CHARGING;
    }
}
