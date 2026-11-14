package com.skydispatch.domain.enums;

/**
 * 冲突事件处置状态。
 *
 * <p>区分"已解决"和"已忽略"很重要：前者是系统自动消解成功，后者是人工判定无需处理，
 * 复盘时两者的责任归属完全不同。
 */
public enum ConflictStatus {

    /** 待处置。 */
    OPEN,

    /** 已消解。 */
    RESOLVED,

    /** 人工忽略。 */
    IGNORED
}
