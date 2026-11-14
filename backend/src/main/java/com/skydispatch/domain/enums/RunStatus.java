package com.skydispatch.domain.enums;

/**
 * 仿真运行状态。
 *
 * <p>一次 simulation_run 就是一次"沙盘推演"。显式区分 PAUSED 与 STOPPED，
 * 是因为暂停可以继续、停止只能结束，恢复逻辑不同。
 */
public enum RunStatus {

    /** 已创建，尚未开始。 */
    CREATED,

    /** 正在推进。 */
    RUNNING,

    /** 已暂停，可恢复。 */
    PAUSED,

    /** 已停止，不再恢复。 */
    STOPPED,

    /** 正常推演结束。 */
    FINISHED
}
