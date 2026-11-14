package com.skydispatch.domain.enums;

/**
 * 航路状态。
 *
 * <p>一次路径规划产生一条航路，航路先 PLANNED，任务起飞后转 ACTIVE，
 * 结束后按结果转 FINISHED/FAILED；便于统计"规划了多少条、实际用了多少条"。
 */
public enum RouteStatus {

    /** 规划完成，尚未执行。 */
    PLANNED,

    /** 正在被任务使用。 */
    ACTIVE,

    /** 已正常走完。 */
    FINISHED,

    /** 航路执行失败。 */
    FAILED
}
