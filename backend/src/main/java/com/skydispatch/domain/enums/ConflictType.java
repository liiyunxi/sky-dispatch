package com.skydispatch.domain.enums;

/**
 * 冲突类型。
 *
 * <p>把"风险"统一抽象成冲突事件，好处是检测、告警、处置流程可以共用一张表和一套接口；
 * 类型字段决定读哪些关联 id（机-机读 droneA/droneB，机-区读 zoneId）。
 */
public enum ConflictType {

    /** 无人机与无人机之间的间隔冲突。 */
    DRONE_DRONE,

    /** 无人机闯入受限空域。 */
    DRONE_ZONE,

    /** 存在超时（无法按时送达）风险。 */
    DEADLINE_RISK,

    /** 存在电量不足风险。 */
    BATTERY_RISK
}
