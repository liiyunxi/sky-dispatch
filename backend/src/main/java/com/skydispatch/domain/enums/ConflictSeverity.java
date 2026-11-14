package com.skydispatch.domain.enums;

/**
 * 冲突/空域严重级别。
 *
 * <p>同时用于冲突事件和空域定义：告警分级后，前端可以按级别用不同颜色展示，
 * 处置策略也可以按级别决定"自动避让"还是"仅提示"。
 */
public enum ConflictSeverity {

    /** 轻微，仅记录。 */
    LOW,

    /** 中等，需要关注。 */
    MEDIUM,

    /** 严重，需要立即处置。 */
    HIGH,

    /** 极严重，必须立即干预（如即将侵入禁飞区）。 */
    CRITICAL
}
