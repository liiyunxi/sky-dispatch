package com.skydispatch.domain.enums;

/**
 * 派单策略类型。
 *
 * <p>把策略名做成枚举（而不是自由字符串），可以保证落库的 strategy 字段干净可控；
 * 具体算法实现由服务层按该类型查表选择，方便对比不同策略的效果。
 */
public enum DispatchStrategyType {

    /** 就近可用：选直线距离最近的空闲机。 */
    NEAREST_AVAILABLE,

    /** 电量优先：选剩余电量最充裕的机。 */
    BATTERY_FIRST,

    /** 负载均衡：选近期任务量最少的机。 */
    LOAD_BALANCE
}
